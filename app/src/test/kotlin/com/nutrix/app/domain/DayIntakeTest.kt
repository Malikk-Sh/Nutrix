package com.nutrix.app.domain

import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.model.DrinkNutrition
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.MealDish
import com.nutrix.app.core.model.MealNutrition
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.model.NutrientBasis
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.MealEntity
import org.junit.Test

class DayIntakeTest {
    private val day = 20_000L

    private fun meal(dishes: List<MealDish>, at: Long = 0, d: Long = day) = MealTotals.apply(
        MealEntity(epochMillis = d * 86_400_000L + at, zoneId = "UTC", localDate = d, mealType = MealType.LUNCH, dishes = dishes))

    private fun drink(type: DrinkType, ml: Int, nutrition: DrinkNutrition? = null, d: Long = day) =
        DrinkEntity(epochMillis = d * 86_400_000L, zoneId = "UTC", localDate = d, drinkType = type, volumeMl = ml, nutrition = nutrition)

    private val soup = MealDish(name = "Суп", kcal = 250.0, proteinGrams = 10.0, fatGrams = 8.0, carbsGrams = 30.0)
    private val main = MealDish(name = "Второе", kcal = 400.0, proteinGrams = 30.0, fatGrams = 15.0, carbsGrams = 35.0)
    private val juice = DrinkNutrition(45.0, 0.5, 0.1, 10.0)

    private fun intake(meals: List<MealEntity>, drinks: List<DrinkEntity> = emptyList(), confirmed: Boolean = true, today: Long? = null) =
        DayIntakes.of(day, meals, drinks, confirmed, today)

    @Test fun `food and a caloric drink add up once, water and tea without calories are not records of the day`() {
        val d = intake(listOf(meal(listOf(soup, main))),
            listOf(drink(DrinkType.JUICE, 200, juice), drink(DrinkType.WATER, 500), drink(DrinkType.TEA, 300, DrinkNutrition.NONE)))
        assertThat(d.records).isEqualTo(2)
        assertThat(d.total(Nutrient.KCAL)).isEqualTo(PartialTotal(740.0, 2, 2))
        assertThat(d.fromMeals(Nutrient.KCAL)).isEqualTo(650.0)
        assertThat(d.fromDrinks(Nutrient.KCAL)).isEqualTo(90.0)
        assertThat(d.state(Nutrient.KCAL)).isEqualTo(DayState.COMPLETE)
    }

    @Test fun `an unknown drink makes the day partial and is never read as zero`() {
        val d = intake(listOf(meal(listOf(soup, main))), listOf(drink(DrinkType.MILK, 250)))
        assertThat(d.total(Nutrient.KCAL)).isEqualTo(PartialTotal(650.0, 1, 2))
        assertThat(d.state(Nutrient.KCAL)).isEqualTo(DayState.PARTIAL)
        assertThat(d.usable(Nutrient.KCAL)).isFalse()
    }

    @Test fun `a partly estimated meal adds its known part but leaves the day incomplete`() {
        val d = intake(listOf(meal(listOf(soup, main, MealDish(name = "Компот")))))
        assertThat(d.total(Nutrient.KCAL)).isEqualTo(PartialTotal(650.0, 0, 1))
        assertThat(d.state(Nutrient.KCAL)).isEqualTo(DayState.PARTIAL)
    }

    @Test fun `states are nothing recorded, still today, not confirmed and complete`() {
        val food = listOf(meal(listOf(soup)))
        assertThat(intake(emptyList(), listOf(drink(DrinkType.WATER, 300))).state(Nutrient.KCAL)).isEqualTo(DayState.NO_RECORDS)
        assertThat(intake(food, today = day).state(Nutrient.KCAL)).isEqualTo(DayState.TODAY)
        assertThat(intake(food, confirmed = false).state(Nutrient.KCAL)).isEqualTo(DayState.UNCONFIRMED)
        assertThat(intake(food).state(Nutrient.KCAL)).isEqualTo(DayState.COMPLETE)
    }

    @Test fun `each nutrient has its own coverage`() {
        val wholeMeal = MealEntity(epochMillis = day * 86_400_000L, zoneId = "UTC", localDate = day, mealType = MealType.DINNER,
            caloriesEstimate = 600, nutrition = MealNutrition(proteinGrams = 25.0))
        val d = intake(listOf(wholeMeal))
        assertThat(d.usable(Nutrient.KCAL)).isTrue()
        assertThat(d.usable(Nutrient.PROTEIN)).isTrue()
        assertThat(d.usable(Nutrient.FAT)).isFalse()
        assertThat(d.total(Nutrient.FAT)).isEqualTo(PartialTotal(null, 0, 1))
        assertThat(d.kcalFromMacros).isNull()
    }

    @Test fun `values per 100 ml grow with the volume, a value for the whole drink does not`() {
        val sugar = DrinkNutrition(20.0, 0.0, 0.0, 5.0, NutrientBasis.PER_DRINK)
        assertThat(drink(DrinkType.COFFEE, 500, sugar).intake.kcal).isEqualTo(20.0)
        assertThat(drink(DrinkType.JUICE, 500, juice).intake.kcal).isEqualTo(225.0)
        assertThat(drink(DrinkType.WATER, 500, juice).intake).isEqualTo(DrinkIntake.ZERO)
    }

    @Test fun `averages use complete finished days only and the range keeps empty days as gaps`() {
        val records = AnalyticsInput(
            from = day, to = day + 3,
            meals = listOf(meal(listOf(soup, main)), meal(listOf(main), d = day + 1), meal(listOf(soup), d = day + 3)) +
                meal(listOf(MealDish(name = "?")), d = day + 1),
            drinks = emptyList(), weights = emptyList(), symptoms = emptyList(), sleeps = emptyList(), activity = emptyList(),
            confirmedDays = setOf(day, day + 1, day + 3),
        )
        val days = DayIntakes.range(records, today = day + 3)
        assertThat(days.map { it.state(Nutrient.KCAL) })
            .containsExactly(DayState.COMPLETE, DayState.PARTIAL, DayState.NO_RECORDS, DayState.TODAY).inOrder()
        assertThat(DayIntakes.average(days, Nutrient.KCAL)).isEqualTo(DayIntakes.Average(650.0, 1))
        assertThat(days.first().kcalFromMacros).isEqualTo(40.0 * 4 + 23.0 * 9 + 65.0 * 4)
    }
}
