package dev.minimal.cal.agenda

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** One occurrence of an event (recurring events are already expanded). Times are epoch millis. */
data class AgendaItem(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
    val calendarId: Long = 0,
    /** Part of a repeating series (or a changed occurrence of one). */
    val repeating: Boolean = false,
    /** iCalendar UID (stable across re-syncs; shared by a series and its changed occurrences). */
    val uid: String? = null,
)

/** Localised words the formatter needs; kept out of the logic so it stays JVM-testable. */
data class AgendaWords(val now: String, val tomorrow: String, val allDay: String)

/**
 * Pure (no Android) rules for which events are upcoming and how their time is labelled.
 *
 * All-day events are stored by Android as UTC midnights, so their dates are read in UTC;
 * timed events are shown in the device zone.
 */
object AgendaFormat {

    /** First local date of an all-day event, and the (exclusive) date it ends. */
    private fun allDayDates(item: AgendaItem): Pair<LocalDate, LocalDate> {
        val start = Instant.ofEpochMilli(item.begin).atZone(ZoneOffset.UTC).toLocalDate()
        val endExclusive = Instant.ofEpochMilli(item.end).atZone(ZoneOffset.UTC).toLocalDate()
        return start to maxOf(endExclusive, start.plusDays(1))
    }

    /** True while the item is still relevant: in progress or in the future. */
    fun isUpcoming(item: AgendaItem, now: Long, zone: ZoneId): Boolean {
        if (!item.allDay) return item.end > now || (item.end == item.begin && item.begin >= now)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return allDayDates(item).second > today
    }

    /** Sort key: local start instant, with all-day events at the start of their (local) day. */
    fun sortKey(item: AgendaItem, now: Long, zone: ZoneId): Long {
        if (!item.allDay) return item.begin
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val firstShownDay = maxOf(allDayDates(item).first, today)
        return firstShownDay.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Upcoming items in display order, duplicates merged, capped at [limit]. */
    fun upcoming(items: List<AgendaItem>, now: Long, zone: ZoneId, limit: Int): List<AgendaItem> =
        dedupe(items.filter { isUpcoming(it, now, zone) })
            .sortedWith(compareBy<AgendaItem> { sortKey(it, now, zone) }.thenBy { !it.allDay }.thenBy { it.begin })
            .take(limit)

    /**
     * Drops repeats of the same occurrence: same start, end, all-day flag and title, where the
     * title comparison ignores case, spacing and apostrophe/dash style. Catches the same event
     * imported twice into one calendar ("Sam's" vs "Sam’s Birthday") and the same meeting
     * present in two calendars. The first occurrence (and its colour) wins.
     */
    fun dedupe(items: List<AgendaItem>): List<AgendaItem> {
        val seen = HashSet<String>(items.size)
        return items.filter { seen.add("${it.begin}|${it.end}|${it.allDay}|${normalizeTitle(it.title)}") }
    }

    private val APOSTROPHES = Regex("[\u2018\u2019\u201B\u02BC\u0060\u00B4]")
    private val DASHES = Regex("[\u2010-\u2015\u2212]")
    private val SPACES = Regex("\\s+")

    internal fun normalizeTitle(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(APOSTROPHES, "'")
            .replace(DASHES, "-")
            .replace(SPACES, " ")
            .trim()

    /**
     * The short label shown before the title: "Now", "14:00", "Tomorrow 09:30", "Wed 09:30",
     * "3 Oct 09:30", or for all-day events "All day", "Tomorrow", "Wed", "3 Oct".
     */
    fun label(
        item: AgendaItem,
        now: Long,
        zone: ZoneId,
        locale: Locale,
        is24Hour: Boolean,
        words: AgendaWords,
    ): String {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (item.allDay) {
            val (start, _) = allDayDates(item)
            return if (start <= today) words.allDay else dayLabel(start, today, locale, words)
        }
        if (item.begin <= now && now < item.end) return words.now
        val start = Instant.ofEpochMilli(item.begin).atZone(zone)
        val time = start.format(DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale))
        val date = start.toLocalDate()
        return if (date == today) time else "${dayLabel(date, today, locale, words)} $time"
    }

    /** "Tomorrow", a short weekday within the next 6 days, otherwise a short date. */
    private fun dayLabel(date: LocalDate, today: LocalDate, locale: Locale, words: AgendaWords): String =
        when {
            date == today.plusDays(1) -> words.tomorrow
            date < today.plusDays(7) -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            else -> date.format(DateTimeFormatter.ofPattern("d MMM", locale))
        }

    /**
     * When the list next needs re-evaluating without any data change: the next start or end of
     * a shown item (so "Now" flips on time), or the next local midnight, whichever is first.
     */
    fun nextRefresh(items: List<AgendaItem>, now: Long, zone: ZoneId): Long {
        val midnight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        var next = midnight
        for (item in items) {
            if (item.allDay) continue
            if (item.begin > now && item.begin < next) next = item.begin
            if (item.end > now && item.end < next) next = item.end
        }
        return next
    }
}
