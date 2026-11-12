package com.drishtisaathi.vision

import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.anamnesis.QuickEstimator
import com.drishtisaathi.engine.anamnesis.QuickPlan
import com.drishtisaathi.engine.vision.VisionMath
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the customer eye check runs.
 * - [far]: power check and two-line blur test per eye (DISTANCE or BOTH);
 * - [near]: reading check with both eyes (NEAR or BOTH, any age); [add]: age ≥ quickEstimate.nearAddFromAge, so the
 *   near point gives a reading add; under that age it is compared with the age-expected near point instead;
 * - [grid]: the Amsler grid per eye, only when straight lines look bent, age 40+, or diabetes / BP.
 */
data class EyeCheckPlan(
    val far: Boolean,
    val near: Boolean,
    val add: Boolean,
    val grid: Boolean,
    /** NEAR only, under the add age: possible long sight when the reading check is skipped. */
    val possibleLongSight: Boolean,
) {
    val runsAnything: Boolean get() = far || near || grid

    companion object {
        const val GRID_FROM_AGE = 40

        fun gridNeeded(a: InterviewAnswers): Boolean =
            !a.urgentRedFlag && (a.straightLinesBent == true || (a.age ?: 0) >= GRID_FROM_AGE || a.diabetesOrBp == true)

        fun from(a: InterviewAnswers, q: QuickPlan): EyeCheckPlan = EyeCheckPlan(
            far = q.far,
            near = !a.urgentRedFlag && a.visionProblem?.near == true,
            add = q.near,
            grid = gridNeeded(a),
            possibleLongSight = q.possibleLongSight,
        )
    }
}

/**
 * Two-line blur test for one eye: horizontal lines, then vertical lines, each held close and moved away until it
 * blurs. Power per pattern = −1/d; null distance = still sharp at arm's length (0 D).
 */
object TwoLineAstig {
    const val FLAG_D = 0.75

    data class Result(
        val horizontalD: Double,
        val verticalD: Double,
        /** |difference| rounded to 0.25 D (shown as minus cylinder). */
        val cylD: Double,
        /** 180 when the horizontal lines blur first (closer), 90 when the vertical lines do; null without a flag. */
        val axisDeg: Int?,
        val flagged: Boolean,
    )

    fun analyse(horizontalMm: Double?, verticalMm: Double?): Result {
        val h = horizontalMm?.let { -1000.0 / it } ?: 0.0
        val v = verticalMm?.let { -1000.0 / it } ?: 0.0
        val diff = abs(h - v)
        val flagged = diff >= FLAG_D - 1e-9
        val axis = when {
            !flagged -> null
            h < v -> 180   // horizontal lines blur at the shorter distance: the vertical meridian is more myopic
            else -> 90
        }
        return Result(h, v, (diff / 0.25).roundToInt() * 0.25, axis, flagged)
    }
}

/**
 * Reading check (both eyes): near point → reading add from the age of quickEstimate.nearAddFromAge (Add = 1/WD − A/2,
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
