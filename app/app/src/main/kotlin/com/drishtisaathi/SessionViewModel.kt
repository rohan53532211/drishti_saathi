package com.drishtisaathi

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drishtisaathi.calibration.PersistentCalibrationRepository
import com.drishtisaathi.camera.EngineBridge
import com.drishtisaathi.data.AppDatabase
import com.drishtisaathi.data.Measurements
import com.drishtisaathi.engine.AnthroPriors
import com.drishtisaathi.engine.MeasuredFace
import com.drishtisaathi.engine.ReferralLevel
import com.drishtisaathi.engine.VisionTests
import com.drishtisaathi.engine.face.FaceCapture
import com.drishtisaathi.engine.face.FaceFrame
import com.drishtisaathi.data.SessionCodec
import com.drishtisaathi.data.SessionEntity
import com.drishtisaathi.data.DeviceCorrectionEntity
import com.drishtisaathi.data.PairedRecords
import com.drishtisaathi.engine.vision.DeviceCorrection
import com.drishtisaathi.engine.vision.DeviceFit
import com.drishtisaathi.engine.DrishtiEngine
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.KnownRx
import com.drishtisaathi.engine.PowerKnown
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.anamnesis.QuickEstimator
import com.drishtisaathi.engine.anamnesis.QuickPlan
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
    /** Addition 3: ids of up to 2 frames ticked for comparison. */
    val compareIds: List<String> = emptyList(),
    /** M2 camera capture; null = skipped, not done, or discarded by the plausibility gate. */
    val face: MeasuredFace? = null,
    val captureStatus: FaceCapture.Status? = null,
    /** M3 on-screen tests; empty = skipped or not done. */
    val tests: VisionTests = VisionTests(),
    val testsDone: Boolean = false,
    /** Staff demo mode: sample customer, labelled DEMO everywhere. */
    val demo: Boolean = false,
    /** Staff override: camera steps and screen tests done with glasses on (noted on the summary and shop PDF). */
    val glassesOn: Boolean = false,
    /** Power path quick estimate (primary = power source; otherwise a comparison for staff). */
    val quick: QuickEstimate? = null,
    /** Staff: also run the full far-point test (and clock dial) in the screen tests. */
    val fullFarTest: Boolean = false,
) {
    val visibleQuestions: List<Question> get() = Questions.visible(answers)
    val currentQuestion: Question? get() = visibleQuestions.getOrNull(questionIndex)
    val allAnswered: Boolean get() = visibleQuestions.all { it.id in answered }
}

