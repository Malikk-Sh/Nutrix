package com.nutrix.app.navigation

import kotlinx.serialization.Serializable

// Top-level destinations
@Serializable object DashboardRoute
@Serializable data class CalendarRoute(val epochDay: Long = -1)
@Serializable object AnalyticsRoute
@Serializable object ProfileRoute

// Diaries
@Serializable object FoodDiaryRoute
@Serializable data class FoodEditorRoute(val mealId: Long = 0, val favoriteId: Long = 0, val openCamera: Boolean = false, val repeatMealId: Long = 0)
@Serializable object FavoritesRoute
@Serializable object CameraRoute
@Serializable data class CropRoute(val fileName: String)
@Serializable data class WaterRoute(val openAdd: Boolean = false)
@Serializable data class DrinkEditorRoute(val drinkId: Long = 0)
@Serializable data class WeightRoute(val openAdd: Boolean = false)
@Serializable data class SymptomsRoute(val openAdd: Boolean = false)
@Serializable data class SymptomEditorRoute(val symptomId: Long = 0, val mealId: Long = 0)
@Serializable data class NoteEditorRoute(val noteId: Long = 0)
@Serializable object SleepActivityRoute
@Serializable data class SleepEditorRoute(val sleepId: Long = 0)
@Serializable data class DayRoute(val epochDay: Long)
@Serializable object SearchRoute
@Serializable object InsightsRoute

// Settings & tools
@Serializable object ProfileEditRoute
@Serializable object ReportsRoute
@Serializable object BackupRoute
@Serializable object IntegrationsRoute
@Serializable object RemindersRoute
@Serializable object SecurityRoute
@Serializable object GeminiSettingsRoute
@Serializable object AboutRoute

/** Keys for results passed back through SavedStateHandle. */
object NavResults {
    const val CAMERA_PHOTO = "camera_photo"
    const val CROPPED_PHOTO = "cropped_photo"
    const val CROP_SOURCE = "crop_source"
}

/** Deep-link actions coming from widgets and notifications. */
object AppActions {
    const val EXTRA_ACTION = "nutrix_action"
    const val ADD_FOOD_CAMERA = "add_food_camera"
    const val ADD_FOOD = "add_food"
    const val ADD_WATER = "add_water"
    const val ADD_WEIGHT = "add_weight"
    const val ADD_SYMPTOM = "add_symptom"
    const val OPEN_TODAY = "open_today"
}
