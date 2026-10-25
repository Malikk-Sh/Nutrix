package com.nutrix.app.integration

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrix.app.R
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.datastore.SourceSyncState
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.data.repository.HealthRepository
import com.nutrix.app.data.repository.UserMessages
import com.nutrix.app.data.repository.WeightRepository
import com.nutrix.app.integration.healthconnect.HcAvailability
import com.nutrix.app.integration.healthconnect.HealthConnectManager
import com.nutrix.app.integration.samsung.SamsungDataCategory
import com.nutrix.app.integration.samsung.SamsungHealthException
import com.nutrix.app.integration.samsung.SamsungHealthGateway
import com.nutrix.app.integration.samsung.SamsungStatus
import com.nutrix.app.integration.sync.HealthSyncCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HcUiState(
    val availability: HcAvailability = HcAvailability.NOT_SUPPORTED,
    val enabled: Boolean = false,
    val granted: Set<String> = emptySet(),
    val core: Set<String> = emptySet(),
    val backgroundSupported: Boolean = false,
    val historySupported: Boolean = false,
    val sync: SourceSyncState = SourceSyncState(),
)

data class SamsungUiState(
    val sdkBundled: Boolean = false,
    val installed: Boolean = false,
    val version: String? = null,
    val status: SamsungStatus? = null,
    val enabled: Boolean = false,
    val categories: Set<SamsungDataCategory> = emptySet(),
    val sync: SourceSyncState = SourceSyncState(),
    val lastError: SamsungStatus? = null,
)

@HiltViewModel
class IntegrationsViewModel @Inject constructor(
    val hc: HealthConnectManager,
    private val samsung: SamsungHealthGateway,
    private val settings: SettingsRepository,
    private val coordinator: HealthSyncCoordinator,
    private val weights: WeightRepository,
    private val health: HealthRepository,
    private val messages: UserMessages,
) : ViewModel() {

    private val _hc = MutableStateFlow(HcUiState())
    private val _samsung = MutableStateFlow(SamsungUiState())
    val running = coordinator.running

    val hcState: StateFlow<HcUiState> = combine(_hc, settings.settings, settings.syncState(DataSource.HEALTH_CONNECT)) { h, s, sync ->
        h.copy(enabled = s.healthConnectEnabled, sync = sync)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HcUiState())

    val samsungState: StateFlow<SamsungUiState> = combine(_samsung, settings.settings, settings.syncState(DataSource.SAMSUNG_HEALTH)) { sh, s, sync ->
        sh.copy(enabled = s.samsungHealthEnabled, sync = sync)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SamsungUiState())

    val settingsState = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _lastReport = MutableStateFlow<Map<DataSource, SyncReport>>(emptyMap())
    val lastReport: StateFlow<Map<DataSource, SyncReport>> = _lastReport.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val availability = hc.availability()
            val granted = if (availability == HcAvailability.AVAILABLE) runCatching { hc.grantedPermissions() }.getOrDefault(emptySet()) else emptySet()
            _hc.value = HcUiState(
                availability = availability,
                granted = granted,
                core = hc.corePermissions,
                backgroundSupported = availability == HcAvailability.AVAILABLE &&
                    hc.isFeatureAvailable(androidx.health.connect.client.HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND),
                historySupported = availability == HcAvailability.AVAILABLE &&
                    hc.isFeatureAvailable(androidx.health.connect.client.HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY),
            )
            val installed = samsung.isSamsungHealthInstalled()
            val status = if (samsung.sdkBundled && installed) runCatching { samsung.status() }.getOrDefault(SamsungStatus.ERROR) else null
            val categories = if (status == SamsungStatus.READY) runCatching { samsung.grantedCategories() }.getOrDefault(emptySet()) else emptySet()
            _samsung.update {
                it.copy(sdkBundled = samsung.sdkBundled, installed = installed, version = samsung.samsungHealthVersion(), status = status, categories = categories)
            }
        }
    }

    /** Result of the Health Connect permission dialog. */
    fun onHcPermissionsResult(granted: Set<String>) {
        viewModelScope.launch {
            if (granted.any { it in hc.corePermissions }) {
                settings.setHealthConnectEnabled(true)
                val s = settings.current()
                coordinator.schedulePeriodic(s.autoSync, s.syncIntervalHours)
                refresh()
                _lastReport.update { it + coordinator.syncAll(manual = true) }
            } else refresh()
        }
    }

    fun disconnectHc(deleteImported: Boolean) {
        viewModelScope.launch {
            settings.setHealthConnectEnabled(false)
            settings.resetSyncState(DataSource.HEALTH_CONNECT)
            runCatching { hc.revokeAll() }
            if (deleteImported) {
                weights.deleteAllFromSource(DataSource.HEALTH_CONNECT)
                health.deleteAllFromSource(DataSource.HEALTH_CONNECT)
            }
            reschedule()
            refresh()
        }
    }

    fun connectSamsung(activity: Activity) {
        viewModelScope.launch {
            try {
                val categories = samsung.requestPermissions(activity)
                if (categories.isNotEmpty()) {
                    settings.setSamsungHealthEnabled(true)
                    reschedule()
                    refresh()
                    _lastReport.update { it + coordinator.syncAll(manual = true) }
                } else refresh()
            } catch (e: SamsungHealthException) {
                _samsung.update { it.copy(lastError = e.status) }
            } catch (e: Exception) {
                _samsung.update { it.copy(lastError = SamsungStatus.ERROR) }
            }
        }
    }

    fun resolveSamsung(activity: Activity) {
        samsung.resolve(activity)
    }

    fun disconnectSamsung(deleteImported: Boolean) {
        viewModelScope.launch {
            settings.setSamsungHealthEnabled(false)
            settings.resetSyncState(DataSource.SAMSUNG_HEALTH)
            if (deleteImported) {
                weights.deleteAllFromSource(DataSource.SAMSUNG_HEALTH)
                health.deleteAllFromSource(DataSource.SAMSUNG_HEALTH)
            }
            reschedule()
            refresh()
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            val reports = coordinator.syncAll(manual = true)
            _lastReport.value = reports
            if (reports.values.all { it.error == null }) messages.show(R.string.sync_done)
            refresh()
        }
    }

    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            settings.setAutoSync(enabled)
            reschedule()
        }
    }

    fun setInterval(hours: Int) {
        viewModelScope.launch {
            settings.setSyncInterval(hours)
            reschedule()
        }
    }

    fun movePriorityUp(source: DataSource) {
        viewModelScope.launch {
            val list = settings.current().sourcePriority.toMutableList()
            val i = list.indexOf(source)
            if (i > 0) {
                list.removeAt(i)
                list.add(i - 1, source)
                settings.setSourcePriority(list)
            }
        }
    }

    private suspend fun reschedule() {
        val s = settings.current()
        coordinator.schedulePeriodic(s.autoSync && (s.healthConnectEnabled || s.samsungHealthEnabled), s.syncIntervalHours)
    }
}
