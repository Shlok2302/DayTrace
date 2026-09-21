package com.example.voicerecorder.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.voicerecorder.R
import com.example.voicerecorder.summary.DayTraceBackup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Settings > Data & Storage: how much is stored, exporting the notes
 * (readable text, or a .daytrace backup) and restoring a backup.
 *
 * Exporting only reads. Android deletes the app's data on uninstall, so
 * the user exports before uninstalling and imports after reinstalling.
 */
class DataStorageFragment : SettingsPageFragment() {

    override val pageTitle = R.string.settings_storage

    override val pageSubtitle = R.string.data_storage_subtitle

    private val saveText =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) write(uri, R.string.export_text_saved) { context, output ->
                output.write(DayTraceBackup.exportText(context).toByteArray(Charsets.UTF_8))
            }
        }

    private val saveBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) write(uri, R.string.export_backup_saved) { context, output ->
                DayTraceBackup.writeBackup(context, output)
            }
        }

    private val openBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) checkBackup(uri)
        }

    override fun rows(): List<SettingsRow> =
        listOf(

            SettingsRow.Open(
                icon = R.drawable.ic_database,
                title = getString(R.string.storage_used),
                subtitle = getString(R.string.storage_used_subtitle),
                onClick = { showStorage() }
            ),

            SettingsRow.Open(
                icon = R.drawable.ic_export,
                title = getString(R.string.export_data),
                subtitle = getString(R.string.export_data_subtitle),
                onClick = { chooseExport() }
            ),

            SettingsRow.Open(
                icon = R.drawable.ic_import,
                title = getString(R.string.import_data),
                subtitle = getString(R.string.import_data_subtitle),
                // .daytrace has no MIME type of its own; the content is checked instead.
                onClick = { openBackup.launch(arrayOf("*/*")) }
            )
        )

    // Export ---------------------------------------------------------------

    private fun chooseExport() {

        val today =
            LocalDate.now().toString()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_data)
            .setItems(
                arrayOf(
                    getString(R.string.export_share_text),
                    getString(R.string.export_save_text),
                    getString(R.string.export_save_backup)
                )
            ) { _, which ->
                when (which) {
                    0 -> shareText()
                    1 -> saveText.launch("DayTrace notes $today.txt")
                    2 -> saveBackup.launch("DayTrace backup $today.daytrace")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** To Samsung Notes, Google Keep, messages... */
    private fun shareText() {

        viewLifecycleOwner.lifecycleScope.launch {

            val text =
                withContext(Dispatchers.IO) { DayTraceBackup.exportText(requireContext().applicationContext) }

            val send =
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_text_subject))
                    .putExtra(Intent.EXTRA_TEXT, text)

            startActivity(Intent.createChooser(send, getString(R.string.export_share_text)))
        }
    }

    private fun write(
        uri: Uri,
        done: Int,
        body: (Context, java.io.OutputStream) -> Unit
    ) {

        val context =
            requireContext().applicationContext

        viewLifecycleOwner.lifecycleScope.launch {

            val ok =
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "wt")!!.use { body(context, it) }
                    }.isSuccess
                }

            Toast.makeText(context, if (ok) done else R.string.export_failed, Toast.LENGTH_LONG).show()
        }
    }

    // Import ---------------------------------------------------------------

    /** Reads and checks the whole backup first; nothing is written until the user chooses. */
    private fun checkBackup(
        uri: Uri
    ) {

        val context =
            requireContext().applicationContext

        viewLifecycleOwner.lifecycleScope.launch {

            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val contents = DayTraceBackup.read(context.contentResolver.openInputStream(uri)!!)
                        contents to DayTraceBackup.alreadyHere(context, contents)
                    }
                }

            val (contents, alreadyHere) =
                result.getOrElse { error ->
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.import_data_invalid_title)
                        .setMessage(
                            (error as? DayTraceBackup.BackupException)?.message
                                ?: getString(R.string.import_data_unreadable)
                        )
                        .setPositiveButton(R.string.close, null)
                        .show()
                    return@launch
                }

            val newNotes =
                contents.noteCount - alreadyHere

            val summary =
                getString(
                    R.string.import_data_summary,
                    Notes.formatDateTime(contents.exportedAt),
                    resources.getQuantityString(R.plurals.backup_recordings, contents.recordings.size, contents.recordings.size),
                    resources.getQuantityString(R.plurals.backup_notes, contents.noteCount, contents.noteCount),
                    alreadyHere
                )

            val dialog =
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.import_data)
                    .setNegativeButton(R.string.cancel, null)
                    .setNeutralButton(R.string.import_data_replace) { _, _ -> confirmReplace(contents) }

            if (newNotes > 0) {
                dialog
                    .setMessage(summary + "\n\n" + resources.getQuantityString(R.plurals.import_data_add_hint, newNotes, newNotes))
                    .setPositiveButton(R.string.import_data_add) { _, _ -> restore(contents, replace = false) }
            } else {
                dialog.setMessage(summary + "\n\n" + getString(R.string.import_data_nothing_new))
            }

            dialog.show()
        }
    }

    /** Replacing deletes the notes on this device, so it is asked again. */
    private fun confirmReplace(
        contents: DayTraceBackup.Contents
    ) {

        viewLifecycleOwner.lifecycleScope.launch {

            val local =
                withContext(Dispatchers.IO) {
                    Notes.recordings(requireContext().applicationContext).sumOf { it.notes.size }
                }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.import_data_replace_title)
                .setMessage(getString(R.string.import_data_replace_message, local, contents.noteCount))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.import_data_replace) { _, _ -> restore(contents, replace = true) }
                .show()
        }
    }

    private fun restore(
        contents: DayTraceBackup.Contents,
        replace: Boolean
    ) {

        val context =
            requireContext().applicationContext

        viewLifecycleOwner.lifecycleScope.launch {

            val result =
                withContext(Dispatchers.IO) {
                    runCatching { DayTraceBackup.restore(context, contents, replace) }
                }

            val message =
                result.fold(
                    onSuccess = {
                        resources.getQuantityString(R.plurals.import_data_restored, it.added, it.added) + " " +
                                resources.getQuantityString(R.plurals.import_data_already, it.alreadyHere, it.alreadyHere)
                    },
                    onFailure = { getString(R.string.import_data_failed) }
                )

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.import_data)
                .setMessage(message)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    // Storage --------------------------------------------------------------

    /**
     * Real numbers: how many notes are stored, how much space they take,
     * and how many recordings are still on the device.
     */
    private fun showStorage() {

        viewLifecycleOwner.lifecycleScope.launch {

            val summary =
                withContext(Dispatchers.IO) { storageSummary(requireContext()) }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.storage_used)
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
            recordings.sumOf { it.activeNotes.size }

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
}
