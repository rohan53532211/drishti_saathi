package com.drishtisaathi.i18n

/** AI voice interview (Sarvam, through the shop's backend). Section 6 wording applies. */
object SAiVoice {
    val startButton = tx("Voice interview (AI)", "आवाज़ से इंटरव्यू (AI)")
    val startHint = tx("Talk freely in Hindi or English; the AI asks the questions.", "हिंदी या अंग्रेज़ी में खुलकर बोलें; सवाल AI पूछेगा।")
    val title = tx("Voice interview (AI)", "आवाज़ से इंटरव्यू (AI)")

    // ---- consent ----
    val language = tx("Language", "भाषा")
    val hindi = tx("Hindi", "हिंदी")
    val english = tx("English", "English")
    val consentBody = tx(
        "Your voice will be sent to a secure server for transcription. No name or photo is sent.",
        "आपकी आवाज़ लिखने (ट्रांसक्रिप्शन) के लिए एक सुरक्षित सर्वर पर भेजी जाएगी। नाम या फ़ोटो नहीं भेजी जाती।",
    )
    val consentMore = tx(
        "Please do not say your name or phone number. The shop's own check and advice stay the same.",
        "कृपया अपना नाम या फ़ोन नंबर न बोलें। दुकान की अपनी जाँच और सलाह वैसी ही रहेगी।",
    )
    val continueAi = tx("Continue", "आगे बढ़ें")
    val useButtons = tx("Use buttons instead", "इसके बजाय बटन से जवाब दें")

    // ---- interview ----
    val questionOf = tx("Question %d of ~%d", "सवाल %d / लगभग %d")
    val tapToSpeak = tx("Tap to speak", "बोलने के लिए दबाएँ")
    val tapToStop = tx("Tap to stop", "रोकने के लिए दबाएँ")
    val recording = tx("Listening… (stops by itself after 15 seconds)", "सुन रहे हैं… (15 सेकंड बाद अपने आप रुकेगा)")
    val thinking = tx("Thinking…", "सोच रहे हैं…")
    val starting = tx("Starting…", "शुरू हो रहा है…")
    val youSaid = tx("You said: %s", "आपने कहा: %s")
    val playAgain = tx("Hear the question again", "सवाल फिर से सुनें")
    val endInterview = tx("End interview", "इंटरव्यू ख़त्म करें")
    val retry = tx("Try again", "फिर से कोशिश करें")
    val finished = tx("Thank you. Continuing with the shop's check.", "धन्यवाद। अब दुकान की जाँच आगे बढ़ेगी।")
    val continueButton = tx("Continue", "आगे बढ़ें")
    val micNeeded = tx("Allow the microphone to answer by voice.", "आवाज़ से जवाब देने के लिए माइक्रोफ़ोन की अनुमति दें।")

    // ---- errors ----
    val errNotHeard = tx("Sorry, we did not catch that. Please tap and speak again.", "माफ़ कीजिए, सुनाई नहीं दिया। दबाकर फिर से बोलें।")
    val errTooLong = tx("That was too long. Please answer in a few words.", "जवाब बहुत लंबा था। कृपया थोड़े शब्दों में बताएँ।")
    val errMic = tx("The microphone did not work. Please try again.", "माइक्रोफ़ोन नहीं चला। फिर से कोशिश करें।")
    val errBackend = tx("The voice service did not respond (%s).", "आवाज़ सेवा ने जवाब नहीं दिया (%s)।")
    val unavailable = tx("Voice AI unavailable, continuing with the standard interview", "वॉइस AI उपलब्ध नहीं है, सामान्य इंटरव्यू से आगे बढ़ रहे हैं")

    // ---- staff view / shop PDF ----
    val transcriptTitle = tx("AI interview transcript (staff only)", "AI इंटरव्यू की बातचीत (सिर्फ़ स्टाफ़)")
    val transcriptQ = tx("Q%d: %s", "सवाल %d: %s")
    val transcriptA = tx("Answer: %s", "जवाब: %s")
    val transcriptNoAnswer = tx("(no answer)", "(जवाब नहीं)")
    val transcriptFellBack = tx("Voice AI stopped; the standard interview was used for the rest.", "वॉइस AI रुक गया; बाकी सामान्य इंटरव्यू से हुआ।")
    val transcriptNote = tx("Answers were checked by staff on the confirmation screen.", "जवाब स्टाफ़ ने पुष्टि वाली स्क्रीन पर जाँचे।")
}
