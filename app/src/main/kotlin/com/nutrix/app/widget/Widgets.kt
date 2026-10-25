package com.nutrix.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.nutrix.app.MainActivity
import com.nutrix.app.R
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.core.time.AppLocale
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.core.util.Units
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.data.repository.HealthRepository
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.data.repository.WeightRepository
import com.nutrix.app.domain.WeightStatsCalculator
import com.nutrix.app.navigation.AppActions
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun drinks(): DrinkRepository
    fun meals(): MealRepository
    fun weights(): WeightRepository
    fun health(): HealthRepository
    fun settings(): SettingsRepository
}

private fun Context.entry(): WidgetEntryPoint {
    AppLocale.init(getString(R.string.locale_tag))
    return EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)
}

private object WidgetColors {
    val background = ColorProvider(Color(0xF2181C1F))
    val text = ColorProvider(Color(0xFFE7E9EB))
    val secondary = ColorProvider(Color(0xFFA9B0B6))
    val water = ColorProvider(Color(0xFF7AB4FF))
    val teal = ColorProvider(Color(0xFF2DD4BF))
    val green = ColorProvider(Color(0xFF4ADE80))
    val button = ColorProvider(Color(0xFF262B30))
}

private fun openApp(context: Context, action: String) =
    actionStartActivity(
        Intent(context, MainActivity::class.java)
            .putExtra(AppActions.EXTRA_ACTION, action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    )

@Composable
private fun WidgetCard(content: @Composable () -> Unit) {
    Box(
        GlanceModifier
            .fillMaxSize()
            .background(WidgetColors.background)
            .cornerRadius(24.dp)
            .padding(14.dp),
    ) { content() }
}

@Composable
private fun Title(text: String, color: ColorProvider) {
    Text(text, style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium))
}

@Composable
private fun Chip(text: String, modifier: GlanceModifier) {
    Box(modifier.background(WidgetColors.button).cornerRadius(14.dp).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(text, style = TextStyle(color = WidgetColors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium))
    }
}

// ---------------- Water ----------------

class WaterWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val e = context.entry()
        val total = e.drinks().totalForDay(TimeUtils.todayEpochDay())
        val s = e.settings().current()
        val volumes = s.quickVolumes.take(2)
        provideContent {
            WidgetCard {
                Column(GlanceModifier.fillMaxSize().clickable(openApp(context, AppActions.ADD_WATER))) {
                    Title(context.getString(R.string.widget_water_title), WidgetColors.water)
                    Spacer(GlanceModifier.height(4.dp))
                    Text("${Formatters.integer(total.toLong())} ${context.getString(R.string.unit_ml)}", style = TextStyle(color = WidgetColors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold))
                    s.waterGoalMl?.let {
                        Text(context.getString(R.string.widget_of_goal, "${Formatters.integer(it.toLong())} ${context.getString(R.string.unit_ml)}"), style = TextStyle(color = WidgetColors.secondary, fontSize = 12.sp))
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    Row {
                        volumes.forEachIndexed { i, v ->
                            if (i > 0) Spacer(GlanceModifier.width(6.dp))
                            Chip("+$v", GlanceModifier.clickable(actionRunCallback<AddWaterAction>(actionParametersOf(AddWaterAction.VOLUME to v))))
                        }
                    }
                }
            }
        }
    }
}

class AddWaterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val volume = parameters[VOLUME] ?: 250
        context.entry().drinks().quickAdd(DrinkType.WATER, volume)
        WidgetUpdater.updateAll(context)
    }

    companion object {
        val VOLUME = ActionParameters.Key<Int>("volume")
    }
}

class WaterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WaterWidget()
}

// ---------------- Weight ----------------

class WeightWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val e = context.entry()
        val s = e.settings().current()
        val all = e.weights().observeAll().first()
        val stats = WeightStatsCalculator.compute(all, s.targetWeightKg)
        val unit = context.getString(if (s.weightUnit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)
        provideContent {
            WidgetCard {
                Column(GlanceModifier.fillMaxSize().clickable(openApp(context, AppActions.ADD_WEIGHT))) {
                    Title(context.getString(R.string.widget_weight_title), WidgetColors.teal)
                    Spacer(GlanceModifier.height(4.dp))
                    val latest = stats.latest
                    if (latest == null) {
                        Text(context.getString(R.string.widget_no_weight), style = TextStyle(color = WidgetColors.secondary, fontSize = 14.sp))
                    } else {
                        Text(Units.formatWeight(latest.weightKg, s.weightUnit, unit), style = TextStyle(color = WidgetColors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold))
                        Text(Formatters.dayMonthShort(latest.time.localDate), style = TextStyle(color = WidgetColors.secondary, fontSize = 12.sp))
                        stats.weekChangeKg?.let {
                            Text(
                                context.getString(R.string.card_weight_week, "${Formatters.signedDecimal(Units.toDisplay(it, s.weightUnit))} $unit"),
                                style = TextStyle(color = WidgetColors.secondary, fontSize = 12.sp),
                            )
                        }
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    Chip("+ ${context.getString(R.string.widget_weight_title)}", GlanceModifier.clickable(openApp(context, AppActions.ADD_WEIGHT)))
                }
            }
        }
    }
}

class WeightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeightWidget()
}

// ---------------- Quick food ----------------

class QuickFoodWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        context.entry()
        provideContent {
            WidgetCard {
                Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                    Title(context.getString(R.string.widget_food_title), WidgetColors.green)
                    Spacer(GlanceModifier.height(10.dp))
                    Box(
                        GlanceModifier
                            .fillMaxWidth()
                            .background(WidgetColors.green)
                            .cornerRadius(18.dp)
                            .padding(vertical = 12.dp)
                            .clickable(openApp(context, AppActions.ADD_FOOD_CAMERA)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("📷 " + context.getString(R.string.widget_food_camera), style = TextStyle(color = ColorProvider(Color(0xFF032611)), fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    }
                    Spacer(GlanceModifier.height(6.dp))
                    Chip(context.getString(R.string.card_food_add), GlanceModifier.clickable(openApp(context, AppActions.ADD_FOOD)))
                }
            }
        }
    }
}

class QuickFoodWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickFoodWidget()
}

// ---------------- Daily stats ----------------

class StatsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val e = context.entry()
        val today = TimeUtils.todayEpochDay()
        val meals = e.meals().getRange(today, today).size
        val water = e.drinks().totalForDay(today)
        val steps = e.health().getActivityRange(today, today).firstOrNull()?.steps
        val s = e.settings().current()
        val latest = e.weights().observeLatest().first()
        val unit = context.getString(if (s.weightUnit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)
        provideContent {
            WidgetCard {
                Column(GlanceModifier.fillMaxSize().clickable(openApp(context, AppActions.OPEN_TODAY))) {
                    Title(context.getString(R.string.widget_stats_title), WidgetColors.teal)
                    Spacer(GlanceModifier.height(6.dp))
                    StatLine(context.getString(R.string.widget_meals), meals.toString(), WidgetColors.green)
                    StatLine(context.getString(R.string.widget_water), "${Formatters.integer(water.toLong())} ${context.getString(R.string.unit_ml)}", WidgetColors.water)
                    if (steps != null) StatLine(context.getString(R.string.widget_steps), Formatters.integer(steps), WidgetColors.text)
                    latest?.let { StatLine(context.getString(R.string.widget_weight_title), Units.formatWeight(it.weightKg, s.weightUnit, unit), WidgetColors.teal) }
                }
            }
        }
    }

    @Composable
    private fun StatLine(label: String, value: String, color: ColorProvider) {
        Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = TextStyle(color = WidgetColors.secondary, fontSize = 13.sp), modifier = GlanceModifier.defaultWeight())
            Text(value, style = TextStyle(color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        }
    }
}

class StatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatsWidget()
}

@Singleton
class WidgetUpdater @Inject constructor() {
    suspend fun updateAll(context: Context) = Companion.updateAll(context)

    companion object {
        suspend fun updateAll(context: Context) {
            runCatching { WaterWidget().updateAll(context) }
            runCatching { WeightWidget().updateAll(context) }
            runCatching { StatsWidget().updateAll(context) }
        }
    }
}
