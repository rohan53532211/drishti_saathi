package com.drishtisaathi.i18n

/** First run and accounts (UI phase 2, docs/DESIGN.md §5.1): welcome, role choice, sign-up wizard, login. */
object SAuth {
    // ---- welcome ----
    val appName = tx("Drishti Saathi", "दृष्टि साथी")
    val tagline = tx("Eye care, made simple", "आँखों की देखभाल, आसान")
    val getStarted = tx("Get started", "शुरू करें")

    // ---- role choice ----
    val whoTitle = tx("Who is using this phone?", "यह फ़ोन कौन इस्तेमाल कर रहा है?")
    val rolePatient = tx("I'm a patient", "मैं मरीज़ हूँ")
    val rolePatientLine = tx("Check your eyes and talk to an optometrist", "अपनी आँखें जाँचें और ऑप्टोमेट्रिस्ट से बात करें")
    val roleShop = tx("Optical shop", "ऑप्टिकल दुकान")
    val roleShopLine = tx("Check your customers' eyes in the shop", "दुकान पर ग्राहकों की आँखें जाँचें")
    val roleOptometrist = tx("I'm an optometrist", "मैं ऑप्टोमेट्रिस्ट हूँ")
    val roleOptometristLine = tx("Answer patients on the web portal", "वेब पोर्टल पर मरीज़ों को जवाब दें")
    val portalOpening = tx("Opening the optometrist portal…", "ऑप्टोमेट्रिस्ट पोर्टल खुल रहा है…")
    val noBrowser = tx("No browser found. Open %s on a computer.", "कोई ब्राउज़र नहीं मिला। कंप्यूटर पर %s खोलें।")

    // ---- patient entry ----
    val patientTitle = tx("Your eye-care account", "आपका आँखों की देखभाल वाला खाता")
    val patientLine = tx("Save your results and ask an optometrist from home.", "अपने नतीजे सँभालें और घर बैठे ऑप्टोमेट्रिस्ट से पूछें।")
    val createAccount = tx("Create account", "खाता बनाएँ")
    val haveAccount = tx("I have an account – Log in", "मेरा खाता है – लॉग इन करें")

    // ---- sign-up wizard ----
    val stepOf = tx("Step %d of %d", "चरण %d / %d")
    val next = tx("Next", "आगे")
    val step1 = tx("Your name and mobile", "आपका नाम और मोबाइल")
    val nameLabel = tx("Full name", "पूरा नाम")
    val phoneLabel = tx("Mobile number", "मोबाइल नंबर")
    val phoneHelper = tx("10 digits. You log in with it.", "10 अंक। इसी से लॉग इन होगा।")
    val step2 = tx("A little about you", "आपके बारे में थोड़ा")
    val ageLabel = tx("Age", "उम्र")
    val ageHelper = tx("Lenses depend on age.", "लेंस उम्र के हिसाब से चुने जाते हैं।")
    val genderLabel = tx("Gender (you can skip this)", "लिंग (छोड़ भी सकते हैं)")
    val step3 = tx("Set a password", "पासवर्ड बनाएँ")
    val passwordLabel = tx("Password", "पासवर्ड")
    val passwordHelper = tx("At least 8 letters or numbers.", "कम से कम 8 अक्षर या अंक।")
    val show = tx("Show password", "पासवर्ड दिखाएँ")
    val hide = tx("Hide password", "पासवर्ड छिपाएँ")
    val consentTitle = tx("Your consent", "आपकी सहमति")
    val whatWeShare = tx("What we share", "हम क्या भेजते हैं")
    val share1 = tx("Your name, age and test report go only to the optometrist you choose.",
        "आपका नाम, उम्र और जाँच की रिपोर्ट सिर्फ़ आपके चुने हुए ऑप्टोमेट्रिस्ट को जाती है।")
    val share2 = tx("Your mobile number is for logging in and for your consultation.",
        "आपका मोबाइल नंबर लॉग इन और आपकी सलाह के लिए है।")
    val share3 = tx("You can delete your account and data any time in Profile.",
        "आप कभी भी प्रोफ़ाइल में जाकर अपना खाता और डेटा मिटा सकते हैं।")
    val creating = tx("Creating your account…", "आपका खाता बन रहा है…")

    // ---- done ----
    val ready = tx("Your profile is ready", "आपकी प्रोफ़ाइल तैयार है")
    val readyLine = tx("Next: a 10-minute eye test on this phone.", "अब: इसी फ़ोन पर 10 मिनट की आँखों की जाँच।")
    val startFirstTest = tx("Start my first eye test", "पहली आँख जाँच शुरू करें")
    val later = tx("Later", "बाद में")

    // ---- login ----
    val welcomeBack = tx("Welcome back", "फिर से स्वागत है")
    val loggingIn = tx("Logging in…", "लॉग इन हो रहा है…")
    val forgot = tx("Forgot password? Ask your optical shop or optometrist to reset it.",
        "पासवर्ड भूल गए? अपनी ऑप्टिकल दुकान या ऑप्टोमेट्रिस्ट से नया करवाएँ।")
    val noAccount = tx("New here? Create account", "नए हैं? खाता बनाएँ")

    // ---- role switch ----
    val switchRole = tx("Change who uses this phone", "फ़ोन इस्तेमाल करने वाला बदलें")
}
