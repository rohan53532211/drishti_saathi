package com.drishtisaathi.profile

import com.drishtisaathi.bridge.BridgeProfile
import com.drishtisaathi.bridge.BridgeReview
import com.drishtisaathi.bridge.ConfirmedPower
import com.drishtisaathi.data.RemoteReviewEntity
import com.drishtisaathi.engine.Budget
import com.drishtisaathi.engine.Eye
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.engine.profile.AmslerEyeResult
import com.drishtisaathi.engine.profile.ColourOutcome
import com.drishtisaathi.engine.profile.ColourResult
import com.drishtisaathi.engine.profile.ContrastEyeResult
import com.drishtisaathi.engine.profile.LensCatalog
import com.drishtisaathi.engine.profile.LensEngine
import com.drishtisaathi.engine.profile.ScreeningResults
import com.drishtisaathi.engine.profile.SurveyAnswers
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.pdf.Block
import com.drishtisaathi.pdf.PdfContent
import com.drishtisaathi.vision.PowerRange
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Phase G: the QR payload, the QR code itself, the shared summary lines, the PDF rings, and the confirmed power. */
class QrPayloadTest {
    private val f = ProfileFixtures
    private val engine = LensEngine(LensCatalog.parse(File("src/main/assets/lens_catalog.json").readText()), f.rules.lensEngine)
    private val a = InterviewAnswers(age = 35, visionProblem = VisionProblem.DISTANCE, distanceBlur = true, budget = Budget.MID, redFlagFlashes = false, redFlagOther = false)
    private val survey = SurveyAnswers().with("screenHoursDay", listOf("10")).with("tiredEyes", listOf("OFTEN")).with("driveFrequency", listOf("WEEKLY"))
    private val assisted = PowerRange.lensAssistedEye(PowerRange.farEye(235.0), PowerRange.farEye(444.0), -2.0, f.rules.lensAssisted)!!
    private val screening = ScreeningResults(
        amsler = listOf(AmslerEyeResult(Eye.RIGHT, false, emptyList(), 12, 20, 300.0), AmslerEyeResult(Eye.LEFT, false, emptyList(), 12, 20, 300.0)),
        contrast = listOf(ContrastEyeResult(Eye.RIGHT, 3.0, false), ContrastEyeResult(Eye.LEFT, 12.5, true)),
        colour = ColourResult(ColourOutcome.POSSIBLE_RED_GREEN, 0, 3, 0, 8),
    )
    private val state = f.state(a, survey, QuickEstimate(assisted, PowerRange.farEye(400.0))).copy(screening = screening, sessionId = 12)
    private val view = ProfileView.build(state, f.rules, engine)

