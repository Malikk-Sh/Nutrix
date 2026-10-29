@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.nutrix.app.core.model.ThemeMode
import com.nutrix.app.R

// Brand palette: deep graphite with teal, green and soft blue accents.
private val Teal = Color(0xFF54D9BA)
private val TealDeep = Color(0xFF087F70)
private val Green = Color(0xFF72D9A3)
private val GreenDeep = Color(0xFF147A4C)
private val SoftBlue = Color(0xFF80BFFF)
private val BlueDeep = Color(0xFF2563AA)

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
    background = Color(0xFF101619),
    onBackground = Color(0xFFF2F5F3),
    surface = Color(0xFF101619),
    onSurface = Color(0xFFF2F5F3),
    surfaceVariant = Color(0xFF263238),
    onSurfaceVariant = Color(0xFFACBBC0),
    surfaceContainerLowest = Color(0xFF0C1215),
    surfaceContainerLow = Color(0xFF141D21),
    surfaceContainer = Color(0xFF1A2327),
    surfaceContainerHigh = Color(0xFF222D32),
    surfaceContainerHighest = Color(0xFF2B373D),
    outline = Color(0xFF52656E),
    outlineVariant = Color(0xFF334047),
    error = Color(0xFFFF958E),
    onError = Color(0xFF3B0907),
    errorContainer = Color(0xFF4A1A17),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFF2F5F3),
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
    background = Color(0xFFF2F5F3),
    onBackground = Color(0xFF15211F),
    surface = Color(0xFFF2F5F3),
    onSurface = Color(0xFF15211F),
    surfaceVariant = Color(0xFFE2E7EA),
    onSurfaceVariant = Color(0xFF52635E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFCFA),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEDF3EF),
    surfaceContainerHighest = Color(0xFFE5EEE9),
    outline = Color(0xFFB5C9BF),
    outlineVariant = Color(0xFFD7E2DE),
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
    meal = Color(0xFF72D9A3),
    water = Color(0xFF80BFFF),
    weight = Color(0xFF54D9BA),
    sleep = Color(0xFFB5A8F5),
    activity = Color(0xFFF0C36A),
    symptom = Color(0xFFF7AB91),
    note = Color(0xFFB9C7CD),
    heart = Color(0xFFF58EA7),
)

private val LightCategories = CategoryColors(
    meal = Color(0xFF147A4C),
    water = Color(0xFF2563AA),
    weight = Color(0xFF087F70),
    sleep = Color(0xFF5B5BD6),
    activity = Color(0xFFB7791F),
    symptom = Color(0xFFD9541E),
    note = Color(0xFF5F6B73),
    heart = Color(0xFFD6324F),
)

val LocalCategoryColors = staticCompositionLocalOf { DarkCategories }

private val NutrixFont = FontFamily(
    Font(R.font.golos_text, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.golos_text, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.golos_text, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.golos_text, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private val NutrixTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = NutrixFont, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
        displayMedium = displayMedium.copy(fontFamily = NutrixFont, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
        displaySmall = displaySmall.copy(fontFamily = NutrixFont, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
        headlineLarge = headlineLarge.copy(fontFamily = NutrixFont, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
        headlineMedium = headlineMedium.copy(fontFamily = NutrixFont, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontFamily = NutrixFont, fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontFamily = NutrixFont, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = NutrixFont, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontFamily = NutrixFont, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontFamily = NutrixFont),
        bodyMedium = bodyMedium.copy(fontFamily = NutrixFont, lineHeight = 21.sp),
        bodySmall = bodySmall.copy(fontFamily = NutrixFont, lineHeight = 18.sp),
        labelLarge = labelLarge.copy(fontFamily = NutrixFont, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(fontFamily = NutrixFont, fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.copy(fontFamily = NutrixFont, fontWeight = FontWeight.Medium),
    )
}

/** Large numbers on cards. */
val MetricTextStyle = TextStyle(fontFamily = NutrixFont, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp, fontFeatureSettings = "tnum")

private val NutrixShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
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
    val CardPadding = 14.dp
    val CardSpacing = 10.dp
}
