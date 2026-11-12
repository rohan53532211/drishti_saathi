package com.drishtisaathi.engine.anamnesis

import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.Optics
import com.drishtisaathi.engine.PowerKnown
import com.drishtisaathi.engine.QuickTarget
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.engine.vision.VisionMath

/** Which quick tests the power path runs for this customer. */
data class QuickPlan(
    /** Far point per eye (DISTANCE or BOTH). */
    val far: Boolean,
    /** Near point → reading add (NEAR or BOTH, age ≥ quickEstimate.nearAddFromAge). Runs after the far point. */
    val near: Boolean,
    /** NEAR only and younger: no plus power is estimated; "possible long-sightedness, check with trial lens". */
    val possibleLongSight: Boolean,
) {
    val runsAnything: Boolean get() = far || near
}

/**
 * The power path's quick estimate (pure; the screen only supplies distances).
 *
 * Live line while the phone moves: "If blur starts here: about −X.XX D" with SE = −1/d (white) or −1/d + LCA (blue,
 * optics.lcaBlueOffsetD), rounded to 0.25 D. The tap ("Blur" / "dhundhla") freezes it. One reading per eye, no
 * repeats and no Bayesian band: it is approximate and always confirmed with the trial lens. The full far-point test
 * stays available to staff.
 */
class QuickEstimator(private val rules: RulesConfig) {
    private val vm = VisionMath(rules)
    private val optics = Optics(rules)
    private val q = rules.quickEstimate

    fun liveSeD(distanceM: Double, target: QuickTarget = q.target): Double {
        val raw = if (target == QuickTarget.WHITE) vm.seFromWhiteFarPoint(distanceM) else vm.seFromBlueFarPoint(distanceM)
        return optics.roundToStep(raw)
    }

    fun plan(problem: VisionProblem?, age: Int?): QuickPlan {
        val p = problem ?: return QuickPlan(far = false, near = false, possibleLongSight = false)
        val oldEnough = age != null && age >= q.nearAddFromAge
        return QuickPlan(
            far = p.distance,
            near = p.near && oldEnough,
            possibleLongSight = p == VisionProblem.NEAR && !oldEnough,
        )
    }

    /** Plan for this interview; nothing when an urgent red flag stopped the power path. */
    fun plan(a: InterviewAnswers): QuickPlan =
        if (a.urgentRedFlag) QuickPlan(false, false, false) else plan(a.visionProblem, a.age)

    /** Working distance for the add: the "usual reading distance" answer, else the occupation default. */
    fun workingDistanceM(a: InterviewAnswers): Double =
        a.nearDistance?.let { q.workingDistanceM.getValue(it) } ?: optics.workingDistanceM(a.occupation)

    /**
     * Reading add from one near-point reading, with the existing near-point formula and the Hofstetter age check
     * ([VisionMath.fuseAdd]). [farSeD] = the quick far-point SE (mean of the eyes) or null (treated as 0).
     */
    fun nearAdd(nearPointM: Double, farSeD: Double?, a: InterviewAnswers): Double? {
        val age = a.age ?: return null
        if (age < q.nearAddFromAge) return null
        return vm.fuseAdd(nearPointM, farSeD ?: 0.0, workingDistanceM(a), age, Confidence.LOW).add.value
    }

    /**
     * YES path: offer an optional quick check to compare with the known power when the glasses are older than
     * quickEstimate.compareIfGlassesOlderThanYears, or blur is still reported with the current glasses.
     */
    fun offerCompare(a: InterviewAnswers): Boolean {
        if (a.powerKnown != PowerKnown.YES || a.urgentRedFlag) return false
        val old = (a.glassesAgeYears ?: -1) > q.compareIfGlassesOlderThanYears
        val stillBlur = a.wearsGlasses == true && (a.visionProblem?.let { it != VisionProblem.NONE } == true || a.happyWithGlasses == false)
        return (old || stillBlur) && plan(a.visionProblem, a.age).far
    }
}
