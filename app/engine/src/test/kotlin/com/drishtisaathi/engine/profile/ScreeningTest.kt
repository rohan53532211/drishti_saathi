package com.drishtisaathi.engine.profile

import com.drishtisaathi.engine.Eye
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.TestAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Phase C: Amsler routing, contrast scoring, colour scoring and control-plate validity. */
class ScreeningTest {
    private val cfg = TestAssets.rules.screening

    // ---- Amsler ----

    @Test fun `the grid is 20 by 20 squares of 5 mm - 10 cm at 30 cm - and starts only at 27 to 33 cm`() {
        assertEquals(5.0, cfg.amsler.squareMm, 0.0); assertEquals(20, cfg.amsler.squares)
        assertEquals(300.0, cfg.amsler.distanceMm, 0.0)
        assertFalse(Amsler.distanceOk(269.0, cfg.amsler))
        assertTrue(Amsler.distanceOk(270.0, cfg.amsler)); assertTrue(Amsler.distanceOk(330.0, cfg.amsler))
        assertFalse(Amsler.distanceOk(331.0, cfg.amsler)); assertFalse(Amsler.distanceOk(null, cfg.amsler))
    }

    @Test fun `squares keep their real size - a wide screen shows the full grid, a phone leaves out whole columns`() {
        val mmPerPx = 0.06                                   // about 423 dpi
        val full = Amsler.layout(2000.0, 2000.0, mmPerPx, cfg.amsler)!!
        assertTrue(full.full); assertEquals(20, full.columns); assertEquals(20, full.rows)
        assertEquals(5.0 / mmPerPx, full.squarePx, 1e-9)
        assertEquals(100.0, full.widthPx * mmPerPx, 1e-6)     // 10 cm

        val phone = Amsler.layout(66.0 / mmPerPx, 110.0 / mmPerPx, mmPerPx, cfg.amsler)!!   // a 66 mm wide screen
        assertFalse(phone.full)
        assertEquals("13 whole squares fit; an even count keeps the dot on a crossing", 12, phone.columns)
        assertEquals(20, phone.rows)
        assertEquals("never scaled", 5.0 / mmPerPx, phone.squarePx, 1e-9)
        assertNull("too small a screen: no grid", Amsler.layout(30.0 / mmPerPx, 110.0 / mmPerPx, mmPerPx, cfg.amsler))
    }

    @Test fun `a tap maps to its square`() {
        val l = Amsler.layout(2000.0, 2000.0, 0.1, cfg.amsler)!!   // 50 px squares
        assertEquals(AmslerCell(0, 0), l.cellAt(10.0, 49.0))
        assertEquals(AmslerCell(3, 19), l.cellAt(175.0, 999.0))
        assertNull(l.cellAt(-1.0, 5.0)); assertNull(l.cellAt(1000.0, 5.0))
    }

    @Test fun `any yes on the grid is a red flag with the tapped region - a no is not`() {
        val l = Amsler.layout(2000.0, 2000.0, 0.1, cfg.amsler)!!
        val yes = Amsler.result(Eye.RIGHT, true, listOf(AmslerCell(4, 5), AmslerCell(4, 5), AmslerCell(5, 5)), l, 300.0)
        assertTrue(yes.distortion)
        assertEquals("the tapped squares are saved once each", listOf(AmslerCell(4, 5), AmslerCell(5, 5)), yes.cells)
        val no = Amsler.result(Eye.LEFT, false, listOf(AmslerCell(1, 1)), l, 300.0)
        assertFalse(no.distortion); assertTrue(no.cells.isEmpty())

        val safety = TestAssets.rules.safety
        val bad = Safety.gate(InterviewAnswers(age = 60), SurveyAnswers(), safety, screening = ScreeningResults(amsler = listOf(no, yes)))
        assertEquals(listOf(RedFlag.AMSLER_DISTORTION), bad.redFlags)
        assertEquals(ReportTag.URGENT, bad.tag); assertFalse(bad.lensAllowed)
        assertFalse("the macula flag is urgent, but the check is already done: not the short path", bad.emergency)
        val fine = Safety.gate(InterviewAnswers(age = 60), SurveyAnswers(), safety, screening = ScreeningResults(amsler = listOf(no)))
        assertTrue(fine.lensAllowed)
        assertTrue(ScreeningResults(amsler = listOf(yes)).toVisionTests().amslerAbnormal)
    }

    // ---- contrast ----

