package com.drishtisaathi.calibration

import com.drishtisaathi.engine.Source
import com.drishtisaathi.engine.config.CameraConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CardGeometryTest {
    private val cfg = CameraConfig()

    /** A card-like rectangle [w]×[h] centred at (cx, cy), rotated by [deg]. */
    private fun rect(w: Double, h: Double, cx: Double = 500.0, cy: Double = 300.0, deg: Double = 0.0): List<Pt> {
        val a = Math.toRadians(deg)
        return listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).map { (sx, sy) ->
            val x = sx * w / 2; val y = sy * h / 2
            Pt(cx + x * cos(a) - y * sin(a), cy + x * sin(a) + y * cos(a))
        }
    }

    @Test fun `ID-1 card is accepted and measured`() {
        val (v, q) = CardGeometry.validate(rect(428.0, 269.9), 1000, cfg)
        assertEquals(QuadVerdict.OK, v)
        assertEquals(428.0, q!!.widthPx, 1e-6)
        assertEquals(0.2, CardGeometry.cameraMmPerPx(q, cfg), 1e-9) // 85.60 / 428
    }

    @Test fun `corner order and small rotation do not matter`() {
        val pts = rect(400.0, 252.2, deg = 7.0)
        val shuffled = listOf(pts[2], pts[0], pts[3], pts[1])
        val (v, q) = CardGeometry.validate(shuffled, 1000, cfg)
        assertEquals(QuadVerdict.OK, v)
        assertEquals(400.0, q!!.widthPx, 1e-6)
    }

    @Test fun `card held portrait is measured along its long side`() {
        val (v, q) = CardGeometry.validate(rect(252.2, 400.0), 1000, cfg)
        assertEquals(QuadVerdict.OK, v)
        assertEquals(400.0, q!!.widthPx, 1e-6)
    }

    @Test fun `wrong aspect, size, skew and shape are rejected`() {
        assertEquals(QuadVerdict.WRONG_ASPECT, CardGeometry.validate(rect(300.0, 300.0), 1000, cfg).first)
        assertEquals(QuadVerdict.WRONG_ASPECT, CardGeometry.validate(rect(500.0, 200.0), 1000, cfg).first)
        assertEquals(QuadVerdict.TOO_SMALL, CardGeometry.validate(rect(100.0, 63.0), 1000, cfg).first)
        assertEquals(QuadVerdict.TOO_LARGE, CardGeometry.validate(rect(990.0, 624.0), 1000, cfg).first)
        val trapezoid = listOf(Pt(300.0, 200.0), Pt(700.0, 200.0), Pt(640.0, 450.0), Pt(360.0, 450.0))
        assertEquals(QuadVerdict.SKEWED, CardGeometry.validate(trapezoid, 1000, cfg).first)
        assertEquals(QuadVerdict.NOT_FOUR_CORNERS, CardGeometry.validate(rect(400.0, 252.0).take(3), 1000, cfg).first)
        // Corners are re-ordered by angle, so only degenerate (collinear) sets are non-convex.
        val collinear = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(200.0, 0.0), Pt(300.0, 0.0))
        assertEquals(QuadVerdict.NOT_CONVEX, CardGeometry.validate(collinear, 1000, cfg).first)
    }

    @Test fun `largest valid quad wins`() {
        val best = CardGeometry.best(listOf(rect(300.0, 300.0), rect(250.0, 157.6), rect(420.0, 264.8)), 1000, cfg)
        assertEquals(420.0, best!!.widthPx, 1e-6)
        assertNull(CardGeometry.best(listOf(rect(300.0, 300.0)), 1000, cfg))
    }

    @Test fun `forehead math gives iris mm and rejects implausible values`() {
        // Card 428 px wide → 0.2 mm/px; iris 58.5 px → 11.7 mm.
        val ok = ForeheadMath.outcome(428.0, 58.5, cfg) as ForeheadOutcome.Ok
        assertEquals(11.7, ok.irisDiameterMm, 1e-9)
        assertEquals(0.2, ok.cameraMmPerPx, 1e-12)
        assertTrue(ForeheadMath.outcome(428.0, 30.0, cfg) is ForeheadOutcome.Implausible)
        assertTrue(ForeheadMath.outcome(428.0, 80.0, cfg) is ForeheadOutcome.Implausible)
    }

    @Test fun `screen scale arithmetic`() {
        assertEquals(0.1, ScreenScale.mmPerPxFromCardLongSide(856.0, cfg), 1e-12)
        assertEquals(25.4 / 420.0, ScreenScale.mmPerPxFromDpi(420.0), 1e-12)
        assertEquals(856.0, ScreenScale.cardLongSidePx(0.1, cfg), 1e-9)
        assertTrue(ScreenScale.plausible(0.06, cfg))
        assertFalse(ScreenScale.plausible(0.5, cfg))
        assertFalse(ScreenScale.plausible(0.001, cfg))
    }
}

class PersistentCalibrationRepositoryTest {
    private class MapStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    @Test fun `screen scale persists, customer values do not`() {
        val store = MapStore()
        val repo = PersistentCalibrationRepository(store)
        assertNull(repo.calibration.value.screenMmPerPx)
        repo.update { it.copy(screenMmPerPx = 0.0625, screenSource = Source.MEASURED, irisDiameterMm = 12.1, cameraMmPerPx = 0.2) }
        assertEquals(12.1, repo.calibration.value.irisDiameterMm!!, 0.0)

        val reopened = PersistentCalibrationRepository(store).calibration.value
        assertEquals(0.0625, reopened.screenMmPerPx!!, 0.0)
        assertEquals(Source.MEASURED, reopened.screenSource)
        assertNull(reopened.irisDiameterMm)
        assertNull(reopened.cameraMmPerPx)
    }

    @Test fun `clearCustomer keeps the screen scale`() {
        val repo = PersistentCalibrationRepository(MapStore())
        repo.update { it.copy(screenMmPerPx = 0.07, screenSource = Source.MEASURED, irisDiameterMm = 11.5, cameraMmPerPx = 0.19) }
        repo.clearCustomer()
        val c = repo.calibration.value
        assertEquals(0.07, c.screenMmPerPx!!, 0.0)
        assertNull(c.irisDiameterMm)
        assertNull(c.cameraMmPerPx)
    }

    @Test fun `clearAll forgets the stored screen scale`() {
        val store = MapStore()
        val repo = PersistentCalibrationRepository(store)
        repo.update { it.copy(screenMmPerPx = 0.07, screenSource = Source.MEASURED) }
        repo.clearAll()
        assertNull(PersistentCalibrationRepository(store).calibration.value.screenMmPerPx)
        assertTrue(store.map.isEmpty())
    }
}
