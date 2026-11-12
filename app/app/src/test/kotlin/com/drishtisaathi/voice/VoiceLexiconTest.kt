package com.drishtisaathi.voice

import com.drishtisaathi.interview.Question
import com.drishtisaathi.interview.Questions
import com.drishtisaathi.voice.VoiceTestSupport.lexicon
import com.drishtisaathi.voice.VoiceTestSupport.lexiconText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoiceLexiconTest {

    @Test fun `asset parses and validates`() {
        assertEquals(emptyList<String>(), lexicon.validate())
        assertEquals(1, lexicon.version)
    }

    @Test fun `every choice option of every question has a Hindi and an English keyword`() {
        val choiceQs = Questions.all.filterIsInstance<Question.Choice<*>>()
        assertTrue(choiceQs.size >= 7)
        for (q in choiceQs) {
            val lq = lexicon.choices[q.id] ?: run { fail("no lexicon for ${q.id}"); return }
            for (opt in q.options) {
                val name = VoiceParser.optionName(opt!!)
                val o = lq.options[name] ?: run { fail("${q.id}: option $name missing"); return }
                assertTrue("${q.id}.$name hi", o.hi.any { TextNormalizer.isDevanagari(it) })
                assertTrue("${q.id}.$name en", o.en.any { TextNormalizer.isLatin(it) })
            }
        }
    }

    @Test fun `lexicon has no questions or options the app does not have`() {
        val choiceQs = Questions.all.filterIsInstance<Question.Choice<*>>().associateBy { it.id }
        for ((qid, lq) in lexicon.choices) {
            val q = choiceQs[qid] ?: run { fail("lexicon question $qid not in Questions"); return }
            val names = q.options.map { VoiceParser.optionName(it!!) }.toSet()
            assertEquals("options of $qid", names, lq.options.keys)
        }
    }

    @Test fun `Hindi number words cover 0 to 99`() {
        val devanagari = lexicon.numbers.words.filterKeys { TextNormalizer.isDevanagari(it) }.values.toSet()
        val missing = (0..99).filterNot { it in devanagari }
        assertEquals(emptyList<Int>(), missing)
    }

    @Test fun `unknown keys are rejected`() {
        val bad = lexiconText.replaceFirst("\"version\": 1,", "\"version\": 1, \"surprise\": true,")
        assertThrows { VoiceLexicon.fromQuestionsFile(bad) }
    }

    @Test fun `same keyword for two options is rejected`() {
        val bad = lexiconText.replaceFirst("\"black\", \"dark\", \"kala\"", "\"black\", \"dark\", \"kala\", \"brown\"")
        val e = assertThrows { VoiceLexicon.fromQuestionsFile(bad) }
        assertTrue(e.message!!.contains("brown"))
    }

    @Test fun `Latin text in a hi list is rejected`() {
        val bad = lexiconText.replaceFirst("\"काला\",", "\"kaala\",")
        assertThrows { VoiceLexicon.fromQuestionsFile(bad) }
    }

    @Test fun `a word that is both yes and no is rejected`() {
        val bad = lexiconText.replaceFirst("\"yes\", \"yeah\"", "\"yes\", \"nope\"")
        assertThrows { VoiceLexicon.fromQuestionsFile(bad) }
    }

    @Test fun `range options need a range`() {
        val bad = lexiconText.replaceFirst("\"range\": [3, 4]", "\"priority\": 0")
        assertThrows { VoiceLexicon.fromQuestionsFile(bad) }
    }

    @Test fun `overlapping ranges are rejected`() {
        val bad = lexiconText.replaceFirst("\"range\": [3, 4]", "\"range\": [2, 4]")
        assertThrows { VoiceLexicon.fromQuestionsFile(bad) }
    }

    // ---- normaliser ----

    @Test fun `normaliser unifies nukta, chandrabindu, digits and punctuation`() {
        assertEquals(TextNormalizer.normalize("हां"), TextNormalizer.normalize("हाँ"))
        assertEquals(TextNormalizer.normalize("ज्यादा"), TextNormalizer.normalize("ज़्यादा"))
        assertEquals("35 साल", TextNormalizer.normalize("३५ साल।"))
        assertEquals("नहीं दूर का धुंधला नहीं", TextNormalizer.normalize("नहीं, दूर का धुंधला नहीं!"))
        assertEquals("dont know", TextNormalizer.normalize("Don't know"))
        assertEquals("1500 रुपये", TextNormalizer.normalize("1,500 रुपये"))
        assertEquals(listOf("रंग", "बिरंगा"), TextNormalizer.tokens("रंग-बिरंगा"))
    }

    @Test fun `script checks`() {
        assertTrue(TextNormalizer.isDevanagari("हाँ"))
        assertFalse(TextNormalizer.isDevanagari("haan"))
        assertTrue(TextNormalizer.isLatin("haan ji"))
        assertFalse(TextNormalizer.isLatin("हाँ"))
    }

    private fun assertThrows(block: () -> Unit): Throwable {
        try {
            block()
        } catch (t: Throwable) {
            return t
        }
        fail("expected an exception")
        throw AssertionError()
    }
}
