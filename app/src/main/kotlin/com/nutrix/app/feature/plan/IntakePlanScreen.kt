package com.nutrix.app.feature.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrix.app.R
import com.nutrix.app.core.model.ActivityLevel
import com.nutrix.app.core.model.FormulaSex
import com.nutrix.app.core.model.NutritionGoal
import com.nutrix.app.core.model.PlanSource
import com.nutrix.app.core.model.SpecialCircumstance
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.components.ChoiceChips
import com.nutrix.app.core.ui.components.InfoBanner
import com.nutrix.app.core.ui.components.MultiChoiceChips
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.NutrixTextField
import com.nutrix.app.core.ui.components.ScreenList
import com.nutrix.app.core.ui.components.SectionHeader
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.core.util.Units
import com.nutrix.app.database.entity.IntakePlanEntity
import com.nutrix.app.domain.GuidelineResult
import com.nutrix.app.domain.MaintenanceEstimate
import com.nutrix.app.domain.MissingField
import com.nutrix.app.domain.NotApplicableReason
import java.time.LocalDate

/**
 * Calorie and macronutrient guidelines: a maintenance range from the formula for healthy adults, a
 * specialist's guideline, or none. Each saved guideline starts today and keeps the earlier days as they were.
 */
@Composable
fun IntakePlanScreen(onBack: () -> Unit, onAddWeight: () -> Unit, viewModel: IntakePlanViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val s = state ?: return
    val colors = LocalCategoryColors.current
    NutrixScreen(title = stringResource(R.string.plan_title), onBack = onBack) { padding ->
        ScreenList(padding, bottomExtra = 24.dp) {
            item(key = "intro") { InfoBanner(stringResource(R.string.plan_intro), Icons.Rounded.Info, tint = colors.meal) }
            item(key = "active") { ActivePlanCard(s.active, s.plans) }
            item(key = "goal") {
                NutrixCard(Modifier.testTag("plan-goal")) {
                    Text(stringResource(R.string.plan_goal_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    ChoiceChips(NutritionGoal.entries, s.settings.nutritionGoal, { stringResource(goalLabel(it)) },
                        { g -> if (g != null) viewModel.setGoal(g) }, allowDeselect = false, accent = colors.meal)
                }
            }
            if (s.settings.nutritionGoal == NutritionGoal.MAINTAIN) {
                item(key = "inputs") { InputsCard(s, viewModel, onAddWeight) }
            }
            item(key = "result") { ResultCard(s, viewModel) }
            item(key = "manual") { ManualPlanCard(s.active, viewModel::saveManual) }
            if (s.active != null) item(key = "off") {
                OutlinedButton(onClick = viewModel::turnOff, modifier = Modifier.fillMaxWidth().testTag("plan-off")) {
                    Text(stringResource(R.string.plan_turn_off))
                }
            }
            if (s.plans.isNotEmpty()) {
                item(key = "history-h") { SectionHeader(stringResource(R.string.plan_history)) }
                item(key = "history") {
                    NutrixCard {
                        s.plans.sortedByDescending { it.startDay }.forEach { p ->
                            Text(planLine(p), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        }
                        Text(stringResource(R.string.plan_history_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun goalLabel(g: NutritionGoal) = when (g) {
    NutritionGoal.MAINTAIN -> R.string.plan_goal_maintain
    NutritionGoal.CHANGE -> R.string.plan_goal_change
    NutritionGoal.OBSERVE -> R.string.plan_goal_observe
}

@Composable
private fun ActivePlanCard(active: IntakePlanEntity?, plans: List<IntakePlanEntity>) {
    NutrixCard(Modifier.testTag("plan-active")) {
        Text(stringResource(R.string.plan_active_title), style = MaterialTheme.typography.titleSmall)
        if (active == null) {
            Text(stringResource(if (plans.isEmpty()) R.string.plan_active_none else R.string.plan_active_off), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(planRange(active), style = MaterialTheme.typography.headlineSmall)
            macrosLine(active)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(stringResource(R.string.plan_active_since, sourceText(active), Formatters.dayMonthShort(LocalDate.ofEpochDay(active.startDay))),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            active.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun InputsCard(s: PlanUiState, viewModel: IntakePlanViewModel, onAddWeight: () -> Unit) {
    val colors = LocalCategoryColors.current
    var year by rememberSaveable { mutableStateOf(s.settings.birthYear?.toString().orEmpty()) }
    var height by rememberSaveable { mutableStateOf(s.settings.heightCm?.toString().orEmpty()) }
    val thisYear = LocalDate.ofEpochDay(s.today).year
    NutrixCard(Modifier.testTag("plan-inputs")) {
        Text(stringResource(R.string.plan_inputs_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NutrixTextField(year, { v ->
                year = v.filter(Char::isDigit).take(4)
                viewModel.setBirthYear(year.toIntOrNull()?.takeIf { it in 1900..thisYear })
            }, stringResource(R.string.plan_birth_year), Modifier.weight(1f).testTag("plan-birth-year"), keyboardType = KeyboardType.Number)
            NutrixTextField(height, { v ->
                height = v.filter(Char::isDigit).take(3)
                viewModel.setHeight(height.toIntOrNull()?.takeIf { it in 50..260 })
            }, stringResource(R.string.profile_height), Modifier.weight(1f).testTag("plan-height"), keyboardType = KeyboardType.Number,
                suffix = stringResource(R.string.unit_cm))
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.plan_sex_title), style = MaterialTheme.typography.labelLarge)
        // "Not given" is a real choice: the range then covers both variants of the formula and is wider.
        ChoiceChips(listOf<FormulaSex?>(FormulaSex.FEMALE, FormulaSex.MALE, null), s.settings.formulaSex, { stringResource(when (it) {
            FormulaSex.FEMALE -> R.string.plan_sex_female
            FormulaSex.MALE -> R.string.plan_sex_male
            null -> R.string.plan_sex_none
        }) }, { viewModel.setSex(it) }, allowDeselect = false, accent = colors.meal)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.plan_activity_title), style = MaterialTheme.typography.labelLarge)
        ChoiceChips(ActivityLevel.entries, s.settings.activityLevel, { stringResource(when (it) {
            ActivityLevel.LOW -> R.string.plan_activity_low
            ActivityLevel.MODERATE -> R.string.plan_activity_moderate
            ActivityLevel.HIGH -> R.string.plan_activity_high
        }) }, { a -> if (a != null) viewModel.setActivity(a) }, allowDeselect = false, accent = colors.meal)
        s.settings.activityLevel?.let { a ->
            Text(stringResource(when (a) {
                ActivityLevel.LOW -> R.string.plan_activity_low_hint
                ActivityLevel.MODERATE -> R.string.plan_activity_moderate_hint
                ActivityLevel.HIGH -> R.string.plan_activity_high_hint
            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        val w = s.latestWeight
        val unit = stringResource(if (s.settings.weightUnit == com.nutrix.app.core.model.WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)
        Text(
            if (w == null) stringResource(R.string.plan_weight_none)
            else stringResource(R.string.plan_weight_latest, Units.formatWeight(w.weightKg, s.settings.weightUnit, unit), Formatters.dayMonthShort(LocalDate.ofEpochDay(w.localDate))),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onAddWeight) { Text(stringResource(R.string.plan_weight_add)) }
        Text(stringResource(R.string.plan_circumstances_title), style = MaterialTheme.typography.labelLarge)
        MultiChoiceChips(SpecialCircumstance.entries, s.settings.circumstances, { stringResource(when (it) {
            SpecialCircumstance.PREGNANCY_OR_LACTATION -> R.string.plan_circ_pregnancy
            SpecialCircumstance.MEDICAL_DIET -> R.string.plan_circ_medical
            SpecialCircumstance.UNINTENDED_WEIGHT_LOSS -> R.string.plan_circ_weight_loss
        }) }, viewModel::toggle, accent = colors.symptom)
        Text(stringResource(R.string.plan_inputs_private), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultCard(s: PlanUiState, viewModel: IntakePlanViewModel) {
    NutrixCard(Modifier.testTag("plan-result")) {
        when (val r = s.result) {
            is GuidelineResult.Ready -> Estimate(r.estimate, s, viewModel::accept)
            is GuidelineResult.NotApplicable -> {
                r.reasons.forEach { Text(stringResource(reasonText(it)), style = MaterialTheme.typography.bodyMedium) }
                if (r.reasons.any { it !in listOf(NotApplicableReason.GOAL_OBSERVE, NotApplicableReason.NO_GOAL) }) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.plan_manual_possible), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is GuidelineResult.Missing -> {
                Text(stringResource(R.string.plan_missing_title), style = MaterialTheme.typography.titleSmall)
                Text(r.fields.map { stringResource(missingText(it)) }.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Estimate(e: MaintenanceEstimate, s: PlanUiState, onAccept: (MaintenanceEstimate) -> Unit) {
    val kcal = stringResource(R.string.unit_kcal)
    val b = e.basis
    Text(stringResource(R.string.plan_result_title), style = MaterialTheme.typography.titleSmall)
    Text("≈${Formatters.integer(e.kcal.toLong())} $kcal", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("plan-estimate"))
    Text(stringResource(R.string.plan_result_range, Formatters.integer(e.kcalMin.toLong()), Formatters.integer(e.kcalMax.toLong())), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.plan_result_macros, e.protein.min, e.protein.max, e.fat.min, e.fat.max, e.carbs.min, e.carbs.max), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(6.dp))
    // Rounded to tens: the formula is not more exact than that.
    fun tens(v: Int) = Formatters.integer(Math.round(v / 10.0) * 10)
    val resting = if (b.restingMin == b.restingMax) tens(b.restingMin) else "${tens(b.restingMin)}–${tens(b.restingMax)}"
    Text(stringResource(R.string.plan_result_resting, resting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.plan_result_method, Formatters.decimal(b.activity.palMin, 1), Formatters.decimal(b.activity.palMax, 1)) +
        if (b.sex == null) " " + stringResource(R.string.plan_result_no_sex) else "",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val unit = stringResource(if (s.settings.weightUnit == com.nutrix.app.core.model.WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)
    Text(stringResource(R.string.plan_result_basis, Units.formatWeight(b.weightKg, s.settings.weightUnit, unit), Formatters.dayMonthShort(LocalDate.ofEpochDay(b.weightDay)),
        b.heightCm, b.age), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    val active = s.active
    when {
        active != null && active.matches(e) -> Text(stringResource(R.string.plan_result_accepted, Formatters.dayMonthShort(LocalDate.ofEpochDay(active.startDay))),
            style = MaterialTheme.typography.bodyMedium, color = LocalCategoryColors.current.meal)
        else -> {
            if (active?.source == PlanSource.FORMULA) Text(stringResource(R.string.plan_result_changed, planRange(active)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { onAccept(e) }, modifier = Modifier.fillMaxWidth().testTag("plan-accept")) {
                Text(stringResource(if (active?.source == PlanSource.FORMULA) R.string.plan_result_update else R.string.plan_result_accept))
            }
        }
    }
}

@Composable
private fun ManualPlanCard(active: IntakePlanEntity?, onSave: (ManualPlanInput) -> Unit) {
    var input by rememberSaveable(stateSaver = manualSaver) { mutableStateOf(ManualPlanInput.of(active)) }
    LaunchedEffect(active?.startDay, active?.source) { if (active?.source == PlanSource.MANUAL) input = ManualPlanInput.of(active) }
    val g = stringResource(R.string.unit_g)
    NutrixCard(Modifier.testTag("plan-manual")) {
        Text(stringResource(R.string.plan_manual_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.plan_manual_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        @Composable fun pair(label: Int, min: String, max: String, unit: String, tag: String, set: (String, String) -> Unit) {
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NutrixTextField(min, { set(it.filter(Char::isDigit).take(5), max) }, stringResource(R.string.plan_from), Modifier.weight(1f).testTag("$tag-min"),
                    keyboardType = KeyboardType.Number, suffix = unit)
                NutrixTextField(max, { set(min, it.filter(Char::isDigit).take(5)) }, stringResource(R.string.plan_to), Modifier.weight(1f).testTag("$tag-max"),
                    keyboardType = KeyboardType.Number, suffix = unit)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            pair(R.string.intake_kcal, input.kcalMin, input.kcalMax, stringResource(R.string.unit_kcal), "manual-kcal") { a, b -> input = input.copy(kcalMin = a, kcalMax = b) }
            pair(R.string.intake_protein, input.proteinMin, input.proteinMax, g, "manual-protein") { a, b -> input = input.copy(proteinMin = a, proteinMax = b) }
            pair(R.string.intake_fat, input.fatMin, input.fatMax, g, "manual-fat") { a, b -> input = input.copy(fatMin = a, fatMax = b) }
            pair(R.string.intake_carbs, input.carbsMin, input.carbsMax, g, "manual-carbs") { a, b -> input = input.copy(carbsMin = a, carbsMax = b) }
            NutrixTextField(input.note, { input = input.copy(note = it.take(200)) }, stringResource(R.string.plan_manual_note))
        }
        val c = input.consistency
        if (!input.invalid && !c.consistent) Text(
            stringResource(if (c.tooHigh) R.string.plan_manual_too_high else R.string.plan_manual_too_low,
                Formatters.integer(((if (c.tooHigh) c.minKcalFromMacros else c.maxKcalFromMacros) ?: 0).toLong())),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("manual-inconsistent"),
        )
        Spacer(Modifier.height(6.dp))
        Button(onClick = { onSave(input) }, enabled = !input.invalid, modifier = Modifier.fillMaxWidth().testTag("manual-save")) {
            Text(stringResource(R.string.plan_manual_save))
        }
    }
}

private val manualSaver = androidx.compose.runtime.saveable.listSaver<ManualPlanInput, String>(
    save = { listOf(it.kcalMin, it.kcalMax, it.proteinMin, it.proteinMax, it.fatMin, it.fatMax, it.carbsMin, it.carbsMax, it.note) },
    restore = { ManualPlanInput(it[0], it[1], it[2], it[3], it[4], it[5], it[6], it[7], it[8]) },
)

@Composable
private fun planRange(p: IntakePlanEntity): String {
    val kcal = stringResource(R.string.unit_kcal)
    return if (p.kcalMin != null && p.kcalMax != null) "${Formatters.integer(p.kcalMin.toLong())}–${Formatters.integer(p.kcalMax.toLong())} $kcal" else ""
}

@Composable
private fun macrosLine(p: IntakePlanEntity): String? {
    val g = stringResource(R.string.unit_g)
    val parts = listOfNotNull(
        p.proteinMin?.let { min -> p.proteinMax?.let { "Б $min–$it $g" } },
        p.fatMin?.let { min -> p.fatMax?.let { "Ж $min–$it $g" } },
        p.carbsMin?.let { min -> p.carbsMax?.let { "У $min–$it $g" } },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun sourceText(p: IntakePlanEntity): String = stringResource(when (p.source) {
    PlanSource.FORMULA -> R.string.plan_source_formula
    PlanSource.MANUAL -> R.string.plan_source_manual
    PlanSource.NONE -> R.string.plan_source_none
})

@Composable
private fun planLine(p: IntakePlanEntity): String {
    val since = stringResource(R.string.plan_history_since, Formatters.dayMonthShort(LocalDate.ofEpochDay(p.startDay)))
    return if (p.source == PlanSource.NONE) "$since — ${sourceText(p)}" else "$since — ${sourceText(p)}: ${planRange(p)}"
}

private fun reasonText(r: NotApplicableReason) = when (r) {
    NotApplicableReason.NO_GOAL -> R.string.plan_reason_no_goal
    NotApplicableReason.GOAL_CHANGE -> R.string.plan_reason_change
    NotApplicableReason.GOAL_OBSERVE -> R.string.plan_reason_observe
    NotApplicableReason.UNDER_18 -> R.string.plan_reason_under_18
    NotApplicableReason.OVER_80 -> R.string.plan_reason_over_80
    NotApplicableReason.LOW_BMI -> R.string.plan_reason_low_bmi
    NotApplicableReason.PREGNANCY_OR_LACTATION -> R.string.plan_reason_pregnancy
    NotApplicableReason.MEDICAL_DIET -> R.string.plan_reason_medical
    NotApplicableReason.UNINTENDED_WEIGHT_LOSS -> R.string.plan_reason_weight_loss
}

private fun missingText(f: MissingField) = when (f) {
    MissingField.HEIGHT -> R.string.plan_missing_height
    MissingField.WEIGHT -> R.string.plan_missing_weight
    MissingField.WEIGHT_OUTDATED -> R.string.plan_missing_weight_old
    MissingField.BIRTH_YEAR -> R.string.plan_missing_birth_year
    MissingField.ACTIVITY -> R.string.plan_missing_activity
}
