package com.example.voicerecorder.google

import android.app.PendingIntent
import org.json.JSONObject
import java.io.IOException

/**
 * Why a Google request did not work. Every Google call in DayTrace fails
 * with one of these, so a screen can say what happened and offer the
 * right next step: try again, reconnect, or wait until the phone is online.
 *
 * A failed Google request never changes a DayTrace note.
 */
class GoogleException(
    val kind: Kind,
    message: String,
    /** For [Kind.NEEDS_CONSENT]: Google's screen that asks the user. */
    val resolution: PendingIntent? = null,
    cause: Throwable? = null
) : Exception(message, cause) {

    /** [retryLater]: the same request can simply be sent again later. */
    enum class Kind(
        val retryLater: Boolean
    ) {
        /** No connection, or Google did not answer in time. */
        OFFLINE(true),

        /** Google had a problem on its side (5xx). */
        SERVER(true),

        /** Too many requests, or the daily quota is used up. */
        QUOTA(true),

        /** The user has to connect (again): access expired, was removed, or was never given. */
        NEEDS_CONSENT(false),

        /** The user said no on Google's screen. */
        DENIED(false),

        /** This build of DayTrace is not registered with Google (OAuth client missing). */
        NOT_CONFIGURED(false),

        /** The API is turned off for DayTrace's Google Cloud project. */
        API_DISABLED(false),

        /** The calendar (or event) does not exist any more. */
        NOT_FOUND(false),

        /** The account may not write there, e.g. a calendar it does not own. */
        FORBIDDEN(false),

        /** The id is already taken: the object was already created. */
        CONFLICT(false),

        /** Google refused the request. */
        INVALID(false),

        UNKNOWN(false)
    }
}

/**
 * Turns Google's answers into [GoogleException]s. Only the status code
 * and Google's reason codes are kept: never the request, the response
 * body or a token, so nothing private ends up in a message or a log.
 */
object GoogleErrors {

    fun fromHttp(
        code: Int,
        body: String
    ): GoogleException {

        val error =
            runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()

        val reasons =
            mutableSetOf<String>()

        error?.optJSONArray("errors")?.let { errors ->
            for (index in 0 until errors.length()) {
                errors.optJSONObject(index)?.optString("reason")?.let(reasons::add)
            }
        }

        // Newer APIs also give a google.rpc.ErrorInfo with a reason.
        error?.optJSONArray("details")?.let { details ->
            for (index in 0 until details.length()) {
                details.optJSONObject(index)?.optString("reason")?.let(reasons::add)
            }
        }

        reasons.remove("")

        val kind =
            when {
                code == 401 -> GoogleException.Kind.NEEDS_CONSENT
                code == 429 -> GoogleException.Kind.QUOTA
                code == 403 && reasons.any { it in QUOTA_REASONS } -> GoogleException.Kind.QUOTA
                code == 403 && reasons.any { it in DISABLED_REASONS } -> GoogleException.Kind.API_DISABLED
                code == 403 && reasons.any { it in SCOPE_REASONS } -> GoogleException.Kind.NEEDS_CONSENT
                code == 403 -> GoogleException.Kind.FORBIDDEN
                code == 404 || code == 410 -> GoogleException.Kind.NOT_FOUND
                code == 409 -> GoogleException.Kind.CONFLICT
                code == 408 -> GoogleException.Kind.OFFLINE
                code >= 500 -> GoogleException.Kind.SERVER
                code in 400..499 -> GoogleException.Kind.INVALID
                else -> GoogleException.Kind.UNKNOWN
            }

        val detail =
            if (reasons.isEmpty()) "" else " (${reasons.joinToString()})"

        return GoogleException(kind, "Google answered $code$detail")
    }

    fun offline(
        cause: IOException
    ): GoogleException =
        GoogleException(
            GoogleException.Kind.OFFLINE,
            "Could not reach Google (${cause.javaClass.simpleName})",
            cause = cause
        )

    private val QUOTA_REASONS =
        setOf("rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded", "dailyLimitExceeded", "RATE_LIMIT_EXCEEDED")

    private val DISABLED_REASONS =
        setOf("accessNotConfigured", "SERVICE_DISABLED", "API_KEY_SERVICE_BLOCKED")

    private val SCOPE_REASONS =
        setOf("insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT")
}
