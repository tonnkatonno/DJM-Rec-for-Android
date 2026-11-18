package com.audiopro.djmrec.integrations.soundcloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class HalfBandDecimatorTest {
    private val rate = 96_000

    /** Runs [input] (interleaved, [channels]) through a decimator in blocks of [block] frames (or [blocks]) and returns the output. */
    private fun run(input: DoubleArray, channels: Int, block: Int = 4096, blocks: List<Int>? = null): DoubleArray {
        val decimator = HalfBandDecimator(channels)
        val out = ArrayList<Double>()
        val collect: (DoubleArray, Int) -> Unit = { buffer, count -> for (i in 0 until count * channels) out.add(buffer[i]) }
        val frames = input.size / channels
        var position = 0
        var n = 0
        while (position < frames) {
            val size = minOf(blocks?.get(n++ % blocks.size) ?: block, frames - position)
            decimator.process(input.copyOfRange(position * channels, (position + size) * channels), size, collect)
            position += size
        }
        decimator.finish(collect)
        return out.toDoubleArray()
    }

    private fun tone(frequency: Double, frames: Int, amplitude: Double = 0.5) =
        DoubleArray(frames) { amplitude * sin(2 * PI * frequency * it / rate) }

    @Test fun outputHasHalfTheFramesRoundedUp() {
        for (frames in listOf(0, 1, 2, 3, 100, 1001, 20_000)) {
            assertEquals("frames=$frames", (frames + 1) / 2, run(DoubleArray(frames * 2), 2).size / 2)
        }
    }

    @Test fun passbandToneIsKeptAndStaysOnTheSameTimeline() {
        for (frequency in listOf(1_000.0, 20_000.0)) {
            val input = tone(frequency, rate * 2)
            val output = run(input, 1)
            var worst = 0.0
            for (m in 200 until output.size - 200) worst = maxOf(worst, abs(output[m] - input[2 * m]))    // sample m sits on input 2m
            assertTrue("$frequency Hz: worst error $worst", worst < 2e-5)
        }
    }

    @Test fun whatWouldFoldBackIsRejected() {
        for (frequency in listOf(28_000.0, 30_000.0, 40_000.0)) {
            val output = run(tone(frequency, rate * 2), 1)
            var sum = 0.0
            for (m in 200 until output.size - 200) sum += output[m] * output[m]
            val rms = sqrt(sum / (output.size - 400))
            assertTrue("$frequency Hz: rms $rms", rms < 0.5 * 1e-5)              // at least 100 dB below the input tone
        }
    }

    @Test fun blockSizesDoNotChangeTheResult() {
        val random = Random(7)
        val input = DoubleArray(10_001 * 2) { random.nextDouble(-1.0, 1.0) }
        val oneShot = run(input, 2, block = 1 shl 20)
        val ragged = run(input, 2, blocks = listOf(1, 777, 2, 95, 4096, 3, 48))
        assertArrayEquals(oneShot, ragged, 0.0)
    }

    @Test fun channelsStaySeparate() {
        val left = tone(5_000.0, 9_600)
        val stereo = DoubleArray(left.size * 2) { if (it % 2 == 0) left[it / 2] else 0.0 }
        val output = run(stereo, 2)
        val mono = run(left, 1)
        for (m in mono.indices) {
            assertEquals(mono[m], output[2 * m], 0.0)
            assertEquals(0.0, output[2 * m + 1], 0.0)
        }
    }

    @Test fun silenceAndDcPassUnchanged() {
        val output = run(DoubleArray(4_000) { 0.25 }, 1)
        for (m in 100 until output.size - 100) assertEquals(0.25, output[m], 1e-12)
        assertTrue(run(DoubleArray(4_000), 1).all { it == 0.0 })
    }
}
