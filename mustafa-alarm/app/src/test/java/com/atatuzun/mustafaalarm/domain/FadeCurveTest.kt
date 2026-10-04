package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class FadeCurveTest {
    @Test fun startsQuiet_endsFull() {
        assertEquals(0.05f, FadeCurve.volumeAt(0), 0.0001f)
        assertEquals(1f, FadeCurve.volumeAt(30_000), 0f)
        assertEquals(1f, FadeCurve.volumeAt(60_000), 0f)
    }

    @Test fun risesMonotonically() {
        val v = (0..60).map { FadeCurve.volumeAt(it * 500L) }
        assertTrue(v.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun logShape() = assertEquals(log10(5.5).toFloat(), FadeCurve.volumeAt(15_000), 0.001f)
}
