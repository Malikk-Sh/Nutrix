package com.nutrix.app.core.model

object GeminiConfig {
    const val DEFAULT_MODEL = "gemini-3.1-flash-lite"
    const val MAX_PHOTO_BYTES = 2 * 1024 * 1024
    fun validModel(model: String): Boolean = model.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}"))
    fun validKey(key: String): Boolean = key.length in 8..512 && key.all { it.code in 33..126 }
}
