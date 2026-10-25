package com.nutrix.app.feature.search

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nutrix.app.R
import com.nutrix.app.core.model.SymptomType
import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.components.EmptyState
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.ScreenList
import com.nutrix.app.core.ui.components.SectionHeader
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.core.util.Units
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.data.repository.NoteRepository
import com.nutrix.app.data.repository.SymptomRepository
import com.nutrix.app.data.repository.WeightRepository
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.database.entity.MealWithPhotos
import com.nutrix.app.database.entity.NoteEntity
import com.nutrix.app.database.entity.SymptomEntity
import com.nutrix.app.database.entity.WeightEntity
import com.nutrix.app.feature.food.MealRow
import com.nutrix.app.feature.symptoms.SymptomRow
import com.nutrix.app.feature.water.DrinkRow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class SearchResults(
    val query: String = "",
    val date: LocalDate? = null,
    val meals: List<MealEntity> = emptyList(),
    val drinks: List<DrinkEntity> = emptyList(),
    val symptoms: List<SymptomEntity> = emptyList(),
    val notes: List<NoteEntity> = emptyList(),
    val weights: List<WeightEntity> = emptyList(),
) {
    val isEmpty get() = date == null && meals.isEmpty() && drinks.isEmpty() && symptoms.isEmpty() && notes.isEmpty() && weights.isEmpty()
}

object DateQueryParser {
    private val patterns = listOf("d.M.yyyy", "d.M.yy", "yyyy-M-d", "d/M/yyyy")

    /** Understands "25.10", "25.10.2026", "2026-10-25". A day/month without year means the latest past one. */
    fun parse(text: String, today: LocalDate = LocalDate.now()): LocalDate? {
        val t = text.trim()
        patterns.forEach { p -> runCatching { return LocalDate.parse(t, DateTimeFormatter.ofPattern(p)) } }
        val m = Regex("""^(\d{1,2})[./](\d{1,2})$""").matchEntire(t) ?: return null
        val day = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val candidate = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
        return if (candidate.isAfter(today)) candidate.minusYears(1) else candidate
    }
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val meals: MealRepository,
    private val drinks: DrinkRepository,
    private val symptoms: SymptomRepository,
    private val notes: NoteRepository,
    private val weights: WeightRepository,
    settings: com.nutrix.app.core.datastore.SettingsRepository,
) : ViewModel() {
    val query = MutableStateFlow("")
    val unit: StateFlow<WeightUnit> = settings.settings.map { it.weightUnit }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WeightUnit.KG)

    val results: StateFlow<SearchResults?> = query.debounce(250).mapLatest { q ->
        val text = q.trim()
        if (text.length < 2) return@mapLatest null
        val lower = text.lowercase()
        val matchedTypes = SymptomType.entries.filter { context.getString(it.labelRes).lowercase().contains(lower) }.toSet()
        val symptomHits = symptoms.all().filter {
            it.type in matchedTypes || it.note.orEmpty().lowercase().contains(lower) || it.customName.orEmpty().lowercase().contains(lower)
        }.take(100)
        SearchResults(
            query = text,
            date = DateQueryParser.parse(text),
            meals = meals.search(text),
            drinks = drinks.search(text),
            symptoms = symptomHits,
            notes = notes.search(text),
            weights = weights.search(text),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenMeal: (Long) -> Unit,
    onOpenDrink: (Long) -> Unit,
    onOpenSymptom: (Long) -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenWeight: () -> Unit,
    onOpenDay: (Long) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    NutrixScreen(title = stringResource(R.string.search_title), onBack = onBack) { padding ->
        ScreenList(padding) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.query.value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { viewModel.query.value = "" }) { Icon(Icons.Rounded.Clear, stringResource(R.string.action_clear)) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = MaterialTheme.shapes.large,
                )
            }
            val r = results
            when {
                r == null -> item { Text(stringResource(R.string.search_start), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
                r.isEmpty -> item { EmptyState(Icons.Rounded.SearchOff, stringResource(R.string.search_nothing), null) }
                else -> {
                    r.date?.let { d ->
                        item {
                            NutrixCard(onClick = { onOpenDay(d.toEpochDay()) }) {
                                androidx.compose.foundation.layout.Row {
                                    Icon(Icons.Rounded.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary)
                                    Text(
                                        stringResource(R.string.search_open_date, Formatters.fullDate(d)),
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(start = 10.dp),
                                    )
                                }
                            }
                        }
                    }
                    if (r.meals.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.search_meals)) }
                        items(r.meals, key = { "m${it.id}" }) { m -> MealRow(MealWithPhotos(m, emptyList()), onClick = { onOpenMeal(m.id) }) }
                    }
                    if (r.symptoms.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.search_symptoms)) }
                        items(r.symptoms, key = { "s${it.id}" }) { s -> SymptomRow(s, null, onClick = { onOpenSymptom(s.id) }) }
                    }
                    if (r.notes.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.search_notes)) }
                        items(r.notes, key = { "n${it.id}" }) { n ->
                            NutrixCard(onClick = { onOpenNote(n.id) }, contentPadding = 12.dp) {
                                Text(Formatters.dateTime(n.epochMillis, n.zoneId), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(n.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                            }
                        }
                    }
                    if (r.drinks.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.search_drinks)) }
                        items(r.drinks, key = { "d${it.id}" }) { d -> DrinkRow(d, onClick = { onOpenDrink(d.id) }, onDelete = null) }
                    }
                    if (r.weights.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.search_weights)) }
                        items(r.weights, key = { "w${it.id}" }) { w ->
                            val label = stringResource(if (unit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)
                            NutrixCard(onClick = onOpenWeight, contentPadding = 12.dp) {
                                Text(Units.formatWeight(w.weightKg, unit, label), style = MaterialTheme.typography.titleMedium)
                                Text(Formatters.dateTime(w.epochMillis, w.zoneId) + (w.note?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
