package com.drishtisaathi.i18n

import com.drishtisaathi.engine.profile.Freq
import com.drishtisaathi.engine.profile.RedFlag
import com.drishtisaathi.engine.profile.ReportTag

/** Text for the vision-profile branch: safety rules, survey, lens-assisted mode, screening tests, profile, lens cards. */
object SProfile {
    // ---- Phase A: safety ----
    val seeDoctorToday = tx("Please see an eye doctor today", "कृपया आज ही आँख के डॉक्टर को दिखाएँ")
    val seeDoctorWhy = tx("Because you told us about:", "क्योंकि आपने यह बताया:")
    val bannerContinue = tx("Continue the check", "जाँच जारी रखें")
    val bannerNote = tx(
        "We will finish a short check and send the report to the optometrist marked urgent. No lenses are suggested today.",
        "हम छोटी जाँच पूरी करके रिपोर्ट ऑप्टोमेट्रिस्ट को 'ज़रूरी' लिखकर भेजेंगे। आज कोई लेंस नहीं सुझाया जाएगा।",
    )
    val shortPathNote = tx(
        "Short check only: each eye's power and the grid, about 2 minutes.",
        "सिर्फ़ छोटी जाँच: हर आँख का नंबर और ग्रिड, लगभग 2 मिनट।",
    )
    val forOptometristOnly = tx("For the optometrist only", "सिर्फ़ ऑप्टोमेट्रिस्ट के लिए")
    val noLensToday = tx(
        "No lens is suggested today. The eye doctor visit comes first.",
        "आज कोई लेंस नहीं सुझाया गया है। पहले आँख के डॉक्टर को दिखाएँ।",
    )
    val childMyopiaTitle = tx(
        "Get your child checked by an optometrist (myopia control)",
        "अपने बच्चे की जाँच ऑप्टोमेट्रिस्ट से कराएँ (मायोपिया कंट्रोल)",
    )
    val childMyopiaBody = tx(
        "Your child's eyes look short-sighted. The power was measured for the optometrist; no lens is chosen here. An optometrist can check whether lenses that slow down myopia are needed.",
        "आपके बच्चे की दूर की नज़र कमज़ोर लगती है। नंबर ऑप्टोमेट्रिस्ट के लिए नापा गया है; यहाँ कोई लेंस नहीं चुना जाता। ऑप्टोमेट्रिस्ट देखेंगे कि नज़र बढ़ने से रोकने वाले लेंस चाहिए या नहीं।",
    )
    val optometristConfirms = tx("Optometrist confirms", "ऑप्टोमेट्रिस्ट पक्का करेंगे")
    val referralCard = tx("Referral", "रेफ़रल")
    val reportTagLine = tx("Report for the optometrist: %s", "ऑप्टोमेट्रिस्ट की रिपोर्ट: %s")
    private val TAG = mapOf(
        ReportTag.URGENT to tx("URGENT", "ज़रूरी (URGENT)"),
        ReportTag.CHILD_MYOPIA to tx("CHILD MYOPIA", "बच्चे का मायोपिया"),
        ReportTag.ROUTINE to tx("ROUTINE", "सामान्य"),
    )
    fun tag(t: ReportTag): Txt = TAG.getValue(t)

    private val FLAG = mapOf(
        RedFlag.SUDDEN_VISION_LOSS to tx("Sudden loss of vision", "नज़र अचानक कम होना"),
        RedFlag.EYE_PAIN_REDNESS to tx("Eye pain or redness", "आँख में दर्द या लाली"),
        RedFlag.NEW_FLASHES to tx("New flashes of light", "रोशनी की नई चमक"),
        RedFlag.NEW_FLOATERS to tx("New floating spots", "नए तैरते धब्बे"),
        RedFlag.CURTAIN_SHADOW to tx("A curtain or shadow over the vision", "नज़र के आगे पर्दा या परछाईं"),
        RedFlag.DOUBLE_VISION to tx("Double vision", "दो-दो दिखना"),
        RedFlag.AMSLER_DISTORTION to tx("Wavy, bent or missing lines (grid)", "लकीरें टेढ़ी, लहरदार या ग़ायब (ग्रिड)"),
        RedFlag.FLASHES_FLOATERS_CURTAIN_UNSPECIFIED to tx("Flashes, floating spots or a curtain over the vision", "रोशनी की चमक, तैरते धब्बे या नज़र के आगे पर्दा"),
        RedFlag.SUDDEN_LOSS_PAIN_DOUBLE_UNSPECIFIED to tx(
            "Sudden drop in vision, eye pain or redness, or double vision", "अचानक नज़र कम होना, आँख में दर्द-लाली, या दो-दो दिखना"),
    )
    fun redFlag(f: RedFlag): Txt = FLAG.getValue(f)

