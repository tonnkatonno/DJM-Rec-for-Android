package com.audiopro.djmrec.audio

/** Container formats the native encoder chain supports. Ordinal maps 1:1 to the C++ enum. */
enum class RecordingFormat(val nativeValue: Int, val extension: String) {
    WAV(0, "wav"),
    FLAC(1, "flac")
}

/** Bit depth requested from the hardware. 24-bit is packed into a 32-bit container by AAudio. */
enum class BitDepth(val bits: Int) {
    PCM_16(16),
    PCM_24(24),
    PCM_32(32)
}

sealed class RecordingState {
    data object Idle : RecordingState()
    data object Preparing : RecordingState()
    /** Audio stream is open and meters/waveform are live, but no file is being written. */
    data object Monitoring : RecordingState()
    data object Recording : RecordingState()
    data object Paused : RecordingState()
    data class Error(val message: String) : RecordingState()
}

/** Real-time peak/RMS reading for one channel, already converted to dBFS by the native meter. */
data class ChannelLevel(val peakDb: Float, val rmsDb: Float, val isClipping: Boolean)

data class StereoLevels(val left: ChannelLevel, val right: ChannelLevel)

/**
 * "Is it really still recording?" -- answered from the output file itself, not just from the
 * state flag: the file must keep growing while Recording. Drives the lock-screen notification
 * and the over-keyguard recorder view.
 */
data class RecordingHealth(
    val fileName: String? = null,
    val format: RecordingFormat? = null,
    val fileBytes: Long = 0L,
    /** Recording, but the file hasn't grown for [STALL_AFTER_MS] -- the writer is stuck. */
    val stalled: Boolean = false,
    /** Recording, but input has been below the silence floor for [NO_SIGNAL_AFTER_MS]. */
    val noSignal: Boolean = false,
    val xruns: Int = 0,
) {
    val ok: Boolean get() = !stalled && !noSignal

    companion object {
        const val STALL_AFTER_MS = 6_000L
        const val NO_SIGNAL_AFTER_MS = 20_000L
        const val SIGNAL_FLOOR_DB = -50f
    }
}
