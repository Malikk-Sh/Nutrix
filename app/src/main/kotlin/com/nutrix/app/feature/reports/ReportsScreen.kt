package com.nutrix.app.feature.reports

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nutrix.app.R
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.ScreenList
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

enum class ExportFormat(val mime: String, val ext: String) {
    PDF("application/pdf", "pdf"),
    DOCTOR_PDF("application/pdf", "pdf"),
    CSV_EVENTS("text/csv", "csv"),
    CSV_ARCHIVE("application/zip", "zip"),
    JSON("application/json", "json"),
}

sealed interface ExportStatus {
    data object Idle : ExportStatus
    data object Working : ExportStatus
    data object Saved : ExportStatus
    data object Empty : ExportStatus
    data object Failed : ExportStatus
    data class Share(val file: File, val mime: String) : ExportStatus
}

@HiltViewModel
class ReportsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val loader: ReportDataLoader,
    private val pdf: PdfReportGenerator,
    private val exporters: DataExporters,
    private val settings: SettingsRepository,
) : ViewModel() {
    val from = MutableStateFlow(LocalDate.now().minusDays(29))
    val to = MutableStateFlow(LocalDate.now())
    val format = MutableStateFlow(ExportFormat.PDF)
    val status = MutableStateFlow<ExportStatus>(ExportStatus.Idle)
    val includePhotos = settings.settings.map { it.includePhotosInReports }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setIncludePhotos(v: Boolean) {
        viewModelScope.launch { settings.setIncludePhotosInReports(v) }
    }

    fun fileName(): String {
        val f = format.value
        val prefix = if (f == ExportFormat.DOCTOR_PDF) "nutrix-doctor-report" else "nutrix-export"
        return "$prefix-${from.value}-${to.value}.${f.ext}"
    }

    private suspend fun write(out: OutputStream): Boolean = withContext(Dispatchers.IO) {
        val data = loader.load(from.value, to.value)
        if (data.isEmpty) return@withContext false
        when (format.value) {
            ExportFormat.PDF -> pdf.write(data, doctorMode = false, includePhotos = includePhotos.value, out = out)
            ExportFormat.DOCTOR_PDF -> pdf.write(data, doctorMode = true, includePhotos = includePhotos.value, out = out)
            ExportFormat.CSV_EVENTS -> exporters.writeEventsCsv(data, out)
            ExportFormat.CSV_ARCHIVE -> exporters.writeCsvArchive(data, out)
            ExportFormat.JSON -> exporters.writeJson(data, out)
        }
        true
    }

    fun saveTo(uri: Uri) {
        status.value = ExportStatus.Working
        viewModelScope.launch {
            status.value = runCatching {
                context.contentResolver.openOutputStream(uri)!!.use { write(it) }
            }.fold({ ok ->
                if (ok) ExportStatus.Saved else {
                    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    ExportStatus.Empty
                }
            }, { ExportStatus.Failed })
        }
    }

    fun share() {
        status.value = ExportStatus.Working
        viewModelScope.launch {
            val dir = File(context.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val file = File(dir, fileName())
            status.value = runCatching { file.outputStream().use { write(it) } }
                .fold({ ok -> if (ok) ExportStatus.Share(file, format.value.mime) else ExportStatus.Empty }, { ExportStatus.Failed })
        }
    }

    fun reset() {
        status.value = ExportStatus.Idle
    }
}

@Composable
fun ReportsScreen(onBack: () -> Unit, viewModel: ReportsViewModel = hiltViewModel()) {
    val from by viewModel.from.collectAsStateWithLifecycle()
    val to by viewModel.to.collectAsStateWithLifecycle()
    val format by viewModel.format.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val includePhotos by viewModel.includePhotos.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var rangeDialog by rememberSaveable { mutableStateOf(false) }

    val launchers = ExportFormat.entries.associateWith { f ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(f.mime)) { uri -> if (uri != null) viewModel.saveTo(uri) }
    }

    NutrixScreen(title = stringResource(R.string.reports_title), onBack = onBack) { padding ->
        ScreenList(padding) {
            item {
                NutrixCard {
                    Text(stringResource(R.string.reports_period), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(7 to R.string.period_7, 14 to R.string.period_14, 30 to R.string.period_30, 90 to R.string.period_90).forEach { (d, label) ->
                            val selected = to == LocalDate.now() && from == LocalDate.now().minusDays(d - 1L)
                            FilterChip(selected, { viewModel.from.value = LocalDate.now().minusDays(d - 1L); viewModel.to.value = LocalDate.now() }, label = { Text(stringResource(label)) })
                        }
                        FilterChip(false, { rangeDialog = true }, label = { Text(stringResource(R.string.period_custom)) })
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.period_range, Formatters.fullDate(from), Formatters.fullDate(to)), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                NutrixCard {
                    Text(stringResource(R.string.reports_formats), style = MaterialTheme.typography.titleMedium)
                    listOf(
                        ExportFormat.PDF to (R.string.report_pdf to R.string.report_pdf_desc),
                        ExportFormat.DOCTOR_PDF to (R.string.report_doctor to R.string.report_doctor_desc),
                        ExportFormat.CSV_EVENTS to (R.string.report_csv_events to R.string.report_csv_events_desc),
                        ExportFormat.CSV_ARCHIVE to (R.string.report_csv_archive to R.string.report_csv_archive_desc),
                        ExportFormat.JSON to (R.string.report_json to R.string.report_json_desc),
                    ).forEach { (f, labels) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = format == f, role = Role.RadioButton, onClick = { viewModel.format.value = f }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = format == f, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text(stringResource(labels.first), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(labels.second), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (format == ExportFormat.PDF || format == ExportFormat.DOCTOR_PDF) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.report_include_photos), modifier = Modifier.weight(1f))
                            Switch(includePhotos, viewModel::setIncludePhotos)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { launchers.getValue(format).launch(viewModel.fileName()) }, enabled = status !is ExportStatus.Working) {
                            Text(stringResource(R.string.report_save))
                        }
                        OutlinedButton(onClick = viewModel::share, enabled = status !is ExportStatus.Working) { Text(stringResource(R.string.report_share)) }
                    }
                }
            }
        }
    }

    when (val st = status) {
        ExportStatus.Working -> AlertDialog(onDismissRequest = {}, confirmButton = {}, text = {
            Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Spacer(Modifier.width(16.dp)); Text(stringResource(R.string.report_generating)) }
        })
        ExportStatus.Saved, ExportStatus.Empty, ExportStatus.Failed -> AlertDialog(
            onDismissRequest = viewModel::reset,
            confirmButton = { TextButton(onClick = viewModel::reset) { Text(stringResource(R.string.action_ok)) } },
            text = {
                Text(stringResource(when (st) { ExportStatus.Saved -> R.string.report_saved; ExportStatus.Empty -> R.string.report_empty; else -> R.string.report_failed }))
            },
        )
        is ExportStatus.Share -> LaunchedEffect(st) {
            viewModel.reset()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", st.file)
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType(st.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    null,
                ),
            )
        }
        ExportStatus.Idle -> Unit
    }

    if (rangeDialog) {
        val pickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { rangeDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val s = pickerState.selectedStartDateMillis
                    val e = pickerState.selectedEndDateMillis ?: s
                    if (s != null && e != null) {
                        val a = Instant.ofEpochMilli(s).atZone(ZoneOffset.UTC).toLocalDate()
                        val b = Instant.ofEpochMilli(e).atZone(ZoneOffset.UTC).toLocalDate()
                        viewModel.from.value = minOf(a, b)
                        viewModel.to.value = maxOf(a, b)
                    }
                    rangeDialog = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { rangeDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DateRangePicker(state = pickerState, modifier = Modifier.height(480.dp)) }
    }
}
