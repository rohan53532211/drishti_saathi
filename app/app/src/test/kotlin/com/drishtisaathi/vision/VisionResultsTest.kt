package com.drishtisaathi.vision

import com.drishtisaathi.calibration.Calibration
import com.drishtisaathi.engine.AnthroPriors
import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.ReferralLevel
import com.drishtisaathi.engine.ReferralReason
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.RulesEngine
import com.drishtisaathi.engine.Source
import com.drishtisaathi.engine.config.VisionTestConfig
import com.drishtisaathi.engine.vision.VisionMath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

private val rulesText = File("src/main/assets/rules.json").readText()
private val rules = RulesConfig.parse(rulesText)
private val cfg = rules.visionTests
private val vm = VisionMath(rules)

class VisionConfigTest {
    @Test fun `rules json visionTests section lists every config key`() {
        val section = Json.parseToJsonElement(rulesText).jsonObject["visionTests"]!!.jsonObject
        val d = VisionTestConfig.serializer().descriptor
        assertEquals((0 until d.elementsCount).map { d.getElementName(it) }.toSet(), section.keys)
    }

    @Test fun `parsed config matches the brief`() {
        assertEquals(2, cfg.repeats)
        assertEquals("#0000FF", cfg.farPointBlueRgb)
        assertEquals(100.0, cfg.clockDialBeyondFarPointMm, 0.0)
        assertTrue(cfg.nearAcuityMinMm < 400.0 && 400.0 < cfg.nearAcuityMaxMm)
    }

    @Test fun `unknown or invalid visionTests keys are rejected`() {
        assertTrue(runCatching { RulesConfig.parse(rulesText.replace("\"farPointMinMm\"", "\"farPointMinimumMm\"")) }.isFailure)
        assertTrue(runCatching { RulesConfig.parse(rulesText.replace("\"#0000FF\"", "\"blue\"")) }.isFailure)
    }
}

class SizingTest {
    @Test fun `card scale wins over DisplayMetrics`() {
        val s = Sizing.screenScale(Calibration(screenMmPerPx = 0.0625), 400f, 420)
        assertEquals(ScreenScale(0.0625, true), s)
    }

    @Test fun `fallback uses xdpi, then densityDpi when xdpi is implausible`() {
        assertEquals(25.4 / 400.0, Sizing.screenScale(Calibration(), 400f, 420).mmPerPx, 1e-12)
        assertFalse(Sizing.screenScale(Calibration(), 400f, 420).fromCard)
        assertEquals(25.4 / 420.0, Sizing.screenScale(Calibration(), 1f, 420).mmPerPx, 1e-12)
        assertEquals(25.4 / 420.0, Sizing.screenScale(Calibration(), 0f, 420).mmPerPx, 1e-12)
    }

    @Test fun `20-20 letter at 400 mm is about 0_58 mm, true physical size in pixels`() {
        val scale = ScreenScale(0.05, true)
        val px = Sizing.letterHeightPx(vm, 400.0, scale, 0.0)
        assertEquals(0.5818, px * 0.05, 1e-3)
        // logMAR 0.3 doubles it; live distance doubling doubles it too (constant angle)
        assertEquals(2.0 * px, Sizing.letterHeightPx(vm, 400.0, scale, 0.30103), 1e-6)
        assertEquals(2.0 * px, Sizing.letterHeightPx(vm, 800.0, scale, 0.0), 1e-6)
    }

    @Test fun `arcmin size and Amsler square`() {
        val scale = ScreenScale(0.1, true)
        assertEquals(Sizing.letterHeightPx(vm, 500.0, scale, 0.0), Sizing.arcminPx(500.0, 5.0, scale), 1e-9)
        assertEquals(5.236, Sizing.arcminPx(300.0, 60.0, scale) * 0.1, 1e-3) // 1° at 30 cm ≈ 5.2 mm
        assertEquals(Sizing.Drawn(100.0, true), Sizing.fit(150.0, 100.0))
    }