class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private fun asset(name: String) = getApplication<Application>().assets.open(name).bufferedReader().use { it.readText() }

    val rules: RulesConfig by lazy { RulesConfig.parse(asset("rules.json")) }
    private val priors: AnthroPriors by lazy { AnthroPriors.parse(asset("anthro_priors.json")) }
    /** All 30 frames: frames.json + frames/more_frames.json (try-on catalogue). */
    val engine: DrishtiEngine by lazy {
        DrishtiEngine.fromJson(asset("rules.json"), asset("anthro_priors.json"), asset("frames.json"), asset("frames/more_frames.json"))
    }
    private val faceCapture: FaceCapture by lazy { FaceCapture(rules, priors) }

    /** Screen scale persists per phone; the customer's iris size is in memory only and cleared per session. */
    val calibration = PersistentCalibrationRepository(app)

    init {
        // Camera code (distance, head pose) uses the engine's maths from here on.
        EngineBridge.rules = rules
    }
    val choiceContext: ChoiceContext by lazy {
        ChoiceContext(rules.frameScoring.budgetMaxPriceInrLow, rules.frameScoring.budgetMaxPriceInrMid)
    }

    private val db = AppDatabase.get(app)
    private val dao = db.sessions()
    val sessions: Flow<List<SessionEntity>> = dao.observeAll()

    // ---- learning loop: per-phone-model correction ------------------------------------------

    /** Same string stored with every session. */
    private val phoneModel = "${Build.MANUFACTURER} ${Build.MODEL}"
    private val _deviceFit = MutableStateFlow<DeviceFit?>(null)
    /** This phone model's fit from verified shop records; null until minVerifiedSessions exist. */
    val deviceFit: StateFlow<DeviceFit?> = _deviceFit.asStateFlow()
    private var refitJob: Job? = null

    init {
        viewModelScope.launch {
            _deviceFit.value = db.deviceCorrections().get(phoneModel)?.toFit() // stored fit, available at once
            refitDeviceCorrection()                                              // then refresh with current rules
        }
    }

    /** Refits from all verified sessions of this phone model and stores the fit (or removes it below the minimum). */
    private fun refitDeviceCorrection() {
        val previous = refitJob
        refitJob = viewModelScope.launch {
            previous?.join()
            val fit = DeviceCorrection.fit(PairedRecords.from(dao.verifiedFor(phoneModel)), rules.deviceCorrection)
            if (fit == null) db.deviceCorrections().delete(phoneModel)
            else db.deviceCorrections().upsert(DeviceCorrectionEntity.from(phoneModel, fit, System.currentTimeMillis()))
            _deviceFit.value = fit
        }
    }

    /** The pending save; delete waits for it so a fast "Delete" never leaves a row behind. */
    private var saveJob: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Tick/untick a frame for comparison; a third tick replaces the oldest. */
    fun toggleCompare(frameId: String) = _state.update { s ->
        val ids = if (frameId in s.compareIds) s.compareIds - frameId else (s.compareIds + frameId).takeLast(2)
        s.copy(compareIds = ids)
    }

    fun setLang(lang: Lang) = _state.update { it.copy(lang = lang) }
    fun setStaffMode(on: Boolean) = _state.update { it.copy(staffMode = on) }

    /** Fresh session; keeps language and staff mode. Forgets the previous customer's iris calibration. */
    fun startNew() {
        calibration.clearCustomer()
        _state.update { UiState(lang = it.lang, staffMode = it.staffMode) }
    }

    /** Customer tapped "I agree": a fresh session starts with the consent time recorded. */
    fun giveConsent() {
        calibration.clearCustomer()
        _state.update { UiState(lang = it.lang, staffMode = it.staffMode, consentAt = System.currentTimeMillis()) }
    }

    // ---- demo mode ---------------------------------------------------------------------------

    /** Loads a sample customer (answers, camera, screen tests already filled in) for demos and recordings. */
    fun loadDemo(c: com.drishtisaathi.demo.DemoCustomer) {
        calibration.clearCustomer()
        _state.update {
            UiState(
                lang = it.lang, staffMode = it.staffMode,
                answers = c.answers,
                answered = Questions.visible(c.answers).map { q -> q.id }.toSet(),
                face = c.face,
                captureStatus = c.face?.let { FaceCapture.Status.OK },
                tests = c.tests ?: VisionTests(),
                testsDone = c.tests != null,
                consentAt = System.currentTimeMillis(),
                demo = true,
            )
        }
    }

    // ---- camera capture + screen tests -------------------------------------------------------

    /**
     * True when the interview alone already triggers an urgent referral: the camera steps and screen tests
     * are skipped and the customer goes straight to the referral screen.
     */
    fun interviewNeedsUrgentReferral(): Boolean {
        val s = _state.value
        return engine.evaluate(s.answers, s.staff).assessment.referral.level == ReferralLevel.URGENT
    }

    /** M2: measures PD, face width, bridge, shape from the accepted frames (landmarks only; frames dropped after). */
    fun setCapture(frames: List<FaceFrame>): FaceCapture.Status {
        val out = faceCapture.analyse(frames, calibration.calibration.value.irisDiameterMm)
        _state.update { it.copy(face = out.face, captureStatus = out.status, result = null) }
        // rules_update.md rule 2: keep a card-calibrated PD for camera distances beyond the iris range
        val cardPd = out.face?.takeIf { it.cardCalibrated }?.pdMm?.value
        calibration.update { it.copy(pdMm = cardPd) }
        return out.status
    }

    /** Before the camera steps: true = staff chose to continue with the customer's glasses on. */
    fun setGlassesOn(on: Boolean) = _state.update { it.copy(glassesOn = on, result = null) }

    fun skipCapture() = _state.update { it.copy(face = null, captureStatus = null, result = null) }

    fun setVisionTests(tests: VisionTests) = _state.update { it.copy(tests = tests, testsDone = true, result = null) }
    fun skipVisionTests() = _state.update { it.copy(tests = VisionTests(), testsDone = false, result = null) }

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

    fun setKnownRx(rx: KnownRx?) = _state.update { it.copy(answers = it.answers.copy(knownRx = rx), result = null) }

    // ---- power path --------------------------------------------------------------------------

    val quickEstimator: QuickEstimator by lazy { QuickEstimator(rules) }

    /** Which quick tests run for the current answers (nothing after an urgent red flag). */
    fun quickPlan(): QuickPlan = quickEstimator.plan(_state.value.answers)

    /** YES path: offer the optional quick check next to the known power. */
    fun offerCompare(): Boolean = quickEstimator.offerCompare(_state.value.answers)

    /** "No numbers available" on the old-glasses screen: the power path continues as NO. */
    fun setPowerKnown(v: PowerKnown) = _state.update {
        it.copy(answers = it.answers.copy(powerKnown = v, knownRx = it.answers.knownRx.takeIf { v == PowerKnown.YES }), result = null)
    }

    fun setQuick(q: QuickEstimate?) = _state.update { it.copy(quick = q, result = null) }

    fun setFullFarTest(on: Boolean) = _state.update { it.copy(fullFarTest = on) }

    /** The quick estimate only counts while the power path still applies (an urgent flag clears it). */
    private fun quickFor(s: UiState): QuickEstimate? = s.quick?.takeIf { !s.answers.urgentRedFlag && s.answers.powerKnown != null }

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
        // Demo customers are never corrected, so recordings stay the same on every phone.
        val result = engine.evaluate(
            s.answers, s.staff, s.tests, s.face, correction = if (s.demo) null else _deviceFit.value, quick = quickFor(s),
        )
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
                phoneModel = phoneModel,
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
                measurementsJson = SessionCodec.encode(Measurements(s.face, s.tests.takeIf { s.testsDone }, demo = s.demo, glassesOn = s.glassesOn, quick = quickFor(s))),
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
            refitDeviceCorrection()
        }
    }

    /** "Delete this session": removes the stored row and clears it from memory. */
    fun deleteCurrentSession(onDone: () -> Unit) {
        viewModelScope.launch {
            saveJob?.join()
            _state.value.sessionId?.let { removeSession(it) }
            startNew() // also clears the customer's calibration
            onDone()
        }
    }

    /** Deletes the stored row and any PDFs exported for it. */
    private suspend fun removeSession(id: Long) {
        dao.delete(id)
        withContext(Dispatchers.IO) { PdfExporter.deleteFor(getApplication(), id) }
        refitDeviceCorrection()
    }

    fun deleteSession(id: Long) = viewModelScope.launch {
        removeSession(id)
        if (_state.value.sessionId == id) startNew()
    }

    /** Opens a stored session (history) with its saved result, without re-running the engine. */
    fun openSession(id: Long, onLoaded: () -> Unit) = viewModelScope.launch {
        val e = dao.get(id) ?: return@launch
        val answers = SessionCodec.answers(e)
        val m = SessionCodec.measurements(e)
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
                face = m.face,
                captureStatus = null,
                tests = m.tests ?: VisionTests(),
                testsDone = m.tests != null,
                demo = m.demo,
                glassesOn = m.glassesOn,
                quick = m.quick,
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
                val name = PdfExporter.prefix(st.sessionId) + (if (st.demo) "DEMO_" else "") + (if (customer) "customer_hi" else "shop_${st.lang.name.lowercase()}") + ".pdf"
                PdfExporter.write(app, blocks, File(PdfExporter.dir(app), name))
            }
        }.onSuccess(onReady).onFailure { onError() }
    }
}
