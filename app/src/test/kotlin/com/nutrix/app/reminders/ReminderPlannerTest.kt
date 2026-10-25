package com.nutrix.app.reminders

import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.datastore.ReminderConfig
import com.nutrix.app.core.datastore.ReminderMode
import com.nutrix.app.core.datastore.ReminderType
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderPlannerTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val quietOff = QuietHours(false, 0, 0)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): ZonedDateTime = ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), zone)

    @Test
    fun `next fixed time later today`() {
        val config = ReminderConfig(ReminderType.MEAL, enabled = true, times = listOf(9 * 60, 13 * 60 + 30, 19 * 60))
        val next = ReminderPlanner.nextTrigger(config, quietOff, at(2026, 10, 26, 10, 0))
        assertThat(next).isEqualTo(at(2026, 10, 26, 13, 30))
    }

    @Test
    fun `rolls over to tomorrow after the last time`() {
        val config = ReminderConfig(ReminderType.MEAL, enabled = true, times = listOf(9 * 60))
        assertThat(ReminderPlanner.nextTrigger(config, quietOff, at(2026, 10, 26, 20, 0))).isEqualTo(at(2026, 10, 27, 9, 0))
    }

    @Test
    fun `respects selected weekdays`() {
        // 2026-10-30 is a Friday; only Mondays selected.
        val config = ReminderConfig(ReminderType.WEIGHT, enabled = true, times = listOf(8 * 60), days = setOf(DayOfWeek.MONDAY.value))
        val next = ReminderPlanner.nextTrigger(config, quietOff, at(2026, 10, 30, 7, 0))!!
        assertThat(next.dayOfWeek).isEqualTo(DayOfWeek.MONDAY)
        assertThat(next.toLocalDate().toString()).isEqualTo("2026-11-02")
    }

    @Test
    fun `interval reminders skip quiet hours`() {
        val config = ReminderConfig(ReminderType.WATER, enabled = true, mode = ReminderMode.INTERVAL, intervalMinutes = 120, windowStartMinutes = 8 * 60, windowEndMinutes = 22 * 60)
        val quiet = QuietHours(true, 21 * 60, 9 * 60)
        val times = ReminderPlanner.intervalTimes(config)
        assertThat(times).containsExactly(480, 600, 720, 840, 960, 1080, 1200, 1320).inOrder()
        // At 20:30 the 22:00 slot is inside quiet hours, the 08:00 slot too; next is 10:00 tomorrow.
        assertThat(ReminderPlanner.nextTrigger(config, quiet, at(2026, 10, 26, 20, 30))).isEqualTo(at(2026, 10, 27, 10, 0))
    }

    @Test
    fun `disabled reminder has no trigger`() {
        val config = ReminderConfig(ReminderType.MEAL, enabled = false, times = listOf(600))
        assertThat(ReminderPlanner.nextTrigger(config, quietOff, at(2026, 10, 26, 8, 0))).isNull()
    }

    @Test
    fun `quiet window wrapping midnight`() {
        val quiet = QuietHours(true, 22 * 60 + 30, 8 * 60)
        assertThat(quiet.contains(23 * 60)).isTrue()
        assertThat(quiet.contains(3 * 60)).isTrue()
        assertThat(quiet.contains(8 * 60)).isFalse()
        assertThat(quiet.contains(12 * 60)).isFalse()
    }

    @Test
    fun `snooze into quiet hours moves to end of quiet window`() {
        val quiet = QuietHours(true, 22 * 60 + 30, 8 * 60)
        assertThat(ReminderPlanner.adjustForQuiet(at(2026, 10, 26, 23, 0), quiet)).isEqualTo(at(2026, 10, 27, 8, 0))
        assertThat(ReminderPlanner.adjustForQuiet(at(2026, 10, 27, 2, 0), quiet)).isEqualTo(at(2026, 10, 27, 8, 0))
        assertThat(ReminderPlanner.adjustForQuiet(at(2026, 10, 27, 12, 0), quiet)).isEqualTo(at(2026, 10, 27, 12, 0))
    }
}
