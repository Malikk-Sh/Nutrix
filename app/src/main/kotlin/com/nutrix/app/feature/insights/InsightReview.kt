package com.nutrix.app.feature.insights

import android.content.Context
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.security.SecretCipher
import com.nutrix.app.data.repository.InsightReport
import com.nutrix.app.domain.insights.Insight
import com.nutrix.app.domain.insights.InsightComparison
import com.nutrix.app.domain.insights.MeasureKind
import com.nutrix.app.feature.ai.FoodAiError
import com.nutrix.app.feature.ai.FoodAiException
import com.nutrix.app.feature.ai.GeminiClient
import com.nutrix.app.feature.ai.candidateText
import com.nutrix.app.feature.ai.readGeminiKey
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Serializable
data class ReviewItem(val insightId: String, val text: String)

@Serializable
data class InsightReview(val summary: String, val points: List<ReviewItem>, val suggestions: List<ReviewItem>)

/** The last review with the fingerprint of the facts it was written for, so stale reviews are visible. */
@Serializable
data class CachedReview(val payloadHash: String, val createdAt: Long, val model: String, val review: InsightReview)

/**
 * What leaves the phone for the review: ids, topics, tones and numeric facts of the visible observations.
 * No diary text, notes, photos, names or dates of individual records.
 */
object InsightReviewPayload {
    /**
     * Version of what is sent and of the prompt that reads it. Part of the cache fingerprint, so a review
     * written for an older contract is shown as outdated.
     */
    const val CONTRACT_VERSION = 3

