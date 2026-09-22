package com.example.voicerecorder.google.calendar

import com.example.voicerecorder.google.GoogleException
import com.example.voicerecorder.google.GoogleRestClient
import org.json.JSONObject
import java.security.MessageDigest

/** A calendar the user can add events to. */
data class GoogleCalendar(
    val id: String,
    val name: String,
    val primary: Boolean
)

/** An event in Google Calendar, as far as DayTrace needs to know it. */
data class CalendarEvent(
    val id: String,
    val htmlLink: String,
    val status: String
) {

    /** Deleted in Google Calendar (Google keeps deleted events for a while). */
    val isCancelled: Boolean
        get() = status == "cancelled"
}

/** The Google Calendar requests DayTrace makes. Tests use a fake. */
interface CalendarApi {

    /** The calendars the user owns (the only ones DayTrace may add to). */
    suspend fun calendars(): List<GoogleCalendar>

    suspend fun insert(
        calendarId: String,
        event: JSONObject
    ): CalendarEvent

    /** Null when there is no event with this id (never made, or deleted for good). */
    suspend fun get(
        calendarId: String,
        eventId: String
    ): CalendarEvent?
}

/** Google Calendar API v3. */
class RestCalendarApi(
    private val rest: GoogleRestClient
) : CalendarApi {

    override suspend fun calendars(): List<GoogleCalendar> {

        val calendars =
            mutableListOf<GoogleCalendar>()

        var pageToken: String? = null

        do {
            val page =
                rest.get(
                    "$BASE/users/me/calendarList?minAccessRole=owner&maxResults=250" +
                            pageToken?.let { "&pageToken=${GoogleRestClient.encode(it)}" }.orEmpty()
                )

            val items =
                page.optJSONArray("items")

            for (index in 0 until (items?.length() ?: 0)) {

                val item =
                    items?.optJSONObject(index) ?: continue

                if (item.optBoolean("deleted")) {
                    continue
                }

                calendars += GoogleCalendar(
                    id = item.getString("id"),
                    name = item.optString("summaryOverride").ifEmpty { item.optString("summary") }.ifEmpty { item.getString("id") },
                    primary = item.optBoolean("primary")
                )
            }

            pageToken = page.optString("nextPageToken").ifEmpty { null }

        } while (pageToken != null)

        // The main calendar first, then by name.
        return calendars.sortedWith(compareBy<GoogleCalendar> { !it.primary }.thenBy { it.name.lowercase() })
    }

    override suspend fun insert(
        calendarId: String,
        event: JSONObject
    ): CalendarEvent =
        toEvent(rest.post("$BASE/calendars/${GoogleRestClient.encode(calendarId)}/events", event))

    override suspend fun get(
        calendarId: String,
        eventId: String
    ): CalendarEvent? =
        try {
            toEvent(
                rest.get(
                    "$BASE/calendars/${GoogleRestClient.encode(calendarId)}/events/${GoogleRestClient.encode(eventId)}"
                )
            )
        } catch (e: GoogleException) {
            if (e.kind == GoogleException.Kind.NOT_FOUND) null else throw e
        }

    private fun toEvent(
        json: JSONObject
    ): CalendarEvent =
        CalendarEvent(
            id = json.getString("id"),
            htmlLink = json.optString("htmlLink"),
            status = json.optString("status")
        )

    private companion object {

        const val BASE =
            "https://www.googleapis.com/calendar/v3"
    }
}

/**
 * The event id DayTrace gives a note's event. Google lets the app choose
 * the id, and refuses a second event with the same id (409), so the same
 * note sent twice (a double tap, a retry after the answer was lost, a
 * reinstall) can never create two events.
 */
object CalendarEventIds {

    /** Google allows the letters a-v and digits 0-9 (base32hex), 5 to 1024 characters. */
    private const val ALPHABET =
        "0123456789abcdefghijklmnopqrstuv"

    /**
     * [generation] only goes up when the user deleted the event in Google
     * Calendar and added the note again (a deleted event keeps its id).
     */
    fun forNote(
        noteId: String,
        calendarId: String,
        generation: Int
    ): String {

        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest("daytrace|$noteId|$calendarId|$generation".toByteArray(Charsets.UTF_8))

        return "dt" + base32hex(digest.copyOf(20))
    }

    private fun base32hex(
        bytes: ByteArray
    ): String {

        val out =
            StringBuilder()

        var buffer = 0
        var bits = 0

        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }

        if (bits > 0) {
            out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        }

        return out.toString()
    }
}
