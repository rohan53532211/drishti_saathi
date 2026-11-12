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
 * Phone powers are always shown as a range (centre ± 0.25 D), never as one exact number.
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
                e.phoneEstimateSeD != null -> com.drishtisaathi.vision.PowerRange.around(e.phoneEstimateSeD!!).let { r ->
                    SAnamnesis.compareEye.format(eyeName(e.eye), fmtD(r.fromD), fmtD(r.toD), fmtD(se(known)))
                }
                else -> null
            }
        }
    }

    /** The customer's known power (power question YES), shown next to the phone check's range. */
    fun knownLine(state: UiState): Txt? {
        val k = state.answers.knownRx?.takeIf { state.answers.powerKnown == com.drishtisaathi.engine.PowerKnown.YES } ?: return null
        fun rx(r: EyeRx?) = r?.let { if (it.cyl == 0.0) "${fmtD(it.sph)} DS" else "${fmtD(it.sph)} / ${fmtD(it.cyl)} × ${it.axis ?: "?"}" } ?: "—"
        val v = "R ${rx(k.right)} · L ${rx(k.left)}"
        return Txt("${com.drishtisaathi.i18n.SShop.knownPowerLine.en}: $v", "${com.drishtisaathi.i18n.SShop.knownPowerLine.hi}: $v")
    }

    /**
     * The eye check's result (compulsory for everyone, also next to a known power or a red flag): the known power if
     * any, each eye's phone range, the near result.
     */
    fun quickLines(state: UiState): List<Txt> {
        val q = state.quick ?: return listOfNotNull(knownLine(state))
        return buildList {
            knownLine(state)?.let(::add)
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
        val a = state.answers
        val p = FunctionalProfile.from(a, rules)
        // A zone whose questions were skipped says "not answered" instead of an invented 0.0 h.
        val nearAnswered = a.readingFrequency != null || a.nearTask != null || a.screenHours != null
        val interAnswered = a.screenHours != null
        val distAnswered = a.occupation != null || a.outdoorWork != null || a.nightDriving != null || a.distanceSituation != null
        val na = com.drishtisaathi.i18n.SShop.notAnswered
        fun zone(t: Txt, answered: Boolean, h: Double): Txt =
            if (answered) t.format(h) else Txt(t.en.substringBefore(":") + ": " + na.en, t.hi.substringBefore(":") + ": " + na.hi)
        return buildList {
            add(zone(SAnamnesis.profileNear, nearAnswered, p.nearHours))
            add(zone(SAnamnesis.profileIntermediate, interAnswered, p.intermediateHours))
            add(zone(SAnamnesis.profileDistance, distAnswered, p.distanceHours))
            if (nearAnswered || interAnswered || distAnswered) {
                add(SAnamnesis.profileDominant.withArg(SAnamnesis.zone(p.dominant)))
                add(SAnamnesis.profileLens.withArg(SAnamnesis.profileLens(p.impliedLens)))
            }
        }
    }
}
