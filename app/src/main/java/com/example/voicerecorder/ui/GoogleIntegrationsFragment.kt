package com.example.voicerecorder.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.voicerecorder.R
import com.example.voicerecorder.google.GoogleConsent
import com.example.voicerecorder.google.GoogleIntegrationManager
import com.example.voicerecorder.google.GoogleService
import com.example.voicerecorder.google.GoogleSettings
import com.example.voicerecorder.google.PendingCalendarAdd
import com.example.voicerecorder.google.ServiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Settings > Google Integrations: the Google account, and Google Calendar
 * with where its events go. Each service is connected on its own and
 * shows whether Google still allows it.
 */
class GoogleIntegrationsFragment : SettingsPageFragment() {

    override val pageTitle = R.string.settings_google

    override val pageSubtitle = R.string.settings_google_page_subtitle

    private val manager by lazy { GoogleIntegrationManager(requireContext()) }

    private val calendar by lazy { CalendarFlow(this) { refresh() } }

    /** Asked from Google when the page opens; null until then. */
    private var calendarState: ServiceState? = null

    private var waiting: List<PendingCalendarAdd> = emptyList()

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        GoogleIntegrationManager.observe(requireContext(), viewLifecycleOwner) { refresh(checkGoogle = false) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh(
        checkGoogle: Boolean = true
    ) {

        if (view == null) {
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {

            waiting = withContext(Dispatchers.IO) { manager.store.pending() }
            renderRows()

            if (checkGoogle) {
                calendarState = manager.state(GoogleService.CALENDAR)
                renderRows()
            }
        }
    }

    override fun rows(): List<SettingsRow> {

        val settings =
            manager.settings

        val email =
            settings.accountEmail

        val calendarOn =
            manager.isConnected(GoogleService.CALENDAR)

        val needsReconnect =
            calendarOn && calendarState == ServiceState.NEEDS_RECONNECT

        val calendarName =
            settings.calendarName ?: email.orEmpty()

        val rows =
            mutableListOf<SettingsRow>(

                SettingsRow.Open(
                    icon = R.drawable.ic_account,
                    title = getString(R.string.google_account),
                    subtitle = email ?: getString(R.string.google_account_not_connected),
                    value = getString(if (email != null) R.string.google_connected else R.string.google_connect),
                    onClick = { if (email == null) connectAccount() else showAccount(email) }
                ),

                SettingsRow.Open(
                    icon = R.drawable.ic_calendar,
                    title = getString(R.string.google_calendar),
                    subtitle = when {
                        !calendarOn -> getString(R.string.google_calendar_subtitle_off)
                        needsReconnect -> getString(R.string.google_calendar_subtitle_reconnect)
                        calendarState == ServiceState.CANNOT_CHECK -> getString(R.string.google_calendar_subtitle_unchecked)
                        else -> getString(R.string.google_calendar_subtitle_on, calendarName)
                    },
                    value = getString(
                        when {
                            !calendarOn -> R.string.google_connect
                            needsReconnect -> R.string.google_reconnect
                            else -> R.string.google_connected
                        }
                    ),
                    onClick = { openCalendar(calendarOn, needsReconnect, calendarName) }
                )
            )

        if (calendarOn) {

            rows += SettingsRow.Open(
                icon = R.drawable.ic_calendar_add,
                title = getString(R.string.google_calendar_choose),
                subtitle = getString(R.string.google_calendar_choose_subtitle),
                value = calendarName,
                onClick = {
                    calendar.chooseCalendar(settings.calendarId) {
                        settings.calendarId = it.id
                        settings.calendarName = it.name
                        renderRows()
                    }
                }
            )

            rows += SettingsRow.Open(
                icon = R.drawable.ic_bell,
                title = getString(R.string.google_event_reminders),
                subtitle = getString(R.string.google_event_reminders_subtitle),
                value = CalendarText.reminder(requireContext(), settings.eventReminderMinutes),
                onClick = {
                    calendar.chooseReminder(settings.eventReminderMinutes) {
                        settings.eventReminderMinutes = it
                        renderRows()
                    }
                }
            )

            rows += SettingsRow.Open(
                icon = R.drawable.ic_clock,
                title = getString(R.string.google_event_length),
                subtitle = getString(R.string.google_event_length_subtitle),
                value = CalendarText.length(requireContext(), settings.eventLengthMinutes),
                onClick = { chooseLength() }
            )
        }

        if (waiting.isNotEmpty()) {
            rows += SettingsRow.Open(
                icon = R.drawable.ic_refresh,
                title = getString(R.string.google_waiting),
                subtitle = resources.getQuantityString(R.plurals.google_waiting_subtitle, waiting.size, waiting.size),
                onClick = { showWaiting() }
            )
        }

        rows += SettingsRow.Open(
            icon = R.drawable.ic_shield,
            title = getString(R.string.google_privacy),
            subtitle = getString(R.string.google_privacy_subtitle),
            onClick = { showPrivacy() }
        )

        return rows
    }

    // Account ---------------------------------------------------------------

    private fun connectAccount() {

        val consent =
            (activity as? GoogleConsent.Host)?.googleConsent ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val email = manager.connectAccount(consent)
                refresh()
                DayTraceDialog(requireContext())
                    .tone(DayTraceDialog.Tone.SUCCESS)
                    .icon(R.drawable.ic_check_circle)
                    .title(R.string.google_account_connected_title)
                    .message(getString(R.string.google_account_connected_detail, email))
                    .primary(R.string.google_connect_calendar_now, arrow = true) { connectCalendar() }
                    .secondary(R.string.later)
                    .show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                calendar.showError(e, R.string.google_error_connect_title, retry = { connectAccount() })
            }
        }
    }

