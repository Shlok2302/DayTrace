package com.example.voicerecorder.ui

import android.app.Dialog
import android.content.Context
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.voicerecorder.R

/**
 * The one dialog style of DayTrace, for every confirmation, warning and
 * message: a cream card with a soft illustrated icon, a serif title, a
 * short explanation, an optional gentle warning, a prominent main action
 * and a quiet secondary one. It rises in softly and fades out.
 *
 *     DayTraceDialog(context)
 *         .tone(DayTraceDialog.Tone.DANGER)
 *         .icon(R.drawable.ic_trash)
 *         .title(R.string.delete_forever_confirm_title)
 *         .message(...)
 *         .warning(R.string.delete_forever_confirm_message)
 *         .primary(R.string.delete_forever) { ... }
 *         .secondary(R.string.cancel)
 *         .show()
 *
 * Destructive actions keep the dark green main button; the tone shows in
 * the icon and the warning, so the dialog stays calm rather than alarming.
 *
 * With [choices] it is a picker (the current value is ticked) or a list
 * of options; picking one closes it.
 */
class DayTraceDialog(
    private val context: Context
) {

    /** The colour of the illustration: accent, circle and halo. */
    enum class Tone(
        @ColorRes val accent: Int,
        @ColorRes val circle: Int,
        @ColorRes val halo: Int
    ) {
        NEUTRAL(R.color.forest, R.color.forest_soft, R.color.remember_tint),
        SUCCESS(R.color.remember, R.color.remember_soft, R.color.remember_tint),
        WARNING(R.color.idea, R.color.idea_soft, R.color.idea_tint),
        DANGER(R.color.record_red, R.color.record_red_soft, R.color.danger_tint),
        INFO(R.color.gossip, R.color.gossip_soft, R.color.gossip_tint)
    }

    private class Action(
        val label: CharSequence,
        val onClick: () -> Unit
    )

    /** One entry of a [choices] list. */
    class Choice(
        val label: CharSequence,
        val detail: CharSequence? = null,
        @DrawableRes val icon: Int? = null
    )

    private var tone = Tone.NEUTRAL
    private var icon = R.drawable.ic_leaf
    private var title: CharSequence = ""
    private var message: CharSequence? = null
    private var warning: CharSequence? = null
    private var primary: Action? = null
    private var primaryArrow = false
    private var secondary: Action? = null
    private var extra: Action? = null
    private var extraDestructive = false
    private var choices: List<Choice>? = null
    private var selected: Int? = null
    private var onPick: (Int) -> Unit = {}
    private var content: View? = null
    private var busy = false
    private var inputValue: CharSequence? = null
    private var inputHint: CharSequence? = null
    private var onTyped: ((String) -> Unit)? = null

    fun tone(tone: Tone) = apply { this.tone = tone }

    fun icon(@DrawableRes icon: Int) = apply { this.icon = icon }

    fun title(title: CharSequence) = apply { this.title = title }

    fun title(@StringRes title: Int) = title(context.getString(title))

    fun message(message: CharSequence?) = apply { this.message = message }

    fun message(@StringRes message: Int) = message(context.getString(message))

    /** A gentle warning box under the message, e.g. "It can't be undone." */
    fun warning(warning: CharSequence?) = apply { this.warning = warning }

    fun warning(@StringRes warning: Int) = warning(context.getString(warning))

    /** The main action. [arrow] adds a "continue" arrow after the label. */
    fun primary(
        label: CharSequence,
        arrow: Boolean = false,
        onClick: () -> Unit = {}
    ) = apply {
        primary = Action(label, onClick)
        primaryArrow = arrow
    }

    fun primary(
        @StringRes label: Int,
        arrow: Boolean = false,
        onClick: () -> Unit = {}
    ) = primary(context.getString(label), arrow, onClick)

    /** The quiet action next to the main one, usually "Cancel". */
    fun secondary(
        label: CharSequence,
        onClick: () -> Unit = {}
    ) = apply { secondary = Action(label, onClick) }

    fun secondary(
        @StringRes label: Int,
        onClick: () -> Unit = {}
    ) = secondary(context.getString(label), onClick)

    /** A third choice, shown as a text link under the buttons. */
    fun extra(
        label: CharSequence,
        destructive: Boolean = false,
        onClick: () -> Unit
    ) = apply {
        extra = Action(label, onClick)
        extraDestructive = destructive
    }

    fun extra(
        @StringRes label: Int,
        destructive: Boolean = false,
        onClick: () -> Unit
    ) = extra(context.getString(label), destructive, onClick)

    /**
     * A list to pick from. [selected] ticks the current value (a settings
     * picker); without it the entries are options with a chevron. Picking
     * one closes the dialog and calls [onPick] with its position.
     */
    fun choices(
        items: List<Choice>,
        selected: Int? = null,
        onPick: (Int) -> Unit
    ) = apply {
        this.choices = items
        this.selected = selected
        this.onPick = onPick
    }

    /** A view of the caller's own under the message, e.g. the details of an event. */
    fun content(view: View) = apply { content = view }

    /**
     * One line to type in, e.g. a task's title or the name of a new
     * document. The main action gets what was typed; [onDone] runs
     * instead of the main action's own click.
     */
    fun input(
        value: CharSequence,
        @StringRes hint: Int,
        onDone: (String) -> Unit
    ) = apply {
        inputValue = value
        inputHint = context.getString(hint)
        onTyped = onDone
    }

    /**
     * "Working on it": a spinner instead of buttons, and it cannot be
     * closed by the user. The caller dismisses the returned dialog.
     */
    fun busy() = apply { busy = true }

    fun show(): Dialog {

        val dialog =
            Dialog(context, R.style.DayTraceDialog)

        val view =
            LayoutInflater.from(context).inflate(R.layout.dialog_daytrace, null)

        bindIllustration(view)

        view.findViewById<TextView>(R.id.title).text = title

        view.findViewById<TextView>(R.id.message).apply {
            text = message
            isVisible = !message.isNullOrBlank()
        }

        view.findViewById<View>(R.id.callout).isVisible = !warning.isNullOrBlank()
        view.findViewById<TextView>(R.id.calloutText).text = warning

        val field =
            inputValue?.let { buildInput(it) }

        (content ?: field)?.let { custom ->
            view.findViewById<android.widget.FrameLayout>(R.id.content).apply {
                isVisible = true
                addView(custom)
            }
        }

        bindChoices(view, dialog)
        bindButtons(view, dialog, field)

        view.findViewById<View>(R.id.btnClose).setOnClickListener { dialog.cancel() }

        if (busy) {
            view.findViewById<View>(R.id.progress).isVisible = true
            view.findViewById<View>(R.id.buttons).isVisible = false
            view.findViewById<View>(R.id.btnClose).isVisible = false
            dialog.setCancelable(false)
        }

        // The faint leaf stays inside the rounded card.
        view.findViewById<View>(R.id.card).clipToOutline = true

        dialog.setContentView(view)
        dialog.setCanceledOnTouchOutside(!busy)

        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.show()

        return dialog
    }

    private fun bindIllustration(
        view: View
    ) {
        view.findViewById<View>(R.id.halo).backgroundTintList =
            ContextCompat.getColorStateList(context, tone.halo)

        view.findViewById<View>(R.id.iconCircle).backgroundTintList =
            ContextCompat.getColorStateList(context, tone.circle)

        view.findViewById<ImageView>(R.id.sparks).imageTintList =
            ContextCompat.getColorStateList(context, tone.accent)

        view.findViewById<ImageView>(R.id.icon).apply {
            setImageResource(icon)
            imageTintList = ContextCompat.getColorStateList(context, tone.accent)
        }
    }

    private fun bindChoices(
        view: View,
        dialog: Dialog
    ) {

        val items =
            choices ?: return

        val list =
            view.findViewById<LinearLayout>(R.id.choices)

        list.isVisible = true

        val inflater =
            LayoutInflater.from(context)

        items.forEachIndexed { index, choice ->

            val row =
                inflater.inflate(R.layout.item_dialog_choice, list, false)

            val isSelected =
                index == selected

            row.setBackgroundResource(
                if (isSelected) R.drawable.bg_dialog_choice_selected else R.drawable.bg_dialog_choice
            )

            row.findViewById<TextView>(R.id.label).text = choice.label

            row.findViewById<TextView>(R.id.detail).apply {
                text = choice.detail
                isVisible = !choice.detail.isNullOrBlank()
            }

            row.findViewById<View>(R.id.iconHolder).isVisible = choice.icon != null
            choice.icon?.let { row.findViewById<ImageView>(R.id.icon).setImageResource(it) }

            row.findViewById<ImageView>(R.id.indicator).apply {
                when {
                    // A list of options: each one leads somewhere.
                    selected == null -> {
                        setImageResource(R.drawable.ic_chevron_right)
                        imageTintList = ContextCompat.getColorStateList(context, R.color.text_tertiary)
                    }
                    isSelected -> setImageResource(R.drawable.ic_choice_on)
                    else -> setImageResource(R.drawable.ic_choice_off)
                }
            }

            row.isSelected = isSelected

            row.setOnClickListener {
                dialog.dismiss()
                onPick(index)
            }

            list.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { if (index > 0) topMargin = dp(10) }
            )
        }
    }

    /** The one-line field of [input], in the dialog's own style. */
    private fun buildInput(
        value: CharSequence
    ): EditText =
        EditText(context).apply {
            setText(value)
            hint = inputHint
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            textSize = 15f
            setSingleLine()
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSelection(text.length)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun bindButtons(
        view: View,
        dialog: Dialog,
        field: EditText?
    ) {

        val buttons =
            view.findViewById<LinearLayout>(R.id.buttons)

        val primaryButton =
            view.findViewById<TextView>(R.id.btnPrimary)

        val secondaryButton =
            view.findViewById<TextView>(R.id.btnSecondary)

        val main =
            primary ?: if (choices == null) Action(context.getString(R.string.close)) {} else null

        primaryButton.isVisible = main != null

        if (main != null) {

            primaryButton.text =
                if (primaryArrow) withArrow(main.label) else main.label

            primaryButton.setOnClickListener {
                // With a field, the main action is "use what was typed".
                val typed = field?.text?.toString()
                dialog.dismiss()
                if (typed != null && onTyped != null) onTyped?.invoke(typed) else main.onClick()
            }
        }

        val second =
            secondary

        secondaryButton.isVisible = second != null

        if (second != null) {
            secondaryButton.text = second.label
            secondaryButton.setOnClickListener {
                dialog.dismiss()
                second.onClick()
            }
        }

        // Long labels do not fit side by side: stack them, the main action on top.
        if (main != null && second != null && main.label.length + second.label.length > STACK_AFTER_CHARS) {
            buttons.orientation = LinearLayout.VERTICAL
            buttons.removeAllViews()
            buttons.addView(primaryButton, stacked(0))
            buttons.addView(secondaryButton, stacked(dp(10)))
        } else if (second == null) {
            (primaryButton.layoutParams as LinearLayout.LayoutParams).marginStart = 0
        }

        val third =
            extra

        view.findViewById<TextView>(R.id.btnExtra).apply {
            isVisible = third != null
            if (third != null) {
                text = third.label
                setTextColor(
                    ContextCompat.getColor(context, if (extraDestructive) R.color.record_red else R.color.forest)
                )
                setOnClickListener {
                    dialog.dismiss()
                    third.onClick()
                }
            }
        }
    }

    /** "Replace notes →": the arrow follows the label, whatever its length. */
    private fun withArrow(
        label: CharSequence
    ): CharSequence {

        val arrow =
            ContextCompat.getDrawable(context, R.drawable.ic_arrow_forward)?.mutate() ?: return label

        arrow.setTint(ContextCompat.getColor(context, R.color.on_forest))
        arrow.setBounds(0, 0, dp(18), dp(18))

        val align =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                DynamicDrawableSpan.ALIGN_CENTER
            } else {
                DynamicDrawableSpan.ALIGN_BASELINE
            }

        return SpannableStringBuilder(label)
            .append("  ")
            .append(" ", ImageSpan(arrow, align), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun stacked(
        top: Int
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = top }

    private fun dp(
        value: Int
    ): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {

        /** Two labels longer than this together are stacked instead of side by side. */
        const val STACK_AFTER_CHARS =
            24
    }
}
