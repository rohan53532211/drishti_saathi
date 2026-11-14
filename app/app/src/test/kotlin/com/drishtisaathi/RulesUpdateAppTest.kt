package com.drishtisaathi

import com.drishtisaathi.calibration.Calibration
import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.DrishtiEngine
import com.drishtisaathi.engine.Estimate
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.MeasuredEye
import com.drishtisaathi.engine.MeasuredFace
import com.drishtisaathi.engine.ReasonCode
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.Source
import com.drishtisaathi.engine.VisionTests
import com.drishtisaathi.engine.face.ScaleSource
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.interview.ChoiceContext
import com.drishtisaathi.interview.Questions
import com.drishtisaathi.pdf.Block
import com.drishtisaathi.pdf.PdfContent
import com.drishtisaathi.ui.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** App wording for rules_update.md rules 5 (uncertain band), 6 (PD without the card) and 7 (ready-reader). */
class RulesUpdateAppTest {
    private val assets = File("src/main/assets")
    private fun asset(n: String) = File(assets, n).readText()
    private val rules = RulesConfig.parse(asset("rules.json"))
    private val engine = DrishtiEngine.fromJson(asset("rules.json"), asset("anthro_priors.json"), asset("frames.json"))
    private val ctx = ChoiceContext(1500, 4000)

    private fun face(src: ScaleSource?) = MeasuredFace(
        pdMm = Estimate(63.4, Confidence.HIGH, Source.MEASURED),
        faceWidthMm = Estimate(140.0, Confidence.HIGH, Source.MEASURED),
        bridgeWidthMm = Estimate(18.0, Confidence.HIGH, Source.MEASURED),
        framesUsed = 30,
        scaleSource = src,
    )

    /** Same catalogue and priors, stricter show threshold: makes a typical band "uncertain" for the rule 5 test. */
    private val strictEngine = com.drishtisaathi.engine.DrishtiEngine(
        rules.copy(bayes = rules.bayes.copy(minBandProbabilityToShow = 0.70, bandProbabilityHigh = 0.80)),
        com.drishtisaathi.engine.AnthroPriors.parse(asset("anthro_priors.json")),
        com.drishtisaathi.engine.FrameCatalog.parse(asset("frames.json")),
    )

    private fun state(
        a: InterviewAnswers, tests: VisionTests = VisionTests(), face: MeasuredFace? = null,
        eng: com.drishtisaathi.engine.DrishtiEngine = engine,
    ) = UiState(
        answers = a,
        answered = Questions.visible(a).map { it.id }.toSet(),
        result = eng.evaluate(a, tests = tests, face = face),
        sessionId = 3,
        createdAt = 0,
    )

    private fun text(blocks: List<Block>) = blocks.joinToString("\n") {
        when (it) {
            is Block.Title -> it.text; is Block.Heading -> it.text; is Block.Para -> it.text; is Block.Bullet -> it.text; is Block.FrameRow -> it.name + " " + it.line; is Block.Rings -> it.rings.joinToString(" ") { r -> r.title + " " + r.label }; Block.Rule -> "---"
        }
    }

    private val adult = InterviewAnswers(age = 35, distanceBlur = true, nearBlur = false, redFlagFlashes = false, redFlagOther = false)

    // ---- rule 6 ---------------------------------------------------------------------------------

    @Test fun `rule 6 - PD shows plus-minus 3 mm without the card and plus-minus 1 mm with it`() {
        val noCard = engine.evaluate(adult, face = face(ScaleSource.IRIS_PRIOR)).assessment
        assertEquals("about 63 mm (±3 mm)", Fmt.pd(noCard, Lang.EN))
        assertEquals("लगभग 63 mm (±3 mm)", Fmt.pd(noCard, Lang.HI))
        val card = engine.evaluate(adult, face = face(ScaleSource.CARD)).assessment
        assertEquals("about 63 mm (±1 mm)", Fmt.pd(card, Lang.EN))
        val avg = engine.evaluate(adult).assessment
        assertNull(avg.pdUncertaintyMm)
        assertEquals(Fmt.mm(avg.pdMm.value), Fmt.pd(avg, Lang.EN))
    }

    @Test fun `rule 6 - fit bullets are neutral without the card, in the screen text and both PDFs`() {
        val fitCodes = setOf(ReasonCode.FITS_FACE, ReasonCode.NARROW_FOR_FACE, ReasonCode.WIDE_FOR_FACE,
            ReasonCode.BRIDGE_FITS, ReasonCode.BRIDGE_MISMATCH)
        val s = state(adult, face = face(ScaleSource.IRIS_PRIOR))
        val bullets = s.result!!.frames.flatMap { rf -> rf.reasons.filter { it.code in fitCodes }.map { Fmt.reason(it, Lang.EN) } }
        assertTrue(bullets.isNotEmpty())
        bullets.forEach { b ->
            assertFalse(b, Regex("""\b(fits|suits|narrow|wide|may not sit)\b""", RegexOption.IGNORE_CASE).containsMatchIn(b))
            assertTrue(b, "without the card" in b)
        }
        val shop = text(PdfContent.shopCopy(s, rules, ctx, Lang.EN))
        assertFalse(shop.contains("Fits your face width"))
        assertTrue(shop.contains("about 63 mm (±3 mm)"))
        val cust = text(PdfContent.customerCopy(s, rules))
        assertFalse(cust.contains("चेहरे की चौड़ाई पर ठीक"))
        assertTrue(cust.contains("कार्ड के बिना"))

        // with the card the measured wording comes back
        val calibrated = state(adult, face = face(ScaleSource.CARD))
        assertTrue(text(PdfContent.shopCopy(calibrated, rules, ctx, Lang.EN)).contains("±1 mm"))
        assertTrue(calibrated.result!!.frames.flatMap { it.reasons }.none { it.uncalibrated })
    }

