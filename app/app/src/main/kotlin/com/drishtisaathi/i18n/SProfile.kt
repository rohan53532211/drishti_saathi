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

    // ---- Phase C: screening tests ----
    /** The label the screening results carry everywhere (the one place this word is allowed, like the disclaimer). */
    val screeningLabel = tx("Screening, not a diagnosis", "यह स्क्रीनिंग है, निदान नहीं")
    val screeningTitle = tx("Screening tests", "स्क्रीनिंग जाँच")
    val setupTitle = tx("Before the screening tests", "स्क्रीनिंग जाँच से पहले")
    val setupBody = tx(
        "The screen is now at full brightness. Staff: please turn off Night light / Eye comfort mode in the phone's quick settings, so colours and contrast are shown correctly. One eye is tested at a time.",
        "स्क्रीन की चमक अब पूरी है। स्टाफ़: फ़ोन की क्विक सेटिंग में नाइट लाइट / आई कम्फ़र्ट मोड बंद कर दें, ताकि रंग और कंट्रास्ट सही दिखें। एक बार में एक आँख की जाँच होगी।",
    )
    val setupStart = tx("Night light is off: start", "नाइट लाइट बंद है: शुरू करें")
    val skipTest = tx("Skip this test", "यह जाँच छोड़ें")
    val distanceCm = tx("%d cm", "%d cm")
    val holdRange = tx("Hold at %d–%d cm", "%d–%d cm पर रखें")
    val noFace = tx("Face not seen", "चेहरा नहीं दिखा")
    val coverEye = tx("Cover your %s eye with your palm.", "अपनी %s आँख हथेली से ढकें।")
    val amslerTitle = tx("Grid test", "ग्रिड जाँच")
    val amslerHold = tx(
        "Hold the phone at 30 cm and keep looking at the dot in the middle.",
        "फ़ोन 30 cm पर रखें और बीच के बिंदु को देखते रहें।",
    )
    val amslerQuestion = tx("Are any lines wavy, missing, blurred or broken?", "क्या कोई लकीर टेढ़ी, ग़ायब, धुंधली या टूटी दिखती है?")
    val amslerNo = tx("No, all lines are straight", "नहीं, सब लकीरें सीधी हैं")
    val amslerYes = tx("Yes, some lines look wrong", "हाँ, कुछ लकीरें ठीक नहीं दिखतीं")
    val amslerTap = tx("Tap the places on the grid where the lines look wrong, then Done.", "ग्रिड पर वहाँ दबाएँ जहाँ लकीरें ठीक नहीं दिखतीं, फिर 'हो गया' दबाएँ।")
    val done = tx("Done", "हो गया")
    val amslerNarrow = tx("Grid shown: %d × %d squares of 5 mm (this screen is narrower than 10 cm)", "ग्रिड: 5 mm के %d × %d खाने (यह स्क्रीन 10 cm से कम चौड़ी है)")
    val amslerTooSmall = tx("This screen is too small for the grid test.", "ग्रिड जाँच के लिए यह स्क्रीन बहुत छोटी है।")
    val contrastTitle = tx("Contrast test", "कंट्रास्ट जाँच")
    val contrastInstruction = tx("Which letter do you see? It gets fainter each time.", "कौन-सा अक्षर दिख रहा है? हर बार यह हल्का होता जाएगा।")
    val cantSee = tx("I can't see a letter", "कोई अक्षर नहीं दिखता")
    val colourTitle = tx("Colour test", "रंग जाँच")
    val colourInstruction = tx("Keep both eyes open. Which number do you see in the dots?", "दोनों आँखें खुली रखें। बिंदुओं में कौन-सा अंक दिखता है?")
    val noNumber = tx("I can't see a number", "कोई अंक नहीं दिखता")
    val plateOf = tx("Plate %d of %d", "प्लेट %d / %d")
    val classicTitle = tx("E test and clock dial", "E जाँच और घड़ी डायल")
    /** Spoken digits 0–9 (Hindi, Hinglish, English). */
    val sayDigits = listOf(
        listOf("शून्य", "zero", "shunya", "0"), listOf("एक", "one", "ek", "1"), listOf("दो", "two", "do", "2"), listOf("तीन", "three", "teen", "3"),
        listOf("चार", "four", "chaar", "char", "4"), listOf("पाँच", "पांच", "five", "paanch", "5"), listOf("छह", "छः", "six", "chhah", "6"),
        listOf("सात", "seven", "saat", "7"), listOf("आठ", "eight", "aath", "8"), listOf("नौ", "nine", "nau", "9"),
    )
    // results
    val resGrid = tx("Grid", "ग्रिड")
    val resGridOk = tx("lines straight", "लकीरें सीधी")
    val resGridBad = tx("wavy or missing lines reported (%d square(s) marked)", "टेढ़ी या ग़ायब लकीरें बताईं (%d खाने चिह्नित)")
    val resContrast = tx("Contrast", "कंट्रास्ट")
    val resContrastStep = tx("lowest step seen %s%% – %s", "सबसे हल्का दिखा %s%% – %s")
    val resContrastNone = tx("no step seen – %s", "कोई स्तर नहीं दिखा – %s")
    val normal = tx("Normal", "सामान्य")
    val reduced = tx("Reduced", "कम")
    val eyeHealthNote = tx("Suggest cataract / eye-health check", "मोतियाबिंद / आँखों की सेहत की जाँच की सलाह")
    val resColour = tx("Colour", "रंग")
    val colourNormal = tx("Normal", "सामान्य")
    val colourRedGreen = tx("Possible red-green deficiency – confirm with optometrist", "लाल-हरे रंग की कमी हो सकती है – ऑप्टोमेट्रिस्ट से पक्का करें")
    val colourBlueYellow = tx("Possible blue-yellow deficiency – confirm with optometrist", "नीले-पीले रंग की कमी हो सकती है – ऑप्टोमेट्रिस्ट से पक्का करें")
    val colourBoth = tx("Possible red-green and blue-yellow deficiency – confirm with optometrist", "लाल-हरे और नीले-पीले रंग की कमी हो सकती है – ऑप्टोमेट्रिस्ट से पक्का करें")
    val colourInvalid = tx("Test not valid, repeat", "जाँच मान्य नहीं, दोबारा करें")
    val colourNeeds = tx("Ask about colour needs for driving licence/work", "ड्राइविंग लाइसेंस / काम के लिए रंग की ज़रूरत के बारे में पूछें")
    val resETest = tx("E test (near)", "E जाँच (पास)")
    val resETestLine = tx("about logMAR %s%s", "लगभग logMAR %s%s")
    val resBelowLimit = tx(" – below the safe limit", " – सुरक्षित सीमा से कम")
    val resDial = tx("Clock dial", "घड़ी डायल")
    val resDialOk = tx("all lines equally dark", "सब लकीरें बराबर गहरी")
    val resDialFlag = tx("some lines darker: possible astigmatism, axis about %d°", "कुछ लकीरें ज़्यादा गहरी: एस्टिग्मैटिज़्म हो सकता है, एक्सिस लगभग %d°")
    val resSkipped = tx("not done", "नहीं हुई")
    val eyeR = tx("R", "दाईं")
    val eyeL = tx("L", "बाईं")

    // ---- Phase E: vision profile rings and lens cards ----
    val profileTitle = tx("Your Vision Profile", "आपकी विज़न प्रोफ़ाइल")
    val ringWork = tx("Work", "काम")
    val ringDigital = tx("Digital", "डिजिटल")
    val ringMobility = tx("Mobility", "सफ़र")
    val ringLeisure = tx("Leisure", "फ़ुर्सत")
    val ringNotAnswered = tx("not answered", "जवाब नहीं दिया")
    val nightGlare = tx("Night-glare sensitive", "रात की चौंध से परेशानी")
    private val RING_LABEL = mapOf(
        "NEAR_FOCUSED" to tx("Near-focused", "पास का काम ज़्यादा"), "BALANCED" to tx("Balanced", "संतुलित"),
        "DISTANCE_FOCUSED" to tx("Distance-focused", "दूर का काम ज़्यादा"),
        "LIGHT" to tx("Light", "हल्का इस्तेमाल"), "CONNECTED" to tx("Connected", "जुड़े हुए"), "DIGITAL_NATIVE" to tx("Digital Native", "डिजिटल नेटिव"),
        "RARELY" to tx("Rarely", "कभी-कभार"), "WEEKLY" to tx("Weekly", "हर हफ़्ते"), "DAILY" to tx("Daily", "रोज़"),
        "LOW" to tx("Low Sun & UV Exposure", "धूप और UV कम"), "MEDIUM" to tx("Medium Sun & UV Exposure", "धूप और UV मध्यम"),
        "HIGH" to tx("High Sun & UV Exposure", "धूप और UV ज़्यादा"),
    )
    fun ringLabel(code: String): Txt = RING_LABEL.getValue(code)
    val lensLabel = tx("Suggested ZEISS lens family – optician confirms", "सुझाया गया ZEISS लेंस परिवार – ऑप्टिशियन पक्का करेंगे")
    val lensMain = tx("Your lens", "आपका लेंस")
    val lensSecond = tx("A second pair", "दूसरा चश्मा")
    val whyForYou = tx("Why for you", "आपके लिए क्यों")
    val lensDesign = tx("Design", "डिज़ाइन")
    val lensMaterial = tx("In the lens", "लेंस में")
    val lensCoating = tx("Coating", "कोटिंग")
    val lensMore = tx("More on zeiss.co.in", "zeiss.co.in पर और जानें")
    val measurementsTitle = tx("Measurements", "नाप")
    val wordFar = tx("far", "दूर")
    val wordNear = tx("near", "पास")
    val wordTired = tx("tired eyes", "आँखों की थकान")
    val wordHeadache = tx("headaches", "सिरदर्द")
    val wordNeck = tx("neck pain", "गर्दन दर्द")
    val wordStrain = tx("eye strain", "आँखों पर ज़ोर")
    private val BUDGET = mapOf("LOW" to tx("low", "कम"), "MID" to tx("medium", "मध्यम"), "HIGH" to tx("high", "ज़्यादा"))
    private val DRIVE = mapOf("DAILY" to tx("every day", "रोज़"), "WEEKLY" to tx("every week", "हर हफ़्ते"), "MONTHLY" to tx("now and then", "कभी-कभी"), "NEVER" to tx("never", "कभी नहीं"))
    private val REASON = mapOf(
        "BUDGET" to tx("Fits your budget (%s)", "आपके बजट (%s) में"),
        "AGE_NEAR_FOCUS" to tx("At %s, focusing up close starts to need help", "%s की उम्र में पास देखने के लिए मदद की ज़रूरत पड़ने लगती है"),
        "CHILD" to tx("Made for children's eyes and frames", "बच्चों की आँखों और फ़्रेम के लिए बना"),
        "DRIVES" to tx("You drive %s", "आप %s गाड़ी चलाते हैं"),
        "GLARE" to tx("Oncoming headlights dazzle you", "सामने की लाइट से आपकी आँखें चौंधियाती हैं"),
        "LOW_LIGHT" to tx("Seeing in low light is hard for you", "कम रोशनी में आपको देखने में दिक्कत होती है"),
        "BUSY_WORK" to tx("Your work is visually busy", "आपके काम में आँखों पर बहुत ज़ोर पड़ता है"),
        "FOCUS" to tx("You struggle to refocus", "नज़र बदलने पर साफ़ देखने में आपको दिक्कत होती है"),
        "FIRST_PROGRESSIVE" to tx("Your first lens with far and near in one: easy to get used to", "दूर और पास वाला आपका पहला लेंस: आदत जल्दी पड़ती है"),
        "DIGITAL" to tx("You use screens %s h a day", "आप रोज़ %s घंटे स्क्रीन देखते हैं"),
        "DRIVESAFE_DESIGN" to tx("Goes with the DriveSafe lens", "DriveSafe लेंस के साथ आती है"),
        "PUBLIC_FACING" to tx("You deal with people all day", "आप दिन भर लोगों से मिलते हैं"),
        "DRIVES_DAY" to tx("You drive in daylight", "आप दिन में गाड़ी चलाते हैं"),
        "WATER" to tx("You are often near water", "आप अक्सर पानी के पास रहते हैं"),
        "OUTDOOR_SPORT" to tx("You do outdoor sport", "आप बाहर खेलते हैं"),
        "FIELD_WORK" to tx("You work in the fields", "आप खेत में काम करते हैं"),
        "OUTDOOR_HOURS" to tx("You are outdoors %s h a day", "आप रोज़ %s घंटे बाहर रहते हैं"),
    )
    /** A lens-engine reason code with its numbers, in words. */
    fun reason(code: String, args: List<String>): Txt {
        val t = REASON.getValue(code)
        val a = args.firstOrNull() ?: return t
        val word = when (code) { "BUDGET" -> BUDGET[a]; "DRIVES" -> DRIVE[a]; else -> null }
        return Txt(t.en.format(word?.en ?: a), t.hi.format(word?.hi ?: a))
    }
    val reasonCodes: Set<String> get() = REASON.keys

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
        reasonCodes.forEach { reason(it, listOf("LOW")) }
    }
}
