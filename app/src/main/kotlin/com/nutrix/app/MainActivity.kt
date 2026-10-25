package com.nutrix.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.activity.viewModels
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.datastore.UserSettings
import com.nutrix.app.core.security.AppLockManager
import com.nutrix.app.core.security.LockState
import com.nutrix.app.core.ui.components.LocalExternalTransition
import com.nutrix.app.core.ui.components.LocalSnackbarHost
import com.nutrix.app.core.ui.components.showUndo
import com.nutrix.app.core.ui.theme.NutrixTheme
import com.nutrix.app.data.repository.UndoManager
import com.nutrix.app.data.repository.UserMessages
import com.nutrix.app.feature.lock.LockScreen
import com.nutrix.app.navigation.AppActions
import com.nutrix.app.navigation.NutrixNavHost
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    settings: SettingsRepository,
    val lock: AppLockManager,
    val undo: UndoManager,
    val messages: UserMessages,
) : ViewModel() {
    val settings: StateFlow<UserSettings?> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pendingAction = MutableStateFlow<String?>(null)
}

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val rootViewModel: RootViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition {
            rootViewModel.settings.value == null || rootViewModel.lock.state.value == LockState.CHECKING
        }
        if (savedInstanceState == null) handleIntent(intent)

        rootViewModel.viewModelScope.launch {
            rootViewModel.settings.collect { s ->
                val secure = s?.secureScreen == true
                if (secure) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        setContent { NutrixRoot(rootViewModel) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(AppActions.EXTRA_ACTION)?.let { rootViewModel.pendingAction.value = it }
        intent?.removeExtra(AppActions.EXTRA_ACTION)
    }
}

@Composable
fun NutrixRoot(vm: RootViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lockState by vm.lock.state.collectAsStateWithLifecycle()
    val pendingAction by vm.pendingAction.collectAsStateWithLifecycle()
    val s = settings ?: return
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.undo.pending.collect { p ->
            launch {
                snackbar.showUndo(context.getString(p.message), context.getString(R.string.action_undo), onUndo = p.undo, onCommit = p.commit)
            }
        }
    }
    LaunchedEffect(Unit) {
        vm.messages.messages.collect { m ->
            launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(context.getString(m.res, *m.args.toTypedArray()))
            }
        }
    }

    NutrixTheme(s.themeMode) {
        CompositionLocalProvider(
            LocalSnackbarHost provides snackbar,
            LocalExternalTransition provides { vm.lock.allowExternalTransition() },
        ) {
            Box(Modifier.fillMaxSize()) {
                // While locked the diary is covered by an opaque lock screen and hidden from accessibility.
                if (lockState != LockState.CHECKING) {
                    Box(if (lockState == LockState.LOCKED) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) {
                        NutrixNavHost(
                            pendingAction = pendingAction.takeIf { lockState == LockState.UNLOCKED },
                            onActionConsumed = { vm.pendingAction.value = null },
                        )
                    }
                }
                SnackbarHost(
                    snackbar,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(bottom = 84.dp),
                )
                if (lockState == LockState.LOCKED) {
                    LockScreen(vm.lock, biometricEnabled = s.biometricEnabled)
                }
            }
        }
    }
}
