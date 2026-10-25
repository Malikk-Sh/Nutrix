package com.nutrix.app.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import com.nutrix.app.core.ui.icons.EventBusy
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.nutrix.app.R
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.EntryCategory
import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.color
import com.nutrix.app.core.ui.components.EmptyState
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.ScreenList
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.data.repository.TimelineItem
import com.nutrix.app.data.repository.TimelineRepository
import com.nutrix.app.feature.food.dayTitle
import com.nutrix.app.navigation.CalendarRoute
import com.nutrix.app.navigation.DayRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    timeline: TimelineRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val initial = savedStateHandle.toRoute<CalendarRoute>().epochDay.takeIf { it >= 0 }?.let(LocalDate::ofEpochDay) ?: LocalDate.now()
    val month = MutableStateFlow(YearMonth.from(initial))
    val selected = MutableStateFlow(initial)
    val filters = MutableStateFlow(EntryCategory.entries.toSet())

    val markers: StateFlow<Map<Long, Set<EntryCategory>>> = month.flatMapLatest { ym ->
        timeline.observeMarkers(ym.atDay(1).toEpochDay() - 7, ym.atEndOfMonth().toEpochDay() + 7)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val items: StateFlow<List<TimelineItem>> = combine(selected.flatMapLatest { timeline.observeDay(it.toEpochDay()) }, filters) { list, f ->
        list.filter { it.category in f }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unit: StateFlow<WeightUnit> = settings.settings.map { it.weightUnit }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WeightUnit.KG)

    fun shiftMonth(delta: Long) {
        month.value = month.value.plusMonths(delta)
    }

    fun select(date: LocalDate) {
        selected.value = date
        month.value = YearMonth.from(date)
    }

    fun toggleFilter(c: EntryCategory) {
        val f = filters.value
        filters.value = if (c in f) f - c else f + c
    }
}

@Composable
fun CalendarScreen(contentPadding: PaddingValues, onOpenDay: (Long) -> Unit, onTarget: (TimelineTarget) -> Unit, viewModel: CalendarViewModel = hiltViewModel()) {
    val month by viewModel.month.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val markers by viewModel.markers.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    NutrixScreen(title = stringResource(R.string.calendar_title), onBack = null) { padding ->
        ScreenList(PaddingValues(top = padding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())) {
            item(key = "month") {
                NutrixCard(contentPadding = 12.dp) {
                    MonthGrid(month, selected, markers, onSelect = viewModel::select, onShift = viewModel::shiftMonth)
                }
            }
            item(key = "filters") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EntryCategory.entries.forEach { c ->
                        FilterChip(
                            selected = c in filters,
                            onClick = { viewModel.toggleFilter(c) },
                            label = { Text(stringResource(c.labelRes)) },
                            leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(c.color())) },
                        )
                    }
                }
            }
            item(key = "day-h") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dayTitle(selected), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onOpenDay(selected.toEpochDay()) }) { Text(stringResource(R.string.calendar_open_day)) }
                }
            }
            if (items.isEmpty()) {
                item { EmptyState(Icons.Rounded.EventBusy, stringResource(R.string.day_empty), null) }
            } else {
                item(key = "day") {
                    NutrixCard(contentPadding = 8.dp) {
                        items.forEachIndexed { i, it -> TimelineRow(it, unit, i == items.lastIndex) { onTarget(it.target()) } }
                    }
                }
            }
        }
    }
}

