package com.nutrix.app.data.repository

import com.nutrix.app.core.model.EntryCategory
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.ExerciseEntity
import com.nutrix.app.database.entity.MealWithPhotos
import com.nutrix.app.database.entity.NoteEntity
import com.nutrix.app.database.entity.SleepEntity
import com.nutrix.app.database.entity.SymptomEntity
import com.nutrix.app.database.entity.WeightEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TimelineItem {
    val key: String
    val epochMillis: Long
    val zoneId: String
    val category: EntryCategory

    data class Meal(val meal: MealWithPhotos) : TimelineItem {
        override val key get() = "meal-${meal.meal.id}"
        override val epochMillis get() = meal.meal.epochMillis
        override val zoneId get() = meal.meal.zoneId
        override val category get() = EntryCategory.MEAL
    }

    data class Drink(val drink: DrinkEntity) : TimelineItem {
        override val key get() = "drink-${drink.id}"
        override val epochMillis get() = drink.epochMillis
        override val zoneId get() = drink.zoneId
        override val category get() = EntryCategory.DRINK
    }

    data class Weight(val weight: WeightEntity) : TimelineItem {
        override val key get() = "weight-${weight.id}"
        override val epochMillis get() = weight.epochMillis
        override val zoneId get() = weight.zoneId
        override val category get() = EntryCategory.WEIGHT
    }

    data class Symptom(val symptom: SymptomEntity) : TimelineItem {
        override val key get() = "symptom-${symptom.id}"
        override val epochMillis get() = symptom.epochMillis
        override val zoneId get() = symptom.zoneId
        override val category get() = EntryCategory.SYMPTOM
    }

    data class FellAsleep(val sleep: SleepEntity) : TimelineItem {
        override val key get() = "sleep-start-${sleep.id}"
        override val epochMillis get() = sleep.startMillis
        override val zoneId get() = sleep.zoneId
        override val category get() = EntryCategory.SLEEP
    }

    data class WokeUp(val sleep: SleepEntity) : TimelineItem {
        override val key get() = "sleep-end-${sleep.id}"
        override val epochMillis get() = sleep.endMillis
        override val zoneId get() = sleep.zoneId
        override val category get() = EntryCategory.SLEEP
    }

    data class Note(val note: NoteEntity) : TimelineItem {
        override val key get() = "note-${note.id}"
        override val epochMillis get() = note.epochMillis
        override val zoneId get() = note.zoneId
        override val category get() = EntryCategory.NOTE
    }

    data class Exercise(val exercise: ExerciseEntity) : TimelineItem {
        override val key get() = "exercise-${exercise.id}"
        override val epochMillis get() = exercise.startMillis
        override val zoneId get() = exercise.zoneId
        override val category get() = EntryCategory.ACTIVITY
    }
}

@Singleton
class TimelineRepository @Inject constructor(
    private val meals: MealRepository,
    private val drinks: DrinkRepository,
    private val weights: WeightRepository,
    private val symptoms: SymptomRepository,
    private val health: HealthRepository,
    private val notes: NoteRepository,
) {
    /** All events of a calendar day sorted by time. */
    fun observeDay(day: Long): Flow<List<TimelineItem>> {
        val diary = combine(
            meals.observeDay(day),
            drinks.observeDay(day),
            weights.observeDay(day),
            symptoms.observeDay(day),
            notes.observeDay(day),
        ) { m, d, w, s, n ->
            m.map { TimelineItem.Meal(it) } + d.map { TimelineItem.Drink(it) } + w.map { TimelineItem.Weight(it) } +
                s.map { TimelineItem.Symptom(it) } + n.map { TimelineItem.Note(it) }
        }
        val healthItems = combine(
            health.observeSleepRange(day, day + 1),
            health.observeExercises(day, day),
        ) { sleeps, exercises ->
            val wake = sleeps.filter { it.localDate == day }.map { TimelineItem.WokeUp(it) }
            val asleep = sleeps.filter { TimeUtils.epochDay(it.startMillis, it.zoneId) == day }.map { TimelineItem.FellAsleep(it) }
            wake + asleep + exercises.map { TimelineItem.Exercise(it) }
        }
        return combine(diary, healthItems) { a, b -> (a + b).sortedBy { it.epochMillis } }
    }

    /** Which categories have data for each day of the range (calendar indicators). */
    fun observeMarkers(from: Long, to: Long): Flow<Map<Long, Set<EntryCategory>>> {
        val flows: List<Flow<Pair<EntryCategory, List<Long>>>> = listOf(
            meals.observeDays(from, to).tag(EntryCategory.MEAL),
            drinks.observeDays(from, to).tag(EntryCategory.DRINK),
            weights.observeDays(from, to).tag(EntryCategory.WEIGHT),
            health.observeSleepDays(from, to).tag(EntryCategory.SLEEP),
            symptoms.observeDays(from, to).tag(EntryCategory.SYMPTOM),
            notes.observeDays(from, to).tag(EntryCategory.NOTE),
            health.observeActivityDays(from, to).tag(EntryCategory.ACTIVITY),
        )
        return combine(flows) { pairs ->
            val map = mutableMapOf<Long, MutableSet<EntryCategory>>()
            pairs.forEach { (category, days) -> days.forEach { map.getOrPut(it) { mutableSetOf() } += category } }
            map
        }
    }

    private fun Flow<List<Long>>.tag(category: EntryCategory): Flow<Pair<EntryCategory, List<Long>>> =
        kotlinx.coroutines.flow.flow { collect { emit(category to it) } }
}
