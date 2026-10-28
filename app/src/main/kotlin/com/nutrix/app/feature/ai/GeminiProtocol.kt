package com.nutrix.app.feature.ai

import com.nutrix.app.core.model.GeminiConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Base64

@Serializable
data class FoodIngredient(val name: String, val grams: Double?)

@Serializable
data class FoodEstimate(
    val isFood: Boolean,
    val dishName: String,
    val ingredients: List<FoodIngredient>,
    val portionGrams: Double?,
    val caloriesKcal: Double?,
    val proteinGrams: Double?,
    val fatGrams: Double?,
    val carbsGrams: Double?,
    val uncertainty: String?,
)

enum class FoodAiError {
    KEY_MISSING, KEY_UNAVAILABLE, KEY_INVALID, ACCESS_DENIED, MODEL_UNAVAILABLE,
    QUOTA, TIMEOUT, NETWORK, SERVER, BAD_REQUEST, INVALID_RESPONSE, BLOCKED, TRUNCATED,
    NO_FOOD, PHOTO_UNAVAILABLE, PHOTO_CHANGED,
}

/** Only transient failures may repeat an upload, at most once in the editor. */
val FoodAiError.isTransient: Boolean get() = this in setOf(FoodAiError.NETWORK, FoodAiError.TIMEOUT, FoodAiError.SERVER)

/** Deliberately contains no response body, key, photo bytes, or upstream exception text. */
class FoodAiException(val reason: FoodAiError) : Exception(reason.name)

data class AiPhoto(val bytes: ByteArray, val mimeType: String = "image/jpeg")

class GeminiProtocol(val schema: JsonObject, private val systemPrompt: String) {
    private val json = Json { ignoreUnknownKeys = true }

    fun request(photo: AiPhoto, hint: String): JsonObject {
        if (photo.bytes.isEmpty() || photo.bytes.size > MAX_PHOTO_BYTES || photo.mimeType !in IMAGE_TYPES) {
            throw FoodAiException(FoodAiError.PHOTO_UNAVAILABLE)
        }
        require(hint.length <= 1000)
        return buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } } }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", "Проанализируй фото еды. Уточнение пользователя: ${hint.ifBlank { "нет" }}") }
                        addJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", photo.mimeType)
                                put("data", Base64.getEncoder().encodeToString(photo.bytes))
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseJsonSchema", schema)
                put("maxOutputTokens", 4096)
                put("candidateCount", 1)
            }
        }
    }

    fun response(body: String): FoodEstimate = try {
        val root = json.parseToJsonElement(body).jsonObject
        val block = root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.content
        if (block != null && block != "BLOCK_REASON_UNSPECIFIED") throw FoodAiException(FoodAiError.BLOCKED)
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw FoodAiException(FoodAiError.INVALID_RESPONSE)
        when (candidate["finishReason"]?.jsonPrimitive?.content) {
            "STOP" -> Unit
            "MAX_TOKENS", "CONTINUATION" -> throw FoodAiException(FoodAiError.TRUNCATED)
            "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "RECITATION" -> throw FoodAiException(FoodAiError.BLOCKED)
            else -> throw FoodAiException(FoodAiError.INVALID_RESPONSE)
        }
        val text = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
            .map { it.jsonObject }.filter { it["thought"]?.jsonPrimitive?.booleanOrNull != true }
            .mapNotNull { it["text"]?.jsonPrimitive?.content }.joinToString("")
        if (text.length > 32_768) throw FoodAiException(FoodAiError.INVALID_RESPONSE)
        validate(json.decodeFromString<FoodEstimate>(text))
    } catch (error: FoodAiException) { throw error }
    catch (_: Exception) { throw FoodAiException(FoodAiError.INVALID_RESPONSE) }

    private fun validate(value: FoodEstimate): FoodEstimate {
        fun number(v: Double?, max: Double) = v == null || v.isFinite() && v in 0.0..max
        require(value.dishName.length <= 200 && value.ingredients.size <= 32)
        require(value.uncertainty == null || value.uncertainty.length <= 1000)
        require(number(value.portionGrams, 10_000.0) && value.portionGrams != 0.0)
        require(number(value.caloriesKcal, 100_000.0))
        val macros = listOf(value.proteinGrams, value.fatGrams, value.carbsGrams)
        require(macros.all { number(it, 10_000.0) })
        if (value.portionGrams != null) require(macros.filterNotNull().sum() <= value.portionGrams * 1.1)
        require(value.ingredients.all { it.name.isNotBlank() && it.name.length <= 120 && number(it.grams, 10_000.0) })
        if (!value.isFood) throw FoodAiException(FoodAiError.NO_FOOD)
        require(value.dishName.isNotBlank() && value.ingredients.isNotEmpty())
        return value.copy(dishName = value.dishName.trim(), ingredients = value.ingredients.map { it.copy(name = it.name.trim()) })
    }

    companion object {
        const val DEFAULT_MODEL = GeminiConfig.DEFAULT_MODEL
        const val MAX_PHOTO_BYTES = GeminiConfig.MAX_PHOTO_BYTES
        val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")
        fun validModel(model: String): Boolean = GeminiConfig.validModel(model)
        fun validKey(key: String): Boolean = GeminiConfig.validKey(key)
    }
}
