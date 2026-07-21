package com.audiopro.djmrec.integrations.soundcloud

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.audiopro.djmrec.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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

    private const val PREFS_NAME = "soundcloud_tokens"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at"

    private var pendingCodeVerifier: String? = null
    private var pendingState: String? = null

    private val _pendingRedirect = MutableStateFlow<Uri?>(null)
    /** Set by [onRedirectReceived] (called from MainActivity); UI collects this to know when a
     *  Custom Tabs round trip has returned, then should call [handleRedirect] and [consumeRedirect]. */
    val pendingRedirect: StateFlow<Uri?> = _pendingRedirect.asStateFlow()

    fun onRedirectReceived(uri: Uri) {
        _pendingRedirect.value = uri
    }

    fun consumeRedirect() {
        _pendingRedirect.value = null
    }

    /** Launches the SoundCloud login/consent page in a Custom Tab. */
    fun launchAuthorization(context: Context) {
        val codeVerifier = randomUrlSafeString()
        val codeChallenge = sha256UrlSafe(codeVerifier)
        val state = randomUrlSafeString()
        pendingCodeVerifier = codeVerifier
        pendingState = state

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
        val verifier = pendingCodeVerifier
        pendingCodeVerifier = null
        val expectedState = pendingState
        pendingState = null

        if (code == null || verifier == null || returnedState == null || returnedState != expectedState) {
            return false
        }
        return exchangeCodeForTokens(context, code, verifier)
    }

    fun isConnected(context: Context): Boolean =
        prefs(context).contains(KEY_REFRESH_TOKEN)

    fun disconnect(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Returns a currently-valid access token, refreshing first if the cached one is expired
     *  (or close to it). Returns null if never connected, or if the refresh itself fails
     *  (revoked/expired refresh token) -- callers should treat null as "not connected". */
    suspend fun getValidAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val expiresAt = p.getLong(KEY_EXPIRES_AT, 0L)
        val accessToken = p.getString(KEY_ACCESS_TOKEN, null)
        if (accessToken != null && System.currentTimeMillis() < expiresAt - 60_000L) {
            return@withContext accessToken
        }
        val refreshToken = p.getString(KEY_REFRESH_TOKEN, null) ?: return@withContext null
        refreshAccessToken(context, refreshToken)
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
            if (postForTokens(context, body)) prefs(context).getString(KEY_ACCESS_TOKEN, null) else null
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
                val editor = prefs(context).edit()
                    .putString(KEY_ACCESS_TOKEN, json.getString("access_token"))
                    .putLong(
                        KEY_EXPIRES_AT,
                        System.currentTimeMillis() + json.optInt("expires_in", 3600) * 1000L
                    )
                val newRefreshToken = json.optString("refresh_token", "")
                if (newRefreshToken.isNotEmpty()) editor.putString(KEY_REFRESH_TOKEN, newRefreshToken)
                editor.apply()
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
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
