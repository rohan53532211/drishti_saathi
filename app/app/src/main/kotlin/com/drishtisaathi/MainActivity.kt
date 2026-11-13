package com.drishtisaathi

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.drishtisaathi.calibration.ForeheadCardCalibrationScreen
import com.drishtisaathi.camera.CameraDebugScreen
import com.drishtisaathi.camera.CameraPermissionGate
import com.drishtisaathi.camera.CameraPreview
import com.drishtisaathi.camera.FaceCaptureScreen
import com.drishtisaathi.camera.PreviewTransform
import com.drishtisaathi.camera.rememberFaceTracker
import com.drishtisaathi.engine.ReferralLevel
import com.drishtisaathi.i18n.S
import com.drishtisaathi.i18n.SFlow
import com.drishtisaathi.interview.Questions
import com.drishtisaathi.interview.Stage
import com.drishtisaathi.i18n.SShop
import com.drishtisaathi.ui.ShopToolsScreen
import com.drishtisaathi.ui.DiagnosticsScreen
import com.drishtisaathi.ui.LanguageScreen
import com.drishtisaathi.ui.PhoneSetupScreen
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxWidth
import com.drishtisaathi.pdf.PdfExporter
import com.drishtisaathi.tryon.TryOnMapping
import com.drishtisaathi.tryon.TryOnScreen
import com.drishtisaathi.ui.BigButton
import com.drishtisaathi.ui.CaptureOutcomeScreen
import com.drishtisaathi.ui.CompareScreen
import com.drishtisaathi.ui.ConfirmScreen
import com.drishtisaathi.ui.ConsentScreen
import com.drishtisaathi.ui.DrishtiTheme
import com.drishtisaathi.ui.HistoryScreen
import com.drishtisaathi.ui.HomeScreen
import com.drishtisaathi.ui.InterviewScreen
import com.drishtisaathi.ui.OldNumbersScreen
import com.drishtisaathi.ui.ReferralScreen
import com.drishtisaathi.ui.ResultScreen
import com.drishtisaathi.ui.StepFrame
import com.drishtisaathi.ui.DemoScreen
import com.drishtisaathi.ui.DemoBadge
import com.drishtisaathi.demo.DemoCustomers
import com.drishtisaathi.vision.CameraQuickEstimateFlow
import com.drishtisaathi.ui.CompareOfferScreen
import com.drishtisaathi.ui.BridgeConsentScreen
import com.drishtisaathi.ui.BridgeSettingsScreen
import com.drishtisaathi.ui.RemoteReviewCard
import com.drishtisaathi.ui.AiConsentScreen
import com.drishtisaathi.ui.AiInterviewScreen
import com.drishtisaathi.engine.PowerKnown
import com.drishtisaathi.engine.QuickEstimate
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.drishtisaathi.voice.rememberVoiceController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DrishtiTheme { DrishtiApp() } }
    }
}

private object Routes {
    const val HOME = "home"
    // Shop tools (no staff switch on the customer path)
    const val SHOP_TOOLS = "shopTools"
    const val PHONE_SETUP = "phoneSetup"
    const val DIAGNOSTICS = "diagnostics"
    const val CAMERA_DEBUG = "cameraDebug"
    const val DEMO = "demo"
    // Customer flow: consent → language → questions (AI voice or scripted) → power / eye check → face → more
    // questions → results
    const val CONSENT = "consent"
    const val LANGUAGE = "language"
    const val INTERVIEW = "interview"
    const val AI_CONSENT = "aiConsent"
    const val AI_INTERVIEW = "aiInterview"
    const val OLD_NUMBERS = "oldNumbers"
    const val COMPARE_OFFER = "compareOffer"
    const val QUICK = "quick?primary={primary}"
    fun quick(primary: Boolean) = "quick?primary=$primary"
    const val CARD = "card"
    const val CAPTURE = "capture"
    const val CAPTURE_OUTCOME = "captureOutcome"
    const val REFERRAL = "referral"
    const val RESULT = "result"
    /** "For the shop" → Edit answers (the old confirmation screen). */
    const val EDIT = "editAnswers"
    const val TRY_ON = "tryOn?frame={frame}"
    const val HISTORY = "history"
    const val COMPARE = "compare"
    // Remote Optometrist Bridge
    const val BRIDGE_CONSENT = "bridgeConsent"
    const val BRIDGE_SETTINGS = "bridgeSettings"

