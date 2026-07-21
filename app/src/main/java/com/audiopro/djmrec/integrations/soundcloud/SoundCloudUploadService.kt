package com.audiopro.djmrec.integrations.soundcloud

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foreground service performing SoundCloud uploads one at a time, off a queue fed by
 * [SoundCloudUploadManager.enqueueUpload]. Mirrors
 * [com.audiopro.djmrec.integrations.drive.UploadService]'s structure.
 */
class SoundCloudUploadService : Service() {

    companion object {
        const val ACTION_UPLOAD = "com.audiopro.djmrec.action.SOUNDCLOUD_UPLOAD"
        const val EXTRA_FILE_PATH = "extra_file_path"
        private const val TAG = "SoundCloudUploadService"
        private const val CHANNEL_ID = "upload_channel" // shared channel with Drive's uploader
        private const val NOTIFICATION_ID = 1003
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
            for (file in queue) {
                processUpload(file)
            }
            workerRunning = false
            withContext(Dispatchers.Main) {
                ServiceCompat.stopForeground(this@SoundCloudUploadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun processUpload(file: File) {
        SoundCloudUploadManager.updateState(
            file.absolutePath, SoundCloudUploadManager.UploadState(SoundCloudUploadManager.Status.UPLOADING, 0f)
        )
        updateNotification(file.name, 0f)

        if (!SoundCloudAuthManager.isConnected(applicationContext)) {
            SoundCloudUploadManager.updateState(
                file.absolutePath,
                SoundCloudUploadManager.UploadState(
                    SoundCloudUploadManager.Status.FAILED,
                    error = "Connect SoundCloud in Settings first"
                )
            )
            return
        }

        try {
            val trackUrl = SoundCloudUploader.upload(
                applicationContext, file, title = file.nameWithoutExtension, private = true,
                genre = SoundCloudUploadManager.getDefaultGenre(applicationContext),
                tags = SoundCloudUploadManager.getDefaultTags(applicationContext),
                description = SoundCloudUploadManager.getDefaultDescription(applicationContext)
            ) { progress ->
                SoundCloudUploadManager.updateState(
                    file.absolutePath,
                    SoundCloudUploadManager.UploadState(SoundCloudUploadManager.Status.UPLOADING, progress)
                )
                updateNotification(file.name, progress)
            }
            SoundCloudUploadManager.markUploaded(applicationContext, file)
            SoundCloudUploadManager.updateState(
                file.absolutePath,
                SoundCloudUploadManager.UploadState(SoundCloudUploadManager.Status.DONE, 1f, trackUrl = trackUrl)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Upload failed for ${file.name}", e)
            SoundCloudUploadManager.updateState(
                file.absolutePath,
                SoundCloudUploadManager.UploadState(
                    SoundCloudUploadManager.Status.FAILED, error = e.message ?: "Upload failed"
                )
            )
        }
    }

    private fun startForegroundSafely() {
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(null, 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
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
        val title = if (fileName != null) "Uploading to SoundCloud: $fileName" else "Cloud upload"
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
