package com.drishtisaathi.engine.profile

import com.drishtisaathi.engine.Budget
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.Occupation
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.engine.config.LensEngineConfig
import com.drishtisaathi.engine.config.VisionProfileConfig
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Everything the profile rings and the lens engine use about one customer, from the survey, the interview (as a
 * fallback when a survey question was not answered) and the measurements. Null = not known.
 */
data class ProfileInputs(
    val age: Int? = null,
    /** Measured near add (D); null = not measured. */
    val addD: Double? = null,
    val myopic: Boolean = false,
    val screenHours: Double? = null,
    val tiredEyes: Freq? = null,
    val nearBlur: Freq? = null,
    val headache: Freq? = null,
    val neckPain: Freq? = null,
    val focusStruggle: Freq? = null,
    val lowLightTrouble: Freq? = null,
    /** Hours a day at a desk / screen for work. */
    val deskHours: Double? = null,
    val workActivities: Set<String> = emptySet(),
    val drive: DriveFrequency? = null,
    val glare: Freq? = null,
    val lowLightDriving: Freq? = null,
    val outdoorHours: Double? = null,
    val leisure: Set<String> = emptySet(),
    val indoorOutdoor: String? = null,
    val onePair: Boolean? = null,
    val firstProgressive: Boolean? = null,
    val budget: Budget? = null,
    /** The contrast test came out reduced (counts as poor low-light vision). */
    val contrastReduced: Boolean = false,
    val problem: VisionProblem? = null,
) {
    companion object {
        private val OCCUPATION_ACTIVITY = mapOf(
            Occupation.OFFICE_COMPUTER to "DESKTOP", Occupation.FINE_HANDWORK to "FINE_HANDWORK", Occupation.FIELD_OUTDOOR to "FIELD_FARM",
            Occupation.SHOP_BUSINESS to "MEETINGS", Occupation.STUDENT to "READING_WRITING", Occupation.DRIVER to "FIELD_FARM",
        )

        fun from(
            a: InterviewAnswers, s: SurveyAnswers, cfg: LensEngineConfig,
            quick: QuickEstimate? = null, screening: ScreeningResults? = null, gate: SafetyGate? = null, addD: Double? = null,
        ): ProfileInputs {
            val strain = if (a.headacheStrain == true) Freq.SOMETIMES else null
            val activities = s.set("workActivities").ifEmpty { setOfNotNull(a.occupation?.let(OCCUPATION_ACTIVITY::get)) }
            val screen = s.number("screenHoursDay") ?: a.screenHours?.let { cfg.fallbackScreenHours[it] }
            val desk = s.number("workHours")?.takeIf { "DESKTOP" in activities }
                ?: cfg.fallbackOfficeDeskHours.takeIf { !s.answered("workHours") && a.occupation == Occupation.OFFICE_COMPUTER && (screen ?: 0.0) >= cfg.screenHoursFrom }
            val drive = s.one("driveFrequency")?.let { v -> DriveFrequency.entries.firstOrNull { it.name == v } }
                ?: when { a.occupation == Occupation.DRIVER -> DriveFrequency.DAILY; a.nightDriving == true -> DriveFrequency.WEEKLY; else -> null }
            val myopic = quick?.eyes?.any { (_, e) -> !e.noMyopiaAtReach && (e.seD ?: 0.0) < 0 } == true ||
                a.knownRx?.eyes?.any { it.sphericalEquivalent < 0 } == true || gate?.childMyopia == true
            return ProfileInputs(
                age = a.age, addD = addD ?: quick?.addD, myopic = myopic, screenHours = screen,
                tiredEyes = s.freq("tiredEyes") ?: strain, nearBlur = s.freq("nearBlurFreq") ?: (if (a.nearBlur == true) Freq.SOMETIMES else null),
                headache = s.freq("headacheFreq") ?: strain, neckPain = s.freq("neckPain"),
                focusStruggle = s.freq("focusStruggle"), lowLightTrouble = s.freq("lowLightTrouble"),
                deskHours = desk, workActivities = activities, drive = drive,
                glare = s.freq("glareOncoming"), lowLightDriving = s.freq("lowLightDriving"),
                outdoorHours = s.number("outdoorHours") ?: cfg.fallbackOutdoorWorkHours.takeIf { a.outdoorWork == true },
                leisure = s.set("leisureActivities"), indoorOutdoor = s.one("indoorOutdoor"),
                onePair = s.yes("onePair"), firstProgressive = s.yes("firstProgressive") ?: a.firstTimeWearer.takeIf { it == true },
                budget = a.budget, contrastReduced = screening?.contrastReduced == true, problem = a.visionProblem,
            )
        }
    }
}

