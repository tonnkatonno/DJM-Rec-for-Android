package com.audiopro.djmrec.ui

import android.content.Intent
import android.media.MediaScannerConnection
import android.media.MediaMetadataRetriever
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.audiopro.djmrec.integrations.drive.DriveUploadManager
import com.audiopro.djmrec.integrations.soundcloud.SoundCloudUploadManager
import com.audiopro.djmrec.ui.theme.AccentGreen
import com.audiopro.djmrec.ui.theme.BackgroundDark
import com.audiopro.djmrec.ui.theme.SurfaceDark
import com.audiopro.djmrec.ui.theme.SurfaceVariantDark
import com.audiopro.djmrec.ui.theme.TextPrimary
import com.audiopro.djmrec.ui.theme.TextSecondary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Data
// ---------------------------------------------------------------------------

private data class RecordingInfo(
    val file: File,
    val name: String,
    val format: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val durationMs: Long
)

private val dateFormat = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US)

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb)
    else String.format(Locale.US, "%d KB", bytes / 1024)
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val driveUploads by DriveUploadManager.uploads.collectAsState()
    val soundCloudUploads by SoundCloudUploadManager.uploads.collectAsState()
    val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "DJMRec")
    var refreshKey by remember { mutableIntStateOf(0) }
    val recordings = remember(dir, refreshKey) {
        dir.mkdirs()
        // Migrate any recordings from the old app-private location to the new public folder.
        val oldDir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "DJMRec")
        if (oldDir.isDirectory) {
            oldDir.listFiles()?.forEach { oldFile ->
                val newFile = File(dir, oldFile.name)
                if (!newFile.exists()) {
                    oldFile.copyTo(newFile)
                    oldFile.delete()
                }
            }
        }
        dir.listFiles()
            ?.filter { it.extension.lowercase() in listOf("wav", "flac") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                val dur = try {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(file.absolutePath)
                    val durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    mmr.release()
                    durStr?.toLongOrNull() ?: 0L
                } catch (_: Exception) { 0L }

                RecordingInfo(
                    file = file,
                    name = file.nameWithoutExtension,
                    format = file.extension.uppercase(),
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    durationMs = dur
                )
            }
            ?: emptyList()
    }

    var deleteTarget by remember { mutableStateOf<RecordingInfo?>(null) }
    var renameTarget by remember { mutableStateOf<RecordingInfo?>(null) }
    var renameText by remember { mutableStateOf("") }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = { Text("Recordings", color = TextPrimary) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                        }
                    },
                    actions = {
                        IconButton(onClick = { refreshKey++ }) {
                            Icon(Icons.Filled.Refresh, "Refresh recordings", tint = TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
                )
            }
        }
    ) { padding ->
        if (recordings.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.MusicNote, contentDescription = null,
                        modifier = Modifier.size(64.dp), tint = TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No recordings yet", color = TextSecondary)
                    TextButton(onClick = { refreshKey++ }) { Text("Refresh") }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("${recordings.size} recordings", color = TextSecondary)
                            Text(
                                formatSize(recordings.sumOf { it.sizeBytes }),
                                color = AccentGreen,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        IconButton(onClick = { refreshKey++ }) {
                            Icon(Icons.Filled.Refresh, "Refresh recordings", tint = TextSecondary)
                        }
                    }
                }
                items(recordings, key = { it.file.absolutePath }) { rec ->
                    val liveState = driveUploads[rec.file.absolutePath]
                    val driveState = liveState
                        ?: if (DriveUploadManager.isUploaded(context, rec.file)) {
                            DriveUploadManager.UploadState(DriveUploadManager.Status.DONE, 1f)
                        } else null
                    val liveScState = soundCloudUploads[rec.file.absolutePath]
                    val scState = liveScState
                        ?: if (SoundCloudUploadManager.isUploaded(context, rec.file)) {
                            SoundCloudUploadManager.UploadState(SoundCloudUploadManager.Status.DONE, 1f)
                        } else null
                    RecordingCard(
                        info = rec,
                        driveState = driveState,
                        onUploadToDrive = { DriveUploadManager.enqueueUpload(context, rec.file) },
                        soundCloudState = scState,
                        onUploadToSoundCloud = { SoundCloudUploadManager.enqueueUpload(context, rec.file) },
                        onPlay = {
                            try {
                                val uri = FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", rec.file)
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, mimeType(rec.format))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(intent)
                            } catch (error: Exception) {
                                Toast.makeText(context, "Could not open recording: ${error.message}", Toast.LENGTH_LONG).show()
                            }
                        },
                        onShare = {
                            try {
                                val uri = FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", rec.file)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = mimeType(rec.format)
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share recording"))
                            } catch (error: Exception) {
                                Toast.makeText(context, "Could not share recording: ${error.message}", Toast.LENGTH_LONG).show()
                            }
                        },
                        onDelete = { deleteTarget = rec },
                        onRename = {
                            renameTarget = rec
                            renameText = rec.name
                        }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) } // FAB clearance
            }
        }
    }

    // Delete confirmation dialog
    deleteTarget?.let { rec ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete recording?") },
            text = { Text("${rec.name}.${rec.format.lowercase()} will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    if (!rec.file.delete()) {
                        Toast.makeText(context, "Could not delete recording", Toast.LENGTH_SHORT).show()
                    }
                    MediaScannerConnection.scanFile(context, arrayOf(rec.file.absolutePath), null, null)
                    refreshKey++
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Rename dialog
    renameTarget?.let { rec ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text("Name") }
                    )
                    Text(
                        ".${rec.format.lowercase()}",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameText.trim()
                    if (newName.isNotEmpty() && '/' !in newName && '\\' !in newName && newName != rec.name) {
                        val newFile = File(rec.file.parent, "$newName.${rec.format.lowercase()}")
                        if (!newFile.exists()) {
                            if (rec.file.renameTo(newFile)) {
                                MediaScannerConnection.scanFile(
                                    context,
                                    arrayOf(rec.file.absolutePath, newFile.absolutePath),
                                    null,
                                    null
                                )
                                refreshKey++
                            } else {
                                Toast.makeText(context, "Could not rename recording", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "A file with that name already exists", Toast.LENGTH_SHORT).show()
                        }
                    } else if ('/' in newName || '\\' in newName) {
                        Toast.makeText(context, "Name cannot contain / or \\", Toast.LENGTH_SHORT).show()
                    }
                    renameTarget = null
                }) { Text("Save", color = AccentGreen) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }
}

