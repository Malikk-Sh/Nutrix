package com.nutrix.app.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2 PIN verifier: the PIN itself is never stored, only a salted slow hash. */
object PinHasher {
    private const val ITERATIONS = 60_000
    private const val KEY_BITS = 256

    data class Verifier(val salt: ByteArray, val hash: ByteArray, val iterations: Int) {
        fun encode(): ByteArray {
            val iter = iterations.toString().toByteArray()
            return byteArrayOf(salt.size.toByte(), iter.size.toByte()) + salt + iter + hash
        }

        companion object {
            fun decode(bytes: ByteArray): Verifier {
                val saltSize = bytes[0].toInt()
                val iterSize = bytes[1].toInt()
                val salt = bytes.copyOfRange(2, 2 + saltSize)
                val iter = String(bytes.copyOfRange(2 + saltSize, 2 + saltSize + iterSize)).toInt()
                val hash = bytes.copyOfRange(2 + saltSize + iterSize, bytes.size)
                return Verifier(salt, hash, iter)
            }
        }
    }

    fun create(pin: String, iterations: Int = ITERATIONS): Verifier {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Verifier(salt, derive(pin, salt, iterations), iterations)
    }

    fun verify(pin: String, verifier: Verifier): Boolean =
        MessageDigest.isEqual(derive(pin, verifier.salt, verifier.iterations), verifier.hash)

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
