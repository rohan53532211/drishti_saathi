package com.drishtisaathi.i18n

import com.drishtisaathi.engine.AffectedEye
import com.drishtisaathi.engine.DistanceSituation
import com.drishtisaathi.engine.NearDistance
import com.drishtisaathi.engine.NearTask
import com.drishtisaathi.engine.PowerKnown
import com.drishtisaathi.engine.ProblemSince
import com.drishtisaathi.engine.RedFlagOnset
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.engine.anamnesis.ProfileLens
import com.drishtisaathi.engine.anamnesis.VisionZone

/**
 * Adaptive anamnesis and the power path (12 Nov). Section 6 wording: "estimated band", "starting trial lens";
 * never "prescription", "diagnosis", "power is" (पर्चा, निदान, पावर है, आपका नंबर). The slip is "पर्ची".
 */
object SAnamnesis {
    // ---- questions ----
    val qVisionProblem = tx("Do you have trouble seeing far, near, or both?", "क्या आपको दूर का, पास का, या दोनों देखने में दिक्कत है?")
    val qDistanceSituation = tx("Where do you notice it most?", "यह दिक्कत सबसे ज़्यादा कहाँ होती है?")
    val qDistanceSince = tx("Since when has far-away been blurry?", "दूर का धुंधला कब से दिख रहा है?")
    val qNearTask = tx("Which close work is hardest?", "पास का कौन सा काम सबसे मुश्किल लगता है?")
    val qNearDistance = tx("How far do you usually hold your phone or paper?", "आप मोबाइल या कागज़ आमतौर पर कितनी दूर रखते हैं?")
    val qHappyWithGlasses = tx("Are you happy with your current glasses?", "क्या आप अपने मौजूदा चश्मे से संतुष्ट हैं?")
    val qRedFlagOnset = tx("When did this start?", "यह कब शुरू हुआ?")
    val qRedFlagEye = tx("Which eye?", "कौन सी आँख में?")
    val qPowerKnown = tx("Do you remember your glasses power, or have the slip?", "क्या आपको अपने चश्मे का नंबर याद है, या पर्ची है?")

    fun visionProblem(v: VisionProblem) = VISION_PROBLEM.getValue(v)
    private val VISION_PROBLEM = mapOf(
        VisionProblem.DISTANCE to tx("Far", "दूर का"),
        VisionProblem.NEAR to tx("Near", "पास का"),
        VisionProblem.BOTH to tx("Both", "दोनों"),
        VisionProblem.NONE to tx("No trouble", "कोई दिक्कत नहीं"),
    )
    fun distanceSituation(v: DistanceSituation) = SITUATION.getValue(v)
    private val SITUATION = mapOf(
        DistanceSituation.DRIVING to tx("Driving", "गाड़ी चलाते समय"),
        DistanceSituation.TV to tx("Watching TV", "टीवी देखते समय"),
        DistanceSituation.SIGNBOARDS to tx("Reading signboards / bus numbers", "बोर्ड / बस के नंबर पढ़ते समय"),
        DistanceSituation.CLASSROOM_BOARD to tx("Classroom board", "क्लास का बोर्ड"),
        DistanceSituation.OTHER to tx("Something else", "कुछ और"),
    )
    fun since(v: ProblemSince) = SINCE.getValue(v)
    private val SINCE = mapOf(
        ProblemSince.UNDER_6_MONTHS to tx("Less than 6 months", "6 महीने से कम"),
        ProblemSince.MONTHS_6_TO_24 to tx("6 months to 2 years", "6 महीने से 2 साल"),
        ProblemSince.OVER_2_YEARS to tx("More than 2 years", "2 साल से ज़्यादा"),
    )
    fun nearTask(v: NearTask) = NEAR_TASK.getValue(v)
    private val NEAR_TASK = mapOf(
        NearTask.PHONE to tx("Mobile phone", "मोबाइल"),
        NearTask.NEWSPAPER to tx("Newspaper / books", "अख़बार / किताब"),
        NearTask.SEWING to tx("Sewing / fine work", "सिलाई / बारीक काम"),
        NearTask.ACCOUNTS to tx("Accounts / bills", "हिसाब-किताब / बिल"),
        NearTask.OTHER to tx("Something else", "कुछ और"),
    )
    fun nearDistance(v: NearDistance) = NEAR_DISTANCE.getValue(v)
    private val NEAR_DISTANCE = mapOf(
        NearDistance.VERY_CLOSE to tx("Very close (about 25 cm)", "बहुत पास (लगभग 25 cm)"),
        NearDistance.NORMAL to tx("Normal (about 35 cm)", "सामान्य (लगभग 35 cm)"),
        NearDistance.ARM_LENGTH to tx("Far, almost at arm's length", "दूर, लगभग हाथ भर"),
    )
    fun onset(v: RedFlagOnset) = ONSET.getValue(v)
    private val ONSET = mapOf(
        RedFlagOnset.TODAY to tx("Today", "आज"),
        RedFlagOnset.THIS_WEEK to tx("This week", "इस हफ़्ते"),
        RedFlagOnset.LONGER to tx("Longer ago", "उससे पहले"),
    )
    fun eye(v: AffectedEye) = EYE.getValue(v)
    private val EYE = mapOf(
        AffectedEye.RIGHT to tx("Right eye", "दायीं आँख"),
        AffectedEye.LEFT to tx("Left eye", "बायीं आँख"),
        AffectedEye.BOTH to tx("Both eyes", "दोनों आँखें"),
    )
    fun powerKnown(v: PowerKnown) = POWER_KNOWN.getValue(v)
    private val POWER_KNOWN = mapOf(
        PowerKnown.YES to tx("Yes, I know it / have the slip", "हाँ, याद है / पर्ची है"),
        PowerKnown.NOT_SURE to tx("Not sure", "पक्का नहीं पता"),
        PowerKnown.NO to tx("No", "नहीं"),
    )