    // ---- Phase B: lens-assisted mode ----
    val lensModeTitle = tx("Check through a lens", "लेंस के साथ जाँच")
    val lensModeWhy = tx(
        "This eye reads only very close. That close, 1 cm changes the result a lot, so the check is repeated through a known lens.",
        "यह आँख बहुत पास से ही पढ़ पाती है। इतने पास 1 cm से भी नतीजा बहुत बदलता है, इसलिए जाँच एक जाने हुए लेंस के साथ दोबारा होगी।",
    )
    val lensSuggest = tx(
        "Staff: hold a %s D lens close in front of this eye (a trial lens, or the customer's old glasses).",
        "स्टाफ़: इस आँख के ठीक सामने %s D का लेंस पकड़ें (ट्रायल लेंस, या ग्राहक का पुराना चश्मा)।",
    )
    val lensEnter = tx("Lens held in front of the eye (D)", "आँख के सामने पकड़ा गया लेंस (D)")
    val lensFirstPass = tx("First reading without a lens: about %s D", "बिना लेंस की पहली रीडिंग: लगभग %s D")
    val lensStart = tx("Start the check through the lens", "लेंस के साथ जाँच शुरू करें")
    val lensNone = tx("No lens here: keep the first reading", "लेंस नहीं है: पहली रीडिंग ही रखें")
    val lensInvalid = tx("Enter a minus lens in steps of 0.25 D", "0.25 D के क़दमों में माइनस लेंस भरें")
    val lensTooStrong = tx(
        "The letters stayed sharp at every distance, so this lens is too strong. Use a weaker lens.",
        "अक्षर हर दूरी पर साफ़ रहे, यानी यह लेंस ज़्यादा तेज़ है। कमज़ोर लेंस लें।",
    )
    val lensInstruction = tx(
        "Staff hold the %s D lens close in front of the eye. Start close, move the phone slowly away, tap when the letters blur.",
        "स्टाफ़ %s D का लेंस आँख के ठीक सामने पकड़ें। पास से शुरू करें, फ़ोन धीरे-धीरे दूर ले जाएँ, अक्षर धुंधले हों तो दबाएँ।",
    )
    val methodLens = tx(
        "Measured through %s D lens, residual %s D, total %s D (range %s to %s)",
        "%s D के लेंस के साथ नापा, बचा हुआ %s D, कुल %s D (रेंज %s से %s)",
    )
    val methodLensShort = tx("through %s D lens", "%s D लेंस के साथ")
    val methodUnaidedShort = tx("without a lens", "बिना लेंस")
    val highMyopiaNote = tx(
        "High myopia – optometrist must confirm; eye-health check advised",
        "बहुत ज़्यादा माइनस नंबर – ऑप्टोमेट्रिस्ट पक्का करें; आँखों की सेहत की जाँच की सलाह",
    )
    val padDelete = tx("Delete", "मिटाएँ")

    // ---- report queue / bridge chip ----
    val bridgeConnected = tx("Connected", "जुड़ा है")
    val bridgeOffline = tx("Offline – queued %d", "ऑफ़लाइन – %d कतार में")
    val bridgeOfflineNone = tx("Offline", "ऑफ़लाइन")
    val reportQueued = tx("Report queued: it is sent to the optometrist as soon as the server connects.", "रिपोर्ट कतार में है: सर्वर जुड़ते ही ऑप्टोमेट्रिस्ट को भेज दी जाएगी।")
    val reportSendNow = tx("Send to optometrist", "ऑप्टोमेट्रिस्ट को भेजें")
    val consentReport = tx(
        "The report of this check (answers and measurements; no name, no phone number, no photo) is always sent to the shop's optometrist for review.",
        "इस जाँच की रिपोर्ट (जवाब और नाप; कोई नाम, फ़ोन नंबर या फ़ोटो नहीं) हमेशा दुकान के ऑप्टोमेट्रिस्ट को देखने के लिए भेजी जाती है।",
    )
    val consentReportH = tx("Report to the optometrist", "ऑप्टोमेट्रिस्ट को रिपोर्ट")

    // ---- survey ----
    val whyWeAsk = tx("Why we ask?", "यह क्यों पूछ रहे हैं?")
    val stepOf = tx("Step %d of %d", "चरण %d / %d")
    val chooseAll = tx("Choose all that apply, then Next", "जो-जो लागू हो चुनें, फिर 'आगे' दबाएँ")
    val noneOfThese = tx("None of these", "इनमें से कोई नहीं")
    val surveyTitle = tx("Your vision profile", "आपकी विज़न प्रोफ़ाइल")
    val listening = tx("Listening…", "सुन रहा हूँ…")
    val micOn = tx("Answer by voice", "बोलकर जवाब दें")
    val micOff = tx("Voice off", "आवाज़ बंद")
    private val FREQ = mapOf(
        Freq.NEVER to tx("Never", "कभी नहीं"), Freq.SOMETIMES to tx("Sometimes", "कभी-कभी"), Freq.OFTEN to tx("Often", "अक्सर"),
    )
    fun freq(f: Freq): Txt = FREQ.getValue(f)
    /** Spoken keywords for the fixed options (never before no: "कभी नहीं" contains "नहीं"). */
    val sayFreq = mapOf(
        Freq.NEVER to listOf("कभी नहीं", "never", "kabhi nahi", "बिल्कुल नहीं"),
        Freq.SOMETIMES to listOf("कभी-कभी", "कभी कभी", "sometimes", "kabhi kabhi"),
        Freq.OFTEN to listOf("अक्सर", "often", "aksar", "हमेशा", "रोज़"),
    )
    val sayYes = listOf("हाँ", "हां", "जी हाँ", "yes", "haan", "ha")
    val sayNo = listOf("नहीं", "no", "nahi", "nahin")
    val sayNext = listOf("आगे", "next", "aage")

    /** Every fixed string (for tests). */
    fun touchAll() {
        ReportTag.entries.forEach(::tag); RedFlag.entries.forEach(::redFlag); Freq.entries.forEach(::freq)
    }
}
