package com.example.voicerecorder.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.voicerecorder.MainActivity
import com.example.voicerecorder.R
import com.example.voicerecorder.summary.Note
import com.example.voicerecorder.ui.Notes

/**
 * The app's own notifications (the recording service keeps its own):
 *
 * - Reminders for Remember notes with a deadline, with "Done" and
 *   "Not yet" buttons.
 * - Processing updates: a recording was turned into notes, or failed.
 */
object AppNotifications {

    private const val CHANNEL_REMINDERS =
        "reminders"

    private const val CHANNEL_UPDATES =
        "processing_updates"

    private const val ID_REMINDER =
        1001

    private const val ID_UPDATE =
        1002

    private const val TAG_UPDATE =
        "processing"

    /** How long the "marked as done" confirmation stays. */
    private const val DONE_TIMEOUT_MILLIS =
        6_000L

    fun createChannels(
        context: Context
    ) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager =
            context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.channel_reminders),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.channel_reminders_description) }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPDATES,
                context.getString(R.string.channel_updates),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.channel_updates_description) }
        )
    }

    /** False when the user has not allowed (or has turned off) notifications. */
    fun canPost(
        context: Context
    ): Boolean {

        val permitted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED

        return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** "Send the APK to Shlok — Due at 08:00 PM. Have you done it?" [Done] [Not yet] */
    @SuppressLint("MissingPermission")
    fun showReminder(
        context: Context,
        note: Note,
        slot: ReminderTimes.Slot
    ) {

        if (!canPost(context)) {
            return
        }

        val question =
            when (slot) {
                ReminderTimes.Slot.EARLY ->
                    context.getString(R.string.reminder_early, Notes.formatDue(note))
                ReminderTimes.Slot.DUE ->
                    context.getString(R.string.reminder_due)
                ReminderTimes.Slot.DAY ->
                    context.getString(R.string.reminder_day)
            }

        val notification =
            NotificationCompat.Builder(context, CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_bell)
                .setColor(ContextCompat.getColor(context, R.color.remember))
                .setContentTitle(note.title)
                .setContentText(question)
                .setStyle(NotificationCompat.BigTextStyle().bigText("${note.text}\n$question"))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openApp(context, MainActivity.EXTRA_OPEN_NOTE, note.id))
                .addAction(
                    0,
                    context.getString(R.string.reminder_done),
                    ReminderReceiver.actionIntent(context, ReminderReceiver.ACTION_DONE, note.id)
                )
                .addAction(
                    0,
                    context.getString(R.string.reminder_not_yet),
                    ReminderReceiver.actionIntent(context, ReminderReceiver.ACTION_NOT_YET, note.id)
                )
                .build()

        // One notification per note: the reminder at the deadline replaces the early one.
        NotificationManagerCompat.from(context).notify(note.id, ID_REMINDER, notification)
    }

    /** Replaces a reminder with a short "done" confirmation that goes away by itself. */
    @SuppressLint("MissingPermission")
    fun showDone(
        context: Context,
        noteId: String,
        title: String
    ) {

        if (!canPost(context)) {
            return
        }

        val notification =
            NotificationCompat.Builder(context, CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_check)
                .setColor(ContextCompat.getColor(context, R.color.remember))
                .setContentTitle(context.getString(R.string.reminder_marked_done, title))
                .setContentText(context.getString(R.string.reminder_moved_to_bin))
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setAutoCancel(true)
                .setTimeoutAfter(DONE_TIMEOUT_MILLIS)
                .build()

        NotificationManagerCompat.from(context).notify(noteId, ID_REMINDER, notification)
    }

    fun cancelReminder(
        context: Context,
        noteId: String
    ) {
        NotificationManagerCompat.from(context).cancel(noteId, ID_REMINDER)
    }

    /** "2 notes saved — Send APK to Shlok, DBMS assignment" */
    @SuppressLint("MissingPermission")
    fun showNotesReady(
        context: Context,
        notes: List<Note>
    ) {

        if (!canPost(context)) {
            return
        }

        val title =
            if (notes.isEmpty()) {
                context.getString(R.string.update_nothing_title)
            } else {
                context.resources.getQuantityString(R.plurals.update_notes_title, notes.size, notes.size)
            }

        val text =
            if (notes.isEmpty()) {
                context.getString(R.string.update_nothing_text)
            } else {
                notes.joinToString(", ") { it.title }
            }

        post(context, title, text, openApp(context, MainActivity.EXTRA_OPEN_HISTORY, "1"))
    }

    @SuppressLint("MissingPermission")
    fun showProcessingFailed(
        context: Context,
        reason: String
    ) {

        if (!canPost(context)) {
            return
        }

        post(
            context,
            context.getString(R.string.update_failed_title),
            context.getString(R.string.update_failed_text, reason),
            openApp(context, MainActivity.EXTRA_OPEN_HISTORY, "1")
        )
    }

    @SuppressLint("MissingPermission")
    private fun post(
        context: Context,
        title: String,
        text: String,
        open: PendingIntent
    ) {

        val notification =
            NotificationCompat.Builder(context, CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_leaf)
                .setColor(ContextCompat.getColor(context, R.color.forest))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()

        NotificationManagerCompat.from(context).notify(TAG_UPDATE, ID_UPDATE, notification)
    }

    private fun openApp(
        context: Context,
        extra: String,
        value: String
    ): PendingIntent {

        val intent =
            Intent(context, MainActivity::class.java)
                // Separate data per target, so two notifications never share one PendingIntent.
                .setData(Uri.Builder().scheme("daytrace").authority("open").appendPath(extra).appendPath(value).build())
                .putExtra(extra, value)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )

        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
