package com.drishtisaathi.ui

import com.drishtisaathi.UiState
import com.drishtisaathi.engine.Eye
import com.drishtisaathi.engine.EyeRx
import com.drishtisaathi.engine.LensAdvice
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.anamnesis.FunctionalProfile
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.SAnamnesis
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.i18n.fmtD
import com.drishtisaathi.vision.quickEyeText

/**
 * Power path lines shared by the summary screen and the shop PDF (never the customer copy: it has no power numbers).
 */
object PowerPathText {

    private fun Txt.withArg(arg: Txt): Txt = Txt(en.format(arg.en), hi.format(arg.hi))
    private fun eyeName(e: Eye) = if (e == Eye.RIGHT) "R" else "L"
    private fun se(rx: EyeRx) = rx.sph + rx.cyl / 2.0

    /** "Power source: customer's known power" / "phone estimate, approximate" / measured / old numbers. */
    fun sourceLine(adv: LensAdvice): Txt? = adv.powerSource?.let { SAnamnesis.powerSourceLine.withArg(S.source(it)) }

    /** Staff only: the optional quick check next to the customer's known power (power path YES). */
    fun compareLines(state: UiState, adv: LensAdvice): List<Txt> {
        if (state.quick?.primary != false) return emptyList()
        return adv.eyes.mapNotNull { e ->
            val known = (if (e.eye == Eye.RIGHT) state.answers.knownRx?.right else state.answers.knownRx?.left) ?: return@mapNotNull null
            when {
                e.phoneNoMyopiaAtReach -> SAnamnesis.compareEyeNoMyopia.format(eyeName(e.eye), fmtD(se(known)))
                e.phoneEstimateSeD != null -> SAnamnesis.compareEye.format(eyeName(e.eye), fmtD(e.phoneEstimateSeD!!), fmtD(se(known)))
                else -> null
            }
        }
    }

    /** Staff detail of the primary quick estimate (each eye's frozen reading, the near point). */
    fun quickLines(state: UiState): List<Txt> {
        val q = state.quick?.takeIf { it.primary } ?: return emptyList()
        return buildList {
            q.eyes.forEach { (eye, e) -> val t = quickEyeText(e); add(Txt(eyeName(eye) + ": " + t.en, eyeName(eye) + ": " + t.hi)) }
            // The reading check: the add (38+), or the near point against the age-expected one (no plus number).
            q.nearPointCm?.let { cm ->
                add(com.drishtisaathi.vision.nearResultText(cm, q.addD, q.expectedNearPointCm, q.possibleLongSight))
            }
            if (q.possibleLongSight && q.expectedNearPointCm == null) add(SAnamnesis.quickLongSight)
        }
    }

    /** The functional vision profile card: hours per zone, the main zone and the lens it points to. */
    fun profileLines(state: UiState, rules: RulesConfig): List<Txt> {
        val p = FunctionalProfile.from(state.answers, rules)
        return listOf(
            SAnamnesis.profileNear.format(p.nearHours),
            SAnamnesis.profileIntermediate.format(p.intermediateHours),
            SAnamnesis.profileDistance.format(p.distanceHours),
            SAnamnesis.profileDominant.withArg(SAnamnesis.zone(p.dominant)),
            SAnamnesis.profileLens.withArg(SAnamnesis.profileLens(p.impliedLens)),
        )
    }
}
