package com.drishtisaathi.i18n

/**
 * User-facing text for Track C (vision test screens). Owner edits only this file. Create every string with tx(en, hi).
 * Section 6: never "prescription", "diagnosis", "power is". Hindi needs a native-speaker proofread before demo.
 */
object SVision {
    // ---- Flow ----
    val flowTitle = tx("Eye tests on the phone", "फ़ोन पर आँखों की जाँच")
    val introTitle = tx("Before we start", "शुरू करने से पहले")
    val introBody = tx(
        "Sit in good light. Take off your glasses for the first tests. Hold the phone at eye level and keep your face in view of the front camera. Each test is done twice. You can skip any test.",
        "अच्छी रोशनी में बैठिए। पहली जाँचों के लिए चश्मा उतार दीजिए। फ़ोन को आँखों की ऊँचाई पर रखिए और चेहरा सामने वाले कैमरे में दिखता रहे। हर जाँच दो बार होगी। आप कोई भी जाँच छोड़ सकते हैं।",
    )
    val start = tx("Start", "शुरू करें")
    val skipTest = tx("Skip this test", "यह जाँच छोड़ें")
    val cancel = tx("Stop the tests", "जाँच बंद करें")
    val next = tx("Next", "आगे")
    val repeatTest = tx("Repeat this test", "यह जाँच दोबारा करें")
    val continueAnyway = tx("Continue", "आगे बढ़ें")
    val lowAgreement = tx(
        "The two tries did not agree well. Please repeat this test once more.",
        "दोनों बार के जवाब आपस में ठीक से नहीं मिले। कृपया यह जाँच एक बार और कीजिए।",
    )
    val repeatOf = tx("Try %d of %d", "%d में से %d बार")
    val distanceNow = tx("Distance: %d cm", "दूरी: %d सेमी")
    val distanceUnknown = tx("Distance: waiting for the camera", "दूरी: कैमरे का इंतज़ार")
    val simulated = tx("Demo: distance set by hand", "डेमो: दूरी हाथ से सेट की गई")

    // ---- Eyes ----
    val coverLeft = tx("Cover your LEFT eye with your palm. Test the RIGHT eye.", "बाईं आँख हथेली से ढकिए। दाईं आँख की जाँच।")
    val coverRight = tx("Cover your RIGHT eye with your palm. Test the LEFT eye.", "दाईं आँख हथेली से ढकिए। बाईं आँख की जाँच।")
    val bothEyes = tx("Keep both eyes open.", "दोनों आँखें खुली रखिए।")
    val eyeRight = tx("Right eye", "दाईं आँख")
    val eyeLeft = tx("Left eye", "बाईं आँख")
    val eyeBoth = tx("Both eyes", "दोनों आँखें")

    // ---- Pause instructions (spoken by Track D's voice if wired) ----
    val pauseNoFace = tx("Please look at the screen. Keep your face in front of the camera.", "कृपया स्क्रीन की तरफ़ देखिए। चेहरा कैमरे के सामने रखिए।")
    val pauseNoReading = tx("Waiting for the camera to find your face.", "कैमरा आपका चेहरा ढूँढ रहा है, कृपया रुकिए।")
    val pauseTooClose = tx("Move the phone a little farther away.", "फ़ोन थोड़ा दूर कीजिए।")
    val pauseTooFar = tx("Bring the phone a little closer.", "फ़ोन थोड़ा पास लाइए।")
    val holdAt = tx("Hold the phone about %d cm from your eye.", "फ़ोन को आँख से लगभग %d सेमी दूर रखिए।")

    // ---- 1. Near acuity ----
    val acuityTitle = tx("Small letter test", "छोटे अक्षर की जाँच")
    val acuityIntro = tx(
        "Wear your reading glasses if you have them. Hold the phone at reading distance. Tap the arrow that shows which way the bars of the E point. If you are not sure, guess.",
        "अगर पढ़ने का चश्मा है तो पहन लीजिए। फ़ोन को पढ़ने की दूरी पर रखिए। E की लकीरें जिस तरफ़ हैं, उस तरफ़ का तीर दबाइए। पक्का न हो तो अंदाज़ा लगाइए।",
    )
    val acuityQuestion = tx("Which way do the bars point?", "लकीरें किस तरफ़ हैं?")
    val cannotSee = tx("Cannot see", "दिख नहीं रहा")
    val arrowUp = tx("Up", "ऊपर")
    val arrowDown = tx("Down", "नीचे")
    val arrowLeft = tx("Left", "बाएँ")
    val arrowRight = tx("Right", "दाएँ")

