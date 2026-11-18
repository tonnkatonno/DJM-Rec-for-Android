package com.audiopro.djmrec.integrations.soundcloud

/**
 * Thins out progress callbacks for the notification and the UI state. The upload reports progress on every buffered write
 * (every ~8 KB), and on 2026-11-19 a 2 GB set asked Android for ~1,600 notification updates a second -- the system dropped
 * them ("Shedding notify ... rate limit exceeded") and the app stuttered. Now: at most one update per [minIntervalMs], and
 * only when the value moved at least [minStep] or [maxSilenceMs] passed; the end (1.0) is always reported.
 */
internal class ProgressThrottle(
    private val minStep: Float = 0.01f,
    private val minIntervalMs: Long = 250L,
    private val maxSilenceMs: Long = 1_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastValue = -1f
    private var lastTime = 0L

    fun shouldEmit(value: Float): Boolean {
        val now = clock()
        val emit = when {
            lastValue < 0f -> true
            value >= 1f -> lastValue < 1f
            value == lastValue -> false
            now - lastTime < minIntervalMs -> false
            else -> value - lastValue >= minStep || now - lastTime >= maxSilenceMs
        }
        if (emit) {
            lastValue = value
            lastTime = now
        }
        return emit
    }
}
