package com.drishtisaathi.calibration

import com.drishtisaathi.engine.config.CameraConfig
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** A 2-D point in image pixels. */
data class Pt(val x: Double, val y: Double)

/** Why a candidate quadrilateral was rejected (or OK). */
enum class QuadVerdict { OK, NOT_FOUR_CORNERS, NOT_CONVEX, WRONG_ASPECT, TOO_SMALL, TOO_LARGE, SKEWED }

/** A validated card outline: [widthPx] along the long sides, [heightPx] along the short sides. */
data class CardQuad(val corners: List<Pt>, val widthPx: Double, val heightPx: Double) {
    val aspect: Double get() = widthPx / heightPx
}

/**
 * Pure checks for the bank-card outline (ID-1: 85.60 × 53.98 mm, aspect ≈ 1.586). OpenCV finds candidate
 * 4-corner contours; this decides which one is the card. Unit-tested without OpenCV.
 */
object CardGeometry {

    /** Puts 4 corners in order around the polygon (by angle around the centroid). */
    fun order(corners: List<Pt>): List<Pt> {
        val cx = corners.sumOf { it.x } / corners.size
        val cy = corners.sumOf { it.y } / corners.size
        return corners.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
    }

    private fun len(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)

    private fun isConvex(p: List<Pt>): Boolean {
        var sign = 0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]; val c = p[(i + 2) % p.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Shoelace area in px². */
    fun area(p: List<Pt>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2.0
    }

    /**
     * Validates a candidate. Width = mean of the two long opposite sides, height = mean of the two short ones.
     * @param imageWidthPx photo width, for the size-fraction limits.
     */
    fun validate(corners: List<Pt>, imageWidthPx: Int, cfg: CameraConfig): Pair<QuadVerdict, CardQuad?> {
        if (corners.size != 4) return QuadVerdict.NOT_FOUR_CORNERS to null
        val p = order(corners)
        if (!isConvex(p)) return QuadVerdict.NOT_CONVEX to null
        val s0 = len(p[0], p[1]); val s1 = len(p[1], p[2]); val s2 = len(p[2], p[3]); val s3 = len(p[3], p[0])
        // Opposite pairs: (s0, s2) and (s1, s3).
        val pairA = (s0 + s2) / 2.0
        val pairB = (s1 + s3) / 2.0
        val mismatch = max(abs(s0 - s2) / max(s0, s2), abs(s1 - s3) / max(s1, s3))
        val width = max(pairA, pairB)
        val height = minOf(pairA, pairB)
        if (height <= 0.0) return QuadVerdict.NOT_CONVEX to null
        val quad = CardQuad(p, width, height)
        val aspectErr = abs(quad.aspect - cfg.cardAspect) / cfg.cardAspect
        val fraction = width / imageWidthPx
        return when {
            fraction < cfg.cardMinWidthFraction -> QuadVerdict.TOO_SMALL to null
            fraction > cfg.cardMaxWidthFraction -> QuadVerdict.TOO_LARGE to null
            mismatch > cfg.cardMaxSideMismatch -> QuadVerdict.SKEWED to null
            aspectErr > cfg.cardAspectTolerance -> QuadVerdict.WRONG_ASPECT to null
            else -> QuadVerdict.OK to quad
        }
    }

    /** Among candidate quads, the valid one with the largest area (the card, not a printed box on it). */
    fun best(candidates: List<List<Pt>>, imageWidthPx: Int, cfg: CameraConfig): CardQuad? =
        candidates.mapNotNull { validate(it, imageWidthPx, cfg).second }.maxByOrNull { area(it.corners) }

    /** Camera scale at the card's plane: mm per image pixel. */
    fun cameraMmPerPx(card: CardQuad, cfg: CameraConfig): Double = cfg.cardWidthMm / card.widthPx
}

/** Screen-scale arithmetic for the drag-to-match calibration (pure). */
object ScreenScale {
    /** The rectangle is drawn portrait (card's long side vertical, since a phone is narrower than 85.6 mm). */
    fun mmPerPxFromCardLongSide(longSidePx: Double, cfg: CameraConfig): Double = cfg.cardWidthMm / longSidePx

    /** Fallback from the panel's reported density (often rounded by the maker → lower confidence). */
    fun mmPerPxFromDpi(dpi: Double): Double = 25.4 / dpi

    /** Card long side in px for a given scale (to draw the starting rectangle). */
    fun cardLongSidePx(mmPerPx: Double, cfg: CameraConfig): Double = cfg.cardWidthMm / mmPerPx

    fun plausible(mmPerPx: Double, cfg: CameraConfig): Boolean = mmPerPx in cfg.screenMinMmPerPx..cfg.screenMaxMmPerPx
}