    @Test fun `distance gate`() {
        assertEquals(DistanceStatus.NO_READING, DistanceGate.check(null, 300.0, 500.0))
        assertEquals(DistanceStatus.NO_FACE, DistanceGate.check(DistanceReading(400.0, Confidence.HIGH, faceVisible = false), 300.0, 500.0))
        assertEquals(DistanceStatus.TOO_CLOSE, DistanceGate.check(DistanceReading(250.0, Confidence.HIGH), 300.0, 500.0))
        assertEquals(DistanceStatus.TOO_FAR, DistanceGate.check(DistanceReading(550.0, Confidence.HIGH), 300.0, 500.0))
        assertEquals(DistanceStatus.OK, DistanceGate.check(DistanceReading(50.0, Confidence.HIGH), null, null))
    }
}

class FarPointTest {
    private fun r(mm: Double, c: Confidence = Confidence.HIGH, face: Boolean = true) = DistanceReading(mm, c, face)

    @Test fun `consistent blue and white give the mean SE with high confidence`() {
        val res = FarPointAnalysis.analyse(TestEye.RIGHT, listOf(400.0, 400.0), listOf(550.0, 540.0), rules)!!
        assertEquals(-1.83, res.seBlueD!!, 1e-9)
        assertEquals(true, res.consistent)
        assertEquals((res.seBlueD!! + res.seWhiteD!!) / 2, res.seD!!, 1e-9)
        assertEquals(Confidence.HIGH, res.confidence)
        assertFalse(res.noMyopiaAtArmsLength)
        assertEquals(1000.0 / -res.seD!!, res.farPointMm!!, 1e-9)
    }

    @Test fun `blue and white disagreeing gives blue SE and LOW`() {
        val res = FarPointAnalysis.analyse(TestEye.RIGHT, listOf(400.0, 400.0), listOf(300.0, 300.0), rules)!!
        assertEquals(false, res.consistent)
        assertEquals(-1.83, res.seD!!, 1e-9)
        assertEquals(Confidence.LOW, res.confidence)
    }

    @Test fun `repeat spread sets the confidence`() {
        // −2.5+0.67 = −1.83 vs −1/0.33+0.67 = −2.36: spread 0.53 D → LOW
        assertEquals(Confidence.LOW, FarPointAnalysis.analyse(TestEye.LEFT, listOf(400.0, 330.0), emptyList(), rules)!!.confidence)
        // 400 vs 370 mm: spread 0.20 D → HIGH; 400 vs 350: 0.36 D → MEDIUM
        assertEquals(Confidence.HIGH, FarPointAnalysis.analyse(TestEye.LEFT, listOf(400.0, 370.0), emptyList(), rules)!!.confidence)
        assertEquals(Confidence.MEDIUM, FarPointAnalysis.analyse(TestEye.LEFT, listOf(400.0, 350.0), emptyList(), rules)!!.confidence)
    }

    @Test fun `white beyond 0_8 m or unblurred skips the self-check`() {
        val res = FarPointAnalysis.analyse(TestEye.RIGHT, listOf(550.0, 550.0), listOf(null, null), rules)!!
        assertNull(res.consistent)
        assertEquals(-1.0 / 0.55 + 0.67, res.seD!!, 1e-9)
        assertNull(res.seWhiteD)
    }

    @Test fun `no blur within arm's length is censored, never invented`() {
        val res = FarPointAnalysis.analyse(TestEye.RIGHT, listOf(null, 720.0), listOf(null, null), rules)!!
        assertTrue(res.noMyopiaAtArmsLength)
        assertNull(res.seD)
        assertEquals(Confidence.HIGH, res.confidence)
        assertEquals(true, res.consistent)
        // white blurring while blue never did is contradictory
        assertEquals(Confidence.LOW, FarPointAnalysis.analyse(TestEye.RIGHT, listOf(null, null), listOf(500.0, null), rules)!!.confidence)
    }

    @Test fun `one repeat clear at arm's length is censored at arm's length and lowers confidence`() {
        val res = FarPointAnalysis.analyse(TestEye.RIGHT, listOf(400.0, null), emptyList(), rules)!!
        assertFalse(res.noMyopiaAtArmsLength)
        // censored at the single reach setting farPoint.selfHeldReachM (0.60 m)
        assertEquals(((-1.0 / 0.6 + 0.67) + (-1.83)) / 2, res.seD!!, 1e-9)
        assertEquals(Confidence.LOW, res.confidence)
    }

