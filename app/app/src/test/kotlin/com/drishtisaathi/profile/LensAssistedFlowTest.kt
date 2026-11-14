package com.drishtisaathi.profile

import com.drishtisaathi.data.Measurements
import com.drishtisaathi.data.PairedRecords
import com.drishtisaathi.data.SessionCodec
import com.drishtisaathi.data.SessionEntity
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.QuickEye
import com.drishtisaathi.engine.QuickTarget
import com.drishtisaathi.engine.StaffMeasurements
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.engine.profile.PowerMethod
import com.drishtisaathi.engine.vision.DeviceFit
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SProfile
import com.drishtisaathi.pdf.PdfContent
import com.drishtisaathi.vision.PowerRange
import com.drishtisaathi.vision.quickEyeText
import com.drishtisaathi.vision.quickMethodText
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase B in the app: the lens-assisted eye, how it is worded on every output, the number pad, the learning loop. */
class LensAssistedFlowTest {
    private val f = ProfileFixtures
    private val cfg = f.rules.lensAssisted

    /** First pass 23.5 cm (−4.25 D), then through a −2.00 lens the letters blur at 44 cm. */
    private val first = PowerRange.farEye(235.0)
    private val through = PowerRange.farEye(444.0)
    private val assisted = PowerRange.lensAssistedEye(first, through, -2.0, cfg)!!

    @Test fun `the first pass of minus 4_25 switches to the lens and the total comes out at minus 4_25`() {
        assertEquals(-4.25, first.seD!!, 1e-9)
        assertTrue(com.drishtisaathi.engine.profile.LensAssisted.needed(first.seD, cfg))
        assertEquals(PowerMethod.LENS_ASSISTED, assisted.method)
        assertEquals(-2.0, assisted.lensD!!, 0.0)
        assertEquals(-2.25, assisted.residualD!!, 1e-9)
        assertEquals(-4.25, assisted.seD!!, 1e-9)
        assertEquals(-4.0, assisted.rangeFromD!!, 1e-9); assertEquals(-4.5, assisted.rangeToD!!, 1e-9)
        assertEquals(-4.25, assisted.unaidedSeD!!, 1e-9)
        assertEquals("the raw residual is kept for the learning loop", -100.0 / 44.0, assisted.residualRawD!!, 1e-9)
    }

    @Test fun `every output states the method with the lens, the residual, the total and the range`() {
        val line = quickEyeText(assisted).en
        assertEquals("Measured through −2.00 D lens, residual −2.25 D, total −4.25 D (range −4.00 to −4.50)", line)
        assertEquals("through −2.00 D lens", quickMethodText(assisted).en)
        assertEquals("without a lens", quickMethodText(PowerRange.farEye(400.0)).en)
        assertTrue(quickEyeText(assisted).hi.contains("−4.25"))

        val a = InterviewAnswers(age = 30, visionProblem = VisionProblem.DISTANCE, distanceBlur = true, redFlagFlashes = false, redFlagOther = false)
        val st = f.state(a, quick = QuickEstimate(assisted, PowerRange.farEye(400.0)))
        val shop = f.texts(PdfContent.shopCopy(st, f.rules, f.ctx, Lang.EN))
        assertTrue(shop.toString(), shop.any { it == "R: $line" })
        val p = com.drishtisaathi.bridge.BridgeProfile.build(st, f.rules, f.consent, "t")
        assertEquals(line, p["power"]!!.jsonObject["phoneRange"]!!.jsonObject["right"]!!.jsonPrimitive.content)
        val stored = p["power"]!!.jsonObject["phoneEstimate"]!!.jsonObject["right"]!!.jsonObject
        assertEquals("LENS_ASSISTED", stored["method"]!!.jsonPrimitive.content)
        assertEquals("-2.0", stored["lensD"]!!.jsonPrimitive.content)
        // the engine uses the total as this eye's power
        assertEquals(-4.25, st.result!!.assessment.advice!!.eyes.first().startingTrialLens.value.sph, 1e-9)
    }

    @Test fun `letters sharp at every distance through the lens - too strong, no result`() {
        val sharp = QuickEye(null, null, QuickTarget.WHITE, noMyopiaAtReach = true)
        assertNull(PowerRange.lensAssistedEye(first, sharp, -4.0, cfg))
    }

    @Test fun `beyond minus 8 D the note is added, with or without a lens`() {
        val strong = PowerRange.farEye(115.0) // −8.75 D
        assertTrue(PowerRange.unaided(strong, cfg).highMyopia)
        assertTrue(quickEyeText(PowerRange.unaided(strong, cfg)).en.endsWith(SProfile.highMyopiaNote.en))
        val e = PowerRange.lensAssistedEye(strong, PowerRange.farEye(400.0), -6.5, cfg)!!   // −6.03 + −2.50
        assertEquals(-8.5, e.seD!!, 1e-9)
        assertTrue(e.highMyopia)
        assertTrue(quickEyeText(e).en.contains("High myopia – optometrist must confirm; eye-health check advised"))
        assertFalse(PowerRange.unaided(first, cfg).highMyopia)
    }

    @Test fun `the number pad types a minus lens`() {
        var t = ""
        for (k in listOf("2", ".", "5", "0", "9")) t = padType(t, k)
        assertEquals("2.50", t)
        assertEquals(-2.5, padLensPower(t)!!, 0.0)
        assertEquals("2.5", padType(t, "<"))
        assertEquals("12", padType(padType(padType("", "1"), "2"), "3"))
        assertEquals("", padType("", "."))
        assertNull(padLensPower(""))
        assertEquals(0.0, padLensPower("0")!!, 0.0)
    }

    @Test fun `learning loop - a lens-assisted eye gives a pair for the residual only and the session stores lens and method`() {
        val m = Measurements(quick = QuickEstimate(assisted, null))
        val row = SessionEntity(
            id = 3, createdAt = 0, lang = "HI", phoneModel = "x", answersJson = SessionCodec.encode(InterviewAnswers()),
            staffJson = SessionCodec.encode(StaffMeasurements()), resultJson = "{}", referralLevel = "NONE",
            finalTrialRight = "-4.50", verifiedAt = 1L, measurementsJson = SessionCodec.encode(m),
        )
        val back = SessionCodec.measurements(row).quick!!.right!!
        assertEquals(PowerMethod.LENS_ASSISTED, back.method); assertEquals(-2.0, back.lensD!!, 0.0)

        val pairs = PairedRecords.from(listOf(row), cfg)
        assertEquals(1, pairs.size)
        assertEquals(-100.0 / 44.0, pairs[0].measuredSeD, 1e-9)      // what the phone read through the lens
        assertEquals(-2.5, pairs[0].finalSeD, 1e-9)                   // final −4.50 minus the −2.00 lens
        assertEquals("without the lens rules nothing is paired", 0, PairedRecords.from(listOf(row)).size)

        // a fit from such pairs moves the residual, not the lens
        val fit = DeviceFit(intercept = -0.25, slope = 1.0, sessions = 5, eyes = 5, residualSdD = 0.1, maxCorrectionD = 1.0)
        val corrected = PowerRange.lensAssistedEye(first, through, -2.0, cfg, fit)!!
        assertEquals(-2.5, corrected.residualD!!, 1e-9)
        assertEquals(-4.5, corrected.seD!!, 1e-9)
    }
}
