package com.audiopro.djmrec.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.audiopro.djmrec.storage.LibraryRecording
import java.io.File

/**
 * Bridges the MediaStore-based recording library (storage.RecordingLibrary) to the Drive /
 * SoundCloud uploaders, which stream from a plain [File]. Files in Music/DJMRec are readable by
 * path for the install that created them, and -- with READ_MEDIA_AUDIO -- also for takes made
 * before a reinstall / "clear data".
 */
object RecordingFiles {
    private const val TAG = "RecordingFiles"
    const val FOLDER_NAME = "DJMRec"

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

    fun fileFor(context: Context, recording: LibraryRecording): File? =
        recording.legacyFile ?: fileFor(context, recording.uri, recording.displayName)

    /** Resolves a published MediaStore row to its on-disk file. MediaStore may have de-duplicated
     *  the display name ("set (1).flac"), so the DATA column wins over the requested name. */
    @Suppress("DEPRECATION") // DATA is still the only way to map a MediaStore row back to a File
    fun fileFor(context: Context, uri: Uri, displayName: String): File? {
        val fromStore = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.onFailure { Log.w(TAG, "DATA lookup failed for $uri", it) }.getOrNull()
        val file = fromStore?.let(::File) ?: File(directory(), displayName)
        return file.takeIf { it.isFile }
    }
}

/**
 * Track-change marks of a take ("MARK TRACK" while recording). Kept in app-private storage:
 * scoped storage doesn't allow writing a .txt next to the audio in Music/. The SoundCloud
 * uploader prepends it to the track description.
 */
object TracklistStore {
    private fun dir(context: Context) = File(context.filesDir, "tracklists").apply { mkdirs() }

    private fun fileFor(context: Context, audio: File) = File(dir(context), "${audio.nameWithoutExtension}.txt")

    fun write(context: Context, audio: File, timestamps: List<String>) {
        runCatching {
            fileFor(context, audio).writeText(
                timestamps.mapIndexed { i, t -> "$t  Track ${i + 1}" }.joinToString("\n")
            )
        }.onFailure { Log.w("TracklistStore", "Could not store tracklist for ${audio.name}", it) }
    }

    fun read(context: Context, audio: File): String? =
        fileFor(context, audio).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
}
