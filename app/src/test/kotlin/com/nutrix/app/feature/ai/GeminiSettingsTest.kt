package com.nutrix.app.feature.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.nutrix.app.core.photo.PhotoStorage
import org.robolectric.annotation.GraphicsMode
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.security.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeminiSettingsTest {
    @Test fun `key is excluded from backup and cannot be replaced by archive input`() = runBlocking {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.clearAll()
        settings.setGeminiKeyBlob("encrypted-device-secret")
        settings.setGeminiModel("gemini-3.6-flash")
        assertThat(settings.hasGeminiKey.first()).isTrue()
        val exported = settings.exportForBackup()
        assertThat(exported.toString()).doesNotContain("encrypted-device-secret")
        assertThat(exported.keys.any { it.contains("gemini_api_key") }).isFalse()
        settings.importFromBackup(JsonObject(exported + ("s:gemini_api_key_blob" to JsonPrimitive("archive-secret"))))
        assertThat(settings.geminiKeyBlob()).isEqualTo("encrypted-device-secret")
        assertThat(settings.current().geminiModel).isEqualTo("gemini-3.6-flash")
        settings.clearAll()
        assertThat(settings.hasGeminiKey.first()).isFalse()
    }

    @Test fun `unreadable keystore fails before sending a request and missing key is distinct`() = runBlocking {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.clearAll()
        val cipher = object : SecretCipher {
            override fun encrypt(plain: ByteArray): String = error("unavailable")
            override fun decrypt(blob: String): ByteArray = error("unavailable")
        }
        assertThat((runCatching { settings.readGeminiKey(cipher) }.exceptionOrNull() as FoodAiException).reason).isEqualTo(FoodAiError.KEY_MISSING)
        settings.setGeminiKeyBlob("not-plaintext")
        assertThat((runCatching { settings.readGeminiKey(cipher) }.exceptionOrNull() as FoodAiException).reason).isEqualTo(FoodAiError.KEY_UNAVAILABLE)
        assertThat(runCatching { settings.setGeminiModel("model/../../evil") }.isFailure).isTrue()
    }
    @Test fun `paid project confirmation belongs to the current key`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val settings = SettingsRepository(context).also { it.clearAll() }
            val cipher = object : SecretCipher {
                override fun encrypt(plain: ByteArray): String = "sealed-" + plain.size
                override fun decrypt(blob: String): ByteArray = error("not needed")
            }
            val vm = GeminiSettingsViewModel(settings, cipher, GeminiClient(GeminiProtocol(buildJsonObject { }, "")))
            // Subscribed before any action, like the open screen.
            val screen = launch(Dispatchers.Unconfined) { vm.state.collect { } }
            suspend fun idle(check: (GeminiSettingsState) -> Boolean) = withTimeout(10_000) { vm.state.first { it.loaded && !it.busy && check(it) } }
            idle { !it.hasKey }
            vm.saveKey("AIza" + "x".repeat(35))
            idle { it.hasKey && !it.paidProject }
            vm.setPaidProject(true)
            idle { it.paidProject }
            settings.setAiReviewConsent(true)
            // A replaced key starts as free again: consent and the cloud path close with it.
            vm.saveKey("AIza" + "y".repeat(35))
            idle { it.keyRevision == 2 && !it.paidProject }
            assertThat(settings.current().aiReviewConsent).isFalse()
            vm.setPaidProject(true)
            idle { it.paidProject }
            vm.removeKey()
            idle { !it.hasKey && !it.paidProject }
            screen.cancel()
            settings.clearAll()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `a new key never appears next to the previous key's billing confirmation`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context).also { it.clearAll() }
        settings.replaceGeminiKey("old-key")
        settings.setAiPaidProject(true)
        settings.setAiReviewConsent(true)
        settings.setAiReviewCache("{}")
        // Every snapshot the store publishes while the key is replaced.
        val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<String?, Boolean>>()
        val watcher = launch(Dispatchers.Unconfined) { settings.geminiKeyState.collect { seen += it } }
        settings.replaceGeminiKey("new-key")
        withTimeout(10_000) { settings.geminiKeyState.first { it.first == "new-key" } }
        watcher.cancel()
        assertThat(seen).doesNotContain("new-key" to true)
        val after = settings.current()
        assertThat(after.aiPaidProject).isFalse()
        assertThat(after.aiReviewConsent).isFalse()
        assertThat(settings.aiReviewCache.first()).isNull()
        settings.clearAll()
    }

    @Test fun `paid switch waits for a running key operation`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val settings = SettingsRepository(context).also { it.clearAll() }
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val cipher = object : SecretCipher {
                // The key write is held until the test lets it go.
                override fun encrypt(plain: ByteArray): String = runBlocking { gate.await(); "sealed-" + plain.size }
                override fun decrypt(blob: String): ByteArray = error("not needed")
            }
            val vm = GeminiSettingsViewModel(settings, cipher, GeminiClient(GeminiProtocol(buildJsonObject { }, "")))
            val screen = launch(Dispatchers.Unconfined) { vm.state.collect { } }
            withTimeout(10_000) { vm.state.first { it.loaded } }
            vm.saveKey("AIza" + "z".repeat(35))
            withTimeout(10_000) { vm.state.first { it.busy } }
            // A confirmation given mid-operation (a dialog opened for the previous key) is not written at all.
            vm.setPaidProject(true)
            assertThat(settings.current().aiPaidProject).isFalse()
            gate.complete(Unit)
            withTimeout(10_000) { vm.state.first { it.loaded && !it.busy && it.hasKey } }
            assertThat(settings.current().aiPaidProject).isFalse()
            screen.cancel()
            settings.clearAll()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `chosen photo is resized reencoded without EXIF and private paths are rejected`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = PhotoStorage(context)
        val file = storage.file("ai-upload-test.jpg")
        val bitmap = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_USER_COMMENT, "private metadata")
            setLatLong(55.75, 37.61)
            saveAttributes()
        }
        val bytes = storage.prepareForAi(file.name)
        assertThat(bytes.size).isAtMost(GeminiProtocol.MAX_PHOTO_BYTES)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        assertThat(options.outWidth).isAtMost(1280)
        assertThat(options.outHeight).isAtMost(1280)
        val exif = ExifInterface(bytes.inputStream())
        assertThat(exif.getAttribute(ExifInterface.TAG_USER_COMMENT)).isNull()
        assertThat(exif.latLong).isNull()
        assertThat(runCatching { storage.prepareForAi("../settings.preferences_pb") }.isFailure).isTrue()
        file.delete()
        Unit
    }

}
