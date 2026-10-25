package com.nutrix.app.integration.healthconnect

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.nutrix.app.integration.ExternalDaily
import com.nutrix.app.integration.ExternalExercise
import com.nutrix.app.integration.ExternalSleep
import com.nutrix.app.integration.ExternalWeight
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.reflect.KClass

enum class HcAvailability { AVAILABLE, UPDATE_REQUIRED, NOT_SUPPORTED }

/** Thin wrapper around the official Health Connect client. Read-only. */
@Singleton
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val providerPackage = "com.google.android.apps.healthdata"

    /** Record types NUTRIX reads, and why (shown on the integration screen). */
    val readTypes: List<KClass<out Record>> = listOf(
        StepsRecord::class,
        SleepSessionRecord::class,
        HeartRateRecord::class,
        WeightRecord::class,
        ExerciseSessionRecord::class,
        ActiveCaloriesBurnedRecord::class,
        TotalCaloriesBurnedRecord::class,
    )

    val corePermissions: Set<String> = readTypes.map { HealthPermission.getReadPermission(it) }.toSet()
    val backgroundPermission = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
    val historyPermission = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

    fun availability(): HcAvailability = when (HealthConnectClient.getSdkStatus(context, providerPackage)) {
        HealthConnectClient.SDK_AVAILABLE -> HcAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcAvailability.UPDATE_REQUIRED
        else -> HcAvailability.NOT_SUPPORTED
    }

    private val client: HealthConnectClient? by lazy {
        if (availability() == HcAvailability.AVAILABLE) HealthConnectClient.getOrCreate(context) else null
    }

    private fun requireClient(): HealthConnectClient = client ?: throw IllegalStateException("Health Connect unavailable")

    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract(providerPackage)

    /** Permissions to request: core types plus optional background/history access when supported. */
    fun requestablePermissions(): Set<String> = buildSet {
        addAll(corePermissions)
        if (isFeatureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)) add(backgroundPermission)
        if (isFeatureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)) add(historyPermission)
    }

    suspend fun grantedPermissions(): Set<String> =
        client?.permissionController?.getGrantedPermissions() ?: emptySet()

    suspend fun revokeAll() {
        client?.permissionController?.revokeAllPermissions()
    }

    fun isFeatureAvailable(feature: Int): Boolean = runCatching {
        client?.features?.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    fun settingsIntent(): Intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)

    fun installIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$providerPackage&url=healthconnect%3A%2F%2Fonboarding"))
            .setPackage("com.android.vending")

    // ---- Reading ----

    private suspend fun <T : Record> readAll(type: KClass<T>, from: Instant, to: Instant): List<T> {
        val c = requireClient()
        val out = mutableListOf<T>()
        var token: String? = null
        var pages = 0
        do {
            val response = c.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, to), pageSize = 1000, pageToken = token))
            out += response.records
            token = response.pageToken
            pages++
        } while (!token.isNullOrEmpty() && pages < 50)
        return out
    }

    suspend fun readWeights(from: Instant, to: Instant): List<ExternalWeight> =
        readAll(WeightRecord::class, from, to).map(::toExternal)

    suspend fun readSleep(from: Instant, to: Instant): List<ExternalSleep> =
        readAll(SleepSessionRecord::class, from, to).mapNotNull(::toExternal)

    suspend fun readExercises(from: Instant, to: Instant): List<ExternalExercise> =
        readAll(ExerciseSessionRecord::class, from, to).map(::toExternal)

    fun toExternal(r: WeightRecord) = ExternalWeight(r.metadata.id, r.time, r.zoneOffset, r.weight.inKilograms, r.metadata.dataOrigin.packageName)

    fun toExternal(r: SleepSessionRecord): ExternalSleep? {
        val total = java.time.Duration.between(r.startTime, r.endTime).toMinutes()
        // Exclude awake / out-of-bed stages when the source provides stage data.
        val awake = r.stages.filter {
            it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE || it.stage == SleepSessionRecord.STAGE_TYPE_OUT_OF_BED ||
                it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED
        }.sumOf { java.time.Duration.between(it.startTime, it.endTime).toMinutes() }
        val minutes = (total - awake).toInt()
        if (minutes <= 0) return null
        return ExternalSleep(r.metadata.id, r.startTime, r.endTime, r.endZoneOffset, minutes, r.metadata.dataOrigin.packageName)
    }

    fun toExternal(r: ExerciseSessionRecord) = ExternalExercise(
        id = r.metadata.id,
        start = r.startTime,
        end = r.endTime,
        zoneOffset = r.startZoneOffset,
        exerciseType = r.exerciseType,
        title = r.title?.takeIf { it.isNotBlank() },
        kcal = null,
        sourceApp = r.metadata.dataOrigin.packageName,
    )

    /**
     * Daily aggregates computed by Health Connect itself, which already de-duplicates overlapping
     * data written by several apps (e.g. phone + watch steps).
     */
    suspend fun readDaily(from: LocalDate, to: LocalDate, granted: Set<String>): List<ExternalDaily> {
        val c = requireClient()
        val metrics = buildSet<AggregateMetric<*>> {
            if (HealthPermission.getReadPermission(StepsRecord::class) in granted) add(StepsRecord.COUNT_TOTAL)
            if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in granted) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class) in granted) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            if (HealthPermission.getReadPermission(HeartRateRecord::class) in granted) {
                add(HeartRateRecord.BPM_MIN); add(HeartRateRecord.BPM_MAX); add(HeartRateRecord.BPM_AVG)
            }
            if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) add(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
        }
        if (metrics.isEmpty()) return emptyList()
        val groups = c.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = metrics,
                timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
            ),
        )
        val latestHr = if (HealthPermission.getReadPermission(HeartRateRecord::class) in granted) latestHeartRate(to) else null
        return groups.map { g ->
            val r = g.result
            val day = g.startTime.toLocalDate()
            ExternalDaily(
                date = day,
                steps = r[StepsRecord.COUNT_TOTAL],
                activeKcal = r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
                totalKcal = r[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories,
                exerciseMinutes = r[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes()?.toInt(),
                hrMin = r[HeartRateRecord.BPM_MIN]?.toInt(),
                hrMax = r[HeartRateRecord.BPM_MAX]?.toInt(),
                hrAvg = r[HeartRateRecord.BPM_AVG]?.toInt(),
                hrLatestBpm = latestHr?.takeIf { day == to }?.first,
                hrLatestMillis = latestHr?.takeIf { day == to }?.second,
            )
        }
    }

    private suspend fun latestHeartRate(day: LocalDate): Pair<Int, Long>? {
        val zone = java.time.ZoneId.systemDefault()
        val records = readAll(HeartRateRecord::class, day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant())
        val sample = records.flatMap { it.samples }.maxByOrNull { it.time } ?: return null
        return sample.beatsPerMinute.toDouble().roundToInt() to sample.time.toEpochMilli()
    }

    // ---- Changes API ----

    suspend fun changesToken(type: KClass<out Record>): String =
        requireClient().getChangesToken(ChangesTokenRequest(recordTypes = setOf(type)))

    sealed interface ChangeBatch {
        data class Changes(val upserts: List<Record>, val deletedIds: List<String>, val nextToken: String) : ChangeBatch
        data object TokenExpired : ChangeBatch
    }

    suspend fun readChanges(token: String): ChangeBatch {
        val c = requireClient()
        val upserts = mutableListOf<Record>()
        val deletions = mutableListOf<String>()
        var next = token
        var guard = 0
        do {
            val response = c.getChanges(next)
            if (response.changesTokenExpired) return ChangeBatch.TokenExpired
            response.changes.forEach { change ->
                when (change) {
                    is UpsertionChange -> upserts += change.record
                    is DeletionChange -> deletions += change.recordId
                }
            }
            next = response.nextChangesToken
            guard++
        } while (response.hasMore && guard < 100)
        return ChangeBatch.Changes(upserts, deletions, next)
    }
}
