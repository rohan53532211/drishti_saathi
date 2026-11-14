package com.drishtisaathi.profile

import com.drishtisaathi.UiState
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.profile.L10n
import com.drishtisaathi.engine.profile.LensEngine
import com.drishtisaathi.engine.profile.LensPlan
import com.drishtisaathi.engine.profile.LensWords
import com.drishtisaathi.engine.profile.ProfileInputs
import com.drishtisaathi.engine.profile.SafetyGate
import com.drishtisaathi.engine.profile.Suggestion
import com.drishtisaathi.engine.profile.SuggestionKind
import com.drishtisaathi.engine.profile.VisionProfile
import com.drishtisaathi.i18n.SProfile
import com.drishtisaathi.i18n.Txt

/** One session's profile rings, lens suggestion and the safety gate behind it. */
data class ProfileView(val gate: SafetyGate, val inputs: ProfileInputs, val profile: VisionProfile, val plan: LensPlan) {
    companion object {
        private fun l(t: Txt) = L10n(t.en, t.hi)
        val words: LensWords get() = LensWords(l(SProfile.wordFar), l(SProfile.wordNear), l(SProfile.wordTired), l(SProfile.wordHeadache), l(SProfile.wordNeck), l(SProfile.wordStrain))

        fun build(s: UiState, rules: RulesConfig, engine: LensEngine): ProfileView {
            val gate = s.gate(rules)
            // the near add: the session's own add band when the rules engine gave one, else the phone's reading
            val add = s.result?.assessment?.advice?.add?.value?.midD ?: s.quick?.addD
            val inputs = ProfileInputs.from(s.answers, s.survey, rules.lensEngine, s.quick, s.screening, gate, add)
            return ProfileView(gate, inputs, VisionProfile.from(inputs, rules.visionProfile), engine.plan(inputs, gate, words))
        }
    }
}

/** The lens suggestion and the rings in words, for the screen, the PDF, the QR and the optometrist report. */
object LensText {
    /** "Why for you": the catalogue's own lines filled with this customer's numbers, then the engine's reasons. */
    fun why(s: Suggestion): List<Txt> = s.why.map { Txt(it.en, it.hi) } + s.reasons.map { SProfile.reason(it.code, it.args) }

    fun kind(k: SuggestionKind): Txt = when (k) {
        SuggestionKind.LENS_TYPE -> SProfile.lensMain
        SuggestionKind.DESIGN -> SProfile.lensDesign
        SuggestionKind.MATERIAL -> SProfile.lensMaterial
        SuggestionKind.COATING -> SProfile.lensCoating
        SuggestionKind.SECOND_PAIR -> SProfile.lensSecond
    }

    fun ringTitle(id: String): Txt = when (id) {
        "WORK" -> SProfile.ringWork; "DIGITAL" -> SProfile.ringDigital; "MOBILITY" -> SProfile.ringMobility; else -> SProfile.ringLeisure
    }

    /** "Work: Near-focused (88)" per ring; a ring nobody answered says so. */
    fun ringLines(p: VisionProfile): List<Txt> = p.rings.map { (id, r) ->
        val t = ringTitle(id)
        val label = SProfile.ringLabel(r.label)
        val glare = if (id == "MOBILITY" && p.nightGlareSensitive) SProfile.nightGlare else null
        if (!r.known) Txt("${t.en}: ${SProfile.ringNotAnswered.en}", "${t.hi}: ${SProfile.ringNotAnswered.hi}")
        else Txt("${t.en}: ${label.en} (${r.score})" + (glare?.let { " · ${it.en}" } ?: ""), "${t.hi}: ${label.hi} (${r.score})" + (glare?.let { " · ${it.hi}" } ?: ""))
    }
}
