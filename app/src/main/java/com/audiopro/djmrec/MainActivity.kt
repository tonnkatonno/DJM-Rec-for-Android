package com.audiopro.djmrec

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
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
import com.audiopro.djmrec.library.RecordingFiles
import com.audiopro.djmrec.ui.LockedRecorderScreen
import com.audiopro.djmrec.ui.MainScreen
import com.audiopro.djmrec.ui.MainViewModel
import com.audiopro.djmrec.ui.OnboardingScreen
import com.audiopro.djmrec.ui.theme.DjmRecTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.ensureLiveMonitoring() }

    /** True while this window is being shown on top of the keyguard (see [updateShowWhenLocked]). */
    private val keyguardLocked = mutableStateOf(false)

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshKeyguardState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val events = (application as DjmRecApplication).sessionEvents
        events.closeRequested.value = false
        lifecycleScope.launch { events.closeRequested.collect { if (it) finishAndRemoveTask() } }
        handleDeepLink(intent)
        // First launch: the onboarding stepper collects every permission itself. Later launches
        // keep the legacy silent re-request in case a required grant was revoked meanwhile.
        if (viewModel.onboardingComplete.value) requestRuntimePermissions()

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
                // A recording or livestream must never die because the device dozed off or
                // auto-locked: while recording, paused or live, keep the device awake on EVERY
                // screen (not just Record); an idle device can stall the USB audio stream.
                // Monitoring only stays awake when the user opted in, and the power-save overlay
                // always needs the screen to remain on to be meaningful. FLAG_KEEP_SCREEN_ON is
                // the supported replacement for the deprecated FULL_WAKE_LOCK and blocks both
                // screen sleep and keyguard auto-lock while this window is visible. If the user
                // locks manually, capture continues in the foreground service (wake lock held).
                val recordingState by viewModel.recordingState.collectAsState()
                val keepScreenOnSetting by viewModel.keepScreenOn.collectAsState()
                val liveState by viewModel.liveStreamState.collectAsState()
                val powerSaveActive = viewModel.powerSaveActive.value
                val keepAwake = recordingState is RecordingState.Recording ||
                    recordingState is RecordingState.Paused ||
                    liveState.isActive ||
                    powerSaveActive ||
                    (keepScreenOnSetting && recordingState is RecordingState.Monitoring)
                DisposableEffect(keepAwake) {
                    if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                val onboardingComplete by viewModel.onboardingComplete.collectAsState()
                when {
                    keyguardLocked.value && recordingState.isTakeActive() ->
                        LockedRecorderScreen(viewModel = viewModel, onUnlock = ::requestUnlock)
                    onboardingComplete -> MainScreen(viewModel = viewModel)
                    else -> OnboardingScreen(onFinished = {
                        viewModel.completeOnboarding()
                        viewModel.rescanUsbDevices()
                    })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setUiVisible(true)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshKeyguardState()
    }

    override fun onStop() {
        unregisterReceiver(screenStateReceiver)
        viewModel.setUiVisible(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshKeyguardState()
        // USB attach can precede Activity creation/resume. Reconcile the framework device list
        // here so monitoring does not depend on opening the Mixer USB picker first.
        viewModel.rescanUsbDevices()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        refreshKeyguardState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
        viewModel.rescanUsbDevices()
    }

    /** djmrec://<route> navigates; djmrec://soundcloud-callback completes the SoundCloud login. */
    private fun handleDeepLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != "djmrec") return
        if (data.host == "soundcloud-callback") {
            SoundCloudAuthManager.onRedirectReceived(this, data)
            viewModel.pendingRoute.value = "settings"
        } else {
            data.host?.let { viewModel.pendingRoute.value = it }
        }
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

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        // Lists takes made before a reinstall / "clear data" in the Recordings tab.
        permissions += RecordingFiles.readPermission
        requestPermissions.launch(permissions.toTypedArray())
    }
}
