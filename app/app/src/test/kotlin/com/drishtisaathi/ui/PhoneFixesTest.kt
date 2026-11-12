package com.drishtisaathi.ui

import com.drishtisaathi.data.DbEncryption
import com.drishtisaathi.data.Measurements
import com.drishtisaathi.data.SessionCodec
import com.drishtisaathi.data.SessionEntity
import com.drishtisaathi.engine.EyeRx
import com.drishtisaathi.engine.Referral
import com.drishtisaathi.engine.ReferralLevel
import com.drishtisaathi.engine.ReferralReason
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.SFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fixes from phone testing (12 Nov). */
class PhoneFixesTest {

    // ---- 2. axis 0..180 in steps of 5, only with a cylinder ----
    @Test fun `axis steps by 5 and stays within 0 to 180`() {
        assertEquals(180, EyeDraft.stepAxis(180, up = true))
        assertEquals(175, EyeDraft.stepAxis(180, up = false))
        assertEquals(0, EyeDraft.stepAxis(0, up = false))
        assertEquals(5, EyeDraft.stepAxis(0, up = true))
        var a = 180
        repeat(40) { a = EyeDraft.stepAxis(a, up = false); assertEquals(0, a % 5) }
        assertEquals(0, a)
    }

    @Test fun `axis is saved only when the cylinder is not 0`() {
        assertEquals(EyeRx(-1.75, -0.5, 90, null), EyeDraft(-1.75, -0.5, 90).toRx())
        assertNull(EyeDraft(-1.75, 0.0, 90).toRx().axis)
        assertEquals(EyeDraft(-1.0, -0.75, 45, 1.5), EyeDraft.from(EyeRx(-1.0, -0.75, 45, 1.5)))
    }

    // ---- 3. all zeros asks first ----
    @Test fun `all-zero detection`() {
        assertTrue(EyeDraft().allZero)
        assertFalse(EyeDraft(sph = -0.25).allZero)
        assertFalse(EyeDraft(cyl = -0.25).allZero)
        assertFalse(EyeDraft(add = 1.0).allZero)
        assertEquals("No numbers available?", S.allZeroTitle.en)
    }

    // ---- 4. urgent and routine reasons in separate groups ----
    @Test fun `routine reasons are grouped apart from urgent ones`() {
        val r = Referral(
            ReferralLevel.URGENT,
            listOf(ReferralReason.FLASHES_FLOATERS_CURTAIN, ReferralReason.ASTIGMATISM_FLAG, ReferralReason.OLD_GLASSES_WITH_STRAIN),
            adviceWithheld = true,
        )
        val g = ReferralGroups.of(r)
        assertEquals(listOf(ReferralReason.FLASHES_FLOATERS_CURTAIN), g.urgent)
        assertEquals(listOf(ReferralReason.ASTIGMATISM_FLAG, ReferralReason.OLD_GLASSES_WITH_STRAIN), g.routine)
        assertTrue(g.urgent.all { it.urgent } && g.routine.none { it.urgent })
        assertEquals("Routine", S.routineGroup.en)
    }

    // ---- 5. glasses-on override is stored with the session ----
    @Test fun `glasses-on override round-trips and old rows default to glasses off`() {
        val e = SessionEntity(
            createdAt = 0, lang = "HI", phoneModel = "x", answersJson = "{}", staffJson = "{}", resultJson = "{}",
            referralLevel = "NONE", measurementsJson = SessionCodec.encode(Measurements(glassesOn = true)),
        )
        assertTrue(SessionCodec.measurements(e).glassesOn)
        assertFalse(SessionCodec.measurements(e.copy(measurementsJson = """{"demo":false}""")).glassesOn)
        assertTrue(SFlow.glassesOffTitle.en.contains("glasses"))
    }

    // ---- 8. demo catalogue label ----
    @Test fun `demo catalogue label wording`() {
        assertEquals("Demo catalogue: sizes are typical, not measured", S.demoCatalogue.en)
    }

    // ---- 7. database encryption helpers ----
    @Test fun `plain SQLite header is recognised, anything else counts as encrypted`() {
        val plain = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(84)
        assertEquals(DbEncryption.FileState.PLAIN, DbEncryption.stateOf(plain))
        assertEquals(DbEncryption.FileState.ENCRYPTED, DbEncryption.stateOf(ByteArray(100) { (it * 37 + 11).toByte() }))
        assertEquals(DbEncryption.FileState.MISSING, DbEncryption.stateOf(ByteArray(0)))
        assertEquals(DbEncryption.FileState.MISSING, DbEncryption.stateOf(null as ByteArray?))
    }

    @Test fun `attach SQL escapes quotes, passphrase is 64 hex chars`() {
        assertEquals(
            "ATTACH DATABASE '/data/it''s/drishti.db.encrypting' AS encrypted KEY 'ab''c'",
            DbEncryption.attachSql("/data/it's/drishti.db.encrypting", "ab'c"),
        )
        val hex = DbEncryption.toHex(ByteArray(32) { it.toByte() })
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in "0123456789abcdef" })
        assertEquals("000102", DbEncryption.toHex(byteArrayOf(0, 1, 2)))
        assertEquals("ff80", DbEncryption.toHex(byteArrayOf(-1, -128)))
    }

    @Test fun `file-state check on a real file`() {
        val f = File.createTempFile("drishti", ".db")
        try {
            assertEquals(DbEncryption.FileState.MISSING, DbEncryption.stateOf(f))
            f.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(4096))
            assertEquals(DbEncryption.FileState.PLAIN, DbEncryption.stateOf(f))
            f.writeBytes(ByteArray(4096) { 0x5a })
            assertEquals(DbEncryption.FileState.ENCRYPTED, DbEncryption.stateOf(f))
        } finally { f.delete() }
    }

    @Test fun `Room is opened through SQLCipher with the prepared passphrase`() {
        val store = File("src/main/kotlin/com/drishtisaathi/data/SessionStore.kt").readText()
        assertTrue(store.contains(".openHelperFactory(SupportOpenHelperFactory(DbEncryption.prepare("))
    }
}
