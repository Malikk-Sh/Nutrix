package com.nutrix.app.data.repository

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Short confirmations shown by the app-wide snackbar (survive navigation). */
@Singleton
class UserMessages @Inject constructor() {
    data class Message(@StringRes val res: Int, val args: List<Any> = emptyList())

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 8)
    val messages: SharedFlow<Message> = _messages.asSharedFlow()

    fun show(@StringRes res: Int, vararg args: Any) {
        _messages.tryEmit(Message(res, args.toList()))
    }
}
