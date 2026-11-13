package com.drishtisaathi.engine.tryon3d

import com.drishtisaathi.engine.ColourPref
import com.drishtisaathi.engine.Frame
import com.drishtisaathi.engine.FrameShape
import com.drishtisaathi.engine.LookPref
import com.drishtisaathi.engine.RimType
import com.drishtisaathi.engine.StylePref
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 3D frame catalogue (frames_catalog.json from tools/frames_to_glb.py, or GET /catalog from the backend).
 * Units are mm; the GLB origin is the bridge centre, +X the wearer's right, +Y up, +Z towards the face.
 */
@Serializable
data class Sizes3D(
    val a: Int,
    val dbl: Int,
    val temple: Int,
    val b: Int = 0,
    val frontWidth: Int = 0,
    val label: String = "$a-$dbl-$temple",
)

@Serializable
data class Anchors3D(
    val origin: String = "bridge centre",
    /** Where the bridge rests on the nose (model mm). */
    val noseRestMm: List<Double> = listOf(0.0, 0.0, 0.0),
    /** lens_R / lens_L centres [x, y] (model mm). */
    val lensCentresMm: Map<String, List<Double>> = emptyMap(),
    /** temple_R / temple_L hinge points [x, y, z] (model mm); the temple nodes' origins. */
    val hingesMm: Map<String, List<Double>> = emptyMap(),
    /** Temple tip in the temple's own (hinge) coordinates [x, y, z]. */
    val templeEndLocalMm: List<Double> = listOf(0.0, -16.0, 135.0),
    val units: String = "mm",
    val axes: String = "",
)

@Serializable
data class Frame3D(
    val id: String,
    val name: String,
    val sku: String = "",
    val category: String = CATEGORY_EYEGLASSES,
    val shape: String,
    val rim: String = "full",
    val colour: String,
    val material: String,
    val materialEstimated: Boolean = true,
    val sizes: Sizes3D,
    /** "measured" (printed size of a real shop frame) or "estimated" (from photo proportions). */
    val dimensionsSource: String = DIMENSIONS_ESTIMATED,
    val priceInr: Int,
    val priceSource: String = "demo",
    /** Paths relative to the catalogue folder (bundled or generated). */
    val glb: String? = null,
    val thumb: String? = null,
    /** Server URLs (GET /catalog) relative to the backend base URL. */
    val glbUrl: String? = null,
    val thumbUrl: String? = null,
    val source: String = "",
    val anchors: Anchors3D = Anchors3D(),
    val quality: Double = 0.0,
    val glbBytes: Long = 0,
) {
    val sizesEstimated: Boolean get() = dimensionsSource != DIMENSIONS_MEASURED
    val priceBand: String get() = Catalog3D.priceBand(priceInr)
    val sizeBand: String get() = Catalog3D.sizeBand(sizes.a)
    val frontWidthMm: Double get() = if (sizes.frontWidth > 0) sizes.frontWidth.toDouble() else 2.0 * sizes.a + sizes.dbl + 6

    /** Hinge x (mm) of the wearer's right temple, for the temple-bend math. */
    val hingeHalfWidthMm: Double
        get() = anchors.hingesMm["temple_R"]?.getOrNull(0)?.let { kotlin.math.abs(it) } ?: (frontWidthMm / 2 - 2)

    companion object {
        const val CATEGORY_EYEGLASSES = "eyeglasses"
        const val CATEGORY_SUNGLASSES = "sunglasses"
        const val DIMENSIONS_MEASURED = "measured"
        const val DIMENSIONS_ESTIMATED = "estimated"
    }
}