    private fun run(vararg right: Boolean): ContrastRun = right.fold(ContrastRun(cfg.contrast)) { r, a -> r.answer(a) }

    @Test fun `contrast steps and the 2 of 3 rule`() {
        assertEquals(listOf(100.0, 50.0, 25.0, 12.5, 6.0, 3.0, 1.5), cfg.contrast.stepsPercent)
        assertEquals(0.5, cfg.contrast.letterLogMar, 0.0)
        // two right in a row pass the step without a third trial
        val a = run(true, true)
        assertEquals(1, a.step); assertEquals(100.0, a.lowestPassed!!, 0.0); assertFalse(a.done)
        // right, wrong, right also passes
        assertEquals(1, run(true, false, true).step)
        // two wrong fail the step and end the run
        val f = run(true, true, false, false)
        assertTrue(f.done); assertEquals(100.0, f.lowestPassed!!, 0.0)
        // wrong, right, wrong fails as well
        assertTrue(run(false, true, false).done)
        assertNull("even the first step failed", run(false, false).lowestPassed)
    }

    @Test fun `contrast result - lowest step passed, Normal or Reduced`() {
        val all = (1..14).fold(ContrastRun(cfg.contrast)) { r, _ -> r.answer(true) }
        assertTrue(all.done)
        assertEquals(1.5, all.result(Eye.RIGHT).lowestPassedPercent!!, 0.0)
        assertFalse(all.result(Eye.RIGHT).reduced)
        // passes 100, 50, 25, 12.5, 6 and 3, fails 1.5: still normal
        val three = (1..12).fold(ContrastRun(cfg.contrast)) { r, _ -> r.answer(true) }.answer(false).answer(false)
        assertEquals(3.0, three.result(Eye.LEFT).lowestPassedPercent!!, 0.0); assertFalse(three.result(Eye.LEFT).reduced)
        // stops at 6 %: reduced
        val six = (1..10).fold(ContrastRun(cfg.contrast)) { r, _ -> r.answer(true) }.answer(false).answer(false)
        assertEquals(6.0, six.result(Eye.LEFT).lowestPassedPercent!!, 0.0); assertTrue(six.result(Eye.LEFT).reduced)
        assertTrue(run(false, false).result(Eye.LEFT).reduced)
    }

    @Test fun `reduced contrast suggests an eye-health check from 50, or with low-light or glare complaints`() {
        val c = cfg.contrast
        assertTrue(Contrast.eyeHealthNote(true, 50, false, c))
        assertFalse(Contrast.eyeHealthNote(true, 49, false, c))
        assertTrue(Contrast.eyeHealthNote(true, 30, true, c))
        assertFalse(Contrast.eyeHealthNote(true, null, false, c))
        assertFalse("normal contrast never gets the note", Contrast.eyeHealthNote(false, 70, true, c))
    }

    @Test fun `fainter steps are drawn lighter and every step is a different grey`() {
        val greys = cfg.contrast.stepsPercent.map(Contrast::letterGrey)
        assertEquals(0, greys.first())
        assertEquals(greys.sorted(), greys)
        assertEquals(greys.size, greys.toSet().size)
        assertTrue(greys.last() < 255)
    }

    // ---- colour ----

    @Test fun `a run has 1 control plate first, then 5 red-green and 2 blue-yellow`() {
        val s = ColourPlates.series(cfg.colour, Random(7))
        assertEquals(8, s.size)
        assertEquals(PlateType.CONTROL, s.first().type)
        assertEquals(5, s.count { it.type == PlateType.RED_GREEN }); assertEquals(2, s.count { it.type == PlateType.BLUE_YELLOW })
        assertTrue(s.all { it.digit in ColourPlates.DIGITS })
        assertTrue("no digit twice in a row", s.zipWithNext().none { (a, b) -> a.digit == b.digit })
    }

