package com.drishtisaathi.i18n

/** User-facing text for the 3D catalogue, 3D viewer, live 3D try-on and the face avatar. Create every string with tx(en, hi). */
object S3D {
    // ---- catalogue ----
    val catalogTitle = tx("Frames in 3D", "3D में फ़्रेम")
    val tryIn3d = tx("Try frames in 3D", "3D में फ़्रेम पहनकर देखें")
    val search = tx("Search: colour, shape, size…", "खोजें: रंग, आकार, साइज़…")
    val recommended = tx("Recommended for you", "आपके लिए सुझाए गए")
    val priceLow = tx("Price: low to high", "कीमत: कम से ज़्यादा")
    val priceHigh = tx("Price: high to low", "कीमत: ज़्यादा से कम")
    val eyeglasses = tx("Eyeglasses", "चश्मे")
    val sunglasses = tx("Sunglasses", "धूप के चश्मे")
    val all = tx("All", "सभी")
    val shape = tx("Shape", "आकार")
    val rim = tx("Rim", "रिम")
    val colour = tx("Colour", "रंग")
    val material = tx("Material", "मटीरियल")
    val price = tx("Price", "कीमत")
    val size = tx("Size", "साइज़")
    val clearFilters = tx("Clear filters", "फ़िल्टर हटाएँ")
    /** %d = number of frames */
    val count = tx("%d frames", "%d फ़्रेम")
    val noMatch = tx("No frames match. Clear a filter.", "कोई फ़्रेम नहीं मिला। कोई फ़िल्टर हटाएँ।")
    val online = tx("More frames from the shop server", "दुकान के सर्वर से और फ़्रेम")
    val offline = tx("Showing the frames stored on this phone", "इस फ़ोन में रखे फ़्रेम दिख रहे हैं")
    val demoCatalog = tx(
        "Demo catalogue: 3D frames are made from sample product photos. Sizes, material and colour are estimated; prices are examples.",
        "डेमो कैटलॉग: 3D फ़्रेम नमूना फ़ोटो से बने हैं। साइज़, मटीरियल और रंग अनुमानित हैं; कीमतें उदाहरण हैं।",
    )

    // ---- viewer ----
    val viewerHint = tx("Drag to turn · pinch to zoom", "घुमाने के लिए खींचें · ज़ूम के लिए दो उँगलियाँ")
    val sizesEstimated = tx("Sizes estimated from the photo", "साइज़ फ़ोटो से अनुमानित")
    val sizesMeasured = tx("Sizes measured on the real frame", "साइज़ असली फ़्रेम पर नापे गए")
    val estimated = tx("estimated", "अनुमानित")
    /** %s = A-DBL-temple */
    val sizeLine = tx("Size %s (lens – bridge – temple, mm)", "साइज़ %s (लेंस – ब्रिज – डंडी, mm)")
    /** %1$s material, %2$s "estimated" */
    val materialLine = tx("Material: %1\$s (%2\$s)", "मटीरियल: %1\$s (%2\$s)")
    val colourLine = tx("Colour: %1\$s (%2\$s)", "रंग: %1\$s (%2\$s)")
    val tryLive = tx("Try on live (camera)", "कैमरे में पहनकर देखें")
    val tryAvatar = tx("Try on my 3D face", "मेरे 3D चेहरे पर देखें")
    val loading = tx("Loading 3D frame…", "3D फ़्रेम लोड हो रहा है…")
    val downloadFailed = tx("Could not load this frame. Showing the photo instead.", "यह फ़्रेम लोड नहीं हुआ। फ़ोटो दिखा रहे हैं।")
    val favourite = tx("Save", "सेव करें")
    val favourited = tx("Saved", "सेव किया")

    // ---- live try-on ----
    val liveTitle = tx("Live 3D try-on", "लाइव 3D ट्राई-ऑन")
    /** %1$d frame width, %2$d face width */
    val fitText = tx("Frame %1\$d mm, face about %2\$d mm", "फ़्रेम %1\$d mm, चेहरा लगभग %2\$d mm")
    val fitGood = tx("Good width", "चौड़ाई ठीक")
    val fitSlightlyNarrow = tx("A little narrow", "थोड़ा तंग")
    val fitSlightlyWide = tx("A little wide", "थोड़ा चौड़ा")
    val fitTooNarrow = tx("Too narrow", "बहुत तंग")
    val fitTooWide = tx("Too wide", "बहुत चौड़ा")
    val scaleCard = tx("Scale from your card-measured PD", "कार्ड से नापी आँखों की दूरी से साइज़")
    val scaleFace = tx("Scale from your face width (approximate)", "चेहरे की चौड़ाई से साइज़ (लगभग)")
    val scaleDefault = tx("Scale from an average face (approximate)", "औसत चेहरे से साइज़ (लगभग)")
    val alignCheck = tx("3D try-on alignment check", "3D ट्राई-ऑन की सही जगह की जाँच")
    val switchedTo2d = tx("Showing the simple try-on on this phone.", "इस फ़ोन पर सरल ट्राई-ऑन दिख रहा है।")

