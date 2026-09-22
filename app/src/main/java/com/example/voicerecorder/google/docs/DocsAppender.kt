package com.example.voicerecorder.google.docs

import com.example.voicerecorder.google.DocumentEntry
import com.example.voicerecorder.google.DocumentLink
import com.example.voicerecorder.google.GoogleIntegrationStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Appends a note to one of the Google documents DayTrace made, at most
 * once per document.
 *
 * A document can be edited by anyone it is shared with, so DayTrace does
 * not trust a position in it. Every entry ends with a mark worked out
 * from the note ([DocumentMarkers]); before appending, the document's own
 * text is searched for that mark. That covers a lost answer, a
 * reinstall, and the same note being sent twice by hand.
 *
 * The DayTrace note is never read or written here.
 */
class DocsAppender(
    private val api: DocsApi,
    private val store: GoogleIntegrationStore,
    private val now: () -> Long = System::currentTimeMillis
) {

    sealed class Outcome {

        data class Appended(val document: DocumentLink) : Outcome()

        /** The note is already in this document: nothing was written. */
        data class AlreadyThere(val document: DocumentLink) : Outcome()

        /** The document is gone in Google Docs; DayTrace has forgotten it. */
        object DocumentGone : Outcome()
    }

    /** One note to append, as the user confirmed it. */
    class Request(
        val noteId: String,
        val documentId: String,
        val entry: DocumentText.Entry
    )

    /**
     * Makes a new DayTrace document and remembers it. [category] is what
     * it collects ("Idea", "Thoughts", ...), so the next note of that
     * category can be offered the same document.
     */
    suspend fun createDocument(
        title: String,
        category: String
    ): DocumentLink {

        val created =
            api.create(title)

        val document =
            DocumentLink(
                documentId = created.documentId,
                title = created.title.ifEmpty { title },
                category = category,
                createdAt = now()
            )

        store.saveDocument(document)

        return document
    }

    /** The documents DayTrace made that still exist, newest offer first. */
    fun documents(
        category: String? = null
    ): List<DocumentLink> =
        store.documents().filter { category == null || it.category == category || it.category.isEmpty() }

    /**
     * Finds the documents DayTrace made that this phone does not know
     * about, and remembers them again. That happens after a reinstall or
     * a cleared app storage: without it the user would be offered a new
     * document and end up with a second "DayTrace Ideas".
     *
     * Only ever sees the documents DayTrace itself created (drive.file).
     * Returns how many were recovered.
     */
    suspend fun recoverDocuments(): Int {

        val known =
            store.documents().map { it.documentId }.toSet()

        val found =
            api.listDayTraceDocuments().filterNot { it.documentId in known }

        found.forEach { drive ->
            store.saveDocument(
                DocumentLink(
                    documentId = drive.documentId,
                    title = drive.title,
                    // Which category it collects is not in Drive: it is
                    // offered for every category until the user picks it.
                    category = "",
                    createdAt = drive.createdAt.takeIf { it > 0 } ?: now()
                )
            )
        }

        return found.size
    }

    suspend fun append(
        request: Request
    ): Outcome {

        val document =
            store.document(request.documentId)
                ?: return Outcome.DocumentGone

        val live =
            api.get(request.documentId)

        if (live == null) {
            // Deleted in Google Docs, or access removed: stop offering it.
            store.removeDocument(request.documentId)
            store.removeDocumentEntries(request.documentId)
            return Outcome.DocumentGone
        }

        val marker =
            DocumentMarkers.forNote(request.noteId)

        /*
         * The document itself decides: its text is the one place that
         * knows what is really in it. This is what makes a retry after a
         * lost answer, or a reinstall, safe.
         */
        if (live.text.contains(marker)) {
            rememberAppended(request)
            return Outcome.AlreadyThere(document)
        }

        /*
         * The phone may still think this note is in the document while the
         * document says otherwise (someone deleted that part). The document
         * wins: the note is written again, and the entry below is simply
         * overwritten — the other notes' entries are left alone.
         */
        api.batchUpdate(
            request.documentId,
            DocumentText.requests(request.entry, marker, live.appendIndex)
        )

        rememberAppended(request)

        // The title may have been renamed in Google Docs since.
        val named =
            document.copy(title = live.title.ifEmpty { document.title })

        store.saveDocument(named)

        return Outcome.Appended(named)
    }

    /** True when this note is already in this document, as far as the phone knows. */
    fun alreadyAppended(
        noteId: String,
        documentId: String
    ): Boolean =
        store.documentEntry(noteId, documentId) != null

    private fun rememberAppended(
        request: Request
    ) {
        store.saveDocumentEntry(
            DocumentEntry(
                noteId = request.noteId,
                documentId = request.documentId,
                appendedAt = now()
            )
        )
    }
}

