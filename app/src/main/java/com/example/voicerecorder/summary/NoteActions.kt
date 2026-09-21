package com.example.voicerecorder.summary

import android.content.Context
import com.example.voicerecorder.reminders.AppNotifications
import com.example.voicerecorder.reminders.Reminders

/**
 * What the user can do with a note, from the app or from a reminder.
 * Keeps the saved notes and the reminders in step: a note in the recycle
 * bin never reminds, and a restored note reminds again.
 */
object NoteActions {

    /** Deleted, or done ([done] = true): either way it goes to the recycle bin. */
    fun moveToBin(
        context: Context,
        noteId: String,
        done: Boolean
    ): Boolean {

        val moved =
            NoteStore(context).moveToBin(noteId, done)

        Reminders.cancel(context, noteId)
        AppNotifications.cancelReminder(context, noteId)

        return moved
    }

    fun restore(
        context: Context,
        noteId: String
    ): Boolean {

        val restored =
            NoteStore(context).restore(noteId)

        if (restored) {
            Reminders.sync(context)
        }

        return restored
    }

    fun deleteForever(
        context: Context,
        noteId: String
    ): Boolean {

        Reminders.cancel(context, noteId)

        return NoteStore(context).deleteForever(noteId)
    }

    /** Deletes everything in the recycle bin for good; returns how many notes. */
    fun emptyBin(
        context: Context
    ): Int =
        NoteStore(context).purgeBin(all = true)
            .onEach { Reminders.cancel(context, it) }
            .size

    /** Deletes the notes that have been in the recycle bin for 30 days. */
    fun purgeExpired(
        context: Context
    ): Int =
        NoteStore(context).purgeBin()
            .onEach { Reminders.cancel(context, it) }
            .size
}
