package com.nutrix.app.data.repository

import androidx.annotation.StringRes
import com.nutrix.app.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deletions are offered here and shown as an app-wide "Undo" snackbar, so the snackbar survives
 * closing the editor that triggered it. [commit] runs when undo is no longer possible.
 */
@Singleton
class UndoManager @Inject constructor() {
    data class Pending(
        @StringRes val message: Int,
        val undo: suspend () -> Unit,
        val commit: suspend () -> Unit = {},
    )

    private val _pending = MutableSharedFlow<Pending>(extraBufferCapacity = 8)
    val pending: SharedFlow<Pending> = _pending.asSharedFlow()

    fun offer(@StringRes message: Int = R.string.deleted_entry, undo: suspend () -> Unit, commit: suspend () -> Unit = {}) {
        _pending.tryEmit(Pending(message, undo, commit))
    }
}
