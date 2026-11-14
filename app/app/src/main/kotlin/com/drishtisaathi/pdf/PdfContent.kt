package com.drishtisaathi.pdf

import com.drishtisaathi.UiState
import com.drishtisaathi.engine.Eye
import com.drishtisaathi.engine.ReferralLevel
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.interview.ChoiceContext
import com.drishtisaathi.ui.Fmt
import com.drishtisaathi.ui.PowerPathText
import com.drishtisaathi.i18n.SAnamnesis
import com.drishtisaathi.ui.answerText
import com.drishtisaathi.profile.gate
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
    /** A saved frame: thumbnail (looked up by [frameId] when the exporter has one), name and one detail line. */
    data class FrameRow(val frameId: String, val name: String, val line: String) : Block()
    /** The four profile rings: title, label, score 0–100 (negative = not answered) and colour (RGB). */
    data class Rings(val rings: List<RingSpec>) : Block()
    data class RingSpec(val title: String, val label: String, val score: Int, val rgb: Int)
}

enum class Tone { NORMAL, MUTED, URGENT, ROUTINE, ACCENT }

object PdfContent {
    /** The section colours of the rings (the design tokens: work, digital, mobility, leisure). */
    val RING_RGB = mapOf("WORK" to 0x2F80ED, "DIGITAL" to 0xA3D33A, "MOBILITY" to 0x6B7785, "LEISURE" to 0xF2A93B)

    private fun date(ms: Long?) = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(Date(ms ?: System.currentTimeMillis()))

    private fun referralTone(level: ReferralLevel) = when (level) {
        ReferralLevel.URGENT -> Tone.URGENT
        ReferralLevel.ROUTINE -> Tone.ROUTINE
        ReferralLevel.NONE -> Tone.ACCENT
    }

    /** Urgent reasons (red) and routine reasons (amber) as separate labelled groups. */
    private fun reasonGroups(r: com.drishtisaathi.engine.Referral, rules: RulesConfig, l: Lang, out: MutableList<Block>) {
        val g = com.drishtisaathi.ui.ReferralGroups.of(r)
        if (g.urgent.isNotEmpty()) {
            out += Block.Para(S.urgentGroup[l], bold = true, tone = Tone.URGENT)
            g.urgent.forEach { out += Block.Bullet(S.referralReason(it, rules)[l], Tone.URGENT) }
        }
        if (g.routine.isNotEmpty()) {
            out += Block.Para(S.routineGroup[l], bold = true, tone = Tone.ROUTINE)
            g.routine.forEach { out += Block.Bullet(S.referralReason(it, rules)[l], Tone.ROUTINE) }
        }
    }

    /** Full staff summary in the shop's chosen language. */
    /** "Saved frames" section: name, size (estimated *), price. Frame facts only, no power numbers. */
    private fun savedSection(saved: List<com.drishtisaathi.data.SavedFrameEntity>, l: Lang, out: MutableList<Block>) {
        if (saved.isEmpty()) return
        out += Block.Heading(com.drishtisaathi.i18n.S3D.savedFrames[l])
        saved.forEach { f ->
            out += Block.FrameRow(f.frameId, f.name, "${f.sizeLabel}${if (f.sizeEstimated) "*" else ""} · ₹${"%,d".format(Locale.US, f.priceInr)}")
        }
        if (saved.any { it.sizeEstimated }) out += Block.Para("* " + com.drishtisaathi.i18n.S3D.demoCatalog[l], tone = Tone.MUTED)
    }

