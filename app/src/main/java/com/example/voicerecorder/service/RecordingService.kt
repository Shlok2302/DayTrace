package com.example.voicerecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.voicerecorder.MainActivity
import com.example.voicerecorder.R
import com.example.voicerecorder.encoder.Mp3Converter
import com.example.voicerecorder.settings.AppSettings
import java.io.File

class RecordingService : Service() {

    private var mediaRecorder: MediaRecorder? = null

    private var recordingFile: File? = null

    companion object {

        private const val TAG =
            "RecordingService"

        const val ACTION_START =
            "ACTION_START_RECORDING"

        const val ACTION_STOP =
            "ACTION_STOP_RECORDING"

        const val ACTION_RECORDING_PROCESSING =
            "com.example.voicerecorder.RECORDING_PROCESSING"

        const val ACTION_RECORDING_COMPLETE =
            "com.example.voicerecorder.RECORDING_COMPLETE"

        const val ACTION_RECORDING_FAILED =
            "com.example.voicerecorder.RECORDING_FAILED"

        const val EXTRA_FILE_PATH =
            "file_path"

        private const val CHANNEL_ID =
            "voice_recording_channel"

        private const val NOTIFICATION_ID =
            1001

        /**
         * True only while the microphone is really recording. The record
         * screen checks it, so it never shows "Recording..." when the
         * recorder failed to start or has already stopped.
         */
        @Volatile
        var isRecording: Boolean = false
            private set
    }

    override fun onCreate() {

        super.onCreate()

        Log.d(
            TAG,
            "Service created"
        )

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_START -> {

                Log.d(
                    TAG,
                    "START received"
                )

                startRecording()
            }

            ACTION_STOP -> {

                Log.d(
                    TAG,
                    "STOP received"
                )

                stopRecording()
            }
        }