private fun mimeType(format: String): String = when (format.lowercase()) {
    "wav" -> "audio/wav"
    "flac" -> "audio/flac"
    else -> "audio/*"
}

// ---------------------------------------------------------------------------
// Card
// ---------------------------------------------------------------------------

@Composable
private fun RecordingCard(
    info: RecordingInfo,
    driveState: DriveUploadManager.UploadState?,
    onUploadToDrive: () -> Unit,
    soundCloudState: SoundCloudUploadManager.UploadState?,
    onUploadToSoundCloud: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onPlay)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.MusicNote, contentDescription = null,
                    tint = AccentGreen, modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = info.name,
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(info.format, color = AccentGreen, style = MaterialTheme.typography.labelSmall)
                        Text(formatDuration(info.durationMs), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                        Text(formatSize(info.sizeBytes), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        dateFormat.format(Date(info.lastModified)),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Edit, "Rename", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Share, "Share", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Delete, "Delete", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CloudUploadChip(
                    label = "DRIVE",
                    status = driveState?.status?.toChipStatus() ?: ChipStatus.IDLE,
                    progress = driveState?.progress ?: 0f,
                    error = driveState?.error,
                    accent = com.audiopro.djmrec.ui.theme.AccentCyan,
                    onClick = onUploadToDrive
                )
                CloudUploadChip(
                    label = "SOUNDCLOUD",
                    status = soundCloudState?.status?.toChipStatus() ?: ChipStatus.IDLE,
                    progress = soundCloudState?.progress ?: 0f,
                    error = soundCloudState?.error,
                    accent = com.audiopro.djmrec.ui.theme.AccentOrange,
                    onClick = onUploadToSoundCloud
                )
            }
        }
    }
}

private enum class ChipStatus { IDLE, UPLOADING, DONE, FAILED }
private fun DriveUploadManager.Status.toChipStatus(): ChipStatus = when (this) {
    DriveUploadManager.Status.QUEUED, DriveUploadManager.Status.UPLOADING -> ChipStatus.UPLOADING
    DriveUploadManager.Status.DONE -> ChipStatus.DONE
    DriveUploadManager.Status.FAILED -> ChipStatus.FAILED
}
private fun SoundCloudUploadManager.Status.toChipStatus(): ChipStatus = when (this) {
    SoundCloudUploadManager.Status.QUEUED, SoundCloudUploadManager.Status.UPLOADING -> ChipStatus.UPLOADING
    SoundCloudUploadManager.Status.DONE -> ChipStatus.DONE
    SoundCloudUploadManager.Status.FAILED -> ChipStatus.FAILED
}

/** Compact "DRIVE" / "SOUNDCLOUD" upload affordance -- label + status icon/spinner, tappable
 *  to start or retry. Shared shape for both integrations so the row reads as one system. */
@Composable
private fun CloudUploadChip(
    label: String,
    status: ChipStatus,
    progress: Float,
    error: String?,
    accent: androidx.compose.ui.graphics.Color,
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
            ChipStatus.DONE -> Icon(
                Icons.Filled.CloudDone, "Uploaded", tint = accent, modifier = Modifier.size(14.dp)
            )
            ChipStatus.FAILED -> Icon(
                Icons.Filled.ErrorOutline, "Failed -- tap to retry",
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp)
            )
            ChipStatus.IDLE -> Icon(
                Icons.Filled.CloudUpload, "Upload", tint = TextSecondary, modifier = Modifier.size(14.dp)
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (status == ChipStatus.FAILED) MaterialTheme.colorScheme.error
            else if (status == ChipStatus.IDLE) TextSecondary else accent
        )
        if (status == ChipStatus.FAILED && error != null) {
            Text(
                error, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}
