package com.drishtisaathi.pdf

import com.drishtisaathi.UiState
import com.drishtisaathi.engine.Eye
import com.drishtisaathi.engine.ReferralLevel
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.interview.ChoiceContext
import com.drishtisaathi.ui.Fmt
import com.drishtisaathi.ui.answerText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Renderer-independent PDF content, so wording and the disclaimer can be unit-tested on the JVM. */
sealed class Block {
    data class Title(val text: String) : Block()
    data class Heading(val text: String) : Block()
    data class Para(val text: String, val bold: Boolean = false, val tone: Tone = Tone.NORMAL) : Block()
    data class Bullet(val text: String, val tone: Tone = Tone.NORMAL) : Block()
    data object Rule : Block()
}

enum class Tone { NORMAL, MUTED, URGENT, ROUTINE, ACCENT }

object PdfContent {

    private fun date(ms: Long?) = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(Date(ms ?: System.currentTimeMillis()))

    private fun referralTone(level: ReferralLevel) = when (level) {
        ReferralLevel.URGENT -> Tone.URGENT
        ReferralLevel.ROUTINE -> Tone.ROUTINE
        ReferralLevel.NONE -> Tone.ACCENT
    }

    /** Full staff summary in the shop's chosen language. */
    fun shopCopy(state: UiState, rules: RulesConfig, ctx: ChoiceContext, l: Lang): List<Block> {
        val r = state.result ?: return emptyList()
        val a = r.assessment
        val out = mutableListOf<Block>()
        out += Block.Title("${if (state.demo) "DEMO · " else ""}${S.appName[l]} · ${S.shopSummary[l]}")
        if (state.demo) out += Block.Para(com.drishtisaathi.i18n.SFlow.demoPdfLine[l], bold = true, tone = Tone.URGENT)
        out += Block.Para("${state.sessionId?.let { S.sessionNo.format(it.toInt())[l] + " · " } ?: ""}${S.generatedOn[l]}: ${date(state.createdAt)}", tone = Tone.MUTED)
        out += Block.Para(S.disclaimerBoth, bold = true)
        out += Block.Rule

        out += Block.Heading(S.referral[l])
        out += Block.Para(S.referralLevel(a.referral.level)[l], bold = true, tone = referralTone(a.referral.level))
        a.referral.reasons.forEach { out += Block.Bullet(S.referralReason(it, rules)[l], referralTone(a.referral.level)) }

        val adv = a.advice
        if (adv == null) {
            out += Block.Para(S.adviceWithheld[l], bold = true)
        } else {
            out += Block.Heading(S.startingTrialLens[l])
            if (adv.eyes.isEmpty()) {
                if (com.drishtisaathi.engine.StartingHint.DISTANCE_BLUR_START_MINUS !in adv.startingHints) out += Block.Para(S.noStartingLens[l])
                adv.startingHints.forEach { out += Block.Bullet(S.startingHint(it)[l], Tone.ACCENT) }
            }
            adv.eyes.forEach { e ->
                val eye = if (e.eye == Eye.RIGHT) "R" else "L"
                out += Block.Para("$eye  ${Fmt.trialLens(e.startingTrialLens.value)}", bold = true, tone = Tone.ACCENT)
                out += if (e.bandUncertain) Block.Bullet(S.bandUncertain[l], Tone.ROUTINE)
                else Block.Bullet("${S.estimatedBand[l]}: ${Fmt.band(e.band.value, l)} ${Fmt.meta(e.band, l)}")
            }
            adv.add?.let { out += Block.Para("${S.readingAdd[l]}: ${Fmt.band(it.value, l)} ${Fmt.meta(it, l)}") }
            if (adv.confirmAddWithReadyReader) out += Block.Bullet(S.confirmReadyReader[l], Tone.ACCENT)
            if (adv.eyes.isNotEmpty()) out += Block.Para(S.startingLensHint[l], tone = Tone.MUTED)

            out += Block.Heading(S.lensPackage[l])
            out += Block.Bullet("${S.lensType[l]}: ${S.lensType(adv.lensType.value)[l]} ${Fmt.meta(adv.lensType, l)}")
            out += Block.Bullet("${S.material[l]}: ${S.material(adv.materialId.value)[l]} ${Fmt.meta(adv.materialId, l)}")
            out += Block.Bullet("${S.coatings[l]}: " +
                if (adv.coatings.isEmpty()) S.noCoatings[l] else adv.coatings.joinToString(", ") { S.coating(it)[l] })

            out += Block.Heading(S.suggestedFrames[l])
            r.frames.forEachIndexed { i, rf ->
                out += Block.Para("${i + 1}. ${rf.frame.name} (${rf.frame.id}) · ${Fmt.frameLine(rf, l)}", bold = true)
                Fmt.label(rf, l)?.let { out += Block.Para(it, bold = true, tone = Tone.ACCENT) }
                Fmt.reasons(rf, l).forEach { (pos, txt) -> out += Block.Bullet((if (pos) "+ " else "− ") + txt, if (pos) Tone.NORMAL else Tone.ROUTINE) }
            }
        }

        if (a.notes.isNotEmpty()) {
            out += Block.Heading(S.notesForStaff[l])
            a.notes.forEach { out += Block.Bullet(S.note(it, rules.oldNumbers.freshMaxYears)[l]) }
        }
        out += Block.Heading(S.staffOnly[l])
        out += Block.Bullet("${S.pd[l]}: ${Fmt.pd(a, l)} ${Fmt.meta(a.pdMm, l)}")
        out += Block.Bullet("${S.faceWidth[l]}: ${Fmt.mm(a.faceWidthMm.value)} ${Fmt.meta(a.faceWidthMm, l)}")

        out += Block.Heading(S.profile[l])
        state.visibleQuestions.forEach { q -> out += Block.Bullet("${q.text[l]}  →  ${answerText(q, state, ctx)[l]}") }

        state.verification.verifiedAt?.let {
            val v = state.verification
            out += Block.Heading(S.verification[l])
            if (v.finalTrialRight.isNotBlank()) out += Block.Bullet("R: ${v.finalTrialRight}")
            if (v.finalTrialLeft.isNotBlank()) out += Block.Bullet("L: ${v.finalTrialLeft}")
            if (v.finalAdd.isNotBlank()) out += Block.Bullet("ADD: ${v.finalAdd}")
            v.pdMeterMm?.let { pd -> out += Block.Bullet("${S.pdMeter[l]}: $pd") }
        }

        out += Block.Rule
        out += Block.Para(S.disclaimerBoth, bold = true)
        if (state.demo) out += Block.Para(com.drishtisaathi.i18n.SFlow.demoPdfLine[l], bold = true, tone = Tone.URGENT)
        return out
    }

