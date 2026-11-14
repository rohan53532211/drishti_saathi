package com.drishtisaathi.profile

import com.drishtisaathi.UiState
import com.drishtisaathi.engine.Eye
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.profile.ColourOutcome
import com.drishtisaathi.engine.profile.Contrast
import com.drishtisaathi.engine.profile.DialEyeResult
import com.drishtisaathi.engine.profile.ETestEyeResult
import com.drishtisaathi.engine.profile.ScreeningResults
import com.drishtisaathi.i18n.SProfile
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.vision.FarPointEyeResult
import com.drishtisaathi.vision.TestEye
import com.drishtisaathi.vision.VisionSession
import java.util.Locale

/** The steps of the screening stage. */
sealed interface ScreenStep {
    /** Full brightness and "turn off Night light". */
    data object Setup : ScreenStep
    data class Amsler(val eye: Eye) : ScreenStep
    data class Contrast(val eye: Eye) : ScreenStep
    /** Both eyes open. */
    data object Colour : ScreenStep
    /** The earlier tumbling-E and clock-dial tests, kept. */
    data object Classic : ScreenStep
}

object ScreeningPlan {
    /**
     * The power of [eye] for "wear your correction first": the phone's reading when it found a minus power, else the
     * customer's known power for that eye.
     */
    fun eyePower(eye: Eye, quick: QuickEstimate?, answers: com.drishtisaathi.engine.InterviewAnswers): Double? {
        val q = if (eye == Eye.RIGHT) quick?.right else quick?.left
        val known = (if (eye == Eye.RIGHT) answers.knownRx?.right else answers.knownRx?.left)?.sphericalEquivalent
        return q?.seD?.takeIf { q.myopic } ?: known
    }

    /** Everyone: grid, contrast, colour, then the E test and clock dial. Emergency short path: the grid only. */
    fun steps(emergency: Boolean): List<ScreenStep> = buildList {
        add(ScreenStep.Setup)
        add(ScreenStep.Amsler(Eye.RIGHT)); add(ScreenStep.Amsler(Eye.LEFT))
        if (!emergency) {
            add(ScreenStep.Contrast(Eye.RIGHT)); add(ScreenStep.Contrast(Eye.LEFT))
            add(ScreenStep.Colour)
            add(ScreenStep.Classic)
        }
    }

    fun skipKey(s: ScreenStep): String = when (s) {
        is ScreenStep.Amsler -> "AMSLER_${s.eye}"
        is ScreenStep.Contrast -> "CONTRAST_${s.eye}"
        ScreenStep.Colour -> "COLOUR"
        ScreenStep.Classic -> "E_TEST_AND_DIAL"
        ScreenStep.Setup -> "SETUP"
    }

    /**
     * The power check's far point per eye, so the clock dial is held just beyond it (the first pass without a lens
     * for a lens-assisted eye). Only used for placing the dial; it never becomes a measured power.
     */
    fun seededSession(quick: QuickEstimate?): VisionSession {
        fun seed(eye: TestEye, q: com.drishtisaathi.engine.QuickEye?): Pair<TestEye, FarPointEyeResult>? {
            val se = (q?.unaidedSeD ?: q?.seD)?.takeIf { q?.noMyopiaAtReach != true && it < 0 } ?: return null
            return eye to FarPointEyeResult(eye, emptyList(), listOf(1000.0 / -se), null, se, se, com.drishtisaathi.engine.Confidence.LOW,
                noMyopiaAtArmsLength = false, consistent = null)
        }
        return VisionSession(farPoint = listOfNotNull(seed(TestEye.RIGHT, quick?.right), seed(TestEye.LEFT, quick?.left)).toMap())
    }

    /** The E test and clock dial of [session] as stored results. */
    fun classicResults(session: VisionSession): Pair<List<ETestEyeResult>, List<DialEyeResult>> {
        fun eye(t: TestEye): Eye? = when (t) { TestEye.RIGHT -> Eye.RIGHT; TestEye.LEFT -> Eye.LEFT; TestEye.BOTH -> null }
        val e = session.nearAcuity.values.map { ETestEyeResult(eye(it.eye), it.logMar, it.belowThreshold) }
        val d = session.clockDial.values.mapNotNull { r -> eye(r.eye)?.let { DialEyeResult(it, r.astigmatismFlag, r.axisDeg, r.confidence) } }
        return e to d
    }
}

/** One screening result line: a label, the result, and a status dot. */
data class ScreenLine(val label: Txt, val value: Txt, val status: Status, val note: Txt? = null) {
    enum class Status { OK, WATCH, URGENT, NOT_DONE }
    val text: Txt get() = Txt("${label.en}: ${value.en}" + (note?.let { ". ${it.en}" } ?: ""), "${label.hi}: ${value.hi}" + (note?.let { "। ${it.hi}" } ?: ""))
}

