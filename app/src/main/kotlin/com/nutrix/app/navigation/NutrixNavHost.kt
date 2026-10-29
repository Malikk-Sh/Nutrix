package com.nutrix.app.navigation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import com.nutrix.app.core.ui.icons.CalendarMonth
import androidx.compose.material.icons.rounded.Home
import com.nutrix.app.core.ui.icons.Insights
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.nutrix.app.R
import com.nutrix.app.backup.BackupScreen
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.core.ui.components.LocalSnackbarHost
import com.nutrix.app.feature.ai.GeminiSettingsScreen
import com.nutrix.app.feature.activity.SleepActivityScreen
import com.nutrix.app.feature.activity.SleepEditorScreen
import com.nutrix.app.feature.analytics.AnalyticsScreen
import com.nutrix.app.feature.calendar.CalendarScreen
import com.nutrix.app.feature.calendar.DayScreen
import com.nutrix.app.feature.calendar.TimelineTarget
import com.nutrix.app.feature.dashboard.DashboardActions
import com.nutrix.app.feature.dashboard.DashboardScreen
import com.nutrix.app.feature.food.CameraScreen
import com.nutrix.app.feature.food.CropScreen
import com.nutrix.app.feature.food.FavoritesScreen
import com.nutrix.app.feature.food.FoodDiaryScreen
import com.nutrix.app.feature.food.FoodEditorScreen
import com.nutrix.app.feature.notes.NoteEditorScreen
import com.nutrix.app.feature.reports.ReportsScreen
import com.nutrix.app.feature.search.SearchScreen
import com.nutrix.app.feature.settings.AboutScreen
import com.nutrix.app.feature.settings.ProfileActions
import com.nutrix.app.feature.settings.ProfileEditScreen
import com.nutrix.app.feature.settings.ProfileScreen
import com.nutrix.app.feature.settings.SecurityScreen
import com.nutrix.app.feature.symptoms.SymptomEditorScreen
import com.nutrix.app.feature.symptoms.SymptomsScreen
import com.nutrix.app.feature.water.DrinkEditorScreen
import com.nutrix.app.feature.water.WaterScreen
import com.nutrix.app.feature.weight.WeightScreen
import com.nutrix.app.integration.IntegrationsScreen
import com.nutrix.app.reminders.RemindersScreen
import kotlin.reflect.KClass

private data class TopLevel(val route: Any, val type: KClass<*>, val icon: ImageVector, val label: Int)

private val topLevels = listOf(
    TopLevel(DashboardRoute, DashboardRoute::class, Icons.Rounded.Home, R.string.nav_home),
    TopLevel(CalendarRoute(), CalendarRoute::class, Icons.Rounded.CalendarMonth, R.string.nav_calendar),
    TopLevel(AnalyticsRoute, AnalyticsRoute::class, Icons.Rounded.Insights, R.string.nav_analytics),
    TopLevel(ProfileRoute, ProfileRoute::class, Icons.Rounded.Person, R.string.nav_profile),
)

fun NavHostController.openTarget(target: TimelineTarget) = when (target) {
    is TimelineTarget.Meal -> navigate(FoodEditorRoute(mealId = target.id))
    is TimelineTarget.Drink -> navigate(DrinkEditorRoute(target.id))
    TimelineTarget.Weight -> navigate(WeightRoute())
    is TimelineTarget.Symptom -> navigate(SymptomEditorRoute(symptomId = target.id))
    is TimelineTarget.Note -> navigate(NoteEditorRoute(target.id))
    is TimelineTarget.Sleep -> if (target.editable) navigate(SleepEditorRoute(target.id)) else navigate(SleepActivityRoute)
    TimelineTarget.Activity -> navigate(SleepActivityRoute)
}

