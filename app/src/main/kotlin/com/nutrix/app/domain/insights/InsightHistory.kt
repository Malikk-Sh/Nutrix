package com.nutrix.app.domain.insights

/** How an observation compares with the four weeks before the analysis window. */
enum class Recurrence {
    /** The earlier weeks were checked by the same rule and did not show it: preliminary until it comes back. */
    NEW,
    /** Seen in the earlier weeks too, by the same rule and with the same tone. */
    REPEATS,
    /** Seen before by the same rule, but it reads differently now (for example a steadier rhythm). */
    CHANGED,
    /** The earlier weeks had too little data for this rule, so there is nothing to compare with. */
    NO_BASELINE,
    /** The earlier weeks were checked by another variant of the rule (for example by the clock instead of sleep). */
    OTHER_BASIS,
}

data class InsightHistory(val recurrence: Recurrence, val previous: Insight?, val previousBasis: String? = null)

/**
 * Splits history into the analysis window and the window before it, so an observation found once is
 * kept apart from one that keeps coming back. Only rules that could be evaluated on the same basis in
 * both windows are compared; "too little data" is never read as "not there".
 */
object InsightComparison {
    fun previousWindow(window: LongRange): LongRange =
        (window.first - InsightEngine.WINDOW_DAYS)..(window.first - 1)

    fun history(current: Insight, previous: Analysis): InsightHistory {
        val check = previous.check(current.id)
        val before = previous.insights.firstOrNull { it.id == current.id }
        return when {
            check.state == RuleState.NOT_ENOUGH_DATA -> InsightHistory(Recurrence.NO_BASELINE, null)
            check.basis != current.basis -> InsightHistory(Recurrence.OTHER_BASIS, null, check.basis)
            before == null -> InsightHistory(Recurrence.NEW, null)
            before.tone == current.tone -> InsightHistory(Recurrence.REPEATS, before)
            else -> InsightHistory(Recurrence.CHANGED, before)
        }
    }

    /** Patterns of the earlier weeks that the latest weeks checked on the same basis and did not find. */
    fun faded(current: Analysis, previous: Analysis): List<Insight> =
        previous.insights.filter { it.isPattern }.filter { p ->
            current.check(p.id).let { it.state == RuleState.NOT_FOUND && it.basis == p.basis }
        }
}