        return START_STICKY
    }

    private fun startRecording() {

        if (mediaRecorder != null) {

            Log.d(
                TAG,
                "Recording already active"
            )

            return
        }

        /*
         * Temporary recording location.
         *
         * This M4A is private and is only used while
         * recording/converting.
         */
        val recordingsDirectory =
            File(
                filesDir,
                "Recordings"
            )

        if (!recordingsDirectory.exists()) {

            recordingsDirectory.mkdirs()
        }

        val fileName =
            "Voice_Recording_${System.currentTimeMillis()}.m4a"

        val file =
            File(
                recordingsDirectory,
                fileName
            )

        recordingFile = file

        Log.d(
            TAG,
            "Temporary M4A: ${file.absolutePath}"
        )

        try {

            /*
             * Start foreground mode before recording.
             */
            startForegroundNotification()

            mediaRecorder =
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.S
                ) {

                    MediaRecorder(this)

                } else {

                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

            mediaRecorder?.apply {

                setAudioSource(
                    MediaRecorder.AudioSource.MIC
                )

                setOutputFormat(
                    MediaRecorder.OutputFormat.MPEG_4
                )

                setAudioEncoder(
                    MediaRecorder.AudioEncoder.AAC
                )

                /*
                 * From Settings > Recording. "High" is the original
                 * 44100 Hz / 128 kbps, and is the default.
                 */
                val quality =
                    AppSettings(this@RecordingService).recordingQuality

                setAudioSamplingRate(
                    quality.sampleRate
                )

                setAudioEncodingBitRate(
                    quality.bitRate
                )

                setOutputFile(
                    file.absolutePath
                )

                prepare()

                start()
            }

            isRecording =
                true

            Log.d(
                TAG,
                "Recording started"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to start recording",
                e
            )

            mediaRecorder?.release()

            mediaRecorder = null

            recordingFile = null

            isRecording = false

            sendBroadcastAction(
                ACTION_RECORDING_FAILED
            )

            stopForeground(
                STOP_FOREGROUND_REMOVE
            )

            stopSelf()
        }
    }

    private fun stopRecording() {

        val recorder =
            mediaRecorder

        if (recorder == null) {

            Log.d(
                TAG,
                "No active recorder"
            )

            stopSelf()

            return
        }

        isRecording =
            false

        Log.d(
            TAG,
            "Stopping recorder"
        )

        var stopped =
            true

        try {

            recorder.stop()

            Log.d(
                TAG,
                "MediaRecorder stopped"
            )

        } catch (e: RuntimeException) {

            stopped = false

            Log.e(
                TAG,
                "MediaRecorder stop failed",
                e
            )
        }

        try {
            recorder.reset()
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Recorder reset failed",
                e
            )
        }

        try {
            recorder.release()
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Recorder release failed",
                e
            )
        }

        mediaRecorder = null

        val inputFile =
            recordingFile

        recordingFile = null

        if (
            !stopped ||
            inputFile == null ||
            !inputFile.exists() ||
            inputFile.length() <= 0
        ) {

            Log.e(
                TAG,
                "Invalid recording file"
            )

            sendBroadcastAction(
                ACTION_RECORDING_FAILED
            )

            stopForeground(
                STOP_FOREGROUND_REMOVE
            )

            stopSelf()

            return
        }

        Log.d(
            TAG,
            "M4A exists: ${inputFile.exists()}"
        )

        Log.d(
            TAG,
            "M4A size: ${inputFile.length()}"
        )

        convertToMp3(
            inputFile
        )
    }

    private fun convertToMp3(
        inputFile: File
    ) {

        updateNotification(
            "Processing recording..."
        )

        sendBroadcastAction(
            ACTION_RECORDING_PROCESSING
        )

        val temporaryMp3 =
            File(
                inputFile.parentFile,
                inputFile.nameWithoutExtension +
                        ".mp3"
            )

        Log.d(
            TAG,
            "Starting FFmpeg conversion"
        )

        Log.d(
            TAG,
            "Input: ${inputFile.absolutePath}"
        )

        Log.d(
            TAG,
            "Temporary MP3: ${temporaryMp3.absolutePath}"
        )

        Mp3Converter.convert(
            inputFile,
            temporaryMp3,
            AppSettings(this).recordingQuality
        ) { success ->

            Log.d(
                TAG,
                "FFmpeg success: $success"
            )

            Log.d(
                TAG,
                "MP3 exists: ${temporaryMp3.exists()}"
            )

            Log.d(
                TAG,
                "MP3 size: ${temporaryMp3.length()}"
            )

            if (
                success &&
                temporaryMp3.exists() &&
                temporaryMp3.length() > 0
            ) {

                /*
                 * Now put the MP3 into:
                 *
                 * Internal storage/Music/App Records/
                 */
                val savedUri =
                    saveMp3ToMusicFolder(
                        temporaryMp3
                    )

                if (savedUri != null) {

                    Log.d(
                        TAG,
                        "MP3 saved to MediaStore: $savedUri"
                    )

                    /*
                     * Delete private temporary files
                     * only after the public/user-visible
                     * MP3 has been successfully created.
                     */
                    try {
                        inputFile.delete()
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Could not delete M4A",
                            e
                        )
                    }

                    try {
                        temporaryMp3.delete()
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Could not delete temporary MP3",
                            e
                        )
                    }

                    sendCompletedBroadcast(
                        savedUri
                    )

                } else {

                    Log.e(
                        TAG,
                        "Could not save MP3 to Music/App Records"
                    )

                    sendBroadcastAction(
                        ACTION_RECORDING_FAILED
                    )
                }

            } else {

                Log.e(
                    TAG,
                    "MP3 conversion failed"
                )

                /*
                 * Keep the M4A for debugging if conversion
                 * fails.
                 */
                sendBroadcastAction(
                    ACTION_RECORDING_FAILED
                )
            }

            stopForeground(
                STOP_FOREGROUND_REMOVE
            )

            stopSelf()
        }
    }

    /**
     * Saves the finished MP3 into:
     *
     * Internal storage/Music/App Records/
     *
     * using Android MediaStore.
     */
    private fun saveMp3ToMusicFolder(
        mp3File: File
    ): Uri? {

        val resolver =
            contentResolver

        val fileName =
            mp3File.name

        val values =
            ContentValues().apply {

                put(
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    fileName
                )

                put(
                    MediaStore.Audio.Media.MIME_TYPE,
                    "audio/mpeg"
                )

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.Q
                ) {

                    put(
                        MediaStore.Audio.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MUSIC +
                                "/App Records"
                    )

                    put(
                        MediaStore.Audio.Media.IS_PENDING,
                        1
                    )
                }
            }

        val uri =
            resolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                values
            )

        if (uri == null) {

            Log.e(
                TAG,
                "MediaStore insert returned null"
            )

            return null
        }

        try {

            resolver.openOutputStream(
                uri
            ).use { outputStream ->

                if (outputStream == null) {

                    throw Exception(
                        "Could not open MediaStore output stream"
                    )
                }

                mp3File.inputStream().use { inputStream ->

                    inputStream.copyTo(
                        outputStream
                    )
                }
            }

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {

                val completedValues =
                    ContentValues().apply {

                        put(
                            MediaStore.Audio.Media.IS_PENDING,
                            0
                        )
                    }

                resolver.update(
                    uri,
                    completedValues,
                    null,
                    null
                )
            }

            return uri

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to copy MP3 to MediaStore",
                e
            )

            resolver.delete(
                uri,
                null,
                null
            )

            return null
        }
    }

    private fun startForegroundNotification() {

        val notification =
            createNotification(
                "Recording is active"
            )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.R
        ) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_MICROPHONE
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun updateNotification(
        text: String
    ) {

        val notification =
            createNotification(text)

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            NOTIFICATION_ID,
            notification
        )
    }

    private fun createNotification(
        text: String
    ): Notification {

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle(
                "Voice Recorder"
            )
            .setContentText(
                text
            )
            .setSmallIcon(
                R.drawable.ic_mic
            )
            .setContentIntent(
                openRecorder()
            )
            .setOngoing(true)
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .build()
    }

    /**
     * Tapping the notification opens the app on the record screen,
     * where the recording can be stopped.
     */
    private fun openRecorder(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_RECORDER, true)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Voice Recording",
                    NotificationManager.IMPORTANCE_LOW
                )

            channel.description =
                "Voice recording status"

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                channel
            )
        }
    }

    private fun sendBroadcastAction(
        action: String
    ) {

        val intent =
            Intent(action)

        intent.setPackage(
            packageName
        )

        sendBroadcast(intent)
    }

    private fun sendCompletedBroadcast(
        uri: Uri
    ) {

        val intent =
            Intent(
                ACTION_RECORDING_COMPLETE
            )

        intent.setPackage(
            packageName
        )

        intent.putExtra(
            EXTRA_FILE_PATH,
            uri.toString()
        )

        sendBroadcast(intent)
    }

    /**
     * Removing the app from Recents must not stop
     * the foreground recording service.
     */
    override fun onTaskRemoved(
        rootIntent: Intent?
    ) {

        Log.d(
            TAG,
            "Application removed from Recents"
        )

        super.onTaskRemoved(
            rootIntent
        )
    }

    override fun onDestroy() {

        /*
         * Stopped without STOP (e.g. by the system): free the microphone
         * and let the record screen know nothing is recording any more.
         */
        if (mediaRecorder != null) {

            Log.w(
                TAG,
                "Service destroyed while recording"
            )

            runCatching { mediaRecorder?.release() }

            mediaRecorder = null

            sendBroadcastAction(
                ACTION_RECORDING_FAILED
            )
        }

        isRecording = false

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }
}