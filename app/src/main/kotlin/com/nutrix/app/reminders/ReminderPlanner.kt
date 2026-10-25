package com.nutrix.app.reminders

import com.nutrix.app.core.datastore.ReminderConfig
import com.nutrix.app.core.datastore.ReminderMode
import java.time.ZonedDateTime

data class QuietHours(val enabled: Boolean, val startMinutes: Int, val endMinutes: Int) {
    /** Window may wrap around midnight (e.g. 22:30–08:00). */
    fun contains(minuteOfDay: Int): Boolean {
        if (!enabled || startMinutes == endMinutes) return false
        return if (startMinutes < endMinutes) minuteOfDay in startMinutes until endMinutes
        else minuteOfDay >= startMinutes || minuteOfDay < endMinutes
    }
}

/** Computes when a reminder should fire next. Pure logic, unit-tested. */
object ReminderPlanner {
    private const val LOOKAHEAD_DAYS = 8L

    fun nextTrigger(config: ReminderConfig, quiet: QuietHours, now: ZonedDateTime): ZonedDateTime? {
        if (!config.enabled || config.days.isEmpty()) return null
        val minutesList = when (config.mode) {
            ReminderMode.FIXED_TIMES -> config.times.sorted()
            ReminderMode.INTERVAL -> intervalTimes(config)
        }.filter { !quiet.contains(it) }
        if (minutesList.isEmpty()) return null
        val today = now.toLocalDate()
        for (offset in 0..LOOKAHEAD_DAYS) {
            val date = today.plusDays(offset)
            if (date.dayOfWeek.value !in config.days) continue
            for (m in minutesList) {
                val candidate = date.atStartOfDay(now.zone).plusMinutes(m.toLong())
                if (candidate.isAfter(now.plusSeconds(30))) return candidate
            }
        }
        return null
    }

    fun intervalTimes(config: ReminderConfig): List<Int> {
        val interval = config.intervalMinutes.coerceAtLeast(15)
        val start = config.windowStartMinutes
        val end = config.windowEndMinutes
        if (end <= start) return listOf(start)
        return generateSequence(start) { it + interval }.takeWhile { it <= end }.toList()
    }

    /** Moves a snoozed reminder out of quiet hours to the end of the quiet window. */
    fun adjustForQuiet(time: ZonedDateTime, quiet: QuietHours): ZonedDateTime {
        val minute = time.hour * 60 + time.minute
        if (!quiet.contains(minute)) return time
        var end = time.toLocalDate().atStartOfDay(time.zone).plusMinutes(quiet.endMinutes.toLong())
        if (!end.isAfter(time)) end = end.plusDays(1)
        return end
    }
}
