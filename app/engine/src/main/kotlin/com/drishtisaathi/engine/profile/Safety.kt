package com.drishtisaathi.engine.profile

import com.drishtisaathi.engine.Eye
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.PowerKnown
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.VisionTests
import com.drishtisaathi.engine.config.SafetyConfig
import kotlinx.serialization.Serializable

/**
 * The red flags (Phase A). The first six can be picked one by one in the "which of these" follow-up; the two
 * UNSPECIFIED values stand for a "yes" to one of the two red-flag questions without that follow-up.
 */
@Serializable
enum class RedFlag {
    SUDDEN_VISION_LOSS, EYE_PAIN_REDNESS, NEW_FLASHES, NEW_FLOATERS, CURTAIN_SHADOW, DOUBLE_VISION,
    /** Wavy, missing, blurred or broken lines marked on the grid test (never from an answer). */
    AMSLER_DISTORTION,
    /** The customer said straight lines (door frames, tiles) look bent or wavy: their answer, not a grid result. */
    BENT_LINES_REPORTED,
    /** "Flashes, floating spots or a curtain": yes, signs not picked. */
    FLASHES_FLOATERS_CURTAIN_UNSPECIFIED,
    /** "Sudden drop in vision, pain or redness, double vision": yes, signs not picked. */
    SUDDEN_LOSS_PAIN_DOUBLE_UNSPECIFIED;

    val unspecified: Boolean get() = this == FLASHES_FLOATERS_CURTAIN_UNSPECIFIED || this == SUDDEN_LOSS_PAIN_DOUBLE_UNSPECIFIED

    companion object {
        /** The signs offered in the follow-up question (survey id [Safety.SIGNS_QUESTION]). */
        val pickable = listOf(SUDDEN_VISION_LOSS, EYE_PAIN_REDNESS, NEW_FLASHES, NEW_FLOATERS, CURTAIN_SHADOW, DOUBLE_VISION)
    }
}

/** How the report is marked for the optometrist. */
@Serializable enum class ReportTag { URGENT, CHILD_MYOPIA, ROUTINE }

/** One step of the check, in the order it runs. */
@Serializable enum class CheckStep { SURVEY, CAMERA_PD, POWER, AMSLER, CONTRAST, COLOUR, E_TEST, CLOCK_DIAL, MORE_QUESTIONS }

/**
 * The safety gate. Everyone gets the check and the report always goes to the optometrist; this decides what may be
 * shown and how the report is marked.
 */
@Serializable
data class SafetyGate(
    val redFlags: List<RedFlag> = emptyList(),
    /** Sudden vision loss, curtain / shadow or new flashes: short path only, then urgent referral. */
    val emergency: Boolean = false,
    val child: Boolean = false,
    /** Under 18 with any minus power: the power is measured, no lens choice, referral for myopia control. */
    val childMyopia: Boolean = false,
    val tag: ReportTag = ReportTag.ROUTINE,
    /** The eyes whose grid test showed distortion (empty when the flag comes from the older both-eyes grid test). */
    val amslerEyes: List<Eye> = emptyList(),
) {
    val urgent: Boolean get() = redFlags.isNotEmpty()
    /** A red flag or child myopia: no lens is suggested or sold; the referral card is shown instead. */
    val lensAllowed: Boolean get() = !urgent && !childMyopia
    /** With a red flag or child myopia the power is worded "For the optometrist only", never "your power". */
    val powerForOptometristOnly: Boolean get() = urgent || childMyopia
    /** A child without myopia may get a lens family for children, labelled "optometrist confirms". */
    val optometristConfirms: Boolean get() = child && lensAllowed
}

object Safety {
    /** Survey question: which red-flag signs (multi-select of [RedFlag.pickable]). */
    const val SIGNS_QUESTION = "redFlagSigns"
    /** Survey question (Digital section): double vision → red-flag path. */
    const val DOUBLE_VISION_QUESTION = "doubleVision"

