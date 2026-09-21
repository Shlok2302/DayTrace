package com.example.voicerecorder.reminders

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * When a Remember note's reminders go off. Plain logic with no Android
 * code, so it can be unit tested.
 *
 * - A deadline with a time (e.g. today 20:00): one reminder
 *   [earlyMinutes] before it (19:30) and one at the deadline (20:00).
 * - A deadline with only a day (e.g. "by Friday"): one reminder that
 *   morning at [DAY_REMINDER_HOUR].
 *
 * Reminders whose time has already passed are left out.
 */
object ReminderTimes {

    enum class Slot {

        /** Some minutes before the deadline. */
        EARLY,

        /** At the deadline. */
        DUE,

        /** The morning of a deadline that has only a day. */
        DAY
    }

    data class Alert(
        val slot: Slot,
        val at: LocalDateTime
    )

    const val DAY_REMINDER_HOUR =
        9

    /** The deadline, or null when the note has none (or it cannot be read). */
    fun due(
        dueDate: String,
        dueTime: String
    ): LocalDateTime? {

        val date =
            runCatching { LocalDate.parse(dueDate) }.getOrNull() ?: return null

        if (dueTime.isEmpty()) {
            return date.atTime(DAY_REMINDER_HOUR, 0)
        }

        val time =
            runCatching { LocalTime.parse(dueTime) }.getOrNull() ?: return null

        return date.atTime(time)
    }

    fun alerts(
        dueDate: String,
        dueTime: String,
        earlyMinutes: Int,
        now: LocalDateTime
    ): List<Alert> {

        val due =
            due(dueDate, dueTime) ?: return emptyList()

        val alerts =
            if (dueTime.isEmpty()) {
                listOf(Alert(Slot.DAY, due))
            } else {
                listOfNotNull(
                    if (earlyMinutes > 0) Alert(Slot.EARLY, due.minusMinutes(earlyMinutes.toLong())) else null,
                    Alert(Slot.DUE, due)
                )
            }

        return alerts.filter { it.at.isAfter(now) }
    }
}
