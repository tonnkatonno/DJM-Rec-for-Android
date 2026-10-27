package com.audiopro.djmrec.audio

/**
 * Manual recording gain in whole dB. The native engine applies it before the safety limiter, so
 * the meters, the file and the livestream all carry the same level. The clamp in
 * UsbAudioEngine::setRecordingGainDb must stay in step with [MIN_DB]..[MAX_DB].
 */
object RecordingGain {
    const val MIN_DB = -24
    const val MAX_DB = 24
    const val DEFAULT_DB = 12

    fun clamp(db: Int): Int = db.coerceIn(MIN_DB, MAX_DB)

    /** "+12 dB", "0 dB" or "-6 dB". */
    fun label(db: Int): String = if (db > 0) "+$db dB" else "$db dB"
}
