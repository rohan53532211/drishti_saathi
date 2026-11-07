package com.drishtisaathi.ui

import com.drishtisaathi.engine.Assessment
import com.drishtisaathi.engine.Estimate
import com.drishtisaathi.engine.EyeAdvice
import com.drishtisaathi.engine.FrameReason
import com.drishtisaathi.engine.ReasonCode
import com.drishtisaathi.engine.PowerBand
import com.drishtisaathi.engine.RankedFrame
import com.drishtisaathi.engine.TrialLens
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.fmtD
import java.util.Locale

/** Shared formatting for the summary screen and the PDFs, so both say exactly the same thing. */
object Fmt {
    fun band(b: PowerBand, l: Lang): String =
        if (b.lowD == b.highD) "${fmtD(b.lowD)} D" else S.bandRange.format(fmtD(b.lowD), fmtD(b.highD))[l]

    fun trialLens(t: TrialLens): String =
        if (t.cyl == 0.0) "${fmtD(t.sph)} DS" else "${fmtD(t.sph)} / ${fmtD(t.cyl)} × ${t.axis ?: "?"}"

    /** "(medium confidence, from old glasses numbers)" */
    fun meta(e: Estimate<*>, l: Lang): String = "(${S.confidence(e.confidence)[l]}, ${S.source(e.source)[l]})"

    fun mm(v: Double): String = String.format(Locale.US, "%.1f mm", v)

    /**
     * PD with its measurement uncertainty (rules_update.md rule 6): "about 63 mm (±3 mm)" without the card,
     * "(±1 mm)" with it; the plain value when the engine gave no uncertainty (population average).
     */
    fun pd(a: Assessment, l: Lang): String {
        val sd = a.pdUncertaintyMm ?: return mm(a.pdMm.value)
        return S.pdAbout.format(Math.round(a.pdMm.value).toInt(), Math.round(sd).toInt().coerceAtLeast(1))[l]
    }

    /** The estimated band, or the "too uncertain to show" line (rules_update.md rule 5). */
    fun bandOrUncertain(e: EyeAdvice, l: Lang): String = if (e.bandUncertain) S.bandUncertain[l] else band(e.band.value, l)

    /** One bullet, e.g. "Thin lens: edge about 4.2 mm in 1.60". */
    fun reason(r: FrameReason, l: Lang): String {
        val tpl = S.reasonTemplate(r.code, r.isEdge, r.assumed, r.uncalibrated)
        return when (r.code) {
            ReasonCode.FITS_FACE, ReasonCode.NARROW_FOR_FACE, ReasonCode.WIDE_FOR_FACE -> tpl.format(r.a ?: 0.0, r.b ?: 0.0)
            ReasonCode.THIN_LENS, ReasonCode.THICK_LENS -> tpl.format(r.a ?: 0.0, S.materialShort(r.materialId ?: ""))
            ReasonCode.IN_BUDGET, ReasonCode.OVER_BUDGET -> tpl.format((r.a ?: 0.0).toInt())
            ReasonCode.LIGHTWEIGHT, ReasonCode.HEAVIER, ReasonCode.LENS_HEIGHT_OK -> tpl.format(r.a ?: 0.0)
            ReasonCode.LENS_HEIGHT_LOW, ReasonCode.BRIDGE_FITS, ReasonCode.BRIDGE_MISMATCH -> tpl.format(r.a ?: 0.0, r.b ?: 0.0)
            else -> tpl
        }[l]
    }

    /** Bullets for a ranked frame, with "+"/"−" marks; falls back to the old one-line reason for stored sessions. */
    fun reasons(rf: RankedFrame, l: Lang): List<Pair<Boolean, String>> =
        if (rf.reasons.isNotEmpty()) rf.reasons.map { it.positive to reason(it, l) }
        else listOf(true to S.fit(rf.fit)[l])

    fun label(rf: RankedFrame, l: Lang): String? = rf.label?.let { S.matchLabel(it)[l] }

    fun frameLine(rf: RankedFrame, l: Lang): String =
        S.frameLine.format(rf.frame.sizeMark, String.format(Locale.US, "%.0f mm", rf.frameWidthMm), rf.frame.priceInr)[l]
}
