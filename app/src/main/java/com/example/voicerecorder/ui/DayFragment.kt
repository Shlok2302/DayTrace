package com.example.voicerecorder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.voicerecorder.MainActivity
import com.example.voicerecorder.R
import com.example.voicerecorder.google.GoogleIntegrationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * One day's recordings: the week strip, full note cards and the note
 * about audio being deleted.
 */
class DayFragment : Fragment(R.layout.fragment_day) {

    private lateinit var week: LinearLayout
    private lateinit var notes: LinearLayout
    private lateinit var tvDate: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView

    private var entries: List<NoteEntry> = emptyList()

    /** Google suggestions, events and tasks for the cards. */
    private var google: GoogleStates = GoogleStates.NONE

    private val calendarFlow by lazy { CalendarFlow(this) { if (view != null) load() } }

    private val taskFlow by lazy { TaskFlow(this) { if (view != null) load() } }

    private val docsFlow by lazy { DocsFlow(this) { if (view != null) load() } }

    private val sendToGoogle by lazy { SendToGoogle(this, calendarFlow, taskFlow, docsFlow) }

    private var selected: LocalDate = LocalDate.now()

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        week = view.findViewById(R.id.week)
        notes = view.findViewById(R.id.notes)
        tvDate = view.findViewById(R.id.tvDate)
        tvCount = view.findViewById(R.id.tvCount)
        tvEmpty = view.findViewById(R.id.tvEmpty)

        arguments?.getString(ARG_DATE)?.let { selected = LocalDate.parse(it) }

        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<View>(R.id.btnCalendar).setOnClickListener {
            (activity as? MainActivity)?.openHistory(selected)
        }

        // An event added in the background (or a new suggestion) shows at once.
        GoogleIntegrationManager.observe(requireContext(), viewLifecycleOwner) { if (this.view != null) load() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {

        viewLifecycleOwner.lifecycleScope.launch {

            val (loaded, states) =
                withContext(Dispatchers.IO) { Notes.recordings(requireContext()) to GoogleStates.load(requireContext()) }

            entries = Notes.entries(loaded)
            google = states

            render()
        }
    }

    private fun render() {

        if (view == null) {
            return
        }

        tvDate.text = Notes.formatDate(selected)

        val dayEntries =
            entries.filter { it.date == selected }

        val recordings =
            dayEntries.map { it.recording.name }.distinct().size

        tvCount.text =
            if (recordings == 1) {
                getString(R.string.one_recording_on_day)
            } else {
                getString(R.string.recordings_on_day, recordings)
            }

        buildWeek()

        val inflater =
            LayoutInflater.from(requireContext())

        notes.removeAllViews()

        dayEntries.forEach { entry ->

            val card =
                NoteCards.dayCard(
                    inflater,
                    notes,
                    entry,
                    onOpen = { open(it) },
                    onChanged = { if (view != null) load() },
                    google = google,
                    onGoogle = { sendToGoogle.start(it, google) }
                )

            val params =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (resources.displayMetrics.density * 12).toInt()
                }

            notes.addView(card, params)
        }

        tvEmpty.isVisible = dayEntries.isEmpty()
    }

    private fun buildWeek() {

        val inflater =
            LayoutInflater.from(requireContext())

        week.removeAllViews()

        val monday =
            selected.with(DayOfWeek.MONDAY)

        (0 until DAYS_PER_WEEK).forEach { index ->

            val date =
                monday.plusDays(index.toLong())

            val cell =
                inflater.inflate(R.layout.item_week_day, week, false)

            val isSelected =
                date == selected

            cell.setBackgroundResource(
                if (isSelected) R.drawable.bg_week_day_selected else R.drawable.bg_week_day
            )

            cell.findViewById<TextView>(R.id.tvWeekday).apply {
                text = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3)
                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (isSelected) R.color.on_forest else R.color.text_secondary
                    )
                )
            }

            cell.findViewById<TextView>(R.id.tvWeekDayNumber).apply {
                text = date.dayOfMonth.toString()
                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (isSelected) R.color.on_forest else R.color.text_primary
                    )
                )
            }

            val dots =
                cell.findViewById<LinearLayout>(R.id.dots)

            dots.removeAllViews()

            entries
                .filter { it.date == date }
                .map { it.note.category }
                .distinct()
                .take(MAX_DOTS)
                .forEach { category ->

                    val dot =
                        View(requireContext()).apply {
                            setBackgroundResource(R.drawable.bg_dot)
                            backgroundTintList =
                                ContextCompat.getColorStateList(
                                    requireContext(),
                                    Categories.of(category).color
                                )
                        }

                    val size =
                        (resources.displayMetrics.density * 5).toInt()

                    dots.addView(
                        dot,
                        LinearLayout.LayoutParams(size, size).apply {
                            marginStart = (resources.displayMetrics.density * 1.5f).toInt()
                            marginEnd = (resources.displayMetrics.density * 1.5f).toInt()
                        }
                    )
                }

            cell.setOnClickListener {
                selected = date
                render()
            }

            week.addView(
                cell,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (resources.displayMetrics.density * 3).toInt()
                    marginEnd = (resources.displayMetrics.density * 3).toInt()
                }
            )
        }
    }

    private fun open(
        entry: NoteEntry
    ) {
        (activity as? MainActivity)?.open(NoteDetailFragment.forNote(entry.id))
    }

    companion object {

        private const val ARG_DATE =
            "date"

        private const val DAYS_PER_WEEK =
            7

        private const val MAX_DOTS =
            3

        fun forDate(
            date: LocalDate
        ): DayFragment =
            DayFragment().apply {
                arguments = Bundle().apply { putString(ARG_DATE, date.toString()) }
            }
    }
}
