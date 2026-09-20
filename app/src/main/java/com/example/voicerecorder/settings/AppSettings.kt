package com.example.voicerecorder.settings

import android.content.Context
import com.example.voicerecorder.summary.GeminiSummarizer

/**
 * The settings shown on the Settings screens, stored on the device.
 *
 * Every default matches what the app did before settings existed, so a
 * fresh install behaves exactly as before.
 */
class AppSettings(
    context: Context
) {

    private val prefs =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Sample rate and bit rate used while recording. */
    var recordingQuality: RecordingQuality
        get() = RecordingQuality.of(prefs.getString(KEY_QUALITY, null))
        set(value) = prefs.edit().putString(KEY_QUALITY, value.name).apply()

    /** Analyze a recording as soon as it is saved. */
    var autoProcess: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PROCESS, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_PROCESS, value).apply()

    /** Show the timer while recording. */
    var showDuration: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DURATION, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_DURATION, value).apply()

    /** Which Gemini model transcribes and writes the notes. */
    var geminiModel: String
        get() = prefs.getString(KEY_MODEL, null) ?: GeminiSummarizer.DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /** Allow processing on mobile data, not just Wi-Fi. */
    var processOnMobileData: Boolean
        get() = prefs.getBoolean(KEY_MOBILE_DATA, true)
        set(value) = prefs.edit().putBoolean(KEY_MOBILE_DATA, value).apply()

    /** Retry a recording when processing fails. */
    var retryFailed: Boolean
        get() = prefs.getBoolean(KEY_RETRY, true)
        set(value) = prefs.edit().putBoolean(KEY_RETRY, value).apply()

    /** Recordings shorter than this are not sent to Gemini. */
    var minimumSeconds: Int
        get() = prefs.getInt(KEY_MINIMUM_SECONDS, 3)
        set(value) = prefs.edit().putInt(KEY_MINIMUM_SECONDS, value).apply()

    /** Extra names and words Gemini should spell correctly. */
    var knownTerms: List<String>
        get() = prefs.getString(KEY_TERMS, null)
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        set(value) = prefs.edit().putString(KEY_TERMS, value.joinToString("\n")).apply()

    companion object {

        private const val NAME = "daytrace_settings"

        private const val KEY_QUALITY = "recording_quality"
        private const val KEY_AUTO_PROCESS = "auto_process"
        private const val KEY_SHOW_DURATION = "show_duration"
        private const val KEY_MODEL = "gemini_model"
        private const val KEY_MOBILE_DATA = "mobile_data"
        private const val KEY_RETRY = "retry_failed"
        private const val KEY_MINIMUM_SECONDS = "minimum_seconds"
        private const val KEY_TERMS = "known_terms"

        val MINIMUM_SECONDS_CHOICES =
            listOf(0, 3, 5, 10)

        /** Model id to the name shown on screen. */
        val MODELS =
            linkedMapOf(
                GeminiSummarizer.DEFAULT_MODEL to "3.5 Flash Lite",
                "gemini-3.5-flash" to "3.5 Flash",
                "gemini-flash-latest" to "Flash latest"
            )
    }
}

/**
 * Recording quality. HIGH is what the recorder has always used.
 */
enum class RecordingQuality(
    val label: String,
    val sampleRate: Int,
    val bitRate: Int
) {

    HIGH("High", 44_100, 128_000),
    MEDIUM("Medium", 44_100, 64_000),
    LOW("Low", 22_050, 32_000);

    companion object {

        fun of(
            name: String?
        ): RecordingQuality =
            values().firstOrNull { it.name == name } ?: HIGH
    }
}
