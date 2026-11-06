package com.drishtisaathi.ui

import com.drishtisaathi.engine.Estimate
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

    /** Numeric reason line, e.g. "fits your face width, edge about 4.2 mm in 1.60". */
    fun frameReason(rf: RankedFrame, l: Lang): String {
        val parts = mutableListOf(S.fit(rf.fit)[l])
        val mat = S.materialShort(rf.materialId)
        parts += rf.thickestMm?.let { t ->
            (if (rf.thickestIsEdge) S.edgeAbout else S.centreAbout).format(t, mat)[l]
        } ?: S.thicknessUnknown[l]
        if (!rf.withinBudget) parts += S.overBudget[l]
        return parts.joinToString(", ")
    }

    fun frameLine(rf: RankedFrame, l: Lang): String =
        S.frameLine.format(rf.frame.sizeMark, String.format(Locale.US, "%.0f mm", rf.frameWidthMm), rf.frame.priceInr)[l]
}
