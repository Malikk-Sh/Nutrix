package com.nutrix.app.domain

import com.nutrix.app.core.model.DataSource
import com.nutrix.app.database.entity.ActivityDailyEntity
import com.nutrix.app.database.entity.SleepEntity
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
        val stepsRow = ordered.firstOrNull { it.steps != null && it.steps > 0 }
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

/** Collapses the same night reported by several sources (overlap of more than half). */
object SleepDeduplicator {
    fun dedupe(sessions: List<SleepEntity>, priority: List<DataSource>): List<SleepEntity> {
        val ordered = sessions.sortedWith(compareBy<SleepEntity> { rank(it.source, priority) }.thenBy { it.startMillis })
        val kept = mutableListOf<SleepEntity>()
        for (s in ordered) {
            val duplicate = kept.any { k -> k.source != s.source && overlapRatio(k, s) > 0.5 }
            if (!duplicate) kept += s
        }
        return kept.sortedBy { it.endMillis }
    }

    fun overlapRatio(a: SleepEntity, b: SleepEntity): Double {
        val overlap = min(a.endMillis, b.endMillis) - max(a.startMillis, b.startMillis)
        if (overlap <= 0) return 0.0
        val shorter = min(a.endMillis - a.startMillis, b.endMillis - b.startMillis).coerceAtLeast(1)
        return overlap.toDouble() / shorter
    }

    private fun rank(source: DataSource, priority: List<DataSource>): Int =
        if (source == DataSource.MANUAL) -1 else priority.indexOf(source).let { if (it < 0) priority.size else it }
}
