package com.example.voicerecorder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.voicerecorder.MainActivity
import com.example.voicerecorder.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Stats tab: how many notes per category, and how much was recorded
 * recently.
 */
class StatsFragment : Fragment(R.layout.fragment_stats) {

    private enum class Range { WEEK, MONTH, ALL }

    private lateinit var segments: LinearLayout
    private lateinit var chart: ActivityChartView
    private lateinit var tvEmpty: TextView

    private val tiles = mutableListOf<FrameLayout>()

    private var entries: List<NoteEntry> = emptyList()

    private var range: Range = Range.WEEK

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        segments = view.findViewById(R.id.segments)
        chart = view.findViewById(R.id.chart)
        tvEmpty = view.findViewById(R.id.tvEmpty)

        tiles.clear()
        tiles += view.findViewById<FrameLayout>(R.id.tile0)
        tiles += view.findViewById<FrameLayout>(R.id.tile1)
        tiles += view.findViewById<FrameLayout>(R.id.tile2)
        tiles += view.findViewById<FrameLayout>(R.id.tile3)

        view.findViewById<View>(R.id.btnSettings).setOnClickListener {
            (activity as? MainActivity)?.openSettings()
        }

        buildSegments()
        buildTiles()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {

        viewLifecycleOwner.lifecycleScope.launch {

            val loaded =
                withContext(Dispatchers.IO) { Notes.recordings(requireContext()) }

            entries = Notes.entries(loaded)

            render()
        }
    }

    private fun buildSegments() {

        val inflater =
            LayoutInflater.from(requireContext())

        segments.removeAllViews()

        listOf(
            Range.WEEK to R.string.stats_week,
            Range.MONTH to R.string.stats_month,
            Range.ALL to R.string.stats_all
        ).forEach { (value, label) ->

            val segment =
                inflater.inflate(R.layout.item_segment, segments, false) as TextView

            segment.setText(label)

            segment.setOnClickListener {
                range = value
                render()
            }

            segments.addView(
                segment,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (resources.displayMetrics.density * 3).toInt()
                    marginEnd = (resources.displayMetrics.density * 3).toInt()
                }
            )
        }
    }

    private fun buildTiles() {

        val inflater =
            LayoutInflater.from(requireContext())

        tiles.forEachIndexed { index, holder ->

            val style =
                Categories.all.getOrNull(index) ?: return@forEachIndexed

            holder.removeAllViews()

            val tile =
                inflater.inflate(R.layout.item_stat_tile, holder, false)

            tile.setBackgroundResource(style.card)
            tile.findViewById<View>(R.id.iconHolder).setBackgroundResource(style.circle)

            tile.findViewById<ImageView>(R.id.imgCategory).apply {
                setImageResource(style.icon)
                imageTintList = ContextCompat.getColorStateList(requireContext(), style.color)
            }

            tile.findViewById<TextView>(R.id.tvCategory).text = style.name

            holder.addView(tile)
        }
    }

    private fun render() {

        if (view == null) {
            return
        }

        renderSegments()

        val from =
            when (range) {
                Range.WEEK -> LocalDate.now().with(DayOfWeek.MONDAY)
                Range.MONTH -> LocalDate.now().withDayOfMonth(1)
                Range.ALL -> LocalDate.MIN
            }

        val inRange =
            entries.filter { it.date >= from }

        tiles.forEachIndexed { index, holder ->

            val style =
                Categories.all.getOrNull(index) ?: return@forEachIndexed

            val count =
                inRange.count { it.note.category == style.name }

            holder.findViewById<TextView>(R.id.tvCount).text = count.toString()
        }

        renderChart(inRange)

        tvEmpty.isVisible = entries.isEmpty()
    }

    private fun renderSegments() {

        (0 until segments.childCount).forEach { index ->

            val segment =
                segments.getChildAt(index) as TextView

            val selected =
                index == range.ordinal

            segment.setBackgroundResource(
                if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )

            segment.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (selected) R.color.on_forest else R.color.text_primary
                )
            )
        }
    }

    /**
     * Week: one bar per day. Month: one bar per week. All time: one bar
     * per month.
     */
    private fun renderChart(
        inRange: List<NoteEntry>
    ) {

        val today =
            LocalDate.now()

        when (range) {

            Range.WEEK -> {
                val monday =
                    today.with(DayOfWeek.MONDAY)

                val days =
                    (0 until 7).map { monday.plusDays(it.toLong()) }

                chart.show(
                    values = days.map { day -> inRange.count { it.date == day } },
                    labels = days.map {
                        it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3)
                    }
                )
            }

            Range.MONTH -> {
                val first =
                    today.withDayOfMonth(1)

                val weeks =
                    (0 until 5).map { first.plusWeeks(it.toLong()) }

                chart.show(
                    values = weeks.map { start ->
                        inRange.count { it.date >= start && it.date < start.plusWeeks(1) }
                    },
                    labels = weeks.mapIndexed { index, _ -> "W${index + 1}" }
                )
            }

            Range.ALL -> {
                val months =
                    (5 downTo 0).map { today.withDayOfMonth(1).minusMonths(it.toLong()) }

                chart.show(
                    values = months.map { start ->
                        entries.count { it.date >= start && it.date < start.plusMonths(1) }
                    },
                    labels = months.map {
                        it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3)
                    }
                )
            }
        }
    }
}
