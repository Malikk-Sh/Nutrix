package com.nutrix.app.feature.ai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
)

@HiltViewModel
class GeminiSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val cipher: SecretCipher,
    private val client: GeminiClient,
) : ViewModel() {
    private val operation = MutableStateFlow(GeminiOperation())
    val state = combine(settings.settings, settings.hasGeminiKey, operation) { s, key, op ->
        GeminiSettingsState(s.geminiModel, key, true, op.busy, op.error, op.models, op.keyRevision, op.verified)
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
        operation.update { it.copy(keyRevision = it.keyRevision + 1, models = emptyList(), verified = false) }
    }

    fun removeKey() = run {
        settings.setGeminiKeyBlob(null)
        operation.update { it.copy(models = emptyList(), verified = false) }
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
    val uri = LocalUriHandler.current
    LaunchedEffect(state.model) { model = state.model }
    LaunchedEffect(state.keyRevision) { key = "" }
    NutrixScreen(title = stringResource(R.string.ai_settings_title), onBack = onBack) { padding ->
        ScreenList(padding) {
            item {
                NutrixCard {
                    Text(stringResource(R.string.ai_privacy), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.ai_key_storage), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { uri.openUri("https://aistudio.google.com/apikey") }) { Text(stringResource(R.string.ai_get_key)) }
                }
            }
            item {
                NutrixCard {
                    Text(stringResource(if (state.hasKey) R.string.ai_key_saved else R.string.ai_key_missing))
                    OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.ai_key_label)) },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true,
                        enabled = state.loaded && !state.busy, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.saveKey(key) }, enabled = state.loaded && !state.busy && key.isNotBlank()) { Text(stringResource(R.string.ai_save_key)) }
                        if (state.hasKey) TextButton(onClick = viewModel::removeKey, enabled = !state.busy) { Text(stringResource(R.string.ai_delete_key)) }
                    }
                    OutlinedButton(onClick = viewModel::verify, enabled = state.hasKey && !state.busy && key.isBlank()) { Text(stringResource(R.string.ai_check_key)) }
                    if (state.verified) Text(stringResource(R.string.ai_key_verified, state.models.size), style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                NutrixCard {
                    OutlinedTextField(model, { model = it }, label = { Text(stringResource(R.string.ai_model)) }, singleLine = true,
                        enabled = state.loaded && !state.busy, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.ai_model_hint), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { viewModel.saveModel(model) }, enabled = state.loaded && !state.busy && GeminiProtocol.validModel(model.trim()) && model.trim() != state.model) { Text(stringResource(R.string.action_save)) }
                    if (state.models.isNotEmpty()) {
                        // Common image-capable families first; the complete API list is available via the CLI.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.models.filter { "flash" in it || "pro" in it }.filterNot { "tts" in it || "image" in it || "live" in it }.take(16).forEach { name ->
                                SuggestionChip(onClick = { model = name }, label = { Text(name) }, enabled = !state.busy)
                            }
                        }
                    }
                }
            }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item { Text(stringResource(error.messageRes), color = MaterialTheme.colorScheme.error) } }
        }
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