    // ---- power path ----
    val compareTitle = tx("Quick check of the current glasses (optional)", "मौजूदा चश्मे की झटपट जाँच (वैकल्पिक)")
    val compareBody = tx(
        "The glasses are old or vision is still blurry. A 1-minute phone check can be shown to staff next to the known power.",
        "चश्मा पुराना है या अब भी धुंधला दिखता है। 1 मिनट की फ़ोन जाँच स्टाफ़ को पुराने नंबर के साथ दिखाई जा सकती है।",
    )
    val compareYes = tx("Do the quick check", "झटपट जाँच करें")
    val compareNo = tx("Skip", "छोड़ें")

    val quickTitle = tx("Quick estimate", "झटपट अनुमान")
    val quickIntroFar = tx(
        "Glasses off. Cover one eye. Hold the phone close so the letters are clear, then slowly move it away. When the letters start to blur, tap Blur or say \"dhundhla\".",
        "चश्मा उतारें। एक आँख ढकें। फ़ोन पास रखें ताकि अक्षर साफ़ दिखें, फिर धीरे-धीरे दूर ले जाएँ। जैसे ही अक्षर धुंधले होने लगें, \"धुंधला\" बटन दबाएँ या \"धुंधला\" बोलें।",
    )
    val quickIntroNear = tx(
        "Both eyes open. Start at arm's length and slowly bring the phone closer. When the small letters blur, tap Blur or say \"dhundhla\".",
        "दोनों आँखें खुली रखें। हाथ भर दूरी से शुरू करें और फ़ोन धीरे-धीरे पास लाएँ। जैसे ही छोटे अक्षर धुंधले हों, \"धुंधला\" दबाएँ या \"धुंधला\" बोलें।",
    )
    val quickGlassesOff = tx("Take off your glasses.", "चश्मा उतार दें।")
    val quickBringClose = tx("Bring the phone close until the letters are clear.", "फ़ोन पास लाएँ, जब तक अक्षर साफ़ न दिखें।")
    val quickMoveAway = tx("Now slowly move it away.", "अब धीरे-धीरे दूर ले जाएँ।")
    val quickStartFar = tx("Hold the phone at arm's length.", "फ़ोन हाथ भर दूर रखें।")
    val quickMoveIn = tx("Now slowly bring it closer.", "अब धीरे-धीरे पास लाएँ।")
    val quickLive = tx("If blur starts here: about %s D", "अगर यहाँ धुंधला शुरू हो: लगभग %s D")
    val quickBlur = tx("Blur (dhundhla)", "धुंधला")
    val quickStillClear = tx("Still clear at arm's length", "हाथ भर दूरी पर भी साफ़")
    val quickFrozen = tx("Blur at %d cm, about %s D (approximate, confirm with trial lens)", "%d cm पर धुंधला, लगभग %s D (अनुमान, ट्रायल लेंस से पक्का करें)")
    val quickNoMyopia = tx("No myopia found at arm's length, check with trial lens", "हाथ भर दूरी तक कोई कमज़ोर दूर-नज़र नहीं मिली, ट्रायल लेंस से जाँचें")
    val quickNearFrozen = tx("Near point %d cm: reading add about %s D. Confirm with a ready-reader at the counter.", "पास का बिंदु %d cm: पढ़ने का ऐड लगभग %s D। काउंटर पर रेडी-रीडर से पक्का करें।")
    val quickNearNone = tx("Near point not found: confirm with a ready-reader at the counter.", "पास का बिंदु नहीं मिला: काउंटर पर रेडी-रीडर से पक्का करें।")
    val quickLongSight = tx("Possible long-sightedness, check with trial lens", "दूर-दृष्टि (लॉन्ग-साइट) हो सकती है, ट्रायल लेंस से जाँचें")
    val quickNext = tx("Next", "आगे")
    val quickDone = tx("Done: continue the questions", "हो गया: सवाल जारी रखें")
    val quickSkip = tx("Skip the quick estimate", "झटपट अनुमान छोड़ें")
    val quickNearTitle = tx("Reading distance", "पढ़ने की दूरी")
    val quickNoCamera = tx("The camera is needed to measure the distance.", "दूरी नापने के लिए कैमरा ज़रूरी है।")

