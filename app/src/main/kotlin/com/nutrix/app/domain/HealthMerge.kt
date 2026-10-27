package com.nutrix.app.domain

import com.nutrix.app.core.model.DataSource
import com.nutrix.app.database.entity.ActivityDailyEntity
import com.nutrix.app.database.entity.SleepEntity
import com.nutrix.app.database.entity.ExerciseEntity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Activity of one day after choosing, per metric, the highest-priority source that has it. */
@kotlinx.serialization.Serializable
data class DailyActivity(
    val localDate: Long,
    val steps: Long? = null,
    val stepsSource: DataSource? = null,
    val activeKcal: Double? = null,
    val totalKcal: Double? = null,
    val exerciseMinutes: Int? = null,
    val hrMin: Int? = null,
    val hrMax: Int? = null,
    val hrAvg: Int? = null,
    val hrLatestBpm: Int? = null,
    val hrLatestMillis: Long? = null,
    val hrSource: DataSource? = null,
    val updatedAt: Long? = null,
)

object ActivityMerger {
    fun merge(rows: List<ActivityDailyEntity>, priority: List<DataSource>): List<DailyActivity> =
        rows.groupBy { it.localDate }.map { (day, list) -> mergeDay(day, list, priority) }.sortedBy { it.localDate }

    fun mergeDay(day: Long, rows: List<ActivityDailyEntity>, priority: List<DataSource>): DailyActivity {
        val ordered = rows.sortedBy { rank(it.source, priority) }
        val stepsRow = ordered.firstOrNull { it.steps != null }
        val hrRow = ordered.firstOrNull { it.hrAvg != null || it.hrLatestBpm != null }
        return DailyActivity(
            localDate = day,
            steps = stepsRow?.steps,
            stepsSource = stepsRow?.source,
            activeKcal = ordered.firstNotNullOfOrNull { it.activeKcal },
            totalKcal = ordered.firstNotNullOfOrNull { it.totalKcal },
            exerciseMinutes = ordered.firstNotNullOfOrNull { it.exerciseMinutes },
            hrMin = hrRow?.hrMin,
            hrMax = hrRow?.hrMax,
            hrAvg = hrRow?.hrAvg,
            hrLatestBpm = hrRow?.hrLatestBpm,
            hrLatestMillis = hrRow?.hrLatestMillis,
            hrSource = hrRow?.source,
            updatedAt = rows.maxOfOrNull { it.updatedAt },
        )
    }

    private fun rank(source: DataSource, priority: List<DataSource>): Int =
        if (source == DataSource.MANUAL) -1 else priority.indexOf(source).let { if (it < 0) priority.size else it }
}

/** Only equivalent sessions are duplicates: a nap inside a longer session is still a nap. */
object SleepDeduplicator {
    fun dedupe(sessions: List<SleepEntity>, priority: List<DataSource>): List<SleepEntity> {
        val ordered = sessions.sortedWith(compareBy<SleepEntity> { rank(it.source, priority) }.thenBy { it.startMillis })
        val kept = mutableListOf<SleepEntity>()
        for (s in ordered) {
            val duplicate = s.source != DataSource.MANUAL && kept.any { k ->
                k.source != s.source && overlapRatio(k, s) >= 0.8
            }
            if (!duplicate) kept += s
        }
        return kept.sortedBy { it.endMillis }
    }

    fun overlapRatio(a: SleepEntity, b: SleepEntity): Double {
        val overlap = min(a.endMillis, b.endMillis) - max(a.startMillis, b.startMillis)
        if (overlap <= 0) return 0.0
        val longer = max(a.endMillis - a.startMillis, b.endMillis - b.startMillis).coerceAtLeast(1)
        return overlap.toDouble() / longer
    }

    private fun rank(source: DataSource, priority: List<DataSource>): Int =
        if (source == DataSource.MANUAL) -1 else priority.indexOf(source).let { if (it < 0) priority.size else it }
}

/** Collapse cross-source copies at read time; keep raw imports for resync and priority changes. */
object ExerciseDeduplicator {
    fun dedupe(sessions: List<ExerciseEntity>, priority: List<DataSource>): List<ExerciseEntity> {
        val ordered = sessions.sortedWith(compareBy<ExerciseEntity> {
            if (it.source == DataSource.MANUAL) -1 else priority.indexOf(it.source).let { i -> if (i < 0) priority.size else i }
        }.thenBy { it.startMillis }.thenBy { it.id })
        val groups = mutableListOf<MutableList<ExerciseEntity>>()
        for (session in ordered) {
            // One-to-one matching: do not absorb two distinct sessions from the same source.
            val group = groups.firstOrNull { copies ->
                copies.none { it.source == session.source } && copies.all { sameSession(it, session) }
            }
            if (group == null) groups += mutableListOf(session) else group += session
        }
        return groups.map { copies ->
            val preferred = copies.first()
            preferred.copy(
                title = copies.firstNotNullOfOrNull { it.title?.takeIf(String::isNotBlank) },
                exerciseType = copies.firstNotNullOfOrNull { it.exerciseType },
                kcal = copies.firstNotNullOfOrNull { it.kcal },
            )
        }.sortedBy { it.startMillis }
    }

    private fun sameSession(a: ExerciseEntity, b: ExerciseEntity): Boolean {
        if (a.source == DataSource.MANUAL || b.source == DataSource.MANUAL) return false
        if (a.exerciseType != null && b.exerciseType != null && a.exerciseType != b.exerciseType) return false
        val duration = max(a.endMillis - a.startMillis, b.endMillis - b.startMillis)
        if (duration <= 0) return false
        val overlap = min(a.endMillis, b.endMillis) - max(a.startMillis, b.startMillis)
        return abs(a.startMillis - b.startMillis) <= 120_000 &&
            abs(a.endMillis - b.endMillis) <= 120_000 && overlap.toDouble() / duration >= 0.8
    }
}

object SleepTotals {
    /** Sum actual sleep minutes, counting overlapping time once. Call with one wake-up day. */
    fun minutes(sessions: List<SleepEntity>): Int {
        val valid = sessions.filter { it.endMillis > it.startMillis && it.durationMinutes > 0 }
        val boundaries = valid.flatMap { listOf(it.startMillis, it.endMillis) }.distinct().sorted()
        val millis = boundaries.zipWithNext().sumOf { (start, end) ->
            val fraction = valid.filter { it.startMillis <= start && it.endMillis >= end }.maxOfOrNull {
                (it.durationMinutes * 60_000.0 / (it.endMillis - it.startMillis)).coerceAtMost(1.0)
            } ?: 0.0
            (end - start) * fraction
        }
        return kotlin.math.round(millis / 60_000).toInt()
    }
}
