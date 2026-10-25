package com.nutrix.app.core.ui

import androidx.annotation.StringRes
import com.nutrix.app.R
import com.nutrix.app.core.model.AfterMealFeeling
import com.nutrix.app.core.model.BodyPosition
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.EntryCategory
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.model.PortionEaten
import com.nutrix.app.core.model.Satiety
import com.nutrix.app.core.model.SymptomType

@get:StringRes
val MealType.labelRes: Int
    get() = when (this) {
        MealType.BREAKFAST -> R.string.meal_breakfast
        MealType.LUNCH -> R.string.meal_lunch
        MealType.DINNER -> R.string.meal_dinner
        MealType.SNACK -> R.string.meal_snack
        MealType.OTHER -> R.string.meal_other
    }

@get:StringRes
val PortionEaten.labelRes: Int
    get() = when (this) {
        PortionEaten.ALL -> R.string.portion_all
        PortionEaten.P75 -> R.string.portion_75
        PortionEaten.P50 -> R.string.portion_50
        PortionEaten.P25 -> R.string.portion_25
        PortionEaten.FEW_BITES -> R.string.portion_few
    }

@get:StringRes
val Satiety.labelRes: Int
    get() = when (this) {
        Satiety.NOT_FULL -> R.string.satiety_not_full
        Satiety.NORMAL -> R.string.satiety_normal
        Satiety.QUICK -> R.string.satiety_quick
        Satiety.OVERFULL -> R.string.satiety_overfull
    }

@get:StringRes
val AfterMealFeeling.labelRes: Int
    get() = when (this) {
        AfterMealFeeling.NORMAL -> R.string.feeling_normal
        AfterMealFeeling.NAUSEA -> R.string.feeling_nausea
        AfterMealFeeling.BELCHING -> R.string.feeling_belching
        AfterMealFeeling.PAIN -> R.string.feeling_pain
        AfterMealFeeling.BLOATING -> R.string.feeling_bloating
        AfterMealFeeling.HEAVINESS -> R.string.feeling_heaviness
        AfterMealFeeling.OTHER -> R.string.feeling_other
    }

@get:StringRes
val DrinkType.labelRes: Int
    get() = when (this) {
        DrinkType.WATER -> R.string.drink_water
        DrinkType.TEA -> R.string.drink_tea
        DrinkType.COFFEE -> R.string.drink_coffee
        DrinkType.MILK -> R.string.drink_milk
        DrinkType.JUICE -> R.string.drink_juice
        DrinkType.OTHER -> R.string.drink_other
    }

@get:StringRes
val SymptomType.labelRes: Int
    get() = when (this) {
        SymptomType.NAUSEA -> R.string.symptom_nausea
        SymptomType.DIZZINESS -> R.string.symptom_dizziness
        SymptomType.DARKENING_VISION -> R.string.symptom_darkening_vision
        SymptomType.WEAKNESS -> R.string.symptom_weakness
        SymptomType.ABDOMINAL_PAIN -> R.string.symptom_abdominal_pain
        SymptomType.EARLY_SATIETY -> R.string.symptom_early_satiety
        SymptomType.BELCHING -> R.string.symptom_belching
        SymptomType.BLOATING -> R.string.symptom_bloating
        SymptomType.PALPITATIONS -> R.string.symptom_palpitations
        SymptomType.TREMOR -> R.string.symptom_tremor
        SymptomType.SWEATING -> R.string.symptom_sweating
        SymptomType.OTHER -> R.string.symptom_other
    }

@get:StringRes
val BodyPosition.labelRes: Int
    get() = when (this) {
        BodyPosition.LYING -> R.string.position_lying
        BodyPosition.SITTING -> R.string.position_sitting
        BodyPosition.STANDING -> R.string.position_standing
        BodyPosition.STANDING_UP -> R.string.position_standing_up
        BodyPosition.WALKING -> R.string.position_walking
        BodyPosition.EXERCISE -> R.string.position_exercise
        BodyPosition.EATING -> R.string.position_eating
        BodyPosition.OTHER -> R.string.position_other
    }

@get:StringRes
val DataSource.labelRes: Int
    get() = when (this) {
        DataSource.MANUAL -> R.string.source_manual
        DataSource.HEALTH_CONNECT -> R.string.source_health_connect
        DataSource.SAMSUNG_HEALTH -> R.string.source_samsung_health
    }

@get:StringRes
val EntryCategory.labelRes: Int
    get() = when (this) {
        EntryCategory.MEAL -> R.string.category_meal
        EntryCategory.DRINK -> R.string.category_drink
        EntryCategory.WEIGHT -> R.string.category_weight
        EntryCategory.SLEEP -> R.string.category_sleep
        EntryCategory.SYMPTOM -> R.string.category_symptom
        EntryCategory.NOTE -> R.string.category_note
        EntryCategory.ACTIVITY -> R.string.category_activity
    }

@StringRes
fun appetiteLabel(value: Int): Int = when (value) {
    0 -> R.string.appetite_0
    1 -> R.string.appetite_1
    2 -> R.string.appetite_2
    3 -> R.string.appetite_3
    4 -> R.string.appetite_4
    else -> R.string.appetite_5
}

@StringRes
fun intensityLabel(value: Int): Int = when (value) {
    0 -> R.string.intensity_0
    1 -> R.string.intensity_1
    2 -> R.string.intensity_2
    3 -> R.string.intensity_3
    4 -> R.string.intensity_4
    else -> R.string.intensity_5
}

/** Human-readable name of a package that wrote health data. */
fun sourceAppName(packageName: String?): String? = when (packageName) {
    null, "" -> null
    "com.sec.android.app.shealth" -> "Samsung Health"
    "com.google.android.apps.fitness" -> "Google Fit"
    "com.google.android.apps.healthdata" -> "Health Connect"
    "com.fitbit.FitbitMobile" -> "Fitbit"
    "com.garmin.android.apps.connectmobile" -> "Garmin Connect"
    "com.xiaomi.wearable", "com.mi.health" -> "Mi Fitness"
    "com.huawei.health" -> "Huawei Health"
    "com.withings.wiscale2" -> "Withings"
    "com.ouraring.oura" -> "Oura"
    "com.nutrix.app" -> "NUTRIX"
    else -> packageName
}