    fun tryOn(frameId: String?) = "tryOn" + (frameId?.let { "?frame=$it" } ?: "")

    // 3D catalogue, viewer, live 3D try-on, face avatar
    const val CATALOG3D = "catalog3d"
    const val FRAME_CATALOGUE = "frameCatalogue"
    const val VIEWER3D = "viewer3d/{id}"
    fun viewer3d(id: String) = "viewer3d/$id"
    const val LIVE3D = "live3d?frame={frame}&debug={debug}"
    fun live3d(id: String?, debug: Boolean = false) = "live3d?debug=$debug" + (id?.let { "&frame=$it" } ?: "")
    const val AVATAR_CONSENT = "avatarConsent?frame={frame}"
    fun avatarConsent(id: String?) = "avatarConsent" + (id?.let { "?frame=$it" } ?: "")
    const val AVATAR_CAPTURE = "avatarCapture?frame={frame}"
    fun avatarCapture(id: String?) = "avatarCapture" + (id?.let { "?frame=$it" } ?: "")
    const val AVATAR = "avatar?frame={frame}"
    fun avatar(id: String?) = "avatar" + (id?.let { "?frame=$it" } ?: "")
}

/** Previews and landmarks share Track B's mapping (FILL_CENTER crop + mirroring), so the try-on lines up. */
private val PreviewMapping = TryOnMapping { x, y, f, w, h ->
    PreviewTransform.forFrame(f, w, h).map(x.toFloat(), y.toFloat()).let { Offset(it.x, it.y) }
}

