package com.drishtisaathi.engine.tryon3d

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal glTF 2.0 binary (GLB) writer: triangle meshes with positions, normals, UVs, one PBR material each
 * (colour factor and optional embedded JPEG/PNG texture). Used for the on-phone face avatar so the renderer's
 * standard glTF loader (Filament gltfio) can draw it; no precompiled materials are needed.
 */
class GlbWriter {
    class Material(
        val name: String,
        val baseColor: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
        val image: Int? = null,
        val blend: Boolean = false,
        val doubleSided: Boolean = false,
        val roughness: Float = 0.8f,
        val metallic: Float = 0f,
    )

    private val bin = ByteArrayOutputStream()
    private val views = mutableListOf<JsonObject>()
    private val accessors = mutableListOf<JsonObject>()
    private val images = mutableListOf<JsonObject>()
    private val materials = mutableListOf<JsonObject>()
    private val meshes = mutableListOf<JsonObject>()
    private val nodes = mutableListOf<JsonObject>()

    private fun view(bytes: ByteArray, target: Int?): Int {
        while (bin.size() % 4 != 0) bin.write(0)
        val off = bin.size()
        bin.write(bytes)
        views += buildJsonObject {
            put("buffer", 0); put("byteOffset", off); put("byteLength", bytes.size)
            if (target != null) put("target", target)
        }
        return views.size - 1
    }

