package com.nutrix.app.feature.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.datastore.UserSettings
import com.nutrix.app.core.model.ActivityLevel
import com.nutrix.app.core.model.FormulaSex
import com.nutrix.app.core.model.NutritionGoal
import com.nutrix.app.core.model.PlanSource
import com.nutrix.app.core.model.SpecialCircumstance
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.data.repository.IntakePlanRepository
import com.nutrix.app.data.repository.WeightRepository
import com.nutrix.app.database.entity.IntakePlanEntity
import com.nutrix.app.database.entity.WeightEntity
import com.nutrix.app.domain.GramRange
import com.nutrix.app.domain.GuidelineInputs
import com.nutrix.app.domain.GuidelineResult
import com.nutrix.app.domain.IntakePlans
import com.nutrix.app.domain.MaintenanceEstimate
import com.nutrix.app.domain.MaintenanceGuideline
import com.nutrix.app.domain.PlanConsistency
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject

data class PlanUiState(
    val settings: UserSettings,
    val latestWeight: WeightEntity?,
    val plans: List<IntakePlanEntity>,
    val result: GuidelineResult,
    /** The guideline today has, if any. */
    val active: IntakePlanEntity?,
    val today: Long,
)

/** A specialist's or the user's own guideline as typed; every bound is optional except the calorie range. */
data class ManualPlanInput(
    val kcalMin: String = "",
    val kcalMax: String = "",
    val proteinMin: String = "",
    val proteinMax: String = "",
    val fatMin: String = "",
    val fatMax: String = "",
    val carbsMin: String = "",
    val carbsMax: String = "",
    val note: String = "",
) {
    private fun int(t: String, range: IntRange) = t.trim().toIntOrNull()?.takeIf { it in range }
    private fun pair(a: String, b: String, range: IntRange): GramRange? {
        val min = int(a, range) ?: return null
        val max = int(b, range) ?: return null
        return if (min <= max) GramRange(min, max) else null
    }
    private fun badPair(a: String, b: String, range: IntRange) = (a.isNotBlank() || b.isNotBlank()) && pair(a, b, range) == null

    val kcal: GramRange? get() = pair(kcalMin, kcalMax, 500..10000)
    val protein: GramRange? get() = pair(proteinMin, proteinMax, 0..1000)
    val fat: GramRange? get() = pair(fatMin, fatMax, 0..1000)
    val carbs: GramRange? get() = pair(carbsMin, carbsMax, 0..1500)

    /** A calorie range is required; a macronutrient is either left empty or given as a whole range. */
    val invalid: Boolean
        get() = kcal == null || badPair(proteinMin, proteinMax, 0..1000) || badPair(fatMin, fatMax, 0..1000) || badPair(carbsMin, carbsMax, 0..1500)

    val consistency: PlanConsistency get() = PlanConsistency.of(kcal?.min, kcal?.max, protein, fat, carbs)

    fun toPlan(): IntakePlanEntity? {
        if (invalid) return null
        val k = kcal ?: return null
        return IntakePlanEntity(
            startDay = 0, source = PlanSource.MANUAL, kcalMin = k.min, kcalMax = k.max,
            proteinMin = protein?.min, proteinMax = protein?.max, fatMin = fat?.min, fatMax = fat?.max,
            carbsMin = carbs?.min, carbsMax = carbs?.max, note = note.trim().ifBlank { null },
        )
    }

    companion object {
        fun of(plan: IntakePlanEntity?): ManualPlanInput = if (plan?.source != PlanSource.MANUAL) ManualPlanInput() else ManualPlanInput(
            plan.kcalMin?.toString().orEmpty(), plan.kcalMax?.toString().orEmpty(),
            plan.proteinMin?.toString().orEmpty(), plan.proteinMax?.toString().orEmpty(),
            plan.fatMin?.toString().orEmpty(), plan.fatMax?.toString().orEmpty(),
            plan.carbsMin?.toString().orEmpty(), plan.carbsMax?.toString().orEmpty(), plan.note.orEmpty(),
        )
    }
}

@HiltViewModel
class IntakePlanViewModel @Inject constructor(
    private val settings: SettingsRepository,
    weights: WeightRepository,
    private val plans: IntakePlanRepository,
) : ViewModel() {
    /** Profile edits read the stored profile and write it whole, one at a time, so quick taps never undo each other. */
    private val writes = Mutex()

    val state: StateFlow<PlanUiState?> = combine(settings.settings, weights.observeLatest(), plans.observeAll()) { s, weight, all ->
        val today = TimeUtils.todayEpochDay()
        PlanUiState(
            settings = s,
            latestWeight = weight,
            plans = all,
            result = MaintenanceGuideline.evaluate(GuidelineInputs(
                goal = s.nutritionGoal, heightCm = s.heightCm, weightKg = weight?.weightKg, weightDay = weight?.localDate,
                birthYear = s.birthYear, sex = s.formulaSex, activity = s.activityLevel, circumstances = s.circumstances,
                today = today, currentYear = LocalDate.ofEpochDay(today).year,
            )),
            active = IntakePlans.activeOn(today, all),
            today = today,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private fun profile(change: (UserSettings) -> UserSettings) {
        viewModelScope.launch {
            writes.withLock {
                val s = change(settings.current())
                settings.setNutritionProfile(s.nutritionGoal, s.birthYear, s.formulaSex, s.activityLevel, s.circumstances)
            }
        }
    }

    fun setGoal(goal: NutritionGoal) = profile { it.copy(nutritionGoal = goal) }
    fun setBirthYear(year: Int?) = profile { it.copy(birthYear = year) }
    fun setSex(sex: FormulaSex?) = profile { it.copy(formulaSex = sex) }
    fun setActivity(activity: ActivityLevel) = profile { it.copy(activityLevel = activity) }
    fun toggle(circumstance: SpecialCircumstance) = profile {
        it.copy(circumstances = if (circumstance in it.circumstances) it.circumstances - circumstance else it.circumstances + circumstance)
    }

    fun setHeight(cm: Int?) {
        viewModelScope.launch { writes.withLock { settings.setHeight(cm) } }
    }

    /** Accepting starts the guideline today; the days before keep the guideline they had. */
    fun accept(estimate: MaintenanceEstimate) {
        viewModelScope.launch {
            plans.startToday(IntakePlanEntity(
                startDay = 0, source = PlanSource.FORMULA, kcalMin = estimate.kcalMin, kcalMax = estimate.kcalMax,
                proteinMin = estimate.protein.min, proteinMax = estimate.protein.max, fatMin = estimate.fat.min, fatMax = estimate.fat.max,
                carbsMin = estimate.carbs.min, carbsMax = estimate.carbs.max, method = MaintenanceGuideline.METHOD, basis = estimate.basis,
            ))
        }
    }

    fun saveManual(input: ManualPlanInput) {
        val plan = input.toPlan() ?: return
        viewModelScope.launch { plans.startToday(plan) }
    }

    fun turnOff() {
        viewModelScope.launch { plans.startToday(IntakePlanEntity(startDay = 0, source = PlanSource.NONE)) }
    }
}

/** True when [plan] is the guideline [estimate] would save, so accepting again changes nothing. */
fun IntakePlanEntity.matches(estimate: MaintenanceEstimate): Boolean =
    source == PlanSource.FORMULA && kcalMin == estimate.kcalMin && kcalMax == estimate.kcalMax && basis == estimate.basis
