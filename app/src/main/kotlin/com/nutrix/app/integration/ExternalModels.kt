package com.nutrix.app.integration

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Platform-neutral records read from Health Connect or Samsung Health. */
data class ExternalWeight(
    val id: String,
    val time: Instant,
    val zoneOffset: ZoneOffset?,
    val kg: Double,
    val sourceApp: String?,
)

data class ExternalSleep(
    val id: String,
    val start: Instant,
    val end: Instant,
    val endZoneOffset: ZoneOffset?,
    /** Asleep time; awake periods inside the session are excluded when stage data allows. */
    val durationMinutes: Int,
    val sourceApp: String?,
)

data class ExternalExercise(
    val id: String,
    val start: Instant,
    val end: Instant,
    val zoneOffset: ZoneOffset?,
    val exerciseType: Int?,
    val title: String?,
    val kcal: Double?,
    val sourceApp: String?,
)

data class ExternalDaily(
    val date: LocalDate,
    val steps: Long? = null,
    val activeKcal: Double? = null,
    val totalKcal: Double? = null,
    val exerciseMinutes: Int? = null,
    val hrMin: Int? = null,
    val hrMax: Int? = null,
    val hrAvg: Int? = null,
    val hrLatestBpm: Int? = null,
    val hrLatestMillis: Long? = null,
)

/** Zone used to store an imported record: the offset it was recorded with, else the device zone. */
fun zoneFor(offset: ZoneOffset?): ZoneId = offset ?: ZoneId.systemDefault()

/** Summary of one synchronisation run. */
data class SyncReport(
    val upserted: Int = 0,
    val deleted: Int = 0,
    val days: Int = 0,
    val error: SyncError? = null,
) {
    operator fun plus(o: SyncReport) = SyncReport(upserted + o.upserted, deleted + o.deleted, maxOf(days, o.days), error ?: o.error)
}

enum class SyncError {
    NOT_AVAILABLE,
    NO_PERMISSIONS,
    PERMISSION_REVOKED,
    BACKGROUND_NOT_ALLOWED,
    PLATFORM_ERROR,
    SDK_NOT_BUNDLED,
    DISABLED,
}
