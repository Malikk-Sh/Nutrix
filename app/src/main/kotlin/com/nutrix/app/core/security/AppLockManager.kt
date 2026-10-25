package com.nutrix.app.core.security

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.nutrix.app.core.datastore.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class LockState { CHECKING, LOCKED, UNLOCKED }

sealed interface PinResult {
    data object Success : PinResult
    data class Wrong(val attemptsLeft: Int) : PinResult
    data class LockedOut(val secondsLeft: Int) : PinResult
}

@Singleton
class AppLockManager @Inject constructor(
    private val settings: SettingsRepository,
    private val cipher: SecretCipher,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(LockState.CHECKING)
    val state: StateFlow<LockState> = _state.asStateFlow()

    private var backgroundSince: Long? = null
    private var externalTransitionUntil = 0L
    private var failedAttempts = 0
    private var lockoutUntil = 0L

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch {
            val s = settings.current()
            _state.value = if (s.lockEnabled && settings.pinBlob() != null) LockState.LOCKED else LockState.UNLOCKED
        }
    }

    /** Call before opening the camera app, photo picker or file picker so returning does not re-lock. */
    fun allowExternalTransition() {
        externalTransitionUntil = SystemClock.elapsedRealtime() + 10 * 60 * 1000L
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundSince = SystemClock.elapsedRealtime()
    }

    override fun onStart(owner: LifecycleOwner) {
        val since = backgroundSince ?: return
        backgroundSince = null
        scope.launch {
            val s = settings.current()
            if (!s.lockEnabled || settings.pinBlob() == null) return@launch
            val now = SystemClock.elapsedRealtime()
            if (now < externalTransitionUntil) {
                externalTransitionUntil = 0L
                return@launch
            }
            if (now - since >= s.lockTimeoutSeconds * 1000L) _state.value = LockState.LOCKED
        }
    }

    suspend fun hasPin(): Boolean = settings.pinBlob() != null

    suspend fun setPin(pin: String) {
        val verifier = withContext(Dispatchers.Default) { PinHasher.create(pin) }
        settings.setPinBlob(cipher.encrypt(verifier.encode()))
    }

    suspend fun disableLock() {
        settings.setLockEnabled(false)
        settings.setBiometricEnabled(false)
        settings.setPinBlob(null)
        _state.value = LockState.UNLOCKED
    }

    suspend fun verifyPin(pin: String): Boolean {
        val blob = settings.pinBlob() ?: return false
        return withContext(Dispatchers.Default) {
            runCatching { PinHasher.verify(pin, PinHasher.Verifier.decode(cipher.decrypt(blob))) }.getOrDefault(false)
        }
    }

    suspend fun unlockWithPin(pin: String): PinResult {
        val now = SystemClock.elapsedRealtime()
        if (now < lockoutUntil) return PinResult.LockedOut(((lockoutUntil - now) / 1000).toInt() + 1)
        return if (verifyPin(pin)) {
            failedAttempts = 0
            _state.value = LockState.UNLOCKED
            PinResult.Success
        } else {
            failedAttempts++
            if (failedAttempts >= MAX_ATTEMPTS) {
                failedAttempts = 0
                lockoutUntil = now + LOCKOUT_MS
                PinResult.LockedOut((LOCKOUT_MS / 1000).toInt())
            } else {
                PinResult.Wrong(MAX_ATTEMPTS - failedAttempts)
            }
        }
    }

    fun unlockWithBiometrics() {
        failedAttempts = 0
        _state.value = LockState.UNLOCKED
    }

    fun lockNow() {
        scope.launch { if (settings.current().lockEnabled && hasPin()) _state.value = LockState.LOCKED }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 30_000L
    }
}
