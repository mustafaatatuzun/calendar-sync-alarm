package com.atatuzun.mustafaalarm.ring

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PressCounterTest {

    // Review Focus #2: four rapid presses => fire ONCE, counter at 0 after; a fourth starts a new count.
    @Test fun `three rapid presses fire once then fourth starts a new count`() = runTest {
        val fires = mutableListOf<Int>()
        val pc = PressCounter(this, required = 3, onFire = { fires += 1 })
        pc.press(); pc.press(); pc.press()
        assertEquals(listOf(1), fires)
        assertEquals(0, pc.presses.value)
        pc.press()
        assertEquals(1, pc.presses.value)
    }

    // Review Focus #3: 2 presses + 2.5s pause + 1 press => counter resets, nothing fires.
    @Test fun `pause longer than reset clears the counter`() = runTest {
        val fires = mutableListOf<Int>()
        val pc = PressCounter(this, required = 3, resetMillis = 2_000L, onFire = { fires += 1 })
        pc.press(); pc.press()
        advanceTimeBy(2_500L)
        assertEquals(0, pc.presses.value)
        pc.press()
        assertEquals(1, pc.presses.value)
        assertEquals(emptyList<Int>(), fires)
    }

    @Test fun `required 1 fires on first press`() = runTest {
        val fires = mutableListOf<Int>()
        val pc = PressCounter(this, required = 1, onFire = { fires += 1 })
        pc.press()
        assertEquals(listOf(1), fires)
    }
}