    fun shopCopy(state: UiState, rules: RulesConfig, ctx: ChoiceContext, l: Lang,
                 saved: List<com.drishtisaathi.data.SavedFrameEntity> = emptyList(),
                 /** The profile rings and the lens suggestion (Phase E); null = left out. */
                 view: com.drishtisaathi.profile.ProfileView? = null): List<Block> {
        val r = state.result ?: return emptyList()
        val a = r.assessment
        val out = mutableListOf<Block>()
        out += Block.Title("${if (state.demo) "DEMO · " else ""}${S.appName[l]} · ${S.shopSummary[l]}")
        if (state.demo) out += Block.Para(com.drishtisaathi.i18n.SFlow.demoPdfLine[l], bold = true, tone = Tone.URGENT)
        out += Block.Para("${state.sessionId?.let { S.sessionNo.format(it.toInt())[l] + " · " } ?: ""}${S.generatedOn[l]}: ${date(state.createdAt)}", tone = Tone.MUTED)
        out += Block.Para(S.disclaimerBoth, bold = true)
        val gate = state.gate(rules)
        val P = com.drishtisaathi.i18n.SProfile
        val urgent = a.referral.level == ReferralLevel.URGENT || gate.urgent
        out += Block.Para(P.reportTagLine.format(P.tag(gate.tag)[l])[l], bold = true,
            tone = when (gate.tag) { com.drishtisaathi.engine.profile.ReportTag.URGENT -> Tone.URGENT; com.drishtisaathi.engine.profile.ReportTag.CHILD_MYOPIA -> Tone.ROUTINE; else -> Tone.MUTED })
        if (gate.urgent) {
            out += Block.Para(P.seeDoctorToday[l], bold = true, tone = Tone.URGENT)
            gate.redFlags.forEach { out += Block.Bullet(P.redFlag(it)[l], Tone.URGENT) }
            out += Block.Para(P.noLensToday[l], bold = true)
        } else if (urgent) {
            out += Block.Para(com.drishtisaathi.i18n.SShop.urgentTop[l], bold = true, tone = Tone.URGENT)
            reasonGroups(a.referral, rules, l, out)
        }
        if (gate.childMyopia) {
            out += Block.Para(P.childMyopiaTitle[l], bold = true, tone = Tone.ROUTINE)
            out += Block.Para(P.childMyopiaBody[l])
        }
        out += Block.Rule

        // The eye check (compulsory): the known power if any, the phone ranges, the near result.
        val quick = PowerPathText.quickLines(state)
        if (quick.isNotEmpty()) {
            out += Block.Heading(com.drishtisaathi.i18n.SShop.eyeCheckTitle[l])
            if (gate.powerForOptometristOnly) out += Block.Para(P.forOptometristOnly[l], bold = true, tone = Tone.URGENT)
            quick.forEach { out += Block.Bullet(it[l]) }
        }

        // Screening tests, always labelled "Screening, not a diagnosis".
        val screening = com.drishtisaathi.profile.ScreeningText.lines(state, rules)
        if (screening.isNotEmpty()) {
            out += Block.Heading(P.screeningTitle[l])
            out += Block.Para(P.screeningLabel[l], tone = Tone.MUTED)
            screening.forEach {
                out += Block.Bullet(it.text[l], when (it.status) {
                    com.drishtisaathi.profile.ScreenLine.Status.URGENT -> Tone.URGENT
                    com.drishtisaathi.profile.ScreenLine.Status.WATCH -> Tone.ROUTINE
                    com.drishtisaathi.profile.ScreenLine.Status.NOT_DONE -> Tone.MUTED
                    else -> Tone.NORMAL
                })
            }
        }

        // The vision profile rings and the suggested lens family with its reasons.
        if (view != null) {
            out += Block.Heading(P.profileTitle[l])
            out += Block.Rings(view.profile.rings.map { (id, r) ->
                Block.RingSpec(com.drishtisaathi.profile.LensText.ringTitle(id)[l], (if (r.known) P.ringLabel(r.label) else P.ringNotAnswered)[l],
                    if (r.known) r.score else -1, RING_RGB.getValue(id))
            })
            com.drishtisaathi.profile.LensText.ringLines(view.profile).forEach { out += Block.Bullet(it[l]) }
            if (view.plan.allowed) {
                out += Block.Heading(P.lensLabel[l])
                if (view.plan.optometristConfirms) out += Block.Para(P.optometristConfirms[l], bold = true, tone = Tone.ROUTINE)
                view.plan.all.forEach { s ->
                    out += Block.Para("${com.drishtisaathi.profile.LensText.kind(s.kind)[l]}: ${s.name}", bold = true, tone = Tone.ACCENT)
                    out += Block.Para(s.what, tone = Tone.MUTED)
                    com.drishtisaathi.profile.LensText.why(s).forEach { out += Block.Bullet(it[l]) }
                }
            }
        }

        if (!urgent) {
            out += Block.Heading(S.referral[l])
            out += Block.Para(S.referralLevel(a.referral.level)[l], bold = true, tone = referralTone(a.referral.level))
            reasonGroups(a.referral, rules, l, out)
        }
        if (state.glassesOn) out += Block.Para(com.drishtisaathi.i18n.SFlow.glassesOnNote[l], bold = true, tone = Tone.ROUTINE)

        val adv = a.advice.takeIf { gate.lensAllowed }
        if (!gate.lensAllowed) {
            // a red flag or child myopia: referral only, no lens or frame suggestion
        } else if (adv == null) {
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
                e.correctedWithShopRecords?.let { out += Block.Bullet(S.correctedWithRecords.format(it)[l], Tone.MUTED) }
            }
            adv.add?.let { out += Block.Para("${S.readingAdd[l]}: ${Fmt.band(it.value, l)} ${Fmt.meta(it, l)}") }
            if (adv.confirmAddWithReadyReader) out += Block.Bullet(S.confirmReadyReader[l], Tone.ACCENT)
            if (adv.eyes.isNotEmpty()) out += Block.Para(S.startingLensHint[l], tone = Tone.MUTED)
            PowerPathText.sourceLine(adv)?.let { out += Block.Para(it[l], bold = true) }
            val compare = PowerPathText.compareLines(state, adv)
            if (compare.isNotEmpty()) {
                out += Block.Para(SAnamnesis.compareHeading[l], bold = true, tone = Tone.ROUTINE)
                compare.forEach { out += Block.Bullet(it[l], Tone.ROUTINE) }
            }

            out += Block.Heading(SAnamnesis.profileTitle[l])
            PowerPathText.profileLines(state, rules).forEach { out += Block.Bullet(it[l]) }

            out += Block.Heading(S.lensPackage[l])
            out += Block.Bullet("${S.lensType[l]}: ${S.lensType(adv.lensType.value)[l]} ${Fmt.meta(adv.lensType, l)}")
            out += Block.Bullet("${S.material[l]}: ${S.material(adv.materialId.value)[l]} ${Fmt.meta(adv.materialId, l)}")
            out += Block.Bullet("${S.coatings[l]}: " +
                if (adv.coatings.isEmpty()) S.noCoatings[l] else adv.coatings.joinToString(", ") { S.coating(it)[l] })

            out += Block.Heading(S.suggestedFrames[l])
            out += Block.Para(S.demoCatalogue[l], bold = true, tone = Tone.ROUTINE)
            r.frames.forEachIndexed { i, rf ->
                out += Block.Para("${i + 1}. ${rf.frame.name} (${rf.frame.id}) · ${Fmt.frameLine(rf, l)}", bold = true)
                Fmt.label(rf, l)?.let { out += Block.Para(it, bold = true, tone = Tone.ACCENT) }
                Fmt.reasons(rf, l).forEach { (pos, txt) -> out += Block.Bullet((if (pos) "+ " else "− ") + txt, if (pos) Tone.NORMAL else Tone.ROUTINE) }
            }
        }

