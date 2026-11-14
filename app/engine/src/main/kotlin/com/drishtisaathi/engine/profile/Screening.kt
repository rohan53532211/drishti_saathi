package com.drishtisaathi.engine.profile

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.Eye
import com.drishtisaathi.engine.MeasuredEye
import com.drishtisaathi.engine.VisionTests
import com.drishtisaathi.engine.config.AmslerConfig
import com.drishtisaathi.engine.config.ColourConfig
import com.drishtisaathi.engine.config.ContrastConfig
import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

// Phase C screening tests: Amsler grid, contrast, colour. Pure logic; the screens only draw and collect taps.
// Every result is "screening, not a diagnosis".

// ---- Amsler grid ---------------------------------------------------------------------------------

/** One tapped square of the grid: column and row from the top-left, 0-based. */
@Serializable data class AmslerCell(val col: Int, val row: Int)

@Serializable
data class AmslerEyeResult(
    val eye: Eye,
    /** "Are any lines wavy, missing, blurred or broken?" → yes. */
    val distortion: Boolean,
    /** The squares the person tapped (empty when the answer was no). */
    val cells: List<AmslerCell> = emptyList(),
    /** Grid as it was shown (a narrow screen shows fewer whole columns than the full 20). */
    val columns: Int,
    val rows: Int,
    val distanceMm: Double? = null,
)

/** The grid as it fits the screen: whole squares of the true size, an even count each way so the dot sits on a crossing. */
data class AmslerLayout(val columns: Int, val rows: Int, val squarePx: Double, val full: Boolean) {
    val widthPx: Double get() = columns * squarePx
    val heightPx: Double get() = rows * squarePx

    /** The square under a tap at ([xPx], [yPx]) from the grid's top-left corner; null outside the grid. */
    fun cellAt(xPx: Double, yPx: Double): AmslerCell? {
        if (xPx < 0 || yPx < 0 || xPx >= widthPx || yPx >= heightPx) return null
        return AmslerCell(floor(xPx / squarePx).toInt(), floor(yPx / squarePx).toInt())
    }
}

object Amsler {
    /**
     * Squares of [AmslerConfig.squareMm] real millimetres ([mmPerPx] from the display or the bank-card scale), at most
     * [AmslerConfig.squares] each way. A phone screen is narrower than 10 cm, so whole columns are left out at the
     * sides (never scaled: the squares keep their true size); null when fewer than minColumns fit.
     */
    fun layout(availableWidthPx: Double, availableHeightPx: Double, mmPerPx: Double, cfg: AmslerConfig): AmslerLayout? {
        val sq = cfg.squareMm / mmPerPx
        fun even(n: Int) = n - n % 2
        val cols = even(minOf(cfg.squares, floor(availableWidthPx / sq).toInt()))
        val rows = even(minOf(cfg.squares, floor(availableHeightPx / sq).toInt()))
        if (cols < cfg.minColumns || rows < cfg.minColumns) return null
        return AmslerLayout(cols, rows, sq, full = cols == cfg.squares && rows == cfg.squares)
    }

    /** The test starts and accepts answers only inside the distance window (27–33 cm). */
    fun distanceOk(distanceMm: Double?, cfg: AmslerConfig): Boolean = distanceMm != null && distanceMm in cfg.minMm..cfg.maxMm

    /** Any "yes" is a red flag (macula) and the tapped squares go into the report. */
    fun result(eye: Eye, distortion: Boolean, cells: List<AmslerCell>, layout: AmslerLayout, distanceMm: Double?): AmslerEyeResult =
        AmslerEyeResult(eye, distortion, if (distortion) cells.distinct() else emptyList(), layout.columns, layout.rows, distanceMm)
}

// ---- Contrast ------------------------------------------------------------------------------------

