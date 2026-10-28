package com.nutrix.app.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrix.app.AppRestarter
import com.nutrix.app.backup.DiaryDataEraser
import com.nutrix.app.R
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.datastore.UserSettings
import com.nutrix.app.core.model.ThemeMode
import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.core.security.AppLockManager
import com.nutrix.app.data.repository.UserMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val lock: AppLockManager,
    private val messages: UserMessages,
    private val eraser: DiaryDataEraser,
) : ViewModel() {
    val state: StateFlow<UserSettings?> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val hasPin = MutableStateFlow(false)

    init {
        viewModelScope.launch { hasPin.value = lock.hasPin() }
    }

    fun setUnit(u: WeightUnit) = viewModelScope.launch { settings.setWeightUnit(u) }
    fun setTheme(t: ThemeMode) = viewModelScope.launch { settings.setThemeMode(t) }
    fun setSmart(v: Boolean) = viewModelScope.launch { settings.setSmartSuggestions(v) }
    fun setKcal(v: Boolean) = viewModelScope.launch { settings.setCalorieEstimates(v) }

    fun saveProfile(name: String, heightCm: Int?, startKg: Double?, targetKg: Double?) = viewModelScope.launch {
        settings.setProfile(name, heightCm, startKg, targetKg)
        messages.show(R.string.profile_saved)
    }

    fun setPin(pin: String) = viewModelScope.launch {
        lock.setPin(pin)
        settings.setLockEnabled(true)
        hasPin.value = true
    }

    fun setBiometric(v: Boolean) = viewModelScope.launch { settings.setBiometricEnabled(v) }
    fun setTimeout(seconds: Int) = viewModelScope.launch { settings.setLockTimeout(seconds) }
    fun setSecureScreen(v: Boolean) = viewModelScope.launch { settings.setSecureScreen(v) }

    fun disableLock() = viewModelScope.launch {
        lock.disableLock()
        hasPin.value = false
    }

    /** Irreversibly wipes the diary, photos and settings, then restarts the app. */
    fun deleteAll() = viewModelScope.launch {
        try {
            eraser.erase()
            AppRestarter.restart(context)
        } catch (e: Exception) {
            messages.show(R.string.error_generic, context.getString(R.string.settings_delete_all_title))
        }
    }
}
