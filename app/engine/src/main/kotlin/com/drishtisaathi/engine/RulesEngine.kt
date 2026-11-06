package com.drishtisaathi.engine

import kotlinx.serialization.Serializable
import kotlin.math.abs

// ---------------------------------------------------------------------------------------------
// Outputs
// ---------------------------------------------------------------------------------------------

@Serializable enum class ReferralLevel { NONE, ROUTINE, URGENT }

@Serializable
enum class ReferralReason(val urgent: Boolean) {
    // Urgent, same day
    FLASHES_FLOATERS_CURTAIN(true),
    SUDDEN_LOSS_PAIN_REDNESS_HALOS_DOUBLE(true),
    AMSLER_DISTORTION(true),
    // Routine optometrist visit
    DIABETES_OR_BP_WITH_BLUR(false),
    CHILD_UNDER_AGE(false),
    NEAR_ACUITY_BELOW_THRESHOLD(false),
    LOW_CONFIDENCE_REPEATED(false),
    ASTIGMATISM_FLAG(false),
    DISTANCE_BLUR_NO_MYOPIA_FOUND(false),
}

@Serializable
data class Referral(val level: ReferralLevel, val reasons: List<ReferralReason>, val adviceWithheld: Boolean)

@Serializable enum class LensType { NONE_SUGGESTED, SINGLE_VISION, READING, PROGRESSIVE, BIFOCAL_OR_SEPARATE_PAIRS }

@Serializable enum class Eye { RIGHT, LEFT }

/** An estimated power band in dioptres, e.g. −2.00 to −1.00. Never presented as a final value. */
@Serializable
data class PowerBand(val lowD: Double, val highD: Double) {
    val midD: Double get() = (lowD + highD) / 2.0
}

/** A starting lens for the shop's own trial-lens test. Staff refine it by hand. */
@Serializable
data class TrialLens(val sph: Double, val cyl: Double = 0.0, val axis: Int? = null)

@Serializable
data class EyeAdvice(
    val eye: Eye,
    val band: Estimate<PowerBand>,
    val startingTrialLens: Estimate<TrialLens>,
    /** Strongest meridian of the starting lens; drives the thickness estimate. */
    val thicknessPowerD: Double,
)

@Serializable
enum class Note {
    HAND_TEST_NEEDED,
    HYPEROPIA_POSSIBLE,
    CHECK_CYLINDER,
    OLD_NUMBERS_STALE,
    OLD_NUMBERS_AGE_UNKNOWN,
    NO_POWER_INFORMATION,
    ADD_FROM_AGE_TABLE,
    PD_ASSUMED,
    PD_IMPLAUSIBLE,
    FACE_WIDTH_ASSUMED,
}

@Serializable
data class LensAdvice(
    val eyes: List<EyeAdvice>,
    val add: Estimate<PowerBand>?,
    val lensType: Estimate<LensType>,
    val materialId: Estimate<String>,
    val coatings: List<Coating>,
)

@Serializable
data class Assessment(
    val referral: Referral,
    /** Null when advice is withheld (urgent referral, or low confidence twice). */
    val advice: LensAdvice?,
    val notes: List<Note>,
    val pdMm: Estimate<Double>,
    val faceWidthMm: Estimate<Double>,
)

// ---------------------------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------------------------

/** M4 fusion and recommendation rules. Every threshold and table comes from [RulesConfig]. */
class RulesEngine(private val rules: RulesConfig, priors: AnthroPriors) {

    private val optics = Optics(rules.optics)
    private val priors = priors
    private val gate = PlausibilityGate(priors, rules.plausibility)

    fun assess(
        answers: InterviewAnswers,
        staff: StaffMeasurements = StaffMeasurements(),
        tests: VisionTests = VisionTests(),
    ): Assessment {
        val notes = linkedSetOf<Note>()
        val referral = referral(answers, tests)
        val (pd, faceWidth) = anatomy(staff, notes)

        val advice = if (referral.adviceWithheld) null else lensAdvice(answers, tests, notes)
        return Assessment(referral, advice, notes.toList(), pd, faceWidth)
    }

    // ---- Section 6 referral ------------------------------------------------------------------