@Serializable
data class ContrastEyeResult(
    val eye: Eye,
    /** Lowest contrast step passed (percent); null when even the first step failed. */
    val lowestPassedPercent: Double?,
    val reduced: Boolean,
) {
    /**
     * Even the full-contrast (100 %) letters were not read: the letters were out of focus or too small, so this says
     * nothing about contrast. The test is not valid and is repeated with the glasses or the trial lens on.
     */
    val invalid: Boolean get() = lowestPassedPercent == null
    /** Reduced contrast from a valid test only (also for results stored before [invalid] existed). */
    val reducedValid: Boolean get() = reduced && !invalid
}

/**
 * One eye's contrast run: fixed letter size, contrast falls step by step; a step is passed with
 * [ContrastConfig.passCorrect] right answers out of [ContrastConfig.trialsPerStep] and failed as soon as that can no
 * longer be reached. The run ends at the first failed step or after the last step.
 */
data class ContrastRun(
    val cfg: ContrastConfig,
    val step: Int = 0,
    val correct: Int = 0,
    val wrong: Int = 0,
    val lowestPassed: Double? = null,
    val done: Boolean = false,
) {
    val percent: Double get() = cfg.stepsPercent[step.coerceAtMost(cfg.stepsPercent.lastIndex)]

    fun answer(right: Boolean): ContrastRun {
        check(!done)
        val c = correct + if (right) 1 else 0
        val w = wrong + if (right) 0 else 1
        return when {
            c >= cfg.passCorrect -> {
                val last = step == cfg.stepsPercent.lastIndex
                copy(step = if (last) step else step + 1, correct = 0, wrong = 0, lowestPassed = percent, done = last)
            }
            w > cfg.trialsPerStep - cfg.passCorrect -> copy(correct = c, wrong = w, done = true)
            else -> copy(correct = c, wrong = w)
        }
    }

    fun result(eye: Eye): ContrastEyeResult = ContrastEyeResult(eye, lowestPassed, Contrast.reduced(lowestPassed, cfg))
}

object Contrast {
    /** Reduced = a valid test whose lowest passed step is above the normal limit. No step passed = not valid, not reduced. */
    fun reduced(lowestPassedPercent: Double?, cfg: ContrastConfig): Boolean =
        lowestPassedPercent != null && lowestPassedPercent > cfg.normalMaxPercent

    /** The tested eye's power (measured or known) is strong enough that the correction must be worn for this test. */
    fun needsCorrection(seD: Double?, cfg: ContrastConfig): Boolean = seD != null && kotlin.math.abs(seD) >= cfg.wearCorrectionFromAbsD

    /**
     * Grey level (0–255) of a letter on a white screen for a Weber contrast of [percent]: the letter's luminance is
     * (1 − c) of the background's, through a display gamma of 2.2.
     */
    fun letterGrey(percent: Double): Int = (255.0 * (1.0 - percent / 100.0).coerceIn(0.0, 1.0).pow(1.0 / 2.2)).roundToInt()

    /** "Suggest cataract / eye-health check": reduced contrast and (age from the rule, or low-light / glare complaints). */
    fun eyeHealthNote(reduced: Boolean, age: Int?, lowLightOrGlareComplaints: Boolean, cfg: ContrastConfig): Boolean =
        reduced && ((age != null && age >= cfg.eyeHealthNoteFromAge) || lowLightOrGlareComplaints)
}

// ---- Colour --------------------------------------------------------------------------------------

@Serializable enum class PlateType { CONTROL, RED_GREEN, BLUE_YELLOW }

@Serializable
enum class ColourOutcome {
    NORMAL, POSSIBLE_RED_GREEN, POSSIBLE_BLUE_YELLOW, POSSIBLE_RED_GREEN_AND_BLUE_YELLOW,
    /** The control plate (readable with any colour vision) was missed: the test is not valid and must be repeated. */
    INVALID,
}

@Serializable
data class ColourResult(
    val outcome: ColourOutcome,
    val controlWrong: Int, val redGreenWrong: Int, val blueYellowWrong: Int,
    val plates: Int,
) {
    val possibleDeficiency: Boolean get() = outcome != ColourOutcome.NORMAL && outcome != ColourOutcome.INVALID
}

