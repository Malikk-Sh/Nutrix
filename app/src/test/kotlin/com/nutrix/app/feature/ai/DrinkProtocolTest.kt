package com.nutrix.app.feature.ai

import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.NutrientBasis
import com.nutrix.app.feature.water.EnergyInput
import com.nutrix.app.feature.water.EnergyMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class DrinkProtocolTest {
    private val prompt = File("src/main/assets/drink-analysis-prompt.txt").readText()
    private val protocol = DrinkProtocol(Json.parseToJsonElement(File("src/main/assets/drink-analysis.schema.json").readText()).jsonObject, prompt)
    private lateinit var server: MockWebServer
    private lateinit var client: GeminiClient
    private val latte = """{"isDrink":true,"drinkName":"Латте","drinkType":"COFFEE","volumeMl":300,"caloriesKcal":150,"proteinGrams":8,"fatGrams":7.5,"carbsGrams":12,"additions":["молоко 2,5 %"],"uncertainty":"Жирность молока неизвестна"}"""

    private fun envelope(text: String = latte) = buildJsonObject {
        putJsonArray("candidates") { addJsonObject {
            put("finishReason", "STOP")
            putJsonObject("content") { putJsonArray("parts") { addJsonObject { put("text", text) } } }
        } }
    }.toString()

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val food = GeminiProtocol(Json.parseToJsonElement(File("src/main/assets/food-analysis.schema.json").readText()).jsonObject, "food")
        client = GeminiClient(food, OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).followRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(10, TimeUnit.SECONDS).build(), server.url("/"))
    }
    @After fun cleanup() { server.shutdown() }

    @Test fun `a drink photo is sent with the drink prompt and schema and answered for the whole drink`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(envelope()))
        val body = client.generate("test-key-not-a-real-secret", GeminiProtocol.DEFAULT_MODEL, protocol.request(AiPhoto(byteArrayOf(1, 2)), "кружка 300 мл"))
        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertThat(sent.getValue("systemInstruction").toString()).contains("дневник напитков")
        val text = sent.getValue("contents").jsonArray.single().jsonObject.getValue("parts").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content
        assertThat(text).contains("Проанализируй фото напитка.")
        assertThat(text).contains("кружка 300 мл")
        assertThat(sent.getValue("generationConfig").jsonObject.getValue("responseJsonSchema")).isEqualTo(protocol.schema)
        val e = protocol.response(body)
        assertThat(e.type).isEqualTo(DrinkType.COFFEE)
        assertThat(e.volumeMl).isEqualTo(300.0)
        assertThat(e.additions).containsExactly("молоко 2,5 %")
    }

    @Test fun `no drink, impossible amounts and unknown types are rejected, unknown values stay unknown`() {
        fun reason(text: String) = (runCatching { protocol.response(envelope(text)) }.exceptionOrNull() as FoodAiException).reason
        assertThat(reason(latte.replace("\"isDrink\":true", "\"isDrink\":false"))).isEqualTo(FoodAiError.NO_DRINK)
        assertThat(reason(latte.replace("\"volumeMl\":300", "\"volumeMl\":0"))).isEqualTo(FoodAiError.INVALID_RESPONSE)
        assertThat(reason(latte.replace("\"carbsGrams\":12", "\"carbsGrams\":400"))).isEqualTo(FoodAiError.INVALID_RESPONSE)
        assertThat(reason(latte.replace("\"COFFEE\"", "\"BEER\""))).isEqualTo(FoodAiError.INVALID_RESPONSE)
        assertThat(reason(latte.replace("\"caloriesKcal\":150", "\"caloriesKcal\":-5"))).isEqualTo(FoodAiError.INVALID_RESPONSE)
        val unknown = protocol.response(envelope(latte.replace("\"caloriesKcal\":150", "\"caloriesKcal\":null")))
        assertThat(unknown.caloriesKcal).isNull()
        assertThat(EnergyInput.fromEstimate(unknown, "m").mode).isEqualTo(EnergyMode.UNKNOWN)
    }

    @Test fun `a whole-drink estimate is stored per 100 ml when the volume is known, so a corrected volume scales it`() {
        val e = protocol.response(envelope())
        val input = EnergyInput.fromEstimate(e, "gemini-test")
        assertThat(input.basis).isEqualTo(NutrientBasis.PER_100_ML)
        assertThat(input.kcalText).isEqualTo("50")
        assertThat(input.fatText).isEqualTo("2,5")
        val n = input.toNutrition()!!
        assertThat(n.estimateModel).isEqualTo("gemini-test")
        assertThat(n.uncertainty).isEqualTo("Жирность молока неизвестна")
        assertThat(input.totals(300)!![0]).isWithin(1e-9).of(150.0)
        assertThat(input.totals(400)!![0]).isWithin(1e-9).of(200.0)
        // Without a volume the values stay for the whole drink; a plain coffee is "without calories".
        val noVolume = EnergyInput.fromEstimate(e.copy(volumeMl = null), "m")
        assertThat(noVolume.basis).isEqualTo(NutrientBasis.PER_DRINK)
        assertThat(noVolume.kcalText).isEqualTo("150")
        assertThat(EnergyInput.fromEstimate(e.copy(caloriesKcal = 2.0 / 10), "m").mode).isEqualTo(EnergyMode.NONE)
        // A manual change drops the "estimated by Gemini" mark.
        assertThat(input.copy(kcalText = "55").edited().toNutrition()!!.estimateModel).isNull()
    }
}
