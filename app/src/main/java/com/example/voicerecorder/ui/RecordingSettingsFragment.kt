package com.example.voicerecorder.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.voicerecorder.R
import com.example.voicerecorder.settings.AppSettings
import com.example.voicerecorder.settings.RecordingQuality

/**
 * Settings > Recording.
 */
class RecordingSettingsFragment : SettingsPageFragment() {

    override val pageTitle = R.string.settings_recording

    override val pageSubtitle = R.string.settings_recording_page_subtitle

    override fun rows(): List<SettingsRow> =
        listOf(

            SettingsRow.Open(
                icon = R.drawable.ic_waveform,
                title = getString(R.string.recording_quality),
                subtitle = getString(R.string.recording_quality_subtitle),
                value = settings.recordingQuality.label,
                onClick = { chooseQuality() }
            ),

            SettingsRow.Toggle(
                icon = R.drawable.ic_settings,
                title = getString(R.string.auto_process),
                subtitle = getString(R.string.auto_process_subtitle),
                checked = settings.autoProcess,
                onChange = { settings.autoProcess = it }
            ),

            SettingsRow.Toggle(
                icon = R.drawable.ic_clock,
                title = getString(R.string.show_duration),
                subtitle = getString(R.string.show_duration_subtitle),
                checked = settings.showDuration,
                onChange = { settings.showDuration = it }
            ),

            SettingsRow.Open(
                icon = R.drawable.ic_mic,
                title = getString(R.string.microphone_permission),
                subtitle = getString(
                    if (microphoneGranted()) {
                        R.string.microphone_enabled
                    } else {
                        R.string.microphone_disabled
                    }
                ),
                onClick = { openAppSettings() }
            )
        )

    override fun onResume() {
        super.onResume()
        // The microphone row can change while the user is in Android settings.
        renderRows()
    }

    private fun chooseQuality() {

        val choices =
            RecordingQuality.values()

        DayTraceDialog(requireContext())
            .icon(R.drawable.ic_waveform)
            .title(R.string.recording_quality)
            .message(R.string.recording_quality_subtitle)
            .choices(
                choices.map {
                    DayTraceDialog.Choice(it.label, getString(R.string.quality_detail, it.bitRate / 1000, it.sampleRate / 1000f))
                },
                choices.indexOf(settings.recordingQuality)
            ) { index ->
                settings.recordingQuality = choices[index]
                renderRows()
            }
            .secondary(R.string.cancel)
            .show()
    }

    private fun microphoneGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requireContext().packageName, null)
            )
        )
    }
}
