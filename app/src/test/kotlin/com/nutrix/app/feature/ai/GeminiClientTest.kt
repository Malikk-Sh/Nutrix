package com.nutrix.app.feature.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

class GeminiClientTest {
    private val protocol = GeminiProtocol(
        Json.parseToJsonElement(File("src/main/assets/food-analysis.schema.json").readText()).jsonObject,
        File("src/main/assets/food-analysis-prompt.txt").readText(),
    )
    private lateinit var server: MockWebServer
    private lateinit var client: GeminiClient
    private val key = "test-key-not-a-real-secret"
    private val estimate = """{"isFood":true,"dishName":"Гречка с курицей","ingredients":[{"name":"Гречка","grams":180},{"name":"Курица","grams":120}],"portionGrams":300,"caloriesKcal":450,"proteinGrams":35,"fatGrams":10,"carbsGrams":50,"uncertainty":"Количество масла неизвестно"}"""

    private fun envelope(text: String = estimate, finish: String = "STOP") = buildJsonObject {
        putJsonArray("candidates") { addJsonObject {
            put("finishReason", finish)
            putJsonObject("content") { putJsonArray("parts") { addJsonObject { put("text", text) } } }
        } }
    }.toString()

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        client = GeminiClient(protocol, OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).followRedirects(false).retryOnConnectionFailure(false).callTimeout(10, TimeUnit.SECONDS).build(), server.url("/"))
    }
    @After fun cleanup() { server.shutdown() }

    @Test fun `one photo request escapes hint and sends key only in header`() = runBlocking {
        server.enqueue(MockResponse().setBody(envelope()))
        val hint = "Тарелка 25 см, соус \"без сахара\"\nи две ложки масла"
        val photo = byteArrayOf(1, 2, 3)
        val result = client.analyze(key, GeminiProtocol.DEFAULT_MODEL, AiPhoto(photo), hint)
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/v1beta/models/gemini-3.1-flash-lite:generateContent")
        assertThat(request.getHeader("x-goog-api-key")).isEqualTo(key)
        assertThat(request.path).doesNotContain(key)
        val raw = request.body.readUtf8()
        assertThat(raw).doesNotContain(key)
        val body = Json.parseToJsonElement(raw).jsonObject
        val parts = body.getValue("contents").jsonArray.single().jsonObject.getValue("parts").jsonArray
        assertThat(parts[0].jsonObject.getValue("text").jsonPrimitive.content).contains(hint)
        val inline = parts[1].jsonObject.getValue("inlineData").jsonObject
        assertThat(inline.getValue("mimeType").jsonPrimitive.content).isEqualTo("image/jpeg")
        assertThat(Base64.getDecoder().decode(inline.getValue("data").jsonPrimitive.content)).isEqualTo(photo)
        assertThat(body.getValue("generationConfig").jsonObject.getValue("responseJsonSchema")).isEqualTo(protocol.schema)
        assertThat(result.portionGrams).isEqualTo(300.0)
        assertThat(result.proteinGrams).isEqualTo(35.0)
    }

    @Test fun `HTTP errors are sanitized and not retried and invalid keys support Google HTTP 400`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("PRIVATE $key"))
        val error = runCatching { client.analyze(key, "gemini-3.1-flash-lite", AiPhoto(byteArrayOf(1)), "") }.exceptionOrNull() as FoodAiException
        assertThat(error.reason).isEqualTo(FoodAiError.QUOTA)
        assertThat(error.message).doesNotContain(key)
        assertThat(server.requestCount).isEqualTo(1)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"PRIVATE $key","details":[{"reason":"API_KEY_INVALID"}]}}"""))
        val invalidKey = runCatching { client.models(key) }.exceptionOrNull() as FoodAiException
        assertThat(invalidKey.reason).isEqualTo(FoodAiError.KEY_INVALID)
        assertThat(invalidKey.message).doesNotContain(key)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"PRIVATE $key","status":"INVALID_ARGUMENT"}}"""))
        val badRequest = runCatching { client.analyze(key, "gemini-3.1-flash-lite", AiPhoto(byteArrayOf(1)), "") }.exceptionOrNull() as FoodAiException
        assertThat(badRequest.reason).isEqualTo(FoodAiError.BAD_REQUEST)
        assertThat(badRequest.message).doesNotContain(key)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `redirect does not forward key or repeat upload`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
        assertThat(runCatching { client.analyze(key, "gemini-3.1-flash-lite", AiPhoto(byteArrayOf(1)), "") }.exceptionOrNull()).isInstanceOf(FoodAiException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `truncated blocked non food and invalid numeric responses are rejected`() {
        fun reason(body: String) = (runCatching { protocol.response(body) }.exceptionOrNull() as FoodAiException).reason
        assertThat(reason(envelope(finish = "MAX_TOKENS"))).isEqualTo(FoodAiError.TRUNCATED)
        assertThat(reason("""{"promptFeedback":{"blockReason":"SAFETY"}}""")).isEqualTo(FoodAiError.BLOCKED)
        assertThat(reason(envelope(estimate.replace("\"isFood\":true", "\"isFood\":false")))).isEqualTo(FoodAiError.NO_FOOD)
        assertThat(reason(envelope(estimate.replace("\"portionGrams\":300", "\"portionGrams\":-1")))).isEqualTo(FoodAiError.INVALID_RESPONSE)
        assertThat(reason(envelope(estimate.replace("\"proteinGrams\":35", "\"proteinGrams\":5000")))).isEqualTo(FoodAiError.INVALID_RESPONSE)
        assertThat(reason(envelope("not json"))).isEqualTo(FoodAiError.INVALID_RESPONSE)
    }

    @Test fun `unknown weight stays unknown instead of zero`() {
        val result = protocol.response(envelope(estimate.replace("\"portionGrams\":300", "\"portionGrams\":null")))
        assertThat(result.portionGrams).isNull()
        assertThat(result.caloriesKcal).isEqualTo(450.0)
    }

    @Test fun `model list follows pages and excludes models without generateContent`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/gemini-3.1-flash-lite","supportedGenerationMethods":["generateContent"]},{"name":"models/embedding","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"a b"}"""))
        server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/gemini-3.6-flash","supportedGenerationMethods":["generateContent"]}]}"""))
        assertThat(client.models(key)).containsExactly("gemini-3.1-flash-lite", "gemini-3.6-flash")
        server.takeRequest()
        assertThat(server.takeRequest().requestUrl!!.queryParameter("pageToken")).isEqualTo("a b")
    }

    @Test fun `oversized response cannot fill memory`() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(300 * 1024)))
        val error = runCatching { client.analyze(key, "gemini-3.1-flash-lite", AiPhoto(byteArrayOf(1)), "") }.exceptionOrNull() as FoodAiException
        assertThat(error.reason).isEqualTo(FoodAiError.INVALID_RESPONSE)
    }

    @Test fun `cancellation interrupts a slow response and cannot deliver a late result`() = runBlocking {
        server.enqueue(MockResponse().setBody(envelope()).setBodyDelay(2, TimeUnit.SECONDS))
        val job = launch(start = CoroutineStart.UNDISPATCHED) { client.analyze(key, "gemini-3.1-flash-lite", AiPhoto(byteArrayOf(1)), "") }
        withContext(Dispatchers.IO) { assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull() }
        withTimeout(1500) { job.cancelAndJoin() }
        assertThat(job.isCancelled).isTrue()
        Unit
    }

    @Test fun `unsafe model and header key are rejected before any request`() = runBlocking {
        assertThat(runCatching { client.analyze(key, "../models/other?key=leak", AiPhoto(byteArrayOf(1)), "") }.exceptionOrNull()).isInstanceOf(FoodAiException::class.java)
        assertThat(runCatching { client.analyze("$key\nheader", "gemini-3.1-flash-lite", AiPhoto(byteArrayOf(1)), "") }.exceptionOrNull()).isInstanceOf(FoodAiException::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }
}