    val fullFarTest = tx("Staff: also run the full far-point test", "स्टाफ़: पूरा दूर-बिंदु टेस्ट भी करें")

    // ---- summary / PDF ----
    val powerSourceLine = tx("Power source: %s", "पावर का स्रोत: %s")
    val sourceKnown = tx("customer's known power", "ग्राहक का बताया नंबर")
    val sourcePhone = tx("phone estimate, approximate", "फ़ोन का अनुमान, लगभग")
    val compareHeading = tx("Phone quick check (staff only, for comparison)", "फ़ोन की झटपट जाँच (सिर्फ़ स्टाफ़, तुलना के लिए)")
    val compareEye = tx("%s: phone about %s D, known %s D", "%s: फ़ोन लगभग %s D, बताया गया %s D")
    val compareEyeNoMyopia = tx("%s: phone found no myopia at arm's length, known %s D", "%s: फ़ोन को हाथ भर तक कमज़ोर दूर-नज़र नहीं मिली, बताया गया %s D")
    val quickHeading = tx("Phone quick estimate (approximate, confirm with trial lens)", "फ़ोन का झटपट अनुमान (लगभग, ट्रायल लेंस से पक्का करें)")

    val profileTitle = tx("Daily vision profile", "रोज़ की नज़र का प्रोफ़ाइल")
    val profileNear = tx("Near (under 40 cm): about %.1f h a day", "पास (40 cm से कम): रोज़ लगभग %.1f घंटे")
    val profileIntermediate = tx("Intermediate (40–100 cm): about %.1f h a day", "बीच की दूरी (40–100 cm): रोज़ लगभग %.1f घंटे")
    val profileDistance = tx("Distance: about %.1f h a day", "दूर: रोज़ लगभग %.1f घंटे")
    val profileDominant = tx("Main zone: %s", "मुख्य दूरी: %s")
    val profileLens = tx("This points to: %s", "इसके हिसाब से: %s")

    fun zone(v: VisionZone) = ZONE.getValue(v)
    private val ZONE = mapOf(
        VisionZone.NEAR to tx("near", "पास"),
        VisionZone.INTERMEDIATE to tx("intermediate (screens)", "बीच की दूरी (स्क्रीन)"),
        VisionZone.DISTANCE to tx("distance", "दूर"),
        VisionZone.MIXED to tx("mixed, no single zone", "मिला-जुला, कोई एक नहीं"),
    )
    fun profileLens(v: ProfileLens) = PROFILE_LENS.getValue(v)
    private val PROFILE_LENS = mapOf(
        ProfileLens.SINGLE_VISION to tx("single vision", "सिंगल विज़न"),
        ProfileLens.READING to tx("reading glasses", "पढ़ने का चश्मा"),
        ProfileLens.OFFICE_COMPUTER to tx("office / computer lens", "ऑफ़िस / कंप्यूटर लेंस"),
        ProfileLens.PROGRESSIVE to tx("progressive", "प्रोग्रेसिव"),
    )
}
