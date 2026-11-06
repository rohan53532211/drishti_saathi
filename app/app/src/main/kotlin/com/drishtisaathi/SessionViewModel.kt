package com.drishtisaathi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.drishtisaathi.engine.DrishtiEngine
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.KnownRx
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.SessionResult
import com.drishtisaathi.engine.StaffMeasurements
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.interview.ChoiceContext
import com.drishtisaathi.interview.Question
import com.drishtisaathi.interview.Questions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UiState(
    val lang: Lang = Lang.HI,
    val staffMode: Boolean = false,
    val answers: InterviewAnswers = InterviewAnswers(),
    /** Question ids answered (a "Don't know" counts as answered). */
    val answered: Set<String> = emptySet(),
    val staff: StaffMeasurements = StaffMeasurements(),
    val questionIndex: Int = 0,
    /** True while editing one answer from the confirmation screen. */
    val editingFromConfirm: Boolean = false,
    val result: SessionResult? = null,
    val sessionId: Long? = null,
) {
    val visibleQuestions: List<Question> get() = Questions.visible(answers)
    val currentQuestion: Question? get() = visibleQuestions.getOrNull(questionIndex)
    val allAnswered: Boolean get() = visibleQuestions.all { it.id in answered }
}

class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private fun asset(name: String) = getApplication<Application>().assets.open(name).bufferedReader().use { it.readText() }

    val rules: RulesConfig by lazy { RulesConfig.parse(asset("rules.json")) }
    val engine: DrishtiEngine by lazy {
        DrishtiEngine.fromJson(asset("rules.json"), asset("anthro_priors.json"), asset("frames.json"))
    }
    val choiceContext: ChoiceContext by lazy {
        ChoiceContext(rules.frameScoring.budgetMaxPriceInrLow, rules.frameScoring.budgetMaxPriceInrMid)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setLang(lang: Lang) = _state.update { it.copy(lang = lang) }
    fun setStaffMode(on: Boolean) = _state.update { it.copy(staffMode = on) }

    /** Fresh session; keeps language and staff mode. */
    fun startNew() = _state.update { UiState(lang = it.lang, staffMode = it.staffMode) }

    // ---- interview -------------------------------------------------------------------------

    /** Records an answer. Returns true when the interview should go to the confirmation screen. */
    fun answer(questionId: String, update: (InterviewAnswers) -> InterviewAnswers): Boolean {
        var toConfirm = false
        _state.update { s ->
            val answers = update(s.answers)
            val visible = Questions.visible(answers)
            val answered = (s.answered + questionId).filter { id -> visible.any { it.id == id } }.toSet()
            val idx = visible.indexOfFirst { it.id == questionId }
            val nextIdx = idx + 1
            toConfirm = s.editingFromConfirm && visible.all { it.id in answered } || nextIdx >= visible.size
            s.copy(
                answers = answers, answered = answered,
                questionIndex = if (toConfirm) s.questionIndex else nextIdx,
                editingFromConfirm = if (toConfirm) false else s.editingFromConfirm,
                result = null,
            )
        }
        return toConfirm
    }

    fun setKnownRx(rx: KnownRx?) {
        _state.update { it.copy(answers = it.answers.copy(knownRx = rx)) }
    }

    /** Back within the interview. Returns false when already at the first question. */
    fun previousQuestion(): Boolean {
        val s = _state.value
        if (s.questionIndex <= 0) return false
        _state.update { it.copy(questionIndex = it.questionIndex - 1) }
        return true
    }

    fun editQuestion(questionId: String) = _state.update { s ->
        s.copy(questionIndex = s.visibleQuestions.indexOfFirst { it.id == questionId }.coerceAtLeast(0), editingFromConfirm = true)
    }

    fun setStaffMeasurements(m: StaffMeasurements) = _state.update { it.copy(staff = m, result = null) }

    // ---- results ---------------------------------------------------------------------------

    fun computeResult(): SessionResult {
        val s = _state.value
        val result = engine.evaluate(s.answers, s.staff)
        _state.update { it.copy(result = result) }
        return result
    }
}
