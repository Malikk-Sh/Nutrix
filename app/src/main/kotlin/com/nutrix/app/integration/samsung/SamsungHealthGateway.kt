package com.nutrix.app.integration.samsung

import android.app.Activity
import com.nutrix.app.integration.ExternalDaily
import com.nutrix.app.integration.ExternalExercise
import com.nutrix.app.integration.ExternalSleep
import com.nutrix.app.integration.ExternalWeight
import java.time.LocalDate

/** Read-only data categories requested from Samsung Health. */
enum class SamsungDataCategory { STEPS, SLEEP, HEART_RATE, WEIGHT, EXERCISE, ACTIVITY_SUMMARY }

/** Connection state as reported by the Samsung Health Data SDK. */
enum class SamsungStatus {
    READY,
    SDK_NOT_BUNDLED,
    NOT_INSTALLED,
    OLD_VERSION,
    DISABLED,
    NOT_INITIALIZED,
    /** App is not registered with Samsung and developer mode is off. */
    NOT_AUTHORIZED,
    ERROR,
}

class SamsungHealthException(val status: SamsungStatus, message: String?, val resolvable: Boolean = false) : Exception(message)

/**
 * Direct access to Samsung Health via the official Samsung Health Data SDK.
 * The implementation is compiled only when the SDK AAR is present in app/libs
 * (see app/build.gradle.kts); otherwise a stub reports [SamsungStatus.SDK_NOT_BUNDLED].
 */
interface SamsungHealthGateway {
    val sdkBundled: Boolean

    fun isSamsungHealthInstalled(): Boolean

    /** Installed Samsung Health version name, if any. */
    fun samsungHealthVersion(): String?

    suspend fun status(): SamsungStatus

    suspend fun grantedCategories(): Set<SamsungDataCategory>

    /** Shows Samsung Health's own permission dialog. */
    suspend fun requestPermissions(activity: Activity): Set<SamsungDataCategory>

    /** Lets Samsung Health resolve a fixable state (update, enable, finish onboarding). */
    fun resolve(activity: Activity): Boolean

    suspend fun readWeights(from: LocalDate, to: LocalDate): List<ExternalWeight>
    suspend fun readSleep(from: LocalDate, to: LocalDate): List<ExternalSleep>
    suspend fun readExercises(from: LocalDate, to: LocalDate): List<ExternalExercise>
    suspend fun readDaily(from: LocalDate, to: LocalDate): List<ExternalDaily>
}