/** One plate: its kind, the digit hidden in it and the seed of its dot pattern. */
data class PlateSpec(val type: PlateType, val digit: Int, val seed: Long)

/** One dot of a plate, in fractions of the plate diameter (centre 0.5, 0.5); [shade] picks a palette colour. */
data class PlateDot(val x: Double, val y: Double, val r: Double, val figure: Boolean, val shade: Int)

/**
 * Our own generated pseudoisochromatic dot plates (no copied test images): random non-overlapping dots in a circle;
 * the dots inside a digit's shape take the figure palette, the others the background palette. The two palettes of a
 * red-green or blue-yellow plate differ mainly along that confusion direction; the control plate differs for everyone.
 */
object ColourPlates {
    /** 5 × 7 digit shapes, rows top to bottom; digits 2–9 are used (0 and 1 are too easy to guess from the outline). */
    private val FONT = mapOf(
        2 to listOf("01110", "10001", "00001", "00110", "01000", "10000", "11111"),
        3 to listOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
        4 to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
        5 to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
        6 to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
        7 to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
        8 to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
        9 to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
    )
    val DIGITS: List<Int> = FONT.keys.sorted()
    private const val BOX_W = 0.40
    private const val BOX_H = 0.56

    /** True when the plate point ([x], [y]) lies inside [digit]'s shape. */
    fun inDigit(digit: Int, x: Double, y: Double): Boolean {
        val rows = FONT.getValue(digit)
        val u = (x - (0.5 - BOX_W / 2)) / BOX_W
        val v = (y - (0.5 - BOX_H / 2)) / BOX_H
        if (u < 0 || u >= 1 || v < 0 || v >= 1) return false
        return rows[(v * 7).toInt()][(u * 5).toInt()] == '1'
    }

    /** The plates of one run: the control plate first, then the red-green and blue-yellow plates mixed. */
    fun series(cfg: ColourConfig, rnd: Random): List<PlateSpec> {
        var last = -1
        fun digit(): Int { while (true) { val d = DIGITS[rnd.nextInt(DIGITS.size)]; if (d != last) { last = d; return d } } }
        // the order of the kinds is mixed first, then the digits are dealt, so no digit comes twice in a row
        val kinds = List(cfg.controlPlates) { PlateType.CONTROL } +
            (List(cfg.redGreenPlates) { PlateType.RED_GREEN } + List(cfg.blueYellowPlates) { PlateType.BLUE_YELLOW }).shuffled(rnd)
        return kinds.map { PlateSpec(it, digit(), rnd.nextLong()) }
    }

    /** The dot pattern of [spec] (the same seed always gives the same plate). */
    fun dots(spec: PlateSpec, cfg: ColourConfig): List<PlateDot> {
        val rnd = Random(spec.seed)
        val pal = cfg.palettes.getValue(spec.type)
        val out = ArrayList<PlateDot>(cfg.dots)
        val cell = cfg.maxDotRadius * 2
        val n = (1.0 / cell).toInt() + 1
        val grid = HashMap<Int, MutableList<PlateDot>>()
        var attempts = 0
        val maxAttempts = cfg.dots * 40
        while (out.size < cfg.dots && attempts < maxAttempts) {
            attempts++
            // big dots first; as the tries go on the dots get smaller and fill the gaps
            val t = attempts.toDouble() / maxAttempts
            val r = cfg.minDotRadius + (cfg.maxDotRadius - cfg.minDotRadius) * (1.0 - t) * (1.0 - t) * rnd.nextDouble(0.5, 1.0)
            val x = rnd.nextDouble(); val y = rnd.nextDouble()
            val dx = x - 0.5; val dy = y - 0.5
            if (dx * dx + dy * dy > (0.5 - r) * (0.5 - r)) continue
            val gx = (x / cell).toInt(); val gy = (y / cell).toInt()
            var free = true
            loop@ for (ix in gx - 1..gx + 1) for (iy in gy - 1..gy + 1) {
                for (d in grid[ix * n + iy].orEmpty()) {
                    val ex = d.x - x; val ey = d.y - y; val rr = d.r + r + 0.002
                    if (ex * ex + ey * ey < rr * rr) { free = false; break@loop }
                }
            }
            if (!free) continue
            val fig = inDigit(spec.digit, x, y)
            val dot = PlateDot(x, y, r, fig, rnd.nextInt(if (fig) pal.figure.size else pal.background.size))
            out += dot
            grid.getOrPut(gx * n + gy) { mutableListOf() } += dot
        }
        return out
    }

