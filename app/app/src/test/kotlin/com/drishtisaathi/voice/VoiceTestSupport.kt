package com.drishtisaathi.voice

import com.drishtisaathi.interview.ChoiceContext
import com.drishtisaathi.interview.Question
import com.drishtisaathi.interview.Questions
import java.io.File

object VoiceTestSupport {
    val lexiconText: String by lazy { File("src/main/assets/${VoiceLexicon.ASSET}").readText(Charsets.UTF_8) }
    val lexicon: VoiceLexicon by lazy { VoiceLexicon.parse(lexiconText) }
    val parser: VoiceParser by lazy { VoiceParser(lexicon) }
    val ctx = ChoiceContext(budgetLowCap = 2000, budgetMidCap = 5000)

    fun q(id: String): Question = Questions.all.first { it.id == id }
}