@Composable
fun DrishtiApp(vm: SessionViewModel = viewModel()) {
    val nav = rememberNavController()
    val state by vm.state.collectAsStateWithLifecycle()
    // One TTS engine for the whole app: interview questions, camera instructions, test instructions.
    val voice = rememberVoiceController()

    fun goHome() = nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }

    // Tapped a "Reply received" notification: open that session's summary.
    val activity = LocalContext.current as? android.app.Activity
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val id = activity?.intent?.getLongExtra(com.drishtisaathi.bridge.ReplyNotifier.EXTRA_SESSION, -1L) ?: -1L
        if (id > 0) {
            activity?.intent?.removeExtra(com.drishtisaathi.bridge.ReplyNotifier.EXTRA_SESSION)
            vm.openSession(id) { nav.navigate(Routes.RESULT) }
        }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val setupDone by vm.phoneSetup.done.collectAsStateWithLifecycle()

    // First launch: the one-time phone setup opens by itself ("Skip for now" leaves a reminder on home).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!vm.phoneSetup.done.value && !vm.phoneSetup.offered) {
            vm.phoneSetup.markOffered()
            nav.navigate(Routes.PHONE_SETUP)
        }
    }

    /** Runs the engine with everything gathered so far and opens the results (a referral shows at the top there). */
    fun computeAndShow() {
        vm.computeResult()
        nav.navigate(Routes.RESULT) { popUpTo(Routes.HOME) }
    }

    /** Back to the edit-answers screen ("For the shop"). */
    fun toEdit() { if (!nav.popBackStack(Routes.EDIT, inclusive = false)) nav.navigate(Routes.EDIT) }

    /** LATER questions (only the unanswered ones), then the results. */
    fun toLater() {
        if (vm.startStage(Stage.LATER)) nav.navigate(Routes.INTERVIEW) { popUpTo(Routes.CARD) { inclusive = true } }
        else computeAndShow()
    }

    /** Face measurement for frame fit (card on the forehead first, optional). */
    fun toFace() = nav.navigate(Routes.CARD) { popUpTo(Routes.LANGUAGE) }

    /**
     * After the eye check: precision mode → the results; everyone else (urgent cases too) → the face measurement
     * for frame fit and the remaining questions, then the results (an urgent warning at the top).
     */
    fun afterEyeCheck() {
        if (vm.state.value.precision) { vm.skipCapture(); computeAndShow() } else toFace()
    }

    // ---- power path ----
    /** The power path was opened from the edit-answers screen: return there instead of going on. */
    var powerFromEdit by remember { mutableStateOf(false) }
    fun finishPowerPath() {
        if (powerFromEdit) { powerFromEdit = false; toEdit() } else afterEyeCheck()
    }
    /**
     * The eye check is compulsory for everyone (also with a known power or a red flag). [primary] = it is the power
     * source; false next to a known power (both are shown).
     */
    fun startQuick(primary: Boolean) {
        vm.setGlassesOn(false)
        nav.navigate(Routes.quick(primary))
    }
    fun startPowerPath() {
        vm.setQuick(null)
        vm.skipVisionTests()
        when (vm.state.value.answers.powerKnown) {
            PowerKnown.YES -> nav.navigate(Routes.OLD_NUMBERS)
            // NOT SURE / NO, or not asked (an urgent red flag ends the questions early): the eye check is the source.
            else -> startQuick(primary = true)
        }
    }

    /** CORE questions done: the power question's path and the eye check, also after a red flag (no early end). */
    fun afterCore() = startPowerPath()

    /** Precision mode: consent first, then the same eye check and the same results, saved as a session. */
    var precisionRun by remember { mutableStateOf(false) }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                state, vm::setLang,
                onStart = { precisionRun = false; vm.avatarStaffDefault = false; vm.startNew(); nav.navigate(Routes.CONSENT) },
                onHistory = { nav.navigate(Routes.HISTORY) },
                onShopTools = { vm.avatarStaffDefault = true; nav.navigate(Routes.SHOP_TOOLS) },
                setupDone = setupDone,
                onSetup = { nav.navigate(Routes.PHONE_SETUP) },
            )
        }
        composable(Routes.SHOP_TOOLS) {
            ShopToolsScreen(
                state.lang, vm::setLang, setupDone,
                onSetup = { nav.navigate(Routes.PHONE_SETUP) },
                onDemo = { nav.navigate(Routes.DEMO) },
                onBridge = { nav.navigate(Routes.BRIDGE_SETTINGS) },
                onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
                onBack = { nav.popBackStack() },
                onCatalogue = { vm.loadCatalog3D(); nav.navigate(Routes.FRAME_CATALOGUE) },
            )
        }
        composable(Routes.FRAME_CATALOGUE) {
            val (frames, online) = vm.frames3d.collectAsStateWithLifecycle().value
            androidx.compose.runtime.LaunchedEffect(Unit) { if (frames.isEmpty()) vm.loadCatalog3D() }
            val saved by vm.allSavedFrames.collectAsState(initial = emptyList())
            val sessions by vm.sessions.collectAsState(initial = emptyList())
            val dates = androidx.compose.runtime.remember(sessions) { sessions.associate { it.id to it.createdAt } }
            com.drishtisaathi.tryon3d.FrameCatalogueScreen(
                state.lang, vm::setLang, frames, online, vm::thumb3D, saved, dates,
                onOpen = { nav.navigate(Routes.viewer3d(it.id)) },
                onOpenSession = { id -> vm.openSession(id) { nav.navigate(Routes.RESULT) } },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.PHONE_SETUP) {
            val context = LocalContext.current
            PhoneSetupScreen(
                state.lang, vm::setLang, vm.calibration, vm.rules.camera,
                onFinish = {
                    vm.phoneSetup.markDone()
                    Toast.makeText(context, SShop.setupSaved[state.lang], Toast.LENGTH_SHORT).show()
                    nav.popBackStack()
                },
                onSkip = { nav.popBackStack() },
            )
        }
        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(
                state.lang, vm::setLang,
                onCamera = { nav.navigate(Routes.CAMERA_DEBUG) },
                onPrecision = { precisionRun = true; vm.startNew(); nav.navigate(Routes.CONSENT) },
                onAlign3D = { vm.loadCatalog3D(); nav.navigate(Routes.live3d(null, debug = true)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.CONSENT) {
            ConsentScreen(
                state.lang, vm::setLang,
                onContinue = {
                    vm.giveConsent()
                    if (precisionRun) {
                        // Precision mode: the same reading test as the customer flow, then the same results.
                        precisionRun = false
                        vm.startPrecision()
                        nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }
                        startQuick(primary = true)
                    } else nav.navigate(Routes.LANGUAGE) { popUpTo(Routes.HOME) }
                },
                onCancel = { goHome() },
            )
        }
        composable(Routes.LANGUAGE) {
            var checking by remember { mutableStateOf(false) }
            LanguageScreen(
                state.lang,
                onPick = { lang ->
                    if (!checking) {
                        vm.setLang(lang)
                        checking = true
                        // AI voice interview when the backend answers; otherwise the scripted questions.
                        scope.launch {
                            val ai = !state.demo && vm.aiReachable()
                            checking = false
                            vm.startStage(Stage.CORE)
                            nav.navigate(if (ai) Routes.AI_CONSENT else Routes.INTERVIEW)
                        }
                    }
                },
                onBack = { goHome() },
            )
            if (checking) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        composable(Routes.INTERVIEW) {
            InterviewScreen(
                state, vm.choiceContext, vm::setLang,
                onAnswer = { id, update ->
                    val editing = vm.state.value.editingFromConfirm
                    val stage = vm.state.value.stage
                    val done = vm.answer(id, update)
                    when {
                        editing && id == Questions.POWER_KNOWN -> { powerFromEdit = true; startPowerPath() }
                        editing -> if (done) toEdit()
                        !done -> Unit
                        stage == Stage.CORE -> afterCore()
                        else -> computeAndShow()
                    }
                },
                onOldNumbersYes = { nav.navigate(Routes.OLD_NUMBERS) },
                onBack = {
                    when {
                        state.editingFromConfirm -> toEdit()
                        !vm.previousQuestion() -> nav.popBackStack()
                    }
                },
                voice = voice,
            )
        }
        composable(Routes.AI_CONSENT) {
            AiConsentScreen(
                state.lang, vm::setLang, voice.instructionSpeaker,
                onContinue = { lang ->
                    vm.startAiInterview(lang)
                    nav.navigate(Routes.AI_INTERVIEW) { popUpTo(Routes.AI_CONSENT) { inclusive = true } }
                },
                onUseButtons = { nav.navigate(Routes.INTERVIEW) { popUpTo(Routes.AI_CONSENT) { inclusive = true } } },
            )
        }
        composable(Routes.AI_INTERVIEW) {
            val ai by vm.aiState.collectAsStateWithLifecycle()
            /** Then the scripted questions the AI did not cover (red flags if unclear, power), or straight on. */
            fun leave(fellBack: Boolean) = vm.finishAiInterview(fellBack) { all ->
                nav.navigate(Routes.INTERVIEW) { popUpTo(Routes.AI_INTERVIEW) { inclusive = true } }
                if (all) afterCore()
            }
            AiInterviewScreen(
                state.lang, vm::setLang, ai, voice.instructionSpeaker,
                onAnswer = vm::aiAnswer,
                onMicFailed = vm::aiMicFailed,
                onRetry = vm::aiRetry,
                onEnd = { leave(fellBack = ai.phase == com.drishtisaathi.aivoice.AiUiState.Phase.FALLBACK) },
                onContinue = { fellBack -> leave(fellBack) },
            )
        }
        composable(Routes.OLD_NUMBERS) {
            OldNumbersScreen(
                state.lang, state.answers.knownRx, vm::setLang,
                // Power path YES: the entered numbers are the power source ("customer's known power").
                onSave = { rx ->
                    vm.setKnownRx(rx)
                    startQuick(primary = false)
                },
                // "No numbers available": continue as NO, with the eye check.
                onNone = { vm.setPowerKnown(PowerKnown.NO); startQuick(primary = true) },
                // Back: return to the power question.
                onBack = { powerFromEdit = false; nav.popBackStack() },
            )
        }
        composable(Routes.COMPARE_OFFER) {
            CompareOfferScreen(
                state.lang, vm::setLang, voice.instructionSpeaker,
                onCheck = { startQuick(primary = false) },
                onSkip = { finishPowerPath() },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            Routes.QUICK,
            arguments = listOf(navArgument("primary") { type = NavType.BoolType; defaultValue = true }),
        ) { entry ->
            val l = state.lang
            val primary = entry.arguments?.getBoolean("primary") ?: true
            val plan = androidx.compose.runtime.remember { vm.eyeCheckPlan() }
            StepFrame(l, vm::setLang, SShop.eyeCheckTitle[l], onBack = { nav.popBackStack() }) {
                CameraQuickEstimateFlow(
                    l, vm.rules, vm.calibration, plan, state.answers, primary,
                    onDone = { q -> vm.setQuick(q); finishPowerPath() },
                    onSkip = { vm.setQuick(null); finishPowerPath() },
                    modifier = Modifier.fillMaxSize(),
                    voice = voice,
                    onInstruction = { voice.instructionSpeaker.speak(it, l) },
                    // Without camera permission: demo and precision mode use the distance slider (no dead end).
                    allowDemoWithoutCamera = state.demo || state.precision,
                    // Staff / device checks: the far letters' size line
                    debug = state.precision || state.demo,
                )
            }
        }
        composable(Routes.EDIT) {
            ConfirmScreen(
                state, vm.choiceContext, vm::setLang,
                onEdit = { id -> vm.editQuestion(id); nav.navigate(Routes.INTERVIEW) },
                onStaffMeasurements = vm::setStaffMeasurements,
                onSeeResults = { computeAndShow() },
                onBack = { nav.popBackStack() },
            )
        }

        // ---- face measurement for frame fit: forehead card (optional) → capture → outcome ----
        composable(Routes.CARD) {
            val l = state.lang
            StepFrame(l, vm::setLang, SFlow.stepCard[l], onBack = { nav.popBackStack() }) {
                ForeheadCardCalibrationScreen(
                    l, vm.calibration, vm.rules.camera,
                    onDone = { nav.navigate(Routes.CAPTURE) },
                    onSkip = { nav.navigate(Routes.CAPTURE) },
                )
            }
        }
        composable(Routes.CAPTURE) {
            val l = state.lang
            StepFrame(l, vm::setLang, SFlow.stepCapture[l], onBack = { nav.popBackStack() }) {
                FaceCaptureScreen(
                    l, vm.rules.camera,
                    onDone = { frames -> vm.setCapture(frames); nav.navigate(Routes.CAPTURE_OUTCOME) },
                    onSkip = { vm.skipCapture(); toLater() },
                    modifier = Modifier.fillMaxSize(),
                    speaker = voice.instructionSpeaker,
                )
            }
        }
        composable(Routes.CAPTURE_OUTCOME) {
            val status = state.captureStatus
            if (status == null) androidx.compose.runtime.LaunchedEffect(Unit) { toLater() }
            else CaptureOutcomeScreen(
                state.lang, vm::setLang, status,
                onRetry = { nav.navigate(Routes.CAPTURE) { popUpTo(Routes.CAPTURE) { inclusive = true } } },
                onContinue = { toLater() },
            )
        }

        composable(Routes.REFERRAL) {
            ReferralScreen(
                state, vm.rules, vm::setLang,
                onContinue = { nav.navigate(Routes.RESULT) { popUpTo(Routes.HOME) } },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.RESULT) {
            ResultScreen(
                state, vm.rules, vm::setLang,
                onSaveVerification = vm::saveVerification,
                onDelete = { vm.deleteCurrentSession { goHome() } },
                onDone = { goHome() },
                onBack = { goHome() },
                onEditAnswers = { nav.navigate(Routes.EDIT) },
                savedFrames = {
                    val rows by vm.savedFrames.collectAsStateWithLifecycle()
                    val frames = vm.frames3d.collectAsStateWithLifecycle().value.first
                    androidx.compose.runtime.LaunchedEffect(rows.isNotEmpty()) { if (rows.isNotEmpty() && frames.isEmpty()) vm.loadCatalog3D() }
                    com.drishtisaathi.tryon3d.SavedFramesCard(state.lang, rows, frames, vm::thumb3D)
                },
                extraActions = {
                    val context = LocalContext.current
                    val l = state.lang
                    fun export(customer: Boolean) = vm.exportPdf(
                        customer,
                        onReady = { file -> PdfExporter.share(context, file, (if (customer) S.customerCopy else S.shopSummary)[l]) },
                        onError = { Toast.makeText(context, S.pdfFailed[l], Toast.LENGTH_LONG).show() },
                    )
                    BigButton(S.exportShopPdf[l], { export(customer = false) })
                    BigButton(S.exportCustomerPdf[l], { export(customer = true) })
                },
                onToggleCompare = vm::toggleCompare,
                onCompare = { nav.navigate(Routes.COMPARE) },
                onTryOn = { id -> nav.navigate(Routes.tryOn(id)) },
                onTry3D = { vm.loadCatalog3D(); nav.navigate(Routes.CATALOG3D) },
                remoteCard = {
                    val bridge by vm.bridgeSettings.config.collectAsStateWithLifecycle()
                    RemoteReviewCard(
                        state, configured = bridge.isValid,
                        onSend = { vm.clearBridgeError(); nav.navigate(Routes.BRIDGE_CONSENT) },
                        onCheck = { vm.checkReview() },
                        onPoll = { vm.checkReview(silent = true) },
                    )
                },
            )
        }
        composable(Routes.BRIDGE_CONSENT) {
            BridgeConsentScreen(
                state.lang, vm::setLang, voice.instructionSpeaker, state.bridgeBusy, state.bridgeError,
                onSend = { consent -> vm.sendToOptometrist(consent) { nav.popBackStack() } },
                onCancel = { nav.popBackStack() },
            )
        }
        composable(Routes.BRIDGE_SETTINGS) {
            val bridge by vm.bridgeSettings.config.collectAsStateWithLifecycle()
            BridgeSettingsScreen(
                state.lang, vm::setLang, bridge,
                onSave = vm.bridgeSettings::save,
                onTest = vm::testBridge,
                onBack = { nav.popBackStack() },
            )
        }
        // ---- M5 live try-on (addition 8) ----
        composable(
            Routes.TRY_ON,
            arguments = listOf(navArgument("frame") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val l = state.lang
            val frames = state.result?.frames.orEmpty()
            CameraPermissionGate(l, onSkip = { nav.popBackStack() }) {
                val tracker = rememberFaceTracker(vm.rules.camera)
                TryOnScreen(
                    l, frames, tracker.frames,
                    cameraPreview = { CameraPreview(tracker, Modifier.fillMaxSize()) },
                    config = vm.rules.tryOn,
                    endpieceMmEachSide = vm.rules.frameScoring.endpieceMmEachSide,
                    onLang = vm::setLang,
                    onBack = { nav.popBackStack() },
                    mapping = PreviewMapping,
                    faceWidthMm = state.face?.faceWidthMm?.value,
                    pdMm = state.face?.pdMm?.value,
                    initialFrameId = entry.arguments?.getString("frame"),
                )
            }
        }
        // ---- 3D catalogue, viewer, live 3D try-on and face avatar ----
        val saveToast: (com.drishtisaathi.data.SaveResult) -> Unit = { res ->
            val t = when (res) {
                com.drishtisaathi.data.SaveResult.SAVED -> com.drishtisaathi.i18n.S3D.savedToast
                com.drishtisaathi.data.SaveResult.REMOVED -> com.drishtisaathi.i18n.S3D.removedToast
                com.drishtisaathi.data.SaveResult.NO_SESSION -> com.drishtisaathi.i18n.S3D.noSessionToast
            }
            Toast.makeText(vm.getApplication<android.app.Application>(), t[vm.state.value.lang], Toast.LENGTH_SHORT).show()
        }
        val frameArg = listOf(navArgument("frame") { type = NavType.StringType; nullable = true; defaultValue = null })
        composable(Routes.CATALOG3D) {
            val (frames, online) = vm.frames3d.collectAsStateWithLifecycle().value
            androidx.compose.runtime.LaunchedEffect(Unit) { if (frames.isEmpty()) vm.loadCatalog3D() }
            val rec = androidx.compose.runtime.remember(frames, state.result) { vm.recommended3D(frames) }
            com.drishtisaathi.tryon3d.Catalog3DScreen(
                state.lang, vm::setLang, frames, online, rec, vm::thumb3D,
                onOpen = { nav.navigate(Routes.viewer3d(it.id)) }, onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.VIEWER3D, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val frames = vm.frames3d.collectAsStateWithLifecycle().value.first
            val favs by vm.favourites3d.collectAsStateWithLifecycle()
            val f = frames.firstOrNull { it.id == entry.arguments?.getString("id") }
            if (f == null) {
                androidx.compose.runtime.LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                com.drishtisaathi.tryon3d.FrameViewer3DScreen(
                    state.lang, vm::setLang, f, vm::glb3D, vm::thumb3D, f.id in favs, { vm.toggleFavourite3D(f.id, saveToast) },
                    onLive = { nav.navigate(Routes.live3d(f.id)) },
                    onAvatar = { nav.navigate(if (vm.avatarStore.exists) Routes.avatar(f.id) else Routes.avatarConsent(f.id)) },
                    onBack = { nav.popBackStack() },
                )
            }
        }
        composable(Routes.LIVE3D, arguments = frameArg + navArgument("debug") { type = NavType.BoolType; defaultValue = false }) { entry ->
            val l = state.lang
            val all = vm.frames3d.collectAsStateWithLifecycle().value.first
            val favs by vm.favourites3d.collectAsStateWithLifecycle()
            val start = entry.arguments?.getString("frame")
            // the picked frame, the favourites, then the recommended order (kept short for quick switching)
            val picks = androidx.compose.runtime.remember(all, favs, start) {
                val order = listOfNotNull(start) + favs + vm.recommended3D(all) + all.map { it.id }
                order.distinct().mapNotNull { id -> all.firstOrNull { it.id == id } }.take(20)
            }
            CameraPermissionGate(l, onSkip = { nav.popBackStack() }) {
                val tracker = rememberFaceTracker(vm.rules.camera)
                com.drishtisaathi.tryon3d.LiveTryOn3DScreen(
                    l, vm::setLang, picks, start, tracker.frames,
                    cameraPreview = { CameraPreview(tracker, Modifier.fillMaxSize()) },
                    glb = vm::glb3D, thumb = vm::thumb3D,
                    pdMm = state.face?.pdMm?.value, config = vm.rules.tryOn,
                    debug = entry.arguments?.getBoolean("debug") ?: false,
                    fallback2D = {
                        TryOnScreen(
                            l, state.result?.frames.orEmpty(), tracker.frames,
                            cameraPreview = { CameraPreview(tracker, Modifier.fillMaxSize()) },
                            config = vm.rules.tryOn,
                            endpieceMmEachSide = vm.rules.frameScoring.endpieceMmEachSide,
                            onLang = vm::setLang, onBack = { nav.popBackStack() }, mapping = PreviewMapping,
                            faceWidthMm = state.face?.faceWidthMm?.value, pdMm = state.face?.pdMm?.value,
                        )
                    },
                    onBack = { nav.popBackStack() },
                )
            }
        }
        composable(Routes.AVATAR_CONSENT, arguments = frameArg) { entry ->
            val id = entry.arguments?.getString("frame")
            com.drishtisaathi.tryon3d.AvatarConsentScreen(
                state.lang, vm::setLang,
                onAgree = { nav.navigate(Routes.avatarCapture(id)) { popUpTo(Routes.AVATAR_CONSENT) { inclusive = true } } },
                onNo = { nav.popBackStack() },
            )
        }
        composable(Routes.AVATAR_CAPTURE, arguments = frameArg) { entry ->
            val l = state.lang
            val id = entry.arguments?.getString("frame")
            CameraPermissionGate(l, onSkip = { nav.popBackStack() }) {
                // Staff mode (back camera) by default when the shop tools started the session, else Self
                var mode by androidx.compose.runtime.saveable.rememberSaveable {
                    mutableStateOf(if (vm.avatarStaffDefault) com.drishtisaathi.engine.tryon3d.CaptureMode.STAFF else com.drishtisaathi.engine.tryon3d.CaptureMode.SELF)
                }
                val tracker = rememberFaceTracker(vm.rules.camera, avatarPhoto = true, backCamera = mode == com.drishtisaathi.engine.tryon3d.CaptureMode.STAFF)
                com.drishtisaathi.tryon3d.AvatarCaptureScreen(
                    l, vm::setLang, tracker, mode, { mode = it },
                    cameraPreview = { CameraPreview(tracker, Modifier.fillMaxSize()) },
                    speak = { t -> voice.instructionSpeaker.speak(t, l) },
                    pdMm = state.face?.pdMm?.value,
                    minBrightness = vm.rules.camera.minMeanBrightness, maxBrightness = vm.rules.camera.maxMeanBrightness,
                    defaultFocalFraction = vm.rules.tryOn.defaultFocalLengthFraction,
                    store = vm.avatarStore,
                    onDone = { nav.navigate(Routes.avatar(id)) { popUpTo(Routes.AVATAR_CAPTURE) { inclusive = true } } },
                    onBack = { nav.popBackStack() },
                )
            }
        }
        composable(Routes.AVATAR, arguments = frameArg) { entry ->
            val all = vm.frames3d.collectAsStateWithLifecycle().value.first
            val favs by vm.favourites3d.collectAsStateWithLifecycle()
            val start = entry.arguments?.getString("frame")
            val picks = androidx.compose.runtime.remember(all, favs, start) {
                val order = listOfNotNull(start) + favs + vm.recommended3D(all) + all.map { it.id }
                order.distinct().mapNotNull { id -> all.firstOrNull { it.id == id } }.take(20)
            }
            com.drishtisaathi.tryon3d.AvatarViewerScreen(
                state.lang, vm::setLang, vm.avatarStore, picks, start, vm::glb3D, vm::thumb3D, favs, { id -> vm.toggleFavourite3D(id, saveToast) },
                config = vm.rules.tryOn,
                onRetake = { vm.avatarStore.clear(); nav.navigate(Routes.avatarCapture(start)) { popUpTo(Routes.AVATAR) { inclusive = true } } },
                onDelete = { vm.clearAvatar(); nav.popBackStack() },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.COMPARE) {
            CompareScreen(state, vm.engine, vm.rules, vm::setLang, onBack = { nav.popBackStack() })
        }
        composable(Routes.HISTORY) {
            val sessions by vm.sessions.collectAsState(initial = emptyList())
            val remote by vm.remoteReviews.collectAsState(initial = emptyMap())
            // Check sent sessions for replies when Past sessions opens, then every 15 s while it is open.
            androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { vm.checkAllWaiting(); kotlinx.coroutines.delay(15_000) } }
            HistoryScreen(
                state.lang, sessions, vm::setLang, remote = remote,
                onOpen = { id -> vm.openSession(id) { nav.navigate(Routes.RESULT) } },
                onDelete = { id -> vm.deleteSession(id) },
                onBack = { nav.popBackStack() },
            )
        }

        // ---- shop tools ----
        composable(Routes.CAMERA_DEBUG) {
            val l = state.lang
            StepFrame(l, vm::setLang, SFlow.cameraCheck[l], onBack = { nav.popBackStack() }) {
                CameraDebugScreen(l, vm.rules.camera, vm.calibration, onSkip = { nav.popBackStack() }, modifier = Modifier.fillMaxSize())
            }
        }
        composable(Routes.DEMO) {
            DemoScreen(
                state.lang, vm::setLang, DemoCustomers.all,
                // Sample customers carry every answer and measurement: straight to the results.
                onPick = { c -> vm.loadDemo(c); computeAndShow() },
                onBack = { nav.popBackStack() },
            )
        }
    }
    if (state.demo) DemoBadge(state.lang, Modifier.align(androidx.compose.ui.Alignment.TopCenter))
    }
}
