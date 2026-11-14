package com.drishtisaathi.i18n

/**
 * Teleconsult, patient side (15 Nov). Section 6 wording applies here too: the optometrist's document is called
 * "advice" in the app, never by the banned words. A teleconsult never replaces an urgent visit: [neverReplaces].
 */
object STele {
    // ---- home and account ----
    val patientMode = tx("Patient mode", "मरीज़ मोड")
    val patientModeLine = tx("Register, take the test and consult an optometrist from home.", "रजिस्टर करें, जाँच करें और घर से ऑप्टोमेट्रिस्ट से सलाह लें।")
    val login = tx("Log in", "लॉग इन करें")
    val register = tx("Register", "रजिस्टर करें")
    val newHere = tx("New here? Register", "नए हैं? रजिस्टर करें")
    val haveAccount = tx("I already have an account", "मेरा खाता पहले से है")
    val name = tx("Full name", "पूरा नाम")
    val phone = tx("Mobile number (10 digits)", "मोबाइल नंबर (10 अंक)")
    val password = tx("Password (8 or more characters)", "पासवर्ड (8 या ज़्यादा अक्षर)")
    val age = tx("Age", "उम्र")
    val gender = tx("Gender (optional)", "लिंग (ज़रूरी नहीं)")
    val genderF = tx("Female", "महिला")
    val genderM = tx("Male", "पुरुष")
    val genderO = tx("Other", "अन्य")
    val language = tx("Language", "भाषा")
    /** The consent sentence the server stores with its time. */
    val consent = tx(
        "I agree my report and name are shared with the optometrist I choose",
        "मैं सहमत हूँ कि मेरी रिपोर्ट और नाम मेरे चुने हुए ऑप्टोमेट्रिस्ट को भेजे जाएँ",
    )
    val consentNeeded = tx("Please tick the consent box to register.", "रजिस्टर करने के लिए सहमति पर निशान लगाएँ।")
    val errName = tx("Enter your name.", "अपना नाम भरें।")
    val errPhone = tx("Enter a 10-digit mobile number.", "10 अंकों का मोबाइल नंबर भरें।")
    val errPassword = tx("The password needs 8 or more characters.", "पासवर्ड में 8 या ज़्यादा अक्षर चाहिए।")
    val errAge = tx("Enter your age (1 to 120).", "अपनी उम्र भरें (1 से 120)।")
    val hello = tx("Hello, %s", "नमस्ते, %s")
    val takeTest = tx("Take the eye test", "आँखों की जाँच करें")
    val myConsultations = tx("My consultations", "मेरी सलाह")
    val logout = tx("Log out", "लॉग आउट")
    val deleteAccount = tx("Delete my account and data", "मेरा खाता और डेटा मिटाएँ")
    val deleteConfirm = tx(
        "This deletes your account, your reports, chats and the optometrist's advice from the server. It cannot be undone.",
        "इससे आपका खाता, रिपोर्ट, चैट और ऑप्टोमेट्रिस्ट की सलाह सर्वर से मिट जाएगी। इसे वापस नहीं लाया जा सकता।",
    )
    val deleted = tx("Your account and data were deleted.", "आपका खाता और डेटा मिटा दिया गया।")
    val serverSettings = tx("Server settings", "सर्वर सेटिंग")
    val demoNote = tx(
        "Demo: login is by mobile number and password, and no payment is taken.",
        "डेमो: लॉग इन मोबाइल नंबर और पासवर्ड से है, और कोई भुगतान नहीं लिया जाता।",
    )

    // ---- errors ----
    val errNotConfigured = tx("Set the server address first (Server settings).", "पहले सर्वर का पता सेट करें (सर्वर सेटिंग)।")
    val errNetwork = tx("Could not reach the server. Check the Wi-Fi and try again.", "सर्वर तक नहीं पहुँच पाए। वाई-फ़ाई देखें और फिर कोशिश करें।")
    val errLogin = tx("Wrong mobile number or password.", "मोबाइल नंबर या पासवर्ड ग़लत है।")
    val errSession = tx("Please log in again.", "कृपया फिर से लॉग इन करें।")
    val errTaken = tx("This mobile number is already registered. Log in instead.", "यह मोबाइल नंबर पहले से रजिस्टर है। लॉग इन करें।")
    val errGone = tx("This is not available any more.", "यह अब उपलब्ध नहीं है।")
    val errClosed = tx("This consultation is closed.", "यह सलाह बंद हो चुकी है।")
    val errServer = tx("The server could not do this (%s).", "सर्वर यह नहीं कर पाया (%s)।")

