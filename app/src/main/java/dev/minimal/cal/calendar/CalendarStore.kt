package dev.minimal.cal.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import dev.minimal.cal.agenda.AgendaItem

data class CalendarInfo(val id: Long, val name: String, val color: Int, val account: String)

data class Attendee(val name: String, val email: String, val status: Int, val organizer: Boolean)

data class EventDetails(
    val id: Long,
    val calendarId: Long,
    val calendarName: String,
    val color: Int,
    val title: String,
    val description: String?,
    val location: String?,
    val allDay: Boolean,
    val rrule: String?,
    /** Set when this row is a changed occurrence of a repeating series. */
    val originalId: Long?,
    val organizer: String?,
    val timezone: String?,
    val uid: String?,
)

/**
 * Read-only access to the device calendar database (every calendar on the phone, including
 * Calendar Sync's feeds). All calls do I/O: call off the main thread. Missing permission or a
 * provider error yields empty results rather than a crash.
 */
object CalendarStore {

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Expanded occurrences (repeats included) overlapping [from, to], visible calendars only. */
    fun instances(context: Context, from: Long, to: Long, eventId: Long? = null, max: Int = 1000): List<AgendaItem> {
        if (!hasPermission(context)) return emptyList()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
            Instances.DISPLAY_COLOR, Instances.CALENDAR_ID, Instances.RRULE, Instances.ORIGINAL_ID,
            Instances.UID_2445,
        )
        // Visible calendars only; skip events the user declined and cancelled ones.
        var selection = "${Instances.VISIBLE}=1 AND " +
            "${Instances.SELF_ATTENDEE_STATUS}!=${Attendees.ATTENDEE_STATUS_DECLINED} AND " +
            "(${Instances.STATUS} IS NULL OR ${Instances.STATUS}!=${Instances.STATUS_CANCELED})"
        if (eventId != null) selection += " AND ${Instances.EVENT_ID}=$eventId"
        val out = ArrayList<AgendaItem>()
        runCatching {
            context.contentResolver.query(uri, projection, selection, null, "${Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && out.size < max) {
                    val title = c.getString(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: continue
                    out += AgendaItem(
                        eventId = c.getLong(0),
                        title = title,
                        begin = c.getLong(2),
                        end = c.getLong(3),
                        allDay = c.getInt(4) != 0,
                        color = c.getInt(5),
                        calendarId = c.getLong(6),
                        repeating = !c.isNull(7) || !c.isNull(8),
                        uid = c.getString(9),
                    )
                }
            }
        }
        return out
    }

    /** Alert reminders stored on each event (minutes before start), e.g. from Outlook invites. */
    fun eventReminders(context: Context, eventIds: Collection<Long>): Map<Long, List<Int>> {
        if (eventIds.isEmpty() || !hasPermission(context)) return emptyMap()
        val out = HashMap<Long, MutableList<Int>>()
        eventIds.distinct().chunked(400).forEach { chunk ->
            runCatching {
                context.contentResolver.query(
                    Reminders.CONTENT_URI,
                    arrayOf(Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD),
                    "${Reminders.EVENT_ID} IN (${chunk.joinToString(",")}) AND " +
                        "${Reminders.METHOD} IN (${Reminders.METHOD_ALERT},${Reminders.METHOD_DEFAULT})",
                    null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val minutes = c.getInt(1)
                        // -1 is the provider's "use default"; treat as no explicit reminder.
                        if (minutes >= 0) out.getOrPut(c.getLong(0)) { mutableListOf() } += minutes
                    }
                }
            }
        }
        return out
    }

    fun calendars(context: Context): List<CalendarInfo> {
        if (!hasPermission(context)) return emptyList()
        val out = ArrayList<CalendarInfo>()
        runCatching {
            context.contentResolver.query(
                Calendars.CONTENT_URI,
                arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_COLOR, Calendars.ACCOUNT_NAME),
                "${Calendars.VISIBLE}=1", null, "${Calendars.CALENDAR_DISPLAY_NAME} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += CalendarInfo(c.getLong(0), prettyCalendarName(c.getString(1)), c.getInt(2), c.getString(3).orEmpty())
                }
            }
        }
        return out
    }

    fun event(context: Context, eventId: Long): EventDetails? {
        if (!hasPermission(context)) return null
        return runCatching {
            context.contentResolver.query(
                ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                arrayOf(
                    Events._ID, Events.CALENDAR_ID, Events.CALENDAR_DISPLAY_NAME, Events.DISPLAY_COLOR,
                    Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.ALL_DAY, Events.RRULE,
                    Events.ORIGINAL_ID, Events.ORGANIZER, Events.EVENT_TIMEZONE, Events.UID_2445,
                ),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                EventDetails(
                    id = c.getLong(0),
                    calendarId = c.getLong(1),
                    calendarName = prettyCalendarName(c.getString(2)),
                    color = c.getInt(3),
                    title = c.getString(4)?.trim().orEmpty(),
                    description = c.getString(5)?.trim()?.takeIf { it.isNotEmpty() },
                    location = c.getString(6)?.trim()?.takeIf { it.isNotEmpty() },
                    allDay = c.getInt(7) != 0,
                    rrule = c.getString(8)?.takeIf { it.isNotBlank() },
                    originalId = if (c.isNull(9)) null else c.getLong(9),
                    organizer = c.getString(10)?.takeIf { it.isNotBlank() },
                    timezone = c.getString(11),
                    uid = c.getString(12),
                )
            }
        }.getOrNull()
    }

    fun attendees(context: Context, eventId: Long): List<Attendee> {
        if (!hasPermission(context)) return emptyList()
        val out = ArrayList<Attendee>()
        runCatching {
            context.contentResolver.query(
                Attendees.CONTENT_URI,
                arrayOf(Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_RELATIONSHIP),
                "${Attendees.EVENT_ID}=?", arrayOf(eventId.toString()), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    out += Attendee(
                        name = c.getString(0).orEmpty(),
                        email = c.getString(1).orEmpty(),
                        status = c.getInt(2),
                        organizer = c.getInt(3) == Attendees.RELATIONSHIP_ORGANIZER,
                    )
                }
            }
        }
        return out
    }

    /** Some calendars store resource keys as names (MIUI's local calendars); make them readable. */
    private fun prettyCalendarName(raw: String?): String = when (raw) {
        null, "" -> "Calendar"
        "calendar_displayname_local" -> "Phone"
        "calendar_displayname_birthday" -> "Birthdays"
        else -> raw
    }

    /** The system's calendar authority, for change notifications. */
    val CONTENT_URI = CalendarContract.CONTENT_URI
}
