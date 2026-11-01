package com.nutrix.app.domain

import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.model.DrinkNutrition
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.MealDish
import com.nutrix.app.core.model.NutrientBasis
import org.junit.Test

class DishAsDrinkTest {
    @Test fun `served values become values per 100 ml and the volume is what was drunk`() {
        val compote = MealDish(name = "Компот", servedGrams = 250.0, eatenShare = 0.75, kcal = 100.0, carbsGrams = 25.0,
            estimateModel = "gemini-test", uncertainty = "Сахар неизвестен")
        assertThat(DishAsDrink.volume(compote)).isEqualTo(188)
        assertThat(DishAsDrink.nutrition(compote)).isEqualTo(DrinkNutrition(40.0, null, null, 10.0, NutrientBasis.PER_100_ML, "gemini-test", "Сахар неизвестен"))
    }

    @Test fun `without a served mass the eaten amounts stay for the whole drink and unknown energy stays unknown`() {
        val smoothie = MealDish(name = "Смузи", eatenShare = 0.5, kcal = 300.0, proteinGrams = 6.0)
        assertThat(DishAsDrink.volume(smoothie)).isNull()
        assertThat(DishAsDrink.nutrition(smoothie)).isEqualTo(DrinkNutrition(150.0, 3.0, null, null, NutrientBasis.PER_DRINK))
        assertThat(DishAsDrink.nutrition(MealDish(name = "Морс", servedGrams = 200.0))).isNull()
    }

    @Test fun `the type is only guessed from the name`() {
        assertThat(DishAsDrink.guessType("Капучино с сиропом")).isEqualTo(DrinkType.COFFEE)
        assertThat(DishAsDrink.guessType("Зелёный чай")).isEqualTo(DrinkType.TEA)
        assertThat(DishAsDrink.guessType("Кефир 1 %")).isEqualTo(DrinkType.MILK)
        assertThat(DishAsDrink.guessType("Компот из вишни")).isEqualTo(DrinkType.JUICE)
        assertThat(DishAsDrink.guessType("Смузи")).isEqualTo(DrinkType.OTHER)
    }
}
