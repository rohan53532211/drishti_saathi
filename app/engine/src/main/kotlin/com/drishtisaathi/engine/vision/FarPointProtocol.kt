package com.drishtisaathi.engine.vision

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.Estimate
import com.drishtisaathi.engine.Optics
import com.drishtisaathi.engine.OpticsConfig
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.Source
import com.drishtisaathi.engine.config.FarPointConfig
import kotlin.math.abs

/**
 * Far-point decision logic (rules_update.md rules 3 and 4), pure and unit-tested; the app's far-point screen
 * follows it. Readings so far → the next thing to measure, or the final result.
 *
 * - Each colour (blue first, then white) is read [repeats] times.
 * - Rule 4: if those repeats of one colour differ by more than optics.repeatSpreadMediumMaxD (0.50 D) and
 *   farPoint.repeatSpreadAction = THIRD_READING_MEDIAN, one third reading of that colour is taken and the median of
 *   the three is used (MEDIUM confidence when two of the three agree within 0.50 D, else LOW).
 * - Rule 3: if blue and white SE then differ by more than optics.farPointAgreementMaxD (0.50 D) and
 *   farPoint.consistencyAction = RETAKE_ONCE_THEN_LOW_CONFIDENCE, both colours are retaken once; still disagreeing →
 *   blue SE at LOW confidence.
 *
 * A reading of null means "still clear" up to the limit of its mode: optics.armsLengthM when self-held,
 * farPoint.staffHeldMaxM when staff-held; it is censored at that limit. A self-held tap at or beyond arm's length
 * also counts as "still clear". White is used only when every white reading blurred within optics.whiteFarPointMaxM.
 */
