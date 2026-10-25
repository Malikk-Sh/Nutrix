package com.nutrix.app.feature.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nutrix.app.R
import com.nutrix.app.core.model.AfterMealFeeling
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.core.ui.components.ConfirmDialog
import com.nutrix.app.core.ui.components.EmptyState
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.PhotoThumb
import com.nutrix.app.core.ui.components.Pill
import com.nutrix.app.core.ui.components.ScreenList
import com.nutrix.app.core.ui.components.SectionHeader
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.database.entity.FavoriteMealEntity
import com.nutrix.app.database.entity.MealWithPhotos
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class MealDay(val date: LocalDate, val meals: List<MealWithPhotos>)

@HiltViewModel
class FoodDiaryViewModel @Inject constructor(meals: MealRepository) : ViewModel() {
    private val window = MutableStateFlow(30)

    val days = window.flatMapLatest { days ->
        val today = TimeUtils.todayEpochDay()
        meals.observeRange(today - days + 1, today + 1).map { list ->
            list.groupBy { it.meal.localDate }
                .map { (day, items) -> MealDay(LocalDate.ofEpochDay(day), items.sortedByDescending { it.meal.epochMillis }) }
                .sortedByDescending { it.date }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun loadMore() {
        window.value += 30
    }
}

@Composable
fun FoodDiaryScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpenMeal: (Long) -> Unit,
    onFavorites: () -> Unit,
    onSearch: () -> Unit,
    viewModel: FoodDiaryViewModel = hiltViewModel(),
) {
    val days by viewModel.days.collectAsStateWithLifecycle()
    NutrixScreen(
        title = stringResource(R.string.food_diary_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, stringResource(R.string.action_search)) }
            IconButton(onClick = onFavorites) { Icon(Icons.Rounded.Star, stringResource(R.string.food_favorites_title)) }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(stringResource(R.string.action_add)) },
            )
        },
    ) { padding ->
        val list = days
        ScreenList(padding) {
            if (list != null && list.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.Restaurant,
                        title = stringResource(R.string.food_empty_title),
                        text = stringResource(R.string.food_empty_text),
                    )
                }
            }
            list?.forEach { day ->
                item(key = "h-${day.date}") {
                    SectionHeader(
                        "${dayTitle(day.date)} · ${pluralStringResource(R.plurals.meals_count, day.meals.size, day.meals.size)}",
                    )
                }
                items(day.meals, key = { it.meal.id }) { meal ->
                    MealRow(meal, onClick = { onOpenMeal(meal.meal.id) })
                }
            }
            if (list != null) {
                item(key = "more") {
                    OutlinedButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.food_load_more))
                    }
                }
            }
        }
    }
}

@Composable
fun dayTitle(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> stringResource(R.string.label_today)
        today.minusDays(1) -> stringResource(R.string.label_yesterday)
        else -> Formatters.weekdayDayMonth(date).replaceFirstChar { it.titlecase() }
    }
}

/** Compact meal card used in the diary, the day timeline and search. */
@Composable
fun MealRow(meal: MealWithPhotos, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val m = meal.meal
    val colors = LocalCategoryColors.current
    NutrixCard(modifier = modifier, onClick = onClick, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhotoThumb(meal.sortedPhotos.firstOrNull()?.fileName, size = 64.dp, shape = MaterialTheme.shapes.medium)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        m.title ?: m.items.firstOrNull() ?: stringResource(m.mealType.labelRes),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (meal.photos.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        Pill("+${meal.photos.size - 1}", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "${Formatters.time(m.time)} · ${stringResource(m.mealType.labelRes)}" +
                        (m.caloriesEstimate?.let { " · ≈$it ${stringResource(R.string.unit_kcal)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val chips = buildList {
                    m.appetite?.let { add(stringResource(R.string.food_appetite) + ": $it/5" to colors.meal) }
                    m.portion?.let { add(stringResource(it.labelRes) to colors.meal) }
                    m.satiety?.let { add(stringResource(it.labelRes) to colors.water) }
                    m.feelings.filter { it != AfterMealFeeling.NORMAL }.forEach { add(stringResource(it.labelRes) to colors.symptom) }
                }
                if (chips.isNotEmpty()) {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chips.take(3).forEach { (text, color) -> Pill(text, color) }
                        if (chips.size > 3) Pill("+${chips.size - 3}", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onUse: (Long) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<FavoriteMealEntity?>(null) }
    NutrixScreen(title = stringResource(R.string.food_favorites_title), onBack = onBack) { padding ->
        ScreenList(padding) {
            if (favorites.isEmpty()) {
                item { EmptyState(Icons.Rounded.Star, stringResource(R.string.food_favorites_title), stringResource(R.string.food_no_favorites)) }
            }
            items(favorites, key = { it.id }) { fav ->
                NutrixCard(onClick = { onUse(fav.id) }, contentPadding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PhotoThumb(fav.photoFile, size = 56.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(fav.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val details = listOfNotNull(
                                fav.mealType?.let { stringResource(it.labelRes) },
                                fav.items.takeIf { it.isNotEmpty() }?.joinToString(", "),
                                fav.caloriesEstimate?.let { "≈$it ${stringResource(R.string.unit_kcal)}" },
                            ).joinToString(" · ")
                            if (details.isNotEmpty()) {
                                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                        }
                        IconButton(onClick = { toDelete = fav }) {
                            Icon(Icons.Rounded.Delete, stringResource(R.string.food_favorite_delete), modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
    toDelete?.let { fav ->
        ConfirmDialog(
            title = stringResource(R.string.food_favorite_delete),
            text = fav.title,
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { viewModel.delete(fav); toDelete = null },
            onDismiss = { toDelete = null },
        )
    }
}

@HiltViewModel
class FavoritesViewModel @Inject constructor(private val meals: MealRepository) : ViewModel() {
    val favorites = meals.observeFavorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(fav: FavoriteMealEntity) {
        viewModelScope.launch { meals.deleteFavorite(fav) }
    }
}
