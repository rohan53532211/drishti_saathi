package com.drishtisaathi.voice

import com.drishtisaathi.interview.ChoiceContext
import com.drishtisaathi.interview.Question
import com.drishtisaathi.interview.Questions
import java.io.File

object VoiceTestSupport {
    val lexiconText: String by lazy { File("src/main/assets/${VoiceLexicon.ASSET}").readText(Charsets.UTF_8) }
    val lexicon: VoiceLexicon by lazy { VoiceLexicon.fromQuestionsFile(lexiconText) }
    val parser: VoiceParser by lazy { VoiceParser(lexicon) }
    val ctx = ChoiceContext(budgetLowCap = 2000, budgetMidCap = 5000)

    /** The yes/no old-numbers question left the interview with the power path (12 Nov); its voice handling remains. */
    private val legacyOldNumbers = Question.OldNumbers(Questions.KNOWN_RX, com.drishtisaathi.i18n.S.qKnownRx)

    fun q(id: String): Question = Questions.all.firstOrNull { it.id == id } ?: legacyOldNumbers.takeIf { id == it.id }
        ?: error("no question $id")
}
