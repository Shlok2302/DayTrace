package com.example.voicerecorder.ui

import com.example.voicerecorder.MainActivity
import com.example.voicerecorder.R
import com.example.voicerecorder.settings.AppSettings

/**
 * Settings > AI & Processing.
 */
class AiSettingsFragment : SettingsPageFragment() {

    override val pageTitle = R.string.settings_ai

    override val pageSubtitle = R.string.settings_ai_page_subtitle

    override fun rows(): List<SettingsRow> =
        listOf(

            SettingsRow.Open(
                icon = R.drawable.ic_document,
                title = getString(R.string.gemini_model),
                subtitle = getString(R.string.gemini_model_subtitle),
                value = AppSettings.MODELS[settings.geminiModel] ?: settings.geminiModel,
                onClick = { chooseModel() }
            ),

            SettingsRow.Toggle(
                icon = R.drawable.ic_signal,
                title = getString(R.string.mobile_data),
                subtitle = getString(R.string.mobile_data_subtitle),
                checked = settings.processOnMobileData,
                onChange = { settings.processOnMobileData = it }
            ),

            SettingsRow.Toggle(
                icon = R.drawable.ic_refresh,
                title = getString(R.string.retry_failed),
                subtitle = getString(R.string.retry_failed_subtitle),
                checked = settings.retryFailed,
                onChange = { settings.retryFailed = it }
            ),

            SettingsRow.Open(
                icon = R.drawable.ic_clock,
                title = getString(R.string.minimum_duration),
                subtitle = getString(R.string.minimum_duration_subtitle),
                value = secondsLabel(settings.minimumSeconds),
                onClick = { chooseMinimumDuration() }
            ),

            SettingsRow.Open(
                icon = R.drawable.ic_text,
                title = getString(R.string.known_terms),
                subtitle = getString(R.string.known_terms_subtitle),
                onClick = {
                    (activity as? MainActivity)?.open(KnownTermsFragment())
                }
            )
        )

    private fun chooseModel() {

        val ids =
            AppSettings.MODELS.keys.toList()

        DayTraceDialog(requireContext())
            .icon(R.drawable.ic_sparkle)
            .title(R.string.gemini_model)
            .message(R.string.gemini_model_subtitle)
            .choices(
                AppSettings.MODELS.map { (id, name) -> DayTraceDialog.Choice(name, id) },
                ids.indexOf(settings.geminiModel).coerceAtLeast(0)
            ) { index ->
                settings.geminiModel = ids[index]
                renderRows()
            }
            .secondary(R.string.cancel)
            .show()
    }

    private fun chooseMinimumDuration() {

        val choices =
            AppSettings.MINIMUM_SECONDS_CHOICES

        DayTraceDialog(requireContext())
            .icon(R.drawable.ic_clock)
            .title(R.string.minimum_duration)
            .message(R.string.minimum_duration_subtitle)
            .choices(
                choices.map { DayTraceDialog.Choice(secondsLabel(it)) },
                choices.indexOf(settings.minimumSeconds).coerceAtLeast(0)
            ) { index ->
                settings.minimumSeconds = choices[index]
                renderRows()
            }
            .secondary(R.string.cancel)
            .show()
    }

    private fun secondsLabel(
        seconds: Int
    ): String =
        if (seconds == 0) {
            getString(R.string.no_minimum)
        } else {
            getString(R.string.seconds_format, seconds)
        }
}
