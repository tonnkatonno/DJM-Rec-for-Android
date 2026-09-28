package com.audiopro.djmrec

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.audiopro.djmrec.audio.RecordingState
import com.audiopro.djmrec.integrations.soundcloud.SoundCloudAuthManager
import com.audiopro.djmrec.library.RecordingLibrary
import com.audiopro.djmrec.ui.LockedRecorderScreen
import com.audiopro.djmrec.ui.MainScreen
import com.audiopro.djmrec.ui.MainViewModel
import com.audiopro.djmrec.ui.theme.DjmRecTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Permission results are read reactively via UsbAudioManager/AudioEngine calls that
           will simply fail with a clear log line if RECORD_AUDIO / POST_NOTIFICATIONS were
           denied — no extra state needed here. */ }

    /** True while this window is being shown on top of the keyguard (see [updateShowWhenLocked]). */
    private val keyguardLocked = mutableStateOf(false)

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshKeyguardState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        forwardSoundCloudRedirect(intent)

        // While a take is running, let this activity show over the lock screen: locking the
        // phone mid-set and waking it shows the live recorder (LockedRecorderScreen) instead of
        // a blank keyguard. Switched off again as soon as recording ends, so the app is never
        // reachable past the lock screen otherwise.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.recordingState.collect { updateShowWhenLocked(it.isTakeActive()) }
            }
        }

        setContent {
            DjmRecTheme {
                val state by viewModel.recordingState.collectAsState()
                if (keyguardLocked.value && state.isTakeActive()) {
                    LockedRecorderScreen(viewModel = viewModel, onUnlock = ::requestUnlock)
                } else {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshKeyguardState()
    }

    override fun onResume() {
        super.onResume()
        refreshKeyguardState()
    }

    override fun onStop() {
        unregisterReceiver(screenStateReceiver)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        refreshKeyguardState()
    }

    private fun refreshKeyguardState() {
        keyguardLocked.value = getSystemService<KeyguardManager>()?.isKeyguardLocked == true
    }

    private fun updateShowWhenLocked(show: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(show)
        }
        refreshKeyguardState()
    }

    private fun requestUnlock() {
        val km = getSystemService<KeyguardManager>() ?: return
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = refreshKeyguardState()
        })
    }

    private fun RecordingState.isTakeActive() =
        this is RecordingState.Recording || this is RecordingState.Paused

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forwardSoundCloudRedirect(intent)
    }

    /** The Custom Tabs SoundCloud login returns here via the `djmrec://soundcloud-callback`
     *  intent-filter (launchMode singleTop keeps this in onNewIntent, not a fresh onCreate). */
    private fun forwardSoundCloudRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "djmrec" && data.host == "soundcloud-callback") {
            SoundCloudAuthManager.onRedirectReceived(this, data)
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        permissions += RecordingLibrary.readPermission

        requestPermissions.launch(permissions.toTypedArray())
    }
}
