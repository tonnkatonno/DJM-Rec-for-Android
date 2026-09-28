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
import androidx.compose.runtime.LaunchedEffect
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

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val waveformEnabled by viewModel.waveformEnabled.collectAsState()
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
    var showSoundCloudDefaults by remember { mutableStateOf(false) }

    if (showSoundCloudDefaults) {
        SoundCloudDefaultsScreen(onBack = { showSoundCloudDefaults = false })
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "RECORDING DISPLAY",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Live waveform", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Show the RGB waveform while monitoring and recording. Turn it off to reduce battery use; input meters stay active.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = waveformEnabled,
                    onCheckedChange = viewModel::setWaveformEnabled
                )
            }
        }

        Text(
            "CLOUD",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
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
                        Text("Google Drive", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (driveConnected) "Connected" else "Not connected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = ::connectDrive,
                        enabled = !driveConnecting,
                        shape = RoundedCornerShape(3.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Text(if (driveConnected) "RECONNECT" else "CONNECT", style = MaterialTheme.typography.labelSmall)
                    }
                }
                driveError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Auto-upload new recordings", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Every finished take is queued for Drive upload automatically. Requires Drive to be connected above.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = driveAutoUpload,
                        onCheckedChange = { enabled ->
                            driveAutoUpload = enabled
                            DriveUploadManager.setAutoUploadEnabled(context, enabled)
                        }
                    )
                }
            }
        }

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
                        Text("SoundCloud", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                scConnecting -> "Connecting…"
                                scConnected -> "Connected"
                                scNeedsReconnect -> "Login expired -- tap CONNECT"
                                else -> "Not connected"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { SoundCloudAuthManager.launchAuthorization(context) },
                        enabled = !scConnecting,
                        shape = RoundedCornerShape(3.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Text(if (scConnected) "RECONNECT" else "CONNECT", style = MaterialTheme.typography.labelSmall)
                    }
                }
                scError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Auto-upload new recordings", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Uploaded as a private track (not publicly listed) -- requires SoundCloud to be connected above.",
                            style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = scAutoUpload,
                        onCheckedChange = { enabled ->
                            scAutoUpload = enabled
                            SoundCloudUploadManager.setAutoUploadEnabled(context, enabled)
                        }
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSoundCloudDefaults = true },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoundCloudDefaultsScreen(onBack: () -> Unit) {
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

/** Hairline-bordered, dim-labeled field colors matching the brand's outline tone instead of
 *  Material3's default (comparatively heavy, blue-tinted) outlined text field look. */
@Composable
private fun nfTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary
)
