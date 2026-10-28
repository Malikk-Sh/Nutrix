package com.nutrix.app.backup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nutrix.app.AppRestarter
import com.nutrix.app.R
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.components.InfoBanner
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.ScreenList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

sealed interface BackupUi {
    data object Idle : BackupUi
    data object Working : BackupUi
    data class Done(val photos: Int) : BackupUi
    data object Failed : BackupUi
    data class RestoreNeedsPassword(val uri: Uri, val wrong: Boolean) : BackupUi
    data class RestoreReady(val check: RestoreCheck.Ok) : BackupUi
    data class RestoreInvalid(val reason: RestoreCheck.Reason) : BackupUi
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backup: BackupManager,
    private val auto: AutoBackupScheduler,
    private val settings: SettingsRepository,
) : ViewModel() {
    val ui = MutableStateFlow<BackupUi>(BackupUi.Idle)
    val settingsState = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val hasAutoPassword = MutableStateFlow(false)

    init {
        viewModelScope.launch { hasAutoPassword.value = auto.hasPassword() }
    }

    fun create(uri: Uri, password: String, resolver: android.content.ContentResolver) {
        ui.value = BackupUi.Working
        viewModelScope.launch {
            ui.value = runCatching {
                resolver.openOutputStream(uri)!!.use { backup.createBackup(it, password.takeIf { p -> p.isNotEmpty() }?.toCharArray()) }
            }.fold({ BackupUi.Done(it.photoCount) }, { BackupUi.Failed })
        }
    }

    fun check(uri: Uri, password: String?) {
        ui.value = BackupUi.Working
        viewModelScope.launch {
            ui.value = when (val r = backup.check(uri, password?.toCharArray())) {
                is RestoreCheck.Ok -> BackupUi.RestoreReady(r)
                RestoreCheck.NeedsPassword -> BackupUi.RestoreNeedsPassword(uri, wrong = false)
                RestoreCheck.WrongPassword -> BackupUi.RestoreNeedsPassword(uri, wrong = true)
                is RestoreCheck.Invalid -> BackupUi.RestoreInvalid(r.reason)
            }
        }
    }

    fun restore(ok: RestoreCheck.Ok, context: android.content.Context) {
        ui.value = BackupUi.Working
        viewModelScope.launch {
            try {
                backup.restore(ok)
                AppRestarter.restart(context)
            } catch (e: Exception) {
                ui.value = BackupUi.Failed
            }
        }
    }

    fun dismiss() {
        backup.cleanup()
        ui.value = BackupUi.Idle
    }

    fun setAuto(enabled: Boolean, folder: String?, interval: Int, keep: Int) {
        viewModelScope.launch {
            settings.setAutoBackup(enabled, folder, interval, keep)
            auto.schedule(enabled && folder != null, interval)
        }
    }

    fun setAutoPassword(password: String?) {
        viewModelScope.launch {
            auto.setPassword(password)
            hasAutoPassword.value = auto.hasPassword()
        }
    }

    fun runAutoNow() {
        ui.value = BackupUi.Working
        viewModelScope.launch {
            ui.value = if (runCatching { auto.runNow() }.getOrDefault(false)) BackupUi.Done(0) else BackupUi.Failed
        }
    }
}

