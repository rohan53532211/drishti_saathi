package com.drishtisaathi.vision

import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.QuickEye
import com.drishtisaathi.engine.QuickTarget
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.engine.anamnesis.QuickEstimator
import com.drishtisaathi.engine.vision.VisionMath
import com.drishtisaathi.i18n.Lang
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * What the eye check runs. Compulsory for everyone (also with a known power or a red flag):
 * - the far reading check, right eye then left (always);
 * - the near reading check with both eyes for NEAR / BOTH, age ≥ [ADD_FROM_AGE], or [full] (precision mode);
 * - [add]: age ≥ [ADD_FROM_AGE], so the near point gives a reading add; under that age it is compared with the
 *   age-expected near point instead (no plus number).
 */
data class EyeCheckPlan(val near: Boolean, val add: Boolean) {
    companion object {
        const val ADD_FROM_AGE = 38

        fun nearNeeded(a: InterviewAnswers): Boolean =
            a.visionProblem == VisionProblem.NEAR || a.visionProblem == VisionProblem.BOTH || (a.age ?: 0) >= ADD_FROM_AGE

        /** [full]: precision mode, the near check runs whatever the answers. */
        fun from(a: InterviewAnswers, full: Boolean = false): EyeCheckPlan =
            EyeCheckPlan(near = full || nearNeeded(a), add = (a.age ?: 0) >= ADD_FROM_AGE)
    }
}

/** Standard Sloan letters (equal legibility), used for the far reading check. */
const val SLOAN_LETTERS = "CDHKNORSVZ"

/**
 * The far check's letters: 5 random Sloan letters (no letter twice), replaced by a NEW set every time the measured
 * distance has changed by [STEP_MM] (3 cm) since the last set, so a remembered row cannot be "read" further away.
 */
data class LetterSet(val letters: String, val anchorMm: Double?) {
    /** The set for [distanceMm]: unchanged within 3 cm of the anchor, otherwise a fresh one anchored there. */
    fun update(distanceMm: Double?, rnd: Random): LetterSet = when {
        distanceMm == null || distanceMm <= 0 -> this
        anchorMm == null -> copy(anchorMm = distanceMm)
        abs(distanceMm - anchorMm) >= STEP_MM - 1e-9 -> LetterSet(fresh(rnd, letters), distanceMm)
        else -> this
    }

    companion object {
        const val STEP_MM = 30.0
        const val COUNT = 5

        fun start(rnd: Random) = LetterSet(fresh(rnd, ""), null)

        /** 5 different letters, never the same row as [previous]. */
        fun fresh(rnd: Random, previous: String): String {
            while (true) {
                val s = SLOAN_LETTERS.toList().shuffled(rnd).take(COUNT).joinToString("")
                if (s != previous) return s
            }
        }
    }
}

/** The near check's short sentences (assets/reading_sentences.json), one list per language. */
@Serializable
data class ReadingSentences(val en: List<String>, val hi: List<String>) {
    fun of(lang: Lang): List<String> = if (lang == Lang.HI) hi else en

    /** A random sentence in [lang], never the same as [previous] (a NEW one on every attempt). */
    fun pick(lang: Lang, rnd: Random, previous: String? = null): String {
        val list = of(lang)
        require(list.isNotEmpty()) { "no reading sentences for $lang" }
        if (list.size == 1) return list[0]
        while (true) { val s = list[rnd.nextInt(list.size)]; if (s != previous) return s }
    }

    companion object {
        const val ASSET = "reading_sentences.json"
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): ReadingSentences = json.decodeFromString(serializer(), text)
    }
}

/**
 * Powers are shown as a range, never as one exact number: centre ± [HALF_D] (0.25 D).
 * Far: centre = −1/d (m) rounded to the nearest 0.25 D: 30 cm → −3.33 → centre −3.25 → "−3.00 to −3.50 D".
 */
object PowerRange {
    const val STEP_D = 0.25
    const val HALF_D = 0.25

    data class Range(val centreD: Double, val fromD: Double, val toD: Double)

    fun around(centre: Double): Range = Range(centre, centre + HALF_D * (if (centre < 0) 1 else -1), centre - HALF_D * (if (centre < 0) 1 else -1))

    /** Far range from the distance (mm) where the letters can no longer be read. */
    fun far(blurMm: Double): Range {
        val d = 1000.0 / blurMm
        val centre = -(d / STEP_D).roundToInt() * STEP_D
        return around(centre)
    }

