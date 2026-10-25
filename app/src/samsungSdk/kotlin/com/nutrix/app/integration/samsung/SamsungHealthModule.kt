package com.nutrix.app.integration.samsung

import android.app.Activity
import android.content.Context
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.nutrix.app.integration.ExternalDaily
import com.nutrix.app.integration.ExternalExercise
import com.nutrix.app.integration.ExternalSleep
import com.nutrix.app.integration.ExternalWeight
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.error.AuthorizationException
import com.samsung.android.sdk.health.data.error.ErrorCode
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalDateFilter
import com.samsung.android.sdk.health.data.request.LocalDateGroup
import com.samsung.android.sdk.health.data.request.LocalDateGroupUnit
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.LocalTimeGroup
import com.samsung.android.sdk.health.data.request.LocalTimeGroupUnit
import com.samsung.android.sdk.health.data.request.Ordering
import com.samsung.android.sdk.health.data.request.ReadDataRequest
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Samsung Health Data SDK adapter (read-only). Data is only returned when the user has granted
 * access in Samsung Health's permission dialog AND the app is either registered as a Samsung
 * partner or Samsung Health's developer mode ("Developer Mode for Data Read") is enabled.
 */
@Singleton
class SdkSamsungHealthGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : SamsungHealthGateway {

    override val sdkBundled: Boolean = true

    private val store: HealthDataStore by lazy { HealthDataService.getStore(context) }

    private val permissions: Map<SamsungDataCategory, Permission> by lazy {
        mapOf(
            SamsungDataCategory.STEPS to Permission.of(DataTypes.STEPS, AccessType.READ),
            SamsungDataCategory.SLEEP to Permission.of(DataTypes.SLEEP, AccessType.READ),
            SamsungDataCategory.HEART_RATE to Permission.of(DataTypes.HEART_RATE, AccessType.READ),
            SamsungDataCategory.WEIGHT to Permission.of(DataTypes.BODY_COMPOSITION, AccessType.READ),
            SamsungDataCategory.EXERCISE to Permission.of(DataTypes.EXERCISE, AccessType.READ),
            SamsungDataCategory.ACTIVITY_SUMMARY to Permission.of(DataTypes.ACTIVITY_SUMMARY, AccessType.READ),
        )
    }

    @Volatile
    private var lastResolvable: ResolvablePlatformException? = null

    override fun isSamsungHealthInstalled(): Boolean = SamsungHealthPackage.isInstalled(context)

    override fun samsungHealthVersion(): String? = SamsungHealthPackage.versionName(context)

    override suspend fun status(): SamsungStatus {
        if (!isSamsungHealthInstalled()) return SamsungStatus.NOT_INSTALLED
        return try {
            store.getGrantedPermissions(permissions.values.toSet())
            SamsungStatus.READY
        } catch (e: HealthDataException) {
            map(e)
        }
    }

    override suspend fun grantedCategories(): Set<SamsungDataCategory> = call {
        val granted = store.getGrantedPermissions(permissions.values.toSet())
        permissions.filterValues { it in granted }.keys
    }

    override suspend fun requestPermissions(activity: Activity): Set<SamsungDataCategory> = call {
        val granted = store.requestPermissions(permissions.values.toSet(), activity)
        permissions.filterValues { it in granted }.keys
    }

    override fun resolve(activity: Activity): Boolean {
        val e = lastResolvable ?: return false
        if (!e.hasResolution) return false
        e.resolve(activity)
        return true
    }

    override suspend fun readWeights(from: LocalDate, to: LocalDate): List<ExternalWeight> = call {
        readAll { token ->
            DataTypes.BODY_COMPOSITION.readDataRequestBuilder
                .setLocalTimeFilter(LocalTimeFilter.of(from.atStartOfDay(), to.plusDays(1).atStartOfDay()))
                .setOrdering(Ordering.ASC)
                .apply { if (token != null) setPageToken(token) }
                .build()
        }.mapNotNull { p ->
            val kg: Float = p.getValue(DataType.BodyCompositionType.WEIGHT) ?: return@mapNotNull null
            if (kg <= 0f) return@mapNotNull null
            ExternalWeight(p.uid, p.startTime, p.zoneOffset, kg.toDouble(), p.dataSource?.appId)
        }
    }

