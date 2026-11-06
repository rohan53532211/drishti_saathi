package com.drishtisaathi.engine.vision

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.Estimate
import com.drishtisaathi.engine.OpticsConfig
import com.drishtisaathi.engine.PowerBand
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.Source
import com.drishtisaathi.engine.config.BayesConfig
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Innovation 10: Bayesian SE band. Posterior on a grid (bayes.gridMinD…gridMaxD, step gridStepD) =
 * prior(age, India prevalence)^priorWeight × likelihood.
 *
 * Prior per age band (bayes.agePriors): three classes with the prevalence masses
 * - myopia (SE ≤ myopiaMaxD): falls off as exp(−(myopiaMaxD − SE)/myopiaScaleD),
 * - hyperopia (SE ≥ hyperopiaMinD): exp(−(SE − hyperopiaMinD)/hyperopiaScaleD),
 * - emmetropia (in between): Gaussian(emmetropiaMeanD, emmetropiaSdD),
 * each normalised on the grid so the class masses equal the prevalence figures exactly.
 *
 * Likelihoods: measured SE → N(measured, σ); "no blur within arm's length" → censored,
 * P(measured far point beyond L | SE) = Φ((SE + 1/L)/σ).
 *
 * Output: the 1 D band (bandWidthD) holding the most posterior mass (grid points are bins of width gridStepD, so
 * the band endpoints count half), its probability, the MAP, and a confidence from bandProbabilityHigh/Medium.
 */
class BayesianBand(private val c: BayesConfig, private val optics: OpticsConfig) {

    constructor(rules: RulesConfig) : this(rules.bayes, rules.optics)

    val grid: DoubleArray = run {
        val n = ((c.gridMaxD - c.gridMinD) / c.gridStepD).roundToInt()
        DoubleArray(n + 1) { c.gridMinD + it * c.gridStepD }
    }

