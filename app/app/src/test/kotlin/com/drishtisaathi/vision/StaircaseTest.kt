package com.drishtisaathi.vision

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.config.VisionTestConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StaircaseTest {
    private val cfg = VisionTestConfig()
    private val p = StaircaseParams.from(cfg)

    /** Deterministic observer: always right when the letter is at or above [trueLogMar], always wrong below. */
    private fun run(trueLogMar: Double, s0: Staircase = Staircase(p)): Staircase {
        var s = s0
        var guard = 0
        while (!s.isDone) {
            s = s.answer(s.level >= trueLogMar - 1e-9)
            check(++guard < 1000)
        }
        return s
    }

    @Test fun `coarse 1-down steps before the first reversal`() {
        val s = Staircase(p).answer(true)
        assertEquals(0.5, s.level, 1e-9)
        assertEquals(0.3, s.answer(true).level, 1e-9)
    }

    @Test fun `wrong answer makes the letter bigger and counts a reversal after going down`() {
        val s = Staircase(p).answer(true).answer(false)
        assertEquals(listOf(0.5), s.reversals)
        assertEquals(0.6, s.level, 1e-9) // fine step after the first reversal
    }

    @Test fun `after the first reversal two correct answers are needed to step down`() {
        val s = Staircase(p).answer(true).answer(false).answer(true)
        assertEquals(0.6, s.level, 1e-9)
        assertEquals(0.5, s.answer(true).level, 1e-9)
    }

    @Test fun `deterministic observer converges on its threshold and stops after the configured reversals`() {
        val s = run(0.3)
        assertEquals(Staircase.Stop.REVERSALS, s.stop)
        assertEquals(cfg.staircaseReversals, s.reversals.size)
        assertEquals(0.25, s.threshold(), 0.06) // oscillates between 0.2 and 0.3
    }

    @Test fun `threshold is the mean of the last reversals`() {
        val s = Staircase(p, reversals = listOf(0.9, 0.1, 0.5, 0.3, 0.4, 0.2))
        assertEquals((0.5 + 0.3 + 0.4 + 0.2) / 4, s.threshold(), 1e-9)
    }

    @Test fun `ceiling stop when the largest letter is missed repeatedly`() {
        var s = Staircase(p, level = 1.0)
        repeat(cfg.staircaseBoundaryRuns) { s = s.answer(false) }
        assertEquals(Staircase.Stop.CEILING, s.stop)
        assertEquals(cfg.staircaseMaxLogMar, s.threshold(), 1e-9)
    }

    @Test fun `floor stop when the smallest letter keeps being seen`() {
        val s = run(-1.0)
        assertEquals(Staircase.Stop.FLOOR, s.stop)
        assertEquals(cfg.staircaseMinLogMar, s.threshold(), 1e-9)
    }

    @Test fun `max trials stops a random guesser`() {
        val rnd = Random(7)
        var s = Staircase(p)
        while (!s.isDone) s = s.answer(rnd.nextInt(4) == 0)
        assertTrue(s.trials <= cfg.staircaseMaxTrials)
        assertTrue(s.threshold() in cfg.staircaseMinLogMar..cfg.staircaseMaxLogMar)
    }

    @Test fun `level stays within bounds`() {
        var s = Staircase(p)
        repeat(3) { if (!s.isDone) s = s.answer(false) }
        assertTrue(s.level <= cfg.staircaseMaxLogMar + 1e-9)
    }

    @Test fun `random direction never repeats`() {
        val rnd = Random(1)
        var prev: EDirection? = null
        repeat(200) { val d = EDirection.random(rnd, prev); assertNotEquals(prev, d); prev = d }
    }

    @Test fun `acuity procedure runs two staircases and collects both thresholds`() {
        var a = AcuityProcedure.start(cfg, EDirection.UP)
        var guard = 0
        while (!a.isDone) {
            val seen = a.staircase.level >= 0.3 - 1e-9
            val answer = if (seen) a.shown else EDirection.entries.first { it != a.shown }
            a = a.answer(answer, EDirection.random(Random(guard), a.shown), Confidence.HIGH)
            check(++guard < 500)
        }
        assertEquals(2, a.thresholds.size)
        assertEquals(a.thresholds[0], a.thresholds[1], 1e-9)
        assertFalse(runCatching { a.answer(EDirection.UP, EDirection.UP, Confidence.HIGH) }.isSuccess)
    }

    @Test fun `cannot see counts as wrong`() {
        val a = AcuityProcedure.start(cfg, EDirection.UP).answer(null, EDirection.LEFT, Confidence.MEDIUM)
        assertEquals(0.9, a.staircase.level, 1e-9)
        assertEquals(Confidence.MEDIUM, a.worstConfidence)
    }
}
