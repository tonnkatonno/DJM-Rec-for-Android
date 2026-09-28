package com.audiopro.djmrec.integrations.soundcloud

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.audiopro.djmrec.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SoundCloud's API treats every client as confidential -- unlike Google Drive's Android-type
 * OAuth client, it requires a client_secret even from a backend-less mobile app (see
 * developers.soundcloud.com/docs/api/guide). That secret therefore ships inside the compiled
 * APK as a BuildConfig string and could be extracted by anyone with a copy of the APK file --
 * a materially different exposure than the git-secrecy `secrets.properties` already gives us.
 * Accepted tradeoff for a personally-distributed app; rotate the secret in the SoundCloud
 * console if this APK ever leaks. Because real user tokens are at stake here (unlike Drive,
 * where Play Services holds the grant and we only store a UI hint), tokens are kept in
 * [EncryptedSharedPreferences] rather than plain prefs.
 *
 * OAuth 2.1 + PKCE, authorization-code flow via Custom Tabs (SoundCloud requires a real browser
 * login, there's no on-device account picker like Google's). [MainActivity] must forward the
 * `djmrec://soundcloud-callback` redirect intent to [onRedirectReceived]; UI observes
 * [pendingRedirect] to know when to complete the exchange.
 */
object SoundCloudAuthManager {
    private const val AUTH_URL = "https://secure.soundcloud.com/authorize"
    private const val TOKEN_URL = "https://secure.soundcloud.com/oauth/token"
    const val REDIRECT_URI = "djmrec://soundcloud-callback"

    private const val TAG = "SoundCloudAuth"
    private const val PREFS_NAME = "soundcloud_tokens"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at"

    // PKCE verifier + state survive process death: while the Custom Tab login is open the app is
    // in the background, and aggressive OEMs (OnePlus/Oppo) often kill it -- with in-memory
    // fields the redirect then arrived into a fresh process with no verifier and the login
    // silently failed. Plain prefs are fine: both values are single-use and useless without
    // the matching authorization code.
    private const val PKCE_PREFS = "soundcloud_pkce"
    private const val KEY_PKCE_VERIFIER = "verifier"
    private const val KEY_PKCE_STATE = "state"

    /** Refresh tokens are single-use: two concurrent refreshes (upload + library scan) used to
     *  burn the token and the loser's 400 then wiped the login. Serialize them. */
    private val refreshMutex = Mutex()

    enum class ConnectStatus { IDLE, EXCHANGING, CONNECTED, FAILED }

    private val _connectStatus = MutableStateFlow(ConnectStatus.IDLE)
    /** Outcome of the last Custom Tabs round trip. Completed here, process-wide, rather than in
     *  a screen's LaunchedEffect: after the OS killed the app during login the redirect lands in
     *  a fresh process whose UI may not be showing Settings, and the code was never exchanged. */
    val connectStatus: StateFlow<ConnectStatus> = _connectStatus.asStateFlow()

