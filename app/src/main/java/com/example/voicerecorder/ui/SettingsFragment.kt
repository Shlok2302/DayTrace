package com.example.voicerecorder.ui

import android.os.Build
import com.example.voicerecorder.BuildConfig
import com.example.voicerecorder.MainActivity
import com.example.voicerecorder.R

/**
 * The main Settings page.
 */
class SettingsFragment : SettingsPageFragment() {

    override val pageTitle = R.string.settings_title

    override val pageSubtitle = R.string.settings_subtitle

    override val showBack = false

    override fun rows(): List<SettingsRow> =
        listOf(

            SettingsRow.Open(
                icon = R.drawable.ic_mic,
                title = getString(R.string.settings_recording),
                subtitle = getString(R.string.settings_recording_subtitle),
                onClick = { open(RecordingSettingsFragment()) }
            ),

            SettingsRow.Open(
                icon = R.drawable.ic_sparkle,
                title = getString(R.string.settings_ai),
                subtitle = getString(R.string.settings_ai_subtitle),
                onClick = { open(AiSettingsFragment()) }
            ),

            SettingsRow.Open(
                icon = R.drawable.ic_database,
                title = getString(R.string.settings_storage),
                subtitle = getString(R.string.settings_storage_subtitle),
                onClick = { open(DataStorageFragment()) }
            ),

            SettingsRow.Open(
                icon = R.drawable.ic_trash,
                title = getString(R.string.settings_recycle_bin),
                subtitle = getString(R.string.settings_recycle_bin_subtitle),
                onClick = { open(RecycleBinFragment()) }
            ),

            SettingsRow.Open(
                icon = R.drawable.ic_palette,
                title = getString(R.string.settings_appearance),
                subtitle = getString(R.string.settings_appearance_subtitle),
                onClick = { open(AppearanceSettingsFragment()) }
            ),

            SettingsRow.Open(
                icon = R.drawable.ic_bell,
                title = getString(R.string.settings_notifications),
                subtitle = getString(R.string.settings_notifications_subtitle),
                onClick = { open(NotificationSettingsFragment()) }
            ),

            SettingsRow.Open(
                icon = R.drawable.ic_shield,
                title = getString(R.string.settings_privacy),
                subtitle = getString(R.string.settings_privacy_subtitle),
                onClick = { showPrivacy() }
            ),

            SettingsRow.Open(
                icon = R.drawable.ic_info,
                title = getString(R.string.settings_about),
                subtitle = getString(R.string.settings_about_subtitle),
                onClick = { showAbout() }
            )
        )

    private fun open(
        fragment: androidx.fragment.app.Fragment
    ) {
        (activity as? MainActivity)?.open(fragment)
    }

    private fun showPrivacy() {
        DayTraceDialog(requireContext())
            .tone(DayTraceDialog.Tone.INFO)
            .icon(R.drawable.ic_shield)
            .title(R.string.settings_privacy)
            .message(R.string.privacy_details)
            .primary(R.string.close)
            .show()
    }

    private fun showAbout() {
        DayTraceDialog(requireContext())
            .icon(R.drawable.ic_leaf)
            .title(R.string.settings_about)
            .message(
                getString(
                    R.string.about_details,
                    BuildConfig.VERSION_NAME,
                    Build.VERSION.RELEASE
                )
            )
            .primary(R.string.close)
            .show()
    }
}
