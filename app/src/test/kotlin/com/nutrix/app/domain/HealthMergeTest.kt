package com.nutrix.app.domain

import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.database.entity.ExerciseEntity
import com.nutrix.app.database.entity.SleepEntity
import org.junit.Test

class HealthMergeTest {
    private val hc = DataSource.HEALTH_CONNECT
    private val samsung = DataSource.SAMSUNG_HEALTH
    private val priority = listOf(hc, samsung)
    private val minute = 60_000L

    private fun exercise(id: Long, source: DataSource, start: Int = 760, end: Int = 774, type: Int? = 79) =
        ExerciseEntity(id, start * minute, end * minute, "UTC", 0, type, source = source)

    private fun sleep(id: Long, source: DataSource, start: Int, end: Int) =
        SleepEntity(id, start * minute, end * minute, "UTC", 0, end - start, source)

    @Test fun `walking copies collapse and retain calories from Samsung`() {
        val rows = listOf(exercise(1, hc), exercise(2, samsung).copy(kcal = 50.0, title = "Ходьба"))
        val result = ExerciseDeduplicator.dedupe(rows, priority).single()
        assertThat(result.id).isEqualTo(1L)
        assertThat(result.kcal).isEqualTo(50.0)
        assertThat(result.title).isEqualTo("Ходьба")
        assertThat(ExerciseDeduplicator.dedupe(rows, priority.reversed()).single().id).isEqualTo(2L)
    }

    @Test fun `small source time rounding still matches`() {
        val result = ExerciseDeduplicator.dedupe(listOf(exercise(1, hc), exercise(2, samsung, 761, 775)), priority)
        assertThat(result).hasSize(1)
    }

    @Test fun `adjacent walks different types and same source sessions stay separate`() {
        val rows = listOf(exercise(1, hc), exercise(2, samsung, 775, 789),
            exercise(3, samsung, type = 56), exercise(4, hc))
        assertThat(ExerciseDeduplicator.dedupe(rows, priority)).hasSize(4)
    }

    @Test fun `matching is one to one even when one source has two nearby sessions`() {
        val rows = listOf(exercise(1, hc), exercise(2, hc, 761, 775), exercise(3, samsung))
        assertThat(ExerciseDeduplicator.dedupe(rows, priority).map { it.id }).containsExactly(1L, 2L)
    }

    @Test fun `manual naps are retained alongside imported night and evening sleep`() {
        val rows = listOf(sleep(1, hc, 0, 480), sleep(2, samsung, 10, 480),
            sleep(3, DataSource.MANUAL, 800, 860), sleep(4, DataSource.MANUAL, 1200, 1230))
        val result = SleepDeduplicator.dedupe(rows, priority)
        assertThat(result.map { it.id }).containsExactly(1L, 3L, 4L)
        assertThat(SleepTotals.minutes(result)).isEqualTo(570)
    }

    @Test fun `a short manual session inside a long import cannot hide the night`() {
        val result = SleepDeduplicator.dedupe(listOf(sleep(1, hc, 0, 480), sleep(2, DataSource.MANUAL, 400, 460)), priority)
        assertThat(result).hasSize(2)
        assertThat(SleepTotals.minutes(result)).isEqualTo(480)
    }

    @Test fun `overlapping naps count their shared time once`() {
        assertThat(SleepTotals.minutes(listOf(sleep(1, DataSource.MANUAL, 600, 660), sleep(2, DataSource.MANUAL, 630, 690))))
            .isEqualTo(90)
    }
}
