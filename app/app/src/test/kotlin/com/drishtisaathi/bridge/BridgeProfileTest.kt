package com.drishtisaathi.bridge

import com.drishtisaathi.UiState
import com.drishtisaathi.data.AppDatabase
import com.drishtisaathi.demo.DemoCustomers
import com.drishtisaathi.engine.DrishtiEngine
import com.drishtisaathi.engine.EyeRx
import com.drishtisaathi.engine.KnownRx
import com.drishtisaathi.engine.PowerKnown
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.QuickEye
import com.drishtisaathi.engine.QuickTarget
import com.drishtisaathi.engine.RulesConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Remote Optometrist Bridge: the profile the app sends, its privacy guarantees, and the v4 → v5 migration. */
class BridgeProfileTest {
    private val a = "src/main/assets/"
    private val rules by lazy { RulesConfig.parse(File(a + "rules.json").readText()) }
    private val engine by lazy {
        DrishtiEngine.fromJson(File(a + "rules.json").readText(), File(a + "anthro_priors.json").readText(),
            File(a + "frames.json").readText(), File(a + "frames/more_frames.json").readText())
    }
    private val consent = BridgeConsent(1_762_934_400_000, spoken = true, tapped = true)

    private fun stateOf(c: com.drishtisaathi.demo.DemoCustomer, quick: QuickEstimate? = null): UiState {
        val tests = c.tests ?: com.drishtisaathi.engine.VisionTests()
        return UiState(answers = c.answers, face = c.face, tests = tests, testsDone = c.tests != null, demo = true, quick = quick,
            result = engine.evaluate(c.answers, tests = tests, face = c.face, quick = quick))
    }

    private fun strings(e: JsonElement): List<String> = when (e) {
        is JsonObject -> e.values.flatMap(::strings)
        is JsonArray -> e.flatMap(::strings)
        is JsonPrimitive -> if (e.isString) listOf(e.content) else emptyList()
    }

    private fun keys(e: JsonElement): List<String> = when (e) {
        is JsonObject -> e.keys.toList() + e.values.flatMap(::keys)
        is JsonArray -> e.flatMap(::keys)
        else -> emptyList()
    }

    @Test fun `every demo profile is free of names, photos and contacts, and within the server limits`() {
        for (c in DemoCustomers.all) {
            val p = BridgeProfile.build(stateOf(c), rules, consent, "test")
            assertEquals(c.id, emptyList<String>(), BridgeProfile.forbiddenPaths(p))
            assertTrue(strings(p).all { it.length <= 2000 })
            assertTrue(p.toString().toByteArray().size < 256 * 1024)
            assertEquals(setOf("consent", "app", "demo", "answers", "functionalProfile", "powerSource", "power", "measurements", "estimates"), p.keys)
            // the ranked frame list (names, prices) is never sent
            assertFalse(keys(p).any { it == "frames" || it == "priceInr" || it == "overlay" })
        }
    }

    @Test fun `the guard finds forbidden keys at any depth`() {
        val bad = Json.parseToJsonElement("""{"a":{"b":[{"customer_name":"x"}]},"Photo":1,"phoneModel":"ok"}""")
        assertEquals(listOf("a.b[0].customer_name", "Photo"), BridgeProfile.forbiddenPaths(bad))
    }

    @Test fun `consent must be spoken and tapped`() {
        val s = stateOf(DemoCustomers.youngMyope)
        assertThrows(IllegalArgumentException::class.java) { BridgeProfile.build(s, rules, consent.copy(spoken = false), "t") }
        assertThrows(IllegalArgumentException::class.java) { BridgeProfile.build(s, rules, consent.copy(tapped = false), "t") }
        val c = BridgeProfile.build(s, rules, consent, "t")["consent"]!!.jsonObject
        assertEquals("true", c["spoken"]!!.jsonPrimitive.content)
        assertEquals("true", c["tapped"]!!.jsonPrimitive.content)
        assertEquals(consent.consentAt.toString(), c["consentAt"]!!.jsonPrimitive.content)
    }

