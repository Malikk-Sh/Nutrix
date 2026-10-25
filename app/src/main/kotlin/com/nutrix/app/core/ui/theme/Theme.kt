package com.nutrix.app.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.nutrix.app.core.model.ThemeMode

// Brand palette: deep graphite with teal, green and soft blue accents.
private val Teal = Color(0xFF2DD4BF)
private val TealDeep = Color(0xFF0F9C8C)
private val Green = Color(0xFF4ADE80)
private val GreenDeep = Color(0xFF16A34A)
private val SoftBlue = Color(0xFF7AB4FF)
private val BlueDeep = Color(0xFF2F6FDB)

private val DarkScheme = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF0E3B36),
    onPrimaryContainer = Color(0xFFB4F5EA),
    secondary = Green,
    onSecondary = Color(0xFF032611),
    secondaryContainer = Color(0xFF123824),
    onSecondaryContainer = Color(0xFFC3F5D4),
    tertiary = SoftBlue,
    onTertiary = Color(0xFF04203F),
    tertiaryContainer = Color(0xFF15304F),
    onTertiaryContainer = Color(0xFFD3E4FF),
    background = Color(0xFF0E1012),
    onBackground = Color(0xFFE7E9EB),
    surface = Color(0xFF0E1012),
    onSurface = Color(0xFFE7E9EB),
    surfaceVariant = Color(0xFF22272B),
    onSurfaceVariant = Color(0xFFA9B0B6),
    surfaceContainerLowest = Color(0xFF0A0C0D),
    surfaceContainerLow = Color(0xFF14171A),
    surfaceContainer = Color(0xFF181C1F),
    surfaceContainerHigh = Color(0xFF1E2226),
    surfaceContainerHighest = Color(0xFF262B30),
    outline = Color(0xFF3A4147),
    outlineVariant = Color(0xFF2A3035),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0907),
    errorContainer = Color(0xFF4A1A17),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE7E9EB),
    inverseOnSurface = Color(0xFF1A1D20),
    scrim = Color(0xFF000000),
)

private val LightScheme = lightColorScheme(
    primary = TealDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9F3EC),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = GreenDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3F5DE),
    onSecondaryContainer = Color(0xFF032611),
    tertiary = BlueDeep,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE8FF),
    onTertiaryContainer = Color(0xFF04203F),
    background = Color(0xFFF4F6F7),
    onBackground = Color(0xFF15191C),
    surface = Color(0xFFF4F6F7),
    onSurface = Color(0xFF15191C),
    surfaceVariant = Color(0xFFE2E7EA),
    onSurfaceVariant = Color(0xFF545D64),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBFB),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEFF2F4),
    surfaceContainerHighest = Color(0xFFE6EAED),
    outline = Color(0xFFBCC4CA),
    outlineVariant = Color(0xFFDCE1E5),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/** Category accents used consistently on cards, charts, calendar dots and the timeline. */
@Immutable
data class CategoryColors(
    val meal: Color,
    val water: Color,
    val weight: Color,
    val sleep: Color,
    val activity: Color,
    val symptom: Color,
    val note: Color,
    val heart: Color,
)

private val DarkCategories = CategoryColors(
    meal = Color(0xFF4ADE80),
    water = Color(0xFF7AB4FF),
    weight = Color(0xFF2DD4BF),
    sleep = Color(0xFFA5A6F6),
    activity = Color(0xFFF7C35F),
    symptom = Color(0xFFFF9F7A),
    note = Color(0xFFB8C0C6),
    heart = Color(0xFFFF7A90),
)

private val LightCategories = CategoryColors(
    meal = Color(0xFF16A34A),
    water = Color(0xFF2F6FDB),
    weight = Color(0xFF0F9C8C),
    sleep = Color(0xFF5B5BD6),
    activity = Color(0xFFB7791F),
    symptom = Color(0xFFD9541E),
    note = Color(0xFF5F6B73),
    heart = Color(0xFFD6324F),
)

val LocalCategoryColors = staticCompositionLocalOf { DarkCategories }

private val NutrixTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.Medium),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

/** Large numbers on cards. */
val MetricTextStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)

private val NutrixShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}

@Composable
fun NutrixTheme(themeMode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = isDarkTheme(themeMode)
    val scheme: ColorScheme = if (dark) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalCategoryColors provides if (dark) DarkCategories else LightCategories) {
        MaterialTheme(
            colorScheme = scheme,
            typography = NutrixTypography,
            shapes = NutrixShapes,
            content = content,
        )
    }
}

object NutrixTokens {
    val ScreenPadding = 16.dp
    val CardPadding = 18.dp
    val CardSpacing = 12.dp
}
