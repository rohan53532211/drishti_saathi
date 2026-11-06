package com.drishtisaathi.i18n

import com.drishtisaathi.engine.Budget
import com.drishtisaathi.engine.Coating
import com.drishtisaathi.engine.Confidence
import com.drishtisaathi.engine.FitVerdict
import com.drishtisaathi.engine.LensType
import com.drishtisaathi.engine.Note
import com.drishtisaathi.engine.Occupation
import com.drishtisaathi.engine.ReferralLevel
import com.drishtisaathi.engine.ReferralReason
import com.drishtisaathi.engine.ScreenHours
import com.drishtisaathi.engine.Source
import com.drishtisaathi.engine.StylePref
import java.util.Locale
import kotlin.math.abs

enum class Lang { HI, EN }

/** One piece of user-facing text in both languages. */
data class Txt(val en: String, val hi: String) {
    operator fun get(lang: Lang): String = if (lang == Lang.HI) hi else en
    fun format(vararg args: Any?): Txt = Txt(en.format(Locale.US, *args), hi.format(Locale.US, *args))
    /** "हिंदी / English", used where both languages must appear together. */
    val both: String get() = "$hi / $en"
}

/**
 * Every user-facing string in the app (Section 6 wording rules apply to all of it).
 * Say "estimated band", "starting trial lens". Never "prescription", "diagnosis", "power is".
 * StringsWordingTest scans this whole table. Hindi: have a native speaker proofread before demo.
 */
object S {
    /** Registry for the wording test. Must stay the first property so it exists before the others. */
    val all = mutableListOf<Txt>()
    private fun t(en: String, hi: String) = Txt(en, hi).also { all += it }

    // ---- Mandatory disclaimer (printed on every summary, always both languages) -------------
    val disclaimer = t("Not a medical diagnosis or prescription.", "यह चिकित्सीय निदान या पर्चा नहीं है")
    val disclaimerBoth: String get() = "${disclaimer.hi} / ${disclaimer.en}"

    // ---- General ---------------------------------------------------------------------------
    val appName = t("Drishti Saathi", "दृष्टि साथी")
    val tagline = t("Guided pre-consultation for your optical shop", "आपकी ऑप्टिकल दुकान के लिए जाँच से पहले की मदद")
    val startNew = t("Start new customer", "नया ग्राहक शुरू करें")
    val staffMode = t("Staff mode", "स्टाफ़ मोड")
    val history = t("Past sessions", "पिछले सेशन")
    val noHistory = t("No saved sessions yet.", "अभी कोई सेव किया सेशन नहीं है।")
    val next = t("Next", "आगे")
    val back = t("Back", "पीछे")
    val skip = t("Skip", "छोड़ें")
    val dontKnow = t("Don't know", "पता नहीं")
    val yes = t("Yes", "हाँ")
    val no = t("No", "नहीं")
    val questionOf = t("Question %d of %d", "प्रश्न %d / %d")
    val ageHint = t("Age in years", "उम्र (साल में)")
    val yearsHint = t("Years", "साल")
    val invalidNumber = t("Please enter a valid number", "कृपया सही संख्या लिखें")
    val privacyNote = t(
        "No name or photo is stored. Only answers and measurements stay on this phone.",
        "कोई नाम या फ़ोटो सेव नहीं होती। सिर्फ़ जवाब और माप इसी फ़ोन में रहते हैं।",
    )
    val phase1Note = t(
        "Camera and on-screen eye tests come in the next version. Today: interview and old glasses numbers.",
        "कैमरा और स्क्रीन पर आँखों के टेस्ट अगले संस्करण में आएँगे। अभी: इंटरव्यू और पुराने चश्मे के नंबर।",
    )

