package com.nutrix.app.integration

import com.google.common.truth.Truth.assertThat
import com.nutrix.app.integration.sync.SyncWindow
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SyncWindowTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.of(2026, 10, 26)
    private fun millis(day: LocalDate) = day.atTime(23, 30).atZone(zone).toInstant().toEpochMilli()

    @Test fun fillsMonthLongGap() {
        assertThat(SyncWindow.start(today, millis(today.minusDays(40)), true, 365, zone = zone))
            .isEqualTo(today.minusDays(41))
    }

    @Test fun retainsOverlapAfterRecentSuccess() {
        assertThat(SyncWindow.start(today, millis(today.minusDays(1)), true, 30, zone = zone))
            .isEqualTo(today.minusDays(7))
    }

    @Test fun neverReadsBeforePermittedHistory() {
        assertThat(SyncWindow.start(today, millis(today.minusDays(100)), true, 30, zone = zone))
            .isEqualTo(today.minusDays(30))
    }

    @Test fun missingStateUsesFullWindowAndFutureClockStillHasOverlap() {
        assertThat(SyncWindow.start(today, null, true, 365, zone = zone)).isEqualTo(today.minusDays(365))
        assertThat(SyncWindow.start(today, millis(today.plusDays(2)), true, 365, zone = zone)).isEqualTo(today.minusDays(7))
    }
}
