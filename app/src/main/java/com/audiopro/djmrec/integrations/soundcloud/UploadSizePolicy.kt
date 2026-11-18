package com.audiopro.djmrec.integrations.soundcloud

/**
 * How big a file SoundCloud's upload API takes. Its help pages say 4 GB, but that is the website's uploader: the API's
 * single multipart request (what [SoundCloudUploader] sends) answered HTTP 413 for a 2,191,705,919-byte FLAC on
 * 2026-11-19, after a 2,105,752,462-byte one had gone up -- the line is 2 GiB. A 96 kHz / 24-bit set passes it at about
 * 85 minutes, so a larger file is uploaded as a half-sample-rate FLAC copy instead (see [SoundCloudUploadCopy]).
 */
internal object UploadSizePolicy {
    const val API_LIMIT_BYTES: Long = 1L shl 31
    /** Room for the multipart headers and form fields, with margin. */
    const val SAFE_LIMIT_BYTES: Long = API_LIMIT_BYTES - (32L shl 20)

    fun needsSmallerCopy(fileBytes: Long): Boolean = fileBytes > SAFE_LIMIT_BYTES

    /** A FLAC file's STREAMINFO: sample rate and total samples per channel (0 = unknown), from its first 42 bytes. */
    data class FlacStreamInfo(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val totalSamples: Long)

    fun flacStreamInfo(header: ByteArray): FlacStreamInfo? {
        if (header.size < 42) return null
        if (header[0] != 'f'.code.toByte() || header[1] != 'L'.code.toByte() || header[2] != 'a'.code.toByte() ||
            header[3] != 'C'.code.toByte()) return null
        if ((header[4].toInt() and 0x7F) != 0) return null           // the first metadata block must be STREAMINFO
        fun u(i: Int) = header[i].toInt() and 0xFF
        val b = 18                                                      // 8 (marker + block header) + 10 (block / frame sizes)
        val sampleRate = (u(b) shl 12) or (u(b + 1) shl 4) or (u(b + 2) shr 4)
        val channels = ((u(b + 2) shr 1) and 0x7) + 1
        val bitsPerSample = (((u(b + 2) and 0x1) shl 4) or (u(b + 3) shr 4)) + 1
        val total = ((u(b + 3) and 0x0F).toLong() shl 32) or (u(b + 4).toLong() shl 24) or (u(b + 5).toLong() shl 16) or
            (u(b + 6).toLong() shl 8) or u(b + 7).toLong()
        return FlacStreamInfo(sampleRate, channels, bitsPerSample, total)
    }

    /** How many 2:1 stages bring [sampleRate] to 48 kHz or below (96 kHz -> 1, 192 kHz -> 2, 48 kHz -> 0). */
    fun halvings(sampleRate: Int): Int {
        var rate = sampleRate
        var stages = 0
        while (rate > 48_000 && rate % 2 == 0) {
            rate /= 2
            stages++
        }
        return stages
    }
}
