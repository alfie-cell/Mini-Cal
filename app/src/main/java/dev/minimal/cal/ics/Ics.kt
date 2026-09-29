package dev.minimal.cal.ics

import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * A small iCalendar (RFC 5545) reader for subscription feeds, written for the shapes real
 * feeds use (Proton, Outlook/Exchange, Google): folded lines, escaped text, quoted parameters
 * (e.g. SENT-BY="mailto:…" containing ':'), DATE / UTC / TZID / floating times, IANA and Windows
 * TZIDs, RRULE/RDATE/EXDATE, RECURRENCE-ID exceptions, STATUS, TRANSP, attendees, VALARM.
 * Pure Kotlin (java.time only) so it's unit tested on the JVM.
 */

/** A property line: NAME;PARAM=a,b;PARAM2="x:y":value (value still escaped, as in the file). */
data class ContentLine(val name: String, val params: Map<String, List<String>>, val value: String) {
    fun param(key: String): String? = params[key]?.firstOrNull()
}

/** A BEGIN/END block with its properties and nested blocks. */
class Component(val name: String) {
    val lines = ArrayList<ContentLine>()
    val children = ArrayList<Component>()
    fun first(name: String): ContentLine? = lines.firstOrNull { it.name == name }
    fun all(name: String): List<ContentLine> = lines.filter { it.name == name }
}

/** A point in time as written in the feed. */
sealed class IcsTime {
    /** All-day date (VALUE=DATE). */
    data class Date(val date: LocalDate) : IcsTime()

    /** A resolved instant; [zone] is what the event should be displayed/recurred in. */
    data class Timed(val instant: Instant, val zone: ZoneId) : IcsTime()

    val isDate get() = this is Date

    /** Epoch millis; all-day dates are UTC midnight (Android's convention). */
    fun toEpochMillis(): Long = when (this) {
        is Date -> date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        is Timed -> instant.toEpochMilli()
    }
}

data class IcsPerson(val email: String, val name: String?, val partStat: String?, val role: String?)

data class IcsEvent(
    val uid: String,
    /** Set on a changed occurrence of a series (the original start of the occurrence it replaces). */
    val recurrenceId: IcsTime?,
    val summary: String?,
    val description: String?,
    val location: String?,
    val start: IcsTime,
    /** Exclusive end; derived from DURATION or start when DTEND is absent. */
    val end: IcsTime,
    val rrule: String?,
    val rdates: List<IcsTime>,
    val exdates: List<IcsTime>,
    val cancelled: Boolean,
    val tentative: Boolean,
    /** TRANSP:TRANSPARENT or Microsoft busy-status FREE. */
    val free: Boolean,
    val organizer: IcsPerson?,
    val attendees: List<IcsPerson>,
    /** VALARM display reminders, minutes before start (negative = after). */
    val alarms: List<Int>,
    /** Online-meeting link from a dedicated property (e.g. Proton's X-PM-CONFERENCE-URL). */
    val conferenceUrl: String?,
    val sequence: Int,
) {
    val allDay: Boolean get() = start.isDate
}

data class IcsCalendar(
    val name: String?,
    val description: String?,
    /** Feed's suggested refresh interval (REFRESH-INTERVAL / X-PUBLISHED-TTL). */
    val refreshInterval: Duration?,
    val events: List<IcsEvent>,
    /** Events that couldn't be read (e.g. no DTSTART), counted for diagnostics. */
    val skipped: Int,
)

class IcsException(message: String) : Exception(message)

object Ics {

    /** Parses a whole feed. [deviceZone] is used for floating times when the feed doesn't say. */
    fun parse(bytes: ByteArray, deviceZone: ZoneId = ZoneId.systemDefault()): IcsCalendar {
        val root = components(lines(bytes)).firstOrNull { it.name == "VCALENDAR" }
            ?: throw IcsException("Not an iCalendar feed (no BEGIN:VCALENDAR)")

        val calendarZone = root.first("X-WR-TIMEZONE")?.value?.let { TimeZones.resolve(it.trim(), emptyMap()) }
        val vtimezones = root.children.filter { it.name == "VTIMEZONE" }
            .mapNotNull { vtz -> vtz.first("TZID")?.value?.trim()?.let { it to vtz } }.toMap()
        val floatingZone = calendarZone ?: deviceZone
        val resolver = TimeResolver(vtimezones, floatingZone)

        var skipped = 0
        val events = root.children.filter { it.name == "VEVENT" }.mapNotNull { ev ->
            val parsed = try {
                event(ev, resolver)
            } catch (e: Exception) {
                null
            }
            if (parsed == null) skipped++ // no UID/DTSTART or unreadable dates
            parsed
        }
        return IcsCalendar(
            name = root.first("X-WR-CALNAME")?.let { unescape(it.value).trim() }?.takeIf { it.isNotEmpty() },
            description = root.first("X-WR-CALDESC")?.let { unescape(it.value).trim() }?.takeIf { it.isNotEmpty() },
            refreshInterval = (root.first("REFRESH-INTERVAL") ?: root.first("X-PUBLISHED-TTL"))?.value?.let { duration(it) },
            events = events,
            skipped = skipped,
        )
    }