    @Test fun `distance confidence caps the result`() {
        val res = FarPointAnalysis.analyse(TestEye.RIGHT, listOf(400.0, 400.0), listOf(550.0, 540.0), rules, Confidence.MEDIUM)!!
        assertEquals(Confidence.MEDIUM, res.confidence)
    }

    @Test fun `clock dial target is far point plus offset, capped`() {
        val res = FarPointAnalysis.analyse(TestEye.RIGHT, listOf(300.0, 300.0), emptyList(), rules)!!
        assertEquals(res.farPointMm!! + 100.0, FarPointAnalysis.clockDialTargetMm(res, cfg), 1e-9)
        assertEquals(cfg.clockDialNoFarPointMm, FarPointAnalysis.clockDialTargetMm(null, cfg), 0.0)
        val weak = FarPointAnalysis.analyse(TestEye.RIGHT, listOf(650.0, 650.0), emptyList(), rules)!!
        assertEquals(cfg.clockDialNoFarPointMm, FarPointAnalysis.clockDialTargetMm(weak, cfg), 0.0)
    }

    @Test fun `procedure needs a close start before a blur tap, blue twice then white twice`() {
        var p = FarPointProcedure.start(rules)
        assertEquals(FarColour.BLUE, p.colour)
        assertFalse(p.canMarkBlur(r(400.0), cfg))
        p = p.onReading(r(140.0), cfg)
        assertTrue(p.seenClose)
        assertFalse(p.canMarkBlur(r(90.0), cfg)) // closer than farPointMinMm
        assertFalse(p.canMarkNoBlur(r(700.0), cfg)) // self-held: "still clear" never counts (rule 1)
        assertFalse(p.canMarkBlur(r(400.0, face = false), cfg))
        p = p.markBlur(r(400.0, Confidence.MEDIUM), cfg)
        assertFalse(p.seenClose)
        assertEquals(1, p.repeatIndex)
        p = p.onReading(r(120.0), cfg).markBlur(r(410.0), cfg)
        assertEquals(FarColour.WHITE, p.colour)
        p = p.onReading(r(120.0), cfg).markBlur(r(550.0), cfg)
        p = p.onReading(r(120.0), cfg).markBlur(r(540.0), cfg)
        assertTrue(p.isDone)
        assertEquals(listOf(400.0, 410.0), p.blueMm)
        assertEquals(listOf(550.0, 540.0), p.whiteMm)
        assertEquals(Confidence.MEDIUM, p.worstConfidence)
        // consistent blue/white → HIGH, capped by the MEDIUM distance reading
        assertEquals(Confidence.MEDIUM, p.result(TestEye.RIGHT, rules, capByDistance = true)!!.confidence)
        assertEquals(Confidence.HIGH, p.result(TestEye.RIGHT, rules, capByDistance = false)!!.confidence)
        assertEquals(FarPointAnalysis.analyse(TestEye.RIGHT, p.blueMm, p.whiteMm, rules), p.result(TestEye.RIGHT, rules, false))
    }

    @Test fun `rule 1 - still clear at 60 cm offers staff-held mode, blur valid up to 2 m, no blur only beyond`() {
        var p = FarPointProcedure.start(rules).onReading(r(120.0), cfg)
        assertFalse(p.canOfferStaffHeld(r(550.0)))
        assertTrue(p.canOfferStaffHeld(r(rules.farPoint.selfHeldReachM * 1000.0)))
        p = p.startStaffHeld()
        assertTrue(p.staffHeld)
        assertFalse(p.seenClose) // staff start close again
        assertFalse(p.canOfferStaffHeld(r(700.0)))
        p = p.onReading(r(120.0), cfg)
        assertTrue(p.canMarkBlur(r(1500.0), cfg))
        assertTrue(p.canMarkBlur(r(2000.0), cfg))
        assertFalse(p.canMarkBlur(r(2100.0), cfg))
        assertFalse(p.canMarkNoBlur(r(1900.0), cfg))
        assertTrue(p.canMarkNoBlur(r(2000.0), cfg))
        p = p.markBlur(r(1500.0), cfg)
        p = p.onReading(r(120.0), cfg).markBlur(r(1400.0), cfg)
        assertTrue(p.staffHeld) // stays for this eye
        p = p.onReading(r(120.0), cfg).markNoBlur(r(2100.0), cfg)
        p = p.onReading(r(120.0), cfg).markNoBlur(r(2200.0), cfg)
        assertTrue(p.isDone)
        val res = p.result(TestEye.LEFT, rules, capByDistance = false)!!
        assertTrue(res.staffHeld)
        assertFalse(res.noMyopiaAtArmsLength) // blue blurred within 2 m
        // blue at 1.5 / 1.4 m: −1/d + LCA, median → about −0.03 D, a valid measurement past the old 0.7 m limit
        assertEquals(-1.0 / 1.45 + rules.optics.lcaBlueOffsetD, res.seBlueD!!, 0.01)
        assertEquals(listOf(null, null), res.whiteMm)
    }