    /**
     * Customer copy, always Hindi. Deliberately carries no power numbers (only the lens package,
     * frames and referral), so it cannot be mistaken for a prescription.
     */
    fun customerCopy(state: UiState, rules: RulesConfig): List<Block> {
        val l = Lang.HI
        val r = state.result ?: return emptyList()
        val a = r.assessment
        val out = mutableListOf<Block>()
        out += Block.Title("${if (state.demo) "DEMO · " else ""}${S.appName[l]} · ${S.customerCopy[l]}")
        if (state.demo) out += Block.Para(com.drishtisaathi.i18n.SFlow.demoPdfLine[l], bold = true, tone = Tone.URGENT)
        out += Block.Para("${S.generatedOn[l]}: ${date(state.createdAt)}", tone = Tone.MUTED)
        out += Block.Para(S.disclaimerBoth, bold = true)
        out += Block.Para(S.customerIntro[l])
        out += Block.Rule

        val tone = referralTone(a.referral.level)
        when (a.referral.level) {
            ReferralLevel.URGENT -> { out += Block.Heading(S.urgentTitle[l]); out += Block.Para(S.urgentBody[l], bold = true, tone = tone) }
            ReferralLevel.ROUTINE -> { out += Block.Heading(S.routineTitle[l]); out += Block.Para(S.routineBody[l], tone = tone) }
            ReferralLevel.NONE -> { out += Block.Heading(S.referral[l]); out += Block.Para(S.referralLevel(a.referral.level)[l], tone = tone) }
        }
        a.referral.reasons.forEach { out += Block.Bullet(S.referralReason(it, rules)[l], tone) }

        a.advice?.let { adv ->
            out += Block.Heading(S.lensPackage[l])
            out += Block.Bullet("${S.lensType[l]}: ${S.lensType(adv.lensType.value)[l]}")
            out += Block.Bullet("${S.material[l]}: ${S.material(adv.materialId.value)[l]}")
            adv.coatings.forEach { out += Block.Bullet(S.coating(it)[l]) }
            // rule 7: reading lenses are confirmed with a ready-reader at the counter (no power numbers here)
            if (adv.confirmAddWithReadyReader) out += Block.Para(S.customerReadyReader[l])
            if (r.frames.isNotEmpty()) {
                out += Block.Heading(S.suggestedFrames[l])
                r.frames.take(3).forEachIndexed { i, rf ->
                    out += Block.Para("${i + 1}. ${rf.frame.name} · ₹${"%,d".format(rf.frame.priceInr)}", bold = true)
                    Fmt.label(rf, l)?.let { out += Block.Para(it, bold = true, tone = Tone.ACCENT) }
                    Fmt.reasons(rf, l).forEach { (pos, txt) -> out += Block.Bullet((if (pos) "+ " else "− ") + txt, if (pos) Tone.NORMAL else Tone.ROUTINE) }
                }
            }
        }
        out += Block.Rule
        out += Block.Para(S.privacyNote[l], tone = Tone.MUTED)
        out += Block.Para(S.disclaimerBoth, bold = true)
        if (state.demo) out += Block.Para(com.drishtisaathi.i18n.SFlow.demoPdfLine[l], bold = true, tone = Tone.URGENT)
        return out
    }
}
