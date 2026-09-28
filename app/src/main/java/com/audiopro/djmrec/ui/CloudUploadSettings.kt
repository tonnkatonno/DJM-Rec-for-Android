package com.audiopro.djmrec.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.audiopro.djmrec.integrations.drive.DriveAuthManager
import com.audiopro.djmrec.integrations.drive.DriveUploadManager
import com.audiopro.djmrec.integrations.soundcloud.SoundCloudAuthManager
import com.audiopro.djmrec.integrations.soundcloud.SoundCloudUploadManager
import kotlinx.coroutines.launch

/**
 * "Cloud uploads" block of Settings: Google Drive + SoundCloud connect / auto-upload, and the
 * entry point to the SoundCloud upload defaults ([SoundCloudDefaultsScreen]).
 */
@Composable
fun CloudUploadSettings(onOpenSoundCloudDefaults: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var driveConnected by remember { mutableStateOf(DriveUploadManager.getConnectedHint(context)) }
    var driveAutoUpload by remember { mutableStateOf(DriveUploadManager.isAutoUploadEnabled(context)) }
    var driveConnecting by remember { mutableStateOf(false) }
    var driveError by remember { mutableStateOf<String?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        driveConnecting = false
        when (val outcome = DriveAuthManager.resultFromIntent(context, result.data)) {
            is DriveAuthManager.Outcome.Authorized -> {
                driveConnected = true
                driveError = null
                DriveUploadManager.setConnectedHint(context, true)
            }
            is DriveAuthManager.Outcome.Failed -> driveError = outcome.message
            is DriveAuthManager.Outcome.NeedsConsent -> driveError = "Google Drive connection was not completed"
        }
    }

    fun connectDrive() {
        driveConnecting = true
        driveError = null
        scope.launch {
            when (val outcome = DriveAuthManager.requestAccess(context)) {
                is DriveAuthManager.Outcome.Authorized -> {
                    driveConnecting = false
                    driveConnected = true
                    DriveUploadManager.setConnectedHint(context, true)
                }
                is DriveAuthManager.Outcome.NeedsConsent -> {
                    consentLauncher.launch(
                        IntentSenderRequest.Builder(outcome.pendingIntent.intentSender).build()
                    )
                }
                is DriveAuthManager.Outcome.Failed -> {
                    driveConnecting = false
                    driveError = outcome.message
                }
            }
        }
    }

    val scConnectedState by SoundCloudAuthManager.connectedFlow(context).collectAsState()
    val scConnected = scConnectedState == true
    val scNeedsReconnect by SoundCloudAuthManager.needsReconnect.collectAsState()
    var scAutoUpload by remember { mutableStateOf(SoundCloudUploadManager.isAutoUploadEnabled(context)) }
    val scConnectStatus by SoundCloudAuthManager.connectStatus.collectAsState()
    // Only the token exchange itself blocks the button -- a login tab the user just closed
    // must never leave CONNECT stuck disabled.
    val scConnecting = scConnectStatus == SoundCloudAuthManager.ConnectStatus.EXCHANGING
    val scError = if (scConnectStatus == SoundCloudAuthManager.ConnectStatus.FAILED && !scConnected) {
        "SoundCloud login was not completed -- tap CONNECT to try again"
    } else {
        null
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cloud uploads", style = MaterialTheme.typography.titleLarge)
        ServicePanel(
            title = "Google Drive",
            status = if (driveConnected) "Connected" else "Not connected",
            connected = driveConnected,
            connecting = driveConnecting,
            error = driveError,
            onConnect = ::connectDrive,
            autoUpload = driveAutoUpload,
            autoUploadDetail = "Every finished take is queued for Drive upload automatically. Requires Drive to be connected above.",
            onAutoUpload = { enabled ->
                driveAutoUpload = enabled
                DriveUploadManager.setAutoUploadEnabled(context, enabled)
            }
        )
        ServicePanel(
            title = "SoundCloud",
            status = when {
                scConnecting -> "Connecting…"
                scConnected -> "Connected"
                scNeedsReconnect -> "Login expired -- tap CONNECT"
                else -> "Not connected"
            },
            connected = scConnected,
            connecting = scConnecting,
            error = scError,
            onConnect = { SoundCloudAuthManager.launchAuthorization(context) },
            autoUpload = scAutoUpload,
            autoUploadDetail = "Uploaded as a private track (not publicly listed) -- requires SoundCloud to be connected above.",
            onAutoUpload = { enabled ->
                scAutoUpload = enabled
                SoundCloudUploadManager.setAutoUploadEnabled(context, enabled)
            },
            extra = {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSoundCloudDefaults),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Upload defaults", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Genre, tags, description applied to every upload",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}

@Composable
private fun ServicePanel(
    title: String,
    status: String,
    connected: Boolean,
    connecting: Boolean,
    error: String?,
    onConnect: () -> Unit,
    autoUpload: Boolean,
    autoUploadDetail: String,
    onAutoUpload: (Boolean) -> Unit,
    extra: @Composable () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onConnect,
                    enabled = !connecting,
                    shape = RoundedCornerShape(3.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Text(if (connected) "RECONNECT" else "CONNECT", style = MaterialTheme.typography.labelSmall)
                }
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Auto-upload new recordings", style = MaterialTheme.typography.titleSmall)
                    Text(
                        autoUploadDetail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = autoUpload, onCheckedChange = onAutoUpload)
            }
            extra()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundCloudDefaultsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var scGenre by remember { mutableStateOf(SoundCloudUploadManager.getDefaultGenre(context)) }
    var scTags by remember { mutableStateOf(SoundCloudUploadManager.getDefaultTags(context)) }
    var scDescription by remember { mutableStateOf(SoundCloudUploadManager.getDefaultDescription(context)) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("SOUNDCLOUD DEFAULTS", style = MaterialTheme.typography.labelSmall) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Applied to every upload -- manual or auto -- so nothing needs retyping on soundcloud.com afterward.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = scGenre,
                onValueChange = { scGenre = it; SoundCloudUploadManager.setDefaultGenre(context, it) },
                label = { Text("Default genre") },
                singleLine = true,
                shape = RoundedCornerShape(3.dp),
                colors = nfTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = scTags,
                onValueChange = { scTags = it; SoundCloudUploadManager.setDefaultTags(context, it) },
                label = { Text("Default tags") },
                placeholder = { Text("neurofunk, dnb, neuro") },
                singleLine = true,
                shape = RoundedCornerShape(3.dp),
                colors = nfTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = scDescription,
                onValueChange = { scDescription = it; SoundCloudUploadManager.setDefaultDescription(context, it) },
                label = { Text("Default description") },
                minLines = 4,
                maxLines = 10,
                shape = RoundedCornerShape(3.dp),
                colors = nfTextFieldColors(),
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Hairline-bordered field colors matching the brand's outline tone. */
@Composable
private fun nfTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary
)
