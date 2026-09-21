package com.example.voicerecorder.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.voicerecorder.R
import com.example.voicerecorder.summary.GeminiSummarizer
import com.example.voicerecorder.summary.Note
import com.example.voicerecorder.summary.NoteActions
import com.example.voicerecorder.summary.NoteStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.concurrent.thread

/**
 * Builds the note cards used by the history, day and recycle bin screens.
 */
object NoteCards {

    /**
     * The compact row under the calendar: icon, title, preview, time.
     * [onChanged] is called after the note was deleted or marked as done.
     */
    fun historyCard(
        inflater: LayoutInflater,
        parent: ViewGroup,
        entry: NoteEntry,
        onOpen: (NoteEntry) -> Unit,
        onChanged: () -> Unit = {}
    ): View {

        val view =
            inflater.inflate(R.layout.item_note_history, parent, false)

        val style =
            Categories.of(entry.note.category)

        view.findViewById<View>(R.id.iconHolder).setBackgroundResource(style.circle)

        view.findViewById<ImageView>(R.id.imgCategory).apply {
            setImageResource(style.icon)
            imageTintList = ContextCompat.getColorStateList(context, style.color)
        }

        view.findViewById<TextView>(R.id.tvTitle).text = entry.note.title
        view.findViewById<TextView>(R.id.tvPreview).text = entry.note.text
        view.findViewById<TextView>(R.id.tvTime).text = Notes.formatTime(entry.time)

        view.setOnClickListener { onOpen(entry) }

        view.findViewById<ImageButton>(R.id.btnMore).setOnClickListener { anchor ->
            showMenu(anchor, entry, onOpen, onChanged)
        }

        return view
    }

    /**
     * The full card on the day screen: category pill, time, title, text,
     * tags, and the deadline of a Remember note.
     */
    fun dayCard(
        inflater: LayoutInflater,
        parent: ViewGroup,
        entry: NoteEntry,
        onOpen: (NoteEntry) -> Unit,
        onChanged: () -> Unit = {}
    ): View {

        val view =
            fullCard(inflater, parent, entry)

        if (isReminder(entry.note)) {
            showStatus(view, R.drawable.ic_bell, dueText(view.context, entry.note))
        }

        view.setOnClickListener { onOpen(entry) }

        view.findViewById<ImageButton>(R.id.btnMore).setOnClickListener { anchor ->
            showMenu(anchor, entry, onOpen, onChanged)
        }

        return view
    }

