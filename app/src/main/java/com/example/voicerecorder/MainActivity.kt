package com.example.voicerecorder

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import com.example.voicerecorder.ui.HistoryFragment
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
        } else {
            currentTab = savedInstanceState.getInt(KEY_TAB, R.id.nav_record)
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

    private companion object {

        const val KEY_TAB =
            "tab"
    }
}
