package com.audiopro.djmrec.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingGainTest {
    @Test
    fun clampLimitsToTheSliderRange() {
        assertEquals(-24, RecordingGain.clamp(-100))
        assertEquals(-24, RecordingGain.clamp(-24))
        assertEquals(0, RecordingGain.clamp(0))
        assertEquals(24, RecordingGain.clamp(24))
        assertEquals(24, RecordingGain.clamp(100))
    }

    @Test
    fun defaultIsPlusTwelveAndInsideTheRange() {
        assertEquals(12, RecordingGain.DEFAULT_DB)
        assertTrue(RecordingGain.DEFAULT_DB in RecordingGain.MIN_DB..RecordingGain.MAX_DB)
    }

    @Test
    fun valuesSavedByEarlierBuildsSurviveTheWiderRange() {
        // v0.45 stored whole dB in 0..12; reading them back must not move them.
        for (db in 0..12) assertEquals(db, RecordingGain.clamp(db))
    }

    @Test
    fun labelsCarryAnExplicitSign() {
        assertEquals("+12 dB", RecordingGain.label(12))
        assertEquals("0 dB", RecordingGain.label(0))
        assertEquals("-6 dB", RecordingGain.label(-6))
    }
}
