package com.drishtisaathi.camera

import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SCamera
import com.drishtisaathi.i18n.Txt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityGatesTest {
    private val cfg = TestFaces.cfg
    private val goodStats = ImageStats(meanBrightness = 130.0, laplacianVariance = 200.0)
    private val good = GateInput(
        facePresent = true, box = FaceBox(0.3f, 0.3f, 0.7f, 0.7f), yawDeg = 1.0, rollDeg = -2.0,
        eyeAspectRatio = 0.28, stats = goodStats,
    )

    private fun fails(i: GateInput) = QualityGates.evaluate(i, cfg).failures

    @Test fun `good frame passes and says hold still`() {
        val r = QualityGates.evaluate(good, cfg)
        assertTrue(r.passed)
        assertEquals(SCamera.gOk, r.instruction)
        assertEquals("OK", r.instructionKey)
    }

    @Test fun `no face`() {
        val r = QualityGates.evaluate(GateInput(facePresent = false), cfg)
        assertEquals(listOf(GateFailure.NO_FACE), r.failures)
        assertEquals(SCamera.gNoFace, r.instruction)
    }

    @Test fun `each gate fails on its own`() {
        assertEquals(listOf(GateFailure.YAW), fails(good.copy(yawDeg = 5.5)))
        assertEquals(listOf(GateFailure.YAW), fails(good.copy(yawDeg = -6.0)))
        assertEquals(listOf(GateFailure.ROLL), fails(good.copy(rollDeg = 5.0)))
        assertEquals(listOf(GateFailure.EYES_CLOSED), fails(good.copy(eyeAspectRatio = 0.1)))
        assertEquals(listOf(GateFailure.BLURRY), fails(good.copy(stats = goodStats.copy(laplacianVariance = 5.0))))
        assertEquals(listOf(GateFailure.TOO_DARK), fails(good.copy(stats = goodStats.copy(meanBrightness = 40.0))))
        assertEquals(listOf(GateFailure.TOO_BRIGHT), fails(good.copy(stats = goodStats.copy(meanBrightness = 240.0))))
        assertEquals(listOf(GateFailure.TOO_FAR), fails(good.copy(box = FaceBox(0.4f, 0.4f, 0.6f, 0.6f))))
        assertEquals(listOf(GateFailure.TOO_CLOSE), fails(good.copy(box = FaceBox(0.05f, 0.1f, 0.95f, 0.9f))))
        assertEquals(listOf(GateFailure.NOT_CENTRED), fails(good.copy(box = FaceBox(0.5f, 0.3f, 0.9f, 0.7f))))
    }

    @Test fun `just inside the thresholds passes`() {
        assertTrue(fails(good.copy(yawDeg = 4.9, rollDeg = -4.9, eyeAspectRatio = cfg.minEyeAspectRatio)).isEmpty())
    }

    @Test fun `unmeasured values fail safe`() {
        assertEquals(listOf(GateFailure.YAW, GateFailure.ROLL, GateFailure.EYES_CLOSED, GateFailure.BLURRY),
            fails(good.copy(yawDeg = null, rollDeg = null, eyeAspectRatio = null, stats = null)))
    }

    @Test fun `first failure in priority order is spoken`() {
        val r = QualityGates.evaluate(good.copy(yawDeg = 20.0, stats = goodStats.copy(meanBrightness = 20.0, laplacianVariance = 1.0)), cfg)
        assertEquals(listOf(GateFailure.TOO_DARK, GateFailure.YAW, GateFailure.BLURRY), r.failures)
        assertEquals(SCamera.gDark, r.instruction)
    }

    @Test fun `measured synthetic face passes and tilted one fails roll`() {
        assertTrue(QualityGates.evaluate(TestFaces.frontal(), goodStats, cfg).passed)
        assertEquals(listOf(GateFailure.ROLL), QualityGates.evaluate(TestFaces.frontal(tiltDeg = 9.0), goodStats, cfg).failures)
        assertEquals(listOf(GateFailure.EYES_CLOSED), QualityGates.evaluate(TestFaces.frontal(eyeOpen = 0.08), goodStats, cfg).failures)
        val turned = TestFaces.frontal().copy(transformMatrix = TestFaces.yawMatrix(12.0))
        assertEquals(listOf(GateFailure.YAW), QualityGates.evaluate(turned, goodStats, cfg).failures)
        assertEquals(listOf(GateFailure.NO_FACE), QualityGates.evaluate(null, goodStats, cfg).failures)
    }

    @Test fun `every failure has a bilingual instruction`() {
        GateFailure.entries.forEach { assertTrue(it.instruction.en.isNotBlank() && it.instruction.hi.isNotBlank()) }
    }
}

