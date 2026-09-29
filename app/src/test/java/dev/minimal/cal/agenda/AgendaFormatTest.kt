package dev.minimal.cal.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class AgendaFormatTest {

    private val zone = ZoneId.of("Europe/London")
    private val words = AgendaWords(now = "Now", tomorrow = "Tomorrow", allDay = "All day")

    // Monday 28 Sep 2026, 10:00 London (BST, UTC+1)
    private val now = at(2026, 9, 28, 10, 0)

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    /** All-day events are stored as UTC midnights, end exclusive. */
    private fun allDay(y: Int, m: Int, d: Int, days: Long = 1): AgendaItem {
        val start = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = LocalDate.of(y, m, d).plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return AgendaItem(1, "all day", start, end, true, 0)
    }

    private fun timed(begin: Long, end: Long, title: String = "t") = AgendaItem(1, title, begin, end, false, 0)

    private fun label(item: AgendaItem, is24: Boolean = true) =
        AgendaFormat.label(item, now, zone, Locale.UK, is24, words)

    @Test fun inProgressIsNow() {
        assertEquals("Now", label(timed(at(2026, 9, 28, 9, 30), at(2026, 9, 28, 10, 30))))
    }

    @Test fun laterTodayShowsTime() {
        assertEquals("14:00", label(timed(at(2026, 9, 28, 14, 0), at(2026, 9, 28, 15, 0))))
        assertEquals("2:00 pm", label(timed(at(2026, 9, 28, 14, 0), at(2026, 9, 28, 15, 0)), is24 = false).lowercase())
    }

    @Test fun tomorrowWeekdayAndDate() {
        assertEquals("Tomorrow 09:30", label(timed(at(2026, 9, 29, 9, 30), at(2026, 9, 29, 10, 0))))
        assertEquals("Thu 09:30", label(timed(at(2026, 10, 1, 9, 30), at(2026, 10, 1, 10, 0))))
        assertEquals("8 Oct 09:30", label(timed(at(2026, 10, 8, 9, 30), at(2026, 10, 8, 10, 0))))
    }

    @Test fun allDayLabels() {
        assertEquals("All day", label(allDay(2026, 9, 28)))
        assertEquals("Tomorrow", label(allDay(2026, 9, 29)))
        assertEquals("Wed", label(allDay(2026, 9, 30)))
        // Multi-day event that started yesterday is still "All day" today.
        assertEquals("All day", label(allDay(2026, 9, 27, days = 3)))
    }

    @Test fun upcomingFiltersEndedEvents() {
        assertFalse(AgendaFormat.isUpcoming(timed(at(2026, 9, 28, 8, 0), at(2026, 9, 28, 9, 0)), now, zone))
        assertTrue(AgendaFormat.isUpcoming(timed(at(2026, 9, 28, 9, 0), at(2026, 9, 28, 11, 0)), now, zone))
        assertFalse(AgendaFormat.isUpcoming(allDay(2026, 9, 27), now, zone))
        assertTrue(AgendaFormat.isUpcoming(allDay(2026, 9, 28), now, zone))
    }

    @Test fun orderPutsAllDayAtStartOfItsDay() {
        val late = timed(at(2026, 9, 28, 18, 0), at(2026, 9, 28, 19, 0), "late")
        val tomorrowMorning = timed(at(2026, 9, 29, 8, 0), at(2026, 9, 29, 9, 0), "tomorrow")
        val todayAllDay = allDay(2026, 9, 28).copy(title = "today-all-day")
        val tomorrowAllDay = allDay(2026, 9, 29).copy(title = "tomorrow-all-day")
        val ended = timed(at(2026, 9, 28, 7, 0), at(2026, 9, 28, 8, 0), "ended")
        val result = AgendaFormat.upcoming(listOf(tomorrowMorning, late, ended, tomorrowAllDay, todayAllDay), now, zone, 10)
        assertEquals(listOf("today-all-day", "late", "tomorrow-all-day", "tomorrow"), result.map { it.title })
    }

    @Test fun limitApplies() {
        val items = (1..10).map { timed(at(2026, 9, 28, 11, it), at(2026, 9, 28, 12, 0)) }
        assertEquals(3, AgendaFormat.upcoming(items, now, zone, 3).size)
    }

    @Test fun duplicatesMergedAcrossApostropheStyleAndCalendars() {
        val b = allDay(2026, 9, 30)
        val straight = b.copy(eventId = 33, title = "Sam's Birthday", color = 1)
        val curly = b.copy(eventId = 130, title = "Sam\u2019s Birthday", color = 2)
        val meetingA = timed(at(2026, 10, 1, 16, 0), at(2026, 10, 1, 17, 0), "Quarterly  Planning Review")
        val meetingB = meetingA.copy(eventId = 171, title = "quarterly planning review", color = 9)
        val sameTitleOtherTime = timed(at(2026, 10, 1, 18, 0), at(2026, 10, 1, 19, 0), "Quarterly Planning Review")
        val result = AgendaFormat.upcoming(listOf(straight, curly, meetingA, meetingB, sameTitleOtherTime), now, zone, 10)
        assertEquals(3, result.size)
        assertEquals(1, result.first().color) // first one wins
        assertEquals("team - offsite", AgendaFormat.normalizeTitle("Team \u2014 Offsite"))
    }

    @Test fun nextRefreshIsNextBoundaryOrMidnight() {
        val a = timed(at(2026, 9, 28, 9, 0), at(2026, 9, 28, 10, 45))
        val b = timed(at(2026, 9, 28, 11, 0), at(2026, 9, 28, 12, 0))
        assertEquals(at(2026, 9, 28, 10, 45), AgendaFormat.nextRefresh(listOf(a, b), now, zone))
        assertEquals(at(2026, 9, 29, 0, 0), AgendaFormat.nextRefresh(emptyList(), now, zone))
    }
}
