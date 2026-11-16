package com.drishtisaathi.i18n

/** The guided test flow (UI phase 4, docs/DESIGN.md §5.3): checklist, eye-test chrome, screening template, urgent. */
object SGuide {
    // ---- before the test ----
    val checklistTitle = tx("Before you start", "शुरू करने से पहले")
    val checkLight = tx("Sit in good light", "अच्छी रोशनी में बैठें")
    val checkLightLine = tx("Light on your face, not behind you.", "रोशनी चेहरे पर हो, पीछे नहीं।")
    val checkBattery = tx("Phone charged", "फ़ोन चार्ज हो")
    val checkBatteryLine = tx("Battery at least 20 percent.", "कम से कम 20 प्रतिशत बैटरी।")
    val checkCard = tx("Bank card and glasses nearby", "बैंक कार्ड और चश्मा पास रखें")
    val checkCardLine = tx("Any ATM-size card. Glasses only if you wear them.", "ATM जितना कोई भी कार्ड। चश्मा तभी, अगर आप पहनते हैं।")
    val ready = tx("I'm ready", "मैं तैयार हूँ")

    // ---- eye tests ----
    val rightNow = tx("Right eye now, left eye next", "अभी दाईं आँख, फिर बाईं")
    val leftNow = tx("Right eye done, left eye now", "दाईं आँख हो गई, अभी बाईं")
    val startingIn = tx("Starting in %d", "%d में शुरू")
    val getReady = tx("Get ready: hold the phone close", "तैयार हो जाइए: फ़ोन पास पकड़ें")

    // ---- screening ----
    val testDone = tx("Done", "हो गया")
    val nextTest = tx("Next test", "अगली जाँच")

    // ---- urgent ----
    val whatToDo = tx("What to do now", "अब क्या करें")
    val readAloud = tx("Read aloud", "पढ़कर सुनाएँ")
}