    @Test fun `rule 4 - repeats more than 0_5 D apart trigger a third reading and the median`() {
        var p = FarPointProcedure.start(rules)
        p = p.onReading(r(120.0), cfg).markBlur(r(400.0), cfg) // −1.83 D after LCA
        p = p.onReading(r(120.0), cfg).markBlur(r(600.0), cfg) // −1.00 D: 0.83 D apart
        assertEquals(FarColour.BLUE, p.colour)
        assertEquals(com.drishtisaathi.engine.vision.FarPointProtocol.Why.THIRD_READING, p.why)
        assertEquals(2, p.repeatIndex)
        p = p.onReading(r(120.0), cfg).markBlur(r(420.0), cfg)
        assertEquals(FarColour.WHITE, p.colour)
        p = p.onReading(r(120.0), cfg).markBlur(r(560.0), cfg)
        p = p.onReading(r(120.0), cfg).markBlur(r(560.0), cfg)
        val res = p.result(TestEye.RIGHT, rules, capByDistance = false)!!
        assertTrue(res.thirdReading)
        assertEquals(-1000.0 / 420.0 + rules.optics.lcaBlueOffsetD, res.seBlueD!!, 1e-9) // median of the three
    }

    @Test fun `rule 3 - blue and white disagreeing retakes once, then LOW`() {
        fun round(p0: FarPointProcedure): FarPointProcedure {
            var p = p0
            listOf(400.0, 400.0, 300.0, 300.0).forEach { p = p.onReading(r(120.0), cfg).markBlur(r(it), cfg) }
            return p
        }
        var p = round(FarPointProcedure.start(rules))
        assertFalse(p.isDone)
        assertEquals(FarColour.BLUE, p.colour)
        assertEquals(com.drishtisaathi.engine.vision.FarPointProtocol.Why.RETAKE, p.why)
        assertTrue(p.blueMm.isEmpty())
        p = round(p)
        assertTrue(p.isDone)
        val res = p.result(TestEye.RIGHT, rules, capByDistance = false)!!
        assertEquals(1, res.retakes)
        assertEquals(false, res.consistent)
        assertEquals(Confidence.LOW, res.confidence)
    }
}

class ClockDialTest {
    @Test fun `rule of 30 axis`() {
        assertEquals(180, ClockDialAnalysis.axisForMeridian(0))
        assertEquals(30, ClockDialAnalysis.axisForMeridian(1))
        assertEquals(90, ClockDialAnalysis.axisForMeridian(3))
        assertEquals(150, ClockDialAnalysis.axisForMeridian(5))
        assertEquals(12 to 6, ClockDialAnalysis.hoursOf(0))
        assertEquals(4 to 10, ClockDialAnalysis.hoursOf(4))
    }