class InstructionRateLimiterTest {
    private fun limiter() = InstructionRateLimiter(stableMs = 500, minGapMs = 2000, repeatMs = 6000)

    @Test fun `instruction must be stable before it is spoken`() {
        val l = limiter()
        assertFalse(l.offer("YAW", 0))
        assertFalse(l.offer("YAW", 400))
        assertTrue(l.offer("YAW", 500))
    }

    @Test fun `flickering instructions are not spoken`() {
        val l = limiter()
        var t = 0L
        repeat(20) {
            assertFalse(l.offer(if (it % 2 == 0) "YAW" else "ROLL", t))
            t += 100
        }
    }

    @Test fun `same instruction is not repeated every frame`() {
        val l = limiter()
        assertFalse(l.offer("YAW", 0))
        assertTrue(l.offer("YAW", 500))
        val spoken = (1..100).count { l.offer("YAW", 500L + it * 33) } // ~3.3 s at 30 fps
        assertEquals(0, spoken)
        assertTrue(l.offer("YAW", 6500))
    }

    @Test fun `new instruction waits for the minimum gap`() {
        val l = limiter()
        assertFalse(l.offer("YAW", 0))
        assertTrue(l.offer("YAW", 500))
        assertFalse(l.offer("ROLL", 1000))
        assertFalse(l.offer("ROLL", 1600)) // stable, but only 1.1 s since last speech
        assertTrue(l.offer("ROLL", 2500))
    }

    @Test fun `announcer speaks through the limiter`() {
        val spoken = mutableListOf<String>()
        val speaker = object : InstructionSpeaker {
            override fun speak(text: Txt, lang: Lang) { spoken += text[lang] }
            override fun stop() {}
            override fun shutdown() {}
        }
        var now = 0L
        val a = InstructionAnnouncer(speaker, limiter()) { now }
        val r = QualityGates.evaluate(GateInput(facePresent = false), TestFaces.cfg)
        repeat(30) { a.announce(r, Lang.HI); now += 100 }
        assertEquals(listOf(SCamera.gNoFace.hi), spoken)
        a.sayNow(SCamera.captureDone, Lang.EN)
        assertEquals(SCamera.captureDone.en, spoken.last())
    }
}

class CaptureCollectorTest {
    private val ok = QualityGates.evaluate(GateInput(true, FaceBox(0.3f, 0.3f, 0.7f, 0.7f), 0.0, 0.0, 0.3, ImageStats(120.0, 100.0)), TestFaces.cfg)
    private val bad = QualityGates.evaluate(GateInput(false), TestFaces.cfg)

    @Test fun `collects only passed new frames up to the target`() {
        val c = CaptureCollector(3)
        assertFalse(c.offer(TestFaces.frontal(ts = 1), bad))
        assertTrue(c.offer(TestFaces.frontal(ts = 1), ok))
        assertFalse(c.offer(TestFaces.frontal(ts = 1), ok)) // same frame again
        assertTrue(c.offer(TestFaces.frontal(ts = 2), ok))
        assertTrue(c.offer(TestFaces.frontal(ts = 3), ok))
        assertTrue(c.done)
        assertFalse(c.offer(TestFaces.frontal(ts = 4), ok))
        assertEquals(3, c.frames().size)
        c.reset()
        assertEquals(0, c.count)
    }
}
