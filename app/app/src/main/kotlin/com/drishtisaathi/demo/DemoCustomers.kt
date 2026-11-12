package com.drishtisaathi.demo

import com.drishtisaathi.engine.AffectedEye
import com.drishtisaathi.engine.DistanceSituation
import com.drishtisaathi.engine.NearDistance
import com.drishtisaathi.engine.NearTask
import com.drishtisaathi.engine.PowerKnown
import com.drishtisaathi.engine.ProblemSince
import com.drishtisaathi.engine.RedFlagOnset
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.engine.Budget
import com.drishtisaathi.engine.ColourPref
import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.Estimate
import com.drishtisaathi.engine.FrameShape
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.LookPref
import com.drishtisaathi.engine.MeasuredEye
import com.drishtisaathi.engine.MeasuredFace
import com.drishtisaathi.engine.Occupation
import com.drishtisaathi.engine.ReadingFrequency
import com.drishtisaathi.engine.ScreenHours
import com.drishtisaathi.engine.Source
import com.drishtisaathi.engine.StylePref
import com.drishtisaathi.engine.VisionTests
import com.drishtisaathi.engine.face.FaceShape
import com.drishtisaathi.engine.face.ScaleSource
import com.drishtisaathi.i18n.SFlow
import com.drishtisaathi.i18n.Txt

/**
 * Staff demo mode: invented sample customers (not real people) with pre-filled interview answers, camera
 * measurements and screen-test results, so the whole flow can be shown or screen-recorded without testing live.
 * Sessions made from these are marked DEMO everywhere (screens, history, PDFs).
 */
data class DemoCustomer(
    val id: String,
    val title: Txt,
    val summary: Txt,
    val answers: InterviewAnswers,
    val face: MeasuredFace? = null,
    val tests: VisionTests? = null,
)

object DemoCustomers {
    private fun m(v: Double, c: Confidence = Confidence.HIGH) = Estimate(v, c, Source.MEASURED)

    /** 21-year-old student, distance blur, long screen use, never worn glasses; card-calibrated capture. */
    val youngMyope = DemoCustomer(
        id = "demo_myopia_21",
        title = SFlow.demo1Title,
        summary = SFlow.demo1Summary,
        answers = InterviewAnswers(
            age = 21, wearsGlasses = false, firstTimeWearer = true,
            distanceBlur = true, nearBlur = false, screenHours = ScreenHours.H4_PLUS,
            nightDriving = false, outdoorWork = false, occupation = Occupation.STUDENT,
            headacheStrain = true, diabetesOrBp = false, redFlagFlashes = false, redFlagOther = false,
            budget = Budget.LOW, style = StylePref.MODERN,
            colourPreference = ColourPref.DARK, lightweightPreference = true, lookPreference = LookPref.CASUAL,
            readingFrequency = ReadingFrequency.SOMETIMES,
            visionProblem = VisionProblem.DISTANCE, distanceSituation = DistanceSituation.CLASSROOM_BOARD,
            distanceSince = ProblemSince.MONTHS_6_TO_24, powerKnown = PowerKnown.NO,
        ),
        face = MeasuredFace(
            pdMm = m(61.5), faceWidthMm = m(134.0), bridgeWidthMm = m(17.0, Confidence.MEDIUM),
            faceShape = Estimate(FaceShape.OVAL, Confidence.MEDIUM, Source.MEASURED),
            suggestedFrameShapes = listOf(FrameShape.RECTANGLE, FrameShape.WAYFARER, FrameShape.SQUARE),
            framesUsed = 30, scaleSource = ScaleSource.CARD,
        ),
        tests = VisionTests(
            right = MeasuredEye(seD = -2.25, confidence = Confidence.HIGH),
            left = MeasuredEye(seD = -2.0, confidence = Confidence.HIGH),
        ),
    )

    /** 48-year-old shopkeeper, near blur only, reads daily; capture without the card (PD shown as ±3 mm). */
    val readingNeed = DemoCustomer(
        id = "demo_reading_48",
        title = SFlow.demo2Title,
        summary = SFlow.demo2Summary,
        answers = InterviewAnswers(
            age = 48, wearsGlasses = false, firstTimeWearer = true,
            distanceBlur = false, nearBlur = true, screenHours = ScreenHours.H2_4,
            nightDriving = false, outdoorWork = false, occupation = Occupation.SHOP_BUSINESS,
            headacheStrain = false, diabetesOrBp = false, redFlagFlashes = false, redFlagOther = false,
            budget = Budget.MID, style = StylePref.CLASSIC,
            colourPreference = ColourPref.METALLIC, lightweightPreference = true, lookPreference = LookPref.PROFESSIONAL,
            readingFrequency = ReadingFrequency.DAILY_LONG,
            visionProblem = VisionProblem.NEAR, nearTask = NearTask.ACCOUNTS, nearDistance = NearDistance.NORMAL,
            powerKnown = PowerKnown.NO,
        ),
        face = MeasuredFace(
            pdMm = m(63.0, Confidence.LOW), faceWidthMm = m(140.0, Confidence.LOW), bridgeWidthMm = m(18.0, Confidence.LOW),
            faceShape = Estimate(FaceShape.SQUARE, Confidence.MEDIUM, Source.MEASURED),
            suggestedFrameShapes = listOf(FrameShape.ROUND, FrameShape.OVAL),
            framesUsed = 30, scaleSource = ScaleSource.IRIS_PRIOR,
        ),
        tests = VisionTests(
            right = MeasuredEye(seD = null, confidence = Confidence.HIGH, noMyopiaAtArmsLength = true),
            left = MeasuredEye(seD = null, confidence = Confidence.HIGH, noMyopiaAtArmsLength = true),
            measuredAddD = m(1.25, Confidence.MEDIUM),
        ),
    )

    /** 56-year-old with sudden flashes and floaters: urgent referral, no camera or screen tests. */
    val urgentFlashes = DemoCustomer(
        id = "demo_urgent_56",
        title = SFlow.demo3Title,
        summary = SFlow.demo3Summary,
        answers = InterviewAnswers(
            age = 56, wearsGlasses = true, glassesAgeYears = 3, knownRx = null,
            distanceBlur = true, nearBlur = true, screenHours = ScreenHours.H0_2,
            nightDriving = true, outdoorWork = false, occupation = Occupation.DRIVER,
            headacheStrain = false, diabetesOrBp = false, redFlagFlashes = true, redFlagOther = false,
            budget = Budget.MID, style = StylePref.ANY,
            colourPreference = ColourPref.ANY, lightweightPreference = false, lookPreference = LookPref.BOTH,
            readingFrequency = ReadingFrequency.SOMETIMES,
            visionProblem = VisionProblem.BOTH, distanceSituation = DistanceSituation.DRIVING,
            distanceSince = ProblemSince.UNDER_6_MONTHS, nearTask = NearTask.NEWSPAPER, nearDistance = NearDistance.NORMAL,
            happyWithGlasses = false, redFlagOnset = RedFlagOnset.THIS_WEEK, redFlagEye = AffectedEye.RIGHT,
        ),
    )

    val all: List<DemoCustomer> = listOf(youngMyope, readingNeed, urgentFlashes)
}
