package com.example.voicerecorder.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.voicerecorder.settings.AppSettings
import com.example.voicerecorder.summary.GeminiSummarizer
import com.example.voicerecorder.summary.NoteActions
import com.example.voicerecorder.summary.NoteStore
import kotlin.concurrent.thread

/**
 * - ACTION_ALERT: a reminder's alarm went off; shows the notification if
 *   the note still needs it (not done, not deleted, reminders on).
 * - ACTION_DONE: "Done" on the notification; the note goes to the
 *   recycle bin and its other reminder is cancelled.
 * - ACTION_NOT_YET: "Not yet"; closes the notification. The note stays,
 *   and the reminder at the deadline still comes.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        val noteId =
            intent.getStringExtra(EXTRA_NOTE_ID) ?: return

        val appContext =
            context.applicationContext

        when (intent.action) {

            ACTION_NOT_YET ->
                AppNotifications.cancelReminder(appContext, noteId)

            ACTION_ALERT, ACTION_DONE -> {

                // Reading and writing the notes file is kept off the main thread.
                val pending =
                    goAsync()

                thread {
                    try {
                        if (intent.action == ACTION_ALERT) {
                            alert(appContext, noteId, intent.getStringExtra(EXTRA_SLOT))
                        } else {
                            done(appContext, noteId)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Reminder action failed for $noteId", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private fun alert(
        context: Context,
        noteId: String,
        slotName: String?
    ) {

        val slot =
            ReminderTimes.Slot.values().firstOrNull { it.name == slotName } ?: return

        val note =
            NoteStore(context).findNote(noteId)?.second

        if (note == null || note.isDeleted || note.category != GeminiSummarizer.REMEMBER) {
            Log.i(TAG, "Reminder skipped, note is gone or done: $noteId")
            return
        }

        if (!AppSettings(context).remindersEnabled) {
            return
        }

        Log.i(TAG, "Reminder shown: $noteId $slot")

        AppNotifications.showReminder(context, note, slot)
    }

    private fun done(
        context: Context,
        noteId: String
    ) {

        val title =
            NoteStore(context).findNote(noteId)?.second?.title.orEmpty()

        if (NoteActions.moveToBin(context, noteId, done = true)) {
            Log.i(TAG, "Marked as done from the reminder: $noteId")
            AppNotifications.showDone(context, noteId, title)
        } else {
            AppNotifications.cancelReminder(context, noteId)
        }
    }

    companion object {

        private const val TAG =
            "ReminderReceiver"

        const val ACTION_ALERT =
            "com.example.voicerecorder.REMINDER_ALERT"

        const val ACTION_DONE =
            "com.example.voicerecorder.REMINDER_DONE"

        const val ACTION_NOT_YET =
            "com.example.voicerecorder.REMINDER_NOT_YET"

        const val EXTRA_NOTE_ID =
            "note_id"

        const val EXTRA_SLOT =
            "slot"

        /** The "Done" / "Not yet" buttons of a reminder notification. */
        fun actionIntent(
            context: Context,
            action: String,
            noteId: String
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, ReminderReceiver::class.java)
                    .setAction(action)
                    .setData(Uri.Builder().scheme("daytrace").authority("note").appendPath(noteId).build())
                    .putExtra(EXTRA_NOTE_ID, noteId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