    override suspend fun readSleep(from: LocalDate, to: LocalDate): List<ExternalSleep> = call {
        // Sessions are filtered by start; begin one day earlier so the first night is complete.
        readAll { token ->
            DataTypes.SLEEP.readDataRequestBuilder
                .setLocalTimeFilter(LocalTimeFilter.of(from.minusDays(1).atStartOfDay(), to.plusDays(1).atStartOfDay()))
                .setOrdering(Ordering.ASC)
                .apply { if (token != null) setPageToken(token) }
                .build()
        }.mapNotNull { p ->
            val end = p.endTime ?: return@mapNotNull null
            val asleep = p.getValue(DataType.SleepType.DURATION)
            val minutes = asleep?.toMinutes()?.toInt() ?: java.time.Duration.between(p.startTime, end).toMinutes().toInt()
            if (minutes <= 0) return@mapNotNull null
            ExternalSleep(p.uid, p.startTime, end, p.zoneOffset, minutes, p.dataSource?.appId)
        }
    }

    override suspend fun readExercises(from: LocalDate, to: LocalDate): List<ExternalExercise> = call {
        readAll { token ->
            DataTypes.EXERCISE.readDataRequestBuilder
                .setLocalTimeFilter(LocalTimeFilter.of(from.atStartOfDay(), to.plusDays(1).atStartOfDay()))
                .setOrdering(Ordering.ASC)
                .apply { if (token != null) setPageToken(token) }
                .build()
        }.mapNotNull { p ->
            val end = p.endTime ?: return@mapNotNull null
            val type = p.getValue(DataType.ExerciseType.EXERCISE_TYPE)
            val sessions = p.getValue(DataType.ExerciseType.SESSIONS).orEmpty()
            ExternalExercise(
                id = p.uid,
                start = p.startTime,
                end = end,
                zoneOffset = p.zoneOffset,
                exerciseType = type?.let { mapExerciseType(it.name) },
                title = p.getValue(DataType.ExerciseType.CUSTOM_TITLE)?.takeIf { it.isNotBlank() },
                kcal = sessions.sumOf { it.calories.toDouble() }.takeIf { it > 0 },
                sourceApp = p.dataSource?.appId,
            )
        }
    }

    override suspend fun readDaily(from: LocalDate, to: LocalDate): List<ExternalDaily> = call {
        val timeFilter = LocalTimeFilter.of(from.atStartOfDay(), to.plusDays(1).atStartOfDay())
        val dailyTime = LocalTimeGroup.of(LocalTimeGroupUnit.DAILY, 1)
        val dateFilter = LocalDateFilter.of(from, to.plusDays(1))
        val dailyDate = LocalDateGroup.of(LocalDateGroupUnit.DAILY, 1)

        val steps = optional {
            store.aggregateData(DataType.StepsType.TOTAL.requestBuilder.setLocalTimeFilterWithGroup(timeFilter, dailyTime).build())
                .dataList.associate { it.getStartLocalDateTime().toLocalDate() to it.value }
        }
        val active = optional {
            store.aggregateData(DataType.ActivitySummaryType.TOTAL_ACTIVE_CALORIES_BURNED.requestBuilder.setLocalTimeFilterWithGroup(timeFilter, dailyTime).build())
                .dataList.associate { it.getStartLocalDateTime().toLocalDate() to it.value }
        }
        val total = optional {
            store.aggregateData(DataType.ActivitySummaryType.TOTAL_CALORIES_BURNED.requestBuilder.setLocalTimeFilterWithGroup(timeFilter, dailyTime).build())
                .dataList.associate { it.getStartLocalDateTime().toLocalDate() to it.value }
        }
        val activeTime = optional {
            store.aggregateData(DataType.ActivitySummaryType.TOTAL_ACTIVE_TIME.requestBuilder.setLocalTimeFilterWithGroup(timeFilter, dailyTime).build())
                .dataList.associate { it.getStartLocalDateTime().toLocalDate() to it.value }
        }
        val hrMin = optional {
            store.aggregateData(DataType.HeartRateType.MIN.requestBuilder.setLocalDateFilterWithGroup(dateFilter, dailyDate).build())
                .dataList.associate { it.getStartLocalDateTime().toLocalDate() to it.value }
        }
        val hrMax = optional {
            store.aggregateData(DataType.HeartRateType.MAX.requestBuilder.setLocalDateFilterWithGroup(dateFilter, dailyDate).build())
                .dataList.associate { it.getStartLocalDateTime().toLocalDate() to it.value }
        }
        val hrToday = optional { latestHeartRate(to) }

        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
        days.mapNotNull { day ->
            val latest = hrToday?.takeIf { day == to }
            val daily = ExternalDaily(
                date = day,
                steps = steps?.get(day)?.takeIf { it > 0 },
                activeKcal = active?.get(day)?.toDouble()?.takeIf { it > 0 },
                totalKcal = total?.get(day)?.toDouble()?.takeIf { it > 0 },
                exerciseMinutes = activeTime?.get(day)?.toMinutes()?.toInt()?.takeIf { it > 0 },
                hrMin = hrMin?.get(day)?.roundToInt()?.takeIf { it > 0 },
                hrMax = hrMax?.get(day)?.roundToInt()?.takeIf { it > 0 },
                hrLatestBpm = latest?.first,
                hrLatestMillis = latest?.second,
            )
            daily.takeIf { it.steps != null || it.activeKcal != null || it.totalKcal != null || it.hrMin != null || it.hrLatestBpm != null }
        }
    }

