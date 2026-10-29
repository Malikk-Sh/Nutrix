package com.nutrix.app.feature.food

import com.nutrix.app.domain.kcalIfComplete

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import com.nutrix.app.core.ui.icons.Restaurant
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.MoreVert
import com.nutrix.app.core.ui.icons.Repeat
import com.nutrix.app.core.ui.icons.Healing
import com.nutrix.app.core.ui.components.NutrixActionSheet
import com.nutrix.app.core.ui.components.NutrixContextAction
import com.nutrix.app.core.ui.components.CardHeader
import com.nutrix.app.data.repository.UndoManager
import com.nutrix.app.data.repository.UserMessages
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.height
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
import com.nutrix.app.core.ui.components.NutrixBottomAction
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
class FoodDiaryViewModel @Inject constructor(private val meals: MealRepository, private val undo: UndoManager, private val messages: UserMessages) : ViewModel() {
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

    fun favorite(meal: MealWithPhotos) {
        viewModelScope.launch {
            try {
                val id = meals.favoriteFromMeal(meal.meal, meal.sortedPhotos.firstOrNull()?.fileName)
                messages.show(if (id == 0L) R.string.food_favorite_needs_title else R.string.alignment_favorite_saved)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { messages.show(R.string.food_save_error) }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            try {
                val snapshot = meals.delete(id) ?: return@launch
                undo.offer(undo = { meals.restore(snapshot) }, commit = { meals.purge(snapshot) })
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { messages.show(R.string.food_save_error) }
        }
    }
}

@Composable
fun FoodDiaryScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpenMeal: (Long) -> Unit,
    onFavorites: () -> Unit,
    onSearch: () -> Unit,
    onRepeat: (Long) -> Unit = onOpenMeal,
    onAddSymptom: (Long) -> Unit = onOpenMeal,
    viewModel: FoodDiaryViewModel = hiltViewModel(),
) {
    val days by viewModel.days.collectAsStateWithLifecycle()
    var actionMeal by remember { mutableStateOf<MealWithPhotos?>(null) }
    var deleteMeal by remember { mutableStateOf<MealWithPhotos?>(null) }
    NutrixScreen(
        title = stringResource(R.string.food_diary_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, stringResource(R.string.action_search)) }
            IconButton(onClick = onFavorites) { Icon(Icons.Rounded.Star, stringResource(R.string.food_favorites_title)) }
        },
        bottomBar = { NutrixBottomAction(stringResource(R.string.card_food_add), onAdd) },
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
            list?.firstOrNull { it.date == LocalDate.now() }?.let { today ->
                item(key = "today-summary") {
                    // A meal whose dishes are only partly estimated does not count as known.
                    val knownCalories = today.meals.mapNotNull { it.meal.kcalIfComplete }
                    NutrixCard {
                        CardHeader(Icons.Rounded.Restaurant, pluralStringResource(R.plurals.meals_count, today.meals.size, today.meals.size), LocalCategoryColors.current.meal)
                        if (knownCalories.isNotEmpty()) {
                            Text("≈${knownCalories.sum()} ${stringResource(R.string.unit_kcal)}", style = MaterialTheme.typography.headlineSmall)
                            if (knownCalories.size < today.meals.size) Text(stringResource(R.string.alignment_known_meals, knownCalories.size, today.meals.size), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            list?.forEach { day ->
                item(key = "h-${day.date}") {
                    SectionHeader(
                        "${dayTitle(day.date)} · ${pluralStringResource(R.plurals.meals_count, day.meals.size, day.meals.size)}",
                    )
                }
                items(day.meals, key = { it.meal.id }) { meal ->
                    Box(Modifier.animateItem()) { MealRow(meal, onClick = { onOpenMeal(meal.meal.id) }, onActions = { actionMeal = meal }) }
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
    actionMeal?.let { meal ->
        NutrixActionSheet(meal.meal.title ?: stringResource(R.string.alignment_meal_actions), listOf(
            NutrixContextAction(stringResource(R.string.food_repeat), Icons.Rounded.Repeat) { onRepeat(meal.meal.id) },
            NutrixContextAction(stringResource(R.string.food_save_favorite), Icons.Rounded.Star) { viewModel.favorite(meal) },
            NutrixContextAction(stringResource(R.string.food_add_symptom), Icons.Rounded.Healing) { onAddSymptom(meal.meal.id) },
            NutrixContextAction(stringResource(R.string.action_delete), Icons.Rounded.Delete, destructive = true) { deleteMeal = meal },
        ), onDismiss = { actionMeal = null })
    }
    deleteMeal?.let { meal ->
        ConfirmDialog(stringResource(R.string.confirm_delete_title), stringResource(R.string.confirm_delete_text), stringResource(R.string.action_delete),
            onConfirm = { deleteMeal = null; viewModel.delete(meal.meal.id) }, onDismiss = { deleteMeal = null })
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
fun MealRow(meal: MealWithPhotos, onClick: () -> Unit, modifier: Modifier = Modifier, onActions: (() -> Unit)? = null) {
    val m = meal.meal
    val colors = LocalCategoryColors.current
    NutrixCard(modifier = modifier, onClick = onClick, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhotoThumb(meal.sortedPhotos.firstOrNull()?.fileName, size = 76.dp, shape = MaterialTheme.shapes.medium)
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
                    // Dishes and photos are counted apart: one photo may show three dishes.
                    m.dishes?.takeIf { it.size > 1 }?.let { dishes ->
                        Spacer(Modifier.width(6.dp))
                        Pill(pluralStringResource(R.plurals.dishes_count, dishes.size, dishes.size), colors.meal)
                    }
                    if (meal.photos.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        Pill(stringResource(R.string.meal_photos_more, meal.photos.size - 1), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "${Formatters.time(m.time)} · ${stringResource(m.mealType.labelRes)}" + (mealKcalText(m)?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val nutrition = m.nutrition
                if (m.portionGrams != null || nutrition != null) {
                    Text(
                        listOfNotNull(
                            m.portionGrams?.let { "$it ${stringResource(R.string.unit_g)}" },
                            nutrition?.proteinGrams?.let { "Б ${Formatters.decimal(it)}" },
                            nutrition?.fatGrams?.let { "Ж ${Formatters.decimal(it)}" },
                            nutrition?.carbsGrams?.let { "У ${Formatters.decimal(it)}" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val chips = buildList {
                    m.appetite?.let { add(stringResource(R.string.food_appetite) + ": $it/5" to colors.meal) }
                    m.portion?.let { add(stringResource(it.labelRes) to colors.meal) }
                    m.satiety?.let { add(stringResource(it.labelRes) to colors.water) }
                    m.feelings.filter { it != AfterMealFeeling.NORMAL }.forEach { add(stringResource(it.labelRes) to colors.symptom) }
                }
                if (chips.isNotEmpty()) Text(chips.joinToString(" · ") { it.first }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onActions != null) IconButton(onClick = onActions) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.alignment_meal_actions)) }

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
                        PhotoThumb(fav.photoFile, size = 72.dp)
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
                            val nutrition = fav.nutrition
                            val amounts = listOfNotNull(fav.portionGrams?.let { "$it ${stringResource(R.string.unit_g)}" },
                                nutrition?.proteinGrams?.let { "Б ${Formatters.decimal(it)}" }, nutrition?.fatGrams?.let { "Ж ${Formatters.decimal(it)}" }, nutrition?.carbsGrams?.let { "У ${Formatters.decimal(it)}" })
                            if (amounts.isNotEmpty()) Text(amounts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        }
                        IconButton(onClick = { toDelete = fav }) {
                            Icon(Icons.Rounded.Delete, stringResource(R.string.food_favorite_delete), modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = { onUse(fav.id) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.food_favorite_use))
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

/** "≈750 ккал", or "известно ≈650 ккал, оценены 2 из 3" when only some dishes have calories. */
@Composable
fun mealKcalText(m: com.nutrix.app.database.entity.MealEntity): String? {
    val kcal = m.caloriesEstimate ?: return null
    val n = m.nutrition
    return if (n?.dishCount != null && n.kcalKnown != n.dishCount) stringResource(R.string.meal_kcal_partial, kcal, n.kcalKnown ?: 0, n.dishCount)
    else "≈$kcal ${stringResource(R.string.unit_kcal)}"
}