@Composable
fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    markers: Map<Long, Set<EntryCategory>>,
    onSelect: (LocalDate) -> Unit,
    onShift: (Long) -> Unit,
) {
    val today = LocalDate.now()
    val drag = remember { mutableFloatStateOf(0f) }
    Column(
        Modifier.pointerInput(month) {
            detectHorizontalDragGestures(
                onDragStart = { drag.floatValue = 0f },
                onDragEnd = {
                    if (drag.floatValue > 120) onShift(-1) else if (drag.floatValue < -120) onShift(1)
                },
                onHorizontalDrag = { _, amount -> drag.floatValue += amount },
            )
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onShift(-1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.calendar_prev)) }
            Text(Formatters.monthYear(month.atDay(1)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = { onShift(1) }, enabled = month < YearMonth.from(today)) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.calendar_next))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            DayOfWeek.entries.forEach { d ->
                Text(
                    d.getDisplayName(TextStyle.SHORT, com.nutrix.app.core.time.AppLocale.locale),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        val first = month.atDay(1)
        val offset = first.dayOfWeek.value - 1
        val totalCells = ((offset + month.lengthOfMonth() + 6) / 7) * 7
        for (week in 0 until totalCells / 7) {
            Row(Modifier.fillMaxWidth()) {
                for (dow in 0 until 7) {
                    val index = week * 7 + dow - offset
                    val date = if (index in 0 until month.lengthOfMonth()) month.atDay(index + 1) else null
                    Box(Modifier.weight(1f).aspectRatio(0.9f), contentAlignment = Alignment.Center) {
                        if (date != null) {
                            DayCell(date, date == selected, date == today, date.isAfter(today), markers[date.toEpochDay()].orEmpty(), onClick = { onSelect(date) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, selected: Boolean, isToday: Boolean, future: Boolean, categories: Set<EntryCategory>, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val labels = categories.sortedBy { it.ordinal }.map { stringResource(it.labelRes) }
    val description = Formatters.fullDate(date) + if (labels.isNotEmpty()) ": " + labels.joinToString() else ""
    Column(
        Modifier
            .padding(2.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainer)
            .then(if (isToday) Modifier.border(1.5.dp, primary, MaterialTheme.shapes.small) else Modifier)
            .clickable(enabled = !future, onClick = onClick)
            .padding(vertical = 6.dp)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${date.dayOfMonth}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected || isToday) FontWeight.SemiBold else FontWeight.Normal,
            color = if (future) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            EntryCategory.entries.filter { it in categories && it != EntryCategory.NOTE }.take(5).forEach { c ->
                Box(Modifier.size(5.dp).clip(CircleShape).background(c.color()))
            }
        }
    }
}

// ---------- Day screen ----------

@HiltViewModel
class DayViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    timeline: TimelineRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val day: LocalDate = LocalDate.ofEpochDay(savedStateHandle.toRoute<DayRoute>().epochDay)
    val filters = MutableStateFlow(EntryCategory.entries.toSet())
    val items: StateFlow<List<TimelineItem>?> = combine(timeline.observeDay(day.toEpochDay()), filters) { list, f -> list.filter { it.category in f } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val unit: StateFlow<WeightUnit> = settings.settings.map { it.weightUnit }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WeightUnit.KG)

    fun toggleFilter(c: EntryCategory) {
        val f = filters.value
        filters.value = if (c in f) f - c else f + c
    }
}

@Composable
fun DayScreen(onBack: () -> Unit, onTarget: (TimelineTarget) -> Unit, viewModel: DayViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    NutrixScreen(title = Formatters.weekdayDayMonth(viewModel.day).replaceFirstChar { it.titlecase() }, onBack = onBack) { padding ->
        ScreenList(padding) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EntryCategory.entries.forEach { c ->
                        FilterChip(
                            selected = c in filters,
                            onClick = { viewModel.toggleFilter(c) },
                            label = { Text(stringResource(c.labelRes)) },
                            leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(c.color())) },
                        )
                    }
                }
            }
            val list = items
            if (list != null && list.isEmpty()) {
                item { EmptyState(Icons.Rounded.EventBusy, stringResource(R.string.day_empty), null) }
            } else if (list != null) {
                item {
                    NutrixCard(contentPadding = 8.dp) {
                        list.forEachIndexed { i, it -> TimelineRow(it, unit, i == list.lastIndex) { onTarget(it.target()) } }
                    }
                }
            }
        }
    }
}