    /** [answers]: the digit entered for each plate (null = "can't see a number"). */
    fun score(answers: List<Pair<PlateSpec, Int?>>, cfg: ColourConfig): ColourResult {
        fun wrong(t: PlateType) = answers.count { (p, a) -> p.type == t && a != p.digit }
        val c = wrong(PlateType.CONTROL); val rg = wrong(PlateType.RED_GREEN); val by = wrong(PlateType.BLUE_YELLOW)
        val rgFail = rg >= cfg.redGreenFailFrom
        val byFail = by >= cfg.blueYellowFailFrom
        val outcome = when {
            c > 0 -> ColourOutcome.INVALID
            rgFail && byFail -> ColourOutcome.POSSIBLE_RED_GREEN_AND_BLUE_YELLOW
            rgFail -> ColourOutcome.POSSIBLE_RED_GREEN
            byFail -> ColourOutcome.POSSIBLE_BLUE_YELLOW
            else -> ColourOutcome.NORMAL
        }
        return ColourResult(outcome, c, rg, by, answers.size)
    }
}

// ---- All screening results of a session ----------------------------------------------------------

/** Tumbling-E near acuity of one eye ([eye] null = both eyes together), kept from the earlier test flow. */
@Serializable
data class ETestEyeResult(val eye: Eye?, val logMar: Double, val belowLimit: Boolean)

/** Fogged clock dial of one eye, kept from the earlier test flow: darker lines = possible astigmatism. */
@Serializable
data class DialEyeResult(val eye: Eye, val astigmatismFlag: Boolean, val axisDeg: Int? = null, val confidence: Confidence = Confidence.MEDIUM)

@Serializable
data class ScreeningResults(
    val amsler: List<AmslerEyeResult> = emptyList(),
    val contrast: List<ContrastEyeResult> = emptyList(),
    val colour: ColourResult? = null,
    val eTest: List<ETestEyeResult> = emptyList(),
    val clockDial: List<DialEyeResult> = emptyList(),
    /** Tests the customer or staff skipped ("AMSLER_RIGHT", "CONTRAST_LEFT", "COLOUR", …). */
    val skipped: List<String> = emptyList(),
) {
    val amslerAbnormal: Boolean get() = amsler.any { it.distortion }
    val contrastReduced: Boolean get() = contrast.any { it.reducedValid }
    /** The eyes whose grid showed wavy or missing lines. */
    val amslerEyes: List<Eye> get() = Eye.entries.filter { e -> amsler.any { it.eye == e && it.distortion } }

    /**
     * What the rules engine needs from the screening stage: the grid (urgent when abnormal), the E test's "below the
     * safe limit", and the dial's astigmatism flag per eye. No far-point power: that comes from the power check.
     */
    fun toVisionTests(): VisionTests {
        fun dial(e: Eye) = clockDial.firstOrNull { it.eye == e }?.let { MeasuredEye(null, it.confidence, astigmatismFlag = it.astigmatismFlag, astigmatismAxisDeg = it.axisDeg) }
        return VisionTests(
            right = dial(Eye.RIGHT), left = dial(Eye.LEFT),
            nearAcuityBelowThreshold = eTest.any { it.belowLimit }, amslerAbnormal = amslerAbnormal,
        )
    }
}
