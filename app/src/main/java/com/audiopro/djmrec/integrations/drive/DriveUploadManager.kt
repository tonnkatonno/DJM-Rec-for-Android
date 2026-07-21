package com.audiopro.djmrec.integrations.drive

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * In-process (not cross-app) singleton holding Drive upload state, so any Composable can
 * [uploads].collectAsState() directly with no Service-binding boilerplate -- the actual network
 * work runs in [UploadService], which calls back into this same object (same process, no IPC)
 * to report progress. Persists which local files have already been uploaded so the library
 * screen can show a checkmark instead of re-uploading, and a cached Drive folder ID so every
 * upload doesn't need its own find-or-create round trip.
 */
object DriveUploadManager {

    enum class Status { QUEUED, UPLOADING, DONE, FAILED }
    data class UploadState(val status: Status, val progress: Float = 0f, val error: String? = null)

    private const val PREFS_NAME = "drive_integration"
    private const val KEY_FOLDER_ID = "drive_folder_id"
    private const val KEY_UPLOADED_PATHS = "uploaded_paths"
    private const val KEY_CONNECTED_HINT = "drive_connected_hint"
    private const val KEY_AUTO_UPLOAD = "drive_auto_upload"

    private val _uploads = MutableStateFlow<Map<String, UploadState>>(emptyMap())
    val uploads: StateFlow<Map<String, UploadState>> = _uploads.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isUploaded(context: Context, file: File): Boolean =
        prefs(context).getStringSet(KEY_UPLOADED_PATHS, emptySet())?.contains(file.absolutePath) == true

    fun markUploaded(context: Context, file: File) {
        val p = prefs(context)
        val updated = (p.getStringSet(KEY_UPLOADED_PATHS, emptySet()) ?: emptySet()).toMutableSet()
        updated.add(file.absolutePath)
        p.edit().putStringSet(KEY_UPLOADED_PATHS, updated).apply()
    }

    fun getCachedFolderId(context: Context): String? = prefs(context).getString(KEY_FOLDER_ID, null)
    fun setCachedFolderId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_FOLDER_ID, id).apply()
    }

    /** UI-only hint that a successful [DriveAuthManager] consent has happened at least once --
     *  the real grant lives in Play Services, this just avoids showing "Connect" every launch. */
    fun setConnectedHint(context: Context, connected: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONNECTED_HINT, connected).apply()
    }
    fun getConnectedHint(context: Context): Boolean = prefs(context).getBoolean(KEY_CONNECTED_HINT, false)

    fun setAutoUploadEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_UPLOAD, enabled).apply()
    }
    fun isAutoUploadEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_UPLOAD, false)

    /** null means "no upload attempted this session" -- combine with [isUploaded] for the
     *  persisted "uploaded in a previous session" case. */
    fun currentState(file: File): UploadState? = _uploads.value[file.absolutePath]

    internal fun updateState(path: String, state: UploadState) {
        _uploads.value = _uploads.value + (path to state)
    }

    /** Starts (or re-starts, on retry) an upload. [UploadService] does the actual work and
     *  reports progress back via [updateState]. */
    fun enqueueUpload(context: Context, file: File) {
        updateState(file.absolutePath, UploadState(Status.QUEUED))
        val intent = Intent(context, UploadService::class.java).apply {
            action = UploadService.ACTION_UPLOAD
            putExtra(UploadService.EXTRA_FILE_PATH, file.absolutePath)
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
