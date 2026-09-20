package com.example.voicerecorder.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.example.voicerecorder.R

/**
 * The switch used on the settings rows: a green pill with a white thumb.
 *
 * Written as plain views on purpose. Material's own switch needs a
 * Material 3 theme, and this app uses the Material 2 theme.
 */
class ToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onCheckedChange: ((Boolean) -> Unit)? = null

    private val density =
        resources.displayMetrics.density

    private val trackWidth = (density * TRACK_WIDTH_DP).toInt()
    private val trackHeight = (density * TRACK_HEIGHT_DP).toInt()
    private val thumbSize = (density * THUMB_SIZE_DP).toInt()
    private val inset = (density * INSET_DP)

    private val track =
        View(context)

    private val thumb =
        View(context).apply { setBackgroundResource(R.drawable.bg_switch_thumb) }

    var isChecked: Boolean = false
        private set

    init {
        addView(
            track,
            LayoutParams(trackWidth, trackHeight, Gravity.CENTER_VERTICAL or Gravity.START)
        )

        addView(
            thumb,
            LayoutParams(thumbSize, thumbSize, Gravity.CENTER_VERTICAL or Gravity.START).apply {
                marginStart = inset.toInt()
            }
        )

        isClickable = true
        isFocusable = true

        setOnClickListener { toggle() }

        apply(animate = false)
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int
    ) {
        setMeasuredDimension(trackWidth, trackHeight)
        measureChildren(widthMeasureSpec, heightMeasureSpec)
    }

    fun setChecked(
        checked: Boolean,
        animate: Boolean = true
    ) {
        isChecked = checked
        apply(animate)
    }

    fun toggle() {
        setChecked(!isChecked)
        onCheckedChange?.invoke(isChecked)
    }

    private fun apply(
        animate: Boolean
    ) {

        track.setBackgroundResource(
            if (isChecked) R.drawable.bg_switch_track_on else R.drawable.bg_switch_track_off
        )

        val target =
            if (isChecked) trackWidth - thumbSize - inset * 2 else 0f

        if (animate) {
            thumb.animate().translationX(target).setDuration(140L).start()
        } else {
            thumb.translationX = target
        }
    }

    private companion object {

        const val TRACK_WIDTH_DP = 50f
        const val TRACK_HEIGHT_DP = 30f
        const val THUMB_SIZE_DP = 24f
        const val INSET_DP = 3f
    }
}