    // ---- Consent (addition 1) -----------------------------------------------------------------
    val consentTitle = t("Before we start", "शुरू करने से पहले")
    val consentWhatH = t("What we collect", "हम क्या लेते हैं")
    val consentWhat = t(
        "Your answers to a few questions, your age, and any old glasses numbers. No name, no phone number, no photo.",
        "कुछ सवालों के जवाब, आपकी उम्र, और पुराने चश्मे के नंबर (अगर हों)। कोई नाम, फ़ोन नंबर या फ़ोटो नहीं।",
    )
    val consentWhyH = t("Why", "क्यों")
    val consentWhy = t(
        "To help the shop choose a starting trial lens, a lens type and frames for you. The shop still does its own trial-lens test.",
        "ताकि दुकान आपके लिए शुरुआती ट्रायल लेंस, लेंस का प्रकार और फ़्रेम चुन सके। दुकान फिर भी अपना ट्रायल-लेंस टेस्ट करेगी।",
    )
    val consentWhereH = t("Where it stays", "जानकारी कहाँ रहती है")
    val consentWhere = t(
        "Only on this phone. Nothing is uploaded.",
        "सिर्फ़ इसी फ़ोन में। कुछ भी अपलोड नहीं होता।",
    )
    val consentDeleteH = t("Deleting", "मिटाना")
    val consentDelete = t(
        "You can ask the shop to delete this session at any time.",
        "आप कभी भी दुकान से यह सेशन मिटाने को कह सकते हैं।",
    )
    val consentContinue = t("I agree, continue", "मैं सहमत हूँ, आगे बढ़ें")
    val consentCancel = t("Cancel", "रद्द करें")
    val consentGiven = t("Consent given", "सहमति दी गई")

    // ---- M1 interview questions --------------------------------------------------------------
    val qAge = t("How old are you?", "आपकी उम्र क्या है?")
    val qWearsGlasses = t("Do you wear glasses now?", "क्या आप अभी चश्मा पहनते हैं?")
    val qGlassesAge = t("How many years old are your glasses?", "चश्मा कितने साल पुराना है?")
    /** The brief's own Hindi wording; refers to the customer's existing slip, so it is whitelisted. */
    val qKnownRx = t("Do you have your old glasses number or eye-test slip?", "क्या आपके पास पुराना पर्चा/नंबर है?")
    val qDistanceBlur = t("Do far-away things look blurry?", "क्या दूर की चीज़ें धुंधली दिखती हैं?")
    val qNearBlur = t("Is it hard to see near things or read your mobile?", "क्या पास की चीज़ें या मोबाइल पढ़ने में दिक्कत होती है?")
    val qScreenHours = t("How many hours a day do you look at a mobile or computer?", "आप रोज़ कितने घंटे मोबाइल या कंप्यूटर देखते हैं?")
    val qNightDriving = t("Do you drive at night?", "क्या आप रात में गाड़ी चलाते हैं?")
    val qOutdoorWork = t("Do you mostly work outside in the sun?", "क्या आप ज़्यादातर बाहर धूप में काम करते हैं?")
    val qOccupation = t("What is your work?", "आपका काम क्या है?")
    val qHeadache = t("Do you get headaches or tired eyes?", "क्या सिरदर्द या आँखों में थकान होती है?")
    val qDiabetesBp = t("Do you have sugar (diabetes) or BP?", "क्या आपको शुगर या बीपी है?")
    val qRedFlagFlashes = t(
        "Have you suddenly seen flashes of light, floating spots, or a curtain in front of the eye?",
        "क्या अचानक रोशनी की चमक, तैरते धब्बे या आँख के आगे पर्दा जैसा दिखा है?",
    )
    val qRedFlagOther = t(
        "Has your vision suddenly dropped, or do you have eye pain or redness, rings around lights with pain, or double vision?",
        "क्या अचानक नज़र कम हुई, आँख में दर्द-लाली, दर्द के साथ रोशनी के चारों ओर घेरे, या दो-दो दिखता है?",
    )
    val qBudget = t("What is your budget for glasses?", "चश्मे के लिए आपका बजट कितना है?")
    val qStyle = t("What style do you like?", "आपको कौन सा स्टाइल पसंद है?")

