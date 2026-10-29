package com.nutrix.app.feature.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.data.repository.InsightReport
import com.nutrix.app.data.repository.InsightRepository
import com.nutrix.app.feature.ai.FoodAiError
import com.nutrix.app.feature.ai.FoodAiException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class InsightsUiState(
    val report: InsightReport? = null,
    val unit: WeightUnit = WeightUnit.KG,
    val hasKey: Boolean = false,
    val consent: Boolean = false,
    /** Cloud review is offered only for keys of billed projects (see [com.nutrix.app.core.datastore.UserSettings.aiPaidProject]). */
    val paidProject: Boolean = false,
    val review: CachedReview? = null,
    /** The facts changed since [review] was written. */
    val reviewStale: Boolean = false,
    val reviewing: Boolean = false,
    val error: FoodAiError? = null,
    /** Exactly what a review request would send, for the consent dialog. */
    val payloadPreview: String = "",
) {
    val canReview: Boolean get() = report?.visible.orEmpty().isNotEmpty()
}

private data class ReviewRun(val running: Boolean = false, val error: FoodAiError? = null)

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val insights: InsightRepository,
    private val settings: SettingsRepository,
    private val reviewer: InsightReviewer,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val run = MutableStateFlow(ReviewRun())
    private var job: Job? = null

    val state: StateFlow<InsightsUiState> = combine(
        insights.reports, settings.settings, settings.hasGeminiKey, settings.aiReviewCache, run,
    ) { report, s, hasKey, cacheJson, r ->
        val payload = InsightReviewPayload.build(report)
        val cached = cacheJson?.let { runCatching { json.decodeFromString<CachedReview>(it) }.getOrNull() }
        InsightsUiState(
            report = report,
            unit = s.weightUnit,
            hasKey = hasKey,
            consent = s.aiReviewConsent,
            paidProject = s.aiPaidProject,
            review = cached,
            reviewStale = cached != null && cached.payloadHash != InsightReviewPayload.fingerprint(payload),
            reviewing = r.running,
            error = r.error,
            payloadPreview = InsightReviewPayload.pretty(payload),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InsightsUiState())

    fun setHidden(id: String, hidden: Boolean) {
        viewModelScope.launch { settings.setInsightHidden(id, hidden) }
    }

    fun setPaidProject(paid: Boolean) {
        if (!paid) job?.cancel()
        viewModelScope.launch { settings.setAiPaidProject(paid) }
    }

    fun revokeConsent() {
        job?.cancel()
        viewModelScope.launch { settings.setAiReviewConsent(false) }
    }

    /** One request per tap: free-tier quota is small, so nothing is retried automatically. */
    fun review(consentGiven: Boolean = false) {
        if (run.value.running) return
        job = viewModelScope.launch {
            run.value = ReviewRun(running = true)
            try {
                // The free tier's terms forbid personal information: nothing leaves without a paid project.
                if (!settings.current().aiPaidProject) { run.value = ReviewRun(); return@launch }
                if (consentGiven) settings.setAiReviewConsent(true)
                val report = insights.current()
                val payload = InsightReviewPayload.build(report)
                val (review, model) = reviewer.review(payload, InsightReviewPayload.ids(report))
                val cached = CachedReview(InsightReviewPayload.fingerprint(payload), System.currentTimeMillis(), model, review)
                settings.setAiReviewCache(json.encodeToString(CachedReview.serializer(), cached))
                run.value = ReviewRun()
            } catch (cancelled: CancellationException) {
                run.value = ReviewRun()
                throw cancelled
            } catch (error: FoodAiException) {
                run.value = ReviewRun(error = error.reason)
            } catch (_: Exception) {
                run.value = ReviewRun(error = FoodAiError.INVALID_RESPONSE)
            }
        }
    }

    fun cancelReview() {
        job?.cancel()
    }
}
