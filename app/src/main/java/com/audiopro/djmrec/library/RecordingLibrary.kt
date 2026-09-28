package com.audiopro.djmrec.library

import android.Manifest
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Everything about *which* recordings exist lives here, independent of this install's own
 * bookkeeping. Under scoped storage an app only "owns" the media files it created itself, so
 * after "clear data"/reinstall -- or for takes made by the other build variant (release
 * `com.audiopro.djmrec` vs. debug `.debug`), which share the same Music/DJMRec folder -- a plain
 * `File.listFiles()` returned nothing. With READ_MEDIA_AUDIO granted we list the folder *and*
 * ask MediaStore for it, and merge both, so every take in the folder shows up.
 *
 * Deleting/renaming a file this install doesn't own needs the user's one-tap consent via a
 * MediaStore request ([deleteRequest] / [writeRequest]); plain File operations still handle
 * the files we do own, with no dialog.
 */
object RecordingLibrary {
    private const val TAG = "RecordingLibrary"
    const val FOLDER_NAME = "DJMRec"
    private val AUDIO_EXTENSIONS = setOf("wav", "flac")

    val readPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasReadPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, readPermission) == PackageManager.PERMISSION_GRANTED

    fun directory(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER_NAME)

    /** Blocking (disk + MediaStore) -- call off the main thread. Newest first. */
    fun list(context: Context): List<File> {
        val dir = directory()
        dir.mkdirs()
        migrateFromAppPrivateDir(context, dir)

        val found = LinkedHashMap<String, File>()
        dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            ?.forEach { found[it.absolutePath] = it }
        if (hasReadPermission(context)) {
            queryMediaStore(context).forEach { found.putIfAbsent(it.absolutePath, it) }
        }
        return found.values.sortedByDescending { it.lastModified() }
    }

    /** Files that are on disk but not yet in MediaStore (e.g. copied in over USB, or written
     *  before a crash) are invisible to other apps and to [queryMediaStore]; nudge a scan. */
    fun rescan(context: Context) {
        val paths = directory().listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            ?.map { it.absolutePath }
            ?.toTypedArray()
            ?: return
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(context, paths, null, null)
    }

    @Suppress("DEPRECATION") // DATA is still the only way to map a MediaStore row back to a File
    private fun queryMediaStore(context: Context): List<File> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Audio.Media.DATA)
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" to
                arrayOf("${Environment.DIRECTORY_MUSIC}/$FOLDER_NAME/%")
        } else {
            "${MediaStore.Audio.Media.DATA} LIKE ?" to arrayOf("${directory().absolutePath}/%")
        }
        return try {
            context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                buildList {
                    while (c.moveToNext()) {
                        val path = c.getString(dataCol) ?: continue
                        val f = File(path)
                        if (f.extension.lowercase() in AUDIO_EXTENSIONS && f.exists()) add(f)
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed", e)
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    fun mediaUriFor(context: Context, file: File): Uri? {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return try {
            context.contentResolver.query(
                collection, arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DATA} = ?", arrayOf(file.absolutePath), null
            )?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore lookup failed for ${file.name}", e)
            null
        }
    }

    /** Consent dialog for deleting a file another install created. Null if not available
     *  (Android 10, or the file isn't indexed) -- caller falls back to an error message. */
    fun deleteRequest(context: Context, file: File): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val uri = mediaUriFor(context, file) ?: return null
        return MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
    }

    /** Consent dialog for modifying (renaming) a file another install created. */
    fun writeRequest(context: Context, file: File): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val uri = mediaUriFor(context, file) ?: return null
        return MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
    }

    /** Rename through MediaStore -- works for files we own, and for foreign files once
     *  [writeRequest] has been granted. */
    fun renameViaMediaStore(context: Context, file: File, newDisplayName: String): Boolean {
        val uri = mediaUriFor(context, file) ?: return false
        return try {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName) }
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore rename failed for ${file.name}", e)
            false
        }
    }

    /** One-time migration of takes from the old app-private location (pre public-folder builds). */
    private fun migrateFromAppPrivateDir(context: Context, dir: File) {
        val oldDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), FOLDER_NAME)
        if (!oldDir.isDirectory) return
        oldDir.listFiles()?.forEach { oldFile ->
            val newFile = File(dir, oldFile.name)
            if (!newFile.exists()) {
                try {
                    oldFile.copyTo(newFile)
                    oldFile.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "Migration failed for ${oldFile.name}", e)
                }
            }
        }
    }
}
