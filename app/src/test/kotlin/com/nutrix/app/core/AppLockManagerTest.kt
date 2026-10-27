package com.nutrix.app.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.security.AppLockManager
import com.nutrix.app.core.security.PinHasher
import com.nutrix.app.core.security.PinResult
import com.nutrix.app.core.security.SecretCipher
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class AppLockManagerTest {
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: ByteArray): String = Base64.getEncoder().encodeToString(plain)
        override fun decrypt(blob: String): ByteArray = Base64.getDecoder().decode(blob)
    }

    @Test fun `failed attempts and lockout survive manager recreation and backup restore`() = runBlocking {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.clearAll()
        settings.setPinBlob(cipher.encrypt(PinHasher.create("2580", iterations = 1000).encode()))
        repeat(4) { i ->
            assertThat(AppLockManager(settings, cipher).unlockWithPin("0000")).isEqualTo(PinResult.Wrong(4 - i))
        }
        assertThat(AppLockManager(settings, cipher).unlockWithPin("0000")).isInstanceOf(PinResult.LockedOut::class.java)
        val recreatedSettings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        assertThat(AppLockManager(recreatedSettings, cipher).unlockWithPin("2580")).isInstanceOf(PinResult.LockedOut::class.java)
        val backup = settings.exportForBackup()
        assertThat(backup.keys.any { it.contains("pin_") }).isFalse()
        settings.importFromBackup(backup)
        assertThat(AppLockManager(settings, cipher).unlockWithPin("2580")).isInstanceOf(PinResult.LockedOut::class.java)
    }

    @Test fun `successful pin clears persisted attempts and expired lockout permits retry`() = runBlocking {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.clearAll()
        settings.setPinBlob(cipher.encrypt(PinHasher.create("2580", iterations = 1000).encode()))
        settings.setPinAttempts(3, System.currentTimeMillis() - 1000)
        assertThat(AppLockManager(settings, cipher).unlockWithPin("2580")).isEqualTo(PinResult.Success)
        assertThat(settings.pinAttempts()).isEqualTo(0 to 0L)
    }
}
