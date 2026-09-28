package com.audiopro.djmrec.integrations.drive

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.audiopro.djmrec.MainActivity
import com.audiopro.djmrec.R
import com.audiopro.djmrec.library.RemoteLinkIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foreground service that actually performs Drive uploads, one at a time, off a queue fed by
 * [DriveUploadManager.enqueueUpload]. Mirrors [com.audiopro.djmrec.service.RecordingService]'s
 * foreground-notification pattern. State/progress is reported back into [DriveUploadManager]'s
 * StateFlow rather than through a bound interface -- nothing needs to bind to this service.
 */
class UploadService : Service() {

    companion object {
        const val ACTION_UPLOAD = "com.audiopro.djmrec.action.UPLOAD"
        const val EXTRA_FILE_PATH = "extra_file_path"
        private const val TAG = "UploadService"
        private const val CHANNEL_ID = "upload_channel"
        private const val NOTIFICATION_ID = 1002
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<File>(Channel.UNLIMITED)
    private var workerRunning = false

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, android.app.NotificationManager.IMPORTANCE_LOW)
            .setName(getString(R.string.upload_notification_channel_name))
            .setDescription(getString(R.string.upload_notification_channel_desc))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPLOAD) {
            val path = intent.getStringExtra(EXTRA_FILE_PATH)
            if (path != null) {
                startForegroundSafely()
                queue.trySend(File(path))
                startWorkerIfNeeded()
            }
        }
        return START_NOT_STICKY
    }

    private fun startWorkerIfNeeded() {
        if (workerRunning) return
        workerRunning = true
        scope.launch {
            // Drain, then stop -- see SoundCloudUploadService.startWorkerIfNeeded for why this
            // isn't a `for (file in queue)` loop (it never terminates on an unclosed channel).
            while (true) {
                val file = queue.tryReceive().getOrNull()
                    ?: withContext(Dispatchers.Main) {
                        // Last look on Main before stopping; null means we really are done.
                        queue.tryReceive().getOrNull().also { next ->
                            if (next == null) {
                                workerRunning = false
                                ServiceCompat.stopForeground(this@UploadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                    }
                    ?: break
                try {
                    processUpload(file)
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected failure uploading ${file.name}", e)
                    DriveUploadManager.updateState(
                        file.absolutePath,
                        DriveUploadManager.UploadState(
                            DriveUploadManager.Status.FAILED, error = e.message ?: "Upload failed"
                        )
                    )
                }
            }
        }
    }

    private suspend fun processUpload(file: File) {
        DriveUploadManager.updateState(
            file.absolutePath, DriveUploadManager.UploadState(DriveUploadManager.Status.UPLOADING, 0f)
        )
        updateNotification(file.name, 0f)

        when (val outcome = DriveAuthManager.requestAccess(applicationContext)) {
            is DriveAuthManager.Outcome.Authorized -> {
                try {
                    val fileId = DriveUploader.upload(
                        applicationContext, outcome.accessToken, file, mimeTypeFor(file)
                    ) { progress ->
                        DriveUploadManager.updateState(
                            file.absolutePath,
                            DriveUploadManager.UploadState(DriveUploadManager.Status.UPLOADING, progress)
                        )
                        updateNotification(file.name, progress)
                    }
                    DriveUploadManager.markUploaded(applicationContext, file)
                    RemoteLinkIndex.recordDrive(applicationContext, file, fileId)
                    DriveUploadManager.updateState(
                        file.absolutePath, DriveUploadManager.UploadState(DriveUploadManager.Status.DONE, 1f)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Upload failed for ${file.name}", e)
                    DriveUploadManager.updateState(
                        file.absolutePath,
                        DriveUploadManager.UploadState(
                            DriveUploadManager.Status.FAILED, error = e.message ?: "Upload failed"
                        )
                    )
                }
            }
            is DriveAuthManager.Outcome.NeedsConsent -> {
                DriveUploadManager.updateState(
                    file.absolutePath,
                    DriveUploadManager.UploadState(
                        DriveUploadManager.Status.FAILED,
                        error = "Connect Google Drive in Settings first"
                    )
                )
            }
            is DriveAuthManager.Outcome.Failed -> {
                DriveUploadManager.updateState(
                    file.absolutePath,
                    DriveUploadManager.UploadState(DriveUploadManager.Status.FAILED, error = outcome.message)
                )
            }
        }
    }

    private fun mimeTypeFor(file: File): String = when (file.extension.lowercase()) {
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }

    private fun startForegroundSafely() {
        val foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(null, 0f), foregroundType)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground refused by the OS: ${e.message}")
        }
    }

    private fun updateNotification(fileName: String, progress: Float) {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(fileName, progress))
    }

    private fun buildNotification(fileName: String?, progress: Float): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (fileName != null) {
            getString(R.string.upload_notification_title, fileName)
        } else {
            getString(R.string.upload_notification_title_idle)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("${(progress * 100).toInt()}%")
            .setProgress(100, (progress * 100).toInt(), fileName == null)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
