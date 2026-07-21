package com.audiopro.djmrec.integrations.soundcloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads a track to SoundCloud via the v1 REST API's multipart create endpoint. Requires a
 * valid access token from [SoundCloudAuthManager] -- callers should treat a null token as
 * "not connected" and surface that instead of attempting the upload.
 */
object SoundCloudUploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS) // large audio files -- no artificial cap on upload duration
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Returns the track's permalink URL on success. Throws on failure -- callers should catch
     *  and surface `e.message`. */
    suspend fun upload(
        context: Context,
        file: File,
        title: String,
        private: Boolean,
        genre: String = "",
        tags: String = "",
        description: String = "",
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val accessToken = SoundCloudAuthManager.getValidAccessToken(context)
            ?: throw IllegalStateException("Not connected to SoundCloud")

        val mediaType = mimeTypeFor(file).toMediaType()
        val fileBody = ProgressRequestBody(file.asRequestBody(mediaType), file.length(), onProgress)

        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("track[title]", title)
            .addFormDataPart("track[sharing]", if (private) "private" else "public")
        if (genre.isNotBlank()) multipartBuilder.addFormDataPart("track[genre]", genre.trim())
        normalizeTagList(tags)?.let { multipartBuilder.addFormDataPart("track[tag_list]", it) }
        if (description.isNotBlank()) multipartBuilder.addFormDataPart("track[description]", description.trim())
        val multipart = multipartBuilder
            .addFormDataPart("track[asset_data]", file.name, fileBody)
            .build()

        val request = Request.Builder()
            .url("https://api.soundcloud.com/tracks")
            .header("Authorization", "OAuth $accessToken")
            .post(multipart)
            .build()

        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("SoundCloud upload failed (HTTP ${response.code}): ${bodyString.take(200)}")
            }
            val json = JSONObject(bodyString)
            json.optString("permalink_url").ifEmpty { "Uploaded" }
        }
    }

    /** Comma-separated user input -> SoundCloud's space-separated `tag_list` format, quoting any
     *  tag that itself contains whitespace. Returns null if nothing to send. */
    private fun normalizeTagList(rawTags: String): String? {
        val tags = rawTags.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tags.isEmpty()) return null
        return tags.joinToString(" ") { tag -> if (" " in tag) "\"$tag\"" else tag }
    }

    private fun mimeTypeFor(file: File): String = when (file.extension.lowercase()) {
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }

    private class ProgressRequestBody(
        private val delegate: RequestBody,
        private val totalBytes: Long,
        private val onProgress: (Float) -> Unit
    ) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()

        override fun writeTo(sink: BufferedSink) {
            var written = 0L
            val countingSink = object : ForwardingSink(sink) {
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    written += byteCount
                    if (totalBytes > 0) onProgress((written.toFloat() / totalBytes).coerceIn(0f, 1f))
                }
            }
            val bufferedSink = countingSink.buffer()
            delegate.writeTo(bufferedSink)
            bufferedSink.flush()
        }
    }
}
