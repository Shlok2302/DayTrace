package com.example.voicerecorder.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.voicerecorder.settings.AppSettings
import com.example.voicerecorder.summary.GeminiSummarizer
import com.example.voicerecorder.summary.Note
import com.example.voicerecorder.summary.NoteStore
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Schedules the reminders of Remember notes that have a deadline, with
 * AlarmManager. When one goes off, ReminderReceiver shows the notification.
 *
 * [sync] brings every alarm in line with the saved notes and the
 * settings, so it is safe to call at any time: on app start, after a
 * recording is processed, after a reboot, or when a setting changes.
 */
object Reminders {

    private const val TAG =
        "Reminders"

    /*
     * Without the exact-alarm permission Android may deliver a reminder a
     * little late; this is how late at most.
     */
    private const val INEXACT_WINDOW_MILLIS =
        10L * 60 * 1000

    fun sync(
        context: Context
    ) {

        val settings =
            AppSettings(context)

        val now =
            LocalDateTime.now()

        NoteStore(context).loadAll()
            .flatMap { it.notes }
            .filter { it.category == GeminiSummarizer.REMEMBER && it.hasDue }
            .forEach { note ->
                if (settings.remindersEnabled && !note.isDeleted) {
                    schedule(context, note, settings.earlyReminderMinutes, now)
                } else {
                    cancel(context, note.id)
                }
            }
    }

    /** Cancels every reminder of a note (it was done, deleted or turned off). */
    fun cancel(
        context: Context,
        noteId: String
    ) {
        ReminderTimes.Slot.values().forEach { slot ->
            alarmIntent(context, noteId, slot, PendingIntent.FLAG_NO_CREATE)?.let {
                alarms(context).cancel(it)
                it.cancel()
            }
        }
    }

    /**
     * True when reminders arrive on the minute. Without it (Android 12+,
     * until the user allows "Alarms & reminders") they can be up to
     * [INEXACT_WINDOW_MILLIS] late.
     */
    fun canBeExact(
        context: Context
    ): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms(context).canScheduleExactAlarms()

    private fun schedule(
        context: Context,
        note: Note,
        earlyMinutes: Int,
        now: LocalDateTime
    ) {

        val alerts =
            ReminderTimes.alerts(note.dueDate, note.dueTime, earlyMinutes, now)

        ReminderTimes.Slot.values().forEach { slot ->

            val alert =
                alerts.firstOrNull { it.slot == slot }

            if (alert == null) {
                // Already passed, or the early reminder was turned off.
                alarmIntent(context, note.id, slot, PendingIntent.FLAG_NO_CREATE)?.let {
                    alarms(context).cancel(it)
                    it.cancel()
                }
            } else {
                setAlarm(context, note.id, slot, alert.at)
            }
        }
    }

    private fun setAlarm(
        context: Context,
        noteId: String,
        slot: ReminderTimes.Slot,
        at: LocalDateTime
    ) {

        val trigger =
            at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val intent =
            alarmIntent(context, noteId, slot, PendingIntent.FLAG_UPDATE_CURRENT) ?: return

        val alarms =
            alarms(context)

        try {
            if (canBeExact(context)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent)
            } else {
                alarms.setWindow(AlarmManager.RTC_WAKEUP, trigger, INEXACT_WINDOW_MILLIS, intent)
            }
        } catch (e: SecurityException) {
            // The exact-alarm permission was taken away in the meantime.
            alarms.setWindow(AlarmManager.RTC_WAKEUP, trigger, INEXACT_WINDOW_MILLIS, intent)
        }

        Log.i(TAG, "Reminder set: $noteId $slot at $at")
    }

    private fun alarmIntent(
        context: Context,
        noteId: String,
        slot: ReminderTimes.Slot,
        flags: Int
    ): PendingIntent? {

        val intent =
            Intent(context, ReminderReceiver::class.java)
                .setAction(ReminderReceiver.ACTION_ALERT)
                // The data makes each note's and slot's alarm a separate one.
                .setData(
                    Uri.Builder()
                        .scheme("daytrace")
                        .authority("reminder")
                        .appendPath(noteId)
                        .appendPath(slot.name)
                        .build()
                )
                .putExtra(ReminderReceiver.EXTRA_NOTE_ID, noteId)
                .putExtra(ReminderReceiver.EXTRA_SLOT, slot.name)

        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun alarms(
        context: Context
    ): AlarmManager =
        context.getSystemService(AlarmManager::class.java)
}
