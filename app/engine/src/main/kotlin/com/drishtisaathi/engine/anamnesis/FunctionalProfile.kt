package com.drishtisaathi.engine.anamnesis

import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.RulesConfig
import kotlinx.serialization.Serializable

@Serializable enum class VisionZone { NEAR, INTERMEDIATE, DISTANCE, MIXED }

/** Lens type the daily-use pattern points to (a staff hint next to the engine's lens type, not a replacement). */
@Serializable enum class ProfileLens { SINGLE_VISION, READING, OFFICE_COMPUTER, PROGRESSIVE }

/**
 * Functional vision profile: estimated daily hours at near (< 40 cm), intermediate (40–100 cm, computer) and
 * distance, from the interview answers and the weights in rules.json `functionalProfile`.
 */
@Serializable
data class FunctionalProfile(
    val nearHours: Double,
    val intermediateHours: Double,
    val distanceHours: Double,
    val dominant: VisionZone,
    val impliedLens: ProfileLens,
) {
    companion object {
        fun from(a: InterviewAnswers, rules: RulesConfig): FunctionalProfile {
            val c = rules.functionalProfile
            val screen = a.screenHours?.let { c.screenHours.getValue(it) } ?: 0.0
            val share = a.occupation?.let { c.screenIntermediateShareByOccupation[it] } ?: c.screenIntermediateShareDefault
            val near = (a.readingFrequency?.let { c.nearHoursByReading.getValue(it) } ?: 0.0) +
                (a.nearTask?.let { c.nearHoursByTask.getValue(it) } ?: 0.0) + screen * (1 - share)
            val intermediate = screen * share
            val distance = c.distanceHoursBase +
                (a.occupation?.let { c.distanceHoursByOccupation[it] } ?: 0.0) +
                (if (a.outdoorWork == true) c.distanceHoursOutdoorWork else 0.0) +
                (if (a.nightDriving == true) c.distanceHoursNightDriving else 0.0) +
                (a.distanceSituation?.let { c.distanceHoursBySituation.getValue(it) } ?: 0.0)

            val total = near + intermediate + distance
            val zones = listOf(VisionZone.NEAR to near, VisionZone.INTERMEDIATE to intermediate, VisionZone.DISTANCE to distance)
            val (top, topH) = zones.maxBy { it.second }
            val dominant = if (total > 0 && topH / total >= c.dominantMinShare) top else VisionZone.MIXED
            val presbyopic = a.age != null && a.age >= rules.ages.presbyopiaFrom
            val lens = when (dominant) {
                VisionZone.DISTANCE -> ProfileLens.SINGLE_VISION
                VisionZone.NEAR -> if (presbyopic) ProfileLens.READING else ProfileLens.SINGLE_VISION
                VisionZone.INTERMEDIATE -> if (presbyopic) ProfileLens.OFFICE_COMPUTER else ProfileLens.SINGLE_VISION
                VisionZone.MIXED -> if (presbyopic) ProfileLens.PROGRESSIVE else ProfileLens.SINGLE_VISION
            }
            return FunctionalProfile(round1(near), round1(intermediate), round1(distance), dominant, lens)
        }

        private fun round1(h: Double) = Math.round(h * 10) / 10.0
    }
}