@Serializable
data class Catalog3D(val version: Int = 1, val licence: String = "", val frames: List<Frame3D> = emptyList()) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Catalog3D = json.decodeFromString(serializer(), text).also { c ->
            require(c.frames.map { it.id }.toSet().size == c.frames.size) { "frames_catalog.json: duplicate ids" }
            c.frames.forEach { f ->
                require(f.sizes.a > 0 && f.sizes.dbl > 0 && f.sizes.temple > 0) { "frames_catalog.json: ${f.id} needs sizes" }
                require(f.glb != null || f.glbUrl != null) { "frames_catalog.json: ${f.id} needs a glb" }
            }
        }

        /** Same bands as the backend (GET /catalog?price=…&size=…). */
        fun priceBand(price: Int): String = when {
            price <= 999 -> "budget"
            price <= 1999 -> "mid"
            else -> "premium"
        }

        fun sizeBand(lensWidthMm: Int): String = when {
            lensWidthMm <= 49 -> "small"
            lensWidthMm <= 53 -> "medium"
            else -> "large"
        }

        /**
         * The catalogue entry as an engine [Frame] so the existing ranking (face width, budget, style, lens
         * thickness, colour, look) can sort it. Style, look and weight are derived from shape and material.
         */
        fun toEngineFrame(f: Frame3D): Frame {
            val shape = when (f.shape) {
                "round" -> FrameShape.ROUND
                "oval" -> FrameShape.OVAL
                "square", "geometric" -> FrameShape.SQUARE
                "cat-eye" -> FrameShape.CAT_EYE
                "aviator" -> FrameShape.AVIATOR
                else -> FrameShape.RECTANGLE
            }
            val metal = f.material == "metal"
            val styles = buildList {
                when (shape) {
                    FrameShape.RECTANGLE, FrameShape.OVAL -> add(StylePref.CLASSIC)
                    FrameShape.ROUND, FrameShape.SQUARE -> add(StylePref.MODERN)
                    else -> add(StylePref.BOLD)
                }
                if (metal || f.rim != "full") add(StylePref.MINIMAL)
                if (f.colour in listOf("red", "blue", "green", "pink", "purple", "orange")) add(StylePref.BOLD)
            }.distinct()
            val colour = when (f.colour) {
                "black", "grey" -> ColourPref.DARK
                "brown", "tortoise", "beige" -> ColourPref.BROWN_TORTOISE
                "gold", "silver", "white", "clear" -> ColourPref.METALLIC
                else -> ColourPref.COLOURFUL
            }
            val looks = if (shape in listOf(FrameShape.RECTANGLE, FrameShape.OVAL, FrameShape.SQUARE) && colour != ColourPref.COLOURFUL)
                listOf(LookPref.PROFESSIONAL, LookPref.CASUAL) else listOf(LookPref.CASUAL)
            return Frame(
                id = f.id, name = f.name, lensWidthMm = f.sizes.a.toDouble(), bridgeMm = f.sizes.dbl.toDouble(),
                templeMm = f.sizes.temple.toDouble(), shape = shape,
                rim = when (f.rim) { "half" -> RimType.HALF; "rimless" -> RimType.RIMLESS; else -> RimType.FULL },
                material = f.material, priceInr = f.priceInr, styles = styles, colours = listOf(colour),
                weightGrams = if (metal) 18 else 24, looks = looks,
                lensHeightMm = if (f.sizes.b > 0) f.sizes.b.toDouble() else f.sizes.a * 0.75,
            )
        }
    }
}

/** Catalogue screen filters; null = any. [query] matches name, colour, shape, material and size label. */
data class CatalogFilter(
    val category: String? = null,
    val shape: String? = null,
    val rim: String? = null,
    val colour: String? = null,
    val material: String? = null,
    val priceBand: String? = null,
    val sizeBand: String? = null,
    val query: String = "",
) {
    fun matches(f: Frame3D): Boolean {
        if (category != null && f.category != category) return false
        if (shape != null && f.shape != shape) return false
        if (rim != null && f.rim != rim) return false
        if (colour != null && f.colour != colour) return false
        if (material != null && f.material != material) return false
        if (priceBand != null && f.priceBand != priceBand) return false
        if (sizeBand != null && f.sizeBand != sizeBand) return false
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) {
            val hay = listOf(f.name, f.colour, f.shape, f.material, f.sizes.label, f.sku, f.rim).joinToString(" ").lowercase()
            if (q.split(Regex("\\s+")).any { it !in hay }) return false
        }
        return true
    }

    fun apply(frames: List<Frame3D>): List<Frame3D> = frames.filter(::matches)
}

enum class CatalogSort { RECOMMENDED, PRICE_LOW, PRICE_HIGH }

object CatalogOrder {
    /**
     * [recommendedIds] is the ranking engine's order (best first). Frames the ranking left out (e.g. far over
     * budget) follow, cheapest first, so the catalogue never hides a frame.
     */
    fun sort(frames: List<Frame3D>, sort: CatalogSort, recommendedIds: List<String> = emptyList()): List<Frame3D> = when (sort) {
        CatalogSort.PRICE_LOW -> frames.sortedWith(compareBy<Frame3D> { it.priceInr }.thenBy { it.id })
        CatalogSort.PRICE_HIGH -> frames.sortedWith(compareByDescending<Frame3D> { it.priceInr }.thenBy { it.id })
        CatalogSort.RECOMMENDED -> {
            val rank = recommendedIds.withIndex().associate { it.value to it.index }
            frames.sortedWith(
                compareBy<Frame3D> { rank[it.id] ?: Int.MAX_VALUE }
                    .thenByDescending { if (rank[it.id] == null) -it.priceInr.toDouble() else 0.0 }
                    .thenByDescending { it.quality }.thenBy { it.id },
            )
        }
    }
}
