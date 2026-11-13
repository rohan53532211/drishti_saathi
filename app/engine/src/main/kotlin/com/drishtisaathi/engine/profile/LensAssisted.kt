package com.drishtisaathi.engine.profile

import com.drishtisaathi.engine.config.LensAssistedConfig
import com.drishtisaathi.engine.vision.DeviceFit
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** How an eye's power was measured (shown on every report, PDF and QR). */
@Serializable enum class PowerMethod { UNAIDED, LENS_ASSISTED }

/**
 * Phase B "lens-assisted mode" (over-refraction).
 *
 * Power = −1/d. For a strong eye the blur point is very close, so a small distance error becomes a big power error
 * (−4.25 D → 23.5 cm, where 1 cm is about 0.18 D). Staff therefore hold a known minus lens (a trial lens or the
 * customer's old glasses) in front of the eye and the blur-distance test is repeated through it: the residual blur
 * point is farther away, where the same 1 cm matters much less. Total = lens + residual.
 */
object LensAssisted {

    /** The first pass (no lens) is strong enough to switch to lens-assisted mode. */
    fun needed(firstPassSeD: Double?, cfg: LensAssistedConfig): Boolean = firstPassSeD != null && firstPassSeD <= cfg.triggerSeD

    /**
     * The lens to hold: about [LensAssistedConfig.suggestOffsetD] weaker than the estimate, rounded towards the weaker
     * lens to [LensAssistedConfig.suggestStepD] (−4.25 → −2.00, −6.00 → −4.00). Never a plus lens.
     */
    fun suggestLens(firstPassSeD: Double, cfg: LensAssistedConfig): Double {
        val raw = firstPassSeD + cfg.suggestOffsetD
        if (raw >= 0) return 0.0
        return (ceil(raw / cfg.suggestStepD - 1e-9) * cfg.suggestStepD).let { if (it == -0.0) 0.0 else it }
    }

    /** Effective power at the eye of a lens held [LensAssistedConfig.vertexDistanceM] in front of it: Fe = F / (1 − d·F). */
    fun effectiveLens(lensD: Double, cfg: LensAssistedConfig): Double =
        if (abs(lensD) >= cfg.vertexFromAbsD) lensD / (1.0 - cfg.vertexDistanceM * lensD) else lensD

    /** A typed lens power is usable: a minus lens (or plano) within the limit, on the 0.25 D grid. */
    fun validLens(lensD: Double?, cfg: LensAssistedConfig): Boolean =
        lensD != null && lensD <= 0 && abs(lensD) <= cfg.maxLensAbsD && abs(lensD / cfg.lensStepD - (lensD / cfg.lensStepD).roundToInt()) < 1e-6

    /** Stronger than −8 D: "High myopia – optometrist must confirm; eye-health check advised". */
    fun highMyopia(seD: Double?, cfg: LensAssistedConfig): Boolean = seD != null && seD < cfg.highMyopiaBeyondSeD

    @Serializable
    data class Total(
        val lensD: Double,
        /** The lens after the vertex correction (equal to [lensD] below the vertex threshold). */
        val effectiveLensD: Double,
        /** Residual through the lens, rounded to 0.25 D (after the phone-model correction, if any). */
        val residualD: Double,
        val totalD: Double,
        /** The total as a range: ± rangeHalfWidthD, less minus first. */
        val rangeFromD: Double,
        val rangeToD: Double,
        val highMyopia: Boolean,
    )

    sealed interface Outcome {
        data class Measured(val total: Total) : Outcome
        /** No blur point within reach through the lens: it over-corrects. "Use a weaker lens"; never a guess. */
        data object LensTooStrong : Outcome
    }

    private fun round(v: Double, step: Double): Double = ((v / step).roundToInt() * step).let { if (it == -0.0) 0.0 else it }

    /**
     * [residualRawSeD]: −1/d through the lens (plus the blue correction when the blue target is used), unrounded;
     * null = the letters stayed sharp at every distance within reach. [fit] (learning loop) corrects the residual
     * only: the lens power is known exactly.
     */
    fun total(lensD: Double, residualRawSeD: Double?, cfg: LensAssistedConfig, fit: DeviceFit? = null): Outcome {
        if (residualRawSeD == null) return Outcome.LensTooStrong
        val residual = fit?.apply(residualRawSeD) ?: residualRawSeD
        val eff = effectiveLens(lensD, cfg)
        val total = round(eff + residual, cfg.lensStepD)
        return Outcome.Measured(
            Total(
                lensD, eff, round(residual, cfg.lensStepD), total,
                rangeFromD = total + cfg.rangeHalfWidthD, rangeToD = total - cfg.rangeHalfWidthD,
                highMyopia = highMyopia(total, cfg),
            )
        )
    }
}
