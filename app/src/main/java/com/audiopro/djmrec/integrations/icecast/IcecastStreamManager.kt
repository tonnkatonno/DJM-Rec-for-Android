package com.audiopro.djmrec.integrations.icecast

import android.content.Context
import com.audiopro.djmrec.audio.AudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pulls PCM from the native streaming ring buffer ([AudioEngine.pullStreamPcm16]), AAC-encodes it
 * via [AacStreamEncoder], and hands finished ADTS packets off to a sink. The only sink today is a
 * local file ([startLocalFileTest]) -- a way to verify the encoder path end-to-end (play the
 * resulting .aac back) before the real Icecast HTTP source client exists. The poll/encode loop
 * itself doesn't know or care what the sink is, so swapping in an HTTP PUT body later only means
 * adding a new start*() entry point, not touching this loop.
 */
object IcecastStreamManager {

    enum class State { IDLE, RUNNING, ERROR }

    // ~85ms per pull @48kHz -- frequent enough that the 1s native ring buffer never gets close to
    // full, generous enough not to spin the coroutine needlessly while genuinely idle.
    private const val PULL_MAX_FRAMES = 4096
    private const val EMPTY_POLL_BACKOFF_MS = 20L

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /**
     * Starts pulling/encoding into a timestamped local .aac file under the app's external files
     * dir. Returns the destination file immediately -- it is only fully flushed/closed once
     * [stopAndAwait] returns, so callers must await that before sharing/playing it.
     */
    fun startLocalFileTest(context: Context): File {
        stopBlockingCurrentJobReference()

        val sampleRate = AudioEngine.getCurrentSampleRate().takeIf { it > 0 } ?: 48000
        val dir = (context.getExternalFilesDir("stream_test") ?: File(context.cacheDir, "stream_test"))
            .apply { mkdirs() }
        val file = File(dir, "stream-test-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.aac")

        AudioEngine.setStreamingEnabled(true)
        val encoder = AacStreamEncoder(sampleRate)
        encoder.start()
        val out = FileOutputStream(file)
        _state.value = State.RUNNING

        job = scope.launch {
            try {
                while (isActive) {
                    val pcm = AudioEngine.pullStreamPcm16(PULL_MAX_FRAMES)
                    if (pcm != null && pcm.isNotEmpty()) {
                        val frameCount = pcm.size / 2
                        encoder.encode(pcm, frameCount).forEach { packet -> out.write(packet) }
                    } else {
                        delay(EMPTY_POLL_BACKOFF_MS)
                    }
                }
            } catch (e: Exception) {
                _state.value = State.ERROR
            } finally {
                encoder.stop()
                out.close()
                AudioEngine.setStreamingEnabled(false)
            }
        }
        return file
    }

    /** Cancels the running loop and waits for its `finally` block (encoder/file teardown) to
     *  actually finish, so the caller can immediately share/play the file afterward. */
    suspend fun stopAndAwait() {
        val current = job
        job = null
        current?.cancelAndJoin()
        _state.value = State.IDLE
    }

    private fun stopBlockingCurrentJobReference() {
        job?.cancel()
        job = null
    }
}
