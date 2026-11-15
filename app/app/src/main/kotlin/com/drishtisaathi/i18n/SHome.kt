package com.drishtisaathi.i18n

/** Homes and navigation per role (UI phase 3, docs/DESIGN.md §5.2). */
object SHome {
    // ---- bottom navigation ----
    val tabHome = tx("Home", "होम")
    val tabConsults = tx("Consultations", "सलाह")
    val tabProfile = tx("Profile", "प्रोफ़ाइल")
    val tabCustomers = tx("Customers", "ग्राहक")
    val tabTools = tx("Tools", "टूल्स")

    // ---- patient home ----
    val startTest = tx("Start eye test", "आँखों की जाँच शुरू करें")
    val startTestLine = tx("About 10 minutes, on this phone", "इसी फ़ोन पर, लगभग 10 मिनट")
    val lastResult = tx("Last result", "पिछला नतीजा")
    val seeResult = tx("See result", "नतीजा देखें")
    val noResult = tx("No test yet. Your result will show here.", "अभी कोई जाँच नहीं हुई। आपका नतीजा यहाँ दिखेगा।")
    val visionScore = tx("Vision Profile %d of 100", "विज़न प्रोफ़ाइल 100 में से %d")
    val myOptometrist = tx("My optometrist", "मेरे ऑप्टोमेट्रिस्ट")
    val noOptometrist = tx("After your test you can ask an optometrist.", "जाँच के बाद आप ऑप्टोमेट्रिस्ट से पूछ सकते हैं।")
    val waitingOptometrist = tx("Waiting for an optometrist", "ऑप्टोमेट्रिस्ट का इंतज़ार")
    val tips = tx("Tips for your eyes", "आँखों के लिए सुझाव")
    val tipRest = tx("Every 20 minutes, look far away for 20 seconds.", "हर 20 मिनट में 20 सेकंड दूर देखें।")
    val tipLight = tx("Read and take the test in good light.", "अच्छी रोशनी में पढ़ें और जाँच करें।")
    val tipDistance = tx("Hold the phone at arm's length, not close to your eyes.", "फ़ोन को आँखों के पास नहीं, हाथ भर दूर रखें।")

    // ---- consultations ----
    val consultsEmpty = tx("No consultations yet", "अभी कोई सलाह नहीं")
    val consultsEmptyLine = tx("Take the eye test, then ask an optometrist about your result.",
        "आँखों की जाँच करें, फिर अपने नतीजे के बारे में ऑप्टोमेट्रिस्ट से पूछें।")
    val messages = tx("%d messages", "%d संदेश")
    val tryAgain = tx("Try again", "फिर कोशिश करें")
    val loading = tx("Loading", "लोड हो रहा है")

    // ---- profile ----
    val details = tx("Your details", "आपकी जानकारी")
    val mobile = tx("Mobile", "मोबाइल")
    val gender = tx("Gender", "लिंग")
    val notGiven = tx("Not given", "नहीं बताया")
    val appLanguage = tx("App language", "ऐप की भाषा")
    val settings = tx("Settings", "सेटिंग")
    val about = tx("About", "जानकारी")
    val version = tx("Version %s", "संस्करण %s")
    val deleteEverything = tx("Delete everything", "सब कुछ मिटाएँ")

    // ---- shop ----
    val customersEmpty = tx("No customers yet", "अभी कोई ग्राहक नहीं")
    val customersEmptyLine = tx("Saved checks show here, newest first.", "सहेजी गई जाँचें यहाँ दिखेंगी, नई सबसे ऊपर।")
    val setupNow = tx("Set up now", "अभी सेट करें")
    val advanced = tx("Advanced", "एडवांस्ड")
    val advancedLine = tx("For the shop owner: server, calibration and staff checks.", "दुकान मालिक के लिए: सर्वर, कैलिब्रेशन और स्टाफ़ जाँच।")
    val deleteSession = tx("Delete this check?", "यह जाँच मिटाएँ?")
}