    @Test fun `tap angle maps to the nearest line`() {
        assertEquals(0, ClockDialAnalysis.meridianFromTap(0.0, -10.0)) // 12
        assertEquals(0, ClockDialAnalysis.meridianFromTap(0.0, 10.0)) // 6
        assertEquals(3, ClockDialAnalysis.meridianFromTap(10.0, 0.0)) // 3
        assertEquals(3, ClockDialAnalysis.meridianFromTap(-10.0, 0.0)) // 9
        val a = Math.toRadians(30.0)
        assertEquals(1, ClockDialAnalysis.meridianFromTap(10 * sin(a), -10 * cos(a))) // 1
        assertEquals(1, ClockDialAnalysis.meridianFromTap(-10 * sin(a), 10 * cos(a))) // 7
        val b = Math.toRadians(-30.0)
        assertEquals(5, ClockDialAnalysis.meridianFromTap(10 * sin(b), -10 * cos(b))) // 11
        assertNull(ClockDialAnalysis.meridianFromTap(1.0, 1.0, deadZonePx = 5.0))
    }

    @Test fun `axis helpers`() {
        assertEquals(30, ClockDialAnalysis.axisDifference(180, 30))
        assertEquals(15, ClockDialAnalysis.meanAxis(listOf(180, 30)))
        assertEquals(75, ClockDialAnalysis.meanAxis(listOf(90, 60)))
        assertEquals(180, ClockDialAnalysis.meanAxis(listOf(180, 180)))
    }

    @Test fun `agreeing repeats flag astigmatism with an axis`() {
        val same = ClockDialAnalysis.analyse(TestEye.RIGHT, listOf(3, 3), 30)!!
        assertTrue(same.astigmatismFlag); assertEquals(90, same.axisDeg); assertEquals(Confidence.HIGH, same.confidence)
        val near = ClockDialAnalysis.analyse(TestEye.RIGHT, listOf(3, 2), 30)!!
        assertTrue(near.astigmatismFlag); assertEquals(75, near.axisDeg); assertEquals(Confidence.MEDIUM, near.confidence)
    }

    @Test fun `all equal, mixed and inconsistent answers do not flag`() {
        val none = ClockDialAnalysis.analyse(TestEye.LEFT, listOf(null, null), 30)!!
        assertFalse(none.astigmatismFlag); assertEquals(Confidence.HIGH, none.confidence)
        val mixed = ClockDialAnalysis.analyse(TestEye.LEFT, listOf(null, 2), 30)!!
        assertFalse(mixed.astigmatismFlag); assertEquals(Confidence.LOW, mixed.confidence)
        val apart = ClockDialAnalysis.analyse(TestEye.LEFT, listOf(0, 3), 30)!!
        assertFalse(apart.astigmatismFlag); assertNull(apart.axisDeg); assertEquals(Confidence.LOW, apart.confidence)
        assertNull(ClockDialAnalysis.analyse(TestEye.LEFT, emptyList(), 30))
    }
}

class NearPointTest {
    private fun r(mm: Double) = DistanceReading(mm, Confidence.HIGH)

    @Test fun `push-up near point goes through fuseAdd with age, SE and working distance`() {
        val res = NearPointAnalysis.analyse(listOf(333.0, 333.0), 0.0, 0.40, 50, rules)!!
        val expected = vm.fuseAdd(0.333, 0.0, 0.40, 50, Confidence.HIGH)
        assertEquals(expected.add, res.add)
        assertEquals(expected.amplitudeD, res.fusion!!.amplitudeD, 1e-9)
        assertEquals(333.0, res.nearPointMm!!, 1e-9)
        assertEquals(Source.MEASURED, res.add!!.source)
        assertEquals(VisionMath.AmplitudeCheck.WITHIN_EXPECTED, res.fusion!!.check)
        assertTrue(res.add!!.value in 0.75..3.0)
    }

    @Test fun `myopic SE is subtracted from the amplitude`() {
        val myope = NearPointAnalysis.analyse(listOf(200.0, 200.0), -2.0, 0.40, 50, rules)!!
        val emmetrope = NearPointAnalysis.analyse(listOf(333.333, 333.333), 0.0, 0.40, 50, rules)!!
        assertEquals(emmetrope.fusion!!.amplitudeD, myope.fusion!!.amplitudeD, 1e-3)
        assertEquals(emmetrope.add!!.value, myope.add!!.value, 1e-9)
    }

