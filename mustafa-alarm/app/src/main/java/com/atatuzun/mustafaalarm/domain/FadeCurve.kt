package com.atatuzun.mustafaalarm.domain

import kotlin.math.log10

/** Fade-in: a log curve from 5 % to 100 % of the target volume over 30 seconds. */
object FadeCurve {
    const val DURATION_MS = 30_000L
    private const val START = 0.05f

    fun volumeAt(elapsedMs: Long, durationMs: Long = DURATION_MS): Float {
        if (elapsedMs >= durationMs) return 1f
        if (elapsedMs <= 0) return START
        val x = elapsedMs.toDouble() / durationMs
        return maxOf(START, log10(1 + 9 * x).toFloat())
    }
}
