package dev.minimal.cal.ics

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One event row for the calendar provider, plus its attendees/reminders, a stable [key]
 * (series: UID; changed occurrence: UID@original-start) and a content [hash] for diffing.
 * Column names are CalendarContract compile-time constants, so this stays JVM-testable.
 */
data class EventRow(
    val key: String,
    val values: Map<String, Any?>,
    val attendees: List<Map<String, Any?>>,
    val reminders: List<Int>,
    val hash: String,
)

object EventRows {
    private val UTC_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DATE_STAMP: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    fun build(calendar: IcsCalendar): List<EventRow> {
        // Series masters: if a UID appears twice without RECURRENCE-ID, keep the newest SEQUENCE.
        val masters = LinkedHashMap<String, IcsEvent>()
        for (e in calendar.events.filter { it.recurrenceId == null }) {
            val prev = masters[e.uid]
            if (prev == null || e.sequence >= prev.sequence) masters[e.uid] = e
        }
        val exceptions = calendar.events.filter { it.recurrenceId != null }
            .groupBy { it.uid to it.recurrenceId!!.toEpochMillis() }
            .map { (_, list) -> list.maxBy { it.sequence } }

        // A cancelled occurrence of a series is expressed as an EXDATE on the series.
        val extraExdates = HashMap<String, MutableList<IcsTime>>()
        val rows = ArrayList<EventRow>()
        for (ex in exceptions) {
            val master = masters[ex.uid]
            val seriesRepeats = master != null && (master.rrule != null || master.rdates.isNotEmpty())
            when {
                ex.cancelled && seriesRepeats -> extraExdates.getOrPut(ex.uid) { mutableListOf() } += ex.recurrenceId!!
                seriesRepeats -> rows += exceptionRow(ex, master!!)
                else -> rows += row(ex.copy(recurrenceId = null), key = "${ex.uid}@${ex.recurrenceId!!.toEpochMillis()}", extraExdates = emptyList())
            }
        }
        for ((uid, m) in masters) rows.add(0, row(m, key = uid, extraExdates = extraExdates[uid].orEmpty()))
        return rows
    }

    private fun row(e: IcsEvent, key: String, extraExdates: List<IcsTime>): EventRow {
        val v = content(e)
        v[Events.UID_2445] = e.uid
        v[Events._SYNC_ID] = if (key == e.uid) e.uid else key
        val repeats = e.rrule != null || e.rdates.isNotEmpty()
        if (repeats) {
            // Recurring events carry a DURATION instead of DTEND (a provider requirement).
            v[Events.DTEND] = null
            v[Events.DURATION] = duration(e)
            v[Events.RRULE] = e.rrule
            v[Events.RDATE] = dates(e.rdates, e.allDay)
            v[Events.EXDATE] = dates(e.exdates + extraExdates, e.allDay)
        } else {
            v[Events.DTEND] = e.end.toEpochMillis()
            v[Events.DURATION] = null
            v[Events.RRULE] = null
            v[Events.RDATE] = null
            v[Events.EXDATE] = null
        }
        return finish(key, v, e)
    }

    /** A changed occurrence, linked to its series by UID (ORIGINAL_SYNC_ID) and original start. */
    private fun exceptionRow(e: IcsEvent, master: IcsEvent): EventRow {
        val v = content(e)
        v[Events.UID_2445] = e.uid
        v[Events._SYNC_ID] = null
        v[Events.ORIGINAL_SYNC_ID] = master.uid
        v[Events.ORIGINAL_INSTANCE_TIME] = originalInstanceTime(e.recurrenceId!!, master)
        v[Events.ORIGINAL_ALL_DAY] = if (master.allDay) 1 else 0
        v[Events.DTEND] = e.end.toEpochMillis()
        v[Events.DURATION] = null
        v[Events.RRULE] = null
        v[Events.RDATE] = null
        v[Events.EXDATE] = null
        return finish("${e.uid}@${e.recurrenceId.toEpochMillis()}", v, e)
    }

