package com.example.voicerecorder.summary

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.voicerecorder.DayTraceApplication
import com.example.voicerecorder.encoder.Mp3Converter
import com.example.voicerecorder.reminders.AppNotifications
import com.example.voicerecorder.settings.AppSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * The first step of importing an audio file: the step a recording gets
 * in RecordingService. Copies the picked file, converts the copy to MP3
 * with Mp3Converter, saves it to Music/App Records and queues it for
 * SummaryWorker, the normal pipeline. The picked file is only read.
 */
class ImportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {

        val source =
            inputData.getString(KEY_SOURCE)?.let(Uri::parse) ?: return Result.failure()

        val name =
            inputData.getString(KEY_NAME) ?: return Result.failure()

        val originalName =
            inputData.getString(KEY_ORIGINAL_NAME).orEmpty()

        val folder =
            File(applicationContext.cacheDir, "import").apply { mkdirs() }

        val copy =
            File(folder, "$name." + originalName.substringAfterLast('.', "audio").filter { it.isLetterOrDigit() }.take(8).ifEmpty { "audio" })

        val mp3 =
            File(folder, "$name.mp3")

        broadcast(ACTION_IMPORT_STARTED)

        try {
            val copied =
                runCatching {
                    applicationContext.contentResolver.openInputStream(source)!!.use { input ->
                        copy.outputStream().use { input.copyTo(it) }
                    }
                    copy.length() > 0
                }.getOrDefault(false)

            if (!copied) {
                return fail(name, "DayTrace couldn't read $originalName.")
            }

            Log.i(TAG, "Converting $originalName (${copy.length()} bytes) to MP3")

            if (!convert(copy, mp3)) {
                return fail(name, "$originalName couldn't be read as audio. Try an MP3, M4A, WAV, OGG, OPUS, FLAC or AMR file.")
            }

            val saved =
                AudioImport.saveToAppRecords(applicationContext, mp3, "$name.mp3")
                    ?: return fail(name, "The converted audio couldn't be saved on this device.")

            Log.i(TAG, "Imported $originalName as $saved; handing it to SummaryWorker")

            // From here on it is processed exactly like a recording.
            SummaryWorker.enqueue(applicationContext, saved.toString())

            broadcast(ACTION_IMPORT_READY) { putExtra(SummaryWorker.EXTRA_AUDIO_URI, saved.toString()) }

            return Result.success()

        } finally {
            copy.delete()
            mp3.delete()
            runCatching {
                applicationContext.contentResolver.releasePersistableUriPermission(
                    source,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    private suspend fun convert(
        input: File,
        output: File
    ): Boolean =
        suspendCancellableCoroutine { continuation ->
            // An import becomes a DayTrace recording and is stored and
            // uploaded like one, so it follows the same quality setting.
            Mp3Converter.convert(input, output, AppSettings(applicationContext).recordingQuality) { success ->
                if (continuation.isActive) continuation.resume(success)
            }
        }

    private fun fail(
        name: String,
        message: String
    ): Result {

        Log.e(TAG, "Import failed: $message")

        // Frees the name; nothing was saved.
        NoteStore(applicationContext).clearImportInfo(name)

        broadcast(ACTION_IMPORT_FAILED) { putExtra(SummaryWorker.EXTRA_ERROR, message) }

        if (AppSettings(applicationContext).processingUpdates && !DayTraceApplication.isInForeground) {
            runCatching { AppNotifications.showImportFailed(applicationContext, message) }
        }

        return Result.failure()
    }

    private fun broadcast(
        action: String,
        extras: Intent.() -> Unit = {}
    ) {
        applicationContext.sendBroadcast(
            Intent(action).setPackage(applicationContext.packageName).apply(extras)
        )
    }

    companion object {

        private const val TAG =
            "ImportWorker"

        /** An import started: the file is being converted. */
        const val ACTION_IMPORT_STARTED =
            "com.example.voicerecorder.IMPORT_STARTED"

        /** Converted and queued; SummaryWorker takes over. Has SummaryWorker.EXTRA_AUDIO_URI. */
        const val ACTION_IMPORT_READY =
            "com.example.voicerecorder.IMPORT_READY"

        /** The file could not be imported. Has SummaryWorker.EXTRA_ERROR. */
        const val ACTION_IMPORT_FAILED =
            "com.example.voicerecorder.IMPORT_FAILED"

        private const val KEY_SOURCE =
            "source"

        private const val KEY_NAME =
            "name"

        private const val KEY_ORIGINAL_NAME =
            "original_name"

        fun enqueue(
            context: Context,
            source: Uri,
            name: String,
            originalName: String
        ) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "import:$name",
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<ImportWorker>()
                        .setInputData(
                            workDataOf(
                                KEY_SOURCE to source.toString(),
                                KEY_NAME to name,
                                KEY_ORIGINAL_NAME to originalName
                            )
                        )
                        .build()
                )
        }
    }
}