    private suspend fun latestHeartRate(day: LocalDate): Pair<Int, Long>? {
        val response = store.readData(
            DataTypes.HEART_RATE.readDataRequestBuilder
                .setLocalTimeFilter(LocalTimeFilter.of(day.atStartOfDay(), day.plusDays(1).atStartOfDay()))
                .setOrdering(Ordering.DESC)
                .setLimit(1)
                .build(),
        )
        val point = response.dataList.firstOrNull() ?: return null
        val bpm: Float = point.getValue(DataType.HeartRateType.HEART_RATE) ?: return null
        return bpm.roundToInt() to (point.endTime ?: point.startTime).toEpochMilli()
    }

    private suspend fun readAll(build: (String?) -> ReadDataRequest<HealthDataPoint>): List<HealthDataPoint> {
        val result = mutableListOf<HealthDataPoint>()
        var token: String? = null
        var pages = 0
        do {
            val response = store.readData(build(token))
            result += response.dataList
            token = response.pageToken
            pages++
        } while (!token.isNullOrEmpty() && pages < MAX_PAGES)
        return result
    }

    /** A missing permission for one metric must not prevent reading the others. */
    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: HealthDataException) {
        if (e is ResolvablePlatformException) throw e
        null
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: HealthDataException) {
        throw SamsungHealthException(map(e), e.errorMessage ?: e.message, e is ResolvablePlatformException && e.hasResolution)
    }

    private fun map(e: HealthDataException): SamsungStatus {
        if (e is ResolvablePlatformException) lastResolvable = e
        return when (e.errorCode) {
            ErrorCode.ERR_PLATFORM_NOT_INSTALLED -> SamsungStatus.NOT_INSTALLED
            ErrorCode.ERR_OLD_VERSION_PLATFORM -> SamsungStatus.OLD_VERSION
            ErrorCode.ERR_PLATFORM_DISABLED -> SamsungStatus.DISABLED
            ErrorCode.ERR_PLATFORM_NOT_INITIALIZED -> SamsungStatus.NOT_INITIALIZED
            ErrorCode.ERR_INVALID_CALLER, ErrorCode.ERR_ACCESS_CONTROL, ErrorCode.ERR_INVALID_PLATFORM_SIGNATURE,
            ErrorCode.ERR_NO_USER_PERMISSION -> SamsungStatus.NOT_AUTHORIZED
            else -> if (e is AuthorizationException) SamsungStatus.NOT_AUTHORIZED else SamsungStatus.ERROR
        }
    }

    /** Maps Samsung exercise names onto Health Connect exercise type codes used across the app. */
    private fun mapExerciseType(name: String): Int = when (name) {
        "WALKING" -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        "RUNNING", "TRACK_RUNNING" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        "BIKING", "MOUNTAIN_BIKING" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        "STATIONARY_BIKING" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
        "POOL_SWIMMING" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
        "OPEN_WATER_SWIMMING" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER
        "YOGA" -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
        "PILATES" -> ExerciseSessionRecord.EXERCISE_TYPE_PILATES
        "HIKING" -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
        "ELLIPTICAL_TRAINER" -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
        "STAIR_CLIMBING", "STAIR_CLIMBING_MACHINE" -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING
        "ROWING_MACHINE" -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE
        "WEIGHT_MACHINE", "STRENGTH_TRAINING" -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        "DANCING" -> ExerciseSessionRecord.EXERCISE_TYPE_DANCING
        else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }

    private companion object {
        const val MAX_PAGES = 50
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SamsungHealthModule {
    @Binds
    abstract fun gateway(impl: SdkSamsungHealthGateway): SamsungHealthGateway
}
