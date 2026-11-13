package com.drishtisaathi.engine.tryon3d

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Lenses of a catalogue frame GLB (tools/frames_to_glb.py), for the avatar and the live try-on:
 *  - clear lenses (no texture) become almost fully transparent and matte: alpha [CLEAR_ALPHA], roughness 1, no
 *    metal, so no grey sheen or reflection ever covers the eyes; only the rim and a faint tint at the lens edge
 *    (where the thin lens is seen edge-on) remain;
 *  - tinted sunglasses lenses (textured) keep their tint.
 * The frame front never carries the product photo inside the lens openings: the generator cuts them out of the
 * front mesh (checked by [frontCoversPoint] in the tests).
 */
object FrameLens {
    const val CLEAR_ALPHA = 0.04
    private val LENS_NODES = setOf("lens_R", "lens_L")

    class Parsed(val json: JsonObject, val bin: ByteArray)

    fun parse(glb: ByteArray): Parsed {
        val bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.getInt(0) == 0x46546C67) { "not a GLB" }
        val jsonLen = bb.getInt(12)
        val json = Json.parseToJsonElement(String(glb, 20, jsonLen, Charsets.UTF_8)).jsonObject
        var bin = ByteArray(0)
        val o = 20 + jsonLen
        if (o + 8 <= glb.size) {
            val binLen = bb.getInt(o)
            bin = glb.copyOfRange(o + 8, o + 8 + binLen)
        }
        return Parsed(json, bin)
    }

    fun write(json: JsonObject, bin: ByteArray): ByteArray {
        var j = json.toString().toByteArray(Charsets.UTF_8)
        if (j.size % 4 != 0) j += ByteArray(4 - j.size % 4) { ' '.code.toByte() }
        var b = bin
        if (b.size % 4 != 0) b += ByteArray(4 - b.size % 4)
        val total = 12 + 8 + j.size + (if (b.isEmpty()) 0 else 8 + b.size)
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67).putInt(2).putInt(total)
        out.putInt(j.size).putInt(0x4E4F534A).put(j)
        if (b.isNotEmpty()) out.putInt(b.size).putInt(0x004E4942).put(b)
        return out.array()
    }

    /** Material indices used by the lens nodes. */
    fun lensMaterials(json: JsonObject): Set<Int> {
        val meshes = json["meshes"]?.jsonArray ?: return emptySet()
        val out = HashSet<Int>()
        for (n in json["nodes"]?.jsonArray.orEmpty()) {
            val o = n.jsonObject
            if (o["name"]?.jsonPrimitive?.content !in LENS_NODES) continue
            val mi = o["mesh"]?.jsonPrimitive?.int ?: continue
            for (p in meshes[mi].jsonObject["primitives"]?.jsonArray.orEmpty()) p.jsonObject["material"]?.jsonPrimitive?.int?.let { out += it }
        }
        return out
    }

    private fun isTinted(m: JsonObject) = (m["pbrMetallicRoughness"] as? JsonObject)?.containsKey("baseColorTexture") == true

    /** The GLB with clear lenses made transparent and matte (tinted lenses untouched). Unchanged if it has no lenses. */
    fun clearLenses(glb: ByteArray): ByteArray = runCatching {
        val p = parse(glb)
        val lens = lensMaterials(p.json)
        val mats = p.json["materials"]?.jsonArray ?: return glb
        if (lens.none { it in mats.indices && !isTinted(mats[it].jsonObject) }) return glb
        val newMats = JsonArray(mats.mapIndexed { i, m ->
            val o = m.jsonObject
            if (i !in lens || isTinted(o)) m
            else {
                val pbr = (o["pbrMetallicRoughness"] as? JsonObject) ?: JsonObject(emptyMap())
                val base = pbr["baseColorFactor"]?.jsonArray?.map { it.jsonPrimitive.content.toDouble() } ?: listOf(1.0, 1.0, 1.0, 1.0)
                val rgb = base.take(3)
                JsonObject(o + mapOf(
                    "pbrMetallicRoughness" to JsonObject(pbr + mapOf(
                        "baseColorFactor" to JsonArray((rgb + CLEAR_ALPHA).map { JsonPrimitive(it) }),
                        "roughnessFactor" to JsonPrimitive(1.0),
                        "metallicFactor" to JsonPrimitive(0.0),
                    )),
                    "alphaMode" to JsonPrimitive("BLEND"),
                ))
            }
        })
        write(JsonObject(p.json + ("materials" to newMats)), p.bin)
    }.getOrDefault(glb)

    /** Base colour alpha of each lens material (for checks). */
    fun lensAlphas(glb: ByteArray): List<Pair<Double, Boolean>> {
        val p = parse(glb)
        val mats = p.json["materials"]!!.jsonArray
        return lensMaterials(p.json).map { i ->
            val m = mats[i].jsonObject
            val a = (m["pbrMetallicRoughness"] as? JsonObject)?.get("baseColorFactor")?.jsonArray?.getOrNull(3)?.jsonPrimitive?.content?.toDouble() ?: 1.0
            a to isTinted(m)
        }
    }

    private fun accessorFloats(p: Parsed, index: Int): FloatArray {
        val acc = p.json["accessors"]!!.jsonArray[index].jsonObject
        val bv = p.json["bufferViews"]!!.jsonArray[acc["bufferView"]!!.jsonPrimitive.int].jsonObject
        val count = acc["count"]!!.jsonPrimitive.int
        val comps = when (acc["type"]!!.jsonPrimitive.content) { "VEC3" -> 3; "VEC2" -> 2; else -> 1 }
        val off = (bv["byteOffset"]?.jsonPrimitive?.int ?: 0) + (acc["byteOffset"]?.jsonPrimitive?.int ?: 0)
        val bb = ByteBuffer.wrap(p.bin).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count * comps) { bb.getFloat(off + 4 * it) }
    }

    private fun accessorInts(p: Parsed, index: Int): IntArray {
        val acc = p.json["accessors"]!!.jsonArray[index].jsonObject
        val bv = p.json["bufferViews"]!!.jsonArray[acc["bufferView"]!!.jsonPrimitive.int].jsonObject
        val count = acc["count"]!!.jsonPrimitive.int
        val off = (bv["byteOffset"]?.jsonPrimitive?.int ?: 0) + (acc["byteOffset"]?.jsonPrimitive?.int ?: 0)
        val bb = ByteBuffer.wrap(p.bin).order(ByteOrder.LITTLE_ENDIAN)
        return when (acc["componentType"]!!.jsonPrimitive.int) {
            5121 -> IntArray(count) { p.bin[off + it].toInt() and 0xFF }
            5123 -> IntArray(count) { bb.getShort(off + 2 * it).toInt() and 0xFFFF }
            else -> IntArray(count) { bb.getInt(off + 4 * it) }
        }
    }

    /** True when a triangle of mesh [meshName] covers the model point (x, y) mm seen from the front (z ignored). */
    fun frontCoversPoint(glb: ByteArray, meshName: String, x: Double, y: Double): Boolean {
        val p = parse(glb)
        val meshes = p.json["meshes"]!!.jsonArray
        val mesh = meshes.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.content == meshName }?.jsonObject ?: return false
        for (prim in mesh["primitives"]!!.jsonArray) {
            val a = prim.jsonObject
            val pos = accessorFloats(p, a["attributes"]!!.jsonObject["POSITION"]!!.jsonPrimitive.int)
            val idx = a["indices"]?.jsonPrimitive?.int?.let { accessorInts(p, it) } ?: IntArray(pos.size / 3) { it }
            for (t in idx.indices step 3) {
                val ax = pos[3 * idx[t]]; val ay = pos[3 * idx[t] + 1]
                val bx = pos[3 * idx[t + 1]]; val by = pos[3 * idx[t + 1] + 1]
                val cx = pos[3 * idx[t + 2]]; val cy = pos[3 * idx[t + 2] + 1]
                if (abs((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)) < 1e-6) continue
                val d1 = (x - bx) * (ay - by) - (ax - bx) * (y - by)
                val d2 = (x - cx) * (by - cy) - (bx - cx) * (y - cy)
                val d3 = (x - ax) * (cy - ay) - (cx - ax) * (y - ay)
                val neg = d1 < 0 || d2 < 0 || d3 < 0; val pos3 = d1 > 0 || d2 > 0 || d3 > 0
                if (!(neg && pos3)) return true
            }
        }
        return false
    }
}
