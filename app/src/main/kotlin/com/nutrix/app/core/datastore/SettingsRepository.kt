package com.nutrix.app.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nutrix.app.core.model.ActivityLevel
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.model.FormulaSex
import com.nutrix.app.core.model.NutritionGoal
import com.nutrix.app.core.model.SpecialCircumstance
import com.nutrix.app.core.model.ThemeMode
import com.nutrix.app.core.model.WeightUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store get() = context.settingsStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object Keys {
        val userName = stringPreferencesKey("user_name")
        val heightCm = intPreferencesKey("height_cm")
        val startWeight = doublePreferencesKey("start_weight_kg")
        val targetWeight = doublePreferencesKey("target_weight_kg")
        val weightUnit = stringPreferencesKey("weight_unit")
        val themeMode = stringPreferencesKey("theme_mode")
        val waterGoal = intPreferencesKey("water_goal_ml")
        val quickVolumes = stringPreferencesKey("quick_volumes")
        val lockEnabled = booleanPreferencesKey("lock_enabled")
        val biometricEnabled = booleanPreferencesKey("biometric_enabled")
        val lockTimeout = intPreferencesKey("lock_timeout_s")
        val secureScreen = booleanPreferencesKey("secure_screen")
        val smartSuggestions = booleanPreferencesKey("smart_suggestions")
        val calorieEstimates = booleanPreferencesKey("calorie_estimates")
        val quietEnabled = booleanPreferencesKey("quiet_enabled")
        val quietStart = intPreferencesKey("quiet_start")
        val quietEnd = intPreferencesKey("quiet_end")
        val snooze = intPreferencesKey("snooze_minutes")
        val reminders = stringPreferencesKey("reminders_json")
        val hcEnabled = booleanPreferencesKey("hc_enabled")
        val samsungEnabled = booleanPreferencesKey("samsung_enabled")
        val autoSync = booleanPreferencesKey("auto_sync")
        val syncInterval = intPreferencesKey("sync_interval_h")
        val sourcePriority = stringPreferencesKey("source_priority")
        val autoBackup = booleanPreferencesKey("auto_backup")
        val autoBackupUri = stringPreferencesKey("auto_backup_uri")
        val autoBackupInterval = intPreferencesKey("auto_backup_interval_days")
        val autoBackupKeep = intPreferencesKey("auto_backup_keep")
        val autoBackupEncrypted = booleanPreferencesKey("auto_backup_encrypted")
        val lastAutoBackup = longPreferencesKey("last_auto_backup")
        val reportPhotos = booleanPreferencesKey("report_photos")
        val geminiModel = stringPreferencesKey("gemini_model")
        val geminiKeyBlob = stringPreferencesKey("gemini_api_key_blob")
        val hiddenInsights = stringPreferencesKey("hidden_insights")
        val insightTopics = stringPreferencesKey("insight_topics")
        val aiReviewConsent = booleanPreferencesKey("ai_review_consent")
        val aiPaidProject = booleanPreferencesKey("ai_paid_project")
        // Inputs of the maintenance guideline; never sent anywhere.
        val nutritionGoal = stringPreferencesKey("nutrition_goal")
        val birthYear = intPreferencesKey("birth_year")
        val formulaSex = stringPreferencesKey("formula_sex")
        val activityLevel = stringPreferencesKey("activity_level")
        val circumstances = stringPreferencesKey("special_circumstances")
        // Regenerable caches start with "cache_" and are left out of backups.
        val aiReviewCache = stringPreferencesKey("cache_ai_review")

        // Secrets (never exported in backups): Keystore-encrypted blobs.
        val pinBlob = stringPreferencesKey("pin_blob")
        val pinFailures = intPreferencesKey("pin_failures")
        val pinLockoutUntil = longPreferencesKey("pin_lockout_until")
        val backupPasswordBlob = stringPreferencesKey("backup_password_blob")

        fun syncLast(source: DataSource) = longPreferencesKey("sync_last_${source.name}")
        fun syncError(source: DataSource) = stringPreferencesKey("sync_error_${source.name}")
        fun syncInitial(source: DataSource) = booleanPreferencesKey("sync_initial_${source.name}")
        fun syncSummary(source: DataSource) = stringPreferencesKey("sync_summary_${source.name}")
        fun changesToken(source: DataSource, type: String) = stringPreferencesKey("token_${source.name}_$type")
    }

    private val secretKeys = setOf("pin_blob", "backup_password_blob", "pin_failures", "pin_lockout_until", "gemini_api_key_blob")
    private val backupTypes = mapOf(
        Keys.userName.name to "s", Keys.heightCm.name to "i", Keys.startWeight.name to "d", Keys.targetWeight.name to "d",
        Keys.weightUnit.name to "s", Keys.themeMode.name to "s", Keys.waterGoal.name to "i", Keys.quickVolumes.name to "s",
        Keys.lockEnabled.name to "b", Keys.biometricEnabled.name to "b", Keys.lockTimeout.name to "i", Keys.secureScreen.name to "b",
        Keys.smartSuggestions.name to "b", Keys.calorieEstimates.name to "b", Keys.quietEnabled.name to "b",
        Keys.quietStart.name to "i", Keys.quietEnd.name to "i", Keys.snooze.name to "i", Keys.reminders.name to "s",
        Keys.hcEnabled.name to "b", Keys.samsungEnabled.name to "b", Keys.autoSync.name to "b", Keys.syncInterval.name to "i",
        Keys.sourcePriority.name to "s", Keys.autoBackup.name to "b", Keys.autoBackupUri.name to "s",
        Keys.autoBackupInterval.name to "i", Keys.autoBackupKeep.name to "i", Keys.autoBackupEncrypted.name to "b",
        Keys.lastAutoBackup.name to "l", Keys.reportPhotos.name to "b", Keys.geminiModel.name to "s",
        Keys.hiddenInsights.name to "s", Keys.insightTopics.name to "s",
        Keys.nutritionGoal.name to "s", Keys.birthYear.name to "i", Keys.formulaSex.name to "s", Keys.activityLevel.name to "s",
        Keys.circumstances.name to "s",
    )

    val settings: Flow<UserSettings> = store.data.map { it.toSettings() }

    suspend fun current(): UserSettings = settings.first()

    private fun Preferences.toSettings(): UserSettings {
        val d = UserSettings()
        return UserSettings(
            userName = this[Keys.userName] ?: d.userName,
            heightCm = this[Keys.heightCm],
            startWeightKg = this[Keys.startWeight],
            targetWeightKg = this[Keys.targetWeight],
            weightUnit = enumOr(this[Keys.weightUnit], d.weightUnit),
            themeMode = enumOr(this[Keys.themeMode], d.themeMode),
            waterGoalMl = this[Keys.waterGoal],
            quickVolumes = this[Keys.quickVolumes]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                ?.filter { it > 0 }?.takeIf { it.isNotEmpty() } ?: d.quickVolumes,
            lockEnabled = this[Keys.lockEnabled] ?: d.lockEnabled,
            biometricEnabled = this[Keys.biometricEnabled] ?: d.biometricEnabled,
            lockTimeoutSeconds = this[Keys.lockTimeout] ?: d.lockTimeoutSeconds,
            secureScreen = this[Keys.secureScreen] ?: d.secureScreen,
            smartSuggestions = this[Keys.smartSuggestions] ?: d.smartSuggestions,
            calorieEstimates = this[Keys.calorieEstimates] ?: d.calorieEstimates,
            quietHoursEnabled = this[Keys.quietEnabled] ?: d.quietHoursEnabled,
            quietStartMinutes = this[Keys.quietStart] ?: d.quietStartMinutes,
            quietEndMinutes = this[Keys.quietEnd] ?: d.quietEndMinutes,
            snoozeMinutes = this[Keys.snooze] ?: d.snoozeMinutes,
            reminders = this[Keys.reminders]?.let { decodeReminders(it) } ?: d.reminders,
            healthConnectEnabled = this[Keys.hcEnabled] ?: d.healthConnectEnabled,
            samsungHealthEnabled = this[Keys.samsungEnabled] ?: d.samsungHealthEnabled,
            autoSync = this[Keys.autoSync] ?: d.autoSync,
            syncIntervalHours = this[Keys.syncInterval] ?: d.syncIntervalHours,
            sourcePriority = this[Keys.sourcePriority]?.split(',')
                ?.mapNotNull { name -> DataSource.entries.firstOrNull { it.name == name } }
                ?.takeIf { it.isNotEmpty() } ?: d.sourcePriority,
            autoBackupEnabled = this[Keys.autoBackup] ?: d.autoBackupEnabled,
            autoBackupFolderUri = this[Keys.autoBackupUri],
            autoBackupIntervalDays = this[Keys.autoBackupInterval] ?: d.autoBackupIntervalDays,
            autoBackupKeep = this[Keys.autoBackupKeep] ?: d.autoBackupKeep,
            lastAutoBackupMillis = this[Keys.lastAutoBackup],
            includePhotosInReports = this[Keys.reportPhotos] ?: d.includePhotosInReports,
            geminiModel = this[Keys.geminiModel] ?: d.geminiModel,
            hiddenInsights = this[Keys.hiddenInsights]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: d.hiddenInsights,
            insightTopics = this[Keys.insightTopics]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: d.insightTopics,
            aiReviewConsent = this[Keys.aiReviewConsent] ?: d.aiReviewConsent,
            aiPaidProject = this[Keys.aiPaidProject] ?: d.aiPaidProject,
            nutritionGoal = NutritionGoal.entries.firstOrNull { it.name == this[Keys.nutritionGoal] },
            birthYear = this[Keys.birthYear],
            formulaSex = FormulaSex.entries.firstOrNull { it.name == this[Keys.formulaSex] },
            activityLevel = ActivityLevel.entries.firstOrNull { it.name == this[Keys.activityLevel] },
            circumstances = this[Keys.circumstances]?.split(',')
                ?.mapNotNull { name -> SpecialCircumstance.entries.firstOrNull { it.name == name.trim() } }?.toSet() ?: emptySet(),
        )
    }

    private fun decodeReminders(value: String): List<ReminderConfig> {
        val stored = runCatching { json.decodeFromString(ListSerializer(ReminderConfig.serializer()), value) }
            .getOrDefault(emptyList())
        // Keep every reminder type present even if new types were added in an update.
        return ReminderConfig.defaults().map { def -> stored.firstOrNull { it.type == def.type } ?: def }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    private fun <T> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    suspend fun setProfile(name: String, heightCm: Int?, startWeightKg: Double?, targetWeightKg: Double?) = edit {
        it[Keys.userName] = name.trim()
        it.setOrRemove(Keys.heightCm, heightCm)
        it.setOrRemove(Keys.startWeight, startWeightKg)
        it.setOrRemove(Keys.targetWeight, targetWeightKg)
    }

    suspend fun setTargetWeight(kg: Double?) = edit { it.setOrRemove(Keys.targetWeight, kg) }

    /** What the maintenance guideline is computed from, in one write; null clears a value. */
    suspend fun setNutritionProfile(
        goal: NutritionGoal?, birthYear: Int?, sex: FormulaSex?, activity: ActivityLevel?, circumstances: Set<SpecialCircumstance>,
    ) = edit {
        it.setOrRemove(Keys.nutritionGoal, goal?.name)
        it.setOrRemove(Keys.birthYear, birthYear)
        it.setOrRemove(Keys.formulaSex, sex?.name)
        it.setOrRemove(Keys.activityLevel, activity?.name)
        it.setOrRemove(Keys.circumstances, circumstances.takeIf { c -> c.isNotEmpty() }?.joinToString(",") { c -> c.name })
    }

    suspend fun setHeight(cm: Int?) = edit { it.setOrRemove(Keys.heightCm, cm) }
    suspend fun setWeightUnit(unit: WeightUnit) = edit { it[Keys.weightUnit] = unit.name }
    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.themeMode] = mode.name }
    suspend fun setWaterGoal(ml: Int?) = edit { it.setOrRemove(Keys.waterGoal, ml) }
    suspend fun setQuickVolumes(volumes: List<Int>) = edit {
        it[Keys.quickVolumes] = volumes.filter { v -> v in 1..5000 }.distinct().take(6).joinToString(",")
    }

    suspend fun setLockEnabled(enabled: Boolean) = edit { it[Keys.lockEnabled] = enabled }
    suspend fun setBiometricEnabled(enabled: Boolean) = edit { it[Keys.biometricEnabled] = enabled }
    suspend fun setLockTimeout(seconds: Int) = edit { it[Keys.lockTimeout] = seconds }
    suspend fun setSecureScreen(enabled: Boolean) = edit { it[Keys.secureScreen] = enabled }
    suspend fun setSmartSuggestions(enabled: Boolean) = edit { it[Keys.smartSuggestions] = enabled }
    suspend fun setCalorieEstimates(enabled: Boolean) = edit { it[Keys.calorieEstimates] = enabled }

    suspend fun setQuietHours(enabled: Boolean, start: Int, end: Int) = edit {
        it[Keys.quietEnabled] = enabled
        it[Keys.quietStart] = start
        it[Keys.quietEnd] = end
    }

    suspend fun setSnoozeMinutes(minutes: Int) = edit { it[Keys.snooze] = minutes }

    suspend fun setReminders(list: List<ReminderConfig>) = edit {
        it[Keys.reminders] = json.encodeToString(ListSerializer(ReminderConfig.serializer()), list)
    }

    suspend fun updateReminder(config: ReminderConfig) {
        val current = current().reminders
        setReminders(current.map { if (it.type == config.type) config else it })
    }

    suspend fun setHealthConnectEnabled(enabled: Boolean) = edit { it[Keys.hcEnabled] = enabled }
    suspend fun setSamsungHealthEnabled(enabled: Boolean) = edit { it[Keys.samsungEnabled] = enabled }
    suspend fun setAutoSync(enabled: Boolean) = edit { it[Keys.autoSync] = enabled }
    suspend fun setSyncInterval(hours: Int) = edit { it[Keys.syncInterval] = hours }
    suspend fun setSourcePriority(list: List<DataSource>) = edit {
        it[Keys.sourcePriority] = list.joinToString(",") { s -> s.name }
    }

    suspend fun setAutoBackup(enabled: Boolean, folderUri: String?, intervalDays: Int, keep: Int) = edit {
        it[Keys.autoBackup] = enabled
        it.setOrRemove(Keys.autoBackupUri, folderUri)
        it[Keys.autoBackupInterval] = intervalDays
        it[Keys.autoBackupKeep] = keep
    }

    suspend fun setLastAutoBackup(millis: Long) = edit { it[Keys.lastAutoBackup] = millis }
    suspend fun setIncludePhotosInReports(enabled: Boolean) = edit { it[Keys.reportPhotos] = enabled }

    suspend fun setInsightHidden(id: String, hidden: Boolean) = edit {
        val current = it[Keys.hiddenInsights]?.split(',')?.filter { s -> s.isNotBlank() }?.toSet().orEmpty()
        val next = if (hidden) current + id else current - id
        if (next.isEmpty()) it.remove(Keys.hiddenInsights) else it[Keys.hiddenInsights] = next.sorted().joinToString(",")
    }

    suspend fun setInsightTopic(topic: String, chosen: Boolean) = edit {
        val current = it[Keys.insightTopics]?.split(',')?.filter { s -> s.isNotBlank() }?.toSet().orEmpty()
        val next = if (chosen) current + topic else current - topic
        if (next.isEmpty()) it.remove(Keys.insightTopics) else it[Keys.insightTopics] = next.sorted().joinToString(",")
    }

    suspend fun setAiPaidProject(paid: Boolean) = edit {
        it[Keys.aiPaidProject] = paid
        // Without a paid project nothing may be sent, so consent and the cached review go too.
        if (!paid) {
            it[Keys.aiReviewConsent] = false
            it.remove(Keys.aiReviewCache)
        }
    }

    suspend fun setAiReviewConsent(consent: Boolean) = edit {
        it[Keys.aiReviewConsent] = consent
        if (!consent) it.remove(Keys.aiReviewCache)
    }

    val aiReviewCache: Flow<String?> = store.data.map { it[Keys.aiReviewCache] }

    // Device-local reminder state (prefix "cache_": left out of backups).
    private fun ignoresKey(type: ReminderType) = intPreferencesKey("cache_reminder_ignored_${type.name}")
    private val afterMealDay = longPreferencesKey("cache_after_meal_last_day")

    /** Reminders swiped away in a row per type; reset when the user opens or acts on one. */
    val reminderIgnores: Flow<Map<ReminderType, Int>> = store.data.map { prefs ->
        ReminderType.entries.associateWith { prefs[ignoresKey(it)] ?: 0 }
    }
    suspend fun recordReminderIgnored(type: ReminderType) = edit { it[ignoresKey(type)] = (it[ignoresKey(type)] ?: 0) + 1 }
    suspend fun resetReminderIgnores(type: ReminderType) = edit { it.remove(ignoresKey(type)) }

    suspend fun afterMealAskedDay(): Long? = store.data.first()[afterMealDay]
    suspend fun setAfterMealAskedDay(day: Long) = edit { it[afterMealDay] = day }
    suspend fun setAiReviewCache(json: String?) = edit { it.setOrRemove(Keys.aiReviewCache, json) }

    // Secrets
    suspend fun geminiKeyBlob(): String? = store.data.first()[Keys.geminiKeyBlob]
    val hasGeminiKey: Flow<Boolean> = store.data.map { !it[Keys.geminiKeyBlob].isNullOrBlank() }
    suspend fun setGeminiKeyBlob(blob: String?) = edit { it.setOrRemove(Keys.geminiKeyBlob, blob) }

    /**
     * Stores or removes the key together with what belonged to the previous one, in one write: the
     * billing confirmation, the review consent and the cached review never sit next to a new key.
     */
    suspend fun replaceGeminiKey(blob: String?) = edit {
        it.setOrRemove(Keys.geminiKeyBlob, blob)
        it[Keys.aiPaidProject] = false
        it[Keys.aiReviewConsent] = false
        it.remove(Keys.aiReviewCache)
    }

    /** The key and its billing confirmation read from one snapshot. */
    internal val geminiKeyState: Flow<Pair<String?, Boolean>> = store.data.map { it[Keys.geminiKeyBlob] to (it[Keys.aiPaidProject] ?: false) }
    suspend fun setGeminiModel(model: String) {
        require(com.nutrix.app.core.model.GeminiConfig.validModel(model))
        edit { it[Keys.geminiModel] = model }
    }

    suspend fun pinAttempts(): Pair<Int, Long> = store.data.first().let {
        (it[Keys.pinFailures] ?: 0) to (it[Keys.pinLockoutUntil] ?: 0L)
    }

    suspend fun setPinAttempts(failures: Int, lockoutUntil: Long) = edit {
        it[Keys.pinFailures] = failures
        it[Keys.pinLockoutUntil] = lockoutUntil
    }

    suspend fun pinBlob(): String? = store.data.first()[Keys.pinBlob]
    suspend fun setPinBlob(blob: String?) = edit { it.setOrRemove(Keys.pinBlob, blob) }
    suspend fun backupPasswordBlob(): String? = store.data.first()[Keys.backupPasswordBlob]
    suspend fun setBackupPasswordBlob(blob: String?) = edit {
        it.setOrRemove(Keys.backupPasswordBlob, blob)
        it[Keys.autoBackupEncrypted] = blob != null
    }
    suspend fun autoBackupRequiresPassword(): Boolean = store.data.first().let {
        it[Keys.autoBackupEncrypted] ?: (it[Keys.backupPasswordBlob] != null)
    }

    // Sync state
    fun syncState(source: DataSource): Flow<SourceSyncState> = store.data.map {
        SourceSyncState(
            lastSyncMillis = it[Keys.syncLast(source)],
            lastError = it[Keys.syncError(source)],
            initialImportDone = it[Keys.syncInitial(source)] ?: false,
            lastResultSummary = it[Keys.syncSummary(source)],
        )
    }

    suspend fun setSyncResult(source: DataSource, millis: Long?, error: String?, summary: String?, initialDone: Boolean? = null) = edit {
        if (millis != null) it[Keys.syncLast(source)] = millis
        it.setOrRemove(Keys.syncError(source), error)
        if (summary != null) it[Keys.syncSummary(source)] = summary
        if (initialDone != null) it[Keys.syncInitial(source)] = initialDone
    }

    suspend fun changesToken(source: DataSource, type: String): String? = store.data.first()[Keys.changesToken(source, type)]

    suspend fun setChangesToken(source: DataSource, type: String, token: String?) = edit {
        it.setOrRemove(Keys.changesToken(source, type), token)
    }

    suspend fun resetSyncState(source: DataSource) = edit { prefs ->
        val prefix = listOf("sync_last_${source.name}", "sync_error_${source.name}", "sync_initial_${source.name}",
            "sync_summary_${source.name}")
        prefs.asMap().keys.filter { k -> k.name in prefix || k.name.startsWith("token_${source.name}_") }
            .forEach { k -> prefs.remove(k) }
    }

    /** Exports non-secret preferences for backups. */
    suspend fun exportForBackup(): JsonObject {
        val prefs = store.data.first().asMap()
        val map = prefs.filterKeys { it.name !in secretKeys && !it.name.startsWith("token_") && !it.name.startsWith("sync_") &&
            !it.name.startsWith("cache_") }
            .mapNotNull { (key, value) ->
                val element = when (value) {
                    is Boolean -> JsonPrimitive(value)
                    is Int -> JsonPrimitive(value)
                    is Long -> JsonPrimitive(value)
                    is Double -> JsonPrimitive(value)
                    is Float -> JsonPrimitive(value)
                    is String -> JsonPrimitive(value)
                    else -> null
                } ?: return@mapNotNull null
                "${typeTag(value)}:${key.name}" to element
            }.toMap()
        return JsonObject(map + ("b:${Keys.autoBackupEncrypted.name}" to JsonPrimitive(autoBackupRequiresPassword())))
    }

    private fun typeTag(value: Any): String = when (value) {
        is Boolean -> "b"
        is Int -> "i"
        is Long -> "l"
        is Double -> "d"
        is Float -> "d"
        else -> "s"
    }

    /** Validate before changing the database or any preferences; unknown future keys are ignored. */
    fun validateBackup(obj: JsonObject) {
        obj.forEach { (taggedName, element) ->
            val name = taggedName.substringAfter(':')
            val expected = backupTypes[name] ?: return@forEach
            val value = element as? JsonPrimitive ?: error("Invalid setting: $name")
            require(taggedName.substringBefore(':') == expected && when (expected) {
                "b" -> !value.isString && value.booleanOrNull != null
                "i" -> !value.isString && value.intOrNull != null
                "l" -> !value.isString && value.longOrNull != null
                "d" -> !value.isString && value.doubleOrNull?.isFinite() == true
                else -> value.isString
            }) { "Invalid setting: $name" }
        }
    }

    /** Restore portable settings, preserving local secrets and resetting all import cursors. */
    suspend fun importFromBackup(obj: JsonObject) {
        validateBackup(obj)
        edit { prefs ->
            prefs.asMap().keys.filter { it.name !in secretKeys }
                .forEach { prefs.remove(it) }
            obj.forEach { (taggedName, element) ->
                val primitive = element as? JsonPrimitive ?: return@forEach
                val tag = taggedName.substringBefore(':')
                val name = taggedName.substringAfter(':')
                if (name !in backupTypes) return@forEach
                when (tag) {
                    "b" -> primitive.booleanOrNull?.let { prefs[booleanPreferencesKey(name)] = it }
                    "i" -> primitive.intOrNull?.let { prefs[intPreferencesKey(name)] = it }
                    "l" -> primitive.longOrNull?.let { prefs[longPreferencesKey(name)] = it }
                    "d" -> primitive.doubleOrNull?.let { prefs[doublePreferencesKey(name)] = it }
                    else -> prefs[stringPreferencesKey(name)] = primitive.content
                }
            }
            // A restored backup never carries the PIN, so the lock must not stay enabled without one.
            if (prefs[Keys.pinBlob] == null) {
                prefs[Keys.lockEnabled] = false
                prefs[Keys.biometricEnabled] = false
            }
            // Folder permissions and encryption keys belong to this device, not the archive.
            if (prefs[Keys.autoBackupEncrypted] == true && prefs[Keys.backupPasswordBlob] == null) {
                prefs[Keys.autoBackup] = false
            }
        }
    }

    /** Capture durable bytes while DataStore serialises updates; used only by restore recovery. */
    internal suspend fun snapshotForRecovery(destination: File): Preferences {
        var snapshot: Preferences? = null
        store.updateData { prefs ->
            snapshot = prefs
            val source = context.preferencesDataStoreFile("settings")
            if (source.exists()) {
                destination.outputStream().use { out -> source.inputStream().use { it.copyTo(out) }; out.fd.sync() }
            }
            prefs
        }
        return checkNotNull(snapshot)
    }

    internal suspend fun restoreSnapshot(snapshot: Preferences) { store.updateData { snapshot } }

    suspend fun clearAll() = edit { it.clear() }
}