    // ---- Old glasses numbers entry -----------------------------------------------------------
    val oldNumbersTitle = t("Old glasses numbers", "पुराने चश्मे के नंबर")
    val oldNumbersHint = t(
        "Copy from the slip or lensometer. Leave CYL and ADD at 0 if not written.",
        "पर्ची या लेंसोमीटर से भरें। CYL और ADD न लिखे हों तो 0 छोड़ दें।",
    )
    val rightEye = t("Right eye (R)", "दायीं आँख (R)")
    val leftEye = t("Left eye (L)", "बायीं आँख (L)")
    val sameAsRight = t("Copy right eye to left", "दायीं आँख वाले नंबर बायीं में भरें")
    val noNumbers = t("No numbers available", "नंबर नहीं हैं")
    val saveNumbers = t("Save numbers", "नंबर सेव करें")
    val oldNumbersLabel = t("Old glasses numbers", "पुराने चश्मे के नंबर")

    // ---- Confirmation ------------------------------------------------------------------------
    val confirmTitle = t("Check the answers", "जवाब जाँच लें")
    val confirmHint = t("Tap any answer to change it.", "बदलने के लिए किसी भी जवाब पर टैप करें।")
    val notAnswered = t("Not answered", "जवाब नहीं")
    val staffMeasurements = t("Staff measurements (optional)", "स्टाफ़ माप (वैकल्पिक)")
    val pdLabel = t("PD in mm (PD ruler)", "PD mm में (PD रूलर से)")
    val faceWidthLabel = t("Face width in mm (cheekbone to cheekbone)", "चेहरे की चौड़ाई mm में (गाल की हड्डी से हड्डी)")
    val seeResults = t("See results", "नतीजे देखें")
    val answerAllFirst = t("Please answer all questions first.", "पहले सभी प्रश्नों के जवाब दें।")

    // ---- Summary -----------------------------------------------------------------------------
    val shopSummary = t("Shop summary", "दुकान सारांश")
    val customerCopy = t("Customer copy", "ग्राहक कॉपी")
    val profile = t("Profile", "प्रोफ़ाइल")
    val referral = t("Referral", "रेफ़रल")
    val estimatedBand = t("Estimated band (SE)", "अनुमानित रेंज (SE)")
    val startingTrialLens = t("Starting trial lens", "शुरुआती ट्रायल लेंस")
    val startingLensHint = t(
        "A starting point for the shop's own trial-lens test. Staff must refine it by hand.",
        "यह दुकान के ट्रायल-लेंस टेस्ट के लिए सिर्फ़ शुरुआती बिंदु है। स्टाफ़ इसे हाथ से जाँचकर तय करें।",
    )
    val noStartingLens = t("No starting band yet: start the hand test from plano.", "अभी कोई शुरुआती रेंज नहीं: हाथ वाला टेस्ट प्लेनो से शुरू करें।")
    val readingAdd = t("Reading add (estimated)", "पढ़ने का ऐड (अनुमानित)")
    val lensPackage = t("Lens package", "लेंस पैकेज")
    val lensType = t("Lens type", "लेंस का प्रकार")
    val material = t("Material", "मटेरियल")
    val coatings = t("Coatings", "कोटिंग")
    val noCoatings = t("None needed from answers", "जवाबों के हिसाब से ज़रूरी नहीं")
    val suggestedFrames = t("Suggested frames", "सुझाए गए फ़्रेम")
    val notesForStaff = t("Notes for staff", "स्टाफ़ के लिए नोट")
    val adviceWithheld = t("Lens advice withheld: referral only.", "लेंस सलाह नहीं दी जा रही: केवल रेफ़रल।")
    val right = t("Right", "दायीं")
    val left = t("Left", "बायीं")
    val pd = t("PD", "PD")
    val faceWidth = t("Face width", "चेहरे की चौड़ाई")
    val age = t("Age", "उम्र")
    val years = t("%d years", "%d साल")
    val frameLine = t("%s · %s · ₹%,d", "%s · %s · ₹%,d")
    val thicknessUnknown = t("thickness shown after the trial-lens test", "ट्रायल-लेंस टेस्ट के बाद मोटाई दिखेगी")
    val edgeAbout = t("edge about %.1f mm in %s", "%2\$s में किनारा लगभग %1\$.1f mm")
    val centreAbout = t("centre about %.1f mm in %s", "%2\$s में बीच की मोटाई लगभग %1\$.1f mm")
    val overBudget = t("over budget", "बजट से ज़्यादा")
    val confidenceWord = t("confidence", "भरोसा")
    val bandRange = t("%s to %s D", "%s से %s D")
    val sessionNo = t("Session #%d", "सेशन #%d")
    val staffOnly = t("Staff details", "स्टाफ़ विवरण")
    val score = t("score", "स्कोर")

