package com.example.voicerecorder.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.voicerecorder.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One note: category, title, when it was recorded, the summary with its
 * tags, and what happened to the original audio.
 */
class NoteDetailFragment : Fragment(R.layout.fragment_note_detail) {

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val noteId =
            arguments?.getString(ARG_NOTE_ID).orEmpty()

        viewLifecycleOwner.lifecycleScope.launch {

            val loaded =
                withContext(Dispatchers.IO) { Notes.recordings(requireContext()) }

            val entry =
                Notes.entry(loaded, noteId) ?: return@launch

            val audioExists =
                withContext(Dispatchers.IO) { audioExists(entry.recording.audioUri) }

            bind(view, entry, audioExists)
        }
    }

    private fun bind(
        view: View,
        entry: NoteEntry,
        audioExists: Boolean
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

        view.findViewById<TextView>(R.id.tvAudioStatus).setText(
            if (audioExists) R.string.audio_kept_detail else R.string.audio_deleted_detail
        )

        view.findViewById<View>(R.id.btnMore).setOnClickListener { anchor ->
            android.widget.PopupMenu(requireContext(), anchor).apply {
                menu.add(0, 1, 0, R.string.copy_text)
                setOnMenuItemClickListener {
                    copyToClipboard(entry)
                    true
                }
            }.show()
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
