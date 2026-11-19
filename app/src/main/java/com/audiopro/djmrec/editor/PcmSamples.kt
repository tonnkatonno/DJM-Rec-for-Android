package com.audiopro.djmrec.editor

import android.media.AudioFormat
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android PCM sample encodings -> doubles (full scale = 1.0). Used by [MediaCodecPcmDecoder] for whatever its codec puts out:
 * a real decoder gives 16-bit or float, but on newer Android the FLAC extractor decodes by itself and hands over "audio/raw" in
 * its own encoding (16-, 24- or 32-bit integer, or float). Reading those integers as float -- what happened on Android 17 on
 * 2026-11-19 -- turns them into noise and NaN.
 */
internal object PcmSamples {
    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> throw IOException("unsupported PCM encoding $encoding")
    }

    /** Reads [count] samples from [source] (from its position, little-endian) into [destination] starting at [offset]. */
    fun read(source: ByteBuffer, count: Int, encoding: Int, destination: DoubleArray, offset: Int) {
        val buffer = source.slice().order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT ->
                for (i in 0 until count) destination[offset + i] = ((buffer.get(i).toInt() and 0xFF) - 128) / 128.0
            AudioFormat.ENCODING_PCM_16BIT ->
                for (i in 0 until count) destination[offset + i] = buffer.getShort(i * 2) / 32768.0
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> for (i in 0 until count) {
                val b = i * 3
                val value = (buffer.get(b).toInt() and 0xFF) or ((buffer.get(b + 1).toInt() and 0xFF) shl 8) or (buffer.get(b + 2).toInt() shl 16)
                destination[offset + i] = value / 8388608.0
            }
            AudioFormat.ENCODING_PCM_32BIT ->
                for (i in 0 until count) destination[offset + i] = buffer.getInt(i * 4) / 2147483648.0
            AudioFormat.ENCODING_PCM_FLOAT ->
                for (i in 0 until count) destination[offset + i] = buffer.getFloat(i * 4).toDouble()
            else -> throw IOException("unsupported PCM encoding $encoding")
        }
    }
}
