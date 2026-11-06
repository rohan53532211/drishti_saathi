package com.drishtisaathi

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drishtisaathi.data.AppDatabase
import com.drishtisaathi.data.SessionCodec
import com.drishtisaathi.data.SessionEntity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import com.drishtisaathi.pdf.PdfContent
import com.drishtisaathi.pdf.PdfExporter
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Staff entry after the shop's own trial-lens test: the paired record for the learning loop. */
data class Verification(
    val finalTrialRight: String = "",
    val finalTrialLeft: String = "",
    val finalAdd: String = "",
    val pdMeterMm: Double? = null,
    val verifiedAt: Long? = null,
)

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
    val createdAt: Long? = null,
    val verification: Verification = Verification(),
    val consentAt: Long? = null,
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

    private val dao = AppDatabase.get(app).sessions()
    val sessions: Flow<List<SessionEntity>> = dao.observeAll()

    /** The pending save; delete waits for it so a fast "Delete" never leaves a row behind. */
    private var saveJob: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setLang(lang: Lang) = _state.update { it.copy(lang = lang) }
    fun setStaffMode(on: Boolean) = _state.update { it.copy(staffMode = on) }

    /** Fresh session; keeps language and staff mode. */
    fun startNew() = _state.update { UiState(lang = it.lang, staffMode = it.staffMode) }

    /** Customer tapped "I agree": a fresh session starts with the consent time recorded. */
    fun giveConsent() = _state.update { UiState(lang = it.lang, staffMode = it.staffMode, consentAt = System.currentTimeMillis()) }

    // ---- interview -------------------------------------------------------------------------

    /** Records an answer. Returns true when the interview should go to the confirmation screen. */
    fun answer(questionId: String, update: (InterviewAnswers) -> InterviewAnswers): Boolean {
        var toConfirm = false
        _state.update { s ->
            val answers = update(s.answers)
            val visible = Questions.visible(answers)
            val answered = (s.answered + questionId).filter { id -> visible.any { it.id == id } }.toSet()
            val nextIdx = visible.indexOfFirst { it.id == questionId } + 1
            toConfirm = (s.editingFromConfirm && visible.all { it.id in answered }) || nextIdx >= visible.size
            s.copy(
                answers = answers, answered = answered,
                questionIndex = if (toConfirm) s.questionIndex else nextIdx,
                editingFromConfirm = if (toConfirm) false else s.editingFromConfirm,
                result = null,
            )
        }
        return toConfirm
    }

    fun setKnownRx(rx: KnownRx?) = _state.update { it.copy(answers = it.answers.copy(knownRx = rx)) }

    /** Back within the interview. Returns false when already at the first question. */
    fun previousQuestion(): Boolean {
        if (_state.value.questionIndex <= 0) return false
        _state.update { it.copy(questionIndex = it.questionIndex - 1) }
        return true
    }

    fun editQuestion(questionId: String) = _state.update { s ->
        s.copy(questionIndex = s.visibleQuestions.indexOfFirst { it.id == questionId }.coerceAtLeast(0), editingFromConfirm = true)
    }

    fun setStaffMeasurements(m: StaffMeasurements) = _state.update { it.copy(staff = m, result = null) }

    // ---- results + storage -------------------------------------------------------------------

    /** Runs the engine and saves the session (updates the same row if answers were edited after results). */
    fun computeResult(): SessionResult {
        val s = _state.value
        val result = engine.evaluate(s.answers, s.staff)
        val createdAt = s.createdAt ?: System.currentTimeMillis()
        _state.update { it.copy(result = result, createdAt = createdAt) }
        val previous = saveJob
        saveJob = viewModelScope.launch {
            previous?.join()
            val current = _state.value.sessionId
            val entity = SessionEntity(
                id = current ?: 0,
                createdAt = createdAt,
                lang = s.lang.name,
                phoneModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                answersJson = SessionCodec.encode(s.answers),
                staffJson = SessionCodec.encode(s.staff),
                resultJson = SessionCodec.encode(result),
                referralLevel = result.assessment.referral.level.name,
                consentAt = s.consentAt,
                finalTrialRight = s.verification.finalTrialRight.ifBlank { null },
                finalTrialLeft = s.verification.finalTrialLeft.ifBlank { null },
                finalAdd = s.verification.finalAdd.ifBlank { null },
                pdMeterMm = s.verification.pdMeterMm,
                verifiedAt = s.verification.verifiedAt,
            )
            if (current == null) {
                val id = dao.insert(entity)
                _state.update { it.copy(sessionId = id) }
            } else {
                dao.update(entity)
            }
        }
        return result
    }

    fun saveVerification(v: Verification) {
        val stamped = v.copy(verifiedAt = System.currentTimeMillis())
        _state.update { it.copy(verification = stamped) }
        val id = _state.value.sessionId ?: return
        viewModelScope.launch {
            dao.get(id)?.let { e ->
                dao.update(e.copy(
                    finalTrialRight = stamped.finalTrialRight.ifBlank { null },
                    finalTrialLeft = stamped.finalTrialLeft.ifBlank { null },
                    finalAdd = stamped.finalAdd.ifBlank { null },
                    pdMeterMm = stamped.pdMeterMm,
                    verifiedAt = stamped.verifiedAt,
                ))
            }
        }
    }

    /** "Delete this session": removes the stored row and clears it from memory. */
    fun deleteCurrentSession(onDone: () -> Unit) {
        viewModelScope.launch {
            saveJob?.join()
            _state.value.sessionId?.let { removeSession(it) }
            startNew()
            onDone()
        }
    }

    /** Deletes the stored row and any PDFs exported for it. */
    private suspend fun removeSession(id: Long) {
        dao.delete(id)
        withContext(Dispatchers.IO) { PdfExporter.deleteFor(getApplication(), id) }
    }

    fun deleteSession(id: Long) = viewModelScope.launch {
        removeSession(id)
        if (_state.value.sessionId == id) startNew()
    }

    /** Opens a stored session (history) with its saved result, without re-running the engine. */
    fun openSession(id: Long, onLoaded: () -> Unit) = viewModelScope.launch {
        val e = dao.get(id) ?: return@launch
        val answers = SessionCodec.answers(e)
        _state.update {
            it.copy(
                answers = answers,
                answered = Questions.visible(answers).map { q -> q.id }.toSet(),
                staff = SessionCodec.staff(e),
                result = SessionCodec.result(e),
                sessionId = e.id,
                createdAt = e.createdAt,
                questionIndex = 0,
                editingFromConfirm = false,
                consentAt = e.consentAt,
                verification = Verification(
                    e.finalTrialRight.orEmpty(), e.finalTrialLeft.orEmpty(), e.finalAdd.orEmpty(), e.pdMeterMm, e.verifiedAt,
                ),
            )
        }
        onLoaded()
    }

    // ---- PDF ---------------------------------------------------------------------------------

    /** Builds the shop copy (current language) or the Hindi customer copy, off the main thread. */
    fun exportPdf(customer: Boolean, onReady: (File) -> Unit, onError: () -> Unit) = viewModelScope.launch {
        saveJob?.join() // make sure the session id exists, so delete can find the file later
        val st = _state.value
        val app = getApplication<Application>()
        runCatching {
            withContext(Dispatchers.IO) {
                val blocks = if (customer) PdfContent.customerCopy(st, rules) else PdfContent.shopCopy(st, rules, choiceContext, st.lang)
                val name = PdfExporter.prefix(st.sessionId) + (if (customer) "customer_hi" else "shop_${st.lang.name.lowercase()}") + ".pdf"
                PdfExporter.write(app, blocks, File(PdfExporter.dir(app), name))
            }
        }.onSuccess(onReady).onFailure { onError() }
    }
}
