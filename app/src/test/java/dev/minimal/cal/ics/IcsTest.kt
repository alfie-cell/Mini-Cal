package dev.minimal.cal.ics

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Synthetic feeds mimicking the structures seen in real Proton and Outlook/Exchange published
 * calendars (all names, addresses and ids here are made up).
 */
class IcsTest {

    private val london = ZoneId.of("Europe/London")
    private fun feed(vararg lines: String) = (lines.joinToString("\r\n") + "\r\n").toByteArray()
    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int, zone: ZoneId) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()
    private fun utcMidnight(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private val VTZ_LONDON = arrayOf(
        "BEGIN:VTIMEZONE", "TZID:Europe/London", "X-LIC-LOCATION:Europe/London",
        "BEGIN:STANDARD", "TZNAME:GMT", "TZOFFSETFROM:+0100", "TZOFFSETTO:+0000", "DTSTART:19701025T020000",
        "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU", "END:STANDARD",
        "BEGIN:DAYLIGHT", "TZNAME:BST", "TZOFFSETFROM:+0000", "TZOFFSETTO:+0100", "DTSTART:19700329T010000",
        "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU", "END:DAYLIGHT", "END:VTIMEZONE",
    )

    /** Proton-style: IANA TZIDs + VTIMEZONE, X-WR-*, LANGUAGE params, attendees, conference URL. */
    private val proton = feed(
        "BEGIN:VCALENDAR", "PRODID:-//Example AG//ExampleCalendar 1.0.0//EN", "VERSION:2.0", "CALSCALE:GREGORIAN",
        "METHOD:PUBLISH", "X-WR-CALNAME:Work", "X-WR-TIMEZONE:Europe/Lisbon", "REFRESH-INTERVAL;VALUE=DURATION:PT240M",
        *VTZ_LONDON,
        // Timed event with escapes, folded description, attendees with a quoted SENT-BY containing ':'
        "BEGIN:VEVENT", "UID:aBcD-123_xyz==", "DTSTAMP:20260901T100000Z",
        "SUMMARY;LANGUAGE=en-us:Planning\\, round 2",
        "DESCRIPTION;LANGUAGE=en-us:Line one\\nLine two\\; with semicolon and a very long tail that",
        "  wraps onto a continuation line",
        "DTSTART;TZID=Europe/London:20261005T140000", "DTEND;TZID=Europe/London:20261005T150000",
        "SEQUENCE:1", "STATUS:CONFIRMED", "LOCATION;LANGUAGE=en-us:Room 4",
        "ORGANIZER;CN=\"Org, Person\";SENT-BY=\"mailto:assistant@example.org\":mailto:organizer@example.org",
        "ATTENDEE;CN=Alex;ROLE=REQ-PARTICIPANT;RSVP=TRUE;PARTSTAT=ACCEPTED;X-PM-TOKEN=abc:mailto:alex@example.com",
        "ATTENDEE;CUTYPE=INDIVIDUAL;ROLE=OPT-PARTICIPANT;RSVP=TRUE;PARTSTAT=NEEDS-ACTION;X-NUM-GUESTS=0:mailto:sam@example.com",
        "X-PM-CONFERENCE-ID;X-PM-PROVIDER=2:abc", "X-PM-CONFERENCE-URL;X-PM-HOST=true:https://meet.example.me/join/abc",
        "END:VEVENT",
        // Yearly birthday (all-day), started decades ago
        "BEGIN:VEVENT", "UID:birthday-1", "DTSTAMP:20260901T100000Z", "SUMMARY:Sam’s Birthday",
        "DTSTART;VALUE=DATE:19900314", "DTEND;VALUE=DATE:19900315", "RRULE:FREQ=YEARLY", "SEQUENCE:0", "STATUS:CONFIRMED",
        "END:VEVENT",
        // Weekly series with a moved occurrence and a cancelled occurrence (RECURRENCE-ID with TZID)
        "BEGIN:VEVENT", "UID:series-1", "SUMMARY:Standup", "DTSTART;TZID=Europe/London:20260907T093000",
        "DTEND;TZID=Europe/London:20260907T094500", "RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;UNTIL=20261231T235959Z",
        "EXDATE;TZID=Europe/London:20260909T093000", "STATUS:CONFIRMED", "END:VEVENT",
        "BEGIN:VEVENT", "UID:series-1", "RECURRENCE-ID;TZID=Europe/London:20260908T093000", "SUMMARY:Standup (moved)",
        "DTSTART;TZID=Europe/London:20260908T110000", "DTEND;TZID=Europe/London:20260908T111500", "SEQUENCE:2", "END:VEVENT",
        "BEGIN:VEVENT", "UID:series-1", "RECURRENCE-ID;TZID=Europe/London:20260910T093000", "SUMMARY:Standup",
        "DTSTART;TZID=Europe/London:20260910T093000", "DTEND;TZID=Europe/London:20260910T094500", "STATUS:CANCELLED", "END:VEVENT",
        // UTC event, multi-day all-day, cancelled one-off, monthly BYSETPOS
        "BEGIN:VEVENT", "UID:utc-1", "SUMMARY:Call", "DTSTART:20261006T160000Z", "DTEND:20261006T163000Z", "END:VEVENT",
        "BEGIN:VEVENT", "UID:trip-1", "SUMMARY:Trip", "DTSTART;VALUE=DATE:20261012", "DTEND;VALUE=DATE:20261015", "END:VEVENT",
        "BEGIN:VEVENT", "UID:gone-1", "SUMMARY:Cancelled thing", "DTSTART;TZID=Europe/London:20261007T100000",
        "DTEND;TZID=Europe/London:20261007T110000", "STATUS:CANCELLED", "END:VEVENT",
        "BEGIN:VEVENT", "UID:monthly-1", "SUMMARY:Review", "DTSTART;TZID=Europe/London:20261002T160000",
        "DTEND;TZID=Europe/London:20261002T170000", "RRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1", "END:VEVENT",
        "END:VCALENDAR",
    )

    /** Outlook/Exchange-style: Windows TZID, TRANSP/busy status, empty LOCATION, CDO props. */
    private val outlook = feed(
        "BEGIN:VCALENDAR", "METHOD:PUBLISH", "PRODID:Microsoft Exchange Server 2010", "VERSION:2.0", "X-WR-CALNAME:Calendar",
        "BEGIN:VTIMEZONE", "TZID:GMT Standard Time", "BEGIN:STANDARD", "DTSTART:16010101T020000", "TZOFFSETFROM:+0100",
        "TZOFFSETTO:+0000", "RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=-1SU;BYMONTH=10", "END:STANDARD", "BEGIN:DAYLIGHT",
        "DTSTART:16010101T010000", "TZOFFSETFROM:+0000", "TZOFFSETTO:+0100", "RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=-1SU;BYMONTH=3",
        "END:DAYLIGHT", "END:VTIMEZONE",
        "BEGIN:VEVENT", "DESCRIPTION:\\n", "UID:040000008200E00074C5B7101A82E00800000000ABCDEF", "SUMMARY:Busy block",
        "DTSTART;TZID=GMT Standard Time:20261006T090000", "DTEND;TZID=GMT Standard Time:20261006T100000", "CLASS:PUBLIC",
        "PRIORITY:5", "DTSTAMP:20260928T120000Z", "TRANSP:OPAQUE", "STATUS:CONFIRMED", "SEQUENCE:1", "LOCATION:",
        "X-MICROSOFT-CDO-BUSYSTATUS:BUSY", "X-MICROSOFT-CDO-ALLDAYEVENT:FALSE", "X-MICROSOFT-CDO-INSTTYPE:0", "END:VEVENT",
        "BEGIN:VEVENT", "DESCRIPTION:", "UID:040000008200E00074C5B7101A82E00800000000FEDCBA", "SUMMARY:Holiday",
        "DTSTART;VALUE=DATE:20261019", "DTEND;VALUE=DATE:20261024", "TRANSP:TRANSPARENT", "STATUS:CONFIRMED",
        "X-MICROSOFT-CDO-BUSYSTATUS:FREE", "X-MICROSOFT-CDO-ALLDAYEVENT:TRUE", "END:VEVENT",
        "END:VCALENDAR",
    )

    private fun parseProton() = Ics.parse(proton, deviceZone = ZoneId.of("America/New_York"))
    private fun ev(cal: IcsCalendar, uid: String, rid: Boolean = false) =
        cal.events.first { it.uid == uid && (it.recurrenceId != null) == rid }

    // ---- lexing ----

    @Test fun unfoldsAcrossLinesAndDecodesUtf8SplitByFold() {
        // "é" (C3 A9) split across a fold: must be joined before decoding.
        val bytes = byteArrayOf(*"SUMMARY:caf".toByteArray(), 0xC3.toByte(), 0x0D, 0x0A, 0x20, 0xA9.toByte(), 0x0D, 0x0A)
        assertEquals(listOf("SUMMARY:café"), Ics.lines(bytes))
    }

    @Test fun acceptsLfOnlyAndBom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), *"A:1\nB:2\n C\n".toByteArray())
        assertEquals(listOf("A:1", "B:2C"), Ics.lines(bytes))
    }

    @Test fun quotedParamsMayContainColonsAndSemicolons() {
        val cl = Ics.contentLine("ORGANIZER;CN=\"Org, Person\";SENT-BY=\"mailto:a@b.c\":mailto:o@b.c")!!
        assertEquals("ORGANIZER", cl.name)
        assertEquals("Org, Person", cl.param("CN"))
        assertEquals("mailto:a@b.c", cl.param("SENT-BY"))
        assertEquals("mailto:o@b.c", cl.value)
    }

    @Test fun unescapesText() {
        assertEquals("a, b; c\nd\\e", Ics.unescape("a\\, b\\; c\\nd\\\\e"))
        assertEquals("no escapes", Ics.unescape("no escapes"))
    }

    @Test fun durations() {
        assertEquals(Duration.ofMinutes(-15), Ics.duration("-PT15M"))
        assertEquals(Duration.ofDays(8).plusHours(2), Ics.duration("P1WT2H".replace("WT", "W1DT")))
        assertEquals(Duration.ofMinutes(240), Ics.duration("PT240M"))
        assertNull(Ics.duration("P"))
    }

    // ---- Proton-style feed ----

    @Test fun calendarProperties() {
        val cal = parseProton()
        assertEquals("Work", cal.name)
        assertEquals(Duration.ofHours(4), cal.refreshInterval)
        assertEquals(9, cal.events.size) // 7 series/one-offs + 2 occurrences (moved, cancelled)
        assertEquals(0, cal.skipped)
    }

    @Test fun timedEventWithIanaZoneEscapesAndPeople() {
        val e = ev(parseProton(), "aBcD-123_xyz==")
        assertEquals("Planning, round 2", e.summary)
        assertEquals("Line one\nLine two; with semicolon and a very long tail that wraps onto a continuation line", e.description)
        assertEquals(ms(2026, 10, 5, 14, 0, london), e.start.toEpochMillis())
        assertEquals(ms(2026, 10, 5, 15, 0, london), e.end.toEpochMillis())
        assertEquals("organizer@example.org", e.organizer!!.email)
        assertEquals(listOf("alex@example.com", "sam@example.com"), e.attendees.map { it.email })
        assertEquals("ACCEPTED", e.attendees[0].partStat)
        assertEquals("https://meet.example.me/join/abc", e.conferenceUrl)
    }

    @Test fun birthdayIsAllDayYearly() {
        val e = ev(parseProton(), "birthday-1")
        assertTrue(e.allDay)
        assertEquals("FREQ=YEARLY", e.rrule)
        assertEquals("Sam’s Birthday", e.summary)
    }

    @Test fun floatingAndUtcTimes() {
        val e = ev(parseProton(), "utc-1")
        assertEquals(ms(2026, 10, 6, 16, 0, ZoneOffset.UTC), e.start.toEpochMillis())
    }

    // ---- rows for the provider ----

    private fun rows() = EventRows.build(parseProton()).associateBy { it.key }

    @Test fun seriesUsesDurationAndExdatesIncludingCancelledOccurrence() {
        val r = rows()["series-1"]!!.values
        assertNull(r[Events.DTEND])
        assertEquals("P900S", r[Events.DURATION])
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;UNTIL=20261231T235959Z", r[Events.RRULE])
        // Original EXDATE (9 Sep 09:30 BST) plus the cancelled 10 Sep occurrence, in UTC.
        assertEquals("20260909T083000Z,20260910T083000Z", r[Events.EXDATE])
        assertEquals("Europe/London", r[Events.EVENT_TIMEZONE])
        assertEquals("series-1", r[Events._SYNC_ID])
    }

    @Test fun movedOccurrenceLinksToSeries() {
        val key = "series-1@" + ms(2026, 9, 8, 9, 30, london)
        val r = rows()[key]!!.values
        assertEquals("series-1", r[Events.ORIGINAL_SYNC_ID])
        assertEquals(ms(2026, 9, 8, 9, 30, london), r[Events.ORIGINAL_INSTANCE_TIME])
        assertEquals(ms(2026, 9, 8, 11, 0, london), r[Events.DTSTART])
        assertEquals(ms(2026, 9, 8, 11, 15, london), r[Events.DTEND])
        assertEquals("Standup (moved)", r[Events.TITLE])
        // The cancelled occurrence is not a row of its own.
        assertFalse(rows().keys.any { it.startsWith("series-1@" + ms(2026, 9, 10, 9, 30, london)) })
    }

    @Test fun allDayRowsAreUtcMidnightWithUtcZone() {
        val trip = rows()["trip-1"]!!.values
        assertEquals(1, trip[Events.ALL_DAY])
        assertEquals(utcMidnight(2026, 10, 12), trip[Events.DTSTART])
        assertEquals(utcMidnight(2026, 10, 15), trip[Events.DTEND])
        assertEquals("UTC", trip[Events.EVENT_TIMEZONE])
        val bday = rows()["birthday-1"]!!.values
        assertEquals("P1D", bday[Events.DURATION])
        assertEquals(utcMidnight(1990, 3, 14), bday[Events.DTSTART])
    }

    @Test fun cancelledOneOffKeptAsCancelled() {
        assertEquals(Events.STATUS_CANCELED, rows()["gone-1"]!!.values[Events.STATUS])
    }

    @Test fun conferenceUrlAppendedToDescriptionAndPeopleMapped() {
        val row = rows()["aBcD-123_xyz=="]!!
        assertTrue((row.values[Events.DESCRIPTION] as String).endsWith("Join: https://meet.example.me/join/abc"))
        assertEquals("organizer@example.org", row.values[Events.ORGANIZER])
        assertEquals(Attendees.RELATIONSHIP_ORGANIZER, row.attendees[0][Attendees.ATTENDEE_RELATIONSHIP])
        assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED, row.attendees[1][Attendees.ATTENDEE_STATUS])
        assertEquals(Attendees.TYPE_OPTIONAL, row.attendees[2][Attendees.ATTENDEE_TYPE])
    }

    @Test fun complexRulesPassThroughUntouched() {
        assertEquals("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1", rows()["monthly-1"]!!.values[Events.RRULE])
    }

    @Test fun hashIsStableAndChangesWithContent() {
        val a = EventRows.build(parseProton()).associate { it.key to it.hash }
        val b = EventRows.build(parseProton()).associate { it.key to it.hash }
        assertEquals(a, b)
        val edited = Ics.parse(String(proton).replace("SUMMARY:Call", "SUMMARY:Call (edited)").toByteArray())
        val c = EventRows.build(edited).associate { it.key to it.hash }
        assertNotEquals(a["utc-1"], c["utc-1"])
        assertEquals(a["trip-1"], c["trip-1"])
    }

    // ---- Outlook/Exchange-style feed ----

    @Test fun windowsTimezoneResolvesToIana() {
        val cal = Ics.parse(outlook)
        val busy = cal.events.first { it.summary == "Busy block" }
        assertEquals(ms(2026, 10, 6, 9, 0, london), busy.start.toEpochMillis())
        assertEquals(london, (busy.start as IcsTime.Timed).zone)
        assertNull(busy.location) // "LOCATION:" is treated as none
        assertNull(busy.description) // "\n" only
        assertFalse(busy.free)
    }

    @Test fun outlookAllDayMultiDayAndFree() {
        val holiday = Ics.parse(outlook).events.first { it.summary == "Holiday" }
        assertTrue(holiday.allDay)
        assertTrue(holiday.free)
        val row = EventRows.build(Ics.parse(outlook)).first { it.values[Events.TITLE] == "Holiday" }.values
        assertEquals(Events.AVAILABILITY_FREE, row[Events.AVAILABILITY])
        assertEquals(utcMidnight(2026, 10, 24), row[Events.DTEND])
    }

    // ---- timezone resolution edge cases ----

    @Test fun timezoneFallbacks() {
        assertEquals(london, TimeZones.resolve("/freeassociation.sourceforge.net/Tzfile/Europe/London", emptyMap()))
        assertEquals(ZoneId.of("Europe/Berlin"), TimeZones.resolve("W. Europe Standard Time", emptyMap()))
        val custom = Ics.components(Ics.lines(feed(
            "BEGIN:VTIMEZONE", "TZID:My Custom Zone", "BEGIN:STANDARD", "TZOFFSETFROM:+0200", "TZOFFSETTO:+0100",
            "DTSTART:19701025T030000", "END:STANDARD", "END:VTIMEZONE",
        ))).first()
        assertEquals(ZoneOffset.ofHours(1), TimeZones.resolve("My Custom Zone", mapOf("My Custom Zone" to custom)))
        assertEquals("GMT+01:00", EventRows.timezone(IcsTime.Timed(java.time.Instant.EPOCH, ZoneOffset.ofHours(1))))
    }

    @Test fun floatingTimesUseCalendarZone() {
        val cal = Ics.parse(feed(
            "BEGIN:VCALENDAR", "X-WR-TIMEZONE:Europe/Lisbon", "BEGIN:VEVENT", "UID:f", "SUMMARY:F",
            "DTSTART:20261006T100000", "DURATION:PT1H", "END:VEVENT", "END:VCALENDAR",
        ), deviceZone = ZoneId.of("Asia/Tokyo"))
        val e = cal.events.single()
        assertEquals(ms(2026, 10, 6, 10, 0, ZoneId.of("Europe/Lisbon")), e.start.toEpochMillis())
        assertEquals(ms(2026, 10, 6, 11, 0, ZoneId.of("Europe/Lisbon")), e.end.toEpochMillis())
    }

    @Test fun alarmsAndBrokenEventsTolerated() {
        val cal = Ics.parse(feed(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT", "UID:a", "SUMMARY:With alarm", "DTSTART:20261006T100000Z", "DTEND:20261006T110000Z",
            "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT15M", "END:VALARM",
            "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER;RELATED=START:-P1D", "END:VALARM", "END:VEVENT",
            "BEGIN:VEVENT", "UID:no-start", "SUMMARY:Broken", "END:VEVENT",
            "junk line without colon",
            "END:VCALENDAR",
        ))
        assertEquals(listOf(15, 1440), cal.events.single().alarms)
        assertEquals(1, cal.skipped)
        assertEquals(listOf(15, 1440), EventRows.build(cal).single().reminders)
    }
}
