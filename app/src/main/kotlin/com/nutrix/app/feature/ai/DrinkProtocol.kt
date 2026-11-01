package com.nutrix.app.feature.ai

import com.nutrix.app.core.model.DrinkType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Gemini's estimate of one drink on a photo; amounts are for the whole drink shown, never per 100 ml. */
@Serializable
data class DrinkEstimate(
    val isDrink: Boolean,
    val drinkName: String,
    val drinkType: String,
    val volumeMl: Double?,
    val caloriesKcal: Double?,
    val proteinGrams: Double?,
    val fatGrams: Double?,
    val carbsGrams: Double?,
    val additions: List<String>,
    val uncertainty: String?,
) {
    val type: DrinkType get() = DrinkType.entries.firstOrNull { it.name == drinkType } ?: DrinkType.OTHER
}

/** The drink request: its own prompt and schema, so a drink photo is never read as a meal. */
class DrinkProtocol(val schema: JsonObject, private val systemPrompt: String) {
    private val json = Json { ignoreUnknownKeys = true }

    fun request(photo: AiPhoto, hint: String): JsonObject = photoRequest(systemPrompt, schema, "Проанализируй фото напитка.", photo, hint)

    fun response(body: String): DrinkEstimate = try {
        validate(json.decodeFromString<DrinkEstimate>(candidateText(body)))
    } catch (error: FoodAiException) { throw error }
    catch (_: Exception) { throw FoodAiException(FoodAiError.INVALID_RESPONSE) }

    private fun validate(value: DrinkEstimate): DrinkEstimate {
        fun number(v: Double?, max: Double) = v == null || v.isFinite() && v in 0.0..max
        require(value.drinkName.length <= 120 && value.additions.size <= 16)
        require(value.additions.all { it.isNotBlank() && it.length <= 60 })
        require(value.uncertainty == null || value.uncertainty.length <= 1000)
        require(value.volumeMl == null || value.volumeMl.isFinite() && value.volumeMl in 1.0..5000.0)
        require(number(value.caloriesKcal, 5_000.0))
        val macros = listOf(value.proteinGrams, value.fatGrams, value.carbsGrams)
        require(macros.all { number(it, 1_000.0) })
        // A drink cannot hold more dissolved nutrients than it weighs (syrups are a little heavier than water).
        if (value.volumeMl != null) require(macros.filterNotNull().sum() <= value.volumeMl * 1.2)
        if (!value.isDrink) throw FoodAiException(FoodAiError.NO_DRINK)
        require(value.drinkName.isNotBlank() && DrinkType.entries.any { it.name == value.drinkType })
        return value.copy(drinkName = value.drinkName.trim(), additions = value.additions.map(String::trim))
    }
}