/**
 * How one note looks inside a DayTrace document, and the Google Docs
 * requests that write it.
 *
 * Every entry is laid out the same way and appended at the end, so the
 * document reads in the order the notes were recorded and the newest one
 * is always last:
 *
 *     Idea · 22 Sep 2026, 05:12 PM      (small, above the title)
 *     An AI study planner                (a heading, so it shows in the outline)
 *     The note's own words.
 *     Tags: study, app
 *     [DayTrace:1a2b3c]                  (small; how DayTrace knows it is there)
 */
object DocumentText {

    class Entry(
        val category: String,
        /** When the recording was made, already formatted. */
        val recordedText: String,
        val title: String,
        val body: String,
        val tags: List<String>
    )

    /** A heading level that stands out without competing with the document's own title. */
    private const val HEADING = "HEADING_3"

    private const val SMALL_POINTS = 9.0

    private const val MARKER_POINTS = 7.0

    fun requests(
        entry: Entry,
        marker: String,
        appendIndex: Int
    ): JSONArray {

        val header =
            listOf(entry.category, entry.recordedText).filter { it.isNotBlank() }.joinToString(" · ")

        val tags =
            entry.tags.filter { it.isNotBlank() }

        /*
         * One insert, then the styles: every range below is worked out
         * from this text, so the order the pieces are written in here is
         * the order the offsets follow.
         */
        val pieces =
            mutableListOf<Pair<String, String>>()

        pieces += "header" to header
        pieces += "title" to entry.title.trim()

        entry.body.trim().takeIf { it.isNotEmpty() && it != entry.title.trim() }?.let {
            pieces += "body" to it
        }

        if (tags.isNotEmpty()) {
            pieces += "tags" to tags.joinToString(", ")
        }

        pieces += "marker" to marker

        val text =
            pieces.joinToString("") { it.second + "\n" }

        val requests =
            JSONArray().put(
                JSONObject().put(
                    "insertText",
                    JSONObject()
                        .put("location", JSONObject().put("index", appendIndex))
                        .put("text", text)
                )
            )

        var offset = appendIndex

        pieces.forEach { (name, value) ->

            val start = offset
            val end = start + value.length

            when (name) {

                "title" -> requests.put(paragraphStyle(start, end, HEADING))

                "header" -> requests.put(textStyle(start, end, SMALL_POINTS, grey = true))

                "tags" -> requests.put(textStyle(start, end, SMALL_POINTS, grey = true))

                "marker" -> requests.put(textStyle(start, end, MARKER_POINTS, grey = true))
            }

            // The newline each piece ends with.
            offset = end + 1
        }

        return requests
    }

    private fun paragraphStyle(
        start: Int,
        end: Int,
        namedStyle: String
    ): JSONObject =
        JSONObject().put(
            "updateParagraphStyle",
            JSONObject()
                .put("range", range(start, end))
                .put("paragraphStyle", JSONObject().put("namedStyleType", namedStyle))
                .put("fields", "namedStyleType")
        )

    private fun textStyle(
        start: Int,
        end: Int,
        points: Double,
        grey: Boolean
    ): JSONObject {

        val style =
            JSONObject().put(
                "fontSize",
                JSONObject().put("magnitude", points).put("unit", "PT")
            )

        val fields =
            StringBuilder("fontSize")

        if (grey) {
            style.put(
                "foregroundColor",
                JSONObject().put(
                    "color",
                    JSONObject().put(
                        "rgbColor",
                        JSONObject().put("red", 0.45).put("green", 0.45).put("blue", 0.45)
                    )
                )
            )
            fields.append(",foregroundColor")
        }

        return JSONObject().put(
            "updateTextStyle",
            JSONObject()
                .put("range", range(start, end))
                .put("textStyle", style)
                .put("fields", fields.toString())
        )
    }

    private fun range(
        start: Int,
        end: Int
    ): JSONObject =
        JSONObject().put("startIndex", start).put("endIndex", end)

    /** The documents DayTrace suggests, by what they collect. */
    val SUGGESTED_TITLES =
        linkedMapOf(
            "Idea" to "DayTrace Ideas",
            "Thoughts" to "DayTrace Thoughts",
            "" to "DayTrace Projects"
        )
}
