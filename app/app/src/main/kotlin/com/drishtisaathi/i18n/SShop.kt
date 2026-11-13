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
    val precisionMode = tx("Precision mode: full eye test", "प्रिसिज़न मोड: पूरी आँख जाँच")
    val precisionNote = tx(
        "The same reading test as the customer eye check (far for each eye, then near), with the same results screen and PDFs, saved as a session.",
        "ग्राहक जाँच जैसी ही पढ़ने की जाँच (हर आँख की दूर की, फिर पास की), वही नतीजे और PDF, सेशन के रूप में सेव।",
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
    val lineCheck = tx("Line blur check", "लकीर धुंधलापन जाँच")
    val linesHorizontal = tx(
        "Lying lines. Start close. Move the phone slowly away. Tap BLUR when the lines stop being sharp.",
        "लेटी लकीरें। पास से शुरू करें। फ़ोन धीरे-धीरे दूर ले जाएँ। लकीरें साफ़ न रहें तो 'धुंधला' दबाएँ।",
    )
    val linesVertical = tx(
        "Standing lines. Start close again. Move the phone slowly away. Tap BLUR when the lines stop being sharp.",
        "खड़ी लकीरें। फिर से पास से शुरू करें। फ़ोन धीरे-धीरे दूर ले जाएँ। लकीरें साफ़ न रहें तो 'धुंधला' दबाएँ।",
    )
    val linesFlag = tx(
        "Possible astigmatism: about %s cyl, axis about %d (approximate, confirm with trial lens)",
        "एस्टिग्मैटिज़्म हो सकता है: लगभग %s सिलिंडर, एक्सिस लगभग %d (अनुमान, ट्रायल लेंस से पक्का करें)",
    )
    val linesTogether = tx("Lines blur together: no clear astigmatism", "दोनों लकीरें साथ धुंधली हुईं: साफ़ एस्टिग्मैटिज़्म नहीं")
    val cylShort = tx("cyl about %s, axis about %d (approximate)", "सिलिंडर लगभग %s, एक्सिस लगभग %d (अनुमान)")
    val readingInstruction = tx(
        "Both eyes open. Hold the phone far. Move it slowly closer. Tap BLUR when the text stops being sharp.",
        "दोनों आँखें खुली रखें। फ़ोन दूर पकड़ें। धीरे-धीरे पास लाएँ। अक्षर साफ़ न रहें तो 'धुंधला' दबाएँ।",
    )
    val readingSample = tx("I read the newspaper every morning with my tea.", "मैं रोज़ सुबह चाय के साथ अख़बार पढ़ता हूँ।")
    val sharpUpClose = tx("Still sharp up close", "पास में भी साफ़")
    val nearAdd = tx(
        "Near point %d cm: about %s for reading (approximate, confirm with a ready-reader)",
        "पास का बिंदु %d cm: पढ़ने के लिए लगभग %s (अनुमान, रेडी-रीडर से पक्का करें)",
    )
    val nearFarther = tx(
        "Near point %d cm (expected about %d cm). Near point is farther than expected for your age: possible long-sightedness or focusing weakness. Check with plus trial lenses.",
        "पास का बिंदु %d cm (उम्मीद लगभग %d cm)। उम्र के हिसाब से पास का बिंदु ज़्यादा दूर है: दूर-दृष्टि या फ़ोकस की कमज़ोरी हो सकती है। प्लस ट्रायल लेंस से जाँचें।",
    )
    val nearNormal = tx("Near point %d cm: as expected for your age (about %d cm).", "पास का बिंदु %d cm: उम्र के हिसाब से ठीक (लगभग %d cm)।")
    val nearOnly = tx("Near point %d cm.", "पास का बिंदु %d cm।")
    val gridCheck = tx("Grid check", "जाली जाँच")
    val farCheck = tx("Far reading check", "दूर पढ़ने की जाँच")
    val readingCheck = tx("Near reading check", "पास पढ़ने की जाँच")
    val cantRead = tx("Can't read clearly", "साफ़ नहीं पढ़ पा रहे")
    val newLetters = tx("New letters (say \"badlo\")", "नए अक्षर (बोलें \"बदलो\")")
    val farInstruction = tx(
        "Start close. Move the phone slowly away. Tap 'Can't read clearly' when you can no longer read the letters.",
        "पास से शुरू करें। फ़ोन धीरे-धीरे दूर ले जाएँ। जब अक्षर पढ़ न पाएँ, तो 'साफ़ नहीं पढ़ पा रहे' दबाएँ।",
    )
    val nearInstruction = tx(
        "Both eyes open. Hold the phone far. Move it slowly closer. Tap 'Can't read clearly' when the words blur.",
        "दोनों आँखें खुली रखें। फ़ोन दूर पकड़ें। धीरे-धीरे पास लाएँ। जब शब्द धुंधले हों, तो 'साफ़ नहीं पढ़ पा रहे' दबाएँ।",
    )
    val stillReadableReach = tx("Still readable at arm's length", "हाथ भर दूरी पर भी पढ़ पा रहे हैं")
    val stillReadableClose = tx("Still readable up close", "पास में भी पढ़ पा रहे हैं")
    val doAgain = tx("Do it again", "फिर से करें")
    val farShort = tx(
        "Glasses off, cover your %s eye.\nMove slowly away; tap when letters blur.",
        "चश्मा उतारें, %s आँख ढकें।\nधीरे-धीरे दूर जाएँ; अक्षर धुंधले हों तो दबाएँ।",
    )
    val howTo = tx("How to", "कैसे करें")
    val ok = tx("OK", "ठीक है")
    val afterDoctor = tx(
        "Suggestions for after your eye doctor visit. Do not delay the visit.",
        "ये सुझाव आँखों के डॉक्टर को दिखाने के बाद के लिए हैं। डॉक्टर के पास जाने में देर न करें।",
    )
    val notAnswered = tx("not answered", "जवाब नहीं दिया")
    val farRange = tx(
        "Your approximate power: between %s and %s D (read up to %d cm; not a final glasses number, confirm with a trial lens)",
        "आपका अनुमानित चश्मे का नंबर: %s और %s D के बीच (%d cm तक पढ़ा; पक्का नंबर नहीं, ट्रायल लेंस से पक्का करें)",
    )
    val nearAddRange = tx(
        "Near point %d cm: reading add about %s to %s (approximate, confirm with a ready-reader)",
        "पास का बिंदु %d cm: पढ़ने के लिए लगभग %s से %s (अनुमान, रेडी-रीडर से पक्का करें)",
    )
    val urgentTop = tx(
        "Some of your answers can be signs of an eye problem that needs a doctor soon. Please see an eye doctor today.",
        "आपके कुछ जवाब आँख की ऐसी समस्या के संकेत हो सकते हैं जिसमें जल्दी डॉक्टर की ज़रूरत होती है। कृपया आज ही आँखों के डॉक्टर को दिखाएँ।",
    )
    val phoneCheckNote = tx(
        "This is a quick phone check, not a full eye examination. We recommend a complete eye check-up with an optometrist.",
        "यह फ़ोन से की गई एक छोटी जाँच है, आँखों की पूरी जाँच नहीं। हम ऑप्टोमेट्रिस्ट से आँखों की पूरी जाँच कराने की सलाह देते हैं।",
    )
    val knownPowerLine = tx("Known power (from the customer)", "पहले से पता नंबर (ग्राहक से)")
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
    val notCaught = tx("Distance not caught. Keep your face in view and tap again.", "दूरी नहीं पकड़ी गई। चेहरा कैमरे के सामने रखें और फिर से दबाएँ।")
    val measuring = tx("Measuring distance", "दूरी नापी जा रही है")
    val noFaceDot = tx("Face not found", "चेहरा नहीं दिख रहा")
    val typeDistance = tx("Staff: type the distance from a tape", "स्टाफ़: फ़ीते से नापी दूरी लिखें")
    val distanceCmLabel = tx("Distance in cm", "दूरी cm में")
    val useDistance = tx("Use", "यह दूरी लें")
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
