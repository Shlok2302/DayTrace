package com.example.voicerecorder.google

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.voicerecorder.google.calendar.CalendarAdder
import com.example.voicerecorder.google.calendar.CalendarApi
import com.example.voicerecorder.google.calendar.EventDetectionWorker
import com.example.voicerecorder.google.calendar.GoogleSyncWorker
import com.example.voicerecorder.google.calendar.RestCalendarApi
import com.example.voicerecorder.google.docs.DocsApi
import com.example.voicerecorder.google.docs.DocsAppender
import com.example.voicerecorder.google.docs.RestDocsApi
import com.example.voicerecorder.google.tasks.RestTasksApi
import com.example.voicerecorder.google.tasks.TaskAdder
import com.example.voicerecorder.google.tasks.TaskSyncWorker
import com.example.voicerecorder.google.tasks.TasksApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.concurrent.thread

/** What the settings page shows for a service. */
enum class ServiceState {
    OFF,
    CONNECTED,
    /** Google no longer allows it (expired, removed): the user reconnects. */
    NEEDS_RECONNECT,
    /** Could not ask Google right now (offline). */
    CANNOT_CHECK
}

/**
 * The one entry point of the Google integrations for the screens and
 * workers: connecting and disconnecting the account and each service,
 * and the service clients. Authorization is shared (GoogleAuthManager),
 * so no service handles it on its own.
 *
 * Kept apart from recording, Gemini summarization, NoteStore and the
 * reminders: it only reads notes, and writes to files/google/.
 */
