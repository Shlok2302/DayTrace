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
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.voicerecorder.R

/**
 * Builds the note cards used by the history and day screens.
 */
object NoteCards {

    /**
     * The compact row under the calendar: icon, title, preview, time.
     */
    fun historyCard(
        inflater: LayoutInflater,
        parent: ViewGroup,
        entry: NoteEntry,
        onOpen: (NoteEntry) -> Unit
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
            showMenu(anchor, entry, onOpen)
        }

        return view
    }

    /**
     * The full card on the day screen: category pill, time, title, text, tags.
     */
    fun dayCard(
        inflater: LayoutInflater,
        parent: ViewGroup,
        entry: NoteEntry,
        onOpen: (NoteEntry) -> Unit
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

        view.setOnClickListener { onOpen(entry) }

        view.findViewById<ImageButton>(R.id.btnMore).setOnClickListener { anchor ->
            showMenu(anchor, entry, onOpen)
        }

        return view
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

    private fun showMenu(
        anchor: View,
        entry: NoteEntry,
        onOpen: (NoteEntry) -> Unit
    ) {

        val context =
            anchor.context

        PopupMenu(context, anchor).apply {

            menu.add(0, 1, 0, R.string.open_note)
            menu.add(0, 2, 1, R.string.copy_text)

            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> onOpen(entry)
                    2 -> copy(context, entry.note.text)
                }
                true
            }

        }.show()
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
