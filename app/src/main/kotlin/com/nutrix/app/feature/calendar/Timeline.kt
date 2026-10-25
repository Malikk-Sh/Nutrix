package com.nutrix.app.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.nutrix.app.R
import com.nutrix.app.core.model.AfterMealFeeling
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.color
import com.nutrix.app.core.ui.components.PhotoThumb
import com.nutrix.app.core.ui.icon
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.core.util.Units
import com.nutrix.app.data.repository.TimelineItem
import com.nutrix.app.feature.activity.sourceLabel

/** Where a timeline tap should lead. */
sealed interface TimelineTarget {
    data class Meal(val id: Long) : TimelineTarget
    data class Drink(val id: Long) : TimelineTarget
    data object Weight : TimelineTarget
    data class Symptom(val id: Long) : TimelineTarget
    data class Note(val id: Long) : TimelineTarget
    data class Sleep(val id: Long, val editable: Boolean) : TimelineTarget
    data object Activity : TimelineTarget
}

fun TimelineItem.target(): TimelineTarget = when (this) {
    is TimelineItem.Meal -> TimelineTarget.Meal(meal.meal.id)
    is TimelineItem.Drink -> TimelineTarget.Drink(drink.id)
    is TimelineItem.Weight -> TimelineTarget.Weight
    is TimelineItem.Symptom -> TimelineTarget.Symptom(symptom.id)
    is TimelineItem.Note -> TimelineTarget.Note(note.id)
    is TimelineItem.WokeUp -> TimelineTarget.Sleep(sleep.id, sleep.source == DataSource.MANUAL)
    is TimelineItem.FellAsleep -> TimelineTarget.Sleep(sleep.id, sleep.source == DataSource.MANUAL)
    is TimelineItem.Exercise -> TimelineTarget.Activity
}

@Composable
fun exerciseName(type: Int?): String = stringResource(
    when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> R.string.exercise_walking
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> R.string.exercise_running
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> R.string.exercise_biking
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> R.string.exercise_swimming
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> R.string.exercise_yoga
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> R.string.exercise_pilates
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> R.string.exercise_strength
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> R.string.exercise_hiking
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> R.string.exercise_dancing
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> R.string.exercise_elliptical
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> R.string.exercise_stairs
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE, ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> R.string.exercise_rowing
        else -> R.string.exercise_default
    },
)

/** One row of the vertical day timeline: time, colored node and a short description. */
@Composable
fun TimelineRow(item: TimelineItem, unit: WeightUnit, isLast: Boolean, onClick: () -> Unit) {
    val color = item.category.color()
    val h = stringResource(R.string.unit_hour_short)
    val m = stringResource(R.string.unit_minute_short)
    val unitLabel = stringResource(if (unit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)
    val (title, subtitle, photo) = when (item) {
        is TimelineItem.Meal -> {
            val meal = item.meal.meal
            Triple(
                meal.title ?: meal.items.firstOrNull() ?: stringResource(meal.mealType.labelRes),
                listOfNotNull(
                    stringResource(meal.mealType.labelRes).takeIf { meal.title != null || meal.items.isNotEmpty() },
                    meal.portion?.let { stringResource(it.labelRes) },
                    meal.feelings.filter { it != AfterMealFeeling.NORMAL }.map { stringResource(it.labelRes) }.takeIf { it.isNotEmpty() }?.joinToString(),
                ).joinToString(" · ").ifEmpty { null },
                item.meal.sortedPhotos.firstOrNull()?.fileName,
            )
        }
        is TimelineItem.Drink -> Triple(
            stringResource(R.string.timeline_drink, item.drink.name ?: stringResource(item.drink.drinkType.labelRes), item.drink.volumeMl),
            item.drink.note,
            item.drink.photoFile,
        )
        is TimelineItem.Weight -> Triple(
            stringResource(R.string.timeline_weight, Units.formatWeight(item.weight.weightKg, unit, unitLabel)),
            sourceLabel(item.weight.source, item.weight.sourceApp).takeIf { item.weight.source != DataSource.MANUAL },
            null,
        )
        is TimelineItem.Symptom -> Triple(
            item.symptom.customName ?: stringResource(item.symptom.type.labelRes),
            listOfNotNull(
                item.symptom.intensity?.let { "$it/5" },
                item.symptom.durationMinutes?.let { Formatters.duration(it.toLong(), h, m) },
                item.symptom.note,
            ).joinToString(" · ").ifEmpty { null },
            null,
        )
        is TimelineItem.WokeUp -> Triple(
            stringResource(R.string.timeline_wake),
            stringResource(R.string.timeline_sleep_details, Formatters.duration(item.sleep.durationMinutes.toLong(), h, m)) +
                (if (item.sleep.source != DataSource.MANUAL) " · " + sourceLabel(item.sleep.source, item.sleep.sourceApp) else ""),
            null,
        )
        is TimelineItem.FellAsleep -> Triple(stringResource(R.string.timeline_asleep), null, null)
        is TimelineItem.Note -> Triple(item.note.text, null, null)
        is TimelineItem.Exercise -> Triple(
            stringResource(
                R.string.timeline_exercise,
                item.exercise.title ?: exerciseName(item.exercise.exerciseType),
                Formatters.duration((item.exercise.endMillis - item.exercise.startMillis) / 60_000L, h, m),
            ),
            sourceLabel(item.exercise.source, item.exercise.sourceApp),
            null,
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
    ) {
        Text(
            Formatters.time(item.epochMillis, item.zoneId),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(56.dp)
                .padding(top = 12.dp),
            maxLines = 2,
        )
        Box(Modifier.width(28.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .padding(top = 18.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(if (isLast) MaterialTheme.colorScheme.surface.copy(alpha = 0f) else MaterialTheme.colorScheme.outlineVariant),
            )
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(item.category.icon, null, tint = color, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (photo != null) {
            PhotoThumb(photo, size = 44.dp, modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp))
        }
    }
}
