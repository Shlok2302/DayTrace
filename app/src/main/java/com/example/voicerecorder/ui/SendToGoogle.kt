package com.example.voicerecorder.ui

import androidx.fragment.app.Fragment
import com.example.voicerecorder.MainActivity
import com.example.voicerecorder.R
import com.example.voicerecorder.google.GoogleIntegrationManager
import com.example.voicerecorder.google.GoogleService
import com.example.voicerecorder.summary.GeminiSummarizer

/**
 * "Send to Google" on a note's menu: one place that decides which of the
 * three destinations to offer, and which to suggest.
 *
 * - a Remember note that is a to-do: Google Tasks
 * - a Remember note that is something to attend: Google Calendar
 * - an Idea or a Thought: Google Docs
 *
 * The suggestion is only a suggestion; every destination the user has
 * connected is always offered, and nothing is sent until they confirm in
 * that destination's own flow.
 */
class SendToGoogle(
    private val fragment: Fragment,
    private val calendar: CalendarFlow,
    private val tasks: TaskFlow,
    private val docs: DocsFlow
) {

    private enum class Destination {
        CALENDAR,
        TASKS,
        DOCS
    }

    /**
     * [states] is what the screen already read; the menu is built from it
     * so nothing is read from disk on the main thread.
     */
    fun start(
        entry: NoteEntry,
        states: GoogleStates
    ) {

        val manager =
            GoogleIntegrationManager(fragment.requireContext())

        val available =
            Destination.values().filter {
                when (it) {
                    Destination.CALENDAR -> manager.isConnected(GoogleService.CALENDAR)
                    Destination.TASKS -> manager.isConnected(GoogleService.TASKS)
                    Destination.DOCS -> manager.isConnected(GoogleService.DOCS)
                }
            }

        when {
            available.isEmpty() -> showNothingConnected()
            available.size == 1 -> open(available.first(), entry)
            else -> choose(entry, available, suggested(entry, states))
        }
    }

    /**
     * What DayTrace thinks this note is. Only what was already worked out
     * is used: nothing is asked of Gemini here.
     */
    private fun suggested(
        entry: NoteEntry,
        states: GoogleStates
    ): Destination? {

        val category =
            entry.note.category

        if (category == GeminiSummarizer.REMEMBER) {

            val suggestion =
                states.calendar.suggestions[entry.id]?.takeIf { it.checkedAt > 0 }

            return when {
                suggestion == null -> Destination.TASKS
                suggestion.isEvent -> Destination.CALENDAR
                else -> Destination.TASKS
            }
        }

        return Destination.DOCS
    }

    private fun choose(
        entry: NoteEntry,
        available: List<Destination>,
        suggested: Destination?
    ) {

        val context =
            fragment.requireContext()

        // The suggested one first, so it is the easiest to reach.
        val ordered =
            available.sortedBy { if (it == suggested) 0 else 1 }

        val choices =
            ordered.map { destination ->

                val detail =
                    when (destination) {
                        Destination.CALENDAR -> R.string.send_to_google_calendar_detail
                        Destination.TASKS -> R.string.send_to_google_tasks_detail
                        Destination.DOCS -> R.string.send_to_google_docs_detail
                    }

                DayTraceDialog.Choice(
                    context.getString(label(destination)),
                    context.getString(detail) +
                            if (destination == suggested) " · " + context.getString(R.string.send_to_google_suggested) else "",
                    icon(destination)
                )
            }

        DayTraceDialog(context)
            .icon(R.drawable.ic_link)
            .title(R.string.send_to_google)
            .message(context.getString(R.string.send_to_google_message, entry.note.title))
            .choices(choices, null) { index -> open(ordered[index], entry) }
            .secondary(R.string.cancel)
            .show()
    }

    private fun open(
        destination: Destination,
        entry: NoteEntry
    ) {
        when (destination) {
            Destination.CALENDAR -> calendar.start(entry)
            Destination.TASKS -> tasks.start(entry)
            Destination.DOCS -> docs.start(entry)
        }
    }

    private fun showNothingConnected() {
        DayTraceDialog(fragment.requireContext())
            .tone(DayTraceDialog.Tone.INFO)
            .icon(R.drawable.ic_link)
            .title(R.string.send_to_google_none_title)
            .message(R.string.send_to_google_none_detail)
            .primary(R.string.send_to_google_open_settings) {
                (fragment.activity as? MainActivity)?.openSettings()
            }
            .secondary(R.string.close)
            .show()
    }

    private fun label(
        destination: Destination
    ): Int =
        when (destination) {
            Destination.CALENDAR -> R.string.google_calendar
            Destination.TASKS -> R.string.google_tasks
            Destination.DOCS -> R.string.google_docs
        }

    private fun icon(
        destination: Destination
    ): Int =
        when (destination) {
            Destination.CALENDAR -> R.drawable.ic_calendar
            Destination.TASKS -> R.drawable.ic_check_circle
            Destination.DOCS -> R.drawable.ic_document
        }
}
