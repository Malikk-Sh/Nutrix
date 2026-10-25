package com.nutrix.app.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Every diary record stores the absolute instant (epoch millis) together with the zone it was
 * recorded in and the local calendar day in that zone. This keeps a meal eaten at 23:30 in
 * Moscow on the same day even when the phone later travels to another time zone.
 */
data class RecordTime(val epochMillis: Long, val zoneId: String) {
    val zone: ZoneId get() = TimeUtils.zoneOrDefault(zoneId)
    val zoned: ZonedDateTime get() = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val localDate: LocalDate get() = zoned.toLocalDate()
    val epochDay: Long get() = localDate.toEpochDay()
    val localTime: LocalTime get() = zoned.toLocalTime()

    /** True when the record was made in a zone with a different offset than the current one. */
    fun isForeignZone(current: ZoneId = ZoneId.systemDefault()): Boolean {
        val instant = Instant.ofEpochMilli(epochMillis)
        return zone.rules.getOffset(instant) != current.rules.getOffset(instant)
    }

    companion object {
        fun now(clock: () -> Long = System::currentTimeMillis): RecordTime =
            RecordTime(clock(), ZoneId.systemDefault().id)

        fun of(date: LocalDate, time: LocalTime, zone: ZoneId = ZoneId.systemDefault()): RecordTime =
            RecordTime(ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli(), zone.id)
    }
}

object TimeUtils {
    fun zoneOrDefault(id: String?): ZoneId =
        runCatching { if (id.isNullOrBlank()) ZoneId.systemDefault() else ZoneId.of(id) }
            .getOrDefault(ZoneId.systemDefault())

    fun epochDay(epochMillis: Long, zoneId: String): Long =
        Instant.ofEpochMilli(epochMillis).atZone(zoneOrDefault(zoneId)).toLocalDate().toEpochDay()

    fun today(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.now(zone)

    fun todayEpochDay(zone: ZoneId = ZoneId.systemDefault()): Long = today(zone).toEpochDay()

    /** [start, end) millis of a calendar day in the given zone. */
    fun dayBounds(epochDay: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val date = LocalDate.ofEpochDay(epochDay)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    fun localDateTime(epochMillis: Long, zoneId: String): LocalDateTime =
        Instant.ofEpochMilli(epochMillis).atZone(zoneOrDefault(zoneId)).toLocalDateTime()

    /**
     * Minutes of a bedtime relative to 18:00 so that times around midnight can be averaged:
     * 22:00 -> 240, 01:30 -> 450.
     */
    fun bedtimeMinutesFromEvening(time: LocalTime): Int {
        val minutes = time.hour * 60 + time.minute
        val shifted = minutes - 18 * 60
        return if (shifted < 0) shifted + 24 * 60 else shifted
    }

    fun eveningMinutesToTime(minutes: Int): LocalTime {
        val total = ((minutes + 18 * 60) % (24 * 60) + 24 * 60) % (24 * 60)
        return LocalTime.of(total / 60, total % 60)
    }

    fun minutesOfDay(time: LocalTime): Int = time.hour * 60 + time.minute

    fun timeOfMinutes(minutes: Int): LocalTime {
        val m = ((minutes % 1440) + 1440) % 1440
        return LocalTime.of(m / 60, m % 60)
    }
}