    @Test fun `repeat spread of the add sets confidence, median vergence is used`() {
        // adds 1.0 and 0.5 → spread 0.5 → MEDIUM
        val res = NearPointAnalysis.analyse(listOf(333.333, 250.0), 0.0, 0.40, 50, rules)!!
        assertEquals(Confidence.MEDIUM, res.fusion!!.add.confidence)
        assertEquals(1000.0 / ((3.0 + 4.0) / 2), res.nearPointMm!!, 1e-2)
    }

    @Test fun `unknown SE caps LOW, out of reach gives no add`() {
        assertEquals(Confidence.LOW, NearPointAnalysis.analyse(listOf(333.0, 333.0), null, 0.40, 50, rules)!!.add!!.confidence)
        val out = NearPointAnalysis.analyse(listOf(333.0, null), 0.0, 0.40, 60, rules)!!
        assertTrue(out.censored); assertNull(out.add)
        assertNull(NearPointAnalysis.analyse(emptyList(), 0.0, 0.4, 50, rules))
    }

    @Test fun `SE for the push-up is the mean of measured eyes, no myopia counts as zero`() {
        val a = FarPointAnalysis.analyse(TestEye.RIGHT, listOf(400.0, 400.0), emptyList(), rules)!!
        val b = FarPointAnalysis.analyse(TestEye.LEFT, listOf(null, null), emptyList(), rules)!!
        assertEquals(-1.83 / 2, NearPointAnalysis.seForNearPoint(listOf(a, b))!!, 1e-9)
        assertNull(NearPointAnalysis.seForNearPoint(emptyList()))
    }

    @Test fun `procedure starts far, then accepts a blur tap in range`() {
        var p = NearPointProcedure.start(cfg, 0.0)
        assertEquals(cfg.nearPointStartMinMm, p.startMinMm, 0.0)
        assertFalse(p.canMarkBlur(r(200.0), cfg))
        p = p.onReading(r(400.0), cfg)
        assertTrue(p.canMarkBlur(r(200.0), cfg))
        assertFalse(p.canMarkBlur(r(50.0), cfg))
        p = p.markBlur(r(200.0), cfg)
        assertFalse(p.isDone)
        assertTrue(p.canMarkOutOfReach(r(500.0), cfg))
        p = p.markOutOfReach(r(500.0), cfg)
        assertTrue(p.isDone)
        assertEquals(listOf(200.0, null), p.marksMm)
    }

    @Test fun `uncorrected myope starts inside the far point`() {
        val p = NearPointProcedure.start(cfg, -4.0) // far point 250 mm
        assertEquals(225.0, p.startMinMm, 1e-9)
        assertTrue(p.onReading(r(230.0), cfg).seenFar)
        assertEquals(cfg.nearPointMinMm + 20.0, NearPointProcedure.start(cfg, -20.0).startMinMm, 1e-9)
        assertEquals(cfg.nearPointStartMinMm, NearPointProcedure.start(cfg, null).startMinMm, 0.0)
    }
}

class AcuityAmslerTest {
    @Test fun `near acuity median, spread confidence, scale cap and referral threshold`() {
        val a = NearAcuityAnalysis.analyse(TestEye.RIGHT, listOf(0.2, 0.3), cfg, scaleFromCard = true)!!
        assertEquals(0.25, a.logMar, 1e-9); assertEquals(Confidence.HIGH, a.confidence); assertFalse(a.belowThreshold)
        assertEquals(Confidence.MEDIUM, NearAcuityAnalysis.analyse(TestEye.RIGHT, listOf(0.2, 0.3), cfg, false)!!.confidence)
        assertEquals(Confidence.LOW, NearAcuityAnalysis.analyse(TestEye.RIGHT, listOf(0.1, 0.5), cfg, true)!!.confidence)
        assertTrue(NearAcuityAnalysis.analyse(TestEye.LEFT, listOf(0.8, 0.9), cfg, true)!!.belowThreshold)
        assertFalse(NearAcuityAnalysis.analyse(TestEye.LEFT, listOf(0.7, 0.7), cfg, true)!!.belowThreshold)
    }

