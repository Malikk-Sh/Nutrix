package com.nutrix.app.domain.insights

/** How an observation compares with the four weeks before the analysis window. */
enum class Recurrence {
    /** Only in the latest weeks: preliminary until it shows up again. */
    NEW,
    /** Seen in the previous weeks too, with the same tone. */
    REPEATS,
    /** Seen before, but it reads differently now (for example a steadier rhythm). */
    CHANGED,
}

data class InsightHistory(val recurrence: Recurrence, val previous: Insight?)

/**
 * Splits history into the analysis window and the window before it, so an observation found once is
 * kept apart from one that keeps coming back. A pattern missing now counts as "not repeated" only when
 * the latest weeks had enough data for it to show up.
 */
object InsightComparison {
    fun previousWindow(window: LongRange): LongRange =
        (window.first - InsightEngine.WINDOW_DAYS)..(window.first - 1)

    fun history(current: Insight, previous: List<Insight>): InsightHistory {
        val before = previous.firstOrNull { it.id == current.id }
        val recurrence = when {
            before == null -> Recurrence.NEW
            before.tone == current.tone -> Recurrence.REPEATS
            else -> Recurrence.CHANGED
        }
        return InsightHistory(recurrence, before)
    }

    /** Patterns of the previous weeks that the latest weeks could have shown but did not. */
    fun faded(current: List<Insight>, previous: List<Insight>, coverage: DataCoverage): List<Insight> {
        val now = current.map { it.id }.toSet()
        return previous.filter { it.isPattern && it.id !in now && couldShow(it.id, coverage) }
    }

    /** Rough data needs of each pattern rule, mirroring the minimums in [InsightEngine]. */
    internal fun couldShow(id: String, c: DataCoverage): Boolean = when (id) {
        "late_meals", "long_gaps" -> c.mealDays >= InsightEngine.MIN_DAYS
        "sleep_eating" -> c.estimatedDays >= InsightEngine.MIN_DAYS && c.sleepNights >= InsightEngine.MIN_DAYS
        "late_caffeine" -> c.drinkDays >= 8 && c.sleepNights >= 8
        "meal_discomfort" -> c.ratedMeals >= 8
        "symptoms_after_gaps" -> c.meals >= 14
        else -> false
    }
}
