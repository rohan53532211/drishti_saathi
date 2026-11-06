package com.drishtisaathi.vision

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.config.VisionTestConfig

/*
 * Immutable step-by-step state of each test, driven by the composables (answer → new state). No Android here, so
 * every rule (start positions, valid tap ranges, repeat counting) is unit-tested.
 */

/** Worst distance-reading confidence seen at the moments that produced data. */
internal fun worst(a: Confidence?, b: Confidence): Confidence = a?.min(b) ?: b

private fun DistanceReading?.usable(): DistanceReading? = this?.takeIf { it.faceVisible }

enum class FarColour { BLUE, WHITE }

/**
 * Far point: for each colour (blue first, then white) [repeats] times: bring the phone close (≤ farPointStartMaxMm),
 * move it away, tap when the letters just blur (distance recorded) or "still clear" at ≥ noBlurMinDistanceMm (null).
 */
data class FarPointProcedure(
    val repeats: Int,
    val blueMm: List<Double?> = emptyList(),
    val whiteMm: List<Double?> = emptyList(),
    val seenClose: Boolean = false,
    val worstConfidence: Confidence? = null,
) {
    val colour: FarColour? get() = when {
        blueMm.size < repeats -> FarColour.BLUE
        whiteMm.size < repeats -> FarColour.WHITE
        else -> null
    }
    val isDone: Boolean get() = colour == null
    /** 0-based repeat number within the current colour. */
    val repeatIndex: Int get() = if (colour == FarColour.BLUE) blueMm.size else whiteMm.size

    fun onReading(r: DistanceReading?, cfg: VisionTestConfig): FarPointProcedure {
        val u = r.usable() ?: return this
        return if (!seenClose && u.distanceMm <= cfg.farPointStartMaxMm) copy(seenClose = true) else this
    }

    fun canMarkBlur(r: DistanceReading?, cfg: VisionTestConfig): Boolean {
        val u = r.usable() ?: return false
        return !isDone && seenClose && u.distanceMm >= cfg.farPointMinMm
    }

    fun canMarkNoBlur(r: DistanceReading?, cfg: VisionTestConfig): Boolean {
        val u = r.usable() ?: return false
        return !isDone && seenClose && u.distanceMm >= cfg.noBlurMinDistanceMm
    }

    fun markBlur(r: DistanceReading, cfg: VisionTestConfig): FarPointProcedure {
        check(canMarkBlur(r, cfg)); return record(r.distanceMm, r.confidence)
    }

    fun markNoBlur(r: DistanceReading, cfg: VisionTestConfig): FarPointProcedure {
        check(canMarkNoBlur(r, cfg)); return record(null, r.confidence)
    }

    private fun record(mm: Double?, c: Confidence): FarPointProcedure {
        val w = worst(worstConfidence, c)
        return if (colour == FarColour.BLUE) copy(blueMm = blueMm + mm, seenClose = false, worstConfidence = w)
        else copy(whiteMm = whiteMm + mm, seenClose = false, worstConfidence = w)
    }

    fun result(eye: TestEye, rules: RulesConfig, capByDistance: Boolean): FarPointEyeResult? =
        FarPointAnalysis.analyse(eye, blueMm, whiteMm, rules, if (capByDistance) worstConfidence else null)
}

/**
 * Near point push-up: start with the letters clear at ≥ nearPointStartMinMm, move closer, tap when they blur
 * (recorded if within [nearPointMinMm, nearPointMaxMm]); or "blurry even at arm's length" (null) from the start position.
 */
data class NearPointProcedure(
    val repeats: Int,
    val marksMm: List<Double?> = emptyList(),
    val seenFar: Boolean = false,
    val worstConfidence: Confidence? = null,
) {
    val isDone: Boolean get() = marksMm.size >= repeats
    val repeatIndex: Int get() = marksMm.size

    fun onReading(r: DistanceReading?, cfg: VisionTestConfig): NearPointProcedure {
        val u = r.usable() ?: return this
        return if (!seenFar && u.distanceMm >= cfg.nearPointStartMinMm) copy(seenFar = true) else this
    }

    fun canMarkBlur(r: DistanceReading?, cfg: VisionTestConfig): Boolean {
        val u = r.usable() ?: return false
        return !isDone && seenFar && u.distanceMm in cfg.nearPointMinMm..cfg.nearPointMaxMm
    }

    fun canMarkOutOfReach(r: DistanceReading?, cfg: VisionTestConfig): Boolean {
        val u = r.usable() ?: return false
        return !isDone && u.distanceMm >= cfg.nearPointStartMinMm
    }

    fun markBlur(r: DistanceReading, cfg: VisionTestConfig): NearPointProcedure {
        check(canMarkBlur(r, cfg))
        return copy(marksMm = marksMm + r.distanceMm, seenFar = false, worstConfidence = worst(worstConfidence, r.confidence))
    }

    fun markOutOfReach(r: DistanceReading, cfg: VisionTestConfig): NearPointProcedure {
        check(canMarkOutOfReach(r, cfg))
        return copy(marksMm = marksMm + null, seenFar = false, worstConfidence = worst(worstConfidence, r.confidence))
    }
}

