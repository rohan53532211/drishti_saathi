package com.drishtisaathi.engine.vision

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.TestAssets
import com.drishtisaathi.engine.config.FarPointConfig
import com.drishtisaathi.engine.vision.FarPointProtocol.Colour
import com.drishtisaathi.engine.vision.FarPointProtocol.Reading
import com.drishtisaathi.engine.vision.FarPointProtocol.State
import com.drishtisaathi.engine.vision.FarPointProtocol.Step
import com.drishtisaathi.engine.vision.FarPointProtocol.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** rules_update.md rules 3 (blue vs white → retake once, then LOW) and 4 (repeats disagree → third reading, median). */
class FarPointProtocolTest {
    private val rules = TestAssets.rules
    private val p = FarPointProtocol(rules)

    private fun State.add(vararg mm: Double?, staff: Boolean = false): State =
        mm.fold(this) { s, d -> p.record(s, Reading(d, staff)) }

    private fun done(s: State): FarPointProtocol.Outcome = (p.next(s) as Step.Done).outcome

    @Test fun `config - actions are enums and thresholds come from optics`() {
        assertEquals(FarPointConfig.ConsistencyAction.RETAKE_ONCE_THEN_LOW_CONFIDENCE, rules.farPoint.consistencyAction)
        assertEquals(FarPointConfig.RepeatSpreadAction.THIRD_READING_MEDIAN, rules.farPoint.repeatSpreadAction)
        assertEquals(0.50, rules.optics.farPointAgreementMaxD, 1e-9)
        assertEquals(0.50, rules.optics.repeatSpreadMediumMaxD, 1e-9)
        val bad = TestAssets.text("rules.json").replace("\"THIRD_READING_MEDIAN\"", "\"THIRD_READING\"")
        assertTrue(runCatching { com.drishtisaathi.engine.RulesConfig.parse(bad) }.isFailure)
    }

    @Test fun `blue twice then white twice, agreeing - done, high confidence`() {
        val s = State().add(400.0, 400.0, 550.0, 540.0)
        val o = done(s)
        assertEquals(true, o.consistent)
        assertEquals(Confidence.HIGH, o.confidence)
        assertFalse(o.thirdReadingBlue)
        assertEquals(0, o.retakes)
        assertEquals((o.seBlueD!! + o.seWhiteD!!) / 2, o.seD!!, 1e-9)
    }

    @Test fun `order of measurements`() {
        var s = State()
        assertEquals(Step.Measure(Colour.BLUE, Why.STANDARD, 0), p.next(s))
        s = s.add(400.0)
        assertEquals(Step.Measure(Colour.BLUE, Why.STANDARD, 1), p.next(s))
        s = s.add(370.0) // 0.20 D apart: no third reading
        assertEquals(Step.Measure(Colour.WHITE, Why.STANDARD, 0), p.next(s))
    }

    // ---- rule 4 -------------------------------------------------------------------------------------

    @Test fun `rule 4 - blue repeats more than 0_50 D apart ask for a third reading, median used`() {
        var s = State().add(400.0, 330.0) // −1.83 vs −2.36 D: 0.53 D apart
        assertEquals(Step.Measure(Colour.BLUE, Why.THIRD_READING, 2), p.next(s))
        s = s.add(390.0)
        assertEquals(Colour.WHITE, (p.next(s) as Step.Measure).colour)
        val o = done(s.add(550.0, 540.0))
        assertTrue(o.thirdReadingBlue)
        assertEquals(-1000.0 / 390.0 + 0.67, o.seBlueD!!, 1e-9) // median of the three
        assertEquals(Confidence.MEDIUM, o.confidence) // two of three agree
    }

    @Test fun `rule 4 - third reading that agrees with neither gives LOW`() {
        val o = done(State().add(400.0, 300.0, 200.0, null, null))
        assertTrue(o.thirdReadingBlue)
        assertEquals(Confidence.LOW, o.confidence)
    }

    @Test fun `rule 4 - exactly 0_50 D apart needs no third reading`() {
        // blue SE −1.83 and −2.33: d = 1/(2.33+0.67)... use −1/d_b: 0.4 m → −2.5, 1/3 m → −3.0
        val s = State().add(400.0, 1000.0 / 3.0)
        assertEquals(Colour.WHITE, (p.next(s) as Step.Measure).colour)
    }