    @Test fun `plates are generated - dots inside the circle, never overlapping, the digit drawn in figure colours`() {
        for (type in PlateType.entries) {
            val spec = PlateSpec(type, 5, 42L)
            val dots = ColourPlates.dots(spec, cfg.colour)
            assertTrue("$type: ${dots.size} dots", dots.size > 300)
            assertEquals("the same seed gives the same plate", dots, ColourPlates.dots(spec, cfg.colour))
            assertTrue(dots.all { val dx = it.x - 0.5; val dy = it.y - 0.5; dx * dx + dy * dy <= (0.5 - it.r) * (0.5 - it.r) + 1e-9 })
            assertTrue(dots.all { it.figure == ColourPlates.inDigit(5, it.x, it.y) })
            val fig = dots.count { it.figure }
            assertTrue("$type: $fig figure dots", fig > 25 && fig < dots.size / 2)
            for (i in dots.indices step 7) for (j in i + 1 until dots.size) {
                val a = dots[i]; val b = dots[j]
                val d2 = (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)
                assertTrue(d2 >= (a.r + b.r) * (a.r + b.r))
            }
        }
        assertFalse(ColourPlates.dots(PlateSpec(PlateType.RED_GREEN, 5, 1L), cfg.colour) == ColourPlates.dots(PlateSpec(PlateType.RED_GREEN, 5, 2L), cfg.colour))
        // each digit has its own shape
        val shapes = ColourPlates.DIGITS.map { d -> (0 until 35).map { i -> ColourPlates.inDigit(d, 0.31 + (i % 5) * 0.08, 0.24 + (i / 5) * 0.08) } }
        assertEquals(ColourPlates.DIGITS.size, shapes.toSet().size)
    }

    private fun answers(controlRight: Boolean, rgWrong: Int, byWrong: Int): List<Pair<PlateSpec, Int?>> {
        val s = ColourPlates.series(cfg.colour, Random(3))
        var rg = rgWrong; var by = byWrong
        return s.map { p ->
            val wrong = when (p.type) {
                PlateType.CONTROL -> !controlRight
                PlateType.RED_GREEN -> (rg-- > 0)
                PlateType.BLUE_YELLOW -> (by-- > 0)
            }
            p to if (wrong) null else p.digit
        }
    }

    @Test fun `colour scoring`() {
        val c = cfg.colour
        assertEquals(ColourOutcome.NORMAL, ColourPlates.score(answers(true, 0, 0), c).outcome)
        assertEquals("one red-green slip is still normal", ColourOutcome.NORMAL, ColourPlates.score(answers(true, 1, 0), c).outcome)
        val rg = ColourPlates.score(answers(true, 2, 0), c)
        assertEquals(ColourOutcome.POSSIBLE_RED_GREEN, rg.outcome); assertTrue(rg.possibleDeficiency); assertEquals(2, rg.redGreenWrong)
        assertEquals(ColourOutcome.POSSIBLE_RED_GREEN, ColourPlates.score(answers(true, 5, 0), c).outcome)
        assertEquals(ColourOutcome.POSSIBLE_BLUE_YELLOW, ColourPlates.score(answers(true, 0, 1), c).outcome)
        assertEquals(ColourOutcome.POSSIBLE_RED_GREEN_AND_BLUE_YELLOW, ColourPlates.score(answers(true, 3, 2), c).outcome)
        assertEquals(8, rg.plates)
    }

    @Test fun `a missed control plate makes the test not valid, whatever else was answered`() {
        val c = cfg.colour
        for ((rg, by) in listOf(0 to 0, 5 to 0, 0 to 2, 5 to 2)) {
            val r = ColourPlates.score(answers(false, rg, by), c)
            assertEquals(ColourOutcome.INVALID, r.outcome)
            assertFalse("not valid is never reported as a deficiency", r.possibleDeficiency)
            assertEquals(1, r.controlWrong)
        }
        // a wrong digit counts like "no number"
        val s = ColourPlates.series(c, Random(3))
        val wrongDigit = s.map { p -> p to if (p.type == PlateType.CONTROL) (ColourPlates.DIGITS.first { it != p.digit }) else p.digit }
        assertEquals(ColourOutcome.INVALID, ColourPlates.score(wrongDigit, c).outcome)
    }

    @Test fun `the E test and the clock dial reach the rules engine without inventing a power`() {
        val r = ScreeningResults(
            eTest = listOf(ETestEyeResult(Eye.RIGHT, 0.2, false), ETestEyeResult(Eye.LEFT, 0.8, true)),
            clockDial = listOf(DialEyeResult(Eye.RIGHT, true, 90), DialEyeResult(Eye.LEFT, false)),
        )
        val t = r.toVisionTests()
        assertTrue(t.nearAcuityBelowThreshold)
        assertTrue(t.right!!.astigmatismFlag); assertEquals(90, t.right!!.astigmatismAxisDeg)
        assertNull(t.right!!.seD); assertNull(t.left!!.seD)
        assertFalse(t.amslerAbnormal)
        assertNotNull(t.left)
    }
}
