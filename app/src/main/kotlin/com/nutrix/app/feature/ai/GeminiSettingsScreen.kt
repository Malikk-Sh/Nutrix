package com.nutrix.app.feature.ai

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import com.nutrix.app.core.ui.icons.AutoAwesome
import com.nutrix.app.core.ui.components.CardHeader
import com.nutrix.app.core.ui.components.InfoDisclosure
import com.nutrix.app.core.ui.components.InfoBanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nutrix.app.R
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.security.SecretCipher
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.ScreenList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private data class GeminiOperation(
    val busy: Boolean = false, val error: FoodAiError? = null,
    val models: List<String> = emptyList(), val keyRevision: Int = 0, val verified: Boolean = false,
)

data class GeminiSettingsState(
    val model: String = GeminiProtocol.DEFAULT_MODEL, val hasKey: Boolean = false,
    val loaded: Boolean = false, val busy: Boolean = false, val error: FoodAiError? = null,
    val models: List<String> = emptyList(), val keyRevision: Int = 0, val verified: Boolean = false,
    /** The user confirmed the key belongs to a billed Cloud project, which allows the cloud weekly review. */
    val paidProject: Boolean = false,
)

@HiltViewModel
class GeminiSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val cipher: SecretCipher,
    private val client: GeminiClient,
) : ViewModel() {
    private val operation = MutableStateFlow(GeminiOperation())
    val state = combine(settings.settings, settings.hasGeminiKey, operation) { s, key, op ->
        GeminiSettingsState(s.geminiModel, key, true, op.busy, op.error, op.models, op.keyRevision, op.verified, s.aiPaidProject)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GeminiSettingsState())

    private fun run(block: suspend () -> Unit) {
        if (operation.value.busy) return
        operation.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { operation.update { it.copy(error = (error as? FoodAiException)?.reason ?: FoodAiError.KEY_UNAVAILABLE) } }
            finally { operation.update { it.copy(busy = false) } }
        }
    }

    fun saveKey(text: String) = run {
        val key = text.trim()
        if (!GeminiProtocol.validKey(key)) throw FoodAiException(FoodAiError.KEY_INVALID)
        val bytes = key.toByteArray()
        try { settings.setGeminiKeyBlob(withContext(Dispatchers.IO) { cipher.encrypt(bytes) }) }
        finally { bytes.fill(0) }
        // The billing confirmation belongs to the previous key; a new one starts as free.
        settings.setAiPaidProject(false)
        operation.update { it.copy(keyRevision = it.keyRevision + 1, models = emptyList(), verified = false) }
    }

    fun removeKey() = run {
        settings.setGeminiKeyBlob(null)
        settings.setAiPaidProject(false)
        operation.update { it.copy(models = emptyList(), verified = false) }
    }

    fun setPaidProject(paid: Boolean) {
        viewModelScope.launch { settings.setAiPaidProject(paid) }
    }

    fun saveModel(model: String) = run {
        if (!GeminiProtocol.validModel(model.trim())) throw FoodAiException(FoodAiError.MODEL_UNAVAILABLE)
        settings.setGeminiModel(model.trim())
    }

    fun verify() = run {
        val models = client.models(settings.readGeminiKey(cipher))
        operation.update { it.copy(models = models, verified = true) }
    }
}

