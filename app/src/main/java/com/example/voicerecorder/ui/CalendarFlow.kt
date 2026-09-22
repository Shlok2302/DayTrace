package com.example.voicerecorder.ui

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import com.example.voicerecorder.google.CalendarLink
import com.example.voicerecorder.google.EventSuggestion
import com.example.voicerecorder.google.GoogleConsent
import com.example.voicerecorder.google.GoogleException
import com.example.voicerecorder.google.GoogleIntegrationManager
import com.example.voicerecorder.google.GoogleService
import com.example.voicerecorder.google.GoogleSettings
import com.example.voicerecorder.google.PendingCalendarAdd
import com.example.voicerecorder.google.calendar.CalendarAdder
import com.example.voicerecorder.google.calendar.EventCheck
import com.example.voicerecorder.google.calendar.EventDetector
import com.example.voicerecorder.google.calendar.EventDraft
import com.example.voicerecorder.google.calendar.EventDrafts
import com.example.voicerecorder.google.calendar.GoogleCalendar
import com.example.voicerecorder.google.calendar.GoogleSyncWorker
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * "Add to Google Calendar" for one note, the same from every screen:
 *
 * 1. the preview, "We think this is a calendar event": title, date,
 *    time, place, and anything the user should check
 * 2. Add, Edit or Skip; the event is only created after Add
 * 3. on Add, connect Google Calendar first if needed (only its own
 *    permissions)
 * 4. what happened: added, already there, waiting to go online, or a
 *    DayTrace-style error with the way forward
 *
 * A failure never changes the note: everything here writes only to the
 * Google integration's own files.
 */
