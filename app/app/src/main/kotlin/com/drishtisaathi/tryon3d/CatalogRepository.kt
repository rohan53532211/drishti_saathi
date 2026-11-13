package com.drishtisaathi.tryon3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.drishtisaathi.bridge.BridgeConfig
import com.drishtisaathi.engine.tryon3d.Catalog3D
import com.drishtisaathi.engine.tryon3d.Frame3D
import com.drishtisaathi.engine.tryon3d.LruIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The 3D frame catalogue: 30 frames bundled in the APK (assets/frames3d, offline demos) plus, when a shop server is
 * set, the full catalogue from GET /catalog. Server GLBs and thumbnails download on demand into an LRU disk cache
 * (about 100 MB); the last few GLBs also stay in memory so switching frames takes well under a second.
 */
class CatalogRepository(private val context: Context, maxCacheBytes: Long = 100L * 1024 * 1024) {
    private val dir = File(context.cacheDir, "frames3d").apply { mkdirs() }
    private val index = LruIndex(maxCacheBytes)
    private val memory = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private val thumbs = LruCache<String, Bitmap>(80)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        // rebuild the LRU order from file times (oldest first)
        dir.listFiles()?.sortedBy { it.lastModified() }?.forEach { index.put(it.name, it.length()) }
    }

    val cacheBytes: Long get() = index.bytes

    /** Bundled catalogue (always available). */
    val bundled: List<Frame3D> by lazy {
        runCatching {
            Catalog3D.parse(context.assets.open("$ASSET_DIR/frames_catalog.json").bufferedReader().use { it.readText() }).frames
        }.onFailure { Log.w(TAG, "no bundled 3D catalogue", it) }.getOrDefault(emptyList())
    }

    /** Bundled + server frames (server ones not already bundled). Network errors just give the bundled list. */
    suspend fun all(bridge: BridgeConfig): Pair<List<Frame3D>, Boolean> = withContext(Dispatchers.IO) {
        if (!bridge.isValid) return@withContext bundled to false
        val remote = runCatching { fetchRemote(bridge) }.onFailure { Log.i(TAG, "catalogue server not reachable: ${it.message}") }.getOrNull()
        if (remote == null) bundled to false
        else {
            val ids = bundled.map { it.id }.toSet()
            (bundled + remote.filter { it.id !in ids }) to true
        }
    }

    private fun fetchRemote(bridge: BridgeConfig): List<Frame3D> {
        val out = mutableListOf<Frame3D>()
        var page = 1
        while (page <= 20) {
            val obj = json.parseToJsonElement(get(bridge, "/catalog?page=$page&page_size=100").decodeToString()).jsonObject
            val frames = obj["frames"]!!.jsonArray
            frames.forEach { out += json.decodeFromJsonElement(Frame3D.serializer(), it) }
            val total = obj["total"]?.jsonPrimitive?.intOrNull ?: out.size
            if (out.size >= total || frames.isEmpty()) break
            page++
        }
        return out
    }

    /** GLB bytes: memory → bundled asset → disk cache → server. Throws when it cannot be had. */
    suspend fun glb(frame: Frame3D, bridge: BridgeConfig): ByteArray = withContext(Dispatchers.IO) {
        memory.get(frame.id)?.let { return@withContext it }
        val bytes = frame.glb?.let { rel -> runCatching { context.assets.open("$ASSET_DIR/$rel").use { it.readBytes() } }.getOrNull() }
            ?: cached("${frame.id}.glb") { get(bridge, frame.glbUrl ?: error("no glb for ${frame.id}")) }
        require(bytes.size > 12 && bytes[0] == 'g'.code.toByte() && bytes[1] == 'l'.code.toByte()) { "not a GLB" }
        memory.put(frame.id, bytes)
        bytes
    }

    suspend fun thumb(frame: Frame3D, bridge: BridgeConfig): Bitmap? = withContext(Dispatchers.IO) {
        thumbs.get(frame.id)?.let { return@withContext it }
        val bytes = frame.thumb?.let { rel -> runCatching { context.assets.open("$ASSET_DIR/$rel").use { it.readBytes() } }.getOrNull() }
            ?: frame.thumbUrl?.let { url -> runCatching { cached("${frame.id}.jpg") { get(bridge, url) } }.getOrNull() }
            ?: return@withContext null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { thumbs.put(frame.id, it) }
    }

    @Synchronized
    private fun cached(name: String, fetch: () -> ByteArray): ByteArray {
        val f = File(dir, name)
        if (f.isFile) {
            f.setLastModified(System.currentTimeMillis())
            index.touch(name)
            return f.readBytes()
        }
        val bytes = fetch()
        val tmp = File(dir, "$name.part")
        tmp.writeBytes(bytes)
        tmp.renameTo(f)
        index.put(name, bytes.size.toLong()).forEach { File(dir, it).delete() }
        return bytes
    }

    private fun get(bridge: BridgeConfig, path: String): ByteArray {
        val conn = URL(bridge.baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            if (bridge.token.isNotBlank()) conn.setRequestProperty("X-Bridge-Token", bridge.token)
            val code = conn.responseCode
            check(code in 200..299) { "HTTP $code for $path" }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val ASSET_DIR = "frames3d"
        private const val TAG = "Catalog3D"
    }
}
