package com.example.voicerecorder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.voicerecorder.MainActivity
import com.example.voicerecorder.R
import com.example.voicerecorder.summary.SavedRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/**
 * History tab: month calendar, category filter and the notes of the
 * selected day.
 */
class HistoryFragment : Fragment(R.layout.fragment_history) {

    private lateinit var calendar: MonthCalendarView
    private lateinit var chips: FlowLayout
    private lateinit var notes: LinearLayout
    private lateinit var tvMonth: TextView
    private lateinit var tvSelectedDate: TextView
    private lateinit var tvDayCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var inputSearch: EditText

    private var recordings: List<SavedRecording> = emptyList()

    private val calendarFlow by lazy { CalendarFlow(this) { if (view != null) load() } }

    private val taskFlow by lazy { TaskFlow(this) { if (view != null) load() } }

    private val docsFlow by lazy { DocsFlow(this) { if (view != null) load() } }

    private val sendToGoogle by lazy { SendToGoogle(this, calendarFlow, taskFlow, docsFlow) }

    /** What Google knows about these notes, for "Send to Google". */
    private var google: GoogleStates = GoogleStates.NONE

    private var entries: List<NoteEntry> = emptyList()

    private var month: YearMonth = YearMonth.now()
    private var selected: LocalDate = LocalDate.now()
    private var filter: String? = null
    private var query: String = ""

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        calendar = view.findViewById(R.id.calendar)
        chips = view.findViewById(R.id.chips)
        notes = view.findViewById(R.id.notes)
        tvMonth = view.findViewById(R.id.tvMonth)
        tvSelectedDate = view.findViewById(R.id.tvSelectedDate)
        tvDayCount = view.findViewById(R.id.tvDayCount)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        inputSearch = view.findViewById(R.id.inputSearch)

        arguments?.getString(ARG_DATE)?.let { date ->
            selected = LocalDate.parse(date)
            month = YearMonth.from(selected)
        }

        calendar.onDateSelected = { date ->
            selected = date

            if (YearMonth.from(date) != month) {
                month = YearMonth.from(date)
            }

            render()
        }

        view.findViewById<View>(R.id.btnPreviousMonth).setOnClickListener {
            month = month.minusMonths(1)
            render()
        }

        view.findViewById<View>(R.id.btnNextMonth).setOnClickListener {
            month = month.plusMonths(1)
            render()
        }

        view.findViewById<View>(R.id.dayHeader).setOnClickListener {
            (activity as? MainActivity)?.open(DayFragment.forDate(selected))
        }

        view.findViewById<View>(R.id.btnSearch).setOnClickListener {
            inputSearch.isVisible = !inputSearch.isVisible

            if (!inputSearch.isVisible) {
                inputSearch.setText("")
            } else {
                inputSearch.requestFocus()
            }
        }

        view.findViewById<View>(R.id.btnMenu).setOnClickListener {
            (activity as? MainActivity)?.openSettings()
        }

        inputSearch.doAfterTextChanged { text ->
            query = text?.toString().orEmpty().trim()
            render()
        }

        buildChips()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {

        viewLifecycleOwner.lifecycleScope.launch {

            val (loaded, states) =
                withContext(Dispatchers.IO) { Notes.recordings(requireContext()) to GoogleStates.load(requireContext()) }

            recordings = loaded
            google = states
            entries = Notes.entries(loaded)

            // Open on the latest day that has notes, unless a date was asked for.
            if (arguments?.getString(ARG_DATE) == null && entries.none { it.date == selected }) {
                entries.firstOrNull()?.let { latest ->
                    selected = latest.date
                    month = YearMonth.from(selected)
                }
            }

            render()
        }
    }

