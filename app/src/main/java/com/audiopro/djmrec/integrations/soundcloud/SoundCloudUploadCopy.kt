package com.audiopro.djmrec.integrations.soundcloud

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.audiopro.djmrec.audio.RecordingFormat
import com.audiopro.djmrec.editor.NativeAudioFileWriter
import com.audiopro.djmrec.editor.PcmDecoders
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * The file that actually goes to SoundCloud. Under the API's ~2 GiB line ([UploadSizePolicy]) that is the recording itself;
 * above it, a FLAC copy at half the sample rate (96 -> 48 kHz, 24-bit kept): still lossless for listening, about half the
 * size, and on the same timeline (the decimation is centred, see [HalfBandDecimator]), so marks and timestamps on SoundCloud
 * match the master. The master is never touched; the copy lives in the cache and the caller deletes it after the upload.
 * Decoding and FLAC writing reuse the set editor's [PcmDecoders] and [NativeAudioFileWriter].
 */
internal object SoundCloudUploadCopy {
    class Prepared(val file: File, val temporary: Boolean)

    private const val BLOCK_FRAMES = 8192

    suspend fun prepare(context: Context, source: File, onProgress: (Float) -> Unit): Prepared {
        if (!UploadSizePolicy.needsSmallerCopy(source.length())) return Prepared(source, false)
        val dir = File(context.cacheDir, "soundcloud-upload").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }     // leftovers of an interrupted upload
        val copy = File(dir, "${source.nameWithoutExtension}.flac")
        try {
            writeHalfRateCopy(context, source, copy, onProgress)
        } catch (error: Throwable) {
            copy.delete()
            throw error
        }
        if (UploadSizePolicy.needsSmallerCopy(copy.length())) {
            val mib = copy.length() shr 20
            copy.delete()
            throw IOException(
                "Even the smaller copy is too large for SoundCloud's upload API ($mib MiB; it takes about 2 GiB). " +
                    "Upload this set on soundcloud.com, which takes up to 4 GB."
            )
        }
        return Prepared(copy, true)
    }

    private suspend fun writeHalfRateCopy(context: Context, source: File, copy: File, onProgress: (Float) -> Unit) {
        val totalFrames = readHeader(source)?.let { UploadSizePolicy.flacStreamInfo(it) }?.totalSamples?.takeIf { it > 0 }
        PcmDecoders.open(context, Uri.fromFile(source), source.extension).use { decoder ->
            val stages = UploadSizePolicy.halvings(decoder.sampleRate)
            if (stages == 0) {
                throw IOException(
                    "This set is over SoundCloud's ~2 GiB upload limit already at ${decoder.sampleRate} Hz, so a smaller " +
                        "lossless copy is not possible. Upload it on soundcloud.com, which takes up to 4 GB."
                )
            }
            val outputRate = decoder.sampleRate shr stages
            val bits = if (decoder.bitsPerSample >= 24) 24 else 16
            val handle = ParcelFileDescriptor.open(
                copy,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE
            ).use { descriptor ->
                // the native writer keeps its own duplicate of the descriptor
                NativeAudioFileWriter.open(descriptor.fd, RecordingFormat.FLAC.nativeValue, outputRate, 2, bits)
            }
            if (handle == 0L) throw IOException("could not open the FLAC encoder for the SoundCloud copy")
            var handleOpen = true
            try {
                var ints = IntArray(BLOCK_FRAMES * 2)
                var writeFailed = false
                val write: (DoubleArray, Int) -> Unit = { buffer, count ->
                    if (!writeFailed && count > 0) {
                        if (ints.size < count * 2) ints = IntArray(count * 2)
                        for (i in 0 until count * 2) ints[i] = toInt32(buffer[i])
                        if (!NativeAudioFileWriter.write(handle, ints, count)) writeFailed = true
                    }
                }
                // stage 0 -> stage 1 -> ... -> writer; downstream[i] receives stage i's output
                val decimators = List(stages) { HalfBandDecimator(2) }
                val downstream = arrayOfNulls<(DoubleArray, Int) -> Unit>(stages)
                var feed: (DoubleArray, Int) -> Unit = write
                for (i in stages - 1 downTo 0) {
                    val stage = decimators[i]
                    val target = feed
                    downstream[i] = target
                    feed = { buffer, count -> stage.process(buffer, count, target) }
                }
                val src = DoubleArray(BLOCK_FRAMES * max(1, decoder.channels))
                val stereo = DoubleArray(BLOCK_FRAMES * 2)
                var position = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val frames = decoder.read(src, BLOCK_FRAMES)
                    if (frames <= 0) break
                    for (i in 0 until frames * decoder.channels) {
                        if (!src[i].isFinite()) {
                            throw IOException(
                                "The recording did not decode cleanly (unexpected sample format), so no SoundCloud copy was " +
                                    "made. Upload this set on soundcloud.com, which takes up to 4 GB."
                            )
                        }
                    }
                    toStereo(src, decoder.channels, stereo, frames)
                    feed(stereo, frames)
                    if (writeFailed) throw IOException("storage write failed while preparing the SoundCloud copy")
                    position += frames
                    if (totalFrames != null) onProgress((position.toFloat() / totalFrames).coerceAtMost(0.99f))
                }
                for (i in 0 until stages) decimators[i].finish(downstream[i]!!)     // in order: each tail flows on
                if (writeFailed) throw IOException("storage write failed while preparing the SoundCloud copy")
                // Samples read in the wrong format change the frame count (24-bit read as 16-bit: 1.5x): never upload that.
                if (totalFrames != null && abs(position - totalFrames) > max(4_096L, totalFrames / 1_000)) {
                    throw IOException(
                        "The recording was not read correctly ($position frames decoded, the file holds $totalFrames), so no " +
                            "SoundCloud copy was made. Upload this set on soundcloud.com, which takes up to 4 GB."
                    )
                }
                handleOpen = false
                if (!NativeAudioFileWriter.close(handle)) throw IOException("could not finalize the SoundCloud copy")
                onProgress(1f)
            } finally {
                if (handleOpen) runCatching { NativeAudioFileWriter.close(handle) }
            }
        }
    }

    private fun readHeader(file: File): ByteArray? = runCatching {
        FileInputStream(file).use { input ->
            val header = ByteArray(42)
            var filled = 0
            while (filled < header.size) {
                val read = input.read(header, filled, header.size - filled)
                if (read < 0) break
                filled += read
            }
            header.copyOf(filled)
        }
    }.getOrNull()

    /** Stereo view of any decoder layout: mono is doubled, extra channels are dropped (as the set editor does). */
    private fun toStereo(source: DoubleArray, channels: Int, stereo: DoubleArray, frames: Int) {
        for (frame in 0 until frames) {
            val base = frame * channels
            val left = source[base]
            stereo[frame * 2] = left
            stereo[frame * 2 + 1] = if (channels > 1) source[base + 1] else left
        }
    }

    private fun toInt32(sample: Double): Int =
        (sample * 2147483648.0).roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}
