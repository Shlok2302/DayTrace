package com.example.voicerecorder

import com.example.voicerecorder.reminders.ReminderTimes
import com.example.voicerecorder.reminders.ReminderTimes.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class ReminderTimesTest {

    private val afternoon =
        LocalDateTime.of(2026, 9, 21, 14, 5)

    private fun alerts(
        date: String,
        time: String,
        early: Int = 30,
        now: LocalDateTime = afternoon
    ) =
        ReminderTimes.alerts(date, time, early, now).map { it.slot to it.at.toString() }

    @Test
    fun `8 PM deadline reminds at 7-30 PM and at 8 PM`() {
        assertEquals(
            listOf(Slot.EARLY to "2026-09-21T19:30", Slot.DUE to "2026-09-21T20:00"),
            alerts("2026-09-21", "20:00")
        )
    }

    @Test
    fun `early reminder follows the setting and can be turned off`() {
        assertEquals(
            listOf(Slot.EARLY to "2026-09-21T19:00", Slot.DUE to "2026-09-21T20:00"),
            alerts("2026-09-21", "20:00", early = 60)
        )
        assertEquals(
            listOf(Slot.DUE to "2026-09-21T20:00"),
            alerts("2026-09-21", "20:00", early = 0)
        )
    }

    @Test
    fun `reminders that have already passed are skipped`() {
        // At 19:45 only the 20:00 reminder is left.
        assertEquals(
            listOf(Slot.DUE to "2026-09-21T20:00"),
            alerts("2026-09-21", "20:00", now = LocalDateTime.of(2026, 9, 21, 19, 45))
        )
        // After the deadline, nothing.
        assertEquals(
            emptyList<Pair<Slot, String>>(),
            alerts("2026-09-21", "20:00", now = LocalDateTime.of(2026, 9, 21, 20, 1))
        )
    }

    @Test
    fun `a deadline with only a day reminds that morning`() {
        assertEquals(
            listOf(Slot.DAY to "2026-09-25T09:00"),
            alerts("2026-09-25", "")
        )
        // "today" with no time, recorded in the afternoon: the morning is over.
        assertEquals(
            emptyList<Pair<Slot, String>>(),
            alerts("2026-09-21", "")
        )
    }

    @Test
    fun `no or unreadable deadline gives no reminders`() {
        assertEquals(emptyList<Pair<Slot, String>>(), alerts("", ""))
        assertEquals(emptyList<Pair<Slot, String>>(), alerts("next friday", ""))
        assertNull(ReminderTimes.due("2026-09-21", "8pm"))
    }
}
