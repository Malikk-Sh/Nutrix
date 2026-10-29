package com.nutrix.app.core

import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.ui.motion.FloatingDeltaState
import com.nutrix.app.core.ui.motion.GoalCelebrationState
import org.junit.Test

class MotionLogicTest {
    @Test
    fun `celebrates only when the user's own addition crosses the goal`() {
        val state = GoalCelebrationState()
        // Opening a screen and loading a total above the goal never celebrates.
        assertThat(state.onValue(0, 1800)).isFalse()
        assertThat(state.onValue(1700, 1800)).isFalse()
        state.arm()
        assertThat(state.onValue(1700, 1800)).isFalse() // goal edits or repeated values are not the addition
        assertThat(state.onValue(1950, 1800)).isTrue()
        assertThat(state.bursts).isEqualTo(1)
        // Further additions after the goal do not repeat the effect.
        state.arm()
        assertThat(state.onValue(2200, 1800)).isFalse()
        assertThat(state.bursts).isEqualTo(1)
    }

    @Test
    fun `rapid taps still celebrate the addition that crosses the goal`() {
        val state = GoalCelebrationState()
        state.onValue(1300, 1800)
        state.arm()
        state.arm() // second tap before the first write is observed
        assertThat(state.onValue(1550, 1800)).isFalse()
        assertThat(state.onValue(1800, 1800)).isTrue()
        assertThat(state.bursts).isEqualTo(1)
    }

    @Test
    fun `changes the user did not make never celebrate`() {
        val state = GoalCelebrationState()
        state.onValue(500, 1800)
        state.arm()
        assertThat(state.onValue(750, 1800)).isFalse()
        // A later change (e.g. a sync or another screen) must not count as the user's addition.
        assertThat(state.onValue(1900, 1800)).isFalse()
        assertThat(state.bursts).isEqualTo(0)
    }

    @Test
    fun `no goal means no celebration`() {
        val state = GoalCelebrationState()
        state.onValue(0, null)
        state.arm()
        assertThat(state.onValue(5000, null)).isFalse()
        assertThat(state.bursts).isEqualTo(0)
    }

    @Test
    fun `floating labels are capped so rapid taps cannot pile up`() {
        val state = FloatingDeltaState()
        repeat(10) { state.emit("+250 мл") }
        assertThat(state.items.size).isEqualTo(4)
    }
}