fun NavHostController.handleAppAction(action: String) {
    when (action) {
        AppActions.ADD_FOOD_CAMERA -> navigate(FoodEditorRoute(openCamera = true))
        AppActions.ADD_FOOD -> navigate(FoodEditorRoute())
        AppActions.ADD_WATER -> navigate(WaterRoute())
        AppActions.ADD_WEIGHT -> navigate(WeightRoute(openAdd = true))
        AppActions.ADD_SYMPTOM -> navigate(SymptomsRoute())
        AppActions.OPEN_TODAY -> navigate(DayRoute(TimeUtils.todayEpochDay()))
    }
}

@Composable
fun NutrixNavHost(
    pendingAction: String?,
    onActionConsumed: () -> Unit,
    cameraContent: @Composable (onCaptured: (String) -> Unit, onBack: () -> Unit) -> Unit = { captured, back -> CameraScreen(captured, back) },
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val isTopLevel = topLevels.any { t -> destination?.hierarchy?.any { it.hasRoute(t.type) } == true }
    var fabExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(pendingAction, backStack != null) {
        if (pendingAction != null && backStack != null) {
            nav.handleAppAction(pendingAction)
            onActionConsumed()
        }
    }

    fun selectTop(t: TopLevel) {
        nav.navigate(t.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun quick(a: QuickAction) {
        fabExpanded = false
        when (a) {
            QuickAction.FOOD -> nav.navigate(FoodEditorRoute())
            QuickAction.WATER -> nav.navigate(WaterRoute())
            QuickAction.WEIGHT -> nav.navigate(WeightRoute(openAdd = true))
            QuickAction.SYMPTOM -> nav.navigate(SymptomsRoute(openAdd = true))
            QuickAction.NOTE -> nav.navigate(NoteEditorRoute())
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 600.dp
        val centeredAdd = !wide && maxWidth >= 360.dp && LocalDensity.current.fontScale <= 1.2f
        Row(Modifier.fillMaxSize()) {
            if (wide && isTopLevel) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    topLevels.forEach { t ->
                        val selected = destination?.hierarchy?.any { it.hasRoute(t.type) } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = { selectTop(t) },
                            icon = { Icon(t.icon, null) },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                val bottomPadding = if (isTopLevel && !wide) PaddingValues(bottom = 80.dp) else PaddingValues(0.dp)
                NavHost(nav, startDestination = DashboardRoute, modifier = Modifier.fillMaxSize(),
                    enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 24 } },
                    exitTransition = { fadeOut(tween(150)) },
                    popEnterTransition = { fadeIn(tween(180)) },
                    popExitTransition = { fadeOut(tween(150)) + slideOutHorizontally(tween(180)) { it / 24 } },
                ) {
                    composable<DashboardRoute> {
                        DashboardScreen(
                            contentPadding = bottomPadding,
                            actions = DashboardActions(
                                onSearch = { nav.navigate(SearchRoute) },
                                onFoodDiary = { nav.navigate(FoodDiaryRoute) },
                                onAddFood = { camera -> nav.navigate(FoodEditorRoute(openCamera = camera)) },
                                onWater = { nav.navigate(WaterRoute()) },
                                onAddDrink = { nav.navigate(DrinkEditorRoute()) },
                                onWeight = { add -> nav.navigate(WeightRoute(openAdd = add)) },
                                onSleepActivity = { nav.navigate(SleepActivityRoute) },
                                onIntegrations = { nav.navigate(IntegrationsRoute) },
                                onSymptoms = { add -> nav.navigate(SymptomsRoute(openAdd = add)) },
                                onTimeline = nav::openTarget,
                                onOpenToday = { nav.navigate(DayRoute(TimeUtils.todayEpochDay())) },
                            ),
                        )
                    }
                    composable<CalendarRoute> {
                        CalendarScreen(bottomPadding, onOpenDay = { nav.navigate(DayRoute(it)) }, onTarget = nav::openTarget)
                    }
                    composable<AnalyticsRoute> { AnalyticsScreen(bottomPadding) }
                    composable<ProfileRoute> {
                        ProfileScreen(
                            bottomPadding,
                            ProfileActions(
                                onEditProfile = { nav.navigate(ProfileEditRoute) },
                                onFoodDiary = { nav.navigate(FoodDiaryRoute) },
                                onWater = { nav.navigate(WaterRoute()) },
                                onWeight = { nav.navigate(WeightRoute()) },
                                onSymptoms = { nav.navigate(SymptomsRoute()) },
                                onSleepActivity = { nav.navigate(SleepActivityRoute) },
                                onIntegrations = { nav.navigate(IntegrationsRoute) },
                                onReminders = { nav.navigate(RemindersRoute) },
                                onSecurity = { nav.navigate(SecurityRoute) },
                                onBackup = { nav.navigate(BackupRoute) },
                                onReports = { nav.navigate(ReportsRoute) },
                                onAbout = { nav.navigate(AboutRoute) },
                                onGeminiSettings = { nav.navigate(GeminiSettingsRoute) },
                            ),
                        )
                    }

                    composable<GeminiSettingsRoute> { GeminiSettingsScreen(onBack = nav::popBackStack) }
                    composable<FoodDiaryRoute> {
                        FoodDiaryScreen(
                            onBack = nav::popBackStack,
                            onAdd = { nav.navigate(FoodEditorRoute()) },
                            onOpenMeal = { nav.navigate(FoodEditorRoute(mealId = it)) },
                            onFavorites = { nav.navigate(FavoritesRoute) },
                            onSearch = { nav.navigate(SearchRoute) },
                            onRepeat = { nav.navigate(FoodEditorRoute(repeatMealId = it)) },
                            onAddSymptom = { nav.navigate(SymptomEditorRoute(mealId = it)) },
                        )
                    }
                    composable<FoodEditorRoute> { entry ->
                        val route = entry.toRoute<FoodEditorRoute>()
                        FoodEditorScreen(
                            photoResults = rememberPhotoNavigationResults(entry),
                            openCamera = route.openCamera,
                            onOpenGeminiSettings = { nav.navigate(GeminiSettingsRoute) },
                            onClose = { nav.popBackStack() },
                            onOpenCamera = { nav.navigate(CameraRoute) },
                            onCrop = { nav.navigate(CropRoute(it)) },
                            onAddSymptom = { nav.navigate(SymptomEditorRoute(mealId = it)) },
                            onRepeat = { id ->
                                nav.navigate(FoodEditorRoute(repeatMealId = id)) { popUpTo<FoodEditorRoute> { inclusive = true } }
                            },
                        )
                    }
                    composable<FavoritesRoute> {
                        FavoritesScreen(onBack = nav::popBackStack, onUse = { nav.navigate(FoodEditorRoute(favoriteId = it)) })
                    }
                    composable<CameraRoute> {
                        cameraContent(
                            { file ->
                                nav.previousBackStackEntry?.savedStateHandle?.set(NavResults.CAMERA_PHOTO, file)
                                nav.popBackStack()
                            },
                            { nav.popBackStack() },
                        )
                    }
                    composable<CropRoute> { entry ->
                        CropScreen(
                            fileName = entry.toRoute<CropRoute>().fileName,
                            onDone = { file ->
                                nav.previousBackStackEntry?.savedStateHandle?.set(NavResults.CROPPED_PHOTO, file)
                                nav.popBackStack()
                            },
                            onBack = nav::popBackStack,
                        )
                    }
                    composable<WaterRoute> { entry ->
                        WaterScreen(
                            openAdd = entry.toRoute<WaterRoute>().openAdd,
                            onBack = nav::popBackStack,
                            onAddDrink = { nav.navigate(DrinkEditorRoute()) },
                            onOpenDrink = { nav.navigate(DrinkEditorRoute(it)) },
                        )
                    }
                    composable<DrinkEditorRoute> { entry ->
                        DrinkEditorScreen(photoResults = rememberPhotoNavigationResults(entry), onClose = { nav.popBackStack() }, onOpenCamera = { nav.navigate(CameraRoute) })
                    }
                    composable<WeightRoute> { entry -> WeightScreen(openAdd = entry.toRoute<WeightRoute>().openAdd, onBack = nav::popBackStack) }
                    composable<SymptomsRoute> { entry ->
                        SymptomsScreen(
                            openAdd = entry.toRoute<SymptomsRoute>().openAdd,
                            onBack = nav::popBackStack,
                            onAdd = { nav.navigate(SymptomEditorRoute()) },
                            onOpen = { nav.navigate(SymptomEditorRoute(symptomId = it)) },
                        )
                    }
                    composable<SymptomEditorRoute> { SymptomEditorScreen(onClose = { nav.popBackStack() }) }
                    composable<NoteEditorRoute> { NoteEditorScreen(onClose = { nav.popBackStack() }) }
                    composable<SleepActivityRoute> {
                        SleepActivityScreen(
                            onBack = nav::popBackStack,
                            onAddSleep = { nav.navigate(SleepEditorRoute()) },
                            onOpenSleep = { nav.navigate(SleepEditorRoute(it)) },
                            onOpenIntegrations = { nav.navigate(IntegrationsRoute) },
                        )
                    }
                    composable<SleepEditorRoute> { SleepEditorScreen(onClose = { nav.popBackStack() }) }
                    composable<DayRoute> { DayScreen(onBack = nav::popBackStack, onTarget = nav::openTarget) }
                    composable<SearchRoute> {
                        SearchScreen(
                            onBack = nav::popBackStack,
                            onOpenMeal = { nav.navigate(FoodEditorRoute(mealId = it)) },
                            onOpenDrink = { nav.navigate(DrinkEditorRoute(it)) },
                            onOpenSymptom = { nav.navigate(SymptomEditorRoute(symptomId = it)) },
                            onOpenNote = { nav.navigate(NoteEditorRoute(it)) },
                            onOpenWeight = { nav.navigate(WeightRoute()) },
                            onOpenDay = { nav.navigate(DayRoute(it)) },
                        )
                    }
                    composable<ProfileEditRoute> { ProfileEditScreen(onBack = nav::popBackStack) }
                    composable<ReportsRoute> { ReportsScreen(onBack = nav::popBackStack) }
                    composable<BackupRoute> { BackupScreen(onBack = nav::popBackStack) }
                    composable<IntegrationsRoute> { IntegrationsScreen(onBack = nav::popBackStack) }
                    composable<RemindersRoute> { RemindersScreen(onBack = nav::popBackStack) }
                    composable<SecurityRoute> { SecurityScreen(onBack = nav::popBackStack) }
                    composable<AboutRoute> { AboutScreen(onBack = nav::popBackStack) }
                }

                if (isTopLevel) {
                    // Move the FAB above an app-wide snackbar instead of letting it cover the button.
                    val snackbarShown = LocalSnackbarHost.current.currentSnackbarData != null
                    val fabBottom by animateDpAsState(
                        (if (wide) 16.dp else if (centeredAdd) 30.dp else 104.dp) + if (snackbarShown && !centeredAdd) 72.dp else 0.dp,
                        label = "fabOffset",
                    )
                    Box(
                        Modifier
                            .zIndex(1f)
                            .align(if (centeredAdd) Alignment.BottomCenter else Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(end = if (centeredAdd) 0.dp else 20.dp, bottom = fabBottom),
                    ) {
                        QuickAddFab(fabExpanded, { fabExpanded = it }, ::quick)
                    }
                }
                if (isTopLevel && !wide) {
                    NavigationBar(
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                            .clip(MaterialTheme.shapes.large)
                            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.large),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 0.dp,
                        windowInsets = WindowInsets(0),
                    ) {
                        topLevels.forEachIndexed { index, t ->
                            if (centeredAdd && index == 2) Spacer(Modifier.width(64.dp))
                            val selected = destination?.hierarchy?.any { it.hasRoute(t.type) } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { fabExpanded = false; selectTop(t) },
                                icon = { Icon(t.icon, null) },
                                label = { Text(stringResource(t.label), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
