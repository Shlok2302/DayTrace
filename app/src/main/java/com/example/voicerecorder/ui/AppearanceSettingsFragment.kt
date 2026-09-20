package com.example.voicerecorder.ui

import androidx.appcompat.app.AppCompatDelegate
import com.example.voicerecorder.R
import com.example.voicerecorder.settings.AppTheme
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.theme)
            .setSingleChoiceItems(
                choices.map { it.label }.toTypedArray(),
                choices.indexOf(settings.theme)
            ) { dialog, index ->

                dialog.dismiss()

                val picked = choices[index]

                if (picked != settings.theme) {
                    settings.theme = picked
                    // Recreates the activity, so every screen is redrawn.
                    AppCompatDelegate.setDefaultNightMode(picked.mode)
                }
            }
            .show()
    }
}
