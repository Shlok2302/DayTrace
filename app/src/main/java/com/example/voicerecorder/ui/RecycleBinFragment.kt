package com.example.voicerecorder.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.voicerecorder.MainActivity
import com.example.voicerecorder.R
import com.example.voicerecorder.summary.NoteActions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Recycle bin: notes that were deleted or marked as done.
 * Each can be opened, restored or deleted for good; after 30 days they
 * are deleted for good by themselves.
 */
class RecycleBinFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var rows: LinearLayout

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        rows = view.findViewById(R.id.rows)

        view.findViewById<TextView>(R.id.tvTitle).setText(R.string.settings_recycle_bin)
        view.findViewById<TextView>(R.id.tvSubtitle).setText(R.string.bin_subtitle)

        view.findViewById<View>(R.id.brand).isVisible = false

        view.findViewById<View>(R.id.btnBack).apply {
            isVisible = true
            setOnClickListener { parentFragmentManager.popBackStack() }
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {

        viewLifecycleOwner.lifecycleScope.launch {

            val entries =
                withContext(Dispatchers.IO) {
                    // Anything past its 30 days goes first, even if the app stayed open.
                    NoteActions.purgeExpired(requireContext())
                    Notes.binEntries(Notes.recordings(requireContext()))
                }

            render(entries)
        }
    }

    private fun render(
        entries: List<NoteEntry>
    ) {

        if (view == null) {
            return
        }

        val inflater =
            LayoutInflater.from(requireContext())

        val density =
            resources.displayMetrics.density

        rows.removeAllViews()

        if (entries.isEmpty()) {
            rows.addView(
                TextView(requireContext()).apply {
                    TextViewCompat.setTextAppearance(this, R.style.Text_Body)
                    setText(R.string.bin_empty)
                    gravity = Gravity.CENTER
                    setPadding(0, (density * 48).toInt(), 0, 0)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            return
        }

        entries.forEach { entry ->

            val card =
                NoteCards.binCard(
                    inflater,
                    rows,
                    entry,
                    onOpen = { (activity as? MainActivity)?.open(NoteDetailFragment.forNote(it.id)) },
                    onChanged = { if (view != null) load() }
                )

            rows.addView(
                card,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (density * 12).toInt() }
            )
        }

        rows.addView(
            emptyBinButton(entries.size),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (density * 52).toInt()
            ).apply { topMargin = (density * 8).toInt() }
        )
    }

    private fun emptyBinButton(
        count: Int
    ): View =
        TextView(requireContext()).apply {
            setText(R.string.bin_empty_all)
            gravity = Gravity.CENTER
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.record_red))
            setBackgroundResource(R.drawable.bg_button_record)
            isClickable = true
            isFocusable = true

            setOnClickListener { button ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.bin_empty_confirm_title)
                    .setMessage(resources.getQuantityString(R.plurals.bin_empty_confirm_message, count, count))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete_forever) { _, _ ->
                        NoteCards.run(button, R.string.bin_emptied, { if (view != null) load() }) {
                            NoteActions.emptyBin(it) > 0
                        }
                    }
                    .show()
            }
        }
}