    @Test fun `rule 6 - population average keeps its own not-measured wording`() {
        val s = state(adult)
        val fit = s.result!!.frames.first().reasons.first { it.code == ReasonCode.FITS_FACE || it.code == ReasonCode.NARROW_FOR_FACE || it.code == ReasonCode.WIDE_FOR_FACE }
        assertTrue(Fmt.reason(fit, Lang.EN).contains("not measured"))
    }

    // ---- rule 7 ---------------------------------------------------------------------------------

    @Test fun `rule 7 - reading add asks for a ready-reader check in the shop and customer copies`() {
        val presbyope = InterviewAnswers(age = 52, distanceBlur = false, nearBlur = true, redFlagFlashes = false, redFlagOther = false)
        val s = state(presbyope)
        assertTrue(s.result!!.assessment.advice!!.confirmAddWithReadyReader)
        for (l in Lang.entries) assertTrue(text(PdfContent.shopCopy(s, rules, ctx, l)).contains(S.confirmReadyReader[l]))
        val cust = text(PdfContent.customerCopy(s, rules))
        assertTrue(cust.contains(S.customerReadyReader.hi))
        assertFalse(cust.contains(" D "))

        val noAdd = state(adult)
        assertFalse(text(PdfContent.shopCopy(noAdd, rules, ctx, Lang.EN)).contains(S.confirmReadyReader.en))
        assertFalse(text(PdfContent.customerCopy(noAdd, rules)).contains(S.customerReadyReader.hi))
    }

    // ---- rule 5 ---------------------------------------------------------------------------------

    @Test fun `rule 5 - band below the show threshold is not printed as an estimated band`() {
        val m = MeasuredEye(seD = -3.0, confidence = Confidence.HIGH)
        val s = state(adult, tests = VisionTests(right = m, left = m), eng = strictEngine)
        val eyes = s.result!!.assessment.advice!!.eyes
        assertTrue(eyes.all { it.bandUncertain })
        val shop = text(PdfContent.shopCopy(s, rules, ctx, Lang.EN))
        assertTrue(shop.contains(S.bandUncertain.en))
        assertFalse(shop.contains(S.estimatedBand.en + ":"))
        // the starting trial lens (band midpoint) is still given
        assertTrue(shop.contains(Fmt.trialLens(eyes.first().startingTrialLens.value)))
        eyes.forEach { assertEquals(S.bandUncertain.en, Fmt.bandOrUncertain(it, Lang.EN)) }
    }

    @Test fun `population-average face never gets a fits claim`() {
        // No camera, no staff entry: face width and PD are population averages.
        val s = state(adult.copy(knownRx = com.drishtisaathi.engine.KnownRx(com.drishtisaathi.engine.EyeRx(-2.0), com.drishtisaathi.engine.EyeRx(-2.0)), glassesAgeYears = 1))
        val frames = s.result!!.frames
        assertTrue(frames.isNotEmpty())
        for (l in Lang.entries) frames.forEach { rf ->
            val fit = rf.reasons.first { it.code.name.endsWith("FACE") }
            assertTrue(fit.assumed)
            val line = Fmt.reason(fit, l)
            assertFalse(line, line.contains("fit", ignoreCase = true))
            assertFalse(line, line.contains("ठीक"))
        }
        assertTrue(Fmt.reason(frames.first().reasons.first { it.code.name.endsWith("FACE") }, Lang.EN).contains("average face about"))
    }

    @Test fun `rule 5 - with the shipped 1_5 D band a typical band is shown`() {
        val m = MeasuredEye(seD = -3.0, confidence = Confidence.HIGH)
        val s = state(adult, tests = VisionTests(right = m, left = m))
        val eyes = s.result!!.assessment.advice!!.eyes
        assertTrue(eyes.none { it.bandUncertain })
        eyes.forEach { assertEquals(1.5, it.band.value.highD - it.band.value.lowD, 1e-9) }
        val shop = text(PdfContent.shopCopy(s, rules, ctx, Lang.EN))
        assertTrue(shop.contains(S.estimatedBand.en))
        assertFalse(shop.contains(S.bandUncertain.en))
    }

    // ---- rule 2 ---------------------------------------------------------------------------------

    @Test fun `rule 2 - calibration carries a card PD and the customer reset clears it`() {
        val repo = com.drishtisaathi.calibration.PersistentCalibrationRepository(MapStore())
        repo.update { it.copy(irisDiameterMm = 11.8, pdMm = 62.5) }
        assertEquals(62.5, repo.calibration.value.pdMm!!, 0.0)
        repo.clearCustomer()
        assertNull(repo.calibration.value.pdMm)
        assertNull(Calibration().pdMm)
    }

    private class MapStore : com.drishtisaathi.calibration.KeyValueStore {
        private val m = mutableMapOf<String, String?>()
        override fun getString(key: String): String? = m[key]
        override fun putString(key: String, value: String?) { m[key] = value }
    }
}