/** Near acuity: [repeats] staircases in a row; each answer is a direction (null = "cannot see", counted wrong). */
data class AcuityProcedure(
    val repeats: Int,
    val params: StaircaseParams,
    val staircase: Staircase,
    val shown: EDirection,
    val thresholds: List<Double> = emptyList(),
    val worstConfidence: Confidence? = null,
) {
    val isDone: Boolean get() = thresholds.size >= repeats
    val repeatIndex: Int get() = thresholds.size

    /** [next] is the direction to show after this answer (chosen by the caller, e.g. EDirection.random). */
    fun answer(dir: EDirection?, next: EDirection, readingConfidence: Confidence): AcuityProcedure {
        check(!isDone)
        val s = staircase.answer(dir == shown)
        val w = worst(worstConfidence, readingConfidence)
        if (!s.isDone) return copy(staircase = s, shown = next, worstConfidence = w)
        return copy(staircase = Staircase(params), shown = next, thresholds = thresholds + s.threshold(), worstConfidence = w)
    }

    companion object {
        fun start(cfg: VisionTestConfig, first: EDirection): AcuityProcedure {
            val p = StaircaseParams.from(cfg)
            return AcuityProcedure(cfg.repeats, p, Staircase(p), first)
        }
    }
}

/** Clock dial: one answer per repeat (meridian index, or null = all the same). */
data class ClockDialProcedure(val repeats: Int, val answers: List<Int?> = emptyList()) {
    val isDone: Boolean get() = answers.size >= repeats
    val repeatIndex: Int get() = answers.size
    fun answer(meridian: Int?): ClockDialProcedure { check(!isDone); return copy(answers = answers + meridian) }
}

/** Amsler: one yes/no per repeat; optional taps where the lines look wrong. */
data class AmslerProcedure(val repeats: Int, val answers: List<Boolean> = emptyList(), val marks: List<GridMark> = emptyList()) {
    val isDone: Boolean get() = answers.size >= repeats
    val repeatIndex: Int get() = answers.size
    fun answer(yes: Boolean): AmslerProcedure { check(!isDone); return copy(answers = answers + yes) }
    fun mark(m: GridMark): AmslerProcedure = copy(marks = marks + m)
}

// =================================================================================================
// Order of the tests
// =================================================================================================

sealed interface VisionStep {
    data object Intro : VisionStep
    data class FarPoint(val eye: TestEye) : VisionStep
    data class ClockDial(val eye: TestEye) : VisionStep
    data object NearPoint : VisionStep
    data class NearAcuity(val eye: TestEye) : VisionStep
    data class Amsler(val eye: TestEye) : VisionStep
    data object Summary : VisionStep
}

object VisionFlowPlan {
    /**
     * Glasses off: per eye far point (blue, white) then the fogged dial (it is held just beyond that eye's far point);
     * then the push-up near point with both eyes (age ≥ ages.nearPointTestFrom, or age unknown); then, with reading
     * glasses if the customer has them: near acuity (per eye or both) and the Amsler grid per eye.
     */
    fun steps(age: Int?, rules: RulesConfig): List<VisionStep> = buildList {
        add(VisionStep.Intro)
        for (eye in listOf(TestEye.RIGHT, TestEye.LEFT)) {
            add(VisionStep.FarPoint(eye)); add(VisionStep.ClockDial(eye))
        }
        if (age == null || age >= rules.ages.nearPointTestFrom) add(VisionStep.NearPoint)
        if (rules.visionTests.nearAcuityPerEye) {
            add(VisionStep.NearAcuity(TestEye.RIGHT)); add(VisionStep.NearAcuity(TestEye.LEFT))
        } else add(VisionStep.NearAcuity(TestEye.BOTH))
        add(VisionStep.Amsler(TestEye.RIGHT)); add(VisionStep.Amsler(TestEye.LEFT))
        add(VisionStep.Summary)
    }

    /** Confidence of the result a step just produced in [session], or null if it produced none. Used to offer a retry. */
    fun resultConfidence(step: VisionStep, session: VisionSession): Confidence? = when (step) {
        is VisionStep.FarPoint -> session.farPoint[step.eye]?.confidence
        is VisionStep.ClockDial -> session.clockDial[step.eye]?.confidence
        VisionStep.NearPoint -> session.nearPoint?.add?.confidence
        is VisionStep.NearAcuity -> session.nearAcuity[step.eye]?.confidence
        is VisionStep.Amsler -> session.amsler[step.eye]?.confidence
        else -> null
    }
}
