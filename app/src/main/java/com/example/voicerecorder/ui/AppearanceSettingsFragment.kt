package com.example.voicerecorder.ui

import androidx.appcompat.app.AppCompatDelegate
import com.example.voicerecorder.R
import com.example.voicerecorder.settings.AppTheme

/**
 * Settings > Appearance.
 */
class AppearanceSettingsFragment : SettingsPageFragment() {

    override val pageTitle = R.string.settings_appearance

    override val pageSubtitle = R.string.settings_appearance_page_subtitle

    override fun rows(): List<SettingsRow> =
        listOf(

            SettingsRow.Open(
                icon = R.drawable.ic_palette,
                title = getString(R.string.theme),
                subtitle = getString(R.string.theme_subtitle),
                value = settings.theme.label,
                onClick = { chooseTheme() }
            )
        )

    private fun chooseTheme() {

        val choices =
            AppTheme.values()

        val details =
            mapOf(
                AppTheme.SYSTEM to R.string.theme_system_detail,
                AppTheme.LIGHT to R.string.theme_light_detail,
                AppTheme.DARK to R.string.theme_dark_detail
            )

        DayTraceDialog(requireContext())
            .icon(R.drawable.ic_palette)
            .title(R.string.theme)
            .message(R.string.theme_subtitle)
            .choices(
                choices.map { DayTraceDialog.Choice(it.label, details[it]?.let { id -> getString(id) }) },
                choices.indexOf(settings.theme)
            ) { index ->

                val picked = choices[index]

                if (picked != settings.theme) {
                    settings.theme = picked
                    // Recreates the activity, so every screen is redrawn.
                    AppCompatDelegate.setDefaultNightMode(picked.mode)
                }
            }
            .secondary(R.string.cancel)
            .show()
    }
}