@Composable
fun GeminiSettingsScreen(onBack: () -> Unit, viewModel: GeminiSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") } // Never saved in an Android instance-state bundle.
    var model by remember { mutableStateOf(state.model) }
    var showModels by remember { mutableStateOf(false) }
    var paidDialog by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    LaunchedEffect(state.model) { model = state.model }
    LaunchedEffect(state.keyRevision) { key = "" }
    NutrixScreen(title = stringResource(R.string.alignment_gemini_title), onBack = onBack) { padding ->
        ScreenList(padding) {
            item {
                NutrixCard {
                    CardHeader(Icons.Rounded.AutoAwesome, stringResource(R.string.alignment_gemini_title), MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.alignment_ai_disclosure), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InfoDisclosure(stringResource(R.string.alignment_ai_privacy), stringResource(R.string.ai_privacy) + "\n\n" + stringResource(R.string.ai_key_storage), Icons.Rounded.Info)
                }
            }
            item {
                NutrixCard {
                    Text(stringResource(if (state.hasKey) R.string.ai_key_saved else R.string.ai_key_missing), style = MaterialTheme.typography.titleSmall,
                        color = if (state.hasKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.ai_key_label)) },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true,
                        enabled = state.loaded && !state.busy, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.saveKey(key) }, enabled = state.loaded && !state.busy && key.isNotBlank()) { Text(stringResource(R.string.ai_save_key)) }
                        if (state.hasKey) TextButton(onClick = viewModel::removeKey, enabled = !state.busy) { Text(stringResource(R.string.ai_delete_key)) }
                    }
                    OutlinedButton(onClick = viewModel::verify, enabled = state.hasKey && !state.busy && key.isBlank()) { Text(stringResource(R.string.ai_check_key)) }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.verified) InfoBanner(stringResource(R.string.ai_key_verified, state.models.size), Icons.Rounded.Info, tint = MaterialTheme.colorScheme.primary)
                    state.error?.let { InfoBanner(stringResource(it.messageRes), Icons.Rounded.Info, tint = MaterialTheme.colorScheme.error) }
                }
            }
            item {
                NutrixCard {
                    OutlinedTextField(model, { model = it }, label = { Text(stringResource(R.string.ai_model)) }, singleLine = true,
                        enabled = state.loaded && !state.busy, modifier = Modifier.fillMaxWidth())
                    InfoDisclosure(stringResource(R.string.alignment_ai_model_help), stringResource(R.string.ai_model_hint), Icons.Rounded.Info)
                    Button(onClick = { viewModel.saveModel(model) }, enabled = state.loaded && !state.busy && GeminiProtocol.validModel(model.trim()) && model.trim() != state.model) { Text(stringResource(R.string.action_save)) }
                    if (state.models.isNotEmpty()) {
                        Box {
                            TextButton(onClick = { showModels = true }) { Text(stringResource(R.string.alignment_available_models)) }
                            DropdownMenu(showModels, onDismissRequest = { showModels = false }) {
                                state.models.filter { "flash" in it || "pro" in it }.filterNot { "tts" in it || "image" in it || "live" in it }.take(16).forEach { name ->
                                    DropdownMenuItem(text = { Text(name) }, onClick = { model = name; showModels = false }, enabled = !state.busy)
                                }
                            }
                        }
                    }
                }
            }
            if (state.hasKey) {
                item {
                    NutrixCard(modifier = Modifier.testTag("gemini-paid")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.ai_paid_switch), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(if (state.paidProject) R.string.ai_paid_on else R.string.ai_paid_off),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(state.paidProject, { if (it) paidDialog = true else viewModel.setPaidProject(false) },
                                enabled = state.loaded, modifier = Modifier.testTag("gemini-paid-switch"))
                        }
                        InfoDisclosure(stringResource(R.string.ai_paid_how), stringResource(R.string.ai_paid_how_text), Icons.Rounded.Info)
                    }
                }
            }
            item { TextButton(onClick = { uri.openUri("https://aistudio.google.com/apikey") }) { Text(stringResource(R.string.ai_get_key)) } }
        }
    }
    if (paidDialog) {
        AlertDialog(
            onDismissRequest = { paidDialog = false },
            title = { Text(stringResource(R.string.ai_review_paid_title)) },
            text = { Text(stringResource(R.string.ai_review_paid_text), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { paidDialog = false; viewModel.setPaidProject(true) }) { Text(stringResource(R.string.ai_review_paid_confirm)) } },
            dismissButton = { TextButton(onClick = { paidDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

val FoodAiError.messageRes: Int get() = when (this) {
    FoodAiError.KEY_MISSING -> R.string.ai_error_key_missing
    FoodAiError.KEY_UNAVAILABLE -> R.string.ai_error_key_unavailable
    FoodAiError.KEY_INVALID -> R.string.ai_error_key_invalid
    FoodAiError.ACCESS_DENIED -> R.string.ai_error_access
    FoodAiError.MODEL_UNAVAILABLE -> R.string.ai_error_model
    FoodAiError.QUOTA -> R.string.ai_error_quota
    FoodAiError.TIMEOUT -> R.string.ai_error_timeout
    FoodAiError.NETWORK -> R.string.ai_error_network
    FoodAiError.SERVER -> R.string.ai_error_server
    FoodAiError.BAD_REQUEST -> R.string.ai_error_request
    FoodAiError.BLOCKED -> R.string.ai_error_blocked
    FoodAiError.TRUNCATED -> R.string.ai_error_truncated
    FoodAiError.NO_FOOD -> R.string.ai_error_no_food
    FoodAiError.PHOTO_UNAVAILABLE -> R.string.ai_error_photo
    FoodAiError.PHOTO_CHANGED -> R.string.ai_error_changed
    FoodAiError.INVALID_RESPONSE -> R.string.ai_error_response
}
