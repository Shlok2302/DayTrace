package com.example.voicerecorder.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.voicerecorder.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * The month grid on the history screen: weekday header, the days of the
 * month, and a coloured dot per category recorded on a day.
 */
class MonthCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var onDateSelected: ((LocalDate) -> Unit)? = null

    private val weekdays =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
        }

    private val grid =
        GridLayout(context).apply {
            columnCount = DAYS_PER_WEEK
        }

    private var month: YearMonth =
        YearMonth.now()

    private var selected: LocalDate =
        LocalDate.now()

    private var categoriesByDate: Map<LocalDate, List<String>> =
        emptyMap()

    init {
        orientation = VERTICAL

        addView(
            weekdays,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        addView(
            grid,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        buildWeekdayHeader()
    }

    fun show(
        month: YearMonth,
        selected: LocalDate,
        categoriesByDate: Map<LocalDate, List<String>>
    ) {
        this.month = month
        this.selected = selected
        this.categoriesByDate = categoriesByDate
        buildGrid()
    }

    private fun buildWeekdayHeader() {

        weekdays.removeAllViews()

        (0 until DAYS_PER_WEEK).forEach { index ->

            val day =
                DayOfWeek.MONDAY.plus(index.toLong())

            val label =
                TextView(context).apply {
                    text = day.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3)
                    gravity = android.view.Gravity.CENTER
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    textSize = 12f
                }

            weekdays.addView(
                label,
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            )
        }
    }

    private fun buildGrid() {

        grid.removeAllViews()

        val firstOfMonth =
            month.atDay(1)

        // Monday-first grid, including the tail of the previous month.
        val leading =
            (firstOfMonth.dayOfWeek.value + 6) % 7

        val start =
            firstOfMonth.minusDays(leading.toLong())

        val weeks =
            WEEKS_SHOWN

        (0 until weeks * DAYS_PER_WEEK).forEach { index ->

            val date =
                start.plusDays(index.toLong())

            val cell =
                LayoutInflater.from(context).inflate(R.layout.item_calendar_day, grid, false)

            bindCell(cell, date)

            val params =
                GridLayout.LayoutParams().apply {
                    width = 0
                    height = LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(index % DAYS_PER_WEEK, 1f)
                    rowSpec = GridLayout.spec(index / DAYS_PER_WEEK)
                }

            grid.addView(cell, params)
        }
    }

    private fun bindCell(
        cell: View,
        date: LocalDate
    ) {

        val number =
            cell.findViewById<TextView>(R.id.tvDay)

        val dots =
            cell.findViewById<LinearLayout>(R.id.dots)

        val inMonth =
            YearMonth.from(date) == month

        number.text = date.dayOfMonth.toString()

        when {
            date == selected -> {
                number.setBackgroundResource(R.drawable.bg_day_selected)
                number.setTextColor(ContextCompat.getColor(context, R.color.on_forest))
            }

            date == LocalDate.now() -> {
                number.setBackgroundResource(R.drawable.bg_day_today)
                number.setTextColor(ContextCompat.getColor(context, R.color.forest))
            }

            else -> {
                number.background = null
                number.setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (inMonth) R.color.text_primary else R.color.text_tertiary
                    )
                )
            }
        }

        dots.removeAllViews()

        val categories =
            categoriesByDate[date].orEmpty().distinct().take(MAX_DOTS)

        dots.isVisible = categories.isNotEmpty()

        categories.forEach { category ->

            val dot =
                View(context).apply {
                    setBackgroundResource(R.drawable.bg_dot)
                    backgroundTintList =
                        ContextCompat.getColorStateList(context, Categories.of(category).color)
                }

            val size =
                (resources.displayMetrics.density * 5).toInt()

            val params =
                LayoutParams(size, size).apply {
                    marginStart = (resources.displayMetrics.density * 1.5f).toInt()
                    marginEnd = (resources.displayMetrics.density * 1.5f).toInt()
                }

            dots.addView(dot, params)
        }

        cell.setOnClickListener { onDateSelected?.invoke(date) }
    }

    private companion object {

        const val DAYS_PER_WEEK = 7
        const val WEEKS_SHOWN = 6
        const val MAX_DOTS = 3
    }
}
