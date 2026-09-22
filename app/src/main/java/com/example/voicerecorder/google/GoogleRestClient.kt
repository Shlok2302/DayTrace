package com.example.voicerecorder.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Access tokens for Google's APIs. The app's one implementation is
 * [GoogleAuthManager]; tests use a fake.
 */
interface GoogleTokens {

    /**
     * A token that allows [scopes]. Throws GoogleException(NEEDS_CONSENT)
     * when the user first has to allow access on Google's screen.
     */
    suspend fun token(
        scopes: List<String>
    ): String

    /** Forgets a token Google refused (expired or revoked), so the next one is fresh. */
    suspend fun forget(
        token: String
    )
}

/** One HTTP answer: the status code and the body as text. */
class HttpAnswer(
    val code: Int,
    val body: String
)

/** Sends one request (blocking). The real one is [HttpTransport]. */
fun interface Transport {

    fun send(
        method: String,
        url: String,
        token: String,
        body: String?
    ): HttpAnswer
}

/**
 * Calls one Google REST API with the scopes of one integration. Shared by
 * every Google service, so authorization and errors are handled once:
 *
 * - a token Google refuses (401) is forgotten and the request is sent once
 *   more with a fresh one; if that fails too, the user has to reconnect
 * - no connection becomes GoogleException(OFFLINE)
 * - every other error becomes a [GoogleException] (see [GoogleErrors])
 */
class GoogleRestClient(
    private val tokens: GoogleTokens,
    private val scopes: List<String>,
    private val transport: Transport = HttpTransport
) {

    suspend fun get(
        url: String
    ): JSONObject =
        call("GET", url, null)

    suspend fun post(
        url: String,
        body: JSONObject
    ): JSONObject =
        call("POST", url, body)

    private suspend fun call(
        method: String,
        url: String,
        body: JSONObject?
    ): JSONObject {

        val text =
            body?.toString()

        var token =
            tokens.token(scopes)

        var answer =
            send(method, url, token, text)

        if (answer.code == 401) {
            tokens.forget(token)
            token = tokens.token(scopes)
            answer = send(method, url, token, text)
        }

        if (answer.code !in 200..299) {
            throw GoogleErrors.fromHttp(answer.code, answer.body)
        }

        return try {
            if (answer.body.isBlank()) JSONObject() else JSONObject(answer.body)
        } catch (e: JSONException) {
            throw GoogleException(GoogleException.Kind.UNKNOWN, "Google sent an answer DayTrace could not read", cause = e)
        }
    }

    private suspend fun send(
        method: String,
        url: String,
        token: String,
        body: String?
    ): HttpAnswer =
        withContext(Dispatchers.IO) {
            try {
                transport.send(method, url, token, body)
            } catch (e: IOException) {
                throw GoogleErrors.offline(e)
            }
        }

    companion object {

        /** For a value inside a URL path or query, e.g. a calendar id with "@" and "#". */
        fun encode(
            value: String
        ): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

/** HttpURLConnection, like the Gemini requests. Never logs the token or the body. */
object HttpTransport : Transport {

    private const val CONNECT_TIMEOUT_MS = 20_000

    private const val READ_TIMEOUT_MS = 30_000

    override fun send(
        method: String,
        url: String,
        token: String,
        body: String?
    ): HttpAnswer {

        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }

        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code =
                connection.responseCode

            val stream =
                if (code in 200..299) connection.inputStream else connection.errorStream

            return HttpAnswer(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())

        } finally {
            connection.disconnect()
        }
    }
}
