package com.drishtisaathi.calibration

import android.content.Context
import com.drishtisaathi.engine.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tiny key-value store so the repository logic is unit-testable without Android. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

class SharedPrefsStore(context: Context, name: String = "calibration") : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

/**
 * Calibration repository that keeps the per-phone screen scale across app restarts and holds the
 * per-customer values (iris size, camera scale at the face) in memory only: call [clearCustomer] when a new
 * customer starts (or the session is deleted).
 */
class PersistentCalibrationRepository(private val store: KeyValueStore) : CalibrationRepository {

    constructor(context: Context) : this(SharedPrefsStore(context))

    private val _c = MutableStateFlow(load())
    override val calibration: StateFlow<Calibration> = _c.asStateFlow()

    private fun load(): Calibration {
        val scale = store.getString(KEY_SCREEN)?.toDoubleOrNull()
        val source = store.getString(KEY_SOURCE)?.let { s -> Source.entries.firstOrNull { it.name == s } }
        return if (scale != null && scale > 0) Calibration(screenMmPerPx = scale, screenSource = source ?: Source.POPULATION_AVERAGE)
        else Calibration()
    }

    @Synchronized
    override fun update(transform: (Calibration) -> Calibration) {
        val old = _c.value
        val new = transform(old)
        if (new.screenMmPerPx != old.screenMmPerPx || new.screenSource != old.screenSource) {
            store.putString(KEY_SCREEN, new.screenMmPerPx?.toString())
            store.putString(KEY_SOURCE, new.screenMmPerPx?.let { new.screenSource.name })
        }
        _c.value = new
    }

    /** Forget this customer's values (iris size, camera scale, card PD); keep the phone's screen scale. */
    fun clearCustomer() = update { it.copy(irisDiameterMm = null, cameraMmPerPx = null, pdMm = null) }

    /** Forget the screen scale too (e.g. a different phone's settings were restored). */
    fun clearAll() = update { Calibration() }

    private companion object {
        const val KEY_SCREEN = "screenMmPerPx"
        const val KEY_SOURCE = "screenSource"
    }
}
