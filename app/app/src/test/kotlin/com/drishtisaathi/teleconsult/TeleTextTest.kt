package com.drishtisaathi.teleconsult

import com.drishtisaathi.bridge.BridgeConsent
import com.drishtisaathi.bridge.BridgeProfile
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.i18n.STele
import com.drishtisaathi.profile.ProfileFixtures
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Teleconsult, patient side: the form rules, the wording, what the app reads from the server and what it sends. */
class TeleTextTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `a mobile number is 10 digits, with or without plus 91 or a leading zero`() {
        assertEquals("9876543210", TeleText.phoneDigits("98765 43210"))
        assertEquals("9876543210", TeleText.phoneDigits("+91 98765-43210"))
        assertEquals("9876543210", TeleText.phoneDigits("09876543210"))
        assertNull(TeleText.phoneDigits("12345")); assertNull(TeleText.phoneDigits("")); assertNull(TeleText.phoneDigits("987654321012345"))
    }

    @Test fun `registration needs a name, a phone, a password of 8, an age and the consent box`() {
        assertEquals(STele.errName, TeleText.registrationProblem(" ", "9876543210", "password1", "34", true))
        assertEquals(STele.errPhone, TeleText.registrationProblem("Asha", "98765", "password1", "34", true))
        assertEquals(STele.errPassword, TeleText.registrationProblem("Asha", "9876543210", "short", "34", true))
        assertEquals(STele.errAge, TeleText.registrationProblem("Asha", "9876543210", "password1", "", true))
        assertEquals(STele.errAge, TeleText.registrationProblem("Asha", "9876543210", "password1", "150", true))
        assertEquals("no consent, no account", STele.consentNeeded, TeleText.registrationProblem("Asha", "9876543210", "password1", "34", false))
        assertNull(TeleText.registrationProblem("Asha", "9876543210", "password1", "34", true))
        assertEquals(STele.errPhone, TeleText.loginProblem("", "x")); assertNull(TeleText.loginProblem("9876543210", "x"))
        // the sentence the server stores is the one the patient ticks
        assertEquals("I agree my report and name are shared with the optometrist I choose", STele.consent.en)
    }

    @Test fun `the server's consultation, messages and advice are read as sent`() {
        val k = json.decodeFromString(TeleConsultation.serializer(), """{"id":"abc123def","caseId":"ABC123","status":"IN_PROGRESS","priority":"URGENT",
            "createdAt":1763164800000,"updatedAt":1763164900000,"acceptedAt":1763164850000,"fee":200,"paymentStatus":"not_required (demo)",
            "videoUrl":"https://meet.jit.si/drishti-abcdefghijkl","requestedOptometristId":null,
            "patient":{"id":"p1","role":"patient","name":"Asha","age":34},
            "optometrist":{"id":"o1","role":"optometrist","name":"DEMO Dr. Meera Sharma","qualification":"M.Optom","registrationNo":"DEMO-OPT-0001",
              "clinicName":"DEMO Netra Eye Care","city":"Kanpur","languages":["Hindi","English"],"fee":200,"available":true,"allowWhatsapp":true,"photo":null,"demo":true},
            "hasPrescription":true,"messageCount":4,"lastMessageId":9,"redFlagReasons":["New flashes of light"],
            "urgentNote":"A teleconsult never replaces an urgent visit. Please see an eye doctor today.","whatsappUrl":"https://wa.me/919000000101?text=Hello","report":{"triage":{"tag":"URGENT"}}}""")
        assertTrue(k.urgent); assertTrue(k.canChat); assertFalse(k.closed); assertTrue(k.hasPrescription)
        assertEquals("DEMO Dr. Meera Sharma", k.optometrist!!.name)
        assertEquals("M.Optom · DEMO Netra Eye Care, Kanpur · Hindi, English", TeleText.optometristLine(k.optometrist!!))
        assertEquals(listOf("New flashes of light"), k.redFlagReasons)
        assertFalse("no chat before an optometrist accepts", k.copy(status = "REQUESTED").canChat)
        assertFalse(k.copy(status = "CLOSED").canChat); assertTrue(k.copy(status = "COMPLETED").canChat)

        val msgs = json.decodeFromString(ListSerializer(TeleMessage.serializer()), """[{"id":7,"senderRole":"system","senderId":null,"kind":"SYSTEM","text":"accepted","hasImage":false,"createdAt":1},
            {"id":8,"senderRole":"optometrist","senderId":"o1","kind":"VIDEO","text":"https://meet.jit.si/drishti-abcdefghijkl","hasImage":false,"createdAt":2}]""")
        assertEquals(listOf(7L, 8L), msgs.map { it.id }); assertEquals("VIDEO", msgs[1].kind)

        val a = json.decodeFromString(TeleAdvice.serializer(), """{"right":{"sph":-2.25,"cyl":-0.5,"axis":180},"left":{"sph":-2.0,"cyl":null,"axis":null},"add":1.5,"pdMm":62.5,
            "lensAdvice":["ZEISS Single Vision","DuraVision"],"referralAdvice":"Full eye exam within a month","needsInPersonExam":true,"followUpDate":"2026-12-15",
            "notes":"Wear for distance","optometristName":"DEMO Dr. Meera Sharma","registrationNo":"DEMO-OPT-0001","qualification":"M.Optom","clinic":"DEMO Netra Eye Care, Kanpur","createdAt":3}""")
        assertEquals(
            listOf("Right eye: −2.25 / −0.50 × 180", "Left eye: −2.00 DS", "Near addition: +1.50 D", "PD: 62.5 mm", "Lens advice: ZEISS Single Vision, DuraVision",
                "An in-person eye examination is advised.", "Referral: Full eye exam within a month", "Follow-up: 2026-12-15", "Notes: Wear for distance",
                "DEMO Dr. Meera Sharma, registration no. DEMO-OPT-0001"),
            TeleText.adviceLines(a).map { it.en })
        assertTrue(TeleText.adviceLines(a).all { it.hi.isNotBlank() })
        assertEquals("only what was written is shown", listOf("Left eye: −1.00 DS"), TeleText.adviceLines(TeleAdvice(left = TeleEye(sph = -1.0))).map { it.en })
    }

    @Test fun `every status has words in both languages, and an unknown one is still shown`() {
        assertEquals(setOf("REQUESTED", "ACCEPTED", "IN_PROGRESS", "COMPLETED", "CLOSED"), STele.statusCodes)
        STele.statusCodes.forEach { assertTrue(it, STele.status(it).en.isNotBlank() && STele.status(it).hi.isNotBlank()) }
        assertEquals("Status: ON_HOLD", STele.status("ON_HOLD").en)
    }

    @Test fun `only the server's call and WhatsApp links are opened`() {
        assertEquals("https://meet.jit.si/drishti-abcdefghijkl", TeleText.safeLink("https://meet.jit.si/drishti-abcdefghijkl"))
        assertEquals("https://wa.me/919000000101?text=Hi", TeleText.safeLink("https://wa.me/919000000101?text=Hi"))
        for (bad in listOf("http://meet.jit.si/x", "https://evil.example/meet.jit.si/", "intent://x", "file:///sdcard/a", "https://meet.jit.si.evil.example/x", null))
            assertNull(bad, TeleText.safeLink(bad))
    }

    @Test fun `errors are worded for the patient`() {
        assertEquals(STele.errLogin, TeleText.error(TeleException(TeleException.Kind.UNAUTHORIZED), login = true))
        assertEquals(STele.errSession, TeleText.error(TeleException(TeleException.Kind.UNAUTHORIZED)))
        assertEquals(STele.errTaken, TeleText.error(TeleException(TeleException.Kind.CONFLICT), login = true))
        assertEquals(STele.errNotConfigured, TeleText.error(TeleException(TeleException.Kind.NOT_CONFIGURED)))
        assertEquals(STele.errNetwork, TeleText.error(TeleException(TeleException.Kind.NETWORK, "timeout")))
        assertEquals("The server could not do this (phone must have 10 digits).", TeleText.error(TeleException(TeleException.Kind.INVALID, "phone must have 10 digits")).en)
        assertEquals("this phone number is already registered", TeleClient.detailOf("""{"detail":"this phone number is already registered"}"""))
        assertEquals("", TeleClient.detailOf("<html>502</html>"))
        // no address set: the call fails before anything is sent
        val e = runCatching { TeleClient("").optometrists() }.exceptionOrNull() as TeleException
        assertEquals(TeleException.Kind.NOT_CONFIGURED, e.kind)
    }

    @Test fun `the report sent with a request is the optometrist report - no name, photo or phone inside it`() {
        val f = ProfileFixtures
        val urgent = InterviewAnswers(age = 58, visionProblem = VisionProblem.DISTANCE, distanceBlur = true, redFlagFlashes = true, redFlagOther = false)
        val report = BridgeProfile.build(f.state(urgent), f.rules, BridgeConsent(1_763_164_800_000, spoken = true, tapped = true), "1.2")
        assertEquals(emptyList<String>(), BridgeProfile.forbiddenPaths(report))
        // the server reads its priority from the same tag
        assertEquals("URGENT", report["triage"]!!.jsonObject["tag"]!!.jsonPrimitive.content)
        assertTrue(report.toString().length < 256 * 1024)
    }
}
