package dev.minimal.cal.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Turns an iCalendar RRULE (as stored in CalendarContract) into a short English sentence:
 * "Daily", "Every 2 weeks on Mon, Wed", "Weekdays", "Monthly on day 15",
 * "Monthly on the 2nd Tue", "Yearly on 30 Sep", "... until 12 Dec 2026", "... (5 times)".
 * Pure Kotlin so it's unit tested. Unknown parts fall back to a plain "Repeats".
 */
object RepeatText {

    fun describe(rrule: String?, start: LocalDate, locale: Locale = Locale.UK): String? {
        if (rrule.isNullOrBlank()) return null
        val parts = rrule.removePrefix("RRULE:").split(';').mapNotNull {
            val i = it.indexOf('='); if (i > 0) it.substring(0, i).uppercase() to it.substring(i + 1) else null
        }.toMap()
        val interval = parts["INTERVAL"]?.toIntOrNull()?.takeIf { it > 1 } ?: 1
        val byDay = parts["BYDAY"]?.split(',')?.filter { it.isNotBlank() }.orEmpty()

        val base = when (parts["FREQ"]?.uppercase()) {
            "DAILY" -> every(interval, "Daily", "day")
            "WEEKLY" -> {
                val days = byDay.mapNotNull { dayOf(it) }
                when {
                    interval == 1 && days.toSet() == WEEKDAYS -> "Weekdays"
                    days.isEmpty() -> every(interval, "Weekly", "week") + " on " + shortDay(start.dayOfWeek, locale)
                    else -> every(interval, "Weekly", "week") + " on " + days.sorted().joinToString(", ") { shortDay(it, locale) }
                }
            }
            "MONTHLY" -> {
                val monthDay = parts["BYMONTHDAY"]?.toIntOrNull()
                val ordinalDay = byDay.firstOrNull()?.let { ordinalWeekday(it, locale) }
                every(interval, "Monthly", "month") + when {
                    ordinalDay != null -> " on the $ordinalDay"
                    monthDay == -1 -> " on the last day"
                    monthDay != null -> " on day $monthDay"
                    else -> " on day ${start.dayOfMonth}"
                }
            }
            "YEARLY" -> every(interval, "Yearly", "year") + " on " +
                start.format(DateTimeFormatter.ofPattern("d MMM", locale))
            else -> return "Repeats"
        }

        val until = parts["UNTIL"]?.let(::parseUntil)
        val count = parts["COUNT"]?.toIntOrNull()
        return when {
            until != null -> "$base until ${until.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))}"
            count != null -> "$base ($count times)"
            else -> base
        }
    }

    private val WEEKDAYS = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )

    private fun every(interval: Int, single: String, unit: String) =
        if (interval == 1) single else "Every $interval ${unit}s"

    /** "MO" / "2TU" / "-1FR" -> day of week (ignoring the ordinal). */
    private fun dayOf(code: String): DayOfWeek? = when (code.takeLast(2).uppercase()) {
        "MO" -> DayOfWeek.MONDAY; "TU" -> DayOfWeek.TUESDAY; "WE" -> DayOfWeek.WEDNESDAY
        "TH" -> DayOfWeek.THURSDAY; "FR" -> DayOfWeek.FRIDAY; "SA" -> DayOfWeek.SATURDAY
        "SU" -> DayOfWeek.SUNDAY; else -> null
    }

    /** "2TU" -> "2nd Tue", "-1FR" -> "last Fri"; null when there's no ordinal. */
    private fun ordinalWeekday(code: String, locale: Locale): String? {
        val n = code.dropLast(2).toIntOrNull() ?: return null
        val day = dayOf(code) ?: return null
        val ord = when (n) {
            -1 -> "last"; 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th"
        }
        return "$ord ${shortDay(day, locale)}"
    }

    private fun shortDay(d: DayOfWeek, locale: Locale) = d.getDisplayName(TextStyle.SHORT, locale)

    /** UNTIL is "yyyyMMdd" or "yyyyMMdd'T'HHmmss['Z']"; only the date matters for display. */
    private fun parseUntil(v: String): LocalDate? = try {
        LocalDate.parse(v.take(8), DateTimeFormatter.BASIC_ISO_DATE)
    } catch (_: Exception) {
        null
    }
}
