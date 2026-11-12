package com.drishtisaathi

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-time phone setup (bank-card screen size + a quick camera test), stored per phone. [offered] = it already
 * opened once on first launch; after "Skip for now" home shows a reminder until [done].
 */
class PhoneSetup(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("phone_setup", Context.MODE_PRIVATE)
    private val _done = MutableStateFlow(prefs.getBoolean(KEY_DONE, false))
    val done: StateFlow<Boolean> = _done.asStateFlow()
    val offered: Boolean get() = prefs.getBoolean(KEY_OFFERED, false)

    fun markOffered() = prefs.edit().putBoolean(KEY_OFFERED, true).apply()
    fun markDone() { prefs.edit().putBoolean(KEY_DONE, true).putBoolean(KEY_OFFERED, true).apply(); _done.value = true }

    private companion object {
        const val KEY_DONE = "done"
        const val KEY_OFFERED = "offered"
    }
}
