package com.nutrix.app.feature.insights

import com.google.common.truth.Truth.assertThat
import com.nutrix.app.data.repository.InsightReport
import com.nutrix.app.domain.insights.FluidSummary
import com.nutrix.app.domain.insights.MealRhythm
import com.nutrix.app.domain.insights.SymptomsAfterGaps
import com.nutrix.app.feature.ai.FoodAiError
import com.nutrix.app.feature.ai.FoodAiException
import com.nutrix.app.feature.ai.GeminiClient
import com.nutrix.app.feature.ai.GeminiProtocol
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class InsightReviewProtocolTest {
    private val protocol = InsightReviewProtocol(
        Json.parseToJsonElement(File("src/main/assets/insight-review.schema.json").readText()).jsonObject,
        File("src/main/assets/insight-review-prompt.txt").readText(),
    )
    private val report = InsightReport(
        from = 20_000, to = 20_027, hidden = setOf("symptoms_after_gaps"),
        all = listOf(
            MealRhythm(20, 9 * 60, 20 * 60 + 30, 690, 150, 6, 2.8, emptyList()),
            FluidSummary(25, 1300, 1800, 6, 0.3, emptyList()),
            SymptomsAfterGaps(5, 5, 4, 0.2),
        ),
    )
    private lateinit var server: MockWebServer
    private lateinit var client: GeminiClient

    private fun envelope(text: String, finish: String = "STOP") = buildJsonObject {
        putJsonArray("candidates") { addJsonObject {
            put("finishReason", finish)
            putJsonObject("content") { putJsonArray("parts") { addJsonObject { put("text", text) } } }
        } }
    }.toString()

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val food = GeminiProtocol(buildJsonObject { }, "")
        client = GeminiClient(food, OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).followRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(10, TimeUnit.SECONDS).build(), server.url("/"))
    }

    @After fun cleanup() { server.shutdown() }

    @Test fun `payload carries only visible observations and numeric facts`() {
        val payload = InsightReviewPayload.build(report)
        val observations = payload.getValue("observations").jsonArray.map { it.jsonObject }
        assertThat(observations.map { it.getValue("id").jsonPrimitive.content }).containsExactly("meal_rhythm", "fluid")
        assertThat(observations.first().getValue("facts").jsonObject.getValue("first_meal_median").jsonPrimitive.content).isEqualTo("09:00")
        assertThat(InsightReviewPayload.fingerprint(payload, "m")).isEqualTo(InsightReviewPayload.fingerprint(InsightReviewPayload.build(report), "m"))
        assertThat(InsightReviewPayload.fingerprint(payload, "m"))
            .isNotEqualTo(InsightReviewPayload.fingerprint(InsightReviewPayload.build(report.copy(hidden = emptySet())), "m"))
        // Another model or another contract makes the cached review outdated.
        assertThat(InsightReviewPayload.fingerprint(payload, "m")).isNotEqualTo(InsightReviewPayload.fingerprint(payload, "other"))
        assertThat(payload.getValue("contract_version").toString()).isEqualTo(InsightReviewPayload.CONTRACT_VERSION.toString())
    }

    @Test fun `one request with schema and key only in header`() = runBlocking {
        server.enqueue(MockResponse().setBody(envelope("""{"summary":"Режим ровный.","points":[],"suggestions":[]}""")))
        val payload = InsightReviewPayload.build(report)
        val body = client.generate("test-key-not-a-real-secret", "gemini-3.1-flash-lite", protocol.request(payload))
        val review = protocol.response(body, InsightReviewPayload.ids(report))
        val request = server.takeRequest()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(request.path).isEqualTo("/v1beta/models/gemini-3.1-flash-lite:generateContent")
        assertThat(request.getHeader("x-goog-api-key")).isEqualTo("test-key-not-a-real-secret")
        val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertThat(sent.getValue("generationConfig").jsonObject.getValue("responseJsonSchema")).isNotNull()
        assertThat(sent.toString()).contains("meal_rhythm")
        assertThat(sent.toString()).doesNotContain("symptoms_after_gaps")
        assertThat(review.summary).isEqualTo("Режим ровный.")
    }

    @Test fun `items citing unknown observations or drifting into medicine are dropped`() {
        val text = """{"summary":"Первый приём пищи сильно плавает.",
            "points":[{"insightId":"meal_rhythm","text":"Начало дня меняется на 2,5 часа."},
                      {"insightId":"made_up","text":"Вы мало спите."},
                      {"insightId":"fluid","text":"Похоже на диабет, нужен препарат."},
                      {"insightId":"meal_rhythm","text":"Начало дня меняется на 2,5 часа."}],
            "suggestions":[{"insightId":"fluid","text":"Попробуйте выпивать стакан воды до обеда."},
                           {"insightId":"symptoms_after_gaps","text":"Ешьте чаще."}]}"""
        val review = protocol.response(envelope(text), InsightReviewPayload.ids(report))
        assertThat(review.points.map { it.text }).containsExactly("Начало дня меняется на 2,5 часа.")
        assertThat(review.suggestions.map { it.insightId }).containsExactly("fluid")
    }

    @Test fun `empty, truncated and blocked answers are typed errors`() {
        fun reason(body: String) = (runCatching { protocol.response(body, setOf("meal_rhythm")) }.exceptionOrNull() as FoodAiException).reason
        assertThat(reason(envelope("""{"summary":"","points":[],"suggestions":[]}"""))).isEqualTo(FoodAiError.INVALID_RESPONSE)
        assertThat(reason(envelope("{}", finish = "MAX_TOKENS"))).isEqualTo(FoodAiError.TRUNCATED)
        assertThat(reason(envelope("{}", finish = "SAFETY"))).isEqualTo(FoodAiError.BLOCKED)
        assertThat(reason(envelope("не JSON"))).isEqualTo(FoodAiError.INVALID_RESPONSE)
    }
}
