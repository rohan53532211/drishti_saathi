package com.drishtisaathi.engine

import kotlin.math.abs

/**
 * M5 lens thickness estimate (thin-lens sag approximation).
 *
 *   decentration = (A + DBL − PD) / 2
 *   y            = A/2 + decentration            (furthest edge from the optical centre, mm)
 *   sag          = y² · |F| / (2000 · (n − 1))
 *   minus lens:  edge   = t_centre + sag
 *   plus lens:   centre = t_minEdge + sag
 */
object Thickness {

    data class Result(
        val powerD: Double,
        val materialId: String,
        val yMm: Double,
        val edgeMm: Double,
        val centreMm: Double,
    ) {
        val isPlus: Boolean get() = powerD > 0
        /** The thickest point of the lens: edge for minus, centre for plus. */
        val maxMm: Double get() = maxOf(edgeMm, centreMm)
    }

    fun decentrationMm(lensWidthMm: Double, bridgeMm: Double, pdMm: Double): Double =
        (lensWidthMm + bridgeMm - pdMm) / 2.0

    fun maxRadiusMm(lensWidthMm: Double, bridgeMm: Double, pdMm: Double): Double =
        lensWidthMm / 2.0 + abs(decentrationMm(lensWidthMm, bridgeMm, pdMm))

    fun sagMm(yMm: Double, powerD: Double, index: Double): Double =
        yMm * yMm * abs(powerD) / (2000.0 * (index - 1.0))

    fun estimate(powerD: Double, lensWidthMm: Double, bridgeMm: Double, pdMm: Double, material: MaterialSpec): Result {
        val y = maxRadiusMm(lensWidthMm, bridgeMm, pdMm)
        val sag = sagMm(y, powerD, material.index)
        return if (powerD <= 0) {
            val centre = material.centreThicknessMinusMm
            Result(powerD, material.id, y, edgeMm = centre + sag, centreMm = centre)
        } else {
            val edge = material.minEdgePlusMm
            Result(powerD, material.id, y, edgeMm = edge, centreMm = edge + sag)
        }
    }
}
