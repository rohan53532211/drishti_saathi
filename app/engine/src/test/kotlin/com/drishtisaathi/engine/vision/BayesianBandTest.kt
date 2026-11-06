package com.drishtisaathi.engine.vision

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.Eye
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.KnownRx
import com.drishtisaathi.engine.EyeRx
import com.drishtisaathi.engine.MeasuredEye
import com.drishtisaathi.engine.PowerBand
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.RulesEngine
import com.drishtisaathi.engine.TestAssets
import com.drishtisaathi.engine.VisionTests
import com.drishtisaathi.engine.config.BayesConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BayesianBandTest {
    private val rules = TestAssets.rules
    private val bayes = BayesianBand(rules)

    private fun mass(p: DoubleArray, pred: (Double) -> Boolean) = bayes.grid.indices.filter { pred(bayes.grid[it]) }.sumOf { p[it] }

    // ---- prior -------------------------------------------------------------------------------------

    @Test fun `adult prior reproduces India prevalence`() {
        val p = bayes.prior(45)
        assertEquals(1.0, p.sum(), 1e-9)
        assertEquals(0.277, mass(p) { it <= -0.5 + 1e-9 }, 1e-9)
        assertEquals(0.229, mass(p) { it >= 0.5 - 1e-9 }, 1e-9)
        assertEquals(1 - 0.277 - 0.229, mass(p) { it > -0.5 && it < 0.5 }, 1e-9)
    }

    @Test fun `age picks the prior row, unknown age uses the assumed age`() {
        assertEquals(0, bayes.agePrior(22).minAge)
        assertEquals(30, bayes.agePrior(60).minAge)
        assertEquals(bayes.agePrior(rules.bayes.assumedAgeWhenUnknown), bayes.agePrior(null))
        // Under-30 row: more myopia
        assertTrue(mass(bayes.prior(22)) { it <= -0.5 } > mass(bayes.prior(45)) { it <= -0.5 })
    }

    // ---- measured SE -------------------------------------------------------------------------------

    @Test fun `band is 1 D wide, holds the measurement, probability matches the posterior`() {
        val r = bayes.fromMeasurement(-3.0, 45, Confidence.HIGH)
        assertEquals(1.0, r.band.highD - r.band.lowD, 1e-9)
        assertTrue(-3.0 in r.band.lowD..r.band.highD)
        assertEquals(1.0, r.posterior.sum(), 1e-9)
        val step = rules.bayes.gridStepD
        val inside = mass(r.posterior) { it > r.band.lowD + 1e-9 && it < r.band.highD - 1e-9 } +
            0.5 * mass(r.posterior) { kotlin.math.abs(it - r.band.lowD) < 1e-9 || kotlin.math.abs(it - r.band.highD) < 1e-9 }
        assertEquals(inside, r.probability, 1e-9)
        assertEquals(r.band.midD, r.startingLensD, 1e-9)
        assertEquals(0.0, (r.startingLensD / step) % 1.0, 1e-9)
    }

    @Test fun `prior shrinks a mild measurement towards emmetropia`() {
        val r = bayes.fromMeasurement(-2.0, 45, Confidence.MEDIUM)
        assertTrue(r.posteriorMeanD > -2.0 && r.posteriorMeanD < 0.0)
    }

    @Test fun `noisier measurement - lower band probability and confidence`() {
        val high = bayes.fromMeasurement(-2.5, 45, Confidence.HIGH)
        val low = bayes.fromMeasurement(-2.5, 45, Confidence.LOW)
        assertTrue(high.probability > low.probability)
        assertEquals(Confidence.HIGH, high.confidence)
        assertEquals(Confidence.LOW, low.confidence)
        assertEquals(rules.bayes.sigmaHighD, high.sigmaD!!, 1e-9)
    }

    @Test fun `flat prior centres the band on the measurement`() {
        val flat = BayesianBand(rules.bayes.copy(priorWeight = 0.0), rules.optics)
        val r = flat.fromMeasurement(-2.0, 45, Confidence.MEDIUM)
        assertEquals(PowerBand(-2.5, -1.5), r.band)
        assertEquals(-2.0, r.mapD, 1e-9)
    }

    @Test fun `confidence thresholds come from rules json`() {
        val strict = BayesianBand(rules.bayes.copy(bandProbabilityHigh = 0.99, bandProbabilityMedium = 0.98), rules.optics)
        assertEquals(Confidence.LOW, strict.fromMeasurement(-3.0, 45, Confidence.HIGH).confidence)
    }

    @Test fun `sigma from repeats widens only when the repeats disagree`() {
        assertEquals(rules.bayes.sigmaHighD, bayes.sigmaFromRepeats(listOf(-2.0, -2.1), Confidence.HIGH), 1e-9)
        assertEquals(1.4142, bayes.sigmaFromRepeats(listOf(-1.0, -3.0), Confidence.HIGH), 1e-3)
        assertEquals(rules.bayes.sigmaMediumD, bayes.sigmaFromRepeats(listOf(-2.0), Confidence.MEDIUM), 1e-9)
    }

    // ---- censored: no blur within arm's length ------------------------------------------------------

    @Test fun `no blur within arms length - myopia beyond the limit becomes unlikely`() {
        val r = bayes.fromNoBlurAtArmsLength(45)
        val limit = -1.0 / rules.optics.armsLengthM
        assertTrue(mass(r.posterior) { it < limit - 1.0 } < 0.01)
        assertTrue(r.band.highD >= 0.0)
        assertTrue(r.mapD >= -0.5)
    }

    @Test fun `normal cdf`() {
        assertEquals(0.5, BayesianBand.normalCdf(0.0), 1e-7)
        assertEquals(0.97725, BayesianBand.normalCdf(2.0), 1e-4)
        assertEquals(0.02275, BayesianBand.normalCdf(-2.0), 1e-4)
    }

    // ---- RulesEngine integration --------------------------------------------------------------------

    private val engine = RulesEngine(rules, TestAssets.anthro)
    private val adult = InterviewAnswers(age = 45, distanceBlur = true, nearBlur = false)

    @Test fun `rules engine carries the band probability for measured eyes`() {
        val m = MeasuredEye(seD = -3.1, confidence = Confidence.HIGH)
        val eye = engine.assess(adult, tests = VisionTests(right = m)).advice!!.eyes.single { it.eye == Eye.RIGHT }
        val direct = bayes.fromMeasurement(-3.1, 45, Confidence.HIGH)
        assertEquals(direct.band, eye.band.value)
        assertEquals(direct.probability, eye.bandProbability!!, 1e-12)
        assertEquals(direct.startingLensD, eye.startingTrialLens.value.sph, 1e-9)
        assertEquals(direct.startingLensD, eye.thicknessPowerD, 1e-9)
    }

    @Test fun `measurement confidence caps the band confidence`() {
        val m = MeasuredEye(seD = -3.0, confidence = Confidence.MEDIUM)
        val eye = engine.assess(adult, tests = VisionTests(right = m)).advice!!.eyes.single()
        assertTrue(eye.band.confidence != Confidence.HIGH)
    }

    @Test fun `old numbers have no band probability`() {
        val a = adult.copy(glassesAgeYears = 1, knownRx = KnownRx(EyeRx(-2.0)))
        assertNull(engine.assess(a).advice!!.eyes.single().bandProbability)
    }

    @Test fun `bayes disabled in rules json restores the fixed band`() {
        val off = RulesConfig.parse(TestAssets.text("rules.json").replace(Regex("\"enabled\": true(,\\s*\"gridMinD\")"), "\"enabled\": false$1"))
        val m = MeasuredEye(seD = -1.4, confidence = Confidence.HIGH)
        val eye = RulesEngine(off, TestAssets.anthro).assess(adult, tests = VisionTests(right = m)).advice!!.eyes.single()
        assertEquals(PowerBand(-2.0, -1.0), eye.band.value)
        assertEquals(-1.5, eye.startingTrialLens.value.sph, 1e-9)
        assertNull(eye.bandProbability)
    }

    @Test fun `a measurement far outside the grid does not crash`() {
        val m = MeasuredEye(seD = -25.0, confidence = Confidence.MEDIUM)
        val eye = engine.assess(adult, tests = VisionTests(right = m)).advice!!.eyes.single()
        assertTrue(eye.band.value.lowD >= rules.bayes.gridMinD)
    }

    @Test fun `missing bayes section or key fails loudly`() {
        assertThrows(IllegalArgumentException::class.java) { BayesConfig() }
        val broken = TestAssets.text("rules.json").replace("\"bandWidthD\"", "\"bandWidthDD\"")
        assertThrows(Exception::class.java) { RulesConfig.parse(broken) }
    }
}
