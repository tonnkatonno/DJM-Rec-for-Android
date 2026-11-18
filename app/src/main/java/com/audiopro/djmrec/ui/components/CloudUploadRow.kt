package com.audiopro.djmrec.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.integrations.drive.DriveUploadManager
import com.audiopro.djmrec.integrations.soundcloud.SoundCloudUploadManager
import com.audiopro.djmrec.library.RemoteLinkIndex
import com.audiopro.djmrec.ui.theme.AccentCyan
import com.audiopro.djmrec.ui.theme.AccentOrange
import com.audiopro.djmrec.ui.theme.TextSecondary
import java.io.File

/**
 * DRIVE / SOUNDCLOUD status for one recording. "Uploaded" comes from three sources, strongest
 * first: a live upload job, the link index built by asking the services themselves (survives a
 * reinstall / "clear data"), and this install's own uploaded flag. Tapping uploads (or retries);
 * once a link is known, tapping opens it instead so a take can't be uploaded twice by accident.
 */
@Composable
fun CloudUploadRow(file: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val driveUploads by DriveUploadManager.uploads.collectAsState()
    val soundCloudUploads by SoundCloudUploadManager.uploads.collectAsState()
    val scLinks by RemoteLinkIndex.soundCloudLinks.collectAsState()
    val driveLinks by RemoteLinkIndex.driveLinks.collectAsState()

    val links = RemoteLinkIndex.linksFor(file, scLinks, driveLinks)
    val driveJob = driveUploads[file.absolutePath]
    val driveStatus = driveJob?.status?.toChipStatus()
        ?: if (links.driveUrl != null || DriveUploadManager.isUploaded(context, file)) ChipStatus.DONE else ChipStatus.IDLE
    val scJob = soundCloudUploads[file.absolutePath]
    val scStatus = scJob?.status?.toChipStatus()
        ?: if (links.soundCloudUrl != null || SoundCloudUploadManager.isUploaded(context, file)) ChipStatus.DONE else ChipStatus.IDLE
    val scUrl = (scJob?.trackUrl ?: links.soundCloudUrl)?.takeIf { it.startsWith("http") }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(context, "No app to open $url", Toast.LENGTH_LONG).show() }
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CloudUploadChip(
            label = "DRIVE",
            status = driveStatus,
            progress = driveJob?.progress ?: 0f,
            error = driveJob?.error,
            hasLink = links.driveUrl != null,
            accent = AccentCyan,
            onClick = { links.driveUrl?.let(::open) ?: DriveUploadManager.enqueueUpload(context, file) }
        )
        CloudUploadChip(
            label = "SOUNDCLOUD",
            status = scStatus,
            progress = scJob?.progress ?: 0f,
            error = scJob?.error,
            hasLink = scUrl != null,
            accent = AccentOrange,
            onClick = { scUrl?.let(::open) ?: SoundCloudUploadManager.enqueueUpload(context, file) }
        )
    }
}

private enum class ChipStatus { IDLE, UPLOADING, DONE, FAILED }

private fun DriveUploadManager.Status.toChipStatus(): ChipStatus = when (this) {
    DriveUploadManager.Status.QUEUED, DriveUploadManager.Status.UPLOADING -> ChipStatus.UPLOADING
    DriveUploadManager.Status.DONE -> ChipStatus.DONE
    DriveUploadManager.Status.FAILED -> ChipStatus.FAILED
}

private fun SoundCloudUploadManager.Status.toChipStatus(): ChipStatus = when (this) {
    SoundCloudUploadManager.Status.QUEUED, SoundCloudUploadManager.Status.PREPARING,
    SoundCloudUploadManager.Status.UPLOADING -> ChipStatus.UPLOADING
    SoundCloudUploadManager.Status.DONE -> ChipStatus.DONE
    SoundCloudUploadManager.Status.FAILED -> ChipStatus.FAILED
}

@Composable
private fun CloudUploadChip(
    label: String,
    status: ChipStatus,
    progress: Float,
    error: String?,
    hasLink: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.clickable(enabled = status != ChipStatus.UPLOADING, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (status) {
            ChipStatus.UPLOADING -> Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress }, modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp, color = accent
                )
            }
            ChipStatus.DONE -> Icon(Icons.Filled.CloudDone, "Uploaded", tint = accent, modifier = Modifier.size(14.dp))
            ChipStatus.FAILED -> Icon(
                Icons.Filled.ErrorOutline, "Failed -- tap to retry",
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp)
            )
            ChipStatus.IDLE -> Icon(Icons.Filled.CloudUpload, "Upload", tint = TextSecondary, modifier = Modifier.size(14.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = when (status) {
                ChipStatus.FAILED -> MaterialTheme.colorScheme.error
                ChipStatus.IDLE -> TextSecondary
                else -> accent
            }
        )
        if (status == ChipStatus.DONE && hasLink) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open link", tint = accent, modifier = Modifier.size(12.dp))
        }
        if (status == ChipStatus.FAILED && error != null) {
            Text(
                error, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}
