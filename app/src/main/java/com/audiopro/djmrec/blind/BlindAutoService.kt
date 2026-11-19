package com.audiopro.djmrec.blind

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.service.media.MediaBrowserService
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.audiopro.djmrec.MainActivity
import com.audiopro.djmrec.R
import com.audiopro.djmrec.storage.LibraryRecording
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Android Auto: plays a recording that has a blind list (the searcher's "vak próba") and grades its moments from the car.
 *
 * Between moments the bar has ⏮ / ⏭: the moment before, or the next ungraded one, starting 4 s before it. Inside a moment
 * -- from that lead-in until 6 s after it ends -- the four grade buttons take their place (✗ ~ ✓ ★; the grade in effect
 * is drawn ringed, the same again clears it): Android Auto has room for about four buttons around play / pause, and the
 * grades must not land in an overflow menu. The steering wheel's next / previous always navigate. Grades given in the
 * SoundCloud extension come with the list ([BlindList.seed]); the car's own go to [BlindGradeStore] and into the
 * diagnostic report. Framework media classes only, so no new dependency.
 *
 * Not a foreground service: Android Auto keeps it bound (and so alive) while connected, and the player holds a wake lock.
 */
class BlindAutoService : MediaBrowserService() {
    private lateinit var session: MediaSession
    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private var player: MediaPlayer? = null
    private var prepared = false
    private var playWhenReady = false
    private var pendingSeekMs: Long? = null
    private var recording: LibraryRecording? = null
    private var blind: BlindSession? = null
    private var shownMoment: Int? = null          // the moment whose grade buttons are on screen; null = ⏮ / ⏭
    private var errorMessage: String? = null
    private var lastMetadataKey: String? = null
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var noisyRegistered = false
    private var destroyed = false                 // a lookup finishing after Android Auto let go must not start a player

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, TAG).apply {
            setCallback(callback, main)
            setSessionActivity(
                PendingIntent.getActivity(
                    this@BlindAutoService, 0, Intent(this@BlindAutoService, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }
        sessionToken = session.sessionToken
        publishState()
    }

    override fun onDestroy() {
        destroyed = true
        savePosition()
        releasePlayer()
        abandonFocus()
        unregisterNoisy()
        session.release()
        io.shutdown()
        super.onDestroy()
    }

    // --- browsing -------------------------------------------------------------------------------------

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): MediaBrowserService.BrowserRoot {
        val trusted = clientUid == Process.myUid() || clientUid == Process.SYSTEM_UID || clientPackageName in TRUSTED_CALLERS
        Log.i(TAG, "browser connected: $clientPackageName (trusted=$trusted)")
        return MediaBrowserService.BrowserRoot(if (trusted) ROOT_ID else EMPTY_ROOT_ID, null)
    }

    override fun onLoadChildren(parentId: String, result: MediaBrowserService.Result<MutableList<MediaBrowser.MediaItem>>) {
        if (parentId == EMPTY_ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }
        result.detach()
        io.execute {
            val items = runCatching { children(parentId) }
                .onFailure { Log.e(TAG, "browsing $parentId failed", it) }
                .getOrDefault(emptyList())
            main.post { if (!destroyed) result.sendResult(items.toMutableList()) }
        }
    }

    /** Runs on [io]: it looks the recordings up in MediaStore. */
    private fun children(parentId: String): List<MediaBrowser.MediaItem> {
        if (parentId == ROOT_ID) {
            return BlindLists.all.flatMap { list ->
                val graded = BlindGradeStore.load(this, list)
                val found = BlindRecordings.find(this, list) != null
                listOf(
                    item(
                        listMediaId(list), list.title,
                        if (found) "${graded.done}/${graded.total} értékelve · az első értékeletlennél indul"
                        else "Nincs a telefonon: Music/DJMRec/${list.match}",
                        playable = true,
                    ),
                    item(listMediaId(list) + MOMENTS_SUFFIX, "Pillanatok (${list.moments.size})", "Ugrás egy adott pillanatra", playable = false),
                )
            }
        }
        val list = listFor(parentId.removeSuffix(MOMENTS_SUFFIX))?.takeIf { parentId.endsWith(MOMENTS_SUFFIX) } ?: return emptyList()
        val graded = BlindGradeStore.load(this, list)
        return list.moments.sortedBy { it.start }.map { m ->
            val grade = graded.gradeOf(m.n)
            item(
                "${listMediaId(list)}/${m.n}", "#${m.n}  ${m.from}–${m.to}",
                (grade?.let { "${it.symbol} ${it.label}" } ?: "még nincs értékelve") + " · " + m.pair,
                playable = true,
            )
        }
    }

    private fun item(id: String, title: String, subtitle: String, playable: Boolean) = MediaBrowser.MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle).build(),
        if (playable) MediaBrowser.MediaItem.FLAG_PLAYABLE else MediaBrowser.MediaItem.FLAG_BROWSABLE,
    )

    // --- transport ----------------------------------------------------------------------------------------

    private val callback = object : MediaSession.Callback() {
        override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) = playFromMediaId(mediaId)
        override fun onPlayFromSearch(query: String?, extras: Bundle?) = resume()
        override fun onPlay() = resume()
        override fun onPause() = pause()
        override fun onStop() = stopPlayback()
        override fun onSkipToNext() = skip(next = true)
        override fun onSkipToPrevious() = skip(next = false)
        override fun onSeekTo(pos: Long) = seekTo(pos)

        override fun onCustomAction(action: String, extras: Bundle?) {
            BlindGrade.of(action.removePrefix(ACTION_PREFIX))?.let(::grade)
        }

        // The steering wheel's next / previous navigate even while the grade buttons stand in for ⏮ / ⏭ on screen.
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val key = keyEventOf(mediaButtonIntent)
            if (key != null && key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                when (key.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_NEXT -> { skip(next = true); return true }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { skip(next = false); return true }
                }
            }
            return super.onMediaButtonEvent(mediaButtonIntent)
        }
    }

    private fun playFromMediaId(mediaId: String) {
        listFor(mediaId)?.let { return start(it, moment = null, atMs = null) }
        val list = listFor(mediaId.substringBeforeLast('/')) ?: return
        start(list, moment = mediaId.substringAfterLast('/').toIntOrNull(), atMs = null)
    }

    /** Play: continue the current player, else where the last session stopped, else the first list from its start. */
    private fun resume() {
        if (player != null) return play()
        val list = BlindLists.all.firstOrNull { it.id == prefs.getString(KEY_LIST, null) } ?: BlindLists.all.firstOrNull() ?: return
        start(list, moment = null, atMs = prefs.getLong(KEY_POSITION + list.id, -1L).takeIf { it >= 0 })
    }

    /** Opens [list]'s recording at moment [moment], or at [atMs], or at the first ungraded moment. */
    private fun start(list: BlindList, moment: Int?, atMs: Long?) {
        errorMessage = null
        io.execute {
            val found = runCatching { BlindRecordings.find(this, list) }
                .onFailure { Log.e(TAG, "looking up ${list.match} failed", it) }
                .getOrNull()
            val graded = BlindGradeStore.load(this, list)
            main.post {
                if (destroyed) return@post
                if (found == null) {
                    fail("Nincs meg a felvétel a telefonon: Music/DJMRec/${list.match} (ha megvan, nyisd meg egyszer a DJM-Rec-et).")
                    return@post
                }
                blind = graded
                prefs.edit { putString(KEY_LIST, list.id) }
                val target = moment?.let(graded::moment) ?: if (atMs == null) graded.firstTarget() else null
                open(found, target?.let { (it.leadStart * 1000).toLong() } ?: atMs ?: 0L)
            }
        }
    }

    private fun open(rec: LibraryRecording, ms: Long) {
        playWhenReady = true
        if (player != null && recording?.uri == rec.uri) {
            seekTo(ms)
            play()
            return
        }
        releasePlayer()
        recording = rec
        pendingSeekMs = ms
        lastMetadataKey = null
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(ATTRIBUTES)
        mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
        mp.setOnPreparedListener { if (it === player) onPrepared(it) }
        mp.setOnSeekCompleteListener { if (it === player) refreshMoment(force = true) }
        mp.setOnCompletionListener {
            if (it === player) {
                playWhenReady = false
                savePosition()
                publishState()
            }
        }
        mp.setOnErrorListener { p, what, extra ->
            if (p === player) fail("A lejátszás megállt (hiba $what/$extra).")
            true
        }
        try {
            mp.setDataSource(applicationContext, rec.uri)
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "cannot open ${rec.displayName}", e)
            fail("A felvétel nem nyitható meg: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        Log.i(TAG, "opening ${rec.displayName} at ${ms / 1000.0} s")
        publishState()
    }

    private fun onPrepared(mp: MediaPlayer) {
        prepared = true
        pendingSeekMs?.let { mp.seekTo(it.coerceAtLeast(0L), MediaPlayer.SEEK_CLOSEST) }
        pendingSeekMs = null
        if (playWhenReady) play() else publishState()
    }

    private fun play() {
        val mp = player ?: return
        playWhenReady = true
        if (!prepared) return publishState()
        if (!requestFocus()) {
            Log.w(TAG, "audio focus denied")
            playWhenReady = false
            return publishState()
        }
        session.isActive = true
        if (!mp.isPlaying) mp.start()
        registerNoisy()
        main.removeCallbacks(tick)
        main.post(tick)
        publishState()
    }

    private fun pause() {
        playWhenReady = false
        player?.let { if (prepared && it.isPlaying) it.pause() }
        unregisterNoisy()
        savePosition()
        publishState()
    }

    private fun stopPlayback() {
        savePosition()
        releasePlayer()
        abandonFocus()
        unregisterNoisy()
        session.isActive = false
        publishState()
    }

    private fun skip(next: Boolean) {
        val b = blind
        if (b == null || player == null) return resume()
        val t = positionMs() / 1000.0
        val target = if (next) b.nextTarget(t) else b.previousTarget(t)
        Log.i(TAG, (if (next) "next" else "previous") + " from ${t.toInt()} s -> #${target?.n}")
        seekTo(target?.let { (it.leadStart * 1000).toLong() } ?: 0L)
        play()
    }

    private fun seekTo(ms: Long) {
        val mp = player ?: return
        if (prepared) mp.seekTo(ms.coerceAtLeast(0L), MediaPlayer.SEEK_CLOSEST) else pendingSeekMs = ms
        shownMoment = blind?.momentAt(ms / 1000.0)?.n
        publishState(positionOverride = ms)
    }

    private fun grade(grade: BlindGrade) {
        val b = blind ?: return
        val moment = b.momentAt(positionMs() / 1000.0)
        if (moment == null) {
            Log.i(TAG, "grade ${grade.id} outside every moment: ignored")
            return
        }
        val now = b.grade(moment.n, grade, System.currentTimeMillis())
        val snapshot = b.entries
        io.execute { BlindGradeStore.save(applicationContext, b.list.id, snapshot) }
        Log.i(TAG, "graded #${moment.n}: ${now?.id ?: "cleared"} (${b.done}/${b.total})")
        publishState()
        notifyChildrenChanged(ROOT_ID)
        notifyChildrenChanged(listMediaId(b.list) + MOMENTS_SUFFIX)
    }

    // --- state on the car screen -----------------------------------------------------------------------------

    private val tick = object : Runnable {
        override fun run() {
            refreshMoment()
            val mp = player
            if (mp != null && prepared && mp.isPlaying) main.postDelayed(this, TICK_MS)
        }
    }

    /** Swaps ⏮ / ⏭ and the grade buttons when playback enters or leaves a moment. */
    private fun refreshMoment(force: Boolean = false) {
        val n = blind?.momentAt(positionMs() / 1000.0)?.n
        if (force || n != shownMoment) {
            shownMoment = n
            publishState()
        }
    }

    private fun publishState(positionOverride: Long? = null) {
        val mp = player
        val playing = mp != null && prepared && mp.isPlaying
        val state = when {
            errorMessage != null -> PlaybackState.STATE_ERROR
            mp == null -> if (blind == null) PlaybackState.STATE_NONE else PlaybackState.STATE_STOPPED
            !prepared -> PlaybackState.STATE_BUFFERING
            playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        val b = blind
        val moment = shownMoment?.let { b?.moment(it) }
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackState.ACTION_PLAY_FROM_SEARCH
        val builder = PlaybackState.Builder()
        if (b != null && moment != null && errorMessage == null) {
            val given = b.gradeOf(moment.n)
            for (g in BlindGrade.entries) {
                val name = (if (g == given) "● " else "") + "${g.symbol} ${g.label}"
                builder.addCustomAction(PlaybackState.CustomAction.Builder(ACTION_PREFIX + g.id, name, iconOf(g, g == given)).build())
            }
        } else if (b != null) {
            actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        }
        builder.setActions(actions)
            .setState(state, positionOverride ?: positionMs(), if (playing) 1f else 0f, SystemClock.elapsedRealtime())
        errorMessage?.let { builder.setErrorMessage(it) }
        session.setPlaybackState(builder.build())
        publishMetadata(b, moment)
    }

    private fun publishMetadata(b: BlindSession?, moment: BlindMoment?) {
        if (b == null) return
        val title: String
        val subtitle: String
        if (moment != null) {
            val grade = b.gradeOf(moment.n)
            title = "#${moment.n}  ${moment.pair}"
            subtitle = (grade?.let { "${it.symbol} ${it.label}" } ?: "Értékeld: ✗ ~ ✓ ★") +
                " · ${moment.from}–${moment.to} · ${b.done}/${b.total}"
        } else {
            title = b.list.title
            val next = b.nextTarget(positionMs() / 1000.0)
            subtitle = "${b.done}/${b.total} értékelve" + (next?.let { " · ⏭ #${it.n} (${it.from})" } ?: "")
        }
        val duration = player?.takeIf { prepared }?.duration?.toLong() ?: recording?.duration ?: -1L
        val key = "$title|$subtitle|$duration"
        if (key == lastMetadataKey) return
        lastMetadataKey = key
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, listMediaId(b.list))
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .build()
        )
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        releasePlayer()
        errorMessage = message
        publishState()
    }

    // --- helpers ------------------------------------------------------------------------------------------

    private fun positionMs(): Long = player?.takeIf { prepared }?.currentPosition?.toLong() ?: pendingSeekMs ?: 0L

    private fun savePosition() {
        val b = blind ?: return
        if (player == null || !prepared) return
        prefs.edit {
            putString(KEY_LIST, b.list.id)
            putLong(KEY_POSITION + b.list.id, positionMs())
        }
    }

    private fun releasePlayer() {
        main.removeCallbacks(tick)
        player?.let { mp ->
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        player = null
        prepared = false
        pendingSeekMs = null
        shownMoment = null
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pause()
                abandonFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val mp = player
                resumeOnFocusGain = mp != null && prepared && mp.isPlaying
                pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                resumeOnFocusGain = false
                play()
            }
        }
    }

    private fun requestFocus(): Boolean {
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(ATTRIBUTES)
            .setWillPauseWhenDucked(false)       // navigation prompts: the system lowers the mix, no pause
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
            .also { focusRequest = it }
        return getSystemService(AudioManager::class.java).requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
    }

    // Unplugged from the car (or headphones): pause instead of playing on the phone's speaker.
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
    }

    private fun iconOf(grade: BlindGrade, given: Boolean): Int = when (grade) {
        BlindGrade.BAD -> if (given) R.drawable.ic_blind_bad_on else R.drawable.ic_blind_bad
        BlindGrade.MEH -> if (given) R.drawable.ic_blind_meh_on else R.drawable.ic_blind_meh
        BlindGrade.OK -> if (given) R.drawable.ic_blind_ok_on else R.drawable.ic_blind_ok
        BlindGrade.STAR -> if (given) R.drawable.ic_blind_star_on else R.drawable.ic_blind_star
    }

    companion object {
        private const val TAG = "BlindAuto"
        private const val ROOT_ID = "blind"
        private const val EMPTY_ROOT_ID = "blind-empty"
        private const val MOMENTS_SUFFIX = "/moments"
        private const val ACTION_PREFIX = "blind.grade."
        private const val PREFS = "blind_auto"
        private const val KEY_LIST = "list"
        private const val KEY_POSITION = "position."
        private const val TICK_MS = 500L

        /** Android Auto, the car assistant / Google app (voice) and the system's media controls. */
        private val TRUSTED_CALLERS = setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.carassistant",
            "com.google.android.googlequicksearchbox",
            "com.android.systemui",
        )

        private val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        private fun listMediaId(list: BlindList) = "$ROOT_ID/${list.id}"

        private fun listFor(mediaId: String): BlindList? = BlindLists.all.firstOrNull { listMediaId(it) == mediaId }

        private fun keyEventOf(intent: Intent): KeyEvent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            }
    }
}
