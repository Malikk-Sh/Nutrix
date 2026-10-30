package com.nutrix.app.feature.food

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nutrix.app.R
import com.nutrix.app.core.model.DishSource
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixTextField
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.domain.PartialTotal
import com.nutrix.app.domain.DishTotals
import com.nutrix.app.domain.MealTotals
import kotlin.math.roundToInt

/** Shares of the served portion offered for "eaten"; finer amounts are entered as a smaller portion. */
private val SHARES = listOf(1.0 to R.string.portion_all, 0.75 to R.string.portion_75, 0.5 to R.string.portion_50, 0.25 to R.string.portion_25)

/** The meal's dishes, each with its own portion and nutrients, and the eaten total computed from them. */
@Composable
fun DishesCard(state: FoodEditorUiState, viewModel: FoodEditorViewModel) {
    val form = state.form
    val totals = MealTotals.of(form.dishes.map { it.toDish() })
    var chooseFavorite by rememberSaveable { mutableStateOf(false) }
    NutrixCard(modifier = Modifier.testTag("food-dishes")) {
        Text(stringResource(R.string.food_dishes_title, form.dishes.size), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.food_dishes_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        form.dishes.forEachIndexed { index, dish ->
            Spacer(Modifier.height(12.dp))
            DishEditor(index, dish, form.photos, viewModel)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = viewModel::addDish, enabled = state.loaded && !state.isSaving && state.pendingDraft == null,
                modifier = Modifier.testTag("dish-add")) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.food_dish_add))
            }
            if (state.favorites.isNotEmpty()) Box {
                TextButton(onClick = { chooseFavorite = true }) { Text(stringResource(R.string.food_dish_from_favorite)) }
                DropdownMenu(chooseFavorite, onDismissRequest = { chooseFavorite = false }) {
                    state.favorites.take(12).forEach { fav ->
                        DropdownMenuItem(text = { Text(fav.title) }, leadingIcon = { Icon(Icons.Rounded.Star, null, Modifier.size(18.dp)) },
                            onClick = { chooseFavorite = false; viewModel.addFavoriteAsDish(fav) })
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        DishTotalsSummary(totals)
    }
}

@Composable
private fun DishEditor(index: Int, dish: DishForm, photos: List<String>, viewModel: FoodEditorViewModel) {
    val accent = LocalCategoryColors.current.meal
    Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().testTag("dish-$index")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NutrixTextField(dish.name, { v -> viewModel.updateDish(dish.id) { it.copy(name = v.take(120)) } },
                    stringResource(R.string.food_dish_name), placeholder = stringResource(R.string.food_dish_name_hint),
                    modifier = Modifier.weight(1f).testTag("dish-name-$index"))
                IconButton(onClick = { viewModel.removeDish(dish.id) }, modifier = Modifier.testTag("dish-remove-$index")) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.food_dish_remove, dish.name.ifBlank { (index + 1).toString() }))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NutrixTextField(dish.gramsText, { v -> viewModel.updateDish(dish.id) { it.copy(gramsText = v.take(8)) } },
                    stringResource(R.string.food_dish_served), suffix = stringResource(R.string.unit_g), keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f).testTag("dish-grams-$index"))
                NutrixTextField(dish.kcalText, { v -> viewModel.updateDish(dish.id) { it.copy(kcalText = v.take(8)) } },
                    stringResource(R.string.food_kcal_label), suffix = stringResource(R.string.unit_kcal), keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f).testTag("dish-kcal-$index"))
            }
            // Labels above the fields, so three narrow fields keep whole words.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NutritionValue(stringResource(R.string.food_protein_short), dish.proteinText, stringResource(R.string.unit_g), "dish-protein-$index",
                    { v -> viewModel.updateDish(dish.id) { it.copy(proteinText = v.take(8)) } }, Modifier.weight(1f))
                NutritionValue(stringResource(R.string.food_fat_short), dish.fatText, stringResource(R.string.unit_g), "dish-fat-$index",
                    { v -> viewModel.updateDish(dish.id) { it.copy(fatText = v.take(8)) } }, Modifier.weight(1f))
                NutritionValue(stringResource(R.string.food_carbs_short), dish.carbsText, stringResource(R.string.unit_g), "dish-carbs-$index",
                    { v -> viewModel.updateDish(dish.id) { it.copy(carbsText = v.take(8)) } }, Modifier.weight(1f))
            }
            if (dish.invalid) Text(stringResource(R.string.ai_invalid_macros), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.food_dish_eaten), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SHARES.forEach { (share, label) ->
                    FilterChip(selected = dish.share == share, onClick = { viewModel.updateDish(dish.id) { it.copy(share = share) } },
                        label = { Text(stringResource(label)) }, modifier = Modifier.testTag("dish-share-$index-${(share * 100).roundToInt()}"))
                }
            }
            if (photos.isNotEmpty()) {
                Text(stringResource(R.string.food_dish_photos), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    photos.forEachIndexed { i, photo ->
                        FilterChip(selected = photo in dish.photos, onClick = { viewModel.toggleDishPhoto(dish.id, photo) },
                            label = { Text(stringResource(R.string.ai_photo_number, i + 1)) })
                    }
                }
            }
            if (dish.components.isNotEmpty()) {
                Text(stringResource(R.string.food_dish_components, dish.components.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
            }
            Text(listOfNotNull(stringResource(sourceLabel(dish.source)), dish.estimateModel).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = accent)
            dish.uncertainty?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

fun sourceLabel(source: DishSource): Int = when (source) {
    DishSource.MANUAL -> R.string.dish_source_manual
    DishSource.PHOTO -> R.string.dish_source_photo
    DishSource.REFERENCE -> R.string.dish_source_reference
    DishSource.FAVORITE -> R.string.dish_source_favorite
    DishSource.MEAL_ESTIMATE -> R.string.dish_source_meal
}

/** "Итого съедено: ≈750 ккал · 520 г · Б 40 · Ж 25 · У 80", with what each total covers when it is partial. */
@Composable
fun DishTotalsSummary(t: DishTotals) {
    fun Double.text() = Formatters.decimal(this)
    val parts = listOfNotNull(
        t.kcal.value?.let { "≈${it.roundToInt()} ${stringResource(R.string.unit_kcal)}" },
        t.grams.value?.let { "${it.roundToInt()} ${stringResource(R.string.unit_g)}" },
        t.protein.value?.let { "Б ${it.text()}" },
        t.fat.value?.let { "Ж ${it.text()}" },
        t.carbs.value?.let { "У ${it.text()}" },
    )
    Column(Modifier.testTag("dish-totals")) {
        Text(stringResource(R.string.food_total_title), style = MaterialTheme.typography.titleSmall)
        Text(if (parts.isEmpty()) stringResource(R.string.food_total_none) else parts.joinToString(" · "),
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("dish-totals-value"))
        @Composable fun partial(total: PartialTotal, name: Int) {
            if (total.value != null && !total.complete) Text(stringResource(R.string.food_total_partial, stringResource(name), total.known, total.of),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        partial(t.kcal, R.string.food_total_kcal_name)
        partial(t.grams, R.string.food_total_grams_name)
        // One line for the three macronutrients: the least covered one tells how partial they are.
        listOf(t.protein, t.fat, t.carbs).filter { it.value != null && !it.complete }.minByOrNull { it.known }?.let { partial(it, R.string.food_total_macros_name) }
    }
}