    fun referral(a: InterviewAnswers, t: VisionTests): Referral {
        val reasons = mutableListOf<ReferralReason>()
        if (a.redFlagFlashes == true) reasons += ReferralReason.FLASHES_FLOATERS_CURTAIN
        if (a.redFlagOther == true) reasons += ReferralReason.SUDDEN_LOSS_PAIN_REDNESS_HALOS_DOUBLE
        if (t.amslerAbnormal) reasons += ReferralReason.AMSLER_DISTORTION

        val anyBlur = a.distanceBlur == true || a.nearBlur == true
        if (a.diabetesOrBp == true && anyBlur) reasons += ReferralReason.DIABETES_OR_BP_WITH_BLUR
        if (a.age != null && a.age < rules.ages.childReferralUnder) reasons += ReferralReason.CHILD_UNDER_AGE
        if (t.nearAcuityBelowThreshold) reasons += ReferralReason.NEAR_ACUITY_BELOW_THRESHOLD
        if (t.lowConfidenceCount >= rules.referral.lowConfidenceCountForReferral) reasons += ReferralReason.LOW_CONFIDENCE_REPEATED
        val measured = listOfNotNull(t.right, t.left)
        if (measured.any { it.astigmatismFlag }) reasons += ReferralReason.ASTIGMATISM_FLAG
        if (a.distanceBlur == true && measured.isNotEmpty() && measured.all { it.noMyopiaAtArmsLength }) {
            reasons += ReferralReason.DISTANCE_BLUR_NO_MYOPIA_FOUND
        }

        val level = when {
            reasons.any { it.urgent } -> ReferralLevel.URGENT
            reasons.isNotEmpty() -> ReferralLevel.ROUTINE
            else -> ReferralLevel.NONE
        }
        val withheld = level == ReferralLevel.URGENT || ReferralReason.LOW_CONFIDENCE_REPEATED in reasons
        return Referral(level, reasons, withheld)
    }

    // ---- PD / face width -----------------------------------------------------------------------

    private fun anatomy(staff: StaffMeasurements, notes: MutableSet<Note>): Pair<Estimate<Double>, Estimate<Double>> {
        val fw = staff.faceWidthMm?.let { Estimate(it, Confidence.MEDIUM, Source.STAFF_ENTRY) }
            ?: Estimate(priors.faceWidthMm.mean, Confidence.LOW, Source.POPULATION_AVERAGE).also { notes += Note.FACE_WIDTH_ASSUMED }

        val pd = if (staff.pdMm != null) {
            val check = gate.check(staff.pdMm, staff.faceWidthMm)
            if (check.verdict == PlausibilityGate.Verdict.OK) {
                Estimate(staff.pdMm, Confidence.MEDIUM, Source.STAFF_ENTRY)
            } else {
                notes += Note.PD_IMPLAUSIBLE
                Estimate(staff.pdMm, Confidence.LOW, Source.STAFF_ENTRY)
            }
        } else {
            notes += Note.PD_ASSUMED
            Estimate(priors.pdMm.mean, Confidence.LOW, Source.POPULATION_AVERAGE)
        }
        return pd to fw
    }

    // ---- Lens advice -------------------------------------------------------------------------

    private fun lensAdvice(a: InterviewAnswers, t: VisionTests, notes: MutableSet<Note>): LensAdvice {
        val eyes = listOfNotNull(
            eyeAdvice(Eye.RIGHT, t.right, a.knownRx?.right, a, notes),
            eyeAdvice(Eye.LEFT, t.left, a.knownRx?.left, a, notes),
        )
        val measured = listOfNotNull(t.right, t.left)
        if (measured.any { it.astigmatismFlag }) notes += Note.CHECK_CYLINDER

        if (eyes.isEmpty()) notes += Note.NO_POWER_INFORMATION
        if (a.distanceBlur == true && eyes.isEmpty()) notes += Note.HAND_TEST_NEEDED
        if (a.distanceBlur == true && measured.isNotEmpty() && measured.all { it.noMyopiaAtArmsLength }) {
            notes += Note.HYPEROPIA_POSSIBLE; notes += Note.HAND_TEST_NEEDED
        }
        if (a.headacheStrain == true && eyes.isEmpty()) notes += Note.HAND_TEST_NEEDED

        val lensType = lensType(a, eyes, notes)
        val add = if (lensType.value in NEAR_TYPES) addBand(a, t, notes) else null
        return LensAdvice(eyes, add, lensType, material(a, eyes), coatings(a))
    }

    private fun eyeAdvice(eye: Eye, m: MeasuredEye?, old: EyeRx?, a: InterviewAnswers, notes: MutableSet<Note>): EyeAdvice? {
        val usableMeasure = m?.takeIf { it.seD != null && !it.noMyopiaAtArmsLength }
        if (usableMeasure != null && (usableMeasure.confidence != Confidence.LOW || old == null)) {
            val se = optics.roundToStep(usableMeasure.seD!!)
            val hw = rules.optics.measuredBandHalfWidthD
            val c = usableMeasure.confidence
            return EyeAdvice(
                eye,
                Estimate(PowerBand(se - hw, se + hw), c, Source.MEASURED),
                Estimate(TrialLens(se), c, Source.MEASURED),
                thicknessPowerD = se,
            )
        }
        if (old == null) return null

        val years = a.glassesAgeYears
        val fresh = years != null && years <= rules.oldNumbers.freshMaxYears
        when {
            years == null -> notes += Note.OLD_NUMBERS_AGE_UNKNOWN
            !fresh -> notes += Note.OLD_NUMBERS_STALE
        }
        if (abs(old.cyl) >= rules.oldNumbers.significantCylinderD) notes += Note.CHECK_CYLINDER

        val conf = if (fresh) Confidence.MEDIUM else Confidence.LOW
        val hw = if (fresh) rules.oldNumbers.bandHalfWidthFreshD else rules.oldNumbers.bandHalfWidthStaleD
        val se = optics.roundToStep(old.sphericalEquivalent)
        return EyeAdvice(
            eye,
            Estimate(PowerBand(se - hw, se + hw), conf, Source.OLD_GLASSES_NUMBERS),
            Estimate(TrialLens(old.sph, old.cyl, old.axis), conf, Source.OLD_GLASSES_NUMBERS),
            thicknessPowerD = old.strongestMeridian,
        )
    }

