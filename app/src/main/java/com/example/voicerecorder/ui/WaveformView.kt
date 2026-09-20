package com.example.voicerecorder.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.example.voicerecorder.R
import kotlin.math.abs
import kotlin.math.sin

/**
 * The thin waveform shown while recording. It is a gentle animation,
 * not a reading of the microphone level.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = ContextCompat.getColor(context, R.color.record_red)
        }

    private var phase =
        0f

    private val animator =
        ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }

    override fun onDraw(
        canvas: Canvas
    ) {
        super.onDraw(canvas)

        val barWidth =
            resources.displayMetrics.density * 2.5f

        val gap =
            resources.displayMetrics.density * 2f

        paint.strokeWidth = barWidth

        val step =
            barWidth + gap

        val bars =
            (width / step).toInt().coerceAtLeast(1)

        val centerY =
            height / 2f

        val maxHeight =
            height / 2f

        for (index in 0 until bars) {
            val wave =
                abs(sin(phase + index * 0.45f)) * 0.75f + abs(sin(index * 1.7f)) * 0.25f

            val barHeight =
                (maxHeight * wave).coerceAtLeast(barWidth)

            val x =
                index * step + barWidth / 2f

            canvas.drawLine(x, centerY - barHeight, x, centerY + barHeight, paint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