    // ---- Actions -----------------------------------------------------------------------------
    val exportShopPdf = t("Share shop PDF", "दुकान PDF शेयर करें")
    val exportCustomerPdf = t("Share customer copy (Hindi)", "ग्राहक कॉपी शेयर करें (हिंदी)")
    val deleteSession = t("Delete this session", "यह सेशन मिटाएँ")
    val deleteConfirm = t("Delete this session? This cannot be undone.", "यह सेशन मिटाएँ? यह वापस नहीं होगा।")
    val delete = t("Delete", "मिटाएँ")
    val cancel = t("Cancel", "रद्द करें")
    val sessionDeleted = t("Session deleted", "सेशन मिटा दिया गया")
    val done = t("Done, next customer", "पूरा, अगला ग्राहक")
    val pdfFailed = t("Could not create the PDF", "PDF नहीं बन पाई")

    // ---- Staff verification (learning loop) -------------------------------------------------
    val verification = t("Staff verification", "स्टाफ़ पुष्टि")
    val verificationHint = t(
        "After the hand test, record the final trial lens and the PD meter reading. This builds the accuracy record.",
        "हाथ वाले टेस्ट के बाद अंतिम ट्रायल लेंस और PD मीटर रीडिंग लिखें। इससे सटीकता का रिकॉर्ड बनता है।",
    )
    val finalTrialRight = t("Final trial lens, right (e.g. -1.75 / -0.50 x 180)", "अंतिम ट्रायल लेंस, दायीं (जैसे -1.75 / -0.50 x 180)")
    val finalTrialLeft = t("Final trial lens, left", "अंतिम ट्रायल लेंस, बायीं")
    val finalAdd = t("Final add (if any)", "अंतिम ऐड (अगर हो)")
    val pdMeter = t("PD meter reading (mm)", "PD मीटर रीडिंग (mm)")
    val saveVerification = t("Save verification", "पुष्टि सेव करें")
    val saved = t("Saved", "सेव हो गया")
    val verified = t("Verified", "पुष्टि हो चुकी")

    // ---- Referral screen ---------------------------------------------------------------------
    val urgentTitle = t("Please see an eye doctor today", "कृपया आज ही आँखों के डॉक्टर को दिखाएँ")
    val urgentBody = t(
        "Some answers can be signs of a serious eye problem. New glasses will not fix this. Please go to an eye hospital or eye doctor today.",
        "कुछ जवाब आँख की गंभीर समस्या के संकेत हो सकते हैं। नए चश्मे से यह ठीक नहीं होगा। कृपया आज ही आँखों के अस्पताल या डॉक्टर के पास जाएँ।",
    )
    val routineTitle = t("Please also visit an optometrist soon", "जल्द ही ऑप्टोमेट्रिस्ट को भी दिखाएँ")
    val routineBody = t(
        "The shop can help with glasses today, but some answers need a full eye check by an optometrist or eye doctor.",
        "दुकान आज चश्मे में मदद कर सकती है, पर कुछ जवाबों के लिए ऑप्टोमेट्रिस्ट या आँखों के डॉक्टर से पूरी जाँच ज़रूरी है।",
    )
    val reasons = t("Reasons", "कारण")
    val continueToSummary = t("Continue to summary", "सारांश देखें")

    // ---- Customer copy ---------------------------------------------------------------------
    val customerIntro = t(
        "Summary of your visit. The shop will confirm everything with its own trial-lens test.",
        "आपकी विज़िट का सारांश। दुकान अपने ट्रायल-लेंस टेस्ट से सब कुछ पक्का करेगी।",
    )
    val generatedOn = t("Created", "बनाया गया")

