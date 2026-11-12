package com.drishtisaathi.i18n

/**
 * User-facing text for Track B (camera, calibration, quality-gate instructions). Owner edits only this file.
 * Create every string with tx(en, hi). Hindi needs a native-speaker proofread before demo.
 */
object SCamera {
    // ---- Spoken quality-gate instructions (one per failed gate) ----
    val gNoFace = tx("Please look at the screen.", "कृपया स्क्रीन की तरफ़ देखिए।")
    val gCentre = tx("Move your face to the middle of the screen.", "अपना चेहरा स्क्रीन के बीच में लाइए।")
    val gCloser = tx("Come a little closer.", "थोड़ा पास आइए।")
    val gFarther = tx("Move a little back.", "थोड़ा पीछे हटिए।")
    val gYaw = tx("Look straight at the phone. Do not turn your head.", "सीधे फ़ोन की तरफ़ देखिए। सिर मत घुमाइए।")
    val gRoll = tx("Keep your head straight. Do not tilt it.", "सिर सीधा रखिए। झुकाइए मत।")
    val gEyes = tx("Keep your eyes open.", "आँखें खुली रखिए।")
    val gSharp = tx("Hold still.", "हिलिए मत, स्थिर रहिए।")
    val gDark = tx("It is too dark. Face the light.", "रोशनी कम है। रोशनी की तरफ़ मुँह कीजिए।")
    val gBright = tx("It is too bright. Move away from the strong light.", "रोशनी बहुत तेज़ है। तेज़ रोशनी से थोड़ा हटिए।")
    val gOk = tx("Good. Hold still.", "बहुत अच्छे। ऐसे ही रहिए।")

    // ---- Face capture screen ----
    val captureTitle = tx("Face measurement", "चेहरे का माप")
    val captureIntro = tx(
        "Remove your glasses, hold the phone at arm's length at eye level and look at the screen.",
        "चश्मा उतारिए, फ़ोन को हाथ की दूरी पर आँखों के सामने रखिए और स्क्रीन की तरफ़ देखिए।",
    )
    val captureProgress = tx("Good frames: %d of %d", "सही फ़्रेम: %d में से %d")
    val captureDone = tx("Done. Thank you.", "हो गया। धन्यवाद।")
    val restart = tx("Start again", "फिर से शुरू करें")
    val skipCamera = tx("Continue without camera", "कैमरे के बिना आगे बढ़ें")
    val cameraStarting = tx("Starting camera…", "कैमरा शुरू हो रहा है…")
    val cameraError = tx("The camera could not start.", "कैमरा शुरू नहीं हो सका।")

    // ---- Permission ----
    val permTitle = tx("Camera permission", "कैमरे की अनुमति")
    val permWhy = tx(
        "The camera measures your face and distance from the screen. Pictures stay in the phone's memory only and are never saved.",
        "कैमरा आपके चेहरे का माप और स्क्रीन से दूरी नापता है। तस्वीरें सिर्फ़ फ़ोन की मेमोरी में रहती हैं, कभी सेव नहीं होतीं।",
    )
    val permAllow = tx("Allow camera", "कैमरे की अनुमति दें")
    val permDenied = tx(
        "Camera permission was not given. You can continue without the camera; the typed steps still work.",
        "कैमरे की अनुमति नहीं मिली। आप कैमरे के बिना आगे बढ़ सकते हैं; बाकी सवाल-जवाब चलते रहेंगे।",
    )
    val permSettings = tx("Open settings", "सेटिंग खोलें")

    // ---- Screen calibration (bank card on screen) ----
    val screenCalTitle = tx("Screen size check", "स्क्रीन के आकार की जाँच")
    val screenCalHow = tx(
        "Hold any bank or ID card flat on the screen with its corner in the top-left corner of the box. Drag until the bottom edge of the box matches the bottom edge of the card.",
        "कोई भी बैंक या पहचान कार्ड स्क्रीन पर सीधा रखिए, उसका कोना डिब्बे के ऊपरी-बाएँ कोने में। डिब्बे के निचले किनारे को खींचकर कार्ड के निचले किनारे से मिलाइए।",
    )
    val screenCalSave = tx("The box matches the card", "डिब्बा कार्ड से मिल गया")
    val screenCalNoCard = tx("No card: use phone's estimate", "कार्ड नहीं है: फ़ोन का अंदाज़ा लें")
    val smaller = tx("Smaller", "छोटा")
    val bigger = tx("Bigger", "बड़ा")
    val screenCalBadValue = tx("That size does not look right. Please match the card again.", "यह आकार सही नहीं लगता। कृपया कार्ड से दोबारा मिलाइए।")

    // ---- Forehead card calibration ----
    val foreheadTitle = tx("Card on forehead", "माथे पर कार्ड")
    val foreheadHow = tx(
        "Hold a bank or ID card flat on the forehead, long side across, just above the eyebrows. Look at the screen and tap Take photo. The photo is used once and not saved.",
        "बैंक या पहचान कार्ड को माथे पर, भौंहों के ठीक ऊपर, लंबी तरफ़ आड़ा करके सीधा रखिए। स्क्रीन की तरफ़ देखिए और फ़ोटो लें दबाइए। फ़ोटो एक बार इस्तेमाल होकर मिट जाती है।",
    )
    val takePhoto = tx("Take photo", "फ़ोटो लें")
    val measuring = tx("Measuring…", "माप रहे हैं…")
    val cardNotFound = tx(
        "The card was not found. Hold it flat, fully visible, against a plain background, and try again.",
        "कार्ड नहीं दिखा। कार्ड को सीधा और पूरा दिखते हुए रखिए, और फिर से कोशिश कीजिए।",
    )
    val faceNotFound = tx("The face was not found in the photo. Try again.", "फ़ोटो में चेहरा नहीं दिखा। फिर से कोशिश कीजिए।")
    val irisImplausible = tx(
        "The measurement does not look right. Please try again.",
        "माप सही नहीं लगता। कृपया फिर से कोशिश कीजिए।",
    )
    val foreheadResult = tx("Eye (iris) size measured: %.1f mm", "आँख (आइरिस) का माप: %.1f मिमी")
    val tryAgain = tx("Try again", "फिर से कोशिश करें")
    val useResult = tx("Use this", "यही रखें")
    val skipCard = tx("No card: use the average size", "कार्ड नहीं है: औसत माप लें")

    // ---- Debug screen (staff only) ----
    val debugTitle = tx("Camera check (staff)", "कैमरा जाँच (स्टाफ़)")
    val noFaceShort = tx("No face", "चेहरा नहीं")
    val pass = tx("PASS", "ठीक")
    val fail = tx("FAIL", "ठीक नहीं")
}
