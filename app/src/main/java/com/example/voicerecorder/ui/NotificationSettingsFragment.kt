package com.example.voicerecorder.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.voicerecorder.R
import com.example.voicerecorder.reminders.AppNotifications
import com.example.voicerecorder.reminders.Reminders
import com.example.voicerecorder.settings.AppSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.concurrent.thread

/**
 * Settings > Notifications: the permission, task reminders and
 * processing updates.
 */
class NotificationSettingsFragment : SettingsPageFragment() {

    override val pageTitle = R.string.settings_notifications

    override val pageSubtitle = R.string.settings_notifications_page_subtitle

    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Denied for good: Android no longer asks, so open the app's settings instead.
            if (!granted) openNotificationSettings()
            renderRows()
        }

    override fun rows(): List<SettingsRow> {

        val allowed =
            AppNotifications.canPost(requireContext())

        val rows =
            mutableListOf<SettingsRow>(

                SettingsRow.Open(
                    icon = R.drawable.ic_bell,
                    title = getString(R.string.notifications_allow),
                    subtitle = getString(
                        if (allowed) R.string.notifications_allow_on else R.string.notifications_allow_off
                    ),
                    value = getString(if (allowed) R.string.value_on else R.string.value_off),
                    onClick = { allowNotifications() }
                ),

                SettingsRow.Toggle(
                    icon = R.drawable.ic_check,
                    title = getString(R.string.task_reminders),
                    subtitle = getString(R.string.task_reminders_subtitle),
                    checked = settings.remindersEnabled,
                    onChange = {
                        settings.remindersEnabled = it
                        syncReminders()
                    }
                ),

                SettingsRow.Open(
                    icon = R.drawable.ic_clock,
                    title = getString(R.string.early_reminder),
                    subtitle = getString(R.string.early_reminder_subtitle),
                    value = earlyLabel(settings.earlyReminderMinutes),
                    onClick = { chooseEarlyReminder() }
                )
            )

        // Android 12+ asks the user before an app may set alarms on the minute.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            val exact =
                Reminders.canBeExact(requireContext())

            rows += SettingsRow.Open(
                icon = R.drawable.ic_calendar,
                title = getString(R.string.exact_reminders),
                subtitle = getString(if (exact) R.string.exact_reminders_on else R.string.exact_reminders_off),
                value = getString(if (exact) R.string.value_allowed else R.string.value_not_allowed),
                onClick = { openExactAlarmSettings() }
            )
        }

        rows += SettingsRow.Toggle(
            icon = R.drawable.ic_sparkle,
            title = getString(R.string.processing_updates),
            subtitle = getString(R.string.processing_updates_subtitle),
            checked = settings.processingUpdates,
            onChange = { settings.processingUpdates = it }
        )

        return rows
    }

    override fun onResume() {
        super.onResume()
        // The permissions can change while the user is in Android's settings.
        renderRows()
    }

    private fun allowNotifications() {

        val canAsk =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED

        if (canAsk) {
            AppSettings(requireContext()).notificationPermissionAsked = true
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun chooseEarlyReminder() {

        val choices =
            AppSettings.EARLY_REMINDER_CHOICES

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.early_reminder)
            .setSingleChoiceItems(
                choices.map { earlyLabel(it) }.toTypedArray(),
                choices.indexOf(settings.earlyReminderMinutes)
            ) { dialog, index ->
                settings.earlyReminderMinutes = choices[index]
                syncReminders()
                renderRows()
                dialog.dismiss()
            }
            .show()
    }

    private fun earlyLabel(
        minutes: Int
    ): String =
        when (minutes) {
            0 -> getString(R.string.early_reminder_off)
            60 -> getString(R.string.early_reminder_hour)
            else -> getString(R.string.early_reminder_minutes, minutes)
        }

    /** Sets or cancels the alarms to match the new setting. */
    private fun syncReminders() {
        val context = requireContext().applicationContext
        thread { runCatching { Reminders.sync(context) } }
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
        )
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.fromParts("package", requireContext().packageName, null)
                )
            )
        }
    }
}