class GoogleIntegrationManager(
    context: Context
) {

    private val app =
        context.applicationContext

    val auth =
        GoogleAuthManager(app)

    val settings =
        GoogleSettings(app)

    val store =
        GoogleIntegrationStore(app)

    val accountEmail: String?
        get() = settings.accountEmail

    fun isConnected(
        service: GoogleService
    ): Boolean =
        settings.isEnabled(service)

    /**
     * Lets the user pick a Google account (Google's own screen) and allow
     * DayTrace to see which account it is. Nothing else is asked for yet.
     */
    suspend fun connectAccount(
        consent: GoogleConsent
    ): String {

        val granted =
            authorize(GoogleService.ACCOUNT_SCOPES, consent)

        val email =
            granted.email ?: fetchEmail(granted.token)

        settings.accountEmail = email

        notifyChanged(app)

        return email
    }

    /** Asks Google for this service's permissions only (the account first, if needed). */
    suspend fun connect(
        service: GoogleService,
        consent: GoogleConsent
    ) {

        if (!settings.isAccountConnected) {
            connectAccount(consent)
        }

        authorize(service.scopes, consent)

        settings.setEnabled(service, true)

        when (service) {

            GoogleService.CALENDAR -> {
                useMainCalendarIfNoneChosen()
                // Events confirmed before, waiting for this: send them now.
                resumePending()
                EventDetectionWorker.enqueue(app)
            }

            GoogleService.TASKS -> {
                useMainTaskListIfNoneChosen()
                // Tasks confirmed before, waiting for this: send them now.
                resumePendingTasks()
                // Which Remember notes are to-dos is the same question the
                // calendar asks, and the same answer is reused.
                EventDetectionWorker.enqueue(app)
            }

            // Documents are only ever made and appended to when the user
            // asks, so there is nothing to catch up on here.
            GoogleService.DOCS -> Unit
        }

        notifyChanged(app)
    }

    /** Asks Google, without showing anything, whether it still allows [service]. */
    suspend fun state(
        service: GoogleService
    ): ServiceState {

        if (!isConnected(service)) {
            return ServiceState.OFF
        }

        return try {
            when (auth.authorize(service.scopes)) {
                is GoogleAuthManager.Authorization.Granted -> ServiceState.CONNECTED
                is GoogleAuthManager.Authorization.NeedsConsent -> ServiceState.NEEDS_RECONNECT
            }
        } catch (e: GoogleException) {
            if (e.kind.retryLater) ServiceState.CANNOT_CHECK else ServiceState.NEEDS_RECONNECT
        }
    }

    /**
     * DayTrace stops using [service]. Events waiting to be sent are
     * cancelled; events already in Google Calendar stay there.
     */
    fun disconnect(
        service: GoogleService
    ): Int {

        settings.setEnabled(service, false)

        val cancelled =
            when (service) {

                GoogleService.CALENDAR -> {
                    GoogleSyncWorker.cancel(app)
                    store.clearPending()
                }

                GoogleService.TASKS -> {
                    TaskSyncWorker.cancel(app)
                    store.clearPendingTasks()
                }

                /*
                 * The documents themselves stay in the user's Google Drive,
                 * and DayTrace keeps knowing which notes are already in them,
                 * so reconnecting later does not write anything twice.
                 */
                GoogleService.DOCS -> 0
            }

        notifyChanged(app)

        return cancelled
    }

    /**
     * Removes DayTrace's access at Google and forgets the account. Returns
     * false when Google could not be told (offline): the account is still
     * forgotten here, and the user can remove the access in their Google
     * Account.
     */
    suspend fun disconnectAccount(): Boolean {

        // The services that were connected first (their permission is the likeliest
        // to still be allowed), then the account's own.
        val scopeSets =
            GoogleService.values().filter { isConnected(it) }.map { it.scopes } +
                    GoogleService.values().filterNot { isConnected(it) }.map { it.scopes } +
                    listOf(GoogleService.ACCOUNT_SCOPES)

        val revoked =
            runCatching { auth.revoke(scopeSets) }.isSuccess

        GoogleService.values().forEach { disconnect(it) }

        settings.clear()

        notifyChanged(app)

        return revoked
    }

    fun calendarApi(): CalendarApi =
        RestCalendarApi(GoogleRestClient(auth, GoogleService.CALENDAR.scopes))

    fun calendarAdder(): CalendarAdder =
        CalendarAdder(calendarApi(), store)

    fun tasksApi(): TasksApi =
        RestTasksApi(GoogleRestClient(auth, GoogleService.TASKS.scopes))

    fun taskAdder(): TaskAdder =
        TaskAdder(tasksApi(), store)

    fun docsApi(): DocsApi =
        RestDocsApi(GoogleRestClient(auth, GoogleService.DOCS.scopes))

    fun docsAppender(): DocsAppender =
        DocsAppender(docsApi(), store)

    /** After reconnecting, or "Try again": the waiting events are sent again. */
    fun resumePending() {

        val pending =
            store.pending()

        if (pending.isEmpty()) {
            return
        }

        pending
            .filter { it.state != PendingCalendarAdd.STATE_WAITING }
            .forEach { store.savePending(it.copy(state = PendingCalendarAdd.STATE_WAITING, problem = "")) }

        GoogleSyncWorker.enqueue(app)
    }

    /** Cancels one waiting event. The note is not changed. */
    fun cancelPending(
        noteId: String
    ) {
        store.removePending(noteId)
        notifyChanged(app)
    }

    /** After reconnecting, or "Try again": the waiting tasks are sent again. */
    fun resumePendingTasks() {

        val pending =
            store.pendingTasks()

        if (pending.isEmpty()) {
            return
        }

        pending
            .filter { it.state != PendingTaskAdd.STATE_WAITING }
            .forEach { store.savePendingTask(it.copy(state = PendingTaskAdd.STATE_WAITING, problem = "")) }

        TaskSyncWorker.enqueue(app)
    }

    /** Cancels one waiting task. The note is not changed. */
    fun cancelPendingTask(
        noteId: String
    ) {
        store.removePendingTask(noteId)
        notifyChanged(app)
    }

    /** Google's consent screen, only when Google asks for it. */
    private suspend fun authorize(
        scopes: List<String>,
        consent: GoogleConsent
    ): GoogleAuthManager.Authorization.Granted =
        when (val authorization = auth.authorize(scopes)) {
            is GoogleAuthManager.Authorization.Granted -> authorization
            is GoogleAuthManager.Authorization.NeedsConsent -> auth.granted(consent.ask(authorization.pendingIntent))
        }

    /** The account's email address, when Google did not include it. */
    private suspend fun fetchEmail(
        token: String
    ): String {

        val answer =
            withContext(Dispatchers.IO) {
                try {
                    HttpTransport.send("GET", USER_INFO_URL, token, null)
                } catch (e: java.io.IOException) {
                    throw GoogleErrors.offline(e)
                }
            }

        if (answer.code !in 200..299) {
            throw GoogleErrors.fromHttp(answer.code, answer.body)
        }

        return runCatching { JSONObject(answer.body).getString("email") }.getOrNull()
            ?: throw GoogleException(GoogleException.Kind.UNKNOWN, "Google did not say which account it is")
    }

    /** New events go to the account's main calendar until the user picks another. */
    private suspend fun useMainCalendarIfNoneChosen() {

        if (settings.calendarName != null) {
            return
        }

        runCatching { calendarApi().calendars() }
            .getOrNull()
            ?.firstOrNull { it.primary }
            ?.let {
                settings.calendarId = it.id
                settings.calendarName = it.name
            }
    }

    /** New tasks go to the account's main list until the user picks another. */
    private suspend fun useMainTaskListIfNoneChosen() {

        if (settings.taskListName != null) {
            return
        }

        runCatching { tasksApi().lists() }
            .getOrNull()
            ?.firstOrNull()
            ?.let {
                settings.taskListId = it.id
                settings.taskListName = it.name
            }
    }

    companion object {

        private const val USER_INFO_URL =
            "https://openidconnect.googleapis.com/v1/userinfo"

        /** Sent inside the app when links, waiting events or suggestions change. */
        const val ACTION_CHANGED =
            "com.example.voicerecorder.GOOGLE_CHANGED"

        /**
         * When the app opens, if Google Calendar is connected: send events
         * that are waiting, and look for events in new notes (e.g. notes
         * restored from a backup). Both only run once the phone is online.
         */
        fun onAppOpened(
            context: Context
        ) {

            val app =
                context.applicationContext

            thread {
                runCatching {

                    val manager =
                        GoogleIntegrationManager(app)

                    if (manager.isConnected(GoogleService.CALENDAR)) {
                        EventDetectionWorker.enqueue(app)
                        if (manager.store.pending().any { it.state == PendingCalendarAdd.STATE_WAITING }) {
                            GoogleSyncWorker.enqueue(app)
                        }
                    }

                    if (manager.isConnected(GoogleService.TASKS)) {
                        EventDetectionWorker.enqueue(app)
                        if (manager.store.pendingTasks().any { it.state == PendingTaskAdd.STATE_WAITING }) {
                            TaskSyncWorker.enqueue(app)
                        }
                    }
                }
            }
        }

        fun notifyChanged(
            context: Context
        ) {
            context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
        }

        /** Calls [onChange] while [owner] is started, whenever something Google-related changed. */
        fun observe(
            context: Context,
            owner: LifecycleOwner,
            onChange: () -> Unit
        ) {

            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) = onChange()
                }

            owner.lifecycle.addObserver(object : DefaultLifecycleObserver {

                override fun onStart(owner: LifecycleOwner) {
                    ContextCompat.registerReceiver(
                        context,
                        receiver,
                        IntentFilter(ACTION_CHANGED),
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                }

                override fun onStop(owner: LifecycleOwner) {
                    runCatching { context.unregisterReceiver(receiver) }
                }
            })
        }
    }
}
