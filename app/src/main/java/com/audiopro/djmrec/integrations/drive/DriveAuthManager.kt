package com.audiopro.djmrec.integrations.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.tasks.await

/**
 * Wraps Play Services' AuthorizationClient (`Identity.getAuthorizationClient`) -- the current
 * replacement for the deprecated `GoogleSignInClient.requestScopes` -- to get short-lived OAuth
 * access tokens scoped to `drive.file` (files this app creates, not the user's whole Drive).
 *
 * No backend server and no refresh-token storage on-device: once the user grants consent once
 * (via [Outcome.NeedsConsent]'s consent screen, which requires an Activity), every later call to
 * [requestAccess] from anywhere in the app -- including a background Service -- silently returns
 * a fresh access token with no UI, for as long as Play Services keeps the grant valid. This is
 * what makes "log in once, then it just works" possible without us running any server of our own.
 */
object DriveAuthManager {

    sealed class Outcome {
        data class Authorized(val accessToken: String) : Outcome()
        data class NeedsConsent(val pendingIntent: PendingIntent) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_FILE)))
            .build()

    /** Silent whenever possible; only needs to show UI the first time (or after the user
     *  revokes access from their Google Account settings). Safe to call from any context. */
    suspend fun requestAccess(context: Context): Outcome {
        return try {
            toOutcome(Identity.getAuthorizationClient(context).authorize(request()).await())
        } catch (e: ApiException) {
            Outcome.Failed(e.message ?: "Google authorization failed (code ${e.statusCode})")
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: "Google authorization failed")
        }
    }

    /** Call from an Activity's `ActivityResultLauncher<IntentSenderRequest>` callback after
     *  launching an [Outcome.NeedsConsent]'s `pendingIntent.intentSender`. */
    fun resultFromIntent(context: Context, data: Intent?): Outcome {
        return try {
            toOutcome(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data))
        } catch (e: ApiException) {
            Outcome.Failed(e.message ?: "Google authorization failed (code ${e.statusCode})")
        }
    }

    private fun toOutcome(result: AuthorizationResult): Outcome {
        if (result.hasResolution()) {
            val pendingIntent = result.pendingIntent
                ?: return Outcome.Failed("Google did not return a consent screen to show")
            return Outcome.NeedsConsent(pendingIntent)
        }
        val token = result.accessToken ?: return Outcome.Failed("No access token returned")
        return Outcome.Authorized(token)
    }
}