@Serializable enum class WorkLabel { NEAR_FOCUSED, BALANCED, DISTANCE_FOCUSED }
@Serializable enum class DigitalLabel { LIGHT, CONNECTED, DIGITAL_NATIVE }
@Serializable enum class MobilityLabel { RARELY, WEEKLY, DAILY }
@Serializable enum class LeisureLabel { LOW, MEDIUM, HIGH }

/** One profile ring: a score 0–100 and a label; [known] = the customer answered what it is based on. */
@Serializable data class Ring(val score: Int, val label: String, val known: Boolean)

/** The four profile rings (Phase E). */
@Serializable
data class VisionProfile(
    val work: Ring, val digital: Ring, val mobility: Ring, val leisure: Ring,
    /** Glare from oncoming lights or poor low-light vision at least "sometimes". */
    val nightGlareSensitive: Boolean,
) {
    val rings: List<Pair<String, Ring>> get() = listOf("WORK" to work, "DIGITAL" to digital, "MOBILITY" to mobility, "LEISURE" to leisure)
    /** The mean of the known rings, for the big ring. */
    val overall: Int get() = rings.map { it.second }.filter { it.known }.ifEmpty { rings.map { it.second } }.map { it.score }.average().roundToInt()

    companion object {
        private fun pct(v: Double) = v.roundToInt().coerceIn(0, 100)

        fun from(i: ProfileInputs, cfg: VisionProfileConfig): VisionProfile {
            // Work: how much of the work is near work
            val weights = i.workActivities.mapNotNull { cfg.work.nearWeights[it] }
            val workScore = if (weights.isEmpty()) cfg.work.defaultScore else pct(weights.average() * 100)
            val workLabel = when {
                workScore >= cfg.work.nearFocusedFrom -> WorkLabel.NEAR_FOCUSED
                workScore < cfg.work.distanceFocusedBelow -> WorkLabel.DISTANCE_FOCUSED
                else -> WorkLabel.BALANCED
            }
            // Digital: screen hours a day (< 3 light, 3–6 connected, > 6 digital native)
            val h = i.screenHours
            val digitalLabel = when {
                h == null || h < cfg.digital.connectedFromHours -> DigitalLabel.LIGHT
                h > cfg.digital.nativeAboveHours -> DigitalLabel.DIGITAL_NATIVE
                else -> DigitalLabel.CONNECTED
            }
            // Mobility: how often the person drives or rides
            val d = i.drive
            val mobilityLabel = when (d) { DriveFrequency.DAILY -> MobilityLabel.DAILY; DriveFrequency.WEEKLY -> MobilityLabel.WEEKLY; else -> MobilityLabel.RARELY }
            val sensitive = listOf(i.glare, i.lowLightDriving).any { it?.atLeast(cfg.mobility.sensitiveFrom) == true }
            // Leisure: hours in the sun plus a bonus for sunny activities
            val o = i.outdoorHours
            val bonus = i.leisure.sumOf { cfg.leisure.activityBonus[it] ?: 0 }
            val leisureLabel = when {
                o == null || o < cfg.leisure.mediumFromHours -> LeisureLabel.LOW
                o >= cfg.leisure.highFromHours -> LeisureLabel.HIGH
                else -> LeisureLabel.MEDIUM
            }
            return VisionProfile(
                work = Ring(workScore, workLabel.name, weights.isNotEmpty()),
                digital = Ring(pct((h ?: 0.0) / cfg.digital.fullScaleHours * 100), digitalLabel.name, h != null),
                mobility = Ring(cfg.mobility.scores.getValue(d ?: DriveFrequency.NEVER), mobilityLabel.name, d != null),
                leisure = Ring(pct((o ?: 0.0) / cfg.leisure.fullScaleHours * 100 + bonus), leisureLabel.name, o != null),
                nightGlareSensitive = sensitive,
            )
        }
    }
}
