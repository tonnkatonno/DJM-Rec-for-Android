package com.audiopro.djmrec.service

import android.Manifest

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.audiopro.djmrec.DjmRecApplication
import com.audiopro.djmrec.MainActivity
import com.audiopro.djmrec.R
import com.audiopro.djmrec.audio.AudioEngine
import com.audiopro.djmrec.audio.ChannelLevel
import com.audiopro.djmrec.audio.RecordingFormat
import com.audiopro.djmrec.audio.RecordingHealth
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.audio.StereoLevels
import com.audiopro.djmrec.integrations.drive.DriveUploadManager
import com.audiopro.djmrec.integrations.soundcloud.SoundCloudUploadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Foreground service hosting the entire recording session so the OS cannot kill the process
 * mid-capture. Exposes a [LocalBinder] for the UI's ViewModel to observe state directly, and
 * also reacts to notification action buttons (Pause/Resume/Stop) via `onStartCommand`.
 */
class RecordingService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.audiopro.djmrec.action.START"
        const val ACTION_MONITOR = "com.audiopro.djmrec.action.MONITOR"
        const val ACTION_PAUSE = "com.audiopro.djmrec.action.PAUSE"
        const val ACTION_RESUME = "com.audiopro.djmrec.action.RESUME"
        const val ACTION_STOP = "com.audiopro.djmrec.action.STOP"

        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_SAMPLE_RATE = "extra_sample_rate"
        const val EXTRA_BIT_DEPTH = "extra_bit_depth"
        const val EXTRA_CHANNEL_COUNT = "extra_channel_count"
        const val EXTRA_FORMAT = "extra_format"

        /** [EXTRA_CAPTURE_MODE] value: standard AAudio/AudioRecord path via [EXTRA_DEVICE_ID]. */
        const val CAPTURE_MODE_AAUDIO = 0
        /** [EXTRA_CAPTURE_MODE] value: raw libusb isochronous path via the EXTRA_USB_* extras. */
        const val CAPTURE_MODE_USB_ISO = 1
        /** [EXTRA_CAPTURE_MODE] value: rooted /dev/snd ALSA capture path. */
        const val CAPTURE_MODE_ROOT_ALSA = 2
        const val EXTRA_CAPTURE_MODE = "extra_capture_mode"
        const val EXTRA_ALSA_CARD = "extra_alsa_card"
        const val EXTRA_ALSA_DEVICE = "extra_alsa_device"

        // --- Raw USB iso capture params (only used when EXTRA_CAPTURE_MODE == CAPTURE_MODE_USB_ISO) ---
        /** `UsbDeviceConnection.getFileDescriptor()`; see [UsbAudioManager.openIsoCaptureHandle]. */
        const val EXTRA_USB_FD = "extra_usb_fd"
        const val EXTRA_USB_INTERFACE = "extra_usb_interface"
        const val EXTRA_USB_ALT_SETTING = "extra_usb_alt_setting"
        const val EXTRA_USB_ENDPOINT = "extra_usb_endpoint"
        const val EXTRA_USB_MAX_PACKET_SIZE = "extra_usb_max_packet_size"
        const val EXTRA_USB_TOTAL_CHANNELS = "extra_usb_total_channels"
        const val EXTRA_USB_SUBFRAME_SIZE = "extra_usb_subframe_size"
        const val EXTRA_USB_CHANNEL_OFFSET = "extra_usb_channel_offset"
        const val EXTRA_USB_CLOCK_CONTROL_INTERFACE = "extra_usb_clock_control_interface"
        const val EXTRA_USB_CLOCK_SOURCE_ID = "extra_usb_clock_source_id"
        const val EXTRA_USB_CLOCK_FREQUENCY_SETTABLE = "extra_usb_clock_frequency_settable"
        const val EXTRA_USB_FEEDBACK_ENDPOINT = "extra_usb_feedback_endpoint"
        const val EXTRA_USB_FEEDBACK_MAX_PACKET_SIZE = "extra_usb_feedback_max_packet_size"
        const val EXTRA_USB_VENDOR_ID = "extra_usb_vendor_id"
        const val EXTRA_USB_PRODUCT_ID = "extra_usb_product_id"
        const val EXTRA_USB_RAW_DESCRIPTORS = "extra_usb_raw_descriptors"

        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1001
        private const val METER_UPDATE_INTERVAL_MS = 66L // ~15 fps, plenty for a VU meter
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 500L
        private const val USB_SIGNAL_CHECK_INTERVAL_MS = 100L
        private const val USB_SIGNAL_CHECK_TIMEOUT_MS = 1500L
        private const val LOW_STORAGE_THRESHOLD_BYTES = 500L * 1024 * 1024 // 500 MB
        private const val STORAGE_CHECK_EVERY_N_NOTIFICATION_TICKS = 10 // ~5s at 500ms/tick
        private const val ALERT_CHANNEL_ID = "recording_alerts"
        private const val ALERT_NOTIFICATION_ID = 1010
        /** Android 16 "Live Update" opt-in (Notification.EXTRA_REQUEST_PROMOTED_ONGOING); spelled
         *  out because compileSdk 34 predates the constant. Ignored by older Android versions. */
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }

    inner class LocalBinder : android.os.Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val floorLevel = ChannelLevel(peakDb = -60f, rmsDb = -60f, isClipping = false)
    private val _levels = MutableStateFlow(StereoLevels(floorLevel, floorLevel))
    val levels: StateFlow<StereoLevels> = _levels.asStateFlow()

    private val _elapsedMillis = MutableStateFlow(0L)
    val elapsedMillis: StateFlow<Long> = _elapsedMillis.asStateFlow()

    private val emptyWaveform = FloatArray(0)
    private val _waveformBins = MutableStateFlow(emptyWaveform)
    val waveformBins: StateFlow<FloatArray> = _waveformBins.asStateFlow()

    private var wakeLock: PowerManager.WakeLock? = null

    // Dedicated urgent-audio-priority thread for pulling meter/elapsed data off the native
    // engine and refreshing the notification — kept separate from the main/UI thread so meter
    // polling never gets starved by UI work, matching the spec's thread-priority requirement.
    private lateinit var monitorThread: HandlerThread
    private lateinit var monitorHandler: Handler

    private var currentFormat: RecordingFormat = RecordingFormat.WAV
    private var currentBitDepth: Int = 24
    private var pendingRecordingFormat: RecordingFormat? = null
    private var currentOutputFile: File? = null
    private var deviceLabel: String = "USB Mixer"
    /** True when the in-progress session opened via [startUsbIsoSession] rather than [startSession]. */
    private var isUsbIsoSession = false
    /** True when the audio stream is open for monitoring but no file is being written. */
    private var isMonitoringOnly = false
    @Volatile
    private var waveformEnabled = true
    private var waveformPollTick = 0
    /** 0 == unlimited. Checked against [currentOutputFile]'s on-disk size, not a byte counter
     *  from the native writer -- avoids adding a JNI surface just for this. */
    @Volatile
    private var maxFileSizeBytes: Long = 0L

    /** Elapsed-millis timestamps of each "mark" tap during the current take; cleared on every
     *  new recording. Written out as a sidecar tracklist file on finalize (see [stopSession]). */
    private val trackMarks = mutableListOf<Long>()
    private val _trackMarkCount = MutableStateFlow(0)
    val trackMarkCount: StateFlow<Int> = _trackMarkCount.asStateFlow()

    /** Non-blocking -- recording is never auto-stopped for this, just surfaced so a multi-GB
     *  take doesn't silently run the device out of space mid-set. */
    private val _lowStorageWarning = MutableStateFlow(false)
    val lowStorageWarning: StateFlow<Boolean> = _lowStorageWarning.asStateFlow()
    private var storageCheckTick = 0

    private val meterRunnable = object : Runnable {
        override fun run() {
            if (_state.value is RecordingState.Recording || _state.value is RecordingState.Paused ||
                _state.value is RecordingState.Monitoring) {
                val raw = AudioEngine.getLevels()
                val clipping = AudioEngine.isClipping()
                _levels.value = StereoLevels(
                    left = ChannelLevel(peakDb = raw[0], rmsDb = raw[1], isClipping = clipping),
                    right = ChannelLevel(peakDb = raw[2], rmsDb = raw[3], isClipping = clipping)
                )
                _elapsedMillis.value = AudioEngine.getElapsedMillis()
                // The waveform is visual context, not a meter. Half-rate polling avoids copying
                // 2,048 atomic floats every meter tick and materially reduces display/battery work.
                if (waveformEnabled && waveformPollTick++ % 2 == 0) {
                    _waveformBins.value = AudioEngine.getWaveformBins()
                }
                checkMaxFileSize()
                monitorHandler.postDelayed(this, METER_UPDATE_INTERVAL_MS)
            }
        }
    }

    private val notificationRunnable = object : Runnable {
        override fun run() {
            if (_state.value is RecordingState.Recording || _state.value is RecordingState.Paused ||
                _state.value is RecordingState.Monitoring) {
                updateHealth()
                updateNotification()
                if (_state.value is RecordingState.Recording &&
                    storageCheckTick++ % STORAGE_CHECK_EVERY_N_NOTIFICATION_TICKS == 0) {
                    checkLowStorage()
                }
                monitorHandler.postDelayed(this, NOTIFICATION_UPDATE_INTERVAL_MS)
            }
        }
    }

    private val _health = MutableStateFlow(RecordingHealth())
    val health: StateFlow<RecordingHealth> = _health.asStateFlow()
    private var lastFileBytes = -1L
    private var lastGrowthAtMs = 0L
    private var lastSignalAtMs = 0L
    /** Wall-clock start for the notification chronometer (now - elapsed), re-based only when it
     *  drifts, so the lock-screen timer ticks smoothly on its own between our updates. */
    private var chronometerBaseMs = 0L
    private var alertPosted = false

    /** Runs on the monitor thread every notification tick (500 ms). */
    private fun updateHealth() {
        val now = SystemClock.elapsedRealtime()
        val state = _state.value
        val file = currentOutputFile
        if (file == null || (state !is RecordingState.Recording && state !is RecordingState.Paused)) {
            _health.value = RecordingHealth()
            clearHealthAlert()
            return
        }
        val bytes = file.length()
        if (bytes != lastFileBytes || state is RecordingState.Paused || lastGrowthAtMs == 0L) {
            // Paused legitimately doesn't grow -- keep the stall clock from running meanwhile.
            lastGrowthAtMs = now
        }
        lastFileBytes = bytes
        val levels = _levels.value
        if (maxOf(levels.left.peakDb, levels.right.peakDb) > RecordingHealth.SIGNAL_FLOOR_DB ||
            state is RecordingState.Paused || lastSignalAtMs == 0L) {
            lastSignalAtMs = now
        }
        val health = RecordingHealth(
            fileName = file.name,
            format = currentFormat,
            fileBytes = bytes,
            stalled = now - lastGrowthAtMs > RecordingHealth.STALL_AFTER_MS,
            noSignal = now - lastSignalAtMs > RecordingHealth.NO_SIGNAL_AFTER_MS,
            xruns = try { AudioEngine.getXRunCount() } catch (_: Throwable) { 0 }
        )
        _health.value = health
        if (health.ok) clearHealthAlert() else postHealthAlert(health)
    }

    private fun resetHealth() {
        lastFileBytes = -1L
        lastGrowthAtMs = 0L
        lastSignalAtMs = 0L
        chronometerBaseMs = 0L
        _health.value = RecordingHealth()
        clearHealthAlert()
    }

    /** Separate high-importance notification, posted once per incident, so a stuck writer or a
     *  dead input buzzes the phone in the DJ's pocket instead of being discovered after the set. */
    private fun postHealthAlert(health: RecordingHealth) {
        if (alertPosted || !canPostNotifications()) return
        alertPosted = true
        val text = when {
            health.stalled -> "The recording file stopped growing. Open DJM-Rec and check the mixer connection."
            else -> "No input signal for ${RecordingHealth.NO_SIGNAL_AFTER_MS / 1000}s. Is the mixer channel/fader up?"
        }
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (health.stalled) "⚠ Recording stalled" else "⚠ No input signal")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(mainActivityPendingIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun clearHealthAlert() {
        if (!alertPosted) return
        alertPosted = false
        NotificationManagerCompat.from(this).cancel(ALERT_NOTIFICATION_ID)
    }

    private fun checkLowStorage() {
        val outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        _lowStorageWarning.value = outputDir.usableSpace < LOW_STORAGE_THRESHOLD_BYTES
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        monitorThread = HandlerThread("AudioMonitorThread", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
        monitorHandler = Handler(monitorThread.looper)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    fun setWaveformEnabled(enabled: Boolean) {
        waveformEnabled = enabled
        waveformPollTick = 0
        AudioEngine.setWaveformEnabled(enabled)
        if (!enabled) _waveformBins.value = emptyWaveform
    }

    fun setGainDb(db: Float) {
        AudioEngine.setGainDb(db)
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        AudioEngine.setMonitoringEnabled(enabled)
    }

    /** [mb] in megabytes; 0 or less means unlimited. */
    fun setMaxFileSizeMb(mb: Int) {
        maxFileSizeBytes = if (mb <= 0) 0L else mb.toLong() * 1024L * 1024L
    }

    /** Timestamps a track change during the current take (e.g. mixed into the next tune). No-op
     *  outside Recording -- there's nothing meaningful to timestamp against otherwise. */
    fun markTrackChange() {
        if (_state.value !is RecordingState.Recording) return
        trackMarks.add(AudioEngine.getElapsedMillis())
        _trackMarkCount.value = trackMarks.size
    }

    /** Auto-stops (finalizing the current file cleanly) once it crosses the configured limit --
     *  same code path as pressing Stop, so the header gets patched and the take stays playable.
     *  Polled from [meterRunnable] rather than tracked in the native writer to avoid adding a
     *  JNI surface just for this. */
    private fun checkMaxFileSize() {
        if (maxFileSizeBytes <= 0L) return
        if (_state.value !is RecordingState.Recording) return
        val file = currentOutputFile ?: return
        if (file.length() >= maxFileSizeBytes) {
            Log.i(TAG, "Max file size ($maxFileSizeBytes bytes) reached -- auto-stopping take")
            stopSession()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_MONITOR -> {
                if (_state.value is RecordingState.Monitoring ||
                    _state.value is RecordingState.Recording ||
                    _state.value is RecordingState.Paused ||
                    _state.value is RecordingState.Preparing) {
                    return START_NOT_STICKY
                }
                pendingRecordingFormat = null
                _state.value = RecordingState.Preparing
                // Promote before native USB open/rate probing can block.
                if (!startForegroundNotification()) {
                    _state.value = RecordingState.Error("Android blocked the recording service -- open the app and try again")
                    stopSelf()
                    return START_NOT_STICKY
                }
                val sampleRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, 48000)
                val bitDepth = intent.getIntExtra(EXTRA_BIT_DEPTH, 24)
                val captureMode = intent.getIntExtra(EXTRA_CAPTURE_MODE, CAPTURE_MODE_AAUDIO)
                if (captureMode == CAPTURE_MODE_ROOT_ALSA) {
                    startRootAlsaSession(
                        card = intent.getIntExtra(EXTRA_ALSA_CARD, -1),
                        device = intent.getIntExtra(EXTRA_ALSA_DEVICE, -1),
                        sampleRateHint = sampleRate,
                        totalChannels = intent.getIntExtra(EXTRA_USB_TOTAL_CHANNELS, 2),
                        bitDepth = bitDepth,
                        channelOffset = intent.getIntExtra(EXTRA_USB_CHANNEL_OFFSET, 0),
                        monitorOnly = true
                    )
                } else if (captureMode == CAPTURE_MODE_USB_ISO) {
                    startUsbIsoSession(
                        fd = intent.getIntExtra(EXTRA_USB_FD, -1),
                        interfaceNumber = intent.getIntExtra(EXTRA_USB_INTERFACE, -1),
                        alternateSetting = intent.getIntExtra(EXTRA_USB_ALT_SETTING, -1),
                        endpointAddress = intent.getIntExtra(EXTRA_USB_ENDPOINT, -1),
                        maxPacketSize = intent.getIntExtra(EXTRA_USB_MAX_PACKET_SIZE, -1),
                        totalChannels = intent.getIntExtra(EXTRA_USB_TOTAL_CHANNELS, 2),
                        subframeSize = intent.getIntExtra(EXTRA_USB_SUBFRAME_SIZE, 4),
                        clockControlInterfaceNumber = intent.getIntExtra(EXTRA_USB_CLOCK_CONTROL_INTERFACE, -1),
                        clockSourceId = intent.getIntExtra(EXTRA_USB_CLOCK_SOURCE_ID, -1),
                        clockSupportsFrequencySet = intent.getBooleanExtra(EXTRA_USB_CLOCK_FREQUENCY_SETTABLE, false),
                        feedbackEndpointAddress = intent.getIntExtra(EXTRA_USB_FEEDBACK_ENDPOINT, -1),
                        feedbackMaxPacketSize = intent.getIntExtra(EXTRA_USB_FEEDBACK_MAX_PACKET_SIZE, -1),
                        vendorId = intent.getIntExtra(EXTRA_USB_VENDOR_ID, -1),
                        productId = intent.getIntExtra(EXTRA_USB_PRODUCT_ID, -1),
                        rawDescriptors = intent.getByteArrayExtra(EXTRA_USB_RAW_DESCRIPTORS) ?: byteArrayOf(),
                        bitDepth = bitDepth,
                        channelOffset = intent.getIntExtra(EXTRA_USB_CHANNEL_OFFSET, 0),
                        sampleRateHint = sampleRate,
                        monitorOnly = true
                    )
                } else {
                    val deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, -1)
                    val channelCount = intent.getIntExtra(EXTRA_CHANNEL_COUNT, 2)
                    startSession(deviceId, sampleRate, channelCount, bitDepth, monitorOnly = true)
                }
            }

            ACTION_START -> {
                // If already monitoring, just begin encoding.
                if (_state.value is RecordingState.Monitoring) {
                    currentFormat = recordingFormatFrom(intent)
                    beginRecordingNow()
                    return START_NOT_STICKY
                }
                // A record press while automatic monitoring is opening is queued. Opening a
                // second UsbDeviceConnection here would invalidate the first raw USB stream.
                if (_state.value is RecordingState.Preparing) {
                    pendingRecordingFormat = recordingFormatFrom(intent)
                    return START_NOT_STICKY
                }
                if (_state.value is RecordingState.Recording ||
                    _state.value is RecordingState.Paused) {
                    return START_NOT_STICKY
                }
                _state.value = RecordingState.Preparing
                if (!startForegroundNotification()) {
                    _state.value = RecordingState.Error("Android blocked the recording service -- open the app and try again")
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Otherwise, open stream + encode immediately (full recording from idle).
                val sampleRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, 48000)
                val bitDepth = intent.getIntExtra(EXTRA_BIT_DEPTH, 24)
                val format = recordingFormatFrom(intent)
                val captureMode = intent.getIntExtra(EXTRA_CAPTURE_MODE, CAPTURE_MODE_AAUDIO)

                if (captureMode == CAPTURE_MODE_ROOT_ALSA) {
                    startRootAlsaSession(
                        card = intent.getIntExtra(EXTRA_ALSA_CARD, -1),
                        device = intent.getIntExtra(EXTRA_ALSA_DEVICE, -1),
                        sampleRateHint = sampleRate,
                        totalChannels = intent.getIntExtra(EXTRA_USB_TOTAL_CHANNELS, 2),
                        bitDepth = bitDepth,
                        channelOffset = intent.getIntExtra(EXTRA_USB_CHANNEL_OFFSET, 0),
                        format = format,
                        monitorOnly = false
                    )
                } else if (captureMode == CAPTURE_MODE_USB_ISO) {
                    startUsbIsoSession(
                        fd = intent.getIntExtra(EXTRA_USB_FD, -1),
                        interfaceNumber = intent.getIntExtra(EXTRA_USB_INTERFACE, -1),
                        alternateSetting = intent.getIntExtra(EXTRA_USB_ALT_SETTING, -1),
                        endpointAddress = intent.getIntExtra(EXTRA_USB_ENDPOINT, -1),
                        maxPacketSize = intent.getIntExtra(EXTRA_USB_MAX_PACKET_SIZE, -1),
                        totalChannels = intent.getIntExtra(EXTRA_USB_TOTAL_CHANNELS, 2),
                        subframeSize = intent.getIntExtra(EXTRA_USB_SUBFRAME_SIZE, 4),
                        clockControlInterfaceNumber = intent.getIntExtra(EXTRA_USB_CLOCK_CONTROL_INTERFACE, -1),
                        clockSourceId = intent.getIntExtra(EXTRA_USB_CLOCK_SOURCE_ID, -1),
                        clockSupportsFrequencySet = intent.getBooleanExtra(EXTRA_USB_CLOCK_FREQUENCY_SETTABLE, false),
                        feedbackEndpointAddress = intent.getIntExtra(EXTRA_USB_FEEDBACK_ENDPOINT, -1),
                        feedbackMaxPacketSize = intent.getIntExtra(EXTRA_USB_FEEDBACK_MAX_PACKET_SIZE, -1),
                        vendorId = intent.getIntExtra(EXTRA_USB_VENDOR_ID, -1),
                        productId = intent.getIntExtra(EXTRA_USB_PRODUCT_ID, -1),
                        rawDescriptors = intent.getByteArrayExtra(EXTRA_USB_RAW_DESCRIPTORS) ?: byteArrayOf(),
                        bitDepth = bitDepth,
                        channelOffset = intent.getIntExtra(EXTRA_USB_CHANNEL_OFFSET, 0),
                        sampleRateHint = sampleRate,
                        format = format,
                        monitorOnly = false
                    )
                } else {
                    val deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, -1)
                    val channelCount = intent.getIntExtra(EXTRA_CHANNEL_COUNT, 2)
                    startSession(deviceId, sampleRate, channelCount, bitDepth, format, monitorOnly = false)
                }
            }

            ACTION_PAUSE -> pauseSession()
            ACTION_RESUME -> resumeSession()
            ACTION_STOP -> stopSession()
        }
        // Deliberately not sticky: if the process is killed mid-recording we do not want to
        // silently resume capturing without the user re-confirming — safer default for a
        // professional recording tool than risking a corrupt/incomplete file being extended.
        return START_NOT_STICKY
    }

    private fun recordingFormatFrom(intent: Intent): RecordingFormat {
        val value = intent.getIntExtra(EXTRA_FORMAT, currentFormat.nativeValue)
        return RecordingFormat.entries.firstOrNull { it.nativeValue == value } ?: RecordingFormat.FLAC
    }

    fun startSession(
        audioManagerDeviceId: Int,
        sampleRateHint: Int,
        channelCount: Int,
        bitDepth: Int,
        format: RecordingFormat = RecordingFormat.WAV,
        monitorOnly: Boolean = false
    ) {
        if (_state.value is RecordingState.Recording || _state.value is RecordingState.Monitoring) return
        _state.value = RecordingState.Preparing
        isUsbIsoSession = false
        isMonitoringOnly = monitorOnly
        currentBitDepth = bitDepth

        val negotiatedRate = AudioEngine.open(audioManagerDeviceId, sampleRateHint, channelCount, bitDepth)
        if (negotiatedRate <= 0) {
            failPreparation("Failed to open exclusive audio stream")
            return
        }

        if (monitorOnly) {
            beginMonitoring()
        } else {
            beginEncodingOrFail(bitDepth, format)
        }
    }

    fun startUsbIsoSession(
        fd: Int,
        interfaceNumber: Int,
        alternateSetting: Int,
        endpointAddress: Int,
        maxPacketSize: Int,
        totalChannels: Int,
        subframeSize: Int,
        clockControlInterfaceNumber: Int,
        clockSourceId: Int,
        clockSupportsFrequencySet: Boolean,
        feedbackEndpointAddress: Int,
        feedbackMaxPacketSize: Int,
        vendorId: Int,
        productId: Int,
        rawDescriptors: ByteArray,
        bitDepth: Int,
        channelOffset: Int,
        sampleRateHint: Int,
        format: RecordingFormat = RecordingFormat.WAV,
        monitorOnly: Boolean = false
    ) {
        if (_state.value is RecordingState.Recording || _state.value is RecordingState.Monitoring) return
        _state.value = RecordingState.Preparing
        isUsbIsoSession = true
        isMonitoringOnly = monitorOnly
        currentBitDepth = bitDepth

        if (fd < 0 || interfaceNumber < 0 || endpointAddress < 0 || maxPacketSize <= 0) {
            failPreparation("Invalid USB capture parameters")
            releaseIsoConnectionIfNeeded()
            return
        }

        val negotiatedRate = AudioEngine.openUsbIso(
            fd, interfaceNumber, alternateSetting, endpointAddress, maxPacketSize,
            totalChannels, subframeSize, bitDepth, channelOffset,
            clockControlInterfaceNumber, clockSourceId, clockSupportsFrequencySet,
            feedbackEndpointAddress, feedbackMaxPacketSize, vendorId, productId,
            rawDescriptors, sampleRateHint
        )
        if (negotiatedRate <= 0) {
            failPreparation("Failed to open USB isochronous capture")
            releaseIsoConnectionIfNeeded()
            return
        }

        if (monitorOnly) {
            beginMonitoring()
        } else {
            beginUsbIsoEncodingWhenSignalReady(bitDepth, format)
        }
    }

    fun startRootAlsaSession(
        card: Int,
        device: Int,
        sampleRateHint: Int,
        totalChannels: Int,
        bitDepth: Int,
        channelOffset: Int,
        format: RecordingFormat = RecordingFormat.WAV,
        monitorOnly: Boolean = false
    ) {
        if (_state.value is RecordingState.Recording || _state.value is RecordingState.Monitoring) return
        _state.value = RecordingState.Preparing
        isUsbIsoSession = false
        isMonitoringOnly = monitorOnly
        currentBitDepth = bitDepth

        val negotiatedRate = AudioEngine.openRootAlsa(
            card, device, sampleRateHint, totalChannels, bitDepth, channelOffset
        )
        if (negotiatedRate <= 0) {
            failPreparation("Failed to open root ALSA capture")
            return
        }

        if (monitorOnly) {
            beginMonitoring()
        } else {
            beginEncodingOrFail(bitDepth, format)
        }
    }

    /** Transitions from Monitoring to Recording: starts the encoder writing to a file. */
    fun beginRecordingNow() {
        if (_state.value !is RecordingState.Monitoring) return
        isMonitoringOnly = false
        beginEncodingOrFail(currentBitDepth, currentFormat)
    }

    /** Shared setup after stream open for monitoring: starts metering without file output. */
    private fun beginMonitoring() {
        val queuedFormat = pendingRecordingFormat
        if (queuedFormat != null) {
            pendingRecordingFormat = null
            isMonitoringOnly = false
            beginEncodingOrFail(currentBitDepth, queuedFormat)
            return
        }
        acquireWakeLock()
        if (!startForegroundNotification()) {
            AudioEngine.close()
            releaseIsoConnectionIfNeeded()
            failPreparation("Android blocked the recording service -- open the app and try again")
            stopSelf()
            return
        }
        monitorHandler.post(meterRunnable)
        monitorHandler.post(notificationRunnable)
        _state.value = RecordingState.Monitoring
    }

    /** Shared tail of both [startSession] and [startUsbIsoSession] once the native capture
     *  source is open: creates the output file, starts the encoder, and flips to Recording. */
    private fun beginEncodingOrFail(bitDepth: Int, format: RecordingFormat) {
        val outputDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "DJMRec"
        ).apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(outputDir, "mix_$timestamp.${format.extension}")
        currentOutputFile = outputFile
        currentFormat = format
        trackMarks.clear()
        _trackMarkCount.value = 0
        storageCheckTick = 0
        _lowStorageWarning.value = false
        resetHealth()

        val started = AudioEngine.startRecording(outputFile.absolutePath, format.nativeValue)
        if (!started) {
            AudioEngine.close()
            releaseIsoConnectionIfNeeded()
            failPreparation("Failed to start ${format.name} encoder")
            return
        }

        acquireWakeLock()
        if (!startForegroundNotification()) {
            AudioEngine.close()
            releaseIsoConnectionIfNeeded()
            failPreparation("Android blocked the recording service -- open the app and try again")
            stopSelf()
            return
        }
        monitorHandler.post(meterRunnable)
        monitorHandler.post(notificationRunnable)
        _state.value = RecordingState.Recording
    }

    /** A valid isochronous transfer can still contain digital silence. Wait for source bytes. */
    private fun beginUsbIsoEncodingWhenSignalReady(
        bitDepth: Int,
        format: RecordingFormat
    ) {
        val deadlineMs = SystemClock.elapsedRealtime() + USB_SIGNAL_CHECK_TIMEOUT_MS
        val checkSignal = object : Runnable {
            override fun run() {
                if (_state.value !is RecordingState.Preparing || !isUsbIsoSession) return

                val stats = AudioEngine.getUsbIsoTransferStats()
                val receivedBytes = stats.getOrElse(4) { 0L }
                val nonZeroBytes = stats.getOrElse(5) { 0L }
                if (nonZeroBytes > 0L) {
                    beginEncodingOrFail(bitDepth, format)
                    return
                }

                if (SystemClock.elapsedRealtime() < deadlineMs) {
                    monitorHandler.postDelayed(this, USB_SIGNAL_CHECK_INTERVAL_MS)
                    return
                }

                AudioEngine.close()
                releaseIsoConnectionIfNeeded()
                failPreparation(
                    if (receivedBytes == 0L) {
                        "USB audio endpoint sent no data. Reconnect the mixer and retry."
                    } else {
                        "$deviceLabel sent digital silence after MIX/REC OUT routing. " +
                            "Check the mixer's USB output setting, play audio, then retry."
                    }
                )
            }
        }
        monitorHandler.postDelayed(checkSignal, USB_SIGNAL_CHECK_INTERVAL_MS)
    }

    /** Closes the [UsbAudioManager] connection backing native libusb capture, if this session used it. */
    private fun releaseIsoConnectionIfNeeded() {
        if (isUsbIsoSession) {
            (application as DjmRecApplication).usbAudioManager.releaseIsoCaptureConnection()
        }
    }

    private fun failPreparation(message: String) {
        pendingRecordingFormat = null
        _state.value = RecordingState.Error(message)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    fun pauseSession() {
        if (_state.value !is RecordingState.Recording) return
        AudioEngine.pauseRecording()
        _state.value = RecordingState.Paused
        updateNotification()
    }

    fun resumeSession() {
        if (_state.value !is RecordingState.Paused) return
        AudioEngine.resumeRecording()
        _state.value = RecordingState.Recording
        updateNotification()
    }

    fun stopSession() {
        pendingRecordingFormat = null
        if (_state.value is RecordingState.Preparing || _state.value is RecordingState.Error) {
            AudioEngine.close()
            releaseIsoConnectionIfNeeded()
            _state.value = RecordingState.Idle
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (_state.value is RecordingState.Idle || _state.value is RecordingState.Monitoring) {
            // Full stop from monitoring: close the stream.
            if (_state.value is RecordingState.Monitoring) {
                AudioEngine.close()
                releaseIsoConnectionIfNeeded()
                releaseWakeLock()
                _state.value = RecordingState.Idle
                _elapsedMillis.value = 0L
                _levels.value = StereoLevels(floorLevel, floorLevel)
                _waveformBins.value = emptyWaveform
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        // Recording → stop encoding, keep monitoring.
        val outputFile = currentOutputFile
        AudioEngine.stopRecording()
        outputFile?.takeIf { it.isFile && it.length() > 0L }?.let { file ->
            MediaScannerConnection.scanFile(
                this,
                arrayOf(file.absolutePath),
                arrayOf(mimeTypeFor(currentFormat)),
                null
            )
            if (trackMarks.isNotEmpty()) {
                writeTracklistSidecar(file)
            }
            if (DriveUploadManager.isAutoUploadEnabled(this)) {
                DriveUploadManager.enqueueUpload(this, file)
            }
            if (SoundCloudUploadManager.isAutoUploadEnabled(this)) {
                SoundCloudUploadManager.enqueueUpload(this, file)
            }
        }
        currentOutputFile = null
        resetHealth()
        releaseWakeLock()
        _state.value = RecordingState.Monitoring
        isMonitoringOnly = true
        _elapsedMillis.value = 0L
        _lowStorageWarning.value = false
        updateNotification()
    }

    /** Plain-text "mm:ss  Track N" list next to the audio file (same basename, `.tracklist.txt`)
     *  -- excluded from the library screen's wav/flac filter, and merged into the SoundCloud
     *  description at upload time if present (see SoundCloudUploadService). */
    private fun writeTracklistSidecar(audioFile: File) {
        val sidecar = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.tracklist.txt")
        val lines = trackMarks.mapIndexed { index, ms -> "${formatElapsed(ms)}  Track ${index + 1}" }
        try {
            sidecar.writeText(lines.joinToString("\n"))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write tracklist sidecar for ${audioFile.name}", e)
        }
    }

    private fun mimeTypeFor(format: RecordingFormat): String = when (format) {
        RecordingFormat.WAV -> "audio/wav"
        RecordingFormat.FLAC -> "audio/flac"
    }

    fun setDeviceLabel(label: String) {
        deviceLabel = label
    }

    override fun onDestroy() {
        if (_state.value !is RecordingState.Idle) {
            AudioEngine.stopRecording()
            AudioEngine.close()
            releaseIsoConnectionIfNeeded()
        }
        releaseWakeLock()
        monitorThread.quitSafely()
        super.onDestroy()
    }

    // --- WakeLock -----------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "djmrec:recording"
        ).apply {
            setReferenceCounted(false)
            acquire(TimeUnit.HOURS.toMillis(6)) // safety timeout; renewed implicitly by continued use
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // --- Notification --------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, android.app.NotificationManager.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_name))
            .setDescription(getString(R.string.notification_channel_desc))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
        val alerts = NotificationChannelCompat.Builder(ALERT_CHANNEL_ID, android.app.NotificationManager.IMPORTANCE_HIGH)
            .setName(getString(R.string.alert_channel_name))
            .setDescription(getString(R.string.alert_channel_desc))
            .setVibrationEnabled(true)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(alerts)
    }

    /**
     * Returns false instead of crashing when the OS refuses to promote this service to
     * foreground -- observed on-device as `SecurityException: Starting FGS with type
     * microphone ... requires ... and the app must be in the eligible state/exemptions`,
     * thrown from deep inside `Service.startForeground()` itself (i.e. after
     * `ContextCompat.startForegroundService()` on the caller side already returned normally --
     * catching there, as [com.audiopro.djmrec.ui.MainViewModel.startForegroundServiceSafely]
     * does, is not enough). This can happen even with RECORD_AUDIO granted and
     * FOREGROUND_SERVICE_MICROPHONE declared: Android 14+ additionally requires the app to be
     * in a narrow "recently interacted with" eligibility window for a microphone-type FGS
     * specifically, which a device-attach auto-start can miss by the time onStartCommand runs.
     */
    private fun startForegroundNotification(): Boolean {
        val notification = buildNotification()
        // minSdk is 29 (Q), so the ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE overload is
        // always available — no legacy startForeground(id, notification) fallback needed.
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "startForeground refused by the OS: ${e.message}")
            false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startForeground refused by the OS: ${e.message}")
            false
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun updateNotification() {
        if (canPostNotifications()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun mainActivityPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Built to be read at a glance on the lock screen, mid-set:
     *  - VISIBILITY_PUBLIC -- a secure lock screen otherwise shows only "Contents hidden".
     *  - A system chronometer while recording, so the timer visibly ticks on its own even
     *    between our 500 ms updates -- a frozen timer is the first sign something died.
     *  - Title carries the verdict (● REC / ⚠ STALLED / ❚❚ PAUSED), body the evidence
     *    (file size growing, format, signal, dropouts).
     *  - On Android 16 it asks to be a promoted "Live Update" (status-bar chip + prominent
     *    lock-screen card); older versions ignore the extra.
     */
    private fun buildNotification(): Notification {
        val state = _state.value
        val isPaused = state is RecordingState.Paused
        val isRecording = state is RecordingState.Recording
        val isMonitoringOnly = state is RecordingState.Monitoring
        val elapsedMs = _elapsedMillis.value
        val elapsed = formatElapsed(elapsedMs)
        val health = _health.value
        val hasSignal = maxOf(_levels.value.left.peakDb, _levels.value.right.peakDb) > RecordingHealth.SIGNAL_FLOOR_DB

        val contentIntent = mainActivityPendingIntent()

        val toggleAction = if (isPaused) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, getString(R.string.action_resume),
                servicePendingIntent(ACTION_RESUME)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, getString(R.string.action_pause),
                servicePendingIntent(ACTION_PAUSE)
            )
        }
        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop),
            servicePendingIntent(ACTION_STOP)
        )

        val title = when {
            isMonitoringOnly -> getString(R.string.notification_title_monitoring, deviceLabel)
            isPaused -> getString(R.string.notification_title_paused_at, elapsed)
            health.stalled -> getString(R.string.notification_title_stalled)
            else -> getString(R.string.notification_title_rec, deviceLabel)
        }
        val summary = if (isMonitoringOnly) {
            getString(R.string.notification_text_monitoring)
        } else {
            listOfNotNull(
                health.format?.name,
                formatBytes(health.fileBytes),
                when {
                    isPaused -> null
                    health.noSignal -> "⚠ NO SIGNAL"
                    hasSignal -> "SIGNAL ●"
                    else -> "quiet"
                },
                health.xruns.takeIf { it > 0 }?.let { "$it dropouts" }
            ).joinToString("  ·  ")
        }
        val detail = when {
            isMonitoringOnly -> summary
            health.stalled -> "$summary\nFile is NOT growing -- open the app and check the mixer."
            isPaused -> "$summary\nPaused -- nothing is being written."
            else -> "$summary\n✓ Writing ${health.fileName ?: ""}"
        }

        // Stable chronometer base: only re-based when it drifts >1.5 s from the engine's clock
        // (pause/resume), so the ticking lock-screen timer never jumps back and forth.
        if (isRecording) {
            val wanted = System.currentTimeMillis() - elapsedMs
            if (chronometerBaseMs == 0L || kotlin.math.abs(chronometerBaseMs - wanted) > 1_500L) {
                chronometerBaseMs = wanted
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setColor(if (health.stalled || health.noSignal) 0xFFFFAB00.toInt() else 0xFFFF3D00.toInt())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(toggleAction)
            .addAction(stopAction)
        if (isRecording) {
            builder.setUsesChronometer(true).setShowWhen(true).setWhen(chronometerBaseMs)
        } else {
            builder.setUsesChronometer(false).setShowWhen(false)
        }
        if (isRecording || isPaused) {
            builder.addExtras(android.os.Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        }
        return builder.build()
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(Locale.US, "%.2f GB", mb / 1024)
        else String.format(Locale.US, "%.1f MB", mb)
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatElapsed(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
