package com.example.voicerecorder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.example.voicerecorder.service.RecordingService
import com.example.voicerecorder.summary.SummaryWorker

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

    private val microphonePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                startRecording()
            } else {
                tvStatus.text = "Microphone permission denied"

                Toast.makeText(
                    this,
                    "Microphone permission is required",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val recordingReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                when (intent?.action) {

                    RecordingService.ACTION_RECORDING_PROCESSING -> {

                        tvStatus.text =
                            "Converting to MP3..."

                        btnStart.isEnabled = false
                        btnStop.isEnabled = false
                    }

                    RecordingService.ACTION_RECORDING_COMPLETE -> {

                        tvStatus.text =
                            "Recording saved"

                        btnStart.isEnabled = true
                        btnStop.isEnabled = false

                        Toast.makeText(
                            this@MainActivity,
                            "Recording saved, analyzing…",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    RecordingService.ACTION_RECORDING_FAILED -> {

                        tvStatus.text =
                            "Recording failed"

                        btnStart.isEnabled = true
                        btnStop.isEnabled = false

                        Toast.makeText(
                            this@MainActivity,
                            "Could not save recording",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

    /*
     * Gemini summary status (see summary/SummaryWorker).
     *
     * Only touches the status text while the recorder is idle
     * (START enabled), so it never overwrites an active recording.
     */
    private val summaryReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                if (!btnStart.isEnabled) {
                    return
                }

                when (intent?.action) {

                    SummaryWorker.ACTION_SUMMARY_STARTED -> {

                        tvStatus.text =
                            "Analyzing recording…"
                    }

                    SummaryWorker.ACTION_SUMMARY_COMPLETE -> {

                        val categories =
                            intent.getStringArrayListExtra(
                                SummaryWorker.EXTRA_CATEGORIES
                            ).orEmpty()

                        val notes =
                            intent.getStringArrayListExtra(
                                SummaryWorker.EXTRA_NOTES
                            ).orEmpty()

                        val audioDeleted =
                            intent.getBooleanExtra(
                                SummaryWorker.EXTRA_AUDIO_DELETED,
                                false
                            )

                        val entries =
                            if (notes.isEmpty()) {
                                "Nothing worth keeping in this recording."
                            } else {
                                categories.zip(notes)
                                    .joinToString("\n\n") { (category, note) ->
                                        "$category: $note"
                                    }
                            }

                        val count =
                            if (notes.size == 1) "1 note"
                            else "${notes.size} notes"

                        val audio =
                            if (audioDeleted) "Notes saved. Recording deleted."
                            else "Recording kept."

                        tvStatus.text =
                            "Summary generated ($count)\n\n$entries\n\n$audio"
                    }

                    SummaryWorker.ACTION_SUMMARY_FAILED -> {

                        val error =
                            intent.getStringExtra(
                                SummaryWorker.EXTRA_ERROR
                            ).orEmpty()

                        tvStatus.text =
                            "Summary failed\n\n$error"
                    }
                }
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_main
        )

        btnStart =
            findViewById(R.id.btnStart)

        btnStop =
            findViewById(R.id.btnStop)

        tvStatus =
            findViewById(R.id.tvStatus)

        registerRecordingReceiver()

        registerSummaryReceiver()

        makeStatusScrollable()

        btnStart.setOnClickListener {

            checkMicrophonePermission()
        }

        btnStop.setOnClickListener {

            stopRecording()
        }

        btnStart.isEnabled = true
        btnStop.isEnabled = false

        tvStatus.text = "Ready"
    }

    private fun registerRecordingReceiver() {

        val filter =
            IntentFilter().apply {

                addAction(
                    RecordingService.ACTION_RECORDING_PROCESSING
                )

                addAction(
                    RecordingService.ACTION_RECORDING_COMPLETE
                )

                addAction(
                    RecordingService.ACTION_RECORDING_FAILED
                )
            }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            registerReceiver(
                recordingReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            @Suppress("DEPRECATION")
            registerReceiver(
                recordingReceiver,
                filter
            )
        }
    }

    private fun registerSummaryReceiver() {

        val filter =
            IntentFilter().apply {

                addAction(
                    SummaryWorker.ACTION_SUMMARY_STARTED
                )

                addAction(
                    SummaryWorker.ACTION_SUMMARY_COMPLETE
                )

                addAction(
                    SummaryWorker.ACTION_SUMMARY_FAILED
                )
            }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            registerReceiver(
                summaryReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            @Suppress("DEPRECATION")
            registerReceiver(
                summaryReceiver,
                filter
            )
        }
    }

    /*
     * One recording can produce many summary entries.
     * Cap the status height and let it scroll, so START/STOP
     * always stay on screen.
     */
    private fun makeStatusScrollable() {

        tvStatus.maxLines = 12

        tvStatus.movementMethod =
            ScrollingMovementMethod()

        // Every new status starts at the top.
        tvStatus.doAfterTextChanged {
            tvStatus.scrollTo(0, 0)
        }
    }

    private fun checkMicrophonePermission() {

        val permissionGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

        if (permissionGranted) {

            startRecording()

        } else {

            microphonePermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    private fun startRecording() {

        val intent =
            Intent(
                this,
                RecordingService::class.java
            ).apply {

                action =
                    RecordingService.ACTION_START
            }

        ContextCompat.startForegroundService(
            this,
            intent
        )

        tvStatus.text =
            "● Recording"

        btnStart.isEnabled = false
        btnStop.isEnabled = true
    }

    private fun stopRecording() {

        val intent =
            Intent(
                this,
                RecordingService::class.java
            ).apply {

                action =
                    RecordingService.ACTION_STOP
            }

        startService(intent)

        tvStatus.text =
            "Stopping..."

        btnStart.isEnabled = false
        btnStop.isEnabled = false
    }

    override fun onDestroy() {

        try {
            unregisterReceiver(
                recordingReceiver
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            unregisterReceiver(
                summaryReceiver
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        super.onDestroy()
    }
}