package com.drishtisaathi.i18n

/** Text for the customer flow redesign: home, shop tools, phone setup, the guided eye check, "For the shop". */
object SShop {
    // ---- home ----
    val shopTools = tx("Shop tools", "दुकान के टूल")
    val setupReminder = tx("Phone setup is not done yet. Tap here to do it (1 minute).", "फ़ोन सेटअप अभी नहीं हुआ है। करने के लिए यहाँ दबाएँ (1 मिनट)।")

    // ---- shop tools ----
    val phoneSetup = tx("Phone setup (one time)", "फ़ोन सेटअप (एक बार)")
    val phoneSetupDone = tx("Phone setup: done", "फ़ोन सेटअप: हो गया")
    val diagnostics = tx("Device checks", "जाँच टूल")
    val precisionMode = tx("Precision mode: full eye tests (staff)", "प्रिसिज़न मोड: पूरी आँख जाँच (स्टाफ़)")
    val precisionNote = tx(
        "Longer tests for staff: blue and white far point, retakes, the full protocol and the letter chart. Results are shown here only.",
        "स्टाफ़ के लिए लंबी जाँच: नीला और सफ़ेद दूर-बिंदु, दोबारा जाँच, पूरा तरीका और अक्षर चार्ट। नतीजे सिर्फ़ यहीं दिखते हैं।",
    )

    // ---- phone setup ----
    val setupReason = tx("So test letters are the right size on this phone", "ताकि इस फ़ोन पर जाँच के अक्षर सही साइज़ के हों")
    val setupStep1 = tx("1. Screen size: match a bank card", "1. स्क्रीन साइज़: बैंक कार्ड से मिलाएँ")
    val setupStep2 = tx("2. Quick camera test", "2. कैमरा की छोटी जाँच")
    val setupCameraHint = tx(
        "Hold the phone at arm's length, face the screen. When the distance shows, tap Finish.",
        "फ़ोन हाथ भर दूरी पर रखें, स्क्रीन की ओर देखें। दूरी दिखने लगे तो 'पूरा करें' दबाएँ।",
    )
    val setupFinish = tx("Finish setup", "सेटअप पूरा करें")
    val skipForNow = tx("Skip for now", "अभी छोड़ें")
    val setupSaved = tx("Phone setup saved for this phone", "इस फ़ोन के लिए सेटअप सेव हो गया")

    // ---- language ----
    val chooseLanguage = tx("Choose language", "भाषा चुनें")

    // ---- interview ----
    val fewMoreQuestions = tx("A few more questions", "कुछ और सवाल")
    val aboutYourEyes = tx("A few questions about your eyes", "आपकी आँखों के बारे में कुछ सवाल")
    val checkingVoice = tx("Getting ready…", "तैयारी हो रही है…")

    // ---- eye check ----
    val eyeCheckTitle = tx("Eye check", "आँख जाँच")
    val rightEye = tx("Right eye", "दाईं आँख")
    val leftEye = tx("Left eye", "बाईं आँख")
    val bothEyes = tx("Both eyes", "दोनों आँखें")
    val powerCheck = tx("Power check", "नंबर जाँच")
    val lineCheck = tx("Line check", "लकीर जाँच")
    val gridCheck = tx("Grid check", "जाली जाँच")
    val readingCheck = tx("Reading check", "पढ़ने की जाँच")
    /** "Right eye: Power check" */
    val header = tx("%s: %s", "%s: %s")
    val glassesOffCover = tx(
        "Take off your glasses. Cover your %s eye with your palm.",
        "चश्मा उतारें। %s आँख हथेली से ढकें।",
    )
    val leftWord = tx("LEFT", "बाईं")
    val rightWord = tx("RIGHT", "दाईं")
    val powerInstruction = tx(
        "Start close. Move the phone slowly away. Tap BLUR when letters stop being sharp.",
        "पास से शुरू करें। फ़ोन धीरे-धीरे दूर ले जाएँ। अक्षर साफ़ न रहें तो 'धुंधला' दबाएँ।",
    )
    val powerStartClose = tx("Bring the phone close first, until the letters are sharp.", "पहले फ़ोन पास लाएँ, जब तक अक्षर साफ़ न दिखें।")
    val repeatOnce = tx(
        "Let's do that once more, slowly. Start close again.",
        "एक बार और करते हैं, धीरे-धीरे। फिर से पास से शुरू करें।",
    )
    val distanceCm = tx("%d cm", "%d cm")
    val stillSharp = tx("Still sharp at arm's length", "हाथ भर दूरी पर भी साफ़")
    val noShortSight = tx("No short-sight found at arm's length", "हाथ भर दूरी तक कम दूर-नज़र नहीं मिली")
    val dialInstruction = tx(
        "Hold the phone at about %d cm. Do all lines look equally dark? If not, tap the darkest line.",
        "फ़ोन लगभग %d cm पर रखें। क्या सब लकीरें एक जैसी गहरी हैं? नहीं तो सबसे गहरी लकीर दबाएँ।",
    )
    val allLinesEqual = tx("All lines equal", "सब लकीरें एक जैसी")
    val dialEqualResult = tx("All lines looked equal", "सब लकीरें एक जैसी दिखीं")
    val dialLineResult = tx("Darker line: %d–%d o'clock (check cylinder with trial lens)", "गहरी लकीर: %d–%d बजे (ट्रायल लेंस से सिलिंडर जाँचें)")
    val gridInstruction = tx(
        "Look at the centre dot. Are any lines wavy, bent or missing?",
        "बीच के बिंदु को देखें। क्या कोई लकीर टेढ़ी, मुड़ी या गायब दिखती है?",
    )
    val gridWavy = tx("Yes, wavy or missing", "हाँ, टेढ़ी या गायब")
    val gridFine = tx("No, all straight", "नहीं, सब सीधी")
    val gridFineResult = tx("Grid lines looked straight", "जाली की लकीरें सीधी दिखीं")
    val gridWavyResult = tx("Wavy or missing lines: see an eye doctor", "टेढ़ी या गायब लकीरें: आँखों के डॉक्टर को दिखाएँ")
    val skipped = tx("Skipped", "छोड़ा गया")
    val next = tx("Next", "आगे")
    val skip = tx("Skip", "छोड़ें")
    val faceHint = tx("Keep your face in the frame", "अपना चेहरा फ़्रेम में रखें")
    val mute = tx("Mute", "आवाज़ बंद")
    val unmute = tx("Sound on", "आवाज़ चालू")
    val checkDone = tx("Eye check done", "आँख जाँच पूरी")

    // ---- results: "For the shop" ----
    val forShop = tx("For the shop", "दुकान के लिए")
    val forShopHint = tx(
        "Staff details: notes, measurements, interview transcript, verification, edit answers.",
        "स्टाफ़ की जानकारी: नोट्स, माप, बातचीत का रिकॉर्ड, जाँच दर्ज करें, जवाब बदलें।",
    )
    val editAnswers = tx("Edit answers", "जवाब बदलें")
    val finish = tx("Finish", "पूरा करें")
}
