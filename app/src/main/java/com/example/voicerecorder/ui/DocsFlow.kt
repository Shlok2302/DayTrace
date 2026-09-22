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
import com.example.voicerecorder.google.DocumentLink
import com.example.voicerecorder.google.GoogleConsent
import com.example.voicerecorder.google.GoogleException
import com.example.voicerecorder.google.GoogleIntegrationManager
import com.example.voicerecorder.google.GoogleService
import com.example.voicerecorder.google.docs.DocsAppender
import com.example.voicerecorder.google.docs.DocumentText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Send to Google Docs" for one note, the same from every screen:
 *
 * 1. pick the document it goes to: one DayTrace already made for this
 *    kind of note, or a new one
 * 2. the preview: what will be added, and where
 * 3. the note is only written after the user confirms
 * 4. what happened: added, already in that document, or a DayTrace-style
 *    error with the way forward
 *
 * A note is never written to the same document twice, and a new document
 * is never made for a note that could go in an existing one. A failure
 * never changes the note.
 */
class DocsFlow(
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

    /** Where it starts: the note's menu, or the note screen. */
    fun start(
        entry: NoteEntry
    ): Unit = launch {

        if (!manager.isConnected(GoogleService.DOCS)) {
            askToConnect { start(entry) }
            return@launch
        }

        chooseDocument(entry)
    }

    // Connecting ------------------------------------------------------------

    /** Explains what is asked for, then shows Google's screens. */
    fun askToConnect(
        then: () -> Unit
    ) {
        DayTraceDialog(context)
            .icon(R.drawable.ic_document)
            .title(R.string.google_docs_connect_title)
            .message(
                context.getString(R.string.google_docs_connect_detail) + "\n\n" +
                        context.getString(R.string.google_docs_connect_note)
            )
            .primary(R.string.google_connect, arrow = true) { connectDocs(then) }
            .secondary(R.string.later)
            .show()
    }

    fun connectDocs(
        then: () -> Unit
    ): Unit = launch {

        val consent =
            (fragment.activity as? GoogleConsent.Host)?.googleConsent ?: return@launch

        try {
            manager.connect(GoogleService.DOCS, consent)
            changed()
            then()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showError(e, R.string.google_error_connect_title, retry = { connectDocs(then) })
        }
    }

    fun reconnect(
        then: () -> Unit
    ): Unit = connectDocs(then)

    // Choosing the document --------------------------------------------------

    /**
     * The documents DayTrace made for this kind of note, and the choice
     * of making a new one. A note is never sent to a document it is
     * already in: that one is shown as such instead.
     */
    private suspend fun chooseDocument(
        entry: NoteEntry
    ) {

        val appender =
            manager.docsAppender()

        val category =
            entry.note.category

        var documents =
            withContext(Dispatchers.IO) { appender.documents(category) }

        /*
         * Nothing on this phone: it may be a reinstall rather than a first
         * run. Ask Google for the documents DayTrace made before offering
         * to make another one, so the user does not end up with two.
         */
        if (documents.isEmpty()) {

            val recovered =
                whileBusy(R.string.docs_looking) { manager.docsAppender().recoverDocuments() }

            recovered.onFailure {
                showError(it, R.string.google_error_docs_title, retry = { start(entry) })
                return
            }

            documents = withContext(Dispatchers.IO) { appender.documents(category) }
        }

        val already =
            withContext(Dispatchers.IO) {
                documents.filter { appender.alreadyAppended(entry.id, it.documentId) }.map { it.documentId }.toSet()
            }

        if (documents.isEmpty()) {
            offerFirstDocument(entry, category)
            return
        }

        val choices =
            documents.map { document ->
                DayTraceDialog.Choice(
                    document.title,
                    if (document.documentId in already) {
                        context.getString(R.string.docs_already_title)
                    } else {
                        context.getString(R.string.docs_made_on, Notes.formatDate(Notes.dateOf(document.createdAt)))
                    },
                    R.drawable.ic_document
                )
            } + DayTraceDialog.Choice(
                context.getString(R.string.docs_choose_new),
                null,
                R.drawable.ic_calendar_add
            )

        DayTraceDialog(context)
            .icon(R.drawable.ic_document)
            .title(R.string.docs_choose_title)
            .message(context.getString(R.string.docs_choose_message, entry.note.title))
            .choices(choices, null) { index ->
                if (index < documents.size) {
                    confirm(entry, documents[index])
                } else {
                    nameNewDocument(entry, category, suggestedTitle(category))
                }
            }
            .secondary(R.string.cancel)
            .show()
    }

    /** No document yet: offer to make the one this category belongs in. */
    private fun offerFirstDocument(
        entry: NoteEntry,
        category: String
    ) {
        DayTraceDialog(context)
            .icon(R.drawable.ic_document)
            .title(R.string.docs_no_documents_title)
            .message(R.string.docs_no_documents_detail)
            .primary(R.string.docs_choose_new, arrow = true) {
                nameNewDocument(entry, category, suggestedTitle(category))
            }
            .secondary(R.string.cancel)
            .show()
    }

    private fun nameNewDocument(
        entry: NoteEntry,
        category: String,
        suggested: String
    ) {
        DayTraceDialog(context)
            .icon(R.drawable.ic_document)
            .title(R.string.docs_new_title)
            .message(R.string.docs_new_message)
            .input(suggested, R.string.docs_new_hint) { typed ->
                createThenAppend(entry, category, typed.trim().ifEmpty { suggested })
            }
            .primary(R.string.docs_choose_new)
            .secondary(R.string.cancel)
            .show()
    }

    private fun createThenAppend(
        entry: NoteEntry,
        category: String,
        title: String
    ): Unit = launch {

        whileBusy(R.string.docs_creating) { manager.docsAppender().createDocument(title, category) }
            .onSuccess { document ->
                changed()
                confirm(entry, document)
            }
            .onFailure {
                showError(it, R.string.google_error_docs_title, retry = { createThenAppend(entry, category, title) })
            }
    }

    // Confirming and sending -------------------------------------------------

    /** The last look before anything is written. */
    private fun confirm(
        entry: NoteEntry,
        document: DocumentLink
    ) {
        DayTraceDialog(context)
            .icon(R.drawable.ic_document)
            .title(R.string.docs_preview_title)
            .content(details(entry, document))
            .primary(R.string.docs_send) { append(entry, document) }
            .secondary(R.string.cancel)
            .show()
    }

    /** The document it goes to, and the entry exactly as it will be written. */
    private fun details(
        entry: NoteEntry,
        document: DocumentLink
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

        row(R.drawable.ic_document, R.string.docs_label_document, document.title)

        row(
            R.drawable.ic_text,
            R.string.docs_label_entry,
            entry.note.title,
            // The recording's own date and time, never the time of writing.
            entry.note.category + " · " + Notes.formatDateTime(entry.time)
        )

        return list
    }

    private fun append(
        entry: NoteEntry,
        document: DocumentLink
    ): Unit = launch {

        val request =
            DocsAppender.Request(
                noteId = entry.id,
                documentId = document.documentId,
                entry = DocumentText.Entry(
                    category = entry.note.category,
                    recordedText = Notes.formatDateTime(entry.time),
                    title = entry.note.title,
                    body = entry.note.text,
                    tags = entry.note.tags
                )
            )

        whileBusy(R.string.docs_appending) { manager.docsAppender().append(request) }
            .onSuccess { outcome ->
                changed()
                when (outcome) {
                    is DocsAppender.Outcome.Appended -> showAppended(entry, outcome.document)
                    is DocsAppender.Outcome.AlreadyThere -> showAlreadyThere(entry, outcome.document)
                    is DocsAppender.Outcome.DocumentGone -> showGone(entry, document)
                }
            }
            .onFailure {
                showError(it, R.string.google_error_docs_title, retry = { append(entry, document) })
            }
    }

    private fun showAppended(
        entry: NoteEntry,
        document: DocumentLink
    ) {
        DayTraceDialog(context)
            .tone(DayTraceDialog.Tone.SUCCESS)
            .icon(R.drawable.ic_check_circle)
            .title(R.string.docs_added_title)
            .message(context.getString(R.string.docs_added_detail, entry.note.title, document.title))
            .primary(R.string.ok)
            .extra(R.string.docs_open) { open(document) }
            .show()
    }

    private fun showAlreadyThere(
        entry: NoteEntry,
        document: DocumentLink
    ) {
        DayTraceDialog(context)
            .tone(DayTraceDialog.Tone.SUCCESS)
            .icon(R.drawable.ic_check_circle)
            .title(R.string.docs_already_title)
            .message(context.getString(R.string.docs_already_detail, entry.note.title, document.title))
            .primary(R.string.ok)
            .extra(R.string.docs_open) { open(document) }
            .show()
    }

    /** The document was deleted in Google Drive; DayTrace has forgotten it. */
    private fun showGone(
        entry: NoteEntry,
        document: DocumentLink
    ) {
        DayTraceDialog(context)
            .tone(DayTraceDialog.Tone.WARNING)
            .icon(R.drawable.ic_warning)
            .title(R.string.docs_gone_title)
            .message(context.getString(R.string.docs_gone_detail, document.title))
            .primary(R.string.docs_choose_new) {
                nameNewDocument(entry, entry.note.category, suggestedTitle(entry.note.category))
            }
            .secondary(R.string.close)
            .show()
    }

    fun open(
        document: DocumentLink
    ) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://docs.google.com/document/d/${document.documentId}/edit"))
            )
        } catch (e: ActivityNotFoundException) {
            toast(R.string.google_error_generic)
        }
    }

    // Errors ----------------------------------------------------------------

    fun showError(
        error: Throwable,
        @StringRes title: Int,
        retry: () -> Unit
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

    /** The document DayTrace suggests for this kind of note. */
    private fun suggestedTitle(
        category: String
    ): String =
        DocumentText.SUGGESTED_TITLES[category] ?: DocumentText.SUGGESTED_TITLES.getValue("")

    private suspend fun <T> whileBusy(
        @StringRes title: Int,
        block: suspend () -> T
    ): Result<T> {

        val busy =
            DayTraceDialog(context)
                .icon(R.drawable.ic_document)
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
}
