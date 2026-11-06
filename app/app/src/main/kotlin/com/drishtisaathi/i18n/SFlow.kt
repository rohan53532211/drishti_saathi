package com.drishtisaathi.i18n

import com.drishtisaathi.engine.face.FaceCapture
import com.drishtisaathi.engine.face.FaceShape

/** User-facing text for the integrated flow (capture → tests → results). Create every string with tx(en, hi). */
object SFlow {
    // ---- flow steps ----
    val stepCard = tx("Step 1 of 3: card size check (optional)", "चरण 1/3: कार्ड से साइज़ जाँच (वैकल्पिक)")
    val stepCapture = tx("Step 2 of 3: face measurement", "चरण 2/3: चेहरे का माप")
    val stepTests = tx("Step 3 of 3: eye checks on the screen", "चरण 3/3: स्क्रीन पर आँखों की जाँच")
    val urgentSkipsTests = tx(
        "The answers need an eye doctor first, so the screen checks are skipped.",
        "जवाबों के अनुसार पहले आँखों के डॉक्टर को दिखाना ज़रूरी है, इसलिए स्क्रीन जाँच छोड़ दी गई है।",
    )

    // ---- capture outcome ----
    val captureOk = tx("Face measured", "चेहरे का माप हो गया")
    val captureLow = tx(
        "Face measured, but the numbers look unusual. They are kept with low confidence; staff should check PD with a ruler.",
        "चेहरे का माप हो गया, पर नंबर असामान्य लगते हैं। इन्हें कम भरोसे के साथ रखा गया है; स्टाफ़ PD रूलर से जाँचें।",
    )
    val captureRescan = tx(
        "The face measurement did not look right and was not used. Try again with the face straight and well lit.",
        "चेहरे का माप सही नहीं लगा और इस्तेमाल नहीं किया गया। चेहरा सीधा और रोशनी में रखकर फिर कोशिश करें।",
    )
    val captureNone = tx("No usable face frames. Measurements use average values.", "चेहरे की कोई काम की फ़्रेम नहीं मिली। माप के लिए औसत मान लिए गए हैं।")
    val tryAgain = tx("Try again", "फिर कोशिश करें")
    val continueNext = tx("Continue", "आगे बढ़ें")
    val skipStep = tx("Skip this step", "यह चरण छोड़ें")
    fun captureStatus(s: FaceCapture.Status) = when (s) {
        FaceCapture.Status.OK -> captureOk
        FaceCapture.Status.LOW_PLAUSIBILITY -> captureLow
        FaceCapture.Status.RESCAN -> captureRescan
        FaceCapture.Status.NO_USABLE_FRAMES -> captureNone
    }

    // ---- results additions ----
    val tryOn = tx("Try on", "पहनकर देखें")
    val tryOnAll = tx("Try on these frames", "ये फ़्रेम पहनकर देखें")
    val bridgeWidth = tx("Nose bridge width", "नाक के ब्रिज की चौड़ाई")
    val faceShape = tx("Face shape (estimate, for style only)", "चेहरे का आकार (अनुमान, केवल स्टाइल के लिए)")
    val shapesThatSuit = tx("Frame shapes that often suit", "अक्सर जँचने वाले फ़्रेम आकार")
    val framesMeasured = tx("Face frames used", "इस्तेमाल हुई फ़्रेम")
    val screenTests = tx("Screen eye checks", "स्क्रीन पर आँखों की जाँच")
    val testsNotDone = tx("Not done", "नहीं की गई")
    val testsDone = tx("Done; results are part of the estimated band", "हो गई; नतीजे अनुमानित रेंज में शामिल हैं")

    fun faceShape(v: FaceShape) = FACE_SHAPE.getValue(v)
    private val FACE_SHAPE = mapOf(
        FaceShape.ROUND to tx("round", "गोल"),
        FaceShape.OVAL to tx("oval", "अंडाकार"),
        FaceShape.SQUARE to tx("square", "चौकोर"),
        FaceShape.LONG to tx("long", "लंबा"),
        FaceShape.HEART to tx("heart", "दिल के आकार का"),
    )

    // ---- staff tools on the home screen ----
    val staffTools = tx("Staff tools", "स्टाफ़ टूल")
    val cameraCheck = tx("Camera check (distance, PD, angles)", "कैमरा जाँच (दूरी, PD, कोण)")
    val screenCalibration = tx("Screen size check with a bank card", "बैंक कार्ड से स्क्रीन साइज़ जाँच")
    val screenCalibrated = tx("Screen size checked", "स्क्रीन साइज़ जाँचा गया")
}
