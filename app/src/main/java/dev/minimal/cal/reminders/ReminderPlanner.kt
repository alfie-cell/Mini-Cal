package dev.minimal.cal.reminders

import dev.minimal.cal.agenda.AgendaFormat
import dev.minimal.cal.agenda.AgendaItem
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Per-calendar reminder defaults (any number of each).
 * @param timedMinutes minutes before a timed event.
 * @param allDayOffsets minutes from local midnight of an all-day event's first day
 *   (540 = 09:00 that day, -360 = 18:00 the day before).
 * @param soundUri null = system default sound, "" = silent, else a ringtone URI.
 */
data class CalendarReminderSettings(val timedMinutes: List<Int>, val allDayOffsets: List<Int>, val soundUri: String?) {
    companion object {
        val DEFAULT = CalendarReminderSettings(timedMinutes = listOf(10), allDayOffsets = listOf(9 * 60), soundUri = null)
    }
}

/**
 * A user override for one occurrence or a whole series, stored in the launcher.
 * @param minutes reminders in the event's terms (minutes before for timed events, offsets from
 *   local midnight for all-day ones); null = keep inherited times, empty = no reminders.
 * @param soundUri null = keep the calendar's sound, [SOUND_DEFAULT] = system default, "" = silent, else a URI.
 */
data class ReminderOverride(val minutes: List<Int>?, val soundUri: String? = null) {
    val isEmpty: Boolean get() = minutes == null && soundUri == null

    companion object {
        const val SOUND_DEFAULT = "default"
    }
}

enum class ReminderSource { OCCURRENCE, SERIES, EVENT, CALENDAR }

/** What applies to one occurrence: reminder values (see [ReminderOverride.minutes]), where they came from, and the sound. */
data class ReminderResolution(val values: List<Int>, val source: ReminderSource, val soundUri: String?)

/** An occurrence plus the reminders stored on the event itself (minutes before start). */
data class Occurrence(val item: AgendaItem, val eventReminders: List<Int>)

/** When to notify, for which occurrence, and with which sound. */
data class PlannedReminder(val triggerAt: Long, val item: AgendaItem, val source: ReminderSource, val soundUri: String?) {
    /** Stable id so each reminder fires once, even across restarts. */
    val key: String get() = "${item.eventId}|${item.begin}|$triggerAt"
}

/** Pure reminder rules (no Android), unit tested. */
object ReminderPlanner {

    /** Key for overrides that apply to every occurrence of an event (its whole series). */
    fun seriesKey(item: AgendaItem): String =
        item.uid?.takeIf { it.isNotBlank() }?.let { "uid:${item.calendarId}:$it" } ?: "id:${item.eventId}"

    /** Key for an override on one occurrence only. */
    fun occurrenceKey(item: AgendaItem): String = "${seriesKey(item)}@${item.begin}"

    /**
     * Which reminders apply, in priority order: this-occurrence override, series override, the
     * event's own reminders (e.g. Outlook invites), then the calendar's defaults.
     */
    fun resolve(
        item: AgendaItem,
        eventReminders: List<Int>,
        settings: CalendarReminderSettings,
        overrideFor: (String) -> ReminderOverride?,
    ): ReminderResolution {
        val occ = overrideFor(occurrenceKey(item))
        val ser = overrideFor(seriesKey(item))
        val own = eventReminders.filter { it >= 0 }.distinct()
        val (values, source) = when {
            occ?.minutes != null -> occ.minutes to ReminderSource.OCCURRENCE
            ser?.minutes != null -> ser.minutes to ReminderSource.SERIES
            // Event reminders are "minutes before"; for all-day events that means before local midnight.
            own.isNotEmpty() -> (if (item.allDay) own.map { -it } else own) to ReminderSource.EVENT
            else -> (if (item.allDay) settings.allDayOffsets else settings.timedMinutes) to ReminderSource.CALENDAR
        }
        val sound = when (val s = occ?.soundUri ?: ser?.soundUri) {
            null -> settings.soundUri
            ReminderOverride.SOUND_DEFAULT -> null
            else -> s
        }
        return ReminderResolution(values.distinct().sorted(), source, sound)
    }

    /** All reminders for [occurrences]; duplicates of the same occurrence are merged first. */
    fun plan(
        occurrences: List<Occurrence>,
        settingsFor: (calendarId: Long) -> CalendarReminderSettings,
        zone: ZoneId,
        overrideFor: (String) -> ReminderOverride? = { null },
    ): List<PlannedReminder> {
        val remindersByItem = occurrences.associate { it.item to it.eventReminders }
        val out = ArrayList<PlannedReminder>()
        for (item in AgendaFormat.dedupe(occurrences.map { it.item })) {
            val r = resolve(item, remindersByItem[item].orEmpty(), settingsFor(item.calendarId), overrideFor)
            val start = localStart(item, zone)
            for (v in r.values) {
                val at = if (item.allDay) start + v * MINUTE else start - v * MINUTE
                out += PlannedReminder(at, item, r.source, r.soundUri)
            }
        }
        return out.sortedBy { it.triggerAt }
    }

    /** Reminders to post now: triggered in (after, now], not already fired, and not stale. */
    fun due(planned: List<PlannedReminder>, after: Long, now: Long, fired: Set<String>): List<PlannedReminder> =
        planned.filter { it.triggerAt in (after + 1)..now && it.key !in fired && !isStale(it, now) }

    /** Next trigger time strictly after [now] that hasn't fired, or null. */
    fun next(planned: List<PlannedReminder>, now: Long, fired: Set<String>): Long? =
        planned.firstOrNull { it.triggerAt > now && it.key !in fired }?.triggerAt

    /** Don't announce an event that has already ended (e.g. phone was off for hours). */
    private fun isStale(r: PlannedReminder, now: Long): Boolean = r.item.end in 1 until now

    /** Local start: timed events use their instant; all-day events start at local midnight of their date. */
    fun localStart(item: AgendaItem, zone: ZoneId): Long =
        if (!item.allDay) item.begin
        else Instant.ofEpochMilli(item.begin).atZone(ZoneOffset.UTC).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()

    private const val MINUTE = 60_000L
}
