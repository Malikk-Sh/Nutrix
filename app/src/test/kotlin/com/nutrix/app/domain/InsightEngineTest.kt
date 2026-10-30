package com.nutrix.app.domain

import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.model.AfterMealFeeling
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.model.SymptomType
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.database.entity.SleepEntity
import com.nutrix.app.database.entity.SymptomEntity
import com.nutrix.app.database.entity.WeightEntity
import com.nutrix.app.domain.insights.Coverage
import com.nutrix.app.domain.insights.EvidenceGroup
import com.nutrix.app.domain.insights.RecordType
import com.nutrix.app.domain.insights.FluidSummary
import com.nutrix.app.domain.insights.Insight
import com.nutrix.app.domain.insights.InsightEngine
import com.nutrix.app.domain.insights.InsightInput
import com.nutrix.app.domain.insights.InsightTone
import com.nutrix.app.domain.insights.LateCaffeine
import com.nutrix.app.domain.insights.LateMeals
import com.nutrix.app.domain.insights.MealDiscomfort
import com.nutrix.app.domain.insights.MealRhythm
import com.nutrix.app.domain.insights.NutritionSummary
import com.nutrix.app.domain.insights.SleepRhythm
import com.nutrix.app.domain.insights.StepsTrend
import com.nutrix.app.domain.insights.SymptomsAfterGaps
import com.nutrix.app.domain.insights.WeightTrend
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class InsightEngineTest {
    private val zone = "UTC"
    private val today = LocalDate.of(2026, 10, 29).toEpochDay()
    private val window = InsightEngine.window(today)
    private val days = window.toList()

    private fun at(day: Long, h: Int, m: Int = 0): Long =
        LocalDate.ofEpochDay(day).atTime(LocalTime.of(h, m)).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun meal(day: Long, h: Int, m: Int = 0, kcal: Int? = null, grams: Int? = null, feelings: List<AfterMealFeeling> = emptyList(),
        title: String? = null) =
        MealEntity(epochMillis = at(day, h, m), zoneId = zone, localDate = day, mealType = MealType.OTHER, title = title,
            caloriesEstimate = kcal, portionGrams = grams, feelings = feelings)

    private fun drink(day: Long, h: Int, ml: Int, type: DrinkType = DrinkType.WATER) =
        DrinkEntity(epochMillis = at(day, h), zoneId = zone, localDate = day, drinkType = type, volumeMl = ml)

    /** A night that starts on [day] at [startH]:[startM] and lasts [minutes]; it belongs to the next morning. */
    private fun night(day: Long, startH: Int, minutes: Int, startM: Int = 0): SleepEntity {
        val start = at(day, startH, startM)
        val end = start + minutes * 60_000L
        val wakeDay = java.time.Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
        return SleepEntity(startMillis = start, endMillis = end, zoneId = zone, localDate = wakeDay, durationMinutes = minutes)
    }

    private fun run(
        meals: List<MealEntity> = emptyList(), drinks: List<DrinkEntity> = emptyList(), sleeps: List<SleepEntity> = emptyList(),
        weights: List<WeightEntity> = emptyList(), symptoms: List<SymptomEntity> = emptyList(), activity: List<DailyActivity> = emptyList(),
        goal: Int? = null, target: Double? = null, confirmed: Set<Long> = emptySet(),
    ): List<Insight> = InsightEngine.analyze(InsightInput(
        AnalyticsInput(window.first, window.last, meals, drinks, weights, symptoms, sleeps, activity, confirmedDays = confirmed), goal, target,
    ))

    private inline fun <reified T : Insight> List<Insight>.find(): T? = filterIsInstance<T>().firstOrNull()

    @Test
    fun `steady meal times are recognised as a regular rhythm`() {
        val result = run(meals = days.flatMap { listOf(meal(it, 9), meal(it, 13, 30), meal(it, 19)) })
        val rhythm = result.find<MealRhythm>()!!
        assertThat(rhythm.firstMedian).isEqualTo(9 * 60)
        assertThat(rhythm.lastMedian).isEqualTo(19 * 60)
        assertThat(rhythm.windowMedianMinutes).isEqualTo(10 * 60)
        assertThat(rhythm.tone).isEqualTo(InsightTone.POSITIVE)
        assertThat(result.find<Coverage>()).isNull()
    }

    @Test
    fun `first meal moving around the clock needs attention and counts late starts`() {
        val result = run(meals = days.flatMapIndexed { i, d -> listOf(meal(d, if (i % 2 == 0) 7 else 13), meal(d, 20)) })
        val rhythm = result.find<MealRhythm>()!!
        assertThat(rhythm.firstSpreadMinutes).isAtLeast(300)
        assertThat(rhythm.lateStartDays).isEqualTo(14)
        assertThat(rhythm.tone).isEqualTo(InsightTone.ATTENTION)
    }

    @Test
    fun `a snack after midnight does not become the start of the eating day`() {
        val result = run(meals = days.flatMap { listOf(meal(it, 0, 30), meal(it, 9), meal(it, 19)) })
        assertThat(result.find<MealRhythm>()!!.firstMedian).isEqualTo(9 * 60)
    }

    @Test
    fun `late dinners are linked to shorter sleep only with enough nights of both kinds`() {
        val meals = days.mapIndexed { i, d -> meal(d, if (i % 2 == 0) 22 else 19) }
        val sleeps = days.mapIndexed { i, d -> night(d, 23, if (i % 2 == 0) 6 * 60 else 7 * 60 + 30) }
        val late = run(meals = meals, sleeps = sleeps).find<LateMeals>()!!
        assertThat(late.beforeSleep).isTrue()
        assertThat(late.lateDays).isEqualTo(14)
        assertThat(late.sleepDifferenceMinutes).isEqualTo(-90)
        assertThat(late.isLink).isTrue()
        // Each evening can be opened, the late ones and the ones they are compared with: newest first,
        // with the meal time, how long before sleep and the night's sleep.
        val (match, other) = late.evidence.partition { it.group == EvidenceGroup.MATCH }
        assertThat(match).hasSize(14)
        assertThat(other).hasSize(14)
        assertThat(late.evidence.map { it.day }).isInOrder(Comparator.reverseOrder<Long>())
        assertThat(match.first().clock).isEqualTo(22 * 60)
        assertThat(match.first().minutes).isEqualTo(60)
        assertThat(match.first().value).isEqualTo(6 * 60)
        assertThat(other.first().value).isEqualTo(7 * 60 + 30)
        assertThat(match.map { it.day }.toSet()).isEqualTo(days.filterIndexed { i, _ -> i % 2 == 0 }.toSet())
        assertThat(late.evidence.all { it.record?.type == RecordType.MEAL }).isTrue()
        assertThat(late.facts().toString()).doesNotContain(LocalDate.ofEpochDay(late.evidence.first().day).toString())
    }

    @Test
    fun `without sleep data late meals fall back to the clock`() {
        val late = run(meals = days.mapIndexed { i, d -> meal(d, if (i < 10) 22 else 19, 30) }).find<LateMeals>()!!
        assertThat(late.beforeSleep).isFalse()
        assertThat(late.lateDays).isEqualTo(10)
        val match = late.evidence.filter { it.group == EvidenceGroup.MATCH }
        assertThat(match.map { it.day }.toSet()).isEqualTo(days.take(10).toSet())
        assertThat(match.all { it.clock == 22 * 60 + 30 }).isTrue()
        assertThat(late.evidence.filter { it.group == EvidenceGroup.OTHER }.map { it.day }.toSet()).isEqualTo(days.drop(10).toSet())
    }

    @Test
    fun `too few days give only the coverage hint`() {
        val result = run(meals = days.takeLast(5).map { meal(it, 9) })
        assertThat(result).hasSize(1)
        assertThat((result.single() as Coverage).days).isEqualTo(5)
    }

    @Test
    fun `evening share of estimated calories is reported`() {
        val result = run(meals = days.flatMap { listOf(meal(it, 9, kcal = 300), meal(it, 19, kcal = 900)) }, confirmed = days.toSet())
        val nutrition = result.find<NutritionSummary>()!!
        assertThat(nutrition.kcal).isEqualTo(1200)
        assertThat(nutrition.eveningShare).isWithin(1e-9).of(0.75)
        assertThat(nutrition.tone).isEqualTo(InsightTone.ATTENTION)
        assertThat(nutrition.protein).isNull()
    }

    @Test
    fun `days that are not confirmed or not fully estimated are not a day's intake`() {
        // Four meals a day, three of them estimated: without the fourth, 900 kcal is not what was eaten.
        val partial = days.flatMap { listOf(meal(it, 8, kcal = 300), meal(it, 12, kcal = 300), meal(it, 16, kcal = 300), meal(it, 19)) }
        assertThat(run(meals = partial, confirmed = days.toSet()).find<NutritionSummary>()).isNull()
        // Fully estimated but never confirmed as fully recorded: no averages either.
        val estimated = days.flatMap { listOf(meal(it, 9, kcal = 500), meal(it, 19, kcal = 700)) }
        assertThat(run(meals = estimated).find<NutritionSummary>()).isNull()
        // Confirmed days count, drinks with calories included and water left out; unconfirmed days stay out.
        val juice = days.map { DrinkEntity(epochMillis = at(it, 20), zoneId = zone, localDate = it, drinkType = DrinkType.JUICE, volumeMl = 200,
            nutrition = com.nutrix.app.core.model.DrinkNutrition(50.0)) } + days.map { drink(it, 10, 500) }
        val nutrition = run(meals = estimated, drinks = juice, confirmed = days.take(10).toSet()).find<NutritionSummary>()!!
        assertThat(nutrition.days).isEqualTo(10)
        assertThat(nutrition.kcal).isEqualTo(1300)
        assertThat(nutrition.eveningShare).isWithin(1e-9).of(800.0 / 1300)
    }

    @Test
    fun `coffee late in the day is linked with later bedtimes`() {
        val drinks = days.mapIndexed { i, d -> drink(d, if (i % 2 == 0) 17 else 9, 250, DrinkType.COFFEE) }
        val sleeps = days.mapIndexed { i, d -> if (i % 2 == 0) night(d + 1, 1, 6 * 60) else night(d, 23, 8 * 60) }
        val caffeine = run(drinks = drinks, sleeps = sleeps).find<LateCaffeine>()!!
        assertThat(caffeine.bedtimeShiftMinutes).isEqualTo(120)
        assertThat(caffeine.sleepDifferenceMinutes).isEqualTo(-120)
        assertThat(caffeine.lateDays).isEqualTo(14)
        val (match, other) = caffeine.evidence.partition { it.group == EvidenceGroup.MATCH }
        assertThat(match).hasSize(14)
        assertThat(match.all { it.clock == 17 * 60 && it.minutes == 6 * 60 && it.record?.type == RecordType.DRINK }).isTrue()
        // Nights without late coffee are listed too, with their sleep and nothing to open but the day.
        assertThat(other).hasSize(14)
        assertThat(other.all { it.clock == null && it.minutes == 8 * 60 && it.record == null }).isTrue()
    }

    @Test
    fun `fluid guideline days and morning share are counted`() {
        val drinks = days.flatMapIndexed { i, d -> listOf(drink(d, 9, 500), drink(d, 17, if (i < 21) 1500 else 500)) }
        val fluid = run(drinks = drinks, goal = 1800).find<FluidSummary>()!!
        assertThat(fluid.goalDays).isEqualTo(21)
        assertThat(fluid.morningShare).isWithin(1e-9).of(0.25)
        assertThat(fluid.tone).isEqualTo(InsightTone.POSITIVE)
    }

    @Test
    fun `sleep rhythm reports weekend shift`() {
        val sleeps = days.map { d ->
            val weekend = LocalDate.ofEpochDay(d + 1).dayOfWeek.value >= 6
            night(d, 23, if (weekend) 10 * 60 else 8 * 60)
        }
        val sleep = run(sleeps = sleeps).find<SleepRhythm>()!!
        assertThat(sleep.weekendShiftMinutes).isEqualTo(120)
        assertThat(sleep.bedtimeMedian).isEqualTo(23 * 60)
        assertThat(sleep.tone).isEqualTo(InsightTone.ATTENTION)
    }

    @Test
    fun `weight moving towards the user's own target reads positive`() {
        val weights = days.filterIndexed { i, _ -> i % 3 == 0 }.map { d ->
            WeightEntity(epochMillis = at(d, 8), zoneId = zone, localDate = d, weightKg = 60.0 - (d - days.first()) * 0.05)
        }
        val trend = run(weights = weights, target = 57.0).find<WeightTrend>()!!
        assertThat(trend.kgPerWeek).isWithin(1e-6).of(-0.35)
        assertThat(trend.toTargetKg!!).isLessThan(0.0)
        assertThat(trend.tone).isEqualTo(InsightTone.POSITIVE)
    }

    @Test
    fun `a drop in steps over the last week needs attention`() {
        val activity = days.map { DailyActivity(localDate = it, steps = if (it > window.last - 7) 4000 else 9000) }
        val steps = run(activity = activity).find<StepsTrend>()!!
        assertThat(steps.recentAverage).isEqualTo(4000)
        assertThat(steps.tone).isEqualTo(InsightTone.ATTENTION)
    }

    @Test
    fun `discomfort after the user's larger portions is compared with other meals`() {
        val meals = days.take(12).flatMapIndexed { i, d ->
            listOf(
                meal(d, 13, grams = 600, feelings = listOf(if (i % 4 == 0) AfterMealFeeling.NORMAL else AfterMealFeeling.HEAVINESS)),
                meal(d, 8, grams = 250, feelings = listOf(AfterMealFeeling.NORMAL)),
                meal(d, 19, grams = 300, feelings = listOf(if (i == 0) AfterMealFeeling.BLOATING else AfterMealFeeling.NORMAL)),
            )
        }
        val discomfort = run(meals = meals).find<MealDiscomfort>()!!
        assertThat(discomfort.largeWith).isEqualTo(9)
        assertThat(discomfort.largeTotal).isEqualTo(12)
        assertThat(discomfort.largeFromGrams).isEqualTo(600)
    }

    @Test
    fun `weakness long after meals is compared with how often such gaps happen`() {
        val meals = days.flatMap { listOf(meal(it, 8), meal(it, 19)) }
        val late = days.take(6).map { SymptomEntity(epochMillis = at(it, 16), zoneId = zone, localDate = it, type = SymptomType.WEAKNESS) }
        val found = run(meals = meals, symptoms = late).find<SymptomsAfterGaps>()!!
        assertThat(found.afterGap).isEqualTo(6)
        assertThat(found.baselineShare).isLessThan(0.5)

        val soonAfter = days.take(6).map { SymptomEntity(epochMillis = at(it, 9), zoneId = zone, localDate = it, type = SymptomType.WEAKNESS) }
        assertThat(run(meals = meals, symptoms = soonAfter).find<SymptomsAfterGaps>()).isNull()
    }

    @Test
    fun `attention comes first and facts never carry diary text`() {
        val meals = days.flatMapIndexed { i, d -> listOf(meal(d, if (i % 2 == 0) 7 else 13, title = "Секретное блюдо"), meal(d, 20)) }
        val result = run(meals = meals, drinks = days.map { drink(it, 10, 1800) }, goal = 1500)
        assertThat(result.first().tone).isEqualTo(InsightTone.ATTENTION)
        assertThat(result.flatMap { it.facts().values }.map { it.toString() }.none { "Секрет" in it }).isTrue()
        assertThat(result.map { it.id }.toSet()).hasSize(result.size)
    }
}
