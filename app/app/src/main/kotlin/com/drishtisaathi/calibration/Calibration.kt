package com.drishtisaathi.calibration

import com.drishtisaathi.engine.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bank-card calibration results (ID-1 card width 85.60 mm). Track B fills this; Track C reads screenMmPerPx
 * to draw letters at true physical size; the distance estimator reads the iris size.
 */
data class Calibration(
    /** Screen scale from the drag-to-match-card rectangle; null = not done (fall back to DisplayMetrics.xdpi). */
    val screenMmPerPx: Double? = null,
    val screenSource: Source = Source.POPULATION_AVERAGE,
    /** This customer's iris diameter measured against a card on the forehead; null = use the 11.7 mm prior. */
    val irisDiameterMm: Double? = null,
    /** Camera mm-per-pixel at the face plane from the forehead card photo. */
    val cameraMmPerPx: Double? = null,
)

interface CalibrationRepository {
    val calibration: StateFlow<Calibration>
    fun update(transform: (Calibration) -> Calibration)
}

/** In-memory default. Track B may replace it with a persisted one (screen scale is per phone, keep it). */
class InMemoryCalibrationRepository : CalibrationRepository {
    private val _c = MutableStateFlow(Calibration())
    override val calibration: StateFlow<Calibration> = _c.asStateFlow()
    override fun update(transform: (Calibration) -> Calibration) { _c.value = transform(_c.value) }
}
