package com.audiopro.djmrec.integrations.drive

import android.content.Context
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener
import com.google.api.client.http.FileContent
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Talks to the Drive v3 REST API directly -- deliberately not via `GoogleAccountCredential`,
 * which belongs to the deprecated GoogleSignIn bridge; [DriveAuthManager] already hands us a
 * plain bearer access token instead. Every recording is uploaded into one app-owned folder in
 * the user's My Drive, created on first use and cached by ID afterward.
 */
object DriveUploader {
    private const val APP_FOLDER_NAME = "DJM-Rec Recordings"
    private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

    private fun buildService(accessToken: String): Drive {
        val requestInitializer = HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $accessToken"
            request.connectTimeout = 30_000
            request.readTimeout = 30_000
        }
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), requestInitializer)
            .setApplicationName("DJM-Rec")
            .build()
    }

    private suspend fun resolveFolderId(context: Context, drive: Drive): String =
        withContext(Dispatchers.IO) {
            DriveUploadManager.getCachedFolderId(context)?.let { return@withContext it }

            val existing = drive.files().list()
                .setQ("mimeType='$FOLDER_MIME_TYPE' and name='$APP_FOLDER_NAME' and trashed=false")
                .setSpaces("drive")
                .setFields("files(id)")
                .execute()
            val folderId = existing.files?.firstOrNull()?.id
                ?: drive.files().create(
                    DriveFile().apply {
                        name = APP_FOLDER_NAME
                        mimeType = FOLDER_MIME_TYPE
                    }
                ).setFields("id").execute().id

            DriveUploadManager.setCachedFolderId(context, folderId)
            folderId
        }

    /** Uploads [file] into the app folder. Reports 0f..1f progress via [onProgress] (called from
     *  a background thread -- callers must hop to the main thread themselves if needed). Throws
     *  on failure; callers should catch and surface `e.message`. */
    suspend fun upload(
        context: Context,
        accessToken: String,
        file: File,
        mimeType: String,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val drive = buildService(accessToken)
        val folderId = resolveFolderId(context, drive)

        val metadata = DriveFile().apply {
            name = file.name
            parents = listOf(folderId)
        }
        val mediaContent = FileContent(mimeType, file)
        val request = drive.files().create(metadata, mediaContent).setFields("id")
        request.mediaHttpUploader.setDirectUploadEnabled(false)
        request.mediaHttpUploader.progressListener = MediaHttpUploaderProgressListener { uploader ->
            onProgress(uploader.progress.toFloat())
        }
        request.execute().id
    }
}
