package com.drishtisaathi.vision

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.config.FarPointConfig
import com.drishtisaathi.engine.config.VisionTestConfig
import com.drishtisaathi.engine.vision.FarPointProtocol

/*
 * Immutable step-by-step state of each test, driven by the composables (answer → new state). No Android here, so
 * every rule (start positions, valid tap ranges, repeat counting) is unit-tested.
 */

/** Worst distance-reading confidence seen at the moments that produced data. */
internal fun worst(a: Confidence?, b: Confidence): Confidence = a?.min(b) ?: b

private fun DistanceReading?.usable(): DistanceReading? = this?.takeIf { it.faceVisible }

enum class FarColour { BLUE, WHITE }

/**
 * Far point, driven by the engine's [FarPointProtocol] (rules_update.md rules 1, 3 and 4): for each colour (blue first,
 * then white) [VisionTestConfig.repeats] times: bring the phone close (≤ farPointStartMaxMm), move it away, tap when the
 * letters just blur. The protocol adds a third reading when two repeats of a colour disagree by more than 0.50 D, and
 * retakes the whole far point once when blue and white disagree by more than 0.50 D.
 *
 * Self-held: if the letters are still clear at farPoint.selfHeldReachM (0.60 m) the customer cannot reach the blur
 * point; the screen offers the staff-held mode (Luo method: staff hold the phone and slowly move it away). Then blur
 * taps count up to farPoint.staffHeldMaxM (2.0 m) and "still clear" (null) only beyond it. The mode stays on for the
 * rest of this eye's far point.
 */
data class FarPointProcedure(
    val protocol: FarPointProtocol,
    val farPoint: FarPointConfig,
    val state: FarPointProtocol.State = FarPointProtocol.State(),
    val seenClose: Boolean = false,
    val staffHeld: Boolean = false,
    val worstConfidence: Confidence? = null,
) {
    private val step: FarPointProtocol.Step get() = protocol.next(state)

    val colour: FarColour? get() = (step as? FarPointProtocol.Step.Measure)?.let {
        if (it.colour == FarPointProtocol.Colour.BLUE) FarColour.BLUE else FarColour.WHITE
    }
    /** Why the current reading is asked for (third reading or retake → the screen says so). */
    val why: FarPointProtocol.Why? get() = (step as? FarPointProtocol.Step.Measure)?.why
    val isDone: Boolean get() = colour == null
    /** 0-based reading number within the current colour. */
    val repeatIndex: Int get() = (step as? FarPointProtocol.Step.Measure)?.repeatIndex ?: 0
    val blueMm: List<Double?> get() = state.blue.map { it.distanceMm }
    val whiteMm: List<Double?> get() = state.white.map { it.distanceMm }

    private val selfReachMm get() = farPoint.selfHeldReachM * 1000.0
    private val staffMaxMm get() = farPoint.staffHeldMaxM * 1000.0

    fun onReading(r: DistanceReading?, cfg: VisionTestConfig): FarPointProcedure {
        val u = r.usable() ?: return this
        return if (!seenClose && u.distanceMm <= cfg.farPointStartMaxMm) copy(seenClose = true) else this
    }

    fun canMarkBlur(r: DistanceReading?, cfg: VisionTestConfig): Boolean {
        val u = r.usable() ?: return false
        if (isDone || !seenClose || u.distanceMm < cfg.farPointMinMm) return false
        return !staffHeld || u.distanceMm <= staffMaxMm
    }

    /** Self-held and still clear at arm's reach (0.60 m): offer the staff-held mode. */
    fun canOfferStaffHeld(r: DistanceReading?): Boolean {
        val u = r.usable() ?: return false
        return !isDone && !staffHeld && seenClose && u.distanceMm >= selfReachMm
    }

    /** Staff take the phone: start close again, then move it slowly away from the customer. */
    fun startStaffHeld(): FarPointProcedure = copy(staffHeld = true, seenClose = false)

    /** "Still clear" counts only in the staff-held mode, beyond staffHeldMaxM (2.0 m). */
    fun canMarkNoBlur(r: DistanceReading?, cfg: VisionTestConfig): Boolean {
        val u = r.usable() ?: return false
        return !isDone && staffHeld && seenClose && u.distanceMm >= staffMaxMm
    }

    fun markBlur(r: DistanceReading, cfg: VisionTestConfig): FarPointProcedure {
        check(canMarkBlur(r, cfg)); return record(r.distanceMm, r.confidence)
    }

    fun markNoBlur(r: DistanceReading, cfg: VisionTestConfig): FarPointProcedure {
        check(canMarkNoBlur(r, cfg)); return record(null, r.confidence)
    }

    private fun record(mm: Double?, c: Confidence): FarPointProcedure =
        copy(state = protocol.record(state, FarPointProtocol.Reading(mm, staffHeld)), seenClose = false,
            worstConfidence = worst(worstConfidence, c))

    fun result(eye: TestEye, rules: RulesConfig, capByDistance: Boolean): FarPointEyeResult? =
        FarPointAnalysis.fromState(eye, state, rules, if (capByDistance) worstConfidence else null)

    companion object {
        fun start(rules: RulesConfig) = FarPointProcedure(FarPointProtocol(rules), rules.farPoint)
    }
}

