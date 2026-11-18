package com.audiopro.djmrec.integrations.soundcloud

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

// One-time seed value (this build only) for the default description -- see the comment on
// getDefaultDescription() below. Editable/clearable in Settings like any other value.
private val DEFAULT_DESCRIPTION_SEED = """
◢◤◢◤◢◤◢◤◢◤◢◤◢◤◢◤◢◤◢◤◢◤◢◤
L⠀◈⠀⠀⠀⠀────────────────⠀
I⠀ ◈⠀⠀⠀⠀1⠀8⠀0⠀B⠀P⠀M⠀⠀E⠀N⠀E⠀R⠀G⠀Y
S⠀◈⠀⠀⠀⠀█║▌│█│║▌║││█║▌
T⠀◈⠀⠀⠀⠀────────────────
E⠀◈⠀⠀⠀⠀💣🖤⠀F⠀O⠀L⠀L⠀O⠀W⠀🖤💣
N⠀◈⠀⠀⠀⠀instagram.com/tawny.neuro
'⠀⠀⠀⠀∖∖⠀@tawny180⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀
'⠀⠀⠀⠀⠀⠀∖∖⠀
⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀▃▃▃▃▃▃▃▃▃▃▃▃▃
⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀(⠀(⠀⚡⠀)⠀)
L⠀◈⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀💣
O⠀◈⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀💣💣💣
U⠀◈⠀⠀⠀⠀⠀⠀⠀⠀⠀💣💣💣💣
D⠀◈⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀💣💣💣
◥◣◥◣◥◣◥◣◥◣◥◣◥◣◥◣◥◣◥◣◥◣◥◣⠀
""".trimIndent()

/**
 * In-process singleton holding SoundCloud upload state -- mirrors
 * [com.audiopro.djmrec.integrations.drive.DriveUploadManager]. The actual network work runs in
 * [SoundCloudUploadService], which reports progress back into this object.
 */
object SoundCloudUploadManager {

    /** PREPARING: a set over the API's ~2 GiB line is being turned into a smaller FLAC copy first ([SoundCloudUploadCopy]). */
    enum class Status { QUEUED, PREPARING, UPLOADING, DONE, FAILED }
    data class UploadState(
        val status: Status,
        val progress: Float = 0f,
        val error: String? = null,
        val trackUrl: String? = null
    )

    private const val PREFS_NAME = "soundcloud_integration"
    private const val KEY_UPLOADED_PATHS = "uploaded_paths"
    private const val KEY_AUTO_UPLOAD = "soundcloud_auto_upload"
    private const val KEY_DEFAULT_GENRE = "default_genre"
    private const val KEY_DEFAULT_TAGS = "default_tags"
    private const val KEY_DEFAULT_DESCRIPTION = "default_description"

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

    fun setAutoUploadEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_UPLOAD, enabled).apply()
    }
    fun isAutoUploadEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_UPLOAD, false)

    // One-time seed values below (this build only) so the fields show pre-filled on first run
    // instead of needing to be typed on a phone keyboard -- editable/clearable in Settings same
    // as any other value; once saved once, the user's own edit wins over this fallback anyway.

    /** Applied to every upload (manual or auto) -- set once in Settings so nothing needs
     *  retyping per-track on soundcloud.com afterward. */
    fun getDefaultGenre(context: Context): String =
        prefs(context).getString(KEY_DEFAULT_GENRE, "Drum & Bass") ?: ""
    fun setDefaultGenre(context: Context, value: String) {
        prefs(context).edit().putString(KEY_DEFAULT_GENRE, value).apply()
    }

    /** Comma-separated as typed; normalized to SoundCloud's space-separated `tag_list` format
     *  (quoting multi-word tags) in [SoundCloudUploader]. */
    fun getDefaultTags(context: Context): String =
        prefs(context).getString(KEY_DEFAULT_TAGS, "neurocrack, tawny, drumandbass, neurofunk, dnb, neuro") ?: ""
    fun setDefaultTags(context: Context, value: String) {
        prefs(context).edit().putString(KEY_DEFAULT_TAGS, value).apply()
    }

    fun getDefaultDescription(context: Context): String =
        prefs(context).getString(KEY_DEFAULT_DESCRIPTION, DEFAULT_DESCRIPTION_SEED) ?: ""
    fun setDefaultDescription(context: Context, value: String) {
        prefs(context).edit().putString(KEY_DEFAULT_DESCRIPTION, value).apply()
    }

    fun currentState(file: File): UploadState? = _uploads.value[file.absolutePath]

    internal fun updateState(path: String, state: UploadState) {
        _uploads.value = _uploads.value + (path to state)
    }

    /** Always uploads as a private track -- matches the user's stated workflow (record -> Drive
     *  -> SoundCloud private, so it's immediately shareable but not publicly listed). */
    fun enqueueUpload(context: Context, file: File) {
        updateState(file.absolutePath, UploadState(Status.QUEUED))
        val intent = Intent(context, SoundCloudUploadService::class.java).apply {
            action = SoundCloudUploadService.ACTION_UPLOAD
            putExtra(SoundCloudUploadService.EXTRA_FILE_PATH, file.absolutePath)
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
