package com.nutrix.app.feature.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class GeminiClient internal constructor(
    private val protocol: GeminiProtocol,
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl,
) {
    @Inject constructor(protocol: GeminiProtocol) : this(protocol, OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).callTimeout(100, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
        "https://generativelanguage.googleapis.com/".toHttpUrl())

    suspend fun analyze(key: String, model: String, photo: AiPhoto, hint: String): FoodEstimate {
        if (!GeminiProtocol.validModel(model)) throw FoodAiException(FoodAiError.MODEL_UNAVAILABLE)
        val url = baseUrl.newBuilder().addPathSegments("v1beta/models").addPathSegment("$model:generateContent").build()
        val body = protocol.request(photo, hint).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        return protocol.response(execute(key, Request.Builder().url(url).post(body)))
    }

    /** Listing models validates credentials without sending a photo or generating paid content. */
    suspend fun models(key: String): List<String> {
        val names = mutableSetOf<String>()
        var page: String? = null
        repeat(10) {
            val url = baseUrl.newBuilder().addPathSegments("v1beta/models").addQueryParameter("pageSize", "1000")
                .apply { if (page != null) addQueryParameter("pageToken", page) }.build()
            val result = try { Json.parseToJsonElement(execute(key, Request.Builder().url(url).get())).jsonObject }
                catch (error: FoodAiException) { throw error }
                catch (_: Exception) { throw FoodAiException(FoodAiError.INVALID_RESPONSE) }
            try {
                result["models"]?.jsonArray.orEmpty().forEach { element ->
                    val model = element.jsonObject
                    if (model["supportedGenerationMethods"]?.jsonArray.orEmpty().any { it.jsonPrimitive.content == "generateContent" }) {
                        val name = model["name"]?.jsonPrimitive?.content?.removePrefix("models/")
                        if (name != null && GeminiProtocol.validModel(name)) names += name
                    }
                }
                page = result["nextPageToken"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                if (page == null) return names.sorted()
            } catch (_: Exception) { throw FoodAiException(FoodAiError.INVALID_RESPONSE) }
        }
        throw FoodAiException(FoodAiError.INVALID_RESPONSE)
    }

    private suspend fun execute(key: String, builder: Request.Builder): String {
        if (key.isBlank()) throw FoodAiException(FoodAiError.KEY_MISSING)
        if (!GeminiProtocol.validKey(key)) throw FoodAiException(FoodAiError.KEY_INVALID)
        val call = http.newCall(builder.header("x-goog-api-key", key)
            .header("x-goog-api-client", "nutrix-android/1.0").build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(FoodAiException(
                        if (e is InterruptedIOException) FoodAiError.TIMEOUT else FoodAiError.NETWORK,
                    ))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!response.isSuccessful) throw FoodAiException(when (response.code) {
                                400 -> badRequestReason(response)
                                401 -> FoodAiError.KEY_INVALID
                                403 -> FoodAiError.ACCESS_DENIED
                                404 -> FoodAiError.MODEL_UNAVAILABLE
                                408, 504 -> FoodAiError.TIMEOUT
                                429 -> FoodAiError.QUOTA
                                in 500..599 -> FoodAiError.SERVER
                                else -> FoodAiError.NETWORK
                            })
                            val body = response.body ?: throw FoodAiException(FoodAiError.INVALID_RESPONSE)
                            val source = body.source()
                            source.request(MAX_RESPONSE_BYTES + 1)
                            if (source.buffer.size > MAX_RESPONSE_BYTES) throw FoodAiException(FoodAiError.INVALID_RESPONSE)
                            val text = source.readUtf8()
                            if (continuation.isActive) continuation.resume(text)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(
                                error as? FoodAiException ?: FoodAiException(
                                    if (error is InterruptedIOException) FoodAiError.TIMEOUT else FoodAiError.NETWORK,
                                ),
                            )
                        }
                    }
                }
            })
        }
    }

    private fun badRequestReason(response: Response): FoodAiError {
        // Google also returns HTTP 400 for invalid/expired keys. Inspect only its
        // structured reason, within a small bound; never expose the message/body.
        return try {
            val error = Json.parseToJsonElement(response.peekBody(16L * 1024).string()).jsonObject["error"]?.jsonObject
            val invalidKey = error?.get("details")?.jsonArray.orEmpty().any { detail ->
                detail.jsonObject["reason"]?.jsonPrimitive?.content in setOf("API_KEY_INVALID", "API_KEY_EXPIRED", "API_KEY_NOT_FOUND")
            }
            if (invalidKey) FoodAiError.KEY_INVALID else FoodAiError.BAD_REQUEST
        } catch (_: Exception) { FoodAiError.BAD_REQUEST }
    }

    private companion object { const val MAX_RESPONSE_BYTES = 256L * 1024 }
}