    /**
     * A note in the recycle bin: the same card, with when it was deleted
     * and how long it stays. Its menu restores it or deletes it for good.
     */
    fun binCard(
        inflater: LayoutInflater,
        parent: ViewGroup,
        entry: NoteEntry,
        onOpen: (NoteEntry) -> Unit,
        onChanged: () -> Unit
    ): View {

        val view =
            fullCard(inflater, parent, entry)

        showStatus(view, R.drawable.ic_trash, binStatus(view.context, entry.note))

        view.setOnClickListener { onOpen(entry) }

        view.findViewById<ImageButton>(R.id.btnMore).setOnClickListener { anchor ->

            PopupMenu(anchor.context, anchor).apply {

                menu.add(0, 1, 0, R.string.restore)
                menu.add(0, 2, 1, R.string.delete_forever)

                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> restore(anchor, entry, onChanged)
                        2 -> deleteForever(anchor, entry, onChanged)
                    }
                    true
                }

            }.show()
        }

        return view
    }

    private fun fullCard(
        inflater: LayoutInflater,
        parent: ViewGroup,
        entry: NoteEntry
    ): View {

        val view =
            inflater.inflate(R.layout.item_note_day, parent, false)

        val style =
            Categories.of(entry.note.category)

        view.setBackgroundResource(style.card)
        view.findViewById<View>(R.id.iconHolder).setBackgroundResource(style.circle)

        view.findViewById<ImageView>(R.id.imgCategory).apply {
            setImageResource(style.icon)
            imageTintList = ContextCompat.getColorStateList(context, style.color)
        }

        view.findViewById<TextView>(R.id.tvCategory).apply {
            text = style.name
            setBackgroundResource(style.pill)
            setTextColor(ContextCompat.getColor(context, style.color))
        }

        view.findViewById<TextView>(R.id.tvTime).text = Notes.formatTime(entry.time)
        view.findViewById<TextView>(R.id.tvTitle).text = entry.note.title
        view.findViewById<TextView>(R.id.tvBody).text = entry.note.text

        bindTags(inflater, view.findViewById(R.id.tags), entry.note.tags)

        return view
    }

    private fun showStatus(
        card: View,
        icon: Int,
        text: String
    ) {
        card.findViewById<View>(R.id.statusRow).isVisible = true
        card.findViewById<ImageView>(R.id.imgStatus).setImageResource(icon)
        card.findViewById<TextView>(R.id.tvStatus).text = text
    }

    fun bindTags(
        inflater: LayoutInflater,
        container: ViewGroup,
        tags: List<String>
    ) {

        container.removeAllViews()
        container.isVisible = tags.isNotEmpty()

        tags.forEach { tag ->

            val view =
                inflater.inflate(R.layout.item_tag, container, false) as TextView

            view.text = container.context.getString(R.string.tag_format, tag)
            container.addView(view)
        }
    }

    /** A Remember note with a deadline. */
    fun isReminder(
        note: Note
    ): Boolean =
        note.category == GeminiSummarizer.REMEMBER && note.hasDue

    /** "Due today, 08:00 PM", or "Was due ..." once it has passed. */
    fun dueText(
        context: Context,
        note: Note
    ): String =
        context.getString(
            if (Notes.isOverdue(note)) R.string.was_due_label else R.string.due_label,
            Notes.formatDue(note)
        )

    /** "Completed 2 days ago · deleted for good in 28 days" */
    fun binStatus(
        context: Context,
        note: Note
    ): String {

        val resources =
            context.resources

        val daysAgo =
            ChronoUnit.DAYS.between(Notes.dateOf(note.deletedAt), LocalDate.now()).toInt()

        val whenText =
            when (daysAgo) {
                0 -> context.getString(R.string.when_today)
                1 -> context.getString(R.string.when_yesterday)
                else -> resources.getQuantityString(R.plurals.when_days_ago, daysAgo, daysAgo)
            }

        val what =
            context.getString(
                if (note.doneAt > 0) R.string.bin_status_done else R.string.bin_status_deleted,
                whenText
            )

        val millisLeft =
            note.deletedAt + NoteStore.BIN_DAYS * NoteStore.DAY_MILLIS - System.currentTimeMillis()

        val daysLeft =
            ((millisLeft + NoteStore.DAY_MILLIS - 1) / NoteStore.DAY_MILLIS).toInt().coerceAtLeast(1)

        return context.getString(
            R.string.bin_status,
            what,
            resources.getQuantityString(R.plurals.bin_days_left, daysLeft, daysLeft)
        )
    }

    private fun showMenu(
        anchor: View,
        entry: NoteEntry,
        onOpen: (NoteEntry) -> Unit,
        onChanged: () -> Unit
    ) {

        val context =
            anchor.context

        PopupMenu(context, anchor).apply {

            menu.add(0, 1, 0, R.string.open_note)
            menu.add(0, 2, 1, R.string.copy_text)

            if (entry.note.category == GeminiSummarizer.REMEMBER) {
                menu.add(0, 3, 2, R.string.mark_done)
            }

            menu.add(0, 4, 3, R.string.delete)

            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> onOpen(entry)
                    2 -> copy(context, entry.note.text)
                    3 -> run(anchor, R.string.marked_done, onChanged) {
                        NoteActions.moveToBin(it, entry.id, done = true)
                    }
                    4 -> run(anchor, R.string.moved_to_bin, onChanged) {
                        NoteActions.moveToBin(it, entry.id, done = false)
                    }
                }
                true
            }

        }.show()
    }

    fun restore(
        anchor: View,
        entry: NoteEntry,
        onChanged: () -> Unit
    ) {
        run(anchor, R.string.restored, onChanged) { NoteActions.restore(it, entry.id) }
    }

    /** Asks first: a note deleted for good cannot come back. */
    fun deleteForever(
        anchor: View,
        entry: NoteEntry,
        onChanged: () -> Unit
    ) {
        MaterialAlertDialogBuilder(anchor.context)
            .setTitle(R.string.delete_forever_confirm_title)
            .setMessage(R.string.delete_forever_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_forever) { _, _ ->
                run(anchor, R.string.deleted_forever, onChanged) { NoteActions.deleteForever(it, entry.id) }
            }
            .show()
    }

    /**
     * Runs a note action off the main thread (it writes the notes file),
     * then shows [message] and calls [onChanged] on the main thread.
     */
    fun run(
        anchor: View,
        @StringRes message: Int,
        onChanged: () -> Unit,
        action: (Context) -> Boolean
    ) {

        val context =
            anchor.context.applicationContext

        thread {

            val changed =
                runCatching { action(context) }.getOrDefault(false)

            anchor.post {
                if (changed) {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    onChanged()
                }
            }
        }
    }

    private fun copy(
        context: Context,
        text: String
    ) {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        clipboard.setPrimaryClip(ClipData.newPlainText("note", text))

        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