    // ---- Layer 1: bytes -> unfolded lines -------------------------------------------------

    /**
     * Unfolds at the byte level (a fold may split a multi-byte UTF-8 character), then decodes.
     * Accepts CRLF, LF or CR line endings and a leading BOM.
     */
    fun lines(bytes: ByteArray): List<String> {
        val out = ByteArrayOutputStream(bytes.size)
        var i = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val result = ArrayList<String>()
        fun flush() {
            if (out.size() > 0) result += out.toString(Charsets.UTF_8.name())
            out.reset()
        }
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == '\r'.code.toByte() || b == '\n'.code.toByte()) {
                // Consume one line break (CRLF, LF or CR).
                var j = i + 1
                if (b == '\r'.code.toByte() && j < bytes.size && bytes[j] == '\n'.code.toByte()) j++
                // A following space/tab means the logical line continues.
                if (j < bytes.size && (bytes[j] == ' '.code.toByte() || bytes[j] == '\t'.code.toByte())) {
                    i = j + 1
                    continue
                }
                flush()
                i = j
                continue
            }
            out.write(b.toInt())
            i++
        }
        flush()
        return result
    }

    // ---- Layer 2: content lines ------------------------------------------------------------

    /** Splits NAME;params:value, respecting double-quoted parameter values (which may hold ':', ';', ','). */
    fun contentLine(line: String): ContentLine? {
        var i = 0
        while (i < line.length && line[i] != ';' && line[i] != ':') i++
        if (i >= line.length) return null
        val name = line.substring(0, i).trim().uppercase()
        if (name.isEmpty()) return null
        val params = LinkedHashMap<String, MutableList<String>>()
        while (i < line.length && line[i] == ';') {
            i++
            val eq = line.indexOf('=', i)
            if (eq < 0) return null
            val key = line.substring(i, eq).trim().uppercase()
            i = eq + 1
            val values = params.getOrPut(key) { mutableListOf() }
            // One or more comma-separated values, each optionally quoted.
            while (true) {
                if (i < line.length && line[i] == '"') {
                    val close = line.indexOf('"', i + 1)
                    if (close < 0) return null
                    values += line.substring(i + 1, close)
                    i = close + 1
                } else {
                    val start = i
                    while (i < line.length && line[i] != ',' && line[i] != ';' && line[i] != ':') i++
                    values += line.substring(start, i)
                }
                if (i < line.length && line[i] == ',') i++ else break
            }
        }
        if (i >= line.length || line[i] != ':') return null
        return ContentLine(name, params, line.substring(i + 1))
    }

    /** TEXT value unescaping: \n \N -> newline, \, \; \\ -> literal. */
    fun unescape(value: String): String {
        if ('\\' !in value) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val n = value[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    ',', ';', '\\', ':', '"' -> sb.append(n)
                    else -> sb.append(c).append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    // ---- Layer 3: components ---------------------------------------------------------------

    fun components(lines: List<String>): List<Component> {
        val roots = ArrayList<Component>()
        val stack = ArrayDeque<Component>()
        for (raw in lines) {
            if (raw.isBlank()) continue
            val cl = contentLine(raw) ?: continue // tolerate junk lines
            when (cl.name) {
                "BEGIN" -> {
                    val c = Component(cl.value.trim().uppercase())
                    if (stack.isEmpty()) roots += c else stack.last().children += c
                    stack.addLast(c)
                }
                "END" -> {
                    val want = cl.value.trim().uppercase()
                    // Pop to the matching BEGIN (tolerates a missing END inside).
                    while (stack.isNotEmpty()) if (stack.removeLast().name == want) break
                }
                else -> stack.lastOrNull()?.lines?.add(cl)
            }
        }
        return roots
    }

    // ---- Layer 4: events --------------------------------------------------------------------

    private fun event(ev: Component, r: TimeResolver): IcsEvent? {
        val uid = ev.first("UID")?.value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val start = r.time(ev.first("DTSTART") ?: return null)
        val end = ev.first("DTEND")?.let { r.time(it) }
            ?: ev.first("DURATION")?.let { d -> duration(d.value)?.let { plus(start, it) } }
            ?: defaultEnd(start)
        val status = ev.first("STATUS")?.value?.trim()?.uppercase()
        val busy = ev.first("X-MICROSOFT-CDO-BUSYSTATUS")?.value?.trim()?.uppercase()
        val transparent = ev.first("TRANSP")?.value?.trim()?.uppercase() == "TRANSPARENT"
        return IcsEvent(
            uid = uid,
            recurrenceId = ev.first("RECURRENCE-ID")?.let { r.time(it) },
            summary = text(ev, "SUMMARY"),
            description = text(ev, "DESCRIPTION"),
            location = text(ev, "LOCATION"),
            start = start,
            end = if (end.toEpochMillis() < start.toEpochMillis()) defaultEnd(start) else end,
            rrule = ev.first("RRULE")?.value?.trim()?.takeIf { it.isNotEmpty() },
            rdates = ev.all("RDATE").flatMap { r.times(it) },
            exdates = ev.all("EXDATE").flatMap { r.times(it) },
            cancelled = status == "CANCELLED",
            tentative = status == "TENTATIVE",
            free = if (busy != null) busy == "FREE" else transparent,
            organizer = ev.first("ORGANIZER")?.let(::person),
            attendees = ev.all("ATTENDEE").mapNotNull(::person),
            alarms = ev.children.filter { it.name == "VALARM" }.mapNotNull { alarmMinutes(it) }.distinct(),
            conferenceUrl = (ev.first("X-PM-CONFERENCE-URL") ?: ev.first("X-GOOGLE-CONFERENCE") ?: ev.first("X-MICROSOFT-SKYPETEAMSMEETINGURL"))
                ?.value?.trim()?.takeIf { it.startsWith("http") },
            sequence = ev.first("SEQUENCE")?.value?.trim()?.toIntOrNull() ?: 0,
        )
    }

    private fun text(c: Component, name: String): String? =
        c.first(name)?.let { unescape(it.value).trim() }?.takeIf { it.isNotEmpty() }

    /** "mailto:a@b" with CN/PARTSTAT/ROLE; tolerant of missing mailto: and odd casing. */
    private fun person(line: ContentLine): IcsPerson? {
        val v = line.value.trim()
        val email = (if (v.startsWith("mailto:", ignoreCase = true)) v.substring(7) else v).trim()
        if (email.isEmpty() || '@' !in email) return null
        return IcsPerson(
            email = email,
            name = line.param("CN")?.trim()?.takeIf { it.isNotEmpty() && it != email },
            partStat = line.param("PARTSTAT")?.uppercase(),
            role = line.param("ROLE")?.uppercase(),
        )
    }

    /** Display alarms with a relative TRIGGER (e.g. -PT15M), in minutes before start. */
    private fun alarmMinutes(alarm: Component): Int? {
        val action = alarm.first("ACTION")?.value?.trim()?.uppercase()
        if (action != null && action != "DISPLAY" && action != "AUDIO") return null
        val trigger = alarm.first("TRIGGER") ?: return null
        if (trigger.param("VALUE")?.uppercase() == "DATE-TIME") return null
        if (trigger.param("RELATED")?.uppercase() == "END") return null
        val d = duration(trigger.value) ?: return null
        return (-d.toMinutes()).toInt()
    }

    private fun defaultEnd(start: IcsTime): IcsTime = when (start) {
        is IcsTime.Date -> IcsTime.Date(start.date.plusDays(1))
        is IcsTime.Timed -> start
    }

    private fun plus(t: IcsTime, d: Duration): IcsTime = when (t) {
        is IcsTime.Date -> IcsTime.Date(t.date.plusDays(maxOf(1, d.toDays())))
        is IcsTime.Timed -> IcsTime.Timed(t.instant.plus(d), t.zone)
    }

    /** RFC 5545 duration: [+-]P[nW] or P[nD][T[nH][nM][nS]]. */
    fun duration(text: String): Duration? {
        val m = Regex("""^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""")
            .matchEntire(text.trim().uppercase()) ?: return null
        val (sign, w, d, h, min, s) = m.destructured
        if (w + d + h + min + s == "") return null
        var total = Duration.ofDays((w.toLongOrNull() ?: 0) * 7 + (d.toLongOrNull() ?: 0))
            .plusHours(h.toLongOrNull() ?: 0).plusMinutes(min.toLongOrNull() ?: 0).plusSeconds(s.toLongOrNull() ?: 0)
        if (sign == "-") total = total.negated()
        return total
    }
}

/** Turns DATE / DATE-TIME values into [IcsTime], resolving TZIDs. */
internal class TimeResolver(private val vtimezones: Map<String, Component>, private val floatingZone: ZoneId) {
    private val cache = HashMap<String, ZoneId>()

    fun time(line: ContentLine): IcsTime = times(line).firstOrNull() ?: throw IcsException("Empty date in ${line.name}")

    /** Handles comma-separated lists (EXDATE/RDATE); PERIOD values use their start. */
    fun times(line: ContentLine): List<IcsTime> {
        val isDate = line.param("VALUE")?.uppercase() == "DATE"
        val tzid = line.param("TZID")?.trim()
        return line.value.split(',').mapNotNull { raw ->
            val v = raw.trim().substringBefore('/')
            if (v.isEmpty()) null else parseOne(v, isDate, tzid)
        }
    }

    private fun parseOne(v: String, forceDate: Boolean, tzid: String?): IcsTime {
        if (forceDate || v.length == 8) return IcsTime.Date(LocalDate.parse(v.take(8), DateTimeFormatter.BASIC_ISO_DATE))
        val local = LocalDateTime.parse(v.removeSuffix("Z").removeSuffix("z").take(15), DATE_TIME)
        if (v.endsWith("Z", ignoreCase = true)) {
            return IcsTime.Timed(local.toInstant(ZoneOffset.UTC), tzid?.let { zone(it) } ?: ZoneOffset.UTC)
        }
        val zone = tzid?.let { zone(it) } ?: floatingZone
        return IcsTime.Timed(local.atZone(zone).toInstant(), zone)
    }

    private fun zone(tzid: String): ZoneId = cache.getOrPut(tzid) { TimeZones.resolve(tzid, vtimezones) ?: floatingZone }

    private companion object {
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    }
}

/** TZID -> ZoneId, for the names real feeds use. */
object TimeZones {
    /**
     * Order: IANA id; Windows name (CLDR); a prefixed IANA id ("/freeassociation…/Europe/London");
     * the VTIMEZONE's X-LIC-LOCATION; finally a fixed offset from the VTIMEZONE's STANDARD rule.
     */
    fun resolve(tzid: String, vtimezones: Map<String, Component>): ZoneId? {
        val id = tzid.trim().trim('"')
        iana(id)?.let { return it }
        WindowsZones.MAP[id]?.let { iana(it) }?.let { return it }
        // Prefixed ids: try each "Area/City" style suffix.
        val parts = id.split('/').filter { it.isNotEmpty() }
        for (n in 3 downTo 1) if (parts.size >= n) iana(parts.takeLast(n).joinToString("/"))?.let { return it }
        val vtz = vtimezones[tzid] ?: vtimezones[id] ?: return null
        vtz.first("X-LIC-LOCATION")?.value?.trim()?.let { iana(it) }?.let { return it }
        val standard = vtz.children.firstOrNull { it.name == "STANDARD" } ?: vtz.children.firstOrNull()
        return standard?.first("TZOFFSETTO")?.value?.trim()?.let { offset(it) }
    }

    private fun iana(id: String): ZoneId? =
        if ('/' in id || id == "UTC" || id == "GMT") runCatching { ZoneId.of(id) }.getOrNull() else null

    private fun offset(v: String): ZoneId? = runCatching {
        val m = Regex("""^([+-])(\d{2})(\d{2})(\d{2})?$""").matchEntire(v) ?: return null
        val sign = if (m.groupValues[1] == "-") -1 else 1
        ZoneOffset.ofHoursMinutesSeconds(
            sign * m.groupValues[2].toInt(), sign * m.groupValues[3].toInt(), sign * (m.groupValues[4].toIntOrNull() ?: 0),
        )
    }.getOrNull()
}
