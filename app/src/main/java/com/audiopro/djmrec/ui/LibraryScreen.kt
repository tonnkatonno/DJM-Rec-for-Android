package com.audiopro.djmrec.ui

import android.app.Activity
import android.content.Intent
import android.media.MediaScannerConnection
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.audiopro.djmrec.integrations.drive.DriveUploadManager
import com.audiopro.djmrec.integrations.soundcloud.SoundCloudUploadManager
import com.audiopro.djmrec.library.RecordingLibrary
import com.audiopro.djmrec.library.RemoteLinkIndex
import com.audiopro.djmrec.ui.theme.AccentAmber
import com.audiopro.djmrec.ui.theme.AccentGreen
import com.audiopro.djmrec.ui.theme.BackgroundDark
import com.audiopro.djmrec.ui.theme.SurfaceDark
import com.audiopro.djmrec.ui.theme.SurfaceVariantDark
import com.audiopro.djmrec.ui.theme.TextPrimary
import com.audiopro.djmrec.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private fun probeDurationMs(file: File): Long = try {
    val mmr = MediaMetadataRetriever()
    try {
        mmr.setDataSource(file.absolutePath)
        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } finally {
        mmr.release()
    }
} catch (_: Exception) { 0L }

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val driveUploads by DriveUploadManager.uploads.collectAsState()
    val soundCloudUploads by SoundCloudUploadManager.uploads.collectAsState()
    val scLinks by RemoteLinkIndex.soundCloudLinks.collectAsState()
    val driveLinks by RemoteLinkIndex.driveLinks.collectAsState()
    val scanState by RemoteLinkIndex.scanState.collectAsState()
    var refreshKey by remember { mutableIntStateOf(0) }
    var hasReadPermission by remember { mutableStateOf(RecordingLibrary.hasReadPermission(context)) }
    var recordings by remember { mutableStateOf<List<RecordingInfo>?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasReadPermission = granted
        refreshKey++
    }

    // Re-check on every resume: the permission may have been granted from system Settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = RecordingLibrary.hasReadPermission(context)
                if (now != hasReadPermission) {
                    hasReadPermission = now
                    refreshKey++
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Folder listing + duration probing is disk I/O over possibly dozens of long takes -- keep
    // it off the main thread (it used to run inside remember{} during composition).
    LaunchedEffect(refreshKey, hasReadPermission) {
        recordings = withContext(Dispatchers.IO) {
            RecordingLibrary.rescan(context)
            RecordingLibrary.list(context).map { file ->
                RecordingInfo(
                    file = file,
                    name = file.nameWithoutExtension,
                    format = file.extension.uppercase(),
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    durationMs = probeDurationMs(file)
                )
            }
        }
    }
    // Ask SoundCloud/Drive what's already up there -- independent of this install's local flags.
    LaunchedEffect(refreshKey) {
        RemoteLinkIndex.ensureLoaded(context)
        RemoteLinkIndex.scan(context)
    }

    var deleteTarget by remember { mutableStateOf<RecordingInfo?>(null) }
    var renameTarget by remember { mutableStateOf<RecordingInfo?>(null) }
    var renameText by remember { mutableStateOf("") }
    // Rename waiting on the MediaStore "allow modify" consent (file made by another install).
    var pendingRename by remember { mutableStateOf<Pair<RecordingInfo, String>?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val rename = pendingRename
        pendingRename = null
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        if (rename != null) {
            val (rec, newDisplayName) = rename
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    RecordingLibrary.renameViaMediaStore(context, rec.file, newDisplayName)
                }
                if (!ok) Toast.makeText(context, "Could not rename recording", Toast.LENGTH_SHORT).show()
                refreshKey++
            }
        } else {
            refreshKey++ // delete consent: MediaStore already removed the file
        }
    }

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(context, "No app to open $url", Toast.LENGTH_LONG).show()
        }
    }

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
        val list = recordings
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!hasReadPermission) {
                PermissionBanner(onGrant = { permissionLauncher.launch(RecordingLibrary.readPermission) })
            }
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentGreen)
                }
                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.MusicNote, contentDescription = null,
                            modifier = Modifier.size(64.dp), tint = TextSecondary
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("No recordings in Music/${RecordingLibrary.FOLDER_NAME}", color = TextSecondary)
                        TextButton(onClick = { refreshKey++ }) { Text("Refresh") }
                    }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("${list.size} recordings", color = TextSecondary)
                                Text(
                                    formatSize(list.sumOf { it.sizeBytes }),
                                    color = AccentGreen,
                                    fontFamily = FontFamily.Monospace
                                )
                                val scanLabel = when (scanState) {
                                    RemoteLinkIndex.ScanState.SCANNING -> "CHECKING SOUNDCLOUD / DRIVE…"
                                    RemoteLinkIndex.ScanState.PARTIAL -> "CLOUD CHECK: SOME SERVICES NOT CONNECTED"
                                    RemoteLinkIndex.ScanState.DONE -> "CLOUD CHECK: UP TO DATE"
                                    RemoteLinkIndex.ScanState.IDLE -> null
                                }
                                scanLabel?.let {
                                    Text(it, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            IconButton(onClick = { refreshKey++ }) {
                                Icon(Icons.Filled.Refresh, "Refresh recordings", tint = TextSecondary)
                            }
                        }
                    }
                    items(list, key = { it.file.absolutePath }) { rec ->
                        val links = RemoteLinkIndex.linksFor(rec.file, scLinks, driveLinks)
                        val driveState = driveUploads[rec.file.absolutePath]
                            ?: if (links.driveUrl != null || DriveUploadManager.isUploaded(context, rec.file)) {
                                DriveUploadManager.UploadState(DriveUploadManager.Status.DONE, 1f)
                            } else null
                        val scState = soundCloudUploads[rec.file.absolutePath]
                            ?: if (links.soundCloudUrl != null || SoundCloudUploadManager.isUploaded(context, rec.file)) {
                                SoundCloudUploadManager.UploadState(SoundCloudUploadManager.Status.DONE, 1f)
                            } else null
                        val scUrl = (soundCloudUploads[rec.file.absolutePath]?.trackUrl ?: links.soundCloudUrl)
                            ?.takeIf { it.startsWith("http") }
                        RecordingCard(
                            info = rec,
                            driveState = driveState,
                            driveUrl = links.driveUrl,
                            onUploadToDrive = {
                                val url = links.driveUrl
                                if (url != null) openUrl(url) else DriveUploadManager.enqueueUpload(context, rec.file)
                            },
                            soundCloudState = scState,
                            soundCloudUrl = scUrl,
                            onUploadToSoundCloud = {
                                if (scUrl != null) openUrl(scUrl)
                                else SoundCloudUploadManager.enqueueUpload(context, rec.file)
                            },
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
    }

    // Delete confirmation dialog
    deleteTarget?.let { rec ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete recording?") },
            text = { Text("${rec.name}.${rec.format.lowercase()} will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    if (rec.file.delete()) {
                        MediaScannerConnection.scanFile(context, arrayOf(rec.file.absolutePath), null, null)
                        refreshKey++
                    } else {
                        // Made by another install/variant -> Android asks the user once.
                        val request = RecordingLibrary.deleteRequest(context, rec.file)
                        if (request != null) {
                            consentLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                        } else {
                            Toast.makeText(context, "Could not delete recording", Toast.LENGTH_SHORT).show()
                        }
                    }
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
                        val newDisplayName = "$newName.${rec.format.lowercase()}"
                        val newFile = File(rec.file.parent, newDisplayName)
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
                                // Made by another install/variant -> ask for modify consent first.
                                val request = RecordingLibrary.writeRequest(context, rec.file)
                                if (request != null) {
                                    pendingRename = rec to newDisplayName
                                    consentLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                                } else {
                                    Toast.makeText(context, "Could not rename recording", Toast.LENGTH_SHORT).show()
                                }
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

@Composable
private fun PermissionBanner(onGrant: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(3.dp),
        color = SurfaceVariantDark,
        border = BorderStroke(1.dp, AccentAmber)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "ONLY SHOWING TAKES FROM THIS INSTALL. ALLOW AUDIO ACCESS TO LIST EVERYTHING IN MUSIC/${RecordingLibrary.FOLDER_NAME}.",
                color = AccentAmber,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onGrant) { Text("ALLOW", color = AccentAmber) }
        }
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
    driveUrl: String?,
    onUploadToDrive: () -> Unit,
    soundCloudState: SoundCloudUploadManager.UploadState?,
    soundCloudUrl: String?,
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
                    hasLink = driveUrl != null,
                    accent = com.audiopro.djmrec.ui.theme.AccentCyan,
                    onClick = onUploadToDrive
                )
                CloudUploadChip(
                    label = "SOUNDCLOUD",
                    status = soundCloudState?.status?.toChipStatus() ?: ChipStatus.IDLE,
                    progress = soundCloudState?.progress ?: 0f,
                    error = soundCloudState?.error,
                    hasLink = soundCloudUrl != null,
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

/** Compact "DRIVE" / "SOUNDCLOUD" affordance -- label + status icon/spinner. Tapping uploads
 *  (or retries); once the take is known to be up there ([hasLink]) tapping opens it instead,
 *  so an already-uploaded take can't be uploaded twice by accident. */
@Composable
private fun CloudUploadChip(
    label: String,
    status: ChipStatus,
    progress: Float,
    error: String?,
    hasLink: Boolean,
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
        if (status == ChipStatus.DONE && hasLink) {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew, "Open link", tint = accent,
                modifier = Modifier.size(12.dp)
            )
        }
        if (status == ChipStatus.FAILED && error != null) {
            Text(
                error, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}