    private fun showAccount(
        email: String
    ) {

        val services =
            if (manager.isConnected(GoogleService.CALENDAR)) {
                getString(R.string.google_account_services, getString(R.string.google_calendar))
            } else {
                getString(R.string.google_account_no_services)
            }

        DayTraceDialog(requireContext())
            .icon(R.drawable.ic_account)
            .title(R.string.google_account)
            .message(getString(R.string.google_account_connected_as, email) + "\n\n" + services)
            .primary(R.string.close)
            .extra(R.string.google_disconnect_account, destructive = true) { confirmDisconnectAccount() }
            .show()
    }

    private fun confirmDisconnectAccount() {

        DayTraceDialog(requireContext())
            .tone(DayTraceDialog.Tone.DANGER)
            .icon(R.drawable.ic_link)
            .title(R.string.google_disconnect_account_title)
            .message(R.string.google_disconnect_account_detail)
            .warning(
                if (waiting.isEmpty()) null
                else resources.getQuantityString(R.plurals.google_disconnect_cancels, waiting.size, waiting.size)
            )
            .primary(R.string.google_disconnect) { disconnectAccount() }
            .secondary(R.string.cancel)
            .show()
    }

    private fun disconnectAccount() {

        viewLifecycleOwner.lifecycleScope.launch {

            val revoked =
                manager.disconnectAccount()

            calendarState = null
            refresh(checkGoogle = false)

            if (revoked) {
                Toast.makeText(requireContext(), R.string.google_disconnected, Toast.LENGTH_SHORT).show()
            } else {
                DayTraceDialog(requireContext())
                    .tone(DayTraceDialog.Tone.WARNING)
                    .icon(R.drawable.ic_warning)
                    .title(R.string.google_disconnected_offline_title)
                    .message(R.string.google_disconnected_offline)
                    .primary(R.string.ok)
                    .show()
            }
        }
    }

    // Google Calendar -------------------------------------------------------

