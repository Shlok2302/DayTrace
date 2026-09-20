package com.example.voicerecorder.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.example.voicerecorder.R

/**
 * The circular progress used on the processing and analyzing screens:
 * a soft track with an arc turning around it.
 */
class ProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = ContextCompat.getColor(context, R.color.stroke)
        }

    private val arcPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = ContextCompat.getColor(context, R.color.forest)
        }

    private val bounds =
        RectF()

    private var sweepStart =
        0f

    private val animator =
        ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweepStart = it.animatedValue as Float
                invalidate()
            }
        }

    override fun onSizeChanged(
        width: Int,
        height: Int,
        oldWidth: Int,
        oldHeight: Int
    ) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)

        val strokeWidth =
            height * 0.035f

        trackPaint.strokeWidth = strokeWidth
        arcPaint.strokeWidth = strokeWidth

        val inset =
            strokeWidth / 2f

        bounds.set(inset, inset, width - inset, height - inset)
    }

    override fun onDraw(
        canvas: Canvas
    ) {
        super.onDraw(canvas)

        canvas.drawOval(bounds, trackPaint)
        canvas.drawArc(bounds, sweepStart, SWEEP_DEGREES, false, arcPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {

        const val SWEEP_DEGREES =
            96f
    }
}
