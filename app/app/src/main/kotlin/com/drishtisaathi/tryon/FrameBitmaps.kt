package com.drishtisaathi.tryon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes try-on PNGs from assets once and keeps them in a memory-bounded LRU cache
 * (full size for the overlay, a 1/4-size copy for picker thumbnails).
 */
object FrameBitmaps {
    private const val CACHE_BYTES = 24 * 1024 * 1024

    private val cache = object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(path: String, sampleSize: Int = 1): ImageBitmap? = cache.get(key(path, sampleSize))

    /** Decodes [path] from assets off the main thread; null if the asset is missing or undecodable. */
    suspend fun load(context: Context, path: String, sampleSize: Int = 1): ImageBitmap? {
        cached(path, sampleSize)?.let { return it }
        val img = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(path).use { s ->
                    BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inSampleSize = sampleSize
                    })
                }?.asImageBitmap()
            }.getOrNull()
        } ?: return null
        cache.put(key(path, sampleSize), img)
        return img
    }

    private fun key(path: String, sampleSize: Int) = "$path@$sampleSize"
}

/** The decoded asset at [path] (null while loading or when missing). */
@Composable
fun rememberFrameBitmap(path: String?, sampleSize: Int = 1): State<ImageBitmap?> {
    val context = LocalContext.current.applicationContext
    return produceState(initialValue = path?.let { FrameBitmaps.cached(it, sampleSize) }, path, sampleSize) {
        value = path?.let { FrameBitmaps.load(context, it, sampleSize) }
    }
}