        savedSection(saved, l, out)

        if (a.notes.isNotEmpty()) {
            out += Block.Heading(S.notesForStaff[l])
            a.notes.forEach { out += Block.Bullet(S.note(it, rules.oldNumbers.freshMaxYears)[l]) }
        }
        out += Block.Heading(S.staffOnly[l])
        out += Block.Bullet("${S.pd[l]}: ${Fmt.pd(a, l)} ${Fmt.meta(a.pdMm, l)}")
        out += Block.Bullet("${S.faceWidth[l]}: ${Fmt.mm(a.faceWidthMm.value)} ${Fmt.meta(a.faceWidthMm, l)}")

        out += Block.Heading(S.profile[l])
        state.visibleQuestions.forEach { q -> out += Block.Bullet("${q.text[l]}  →  ${answerText(q, state, ctx)[l]}") }

        // AI voice interview transcript: shop copy only (never the customer copy, never sent to the optometrist)
        state.aiInterview?.let { rec ->
            val V = com.drishtisaathi.i18n.SAiVoice
            out += Block.Heading(V.transcriptTitle[l])
            rec.turns.forEach { t ->
                out += Block.Para(V.transcriptQ.format(t.n, t.question)[l], bold = true)
                out += Block.Bullet(V.transcriptA.format(t.answer ?: V.transcriptNoAnswer[l])[l])
            }
            if (rec.fellBack) out += Block.Para(V.transcriptFellBack[l], tone = Tone.ROUTINE)
            out += Block.Para(V.transcriptNote[l], tone = Tone.MUTED)
        }