class CalendarFlow(
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
            withContext(Dispatchers.IO) { store.calendarLink(entry.id) to store.pendingFor(entry.id) }

        val connected =
            manager.isConnected(GoogleService.CALENDAR)

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
                // Never checked by Gemini: remember the skip anyway.
                store.saveSuggestions(listOf(EventSuggestion(entry.id, EventSuggestion.KIND_OTHER, false, "", "", "", "", "", "", 0, "", 0L, skipped = true)))
            }
        }

        toast(R.string.calendar_skipped)
        changed()
    }

    // Connecting ------------------------------------------------------------

    /** Explains what is asked for, then shows Google's screens. */
    fun askToConnect(
        then: () -> Unit
    ) {
        DayTraceDialog(context)
            .icon(R.drawable.ic_calendar)
            .title(R.string.google_calendar_connect_title)
            .message(context.getString(R.string.google_calendar_connect_detail) + "\n\n" + context.getString(R.string.google_calendar_connect_note))
            .primary(R.string.google_connect, arrow = true) { connectCalendar(then) }
            .secondary(R.string.later)
            .show()
    }

    fun connectCalendar(
        then: () -> Unit
    ): Unit = launch {

        val consent =
            (fragment.activity as? GoogleConsent.Host)?.googleConsent ?: return@launch

        try {
            manager.connect(GoogleService.CALENDAR, consent)
            changed()
            then()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showError(e, R.string.google_error_connect_title, retry = { connectCalendar(then) })
        }
    }

    /** Google no longer allows access: ask again, then carry on. */
    fun reconnect(
        then: () -> Unit
    ): Unit = connectCalendar {
        manager.resumePending()
        then()
    }

    // The preview -----------------------------------------------------------

    private suspend fun checkExisting(
        entry: NoteEntry,
        link: CalendarLink
    ) {

        val result =
            whileBusy(R.string.calendar_checking) { manager.calendarAdder().existing(entry.id) }

        result
            .onSuccess { existing ->
                if (existing != null) showAlreadyAdded(entry, existing) else prepare(entry, removed = true)
            }
            .onFailure { showError(it, R.string.google_error_add_title, retry = { start(entry) }) }
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
            known ?: whileBusy(R.string.calendar_reading_note) { detectNow(entry) }.getOrNull()

        val draft =
            CalendarStates(true, emptyMap(), emptyMap(), emptyMap(), manager.settings).draftFor(entry, suggestion)

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
        draft: EventDraft,
        removed: Boolean,
        edited: Boolean
    ) {

        val title =
            when {
                edited -> R.string.calendar_add_to
                draft.verdict == EventDraft.Verdict.EVENT -> R.string.calendar_preview_event
                draft.verdict == EventDraft.Verdict.MAYBE_EVENT -> R.string.calendar_preview_maybe
                draft.verdict == EventDraft.Verdict.NOT_EVENT -> R.string.calendar_preview_task
                else -> R.string.calendar_add_to
            }

        val warnings =
            draft.checks.mapNotNull { CalendarText.check(context, it, draft) }.distinct()

        val dialog =
            DayTraceDialog(context)
                .icon(R.drawable.ic_calendar_add)
                .title(title)
                .message(if (removed) context.getString(R.string.calendar_preview_removed) else null)
                .content(details(draft))
                .warning(warnings.joinToString("\n\n").ifEmpty { null })
                .secondary(R.string.calendar_edit) { edit(entry, draft, removed) }
                .extra(R.string.calendar_skip) { skip(entry) }

        // Never a day that was not said: without one, the user picks it first.
        if (draft.date != null) {
            dialog.primary(R.string.calendar_add) { add(entry, draft) }
        } else {
            dialog.primary(R.string.calendar_choose_date) { edit(entry, draft, removed) }
        }

        dialog.show()
    }

    /** Title, date, time, place, calendar and reminder, one row each. */
    private fun details(
        draft: EventDraft
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

        row(R.drawable.ic_text, R.string.calendar_label_title, draft.title)

        row(
            R.drawable.ic_calendar,
            R.string.calendar_label_date,
            draft.date?.let { CalendarText.fullDay(context, it) } ?: context.getString(R.string.calendar_no_date)
        )

        val timeNote =
            when {
                draft.allDay ->
                    draft.checks.filterIsInstance<EventCheck.PartOfDayOnly>().firstOrNull()
                        ?.let { context.getString(R.string.calendar_part_of_day_note, it.part) }
                        ?: context.getString(R.string.calendar_no_time_note)
                !draft.lengthSaid ->
                    context.getString(R.string.calendar_default_length_note, CalendarText.length(context, draft.lengthMinutes))
                else -> null
            }

        row(R.drawable.ic_clock, R.string.calendar_label_time, CalendarText.hours(context, draft), timeNote)

        if (draft.location.isNotBlank()) {
            row(R.drawable.ic_location, R.string.calendar_label_place, draft.location)
        }

        row(R.drawable.ic_account, R.string.calendar_label_calendar, CalendarText.calendarName(context, draft))
        row(R.drawable.ic_bell, R.string.calendar_label_reminder, CalendarText.reminderFor(context, draft))

        return list
    }

    // Edit ------------------------------------------------------------------

    private fun edit(
        entry: NoteEntry,
        original: EventDraft,
        removed: Boolean
    ) {

        var draft =
            original

        val dialog =
            Dialog(context, R.style.DayTraceDialog)

        val view =
            LayoutInflater.from(context).inflate(R.layout.dialog_calendar_event, null)

        val titleInput =
            view.findViewById<EditText>(R.id.inputTitle).apply { setText(draft.title) }

        val locationInput =
            view.findViewById<EditText>(R.id.inputLocation).apply { setText(draft.location) }

        val rowDate = view.findViewById<View>(R.id.rowDate)
        val rowAllDay = view.findViewById<View>(R.id.rowAllDay)
        val rowStart = view.findViewById<View>(R.id.rowStart)
        val rowEnd = view.findViewById<View>(R.id.rowEnd)
        val rowCalendar = view.findViewById<View>(R.id.rowCalendar)
        val rowReminder = view.findViewById<View>(R.id.rowReminder)

        val allDayToggle =
            rowAllDay.findViewById<ToggleView>(R.id.toggle)

        fun set(row: View, @DrawableRes icon: Int, @StringRes label: Int, value: String) {
            row.findViewById<ImageView>(R.id.icon).setImageResource(icon)
            row.findViewById<TextView>(R.id.label).setText(label)
            row.findViewById<TextView>(R.id.value).text = value
        }

        fun render() {

            set(rowDate, R.drawable.ic_calendar, R.string.calendar_label_date,
                draft.date?.let { CalendarText.fullDay(context, it) } ?: context.getString(R.string.calendar_field_not_set))

            set(rowAllDay, R.drawable.ic_clock, R.string.calendar_all_day,
                context.getString(if (draft.allDay) R.string.calendar_field_all_day_on else R.string.calendar_field_all_day_off))

            rowStart.isVisible = !draft.allDay
            rowEnd.isVisible = !draft.allDay

            draft.start?.let { start ->
                set(rowStart, R.drawable.ic_clock, R.string.calendar_field_start, CalendarText.time(start))
                val end = CalendarText.end(context, draft) ?: CalendarText.time(start)
                set(rowEnd, R.drawable.ic_clock, R.string.calendar_field_end,
                    if (draft.lengthSaid) end
                    else context.getString(R.string.calendar_default_length_note, end))
            }

            set(rowCalendar, R.drawable.ic_account, R.string.calendar_label_calendar, CalendarText.calendarName(context, draft))
            set(rowReminder, R.drawable.ic_bell, R.string.calendar_label_reminder, CalendarText.reminderFor(context, draft))
        }

        rowAllDay.findViewById<View>(R.id.chevron).isVisible = false
        allDayToggle.isVisible = true
        allDayToggle.setChecked(draft.allDay, animate = false)

        allDayToggle.onCheckedChange = { allDay ->
            if (allDay) {
                draft = draft.copy(start = null, end = null)
                render()
            } else {
                // A time is never made up: the user picks it, or it stays all day.
                pickTime(R.string.calendar_pick_start, LocalTime.of(LocalTime.now().hour, 0), onCancel = {
                    allDayToggle.setChecked(true, animate = true)
                }) { time ->
                    draft = draft.copy(start = time, end = null)
                    render()
                }
            }
        }

        rowAllDay.setOnClickListener { allDayToggle.toggle() }

        rowDate.setOnClickListener {
            pickDate(draft.date ?: LocalDate.now()) { date ->
                draft = draft.copy(date = date)
                render()
            }
        }

        rowStart.setOnClickListener {
            pickTime(R.string.calendar_pick_start, draft.start ?: LocalTime.NOON) { time ->
                draft = draft.copy(start = time, end = draft.end?.takeIf { it.isAfter(time) })
                render()
            }
        }

        rowEnd.setOnClickListener {
            val start = draft.start ?: return@setOnClickListener
            pickTime(R.string.calendar_pick_end, draft.endDateTime()?.toLocalTime() ?: start.plusHours(1)) { time ->
                draft = draft.copy(end = time, lengthSaid = true)
                render()
            }
        }

        rowCalendar.setOnClickListener {
            chooseCalendar(draft.calendarId) { calendar ->
                draft = draft.copy(calendarId = calendar.id, calendarName = calendar.name)
                render()
            }
        }

        rowReminder.setOnClickListener {
            chooseReminder(draft.reminderMinutes) { minutes ->
                draft = draft.copy(reminderMinutes = minutes)
                render()
            }
        }

        view.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
            showPreview(entry, original, removed, edited = false)
        }

        view.findViewById<View>(R.id.btnSave).setOnClickListener {

            // The user has now seen and set everything: only a missing day is still flagged.
            val edited =
                draft.copy(
                    title = titleInput.text.toString().trim().ifEmpty { entry.note.title },
                    location = locationInput.text.toString().trim(),
                    checks = if (draft.date == null) listOf(EventCheck.NoDay) else emptyList()
                )

            dialog.dismiss()
            showPreview(entry, edited, removed, edited = true)
        }

        render()

        view.findViewById<View>(R.id.card).clipToOutline = true

        dialog.setContentView(view)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun pickDate(
        initial: LocalDate,
        onPicked: (LocalDate) -> Unit
    ) {

        val picker =
            MaterialDatePicker.Builder.datePicker()
                .setTheme(R.style.DayTraceCalendar)
                .setTitleText(R.string.calendar_pick_date)
                .setSelection(initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                .build()

        picker.addOnPositiveButtonClickListener { selection ->
            onPicked(Instant.ofEpochMilli(selection).atZone(ZoneOffset.UTC).toLocalDate())
        }

        picker.show(fragment.childFragmentManager, "calendar_date")
    }

    private fun pickTime(
        @StringRes title: Int,
        initial: LocalTime,
        onCancel: () -> Unit = {},
        onPicked: (LocalTime) -> Unit
    ) {

        val picker =
            MaterialTimePicker.Builder()
                .setTheme(R.style.DayTraceClock)
                .setTitleText(title)
                .setTimeFormat(if (DateFormat.is24HourFormat(context)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
                .setHour(initial.hour)
                .setMinute(initial.minute)
                .build()

        var picked = false

        picker.addOnPositiveButtonClickListener {
            picked = true
            onPicked(LocalTime.of(picker.hour, picker.minute))
        }

        picker.addOnDismissListener { if (!picked) onCancel() }

        picker.show(fragment.childFragmentManager, "calendar_time")
    }

    /** The calendars the user owns; picking one calls [onPicked]. */
    fun chooseCalendar(
        currentId: String,
        onPicked: (GoogleCalendar) -> Unit
    ): Unit = launch {

        whileBusy(R.string.google_loading_calendars) { manager.calendarApi().calendars() }
            .onSuccess { calendars ->
                DayTraceDialog(context)
                    .icon(R.drawable.ic_calendar)
                    .title(R.string.google_choose_calendar_title)
                    .message(R.string.google_choose_calendar_message)
                    .choices(
                        calendars.map {
                            DayTraceDialog.Choice(it.name, if (it.primary) context.getString(R.string.google_calendar_primary) else null)
                        },
                        calendars.indexOfFirst { it.id == currentId || (it.primary && currentId == GoogleSettings.PRIMARY) }
                            .takeIf { it >= 0 }
                    ) { index -> onPicked(calendars[index]) }
                    .secondary(R.string.cancel)
                    .show()
            }
            .onFailure { showError(it, R.string.google_error_connect_title, retry = { chooseCalendar(currentId, onPicked) }) }
    }

    fun chooseReminder(
        current: Int,
        onPicked: (Int) -> Unit
    ) {

        val choices =
            GoogleSettings.REMINDER_CHOICES

        DayTraceDialog(context)
            .icon(R.drawable.ic_bell)
            .title(R.string.google_event_reminders)
            .message(R.string.google_reminders_message)
            .choices(choices.map { DayTraceDialog.Choice(CalendarText.reminder(context, it)) }, choices.indexOf(current).takeIf { it >= 0 }) {
                onPicked(choices[it])
            }
            .secondary(R.string.cancel)
            .show()
    }

    // Adding ----------------------------------------------------------------

    private fun add(
        entry: NoteEntry,
        chosen: EventDraft
    ): Unit = launch {

        if (!manager.isConnected(GoogleService.CALENDAR)) {
            askToConnect { add(entry, chosen) }
            return@launch
        }

        // Made before Google Calendar was connected: "your main calendar"
        // is now known by its real id (the event id depends on it).
        val settings =
            manager.settings

        val draft =
            if (chosen.calendarId == GoogleSettings.PRIMARY && settings.calendarId != GoogleSettings.PRIMARY) {
                chosen.copy(calendarId = settings.calendarId, calendarName = settings.calendarName ?: chosen.calendarName)
            } else {
                chosen
            }

        val recordedText =
            Notes.formatDateTime(entry.time)

        val request =
            CalendarAdder.Request(
                noteId = entry.id,
                calendarId = draft.calendarId,
                calendarName = draft.calendarName,
                whenText = CalendarText.whenText(context, draft)
            ) { eventId -> EventDrafts.toJson(draft, eventId, ZoneId.systemDefault(), recordedText) }

        whileBusy(R.string.calendar_adding) { manager.calendarAdder().add(request) }
            .onSuccess { outcome ->
                changed()
                when (outcome) {
                    is CalendarAdder.Outcome.Added -> showAdded(draft, outcome.link)
                    is CalendarAdder.Outcome.AlreadyAdded -> showAlreadyAdded(entry, outcome.link)
                    is CalendarAdder.Outcome.Queued -> {
                        GoogleSyncWorker.enqueue(context.applicationContext)
                        showQueued(outcome.reason)
                    }
                }
            }
            .onFailure { error ->
                showError(
                    error,
                    R.string.google_error_add_title,
                    retry = { add(entry, draft) },
                    calendarName = draft.calendarName,
                    chooseCalendar = {
                        chooseCalendar(draft.calendarId) { calendar ->
                            showPreview(entry, draft.copy(calendarId = calendar.id, calendarName = calendar.name), removed = false, edited = true)
                        }
                    }
                )
            }
    }

    private fun showAdded(
        draft: EventDraft,
        link: CalendarLink
    ) {
        DayTraceDialog(context)
            .tone(DayTraceDialog.Tone.SUCCESS)
            .icon(R.drawable.ic_check_circle)
            .title(R.string.calendar_added_title)
            .message(context.getString(R.string.calendar_added_detail, draft.title, link.whenText, link.calendarName))
            .primary(R.string.ok)
            .extra(R.string.calendar_open) { open(link) }
            .show()
    }

    fun showAlreadyAdded(
        entry: NoteEntry,
        link: CalendarLink
    ) {
        DayTraceDialog(context)
            .tone(DayTraceDialog.Tone.SUCCESS)
            .icon(R.drawable.ic_check_circle)
            .title(R.string.calendar_already_title)
            .message(
                context.getString(
                    R.string.calendar_already_detail,
                    entry.note.title,
                    Notes.formatDate(Notes.dateOf(link.addedAt)),
                    link.whenText,
                    link.calendarName
                )
            )
            .primary(R.string.ok)
            .extra(R.string.calendar_open) { open(link) }
            .show()
    }

    private fun showQueued(
        reason: GoogleException.Kind
    ) {
        DayTraceDialog(context)
            .tone(DayTraceDialog.Tone.INFO)
            .icon(R.drawable.ic_clock)
            .title(
                if (reason == GoogleException.Kind.OFFLINE) R.string.calendar_queued_offline_title
                else R.string.calendar_queued_busy_title
            )
            .message(R.string.calendar_queued_detail)
            .primary(R.string.ok)
            .show()
    }

    /** An event the user confirmed that is not in Google Calendar yet. */
    private fun showPending(
        entry: NoteEntry,
        pending: PendingCalendarAdd
    ) {

        val dialog =
            DayTraceDialog(context)
                .extra(R.string.calendar_waiting_cancel, destructive = true) {
                    manager.cancelPending(entry.id)
                    toast(R.string.calendar_waiting_cancelled)
                    changed()
                }
                .secondary(R.string.close)

        when (pending.state) {

            PendingCalendarAdd.STATE_RECONNECT -> dialog
                .tone(DayTraceDialog.Tone.WARNING)
                .icon(R.drawable.ic_refresh)
                .title(R.string.calendar_card_reconnect)
                .message(context.getString(R.string.calendar_reconnect_pending_detail, entry.note.title))
                .primary(R.string.google_reconnect) { reconnect { changed() } }

            PendingCalendarAdd.STATE_FAILED -> dialog
                .tone(DayTraceDialog.Tone.WARNING)
                .icon(R.drawable.ic_warning)
                .title(R.string.google_error_add_title)
                .message(context.getString(R.string.calendar_failed_pending_detail, entry.note.title))
                .primary(R.string.calendar_try_again) { manager.resumePending() }

            else -> dialog
                .tone(DayTraceDialog.Tone.INFO)
                .icon(R.drawable.ic_clock)
                .title(R.string.calendar_waiting_title)
                .message(context.getString(R.string.calendar_waiting_detail, entry.note.title, pending.whenText))
                .primary(R.string.google_try_now) { manager.resumePending() }
        }

        dialog.show()
    }

    /** Opens the event in the Google Calendar app (or the browser). */
    fun open(
        link: CalendarLink
    ) {

        val intent =
            if (link.htmlLink.isNotEmpty()) {
                Intent(Intent.ACTION_VIEW, Uri.parse(link.htmlLink))
            } else {
                Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build())
            }

        try {
            context.startActivity(intent)
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
        calendarName: String? = null,
        chooseCalendar: (() -> Unit)? = null
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
                .primary(R.string.calendar_try_again) { retry() }
                .secondary(R.string.close)

            GoogleException.Kind.NOT_CONFIGURED -> dialog
                .title(R.string.google_error_not_configured_title)
                .message(R.string.google_error_not_configured)
                .primary(R.string.close)

            GoogleException.Kind.API_DISABLED -> dialog
                .title(R.string.google_error_api_disabled_title)
                .message(R.string.google_error_api_disabled)
                .primary(R.string.calendar_try_again) { retry() }
                .secondary(R.string.close)

            GoogleException.Kind.NOT_FOUND,
            GoogleException.Kind.FORBIDDEN ->
                if (chooseCalendar != null) {
                    dialog
                        .title(R.string.google_error_calendar_title)
                        .message(context.getString(R.string.google_error_calendar, calendarName.orEmpty()))
                        .primary(R.string.google_choose_another) { chooseCalendar() }
                        .secondary(R.string.close)
                } else {
                    dialog
                        .message(R.string.google_error_generic)
                        .primary(R.string.calendar_try_again) { retry() }
                        .secondary(R.string.close)
                }

            GoogleException.Kind.OFFLINE -> dialog
                .message(R.string.google_error_offline)
                .primary(R.string.calendar_try_again) { retry() }
                .secondary(R.string.close)

            GoogleException.Kind.QUOTA,
            GoogleException.Kind.SERVER -> dialog
                .message(R.string.google_error_quota)
                .primary(R.string.calendar_try_again) { retry() }
                .secondary(R.string.close)

            else -> dialog
                .message(R.string.google_error_generic)
                .primary(R.string.calendar_try_again) { retry() }
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
                .icon(R.drawable.ic_calendar)
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