    // ---- Option labels -----------------------------------------------------------------------
    fun screenHours(v: ScreenHours) = when (v) {
        ScreenHours.H0_2 -> SCREEN_0_2
        ScreenHours.H2_4 -> SCREEN_2_4
        ScreenHours.H4_PLUS -> SCREEN_4_PLUS
    }
    private val SCREEN_0_2 = t("0–2 hours", "0–2 घंटे")
    private val SCREEN_2_4 = t("2–4 hours", "2–4 घंटे")
    private val SCREEN_4_PLUS = t("More than 4 hours", "4 घंटे से ज़्यादा")

    fun occupation(v: Occupation) = OCCUPATION.getValue(v)
    private val OCCUPATION = mapOf(
        Occupation.OFFICE_COMPUTER to t("Office / computer", "दफ़्तर / कंप्यूटर"),
        Occupation.SHOP_BUSINESS to t("Shop / business", "दुकान / व्यापार"),
        Occupation.FINE_HANDWORK to t("Fine handwork (tailoring, jewellery, repair)", "बारीक हाथ का काम (सिलाई, ज़ेवर, मरम्मत)"),
        Occupation.DRIVER to t("Driver", "ड्राइवर"),
        Occupation.FIELD_OUTDOOR to t("Farm / field / outdoor work", "खेती / मज़दूरी / बाहर का काम"),
        Occupation.HOMEMAKER to t("Homemaker", "गृहिणी / घर का काम"),
        Occupation.STUDENT to t("Student", "विद्यार्थी"),
        Occupation.SPORT_ACTIVE to t("Sports / very active work", "खेल / भाग-दौड़ वाला काम"),
        Occupation.OTHER to t("Other", "अन्य"),
    )

    /** Budget labels show the caps from rules.json. */
    fun budget(v: Budget, lowCap: Int, midCap: Int): Txt = when (v) {
        Budget.LOW -> BUDGET_LOW.format(lowCap)
        Budget.MID -> BUDGET_MID.format(lowCap, midCap)
        Budget.HIGH -> BUDGET_HIGH.format(midCap)
    }
    private val BUDGET_LOW = t("Low (up to ₹%,d)", "कम (₹%,d तक)")
    private val BUDGET_MID = t("Medium (₹%,d – ₹%,d)", "मध्यम (₹%,d – ₹%,d)")
    private val BUDGET_HIGH = t("Premium (above ₹%,d)", "प्रीमियम (₹%,d से ज़्यादा)")

    fun style(v: StylePref) = STYLE.getValue(v)
    private val STYLE = mapOf(
        StylePref.CLASSIC to t("Classic", "क्लासिक"),
        StylePref.MODERN to t("Modern", "मॉडर्न"),
        StylePref.BOLD to t("Bold / thick frame", "बोल्ड / मोटा फ़्रेम"),
        StylePref.MINIMAL to t("Light / simple", "हल्का / सादा"),
        StylePref.ANY to t("Anything, show me", "कोई भी, दिखाइए"),
    )

    // ---- Engine codes ------------------------------------------------------------------------
    fun referralLevel(v: ReferralLevel) = REFERRAL_LEVEL.getValue(v)
    private val REFERRAL_LEVEL = mapOf(
        ReferralLevel.URGENT to t("URGENT: see an eye doctor today", "तुरंत: आज ही आँखों के डॉक्टर को दिखाएँ"),
        ReferralLevel.ROUTINE to t("Routine: also visit an optometrist soon", "सामान्य: जल्द ही ऑप्टोमेट्रिस्ट को भी दिखाएँ"),
        ReferralLevel.NONE to t("No warning signs reported", "कोई ख़तरे का संकेत नहीं बताया गया"),
    )

