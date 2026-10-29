package com.nutrix.app.domain

import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.model.MealDish
import com.nutrix.app.core.model.MealType
import com.nutrix.app.database.entity.MealEntity
import org.junit.Test

class MealTotalsTest {
    private val soup = MealDish(name = "Суп", servedGrams = 300.0, kcal = 250.0, proteinGrams = 10.0, photos = listOf("a.jpg"))
    private val main = MealDish(name = "Второе", servedGrams = 250.0, kcal = 400.0, proteinGrams = 30.0)
    private val drink = MealDish(name = "Компот", servedGrams = 200.0, kcal = 100.0, proteinGrams = 0.0)
    private fun meal(dishes: List<MealDish>?) = MealEntity(epochMillis = 0, zoneId = "UTC", localDate = 0, mealType = MealType.LUNCH, dishes = dishes)

    @Test fun `three dishes make one meal with their sum, and removing one takes away only its share`() {
        assertThat(MealTotals.of(listOf(soup, main, drink)).kcal).isEqualTo(DishTotal(750.0, 3, 3))
        assertThat(MealTotals.of(listOf(soup, drink)).kcal.value).isEqualTo(350.0)
        val saved = MealTotals.apply(meal(listOf(soup, main, drink)))
        assertThat(saved.caloriesEstimate).isEqualTo(750)
        assertThat(saved.portionGrams).isEqualTo(750)
        assertThat(saved.kcalIfComplete).isEqualTo(750)
        assertThat(saved.items).containsExactly("Суп", "Второе", "Компот").inOrder()
    }

    @Test fun `another photo of the same dish changes nothing`() {
        val twice = soup.copy(photos = listOf("a.jpg", "b.jpg"))
        assertThat(MealTotals.of(listOf(twice, main, drink))).isEqualTo(MealTotals.of(listOf(soup, main, drink)))
    }

    @Test fun `an unknown value stays unknown and the total says what it covers`() {
        val unknown = drink.copy(kcal = null)
        val totals = MealTotals.of(listOf(soup, main, unknown))
        assertThat(totals.kcal).isEqualTo(DishTotal(650.0, 2, 3))
        assertThat(totals.kcal.complete).isFalse()
        val saved = MealTotals.apply(meal(listOf(soup, main, unknown)))
        assertThat(saved.caloriesEstimate).isEqualTo(650)
        assertThat(saved.nutrition!!.kcalKnown).isEqualTo(2)
        assertThat(saved.nutrition!!.dishCount).isEqualTo(3)
        // A partial sum is never the meal's intake.
        assertThat(saved.kcalIfComplete).isNull()
        assertThat(MealTotals.of(listOf(MealDish(name = "?"))).kcal).isEqualTo(DishTotal(null, 0, 1))
    }

    @Test fun `the eaten share counts once, per dish`() {
        val half = main.copy(eatenShare = 0.5)
        val totals = MealTotals.of(listOf(soup, half))
        assertThat(totals.kcal.value).isEqualTo(450.0)
        assertThat(totals.grams.value).isEqualTo(425.0)
        assertThat(totals.protein.value).isEqualTo(25.0)
    }

    @Test fun `a meal without dishes keeps its own whole-meal estimate`() {
        val whole = meal(null).copy(caloriesEstimate = 600, portionGrams = 400)
        assertThat(MealTotals.apply(whole)).isEqualTo(whole)
        assertThat(whole.kcalIfComplete).isEqualTo(600)
        assertThat(MealTotals.apply(meal(emptyList())).dishes).isNull()
    }
}