class FarPointProtocol(
    private val optics: OpticsConfig,
    private val fp: FarPointConfig,
    private val repeats: Int,
) {
    constructor(rules: RulesConfig) : this(rules.optics, rules.farPoint, rules.visionTests.repeats)

    init { require(repeats >= 1) }

    private val o = Optics(optics)

    enum class Colour { BLUE, WHITE }

    /** One tap: blur distance in mm, or null = still clear up to the mode's limit. */
    data class Reading(val distanceMm: Double?, val staffHeld: Boolean = false)

    data class State(
        val blue: List<Reading> = emptyList(),
        val white: List<Reading> = emptyList(),
        /** Retakes of both colours already done (rule 3). */
        val retakes: Int = 0,
        /** Result of the attempt that was retaken, for the staff record. */
        val retakenOutcome: Outcome? = null,
    )

    enum class Why {
        /** First readings of a colour. */
        STANDARD,
        /** Rule 4: the repeats of this colour disagreed; one more reading. */
        THIRD_READING,
        /** Rule 3: blue and white disagreed; the whole far point is taken once more. */
        RETAKE,
    }

    sealed interface Step {
        data class Measure(val colour: Colour, val why: Why, val repeatIndex: Int) : Step
        data class Done(val outcome: Outcome) : Step
    }

    data class Outcome(
        /** Median SE of the blue readings (after the LCA offset); null when blue never blurred. */
        val seBlueD: Double?,
        /** Median SE of the white readings, when usable. */
        val seWhiteD: Double?,
        /** Best SE: mean of blue and white when they agree, else blue; null when no myopia was found. */
        val seD: Double?,
        val confidence: Confidence,
        val noMyopiaWithinReach: Boolean,
        /** Blue/white agreement; null when white could not be used. */
        val consistent: Boolean?,
        val disagreementD: Double?,
        val thirdReadingBlue: Boolean,
        val thirdReadingWhite: Boolean,
        val staffHeld: Boolean,
        val retakes: Int,
    )

    // ---- decisions ----------------------------------------------------------------------------------

    fun next(s: State): Step {
        if (s.blue.size < repeats) return Step.Measure(Colour.BLUE, if (s.retakes > 0 && s.blue.isEmpty()) Why.RETAKE else Why.STANDARD, s.blue.size)
        if (s.blue.size == repeats && needsThird(s.blue, Colour.BLUE)) return Step.Measure(Colour.BLUE, Why.THIRD_READING, s.blue.size)
        if (s.white.size < repeats) return Step.Measure(Colour.WHITE, Why.STANDARD, s.white.size)
        if (s.white.size == repeats && needsThird(s.white, Colour.WHITE)) return Step.Measure(Colour.WHITE, Why.THIRD_READING, s.white.size)
        return Step.Done(evaluate(s.blue, s.white, s.retakes)!!)
    }

    /** Adds [r] to the colour [next] asks for; starts the one retake when the finished set disagrees (rule 3). */
    fun record(s: State, r: Reading): State {
        val step = next(s)
        check(step is Step.Measure) { "far point already done" }
        val added = if (step.colour == Colour.BLUE) s.copy(blue = s.blue + r) else s.copy(white = s.white + r)
        val after = next(added)
        if (after is Step.Done && after.outcome.consistent == false &&
            fp.consistencyAction == FarPointConfig.ConsistencyAction.RETAKE_ONCE_THEN_LOW_CONFIDENCE && s.retakes < 1
        ) {
            return State(retakes = s.retakes + 1, retakenOutcome = after.outcome)
        }
        return added
    }

    fun isDone(s: State): Boolean = next(s) is Step.Done

    // ---- evaluation ---------------------------------------------------------------------------------

    private fun limitMm(r: Reading): Double = (if (r.staffHeld) fp.staffHeldMaxM else optics.armsLengthM) * 1000.0

    /** Blur distance, or null when still clear (including a self-held tap at or beyond arm's length). */
    private fun blurMm(r: Reading): Double? = r.distanceMm?.takeIf { it < limitMm(r) }

    private fun seBlue(r: Reading): Double = -1000.0 / (blurMm(r) ?: limitMm(r)) + optics.lcaBlueOffsetD

    private fun whiteUsable(readings: List<Reading>): Boolean =
        readings.isNotEmpty() && readings.all { r -> blurMm(r)?.let { it <= optics.whiteFarPointMaxM * 1000.0 } == true }

    private fun seWhite(r: Reading): Double = -1000.0 / blurMm(r)!!

    /** Rule 4: the standard repeats of one colour differ by more than optics.repeatSpreadMediumMaxD. */
    private fun needsThird(readings: List<Reading>, colour: Colour): Boolean {
        if (fp.repeatSpreadAction != FarPointConfig.RepeatSpreadAction.THIRD_READING_MEDIAN || repeats < 2) return false
        val ses = when (colour) {
            Colour.BLUE -> if (readings.all { blurMm(it) == null }) return false else readings.map(::seBlue)
            Colour.WHITE -> if (!whiteUsable(readings)) return false else readings.map(::seWhite)
        }
        return ses.max() - ses.min() > optics.repeatSpreadMediumMaxD + 1e-9
    }

    /** Median and confidence: spread of two (combineRepeats); for three, MEDIUM if two agree within the threshold. */
    private fun combine(values: List<Double>): Estimate<Double> {
        if (values.size < 3) return o.combineRepeats(values)
        val sorted = values.sorted()
        val closest = sorted.zipWithNext { a, b -> b - a }.min()
        val conf = if (closest <= optics.repeatSpreadMediumMaxD + 1e-9) Confidence.MEDIUM else Confidence.LOW
        return Estimate(Optics.median(values), conf, Source.MEASURED)
    }

    /** Result from whatever readings exist (also used to analyse stored readings). Null without blue readings. */
    fun evaluate(blue: List<Reading>, white: List<Reading>, retakes: Int = 0): Outcome? {
        if (blue.isEmpty()) return null
        val staff = (blue + white).any { it.staffHeld }
        val third = blue.size > repeats && repeats >= 2
        val thirdW = white.size > repeats && repeats >= 2
        if (blue.all { blurMm(it) == null }) {
            val whiteBlurred = white.any { blurMm(it) != null }
            return Outcome(null, null, null, if (whiteBlurred) Confidence.LOW else Confidence.HIGH, true,
                if (white.isEmpty()) null else !whiteBlurred, null, third, thirdW, staff, retakes)
        }
        val b = combine(blue.map(::seBlue))
        val w = if (whiteUsable(white)) combine(white.map(::seWhite)) else null
        if (w == null) return Outcome(b.value, null, b.value, b.confidence, false, null, null, third, thirdW, staff, retakes)
        val diff = abs(b.value - w.value)
        return if (diff <= optics.farPointAgreementMaxD + 1e-9) {
            Outcome(b.value, w.value, (b.value + w.value) / 2.0, b.confidence.min(w.confidence), false, true, diff, third, thirdW, staff, retakes)
        } else {
            Outcome(b.value, w.value, b.value, Confidence.LOW, false, false, diff, third, thirdW, staff, retakes)
        }
    }
}