    fun referralReason(v: ReferralReason, childAge: Int): Txt =
        if (v == ReferralReason.CHILD_UNDER_AGE) CHILD.format(childAge) else REFERRAL_REASON.getValue(v)
    private val CHILD = t("Child under %d years", "%d साल से कम उम्र का बच्चा")
    private val REFERRAL_REASON = mapOf(
        ReferralReason.FLASHES_FLOATERS_CURTAIN to t("New flashes, floaters or a curtain over vision", "नई रोशनी की चमक, तैरते धब्बे या नज़र के आगे पर्दा"),
        ReferralReason.SUDDEN_LOSS_PAIN_REDNESS_HALOS_DOUBLE to t(
            "Sudden vision loss, painful red eye, halos with pain, or new double vision",
            "अचानक नज़र कम होना, आँख में दर्द-लाली, दर्द के साथ घेरे, या दो-दो दिखना",
        ),
        ReferralReason.AMSLER_DISTORTION to t("Distortion or missing area on the grid test", "ग्रिड टेस्ट में टेढ़ी या गायब रेखाएँ"),
        ReferralReason.DIABETES_OR_BP_WITH_BLUR to t("Sugar or BP with blurred vision", "शुगर या बीपी के साथ धुंधलापन"),
        ReferralReason.NEAR_ACUITY_BELOW_THRESHOLD to t("Near vision test below the safe limit", "पास की नज़र का टेस्ट सुरक्षित सीमा से कम"),
        ReferralReason.LOW_CONFIDENCE_REPEATED to t("Test results were unreliable twice", "टेस्ट के नतीजे दो बार भरोसेमंद नहीं रहे"),
        ReferralReason.ASTIGMATISM_FLAG to t("Possible astigmatism (cylinder)", "सिलिंडर (एस्टिग्मैटिज़्म) की संभावना"),
        ReferralReason.DISTANCE_BLUR_NO_MYOPIA_FOUND to t(
            "Distance blur but no short-sight found: long-sight possible",
            "दूर का धुंधला दिखता है पर निकट-दृष्टि नहीं मिली: दूर-दृष्टि हो सकती है",
        ),
        ReferralReason.CHILD_UNDER_AGE to CHILD,
    )

    fun lensType(v: LensType) = LENS_TYPE.getValue(v)
    private val LENS_TYPE = mapOf(
        LensType.NONE_SUGGESTED to t("No lens suggested from the interview", "इंटरव्यू से कोई लेंस सुझाव नहीं"),
        LensType.SINGLE_VISION to t("Single vision", "सिंगल विज़न"),
        LensType.READING to t("Reading glasses", "पढ़ने का चश्मा"),
        LensType.PROGRESSIVE to t("Progressive", "प्रोग्रेसिव"),
        LensType.BIFOCAL_OR_SEPARATE_PAIRS to t("Bifocal, or separate distance and reading pairs", "बाइफ़ोकल, या दूर और पास के अलग-अलग चश्मे"),
    )

    fun coating(v: Coating) = COATING.getValue(v)
    private val COATING = mapOf(
        Coating.ANTI_REFLECTIVE to t("Anti-reflective coating", "एंटी-रिफ़्लेक्टिव कोटिंग"),
        Coating.BLUE_FILTER_OPTIONAL to t("Blue filter (optional, for comfort)", "ब्लू फ़िल्टर (वैकल्पिक, आराम के लिए)"),
        Coating.PHOTOCHROMIC_OR_TINT to t("Photochromic or tinted lens", "फ़ोटोक्रोमिक या रंगीन लेंस"),
    )

    fun material(id: String): Txt = MATERIAL[id] ?: Txt(id, id)
    private val MATERIAL = mapOf(
        "CR39" to t("CR-39 (index 1.50)", "CR-39 (इंडेक्स 1.50)"),
        "POLYCARBONATE" to t("Polycarbonate (1.59), impact-resistant", "पॉलीकार्बोनेट (1.59), टूटने से सुरक्षित"),
        "HI_160" to t("High index 1.60", "हाई इंडेक्स 1.60"),
        "HI_167" to t("High index 1.67", "हाई इंडेक्स 1.67"),
    )
    fun materialShort(id: String): String = MATERIAL_SHORT[id] ?: id
    private val MATERIAL_SHORT = mapOf("CR39" to "CR-39", "POLYCARBONATE" to "polycarbonate", "HI_160" to "1.60", "HI_167" to "1.67")

    fun confidence(v: Confidence) = CONFIDENCE.getValue(v)
    private val CONFIDENCE = mapOf(
        Confidence.HIGH to t("high confidence", "भरोसा: ज़्यादा"),
        Confidence.MEDIUM to t("medium confidence", "भरोसा: मध्यम"),
        Confidence.LOW to t("low confidence", "भरोसा: कम"),
    )

