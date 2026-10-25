package com.nutrix.app.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Encrypts small secrets before they are written to preferences. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): String
    fun decrypt(blob: String): ByteArray
}

/**
 * AES-256-GCM with a non-exportable key held in the Android Keystore. The key never leaves
 * secure hardware (where available), so copied preference files are useless on another device.
 */
@Singleton
class KeystoreSecretCipher @Inject constructor() : SecretCipher {

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain)
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + encrypted, Base64.NO_WRAP)
    }

    override fun decrypt(blob: String): ByteArray {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val ivSize = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivSize)
        val data = bytes.copyOfRange(1 + ivSize, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "nutrix_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
