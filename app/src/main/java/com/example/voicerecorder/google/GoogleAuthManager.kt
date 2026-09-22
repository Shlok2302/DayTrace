package com.example.voicerecorder.google

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Google authorization for every integration, through Google Identity
 * Services' AuthorizationClient (Google's current recommendation for
 * Android; the old GoogleSignIn APIs are deprecated).
 *
 * - No client secret is in the app: Google recognises DayTrace by its
 *   package name and signing certificate (an "Android" OAuth client in
 *   Google Cloud Console).
 * - Scopes are asked for one integration at a time (incremental
 *   authorization); Google keeps what was already allowed.
 * - Google Play services stores and refreshes the tokens. DayTrace only
 *   keeps the account's email address, and never logs a token.
 */
class GoogleAuthManager(
    context: Context
) : GoogleTokens {

    sealed class Authorization {

        /** Access is allowed; [email] is the account, when Google says. */
        class Granted(
            val token: String,
            val email: String?
        ) : Authorization()

        /** The user has to allow it on Google's screen first (see [GoogleConsent]). */
        class NeedsConsent(
            val pendingIntent: PendingIntent
        ) : Authorization()
    }

    private val app =
        context.applicationContext

    private val settings =
        GoogleSettings(app)

    private val client: AuthorizationClient
        get() = Identity.getAuthorizationClient(app)

    /**
     * Asks Google for [scopes] for the connected account (or lets the user
     * pick an account when none is connected yet). Shows nothing itself.
     */
    suspend fun authorize(
        scopes: List<String>
    ): Authorization {

        val request =
            AuthorizationRequest.builder()
                .setRequestedScopes(scopes.map { Scope(it) })
                .apply { settings.accountEmail?.let { setAccount(Account(it, ACCOUNT_TYPE)) } }
                .build()

        val result =
            try {
                client.authorize(request).await()
            } catch (e: ApiException) {
                throw fromApi(e)
            }

        return read(result)
    }

    /**
     * The answer of Google's consent screen. Throws when access was not
     * given, with Google's own reason when it sent one (e.g. this build
     * is not registered with Google) and DENIED when the user said no.
     */
    fun granted(
        answer: GoogleConsent.Answer
    ): Authorization.Granted {

        val data =
            answer.data

        if (data != null) {

            val result =
                try {
                    client.getAuthorizationResultFromIntent(data)
                } catch (e: ApiException) {
                    throw fromApi(e)
                }

            (read(result) as? Authorization.Granted)?.let { return it }
        }

        throw GoogleException(GoogleException.Kind.DENIED, "Access was not allowed")
    }

    override suspend fun token(
        scopes: List<String>
    ): String =
        when (val authorization = authorize(scopes)) {
            is Authorization.Granted -> authorization.token
            is Authorization.NeedsConsent -> throw GoogleException(
                GoogleException.Kind.NEEDS_CONSENT,
                "Google needs the user to allow access again",
                resolution = authorization.pendingIntent
            )
        }

    override suspend fun forget(
        token: String
    ) {
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    /**
     * Removes DayTrace's access to the account at Google. Google revokes
     * every scope DayTrace was given, whichever set is named, but it needs
     * a set the user has actually allowed ("No access token was returned"
     * otherwise), so each of [scopeSets] is tried until one works.
     */
    suspend fun revoke(
        scopeSets: List<List<String>>
    ) {

        val email =
            settings.accountEmail ?: return

        var failure: ApiException? = null

        for (scopes in scopeSets) {
            try {
                client.revokeAccess(
                    RevokeAccessRequest.builder()
                        .setAccount(Account(email, ACCOUNT_TYPE))
                        .setScopes(scopes.map { Scope(it) })
                        .build()
                ).await()
                return
            } catch (e: ApiException) {
                failure = e
            }
        }

        failure?.let { throw fromApi(it) }
    }

    private fun read(
        result: AuthorizationResult
    ): Authorization {

        val pendingIntent =
            result.pendingIntent

        if (result.hasResolution() && pendingIntent != null) {
            return Authorization.NeedsConsent(pendingIntent)
        }

        val token =
            result.accessToken
                ?: throw GoogleException(GoogleException.Kind.UNKNOWN, "Google gave no access token")

        return Authorization.Granted(token, result.toGoogleSignInAccount()?.email)
    }

    private fun fromApi(
        e: ApiException
    ): GoogleException {

        // "This android application is not registered to use OAuth2.0" comes as an internal error.
        val unregistered =
            e.status.statusMessage.orEmpty().contains("UNREGISTERED_ON_API_CONSOLE")

        val kind =
            if (unregistered) GoogleException.Kind.NOT_CONFIGURED else when (e.statusCode) {
                CommonStatusCodes.NETWORK_ERROR,
                CommonStatusCodes.TIMEOUT,
                CommonStatusCodes.INTERRUPTED -> GoogleException.Kind.OFFLINE
                CommonStatusCodes.DEVELOPER_ERROR -> GoogleException.Kind.NOT_CONFIGURED
                CommonStatusCodes.CANCELED -> GoogleException.Kind.DENIED
                CommonStatusCodes.SIGN_IN_REQUIRED,
                CommonStatusCodes.INVALID_ACCOUNT,
                CommonStatusCodes.RESOLUTION_REQUIRED -> GoogleException.Kind.NEEDS_CONSENT
                CommonStatusCodes.API_NOT_CONNECTED,
                CommonStatusCodes.SERVICE_DISABLED,
                CommonStatusCodes.SERVICE_VERSION_UPDATE_REQUIRED -> GoogleException.Kind.API_DISABLED
                else -> GoogleException.Kind.UNKNOWN
            }

        // Only Google's status: never a token or an account.
        Log.w(TAG, "Google authorization failed: status ${e.statusCode} (${e.status.statusMessage}) -> $kind")

        return GoogleException(kind, "Google authorization failed (status ${e.statusCode})", cause = e)
    }

    private companion object {

        const val TAG =
            "GoogleAuth"

        const val ACCOUNT_TYPE =
            "com.google"
    }
}