    private fun openCalendar(
        connected: Boolean,
        needsReconnect: Boolean,
        calendarName: String
    ) {
        when {
            !connected -> calendar.askToConnect { connected() }
            needsReconnect -> calendar.reconnect { connected() }
            else -> DayTraceDialog(requireContext())
                .icon(R.drawable.ic_calendar)
                .title(R.string.google_calendar)
                .message(getString(R.string.google_calendar_settings_detail, calendarName))
                .primary(R.string.close)
                .extra(R.string.google_disconnect_calendar, destructive = true) { confirmDisconnectCalendar() }
                .show()
        }
    }

    private fun connectCalendar() {
        calendar.connectCalendar { connected() }
    }

    private fun connected() {

        calendarState = ServiceState.CONNECTED
        refresh(checkGoogle = false)

        DayTraceDialog(requireContext())
            .tone(DayTraceDialog.Tone.SUCCESS)
            .icon(R.drawable.ic_check_circle)
            .title(R.string.google_calendar_connected_title)
            .message(
                getString(
                    R.string.google_calendar_connected_detail,
                    manager.settings.calendarName ?: manager.accountEmail.orEmpty()
                )
            )
            .primary(R.string.ok)
            .show()
    }

    private fun confirmDisconnectCalendar() {

        DayTraceDialog(requireContext())
            .tone(DayTraceDialog.Tone.DANGER)
            .icon(R.drawable.ic_calendar)
            .title(R.string.google_disconnect_calendar_title)
            .message(R.string.google_disconnect_calendar_detail)
            .warning(
                if (waiting.isEmpty()) null
                else resources.getQuantityString(R.plurals.google_disconnect_cancels, waiting.size, waiting.size)
            )
            .primary(R.string.google_disconnect) {
                manager.disconnect(GoogleService.CALENDAR)
                calendarState = ServiceState.OFF
                refresh(checkGoogle = false)
                Toast.makeText(requireContext(), R.string.google_calendar_disconnected, Toast.LENGTH_SHORT).show()
            }
            .secondary(R.string.cancel)
            .show()
    }

    private fun chooseLength() {

        val settings =
            manager.settings

        val choices =
            GoogleSettings.LENGTH_CHOICES

        DayTraceDialog(requireContext())
            .icon(R.drawable.ic_clock)
            .title(R.string.google_event_length)
            .message(R.string.google_length_message)
            .choices(
                choices.map { DayTraceDialog.Choice(CalendarText.length(requireContext(), it)) },
                choices.indexOf(settings.eventLengthMinutes).takeIf { it >= 0 }
            ) { index ->
                settings.eventLengthMinutes = choices[index]
                renderRows()
            }
            .secondary(R.string.cancel)
            .show()
    }

    /** The events the user confirmed that are not in Google Calendar yet. */
    private fun showWaiting() {

        val lines =
            waiting.joinToString("\n") { pending ->
                val title = runCatching { JSONObject(pending.event).optString("summary") }.getOrDefault("")
                "• $title · ${pending.whenText}"
            }

        DayTraceDialog(requireContext())
            .tone(DayTraceDialog.Tone.INFO)
            .icon(R.drawable.ic_refresh)
            .title(R.string.google_waiting)
            .message(getString(R.string.google_waiting_detail, lines))
            .primary(R.string.google_try_now) {
                manager.resumePending()
                refresh(checkGoogle = false)
            }
            .secondary(R.string.close)
            .extra(R.string.google_cancel_waiting, destructive = true) {
                val context = requireContext().applicationContext
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { manager.store.clearPending() }
                    GoogleIntegrationManager.notifyChanged(context)
                }
            }
            .show()
    }

    private fun showPrivacy() {
        DayTraceDialog(requireContext())
            .tone(DayTraceDialog.Tone.INFO)
            .icon(R.drawable.ic_shield)
            .title(R.string.google_privacy)
            .message(R.string.google_privacy_details)
            .primary(R.string.close)
            .show()
    }
}