    private fun buildChips() {

        val inflater =
            LayoutInflater.from(requireContext())

        chips.removeAllViews()

        // "All" first, then one chip per category.
        val all =
            inflater.inflate(R.layout.item_chip, chips, false)

        all.findViewById<View>(R.id.iconHolder).isVisible = false
        all.findViewById<TextView>(R.id.tvChip).setText(R.string.category_all)
        all.setPadding(
            (resources.displayMetrics.density * 20).toInt(),
            0,
            (resources.displayMetrics.density * 20).toInt(),
            0
        )
        all.setOnClickListener {
            filter = null
            render()
        }

        chips.addView(all)

        Categories.all.forEach { style ->

            val chip =
                inflater.inflate(R.layout.item_chip, chips, false)

            chip.findViewById<View>(R.id.iconHolder).setBackgroundResource(style.circle)

            chip.findViewById<ImageView>(R.id.imgChip).apply {
                setImageResource(style.icon)
                imageTintList = ContextCompat.getColorStateList(requireContext(), style.color)
            }

            chip.findViewById<TextView>(R.id.tvChip).text = style.name

            chip.setOnClickListener {
                filter = if (filter == style.name) null else style.name
                render()
            }

            chips.addView(chip)
        }
    }

    private fun renderChipSelection() {

        chips.forEachIndexed { index, chip ->

            val selectedChip =
                if (index == 0) filter == null else Categories.all[index - 1].name == filter

            chip.setBackgroundResource(
                if (selectedChip) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )

            chip.findViewById<TextView>(R.id.tvChip).setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (selectedChip) R.color.on_forest else R.color.text_primary
                )
            )
        }
    }

    private fun render() {

        if (view == null) {
            return
        }

        tvMonth.text = Notes.formatMonth(month.atDay(1))

        calendar.show(
            month = month,
            selected = selected,
            categoriesByDate = entries.groupBy { it.date }
                .mapValues { (_, dayEntries) -> dayEntries.map { it.note.category } }
        )

        renderChipSelection()

        val searching =
            query.isNotEmpty()

        val visible =
            entries
                .filter { searching || it.date == selected }
                .filter { filter == null || it.note.category == filter }
                .filter { !searching || it.matches(query) }

        tvSelectedDate.text =
            if (searching) getString(R.string.search) else Notes.formatDate(selected)

        tvDayCount.text =
            countLabel(visible)

        notes.removeAllViews()

        val inflater =
            LayoutInflater.from(requireContext())

        visible.forEach { entry ->

            val card =
                NoteCards.historyCard(
                    inflater,
                    notes,
                    entry,
                    onOpen = { open(it) },
                    onChanged = { if (view != null) load() },
                    onGoogle = { sendToGoogle.start(it, google) }
                )

            val params =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (resources.displayMetrics.density * 10).toInt()
                }

            notes.addView(card, params)
        }

        tvEmpty.isVisible = visible.isEmpty()

        tvEmpty.setText(
            if (entries.isEmpty()) R.string.no_recordings_yet else R.string.no_recordings_day
        )
    }

    private fun countLabel(
        visible: List<NoteEntry>
    ): String {

        val count =
            visible.map { it.recording.name }.distinct().size

        return if (count == 1) {
            getString(R.string.one_recording)
        } else {
            getString(R.string.recordings_count, count)
        }
    }

    private fun open(
        entry: NoteEntry
    ) {
        (activity as? MainActivity)?.open(NoteDetailFragment.forNote(entry.id))
    }

    private fun NoteEntry.matches(
        text: String
    ): Boolean =
        note.title.contains(text, ignoreCase = true) ||
                note.text.contains(text, ignoreCase = true) ||
                note.tags.any { it.contains(text, ignoreCase = true) }

    private inline fun FlowLayout.forEachIndexed(
        action: (Int, View) -> Unit
    ) {
        (0 until childCount).forEach { index -> action(index, getChildAt(index)) }
    }

    companion object {

        private const val ARG_DATE =
            "date"

        fun forDate(
            date: LocalDate?
        ): HistoryFragment =
            HistoryFragment().apply {
                arguments = Bundle().apply {
                    date?.let { putString(ARG_DATE, it.toString()) }
                }
            }
    }
}
