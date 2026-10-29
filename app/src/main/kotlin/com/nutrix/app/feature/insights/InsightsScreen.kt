package com.nutrix.app.feature.insights

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrix.app.R
import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.components.CardHeader
import com.nutrix.app.core.ui.components.EmptyState
import com.nutrix.app.core.ui.components.InfoBanner
import com.nutrix.app.core.ui.components.InfoDisclosure
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.Pill
import com.nutrix.app.core.ui.components.ScreenList
import com.nutrix.app.core.ui.components.Sparkline
import com.nutrix.app.core.ui.icons.AutoAwesome
import com.nutrix.app.core.ui.icons.Insights
import com.nutrix.app.core.ui.motion.entrance
import com.nutrix.app.core.ui.motion.rememberEntranceState
import com.nutrix.app.core.ui.motion.scanSweep
import com.nutrix.app.domain.insights.Insight
import com.nutrix.app.domain.insights.InsightTone
import com.nutrix.app.feature.ai.messageRes
import java.time.LocalDate

@Composable
fun InsightsScreen(onBack: () -> Unit, onOpenGeminiSettings: () -> Unit, viewModel: InsightsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var consentDialog by rememberSaveable { mutableStateOf(false) }
    var paidDialog by rememberSaveable { mutableStateOf(false) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    val report = state.report
    val entrance = rememberEntranceState(ready = report != null)
    NutrixScreen(title = stringResource(R.string.insights_title), onBack = onBack) { padding ->
        ScreenList(padding) {
            item(key = "intro") {
                Column(Modifier.entrance(entrance, 0)) {
                    if (report != null) {
                        Text(stringResource(R.string.insights_period, Formatters.dayMonthShort(LocalDate.ofEpochDay(report.from)),
                            Formatters.dayMonthShort(LocalDate.ofEpochDay(report.to))),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    InfoDisclosure(stringResource(R.string.insights_about_summary), stringResource(R.string.insights_about), Icons.Rounded.Info)
                }
            }
            if (report == null) {
                item(key = "loading") { CircularProgressIndicator(Modifier.padding(16.dp)) }
                return@ScreenList
            }
            item(key = "overview") { OverviewCard(report, state.unit, Modifier.entrance(entrance, 1)) }
            item(key = "review") {
                ReviewCard(state, Modifier.entrance(entrance, 2),
                    onReview = { if (state.consent) viewModel.review() else consentDialog = true },
                    onCancel = viewModel::cancelReview, onRevoke = viewModel::revokeConsent, onSettings = onOpenGeminiSettings,
                    onPaid = { paidDialog = true }, onNotPaid = { viewModel.setPaidProject(false) })
            }
            val visible = report.visible
            if (visible.isEmpty()) {
                item(key = "empty") { EmptyState(Icons.Rounded.Insights, stringResource(R.string.insights_empty), stringResource(R.string.insights_empty_text)) }
            }
            visible.forEachIndexed { index, insight ->
                item(key = insight.id) {
                    InsightCard(insight, state.unit, Modifier.animateItem().entrance(entrance, index + 3),
                        action = { TextButton(onClick = { viewModel.setHidden(insight.id, true) }) { Text(stringResource(R.string.insights_hide)) } })
                }
            }
            if (report.hiddenCount > 0) {
                item(key = "hidden-toggle") {
                    TextButton(onClick = { showHidden = !showHidden }, modifier = Modifier.animateItem()) {
                        Text(stringResource(R.string.insights_show_hidden, report.hiddenCount))
                    }
                }
                if (showHidden) {
                    report.all.filter { it.id in report.hidden }.forEach { insight ->
                        item(key = "hidden-${insight.id}") {
                            InsightCard(insight, state.unit, Modifier.animateItem(),
                                action = { TextButton(onClick = { viewModel.setHidden(insight.id, false) }) { Text(stringResource(R.string.insights_restore)) } })
                        }
                    }
                }
            }
        }
    }
    if (paidDialog) {
        AlertDialog(
            onDismissRequest = { paidDialog = false },
            title = { Text(stringResource(R.string.ai_review_paid_title)) },
            text = { Text(stringResource(R.string.ai_review_paid_text), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { paidDialog = false; viewModel.setPaidProject(true) }) { Text(stringResource(R.string.ai_review_paid_confirm)) } },
            dismissButton = { TextButton(onClick = { paidDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (consentDialog) {
        ConsentDialog(state.payloadPreview, onDismiss = { consentDialog = false }, onAccept = {
            consentDialog = false
            viewModel.review(consentGiven = true)
        })
    }
}

@Composable
fun InsightCard(insight: Insight, unit: WeightUnit, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val text = insightText(insight, unit)
    val color = topicColor(insight.topic)
    NutrixCard(modifier = modifier.testTag("insight-${insight.id}")) {
        CardHeader(topicIcon(insight.topic), text.title, color)
        when (insight.tone) {
            InsightTone.ATTENTION -> Pill(stringResource(R.string.insights_tone_attention), MaterialTheme.colorScheme.tertiary, Modifier.padding(top = 8.dp))
            InsightTone.POSITIVE -> Pill(stringResource(R.string.insights_tone_positive), color, Modifier.padding(top = 8.dp))
            InsightTone.NEUTRAL -> Unit
        }
        Spacer(Modifier.height(8.dp))
        text.lines.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (insight.chart.size >= 3) {
            Spacer(Modifier.height(4.dp))
            Sparkline(insight.chart.map { it.value }, color, Modifier.fillMaxWidth().height(40.dp))
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                text.evidence?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (insight.isLink) {
                    Text(stringResource(R.string.insights_link_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            action?.invoke()
        }
    }
}

@Composable
private fun ReviewCard(state: InsightsUiState, modifier: Modifier, onReview: () -> Unit, onCancel: () -> Unit, onRevoke: () -> Unit,
    onSettings: () -> Unit, onPaid: () -> Unit, onNotPaid: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    NutrixCard(modifier = modifier.animateContentSize().testTag("insight-review")) {
        CardHeader(Icons.Rounded.AutoAwesome, stringResource(R.string.ai_review_title), primary)
        Spacer(Modifier.height(8.dp))
        if (!state.paidProject) {
            Text(stringResource(R.string.ai_review_unpaid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("insight-review-unpaid"))
            if (state.hasKey) TextButton(onClick = onPaid) { Text(stringResource(R.string.ai_review_paid_action)) }
            return@NutrixCard
        }
        val review = state.review
        when {
            state.reviewing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = MaterialTheme.shapes.small, color = primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(40.dp).scanSweep(primary)) {}
                Text(stringResource(R.string.ai_review_loading), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
            review != null -> ReviewContent(review, state)
            else -> Text(stringResource(R.string.ai_review_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            InfoBanner(stringResource(error.messageRes), Icons.Rounded.Info, tint = MaterialTheme.colorScheme.error)
        }
        if (!state.reviewing) {
            Spacer(Modifier.height(10.dp))
            when {
                !state.hasKey -> {
                    Text(stringResource(R.string.ai_review_no_key), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onSettings) { Text(stringResource(R.string.ai_settings)) }
                }
                !state.canReview -> Text(stringResource(R.string.ai_review_need_insights), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                review == null || state.reviewStale || state.error != null -> {
                    Button(onClick = onReview, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("insight-review-action"),
                        shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (review == null) R.string.ai_review_action else R.string.ai_review_again))
                    }
                    Text(stringResource(R.string.ai_review_quota_hint), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
            FlowRow {
                if (state.consent) TextButton(onClick = onRevoke) { Text(stringResource(R.string.ai_review_revoke)) }
                TextButton(onClick = onNotPaid) { Text(stringResource(R.string.ai_review_paid_revoke)) }
            }
        }
    }
}

@Composable
private fun ReviewContent(cached: CachedReview, state: InsightsUiState) {
    val titles = state.report?.all.orEmpty().associateBy { it.id }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("insight-review-text")) {
        if (cached.review.summary.isNotEmpty()) Text(cached.review.summary, style = MaterialTheme.typography.bodyMedium)
        cached.review.points.forEach { point ->
            val insight = titles[point.insightId]
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (insight != null) Icon(topicIcon(insight.topic), null, tint = topicColor(insight.topic), modifier = Modifier.size(18.dp).padding(top = 2.dp))
                Text(point.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
        if (cached.review.suggestions.isNotEmpty()) {
            Text(stringResource(R.string.ai_review_suggestion), style = MaterialTheme.typography.titleSmall)
            cached.review.suggestions.forEach { s -> Text("• " + s.text, style = MaterialTheme.typography.bodySmall) }
        }
        if (cached.review.points.any { titles[it.insightId]?.topic == com.nutrix.app.domain.insights.InsightTopic.WELLBEING }) {
            Text(stringResource(R.string.ai_review_safety), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.ai_review_from, Formatters.dateTime(cached.createdAt, java.time.ZoneId.systemDefault().id), cached.model),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedVisibility(state.reviewStale) {
            Text(stringResource(R.string.ai_review_stale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        Text(stringResource(R.string.ai_review_disclaimer), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConsentDialog(preview: String, onDismiss: () -> Unit, onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_review_consent_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ai_review_consent_text), style = MaterialTheme.typography.bodyMedium)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
                    Text(preview, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(10.dp).testTag("insight-review-preview"))
                }
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.ai_review_consent_accept)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Compact top observation for the dashboard. */
@Composable
fun InsightTeaser(insight: Insight, unit: WeightUnit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val text = insightText(insight, unit)
    val color = topicColor(insight.topic)
    NutrixCard(modifier = modifier.testTag("insight-teaser"), onClick = onOpen) {
        CardHeader(topicIcon(insight.topic), stringResource(R.string.insights_card_title) + " · " + text.title, color)
        Spacer(Modifier.height(6.dp))
        text.lines.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3) }
        OutlinedButton(onClick = onOpen, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.insights_open))
        }
    }
}

/** Offline weekly overview: notable observations, the main data limitation and one diary step. */
@Composable
private fun OverviewCard(report: com.nutrix.app.data.repository.InsightReport, unit: WeightUnit, modifier: Modifier) {
    val overview = report.overview
    val primary = MaterialTheme.colorScheme.primary
    NutrixCard(modifier = modifier.testTag("insight-overview")) {
        CardHeader(Icons.Rounded.Insights, stringResource(R.string.overview_title), primary)
        Text(stringResource(R.string.overview_offline), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.overview_highlights), style = MaterialTheme.typography.titleSmall)
        if (overview.highlights.isEmpty()) {
            Text(stringResource(R.string.overview_none), style = MaterialTheme.typography.bodySmall)
        }
        overview.highlights.forEach { insight ->
            val text = insightText(insight, unit)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Icon(topicIcon(insight.topic), null, tint = topicColor(insight.topic), modifier = Modifier.size(18.dp).padding(top = 2.dp))
                Text(text.title + ": " + text.lines.first().replaceFirstChar { it.lowercase() }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.overview_limitation), style = MaterialTheme.typography.titleSmall)
        Text(limitationText(overview.limitation), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.overview_action), style = MaterialTheme.typography.titleSmall)
        Text(actionText(overview.action), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun limitationText(limitation: com.nutrix.app.domain.insights.DataLimitation): String {
    @Composable fun ofDays(count: Int) = androidx.compose.ui.res.pluralStringResource(R.plurals.insights_of_days, count, count)
    return when (limitation) {
        is com.nutrix.app.domain.insights.DataLimitation.FewMealDays -> stringResource(R.string.limit_few_meals, limitation.days, ofDays(limitation.of))
        com.nutrix.app.domain.insights.DataLimitation.NoSleep -> stringResource(R.string.limit_no_sleep)
        is com.nutrix.app.domain.insights.DataLimitation.FewEstimates -> stringResource(R.string.limit_few_estimates, limitation.days, ofDays(limitation.of))
        is com.nutrix.app.domain.insights.DataLimitation.FewRatedMeals -> stringResource(R.string.limit_few_rated, limitation.rated, limitation.of)
        com.nutrix.app.domain.insights.DataLimitation.OnlyRecorded -> stringResource(R.string.limit_only_recorded)
    }
}

@Composable
private fun actionText(action: com.nutrix.app.domain.insights.DiaryAction): String = stringResource(when (action) {
    com.nutrix.app.domain.insights.DiaryAction.KEEP_RECORDING -> R.string.action_keep_recording
    com.nutrix.app.domain.insights.DiaryAction.LOG_MEALS -> R.string.action_log_meals
    com.nutrix.app.domain.insights.DiaryAction.ADD_SLEEP -> R.string.action_add_sleep
    com.nutrix.app.domain.insights.DiaryAction.RATE_MEALS -> R.string.action_rate_meals
    com.nutrix.app.domain.insights.DiaryAction.ESTIMATE_MEALS -> R.string.action_estimate_meals
    com.nutrix.app.domain.insights.DiaryAction.SET_WATER_GUIDELINE -> R.string.action_set_water_guideline
})