    private fun floats(a: FloatArray): ByteArray =
        ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> a.forEach { b.putFloat(it) } }.array()

    private fun accessor(data: FloatArray, comps: Int, type: String, minMax: Boolean): Int {
        val v = view(floats(data), 34962)
        accessors += buildJsonObject {
            put("bufferView", v); put("componentType", 5126); put("count", data.size / comps); put("type", type)
            if (minMax) {
                val mn = FloatArray(comps) { Float.MAX_VALUE }
                val mx = FloatArray(comps) { -Float.MAX_VALUE }
                for (i in data.indices) { val c = i % comps; mn[c] = minOf(mn[c], data[i]); mx[c] = maxOf(mx[c], data[i]) }
                put("min", JsonArray(mn.map { JsonPrimitive(it) })); put("max", JsonArray(mx.map { JsonPrimitive(it) }))
            }
        }
        return accessors.size - 1
    }

    /**
     * Adds an image (and its texture). [srgb] marks a colour texture: glTF reads baseColor textures as sRGB-encoded
     * (Filament loads them as SRGB8_A8); the flag is also written into the texture's extras for checking.
     */
    fun addImage(bytes: ByteArray, mimeType: String = "image/jpeg", srgb: Boolean = false): Int {
        val v = view(bytes, null)
        images += buildJsonObject { put("bufferView", v); put("mimeType", mimeType) }
        srgbImages += srgb
        return images.size - 1
    }

    private val srgbImages = mutableListOf<Boolean>()

    fun addMaterial(m: Material): Int {
        materials += buildJsonObject {
            put("name", m.name)
            put("pbrMetallicRoughness", buildJsonObject {
                put("baseColorFactor", JsonArray(m.baseColor.map { JsonPrimitive(it) }))
                put("metallicFactor", m.metallic); put("roughnessFactor", m.roughness)
                if (m.image != null) put("baseColorTexture", buildJsonObject { put("index", m.image) })
            })
            if (m.blend) put("alphaMode", "BLEND")
            if (m.doubleSided) put("doubleSided", true)
        }
        return materials.size - 1
    }

    /** Adds a mesh and a node for it; returns the node index. [indices] are triangles (CCW = front). */
    fun addMesh(name: String, positions: FloatArray, indices: IntArray, material: Int,
                normals: FloatArray? = null, uvs: FloatArray? = null, matrix: FloatArray? = null): Int {
        require(positions.size % 3 == 0 && indices.size % 3 == 0) { "bad mesh $name" }
        val n = positions.size / 3
        require(indices.all { it in 0 until n }) { "index out of range in $name" }
        val pos = accessor(positions, 3, "VEC3", true)
        val nor = normals?.let { require(it.size == positions.size); accessor(it, 3, "VEC3", false) }
        val uv = uvs?.let { require(it.size == n * 2); accessor(it, 2, "VEC2", false) }
        val ib = ByteBuffer.allocate(indices.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> indices.forEach { b.putInt(it) } }.array()
        val iv = view(ib, 34963)
        accessors += buildJsonObject { put("bufferView", iv); put("componentType", 5125); put("count", indices.size); put("type", "SCALAR") }
        val idx = accessors.size - 1
        meshes += buildJsonObject {
            put("name", name)
            put("primitives", buildJsonArray {
                add(buildJsonObject {
                    put("attributes", buildJsonObject {
                        put("POSITION", pos)
                        nor?.let { put("NORMAL", it) }
                        uv?.let { put("TEXCOORD_0", it) }
                    })
                    put("indices", idx); put("material", material); put("mode", 4)
                })
            })
        }
        nodes += buildJsonObject {
            put("name", name); put("mesh", meshes.size - 1)
            if (matrix != null) put("matrix", JsonArray(matrix.map { JsonPrimitive(it) }))
        }
        return nodes.size - 1
    }

    fun build(): ByteArray {
        while (bin.size() % 4 != 0) bin.write(0)
        val binBytes = bin.toByteArray()
        val json = buildJsonObject {
            put("asset", buildJsonObject { put("version", "2.0"); put("generator", "Drishti Saathi GlbWriter") })
            put("scene", 0)
            put("scenes", buildJsonArray { add(buildJsonObject { put("nodes", JsonArray(nodes.indices.map { JsonPrimitive(it) })) }) })
            put("nodes", JsonArray(nodes)); put("meshes", JsonArray(meshes)); put("materials", JsonArray(materials))
            put("accessors", JsonArray(accessors)); put("bufferViews", JsonArray(views))
            put("buffers", buildJsonArray { add(buildJsonObject { put("byteLength", binBytes.size) }) })
            if (images.isNotEmpty()) {
                put("images", JsonArray(images))
                put("samplers", buildJsonArray { add(buildJsonObject { put("magFilter", 9729); put("minFilter", 9987) }) })
                put("textures", JsonArray(images.indices.map {
                    buildJsonObject {
                        put("source", it); put("sampler", 0)
                        if (srgbImages[it]) put("extras", buildJsonObject { put("colorSpace", "srgb") })
                    }
                }))
            }
        }.toString().toByteArray(Charsets.UTF_8)
        val jsonPad = (4 - json.size % 4) % 4
        val total = 12 + 8 + json.size + jsonPad + 8 + binBytes.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67); out.putInt(2); out.putInt(total)
        out.putInt(json.size + jsonPad); out.putInt(0x4E4F534A); out.put(json); repeat(jsonPad) { out.put(' '.code.toByte()) }
        out.putInt(binBytes.size); out.putInt(0x004E4942); out.put(binBytes)
        return out.array()
    }

    companion object {
        /** Reads the JSON chunk of a GLB (tests and debugging). */
        fun jsonChunk(glb: ByteArray): String {
            val b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
            require(b.getInt(0) == 0x46546C67) { "not a GLB" }
            val len = b.getInt(12)
            return String(glb, 20, len, Charsets.UTF_8).trimEnd()
        }

        /** A unit sphere (radius 1, CCW outwards) as a GLB, for the invisible head occluder. */
        fun unitSphereGlb(rings: Int = 16, segments: Int = 24): ByteArray {
            val p = ArrayList<Float>()
            for (r in 0..rings) {
                val th = Math.PI * r / rings
                for (s in 0..segments) {
                    val ph = 2 * Math.PI * s / segments
                    p += (kotlin.math.sin(th) * kotlin.math.cos(ph)).toFloat(); p += kotlin.math.cos(th).toFloat()
                    p += (kotlin.math.sin(th) * kotlin.math.sin(ph)).toFloat()
                }
            }
            val idx = ArrayList<Int>()
            val w = segments + 1
            for (r in 0 until rings) for (s in 0 until segments) {
                val a = r * w + s; val b = a + w
                idx += a; idx += a + 1; idx += b
                idx += a + 1; idx += b + 1; idx += b
            }
            val pos = p.toFloatArray()
            val g = GlbWriter()
            val m = g.addMaterial(Material("occluder", roughness = 1f))
            g.addMesh("occluder", pos, idx.toIntArray(), m, pos.copyOf())
            return g.build()
        }

        /** Smooth per-vertex normals (area-weighted) for CCW triangles. */
        fun vertexNormals(p: FloatArray, idx: IntArray): FloatArray {
            val n = FloatArray(p.size)
            for (t in idx.indices step 3) {
                val (a, b, c) = Triple(idx[t] * 3, idx[t + 1] * 3, idx[t + 2] * 3)
                val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
                val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                for (k in listOf(a, b, c)) { n[k] += nx; n[k + 1] += ny; n[k + 2] += nz }
            }
            for (i in n.indices step 3) {
                val l = kotlin.math.sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2]).takeIf { it > 1e-9f } ?: 1f
                n[i] /= l; n[i + 1] /= l; n[i + 2] /= l
            }
            return n
        }
    }
}
