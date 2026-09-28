package com.audiopro.djmrec.library

import android.content.Context
import android.util.Log
import com.audiopro.djmrec.integrations.drive.DriveAuthManager
import com.audiopro.djmrec.integrations.soundcloud.SoundCloudAuthManager
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * "Is this take already on SoundCloud / Drive, and where?" -- answered from the services
 * themselves rather than from this install's local "uploaded" flags, which a reinstall or
 * "clear data" wipes. A scan lists the user's own SoundCloud tracks and the Drive files this app
 * created (drive.file scope survives reinstalls: the grant is per Google account + app, not per
 * install), then matches them to local files by name:
 *  - SoundCloud: track title == file name without extension (that's how we upload them)
 *  - Drive: file name == file name
 * Results are persisted, so links show instantly on the next launch and the scan only refreshes.
 * Scans are silent: if a service isn't connected (or Drive would need a consent screen), that
 * service is simply skipped.
 */
object RemoteLinkIndex {
    private const val TAG = "RemoteLinkIndex"
    private const val PREFS_NAME = "remote_link_index"
    private const val KEY_SC = "soundcloud"
    private const val KEY_DRIVE = "drive"
    private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

    data class Links(val soundCloudUrl: String? = null, val driveUrl: String? = null)

    enum class ScanState { IDLE, SCANNING, DONE, PARTIAL }

    private val _soundCloud = MutableStateFlow<Map<String, String>>(emptyMap()) // norm(title) -> url
    private val _drive = MutableStateFlow<Map<String, String>>(emptyMap())      // norm(name) -> url
    private val _scanState = MutableStateFlow(ScanState.IDLE)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    /** Emits whenever either map changes -- UI combines with [linksFor]. */
    val soundCloudLinks: StateFlow<Map<String, String>> = _soundCloud.asStateFlow()
    val driveLinks: StateFlow<Map<String, String>> = _drive.asStateFlow()

    private val scanMutex = Mutex()
    @Volatile private var loaded = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun norm(s: String) = s.trim().lowercase()

    fun linksFor(file: File, sc: Map<String, String>, drive: Map<String, String>): Links =
        Links(
            soundCloudUrl = sc[norm(file.nameWithoutExtension)],
            driveUrl = drive[norm(file.name)]
        )

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = prefs(context)
            _soundCloud.value = readMap(p.getString(KEY_SC, null))
            _drive.value = readMap(p.getString(KEY_DRIVE, null))
            loaded = true
        }
    }

    /** Called by the upload services on success, so a fresh upload links immediately. */
    fun recordSoundCloud(context: Context, file: File, url: String) {
        ensureLoaded(context)
        if (!url.startsWith("http")) return
        _soundCloud.value = _soundCloud.value + (norm(file.nameWithoutExtension) to url)
        persist(context)
    }

    fun recordDrive(context: Context, file: File, fileId: String) {
        ensureLoaded(context)
        _drive.value = _drive.value + (norm(file.name) to driveViewUrl(fileId))
        persist(context)
    }

    /** Refreshes both services in parallel. Safe to call often -- concurrent calls coalesce. */
    suspend fun scan(context: Context) {
        ensureLoaded(context)
        if (!scanMutex.tryLock()) return
        try {
            _scanState.value = ScanState.SCANNING
            val app = context.applicationContext
            val (scOk, driveOk) = coroutineScope {
                val sc = async(Dispatchers.IO) { scanSoundCloud(app) }
                val drive = async(Dispatchers.IO) { scanDrive(app) }
                sc.await() to drive.await()
            }
            persist(app)
            _scanState.value = if (scOk && driveOk) ScanState.DONE else ScanState.PARTIAL
        } finally {
            scanMutex.unlock()
        }
    }

    /** Returns false if the service couldn't be queried (not connected / network error). */
    private suspend fun scanSoundCloud(context: Context): Boolean {
        if (!SoundCloudAuthManager.isConnected(context)) return false
        val token = SoundCloudAuthManager.getValidAccessToken(context) ?: return false
        return try {
            val found = HashMap<String, String>()
            var url: String? = "https://api.soundcloud.com/me/tracks?limit=200&linked_partitioning=true"
            var pages = 0
            while (url != null && pages++ < 25) {
                val request = Request.Builder().url(url)
                    .header("Authorization", "OAuth $token")
                    .header("Accept", "application/json")
                    .build()
                val body = http.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    r.body?.string().orEmpty()
                }
                val (tracks, next) = parseTrackPage(body)
                for (i in 0 until tracks.length()) {
                    val t = tracks.optJSONObject(i) ?: continue
                    val title = t.optString("title").takeIf { it.isNotBlank() } ?: continue
                    val link = shareableTrackUrl(t) ?: continue
                    found.putIfAbsent(norm(title), link) // newest first -> keep the newest upload
                }
                url = next
            }
            // Replace (not merge): a track deleted on SoundCloud should stop showing as uploaded.
            _soundCloud.value = found
            true
        } catch (e: Exception) {
            Log.w(TAG, "SoundCloud scan failed", e)
            false
        }
    }

    /** `/me/tracks` answers either a bare array or, with linked_partitioning, {collection, next_href}. */
    private fun parseTrackPage(body: String): Pair<JSONArray, String?> {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("[")) return JSONArray(trimmed) to null
        val obj = JSONObject(trimmed)
        val next = obj.optString("next_href").takeIf { it.startsWith("http") }
        return (obj.optJSONArray("collection") ?: JSONArray()) to next
    }

    /** A private track's plain permalink 404s for anyone but the owner -- append its secret
     *  token so the link we show is actually shareable (same thing SoundCloud's "copy link" does). */
    private fun shareableTrackUrl(track: JSONObject): String? {
        val permalink = track.optString("permalink_url").takeIf { it.startsWith("http") } ?: return null
        val isPrivate = track.optString("sharing") == "private"
        val secret = track.optString("secret_token").takeIf { it.isNotBlank() && it != "null" }
            ?: track.optString("secret_uri").takeIf { it.startsWith("http") }
                ?.let { android.net.Uri.parse(it).getQueryParameter("secret_token") }
        return if (isPrivate && secret != null && "/s-" !in permalink) "$permalink/$secret" else permalink
    }

    private suspend fun scanDrive(context: Context): Boolean {
        val outcome = DriveAuthManager.requestAccess(context)
        if (outcome !is DriveAuthManager.Outcome.Authorized) return false
        return try {
            val drive = Drive.Builder(
                NetHttpTransport(), GsonFactory.getDefaultInstance(),
                HttpRequestInitializer { req ->
                    req.headers.authorization = "Bearer ${outcome.accessToken}"
                    req.connectTimeout = 20_000
                    req.readTimeout = 30_000
                }
            ).setApplicationName("DJM-Rec").build()

            // drive.file scope only ever sees files this app created, so "everything not a
            // folder and not trashed" is exactly our uploads, whichever install made them.
            val found = HashMap<String, String>()
            var pageToken: String? = null
            do {
                val page = drive.files().list()
                    .setQ("mimeType != '$FOLDER_MIME_TYPE' and trashed = false")
                    .setSpaces("drive")
                    .setOrderBy("createdTime desc")
                    .setFields("nextPageToken, files(id, name, webViewLink)")
                    .setPageSize(1000)
                    .setPageToken(pageToken)
                    .execute()
                page.files?.forEach { f ->
                    val name = f.name ?: return@forEach
                    found.putIfAbsent(norm(name), f.webViewLink ?: driveViewUrl(f.id))
                }
                pageToken = page.nextPageToken
            } while (pageToken != null)
            _drive.value = found
            true
        } catch (e: Exception) {
            Log.w(TAG, "Drive scan failed", e)
            false
        }
    }

    private fun driveViewUrl(fileId: String) = "https://drive.google.com/file/d/$fileId/view"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun persist(context: Context) {
        prefs(context).edit()
            .putString(KEY_SC, JSONObject(_soundCloud.value as Map<*, *>).toString())
            .putString(KEY_DRIVE, JSONObject(_drive.value as Map<*, *>).toString())
            .apply()
    }

    private fun readMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
