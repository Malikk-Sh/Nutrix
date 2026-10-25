package com.nutrix.app.integration.sync

import com.nutrix.app.core.model.DataSource
import com.nutrix.app.data.repository.HealthRepository
import com.nutrix.app.data.repository.WeightRepository
import com.nutrix.app.database.entity.ActivityDailyEntity
import com.nutrix.app.database.entity.ExerciseEntity
import com.nutrix.app.database.entity.SleepEntity
import com.nutrix.app.database.entity.WeightEntity
import com.nutrix.app.integration.ExternalDaily
import com.nutrix.app.integration.ExternalExercise
import com.nutrix.app.integration.ExternalSleep
import com.nutrix.app.integration.ExternalWeight
import com.nutrix.app.integration.zoneFor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores records from health platforms. Every row keeps its source and the external record id,
 * so re-imports update instead of duplicating and remote deletions can be mirrored.
 */
@Singleton
class ExternalImporter @Inject constructor(
    private val weights: WeightRepository,
    private val health: HealthRepository,
) {
    suspend fun importWeights(source: DataSource, items: List<ExternalWeight>): Int = items.count { w ->
        val zone = zoneFor(w.zoneOffset)
        weights.upsertExternal(
            WeightEntity(
                epochMillis = w.time.toEpochMilli(),
                zoneId = zone.id,
                localDate = w.time.atZone(zone).toLocalDate().toEpochDay(),
                weightKg = w.kg,
                source = source,
                externalId = w.id,
                sourceApp = w.sourceApp,
            ),
        )
    }

    suspend fun importSleep(source: DataSource, items: List<ExternalSleep>): Int = items.count { s ->
        val zone = zoneFor(s.endZoneOffset)
        health.upsertExternalSleep(
            SleepEntity(
                startMillis = s.start.toEpochMilli(),
                endMillis = s.end.toEpochMilli(),
                zoneId = zone.id,
                localDate = s.end.atZone(zone).toLocalDate().toEpochDay(),
                durationMinutes = s.durationMinutes,
                source = source,
                externalId = s.id,
                sourceApp = s.sourceApp,
            ),
        )
    }

    suspend fun importExercises(source: DataSource, items: List<ExternalExercise>): Int = items.count { e ->
        val zone = zoneFor(e.zoneOffset)
        health.upsertExternalExercise(
            ExerciseEntity(
                startMillis = e.start.toEpochMilli(),
                endMillis = e.end.toEpochMilli(),
                zoneId = zone.id,
                localDate = e.start.atZone(zone).toLocalDate().toEpochDay(),
                exerciseType = e.exerciseType,
                title = e.title,
                kcal = e.kcal,
                source = source,
                externalId = e.id,
                sourceApp = e.sourceApp,
            ),
        )
    }

    suspend fun importDaily(source: DataSource, items: List<ExternalDaily>): Int {
        items.forEach { d ->
            val day = d.date.toEpochDay()
            val previous = health.getActivity(day, source)
            health.upsertActivity(
                ActivityDailyEntity(
                    localDate = day,
                    source = source,
                    steps = d.steps,
                    activeKcal = d.activeKcal,
                    totalKcal = d.totalKcal,
                    exerciseMinutes = d.exerciseMinutes,
                    hrMin = d.hrMin,
                    hrMax = d.hrMax,
                    hrAvg = d.hrAvg,
                    // Keep a previously seen "latest" reading when today's sync has none.
                    hrLatestBpm = d.hrLatestBpm ?: previous?.hrLatestBpm,
                    hrLatestMillis = d.hrLatestMillis ?: previous?.hrLatestMillis,
                ),
            )
        }
        return items.size
    }

    /** Mirrors deletions for sources without a change feed: drop local rows missing remotely. */
    suspend fun reconcileWeights(source: DataSource, fromMillis: Long, toMillis: Long, remoteIds: Set<String>): Int =
        weights.externalIdsBetween(source, fromMillis, toMillis).filter { it !in remoteIds }
            .count { weights.deleteExternal(source, it) }

    suspend fun reconcileSleep(source: DataSource, fromMillis: Long, toMillis: Long, remoteIds: Set<String>): Int =
        health.sleepExternalIdsBetween(source, fromMillis, toMillis).filter { it !in remoteIds }
            .count { health.deleteExternalSleep(source, it) }

    suspend fun reconcileExercises(source: DataSource, fromMillis: Long, toMillis: Long, remoteIds: Set<String>): Int =
        health.exerciseExternalIdsBetween(source, fromMillis, toMillis).filter { it !in remoteIds }
            .count { health.deleteExternalExercise(source, it) }

    suspend fun deleteById(source: DataSource, externalId: String): Boolean =
        weights.deleteExternal(source, externalId) or health.deleteExternalSleep(source, externalId) or
            health.deleteExternalExercise(source, externalId)

    fun notifyChanged() = health.notifyChanged()
}
