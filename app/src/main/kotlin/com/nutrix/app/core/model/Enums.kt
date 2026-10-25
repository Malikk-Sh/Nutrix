package com.nutrix.app.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class MealType { BREAKFAST, LUNCH, DINNER, SNACK, OTHER }

@Serializable
enum class PortionEaten(val fraction: Double) {
    ALL(1.0), P75(0.75), P50(0.5), P25(0.25), FEW_BITES(0.1)
}

@Serializable
enum class Satiety { NOT_FULL, NORMAL, QUICK, OVERFULL }

@Serializable
enum class AfterMealFeeling { NORMAL, NAUSEA, BELCHING, PAIN, BLOATING, HEAVINESS, OTHER }

@Serializable
enum class DrinkType { WATER, TEA, COFFEE, MILK, JUICE, OTHER }

@Serializable
enum class SymptomType {
    NAUSEA, DIZZINESS, DARKENING_VISION, WEAKNESS, ABDOMINAL_PAIN, EARLY_SATIETY,
    BELCHING, BLOATING, PALPITATIONS, TREMOR, SWEATING, OTHER
}

@Serializable
enum class BodyPosition { LYING, SITTING, STANDING, STANDING_UP, WALKING, EXERCISE, EATING, OTHER }

/** Where a record came from. Manual records are editable, imported ones are read-only. */
@Serializable
enum class DataSource { MANUAL, HEALTH_CONNECT, SAMSUNG_HEALTH }

@Serializable
enum class WeightUnit { KG, LB }

@Serializable
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Categories shown in the calendar, timeline filters and exports. */
@Serializable
enum class EntryCategory { MEAL, DRINK, WEIGHT, SLEEP, SYMPTOM, NOTE, ACTIVITY }
