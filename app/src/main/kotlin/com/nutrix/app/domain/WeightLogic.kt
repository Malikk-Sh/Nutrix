package com.nutrix.app.domain

import com.nutrix.app.core.model.DataSource
import com.nutrix.app.database.entity.WeightEntity
import kotlin.math.abs

/**
 * Removes measurements that are the same weighing seen through several sources
 * (e.g. a smart scale writing to Samsung Health, which is also exposed via Health Connect,
 * plus the value typed by hand). Manual entries always win, otherwise the user's source
 * priority decides. All rows stay in the database; this only affects what is displayed.
 */
object WeightDeduplicator {
    const val WINDOW_MILLIS = 10 * 60 * 1000L
    const val TOLERANCE_KG = 0.15

    fun dedupe(records: List<WeightEntity>, priority: List<DataSource>): List<WeightEntity> {
        if (records.size < 2) return records
        val sorted = records.sortedBy { it.epochMillis }
        val result = mutableListOf<WeightEntity>()
        val cluster = mutableListOf<WeightEntity>()
        fun flush() {
            if (cluster.isNotEmpty()) {
                result += cluster.minWith(compareBy<WeightEntity> { rank(it.source, priority) }.thenByDescending { it.epochMillis })
                cluster.clear()
            }
        }
        for (record in sorted) {
            val anchor = cluster.firstOrNull()
            val sameWeighing = anchor != null &&
                record.epochMillis - anchor.epochMillis <= WINDOW_MILLIS &&
                abs(record.weightKg - anchor.weightKg) <= TOLERANCE_KG &&
                cluster.none { it.source == record.source }
            if (!sameWeighing) flush()
            cluster += record
        }
        flush()
        return result
    }

    private fun rank(source: DataSource, priority: List<DataSource>): Int =
        if (source == DataSource.MANUAL) -1 else priority.indexOf(source).let { if (it < 0) priority.size else it }

    /** A manual entry close in time and value to an existing one is likely a double tap or re-entry. */
    fun findLikelyDuplicate(existing: List<WeightEntity>, epochMillis: Long, weightKg: Double, ignoreId: Long = 0): WeightEntity? =
        existing.firstOrNull {
            it.id != ignoreId && abs(it.epochMillis - epochMillis) <= 30 * 60 * 1000L && abs(it.weightKg - weightKg) < 0.05
        }
}

data class WeightStats(
    val latest: WeightEntity?,
    val weekChangeKg: Double?,
    val monthChangeKg: Double?,
    val avg7Kg: Double?,
    val minKg: Double?,
    val maxKg: Double?,
    val toGoalKg: Double?,
)

object WeightStatsCalculator {
    private const val DAY = 24 * 60 * 60 * 1000L

    /** [records] must already be de-duplicated. */
    fun compute(records: List<WeightEntity>, targetKg: Double?, nowMillis: Long = System.currentTimeMillis()): WeightStats {
        if (records.isEmpty()) return WeightStats(null, null, null, null, null, null, null)
        val sorted = records.sortedBy { it.epochMillis }
        val latest = sorted.last()
        val week = changeOver(sorted, latest, 7)
        val month = changeOver(sorted, latest, 30)
        val last7 = sorted.filter { it.epochMillis >= nowMillis - 7 * DAY }
        val avg7 = if (last7.isEmpty()) null else {
            // Average of per-day means so that a day with several weighings does not dominate.
            last7.groupBy { it.localDate }.values.map { day -> day.map { it.weightKg }.average() }.average()
        }
        return WeightStats(
            latest = latest,
            weekChangeKg = week,
            monthChangeKg = month,
            avg7Kg = avg7,
            minKg = sorted.minOf { it.weightKg },
            maxKg = sorted.maxOf { it.weightKg },
            toGoalKg = targetKg?.let { latest.weightKg - it },
        )
    }

    /**
     * Change between the latest value and the measurement closest to [days] days earlier
     * (accepting anything from days/2 to days*1.5 back). Null when there is no such reference.
     */
    fun changeOver(sorted: List<WeightEntity>, latest: WeightEntity, days: Int): Double? {
        val target = latest.epochMillis - days * DAY
        val earliestAllowed = latest.epochMillis - (days * 1.5 * DAY).toLong()
        val latestAllowed = latest.epochMillis - (days / 2.0 * DAY).toLong()
        val reference = sorted.filter { it.epochMillis in earliestAllowed..latestAllowed }
            .minByOrNull { abs(it.epochMillis - target) } ?: return null
        return latest.weightKg - reference.weightKg
    }
}