    // ---- asking for a consultation ----
    val consult = tx("Consult an optometrist", "ऑप्टोमेट्रिस्ट से सलाह लें")
    val consultLine = tx(
        "Send this report to a verified optometrist and talk by chat, WhatsApp or video call.",
        "यह रिपोर्ट जाँचे-परखे ऑप्टोमेट्रिस्ट को भेजें और चैट, WhatsApp या वीडियो कॉल से बात करें।",
    )
    val neverReplaces = tx("A teleconsult never replaces an urgent visit.", "टेलीकंसल्ट कभी भी तुरंत डॉक्टर को दिखाने की जगह नहीं ले सकता।")
    val loginFirst = tx("Log in or register as a patient first.", "पहले मरीज़ के रूप में लॉग इन या रजिस्टर करें।")
    val choose = tx("Choose an optometrist", "ऑप्टोमेट्रिस्ट चुनें")
    val anyAvailable = tx("Any available optometrist", "कोई भी उपलब्ध ऑप्टोमेट्रिस्ट")
    val anyAvailableLine = tx("The first optometrist who is free takes your case.", "जो ऑप्टोमेट्रिस्ट पहले ख़ाली होंगे, वे आपका केस लेंगे।")
    val noneAvailable = tx("No optometrist is available right now. You can still send the request to any optometrist.", "अभी कोई ऑप्टोमेट्रिस्ट उपलब्ध नहीं है। फिर भी आप अनुरोध भेज सकते हैं।")
    val fee = tx("Fee ₹%d", "फ़ीस ₹%d")
    val noPayment = tx("No payment in this demo", "इस डेमो में कोई भुगतान नहीं")
    val regNo = tx("Reg. no. %s", "रजि. नं. %s")
    val sendRequest = tx("Send the request", "अनुरोध भेजें")
    val sending = tx("Sending…", "भेज रहे हैं…")
    val shared = tx("Your report and your name will be shared with the optometrist.", "आपकी रिपोर्ट और नाम ऑप्टोमेट्रिस्ट को भेजे जाएँगे।")
    val noReport = tx("Take the eye test first: there is no report to send yet.", "पहले आँखों की जाँच करें: भेजने के लिए अभी कोई रिपोर्ट नहीं है।")

    // ---- my consultations ----
    val none = tx("No consultations yet. Take the test, then tap \"Consult an optometrist\" on the result.", "अभी कोई सलाह नहीं। जाँच करें, फिर नतीजे पर \"ऑप्टोमेट्रिस्ट से सलाह लें\" दबाएँ।")
    val caseNo = tx("Case #%s", "केस #%s")
    val autoRefresh = tx("Updates every 15 seconds while this screen is open.", "यह स्क्रीन खुली रहने तक हर 15 सेकंड में अपडेट होता है।")
    private val STATUS = mapOf(
        "REQUESTED" to tx("Waiting for an optometrist", "ऑप्टोमेट्रिस्ट का इंतज़ार"),
        "ACCEPTED" to tx("Accepted by the optometrist", "ऑप्टोमेट्रिस्ट ने केस ले लिया"),
        "IN_PROGRESS" to tx("Consultation in progress", "सलाह चल रही है"),
        "COMPLETED" to tx("Completed: advice ready", "पूरी हुई: सलाह तैयार है"),
        "CLOSED" to tx("Closed", "बंद"),
    )
    val statusUnknown = tx("Status: %s", "स्थिति: %s")
    fun status(code: String): Txt = STATUS[code] ?: statusUnknown.format(code)
    val statusCodes: Set<String> get() = STATUS.keys
    val urgentTag = tx("URGENT", "ज़रूरी (URGENT)")
    val waitingFor = tx("Waiting for %s", "%s का इंतज़ार")
    val withOptometrist = tx("Optometrist: %s", "ऑप्टोमेट्रिस्ट: %s")

    // ---- one consultation ----
    val chat = tx("Chat", "चैट")
    val chatAfterAccept = tx("You can chat as soon as an optometrist accepts your case.", "ऑप्टोमेट्रिस्ट के केस लेते ही आप चैट कर सकेंगे।")
    val messageHint = tx("Write a message", "संदेश लिखें")
    val send = tx("Send", "भेजें")
    val noMessages = tx("No messages yet.", "अभी कोई संदेश नहीं।")
    val joinVideo = tx("Join video call", "वीडियो कॉल में जुड़ें")
    val videoMessage = tx("Video call link", "वीडियो कॉल का लिंक")
    val whatsapp = tx("WhatsApp the optometrist", "ऑप्टोमेट्रिस्ट को WhatsApp करें")
    val picture = tx("Picture", "चित्र")
    val you = tx("You", "आप")
    val optometrist = tx("Optometrist", "ऑप्टोमेट्रिस्ट")
    val advice = tx("Optometrist's advice", "ऑप्टोमेट्रिस्ट की सलाह")
    val openPdf = tx("Open the advice (PDF)", "सलाह खोलें (PDF)")
    val pdfFailed = tx("Could not open the PDF.", "PDF नहीं खुल पाया।")
    val eyeRight = tx("Right eye", "दाईं आँख")
    val eyeLeft = tx("Left eye", "बाईं आँख")
    val nearAdd = tx("Near addition: %s D", "पास के लिए: %s D")
    val pd = tx("PD: %s mm", "PD: %s mm")
    val lensAdvice = tx("Lens advice: %s", "लेंस की सलाह: %s")
    val inPerson = tx("An in-person eye examination is advised.", "आँखों की जाँच के लिए खुद जाने की सलाह दी गई है।")
    val referral = tx("Referral: %s", "रेफ़रल: %s")
    val followUp = tx("Follow-up: %s", "अगली जाँच: %s")
    val notes = tx("Notes: %s", "नोट: %s")
    val signedBy = tx("%s, registration no. %s", "%s, रजिस्ट्रेशन नं. %s")
    val confirmTrial = tx(
        "From a remote consultation: have the numbers confirmed with a trial lens before glasses are made.",
        "यह दूर से दी गई सलाह है: चश्मा बनवाने से पहले ट्रायल लेंस से नंबर पक्का करवाएँ।",
    )
    val closeConsultation = tx("Close this consultation", "यह सलाह बंद करें")
    val cancelRequest = tx("Cancel the request", "अनुरोध रद्द करें")
    val closeConfirm = tx("Close this consultation? The chat stops for both sides.", "यह सलाह बंद करें? दोनों तरफ़ चैट रुक जाएगी।")
    val yes = tx("Yes", "हाँ")
    val no = tx("No", "नहीं")
    val payment = tx("Payment: not required (demo)", "भुगतान: ज़रूरी नहीं (डेमो)")
}
