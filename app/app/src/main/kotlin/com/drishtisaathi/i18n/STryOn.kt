package com.drishtisaathi.i18n

/** User-facing text for Track E (try-on). Owner edits only this file. Create every string with tx(en, hi). */
object STryOn {
    val title = tx("Try on frames", "फ़्रेम पहनकर देखें")
    val lookAtCamera = tx("Look straight at the camera", "सीधे कैमरे की ओर देखें")
    val noFace = tx("Face not found. Move into the frame and hold still.", "चेहरा नहीं दिखा। कैमरे के सामने आएँ और स्थिर रहें।")
    val pickFrame = tx("Tap a frame to try it", "पहनकर देखने के लिए फ़्रेम चुनें")
    val demoNote = tx(
        "Demo images: frame photos are sample pictures; sizes of some frames are estimated from the photo. Check the real frame in the shop.",
        "डेमो तस्वीरें: फ़्रेम की फ़ोटो नमूना हैं; कुछ फ़्रेम का साइज़ फ़ोटो से अनुमानित है। दुकान में असली फ़्रेम देखें।",
    )
    val noFrames = tx("No frames with try-on pictures.", "पहनकर देखने वाली तस्वीरें किसी फ़्रेम के लिए नहीं हैं।")
    val loading = tx("Loading frame…", "फ़्रेम लोड हो रहा है…")
    /** %s = frame size mark, e.g. 52-18-140. */
    val sizeLine = tx("Size %s", "साइज़ %s")
}