        state.verification.verifiedAt?.let {
            val v = state.verification
            out += Block.Heading(S.verification[l])
            if (v.finalTrialRight.isNotBlank()) out += Block.Bullet("R: ${v.finalTrialRight}")
            if (v.finalTrialLeft.isNotBlank()) out += Block.Bullet("L: ${v.finalTrialLeft}")
            if (v.finalAdd.isNotBlank()) out += Block.Bullet("ADD: ${v.finalAdd}")
            v.pdMeterMm?.let { pd -> out += Block.Bullet("${S.pdMeter[l]}: $pd") }
        }

        out += Block.Rule
        if (!urgent) out += Block.Para(com.drishtisaathi.i18n.SShop.phoneCheckNote[l], bold = true)
        out += Block.Para(S.disclaimerBoth, bold = true)
        if (state.demo) out += Block.Para(com.drishtisaathi.i18n.SFlow.demoPdfLine[l], bold = true, tone = Tone.URGENT)
        return out
    }

    /**
     * Customer copy, always Hindi. Deliberately carries no power numbers (only the lens package,
     * frames and referral), so it cannot be mistaken for a prescription.
     */
    fun customerCopy(state: UiState, rules: RulesConfig, saved: List<com.drishtisaathi.data.SavedFrameEntity> = emptyList()): List<Block> {
        val l = Lang.HI
        val r = state.result ?: return emptyList()
        val a = r.assessment
        val out = mutableListOf<Block>()
        out += Block.Title("${if (state.demo) "DEMO · " else ""}${S.appName[l]} · ${S.customerCopy[l]}")
        if (state.demo) out += Block.Para(com.drishtisaathi.i18n.SFlow.demoPdfLine[l], bold = true, tone = Tone.URGENT)
        out += Block.Para("${S.generatedOn[l]}: ${date(state.createdAt)}", tone = Tone.MUTED)
        out += Block.Para(S.disclaimerBoth, bold = true)
        val gate = state.gate(rules)
        val P = com.drishtisaathi.i18n.SProfile
        val urgent = a.referral.level == ReferralLevel.URGENT || gate.urgent
        if (gate.urgent) {
            out += Block.Para(P.seeDoctorToday[l], bold = true, tone = Tone.URGENT)
            gate.redFlags.forEach { out += Block.Bullet(P.redFlag(it)[l], Tone.URGENT) }
            out += Block.Para(P.noLensToday[l], bold = true)
        } else if (urgent) {
            out += Block.Para(com.drishtisaathi.i18n.SShop.urgentTop[l], bold = true, tone = Tone.URGENT)
            reasonGroups(a.referral, rules, l, out)
        }
        if (gate.childMyopia) {
            out += Block.Para(P.childMyopiaTitle[l], bold = true, tone = Tone.ROUTINE)
            out += Block.Para(P.childMyopiaBody[l])
        }
        out += Block.Para(S.customerIntro[l])
        out += Block.Rule

        val tone = referralTone(a.referral.level)
        when (a.referral.level) {
            ReferralLevel.URGENT -> Unit   // already at the top, once
            ReferralLevel.ROUTINE -> { out += Block.Heading(S.routineTitle[l]); out += Block.Para(S.routineBody[l], tone = tone) }
            ReferralLevel.NONE -> { out += Block.Heading(S.referral[l]); out += Block.Para(S.referralLevel(a.referral.level)[l], tone = tone) }
        }
        if (!urgent) reasonGroups(a.referral, rules, l, out)

        a.advice?.takeIf { gate.lensAllowed }?.let { adv ->
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
        savedSection(saved, l, out)
        out += Block.Rule
        if (!urgent) out += Block.Para(com.drishtisaathi.i18n.SShop.phoneCheckNote[l], bold = true)
        out += Block.Para(S.privacyNote[l], tone = Tone.MUTED)
        out += Block.Para(S.disclaimerBoth, bold = true)
        if (state.demo) out += Block.Para(com.drishtisaathi.i18n.SFlow.demoPdfLine[l], bold = true, tone = Tone.URGENT)
        return out
    }
}