    @Test fun `the compact payload has every field of the brief and nothing personal`() {
        val j = QrPayload.json(state, view, f.rules, "1.2")
        assertEquals(setOf("caseId", "ageBand", "tag", "lang", "rings", "eyes", "pd", "screening", "lens", "appVersion"), j.keys)
        assertEquals("S12", j["caseId"]!!.jsonPrimitive.content)
        assertEquals("30-44", j["ageBand"]!!.jsonPrimitive.content)
        assertEquals("ROUTINE", j["tag"]!!.jsonPrimitive.content)
        assertEquals(setOf("work", "digital", "mobility", "leisure"), j["rings"]!!.jsonObject.keys)
        assertEquals("Digital Native", j["rings"]!!.jsonObject["digital"]!!.jsonArray[0].jsonPrimitive.content)
        val r = j["eyes"]!!.jsonArray[0].jsonObject
        assertEquals("R", r["eye"]!!.jsonPrimitive.content)
        assertEquals("-4.0", r["from"]!!.jsonPrimitive.content); assertEquals("-4.5", r["to"]!!.jsonPrimitive.content)
        assertEquals("LENS_ASSISTED", r["method"]!!.jsonPrimitive.content); assertEquals("-2.0", r["lens"]!!.jsonPrimitive.content)
        assertEquals("UNAIDED", j["eyes"]!!.jsonArray[1].jsonObject["method"]!!.jsonPrimitive.content)
        assertEquals("POSSIBLE_RED_GREEN", j["screening"]!!.jsonObject["colour"]!!.jsonPrimitive.content)
        assertEquals("L:12.5:REDUCED", j["screening"]!!.jsonObject["contrast"]!!.jsonArray[1].jsonPrimitive.content)
        assertTrue(j["lens"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("anti_fatigue"))
        assertEquals("1.2", j["appVersion"]!!.jsonPrimitive.content)
        // no name, photo or phone number anywhere; the exact age is not in it either
        assertEquals(emptyList<String>(), BridgeProfile.forbiddenPaths(j))
        assertFalse(j.toString().contains("\"age\""))
        assertTrue("fits a QR code comfortably", j.toString().toByteArray().size < 900)
    }

    @Test fun `age bands and tags`() {
        assertEquals(listOf("?", "<18", "18-29", "30-44", "45-59", "60+"), listOf(null, 10, 18, 44, 45, 60).map(QrPayload::ageBand))
        val urgent = f.state(a.copy(redFlagFlashes = true), survey)
        assertEquals("URGENT", QrPayload.json(urgent, ProfileView.build(urgent, f.rules, engine), f.rules, "v")["tag"]!!.jsonPrimitive.content)
        val child = f.state(a.copy(age = 10), survey)
        val cj = QrPayload.json(child, ProfileView.build(child, f.rules, engine), f.rules, "v")
        assertEquals("CHILD_MYOPIA", cj["tag"]!!.jsonPrimitive.content)
        assertEquals(0, cj["lens"]!!.jsonArray.size)
    }

    @Test fun `with the backend connected the QR is the link to the report, otherwise the offline summary`() {
        val sent = state.copy(remote = RemoteReviewEntity(12, "3fa9c1d2-aaaa", "https://bridge.example/", 1, 1, "WAITING"))
        assertEquals("https://bridge.example/profiles/3fa9c1d2-aaaa", QrPayload.content(sent, view, f.rules, "v", online = true))
        assertTrue(QrPayload.content(sent, view, f.rules, "v", online = false).startsWith("{\"caseId\":\"3FA9C1\""))
        assertTrue("not sent yet: the summary even when online", QrPayload.content(state, view, f.rules, "v", online = true).startsWith("{"))
    }

    /** Reads a QR back from its modules (each module drawn 4 px wide). */
    private fun decode(text: String): String {
        val m = Qr.matrix(text)
        val s = 4; val w = m.width * s; val h = m.height * s
        val src = object : LuminanceSource(w, h) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray { val r = row?.takeIf { it.size >= w } ?: ByteArray(w); for (x in 0 until w) r[x] = if (m[x / s, y / s]) 0 else -1; return r }
            override fun getMatrix(): ByteArray = ByteArray(w * h) { i -> if (m[(i % w) / s, (i / w) / s]) 0 else -1 }
        }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src))).text
    }

    @Test fun `the QR code encodes and reads back, Hindi included`() {
        val json = QrPayload.json(state, view, f.rules, "1.2").toString()
        assertEquals(json, decode(json))
        assertEquals(Json.parseToJsonElement(json), Json.parseToJsonElement(decode(json)))
        assertEquals("दृष्टि साथी https://x.example/profiles/abc", decode("दृष्टि साथी https://x.example/profiles/abc"))
    }

    @Test fun `the shared summary has the tag, rings, the method line, screening and the lens family - and the labels`() {
        val lines = QrPayload.shareLines(state, view, f.rules).map { it.en }
        assertTrue(lines.toString(), lines.first().startsWith("Date: ") && lines[1].contains("ROUTINE"))
        assertTrue(lines.any { it.startsWith("Digital: Digital Native") })
        assertTrue(lines.any { it.contains("Measured through −2.00 D lens, residual −2.25 D, total −4.25 D") })
        assertTrue(lines.any { it.startsWith("Colour: Possible red-green deficiency") })
        assertTrue("Suggested ZEISS lens family – optician confirms" in lines)
        assertTrue(lines.indexOf("Screening, not a diagnosis") in 1 until lines.indexOfFirst { it.startsWith("Grid R") })
        assertEquals("the disclaimer once, at the end", com.drishtisaathi.i18n.S.disclaimer.en, lines.last())
        val urgent = f.state(a.copy(redFlagOther = true), survey)
        val ul = QrPayload.shareLines(urgent, ProfileView.build(urgent, f.rules, engine), f.rules).map { it.en }
        assertTrue("Please see an eye doctor today" in ul)
        assertFalse(ul.any { it.contains("ZEISS") })
    }

    @Test fun `the shop PDF draws the four rings`() {
        val rings = PdfContent.shopCopy(state, f.rules, f.ctx, Lang.EN, view = view).filterIsInstance<Block.Rings>().single().rings
        assertEquals(listOf("Work", "Digital", "Mobility", "Leisure"), rings.map { it.title })
        assertEquals(100, rings[1].score); assertEquals("Digital Native", rings[1].label)
        assertEquals("a ring nobody answered is drawn empty", -1, rings[0].score)
        assertEquals(0x2F80ED, rings[0].rgb)
    }

    @Test fun `the optometrist's confirmed power comes back with the advice`() {
        val rv = Json { ignoreUnknownKeys = true }.decodeFromString(BridgeReview.serializer(),
            """{"status":"REVIEWED","advice":"Confirmed with trial lenses.","needsInPersonExam":false,"reviewedAt":5,"confirmedPower":{"right":"-4.25 / -0.50 x 180","left":"-4.00"}}""")
        assertEquals(ConfirmedPower("-4.25 / -0.50 x 180", "-4.00"), rv.confirmedPower)
        assertEquals("Confirmed with trial lenses.\nConfirmed by the optometrist: R -4.25 / -0.50 x 180 · L -4.00",
            rv.adviceWithPower { com.drishtisaathi.i18n.SProfile.confirmedByOptometrist.format(it).en })
        val plain = BridgeReview("REVIEWED", "ok")
        assertEquals("ok", plain.adviceWithPower { it })
        assertNull(ConfirmedPower().line())
    }
}
