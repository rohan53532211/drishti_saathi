package com.drishtisaathi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.drishtisaathi.interview.Questions
import com.drishtisaathi.ui.CompareScreen
import com.drishtisaathi.ui.ConfirmScreen
import com.drishtisaathi.ui.ConsentScreen
import com.drishtisaathi.ui.DrishtiTheme
import com.drishtisaathi.ui.HomeScreen
import com.drishtisaathi.ui.InterviewScreen
import com.drishtisaathi.ui.OldNumbersScreen
import com.drishtisaathi.ui.ReferralScreen
import com.drishtisaathi.ui.ResultScreen
import com.drishtisaathi.engine.ReferralLevel
import com.drishtisaathi.i18n.S
import com.drishtisaathi.pdf.PdfExporter
import com.drishtisaathi.ui.BigButton
import com.drishtisaathi.ui.HistoryScreen
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext

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
    const val CONFIRM = "confirm"
    const val REFERRAL = "referral"
    const val RESULT = "result"
    const val HISTORY = "history"
    const val COMPARE = "compare"
}

@Composable
fun DrishtiApp(vm: SessionViewModel = viewModel()) {
    val nav = rememberNavController()
    val state by vm.state.collectAsStateWithLifecycle()

    fun goHome() = nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }
    fun toConfirm() = nav.navigate(Routes.CONFIRM) { popUpTo(Routes.HOME) }

    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                state, vm::setLang, vm::setStaffMode,
                onStart = { vm.startNew(); nav.navigate(Routes.CONSENT) },
                onHistory = { nav.navigate(Routes.HISTORY) },
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
                onAnswer = { id, update -> if (vm.answer(id, update)) toConfirm() },
                onOldNumbersYes = { nav.navigate(Routes.OLD_NUMBERS) },
                onBack = {
                    when {
                        state.editingFromConfirm -> toConfirm()
                        !vm.previousQuestion() -> nav.popBackStack()
                    }
                },
            )
        }
        composable(Routes.OLD_NUMBERS) {
            OldNumbersScreen(
                state.lang, state.answers.knownRx, vm::setLang,
                onSave = { rx ->
                    nav.popBackStack()
                    if (vm.answer(Questions.KNOWN_RX) { it.copy(knownRx = rx) }) toConfirm()
                },
                onNone = {
                    nav.popBackStack()
                    if (vm.answer(Questions.KNOWN_RX) { it.copy(knownRx = null) }) toConfirm()
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.CONFIRM) {
            ConfirmScreen(
                state, vm.choiceContext, vm::setLang,
                onEdit = { id -> vm.editQuestion(id); nav.navigate(Routes.INTERVIEW) },
                onStaffMeasurements = vm::setStaffMeasurements,
                onSeeResults = {
                    val r = vm.computeResult()
                    // Any referral trigger is shown on its own screen before the summary.
                    nav.navigate(if (r.assessment.referral.level == ReferralLevel.NONE) Routes.RESULT else Routes.REFERRAL)
                },
                onBack = { goHome() },
            )
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
            )
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
    }
}
