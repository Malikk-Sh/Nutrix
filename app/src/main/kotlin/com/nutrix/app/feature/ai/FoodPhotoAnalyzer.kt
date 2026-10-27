package com.nutrix.app.feature.ai

import android.content.Context
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.core.security.SecretCipher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

fun interface FoodPhotoAnalyzer {
    suspend fun analyze(fileName: String, hint: String): FoodPhotoResult
}

data class FoodPhotoResult(val estimate: FoodEstimate, val model: String, val photoFile: String)

@Singleton
class GeminiFoodPhotoAnalyzer @Inject constructor(
    private val client: GeminiClient,
    private val settings: SettingsRepository,
    private val cipher: SecretCipher,
    private val photos: PhotoStorage,
) : FoodPhotoAnalyzer {
    override suspend fun analyze(fileName: String, hint: String): FoodPhotoResult {
        val key = settings.readGeminiKey(cipher)
        val model = settings.current().geminiModel
        val photo = try { photos.prepareForAi(fileName) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw FoodAiException(FoodAiError.PHOTO_UNAVAILABLE) }
        return FoodPhotoResult(client.analyze(key, model, AiPhoto(photo), hint), model, fileName)
    }
}

suspend fun SettingsRepository.readGeminiKey(cipher: SecretCipher): String {
    val blob = geminiKeyBlob() ?: throw FoodAiException(FoodAiError.KEY_MISSING)
    return try { withContext(Dispatchers.IO) { cipher.decrypt(blob).decodeToString() } }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { throw FoodAiException(FoodAiError.KEY_UNAVAILABLE) }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FoodAiBindings {
    @Binds abstract fun analyzer(implementation: GeminiFoodPhotoAnalyzer): FoodPhotoAnalyzer
}

@Module
@InstallIn(SingletonComponent::class)
object FoodAiProviders {
    @Provides @Singleton
    fun protocol(@ApplicationContext context: Context) = GeminiProtocol(
        Json.parseToJsonElement(context.assets.open("food-analysis.schema.json").bufferedReader().use { it.readText() }).jsonObject,
        context.assets.open("food-analysis-prompt.txt").bufferedReader().use { it.readText() },
    )
}