    // ---- 2. Far point ----
    val farTitle = tx("Far point test", "दूर-बिंदु जाँच")
    val farIntro = tx(
        "Glasses off. First on a blue screen, then on a white screen. Start with the phone close to your eye, about a hand's width. Then move it slowly away. Tap as soon as the letters start to look blurred.",
        "चश्मा उतारिए। पहले नीली स्क्रीन पर, फिर सफ़ेद स्क्रीन पर। फ़ोन को आँख के पास, लगभग एक हथेली की दूरी पर रखिए। फिर धीरे-धीरे दूर ले जाइए। जैसे ही अक्षर धुंधले लगने लगें, बटन दबाइए।",
    )
    val farBlue = tx("Blue screen", "नीली स्क्रीन")
    val farWhite = tx("White screen", "सफ़ेद स्क्रीन")
    val farBringClose = tx("Bring the phone close to your eye, about a hand's width.", "फ़ोन को आँख के पास लाइए, लगभग एक हथेली की दूरी पर।")
    val farMoveAway = tx("Now move the phone slowly away. Tap when the letters just start to blur.", "अब फ़ोन धीरे-धीरे दूर ले जाइए। अक्षर धुंधले होने लगें तो बटन दबाइए।")
    val farBlurred = tx("Letters look blurred now", "अब अक्षर धुंधले हैं")
    val farStillClear = tx("Still clear at 2 metres", "2 मीटर पर भी साफ़ हैं")
    // rules_update.md rule 1: staff-held far point (Luo method) when the blur point is beyond the customer's reach
    val farCannotReach = tx("Still clear at arm's length: staff will hold the phone", "हाथ की दूरी पर भी साफ़ हैं: स्टाफ़ फ़ोन पकड़ेंगे")
    val farStaffHeld = tx(
        "Staff: hold the phone and slowly move it away from the customer",
        "स्टाफ़: फ़ोन पकड़िए और धीरे-धीरे ग्राहक से दूर ले जाइए",
    )
    val farStaffBringClose = tx(
        "Staff: hold the phone close to the customer's eye, about a hand's width. The customer keeps looking at the letters.",
        "स्टाफ़: फ़ोन को ग्राहक की आँख के पास, लगभग एक हथेली की दूरी पर रखिए। ग्राहक अक्षरों को देखते रहें।",
    )
    val farStaffMoveAway = tx(
        "Staff: hold the phone and slowly move it away from the customer. Customer: say when the letters just start to blur.",
        "स्टाफ़: फ़ोन पकड़िए और धीरे-धीरे ग्राहक से दूर ले जाइए। ग्राहक: अक्षर धुंधले होने लगें तो बताइए।",
    )
    // rules 3 and 4
    val farThirdReading = tx(
        "The two tries were far apart. One more try, please.",
        "दोनों बार के नतीजे काफ़ी अलग थे। कृपया एक बार और कीजिए।",
    )
    val farRetake = tx(
        "Blue and white did not agree. Let us do the far point test once more, blue then white.",
        "नीले और सफ़ेद के नतीजे नहीं मिले। दूर-बिंदु जाँच एक बार फिर कीजिए, पहले नीला फिर सफ़ेद।",
    )
    val sumFarStaffHeld = tx("Staff-held far point", "स्टाफ़ ने फ़ोन पकड़कर दूर-बिंदु मापा")
    val sumFarThird = tx("A third reading was taken (repeats disagreed); median used.", "तीसरी बार मापा गया (दोहराव मेल नहीं खाए); बीच का मान लिया गया।")
    val sumFarRetaken = tx("Blue and white disagreed, so the test was retaken once.", "नीला और सफ़ेद नहीं मिले, इसलिए जाँच एक बार दोबारा की गई।")

    // ---- 3. Clock dial ----
    val dialTitle = tx("Clock dial test", "घड़ी-डायल जाँच")
    val dialIntro = tx(
        "Glasses off. Hold the phone at the distance shown. Some lines may look darker or sharper than others. Tap the darkest line, or tap 'All look the same'.",
        "चश्मा उतारिए। फ़ोन को बताई गई दूरी पर रखिए। कुछ लकीरें बाकी से ज़्यादा गहरी या साफ़ दिख सकती हैं। सबसे गहरी लकीर पर टैप कीजिए, या 'सब एक जैसी हैं' दबाइए।",
    )
    val dialQuestion = tx("Which line looks darkest?", "कौन-सी लकीर सबसे गहरी दिखती है?")
    val dialAllSame = tx("All look the same", "सब एक जैसी हैं")
    val dialLine = tx("%d–%d line", "%d–%d वाली लकीर")
    val lookAway = tx("Look away for a moment and blink. Then look again.", "एक पल के लिए दूसरी तरफ़ देखिए और पलकें झपकाइए। फिर दोबारा देखिए।")