@Composable
fun BackupScreen(onBack: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val s by viewModel.settingsState.collectAsStateWithLifecycle()
    val hasAutoPassword by viewModel.hasAutoPassword.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var autoPasswordDialog by remember { mutableStateOf(false) }
    val mismatch = password.isNotEmpty() && password != repeat

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.create(uri, password, context.contentResolver)
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.check(uri, null)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val st = s
            viewModel.setAuto(true, uri.toString(), st?.autoBackupIntervalDays ?: 7, st?.autoBackupKeep ?: 5)
        }
    }

    NutrixScreen(title = stringResource(R.string.backup_title), onBack = onBack) { padding ->
        ScreenList(padding) {
            item { InfoBanner(stringResource(R.string.backup_intro), Icons.Rounded.Info) }
            item {
                NutrixCard {
                    Text(stringResource(R.string.backup_create), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.backup_create_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    PasswordField(password, { password = it }, stringResource(R.string.backup_password))
                    if (password.isNotEmpty()) {
                        PasswordField(repeat, { repeat = it }, stringResource(R.string.backup_password_repeat), isError = mismatch)
                        if (mismatch) Text(stringResource(R.string.backup_password_mismatch), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            createLauncher.launch("nutrix-backup-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))}.zip")
                        },
                        enabled = !mismatch && ui !is BackupUi.Working,
                    ) { Text(stringResource(R.string.backup_save)) }
                }
            }
            item {
                NutrixCard {
                    Text(stringResource(R.string.restore_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.restore_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        openLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }, enabled = ui !is BackupUi.Working) { Text(stringResource(R.string.restore_pick)) }
                }
            }
            s?.let { st ->
                item {
                    NutrixCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.auto_backup_title), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.auto_backup_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = st.autoBackupEnabled,
                                onCheckedChange = { on ->
                                    if (on && st.autoBackupFolderUri == null) { folderLauncher.launch(null) }
                                    else viewModel.setAuto(on, st.autoBackupFolderUri, st.autoBackupIntervalDays, st.autoBackupKeep)
                                },
                            )
                        }
                        if (st.autoBackupEnabled) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.auto_backup_folder, st.autoBackupFolderUri?.let { Uri.decode(Uri.parse(it).lastPathSegment ?: it) } ?: "—"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { folderLauncher.launch(null) }) { Text(stringResource(R.string.auto_backup_pick_folder)) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(1, 3, 7, 14, 30).forEach { d ->
                                    FilterChip(st.autoBackupIntervalDays == d, { viewModel.setAuto(true, st.autoBackupFolderUri, d, st.autoBackupKeep) }, label = { Text(stringResource(R.string.auto_backup_interval, d)) })
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(3, 5, 10, 20).forEach { k ->
                                    FilterChip(st.autoBackupKeep == k, { viewModel.setAuto(true, st.autoBackupFolderUri, st.autoBackupIntervalDays, k) }, label = { Text(stringResource(R.string.auto_backup_keep, k)) })
                                }
                            }
                            Text(
                                stringResource(if (hasAutoPassword) R.string.auto_backup_password_set else R.string.auto_backup_password_none),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row {
                                TextButton(onClick = { autoPasswordDialog = true }) { Text(stringResource(R.string.auto_backup_password_change)) }
                                if (hasAutoPassword) TextButton(onClick = { viewModel.setAutoPassword(null) }) { Text(stringResource(R.string.auto_backup_password_remove)) }
                            }
                            st.lastAutoBackupMillis?.let {
                                Text(stringResource(R.string.auto_backup_last, Formatters.dateTime(it, ZoneId.systemDefault().id)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(onClick = viewModel::runAutoNow, enabled = st.autoBackupFolderUri != null && ui !is BackupUi.Working) {
                                Text(stringResource(R.string.auto_backup_run_now))
                            }
                        }
                    }
                }
            }
        }
    }

    when (val u = ui) {
        BackupUi.Working -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Spacer(Modifier.height(0.dp)); Text("  " + stringResource(R.string.restore_checking)) } },
        )
        is BackupUi.Done -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.action_ok)) } },
            text = { Text(stringResource(R.string.backup_done, u.photos)) },
        )
        BackupUi.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.action_ok)) } },
            text = { Text(stringResource(R.string.backup_failed)) },
        )
        is BackupUi.RestoreNeedsPassword -> {
            var pw by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = viewModel::dismiss,
                title = { Text(stringResource(R.string.restore_title)) },
                text = {
                    Column {
                        Text(stringResource(if (u.wrong) R.string.restore_wrong_password else R.string.restore_need_password), color = if (u.wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        PasswordField(pw, { pw = it }, stringResource(R.string.backup_password))
                    }
                },
                confirmButton = { TextButton(onClick = { viewModel.check(u.uri, pw) }, enabled = pw.isNotEmpty()) { Text(stringResource(R.string.restore_password_unlock)) } },
                dismissButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
        is BackupUi.RestoreReady -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.restore_confirm_text,
                        Formatters.dateTime(u.check.manifest.createdAt, ZoneId.systemDefault().id),
                        u.check.manifest.appVersion,
                        u.check.manifest.photoCount,
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.restore(u.check, context.applicationContext) }) { Text(stringResource(R.string.restore_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
        is BackupUi.RestoreInvalid -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.action_ok)) } },
            text = {
                Text(
                    stringResource(
                        when (u.reason) {
                            RestoreCheck.Reason.NOT_A_BACKUP -> R.string.restore_invalid_NOT_A_BACKUP
                            RestoreCheck.Reason.CHECKSUM_MISMATCH -> R.string.restore_invalid_CHECKSUM_MISMATCH
                            RestoreCheck.Reason.DATABASE_CORRUPTED -> R.string.restore_invalid_DATABASE_CORRUPTED
                            RestoreCheck.Reason.NEWER_VERSION -> R.string.restore_invalid_NEWER_VERSION
                            RestoreCheck.Reason.IO_ERROR -> R.string.restore_invalid_IO_ERROR
                        },
                    ),
                )
            },
        )
        BackupUi.Idle -> Unit
    }

    if (autoPasswordDialog) {
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { autoPasswordDialog = false },
            title = { Text(stringResource(R.string.auto_backup_password)) },
            text = { PasswordField(pw, { pw = it }, stringResource(R.string.backup_password)) },
            confirmButton = { TextButton(onClick = { viewModel.setAutoPassword(pw); autoPasswordDialog = false }, enabled = pw.length >= 4) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { autoPasswordDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = MaterialTheme.shapes.medium,
    )
}