/**
 * Near point push-up: start with the letters clear at ≥ [startMinMm], move closer, tap when they blur
 * (recorded if within [nearPointMinMm, nearPointMaxMm]); or "blurry even at arm's length" (null) from the start position.
 * Build with [start]: for an uncorrected myope the start is pulled inside the far point, where the letters are clear.
 */
data class NearPointProcedure(
    val repeats: Int,
    val startMinMm: Double,
    val marksMm: List<Double?> = emptyList(),
    val seenFar: Boolean = false,
    val worstConfidence: Confidence? = null,
) {
    val isDone: Boolean get() = marksMm.size >= repeats
    val repeatIndex: Int get() = marksMm.size

    fun onReading(r: DistanceReading?, cfg: VisionTestConfig): NearPointProcedure {
        val u = r.usable() ?: return this
        return if (!seenFar && u.distanceMm >= startMinMm) copy(seenFar = true) else this
    }

    fun canMarkBlur(r: DistanceReading?, cfg: VisionTestConfig): Boolean {
        val u = r.usable() ?: return false
        return !isDone && seenFar && u.distanceMm in cfg.nearPointMinMm..cfg.nearPointMaxMm
    }

    fun canMarkOutOfReach(r: DistanceReading?, cfg: VisionTestConfig): Boolean {
        val u = r.usable() ?: return false
        return !isDone && u.distanceMm >= startMinMm
    }

    fun markBlur(r: DistanceReading, cfg: VisionTestConfig): NearPointProcedure {
        check(canMarkBlur(r, cfg))
        return copy(marksMm = marksMm + r.distanceMm, seenFar = false, worstConfidence = worst(worstConfidence, r.confidence))
    }

    fun markOutOfReach(r: DistanceReading, cfg: VisionTestConfig): NearPointProcedure {
        check(canMarkOutOfReach(r, cfg))
        return copy(marksMm = marksMm + null, seenFar = false, worstConfidence = worst(worstConfidence, r.confidence))
    }

    companion object {
        /** Start distance: nearPointStartMinMm, or 90 % of the far point (1/|SE|) if that is nearer; never below min + 20 mm. */
        fun start(cfg: VisionTestConfig, seD: Double?): NearPointProcedure {
            val farPointMm = seD?.takeIf { it < -0.01 }?.let { 1000.0 / -it }
            val start = minOf(cfg.nearPointStartMinMm, farPointMm?.times(0.9) ?: Double.MAX_VALUE)
                .coerceAtLeast(cfg.nearPointMinMm + 20.0)
            return NearPointProcedure(cfg.repeats, start)
        }
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
