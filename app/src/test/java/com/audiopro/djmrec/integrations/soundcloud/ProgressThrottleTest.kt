package com.audiopro.djmrec.integrations.soundcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressThrottleTest {
    @Test fun aFloodOfTinyStepsBecomesAFewUpdatesASecond() {
        var now = 0L
        val throttle = ProgressThrottle(clock = { now })
        var emitted = 0
        // 2 GB in 8 KB writes over 400 s: ~262,000 callbacks
        val callbacks = 262_144
        for (i in 1..callbacks) {
            now = i * 400_000L / callbacks
            if (throttle.shouldEmit(i.toFloat() / callbacks)) emitted++
        }
        assertTrue("emitted $emitted", emitted in 100..420)                // about one a second here; never more than 4 a second
    }

    @Test fun firstAndLastAreAlwaysReported() {
        var now = 0L
        val throttle = ProgressThrottle(clock = { now })
        assertTrue(throttle.shouldEmit(0f))
        now = 10
        assertFalse(throttle.shouldEmit(0.001f))                           // too soon
        assertTrue(throttle.shouldEmit(1f))                                // the end, even right away
        assertFalse(throttle.shouldEmit(1f))                               // but only once
    }

    @Test fun aSlowUploadStillMovesOncePerSecond() {
        var now = 0L
        val throttle = ProgressThrottle(clock = { now })
        throttle.shouldEmit(0f)
        var emitted = 0
        for (s in 1..10) {
            now = s * 1_000L
            if (throttle.shouldEmit(s * 0.001f)) emitted++                 // 0.1% a second: below the 1% step
        }
        assertEquals(10, emitted)
    }
}