    private fun lensType(a: InterviewAnswers, eyes: List<EyeAdvice>, notes: MutableSet<Note>): Estimate<LensType> {
        val significant = rules.oldNumbers.significantDistanceSeD
        val needsDistance = a.distanceBlur == true || eyes.any { abs(it.band.value.midD) >= significant }
        val oldAdd = a.knownRx?.eyes?.any { (it.add ?: 0.0) > 0 } == true
        val nearNeed = a.nearBlur == true || oldAdd
        val presbyopic = a.age != null && a.age >= rules.ages.presbyopiaFrom

        val type = when {
            (presbyopic && nearNeed && needsDistance) || (oldAdd && needsDistance) -> multifocal(a)
            presbyopic && nearNeed -> LensType.READING
            needsDistance -> LensType.SINGLE_VISION
            else -> {
                if (a.nearBlur == true) { notes += Note.HAND_TEST_NEEDED; notes += Note.HYPEROPIA_POSSIBLE }
                LensType.NONE_SUGGESTED
            }
        }
        return Estimate(type, Confidence.MEDIUM, Source.INTERVIEW)
    }

    /** Progressive for office/screen users who can afford it; bifocal or separate pairs otherwise. */
    private fun multifocal(a: InterviewAnswers): LensType {
        val r = rules.lensTypeRules
        if (a.budget in r.lowBudgets) return LensType.BIFOCAL_OR_SEPARATE_PAIRS
        val screenUser = a.occupation in r.progressiveOccupations || a.screenHours in r.progressiveScreenHours
        return if (screenUser) LensType.PROGRESSIVE else LensType.BIFOCAL_OR_SEPARATE_PAIRS
    }

    private fun addBand(a: InterviewAnswers, t: VisionTests, notes: MutableSet<Note>): Estimate<PowerBand>? {
        t.measuredAddD?.let { m ->
            val add = optics.clampAdd(optics.roundToStep(m.value))
            return Estimate(PowerBand(add, add), m.confidence, Source.MEASURED)
        }
        val ageRow = a.age?.let { age -> rules.addAgeTable.firstOrNull { age in it.minAge..it.maxAge } }
        val oldAdd = a.knownRx?.eyes?.mapNotNull { it.add }?.filter { it > 0 }?.maxOrNull()
        val fresh = a.glassesAgeYears != null && a.glassesAgeYears <= rules.oldNumbers.freshMaxYears

        if (oldAdd != null && (fresh || ageRow == null)) {
            return Estimate(PowerBand(oldAdd, oldAdd), if (fresh) Confidence.MEDIUM else Confidence.LOW, Source.OLD_GLASSES_NUMBERS)
        }
        if (ageRow != null) {
            notes += Note.ADD_FROM_AGE_TABLE
            return Estimate(PowerBand(ageRow.lowD, ageRow.highD), Confidence.LOW, Source.AGE_TABLE)
        }
        return null
    }

    private fun material(a: InterviewAnswers, eyes: List<EyeAdvice>): Estimate<String> {
        val mr = rules.materialRules
        val maxAbsSe = eyes.maxOfOrNull { abs(it.band.value.midD) }
        val powerConf = eyes.map { it.band.confidence }.fold(Confidence.HIGH) { acc, c -> acc.min(c) }

        val impact = (a.age != null && a.age < rules.ages.polycarbonateUnder) || a.occupation in mr.impactOccupations
        if (impact) return Estimate(mr.impactMaterial, Confidence.HIGH, Source.RULE)
        if (maxAbsSe == null) return Estimate(mr.defaultMaterial, Confidence.LOW, Source.RULE)

        val id = when {
            maxAbsSe >= mr.veryHighIndexFromAbsSeD -> mr.veryHighIndexMaterial
            maxAbsSe >= mr.highIndexFromAbsSeD -> mr.highIndexMaterial
            else -> mr.defaultMaterial
        }
        return Estimate(id, powerConf, Source.RULE)
    }

    private fun coatings(a: InterviewAnswers): List<Coating> =
        rules.coatingRules.filter { it.`when`.matches(a) }.flatMap { it.coatings }.distinct()

    companion object {
        val NEAR_TYPES = setOf(LensType.READING, LensType.PROGRESSIVE, LensType.BIFOCAL_OR_SEPARATE_PAIRS)
    }
}
