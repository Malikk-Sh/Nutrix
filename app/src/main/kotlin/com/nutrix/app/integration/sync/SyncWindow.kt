package com.nutrix.app.integration.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Revisit recent days and fill the entire gap since the last successful import. */
object SyncWindow {
    fun start(today: LocalDate, lastSuccessMillis: Long?, initialDone: Boolean, historyDays: Long,
        overlapDays: Long = 7, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
        val earliest = today.minusDays(historyDays)
        if (!initialDone || lastSuccessMillis == null) return earliest
        val lastDay = Instant.ofEpochMilli(lastSuccessMillis).atZone(zone).toLocalDate().minusDays(1)
        return minOf(today.minusDays(overlapDays), lastDay).coerceAtLeast(earliest)
    }
}
