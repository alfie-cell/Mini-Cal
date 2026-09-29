package dev.minimal.cal.reminders

import dev.minimal.cal.agenda.AgendaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ReminderPlannerTest {

    private val zone = ZoneId.of("Europe/London")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun timed(id: Long, begin: Long, cal: Long = 1, title: String = "e$id", uid: String? = "uid-$id") =
        AgendaItem(id, title, begin, begin + 3_600_000, false, 0, cal, uid = uid)

    private fun allDay(id: Long, date: LocalDate, cal: Long = 1, title: String = "a$id"): AgendaItem {
        val b = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return AgendaItem(id, title, b, b + 86_400_000, true, 0, cal)
    }

    private val defaults = { _: Long -> CalendarReminderSettings.DEFAULT }

    @Test fun calendarDefaultForTimedEvent() {
        val plan = ReminderPlanner.plan(listOf(Occurrence(timed(1, at(2026, 9, 30, 14, 0)), emptyList())), defaults, zone)
        assertEquals(listOf(at(2026, 9, 30, 13, 50)), plan.map { it.triggerAt })
        assertTrue(plan.all { it.source == ReminderSource.CALENDAR })
    }

    @Test fun eventOwnRemindersWin() {
        val plan = ReminderPlanner.plan(listOf(Occurrence(timed(1, at(2026, 9, 30, 14, 0)), listOf(15, 60))), defaults, zone)
        assertEquals(listOf(at(2026, 9, 30, 13, 0), at(2026, 9, 30, 13, 45)), plan.map { it.triggerAt })
        assertTrue(plan.all { it.source == ReminderSource.EVENT })
    }

    @Test fun allDayUsesLocalTimeOnTheDayOrDayBefore() {
        val item = allDay(2, LocalDate.of(2026, 9, 30))
        val nine = ReminderPlanner.plan(listOf(Occurrence(item, emptyList())), defaults, zone)
        assertEquals(at(2026, 9, 30, 9, 0), nine.single().triggerAt)
        val eveBefore = ReminderPlanner.plan(listOf(Occurrence(item, emptyList())),
            { CalendarReminderSettings(listOf(10), listOf(-6 * 60), null) }, zone)
        assertEquals(at(2026, 9, 29, 18, 0), eveBefore.single().triggerAt)
    }

    @Test fun noneMeansNoReminder() {
        val off = { _: Long -> CalendarReminderSettings(emptyList(), emptyList(), null) }
        val plan = ReminderPlanner.plan(listOf(
            Occurrence(timed(1, at(2026, 9, 30, 14, 0)), emptyList()),
            Occurrence(allDay(2, LocalDate.of(2026, 9, 30)), emptyList()),
        ), off, zone)
        assertTrue(plan.isEmpty())
    }

    @Test fun perCalendarSettings() {
        val settings = { cal: Long -> if (cal == 2L) CalendarReminderSettings(listOf(30), emptyList(), "x") else CalendarReminderSettings.DEFAULT }
        val plan = ReminderPlanner.plan(listOf(
            Occurrence(timed(1, at(2026, 9, 30, 14, 0), cal = 1), emptyList()),
            Occurrence(timed(2, at(2026, 9, 30, 16, 0), cal = 2), emptyList()),
        ), settings, zone)
        assertEquals(listOf(at(2026, 9, 30, 13, 50), at(2026, 9, 30, 15, 30)), plan.map { it.triggerAt })
    }

    @Test fun duplicatesRemindOnce() {
        val d = LocalDate.of(2026, 9, 30)
        val plan = ReminderPlanner.plan(listOf(
            Occurrence(allDay(33, d, title = "Sam's Birthday"), emptyList()),
            Occurrence(allDay(130, d, title = "Sam’s Birthday"), emptyList()),
        ), defaults, zone)
        assertEquals(1, plan.size)
    }

    @Test fun multipleCalendarDefaults() {
        val two = { _: Long -> CalendarReminderSettings(listOf(15, 24 * 60), listOf(-6 * 60, 9 * 60), null) }
        val timedPlan = ReminderPlanner.plan(listOf(Occurrence(timed(1, at(2026, 9, 30, 14, 0)), emptyList())), two, zone)
        assertEquals(listOf(at(2026, 9, 29, 14, 0), at(2026, 9, 30, 13, 45)), timedPlan.map { it.triggerAt })
        val allDayPlan = ReminderPlanner.plan(listOf(Occurrence(allDay(2, LocalDate.of(2026, 9, 30)), emptyList())), two, zone)
        assertEquals(listOf(at(2026, 9, 29, 18, 0), at(2026, 9, 30, 9, 0)), allDayPlan.map { it.triggerAt })
    }

    @Test fun overridePriorityOccurrenceThenSeriesThenEventThenCalendar() {
        val item = timed(7, at(2026, 9, 30, 14, 0), uid = "abc")
        val series = ReminderPlanner.seriesKey(item)
        val occurrence = ReminderPlanner.occurrenceKey(item)
        val settings = CalendarReminderSettings.DEFAULT
        fun res(overrides: Map<String, ReminderOverride>, own: List<Int> = emptyList()) =
            ReminderPlanner.resolve(item, own, settings) { overrides[it] }

        assertEquals(ReminderSource.CALENDAR to listOf(10), res(emptyMap()).let { it.source to it.values })
        assertEquals(ReminderSource.EVENT to listOf(15), res(emptyMap(), own = listOf(15)).let { it.source to it.values })
        assertEquals(ReminderSource.SERIES to listOf(5, 60),
            res(mapOf(series to ReminderOverride(listOf(60, 5))), own = listOf(15)).let { it.source to it.values })
        assertEquals(ReminderSource.OCCURRENCE to listOf(30),
            res(mapOf(series to ReminderOverride(listOf(60)), occurrence to ReminderOverride(listOf(30)))).let { it.source to it.values })
        // Empty list = explicitly no reminders.
        assertTrue(res(mapOf(series to ReminderOverride(emptyList()))).values.isEmpty())
    }

    @Test fun seriesOverrideAppliesToEveryOccurrenceOnly() {
        val week = 7L * 24 * 3_600_000
        val a = timed(9, at(2026, 9, 30, 14, 0), uid = "weekly")
        val b = a.copy(begin = a.begin + week, end = a.end + week)
        val overrides = mapOf(
            ReminderPlanner.seriesKey(a) to ReminderOverride(listOf(60)),
            ReminderPlanner.occurrenceKey(b) to ReminderOverride(listOf(5)),
        )
        val plan = ReminderPlanner.plan(listOf(Occurrence(a, emptyList()), Occurrence(b, emptyList())), defaults, zone) { overrides[it] }
        assertEquals(listOf(a.begin - 3_600_000, b.begin - 300_000), plan.map { it.triggerAt })
    }

    @Test fun overrideSoundResolution() {
        val item = timed(3, at(2026, 9, 30, 14, 0))
        val cal = CalendarReminderSettings(listOf(10), emptyList(), "content://cal-sound")
        fun sound(o: ReminderOverride?) = ReminderPlanner.resolve(item, emptyList(), cal) { if (it == ReminderPlanner.seriesKey(item)) o else null }.soundUri
        assertEquals("content://cal-sound", sound(null))
        assertEquals("content://cal-sound", sound(ReminderOverride(listOf(5))))     // times only: keep calendar sound
        assertEquals("content://mine", sound(ReminderOverride(null, "content://mine")))
        assertEquals(null, sound(ReminderOverride(null, ReminderOverride.SOUND_DEFAULT)))
        assertEquals("", sound(ReminderOverride(null, "")))
        // Sound-only override keeps the inherited times.
        assertEquals(listOf(10), ReminderPlanner.resolve(item, emptyList(), cal) { ReminderOverride(null, "x") }.values)
    }

    @Test fun keysUseUidSoTheySurviveResync() {
        val item = timed(100, at(2026, 9, 30, 14, 0), uid = "stable-uid")
        val resynced = item.copy(eventId = 555)
        assertEquals(ReminderPlanner.seriesKey(item), ReminderPlanner.seriesKey(resynced))
        assertEquals("id:100", ReminderPlanner.seriesKey(item.copy(uid = null)))
    }

    @Test fun dueFiresOnceAndSkipsStale() {
        val now = at(2026, 9, 30, 13, 51)
        val plan = ReminderPlanner.plan(listOf(Occurrence(timed(1, at(2026, 9, 30, 14, 0)), emptyList())), defaults, zone)
        val due = ReminderPlanner.due(plan, after = now - 120_000, now = now, fired = emptySet())
        assertEquals(1, due.size)
        assertTrue(ReminderPlanner.due(plan, now - 120_000, now, setOf(due.single().key)).isEmpty())
        // Event already over (phone was off): not announced.
        val late = at(2026, 9, 30, 16, 0)
        assertTrue(ReminderPlanner.due(plan, 0, late, emptySet()).isEmpty())
    }

    @Test fun nextSkipsFiredAndPast() {
        val plan = ReminderPlanner.plan(listOf(
            Occurrence(timed(1, at(2026, 9, 30, 14, 0)), emptyList()),
            Occurrence(timed(2, at(2026, 9, 30, 16, 0)), emptyList()),
        ), defaults, zone)
        val now = at(2026, 9, 30, 12, 0)
        assertEquals(at(2026, 9, 30, 13, 50), ReminderPlanner.next(plan, now, emptySet()))
        assertEquals(at(2026, 9, 30, 15, 50), ReminderPlanner.next(plan, now, setOf(plan.first().key)))
        assertNull(ReminderPlanner.next(plan, at(2026, 9, 30, 17, 0), emptySet()))
    }
}
