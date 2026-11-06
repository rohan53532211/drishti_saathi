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
import com.drishtisaathi.ui.ConfirmScreen
import com.drishtisaathi.ui.DrishtiTheme
import com.drishtisaathi.ui.HomeScreen
import com.drishtisaathi.ui.InterviewScreen
import com.drishtisaathi.ui.OldNumbersScreen
import com.drishtisaathi.ui.ResultScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DrishtiTheme { DrishtiApp() } }
    }
}

private object Routes {
    const val HOME = "home"
    const val INTERVIEW = "interview"
    const val OLD_NUMBERS = "oldNumbers"
    const val CONFIRM = "confirm"
    const val RESULT = "result"
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
                onStart = { vm.startNew(); nav.navigate(Routes.INTERVIEW) },
                onHistory = { /* step 8 */ },
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
                onSeeResults = { vm.computeResult(); nav.navigate(Routes.RESULT) },
                onBack = { goHome() },
            )
        }
        composable(Routes.RESULT) {
            ResultScreen(state, vm::setLang, onDone = { goHome() }, onBack = { nav.popBackStack() })
        }
    }
}
