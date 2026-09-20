package com.example.voicerecorder.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Lays children out in a row and wraps to the next line when the row is
 * full. Used for the category filter chips and the tag rows.
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val spacing =
        (context.resources.displayMetrics.density * 8).toInt()

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int
    ) {

        val maxWidth =
            MeasureSpec.getSize(widthMeasureSpec) - paddingStart - paddingEnd

        var rowWidth = 0
        var rowHeight = 0
        var totalHeight = 0

        children().forEach { child ->

            measureChild(child, widthMeasureSpec, heightMeasureSpec)

            if (rowWidth > 0 && rowWidth + spacing + child.measuredWidth > maxWidth) {
                totalHeight += rowHeight + spacing
                rowWidth = 0
                rowHeight = 0
            }

            rowWidth += (if (rowWidth > 0) spacing else 0) + child.measuredWidth
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }

        totalHeight += rowHeight + paddingTop + paddingBottom

        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(totalHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {

        val maxWidth =
            right - left - paddingStart - paddingEnd

        var x = paddingStart
        var y = paddingTop
        var rowHeight = 0

        children().forEach { child ->

            if (x > paddingStart && x + child.measuredWidth > maxWidth + paddingStart) {
                x = paddingStart
                y += rowHeight + spacing
                rowHeight = 0
            }

            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)

            x += child.measuredWidth + spacing
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }

    private fun children(): List<View> =
        (0 until childCount)
            .map { getChildAt(it) }
            .filter { it.visibility != GONE }
}
