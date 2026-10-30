package com.nutrix.app.feature.intake

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import com.nutrix.app.R
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.domain.DayIntake
import com.nutrix.app.domain.DayIntakes
import com.nutrix.app.domain.DayState
import com.nutrix.app.domain.Nutrient

/** "≈1 850 ккал" or "≈62 г"; every value in the diary is an estimate. */
@Composable
fun nutrientText(n: Nutrient, value: Double): String {
    val v = DayIntakes.rounded(n, value)
    val number = if (n == Nutrient.KCAL) Formatters.integer(v.toLong()) else Formatters.decimal(v, if (v >= 10) 0 else 1)
    return "≈$number ${stringResource(if (n == Nutrient.KCAL) R.string.unit_kcal else R.string.unit_g)}"
}

@Composable
fun nutrientName(n: Nutrient): String = stringResource(when (n) {
    Nutrient.KCAL -> R.string.intake_kcal
    Nutrient.PROTEIN -> R.string.intake_protein
    Nutrient.FAT -> R.string.intake_fat
    Nutrient.CARBS -> R.string.intake_carbs
})

/** The day's sum, said as "known X" whenever some record of the day has no value. */
@Composable
fun dayTotalText(intake: DayIntake, n: Nutrient): String? {
    val total = intake.total(n)
    val value = total.value ?: return null
    return if (total.complete) nutrientText(n, value) else stringResource(R.string.intake_known, nutrientText(n, value))
}

/** How the sum came about: what is missing, or food and drinks apart when both add to it. */
@Composable
fun dayCoverageText(intake: DayIntake, n: Nutrient): String? {
    val total = intake.total(n)
    if (intake.records == 0) return null
    if (!total.complete) return stringResource(R.string.intake_partial, total.known, total.of)
    val drinks = intake.fromDrinks(n)?.takeIf { it > 0 } ?: return null
    return stringResource(R.string.intake_split, nutrientText(n, intake.fromMeals(n) ?: 0.0), nutrientText(n, drinks))
}

@Composable
fun dayStateText(state: DayState): String = stringResource(when (state) {
    DayState.COMPLETE -> R.string.intake_state_complete
    DayState.UNCONFIRMED -> R.string.intake_state_unconfirmed
    DayState.PARTIAL -> R.string.intake_state_partial
    DayState.TODAY -> R.string.intake_state_today
    DayState.NO_RECORDS -> R.string.intake_state_empty
})

/** The user's statement that the diary holds the whole day; it says nothing about how exact the estimates are. */
@Composable
fun DayConfirmRow(confirmed: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, tag: String = "day-confirm") {
    Row(
        modifier.toggleable(confirmed, role = Role.Switch, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.intake_confirm_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.intake_confirm_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(confirmed, onCheckedChange = null)
    }
}