    fun source(v: Source) = SOURCE.getValue(v)
    private val SOURCE = mapOf(
        Source.MEASURED to t("measured", "मापा गया"),
        Source.OLD_GLASSES_NUMBERS to t("from old glasses numbers", "पुराने चश्मे के नंबर से"),
        Source.AGE_TABLE to t("from age table", "उम्र की तालिका से"),
        Source.INTERVIEW to t("from interview", "इंटरव्यू से"),
        Source.STAFF_ENTRY to t("entered by staff", "स्टाफ़ द्वारा भरा गया"),
        Source.POPULATION_AVERAGE to t("population average", "औसत मान"),
        Source.RULE to t("by rule", "नियम से"),
    )

    fun note(v: Note, freshYears: Int): Txt = if (v == Note.OLD_NUMBERS_STALE) STALE.format(freshYears) else NOTE.getValue(v)
    private val STALE = t("Old numbers are more than %d years old", "पुराने नंबर %d साल से ज़्यादा पुराने हैं")
    private val NOTE = mapOf(
        Note.HAND_TEST_NEEDED to t("Do the shop's trial-lens hand test", "दुकान का ट्रायल-लेंस टेस्ट (हाथ वाला) करें"),
        Note.HYPEROPIA_POSSIBLE to t("Long-sight (hyperopia) possible: the phone cannot measure it", "दूर-दृष्टि (हाइपरोपिया) हो सकती है: फ़ोन इसे नहीं माप सकता"),
        Note.CHECK_CYLINDER to t("Check cylinder in the trial-lens test", "ट्रायल-लेंस टेस्ट में सिलिंडर ज़रूर जाँचें"),
        Note.OLD_NUMBERS_STALE to STALE,
        Note.OLD_NUMBERS_AGE_UNKNOWN to t("Age of the old numbers is unknown", "पुराने नंबर कितने पुराने हैं, पता नहीं"),
        Note.NO_POWER_INFORMATION to t("No power information yet (on-screen eye tests come in a later version)", "अभी पावर की कोई जानकारी नहीं (स्क्रीन वाले टेस्ट अगले संस्करण में)"),
        Note.ADD_FROM_AGE_TABLE to t("Reading add is from the age table only", "पढ़ने का ऐड सिर्फ़ उम्र की तालिका से"),
        Note.PD_ASSUMED to t("PD not measured: average used. Measure with a PD ruler.", "PD नहीं मापा गया: औसत लिया गया। PD रूलर से मापें।"),
        Note.PD_IMPLAUSIBLE to t("PD looks unusual for this face: re-measure", "इस चेहरे के हिसाब से PD असामान्य है: दोबारा मापें"),
        Note.FACE_WIDTH_ASSUMED to t("Face width not measured: average used for frame fit", "चेहरे की चौड़ाई नहीं मापी: फ़्रेम फ़िट के लिए औसत लिया गया"),
    )

    fun fit(v: FitVerdict) = FIT.getValue(v)
    private val FIT = mapOf(
        FitVerdict.GOOD to t("fits your face width", "आपके चेहरे की चौड़ाई पर ठीक"),
        FitVerdict.SLIGHTLY_NARROW to t("slightly narrow for your face", "आपके चेहरे के लिए थोड़ा छोटा"),
        FitVerdict.SLIGHTLY_WIDE to t("slightly wide for your face", "आपके चेहरे के लिए थोड़ा चौड़ा"),
        FitVerdict.TOO_NARROW to t("narrow for your face", "आपके चेहरे के लिए छोटा"),
        FitVerdict.TOO_WIDE to t("wide for your face", "आपके चेहरे के लिए चौड़ा"),
    )
}

/** Dioptre formatting: always signed, two decimals, true minus sign. */
fun fmtD(d: Double): String {
    val r = Math.round(d * 100) / 100.0
    if (r == 0.0) return "0.00"
    return (if (r > 0) "+" else "−") + String.format(Locale.US, "%.2f", abs(r))
}