    @Test fun `amsler any yes is abnormal, disagreement lowers confidence`() {
        val ok = AmslerAnalysis.analyse(TestEye.RIGHT, listOf(false, false), emptyList(), cfg)!!
        assertFalse(ok.abnormal); assertEquals(Confidence.HIGH, ok.confidence)
        val one = AmslerAnalysis.analyse(TestEye.RIGHT, listOf(true, false), listOf(GridMark(0.1f, -0.2f)), cfg)!!
        assertTrue(one.abnormal); assertEquals(Confidence.LOW, one.confidence)
        val strict = AmslerAnalysis.analyse(TestEye.RIGHT, listOf(true, false), emptyList(), cfg.copy(amslerYesCountForAbnormal = 2))!!
        assertFalse(strict.abnormal)
    }

    @Test fun `amsler and clock procedures count repeats`() {
        var a = AmslerProcedure(2).answer(false)
        assertFalse(a.isDone)
        a = a.mark(GridMark(0f, 0f)).answer(true)
        assertTrue(a.isDone); assertEquals(1, a.marks.size)
        val c = ClockDialProcedure(2).answer(null).answer(4)
        assertTrue(c.isDone); assertEquals(listOf(null, 4), c.answers)
    }
}

class SessionMappingTest {
    private val engine = RulesEngine(rules, AnthroPriors.parse(File("src/main/assets/anthro_priors.json").readText()))

    @Test fun `empty session maps to empty VisionTests`() {
        val t = VisionSession().toVisionTests()
        assertNull(t.right); assertNull(t.left); assertNull(t.measuredAddD)
        assertFalse(t.nearAcuityBelowThreshold); assertFalse(t.amslerAbnormal); assertEquals(0, t.lowConfidenceCount)
    }

    @Test fun `full session maps every field`() {
        val right = FarPointAnalysis.analyse(TestEye.RIGHT, listOf(400.0, 400.0), listOf(550.0, 540.0), rules)!!
        val left = FarPointAnalysis.analyse(TestEye.LEFT, listOf(400.0, 300.0), emptyList(), rules)!! // LOW
        val dial = ClockDialAnalysis.analyse(TestEye.LEFT, listOf(3, 3), 30)!!
        val near = NearPointAnalysis.analyse(listOf(333.0, 333.0), NearPointAnalysis.seForNearPoint(listOf(right, left)), 0.4, 50, rules)!!
        val s = VisionSession(
            farPoint = mapOf(TestEye.RIGHT to right, TestEye.LEFT to left),
            clockDial = mapOf(TestEye.LEFT to dial),
            nearPoint = near,
            nearAcuity = mapOf(TestEye.RIGHT to NearAcuityAnalysis.analyse(TestEye.RIGHT, listOf(0.1, 0.1), cfg, true)!!),
            amsler = mapOf(TestEye.RIGHT to AmslerAnalysis.analyse(TestEye.RIGHT, listOf(false, false), emptyList(), cfg)!!),
        )
        val t = s.toVisionTests()
        assertEquals(right.seD, t.right!!.seD)
        assertEquals(Confidence.HIGH, t.right!!.confidence)
        assertFalse(t.right!!.astigmatismFlag)
        assertTrue(t.left!!.astigmatismFlag); assertEquals(90, t.left!!.astigmatismAxisDeg)
        assertEquals(Confidence.LOW, t.left!!.confidence)
        assertEquals(near.add, t.measuredAddD)
        assertEquals(1 + (if (near.add!!.confidence == Confidence.LOW) 1 else 0), t.lowConfidenceCount)
        assertFalse(t.amslerAbnormal); assertFalse(t.nearAcuityBelowThreshold)
    }

    @Test fun `clock dial alone still produces a MeasuredEye without an SE`() {
        val s = VisionSession(clockDial = mapOf(TestEye.RIGHT to ClockDialAnalysis.analyse(TestEye.RIGHT, listOf(1, 1), 30)!!))
        val e = s.toVisionTests().right!!
        assertNull(e.seD); assertTrue(e.astigmatismFlag); assertEquals(30, e.astigmatismAxisDeg)
    }