    private val _connected = MutableStateFlow<Boolean?>(null)
    /** Observable "has a refresh token" -- flips on connect, disconnect and store resets.
     *  Null until first read via [connectedFlow]. */
    fun connectedFlow(context: Context): StateFlow<Boolean?> {
        if (_connected.value == null) _connected.value = isConnected(context)
        return _connected.asStateFlow()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onRedirectReceived(context: Context, uri: Uri) {
        val app = context.applicationContext
        _connectStatus.value = ConnectStatus.EXCHANGING
        scope.launch {
            val ok = try {
                handleRedirect(app, uri)
            } catch (e: Exception) {
                Log.w(TAG, "SoundCloud token exchange crashed", e)
                false
            }
            _connectStatus.value = if (ok) ConnectStatus.CONNECTED else ConnectStatus.FAILED
            _connected.value = isConnected(app)
        }
    }

    /** Launches the SoundCloud login/consent page in a Custom Tab. */
    fun launchAuthorization(context: Context) {
        val codeVerifier = randomUrlSafeString()
        val codeChallenge = sha256UrlSafe(codeVerifier)
        val state = randomUrlSafeString()
        context.applicationContext.getSharedPreferences(PKCE_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PKCE_VERIFIER, codeVerifier)
            .putString(KEY_PKCE_STATE, state)
            .commit()

        val uri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", BuildConfig.SOUNDCLOUD_CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("display", "popup")
            .build()

        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    }

    /** Call once [pendingRedirect] fires. Returns true on a successful token exchange. */
    suspend fun handleRedirect(context: Context, uri: Uri): Boolean {
        val code = uri.getQueryParameter("code")
        val returnedState = uri.getQueryParameter("state")
        val pkce = context.applicationContext.getSharedPreferences(PKCE_PREFS, Context.MODE_PRIVATE)
        val verifier = pkce.getString(KEY_PKCE_VERIFIER, null)
        val expectedState = pkce.getString(KEY_PKCE_STATE, null)
        pkce.edit().clear().apply()

        if (code == null || verifier == null || returnedState == null || returnedState != expectedState) {
            return false
        }
        return exchangeCodeForTokens(context, code, verifier)
    }

    fun isConnected(context: Context): Boolean =
        guarded(context, false) { it.contains(KEY_REFRESH_TOKEN) }

    fun disconnect(context: Context) {
        guarded(context, Unit) { it.edit().clear().commit() }
        _connected.value = false
    }

    /** Returns a currently-valid access token, refreshing first if the cached one is expired
     *  (or close to it). Returns null if never connected, or if the refresh itself fails
     *  (revoked/expired refresh token) -- callers should treat null as "not connected". */
    suspend fun getValidAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            // Read inside the lock: a refresh that just finished in another coroutine already
            // stored a fresh access token + the *new* refresh token.
            val (accessToken, expiresAt, refreshToken) = guarded(context, Triple<String?, Long, String?>(null, 0L, null)) { p ->
                Triple(p.getString(KEY_ACCESS_TOKEN, null), p.getLong(KEY_EXPIRES_AT, 0L), p.getString(KEY_REFRESH_TOKEN, null))
            }
            if (accessToken != null && System.currentTimeMillis() < expiresAt - 60_000L) {
                return@withLock accessToken
            }
            refreshToken ?: return@withLock null
            refreshAccessToken(context, refreshToken)
        }
    }

    private suspend fun exchangeCodeForTokens(context: Context, code: String, verifier: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", BuildConfig.SOUNDCLOUD_CLIENT_ID)
                .add("client_secret", BuildConfig.SOUNDCLOUD_CLIENT_SECRET)
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", verifier)
                .add("code", code)
                .build()
            postForTokens(context, body)
        }

    /** SoundCloud refresh tokens are single-use -- the response always carries a new one, which
     *  we must persist, or the next refresh will fail. */
    private suspend fun refreshAccessToken(context: Context, refreshToken: String): String? =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", BuildConfig.SOUNDCLOUD_CLIENT_ID)
                .add("client_secret", BuildConfig.SOUNDCLOUD_CLIENT_SECRET)
                .add("refresh_token", refreshToken)
                .build()
            if (postForTokens(context, body)) guarded(context, null) { it.getString(KEY_ACCESS_TOKEN, null) } else null
        }

    private fun postForTokens(context: Context, body: FormBody): Boolean {
        val request = Request.Builder().url(TOKEN_URL).post(body).build()
        return try {
            OkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 401 || response.code == 400) disconnect(context)
                    return false
                }
                val json = JSONObject(response.body?.string().orEmpty())
                val saved = guarded(context, false) { p ->
                    val editor = p.edit()
                        .putString(KEY_ACCESS_TOKEN, json.getString("access_token"))
                        .putLong(
                            KEY_EXPIRES_AT,
                            System.currentTimeMillis() + json.optInt("expires_in", 3600) * 1000L
                        )
                    val newRefreshToken = json.optString("refresh_token", "")
                    if (newRefreshToken.isNotEmpty()) editor.putString(KEY_REFRESH_TOKEN, newRefreshToken)
                    // commit, not apply: a refresh token is single-use, losing it = forced re-login
                    editor.commit()
                }
                if (saved) _needsReconnect.value = false
                saved
            }
        } catch (e: Exception) {
            false
        }
    }

    private val _needsReconnect = MutableStateFlow(false)
    /** True once a corrupted token store had to be wiped -- Settings shows "reconnect" instead
     *  of the upload silently failing. Cleared by the next successful token exchange. */
    val needsReconnect: StateFlow<Boolean> = _needsReconnect.asStateFlow()

    @Volatile private var cachedPrefs: SharedPreferences? = null

    /**
     * The Keystore master key and the Tink keyset stored inside the prefs file can drift apart
     * (reinstall with `-k`, OEM "app clone"/restore, clearing only one of the two, a Keystore
     * reset on OS update) -- every decrypt then throws BadPaddingException/KeyStoreException
     * (BAD_DECRYPT). That used to crash the app from both the upload service and the UI.
     * Instead: probe the store once, and on any crypto failure throw the whole store *and* the
     * master key away and start clean; the user just has to reconnect SoundCloud.
     */
    private fun prefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        synchronized(this) {
            cachedPrefs?.let { return it }
            val app = context.applicationContext
            val created = try {
                openEncryptedPrefs(app).also { it.all } // .all forces every entry to decrypt
            } catch (e: Exception) {
                Log.w(TAG, "SoundCloud token store unreadable, resetting it", e)
                resetEncryptedStore(app)
                _needsReconnect.value = true
                try {
                    openEncryptedPrefs(app)
                } catch (e2: Exception) {
                    // Keystore itself unusable -- degrade to "not connected" rather than crash.
                    Log.e(TAG, "SoundCloud token store still unusable after reset", e2)
                    app.getSharedPreferences("${PREFS_NAME}_fallback_empty", Context.MODE_PRIVATE)
                        .also { it.edit().clear().apply() }
                }
            }
            cachedPrefs = created
            return created
        }
    }

    private fun openEncryptedPrefs(app: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            app,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun resetEncryptedStore(app: Context) {
        try {
            app.deleteSharedPreferences(PREFS_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete $PREFS_NAME", e)
        }
        try {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete master key", e)
        }
    }

    /** Any read/write of an already-open store can still hit a Keystore failure mid-session --
     *  treat it the same as a corrupt store on open. */
    private inline fun <T> guarded(context: Context, fallback: T, block: (SharedPreferences) -> T): T =
        try {
            block(prefs(context))
        } catch (e: Exception) {
            Log.w(TAG, "SoundCloud token store failed mid-use, resetting it", e)
            synchronized(this) {
                cachedPrefs = null
                resetEncryptedStore(context.applicationContext)
            }
            _needsReconnect.value = true
            _connected.value = false
            fallback
        }

    private fun randomUrlSafeString(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun sha256UrlSafe(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
