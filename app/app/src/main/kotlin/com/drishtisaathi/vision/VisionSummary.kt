package com.drishtisaathi.vision

import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.SVision
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.i18n.fmtD
import java.util.Locale
import kotlin.math.roundToInt

/** Staff-facing summary of a [VisionSession], one bilingual line per result (pure, unit-tested). */
object VisionSummary {
    data class Section(val title: Txt, val lines: List<Txt>)

    /** Fill a template; [Txt] arguments are taken in the matching language. */
    internal fun fill(t: Txt, vararg args: Any?): Txt = Txt(
        String.format(Locale.US, t.en, *args.map { if (it is Txt) it.en else it }.toTypedArray()),
        String.format(Locale.US, t.hi, *args.map { if (it is Txt) it.hi else it }.toTypedArray()),
    )

    private fun labelled(label: Txt, value: Txt) = Txt("${label.en}: ${value.en}", "${label.hi}: ${value.hi}")

    fun eyeLabel(e: TestEye): Txt = when (e) {
        TestEye.RIGHT -> SVision.eyeRight
        TestEye.LEFT -> SVision.eyeLeft
        TestEye.BOTH -> SVision.eyeBoth
    }

    private fun mmList(values: List<Double?>): Txt {
        if (values.isEmpty()) return SVision.notRun
        val parts = values.map { v -> v?.let { SVision.cm.format((it / 10.0).roundToInt()) } ?: SVision.beyondReach }
        return Txt(parts.joinToString(" / ") { it.en }, parts.joinToString(" / ") { it.hi })
    }

    private fun missing(step: VisionStep, s: VisionSession) = if (step in s.skipped) SVision.skipped else SVision.notRun

    fun sections(s: VisionSession): List<Section> = buildList {
        for (eye in listOf(TestEye.RIGHT, TestEye.LEFT)) {
            val lines = mutableListOf<Txt>()
            val fp = s.farPoint[eye]
            lines += when {
                fp == null -> labelled(SVision.farTitle, missing(VisionStep.FarPoint(eye), s))
                fp.noMyopiaAtArmsLength -> fill(SVision.sumFarNoMyopia, S.confidence(fp.confidence))
                else -> fill(SVision.sumFar, mmList(fp.blueMm), mmList(fp.whiteMm), fmtD(fp.seD!!), S.confidence(fp.confidence))
            }
            if (fp?.consistent == false) lines += SVision.sumColoursDisagree
            val cd = s.clockDial[eye]
            lines += when {
                cd == null -> labelled(SVision.dialTitle, missing(VisionStep.ClockDial(eye), s))
                cd.astigmatismFlag -> fill(SVision.sumDialFlag, cd.axisDeg, S.confidence(cd.confidence))
                else -> fill(SVision.sumDialNone, S.confidence(cd.confidence))
            }
            val acuity = s.nearAcuity[eye]
            if (acuity != null) lines += acuityLine(acuity)
            else if (VisionStep.NearAcuity(eye) in s.skipped) lines += labelled(SVision.acuityTitle, SVision.skipped)
            val amsler = s.amsler[eye]
            lines += when {
                amsler == null -> labelled(SVision.amslerTitle, missing(VisionStep.Amsler(eye), s))
                amsler.abnormal -> SVision.sumAmslerBad
                else -> SVision.sumAmslerOk
            }
            add(Section(eyeLabel(eye), lines))
        }
        val both = mutableListOf<Txt>()
        val np = s.nearPoint
        val addEst = np?.add
        both += when {
            np == null -> labelled(SVision.nearTitle, missing(VisionStep.NearPoint, s))
            addEst == null || np.nearPointMm == null -> SVision.sumNearOut
            else -> fill(SVision.sumNear, (np.nearPointMm / 10.0).roundToInt(), fmtD(addEst.value), S.confidence(addEst.confidence))
        }
        if (np != null && !np.seMeasured && !np.censored) both += SVision.nearNoSe
        s.nearAcuity[TestEye.BOTH]?.let { both += acuityLine(it) }
        if (s.simulatedDistance) both += SVision.simulated
        add(Section(SVision.eyeBoth, both))
    }

    private fun acuityLine(a: NearAcuityEyeResult): Txt =
        if (a.belowThreshold) SVision.sumAcuityRefer else fill(SVision.sumAcuity, a.logMar, S.confidence(a.confidence))
}
