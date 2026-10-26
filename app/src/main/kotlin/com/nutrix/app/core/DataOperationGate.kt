package com.nutrix.app.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Serialises maintenance with health imports, including their persisted change tokens. */
@Singleton
class DataOperationGate @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
}
