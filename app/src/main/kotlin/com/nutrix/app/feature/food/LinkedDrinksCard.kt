package com.nutrix.app.feature.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nutrix.app.R
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.icon
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.feature.water.drinkKcalText
import com.nutrix.app.feature.water.drinkMacrosText
import com.nutrix.app.feature.water.EnergyInput
import com.nutrix.app.feature.water.macrosSuffix
import com.nutrix.app.core.time.Formatters
import androidx.compose.foundation.layout.Column

/** Drinks had with this meal. They are separate records: the meal shows them but never adds them to its own total. */
@Composable
fun LinkedDrinksCard(
    drinks: List<DrinkEntity>,
    pending: List<PendingDrink>,
    onOpen: (Long) -> Unit,
    onAdd: (() -> Unit)?,
    onBackToDish: (String) -> Unit,
) {
    val colors = LocalCategoryColors.current
    NutrixCard(Modifier.testTag("meal-drinks")) {
        Text(stringResource(R.string.meal_drinks_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(if (drinks.isEmpty() && pending.isEmpty()) R.string.meal_drinks_hint else R.string.meal_drinks_once),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            drinks.forEach { d ->
                Row(Modifier.clickable { onOpen(d.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(d.drinkType.icon, null, tint = colors.water, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${d.name ?: stringResource(d.drinkType.labelRes)} · ${d.volumeMl} ${stringResource(R.string.unit_ml)}",
                            style = MaterialTheme.typography.bodyMedium)
                        drinkMacrosText(d)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    drinkKcalText(d)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            // Dishes moved to drinks in this edit: written as drinks when the meal is saved.
            pending.forEachIndexed { i, p ->
                Row(Modifier.padding(vertical = 4.dp).testTag("meal-pending-drink-$i"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(p.type.icon, null, tint = colors.water, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${p.name.ifBlank { stringResource(p.type.labelRes) }} · ${p.volumeMl} ${stringResource(R.string.unit_ml)}",
                            style = MaterialTheme.typography.bodyMedium)
                        val totals = EnergyInput.of(p.nutrition).totals(p.volumeMl)
                        Text(listOfNotNull(
                            totals?.get(0)?.let { "≈${Formatters.integer(Math.round(it))} ${stringResource(R.string.unit_kcal)}" + macrosSuffix(totals[1], totals[2], totals[3]) },
                            stringResource(R.string.meal_drinks_pending),
                        ).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onBackToDish(p.dish.id) }) { Text(stringResource(R.string.meal_drinks_back)) }
                }
            }
        }
        if (onAdd != null) TextButton(onClick = onAdd, modifier = Modifier.testTag("meal-add-drink")) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.meal_drinks_add))
        }
    }
}
