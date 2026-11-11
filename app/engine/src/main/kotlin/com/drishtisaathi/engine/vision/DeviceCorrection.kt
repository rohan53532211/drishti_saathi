package com.drishtisaathi.engine.vision

import com.drishtisaathi.engine.config.DeviceCorrectionConfig
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Per-phone-model bias correction from the shop's own paired records (learning loop).
 *
 * Each verified session gives up to two pairs (one per eye): the raw far-point SE the phone measured, and the SE of
 * the final trial lens the staff entered after their own test. Once [DeviceCorrectionConfig.minVerifiedSessions]
 * sessions exist for this phone model, a straight line `final = intercept + slope × measured` is fitted by least
 * squares and applied to new far-point estimates before the Bayesian band.
 *
 * Guards (all in rules.json `deviceCorrection`):
 * - pairs whose measured and final SE differ by more than [DeviceCorrectionConfig.excludePairAbsDiffOverD] are
 *   treated as entry errors and left out;
 * - if the measured values barely spread ([DeviceCorrectionConfig.minMeasuredSpreadD]), only an offset is fitted
 *   (slope 1); otherwise the slope is clamped to [slopeMin, slopeMax] and the intercept refitted for that slope;
 * - when applied, the change to any one estimate is capped at ±[DeviceCorrectionConfig.maxCorrectionD].
 *
 * The fit always uses the raw measured SE stored with each session, never a corrected one, so it cannot feed on itself.
 */
@Serializable
data class DeviceFit(
    val intercept: Double,
    val slope: Double,
    /** Verified sessions that contributed at least one pair: the "N shop records" shown to staff. */
    val sessions: Int,
    val eyes: Int,
    /** Residual SD of the fitted line (D); for staff review, not used in the estimate. */
    val residualSdD: Double,
    val maxCorrectionD: Double,
) {
    /** Corrected SE for a raw measured [seD]; the change is capped at ±[maxCorrectionD]. */
    fun apply(seD: Double): Double {
        val delta = (intercept + slope * seD - seD).coerceIn(-maxCorrectionD, maxCorrectionD)
        return seD + delta
    }
}

/** One eye of one verified session: raw measured far-point SE vs final trial lens SE. */
data class PairedEye(val sessionId: Long, val measuredSeD: Double, val finalSeD: Double)

object DeviceCorrection {

    /** Null when disabled, or fewer than [DeviceCorrectionConfig.minVerifiedSessions] sessions have a usable pair. */
    fun fit(pairs: List<PairedEye>, cfg: DeviceCorrectionConfig): DeviceFit? {
        if (!cfg.enabled) return null
        val used = pairs.filter { abs(it.finalSeD - it.measuredSeD) <= cfg.excludePairAbsDiffOverD }
        val sessions = used.map { it.sessionId }.distinct().size
        if (sessions < cfg.minVerifiedSessions) return null

        val n = used.size
        val mx = used.sumOf { it.measuredSeD } / n
        val my = used.sumOf { it.finalSeD } / n
        val sxx = used.sumOf { (it.measuredSeD - mx) * (it.measuredSeD - mx) }
        val sxy = used.sumOf { (it.measuredSeD - mx) * (it.finalSeD - my) }
        val spread = sqrt(sxx / n)

        val slope = if (spread < cfg.minMeasuredSpreadD) 1.0 else (sxy / sxx).coerceIn(cfg.slopeMin, cfg.slopeMax)
        val intercept = my - slope * mx
        val rss = used.sumOf { val r = it.finalSeD - (intercept + slope * it.measuredSeD); r * r }
        val residualSd = sqrt(rss / (n - 1).coerceAtLeast(1))
        return DeviceFit(intercept, slope, sessions, n, residualSd, cfg.maxCorrectionD)
    }
}

/**
 * Spherical equivalent of a trial lens typed by staff, e.g. "-1.75 / -0.50 x 180", "-1.75/-0.5x90", "+1.00 DS",
 * "plano", "pl / -0.75 x 10", "−2.25" (Unicode minus). SE = sphere + cylinder / 2. Null when it cannot be read.
 */
object TrialLensText {
    private val number = Regex("""[+-]?\d+(?:\.\d+)?""")

    fun sphericalEquivalent(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val t = text.trim().lowercase().replace('−', '-').replace('–', '-').replace('×', 'x')
        val powers = t.substringBefore('x') // the axis follows "x"; it does not enter the SE
        val plano = powers.trimStart().startsWith("pl")
        val nums = number.findAll(powers).map { it.value.toDouble() }.toList()
        val (sph, cyl) = when {
            plano && nums.size <= 1 -> 0.0 to (nums.firstOrNull() ?: 0.0)
            !plano && nums.size == 1 -> nums[0] to 0.0
            !plano && nums.size == 2 -> nums[0] to nums[1]
            else -> return null
        }
        if (abs(sph) > 20.0 || abs(cyl) > 10.0) return null
        if (t.contains('x') && cyl == 0.0) return null // an axis without a cylinder is a typo
        return sph + cyl / 2.0
    }
}
