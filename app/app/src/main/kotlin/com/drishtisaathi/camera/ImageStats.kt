package com.drishtisaathi.camera

/** Brightness (mean luminance 0–255) and sharpness (Laplacian variance) of the face region of one frame. */
data class ImageStats(val meanBrightness: Double, val laplacianVariance: Double)

/**
 * Pure image measurements on packed ARGB pixels (Bitmap.getPixels layout), so they are unit-testable
 * without Android. Only the face region is read; the pixels are discarded by the caller right after.
 */
object ImageStatsCalc {

    /** Rec. 601 luma of one ARGB pixel. */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (299 * r + 587 * g + 114 * b) / 1000
    }

    /** Luminance plane of a width × height ARGB array. */
    fun toGray(argb: IntArray, width: Int, height: Int): IntArray {
        require(argb.size >= width * height)
        return IntArray(width * height) { luma(argb[it]) }
    }

    fun meanBrightness(gray: IntArray, width: Int, height: Int): Double {
        val n = width * height
        if (n == 0) return 0.0
        var sum = 0L
        for (i in 0 until n) sum += gray[i]
        return sum.toDouble() / n
    }

    /**
     * Variance of the 4-neighbour Laplacian (OpenCV `Laplacian(ksize=1)`) over the interior pixels.
     * Higher = sharper. A uniform image gives 0.
     */
    fun laplacianVariance(gray: IntArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val lap = (gray[i - 1] + gray[i + 1] + gray[i - width] + gray[i + width] - 4 * gray[i]).toDouble()
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    fun compute(argb: IntArray, width: Int, height: Int): ImageStats {
        val g = toGray(argb, width, height)
        return ImageStats(meanBrightness(g, width, height), laplacianVariance(g, width, height))
    }

    /** Face box (normalised) → integer pixel rectangle [left, top, width, height] clamped to the image. */
    fun roi(box: FaceBox, imageWidth: Int, imageHeight: Int): IntArray? {
        val l = (box.left * imageWidth).toInt().coerceIn(0, imageWidth - 1)
        val t = (box.top * imageHeight).toInt().coerceIn(0, imageHeight - 1)
        val r = (box.right * imageWidth).toInt().coerceIn(0, imageWidth)
        val b = (box.bottom * imageHeight).toInt().coerceIn(0, imageHeight)
        val w = r - l
        val h = b - t
        return if (w >= 3 && h >= 3) intArrayOf(l, t, w, h) else null
    }
}
