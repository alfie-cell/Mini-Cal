package dev.minimal.cal.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.text.format.DateFormat
import android.util.Log
import dev.minimal.cal.R
import dev.minimal.cal.agenda.AgendaItem
import dev.minimal.cal.calendar.CalendarStore
import dev.minimal.cal.calendar.EventActivity
import dev.minimal.cal.calApp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Plans event reminders from the device calendar and keeps exactly one exact alarm set for the
 * next one. Each pass: post anything due since the last pass (catch-up after the phone was off),
 * then arm the alarm for the next reminder. Idempotent; safe to call from anywhere, often.
 */
object ReminderScheduler {
    private const val TAG = "Reminders"
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val PLAN_DAYS = 8L
    private const val MAX_POST_PER_PASS = 10
    const val SNOOZE_MINUTES = 10
    const val JOB_ID = 4201

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "reminders") }

    /** Re-plan in the background. */
    fun request(context: Context) {
        val app = context.applicationContext
        executor.execute { runCatching { reschedule(app) }.onFailure { Log.w(TAG, "reschedule failed", it) } }
    }

    /** Re-plan on the calling thread (receivers use goAsync + this). */
    fun reschedule(context: Context) {
        val prefs = context.calApp.prefs
        val state = context.getSharedPreferences("reminders_state", Context.MODE_PRIVATE)
        if (!prefs.remindersEnabled || !CalendarStore.hasPermission(context)) {
            cancelAlarm(context)
            cancelChangeJob(context)
            return
        }
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        // First pass after enabling: start from now, don't replay old reminders.
        val lastCheck = state.getLong("last_check", 0L).takeIf { it > 0 } ?: now

        val items = CalendarStore.instances(context, now - DAY, now + PLAN_DAYS * DAY)
        val own = CalendarStore.eventReminders(context, items.map { it.eventId })
        val overrides = prefs.allReminderOverrides()
        val plan = ReminderPlanner.plan(
            items.map { Occurrence(it, own[it.eventId].orEmpty()) }, prefs::reminderSettings, zone, overrides::get,
        )

        val fired = state.getStringSet("fired", emptySet())!!.toMutableSet()
        val firedKeys = fired.mapTo(HashSet()) { it.substringAfter(':') }

        // Post what's due, plus snoozed reminders whose time has come.
        val due = ReminderPlanner.due(plan, lastCheck, now, firedKeys)
        if (canNotify(context)) {
            due.take(MAX_POST_PER_PASS).forEach { post(context, it.item, it.soundUri) }
        }
        due.forEach { fired += "${it.triggerAt}:${it.key}" }
        val snoozes = state.getStringSet("snoozes", emptySet())!!.toMutableSet()
        val dueSnoozes = snoozes.filter { it.substringBefore('|').toLongOrNull()?.let { t -> t <= now } ?: true }
        dueSnoozes.forEach { entry ->
            parseSnooze(entry)?.let { (_, eventId, begin) ->
                val item = CalendarStore.instances(context, begin - DAY, begin + DAY, eventId = eventId)
                    .firstOrNull { it.begin == begin }
                if (item != null && canNotify(context)) {
                    val sound = ReminderPlanner.resolve(item, emptyList(), prefs.reminderSettings(item.calendarId), overrides::get).soundUri
                    post(context, item, sound)
                }
            }
        }
        snoozes -= dueSnoozes.toSet()

        // Prune bookkeeping older than 3 days.
        fired.removeAll { (it.substringBefore(':').toLongOrNull() ?: 0L) < now - 3 * DAY }
        state.edit()
            .putLong("last_check", now)
            .putStringSet("fired", fired)
            .putStringSet("snoozes", snoozes)
            .apply()

        // Arm the next alarm: next reminder, next snooze, or a daily re-plan as a safety net.
        val nextReminder = ReminderPlanner.next(plan, now, firedKeys + due.map { it.key })
        val nextSnooze = snoozes.mapNotNull { it.substringBefore('|').toLongOrNull() }.minOrNull()
        val next = listOfNotNull(nextReminder, nextSnooze, now + DAY).min()
        setAlarm(context, next)
        armChangeJob(context)
    }

    /** Snooze an occurrence: it re-fires in [SNOOZE_MINUTES]. */
    fun snooze(context: Context, eventId: Long, begin: Long) {
        val state = context.getSharedPreferences("reminders_state", Context.MODE_PRIVATE)
        val at = System.currentTimeMillis() + SNOOZE_MINUTES * MINUTE
        val snoozes = state.getStringSet("snoozes", emptySet())!!.toMutableSet()
        snoozes += "$at|$eventId|$begin"
        state.edit().putStringSet("snoozes", snoozes).apply()
    }

    private fun parseSnooze(entry: String): Triple<Long, Long, Long>? {
        val p = entry.split('|')
        if (p.size != 3) return null
        return Triple(p[0].toLongOrNull() ?: return null, p[1].toLongOrNull() ?: return null, p[2].toLongOrNull() ?: return null)
    }

    // ---- Notifications ----

    fun canNotify(context: Context) =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notificationId(eventId: Long, begin: Long) = (eventId * 31 + begin).hashCode()

    private fun post(context: Context, item: AgendaItem, soundUri: String?) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = channelFor(context, soundUri)
        val id = notificationId(item.eventId, item.begin)
        val location = CalendarStore.event(context, item.eventId)?.location

        val open = PendingIntent.getActivity(
            context, id, EventActivity.intent(context, item.eventId, item.begin, item.end)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(action: String, requestOffset: Int) = PendingIntent.getBroadcast(
            context, id + requestOffset,
            Intent(context, ReminderReceiver::class.java).setAction(action)
                .putExtra(ReminderReceiver.EXTRA_EVENT_ID, item.eventId)
                .putExtra(ReminderReceiver.EXTRA_BEGIN, item.begin)
                .putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = listOfNotNull(whenText(context, item), location).joinToString(" · ")
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_event)
            .setColor(item.color or 0xFF000000.toInt())
            .setContentTitle(item.title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_EVENT)
            .setWhen(item.begin)
            .setShowWhen(!item.allDay)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.reminder_snooze), action(ReminderReceiver.ACTION_SNOOZE, 1)).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.reminder_dismiss), action(ReminderReceiver.ACTION_DISMISS, 2)).build())
            .build()
        runCatching { nm.notify(id, notification) }.onFailure { Log.w(TAG, "notify failed", it) }
    }

    /** "In 10 min · 14:00–15:00", "Now · 14:00–15:00", "Today · All day", "Tomorrow · All day". */
    private fun whenText(context: Context, item: AgendaItem): String {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        if (item.allDay) {
            val date = ReminderPlanner.localStart(item, zone).let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            val today = LocalDate.now(zone)
            val day = when (date) {
                today -> context.getString(R.string.today)
                today.plusDays(1) -> context.getString(R.string.agenda_tomorrow)
                else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
            }
            return "$day · ${context.getString(R.string.agenda_all_day)}"
        }
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        val fmt = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        val range = "${Instant.ofEpochMilli(item.begin).atZone(zone).format(fmt)}–${Instant.ofEpochMilli(item.end).atZone(zone).format(fmt)}"
        val mins = ((item.begin - now + MINUTE - 1) / MINUTE).toInt()
        val lead = when {
            mins <= 0 -> context.getString(R.string.agenda_now)
            mins < 60 -> context.getString(R.string.reminder_in_minutes, mins)
            else -> context.getString(R.string.reminder_in_hours, mins / 60, mins % 60)
        }
        return "$lead · $range"
    }

    /**
     * Notification sound is fixed per channel once created, so each distinct sound gets its own
     * channel ("" = silent, null = default sound).
     */
    private fun channelFor(context: Context, soundUri: String?): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        val id = when {
            soundUri == null -> "reminders_default"
            soundUri.isEmpty() -> "reminders_silent"
            else -> "reminders_" + Integer.toHexString(soundUri.hashCode())
        }
        if (nm.getNotificationChannel(id) != null) return id
        val base = context.getString(R.string.reminder_channel)
        val name = when {
            soundUri == null -> base
            soundUri.isEmpty() -> "$base · ${context.getString(R.string.reminder_sound_silent)}"
            else -> "$base · " + (runCatching { RingtoneManager.getRingtone(context, Uri.parse(soundUri))?.getTitle(context) }.getOrNull() ?: "Custom")
        }
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            when {
                soundUri == null -> setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrs)
                soundUri.isEmpty() -> setSound(null, null)
                else -> setSound(Uri.parse(soundUri), attrs)
            }
        }
        nm.createNotificationChannel(channel)
        return id
    }

    // ---- Alarm + change job ----

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // USE_EXACT_ALARM is declared (calendar use); canScheduleExactAlarms() still guards it.
    @SuppressLint("MissingPermission")
    private fun setAlarm(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context)
        try {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarm(context: Context) =
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))

    /** One-shot job that fires when anything in the calendar database changes; re-armed each pass. */
    private fun armChangeJob(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, CalendarChangeJob::class.java))
            .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarStore.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setTriggerContentUpdateDelay(3_000)
            .setTriggerContentMaxDelay(60_000)
            .build()
        runCatching { js.schedule(job) }
    }

    private fun cancelChangeJob(context: Context) =
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
}
