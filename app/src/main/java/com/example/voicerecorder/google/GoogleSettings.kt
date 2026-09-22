package com.example.voicerecorder.google

import android.content.Context

/**
 * A Google product DayTrace can send notes to, with the smallest set of
 * OAuth scopes it needs. Each one is connected on its own, when the user
 * turns it on (incremental authorization).
 */
enum class GoogleService(
    val scopes: List<String>
) {

    /**
     * See the list of calendars (to choose one), and add events to
     * calendars the user owns. Not "calendar" or "calendar.events": DayTrace
     * never needs to see or change anything else.
     */
    CALENDAR(
        listOf(
            "https://www.googleapis.com/auth/calendar.calendarlist.readonly",
            "https://www.googleapis.com/auth/calendar.events.owned"
        )
    ),

    /**
     * See the task lists (to choose one) and add tasks. Google Tasks has
     * no narrower write scope than "tasks", so this is the smallest one
     * that can add a task at all.
     */
    TASKS(
        listOf("https://www.googleapis.com/auth/tasks")
    ),

    /**
     * "drive.file", not "documents": DayTrace may only open and change
     * the documents it created itself, never the rest of the user's
     * Drive. The Docs API accepts this scope for those documents.
     */
    DOCS(
        listOf("https://www.googleapis.com/auth/drive.file")
    );

    companion object {

        /** Connecting the account itself only shows which account it is. */
        val ACCOUNT_SCOPES =
            listOf("email")
    }
}

/**
 * The Google integration settings, stored on the device. Tokens are never
 * stored here: Google Play services keeps and refreshes them.
 */
class GoogleSettings(
    context: Context
) {

    private val prefs =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** The connected Google account, or null when none is connected. */
    var accountEmail: String?
        get() = prefs.getString(KEY_ACCOUNT, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT, value).apply()

    val isAccountConnected: Boolean
        get() = accountEmail != null

    /** The user connected this service (Google may still ask again later). */
    fun isEnabled(
        service: GoogleService
    ): Boolean =
        isAccountConnected && prefs.getBoolean(KEY_ENABLED + service.name, false)

    fun setEnabled(
        service: GoogleService,
        enabled: Boolean
    ) {
        prefs.edit().putBoolean(KEY_ENABLED + service.name, enabled).apply()
    }

    /** Where new events go; "primary" until a calendar is chosen. */
    var calendarId: String
        get() = prefs.getString(KEY_CALENDAR_ID, null) ?: PRIMARY
        set(value) = prefs.edit().putString(KEY_CALENDAR_ID, value).apply()

    /** The chosen calendar's name, for the screens. */
    var calendarName: String?
        get() = prefs.getString(KEY_CALENDAR_NAME, null)
        set(value) = prefs.edit().putString(KEY_CALENDAR_NAME, value).apply()

    /**
     * The notification of a new event: [REMINDER_CALENDAR_DEFAULT] (the
     * calendar's own), [REMINDER_NONE], or minutes before it starts.
     */
    var eventReminderMinutes: Int
        get() = prefs.getInt(KEY_REMINDER, REMINDER_CALENDAR_DEFAULT)
        set(value) = prefs.edit().putInt(KEY_REMINDER, value).apply()

    /**
     * How long an event is when the recording gave a start time but no
     * end or length. Shown as "DayTrace's default" wherever it is used.
     */
    var eventLengthMinutes: Int
        get() = prefs.getInt(KEY_LENGTH, DEFAULT_LENGTH_MINUTES)
        set(value) = prefs.edit().putInt(KEY_LENGTH, value).apply()

    // Google Tasks ----------------------------------------------------------

    /** Where new tasks go; [DEFAULT_LIST] until a list is chosen. */
    var taskListId: String
        get() = prefs.getString(KEY_TASK_LIST_ID, null) ?: DEFAULT_LIST
        set(value) = prefs.edit().putString(KEY_TASK_LIST_ID, value).apply()

    /** The chosen task list's name, for the screens. */
    var taskListName: String?
        get() = prefs.getString(KEY_TASK_LIST_NAME, null)
        set(value) = prefs.edit().putString(KEY_TASK_LIST_NAME, value).apply()

    /**
     * Put the note's own words under the task title, so the task carries
     * the context the recording gave it.
     */
    var taskIncludeContext: Boolean
        get() = prefs.getBoolean(KEY_TASK_CONTEXT, true)
        set(value) = prefs.edit().putBoolean(KEY_TASK_CONTEXT, value).apply()

    // Google Docs -----------------------------------------------------------

    /**
     * The document a category is sent to, or null when none was chosen
     * yet. Kept per category ("Idea", "Thoughts", ...), so ideas and
     * thoughts can go to different documents.
     */
    fun documentId(
        category: String
    ): String? =
        prefs.getString(KEY_DOC_ID + category, null)

    fun setDocumentId(
        category: String,
        documentId: String?
    ) {
        prefs.edit().apply {
            if (documentId == null) remove(KEY_DOC_ID + category) else putString(KEY_DOC_ID + category, documentId)
        }.apply()
    }

    /** Forgets the account and every service, for "Disconnect". */
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {

        private const val NAME = "google_integrations"

        private const val KEY_ACCOUNT = "account_email"
        private const val KEY_ENABLED = "enabled_"
        private const val KEY_CALENDAR_ID = "calendar_id"
        private const val KEY_CALENDAR_NAME = "calendar_name"
        private const val KEY_REMINDER = "event_reminder_minutes"
        private const val KEY_LENGTH = "event_length_minutes"
        private const val KEY_TASK_LIST_ID = "task_list_id"
        private const val KEY_TASK_LIST_NAME = "task_list_name"
        private const val KEY_TASK_CONTEXT = "task_include_context"
        private const val KEY_DOC_ID = "document_id_"

        const val PRIMARY = "primary"

        /** Google Tasks' own name for the list every account starts with. */
        const val DEFAULT_LIST = "@default"

        const val REMINDER_CALENDAR_DEFAULT = -1
        const val REMINDER_NONE = 0

        const val DEFAULT_LENGTH_MINUTES = 60

        val REMINDER_CHOICES =
            listOf(REMINDER_CALENDAR_DEFAULT, 10, 30, 60, 24 * 60, REMINDER_NONE)

        val LENGTH_CHOICES =
            listOf(30, 60, 90, 120)
    }
}