    // ---- avatar ----
    val avatarTitle = tx("My 3D face", "मेरा 3D चेहरा")
    val avatarConsentTitle = tx("Make a 3D preview of your face?", "क्या आपके चेहरे का 3D प्रीव्यू बनाएँ?")
    val avatarConsentBody = tx(
        "A 3D preview of your face is made on this phone only and deleted with the session. It is never uploaded or sent to the optometrist.",
        "आपके चेहरे का 3D प्रीव्यू सिर्फ़ इसी फ़ोन पर बनता है और सेशन के साथ मिट जाता है। इसे कभी अपलोड नहीं किया जाता, न ही ऑप्टोमेट्रिस्ट को भेजा जाता है।",
    )
    val avatarAgree = tx("Yes, make it", "हाँ, बनाएँ")
    val avatarNo = tx("No, thanks", "नहीं, धन्यवाद")
    val stepFront = tx("Look straight at the camera and hold still", "सीधे कैमरे की ओर देखें और स्थिर रहें")
    val stepLeft = tx("Slowly turn your head to your left and hold", "धीरे से सिर अपनी बाईं ओर घुमाएँ और रुकें")
    val stepRight = tx("Now turn your head to your right and hold", "अब सिर अपनी दाईं ओर घुमाएँ और रुकें")
    val avatarHold = tx("Hold still…", "स्थिर रहें…")
    val turnMore = tx("Turn a little more", "थोड़ा और घुमाएँ")
    val turnLess = tx("Turn back a little", "थोड़ा वापस घुमाएँ")
    val moreLight = tx("More light on your face, please", "चेहरे पर थोड़ी और रोशनी चाहिए")
    val photoFront = tx("Front", "सामने")
    val photoLeft = tx("Left", "बाएँ")
    val photoRight = tx("Right", "दाएँ")
    val makeAvatar = tx("Make my 3D face", "मेरा 3D चेहरा बनाएँ")
    val avatarMaking = tx("Making your 3D preview…", "आपका 3D प्रीव्यू बन रहा है…")
    val avatarFailed = tx("Could not make the 3D preview. Try again in better light.", "3D प्रीव्यू नहीं बना। अच्छी रोशनी में फिर से कोशिश करें।")
    val avatarLabel = tx("Simplified 3D preview, not a scan", "सरल 3D प्रीव्यू, स्कैन नहीं")
    val avatarHint = tx("Drag to turn (±90°) · pinch to zoom", "घुमाने के लिए खींचें (±90°) · ज़ूम के लिए दो उँगलियाँ")
    val compare = tx("Compare", "तुलना")
    val compareOff = tx("One frame", "एक फ़्रेम")
    val pickSecond = tx("Pick a second frame to compare", "तुलना के लिए दूसरा फ़्रेम चुनें")
    val favourites = tx("Saved frames (this session)", "सेव किए फ़्रेम (इस सेशन में)")
    val deleteAvatar = tx("Delete 3D face", "3D चेहरा मिटाएँ")
    val deleteShort = tx("Delete", "मिटाएँ")
    val retake = tx("Retake", "फिर से लें")

    // shape / rim / colour / material / band labels (catalogue values are English ids); built once
    private val labels: Map<String, Txt> = mapOf(
        "round" to tx("Round", "गोल"),
        "oval" to tx("Oval", "अंडाकार"),
        "rectangle" to tx("Rectangle", "आयताकार"),
        "square" to tx("Square", "चौकोर"),
        "cat-eye" to tx("Cat-eye", "कैट-आई"),
        "aviator" to tx("Aviator", "एविएटर"),
        "geometric" to tx("Geometric", "ज्यामितीय"),
        "full" to tx("Full rim", "पूरा रिम"),
        "half" to tx("Half rim", "आधा रिम"),
        "rimless" to tx("Rimless", "बिना रिम"),
        "acetate" to tx("Acetate", "एसीटेट"),
        "metal" to tx("Metal", "मेटल"),
        "black" to tx("Black", "काला"),
        "grey" to tx("Grey", "स्लेटी"),
        "silver" to tx("Silver", "सिल्वर"),
        "white" to tx("White", "सफ़ेद"),
        "gold" to tx("Gold", "सुनहरा"),
        "brown" to tx("Brown", "भूरा"),
        "tortoise" to tx("Tortoise", "कछुआ-पैटर्न"),
        "red" to tx("Red", "लाल"),
        "orange" to tx("Orange", "नारंगी"),
        "beige" to tx("Beige", "बेज"),
        "pink" to tx("Pink", "गुलाबी"),
        "purple" to tx("Purple", "बैंगनी"),
        "blue" to tx("Blue", "नीला"),
        "green" to tx("Green", "हरा"),
        "clear" to tx("Clear", "पारदर्शी"),
        "budget" to tx("Up to ₹999", "₹999 तक"),
        "mid" to tx("₹1,000–1,999", "₹1,000–1,999"),
        "premium" to tx("₹2,000+", "₹2,000+"),
        "small" to tx("Small (lens ≤49)", "छोटा (लेंस ≤49)"),
        "medium" to tx("Medium (50–53)", "मध्यम (50–53)"),
        "large" to tx("Large (54+)", "बड़ा (54+)"),
    )

    fun label(value: String): Txt = labels[value] ?: Txt(value, value)

    /** Every fixed string, for the wording test. */
    val allFixed: List<Txt> get() = listOf(
        catalogTitle, tryIn3d, search, recommended, priceLow, priceHigh, eyeglasses, sunglasses, all, shape, rim, colour,
        material, price, size, clearFilters, count, noMatch, online, offline, demoCatalog, viewerHint, sizesEstimated,
        sizesMeasured, estimated, sizeLine, materialLine, colourLine, tryLive, tryAvatar, loading, downloadFailed,
        favourite, favourited, liveTitle, fitText, fitGood, fitSlightlyNarrow, fitSlightlyWide, fitTooNarrow, fitTooWide,
        scaleCard, scaleFace, scaleDefault, alignCheck, switchedTo2d, avatarTitle, avatarConsentTitle, avatarConsentBody, avatarAgree,
        avatarNo, stepFront, stepLeft, stepRight, avatarHold, turnMore, turnLess, moreLight, photoFront, photoLeft,
        photoRight, makeAvatar, avatarMaking, avatarFailed, avatarLabel, avatarHint, compare, compareOff, pickSecond, favourites, deleteAvatar, deleteShort, retake,
    )
}
