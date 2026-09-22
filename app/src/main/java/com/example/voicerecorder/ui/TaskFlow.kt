package com.example.voicerecorder.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.voicerecorder.R
import com.example.voicerecorder.google.EventSuggestion
import com.example.voicerecorder.google.GoogleConsent
import com.example.voicerecorder.google.GoogleException
import com.example.voicerecorder.google.GoogleIntegrationManager
import com.example.voicerecorder.google.GoogleService
import com.example.voicerecorder.google.GoogleSettings
import com.example.voicerecorder.google.PendingTaskAdd
import com.example.voicerecorder.google.TaskLink
import com.example.voicerecorder.google.calendar.EventDetector
import com.example.voicerecorder.google.tasks.GoogleTaskList
import com.example.voicerecorder.google.tasks.TaskAdder
import com.example.voicerecorder.google.tasks.TaskDraft
import com.example.voicerecorder.google.tasks.TaskDrafts
import com.example.voicerecorder.google.tasks.TaskSyncWorker
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * "Send to Google Tasks" for one note, the same from every screen:
 *
 * 1. the preview, "This looks like a to-do": the task, its deadline (only
 *    when the recording actually gave one), the list it goes to, and
 *    anything the user should check
 * 2. Add, Edit or Skip; the task is only created after Add
 * 3. on Add, connect Google Tasks first if needed (only its own
 *    permission)
 * 4. what happened: added, already there, waiting to go online, or a
 *    DayTrace-style error with the way forward
 *
 * A failure never changes the note: everything here writes only to the
 * Google integration's own files. Completing a note in DayTrace never
 * touches the task in Google Tasks.
 */
