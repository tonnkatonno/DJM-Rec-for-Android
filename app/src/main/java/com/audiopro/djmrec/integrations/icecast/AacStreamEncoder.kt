package com.audiopro.djmrec.integrations.icecast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * Encodes interleaved stereo 16-bit PCM (as pulled from [com.audiopro.djmrec.audio.AudioEngine
 * .pullStreamPcm16]) into ADTS-framed AAC-LC packets ready to be written straight into an Icecast
 * HTTP source-client request body. Uses the platform [MediaCodec] rather than a native encoder --
 * this pipeline has no existing native-side media codec, and Icecast tolerates hundreds of ms of
 * latency, so there's nothing to gain from encoding on the realtime audio thread.
 *
 * Not thread-safe: [encode] must only be called from one thread at a time (the same one that
 * calls [start]/[stop]).
 */
class AacStreamEncoder(
    private val sampleRate: Int,
    private val channelCount: Int = 2,
    private val bitRateBps: Int = 128_000
) {
    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    fun start() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRateBps)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    /**
     * Feeds [frameCount] interleaved stereo frames from [pcm] into the encoder and drains any
     * ADTS-framed packets that are now ready. May return an empty list (the encoder is allowed
     * to buffer internally before producing output).
     */
    fun encode(pcm: ShortArray, frameCount: Int): List<ByteArray> {
        val codec = codec ?: return emptyList()
        val sampleCount = frameCount * channelCount

        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val inputBuffer: ByteBuffer = codec.getInputBuffer(inputIndex) ?: return emptyList()
            inputBuffer.clear()
            inputBuffer.asShortBuffer().put(pcm, 0, sampleCount)
            codec.queueInputBuffer(inputIndex, 0, sampleCount * 2, 0, 0)
        }
        // If no input buffer was free, the frame is dropped rather than blocking the poll loop --
        // matches the ring buffer's own drop-on-overrun philosophy for this non-critical path.

        val packets = mutableListOf<ByteArray>()
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (outputIndex < 0) break
            val outputBuffer: ByteBuffer = codec.getOutputBuffer(outputIndex) ?: break
            val raw = ByteArray(bufferInfo.size)
            outputBuffer.get(raw)
            packets.add(withAdtsHeader(raw, sampleRate, channelCount))
            codec.releaseOutputBuffer(outputIndex, false)
        }
        return packets
    }

    fun stop() {
        try {
            codec?.stop()
        } finally {
            codec?.release()
            codec = null
        }
    }

    companion object {
        private val ADTS_SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
        )

        private fun adtsSampleRateIndex(sampleRate: Int): Int {
            val index = ADTS_SAMPLE_RATES.indexOf(sampleRate)
            return if (index >= 0) index else 4 // fall back to 44100 if somehow unlisted
        }

        /** Prepends the 7-byte ADTS header (no CRC) each AAC-LC raw frame needs to be a valid,
         *  self-describing stream that any player/Icecast relay can parse without a container. */
        private fun withAdtsHeader(aacFrame: ByteArray, sampleRate: Int, channelCount: Int): ByteArray {
            val packetLength = aacFrame.size + 7
            val freqIdx = adtsSampleRateIndex(sampleRate)
            val header = ByteArray(7)
            header[0] = 0xFF.toByte()
            header[1] = 0xF9.toByte() // MPEG-4, layer 0, no CRC
            header[2] = (((MediaCodecInfo.CodecProfileLevel.AACObjectLC - 1) shl 6) or
                (freqIdx shl 2) or (channelCount shr 2)).toByte()
            header[3] = (((channelCount and 3) shl 6) or (packetLength shr 11)).toByte()
            header[4] = ((packetLength shr 3) and 0xFF).toByte()
            header[5] = ((((packetLength and 7) shl 5)) or 0x1F).toByte()
            header[6] = 0xFC.toByte()
            return header + aacFrame
        }
    }
}
