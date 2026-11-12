package com.drishtisaathi.data

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.MeasuredEye
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.StaffMeasurements
import com.drishtisaathi.engine.VisionTests
import com.drishtisaathi.engine.vision.DeviceCorrection
import com.drishtisaathi.engine.vision.DeviceFit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Learning loop storage: which stored sessions become paired records, the Room row, and the v3 → v4 migration. */
class DeviceCorrectionStoreTest {
    private val rules = RulesConfig.parse(File("src/main/assets/rules.json").readText())

    private fun row(
        id: Long, right: Double?, left: Double?, finalR: String?, finalL: String?,
        verified: Boolean = true, demo: Boolean = false, tests: Boolean = true, noMyopiaRight: Boolean = false,
    ) = SessionEntity(
        id = id, createdAt = 0, lang = "HI", phoneModel = "samsung SM-S711B",
        answersJson = SessionCodec.encode(InterviewAnswers()), staffJson = SessionCodec.encode(StaffMeasurements()),
        resultJson = "{}", referralLevel = "NONE",
        finalTrialRight = finalR, finalTrialLeft = finalL, verifiedAt = if (verified) 1L else null,
        measurementsJson = SessionCodec.encode(Measurements(
            tests = if (!tests) null else VisionTests(
                right = right?.let { MeasuredEye(it, Confidence.HIGH, noMyopiaAtArmsLength = noMyopiaRight) },
                left = left?.let { MeasuredEye(it, Confidence.HIGH) },
            ),
            demo = demo,
        )),
    )

    @Test fun `verified session gives one pair per readable eye`() {
        val p = PairedRecords.from(listOf(row(1, -2.0, -1.5, "-2.25 / -0.50 x 180", "-1.75")))
        assertEquals(2, p.size)
        assertEquals(-2.0, p[0].measuredSeD, 0.0); assertEquals(-2.5, p[0].finalSeD, 1e-9)
        assertEquals(-1.5, p[1].measuredSeD, 0.0); assertEquals(-1.75, p[1].finalSeD, 1e-9)
    }

    @Test fun `unverified, demo, no tests, no-myopia, missing or unreadable lens are skipped`() {
        val skipped = listOf(
            row(1, -2.0, -2.0, "-2.0", "-2.0", verified = false),
            row(2, -2.0, -2.0, "-2.0", "-2.0", demo = true),
            row(3, -2.0, -2.0, "-2.0", "-2.0", tests = false),
            row(4, null, null, "-2.0", "-2.0"),
        )
        assertEquals(0, PairedRecords.from(skipped).size)
        // per eye: right is "no myopia at arm's length", left lens text is unreadable
        assertEquals(0, PairedRecords.from(listOf(row(5, -2.0, -2.0, "-2.0", "see notes", noMyopiaRight = true))).size)
        assertEquals(1, PairedRecords.from(listOf(row(6, -2.0, -2.0, null, "-2.5"))).size)
    }

    @Test fun `5 verified sessions on the phone give a fit, 4 plus a demo do not`() {
        val real = (1..5L).map { row(it, -1.0 - it * 0.5, -1.25 - it * 0.5, "${-1.5 - it * 0.5}", "${-1.75 - it * 0.5}") }
        val fit = DeviceCorrection.fit(PairedRecords.from(real), rules.deviceCorrection)!!
        assertEquals(5, fit.sessions)
        assertEquals(-0.5, fit.intercept, 1e-9)
        assertEquals(1.0, fit.slope, 1e-9)
        val fourAndDemo = real.take(4) + row(9, -3.0, -3.0, "-3.5", "-3.5", demo = true)
        assertNull(DeviceCorrection.fit(PairedRecords.from(fourAndDemo), rules.deviceCorrection))
    }

    @Test fun `Room row round-trips the fit`() {
        val f = DeviceFit(-0.4, 1.05, 6, 11, 0.3, 1.0)
        val e = DeviceCorrectionEntity.from("samsung SM-S711B", f, 42L)
        assertEquals("samsung SM-S711B", e.phoneModel)
        assertEquals(42L, e.fittedAt)
        assertEquals(f, e.toFit())
    }

    // ---- schema / migration ------------------------------------------------------------------

    private fun schema(v: Int) = Json.parseToJsonElement(File("schemas/com.drishtisaathi.data.AppDatabase/$v.json").readText())
        .jsonObject["database"]!!.jsonObject
    private fun createSql(v: Int, table: String): String? = schema(v)["entities"]!!.jsonArray
        .map { it.jsonObject }
        .firstOrNull { it["tableName"]!!.jsonPrimitive.content == table }
        ?.get("createSql")?.jsonPrimitive?.content?.replace("\${TABLE_NAME}", table)

    @Test fun `migration 3 to 4 creates exactly the table Room expects and leaves sessions alone`() {
        assertEquals(4, schema(4)["version"]!!.jsonPrimitive.content.toInt())
        assertNull(createSql(3, "device_corrections"))
        assertEquals(createSql(4, "device_corrections"), AppDatabase.CREATE_DEVICE_CORRECTIONS)
        assertNotNull(createSql(3, "sessions"))
        assertEquals(createSql(3, "sessions"), createSql(4, "sessions"))
        assertEquals(3, AppDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, AppDatabase.MIGRATION_3_4.endVersion)
    }
}
