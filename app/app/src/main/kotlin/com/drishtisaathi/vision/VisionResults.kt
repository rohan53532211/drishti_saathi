package com.drishtisaathi.vision

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.Estimate
import com.drishtisaathi.engine.MeasuredEye
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.VisionTests
import com.drishtisaathi.engine.config.VisionTestConfig
import com.drishtisaathi.engine.vision.VisionMath
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Which eye a test ran on (the customer's own right/left; the other eye is covered with the palm). */
enum class TestEye { RIGHT, LEFT, BOTH }

private fun Confidence.capBy(cap: Confidence?): Confidence = if (cap == null) this else this.min(cap)

// =================================================================================================
// 2. Far point, blue then white
// =================================================================================================

data class FarPointEyeResult(
    val eye: TestEye,
    /** Recorded far-point distances per repeat; null = still clear at arm's length. */
    val blueMm: List<Double?>,
    val whiteMm: List<Double?>,
    /** Median SE of the blue repeats (after the LCA offset) and of the white repeats, when measured. */
    val seBlueD: Double?,
    val seWhiteD: Double?,
    /** Best SE for VisionTests (null when no myopia was found within arm's length). */
    val seD: Double?,
    val confidence: Confidence,
    val noMyopiaAtArmsLength: Boolean,
    /** Two-colour self-check: true/false, or null when white could not be used (beyond 0.8 m or skipped). */
    val consistent: Boolean?,
) {
    /** White-light far point implied by the SE (where the fogged clock dial is referenced), mm. */
    val farPointMm: Double? get() = seD?.takeIf { it < -0.01 }?.let { 1000.0 / -it }
}

object FarPointAnalysis {
    /**
     * Repeats → SE per eye, via VisionMath:
     * - a repeat with no blur within arm's length (null, or ≥ optics.armsLengthM) is censored at arm's length;
     * - all blue repeats unblurred → noMyopiaAtArmsLength (seD = null, never invented); white blurring then = LOW;
     * - blue SE = median of per-repeat −1/d + LCA, confidence from the spread (combineRepeats);
     * - white used only if every white repeat is within optics.whiteFarPointMaxM; then the two-colour check:
     *   agree → SE = mean of blue and white, confidence = the lower of the two; disagree → blue SE, LOW;
     * - finally capped at [distanceCap] (the worst distance-reading confidence at the taps), if given.
     */
    fun analyse(
        eye: TestEye,
        blueMm: List<Double?>,
        whiteMm: List<Double?>,
        rules: RulesConfig,
        distanceCap: Confidence? = null,
    ): FarPointEyeResult? {
        if (blueMm.isEmpty()) return null
        val vm = VisionMath(rules)
        val armsMm = rules.optics.armsLengthM * 1000.0
        val b = blueMm.map { it?.takeIf { d -> d < armsMm } }
        val w = whiteMm.map { it?.takeIf { d -> d < armsMm } }

        if (b.all { it == null }) {
            val whiteBlurred = w.any { it != null }
            val conf = (if (whiteBlurred) Confidence.LOW else Confidence.HIGH).capBy(distanceCap)
            return FarPointEyeResult(eye, blueMm, whiteMm, null, null, null, conf, noMyopiaAtArmsLength = true,
                consistent = if (w.isEmpty()) null else !whiteBlurred)
        }
        val blue = vm.combineRepeats(b.map { vm.seFromBlueFarPoint((it ?: armsMm) / 1000.0) })
        val whiteAllUsable = w.isNotEmpty() && w.all { vm.whiteUsable(it?.div(1000.0)) }
        val white = if (whiteAllUsable) vm.combineRepeats(w.map { vm.seFromWhiteFarPoint(it!! / 1000.0) }) else null

        val (se, conf, consistent) = if (white == null) {
            Triple(blue.value, blue.confidence, null)
        } else if (vm.blueWhiteConsistent(blue.value, white.value)) {
            Triple((blue.value + white.value) / 2.0, blue.confidence.min(white.confidence), true)
        } else {
            Triple(blue.value, Confidence.LOW, false)
        }
        return FarPointEyeResult(eye, blueMm, whiteMm, blue.value, white?.value, se, conf.capBy(distanceCap),
            noMyopiaAtArmsLength = false, consistent = consistent)
    }

    /** Where to hold the fogged clock dial: far point + offset, or the no-far-point distance; never beyond the latter. */
    fun clockDialTargetMm(farPoint: FarPointEyeResult?, cfg: VisionTestConfig): Double {
        val fp = farPoint?.farPointMm ?: return cfg.clockDialNoFarPointMm
        return min(fp + cfg.clockDialBeyondFarPointMm, cfg.clockDialNoFarPointMm)
    }
}

// =================================================================================================
// 3. Fogged clock dial
// =================================================================================================

data class ClockDialEyeResult(
    val eye: TestEye,
    /** Per repeat: meridian index 0–5 (0 = 12–6 line, 1 = 1–7, … 5 = 5–11) or null = all lines look the same. */
    val answers: List<Int?>,
    val astigmatismFlag: Boolean,
    /** Approximate minus-cylinder axis (rule of 30), degrees 1–180, when flagged. */
    val axisDeg: Int?,
    val confidence: Confidence,
)

object ClockDialAnalysis {
    const val MERIDIANS = 6

    /** Clock "rule of 30": darkest line between hours h and h+6 → axis = 30 × the smaller hour (12–6 line → 180). */
    fun axisForMeridian(i: Int): Int {
        require(i in 0 until MERIDIANS); return if (i == 0) 180 else i * 30
    }

    /** Clock hours a meridian joins, for labels: 0 → (12, 6), 1 → (1, 7) … */
    fun hoursOf(i: Int): Pair<Int, Int> = (if (i == 0) 12 else i) to i + 6

    /**
     * Tap at ([dx], [dy]) from the dial centre in screen pixels (y grows downwards) → nearest meridian index,
     * or null inside [deadZonePx] of the centre.
     */
    fun meridianFromTap(dx: Double, dy: Double, deadZonePx: Double = 0.0): Int? {
        if (dx * dx + dy * dy <= deadZonePx * deadZonePx) return null
        var deg = Math.toDegrees(atan2(dx, -dy)) // 0 = 12 o'clock, clockwise
        if (deg < 0) deg += 360.0
        val hour = (deg / 30.0).roundToInt() % 12
        return hour % MERIDIANS
    }

    /** Angular difference of two axes on the 0–180° half circle. */
    fun axisDifference(a: Int, b: Int): Int {
        val d = abs(a - b) % 180
        return min(d, 180 - d)
    }

    /** Mean of axes on the half circle (doubled-angle mean), rounded to 5°, in 1–180. */
    fun meanAxis(axes: List<Int>): Int {
        val s = axes.sumOf { sin(Math.toRadians(2.0 * it)) }
        val c = axes.sumOf { cos(Math.toRadians(2.0 * it)) }
        var deg = Math.toDegrees(atan2(s, c)) / 2.0
        if (deg <= 0) deg += 180.0
        val r = (deg / 5.0).roundToInt() * 5
        return if (r <= 0) 180 else r
    }

    /**
     * Flag only when every repeat names a line and all named axes lie within [toleranceDeg] of each other
     * (identical → HIGH, else MEDIUM). Every repeat "all the same" → no flag, HIGH. Mixed or far-apart answers →
     * no flag, LOW (answers not consistent).
     */
    fun analyse(eye: TestEye, answers: List<Int?>, toleranceDeg: Int): ClockDialEyeResult? {
        if (answers.isEmpty()) return null
        if (answers.all { it == null }) return ClockDialEyeResult(eye, answers, false, null, Confidence.HIGH)
        if (answers.any { it == null }) return ClockDialEyeResult(eye, answers, false, null, Confidence.LOW)
        val axes = answers.map { axisForMeridian(it!!) }
        val agree = axes.all { a -> axes.all { b -> axisDifference(a, b) <= toleranceDeg } }
        if (!agree) return ClockDialEyeResult(eye, answers, false, null, Confidence.LOW)
        val conf = if (answers.distinct().size == 1) Confidence.HIGH else Confidence.MEDIUM
        return ClockDialEyeResult(eye, answers, true, meanAxis(axes), conf)
    }
}

// =================================================================================================
// 4. Near point push-up → add (fused with the age expectation)
// =================================================================================================

data class NearPointTestResult(
    /** Per repeat; null = letters blurred even at arm's length (near point out of reach). */
    val marksMm: List<Double?>,
    /** Median near point (median vergence), when every repeat reached blur. */
    val nearPointMm: Double?,
    val censored: Boolean,
    /** SE used for A = 1/d − |SE|, and whether it was measured (false → assumed 0, confidence LOW). */
    val seUsedD: Double,
    val seMeasured: Boolean,
    val workingDistanceM: Double,
    val fusion: VisionMath.AddFusion?,
) {
    /** For VisionTests.measuredAddD; null when not measurable (out of reach) → the engine falls back to the age table. */
    val add: Estimate<Double>? get() = fusion?.add
}

object NearPointAnalysis {
    /**
     * Each repeat gives a raw add 1/WD − (1/d − |SE|)/2; their median and spread (combineRepeats) give the
     * repeat confidence, capped by [distanceCap] and at LOW when the SE is not measured. The median near point then
     * goes through [VisionMath.fuseAdd] with age, SE and working distance. Any repeat out of reach → no add
     * (censored; never invented).
     */
    fun analyse(
        marksMm: List<Double?>,
        seD: Double?,
        workingDistanceM: Double,
        age: Int?,
        rules: RulesConfig,
        distanceCap: Confidence? = null,
    ): NearPointTestResult? {
        if (marksMm.isEmpty()) return null
        val se = seD ?: 0.0
        if (marksMm.any { it == null }) {
            return NearPointTestResult(marksMm, null, true, se, seD != null, workingDistanceM, null)
        }
        val vm = VisionMath(rules)
        val d = marksMm.map { it!! / 1000.0 }
        val rawAdds = d.map { 1.0 / workingDistanceM - vm.accommodationAmplitude(it, se) / 2.0 }
        var conf = vm.combineRepeats(rawAdds).confidence.capBy(distanceCap)
        if (seD == null) conf = conf.min(Confidence.LOW)
        val medianVergence = median(d.map { 1.0 / it })
        val dMedianM = 1.0 / medianVergence
        val fusion = vm.fuseAdd(dMedianM, se, workingDistanceM, age, conf)
        return NearPointTestResult(marksMm, dMedianM * 1000.0, false, se, seD != null, workingDistanceM, fusion)
    }

    /** SE for the push-up: mean of the eyes' measured SE (no myopia at arm's length counts as 0), null if none. */
    fun seForNearPoint(farPoints: Collection<FarPointEyeResult>): Double? {
        val ses = farPoints.mapNotNull { if (it.noMyopiaAtArmsLength) 0.0 else it.seD }
        return if (ses.isEmpty()) null else ses.average()
    }
}

// =================================================================================================
// 1. Near acuity
// =================================================================================================

data class NearAcuityEyeResult(
    val eye: TestEye,
    val thresholdsLogMar: List<Double>,
    val logMar: Double,
    val confidence: Confidence,
    val belowThreshold: Boolean,
)

object NearAcuityAnalysis {
    /** Median of the repeat thresholds; spread → HIGH/MEDIUM/LOW (config, logMAR); capped by scale source and distance. */
    fun analyse(
        eye: TestEye,
        thresholdsLogMar: List<Double>,
        cfg: VisionTestConfig,
        scaleFromCard: Boolean,
        distanceCap: Confidence? = null,
    ): NearAcuityEyeResult? {
        if (thresholdsLogMar.isEmpty()) return null
        val m = median(thresholdsLogMar)
        val spread = thresholdsLogMar.max() - thresholdsLogMar.min()
        var conf = when {
            thresholdsLogMar.size < 2 -> Confidence.LOW
            spread <= cfg.acuityRepeatHighMaxLogMar + 1e-9 -> Confidence.HIGH
            spread <= cfg.acuityRepeatMediumMaxLogMar + 1e-9 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        if (!scaleFromCard) conf = conf.min(cfg.fallbackScreenScaleCap)
        conf = conf.capBy(distanceCap)
        return NearAcuityEyeResult(eye, thresholdsLogMar, m, conf, m > cfg.nearAcuityReferAboveLogMar + 1e-9)
    }
}

// =================================================================================================
// 5. Amsler grid
// =================================================================================================

/** A tap on the grid, as a fraction of the grid size from its centre (−0.5…0.5 each way). */
data class GridMark(val x: Float, val y: Float)

data class AmslerEyeResult(
    val eye: TestEye,
    /** Per repeat: true = some lines wavy, bent, blurred or missing. */
    val answers: List<Boolean>,
    val marks: List<GridMark>,
    val abnormal: Boolean,
    val confidence: Confidence,
)

object AmslerAnalysis {
    fun analyse(eye: TestEye, answers: List<Boolean>, marks: List<GridMark>, cfg: VisionTestConfig): AmslerEyeResult? {
        if (answers.isEmpty()) return null
        val yes = answers.count { it }
        val conf = if (yes == 0 || yes == answers.size) Confidence.HIGH else Confidence.LOW
        return AmslerEyeResult(eye, answers, marks, yes >= cfg.amslerYesCountForAbnormal, conf)
    }
}

// =================================================================================================
// Session → VisionTests
// =================================================================================================

/** Everything measured in one run of the vision tests. Missing entries = skipped (no data, never invented). */
data class VisionSession(
    val farPoint: Map<TestEye, FarPointEyeResult> = emptyMap(),
    val clockDial: Map<TestEye, ClockDialEyeResult> = emptyMap(),
    val nearPoint: NearPointTestResult? = null,
    val nearAcuity: Map<TestEye, NearAcuityEyeResult> = emptyMap(),
    val amsler: Map<TestEye, AmslerEyeResult> = emptyMap(),
    /** Steps the customer or staff skipped. */
    val skipped: Set<VisionStep> = emptySet(),
    /** Distance came from the debug slider, not the camera (demo only). */
    val simulatedDistance: Boolean = false,
) {
    /**
     * Mapping into the engine's VisionTests:
     * - right/left: MeasuredEye from that eye's far point (seD, confidence, noMyopiaAtArmsLength) and clock dial
     *   (astigmatismFlag, astigmatismAxisDeg); null when neither ran for the eye;
     * - measuredAddD: the fused add from the push-up (null if skipped, not run for age, or out of reach);
     * - nearAcuityBelowThreshold: any tested eye worse than visionTests.nearAcuityReferAboveLogMar;
     * - amslerAbnormal: any eye abnormal (urgent referral in RulesEngine);
     * - lowConfidenceCount: power-bearing results (far point per eye, add) still LOW after the allowed retries.
     */
    fun toVisionTests(): VisionTests = VisionTests(
        right = measuredEye(TestEye.RIGHT),
        left = measuredEye(TestEye.LEFT),
        measuredAddD = nearPoint?.add,
        nearAcuityBelowThreshold = nearAcuity.values.any { it.belowThreshold },
        amslerAbnormal = amsler.values.any { it.abnormal },
        lowConfidenceCount = farPoint.values.count { it.confidence == Confidence.LOW } +
            (if (nearPoint?.add?.confidence == Confidence.LOW) 1 else 0),
    )

    private fun measuredEye(eye: TestEye): MeasuredEye? {
        val fp = farPoint[eye]
        val cd = clockDial[eye]
        if (fp == null && cd == null) return null
        return MeasuredEye(
            seD = fp?.seD,
            confidence = fp?.confidence ?: cd!!.confidence,
            noMyopiaAtArmsLength = fp?.noMyopiaAtArmsLength ?: false,
            astigmatismFlag = cd?.astigmatismFlag ?: false,
            astigmatismAxisDeg = cd?.axisDeg,
        )
    }
}

internal fun median(values: List<Double>): Double {
    require(values.isNotEmpty())
    val s = values.sorted()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
}