    @Test fun `rule 4 - white repeats disagreeing also get a third reading`() {
        val s = State().add(400.0, 400.0, 550.0, 400.0) // white −1.82 vs −2.50
        assertEquals(Step.Measure(Colour.WHITE, Why.THIRD_READING, 2), p.next(s))
        assertTrue(done(s.add(545.0)).thirdReadingWhite)
    }

    @Test fun `rule 4 off - two disagreeing repeats are LOW without a third reading`() {
        val off = FarPointProtocol(rules.optics, rules.farPoint.copy(repeatSpreadAction = FarPointConfig.RepeatSpreadAction.LOW_CONFIDENCE), 2)
        val s = listOf(400.0, 330.0, null, null).fold(State()) { st, d -> off.record(st, Reading(d)) }
        val o = (off.next(s) as Step.Done).outcome
        assertFalse(o.thirdReadingBlue)
        assertEquals(Confidence.LOW, o.confidence)
    }

    // ---- rule 3 -------------------------------------------------------------------------------------

    @Test fun `rule 3 - blue and white disagree, the far point is retaken once`() {
        var s = State().add(400.0, 400.0, 300.0) // blue −1.83, white −3.33 so far
        s = s.add(300.0)
        assertEquals(1, s.retakes)
        assertTrue(s.blue.isEmpty() && s.white.isEmpty())
        assertEquals(false, s.retakenOutcome!!.consistent)
        assertEquals(Step.Measure(Colour.BLUE, Why.RETAKE, 0), p.next(s))
    }

    @Test fun `rule 3 - still disagreeing after the retake gives blue SE at LOW confidence`() {
        val s = State().add(400.0, 400.0, 300.0, 300.0).add(400.0, 400.0, 300.0, 300.0)
        val o = done(s)
        assertEquals(1, o.retakes)
        assertEquals(false, o.consistent)
        assertEquals(Confidence.LOW, o.confidence)
        assertEquals(o.seBlueD!!, o.seD!!, 1e-9)
    }

    @Test fun `rule 3 - a retake that agrees gives the normal result`() {
        val o = done(State().add(400.0, 400.0, 300.0, 300.0).add(400.0, 400.0, 550.0, 540.0))
        assertEquals(1, o.retakes)
        assertEquals(true, o.consistent)
        assertEquals(Confidence.HIGH, o.confidence)
    }

    @Test fun `rule 3 off - disagreement is LOW straight away`() {
        val off = FarPointProtocol(rules.optics, rules.farPoint.copy(consistencyAction = FarPointConfig.ConsistencyAction.LOW_CONFIDENCE), 2)
        val s = listOf(400.0, 400.0, 300.0, 300.0).fold(State()) { st, d -> off.record(st, Reading(d)) }
        val o = (off.next(s) as Step.Done).outcome
        assertEquals(0, o.retakes)
        assertEquals(Confidence.LOW, o.confidence)
    }

    @Test fun `white beyond 0_8 m skips the check and the retake`() {
        val o = done(State().add(500.0, 500.0, 900.0, 900.0))
        assertNull(o.consistent)
        assertEquals(0, o.retakes)
    }

    @Test fun `staff-held white readings count up to 2 m so the blue-white check works`() {
        // blue 1.0 m → −1.0 + 0.67 = −0.33; white 1.5 m → −0.67; diff 0.34 ≤ 0.50
        val o = done(State().add(1000.0, 1000.0, 1500.0, 1500.0, staff = true))
        assertNotNull(o.seWhiteD)
        assertEquals(true, o.consistent)
        assertEquals(-1.0 / 1.5, o.seWhiteD!!, 1e-9)
    }

    // ---- staff-held (rule 1) --------------------------------------------------------------------------

    @Test fun `staff-held taps count up to 2 m, self-held ones only within arm's length`() {
        val staff = done(State().add(1500.0, 1500.0, null, null, staff = true))
        assertNotNull(staff.seD)
        assertTrue(staff.staffHeld)
        assertEquals(-1.0 / 1.5 + 0.67, staff.seBlueD!!, 1e-9)
        assertTrue(done(State().add(1500.0, 1500.0, null, null)).noMyopiaWithinReach)
        val none = done(State().add(null, null, null, null, staff = true))
        assertTrue(none.noMyopiaWithinReach)
        assertNull(none.seD)
    }
}
