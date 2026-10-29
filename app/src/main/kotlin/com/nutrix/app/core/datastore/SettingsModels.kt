package com.nutrix.app.core.datastore

import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.model.ThemeMode
import com.nutrix.app.core.model.WeightUnit
import kotlinx.serialization.Serializable

data class UserSettings(
    val userName: String = "",
    val heightCm: Int? = null,
    val startWeightKg: Double? = null,
    val targetWeightKg: Double? = null,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val themeMode: ThemeMode = ThemeMode.DARK,
    /** User-defined fluid guideline. Null = not set; the app never derives a norm on its own. */
    val waterGoalMl: Int? = null,
    val quickVolumes: List<Int> = DEFAULT_QUICK_VOLUMES,
    val lockEnabled: Boolean = false,
    val biometricEnabled: Boolean = false,
    val lockTimeoutSeconds: Int = 30,
    val secureScreen: Boolean = false,
    val smartSuggestions: Boolean = true,
    val calorieEstimates: Boolean = true,
    val quietHoursEnabled: Boolean = true,
    val quietStartMinutes: Int = 22 * 60 + 30,
    val quietEndMinutes: Int = 8 * 60,
    val snoozeMinutes: Int = 30,
    val reminders: List<ReminderConfig> = ReminderConfig.defaults(),
    val healthConnectEnabled: Boolean = false,
    val samsungHealthEnabled: Boolean = false,
    val autoSync: Boolean = true,
    val syncIntervalHours: Int = 6,
    val sourcePriority: List<DataSource> = listOf(DataSource.HEALTH_CONNECT, DataSource.SAMSUNG_HEALTH),
    val autoBackupEnabled: Boolean = false,
    val autoBackupFolderUri: String? = null,
    val autoBackupIntervalDays: Int = 7,
    val autoBackupKeep: Int = 5,
    val lastAutoBackupMillis: Long? = null,
    val includePhotosInReports: Boolean = false,
    val geminiModel: String = com.nutrix.app.core.model.GeminiConfig.DEFAULT_MODEL,
    /** Observation ids the user chose to hide on the insights screen. */
    val hiddenInsights: Set<String> = emptySet(),
    /** Consent to send aggregated observation facts to Gemini; asked again after a restore. */
    val aiReviewConsent: Boolean = false,
) {
    companion object {
        val DEFAULT_QUICK_VOLUMES = listOf(100, 200, 250, 500)
    }
}

@Serializable
enum class ReminderType { MEAL, WATER, WEIGHT, WELLBEING, MISSED_ENTRIES }

@Serializable
enum class ReminderMode { FIXED_TIMES, INTERVAL }

@Serializable
data class ReminderConfig(
    val type: ReminderType,
    val enabled: Boolean = false,
    val mode: ReminderMode = ReminderMode.FIXED_TIMES,
    /** Minutes of day for FIXED_TIMES mode. */
    val times: List<Int> = emptyList(),
    /** Interval between reminders in INTERVAL mode. */
    val intervalMinutes: Int = 120,
    val windowStartMinutes: Int = 10 * 60,
    val windowEndMinutes: Int = 20 * 60,
    /** ISO days of week (1 = Monday ... 7 = Sunday). */
    val days: Set<Int> = ALL_DAYS,
) {
    companion object {
        val ALL_DAYS = (1..7).toSet()

        fun defaults(): List<ReminderConfig> = listOf(
            ReminderConfig(ReminderType.MEAL, times = listOf(9 * 60, 13 * 60 + 30, 19 * 60)),
            ReminderConfig(ReminderType.WATER, mode = ReminderMode.INTERVAL, intervalMinutes = 120),
            ReminderConfig(ReminderType.WEIGHT, times = listOf(8 * 60 + 30)),
            ReminderConfig(ReminderType.WELLBEING, times = listOf(21 * 60)),
            ReminderConfig(ReminderType.MISSED_ENTRIES, times = listOf(20 * 60 + 30)),
        )
    }
}

/** Per-source synchronisation status, persisted between launches. */
data class SourceSyncState(
    val lastSyncMillis: Long? = null,
    val lastError: String? = null,
    val initialImportDone: Boolean = false,
    val lastResultSummary: String? = null,
)
