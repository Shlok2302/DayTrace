package com.example.voicerecorder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.example.voicerecorder.R
import com.example.voicerecorder.settings.AppSettings

/**
 * One row on a settings page: either it opens something, or it toggles.
 */
sealed class SettingsRow {

    data class Open(
        @DrawableRes val icon: Int,
        val title: String,
        val subtitle: String,
        val value: String? = null,
        val onClick: () -> Unit
    ) : SettingsRow()

    data class Toggle(
        @DrawableRes val icon: Int,
        val title: String,
        val subtitle: String,
        val checked: Boolean,
        val onChange: (Boolean) -> Unit
    ) : SettingsRow()
}

/**
 * Shared layout for the settings pages: title, subtitle and a list of
 * rows. Sub-pages show a back arrow instead of the app name.
 */
abstract class SettingsPageFragment : Fragment(R.layout.fragment_settings) {

    protected abstract val pageTitle: Int

    protected abstract val pageSubtitle: Int

    /** The main page keeps the DayTrace header; sub-pages show a back arrow. */
    protected open val showBack: Boolean = true

    protected lateinit var settings: AppSettings

    private lateinit var rowsContainer: LinearLayout

    protected abstract fun rows(): List<SettingsRow>

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        settings = AppSettings(requireContext())

        rowsContainer = view.findViewById(R.id.rows)

        view.findViewById<TextView>(R.id.tvTitle).setText(pageTitle)
        view.findViewById<TextView>(R.id.tvSubtitle).setText(pageSubtitle)

        view.findViewById<View>(R.id.brand).isVisible = !showBack

        view.findViewById<View>(R.id.btnBack).apply {
            isVisible = showBack
            setOnClickListener { parentFragmentManager.popBackStack() }
        }

        renderRows()
    }

    /** Call after changing a setting that other rows depend on. */
    protected fun renderRows() {

        val inflater =
            LayoutInflater.from(requireContext())

        rowsContainer.removeAllViews()

        rows().forEach { row ->

            val view =
                inflater.inflate(R.layout.item_settings_row, rowsContainer, false)

            bind(view, row)

            val params =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (resources.displayMetrics.density * 10).toInt()
                }

            rowsContainer.addView(view, params)
        }
    }

    private fun bind(
        view: View,
        row: SettingsRow
    ) {

        val icon =
            view.findViewById<ImageView>(R.id.imgIcon)

        val title =
            view.findViewById<TextView>(R.id.tvTitle)

        val subtitle =
            view.findViewById<TextView>(R.id.tvSubtitle)

        val value =
            view.findViewById<TextView>(R.id.tvValue)

        val chevron =
            view.findViewById<ImageView>(R.id.imgChevron)

        val toggle =
            view.findViewById<MaterialSwitch>(R.id.toggle)

        when (row) {

            is SettingsRow.Open -> {
                icon.setImageResource(row.icon)
                title.text = row.title
                subtitle.text = row.subtitle

                value.isVisible = row.value != null
                value.text = row.value.orEmpty()

                chevron.isVisible = true
                toggle.isVisible = false

                view.setOnClickListener { row.onClick() }
            }

            is SettingsRow.Toggle -> {
                icon.setImageResource(row.icon)
                title.text = row.title
                subtitle.text = row.subtitle

                value.isVisible = false
                chevron.isVisible = false

                toggle.isVisible = true
                toggle.setOnCheckedChangeListener(null)
                toggle.isChecked = row.checked
                toggle.setOnCheckedChangeListener { _, checked -> row.onChange(checked) }

                view.setOnClickListener { toggle.toggle() }
            }
        }
    }
}
