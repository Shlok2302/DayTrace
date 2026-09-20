package com.example.voicerecorder.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.voicerecorder.MainActivity
import com.example.voicerecorder.R
import com.example.voicerecorder.service.RecordingService
import com.example.voicerecorder.summary.SavedRecording
import com.example.voicerecorder.summary.SummaryWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Record tab: idle -> recording -> processing -> analyzing -> all done,
 * driven by the broadcasts RecordingService and SummaryWorker already send.
 */
class RecordFragment : Fragment(R.layout.fragment_record) {

    private enum class Stage { IDLE, RECORDING, CONVERTING, ANALYZING, SAVING, DONE, FAILED }

    private lateinit var stateIdle: View
    private lateinit var stateRecording: View
    private lateinit var stateProgress: View
    private lateinit var stateDone: View

    private lateinit var tvTimer: TextView
    private lateinit var tvProgressTitle: TextView
    private lateinit var tvProgressSubtitle: TextView
    private lateinit var tvProgressHint: TextView
    private lateinit var imgProgress: ImageView
    private lateinit var steps: android.widget.LinearLayout

    private lateinit var imgDone: ImageView
    private lateinit var tvDoneTitle: TextView
    private lateinit var tvDoneSubtitle: TextView
    private lateinit var doneCard: View
    private lateinit var tvDoneCategory: TextView
    private lateinit var tvDoneSummary: TextView
    private lateinit var tvDoneSavedOn: TextView
    private lateinit var btnDone: TextView

    private val handler =
        Handler(Looper.getMainLooper())