/**
 * The screening results in words, for the result screen, the shop PDF, the QR and the optometrist page. Always shown
 * under [SProfile.screeningLabel] ("Screening, not a diagnosis").
 */
object ScreeningText {
    private fun pct(v: Double): String = if (v == Math.floor(v)) "%.0f".format(Locale.US, v) else "%.1f".format(Locale.US, v)
    private fun eye(e: Eye?): Txt = when (e) { Eye.RIGHT -> SProfile.eyeR; Eye.LEFT -> SProfile.eyeL; null -> Txt("", "") }
    private fun label(t: Txt, e: Eye?): Txt = if (e == null) t else Txt("${t.en} ${eye(e).en}", "${t.hi} ${eye(e).hi}")

    fun lines(s: ScreeningResults?, age: Int?, lowLightOrGlare: Boolean, rules: RulesConfig): List<ScreenLine> {
        if (s == null) return emptyList()
        val P = SProfile
        val notDone = { t: Txt, e: Eye? -> ScreenLine(label(t, e), P.resSkipped, ScreenLine.Status.NOT_DONE) }
        return buildList {
            for (e in Eye.entries) {
                val r = s.amsler.firstOrNull { it.eye == e }
                add(when {
                    r == null -> notDone(P.resGrid, e)
                    r.distortion -> ScreenLine(label(P.resGrid, e), P.resGridBad.format(r.cells.size), ScreenLine.Status.URGENT)
                    else -> ScreenLine(label(P.resGrid, e), P.resGridOk, ScreenLine.Status.OK)
                })
            }
            for (e in Eye.entries) {
                val r = s.contrast.firstOrNull { it.eye == e }
                if (r == null) { if ("CONTRAST_$e" in s.skipped) add(notDone(P.resContrast, e)); continue }
                // even the 100 % letters were not read: the test says nothing about contrast (no "Reduced", no cataract note)
                if (r.invalid) { add(ScreenLine(label(P.resContrast, e), P.resContrastInvalid, ScreenLine.Status.WATCH)); continue }
                val word = if (r.reduced) P.reduced else P.normal
                val step = pct(r.lowestPassedPercent ?: 0.0)
                val value = Txt(P.resContrastStep.en.format(step, word.en), P.resContrastStep.hi.format(step, word.hi))
                val note = P.eyeHealthNote.takeIf { Contrast.eyeHealthNote(r.reduced, age, lowLightOrGlare, rules.screening.contrast) }
                add(ScreenLine(label(P.resContrast, e), value, if (r.reduced) ScreenLine.Status.WATCH else ScreenLine.Status.OK, note))
            }
            val c = s.colour
            if (c != null) add(when (c.outcome) {
                ColourOutcome.NORMAL -> ScreenLine(P.resColour, P.colourNormal, ScreenLine.Status.OK)
                ColourOutcome.POSSIBLE_RED_GREEN -> ScreenLine(P.resColour, P.colourRedGreen, ScreenLine.Status.WATCH, P.colourNeeds)
                ColourOutcome.POSSIBLE_BLUE_YELLOW -> ScreenLine(P.resColour, P.colourBlueYellow, ScreenLine.Status.WATCH, P.colourNeeds)
                ColourOutcome.POSSIBLE_RED_GREEN_AND_BLUE_YELLOW -> ScreenLine(P.resColour, P.colourBoth, ScreenLine.Status.WATCH, P.colourNeeds)
                ColourOutcome.INVALID -> ScreenLine(P.resColour, P.colourInvalid, ScreenLine.Status.WATCH)
            }) else if ("COLOUR" in s.skipped) add(notDone(P.resColour, null))
            s.eTest.forEach { r ->
                val v = P.resETestLine.format("%.1f".format(Locale.US, r.logMar), if (r.belowLimit) P.resBelowLimit.en else "")
                add(ScreenLine(label(P.resETest, r.eye), Txt(v.en, P.resETestLine.hi.format("%.1f".format(Locale.US, r.logMar), if (r.belowLimit) P.resBelowLimit.hi else "")),
                    if (r.belowLimit) ScreenLine.Status.WATCH else ScreenLine.Status.OK))
            }
            s.clockDial.forEach { r ->
                add(if (r.astigmatismFlag) ScreenLine(label(P.resDial, r.eye), P.resDialFlag.format(r.axisDeg ?: 0), ScreenLine.Status.WATCH)
                else ScreenLine(label(P.resDial, r.eye), P.resDialOk, ScreenLine.Status.OK))
            }
        }
    }

    fun lines(state: UiState, rules: RulesConfig): List<ScreenLine> =
        lines(state.screening, state.answers.age, SurveyBridge.lowLightOrGlare(state.survey, rules.lensEngine.symptomFrom), rules)
}