    data class Result(
        val band: PowerBand,
        /** Posterior probability that the true SE lies in [band]. */
        val probability: Double,
        val mapD: Double,
        val posteriorMeanD: Double,
        val posteriorSdD: Double,
        /** Band midpoint rounded to the power step: the starting trial lens sphere. */
        val startingLensD: Double,
        val confidence: Confidence,
        val sigmaD: Double?,
        val grid: DoubleArray,
        val posterior: DoubleArray,
    ) {
        fun bandEstimate(): Estimate<PowerBand> = Estimate(band, confidence, Source.MEASURED)

        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    // ---- prior ----------------------------------------------------------------------------------

    fun agePrior(age: Int?): BayesConfig.AgePrior {
        val a = age ?: c.assumedAgeWhenUnknown
        return c.agePriors.firstOrNull { a in it.minAge..it.maxAge }
            ?: c.agePriors.minBy { minOf(abs(a - it.minAge), abs(a - it.maxAge)) }
    }

    /** Normalised prior on [grid] (before priorWeight is applied). */
    fun prior(age: Int?): DoubleArray {
        val p = agePrior(age)
        val eps = 1e-9
        val myo = DoubleArray(grid.size) { i -> val g = grid[i]; if (g <= c.myopiaMaxD + eps) exp(-(c.myopiaMaxD - g) / p.myopiaScaleD) else 0.0 }
        val hyp = DoubleArray(grid.size) { i -> val g = grid[i]; if (g >= c.hyperopiaMinD - eps) exp(-(g - c.hyperopiaMinD) / p.hyperopiaScaleD) else 0.0 }
        val emm = DoubleArray(grid.size) { i ->
            val g = grid[i]
            if (g > c.myopiaMaxD + eps && g < c.hyperopiaMinD - eps) {
                val z = (g - p.emmetropiaMeanD) / p.emmetropiaSdD; exp(-0.5 * z * z)
            } else 0.0
        }
        val parts = listOf(myo to p.myopiaPrevalence, hyp to p.hyperopiaPrevalence,
            emm to (1.0 - p.myopiaPrevalence - p.hyperopiaPrevalence))
        val out = DoubleArray(grid.size)
        for ((shape, mass) in parts) {
            val s = shape.sum()
            if (s <= 0) continue
            for (i in grid.indices) out[i] += mass * shape[i] / s
        }
        return normalise(out)
    }

    // ---- likelihoods ----------------------------------------------------------------------------

    fun sigmaFor(measurementConfidence: Confidence): Double = c.sigmaFor(measurementConfidence)

    /** σ for repeated measures: the confidence's σ, widened to the repeats' sample SD when that is larger. */
    fun sigmaFromRepeats(values: List<Double>, measurementConfidence: Confidence): Double {
        val base = sigmaFor(measurementConfidence)
        if (values.size < 2) return base
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
        return max(base, sd)
    }

    /** Measured SE with Gaussian error σ (default from the measurement confidence). */
    fun fromMeasurement(seD: Double, age: Int?, sigmaD: Double): Result {
        require(sigmaD > 0)
        val like = DoubleArray(grid.size) { i -> val z = (grid[i] - seD) / sigmaD; exp(-0.5 * z * z) }
        return summarise(posterior(age, like), sigmaD)
    }

    fun fromMeasurement(seD: Double, age: Int?, measurementConfidence: Confidence): Result =
        fromMeasurement(seD, age, sigmaFor(measurementConfidence))

    /** No blur found within arm's length (optics.armsLengthM): censored likelihood SE > −1/L, softened by σ. */
    fun fromNoBlurAtArmsLength(age: Int?, sigmaD: Double = c.sigmaMediumD, armsLengthM: Double = optics.armsLengthM): Result {
        require(sigmaD > 0 && armsLengthM > 0)
        val limit = -1.0 / armsLengthM
        val like = DoubleArray(grid.size) { i -> normalCdf((grid[i] - limit) / sigmaD) }
        return summarise(posterior(age, like), sigmaD)
    }

    /** Prior alone (no measurement), e.g. to show what age alone suggests. */
    fun fromPriorOnly(age: Int?): Result = summarise(posterior(age, DoubleArray(grid.size) { 1.0 }), null)

    // ---- posterior --------------------------------------------------------------------------------

    private fun posterior(age: Int?, likelihood: DoubleArray): DoubleArray {
        val prior = prior(age)
        val post = DoubleArray(grid.size) { i ->
            val pr = if (c.priorWeight == 1.0) prior[i] else prior[i].pow(c.priorWeight)
            pr * likelihood[i]
        }
        check(post.sum() > 0) { "posterior vanished: measurement outside the SE grid?" }
        return normalise(post)
    }

    private fun summarise(post: DoubleArray, sigma: Double?): Result {
        val steps = (c.bandWidthD / c.gridStepD).roundToInt()
        var bestLow = 0
        var bestMass = -1.0
        for (lo in 0..grid.size - 1 - steps) {
            var m = 0.5 * (post[lo] + post[lo + steps])
            for (k in lo + 1 until lo + steps) m += post[k]
            if (m > bestMass + 1e-12) { bestMass = m; bestLow = lo }
        }
        val band = PowerBand(grid[bestLow], grid[bestLow + steps])
        val mapIdx = post.indices.maxBy { post[it] }
        val mean = grid.indices.sumOf { grid[it] * post[it] }
        val sd = sqrt(grid.indices.sumOf { (grid[it] - mean) * (grid[it] - mean) * post[it] })
        val conf = when {
            bestMass >= c.bandProbabilityHigh -> Confidence.HIGH
            bestMass >= c.bandProbabilityMedium -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        val lens = (band.midD / optics.powerStepD).roundToLong() * optics.powerStepD
        return Result(band, bestMass, grid[mapIdx], mean, sd, lens, conf, sigma, grid, post)
    }

    companion object {
        private fun normalise(a: DoubleArray): DoubleArray {
            val s = a.sum()
            return DoubleArray(a.size) { a[it] / s }
        }

        /** Standard normal CDF via erf (Abramowitz–Stegun 7.1.26, |error| < 1.5e-7). */
        fun normalCdf(z: Double): Double = 0.5 * (1.0 + erf(z / sqrt(2.0)))

        private fun erf(x: Double): Double {
            val t = 1.0 / (1.0 + 0.3275911 * abs(x))
            val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
            return if (x >= 0) y else -y
        }
    }
}
