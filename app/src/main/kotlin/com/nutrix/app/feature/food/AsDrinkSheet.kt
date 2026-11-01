package com.nutrix.app.feature.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nutrix.app.R
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.MealDish
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.components.ChoiceChips
import com.nutrix.app.core.ui.components.NutrixTextField
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.domain.DishAsDrink
import com.nutrix.app.feature.water.EnergyInput
import com.nutrix.app.feature.water.macrosSuffix

/**
 * Confirms how a dish (or a record entered as a meal) becomes a drink: its type, name and volume, and the energy
 * and macronutrients that go with it. Nothing is guessed silently: the type is only a suggestion from the name.
 */
@Composable
fun AsDrinkSheet(dish: MealDish, explanation: String, onConfirm: (DrinkType, String, Int) -> Unit, onDismiss: () -> Unit) {
    var type by rememberSaveable { mutableStateOf(DishAsDrink.guessType(dish.name)) }
    var name by rememberSaveable { mutableStateOf(dish.name) }
    var volume by rememberSaveable { mutableStateOf(DishAsDrink.volume(dish)?.toString().orEmpty()) }
    val ml = volume.toIntOrNull()?.takeIf { it in 1..5000 }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp).testTag("as-drink"),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.as_drink_title), style = MaterialTheme.typography.titleLarge)
            Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceChips(DrinkType.entries, type, { stringResource(it.labelRes) }, { t -> if (t != null) type = t },
                allowDeselect = false, accent = LocalCategoryColors.current.water)
            NutrixTextField(name, { name = it.take(120) }, stringResource(R.string.drink_name_label), modifier = Modifier.testTag("as-drink-name"))
            NutrixTextField(volume, { volume = it.filter(Char::isDigit).take(4) }, stringResource(R.string.water_volume),
                modifier = Modifier.testTag("as-drink-volume"), keyboardType = KeyboardType.Number, suffix = stringResource(R.string.unit_ml),
                isError = volume.isNotEmpty() && ml == null,
                supportingText = if (dish.servedGrams == null && volume.isEmpty()) stringResource(R.string.as_drink_volume_needed) else null)
            val nutrition = DishAsDrink.nutrition(dish)
            val totals = EnergyInput.of(nutrition).totals(ml)
            Text(
                when {
                    type == DrinkType.WATER -> stringResource(R.string.as_drink_water)
                    nutrition == null -> stringResource(R.string.as_drink_energy_unknown)
                    totals == null -> stringResource(R.string.as_drink_energy_later)
                    else -> stringResource(R.string.as_drink_energy, Formatters.integer(Math.round(totals[0] ?: 0.0))) + macrosSuffix(totals[1], totals[2], totals[3])
                },
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("as-drink-energy"),
            )
            Button(onClick = { ml?.let { onConfirm(type, name, it) } }, enabled = ml != null, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("as-drink-confirm")) { Text(stringResource(R.string.as_drink_confirm)) }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}
