package com.drishtisaathi.voice

import com.drishtisaathi.engine.Budget
import com.drishtisaathi.engine.ColourPref
import com.drishtisaathi.engine.LookPref
import com.drishtisaathi.engine.Occupation
import com.drishtisaathi.engine.ReadingFrequency
import com.drishtisaathi.engine.ScreenHours
import com.drishtisaathi.engine.StylePref
import com.drishtisaathi.voice.VoiceTestSupport.ctx
import com.drishtisaathi.voice.VoiceTestSupport.parser
import com.drishtisaathi.voice.VoiceTestSupport.q
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceParserTest {

    private fun yn(text: String): Boolean? =
        ((parser.parseYesNo(text) as? ParseResult.Parsed)?.answer as? VoiceAnswer.YesNo)?.value

    private fun age(text: String): ParseResult = parser.parse(q("age"), text, ctx)
    private fun ageValue(text: String): Int? = ((age(text) as? ParseResult.Parsed)?.answer as? VoiceAnswer.Number)?.value

    private fun choice(qid: String, text: String): Any? =
        ((parser.parse(q(qid), text, ctx) as? ParseResult.Parsed)?.answer as? VoiceAnswer.Choice)?.option

    // ---- yes / no ----

    @Test fun `Hindi yes variants`() {
        for (t in listOf("हाँ", "हां", "हाँ जी", "जी हाँ", "जी", "हां जी", "बिल्कुल", "हाँ, सही है", "हाँ ना", "ज़रूर"))
            assertEquals(t, true, yn(t))
    }

    @Test fun `Hinglish and English yes`() {
        for (t in listOf("haan", "Haan ji", "ji", "yes", "Yeah", "Yes, correct", "ok", "theek hai", "यस"))
            assertEquals(t, true, yn(t))
    }

    @Test fun `no variants`() {
        for (t in listOf("नहीं", "नही", "ना", "जी नहीं", "नहीं जी", "बिल्कुल नहीं", "no", "nahi", "Nahin", "No no", "नो", "गलत"))
            assertEquals(t, false, yn(t))
    }

    @Test fun `negated sentences are no`() {
        assertEquals(false, yn("नहीं, दूर का धुंधला नहीं"))
        assertEquals(false, yn("दूर का धुंधला नहीं दिखता"))
        assertEquals(false, yn("मैं गाड़ी नहीं चलाता"))
        assertEquals(false, yn("सही नहीं"))
        assertEquals(false, yn("nahi, mujhe sugar nahi hai"))
        assertEquals(false, yn("ऐसी कोई दिक्कत नहीं होती"))
    }

    @Test fun `affirmative sentences without yes word are a weak yes`() {
        val r = parser.parseYesNo("मैं रात में गाड़ी चलाता हूँ")
        assertTrue(r is ParseResult.Parsed)
        r as ParseResult.Parsed
        assertEquals(VoiceAnswer.YesNo(true), r.answer)
        assertTrue(r.confidence < 0.7)
        assertEquals(true, yn("हाँ, पास का पढ़ने में दिक्कत होती है"))
        assertEquals(true, yn("कभी कभी"))
    }

    @Test fun `yes and no together is ambiguous`() {
        assertTrue(parser.parseYesNo("हाँ, या नहीं") is ParseResult.Ambiguous)
        // A yes immediately corrected by a negation is a no.
        assertEquals(false, yn("हाँ… नहीं नहीं"))
    }

    @Test fun `bare है counts as a weak yes, read back for confirmation`() {
        assertEquals(true, yn("मुझे शुगर है"))
    }

    @Test fun `don't know is not an answer to yes-no`() {
        assertEquals(ParseResult.NoMatch, parser.parseYesNo("पता नहीं"))
        assertEquals(ParseResult.NoMatch, parser.parseYesNo("I don't know"))
    }

    @Test fun `unrelated speech is no match`() {
        assertEquals(ParseResult.NoMatch, parser.parseYesNo("आज मौसम कैसा"))
        assertEquals(ParseResult.NoMatch, parser.parseYesNo(""))
        assertEquals(ParseResult.NoMatch, parser.parseYesNo("   ।  "))
    }

    @Test fun `confidence grows with keyword coverage`() {
        val short = parser.parseYesNo("हाँ") as ParseResult.Parsed
        val long = parser.parseYesNo("हाँ मुझे लगता है कि शायद") as ParseResult.Parsed
        assertEquals(1.0, short.confidence, 1e-9)
        assertTrue(long.confidence < short.confidence)
    }

    // ---- numbers ----

    @Test fun `age from digits and Hindi words`() {
        assertEquals(35, ageValue("35"))
        assertEquals(35, ageValue("पैंतीस"))
        assertEquals(35, ageValue("पैंतीस साल"))
        assertEquals(35, ageValue("मेरी उम्र 35 साल है"))
        assertEquals(35, ageValue("३५"))
        assertEquals(35, ageValue("35साल"))
        assertEquals(62, ageValue("बासठ बरस"))
        assertEquals(79, ageValue("उनासी"))
        assertEquals(9, ageValue("नौ साल"))
        assertEquals(28, ageValue("अट्ठाईस"))
    }

    @Test fun `age from English and romanised words`() {
        assertEquals(35, ageValue("thirty five"))
        assertEquals(35, ageValue("Thirty-five years"))
        assertEquals(40, ageValue("forty"))
        assertEquals(17, ageValue("I am seventeen"))
        assertEquals(35, ageValue("paintees saal"))
    }

    @Test fun `hundreds`() {
        assertEquals(100, ageValue("सौ साल"))
        assertEquals(105, ageValue("एक सौ पांच"))
        assertEquals(110, ageValue("one hundred ten"))
    }

    @Test fun `out of range age is no match`() {
        assertEquals(ParseResult.NoMatch, age("500"))
        assertEquals(ParseResult.NoMatch, age("दो सौ"))
        assertEquals(ParseResult.NoMatch, age("उम्र याद है"))
    }

    @Test fun `two different numbers are ambiguous`() {
        val r = age("पैंतीस या छत्तीस")
        assertTrue(r is ParseResult.Ambiguous)
        assertEquals(listOf(VoiceAnswer.Number(35), VoiceAnswer.Number(36)), (r as ParseResult.Ambiguous).candidates)
    }

    @Test fun `don't know only where allowed`() {
        assertEquals(ParseResult.NoMatch, age("पता नहीं"))
        val r = parser.parse(q("glassesAgeYears"), "पता नहीं", ctx)
        assertEquals(VoiceAnswer.Number(null), (r as ParseResult.Parsed).answer)
        val r2 = parser.parse(q("glassesAgeYears"), "yaad nahi", ctx)
        assertEquals(VoiceAnswer.Number(null), (r2 as ParseResult.Parsed).answer)
        val r3 = parser.parse(q("glassesAgeYears"), "दो साल", ctx)
        assertEquals(VoiceAnswer.Number(2), (r3 as ParseResult.Parsed).answer)
    }

    @Test fun `number extraction composes thousands and hundreds`() {
        assertEquals(listOf(2500), parser.extractNumbers("दो हज़ार पांच सौ"))
        assertEquals(listOf(1500), parser.extractNumbers("1,500 रुपये"))
        assertEquals(listOf(1500), parser.extractNumbers("पंद्रह सौ"))
        assertEquals(listOf(3000), parser.extractNumbers("3 हजार"))
        assertEquals(listOf(2, 3), parser.extractNumbers("दो तीन साल"))
        assertEquals(listOf(20, 5), parser.extractNumbers("20 5"))
    }

    // ---- choices ----

    @Test fun `occupation in Hindi, Hinglish and English`() {
        assertEquals(Occupation.SHOP_BUSINESS, choice("occupation", "मेरी दुकान है"))
        assertEquals(Occupation.OFFICE_COMPUTER, choice("occupation", "ऑफिस में कंप्यूटर पर काम"))
        assertEquals(Occupation.FINE_HANDWORK, choice("occupation", "सिलाई का काम करती हूँ"))
        assertEquals(Occupation.DRIVER, choice("occupation", "main driver hoon"))
        assertEquals(Occupation.FIELD_OUTDOOR, choice("occupation", "खेती"))
        assertEquals(Occupation.HOMEMAKER, choice("occupation", "घर का काम"))
        assertEquals(Occupation.STUDENT, choice("occupation", "I am a student"))
        assertEquals(Occupation.SPORT_ACTIVE, choice("occupation", "पुलिस में हूँ"))
        assertEquals(Occupation.OTHER, choice("occupation", "कुछ और"))
    }

    @Test fun `negated option is dropped`() {
        assertEquals(Occupation.SHOP_BUSINESS, choice("occupation", "कंप्यूटर नहीं, दुकान"))
        assertEquals(Occupation.SHOP_BUSINESS, choice("occupation", "not office, shop"))
    }

    @Test fun `two options is ambiguous`() {
        val r = parser.parse(q("occupation"), "दुकान और खेती", ctx)
        assertTrue(r is ParseResult.Ambiguous)
        assertEquals(2, (r as ParseResult.Ambiguous).candidates.size)
    }

    @Test fun `screen hours by words and numbers`() {
        assertEquals(ScreenHours.H0_2, choice("screenHours", "बहुत कम"))
        assertEquals(ScreenHours.H0_2, choice("screenHours", "दो घंटे"))
        assertEquals(ScreenHours.H0_2, choice("screenHours", "ज़्यादा नहीं"))
        assertEquals(ScreenHours.H2_4, choice("screenHours", "तीन घंटे"))
        assertEquals(ScreenHours.H2_4, choice("screenHours", "दो-तीन घंटे"))
        assertEquals(ScreenHours.H4_PLUS, choice("screenHours", "6 hours"))
        assertEquals(ScreenHours.H4_PLUS, choice("screenHours", "पूरा दिन"))
        assertEquals(ScreenHours.H4_PLUS, choice("screenHours", "चार से ज़्यादा"))
    }

    @Test fun `budget by words and rupee amounts against the caps`() {
        assertEquals(Budget.LOW, choice("budget", "सस्ता"))
        assertEquals(Budget.MID, choice("budget", "medium"))
        assertEquals(Budget.HIGH, choice("budget", "प्रीमियम"))
        assertEquals(Budget.LOW, choice("budget", "पंद्रह सौ"))
        assertEquals(Budget.LOW, choice("budget", "2000 रुपये"))
        assertEquals(Budget.MID, choice("budget", "तीन हज़ार"))
        assertEquals(Budget.HIGH, choice("budget", "10000"))
        assertEquals(null, choice("budget", "दो")) // not a rupee amount
    }

    @Test fun `style colour look reading`() {
        assertEquals(StylePref.MINIMAL, choice("style", "हल्का सादा"))
        assertEquals(StylePref.BOLD, choice("style", "मोटा फ़्रेम"))
        assertEquals(StylePref.ANY, choice("style", "कोई भी दिखाइए"))
        assertEquals(ColourPref.DARK, choice("colourPreference", "काला"))
        assertEquals(ColourPref.METALLIC, choice("colourPreference", "golden"))
        assertEquals(ColourPref.ANY, choice("colourPreference", "any colour"))
        assertEquals(ColourPref.COLOURFUL, choice("colourPreference", "रंग-बिरंगा"))
        assertEquals(LookPref.PROFESSIONAL, choice("lookPreference", "ऑफिस के लिए"))
        assertEquals(LookPref.BOTH, choice("lookPreference", "काम और रोज़ दोनों"))
        assertEquals(ReadingFrequency.RARELY, choice("readingFrequency", "कभी-कभार"))
        assertEquals(ReadingFrequency.SOMETIMES, choice("readingFrequency", "कभी कभी"))
        assertEquals(ReadingFrequency.DAILY_LONG, choice("readingFrequency", "रोज़ घंटों पढ़ता हूँ"))
    }

    @Test fun `n-best list uses the first hypothesis that parses and scales by recogniser confidence`() {
        val r = parser.parse(q("age"), listOf("उमर बताओ", "पैंतीस"), ctx, listOf(0.9f, 0.5f))
        r as ParseResult.Parsed
        assertEquals(VoiceAnswer.Number(35), r.answer)
        assertEquals(0.45, r.confidence, 1e-6)
        assertEquals(ParseResult.NoMatch, parser.parse(q("age"), listOf("कुछ नहीं", "हम्म"), ctx))
    }

    @Test fun `old numbers question is yes-no`() {
        assertEquals(VoiceAnswer.YesNo(true), (parser.parse(q("knownRx"), "हाँ है", ctx) as ParseResult.Parsed).answer)
        assertEquals(VoiceAnswer.YesNo(false), (parser.parse(q("knownRx"), "नहीं है", ctx) as ParseResult.Parsed).answer)
    }
}