    /**
     * Every red flag found so far, without duplicates, in a fixed order. A red-flag "yes" whose follow-up was not
     * answered is kept as UNSPECIFIED (never dropped). The grid flag needs a grid result with distortion; the
     * customer's own "straight lines look bent" is a separate flag.
     */
    fun redFlags(a: InterviewAnswers, s: SurveyAnswers, tests: VisionTests? = null, screening: ScreeningResults? = null): List<RedFlag> {
        val out = linkedSetOf<RedFlag>()
        val picked = s.set(SIGNS_QUESTION).mapNotNull { v -> RedFlag.pickable.firstOrNull { it.name == v } }
        out += picked
        val flashesGroup = setOf(RedFlag.NEW_FLASHES, RedFlag.NEW_FLOATERS, RedFlag.CURTAIN_SHADOW)
        val otherGroup = setOf(RedFlag.SUDDEN_VISION_LOSS, RedFlag.EYE_PAIN_REDNESS, RedFlag.DOUBLE_VISION)
        if (a.redFlagFlashes == true && picked.none { it in flashesGroup }) out += RedFlag.FLASHES_FLOATERS_CURTAIN_UNSPECIFIED
        if (a.redFlagOther == true && picked.none { it in otherGroup }) out += RedFlag.SUDDEN_LOSS_PAIN_DOUBLE_UNSPECIFIED
        if (s.yes(DOUBLE_VISION_QUESTION) == true) out += RedFlag.DOUBLE_VISION
        if (gridDistortion(tests, screening)) out += RedFlag.AMSLER_DISTORTION
        if (a.straightLinesBent == true) out += RedFlag.BENT_LINES_REPORTED
        return RedFlag.entries.filter { it in out }
    }

    /**
     * The grid flag comes only from a grid test that was actually done: the per-eye grid of the screening stage, or
     * (sessions without it) the older grid test. An answer never sets it.
     */
    fun gridDistortion(tests: VisionTests?, screening: ScreeningResults?): Boolean =
        if (screening != null && screening.amsler.isNotEmpty()) screening.amslerAbnormal else tests?.amslerAbnormal == true

    fun isEmergency(flags: List<RedFlag>, cfg: SafetyConfig): Boolean =
        flags.any { it in cfg.emergencySigns || (it.unspecified && cfg.unspecifiedIsEmergency) }

    /** Any minus power in any eye: the phone's check, the full far-point test, or the customer's known power. */
    fun anyMyopia(a: InterviewAnswers, quick: QuickEstimate?, tests: VisionTests?, cfg: SafetyConfig): Boolean {
        val powers = buildList {
            quick?.eyes?.forEach { (_, e) -> if (!e.noMyopiaAtReach) e.seD?.let(::add) }
            listOfNotNull(tests?.right, tests?.left).forEach { e -> if (!e.noMyopiaAtArmsLength) e.seD?.let(::add) }
            if (a.powerKnown == PowerKnown.YES) a.knownRx?.eyes?.forEach { add(it.sphericalEquivalent); add(it.sph) }
        }
        return powers.any { it <= cfg.childMyopiaFromSeD }
    }

    fun gate(
        a: InterviewAnswers, s: SurveyAnswers, cfg: SafetyConfig,
        quick: QuickEstimate? = null, tests: VisionTests? = null, screening: ScreeningResults? = null,
    ): SafetyGate {
        val flags = redFlags(a, s, tests, screening)
        val child = a.age != null && a.age < cfg.childUnderAge
        val childMyopia = child && anyMyopia(a, quick, tests, cfg)
        val tag = when {
            flags.isNotEmpty() -> ReportTag.URGENT
            childMyopia -> ReportTag.CHILD_MYOPIA
            else -> ReportTag.ROUTINE
        }
        val gridEyes = if (RedFlag.AMSLER_DISTORTION in flags) screening?.amslerEyes.orEmpty() else emptyList()
        return SafetyGate(flags, isEmergency(flags, cfg), child, childMyopia, tag, gridEyes)
    }

    /**
     * The steps of the check. Everyone: survey → camera distance + PD → power of each eye → screening tests →
     * remaining questions. Emergency signs: the short path from rules.json (power of each eye + grid, about 2 min).
     */
    fun steps(gate: SafetyGate, cfg: SafetyConfig): List<CheckStep> =
        if (gate.emergency) cfg.shortPathSteps.map { CheckStep.valueOf(it) } else CheckStep.entries.toList()
}