    fun build(report: InsightReport): JsonObject = buildJsonObject {
        put("contract_version", CONTRACT_VERSION)
        put("period_from", LocalDate.ofEpochDay(report.from).toString())
        put("period_to", LocalDate.ofEpochDay(report.to).toString())
        val earlier = InsightComparison.previousWindow(report.from..report.to)
        put("previous_period_from", LocalDate.ofEpochDay(earlier.first).toString())
        put("previous_period_to", LocalDate.ofEpochDay(earlier.last).toString())
        putJsonArray("observations") {
            report.visible.forEach { insight ->
                addJsonObject {
                    put("id", insight.id)
                    put("topic", insight.topic.name.lowercase())
                    put("tone", insight.tone.name.lowercase())
                    if (insight.basis != Insight.DEFAULT_BASIS) put("basis", insight.basis)
                    // Summaries exist whenever data does: they are compared only when the earlier weeks had them too.
                    val history = report.history(insight)
                    if (insight.isPattern || history.previous != null) put("compared_with_previous_4_weeks", history.recurrence.name.lowercase())
                    putJsonObject("facts") { putFacts(insight.facts()) }
                    if (insight.params().isNotEmpty()) putJsonObject("params") { insight.params().forEach { put(it.key, number(it.kind, it.value)) } }
                    history.previous?.let { before ->
                        putJsonObject("previous_facts") { putFacts(before.facts()) }
                        // Differences are computed here, so the model explains numbers instead of deriving them.
                        putJsonArray("changes") {
                            history.changes.forEach { c ->
                                addJsonObject {
                                    put("metric", c.key)
                                    put("unit", c.kind.name.lowercase())
                                    put("before", number(c.kind, c.before))
                                    put("after", number(c.kind, c.after))
                                    put("difference", number(if (c.kind == MeasureKind.CLOCK) MeasureKind.MINUTES else c.kind, c.delta))
                                    put("noticeable", c.noticeable)
                                }
                            }
                        }
                        if (history.paramChanges.isNotEmpty()) putJsonArray("param_changes") {
                            history.paramChanges.forEach { c ->
                                addJsonObject { put("param", c.key); put("before", number(c.kind, c.before)); put("after", number(c.kind, c.after)) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun JsonObjectBuilder.putFacts(facts: Map<String, Any>) = facts.forEach { (name, value) ->
        when (value) {
            is Number -> put(name, value)
            is Boolean -> put(name, value)
            is String -> put(name, value)
        }
    }

    /** Times of day as "HH:MM", shares with two decimals, everything else rounded as shown in the app. */
    private fun number(kind: MeasureKind, value: Double): JsonPrimitive = when (kind) {
        MeasureKind.CLOCK -> JsonPrimitive(clockText(value.roundToInt()))
        MeasureKind.SHARE, MeasureKind.KG_PER_WEEK -> JsonPrimitive(kotlin.math.round(value * 100) / 100)
        MeasureKind.COUNT -> JsonPrimitive(kotlin.math.round(value * 10) / 10)
        else -> JsonPrimitive(value.roundToInt())
    }

    private fun clockText(minutes: Int): String {
        val m = ((minutes % 1440) + 1440) % 1440
        return String.format(java.util.Locale.ROOT, "%02d:%02d", m / 60, m % 60)
    }

    fun ids(report: InsightReport): Set<String> = report.visible.map { it.id }.toSet()

    /** Facts, contract (inside the payload) and model together: a review stays current only while all three do. */
    fun fingerprint(payload: JsonObject, model: String): String =
        MessageDigest.getInstance("SHA-256").digest("$payload|$model".toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    fun pretty(payload: JsonObject): String = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), payload)
}

class InsightReviewProtocol(private val schema: JsonObject, private val systemPrompt: String) {
    private val json = Json { ignoreUnknownKeys = true }

    fun request(payload: JsonObject): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } } }
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") { addJsonObject { put("text", "Наблюдения из дневника:\n$payload") } }
            }
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseJsonSchema", schema)
            put("maxOutputTokens", 2048)
            put("candidateCount", 1)
            put("temperature", 0.4)
        }
    }

    /** Keeps only items that cite an observation that was sent and reads as diary feedback, not medical advice. */
    fun response(body: String, allowedIds: Set<String>): InsightReview {
        val raw = try { json.decodeFromString<InsightReview>(candidateText(body)) }
            catch (error: FoodAiException) { throw error }
            catch (_: Exception) { throw FoodAiException(FoodAiError.INVALID_RESPONSE) }
        fun clean(items: List<ReviewItem>, max: Int, limit: Int) = items
            .map { ReviewItem(it.insightId.trim(), it.text.trim()) }
            .filter { it.insightId in allowedIds && it.text.isNotEmpty() && it.text.length <= limit && !medical(it.text) }
            .distinctBy { it.text }
            .take(max)
        val summary = raw.summary.trim().takeIf { it.length <= 800 && !medical(it) }.orEmpty()
        val review = InsightReview(summary, clean(raw.points, 5, 400), clean(raw.suggestions, 3, 300))
        if (review.summary.isEmpty() && review.points.isEmpty()) throw FoodAiException(FoodAiError.INVALID_RESPONSE)
        return review
    }

    private fun medical(text: String): Boolean = text.lowercase().let { t -> MEDICAL_TERMS.any { it in t } }

    private companion object {
        // A second line of defence behind the prompt: drop sentences that drift into diagnoses or treatment.
        val MEDICAL_TERMS = listOf("диагноз", "болезн", "заболеван", "лекарств", "препарат", "таблет", "дозиров", "диабет",
            "гипогликем", "анеми", "синдром", "патолог", "лечени", "добавк")
    }
}

/** Writes the review text for the facts it is given; replaced in tests. */
fun interface InsightReviewer {
    suspend fun review(payload: JsonObject, allowedIds: Set<String>): Pair<InsightReview, String>
}

@Singleton
class GeminiInsightReviewer @Inject constructor(
    private val client: GeminiClient,
    private val settings: SettingsRepository,
    private val cipher: SecretCipher,
    private val protocol: InsightReviewProtocol,
) : InsightReviewer {
    override suspend fun review(payload: JsonObject, allowedIds: Set<String>): Pair<InsightReview, String> {
        val key = settings.readGeminiKey(cipher)
        val model = settings.current().geminiModel
        return protocol.response(client.generate(key, model, protocol.request(payload)), allowedIds) to model
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class InsightReviewBindings {
    @Binds abstract fun reviewer(implementation: GeminiInsightReviewer): InsightReviewer
}

@Module
@InstallIn(SingletonComponent::class)
object InsightReviewProviders {
    @Provides @Singleton
    fun protocol(@ApplicationContext context: Context) = InsightReviewProtocol(
        Json.parseToJsonElement(context.assets.open("insight-review.schema.json").bufferedReader().use { it.readText() }).jsonObject,
        context.assets.open("insight-review-prompt.txt").bufferedReader().use { it.readText() },
    )
}
