package com.example.voicerecorder.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.voicerecorder.R
import com.example.voicerecorder.google.GoogleIntegrationManager
import com.example.voicerecorder.google.PendingCalendarAdd
import com.example.voicerecorder.reminders.AppNotifications
import com.example.voicerecorder.reminders.ReminderTimes
import com.example.voicerecorder.settings.AppSettings
import com.example.voicerecorder.summary.NoteActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * One note: category, title, when it was recorded, the summary with its
 * tags, its reminder (Remember notes with a deadline), and what happened
 * to the original audio. A note in the recycle bin can be restored or
 * deleted for good from here.
 */
class NoteDetailFragment : Fragment(R.layout.fragment_note_detail) {

    private val calendarFlow by lazy { CalendarFlow(this) { view?.let(::load) } }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        load(view)

        // An event added in the background shows at once.
        GoogleIntegrationManager.observe(requireContext(), viewLifecycleOwner) { this.view?.let(::load) }
    }

    private fun load(
        view: View
    ) {

        val noteId =
            arguments?.getString(ARG_NOTE_ID).orEmpty()

        viewLifecycleOwner.lifecycleScope.launch {

            val loaded =
                withContext(Dispatchers.IO) { Notes.recordings(requireContext()) }

            val entry =
                Notes.entry(loaded, noteId) ?: return@launch

            val (audioExists, calendar) =
                withContext(Dispatchers.IO) {
                    audioExists(entry.recording.audioUri) to CalendarStates.load(requireContext())
                }

            bind(view, entry, audioExists, calendar)
        }
    }

    private fun bind(
        view: View,
        entry: NoteEntry,
        audioExists: Boolean,
        calendar: CalendarStates
    ) {

        val style =
            Categories.of(entry.note.category)

        view.findViewById<View>(R.id.categoryPill).setBackgroundResource(style.pill)

        view.findViewById<ImageView>(R.id.imgCategory).apply {
            setImageResource(style.icon)
            imageTintList = ContextCompat.getColorStateList(requireContext(), style.color)
        }

        view.findViewById<TextView>(R.id.tvCategory).apply {
            text = style.name
            setTextColor(ContextCompat.getColor(requireContext(), style.color))
        }

        view.findViewById<TextView>(R.id.tvTitle).text = entry.note.title
        view.findViewById<TextView>(R.id.tvDateTime).text = Notes.formatDateTime(entry.time)
        view.findViewById<TextView>(R.id.tvSummary).text = entry.note.text

        NoteCards.bindTags(
            LayoutInflater.from(requireContext()),
            view.findViewById(R.id.tags),
            entry.note.tags
        )

        view.findViewById<TextView>(R.id.tvAudioStatus).text =
            audioStatus(entry.recording, audioExists)

        bindBin(view, entry)
        bindReminder(view, entry)
        bindCalendar(view, entry, calendar)

        view.findViewById<View>(R.id.btnMore).setOnClickListener { anchor ->
            showMenu(anchor, entry)
        }
    }

    /** Restore / Delete forever, for a note in the recycle bin. */
    private fun bindBin(
        view: View,
        entry: NoteEntry
    ) {

        val inBin =
            entry.note.isDeleted

        view.findViewById<View>(R.id.binCard).isVisible = inBin

        if (!inBin) {
            return
        }

        view.findViewById<TextView>(R.id.tvBinStatus).text =
            NoteCards.binStatus(requireContext(), entry.note)

        view.findViewById<View>(R.id.btnRestore).setOnClickListener {
            NoteCards.restore(it, entry) { if (this.view != null) load(view) }
        }

        view.findViewById<View>(R.id.btnDeleteForever).setOnClickListener {
            NoteCards.deleteForever(it, entry) { close() }
        }
    }

    /**
     * When it is due, when the reminders come (or why they will not), and
     * "Mark as done", which moves it to the recycle bin.
     */
    private fun bindReminder(
        view: View,
        entry: NoteEntry
    ) {

        val note =
            entry.note

        val show =
            NoteCards.isReminder(note) && !note.isDeleted

        view.findViewById<View>(R.id.reminderCard).isVisible = show

        if (!show) {
            return
        }

        view.findViewById<TextView>(R.id.tvDue).text = NoteCards.dueText(requireContext(), note)

        val settings =
            AppSettings(requireContext())

        val alerts =
            ReminderTimes.alerts(note.dueDate, note.dueTime, settings.earlyReminderMinutes, LocalDateTime.now())
                .map { Notes.formatTime(Notes.millisOf(it.at)) }

        view.findViewById<TextView>(R.id.tvReminderInfo).text =
            when {
                !settings.remindersEnabled -> getString(R.string.reminders_off)
                alerts.isEmpty() -> getString(R.string.reminders_none_left)
                !AppNotifications.canPost(requireContext()) -> getString(R.string.reminders_blocked)
                alerts.size == 1 -> getString(R.string.reminders_at_one, alerts[0])
                else -> getString(R.string.reminders_at_two, alerts[0], alerts[1])
            }

        view.findViewById<View>(R.id.btnMarkDone).setOnClickListener {
            NoteCards.run(it, R.string.marked_done, ::close) { context ->
                NoteActions.moveToBin(context, entry.id, done = true)
            }
        }
    }

    /**
     * Google Calendar: the event DayTrace suggests (Review and add / Skip),
     * the event that was added, or one that is waiting to be sent.
     */
    private fun bindCalendar(
        view: View,
        entry: NoteEntry,
        calendar: CalendarStates
    ) {

        val card = view.findViewById<View>(R.id.calendarCard)
        val status = view.findViewById<TextView>(R.id.tvCalendarStatus)
        val detail = view.findViewById<TextView>(R.id.tvCalendarDetail)
        val button = view.findViewById<TextView>(R.id.btnCalendar)
        val skip = view.findViewById<View>(R.id.btnCalendarSkip)

        val link = calendar.links[entry.id]
        val pending = calendar.pending[entry.id]
        val suggested = calendar.suggestedDraft(entry)

        card.isVisible = !entry.note.isDeleted && (link != null || pending != null || suggested != null)
        skip.isVisible = false

        if (!card.isVisible) {
            return
        }

        when {
            link != null -> {
                status.setText(R.string.calendar_card_added)
                detail.text = getString(R.string.calendar_card_added_detail, link.whenText, link.calendarName)
                button.setText(R.string.calendar_open)
                button.setOnClickListener { calendarFlow.open(link) }
            }

            pending != null -> {
                when (pending.state) {
                    PendingCalendarAdd.STATE_RECONNECT -> {
                        status.setText(R.string.calendar_card_reconnect)
                        detail.setText(R.string.calendar_card_reconnect_detail)
                        button.setText(R.string.google_reconnect)
                    }
                    PendingCalendarAdd.STATE_FAILED -> {
                        status.setText(R.string.google_error_add_title)
                        detail.setText(R.string.calendar_card_failed_detail)
                        button.setText(R.string.calendar_try_again)
                    }
                    else -> {
                        status.setText(R.string.calendar_waiting_title)
                        detail.setText(R.string.calendar_card_waiting_detail)
                        button.setText(R.string.google_try_now)
                    }
                }
                button.setOnClickListener { calendarFlow.start(entry) }
            }

            suggested != null -> {
                status.setText(
                    if (suggested.verdict == com.example.voicerecorder.google.calendar.EventDraft.Verdict.EVENT) {
                        R.string.calendar_preview_event
                    } else {
                        R.string.calendar_preview_maybe
                    }
                )
                detail.text =
                    listOf(
                        CalendarText.short(requireContext(), suggested).ifEmpty { getString(R.string.calendar_no_date) },
                        suggested.location
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                button.setText(R.string.calendar_review)
                button.setOnClickListener { calendarFlow.start(entry) }
                skip.isVisible = true
                skip.setOnClickListener { calendarFlow.skip(entry) }
            }
        }
    }

    private fun showMenu(
        anchor: View,
        entry: NoteEntry
    ) {

        android.widget.PopupMenu(requireContext(), anchor).apply {

            menu.add(0, 1, 0, R.string.copy_text)

            if (entry.note.isDeleted) {
                menu.add(0, 2, 1, R.string.restore)
                menu.add(0, 3, 2, R.string.delete_forever)
            } else {
                if (entry.note.category == com.example.voicerecorder.summary.GeminiSummarizer.REMEMBER) {
                    menu.add(0, 5, 2, R.string.calendar_add_to)
                }
                menu.add(0, 4, 3, R.string.delete)
            }

            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> copyToClipboard(entry)
                    2 -> NoteCards.restore(anchor, entry) { view?.let(::load) }
                    3 -> NoteCards.deleteForever(anchor, entry, ::close)
                    4 -> NoteCards.run(anchor, R.string.moved_to_bin, ::close) { context ->
                        NoteActions.moveToBin(context, entry.id, done = false)
                    }
                    5 -> calendarFlow.start(entry)
                }
                true
            }

        }.show()
    }

    /** Back to the list the note was opened from. */
    private fun close() {
        if (isAdded) {
            parentFragmentManager.popBackStack()
        }
    }

    private fun copyToClipboard(
        entry: NoteEntry
    ) {
        val clipboard =
            requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager

        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("note", entry.note.text)
        )

        android.widget.Toast
            .makeText(requireContext(), R.string.copied, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    /**
     * Where the audio is. An imported file was never touched (only
     * DayTrace's copy is deleted); a restored note's audio stayed on the
     * phone the backup came from.
     */
    private fun audioStatus(
        recording: com.example.voicerecorder.summary.SavedRecording,
        audioExists: Boolean
    ): String {

        val status =
            when {
                recording.importedFrom != null ->
                    getString(R.string.audio_imported_detail, recording.importedFrom)
                recording.audioUri.isEmpty() ->
                    getString(R.string.audio_restored_detail)
                audioExists ->
                    getString(R.string.audio_kept_detail)
                else ->
                    getString(R.string.audio_deleted_detail)
            }

        // The time was not found in the file: say where it came from.
        val time =
            when (recording.recordedAtSource) {
                com.example.voicerecorder.summary.NoteStore.SOURCE_FILE_DATE -> getString(R.string.recorded_at_file_date)
                com.example.voicerecorder.summary.NoteStore.SOURCE_CHOSEN -> getString(R.string.recorded_at_chosen)
                else -> null
            }

        return if (time == null) status else "$status\n\n$time"
    }

    /**
     * The audio is normally deleted once the notes are saved; a failure
     * keeps it, and then this says so.
     */
    private fun audioExists(
        audioUri: String
    ): Boolean =
        runCatching {
            requireContext().contentResolver
                .query(Uri.parse(audioUri), null, null, null, null)
                ?.use { it.moveToFirst() }
                ?: false
        }.getOrDefault(false)

    companion object {

        private const val ARG_NOTE_ID =
            "note_id"

        fun forNote(
            noteId: String
        ): NoteDetailFragment =
            NoteDetailFragment().apply {
                arguments = Bundle().apply { putString(ARG_NOTE_ID, noteId) }
            }
    }
}
