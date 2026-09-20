package com.example.voicerecorder.ui

import android.content.Context
import android.os.Build
import androidx.lifecycle.lifecycleScope
import com.example.voicerecorder.BuildConfig
import com.example.voicerecorder.MainActivity
import com.example.voicerecorder.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
                onClick = { showStorage() }
            ),

            SettingsRow.Open(
                icon = R.drawable.ic_palette,
                title = getString(R.string.settings_appearance),
                subtitle = getString(R.string.settings_appearance_subtitle),
                onClick = { showNotAvailable(R.string.settings_appearance) }
            ),

            SettingsRow.Open(
                icon = R.drawable.ic_bell,
                title = getString(R.string.settings_notifications),
                subtitle = getString(R.string.settings_notifications_subtitle),
                onClick = { showNotAvailable(R.string.settings_notifications) }
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

    /**
     * Real numbers: how many notes are stored, how much space they take,
     * and how many recordings are still on the device.
     */
    private fun showStorage() {

        viewLifecycleOwner.lifecycleScope.launch {

            val summary =
                withContext(Dispatchers.IO) { storageSummary(requireContext()) }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_storage)
                .setMessage(summary)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun storageSummary(
        context: Context
    ): String {

        val recordings =
            Notes.recordings(context)

        val notes =
            recordings.sumOf { it.notes.size }

        val bytes =
            File(context.filesDir, "notes")
                .listFiles()
                ?.sumOf { it.length() }
                ?: 0L

        val keptAudio =
            context.contentResolver
                .query(
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(android.provider.MediaStore.MediaColumns._ID),
                    "${android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                    arrayOf(context.packageName),
                    null
                )
                ?.use { it.count }
                ?: 0

        return getString(
            R.string.storage_summary,
            recordings.size,
            notes,
            bytes / 1024f,
            keptAudio
        )
    }

    private fun showPrivacy() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_privacy)
            .setMessage(R.string.privacy_details)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_about)
            .setMessage(
                getString(
                    R.string.about_details,
                    BuildConfig.VERSION_NAME,
                    Build.VERSION.RELEASE
                )
            )
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showNotAvailable(
        title: Int
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(R.string.not_available_yet)
            .setPositiveButton(R.string.close, null)
            .show()
    }
}
