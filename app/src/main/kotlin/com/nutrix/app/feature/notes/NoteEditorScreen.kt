package com.nutrix.app.feature.notes

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.nutrix.app.R
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.core.ui.components.ConfirmDialog
import com.nutrix.app.core.ui.components.DateTimeRow
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixBottomAction
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.NutrixTextField
import com.nutrix.app.core.ui.components.ScreenList
import com.nutrix.app.data.repository.NoteRepository
import com.nutrix.app.data.repository.UndoManager
import com.nutrix.app.data.repository.UserMessages
import com.nutrix.app.navigation.NoteEditorRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

data class NoteForm(
    val id: Long = 0,
    val text: String = "",
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.now().withSecond(0).withNano(0),
    val zoneId: String = ZoneId.systemDefault().id,
)

@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val notes: NoteRepository,
    private val undo: UndoManager,
    private val messages: UserMessages,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<NoteEditorRoute>()
    private val _form = MutableStateFlow(NoteForm())
    val form: StateFlow<NoteForm> = _form.asStateFlow()
    val closed = MutableStateFlow(false)
    val isEdit = route.noteId != 0L

    init {
        if (isEdit) viewModelScope.launch {
            val n = notes.get(route.noteId) ?: run { closed.value = true; return@launch }
            val rt = n.time
            _form.value = NoteForm(n.id, n.text, rt.localDate, rt.localTime, n.zoneId)
        }
    }

    fun update(transform: (NoteForm) -> NoteForm) = _form.update(transform)

    fun save() {
        val f = _form.value
        if (f.text.isBlank()) return
        viewModelScope.launch {
            notes.save(f.id, RecordTime.of(f.date, f.time, TimeUtils.zoneOrDefault(f.zoneId)), f.text)
            messages.show(R.string.note_saved)
            closed.value = true
        }
    }

    fun delete() {
        viewModelScope.launch {
            val removed = notes.delete(_form.value.id) ?: return@launch
            undo.offer(undo = { notes.restore(removed) })
            closed.value = true
        }
    }
}

@Composable
fun NoteEditorScreen(onClose: () -> Unit, viewModel: NoteEditorViewModel = hiltViewModel()) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val closed by viewModel.closed.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(closed) { if (closed) onClose() }
    NutrixScreen(
        title = stringResource(if (viewModel.isEdit) R.string.note_edit_title else R.string.note_add_title),
        onBack = onClose,
        actions = { if (viewModel.isEdit) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete)) } },
        bottomBar = {
            NutrixBottomAction(stringResource(R.string.action_save), viewModel::save, enabled = form.text.isNotBlank())
        },
    ) { padding ->
        ScreenList(padding, bottomExtra = 24.dp) {
            item {
                NutrixCard {
                    DateTimeRow(form.date, form.time, { d -> viewModel.update { it.copy(date = d) } }, { t -> viewModel.update { it.copy(time = t) } })
                    Spacer(Modifier.height(12.dp))
                    NutrixTextField(form.text, { v -> viewModel.update { it.copy(text = v) } }, stringResource(R.string.label_note), placeholder = stringResource(R.string.note_text_hint), singleLine = false, minLines = 14)
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_title),
            text = stringResource(R.string.confirm_delete_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}
