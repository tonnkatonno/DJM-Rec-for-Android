package com.audiopro.djmrec.ui.components

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveformBandHeightTest {
    @Test
    fun aFullScaleToneFillsTheStrip() {
        // Mean |x| of a full-scale sine is 2/pi; drawn unscaled that stopped at ~73 % of the strip.
        val height = waveformBandHeight((2 / PI).toFloat())
        assertTrue(height >= 0.99f, "full-scale tone should reach the top, was $height")
    }

    @Test
    fun silenceAndGarbageDrawNothing() {
        assertEquals(0f, waveformBandHeight(0f))
        assertEquals(0f, waveformBandHeight(-0.5f))
        assertEquals(0f, waveformBandHeight(Float.NaN))
        assertEquals(0f, waveformBandHeight(Float.POSITIVE_INFINITY))
    }

    @Test
    fun heightGrowsWithLevelAndNeverLeavesTheStrip() {
        var previous = 0f
        for (step in 0..200) {
            val height = waveformBandHeight(step / 100f)
            assertTrue(height >= previous, "height must not shrink as the level rises (step $step)")
            assertTrue(height <= 1f, "height must stay inside the strip (step $step)")
            previous = height
        }
    }
}
