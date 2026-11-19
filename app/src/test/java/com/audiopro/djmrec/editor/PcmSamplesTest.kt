package com.audiopro.djmrec.editor

import android.media.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmSamplesTest {
    private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun decode(buffer: ByteBuffer, encoding: Int, count: Int, skip: Int = 0): DoubleArray {
        buffer.position(skip)
        return DoubleArray(count + 1).also { PcmSamples.read(buffer, count, encoding, it, 1) }.copyOfRange(1, count + 1)
    }

    @Test fun integerEncodingsAreScaledToFullScale() {
        val s16 = le(6).putShort(-32768).putShort(16384).putShort(32767)
        assertArrayEquals(doubleArrayOf(-1.0, 0.5, 32767 / 32768.0), decode(s16, AudioFormat.ENCODING_PCM_16BIT, 3), 1e-12)

        val s24 = le(9).put(byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x00, 0x00, 0x40, 0xFF.toByte(), 0xFF.toByte(), 0x7F))
        assertArrayEquals(doubleArrayOf(-1.0, 0.5, 8388607 / 8388608.0), decode(s24, AudioFormat.ENCODING_PCM_24BIT_PACKED, 3), 1e-12)

        val s32 = le(8).putInt(Int.MIN_VALUE).putInt(1 shl 30)
        assertArrayEquals(doubleArrayOf(-1.0, 0.5), decode(s32, AudioFormat.ENCODING_PCM_32BIT, 2), 1e-12)

        val u8 = le(3).put(byteArrayOf(0, 0x80.toByte(), 0xC0.toByte()))
        assertArrayEquals(doubleArrayOf(-1.0, 0.0, 0.5), decode(u8, AudioFormat.ENCODING_PCM_8BIT, 3), 1e-12)
    }

    @Test fun floatIsTakenAsIs() {
        val f = le(8).putFloat(-0.25f).putFloat(0.75f)
        assertArrayEquals(doubleArrayOf(-0.25, 0.75), decode(f, AudioFormat.ENCODING_PCM_FLOAT, 2), 1e-12)
    }

    @Test fun integersReadAsTheirOwnEncodingStayFinite() {
        // The Android 17 failure: 24-bit integers read as float gave NaN. Read in their own encoding, every value is finite.
        val bytes = ByteArray(3 * 1000) { (it * 37 + 11).toByte() }
        val values = decode(le(bytes.size).put(bytes), AudioFormat.ENCODING_PCM_24BIT_PACKED, 1000)
        assertTrue(values.all { it.isFinite() && it >= -1.0 && it < 1.0 })
    }

    @Test fun readsFromTheBufferPosition() {
        val buffer = le(6).putShort(1).putShort(-16384).putShort(8192)
        assertArrayEquals(doubleArrayOf(-0.5, 0.25), decode(buffer, AudioFormat.ENCODING_PCM_16BIT, 2, skip = 2), 1e-12)
    }

    @Test fun sampleSizes() {
        assertEquals(1, PcmSamples.bytesPerSample(AudioFormat.ENCODING_PCM_8BIT))
        assertEquals(2, PcmSamples.bytesPerSample(AudioFormat.ENCODING_PCM_16BIT))
        assertEquals(3, PcmSamples.bytesPerSample(AudioFormat.ENCODING_PCM_24BIT_PACKED))
        assertEquals(4, PcmSamples.bytesPerSample(AudioFormat.ENCODING_PCM_32BIT))
        assertEquals(4, PcmSamples.bytesPerSample(AudioFormat.ENCODING_PCM_FLOAT))
        assertTrue(runCatching { PcmSamples.bytesPerSample(12345) }.exceptionOrNull() is IOException)
    }
}
