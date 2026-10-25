package com.nutrix.app.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Blender
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.EmojiFoodBeverage
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalDrink
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.EntryCategory
import com.nutrix.app.core.ui.theme.LocalCategoryColors

val DrinkType.icon: ImageVector
    get() = when (this) {
        DrinkType.WATER -> Icons.Rounded.WaterDrop
        DrinkType.TEA -> Icons.Rounded.EmojiFoodBeverage
        DrinkType.COFFEE -> Icons.Rounded.Coffee
        DrinkType.MILK -> Icons.Rounded.LocalDrink
        DrinkType.JUICE -> Icons.Rounded.Blender
        DrinkType.OTHER -> Icons.Rounded.LocalCafe
    }

val EntryCategory.icon: ImageVector
    get() = when (this) {
        EntryCategory.MEAL -> Icons.Rounded.Restaurant
        EntryCategory.DRINK -> Icons.Rounded.WaterDrop
        EntryCategory.WEIGHT -> Icons.Rounded.MonitorWeight
        EntryCategory.SLEEP -> Icons.Rounded.Bedtime
        EntryCategory.SYMPTOM -> Icons.Rounded.Healing
        EntryCategory.NOTE -> Icons.Rounded.StickyNote2
        EntryCategory.ACTIVITY -> Icons.Rounded.DirectionsRun
    }

@Composable
@ReadOnlyComposable
fun EntryCategory.color(): Color {
    val c = LocalCategoryColors.current
    return when (this) {
        EntryCategory.MEAL -> c.meal
        EntryCategory.DRINK -> c.water
        EntryCategory.WEIGHT -> c.weight
        EntryCategory.SLEEP -> c.sleep
        EntryCategory.SYMPTOM -> c.symptom
        EntryCategory.NOTE -> c.note
        EntryCategory.ACTIVITY -> c.activity
    }
}
