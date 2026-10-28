package com.nutrix.app.integration

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import com.nutrix.app.core.ui.icons.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import com.nutrix.app.core.ui.icons.RadioButtonUnchecked
import com.nutrix.app.core.ui.icons.Shield
import com.nutrix.app.core.ui.icons.Sync
import com.nutrix.app.core.ui.icons.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nutrix.app.R
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.components.CardHeader
import com.nutrix.app.core.ui.components.InfoBanner
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.ScreenList
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.integration.healthconnect.HcAvailability
import com.nutrix.app.integration.samsung.SamsungDataCategory
import com.nutrix.app.integration.samsung.SamsungStatus
import java.time.ZoneId

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun IntegrationsScreen(onBack: () -> Unit, viewModel: IntegrationsViewModel = hiltViewModel()) {
    val hc by viewModel.hcState.collectAsStateWithLifecycle()
    val sh by viewModel.samsungState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val reports by viewModel.lastReport.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val colors = LocalCategoryColors.current
    var disconnect by rememberSaveable { mutableStateOf<DataSource?>(null) }

    // Permissions may change in system settings while we are away.
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refresh() } }

    val hcLauncher = rememberLauncherForActivityResult(viewModel.hc.permissionContract()) { granted -> viewModel.onHcPermissionsResult(granted) }

    NutrixScreen(title = stringResource(R.string.integrations_title), onBack = onBack) { padding ->
        ScreenList(padding) {
            item { InfoBanner(stringResource(R.string.integrations_intro), Icons.Rounded.Shield, tint = colors.weight) }

            // ---------- Health Connect ----------
            item(key = "hc") {
                NutrixCard {
                    CardHeader(Icons.Rounded.Favorite, stringResource(R.string.hc_title), colors.heart)
                    Spacer(Modifier.height(10.dp))
                    val statusText = when (hc.availability) {
                        HcAvailability.AVAILABLE -> stringResource(R.string.hc_status_available)
                        HcAvailability.UPDATE_REQUIRED -> stringResource(R.string.hc_status_update)
                        HcAvailability.NOT_SUPPORTED -> stringResource(R.string.hc_status_unsupported)
                    }
                    Text(statusText, style = MaterialTheme.typography.bodyMedium)
                    when (hc.availability) {
                        HcAvailability.UPDATE_REQUIRED -> TextButton(onClick = {
                            runCatching { context.startActivity(viewModel.hc.installIntent()) }
                        }) { Text(stringResource(R.string.hc_install)) }
                        HcAvailability.AVAILABLE -> {
                            Spacer(Modifier.height(10.dp))
                            Text(stringResource(R.string.hc_permissions), style = MaterialTheme.typography.titleSmall)
                            val labels = listOf(
                                R.string.hc_type_steps, R.string.hc_type_sleep, R.string.hc_type_heart, R.string.hc_type_weight,
                                R.string.hc_type_exercise, R.string.hc_type_active_kcal, R.string.hc_type_total_kcal,
                            )
                            viewModel.hc.readTypes.forEachIndexed { i, type ->
                                val permission = androidx.health.connect.client.permission.HealthPermission.getReadPermission(type)
                                PermissionRow(stringResource(labels[i]), permission in hc.granted)
                            }
                            if (hc.backgroundSupported) PermissionRow(stringResource(R.string.hc_perm_background), viewModel.hc.backgroundPermission in hc.granted)
                            if (hc.historySupported) PermissionRow(stringResource(R.string.hc_perm_history), viewModel.hc.historyPermission in hc.granted)
                            val coreGranted = hc.core.count { it in hc.granted }
                            if (coreGranted == 0) {
                                Spacer(Modifier.height(6.dp))
                                Text(stringResource(R.string.hc_no_permissions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (coreGranted < hc.core.size) {
                                Spacer(Modifier.height(6.dp))
                                Text(stringResource(R.string.hc_partial_permissions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (hc.enabled && viewModel.hc.backgroundPermission !in hc.granted) {
                                Spacer(Modifier.height(6.dp))
                                Text(stringResource(R.string.hc_background_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            SyncStatusLine(hc.sync.lastSyncMillis, hc.sync.lastError, reports[DataSource.HEALTH_CONNECT])
                            Spacer(Modifier.height(8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(onClick = {
                                    hcLauncher.launch(viewModel.hc.requestablePermissions())
                                }) { Text(stringResource(if (hc.enabled) R.string.hc_grant_more else R.string.hc_connect)) }
                                OutlinedButton(onClick = {
                                    runCatching { context.startActivity(viewModel.hc.settingsIntent()) }
                                }) { Text(stringResource(R.string.hc_open_settings)) }
                                if (hc.enabled) TextButton(onClick = { disconnect = DataSource.HEALTH_CONNECT }) { Text(stringResource(R.string.hc_disconnect)) }
                            }
                        }
                        HcAvailability.NOT_SUPPORTED -> Unit
                    }
                }
            }

            // ---------- Samsung Health ----------
            item(key = "samsung") {
                NutrixCard {
                    CardHeader(Icons.Rounded.Watch, stringResource(R.string.samsung_title), colors.water)
                    Text(stringResource(R.string.samsung_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    when {
                        !sh.installed -> Text(stringResource(R.string.samsung_not_installed), style = MaterialTheme.typography.bodyMedium)
                        !sh.sdkBundled -> {
                            Text(stringResource(R.string.samsung_installed, sh.version.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.samsung_sdk_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> {
                            Text(stringResource(R.string.samsung_installed, sh.version.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                            val status = sh.lastError ?: sh.status
                            Text(samsungStatusText(status), style = MaterialTheme.typography.bodySmall, color = if (status == SamsungStatus.READY) colors.meal else MaterialTheme.colorScheme.error)
                            if (sh.enabled || sh.categories.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(stringResource(R.string.samsung_categories), style = MaterialTheme.typography.titleSmall)
                                SamsungDataCategory.entries.forEach { c -> PermissionRow(stringResource(samsungCategoryLabel(c)), c in sh.categories) }
                                SyncStatusLine(sh.sync.lastSyncMillis, sh.sync.lastError, reports[DataSource.SAMSUNG_HEALTH])
                            }
                            Spacer(Modifier.height(8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    context.findActivity()?.let(viewModel::connectSamsung)
                                }) { Text(stringResource(R.string.samsung_connect)) }
                                if (status in setOf(SamsungStatus.OLD_VERSION, SamsungStatus.DISABLED, SamsungStatus.NOT_INITIALIZED)) {
                                    OutlinedButton(onClick = { context.findActivity()?.let(viewModel::resolveSamsung) }) {
                                        Text(stringResource(R.string.samsung_resolve))
                                    }
                                }
                                if (sh.enabled) TextButton(onClick = { disconnect = DataSource.SAMSUNG_HEALTH }) { Text(stringResource(R.string.hc_disconnect)) }
                            }
                            Spacer(Modifier.height(10.dp))
                            InfoBanner(stringResource(R.string.samsung_dev_mode_text), Icons.Rounded.Info)
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.samsung_network_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // ---------- Sync settings ----------
            settings?.let { s ->
                item(key = "sync") {
                    NutrixCard {
                        CardHeader(Icons.Rounded.Sync, stringResource(R.string.sync_section), colors.activity, trailing = {
                            if (running) CircularProgressIndicator(Modifier.size(22.dp))
                        })
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = viewModel::syncNow, enabled = !running && (s.healthConnectEnabled || s.samsungHealthEnabled)) {
                            Text(stringResource(R.string.sync_now))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.sync_auto), modifier = Modifier.weight(1f))
                            Switch(checked = s.autoSync, onCheckedChange = viewModel::setAutoSync)
                        }
                        if (s.autoSync) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(1, 3, 6, 12, 24).forEach { h ->
                                    FilterChip(selected = s.syncIntervalHours == h, onClick = { viewModel.setInterval(h) }, label = { Text(stringResource(R.string.sync_interval, h)) })
                                }
                            }
                        }
                    }
                }
                item(key = "priority") {
                    NutrixCard {
                        Text(stringResource(R.string.priority_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.priority_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        s.sourcePriority.forEachIndexed { i, source ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}. ${stringResource(source.labelRes)}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                if (i > 0) IconButton(onClick = { viewModel.movePriorityUp(source) }) { Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.priority_up)) }
                            }
                        }
                    }
                }
            }
        }
    }

    disconnect?.let { source ->
        AlertDialog(
            onDismissRequest = { disconnect = null },
            title = { Text(stringResource(R.string.hc_disconnect_title).replace("Health Connect", stringResource(source.labelRes))) },
            text = { Text(stringResource(R.string.hc_disconnect_text)) },
            confirmButton = {
                TextButton(onClick = {
                    if (source == DataSource.HEALTH_CONNECT) viewModel.disconnectHc(true) else viewModel.disconnectSamsung(true)
                    disconnect = null
                }) { Text(stringResource(R.string.hc_disconnect_delete)) }
            },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        if (source == DataSource.HEALTH_CONNECT) viewModel.disconnectHc(false) else viewModel.disconnectSamsung(false)
                        disconnect = null
                    }) { Text(stringResource(R.string.hc_disconnect_keep)) }
                    TextButton(onClick = { disconnect = null }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    val colors = LocalCategoryColors.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            null,
            tint = if (granted) colors.meal else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            stringResource(if (granted) R.string.hc_perm_granted else R.string.hc_perm_denied),
            style = MaterialTheme.typography.labelSmall,
            color = if (granted) colors.meal else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SyncStatusLine(lastMillis: Long?, error: String?, report: SyncReport?) {
    Spacer(Modifier.height(8.dp))
    Text(
        lastMillis?.let { stringResource(R.string.sync_last_ok, Formatters.dateTime(it, ZoneId.systemDefault().id)) } ?: stringResource(R.string.sync_never),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (report != null && report.error == null) {
        Text(
            stringResource(R.string.sync_result, report.upserted.toString(), report.deleted.toString(), report.days.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (error != null) {
        Text(stringResource(R.string.sync_error, syncErrorText(error)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun syncErrorText(name: String): String = stringResource(
    when (runCatching { SyncError.valueOf(name) }.getOrNull()) {
        SyncError.NOT_AVAILABLE -> R.string.sync_err_NOT_AVAILABLE
        SyncError.NO_PERMISSIONS -> R.string.sync_err_NO_PERMISSIONS
        SyncError.PERMISSION_REVOKED -> R.string.sync_err_PERMISSION_REVOKED
        SyncError.BACKGROUND_NOT_ALLOWED -> R.string.sync_err_BACKGROUND_NOT_ALLOWED
        SyncError.SDK_NOT_BUNDLED -> R.string.sync_err_SDK_NOT_BUNDLED
        SyncError.DISABLED -> R.string.sync_err_DISABLED
        else -> R.string.sync_err_PLATFORM_ERROR
    },
)

@Composable
private fun samsungStatusText(status: SamsungStatus?): String = stringResource(
    when (status) {
        SamsungStatus.READY -> R.string.samsung_status_ready
        SamsungStatus.OLD_VERSION -> R.string.samsung_status_old
        SamsungStatus.DISABLED -> R.string.samsung_status_disabled
        SamsungStatus.NOT_INITIALIZED -> R.string.samsung_status_not_initialized
        SamsungStatus.NOT_AUTHORIZED -> R.string.samsung_status_not_authorized
        SamsungStatus.NOT_INSTALLED -> R.string.samsung_not_installed
        SamsungStatus.SDK_NOT_BUNDLED -> R.string.samsung_sdk_missing
        SamsungStatus.ERROR, null -> R.string.samsung_status_error
    },
)

private fun samsungCategoryLabel(c: SamsungDataCategory): Int = when (c) {
    SamsungDataCategory.STEPS -> R.string.samsung_cat_steps
    SamsungDataCategory.SLEEP -> R.string.samsung_cat_sleep
    SamsungDataCategory.HEART_RATE -> R.string.samsung_cat_heart
    SamsungDataCategory.WEIGHT -> R.string.samsung_cat_weight
    SamsungDataCategory.EXERCISE -> R.string.samsung_cat_exercise
    SamsungDataCategory.ACTIVITY_SUMMARY -> R.string.samsung_cat_activity
}
