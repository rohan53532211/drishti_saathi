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
import com.drishtisaathi.calibration.ScreenCardCalibrationScreen
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
import com.drishtisaathi.ui.GlassesOffScreen
import com.drishtisaathi.ui.DemoBadge
import com.drishtisaathi.demo.DemoCustomers
import com.drishtisaathi.vision.CameraVisionTestFlow
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
    const val CONSENT = "consent"
    const val INTERVIEW = "interview"
    const val OLD_NUMBERS = "oldNumbers"
    // Power path: optional compare offer (YES), glasses off and the quick estimate (NOT SURE / NO, or the compare)
    const val COMPARE_OFFER = "compareOffer"
    const val POWER_GLASSES_OFF = "powerGlassesOff?primary={primary}"
    const val QUICK = "quick?primary={primary}"
    fun powerGlassesOff(primary: Boolean) = "powerGlassesOff?primary=$primary"
    fun quick(primary: Boolean) = "quick?primary=$primary"
    const val CONFIRM = "confirm"
    // Integrated flow: card check (optional) → face capture → outcome → screen tests → results
    const val GLASSES_OFF = "glassesOff"
    const val CARD = "card"
    const val CAPTURE = "capture"
    const val CAPTURE_OUTCOME = "captureOutcome"
    const val TESTS = "tests"
    const val REFERRAL = "referral"
    const val RESULT = "result"
    const val TRY_ON = "tryOn?frame={frame}"
    const val HISTORY = "history"
    const val COMPARE = "compare"
    // Staff tools
    const val CAMERA_DEBUG = "cameraDebug"
    const val SCREEN_CAL = "screenCalibration"
    const val DEMO = "demo"
    // Remote Optometrist Bridge
    const val BRIDGE_CONSENT = "bridgeConsent"
    const val BRIDGE_SETTINGS = "bridgeSettings"
    // AI voice interview (Sarvam via the backend)
    const val AI_CONSENT = "aiConsent"
    const val AI_INTERVIEW = "aiInterview"

    fun tryOn(frameId: String?) = "tryOn" + (frameId?.let { "?frame=$it" } ?: "")
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
    fun toConfirm() = nav.navigate(Routes.CONFIRM) { popUpTo(Routes.HOME) }

    // ---- power path: side screens opened from the POWER_KNOWN question, then back to the interview ----
    /** The power answer finished the interview (last question, or an edit from the confirmation screen). */
    var powerToConfirm by remember { mutableStateOf(false) }
    fun finishPowerPath() {
        nav.popBackStack(Routes.INTERVIEW, inclusive = false)
        if (powerToConfirm) { powerToConfirm = false; toConfirm() }
    }
    /** Runs the quick estimate (glasses off first), or stores the no-test outcome and returns at once. */
    fun startQuick(primary: Boolean) {
        val plan = vm.quickPlan()
        if (!plan.runsAnything) {
            vm.setQuick(if (primary && plan.possibleLongSight) QuickEstimate(possibleLongSight = true) else null)
            finishPowerPath()
        } else nav.navigate(Routes.powerGlassesOff(primary))
    }

    /** Runs the engine with everything gathered so far; any referral is shown before the summary. */
    fun computeAndShow() {
        val r = vm.computeResult()
        val target = if (r.assessment.referral.level == ReferralLevel.NONE) Routes.RESULT else Routes.REFERRAL
        nav.navigate(target) { popUpTo(Routes.CONFIRM) }
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                state, vm::setLang, vm::setStaffMode,
                onStart = { vm.startNew(); nav.navigate(Routes.CONSENT) },
                onHistory = { nav.navigate(Routes.HISTORY) },
                staffTools = {
                    val l = state.lang
                    BigButton(SFlow.cameraCheck[l], { nav.navigate(Routes.CAMERA_DEBUG) })
                    BigButton(SFlow.screenCalibration[l], { nav.navigate(Routes.SCREEN_CAL) })
                    BigButton(SFlow.demoMode[l], { nav.navigate(Routes.DEMO) })
                    BigButton(com.drishtisaathi.i18n.SBridge.settingsTitle[l], { nav.navigate(Routes.BRIDGE_SETTINGS) })
                },
            )
        }
        composable(Routes.CONSENT) {
            ConsentScreen(
                state.lang, vm::setLang,
                onContinue = { vm.giveConsent(); nav.navigate(Routes.INTERVIEW) { popUpTo(Routes.HOME) } },
                onCancel = { goHome() },
            )
        }
        composable(Routes.INTERVIEW) {
            InterviewScreen(
                state, vm.choiceContext, vm::setLang,
                onAnswer = { id, update ->
                    val done = vm.answer(id, update)
                    if (id == Questions.POWER_KNOWN) {
                        powerToConfirm = done
                        vm.setQuick(null)
                        if (vm.state.value.answers.powerKnown == PowerKnown.YES) nav.navigate(Routes.OLD_NUMBERS)
                        else startQuick(primary = true)
                    } else if (done) toConfirm()
                },
                onOldNumbersYes = { nav.navigate(Routes.OLD_NUMBERS) },
                onBack = {
                    when {
                        state.editingFromConfirm -> toConfirm()
                        !vm.previousQuestion() -> nav.popBackStack()
                    }
                },
                voice = voice,
                startExtra = if (!vm.aiAvailable || state.aiInterview != null || state.demo) null else {
                    {
                        val l = state.lang
                        androidx.compose.material3.Text(com.drishtisaathi.i18n.SAiVoice.startHint[l],
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                        BigButton(com.drishtisaathi.i18n.SAiVoice.startButton[l], { nav.navigate(Routes.AI_CONSENT) })
                    }
                },
            )
        }
        composable(Routes.AI_CONSENT) {
            AiConsentScreen(
                state.lang, vm::setLang, voice.instructionSpeaker,
                onContinue = { lang ->
                    vm.startAiInterview(lang)
                    nav.navigate(Routes.AI_INTERVIEW) { popUpTo(Routes.AI_CONSENT) { inclusive = true } }
                },
                onUseButtons = { nav.popBackStack() },
            )
        }
        composable(Routes.AI_INTERVIEW) {
            val ai by vm.aiState.collectAsStateWithLifecycle()
            /** Back to the standard interview at the first unanswered question (or the confirmation screen). */
            fun leave(fellBack: Boolean) = vm.finishAiInterview(fellBack) { all ->
                nav.popBackStack(Routes.INTERVIEW, inclusive = false)
                if (all) toConfirm()
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
                    if (vm.offerCompare()) nav.navigate(Routes.COMPARE_OFFER) { popUpTo(Routes.INTERVIEW) }
                    else finishPowerPath()
                },
                // "No numbers available": continue as NO, with the quick estimate.
                onNone = { vm.setPowerKnown(PowerKnown.NO); startQuick(primary = true) },
                // Back: return to the power question.
                onBack = { powerToConfirm = false; vm.previousQuestion(); nav.popBackStack() },
            )
        }
        composable(Routes.COMPARE_OFFER) {
            CompareOfferScreen(
                state.lang, vm::setLang, voice.instructionSpeaker,
                onCheck = { nav.navigate(Routes.powerGlassesOff(primary = false)) },
                onSkip = { finishPowerPath() },
                onBack = { finishPowerPath() },
            )
        }
        composable(
            Routes.POWER_GLASSES_OFF,
            arguments = listOf(navArgument("primary") { type = NavType.BoolType; defaultValue = true }),
        ) { entry ->
            val primary = entry.arguments?.getBoolean("primary") ?: true
            GlassesOffScreen(
                state.lang, vm::setLang, state.staffMode, voice.instructionSpeaker,
                onOff = { vm.setGlassesOn(false); nav.navigate(Routes.quick(primary)) },
                onKeepOn = { vm.setGlassesOn(true); nav.navigate(Routes.quick(primary)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            Routes.QUICK,
            arguments = listOf(navArgument("primary") { type = NavType.BoolType; defaultValue = true }),
        ) { entry ->
            val l = state.lang
            val primary = entry.arguments?.getBoolean("primary") ?: true
            val plan = androidx.compose.runtime.remember { vm.quickPlan() }
            StepFrame(l, vm::setLang, com.drishtisaathi.i18n.SAnamnesis.quickTitle[l], onBack = { nav.popBackStack() }) {
                CameraQuickEstimateFlow(
                    l, vm.rules, vm.calibration, plan, state.answers, primary,
                    onDone = { q -> vm.setQuick(q); finishPowerPath() },
                    onSkip = {
                        vm.setQuick(if (primary && plan.possibleLongSight) QuickEstimate(possibleLongSight = true) else null)
                        finishPowerPath()
                    },
                    modifier = Modifier.fillMaxSize(),
                    voice = voice,
                    onInstruction = { voice.instructionSpeaker.speak(it, l) },
                    allowDemoWithoutCamera = state.staffMode,
                )
            }
        }
        composable(Routes.CONFIRM) {
            ConfirmScreen(
                state, vm.choiceContext, vm::setLang,
                onEdit = { id -> vm.editQuestion(id); nav.navigate(Routes.INTERVIEW) },
                onStaffMeasurements = vm::setStaffMeasurements,
                onFullFarTest = vm::setFullFarTest,
                onSeeResults = {
                    // Demo customers already carry camera and screen-test results.
                    if (state.demo) { computeAndShow(); return@ConfirmScreen }
                    // Red flags from the interview: no camera, no screen tests, straight to the referral.
                    if (vm.interviewNeedsUrgentReferral()) {
                        vm.skipCapture(); vm.skipVisionTests(); computeAndShow()
                    } else nav.navigate(Routes.GLASSES_OFF)
                },
                onBack = { goHome() },
            )
        }

        // ---- glasses off before any camera step or screen test (staff may override) ----
        composable(Routes.GLASSES_OFF) {
            GlassesOffScreen(
                state.lang, vm::setLang, state.staffMode, voice.instructionSpeaker,
                onOff = { vm.setGlassesOn(false); nav.navigate(Routes.CARD) },
                onKeepOn = { vm.setGlassesOn(true); nav.navigate(Routes.CARD) },
                onBack = { nav.popBackStack() },
            )
        }
        // ---- M0 per customer: forehead card → personal iris size (optional) ----
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
        // ---- M2 face capture ----
        composable(Routes.CAPTURE) {
            val l = state.lang
            StepFrame(l, vm::setLang, SFlow.stepCapture[l], onBack = { nav.popBackStack() }) {
                FaceCaptureScreen(
                    l, vm.rules.camera,
                    onDone = { frames -> vm.setCapture(frames); nav.navigate(Routes.CAPTURE_OUTCOME) },
                    onSkip = { vm.skipCapture(); nav.navigate(Routes.TESTS) },
                    modifier = Modifier.fillMaxSize(),
                    speaker = voice.instructionSpeaker,
                )
            }
        }
        composable(Routes.CAPTURE_OUTCOME) {
            val status = state.captureStatus
            if (status == null) androidx.compose.runtime.LaunchedEffect(Unit) { nav.navigate(Routes.TESTS) }
            else CaptureOutcomeScreen(
                state.lang, vm::setLang, status,
                onRetry = { nav.navigate(Routes.CAPTURE) { popUpTo(Routes.CAPTURE) { inclusive = true } } },
                onContinue = { nav.navigate(Routes.TESTS) },
            )
        }
        // ---- M3 screen tests → M4 fusion (engine) ----
        composable(Routes.TESTS) {
            val l = state.lang
            StepFrame(l, vm::setLang, SFlow.stepTests[l], onBack = null) {
                CameraVisionTestFlow(
                    l, vm.rules, vm.calibration, state.answers.age, state.answers.occupation,
                    // Power path: the power is known or estimated already; staff can still ask for the full far point.
                    farPoint = state.answers.powerKnown == null || state.fullFarTest,
                    nearPoint = state.quick?.let { it.nearPointCm == null && it.addD == null } ?: true,
                    workingDistanceM = state.answers.nearDistance?.let { vm.quickEstimator.workingDistanceM(state.answers) },
                    onDone = { tests, _ -> vm.setVisionTests(tests); computeAndShow() },
                    onCancel = { vm.skipVisionTests(); computeAndShow() },
                    modifier = Modifier.fillMaxSize(),
                    onInstruction = { voice.instructionSpeaker.speak(it, l) },
                    // Staff can demo the tests without a camera using the distance slider.
                    allowDemoWithoutCamera = state.staffMode,
                )
            }
        }

        composable(Routes.REFERRAL) {
            ReferralScreen(
                state, vm.rules, vm::setLang,
                onContinue = { nav.navigate(Routes.RESULT) { popUpTo(Routes.CONFIRM) } },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.RESULT) {
            ResultScreen(
                state, vm.rules, vm::setLang,
                onSaveVerification = vm::saveVerification,
                onDelete = { vm.deleteCurrentSession { goHome() } },
                onDone = { goHome() },
                onBack = { nav.popBackStack() },
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
                remoteCard = {
                    val bridge by vm.bridgeSettings.config.collectAsStateWithLifecycle()
                    RemoteReviewCard(
                        state, configured = bridge.isValid,
                        onSend = { vm.clearBridgeError(); nav.navigate(Routes.BRIDGE_CONSENT) },
                        onCheck = { vm.checkReview() },
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
        composable(Routes.COMPARE) {
            CompareScreen(state, vm.engine, vm.rules, vm::setLang, onBack = { nav.popBackStack() })
        }
        composable(Routes.HISTORY) {
            val sessions by vm.sessions.collectAsState(initial = emptyList())
            HistoryScreen(
                state.lang, sessions, vm::setLang,
                onOpen = { id -> vm.openSession(id) { nav.navigate(Routes.RESULT) } },
                onDelete = { id -> vm.deleteSession(id) },
                onBack = { nav.popBackStack() },
            )
        }

        // ---- staff tools ----
        composable(Routes.CAMERA_DEBUG) {
            val l = state.lang
            StepFrame(l, vm::setLang, SFlow.cameraCheck[l], onBack = { nav.popBackStack() }) {
                CameraDebugScreen(l, vm.rules.camera, vm.calibration, onSkip = { nav.popBackStack() }, modifier = Modifier.fillMaxSize())
            }
        }
        composable(Routes.DEMO) {
            DemoScreen(
                state.lang, vm::setLang, DemoCustomers.all,
                onPick = { c -> vm.loadDemo(c); nav.navigate(Routes.CONFIRM) { popUpTo(Routes.HOME) } },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SCREEN_CAL) {
            val l = state.lang
            val context = LocalContext.current
            StepFrame(l, vm::setLang, SFlow.screenCalibration[l], onBack = { nav.popBackStack() }) {
                ScreenCardCalibrationScreen(
                    l, vm.calibration, vm.rules.camera,
                    onDone = {
                        Toast.makeText(context, SFlow.screenCalibrated[l], Toast.LENGTH_SHORT).show()
                        nav.popBackStack()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    if (state.demo) DemoBadge(state.lang, Modifier.align(androidx.compose.ui.Alignment.TopCenter))
    }
}
