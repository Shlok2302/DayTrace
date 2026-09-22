package com.example.voicerecorder.google.docs

import com.example.voicerecorder.google.GoogleException
import com.example.voicerecorder.google.GoogleRestClient
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant

/** A Google document DayTrace made, as far as it needs to know it. */
data class GoogleDocument(
    val documentId: String,
    val title: String,
    /**
     * Where new text goes: just before the end of the body. Google keeps
     * a final newline that cannot be written after, so text is inserted
     * at (end - 1).
     */
    val appendIndex: Int,
    /** The document's text, used to see which notes are already in it. */
    val text: String
) {

    val htmlLink: String
        get() = "https://docs.google.com/document/d/$documentId/edit"
}

/**
 * A document DayTrace made, as Google Drive lists it. Used to find them
 * again after a reinstall, when nothing is left on the phone.
 */
data class DriveDocument(
    val documentId: String,
    val title: String,
    val createdAt: Long
)

/** The Google Docs requests DayTrace makes. Tests use a fake. */
interface DocsApi {

    suspend fun create(
        title: String
    ): GoogleDocument

    /**
     * The documents DayTrace itself made, straight from Google Drive.
     *
     * With the "drive.file" scope Google only ever lists the files this
     * app created, so this can never see the rest of the user's Drive.
     * It is how the documents are found again after a reinstall, instead
     * of making a second copy of each one.
     */
    suspend fun listDayTraceDocuments(): List<DriveDocument>

    /** Null when the document is gone (deleted, or access was removed). */
    suspend fun get(
        documentId: String
    ): GoogleDocument?

    /** Appends [requests] (Google Docs batchUpdate requests) to the document. */
    suspend fun batchUpdate(
        documentId: String,
        requests: JSONArray
    )
}

/** Google Docs API v1. */
class RestDocsApi(
    private val rest: GoogleRestClient
) : DocsApi {

    override suspend fun create(
        title: String
    ): GoogleDocument =
        toDocument(rest.post("$BASE/documents", JSONObject().put("title", title)))

    override suspend fun listDayTraceDocuments(): List<DriveDocument> {

        val documents =
            mutableListOf<DriveDocument>()

        var pageToken: String? = null

        do {
            val page =
                rest.get(
                    "$DRIVE/files" +
                            "?q=" + GoogleRestClient.encode(
                        "mimeType='application/vnd.google-apps.document' and trashed=false"
                    ) +
                            "&fields=" + GoogleRestClient.encode("nextPageToken,files(id,name,createdTime)") +
                            "&pageSize=100" +
                            pageToken?.let { "&pageToken=${GoogleRestClient.encode(it)}" }.orEmpty()
                )

            val files =
                page.optJSONArray("files")

            for (index in 0 until (files?.length() ?: 0)) {

                val file =
                    files?.optJSONObject(index) ?: continue

                val id =
                    file.optString("id")

                if (id.isEmpty()) {
                    continue
                }

                documents += DriveDocument(
                    documentId = id,
                    title = file.optString("name"),
                    createdAt = runCatching { Instant.parse(file.optString("createdTime")).toEpochMilli() }
                        .getOrDefault(0L)
                )
            }

            pageToken = page.optString("nextPageToken").ifEmpty { null }

        } while (pageToken != null)

        return documents.sortedBy { it.createdAt }
    }

    override suspend fun get(
        documentId: String
    ): GoogleDocument? =
        try {
            toDocument(rest.get("$BASE/documents/${GoogleRestClient.encode(documentId)}"))
        } catch (e: GoogleException) {
            if (e.kind == GoogleException.Kind.NOT_FOUND) null else throw e
        }

    override suspend fun batchUpdate(
        documentId: String,
        requests: JSONArray
    ) {
        rest.post(
            "$BASE/documents/${GoogleRestClient.encode(documentId)}:batchUpdate",
            JSONObject().put("requests", requests)
        )
    }

    /**
     * Reads the document's end and its text out of Google's answer. Only
     * paragraph text is collected: that is all DayTrace writes, and all
     * it needs to recognise the notes already in the document.
     */
    private fun toDocument(
        json: JSONObject
    ): GoogleDocument {

        val content =
            json.optJSONObject("body")?.optJSONArray("content") ?: JSONArray()

        val text =
            StringBuilder()

        var end = 1

        for (index in 0 until content.length()) {

            val element =
                content.optJSONObject(index) ?: continue

            end = maxOf(end, element.optInt("endIndex", end))

            val elements =
                element.optJSONObject("paragraph")?.optJSONArray("elements") ?: continue

            for (part in 0 until elements.length()) {
                elements.optJSONObject(part)
                    ?.optJSONObject("textRun")
                    ?.optString("content")
                    ?.let(text::append)
            }
        }

        return GoogleDocument(
            documentId = json.getString("documentId"),
            title = json.optString("title"),
            // Google's body always ends with a newline that cannot be written after.
            appendIndex = (end - 1).coerceAtLeast(1),
            text = text.toString()
        )
    }

    private companion object {

        const val BASE =
            "https://docs.googleapis.com/v1"

        /** Only ever lists the files DayTrace made (the "drive.file" scope). */
        const val DRIVE =
            "https://www.googleapis.com/drive/v3"
    }
}

/**
 * The hidden mark DayTrace writes at the end of every entry it appends.
 *
 * A document can be edited by anyone the user shares it with, so the
 * mark, not a position, is what says "this note is already in here". It
 * is worked out from the note, so the same note always makes the same
 * mark.
 */
object DocumentMarkers {

    private const val PREFIX = "[DayTrace:"

    private const val SUFFIX = "]"

    fun forNote(
        noteId: String
    ): String {

        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest("daytrace-doc|$noteId".toByteArray(Charsets.UTF_8))

        return PREFIX + digest.take(6).joinToString("") { "%02x".format(it) } + SUFFIX
    }
}
