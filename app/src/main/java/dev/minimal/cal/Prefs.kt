package dev.minimal.cal

import android.content.Context
import dev.minimal.cal.reminders.CalendarReminderSettings
import dev.minimal.cal.reminders.ReminderOverride
import org.json.JSONArray
import org.json.JSONObject

/** Settings: reminders on/off, per-calendar reminder defaults, and per-event/series overrides. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("cal", Context.MODE_PRIVATE)
    private val overrides = context.getSharedPreferences("reminder_overrides", Context.MODE_PRIVATE)

    /** Whether the permission prompt has been shown before (to detect "don't ask again"). */
    var permissionsAsked: Boolean
        get() = sp.getBoolean("permissions_asked", false)
        set(value) = sp.edit().putBoolean("permissions_asked", value).apply()

    // ---- Event reminders ----

    var remindersEnabled: Boolean
        get() = sp.getBoolean(KEY_REMINDERS, true)
        set(value) = sp.edit().putBoolean(KEY_REMINDERS, value).apply()

    /** Per-calendar defaults; unset calendars use [CalendarReminderSettings.DEFAULT]. */
    fun reminderSettings(calendarId: Long): CalendarReminderSettings {
        val d = CalendarReminderSettings.DEFAULT
        return CalendarReminderSettings(
            timedMinutes = intList("rem_timed_list_$calendarId", "rem_timed_$calendarId", d.timedMinutes),
            allDayOffsets = intList("rem_allday_list_$calendarId", "rem_allday_$calendarId", d.allDayOffsets),
            // null = default notification sound, "" = silent, else a ringtone URI.
            soundUri = sp.getString("rem_sound_$calendarId", null),
        )
    }

    fun setReminderSettings(calendarId: Long, settings: CalendarReminderSettings) {
        sp.edit()
            .putString("rem_timed_list_$calendarId", settings.timedMinutes.joinToString(","))
            .putString("rem_allday_list_$calendarId", settings.allDayOffsets.joinToString(","))
            .remove("rem_timed_$calendarId")
            .remove("rem_allday_$calendarId")
            .apply {
                if (settings.soundUri == null) remove("rem_sound_$calendarId")
                else putString("rem_sound_$calendarId", settings.soundUri)
            }
            .apply()
    }

    /** Comma list; falls back to the single-value key used by the first reminders release. */
    private fun intList(key: String, legacyKey: String, default: List<Int>): List<Int> {
        sp.getString(key, null)?.let { v -> return v.split(',').mapNotNull { it.trim().toIntOrNull() } }
        if (sp.contains(legacyKey)) {
            val v = sp.getInt(legacyKey, NONE)
            return if (v == NONE) emptyList() else listOf(v)
        }
        return default
    }

    /** User overrides for one occurrence or a series, keyed by [ReminderPlanner] keys. */
    fun reminderOverride(key: String): ReminderOverride? = overrides.getString(key, null)?.let(::decodeOverride)

    fun allReminderOverrides(): Map<String, ReminderOverride> =
        overrides.all.mapNotNull { (k, v) -> (v as? String)?.let(::decodeOverride)?.let { k to it } }.toMap()

    fun setReminderOverride(key: String, value: ReminderOverride?) {
        if (value == null || value.isEmpty) overrides.edit().remove(key).apply()
        else overrides.edit().putString(key, encodeOverride(value)).apply()
    }

    private fun encodeOverride(o: ReminderOverride): String = JSONObject().apply {
        o.minutes?.let { put("m", JSONArray(it)) }
        o.soundUri?.let { put("s", it) }
    }.toString()

    private fun decodeOverride(json: String): ReminderOverride? = try {
        val o = JSONObject(json)
        val minutes = o.optJSONArray("m")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
        ReminderOverride(minutes, if (o.has("s")) o.getString("s") else null)
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val KEY_REMINDERS = "reminders_enabled"
        const val NONE = Int.MIN_VALUE
    }
}
