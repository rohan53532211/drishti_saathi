package com.drishtisaathi.i18n

/**
 * Remote Optometrist Bridge (12 Nov). Section 6 wording applies: never "prescription", "diagnosis", "power is"
 * (पर्चा, निदान, पावर है, आपका नंबर). The bilingual disclaimer is shown with the Disclaimer() component.
 */
object SBridge {
    // ---- summary card ----
    val cardTitle = tx("Remote optometrist", "दूर बैठे ऑप्टोमेट्रिस्ट")
    val send = tx("Send to optometrist", "ऑप्टोमेट्रिस्ट को भेजें")
    val notConfigured = tx("Set the optometrist server in staff mode first (Optometrist bridge settings).", "पहले स्टाफ़ मोड में ऑप्टोमेट्रिस्ट सर्वर सेट करें (ऑप्टोमेट्रिस्ट ब्रिज सेटिंग)।")
    val waiting = tx("Waiting for the optometrist", "ऑप्टोमेट्रिस्ट के जवाब का इंतज़ार")
    val reviewed = tx("Reviewed", "जवाब आ गया")
    val sentAt = tx("Sent %s", "भेजा गया %s")
    val checkReply = tx("Check for reply", "जवाब देखें")
    val checking = tx("Checking…", "देख रहे हैं…")
    val sending = tx("Sending…", "भेज रहे हैं…")
    val advice = tx("Optometrist's advice", "ऑप्टोमेट्रिस्ट की सलाह")
    val needsInPerson = tx("Needs an in-person eye exam", "आँखों की जाँच के लिए खुद जाना ज़रूरी है")
    val noInPerson = tx("No in-person exam asked for", "खुद जाकर जाँच की ज़रूरत नहीं बताई")
    val adviceNote = tx("Remote advice from the profile only: confirm everything with the trial-lens test.", "यह सलाह सिर्फ़ प्रोफ़ाइल देखकर दी गई है: सब कुछ ट्रायल-लेंस टेस्ट से पक्का करें।")

    // ---- errors ----
    val errNetwork = tx("Could not reach the server. Check the internet and the server address.", "सर्वर से जुड़ नहीं पाए। इंटरनेट और सर्वर का पता जाँचें।")
    val errToken = tx("The server refused the token. Check it in Optometrist bridge settings.", "सर्वर ने टोकन नहीं माना। ऑप्टोमेट्रिस्ट ब्रिज सेटिंग में जाँचें।")
    val errRejected = tx("The server did not accept the profile (%s).", "सर्वर ने प्रोफ़ाइल नहीं ली (%s)।")
    val errServer = tx("Server error (%s). Try again later.", "सर्वर में दिक्कत (%s)। थोड़ी देर बाद कोशिश करें।")
    val errGone = tx("The server no longer has this profile.", "सर्वर पर अब यह प्रोफ़ाइल नहीं है।")
    val errNoSession = tx("Save the session first.", "पहले सेशन सेव करें।")

    // ---- consent ----
    val consentTitle = tx("Send this check to a remote optometrist?", "क्या यह जाँच दूर बैठे ऑप्टोमेट्रिस्ट को भेजें?")
    val consentBody = tx(
        "The shop would like a qualified optometrist to look at your answers and the phone's estimates and suggest the next step. " +
            "We send: your answers, age, the measurements (like the distance between your eyes) and the app's estimates. " +
            "We never send your name, your photo or your phone number. " +
            "Only the optometrist the shop works with can see it. When the shop deletes this session, it is deleted there too. " +
            "You can say no: the shop's own check goes ahead the same way. Do you agree?",
        "दुकान चाहती है कि एक योग्य ऑप्टोमेट्रिस्ट आपके जवाब और फ़ोन के अनुमान देखकर अगला कदम सुझाएँ। " +
            "हम भेजेंगे: आपके जवाब, उम्र, माप (जैसे आँखों के बीच की दूरी) और ऐप के अनुमान। " +
            "हम आपका नाम, फ़ोटो या फ़ोन नंबर कभी नहीं भेजते। " +
            "इसे सिर्फ़ दुकान का ऑप्टोमेट्रिस्ट देख सकता है। दुकान यह सेशन मिटाएगी तो वहाँ से भी मिट जाएगा। " +
            "आप मना कर सकते हैं: दुकान की अपनी जाँच वैसे ही होगी। क्या आप सहमत हैं?",
    )
    val sentList = tx("Sent: answers, age, measurements, the app's estimates", "भेजा जाएगा: जवाब, उम्र, माप, ऐप के अनुमान")
    val notSentList = tx("Never sent: name, photo, phone number", "कभी नहीं भेजा जाता: नाम, फ़ोटो, फ़ोन नंबर")
    val spokenTick = tx("Staff: I read this aloud and the customer said yes", "स्टाफ़: मैंने यह पढ़कर सुनाया और ग्राहक ने हाँ कहा")
    val agreeSend = tx("I agree, send", "मैं सहमत हूँ, भेजें")
    val dontSend = tx("No, don't send", "नहीं, मत भेजें")
    val readAgain = tx("Read aloud again", "फिर से पढ़कर सुनाएँ")

    // ---- staff settings ----
    val settingsTitle = tx("Optometrist bridge settings", "ऑप्टोमेट्रिस्ट ब्रिज सेटिंग")
    val urlLabel = tx("Server address (https://… or http://laptop-ip:8000)", "सर्वर का पता (https://… या http://laptop-ip:8000)")
    val tokenLabel = tx("Token (if the server uses one)", "टोकन (अगर सर्वर माँगता है)")
    val save = tx("Save", "सेव करें")
    val saved = tx("Saved", "सेव हो गया")
    val test = tx("Test connection", "कनेक्शन जाँचें")
    val connected = tx("Connected", "जुड़ गया")
    val badUrl = tx("Enter an address starting with http:// or https://", "http:// या https:// से शुरू होने वाला पता लिखें")
    val settingsHint = tx(
        "See backend/README.md. Plain http:// works only in the debug app; use an https tunnel for the demo.",
        "backend/README.md देखें। सादा http:// सिर्फ़ डीबग ऐप में चलता है; डेमो के लिए https टनल लें।",
    )
}
