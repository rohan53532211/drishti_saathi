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
import org.junit.Assert.assertFalse
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

    @Test fun `band width comes from rules json, holds the measurement, probability matches the posterior`() {
        val r = bayes.fromMeasurement(-3.0, 45)
        assertEquals(1.5, rules.bayes.bandWidthD, 1e-9)
        assertEquals(rules.bayes.bandWidthD, r.band.highD - r.band.lowD, 1e-9)
        assertTrue(-3.0 in r.band.lowD..r.band.highD)
        assertEquals(1.0, r.posterior.sum(), 1e-9)
        val step = rules.bayes.gridStepD
        val inside = mass(r.posterior) { it > r.band.lowD + 1e-9 && it < r.band.highD - 1e-9 } +
            0.5 * mass(r.posterior) { kotlin.math.abs(it - r.band.lowD) < 1e-9 || kotlin.math.abs(it - r.band.highD) < 1e-9 }
        assertEquals(inside, r.probability, 1e-9)
        assertEquals(r.band.midD, r.startingLensD, 1e-9)
        assertEquals(0.0, (r.startingLensD / step) % 1.0, 1e-9)
    }

    @Test fun `likelihood sigma is 0_80 D from rules json`() {
        assertEquals(0.80, rules.bayes.likelihoodSigmaD, 1e-9)
        assertEquals(0.80, bayes.fromMeasurement(-2.0, 45).sigmaD!!, 1e-9)
        // Myopia magnitude prior: exponential with mean 1.5 D → scale 1.0 D beyond the −0.50 D boundary
        assertEquals(1.0, rules.bayes.myopiaScaleD, 1e-9)
    }

    @Test fun `prior shrinks a mild measurement towards emmetropia`() {
        val r = bayes.fromMeasurement(-2.0, 45)
        assertTrue(r.posteriorMeanD > -2.0 && r.posteriorMeanD < 0.0)
    }

    @Test fun `rule 5 - sigma 0_8 with a 1_5 D band clears 0_60 and is shown`() {
        // Typical measurements at ages 35 and 55 (user decision: widen the band, keep sigma 0.8 and the 0.60 cut-off).
        for (age in listOf(35, 55)) for (se in listOf(-1.4, -3.0, -5.0)) {
            val r = bayes.fromMeasurement(se, age)
            assertTrue("P=${r.probability} for $se at $age", r.probability in 0.60..0.70)
            assertTrue(r.shown)
            assertEquals(Confidence.MEDIUM, r.confidence)
        }
        // Why the band was widened: a 1 D band cannot reach 0.60 with σ 0.8 even under a flat prior (max ≈ 0.47).
        val oneD = BayesianBand(rules.bayes.copy(priorWeight = 0.0, bandWidthD = 1.0), rules.optics, rules.farPoint.selfHeldReachM)
        val p = oneD.fromMeasurement(-3.0, 45)
        assertTrue(p.probability < 0.48)
        assertFalse(p.shown)
    }

    @Test fun `a tighter measurement clears the show threshold`() {
        val r = bayes.fromMeasurement(-3.0, 45, sigmaD = 0.4)
        assertTrue(r.probability >= rules.bayes.minBandProbabilityToShow)
        assertTrue(r.shown)
        assertTrue(r.confidence != Confidence.LOW)
    }

    @Test fun `flat prior centres the band on the measurement`() {
        val flat = BayesianBand(rules.bayes.copy(priorWeight = 0.0), rules.optics, rules.farPoint.selfHeldReachM)
        val r = flat.fromMeasurement(-2.0, 45)
        assertEquals(PowerBand(-2.75, -1.25), r.band)
        assertEquals(-2.0, r.mapD, 1e-9)
    }

    @Test fun `confidence thresholds come from rules json`() {
        val lax = BayesianBand(rules.bayes.copy(bandProbabilityHigh = 0.45, minBandProbabilityToShow = 0.40), rules.optics, rules.farPoint.selfHeldReachM)
        val r = lax.fromMeasurement(-3.0, 45)
        assertEquals(Confidence.HIGH, r.confidence)
        assertTrue(r.shown)
    }

    @Test fun `sigma from repeats widens only when the repeats disagree`() {
        assertEquals(0.80, bayes.sigmaFromRepeats(listOf(-2.0, -2.1)), 1e-9)
        assertEquals(1.4142, bayes.sigmaFromRepeats(listOf(-1.0, -3.0)), 1e-3)
        assertEquals(0.80, bayes.sigmaFromRepeats(listOf(-2.0)), 1e-9)
    }

    // ---- censored: no blur within arm's length ------------------------------------------------------

    @Test fun `no blur within arms length - myopia beyond the limit becomes unlikely`() {
        val r = bayes.fromNoBlurAtArmsLength(45)
        val limit = -1.0 / rules.farPoint.selfHeldReachM
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
        val direct = bayes.fromMeasurement(-3.1, 45)
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

    @Test fun `uncertain band keeps the starting trial lens, flags the band and tells staff to refine by hand`() {
        // With the shipped 1.5 D band typical bands clear 0.60; a stricter cut-off makes this one uncertain.
        val strict = rules.copy(bayes = rules.bayes.copy(minBandProbabilityToShow = 0.70, bandProbabilityHigh = 0.80))
        val m = MeasuredEye(seD = -3.0, confidence = Confidence.HIGH)
        val r = RulesEngine(strict, TestAssets.anthro).assess(adult, tests = VisionTests(right = m))
        val eye = r.advice!!.eyes.single()
        assertTrue(eye.bandUncertain)
        assertEquals(Confidence.LOW, eye.band.confidence)
        assertEquals(eye.band.value.midD, eye.startingTrialLens.value.sph, 1e-9)
        assertTrue(com.drishtisaathi.engine.Note.BAND_UNCERTAIN_REFINE_BY_HAND in r.notes)
    }

    @Test fun `old numbers have no band probability`() {
        val a = adult.copy(glassesAgeYears = 1, knownRx = KnownRx(EyeRx(-2.0)))
        assertNull(engine.assess(a).advice!!.eyes.single().bandProbability)
    }

    @Test fun `bayes disabled in rules json restores the fixed band`() {
        val off = RulesConfig.parse(TestAssets.text("rules.json").replace(Regex("\"enabled\": true(,\\s*\"gridMinD\")"), "\"enabled\": false$1"))
        val m = MeasuredEye(seD = -1.4, confidence = Confidence.HIGH)
        val eye = RulesEngine(off, TestAssets.anthro).assess(adult, tests = VisionTests(right = m)).advice!!.eyes.single()
        // Fixed band of bayes.bandWidthD (1.5 D) around the rounded SE (−1.50)
        assertEquals(PowerBand(-2.25, -0.75), eye.band.value)
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