class TaskFlow(
    private val fragment: Fragment,
    private val onChanged: () -> Unit = {}
) {

    private val context: Context
        get() = fragment.requireContext()

    private val manager by lazy { GoogleIntegrationManager(fragment.requireContext()) }

    private fun launch(
        block: suspend CoroutineScope.() -> Unit
    ) {
        if (fragment.isAdded && fragment.view != null) {
            fragment.viewLifecycleOwner.lifecycleScope.launch(block = block)
        }
    }

    /** Where it starts: the card's suggestion, a note's menu, or the note screen. */
    fun start(
        entry: NoteEntry
    ): Unit = launch {

        val store =
            manager.store

        val (link, pending) =
            withContext(Dispatchers.IO) { store.taskLink(entry.id) to store.pendingTaskFor(entry.id) }

        val connected =
            manager.isConnected(GoogleService.TASKS)

        // The preview comes first; Google is only connected when the user taps Add.
        when {
            pending != null -> showPending(entry, pending)
            link != null && !connected -> showAlreadyAdded(entry, link)
            link != null -> checkExisting(entry, link)
            else -> prepare(entry, removed = false)
        }
    }

    /** "Skip": the suggestion goes away. The note itself is not changed. */
    fun skip(
        entry: NoteEntry
    ): Unit = launch {

        withContext(Dispatchers.IO) {
            val store = manager.store
            if (!store.skip(entry.id)) {
                store.saveSuggestions(
                    listOf(
                        EventSuggestion(
                            entry.id, EventSuggestion.KIND_OTHER, false, "", "",
                            "", "", "", "", 0, "", 0L, skipped = true
                        )
                    )
                )
            }
        }

        toast(R.string.task_skipped)
        changed()
    }

    // Connecting ------------------------------------------------------------

    /** Explains what is asked for, then shows Google's screens. */
    fun askToConnect(
        then: () -> Unit
    ) {
        DayTraceDialog(context)
            .icon(R.drawable.ic_check_circle)
            .title(R.string.google_tasks_connect_title)
            .message(
                context.getString(R.string.google_tasks_connect_detail) + "\n\n" +
                        context.getString(R.string.google_tasks_connect_note)
            )
            .primary(R.string.google_connect, arrow = true) { connectTasks(then) }
            .secondary(R.string.later)
            .show()
    }

    fun connectTasks(
        then: () -> Unit
    ): Unit = launch {

        val consent =
            (fragment.activity as? GoogleConsent.Host)?.googleConsent ?: return@launch

        try {
            manager.connect(GoogleService.TASKS, consent)
            changed()
            then()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showError(e, R.string.google_error_connect_title, retry = { connectTasks(then) })
        }
    }

    /** Google no longer allows access: ask again, then carry on. */
    fun reconnect(
        then: () -> Unit
    ): Unit = connectTasks {
        manager.resumePendingTasks()
        then()
    }

    // The preview -----------------------------------------------------------

    private suspend fun checkExisting(
        entry: NoteEntry,
        link: TaskLink
    ) {

        val result =
            whileBusy(R.string.task_checking) { manager.taskAdder().existing(entry.id) }

        result
            .onSuccess { existing ->
                if (existing != null) showAlreadyAdded(entry, existing) else prepare(entry, removed = true)
            }
            .onFailure { showError(it, R.string.google_error_task_title, retry = { start(entry) }) }
    }

    private suspend fun prepare(
        entry: NoteEntry,
        removed: Boolean
    ) {

        val known =
            withContext(Dispatchers.IO) { manager.store.suggestion(entry.id) }
                ?.takeIf { it.checkedAt > 0 }

        // Not looked at yet (e.g. an older note): ask Gemini now. If it
        // cannot answer, the preview says only the note itself was used.
        val suggestion =
            known ?: whileBusy(R.string.task_reading_note) { detectNow(entry) }.getOrNull()

        val draft =
            TaskStates(true, emptyMap(), emptyMap(), emptyMap(), manager.settings).draftFor(entry, suggestion)

        showPreview(entry, draft, removed = removed, edited = false)
    }

    private suspend fun detectNow(
        entry: NoteEntry
    ): EventSuggestion? =
        withTimeoutOrNull(DETECT_TIMEOUT_MS) {
            try {
                EventDetector(context.applicationContext)
                    .detect(listOf(entry.note), entry.recording.transcript, recordedAt(entry))
                    .firstOrNull()
                    ?.also { withContext(Dispatchers.IO) { manager.store.saveSuggestions(listOf(it)) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    private fun showPreview(
        entry: NoteEntry,
        draft: TaskDraft,
        removed: Boolean,
        edited: Boolean
    ) {

        val title =
            when {
                edited -> R.string.task_send_to
                draft.verdict == TaskDraft.Verdict.TASK -> R.string.task_preview_task
                draft.verdict == TaskDraft.Verdict.MAYBE_TASK -> R.string.task_preview_maybe
                draft.verdict == TaskDraft.Verdict.LOOKS_LIKE_EVENT -> R.string.task_preview_event
                else -> R.string.task_send_to
            }

        val notes =
            mutableListOf<String>()

        if (removed) {
            notes += context.getString(R.string.task_preview_removed)
        }

        if (!edited && draft.verdict == TaskDraft.Verdict.LOOKS_LIKE_EVENT) {
            notes += context.getString(R.string.task_preview_event_note)
        }

        val warnings =
            draft.checks.mapNotNull { TaskText.check(context, it) }.distinct()

        DayTraceDialog(context)
            .icon(R.drawable.ic_check_circle)
            .title(title)
            .message(notes.joinToString("\n\n").ifEmpty { null })
            .content(details(draft))
            .warning(warnings.joinToString("\n\n").ifEmpty { null })
            .primary(R.string.task_add) { add(entry, draft) }
            .secondary(R.string.task_edit) { edit(entry, draft, removed) }
            .extra(R.string.task_skip) { skip(entry) }
            .show()
    }

    /** Task, deadline, list and the note that goes with it, one row each. */
    private fun details(
        draft: TaskDraft
    ): View {

        val list =
            LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun row(@DrawableRes icon: Int, @StringRes label: Int, value: String, note: String? = null) {
            val row = LayoutInflater.from(context).inflate(R.layout.item_event_detail, list, false)
            row.findViewById<ImageView>(R.id.icon).setImageResource(icon)
            row.findViewById<TextView>(R.id.label).setText(label)
            row.findViewById<TextView>(R.id.value).text = value
            row.findViewById<TextView>(R.id.note).apply {
                text = note
                isVisible = !note.isNullOrBlank()
            }
            list.addView(row)
        }

        row(R.drawable.ic_text, R.string.task_label_title, draft.title)

        row(
            R.drawable.ic_calendar,
            R.string.task_label_deadline,
            TaskText.deadline(context, draft)
        )

        if (draft.context.isNotBlank() && draft.context != draft.title) {
            row(R.drawable.ic_document, R.string.task_label_note, draft.context)
        }

        row(R.drawable.ic_check_circle, R.string.task_label_list, TaskText.listName(context, draft))

        return list
    }

    // Edit ------------------------------------------------------------------

    /**
     * Editing a task is short enough for the DayTrace dialog itself: the
     * title, the deadline and the list, each opening its own picker.
     */
    private fun edit(
        entry: NoteEntry,
        original: TaskDraft,
        removed: Boolean
    ) {

        DayTraceDialog(context)
            .icon(R.drawable.ic_edit)
            .title(R.string.task_edit)
            .choices(
                listOf(
                    DayTraceDialog.Choice(
                        context.getString(R.string.task_label_title),
                        original.title,
                        R.drawable.ic_text
                    ),
                    DayTraceDialog.Choice(
                        context.getString(R.string.task_label_deadline),
                        TaskText.deadline(context, original),
                        R.drawable.ic_calendar
                    ),
                    DayTraceDialog.Choice(
                        context.getString(R.string.task_label_list),
                        TaskText.listName(context, original),
                        R.drawable.ic_check_circle
                    )
                ),
                null
            ) { index ->
                when (index) {

                    0 -> editTitle(entry, original, removed)

                    1 -> editDeadline(entry, original, removed)

                    2 -> chooseTaskList(original.taskListId) { list ->
                        showPreview(
                            entry,
                            original.copy(taskListId = list.id, taskListName = list.name),
                            removed,
                            edited = true
                        )
                    }
                }
            }
            .secondary(R.string.cancel) { showPreview(entry, original, removed, edited = false) }
            .show()
    }

    private fun editTitle(
        entry: NoteEntry,
        draft: TaskDraft,
        removed: Boolean
    ) {
        DayTraceDialog(context)
            .icon(R.drawable.ic_text)
            .title(R.string.task_label_title)
            .input(draft.title, R.string.task_field_title_hint) { typed ->
                showPreview(
                    entry,
                    draft.copy(title = typed.trim().ifEmpty { draft.title }),
                    removed,
                    edited = true
                )
            }
            .primary(R.string.calendar_edit_done)
            .secondary(R.string.cancel) { edit(entry, draft, removed) }
            .show()
    }

    /**
     * A deadline is never made up: the user either picks one or takes it
     * away. Google Tasks keeps only the day, so only a day is asked for.
     */
    private fun editDeadline(
        entry: NoteEntry,
        draft: TaskDraft,
        removed: Boolean
    ) {

        val picker =
            MaterialDatePicker.Builder.datePicker()
                .setTheme(R.style.DayTraceCalendar)
                .setTitleText(R.string.task_pick_deadline)
                .setSelection(
                    (draft.due ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                )
                .build()

        picker.addOnPositiveButtonClickListener { selection ->
            showPreview(
                entry,
                draft.copy(due = Instant.ofEpochMilli(selection).atZone(ZoneOffset.UTC).toLocalDate()),
                removed,
                edited = true
            )
        }

        picker.addOnNegativeButtonClickListener { edit(entry, draft, removed) }

        picker.show(fragment.childFragmentManager, "task_deadline")
    }

    /** The user's task lists; picking one calls [onPicked]. */
    fun chooseTaskList(
        currentId: String,
        onPicked: (GoogleTaskList) -> Unit
    ): Unit = launch {

        whileBusy(R.string.google_loading_task_lists) { manager.tasksApi().lists() }
            .onSuccess { lists ->
                DayTraceDialog(context)
                    .icon(R.drawable.ic_check_circle)
                    .title(R.string.google_choose_task_list_title)
                    .message(R.string.google_choose_task_list_message)
                    .choices(
                        lists.map {
                            DayTraceDialog.Choice(
                                it.name,
                                if (it.default) context.getString(R.string.google_task_list_default) else null
                            )
                        },
                        lists.indexOfFirst {
                            it.id == currentId || (it.default && currentId == GoogleSettings.DEFAULT_LIST)
                        }.takeIf { it >= 0 }
                    ) { index -> onPicked(lists[index]) }
                    .secondary(R.string.cancel)
                    .show()
            }
            .onFailure { showError(it, R.string.google_error_connect_title, retry = { chooseTaskList(currentId, onPicked) }) }
    }

    // Adding ----------------------------------------------------------------

    private fun add(
        entry: NoteEntry,
        chosen: TaskDraft
    ): Unit = launch {

        if (!manager.isConnected(GoogleService.TASKS)) {
            askToConnect { add(entry, chosen) }
            return@launch
        }

        // Made before Google Tasks was connected: "your main list" is now
        // known by its real id.
        val settings =
            manager.settings

        val draft =
            if (chosen.taskListId == GoogleSettings.DEFAULT_LIST && settings.taskListId != GoogleSettings.DEFAULT_LIST) {
                chosen.copy(taskListId = settings.taskListId, taskListName = settings.taskListName ?: chosen.taskListName)
            } else {
                chosen
            }

        val recordedText =
            Notes.formatDateTime(entry.time)

        val request =
            TaskAdder.Request(
                noteId = entry.id,
                taskListId = draft.taskListId,
                taskListName = TaskText.listName(context, draft),
                dueText = TaskText.deadlineOf(draft)
            ) { marker -> TaskDrafts.toJson(draft, marker, recordedText) }

        whileBusy(R.string.task_adding) { manager.taskAdder().add(request) }
            .onSuccess { outcome ->
                changed()
                when (outcome) {
                    is TaskAdder.Outcome.Added -> showAdded(draft, outcome.link)
                    is TaskAdder.Outcome.AlreadyAdded -> showAlreadyAdded(entry, outcome.link)
                    is TaskAdder.Outcome.Queued -> {
                        TaskSyncWorker.enqueue(context.applicationContext)
                        showQueued(outcome.reason)
                    }
                }
            }
            .onFailure { error ->
                showError(
                    error,
                    R.string.google_error_task_title,
                    retry = { add(entry, draft) },
                    listName = TaskText.listName(context, draft),
                    chooseList = {
                        chooseTaskList(draft.taskListId) { list ->
                            showPreview(
                                entry,
                                draft.copy(taskListId = list.id, taskListName = list.name),
                                removed = false,
                                edited = true
                            )
                        }
                    }
                )
            }
    }

    private fun showAdded(
        draft: TaskDraft,
        link: TaskLink
    ) {
        DayTraceDialog(context)
            .tone(DayTraceDialog.Tone.SUCCESS)
            .icon(R.drawable.ic_check_circle)
            .title(R.string.task_added_title)
            .message(
                if (link.dueText.isEmpty()) {
                    context.getString(R.string.task_added_detail, draft.title, link.taskListName)
                } else {
                    context.getString(R.string.task_added_detail_due, draft.title, link.taskListName, link.dueText)
                }
            )
            .primary(R.string.ok)
            .extra(R.string.task_open) { open(link) }
            .show()
    }

    fun showAlreadyAdded(
        entry: NoteEntry,
        link: TaskLink
    ) {
        DayTraceDialog(context)
            .tone(DayTraceDialog.Tone.SUCCESS)
            .icon(R.drawable.ic_check_circle)
            .title(R.string.task_already_title)
            .message(
                context.getString(
                    R.string.task_already_detail,
                    entry.note.title,
                    Notes.formatDate(Notes.dateOf(link.addedAt)),
                    link.taskListName
                )
            )
            .primary(R.string.ok)
            .extra(R.string.task_open) { open(link) }
            .show()
    }

    private fun showQueued(
        reason: GoogleException.Kind
    ) {
        DayTraceDialog(context)
            .tone(DayTraceDialog.Tone.INFO)
            .icon(R.drawable.ic_clock)
            .title(
                if (reason == GoogleException.Kind.OFFLINE) R.string.task_queued_offline_title
                else R.string.task_queued_busy_title
            )
            .message(R.string.task_queued_detail)
            .primary(R.string.ok)
            .show()
    }

    /** A task the user confirmed that is not in Google Tasks yet. */
    private fun showPending(
        entry: NoteEntry,
        pending: PendingTaskAdd
    ) {

        val dialog =
            DayTraceDialog(context)
                .extra(R.string.task_waiting_cancel, destructive = true) {
                    manager.cancelPendingTask(entry.id)
                    toast(R.string.task_waiting_cancelled)
                    changed()
                }
                .secondary(R.string.close)

        when (pending.state) {

            PendingTaskAdd.STATE_RECONNECT -> dialog
                .tone(DayTraceDialog.Tone.WARNING)
                .icon(R.drawable.ic_refresh)
                .title(R.string.google_error_reconnect_title)
                .message(context.getString(R.string.task_reconnect_pending_detail, entry.note.title))
                .primary(R.string.google_reconnect) { reconnect { changed() } }

            PendingTaskAdd.STATE_FAILED -> dialog
                .tone(DayTraceDialog.Tone.WARNING)
                .icon(R.drawable.ic_warning)
                .title(R.string.google_error_task_title)
                .message(context.getString(R.string.task_failed_pending_detail, entry.note.title))
                .primary(R.string.task_try_again) { manager.resumePendingTasks() }

            else -> dialog
                .tone(DayTraceDialog.Tone.INFO)
                .icon(R.drawable.ic_clock)
                .title(R.string.task_waiting_title)
                .message(context.getString(R.string.task_waiting_detail, entry.note.title))
                .primary(R.string.google_try_now) { manager.resumePendingTasks() }
        }

        dialog.show()
    }

    /** Opens the task in Google Tasks (or the browser). */
    fun open(
        link: TaskLink
    ) {

        val url =
            link.htmlLink.ifEmpty { "https://tasks.google.com/" }

        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.google_error_generic)
        }
    }

    // Errors ----------------------------------------------------------------

    /**
     * A DayTrace-style error that says what happened and offers the way
     * forward. Nothing was changed when this is shown.
     */
    fun showError(
        error: Throwable,
        @StringRes title: Int,
        retry: () -> Unit,
        listName: String? = null,
        chooseList: (() -> Unit)? = null
    ) {

        if (!fragment.isAdded) {
            return
        }

        val kind =
            (error as? GoogleException)?.kind ?: GoogleException.Kind.UNKNOWN

        val dialog =
            DayTraceDialog(context)
                .tone(DayTraceDialog.Tone.WARNING)
                .icon(R.drawable.ic_warning)
                .title(title)

        when (kind) {

            GoogleException.Kind.NEEDS_CONSENT -> dialog
                .icon(R.drawable.ic_refresh)
                .title(R.string.google_error_reconnect_title)
                .message(R.string.google_error_reconnect)
                .primary(R.string.google_reconnect) { reconnect(retry) }
                .secondary(R.string.later)

            GoogleException.Kind.DENIED -> dialog
                .title(R.string.google_error_denied_title)
                .message(R.string.google_error_denied)
                .primary(R.string.task_try_again) { retry() }
                .secondary(R.string.close)

            GoogleException.Kind.NOT_CONFIGURED -> dialog
                .title(R.string.google_error_not_configured_title)
                .message(R.string.google_error_not_configured)
                .primary(R.string.close)

            GoogleException.Kind.API_DISABLED -> dialog
                .title(R.string.google_error_api_disabled_title)
                .message(R.string.google_error_api_disabled)
                .primary(R.string.task_try_again) { retry() }
                .secondary(R.string.close)

            GoogleException.Kind.NOT_FOUND,
            GoogleException.Kind.FORBIDDEN ->
                if (chooseList != null) {
                    dialog
                        .title(R.string.google_error_task_list_title)
                        .message(context.getString(R.string.google_error_task_list, listName.orEmpty()))
                        .primary(R.string.google_choose_another) { chooseList() }
                        .secondary(R.string.close)
                } else {
                    dialog
                        .message(R.string.google_error_generic)
                        .primary(R.string.task_try_again) { retry() }
                        .secondary(R.string.close)
                }

            GoogleException.Kind.OFFLINE -> dialog
                .message(R.string.google_error_offline)
                .primary(R.string.task_try_again) { retry() }
                .secondary(R.string.close)

            GoogleException.Kind.QUOTA,
            GoogleException.Kind.SERVER -> dialog
                .message(R.string.google_error_quota)
                .primary(R.string.task_try_again) { retry() }
                .secondary(R.string.close)

            else -> dialog
                .message(R.string.google_error_generic)
                .primary(R.string.task_try_again) { retry() }
                .secondary(R.string.close)
        }

        dialog.show()
    }

    // Helpers ---------------------------------------------------------------

    /** Runs [block] with a "working on it" dialog; never swallows cancellation. */
    private suspend fun <T> whileBusy(
        @StringRes title: Int,
        block: suspend () -> T
    ): Result<T> {

        val busy =
            DayTraceDialog(context)
                .icon(R.drawable.ic_check_circle)
                .title(title)
                .message(R.string.please_wait)
                .busy()
                .show()

        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            busy.dismiss()
        }
    }

    private fun changed() {
        GoogleIntegrationManager.notifyChanged(context.applicationContext)
        onChanged()
    }

    private fun toast(
        @StringRes message: Int
    ) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    private fun recordedAt(
        entry: NoteEntry
    ) =
        Instant.ofEpochMilli(entry.time).atZone(ZoneId.systemDefault()).toLocalDateTime()

    private companion object {

        /** Reading one note takes Gemini a few seconds; after this the note alone is used. */
        const val DETECT_TIMEOUT_MS = 25_000L
    }
}
