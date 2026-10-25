package com.nutrix.app.feature.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.nutrix.app.R
import com.nutrix.app.core.security.AppLockManager
import com.nutrix.app.core.security.PinResult
import com.nutrix.app.integration.findActivity
import kotlinx.coroutines.launch

const val BIOMETRIC_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK

fun canUseBiometrics(context: android.content.Context): Boolean =
    BiometricManager.from(context).canAuthenticate(BIOMETRIC_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

@Composable
fun LockScreen(lockManager: AppLockManager, biometricEnabled: Boolean) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val title = stringResource(R.string.lock_biometric_title)
    val negative = stringResource(R.string.lock_biometric_negative)
    val biometricAvailable = biometricEnabled && canUseBiometrics(context)

    fun showBiometric() {
        val activity = context.findActivity() as? FragmentActivity ?: return
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    lockManager.unlockWithBiometrics()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setNegativeButtonText(negative)
                .setAllowedAuthenticators(BIOMETRIC_AUTHENTICATORS)
                .build(),
        )
    }

    LaunchedEffect(biometricAvailable) { if (biometricAvailable) showBiometric() }

    fun submit() {
        val entered = pin
        scope.launch {
            when (val r = lockManager.unlockWithPin(entered)) {
                PinResult.Success -> message = null
                is PinResult.Wrong -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    message = context.getString(R.string.lock_wrong_pin, r.attemptsLeft)
                    pin = ""
                }
                is PinResult.LockedOut -> {
                    message = context.getString(R.string.lock_locked_out, r.secondsLeft)
                    pin = ""
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Swallow touches so nothing underneath the lock screen can be operated.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.lock_enter_pin), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(maxOf(4, pin.length)) { i ->
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(if (i < pin.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(message ?: " ", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "bio", "0", "del")
            keys.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { key ->
                        Box(Modifier.weight(1f).aspectRatio(1.4f), contentAlignment = Alignment.Center) {
                            when (key) {
                                "bio" -> if (biometricAvailable) IconButton(onClick = ::showBiometric) {
                                    Icon(Icons.Rounded.Fingerprint, stringResource(R.string.lock_use_biometric), modifier = Modifier.size(30.dp))
                                }
                                "del" -> IconButton(onClick = { if (pin.isNotEmpty()) pin = pin.dropLast(1) }) {
                                    Icon(Icons.AutoMirrored.Rounded.Backspace, stringResource(R.string.action_delete))
                                }
                                else -> FilledTonalButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (pin.length < 8) pin += key
                                    },
                                    shape = CircleShape,
                                    modifier = Modifier.size(64.dp),
                                ) { Text(key, style = MaterialTheme.typography.titleLarge) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = ::submit, enabled = pin.length >= 4) { Text(stringResource(R.string.action_continue)) }
        }
    }
}