    private val timerTick =
        object : Runnable {
            override fun run() {
                updateTimer()
                handler.postDelayed(this, 1000L)
            }
        }

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecording()
            } else {
                Toast.makeText(requireContext(), R.string.microphone_required, Toast.LENGTH_LONG).show()
            }
        }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                onPipelineEvent(intent ?: return)
            }
        }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        stateIdle = view.findViewById(R.id.stateIdle)
        stateRecording = view.findViewById(R.id.stateRecording)
        stateProgress = view.findViewById(R.id.stateProgress)
        stateDone = view.findViewById(R.id.stateDone)

        tvTimer = view.findViewById(R.id.tvTimer)
        tvProgressTitle = view.findViewById(R.id.tvProgressTitle)
        tvProgressSubtitle = view.findViewById(R.id.tvProgressSubtitle)
        tvProgressHint = view.findViewById(R.id.tvProgressHint)
        imgProgress = view.findViewById(R.id.imgProgress)
        steps = view.findViewById(R.id.steps)

        imgDone = view.findViewById(R.id.imgDone)
        tvDoneTitle = view.findViewById(R.id.tvDoneTitle)
        tvDoneSubtitle = view.findViewById(R.id.tvDoneSubtitle)
        doneCard = view.findViewById(R.id.doneCard)
        tvDoneCategory = view.findViewById(R.id.tvDoneCategory)
        tvDoneSummary = view.findViewById(R.id.tvDoneSummary)
        tvDoneSavedOn = view.findViewById(R.id.tvDoneSavedOn)
        btnDone = view.findViewById(R.id.btnDone)

        buildSteps()

        view.findViewById<View>(R.id.btnStart).setOnClickListener { onStartClicked() }
        view.findViewById<View>(R.id.btnStop).setOnClickListener { stopRecording() }
        view.findViewById<View>(R.id.btnSettings).setOnClickListener {
            (activity as? MainActivity)?.openSettings()
        }

        btnDone.setOnClickListener {
            if (State.stage == Stage.FAILED) {
                State.reset()
                render()
            } else {
                (activity as? MainActivity)?.openHistory()
            }
        }

        render()
    }

    override fun onStart() {
        super.onStart()

        val filter =
            IntentFilter().apply {
                addAction(RecordingService.ACTION_RECORDING_PROCESSING)
                addAction(RecordingService.ACTION_RECORDING_COMPLETE)
                addAction(RecordingService.ACTION_RECORDING_FAILED)
                addAction(SummaryWorker.ACTION_SUMMARY_STARTED)
                addAction(SummaryWorker.ACTION_SUMMARY_SAVING)
                addAction(SummaryWorker.ACTION_SUMMARY_COMPLETE)
                addAction(SummaryWorker.ACTION_SUMMARY_FAILED)
            }

        ContextCompat.registerReceiver(
            requireContext(),
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        State.expireIfStale()
        render()
    }

    override fun onStop() {
        runCatching { requireContext().unregisterReceiver(receiver) }
        handler.removeCallbacks(timerTick)
        super.onStop()
    }

    // Recording ----------------------------------------------------------

    private fun onStartClicked() {

        val granted =
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startRecording()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {

        val intent =
            Intent(requireContext(), RecordingService::class.java)
                .apply { action = RecordingService.ACTION_START }

        ContextCompat.startForegroundService(requireContext(), intent)

        State.stage = Stage.RECORDING
        State.startedAt = SystemClock.elapsedRealtime()
        State.touch()

        render()
    }

    private fun stopRecording() {

        val intent =
            Intent(requireContext(), RecordingService::class.java)
                .apply { action = RecordingService.ACTION_STOP }

        requireContext().startService(intent)

        State.stage = Stage.CONVERTING
        State.touch()

        render()
    }

    // Pipeline events ----------------------------------------------------

    private fun onPipelineEvent(
        intent: Intent
    ) {

        when (intent.action) {

            RecordingService.ACTION_RECORDING_PROCESSING ->
                State.stage = Stage.CONVERTING

            RecordingService.ACTION_RECORDING_COMPLETE ->
                State.stage = Stage.ANALYZING

            SummaryWorker.ACTION_SUMMARY_STARTED ->
                State.stage = Stage.ANALYZING

            SummaryWorker.ACTION_SUMMARY_SAVING ->
                State.stage = Stage.SAVING

            SummaryWorker.ACTION_SUMMARY_COMPLETE -> {
                State.stage = Stage.DONE
                State.audioUri = intent.getStringExtra(SummaryWorker.EXTRA_AUDIO_URI)
                State.message = null
                loadResult()
            }

            RecordingService.ACTION_RECORDING_FAILED -> {
                State.stage = Stage.FAILED
                State.message = getString(R.string.record_failed_recording)
            }

            SummaryWorker.ACTION_SUMMARY_FAILED -> {
                State.stage = Stage.FAILED
                State.message = intent.getStringExtra(SummaryWorker.EXTRA_ERROR)
            }
        }

        State.touch()
        render()
    }

    private fun loadResult() {

        val audioUri =
            State.audioUri ?: return

        viewLifecycleOwner.lifecycleScope.launch {

            val recording =
                withContext(Dispatchers.IO) {
                    Notes.recordings(requireContext()).firstOrNull { it.audioUri == audioUri }
                }

            State.recording = recording
            render()
        }
    }

    // Rendering ----------------------------------------------------------

    private fun render() {

        if (view == null) {
            return
        }

        stateIdle.isVisible = State.stage == Stage.IDLE
        stateRecording.isVisible = State.stage == Stage.RECORDING
        stateProgress.isVisible = State.stage in setOf(Stage.CONVERTING, Stage.ANALYZING, Stage.SAVING)
        stateDone.isVisible = State.stage == Stage.DONE || State.stage == Stage.FAILED

        handler.removeCallbacks(timerTick)

        when (State.stage) {
            Stage.RECORDING -> {
                updateTimer()
                handler.postDelayed(timerTick, 1000L)
            }

            Stage.CONVERTING -> showProgress(
                title = getString(R.string.record_processing),
                subtitle = getString(R.string.record_processing_subtitle),
                icon = R.drawable.ic_document,
                done = 1,
                active = 1
            )

            Stage.ANALYZING -> showProgress(
                title = getString(R.string.record_analyzing),
                subtitle = getString(R.string.record_analyzing_subtitle),
                icon = R.drawable.ic_sparkle,
                done = 2,
                active = 2
            )

            Stage.SAVING -> showProgress(
                title = getString(R.string.record_analyzing),
                subtitle = getString(R.string.record_analyzing_subtitle),
                icon = R.drawable.ic_sparkle,
                done = 4,
                active = 4
            )

            Stage.DONE -> showDone()

            Stage.FAILED -> showFailed()

            Stage.IDLE -> Unit
        }
    }

    private fun updateTimer() {

        val seconds =
            ((SystemClock.elapsedRealtime() - State.startedAt) / 1000L).coerceAtLeast(0L)

        tvTimer.text =
            String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
    }

    private fun buildSteps() {

        if (steps.childCount > 0) {
            return
        }

        val labels =
            listOf(
                R.string.step_recording_stopped,
                R.string.step_converting,
                R.string.step_analyzing,
                R.string.step_summary,
                R.string.step_saving
            )

        labels.forEach { label ->
            val row =
                LayoutInflater.from(requireContext()).inflate(R.layout.item_step, steps, false)

            row.findViewById<TextView>(R.id.tvStep).setText(label)
            steps.addView(row)
        }
    }

    /**
     * [done] steps show a tick, step [active] spins, the rest wait.
     */
    private fun showProgress(
        title: String,
        subtitle: String,
        icon: Int,
        done: Int,
        active: Int
    ) {

        tvProgressTitle.text = title
        tvProgressSubtitle.text = subtitle
        tvProgressHint.isVisible = State.stage == Stage.CONVERTING
        imgProgress.setImageResource(icon)

        for (index in 0 until steps.childCount) {

            val row =
                steps.getChildAt(index)

            val image =
                row.findViewById<ImageView>(R.id.imgStep)

            val text =
                row.findViewById<TextView>(R.id.tvStep)

            when {
                index < done -> {
                    image.setImageResource(R.drawable.ic_step_done)
                    text.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                }

                index == active -> {
                    image.setImageResource(R.drawable.ic_step_active)
                    text.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                }

                else -> {
                    image.setImageResource(R.drawable.ic_step_pending)
                    text.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_tertiary))
                }
            }
        }
    }

    private fun showDone() {

        imgDone.setImageResource(R.drawable.ic_check_big)
        tvDoneTitle.setText(R.string.record_done)
        tvDoneSubtitle.setText(R.string.record_done_subtitle)
        btnDone.setText(R.string.record_view_in_history)

        val recording =
            State.recording

        val note =
            recording?.notes?.firstOrNull()

        doneCard.isVisible = recording != null

        if (recording == null) {
            return
        }

        if (note == null) {
            tvDoneCategory.setText(R.string.nothing_worth_keeping)
            tvDoneSummary.text = getString(R.string.nothing_worth_keeping_detail)
        } else {
            tvDoneCategory.text = note.category
            tvDoneSummary.text =
                if (recording.notes.size > 1) {
                    getString(R.string.summary_with_more, note.text, recording.notes.size - 1)
                } else {
                    note.text
                }
        }

        tvDoneSavedOn.text = Notes.formatDateTime(recording.recordedAt)
    }

    private fun showFailed() {

        imgDone.setImageResource(R.drawable.ic_waveform)
        tvDoneTitle.setText(R.string.record_failed)
        tvDoneSubtitle.text = State.message ?: getString(R.string.record_failed)
        doneCard.isVisible = false
        btnDone.setText(R.string.record_try_again)
    }

    /**
     * Kept outside the fragment so switching tabs during processing does
     * not lose the progress. It is only what the screen shows; the work
     * itself runs in the service and the worker.
     */
    private object State {

        var stage: Stage = Stage.IDLE
        var startedAt: Long = 0L
        var audioUri: String? = null
        var message: String? = null
        var recording: SavedRecording? = null

        private var updatedAt: Long = 0L

        fun touch() {
            updatedAt = SystemClock.elapsedRealtime()
        }

        fun reset() {
            stage = Stage.IDLE
            audioUri = null
            message = null
            recording = null
        }

        /**
         * A stale in-between state (e.g. the app was killed mid-processing)
         * should not greet the user with a spinner that never finishes.
         */
        fun expireIfStale() {

            val waiting =
                stage in setOf(Stage.CONVERTING, Stage.ANALYZING, Stage.SAVING)

            if (waiting && SystemClock.elapsedRealtime() - updatedAt > STALE_AFTER_MS) {
                reset()
            }
        }

        private const val STALE_AFTER_MS =
            10 * 60 * 1000L
    }
}
