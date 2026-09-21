package com.example.voicerecorder

import com.example.voicerecorder.summary.AudioImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ImportDateTest {

    private fun local(
        millis: Long?
    ): String? =
        millis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime().toString() }

    @Test
    fun `date and time in the file name are the recording time`() {
        assertEquals("2026-09-20T20:15", local(AudioImport.dateInName("Recording_20260920_201500.wav")))
        assertEquals("2026-09-20T20:15:30", local(AudioImport.dateInName("20260920201530.mp3")))
        assertEquals("2026-09-20T20:15", local(AudioImport.dateInName("2026-09-20 20.15.m4a")))
    }

    @Test
    fun `a date alone, a counter or a DayTrace id is not a recording time`() {
        assertNull(AudioImport.dateInName("AUD-20260920-WA0001.opus"))
        assertNull(AudioImport.dateInName("Voice 001.m4a"))
        assertNull(AudioImport.dateInName("Imported_1789985999000.mp3"))
        assertNull(AudioImport.dateInName("lecture.m4a"))
    }

    @Test
    fun `impossible or future times are rejected`() {
        assertNull(AudioImport.dateInName("Recording_20261340_251500.wav"))
        val nextYear = LocalDateTime.now().year + 1
        assertNull(AudioImport.dateInName("Recording_${nextYear}0101_101010.wav"))
    }
}