    // ---- 4. Near point ----
    val nearTitle = tx("Reading distance test", "पढ़ने की दूरी की जाँच")
    val nearIntro = tx(
        "Glasses off, both eyes open. Start with the phone at arm's length where the letters are clear. Move it slowly towards your eyes. Tap as soon as the letters start to blur.",
        "चश्मा उतारिए, दोनों आँखें खुली रखिए। फ़ोन को हाथ की दूरी पर रखिए जहाँ अक्षर साफ़ दिखें। फिर धीरे-धीरे आँखों की तरफ़ लाइए। अक्षर धुंधले होने लगें तो बटन दबाइए।",
    )
    val nearStartFar = tx("Hold the phone farther away, where the letters are clear.", "फ़ोन को और दूर रखिए, जहाँ अक्षर साफ़ दिखें।")
    val nearMoveIn = tx("Now bring the phone slowly closer. Tap when the letters start to blur.", "अब फ़ोन धीरे-धीरे पास लाइए। अक्षर धुंधले होने लगें तो बटन दबाइए।")
    val nearBlurred = tx("Letters look blurred now", "अब अक्षर धुंधले हैं")
    val nearOutOfReach = tx("Blurred even at arm's length", "हाथ की दूरी पर भी धुंधले हैं")
    val nearNoSe = tx("Far point not measured: reading result has low confidence.", "दूर-बिंदु नहीं मापा गया: पढ़ने का नतीजा कम भरोसे का है।")

    // ---- 5. Amsler ----
    val amslerTitle = tx("Grid test", "जाली (ग्रिड) जाँच")
    val amslerIntro = tx(
        "Wear your reading glasses if you have them. Hold the phone at reading distance. Look only at the dot in the middle.",
        "अगर पढ़ने का चश्मा है तो पहन लीजिए। फ़ोन को पढ़ने की दूरी पर रखिए। सिर्फ़ बीच वाले बिंदु को देखिए।",
    )
    val amslerQuestion = tx(
        "While looking at the dot: are any lines wavy, bent, blurred or missing?",
        "बिंदु को देखते हुए: क्या कोई लकीर टेढ़ी, मुड़ी हुई, धुंधली या गायब दिखती है?",
    )
    val yes = tx("Yes", "हाँ")
    val no = tx("No", "नहीं")
    val amslerMarkHint = tx("If you can, tap where the lines look wrong. Then tap Done.", "हो सके तो जहाँ लकीरें गड़बड़ दिखें वहाँ टैप कीजिए। फिर 'हो गया' दबाइए।")
    val done = tx("Done", "हो गया")

    // ---- Summary ----
    val summaryTitle = tx("Test results for the staff", "स्टाफ़ के लिए जाँच के नतीजे")
    val summaryNote = tx(
        "These are screening measurements. The shop's trial-lens test confirms the starting trial lens.",
        "ये शुरुआती माप हैं। दुकान की ट्रायल-लेंस जाँच से शुरुआती ट्रायल लेंस पक्का होता है।",
    )
    val finish = tx("Finish", "पूरा करें")
    val skipped = tx("skipped", "छोड़ दी गई")
    val notRun = tx("not run", "नहीं की गई")
    val sumFar = tx("Far point: blue %s, white %s → estimated SE %s D (%s)", "दूर-बिंदु: नीला %s, सफ़ेद %s → अनुमानित SE %s D (%s)")
    val sumFarNoMyopia = tx("Far point: letters clear at arm's length, no myopia found (%s)", "दूर-बिंदु: हाथ की दूरी पर अक्षर साफ़, निकट-दृष्टि नहीं मिली (%s)")
    val sumColoursDisagree = tx("Blue and white results did not agree: repeat or use the hand test.", "नीले और सफ़ेद के नतीजे नहीं मिले: दोबारा करें या हाथ वाली जाँच करें।")
    val sumDialFlag = tx("Clock dial: darker line, possible astigmatism near axis %d° (%s)", "घड़ी-डायल: एक लकीर गहरी, लगभग %d° पर सिलिंडर हो सकता है (%s)")
    val sumDialNone = tx("Clock dial: no line darker (%s)", "घड़ी-डायल: कोई लकीर ज़्यादा गहरी नहीं (%s)")
    val sumNear = tx("Reading test: near point %d cm → estimated add %s D (%s)", "पढ़ने की जाँच: निकट-बिंदु %d सेमी → अनुमानित ऐड %s D (%s)")
    val sumNearOut = tx("Reading test: blurred even at arm's length; add from the age table", "पढ़ने की जाँच: हाथ की दूरी पर भी धुंधला; ऐड उम्र की तालिका से")
    val sumAcuity = tx("Small letters: logMAR %.1f (%s)", "छोटे अक्षर: logMAR %.1f (%s)")
    val sumAcuityRefer = tx("Small letters: below the screening limit, refer for a full eye test", "छोटे अक्षर: जाँच सीमा से कम, पूरी आँख-जाँच के लिए भेजें")
    val sumAmslerOk = tx("Grid: lines look normal", "जाली: लकीरें सामान्य")
    val sumAmslerBad = tx("Grid: wavy or missing lines reported, same-day eye doctor visit needed", "जाली: टेढ़ी या गायब लकीरें बताईं, आज ही आँखों के डॉक्टर को दिखाएँ")
    val cm = tx("%d cm", "%d सेमी")
    val beyondReach = tx("beyond reach", "पहुँच से बाहर")

    // ---- Debug panel ----
    val debugTitle = tx("Demo distance (no camera)", "डेमो दूरी (बिना कैमरा)")
    val debugFace = tx("Face visible", "चेहरा दिख रहा है")
}
