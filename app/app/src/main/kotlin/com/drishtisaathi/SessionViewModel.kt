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
import com.drishtisaathi.aivoice.AiAnamnesisApi
import com.drishtisaathi.aivoice.AiAnamnesisClient
import com.drishtisaathi.aivoice.AiInterviewController
import com.drishtisaathi.aivoice.AiInterviewRecord
import com.drishtisaathi.aivoice.AiLanguage
import com.drishtisaathi.aivoice.AiProfileMapper
import com.drishtisaathi.aivoice.AiUiState
import com.drishtisaathi.bridge.BridgeClient
import com.drishtisaathi.bridge.BridgeConfig
import com.drishtisaathi.bridge.BridgeConsent
import com.drishtisaathi.bridge.BridgeException
import com.drishtisaathi.bridge.BridgeHealth
import com.drishtisaathi.bridge.BridgeProfile
import com.drishtisaathi.bridge.BridgeSettings
import com.drishtisaathi.bridge.ReplyNotifier
import kotlinx.coroutines.flow.map
import com.drishtisaathi.data.RemoteReviewEntity
import com.drishtisaathi.i18n.SBridge
import com.drishtisaathi.i18n.Txt
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
import com.drishtisaathi.interview.Stage
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
    /** CORE = questions before the eye check; LATER = lifestyle and frame questions after the face measurement. */
    val stage: Stage = Stage.CORE,
    val answers: InterviewAnswers = InterviewAnswers(),
    /** Question ids answered (a "Don't know" counts as answered). */
    val answered: Set<String> = emptySet(),
    val staff: StaffMeasurements = StaffMeasurements(),
    val questionIndex: Int = 0,
    /** True while editing one answer from the edit-answers screen ("For the shop"). */
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
    /** Remote Optometrist Bridge: what was sent for this session and the reply; null = not sent. */
    val remote: RemoteReviewEntity? = null,
    val bridgeBusy: Boolean = false,
    val bridgeError: Txt? = null,
    /** AI voice interview transcript for this session (staff and shop PDF only); null = not used. */
    val aiInterview: com.drishtisaathi.aivoice.AiInterviewRecord? = null,
) {
    /** Every question asked for these answers (summary, edit screen, PDF); after an urgent flag only the red-flag ones. */
    val visibleQuestions: List<Question> get() = Questions.visible(answers)
    /** The questions of the current step: one stage, or all of them while editing. */
    val stageQuestions: List<Question> get() = if (editingFromConfirm) visibleQuestions else Questions.visibleIn(stage, answers)
    val currentQuestion: Question? get() = stageQuestions.getOrNull(questionIndex)
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

    // ---- Remote Optometrist Bridge ------------------------------------------------------------

    val bridgeSettings = BridgeSettings(app)
    private val remoteDao = db.remoteReviews()
    private val appVersion: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "?"

    private fun errorText(e: BridgeException): Txt = when (e.kind) {
        BridgeException.Kind.NOT_CONFIGURED -> SBridge.notConfigured
        BridgeException.Kind.NETWORK -> SBridge.errNetwork
        BridgeException.Kind.UNAUTHORIZED -> SBridge.errToken
        BridgeException.Kind.REJECTED -> SBridge.errRejected.format(e.detail)
        BridgeException.Kind.NOT_FOUND -> SBridge.errGone
        BridgeException.Kind.SERVER -> SBridge.errServer.format(e.detail)
    }

    /** Settings screen "Test connection": null on success, else the error to show. */
    suspend fun testBridge(c: BridgeConfig): Pair<BridgeHealth?, Txt?> = withContext(Dispatchers.IO) {
        try {
            val h = BridgeClient(c).health()
            if (!h.tokenOk) null to SBridge.errToken else h to null
        } catch (e: BridgeException) { null to errorText(e) }
    }

    /** Sends the profile (only with spoken and tapped consent), stores the server id; [onSent] runs on success. */
    fun sendToOptometrist(consent: BridgeConsent, onSent: () -> Unit) = viewModelScope.launch {
        if (!consent.complete || _state.value.bridgeBusy) return@launch
        saveJob?.join() // the session row must exist first
        val s = _state.value
        val id = s.sessionId ?: run { _state.update { it.copy(bridgeError = SBridge.errNoSession) }; return@launch }
        val cfg = bridgeSettings.config.value
        _state.update { it.copy(bridgeBusy = true, bridgeError = null) }
        try {
            val profile = BridgeProfile.build(s, rules, consent, appVersion)
            val created = withContext(Dispatchers.IO) { BridgeClient(cfg).send(profile) }
            val e = RemoteReviewEntity(id, created.id, cfg.baseUrl, System.currentTimeMillis(), consent.consentAt, created.status)
            remoteDao.upsert(e)
            _state.update { it.copy(remote = e, bridgeBusy = false) }
            onSent()
        } catch (e: BridgeException) {
            _state.update { it.copy(bridgeBusy = false, bridgeError = errorText(e)) }
        }
    }

    fun clearBridgeError() = _state.update { it.copy(bridgeError = null) }

    /** Sent sessions and their status, for Waiting / Reviewed in Past sessions. */
    val remoteReviews: Flow<Map<Long, RemoteReviewEntity>> = remoteDao.observeAll().map { rows -> rows.associateBy { it.sessionId } }

    /** Asks the server for one sent session's reply, stores it, and notifies when it has just arrived. */
    private suspend fun fetchReview(r: RemoteReviewEntity): RemoteReviewEntity {
        val cfg = bridgeSettings.config.value.copy(url = r.backendUrl)
        val rv = withContext(Dispatchers.IO) { BridgeClient(cfg).review(r.remoteId) }
        val e = r.copy(status = rv.status, advice = rv.advice, needsInPersonExam = rv.needsInPersonExam,
            reviewedAt = rv.reviewedAt, checkedAt = System.currentTimeMillis())
        remoteDao.upsert(e)
        if (!r.reviewed && e.reviewed) {
            ReplyNotifier.replyReceived(getApplication(), _state.value.lang, e.sessionId, e.caseId, e.needsInPersonExam == true)
        }
        _state.update { if (it.remote?.sessionId == e.sessionId) it.copy(remote = e) else it }
        return e
    }

    /**
     * "Check for reply" (and the 15 s check while the summary is open). [silent]: background checks do not show
     * network errors, so a short Wi-Fi drop does not flash red on the summary.
     */
    fun checkReview(silent: Boolean = false) = viewModelScope.launch {
        val r = _state.value.remote ?: return@launch
        if (_state.value.bridgeBusy) return@launch
        _state.update { it.copy(bridgeBusy = true, bridgeError = if (silent) it.bridgeError else null) }
        try {
            fetchReview(r)
            _state.update { it.copy(bridgeBusy = false, bridgeError = null) }
        } catch (e: BridgeException) {
            _state.update { it.copy(bridgeBusy = false, bridgeError = if (silent) it.bridgeError else errorText(e)) }
        }
    }

    /** Past sessions: checks every sent session that is still waiting (quietly; errors are ignored). */
    fun checkAllWaiting() = viewModelScope.launch {
        for (r in remoteDao.waiting()) {
            try { fetchReview(r) } catch (_: BridgeException) {}
        }
    }

    /** Session deleted: remove the server copy too (best effort; offline the local row still goes). */
    private suspend fun removeRemote(sessionId: Long) {
        val r = remoteDao.get(sessionId) ?: return
        withContext(Dispatchers.IO) {
            runCatching { BridgeClient(bridgeSettings.config.value.copy(url = r.backendUrl), timeoutMs = 5_000).delete(r.remoteId) }
        }
        remoteDao.delete(sessionId)
    }

    // ---- AI voice interview (Sarvam via the backend) ------------------------------------------

    private var ai: AiInterviewController? = null
    private var aiConsentAt: Long = 0
    private val _aiState = MutableStateFlow(AiUiState())
    val aiState: StateFlow<AiUiState> = _aiState.asStateFlow()
    private var aiCollect: Job? = null

    /** The AI option is offered only when staff set a server address. */
    val aiAvailable: Boolean get() = bridgeSettings.config.value.isValid

    /** Customer agreed to send their voice: starts the backend interview (questions in [language]). */
    fun startAiInterview(language: AiLanguage, api: AiAnamnesisApi = AiAnamnesisClient(bridgeSettings.config.value)) {
        aiConsentAt = System.currentTimeMillis()
        setLang(language.appLang)
        val c = AiInterviewController(api, language)
        ai = c
        aiCollect?.cancel()
        aiCollect = viewModelScope.launch { c.state.collect { _aiState.value = it } }
        viewModelScope.launch(Dispatchers.IO) { c.start() }
    }

    fun aiAnswer(wav: ByteArray) { val c = ai ?: return; viewModelScope.launch(Dispatchers.IO) { c.answer(wav) } }
    fun aiRetry() { val c = ai ?: return; viewModelScope.launch(Dispatchers.IO) { c.retry() } }
    fun aiMicFailed() { ai?.micFailed() }

    /**
     * Ends the AI interview and puts its answers into the standard interview. [fellBack]: the backend failed, so the
     * answers received so far are used without calling it again. [onDone] gets true when every question is answered.
     */
    fun finishAiInterview(fellBack: Boolean, onDone: (allAnswered: Boolean) -> Unit) = viewModelScope.launch {
        val c = ai ?: return@launch onDone(false)
        val profile = withContext(Dispatchers.IO) { if (fellBack) c.profileSoFar() else c.finish() }
        ai = null
        aiCollect?.cancel()
        _aiState.value = AiUiState()
        val record = AiInterviewRecord(c.language.code, aiConsentAt, profile.transcript, fellBack)
        var all = false
        _state.update { s ->
            val m = AiProfileMapper.map(s.answers, profile, s.answered)
            val visible = Questions.visibleIn(Stage.CORE, m.answers)
            val next = visible.indexOfFirst { it.id !in m.answered }
            all = next < 0
            s.copy(answers = m.answers, answered = m.answered, stage = Stage.CORE, questionIndex = if (next < 0) 0 else next,
                editingFromConfirm = false, aiInterview = record, result = null)
        }
        onDone(all)
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

    /** Fresh session; keeps the language. Forgets the previous customer's iris calibration. */
    fun startNew() {
        calibration.clearCustomer()
        _state.update { UiState(lang = it.lang) }
    }

    /** Customer tapped "I agree": a fresh session starts with the consent time recorded. */
    fun giveConsent() {
        calibration.clearCustomer()
        _state.update { UiState(lang = it.lang, consentAt = System.currentTimeMillis()) }
    }

    // ---- phone setup (once per phone) ---------------------------------------------------------

    val phoneSetup = PhoneSetup(app)

    /**
     * The AI voice interview is used when a server is set and answers within 3 s (it needs the backend and its
     * Sarvam key); otherwise the scripted questions run on the phone.
     */
    suspend fun aiReachable(): Boolean {
        val cfg = bridgeSettings.config.value
        if (!cfg.isValid) return false
        return withContext(Dispatchers.IO) {
            try { BridgeClient(cfg, timeoutMs = 3_000).health().let { it.ok && it.tokenOk } } catch (_: Exception) { false }
        }
    }

    // ---- demo mode ---------------------------------------------------------------------------

    /** Loads a sample customer (answers, camera, screen tests already filled in) for demos and recordings. */
    fun loadDemo(c: com.drishtisaathi.demo.DemoCustomer) {
        calibration.clearCustomer()
        _state.update {
            UiState(
                lang = it.lang,
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

    /** After the eye check: urgent from the answers or the checks (e.g. wavy grid lines) → straight to the referral. */
    fun needsUrgentReferral(): Boolean {
        val s = _state.value
        return engine.evaluate(s.answers, s.staff, s.tests, quick = quickFor(s)).assessment.referral.level == ReferralLevel.URGENT
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

    /**
     * Records an answer and moves to the next unanswered question of this stage. Returns true when the stage is
     * finished (or, while editing, every question is answered again).
     */
    fun answer(questionId: String, update: (InterviewAnswers) -> InterviewAnswers): Boolean {
        var toConfirm = false
        _state.update { s ->
            val answers = update(s.answers)
            val all = Questions.visible(answers)
            val answered = (s.answered + questionId).filter { id -> all.any { it.id == id } }.toSet()
            val next = s.copy(answers = answers, answered = answered)
            val list = next.stageQuestions
            val cur = list.indexOfFirst { it.id == questionId }
            val nextIdx = (cur + 1 until list.size).firstOrNull { list[it].id !in answered } ?: list.size
            toConfirm = (s.editingFromConfirm && next.allAnswered) || nextIdx >= list.size
            s.copy(
                answers = answers, answered = answered,
                questionIndex = if (toConfirm) s.questionIndex else nextIdx,
                editingFromConfirm = if (toConfirm) false else s.editingFromConfirm,
                result = null,
            )
        }
        return toConfirm
    }

    /** Starts [stage] at its first unanswered question. Returns false when nothing is left to ask in it. */
    fun startStage(stage: Stage): Boolean {
        var any = false
        _state.update { s ->
            val list = Questions.visibleIn(stage, s.answers)
            val first = list.indexOfFirst { it.id !in s.answered }
            any = first >= 0
            s.copy(stage = stage, questionIndex = first.coerceAtLeast(0), editingFromConfirm = false)
        }
        return any
    }

    fun setKnownRx(rx: KnownRx?) = _state.update { it.copy(answers = it.answers.copy(knownRx = rx), result = null) }

    // ---- power path --------------------------------------------------------------------------

    val quickEstimator: QuickEstimator by lazy { QuickEstimator(rules) }

    /** Which quick tests run for the current answers (nothing after an urgent red flag). */
    fun quickPlan(): QuickPlan = quickEstimator.plan(_state.value.answers)

    /** What the customer eye check runs (power and two-line per eye, grid when needed, reading check). */
    fun eyeCheckPlan(): com.drishtisaathi.vision.EyeCheckPlan =
        com.drishtisaathi.vision.EyeCheckPlan.from(_state.value.answers, quickPlan())

    /** YES path: offer the optional quick check next to the known power. */
    fun offerCompare(): Boolean = quickEstimator.offerCompare(_state.value.answers)

    /** "No numbers available" on the old-glasses screen: the power path continues as NO. */
    fun setPowerKnown(v: PowerKnown) = _state.update {
        it.copy(answers = it.answers.copy(powerKnown = v, knownRx = it.answers.knownRx.takeIf { v == PowerKnown.YES }), result = null)
    }

    fun setQuick(q: QuickEstimate?) = _state.update { it.copy(quick = q, result = null) }


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
                measurementsJson = SessionCodec.encode(Measurements(s.face, s.tests.takeIf { s.testsDone }, demo = s.demo, glassesOn = s.glassesOn, quick = quickFor(s), aiInterview = s.aiInterview)),
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
        removeRemote(id)
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
        val remote = remoteDao.get(id)
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
                aiInterview = m.aiInterview,
                remote = remote,
                bridgeError = null,
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