    /** The occurrence's start as the series computes it (all-day series: UTC midnight of that date). */
    private fun originalInstanceTime(rid: IcsTime, master: IcsEvent): Long = when {
        master.allDay && rid is IcsTime.Timed -> rid.instant.atZone(rid.zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        else -> rid.toEpochMillis()
    }

    private fun content(e: IcsEvent): MutableMap<String, Any?> = linkedMapOf(
        Events.TITLE to (e.summary ?: "(No title)"),
        Events.DESCRIPTION to description(e),
        Events.EVENT_LOCATION to e.location,
        Events.DTSTART to e.start.toEpochMillis(),
        Events.ALL_DAY to if (e.allDay) 1 else 0,
        Events.EVENT_TIMEZONE to timezone(e.start),
        Events.EVENT_END_TIMEZONE to timezone(e.end),
        Events.STATUS to when {
            e.cancelled -> Events.STATUS_CANCELED
            e.tentative -> Events.STATUS_TENTATIVE
            else -> Events.STATUS_CONFIRMED
        },
        Events.AVAILABILITY to if (e.free) Events.AVAILABILITY_FREE else Events.AVAILABILITY_BUSY,
        Events.ORGANIZER to e.organizer?.email,
        Events.HAS_ATTENDEE_DATA to if (e.attendees.isNotEmpty() || e.organizer != null) 1 else 0,
        Events.HAS_ALARM to if (e.alarms.any { it >= 0 }) 1 else 0,
        Events.GUESTS_CAN_MODIFY to 0,
    )

    /** Description, with a dedicated conference link appended when it isn't already in the text. */
    private fun description(e: IcsEvent): String? {
        val url = e.conferenceUrl
        val base = e.description
        if (url == null || (base != null && url in base)) return base
        return listOfNotNull(base, "Join: $url").joinToString("\n\n")
    }

    private fun finish(key: String, values: MutableMap<String, Any?>, e: IcsEvent): EventRow {
        values[Events.SYNC_DATA2] = key
        val attendees = attendees(e)
        val reminders = e.alarms.filter { it >= 0 }.distinct().sorted()
        val hash = sha1(buildString {
            values.toSortedMap().forEach { (k, v) -> append(k).append('=').append(v).append('\n') }
            attendees.forEach { a -> a.toSortedMap().forEach { (k, v) -> append(k).append('=').append(v).append(';') }; append('\n') }
            append(reminders)
        })
        values[Events.SYNC_DATA1] = hash
        return EventRow(key, values, attendees, reminders, hash)
    }

    private fun attendees(e: IcsEvent): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        val organizer = e.organizer
        var organizerListed = false
        for (p in e.attendees) {
            val isOrganizer = organizer != null && p.email.equals(organizer.email, ignoreCase = true)
            organizerListed = organizerListed || isOrganizer
            out += person(p, if (isOrganizer) Attendees.RELATIONSHIP_ORGANIZER else Attendees.RELATIONSHIP_ATTENDEE)
        }
        if (organizer != null && !organizerListed) out.add(0, person(organizer, Attendees.RELATIONSHIP_ORGANIZER))
        return out
    }

    private fun person(p: IcsPerson, relationship: Int): Map<String, Any?> = linkedMapOf(
        Attendees.ATTENDEE_NAME to p.name,
        Attendees.ATTENDEE_EMAIL to p.email,
        Attendees.ATTENDEE_RELATIONSHIP to relationship,
        Attendees.ATTENDEE_TYPE to when (p.role) {
            "OPT-PARTICIPANT" -> Attendees.TYPE_OPTIONAL
            "NON-PARTICIPANT" -> Attendees.TYPE_NONE
            else -> Attendees.TYPE_REQUIRED
        },
        Attendees.ATTENDEE_STATUS to when (p.partStat) {
            "ACCEPTED" -> Attendees.ATTENDEE_STATUS_ACCEPTED
            "DECLINED" -> Attendees.ATTENDEE_STATUS_DECLINED
            "TENTATIVE" -> Attendees.ATTENDEE_STATUS_TENTATIVE
            "NEEDS-ACTION" -> Attendees.ATTENDEE_STATUS_INVITED
            else -> Attendees.ATTENDEE_STATUS_NONE
        },
    )

    /** "P3600S" / "P2D" as the provider expects for recurring events. */
    private fun duration(e: IcsEvent): String {
        val ms = (e.end.toEpochMillis() - e.start.toEpochMillis()).coerceAtLeast(0)
        return if (e.allDay) "P${maxOf(1, ms / 86_400_000)}D" else "P${ms / 1000}S"
    }

    /** RDATE/EXDATE lists: UTC stamps for timed events, plain dates for all-day ones. */
    private fun dates(list: List<IcsTime>, allDay: Boolean): String? {
        if (list.isEmpty()) return null
        return list.map {
            when {
                allDay && it is IcsTime.Date -> it.date.format(DATE_STAMP)
                allDay && it is IcsTime.Timed -> it.instant.atZone(it.zone).toLocalDate().format(DATE_STAMP)
                it is IcsTime.Date -> it.date.format(DATE_STAMP)
                else -> UTC_STAMP.format((it as IcsTime.Timed).instant)
            }
        }.distinct().joinToString(",")
    }

    /** A java.util.TimeZone-compatible id: IANA ids as-is; fixed offsets as UTC / GMT±hh:mm. */
    fun timezone(t: IcsTime): String = when (t) {
        is IcsTime.Date -> "UTC"
        is IcsTime.Timed -> zoneId(t.zone)
    }

    private fun zoneId(zone: ZoneId): String {
        if (zone !is ZoneOffset) return zone.id
        val s = zone.totalSeconds
        if (s == 0) return "UTC"
        val sign = if (s < 0) "-" else "+"
        val abs = kotlin.math.abs(s)
        return "GMT$sign%02d:%02d".format(abs / 3600, (abs % 3600) / 60)
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** For tests/diagnostics. */
    internal fun stamp(i: Instant): String = UTC_STAMP.format(i)
}
