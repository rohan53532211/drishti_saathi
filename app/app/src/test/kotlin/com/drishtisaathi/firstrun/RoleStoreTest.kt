package com.drishtisaathi.firstrun

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** First run: the chosen role is remembered across app starts and "Change who uses this phone" clears it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoleStoreTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `no role on first run, then the chosen role survives a restart, and switching clears it`() {
        assertNull(RoleStore(app).role.value)
        RoleStore(app).set(AppRole.PATIENT)
        assertEquals(AppRole.PATIENT, RoleStore(app).role.value)        // a new store = the next app start
        RoleStore(app).set(AppRole.SHOP)
        assertEquals(AppRole.SHOP, RoleStore(app).role.value)
        RoleStore(app).set(null)
        assertNull(RoleStore(app).role.value)
    }

    @Test fun `the optometrist card opens the hosted portal`() {
        assertEquals("https://drishti-saathi-backend.onrender.com/portal", PORTAL_URL)
    }
}