    @Test fun `power source and values travel with the profile`() {
        val c = DemoCustomers.youngMyope
        val known = c.copy(answers = c.answers.copy(powerKnown = PowerKnown.YES, wearsGlasses = true, glassesAgeYears = 1,
            knownRx = KnownRx(EyeRx(-2.0, 0.0, null, null), EyeRx(-1.75, 0.0, null, null))), tests = null)
        val p = BridgeProfile.build(stateOf(known), rules, consent, "t")
        assertEquals("KNOWN_POWER", p["powerSource"]!!.jsonPrimitive.content)
        val power = p["power"]!!.jsonObject
        assertEquals(2, power["eyes"]!!.jsonArray.size)
        assertEquals("-2.0", power["knownPower"]!!.jsonObject["right"]!!.jsonObject["sph"]!!.jsonPrimitive.content)

        val q = QuickEstimate(QuickEye(-2.5, 40.0, QuickTarget.WHITE), QuickEye(-2.25, 44.0, QuickTarget.WHITE))
        val est = c.copy(answers = c.answers.copy(powerKnown = PowerKnown.NOT_SURE), tests = null)
        val p2 = BridgeProfile.build(stateOf(est, q), rules, consent, "t")
        assertEquals("PHONE_ESTIMATE", p2["powerSource"]!!.jsonPrimitive.content)
        assertEquals("-2.5", p2["power"]!!.jsonObject["phoneEstimate"]!!.jsonObject["right"]!!.jsonObject["seD"]!!.jsonPrimitive.content)
        assertTrue(p2["functionalProfile"]!!.jsonObject.containsKey("dominant"))
        // a sample for the backend's contract test (backend/tests/fixtures/app_profile.json is generated from this)
        File("build/bridge").mkdirs()
        File("build/bridge/sample_profile.json").writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), p2))
    }

    @Test fun `urgent sessions send the referral and no power`() {
        val p = BridgeProfile.build(stateOf(DemoCustomers.urgentFlashes), rules, consent, "t")
        assertEquals("URGENT", p["estimates"]!!.jsonObject["referralLevel"]!!.jsonPrimitive.content)
        assertTrue(p["powerSource"] is kotlinx.serialization.json.JsonNull)
    }

    @Test fun `server address is trimmed and must be http or https`() {
        assertEquals("https://x.trycloudflare.com", BridgeConfig(" https://x.trycloudflare.com/ ").baseUrl)
        assertTrue(BridgeConfig("http://192.168.1.5:8000").isValid)
        assertFalse(BridgeConfig("192.168.1.5:8000").isValid)
        assertFalse(BridgeConfig("").isValid)
    }

    @Test fun `client without a valid address fails as not configured, without network`() {
        val e = assertThrows(BridgeException::class.java) { BridgeClient(BridgeConfig("")).health() }
        assertEquals(BridgeException.Kind.NOT_CONFIGURED, e.kind)
    }

    @Test fun `forbidden key list matches the server`() {
        val py = File("../../backend/app/main.py").readText()
        val block = py.substringAfter("FORBIDDEN_KEYS = {").substringBefore("}")
        val server = Regex("\"([^\"]+)\"").findAll(block).map { it.groupValues[1].replace("_", "") }.toSet()
        assertEquals(server, BridgeProfile.forbiddenKeys)
    }

    @Test fun `case number matches the one the optometrist's page shows`() {
        assertEquals("3F9A0C", BridgeProfile.caseId("3f9a0c7d1e2b4a5c8d9e0f1a2b3c4d5e"))
        val e = com.drishtisaathi.data.RemoteReviewEntity(1, "ab12cdef", "http://x", 0, 0, "WAITING")
        assertEquals("AB12CD", e.caseId)
        // backend/app/main.py: case_id(pid) = pid[:6].upper()
        assertTrue(File("../../backend/app/main.py").readText().contains("return pid[:6].upper()"))
    }

    // ---- Room v5 ----
    private fun schema(v: Int) = Json.parseToJsonElement(File("schemas/com.drishtisaathi.data.AppDatabase/$v.json").readText())
        .jsonObject["database"]!!.jsonObject
    private fun createSql(v: Int, table: String): String? = schema(v)["entities"]!!.jsonArray.map { it.jsonObject }
        .firstOrNull { it["tableName"]!!.jsonPrimitive.content == table }
        ?.get("createSql")?.jsonPrimitive?.content?.replace("\${TABLE_NAME}", table)

    @Test fun `migration 4 to 5 creates exactly the table Room expects and leaves the others alone`() {
        assertEquals(5, schema(5)["version"]!!.jsonPrimitive.content.toInt())
        assertNull(createSql(4, "remote_reviews"))
        assertEquals(createSql(5, "remote_reviews"), AppDatabase.CREATE_REMOTE_REVIEWS)
        for (t in listOf("sessions", "device_corrections")) assertEquals(createSql(4, t), createSql(5, t))
        assertEquals(4, AppDatabase.MIGRATION_4_5.startVersion)
        assertEquals(5, AppDatabase.MIGRATION_4_5.endVersion)
    }
}
