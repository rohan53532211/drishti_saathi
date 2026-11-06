package com.drishtisaathi.i18n

/** User-facing text for Track D (voice read-back, listening prompts). Owner edits only this file. Create every string with tx(en, hi). */
object SVoice {
    // ---- Toggles ---------------------------------------------------------------------------
    val readAloud = tx("Read questions aloud", "सवाल पढ़कर सुनाएँ")
    val micStart = tx("Answer by voice", "बोलकर जवाब दें")
    val micStop = tx("Stop voice answers", "बोलकर जवाब बंद करें")
    val buttonsAlways = tx("You can always tap the buttons below instead.", "आप कभी भी नीचे दिए बटन भी दबा सकते हैं।")

    // ---- Dialogue --------------------------------------------------------------------------
    val speaking = tx("Reading the question…", "सवाल पढ़ा जा रहा है…")
    val listening = tx("Listening… please speak now", "सुन रहे हैं… अब बोलिए")
    val heard = tx("Heard: %s", "सुना: %s")
    /** Spoken and shown after parsing. %s is the answer label. */
    val readBack = tx("You said: %s. Is that right?", "आपने कहा: %s. सही है?")
    val confirmYes = tx("✓ Yes, correct", "✓ हाँ, सही है")
    val confirmNo = tx("✗ No, ask again", "✗ नहीं, फिर से पूछें")
    val sayAgain = tx("Sorry, I did not catch that. Please say it again.", "माफ़ कीजिए, समझ नहीं आया। कृपया फिर से बोलिए।")
    val fallback = tx(
        "I could not understand. Please answer with the buttons below.",
        "मैं समझ नहीं पाया। कृपया नीचे दिए बटन से जवाब दें।",
    )
    val tapToConfirm = tx("Please tap ✓ or ✗.", "कृपया ✓ या ✗ दबाइए।")
    val accepted = tx("Saved: %s", "दर्ज किया: %s")

    // ---- Availability and errors -----------------------------------------------------------
    val micDenied = tx(
        "Microphone permission was not given. Questions can still be read aloud; please answer with the buttons.",
        "माइक की अनुमति नहीं मिली। सवाल अब भी पढ़कर सुनाए जा सकते हैं; कृपया बटन से जवाब दें।",
    )
    val recognitionUnavailable = tx(
        "Voice answers are not available on this phone. Please use the buttons.",
        "इस फ़ोन पर बोलकर जवाब देना उपलब्ध नहीं है। कृपया बटन इस्तेमाल करें।",
    )
    val recognitionError = tx(
        "Voice answers stopped working. Please use the buttons.",
        "बोलकर जवाब देना बंद हो गया। कृपया बटन इस्तेमाल करें।",
    )
    val noHindiVoice = tx(
        "No Hindi voice is installed on this phone, so questions are read in English.",
        "इस फ़ोन में हिंदी आवाज़ इंस्टॉल नहीं है, इसलिए सवाल अंग्रेज़ी में पढ़े जा रहे हैं।",
    )
    val ttsUnavailable = tx(
        "Reading aloud is not available on this phone.",
        "इस फ़ोन पर पढ़कर सुनाना उपलब्ध नहीं है।",
    )

    // ---- Privacy / consent -----------------------------------------------------------------
    val onDeviceNote = tx(
        "Speech is understood on this phone. Nothing is recorded or saved.",
        "आवाज़ इसी फ़ोन पर समझी जाती है। कुछ भी रिकॉर्ड या सेव नहीं होता।",
    )
    val onlineNote = tx(
        "Online voice recognition is on. Nothing is recorded or saved by this app.",
        "ऑनलाइन आवाज़ पहचान चालू है। यह ऐप कुछ भी रिकॉर्ड या सेव नहीं करता।",
    )
    val onlineConsentTitle = tx("Allow online voice recognition?", "ऑनलाइन आवाज़ पहचान की अनुमति दें?")
    /** Shown before voice input is turned on when on-device recognition cannot be confirmed. */
    val onlineConsentBody = tx(
        "On this phone, speech may be sent over the internet to the phone's speech service (for example Google) " +
            "while you speak. This app does not record or save your voice; only the recognised words are used to " +
            "fill in the answer. You can always use the buttons instead.",
        "इस फ़ोन पर बोलते समय आपकी आवाज़ इंटरनेट से फ़ोन की आवाज़ पहचान सेवा (जैसे Google) को भेजी जा सकती है। " +
            "यह ऐप आपकी आवाज़ रिकॉर्ड या सेव नहीं करता; पहचाने गए शब्द सिर्फ़ जवाब भरने में इस्तेमाल होते हैं। " +
            "आप कभी भी बटन इस्तेमाल कर सकते हैं।",
    )
    val onlineConsentAgree = tx("Allow voice answers", "बोलकर जवाब की अनुमति दें")
    val onlineConsentDecline = tx("Use buttons only", "सिर्फ़ बटन इस्तेमाल करें")
}
