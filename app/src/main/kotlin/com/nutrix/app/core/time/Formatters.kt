package com.nutrix.app.core.time

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Locale used for dates and numbers. It follows the language of the bundled UI strings
 * (R.string.locale_tag) rather than the device locale, so dates and labels never mix languages.
 */
object AppLocale {
    @Volatile
    var locale: Locale = Locale.forLanguageTag("ru")
        private set

    fun init(tag: String) {
        locale = Locale.forLanguageTag(tag)
    }
}

object Formatters {
    private fun pattern(p: String): DateTimeFormatter = DateTimeFormatter.ofPattern(p, AppLocale.locale)

    fun time(time: LocalTime): String = pattern("HH:mm").format(time)

    fun time(record: RecordTime, current: ZoneId = ZoneId.systemDefault()): String {
        val base = time(record.localTime)
        return if (record.isForeignZone(current)) {
            val offset = record.zone.rules.getOffset(Instant.ofEpochMilli(record.epochMillis))
            "$base (GMT${if (offset.id == "Z") "+0" else offset.id})"
        } else base
    }

    fun time(epochMillis: Long, zoneId: String): String = time(RecordTime(epochMillis, zoneId))

    /** "25 октября" */
    fun dayMonth(date: LocalDate): String = pattern("d MMMM").format(date)

    /** "25 окт." */
    fun dayMonthShort(date: LocalDate): String = pattern("d MMM").format(date)

    /** "25.10.2026" */
    fun numeric(date: LocalDate): String = pattern("dd.MM.yyyy").format(date)

    /** "25 октября 2026" */
    fun fullDate(date: LocalDate): String = pattern("d MMMM yyyy").format(date)

    /** "суббота, 25 октября" */
    fun weekdayDayMonth(date: LocalDate): String =
        "${date.dayOfWeek.getDisplayName(TextStyle.FULL, AppLocale.locale)}, ${dayMonth(date)}"

    /** "Октябрь 2026" */
    fun monthYear(date: LocalDate): String =
        pattern("LLLL yyyy").format(date).replaceFirstChar { it.titlecase(AppLocale.locale) }

    fun weekdayShort(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, AppLocale.locale)

    fun dateTime(epochMillis: Long, zoneId: String): String {
        val rt = RecordTime(epochMillis, zoneId)
        return "${numeric(rt.localDate)} ${time(rt)}"
    }

    /** "7 ч 25 мин" style compact duration; unit suffixes are passed from resources. */
    fun duration(minutes: Long, hourSuffix: String, minuteSuffix: String): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "$h $hourSuffix $m $minuteSuffix"
            h > 0 -> "$h $hourSuffix"
            else -> "$m $minuteSuffix"
        }
    }

    fun duration(d: Duration, hourSuffix: String, minuteSuffix: String): String =
        duration(d.toMinutes(), hourSuffix, minuteSuffix)

    fun decimal(value: Double, digits: Int = 1): String =
        String.format(AppLocale.locale, "%.${digits}f", value)

    fun signedDecimal(value: Double, digits: Int = 1): String {
        val s = decimal(kotlin.math.abs(value), digits)
        return when {
            value > 0.0005 -> "+$s"
            value < -0.0005 -> "−$s"
            else -> s
        }
    }

    fun integer(value: Long): String = String.format(AppLocale.locale, "%,d", value)
}
