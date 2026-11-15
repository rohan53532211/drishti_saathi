package com.drishtisaathi.i18n

/** Results, choosing an optometrist, chat and the advice view (UI phase 5). */
object SConsult {
    // chat
    val today = tx("Today", "आज")
    val yesterday = tx("Yesterday", "कल")
    val videoStarted = tx("Your optometrist started a video call", "आपके ऑप्टोमेट्रिस्ट ने वीडियो कॉल शुरू की है")
    val videoCall = tx("Video call", "वीडियो कॉल")
    val whatsapp = tx("WhatsApp", "WhatsApp")
    val sendFailed = tx("Not sent. Tap send to try again.", "संदेश नहीं गया। दोबारा भेजने के लिए भेजें दबाएँ।")
    val tryAgain = tx("Try again", "फिर से कोशिश करें")
    val waitingTitle = tx("Waiting for an optometrist", "ऑप्टोमेट्रिस्ट का इंतज़ार")

    // advice
    val adviceTitle = tx("Optometrist's advice", "ऑप्टोमेट्रिस्ट की सलाह")
    val colEye = tx("Eye", "आँख")
    val right = tx("Right (R)", "दाईं (R)")
    val left = tx("Left (L)", "बाईं (L)")
    val sph = tx("SPH", "SPH")
    val cyl = tx("CYL", "CYL")
    val axis = tx("AXIS", "AXIS")
    val add = tx("ADD (near)", "ADD (पास)")
    val pd = tx("PD", "PD")
    val lensAdvice = tx("Lens advice", "लेंस की सलाह")
    val referral = tx("Referral", "रेफ़रल")
    val followUp = tx("Follow-up", "अगली जाँच")
    val notes = tx("Notes", "नोट")
    val downloadPdf = tx("Download PDF", "PDF डाउनलोड करें")

    // choosing an optometrist
    val recommended = tx("Fastest", "सबसे जल्दी")
    val free = tx("Free", "मुफ़्त")
    val noneAvailableTitle = tx("No optometrist is online right now", "अभी कोई ऑप्टोमेट्रिस्ट ऑनलाइन नहीं है")
    val noneAvailableLine = tx("Send the request anyway: the first one who comes online takes it.", "फिर भी अनुरोध भेजें: जो पहले ऑनलाइन आएँगे, वे इसे लेंगे।")

    // results
    val measurements = tx("Measurements", "माप")
    val actions = tx("What next", "आगे क्या")
    val share = tx("Share", "शेयर करें")
    val pdf = tx("PDF", "PDF")
    val showQr = tx("Show QR code for the shop", "दुकान के लिए QR कोड दिखाएँ")
    val hideQr = tx("Hide QR code", "QR कोड छिपाएँ")
    val opticianConfirms = tx("Your optician confirms the final lens.", "आख़िरी लेंस आपके ऑप्टिशियन तय करेंगे।")
}
