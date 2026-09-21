package com.example.voicerecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import com.example.voicerecorder.settings.AppSettings
import com.example.voicerecorder.ui.HistoryFragment
import com.example.voicerecorder.ui.NoteDetailFragment
import com.example.voicerecorder.ui.RecordFragment
import com.example.voicerecorder.ui.SettingsFragment
import com.example.voicerecorder.ui.StatsFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.time.LocalDate

/**
 * Holds the four tabs and the screens opened from them.
 * The recording itself still runs in RecordingService.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView

    private var currentTab: Int = 0

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        bottomNav = findViewById(R.id.bottomNav)

        bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        if (savedInstanceState == null) {
            showTab(R.id.nav_record)
            handleNotificationTap(intent)
        } else {
            currentTab = savedInstanceState.getInt(KEY_TAB, R.id.nav_record)
        }

        askForNotificationsOnce()
    }

    override fun onNewIntent(
        intent: Intent
    ) {
        super.onNewIntent(intent)
        handleNotificationTap(intent)
    }

    /** A tapped reminder opens its note; a processing update opens History. */
    private fun handleNotificationTap(
        intent: Intent?
    ) {

        val noteId =
            intent?.getStringExtra(EXTRA_OPEN_NOTE)

        when {
            noteId != null -> {
                openHistory()
                open(NoteDetailFragment.forNote(noteId))
            }

            intent?.hasExtra(EXTRA_OPEN_HISTORY) == true ->
                openHistory()
        }

        intent?.removeExtra(EXTRA_OPEN_NOTE)
        intent?.removeExtra(EXTRA_OPEN_HISTORY)
    }

    /**
     * Reminders need the notification permission (Android 13+). It is
     * asked for once; after that the user can turn it on from Settings >
     * Notifications.
     */
    private fun askForNotificationsOnce() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        val settings =
            AppSettings(this)

        val granted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        if (!granted && !settings.notificationPermissionAsked) {
            settings.notificationPermissionAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onSaveInstanceState(
        outState: Bundle
    ) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    private fun showTab(
        itemId: Int
    ) {

        val alreadyOnTab =
            currentTab == itemId && supportFragmentManager.backStackEntryCount == 0

        if (alreadyOnTab) {
            return
        }

        currentTab = itemId

        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

        val fragment =
            when (itemId) {
                R.id.nav_history -> HistoryFragment()
                R.id.nav_stats -> StatsFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> RecordFragment()
            }

        supportFragmentManager.commit {
            replace(R.id.container, fragment)
        }
    }

    /**
     * Opens a screen on top of the current tab (day, note detail).
     */
    fun open(
        fragment: Fragment
    ) {
        supportFragmentManager.commit {
            replace(R.id.container, fragment)
            addToBackStack(null)
        }
    }

    /**
     * Switches to History, optionally on a given day.
     */
    fun openHistory(
        date: LocalDate? = null
    ) {
        currentTab = R.id.nav_history

        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

        supportFragmentManager.commit {
            replace(R.id.container, HistoryFragment.forDate(date))
        }

        bottomNav.menu.findItem(R.id.nav_history)?.isChecked = true
    }

    fun openSettings() {
        bottomNav.selectedItemId = R.id.nav_settings
    }

    companion object {

        private const val KEY_TAB =
            "tab"

        /** Set on a reminder's tap intent: the id of the note to open. */
        const val EXTRA_OPEN_NOTE =
            "open_note"

        /** Set on a processing update's tap intent. */
        const val EXTRA_OPEN_HISTORY =
            "open_history"
    }
}
