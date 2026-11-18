package com.audiopro.djmrec.integrations.soundcloud

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2:1 decimation for the SoundCloud upload copy (96 -> 48 kHz, 88.2 -> 44.1 kHz): a Kaiser-windowed half-band low-pass,
 * 95 taps (49 of them non-zero), cutoff at a quarter of the input rate. At 96 kHz: flat to 20 kHz within +/-0.0001 dB,
 * -6 dB at 24 kHz, at least 100 dB down from 28 kHz; the little that folds back lands above 20 kHz.
 *
 * The filter is centred: output sample m sits exactly on input sample 2m, so the copy keeps the original's timeline (the
 * edges are padded with silence). Streaming: any block sizes give the same output as one pass; [finish] flushes the tail,
 * and the output always holds ceil(input frames / 2) frames. Interleaved doubles in and out, full scale = 1.0.
 */
internal class HalfBandDecimator(private val channels: Int) {
    private val half = HALF
    private val center: Double
    private val oddTaps: IntArray = IntArray((half + 1) / 2) { 2 * it + 1 }   // 1, 3, ..., half
    private val oddWeights: DoubleArray

    private var buffer = DoubleArray((half * 2 + 4096) * channels)
    private var bufferFrames = half                          // starts with `half` frames of silence before the first sample
    private var start = -half.toLong()                       // input index of buffer frame 0
    private var nextOutput = 0L                              // index of the next output frame
    private var received = 0L
    private var finished = false
    private var output = DoubleArray(0)

    init {
        require(channels >= 1) { "channels must be >= 1" }
        val taps = DoubleArray(half + 1)
        for (k in 0..half) {
            val ideal = if (k == 0) 0.5 else sin(PI * k / 2.0) / (PI * k)
            val r = k.toDouble() / half
            taps[k] = ideal * besselI0(BETA * sqrt(1.0 - r * r)) / besselI0(BETA)
        }
        val sum = taps[0] + 2.0 * oddTaps.sumOf { taps[it] }      // even taps (other than the centre) are zero in a half-band
        center = taps[0] / sum
        oddWeights = DoubleArray(oddTaps.size) { taps[oddTaps[it]] / sum }
    }

    /** Feeds [frames] interleaved frames; [emit] receives the finished output frames (the array is reused afterwards). */
    fun process(input: DoubleArray, frames: Int, emit: (DoubleArray, Int) -> Unit) {
        check(!finished) { "already finished" }
        if (frames <= 0) return
        append(input, frames)
        received += frames
        produce(start + bufferFrames - 1 - half, emit)
    }

    /** No more input: the remaining output frames (the signal is taken as silent after its end). */
    fun finish(emit: (DoubleArray, Int) -> Unit) {
        if (finished) return
        finished = true
        append(DoubleArray(half * channels), half)
        produce(received - 1, emit)
    }

    private fun append(input: DoubleArray, frames: Int) {
        val needed = (bufferFrames + frames) * channels
        if (buffer.size < needed) buffer = buffer.copyOf(maxOf(needed, buffer.size * 2))
        System.arraycopy(input, 0, buffer, bufferFrames * channels, frames * channels)
        bufferFrames += frames
    }

    /** Output frames whose centre 2m is at most [lastCentre] and whose whole window is in the buffer. */
    private fun produce(lastCentre: Long, emit: (DoubleArray, Int) -> Unit) {
        val available = start + bufferFrames - 1 - half
        val limit = minOf(lastCentre, available)
        if (2 * nextOutput > limit) return
        val count = ((limit - 2 * nextOutput) / 2 + 1).toInt()
        if (output.size < count * channels) output = DoubleArray(count * channels)
        for (n in 0 until count) {
            val c = (2 * (nextOutput + n) - start).toInt()
            for (ch in 0 until channels) {
                var acc = center * buffer[c * channels + ch]
                for (j in oddTaps.indices) {
                    val k = oddTaps[j]
                    acc += oddWeights[j] * (buffer[(c - k) * channels + ch] + buffer[(c + k) * channels + ch])
                }
                output[n * channels + ch] = acc
            }
        }
        nextOutput += count
        emit(output, count)
        val keepFrom = (2 * nextOutput - half - start).toInt()        // the next window starts here
        if (keepFrom > 0) {
            System.arraycopy(buffer, keepFrom * channels, buffer, 0, (bufferFrames - keepFrom) * channels)
            bufferFrames -= keepFrom
            start += keepFrom
        }
    }

    companion object {
        const val HALF = 47
        const val BETA = 10.0

        /** Modified Bessel function of the first kind, order 0 (the Kaiser window). */
        fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (true) {
                val f = x / (2.0 * k)
                term *= f * f
                sum += term
                if (term < 1e-17 * sum) return sum
                k++
            }
        }
    }
}