    /** Reading add range: the add rounded to 0.25 ± 0.25 (lower end never below 0). */
    fun add(addD: Double): Range {
        val c = (addD / STEP_D).roundToInt() * STEP_D
        return Range(c, (c - HALF_D).coerceAtLeast(0.0), c + HALF_D)
    }

    /** The far reading for one eye: the centre is the power used for lens and frame suggestions. */
    fun farEye(blurMm: Double): QuickEye {
        val r = far(blurMm)
        return QuickEye(r.centreD, (blurMm / 10.0).roundToInt().toDouble(), QuickTarget.WHITE, rangeFromD = r.fromD, rangeToD = r.toD)
    }

    /** Two readings of one eye (the one automatic repeat): the mean vergence. */
    fun farEyeMedian(aMm: Double, bMm: Double): QuickEye = farEye(2.0 / (1.0 / aMm + 1.0 / bMm))
}

/**
 * Near reading check (both eyes): near point → reading add from age [EyeCheckPlan.ADD_FROM_AGE] (Add = 1/WD − A/2,
 * clamped +0.75…+3.00, rounded to 0.25); under that age no plus number, but the near point is compared with the
 * age-expected one (Hofstetter): a measured amplitude below the Hofstetter minimum means "farther than expected".
 */
object NearCheck {
    const val ADD_MIN = 0.75
    const val ADD_MAX = 3.00

    data class Result(
        val nearPointCm: Double,
        val addD: Double?,
        /** Under the add age: the near point expected from the Hofstetter mean amplitude. */
        val expectedNearPointCm: Double?,
        val fartherThanExpected: Boolean,
    )

    fun analyse(nearPointMm: Double, age: Int?, farSeD: Double?, answers: InterviewAnswers, rules: RulesConfig): Result {
        val vm = VisionMath(rules)
        val m = nearPointMm / 1000.0
        val cm = (nearPointMm / 10.0).roundToInt().toDouble()
        if (age == null) return Result(cm, null, null, false)
        if (age >= rules.quickEstimate.nearAddFromAge) {
            val wd = QuickEstimator(rules).workingDistanceM(answers)
            val add = vm.nearPointAdd(m, farSeD ?: 0.0, wd, age).addD
            return Result(cm, ((add / 0.25).roundToInt() * 0.25).coerceIn(ADD_MIN, ADD_MAX), null, false)
        }
        val amplitude = vm.accommodationAmplitude(m, farSeD ?: 0.0)
        val expected = vm.hofstetterMean(age).takeIf { it > 0 }?.let { (100.0 / it).roundToInt().toDouble() }
        return Result(cm, null, expected, amplitude < vm.hofstetterMin(age))
    }
}

/**
 * Far letters inside their fixed panel: distance-locked height from farPoint.letterArcmin (default 7.5' = 20/30,
 * height = 2·d·tan(arcmin/2)), never below farPoint.minLetterPx; letter spacing = 1 letter width (Sloan letters
 * are square); shrunk only if the row would not fit the panel width.
 */
object FarLetters {
    /** Used until the first distance reading (and kept when the face is briefly lost), so the letters never vanish. */
    const val DEFAULT_MM = 300.0

    data class Layout(
        val letters: String,
        val letterPx: Double,
        val letterMm: Double,
        /** Centre x of each letter, from the panel's left edge (px). */
        val centresPx: List<Double>,
        val distanceMm: Double,
    )

    fun heightPx(distanceMm: Double, scale: ScreenScale, cfg: com.drishtisaathi.engine.config.FarPointConfig): Double =
        maxOf(Sizing.arcminPx(distanceMm, cfg.letterArcmin, scale), cfg.minLetterPx)

    fun layout(
        letters: String, distanceMm: Double?, scale: ScreenScale, cfg: com.drishtisaathi.engine.config.FarPointConfig, panelWidthPx: Double,
    ): Layout {
        val d = distanceMm?.takeIf { it > 0 } ?: DEFAULT_MM
        val n = letters.length.coerceAtLeast(1)
        val fit = panelWidthPx / (2 * n - 1)
        val h = minOf(heightPx(d, scale, cfg), fit)
        val start = (panelWidthPx - (2 * n - 1) * h) / 2 + h / 2
        return Layout(letters, h, h * scale.mmPerPx, List(letters.length) { start + it * 2 * h }, d)
    }
}