    @Test fun `engine refers on Amsler, acuity, astigmatism and distance blur without myopia`() {
        val s = VisionSession(
            farPoint = mapOf(
                TestEye.RIGHT to FarPointAnalysis.analyse(TestEye.RIGHT, listOf(null, null), listOf(null, null), rules)!!,
                TestEye.LEFT to FarPointAnalysis.analyse(TestEye.LEFT, listOf(null, null), listOf(null, null), rules)!!,
            ),
            clockDial = mapOf(TestEye.RIGHT to ClockDialAnalysis.analyse(TestEye.RIGHT, listOf(2, 2), 30)!!),
            nearAcuity = mapOf(TestEye.LEFT to NearAcuityAnalysis.analyse(TestEye.LEFT, listOf(0.9, 1.0), cfg, true)!!),
            amsler = mapOf(TestEye.LEFT to AmslerAnalysis.analyse(TestEye.LEFT, listOf(true, true), emptyList(), cfg)!!),
        )
        val a = engine.assess(InterviewAnswers(age = 45, distanceBlur = true), tests = s.toVisionTests())
        assertEquals(ReferralLevel.URGENT, a.referral.level)
        val reasons = a.referral.reasons
        assertTrue(ReferralReason.AMSLER_DISTORTION in reasons)
        assertTrue(ReferralReason.NEAR_ACUITY_BELOW_THRESHOLD in reasons)
        assertTrue(ReferralReason.ASTIGMATISM_FLAG in reasons)
        assertTrue(ReferralReason.DISTANCE_BLUR_NO_MYOPIA_FOUND in reasons)
    }

    @Test fun `engine turns a measured myopic eye into an estimated band and uses the measured add`() {
        val fp = FarPointAnalysis.analyse(TestEye.RIGHT, listOf(400.0, 400.0), listOf(550.0, 540.0), rules)!!
        val near = NearPointAnalysis.analyse(listOf(200.0, 200.0), fp.seD, 0.4, 50, rules)!!
        val s = VisionSession(farPoint = mapOf(TestEye.RIGHT to fp, TestEye.LEFT to fp.copy(eye = TestEye.LEFT)), nearPoint = near)
        val a = engine.assess(InterviewAnswers(age = 50, distanceBlur = true, nearBlur = true), tests = s.toVisionTests())
        assertNotNull(a.advice)
        val advice = a.advice!!
        assertEquals(2, advice.eyes.size)
        assertEquals(Source.MEASURED, advice.eyes[0].band.source)
        assertTrue(advice.eyes[0].band.value.lowD < -1.0)
        assertEquals(Source.MEASURED, advice.add!!.source)
    }

    @Test fun `flow plan order and near point by age`() {
        val young = VisionFlowPlan.steps(25, rules)
        assertFalse(VisionStep.NearPoint in young)
        val old = VisionFlowPlan.steps(50, rules)
        assertEquals(
            listOf(
                VisionStep.Intro,
                VisionStep.FarPoint(TestEye.RIGHT), VisionStep.ClockDial(TestEye.RIGHT),
                VisionStep.FarPoint(TestEye.LEFT), VisionStep.ClockDial(TestEye.LEFT),
                VisionStep.NearPoint,
                VisionStep.NearAcuity(TestEye.RIGHT), VisionStep.NearAcuity(TestEye.LEFT),
                VisionStep.Amsler(TestEye.RIGHT), VisionStep.Amsler(TestEye.LEFT),
                VisionStep.Summary,
            ),
            old,
        )
        assertTrue(VisionStep.NearPoint in VisionFlowPlan.steps(null, rules))
        val binocular = rules.copy(visionTests = cfg.copy(nearAcuityPerEye = false))
        assertTrue(VisionStep.NearAcuity(TestEye.BOTH) in VisionFlowPlan.steps(50, binocular))
    }

    @Test fun `retry confidence comes from the step's own result`() {
        val s = VisionSession(clockDial = mapOf(TestEye.LEFT to ClockDialAnalysis.analyse(TestEye.LEFT, listOf(0, 3), 30)!!))
        assertEquals(Confidence.LOW, VisionFlowPlan.resultConfidence(VisionStep.ClockDial(TestEye.LEFT), s))
        assertNull(VisionFlowPlan.resultConfidence(VisionStep.ClockDial(TestEye.RIGHT), s))
    }
}
