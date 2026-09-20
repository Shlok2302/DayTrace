package com.example.voicerecorder.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.voicerecorder.R

/**
 * The "Recent Activity" bars: one bar per label, heights relative to the
 * busiest one.
 */
class ActivityChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.forest)
        }

    private val emptyPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.surface_sunken)
        }

    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.text_tertiary)
            textSize = context.resources.displayMetrics.scaledDensity * 11
            textAlign = Paint.Align.CENTER
        }

    private val bar =
        RectF()

    private var values: List<Int> =
        emptyList()

    private var labels: List<String> =
        emptyList()

    fun show(
        values: List<Int>,
        labels: List<String>
    ) {
        this.values = values
        this.labels = labels
        invalidate()
    }

    override fun onDraw(
        canvas: Canvas
    ) {
        super.onDraw(canvas)

        if (values.isEmpty()) {
            return
        }

        val density =
            resources.displayMetrics.density

        val labelHeight =
            density * 20

        val radius =
            density * 6

        val slot =
            width.toFloat() / values.size

        val barWidth =
            minOf(slot * 0.42f, density * 26)

        val chartHeight =
            height - labelHeight

        val maximum =
            (values.maxOrNull() ?: 0).coerceAtLeast(1)

        values.forEachIndexed { index, value ->

            val centerX =
                slot * index + slot / 2f

            val barHeight =
                if (value == 0) density * 4 else chartHeight * (value.toFloat() / maximum) * 0.92f

            bar.set(
                centerX - barWidth / 2f,
                chartHeight - barHeight,
                centerX + barWidth / 2f,
                chartHeight
            )

            canvas.drawRoundRect(bar, radius, radius, if (value == 0) emptyPaint else barPaint)

            labels.getOrNull(index)?.let { label ->
                canvas.drawText(label, centerX, height - density * 4, labelPaint)
            }
        }
    }
}
