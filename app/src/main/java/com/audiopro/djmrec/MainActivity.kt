package com.audiopro.djmrec

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.audiopro.djmrec.integrations.soundcloud.SoundCloudAuthManager
import com.audiopro.djmrec.ui.MainScreen
import com.audiopro.djmrec.ui.MainViewModel
import com.audiopro.djmrec.ui.theme.DjmRecTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Permission results are read reactively via UsbAudioManager/AudioEngine calls that
           will simply fail with a clear log line if RECORD_AUDIO / POST_NOTIFICATIONS were
           denied — no extra state needed here. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        forwardSoundCloudRedirect(intent)

        setContent {
            DjmRecTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forwardSoundCloudRedirect(intent)
    }

    /** The Custom Tabs SoundCloud login returns here via the `djmrec://soundcloud-callback`
     *  intent-filter (launchMode singleTop keeps this in onNewIntent, not a fresh onCreate). */
    private fun forwardSoundCloudRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "djmrec" && data.host == "soundcloud-callback") {
            SoundCloudAuthManager.onRedirectReceived(data)
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissions.launch(permissions.toTypedArray())
    }
}
