package dev.minimal.cal.widget

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import dev.minimal.cal.R
import dev.minimal.cal.agenda.AgendaFormat
import dev.minimal.cal.agenda.AgendaWords
import dev.minimal.cal.calendar.AgendaActivity
import dev.minimal.cal.calendar.CalendarStore
import dev.minimal.cal.calendar.EventActivity
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** "Upcoming events" home-screen widget; works in any launcher (incl. MIUI's). */
class UpcomingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = WidgetUpdater.request(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        WidgetUpdater.request(context)
    override fun onEnabled(context: Context) = WidgetUpdater.request(context)
    override fun onDisabled(context: Context) = WidgetUpdater.cancel(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetUpdater.ACTION_REFRESH) WidgetUpdater.request(context)
    }
}

/** Runs when anything in the calendar database changes; refreshes widgets and re-arms itself. */
class WidgetCalendarJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                WidgetUpdater.update(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}

object WidgetUpdater {
    const val ACTION_REFRESH = "dev.minimal.cal.WIDGET_REFRESH"
    private const val TAG = "Widget"
    private const val JOB_ID = 4302
    private const val DAY = 24L * 60 * 60 * 1000
    private const val MAX_ROWS = 30
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "widget") }

    fun request(context: Context) {
        val app = context.applicationContext
        executor.execute { runCatching { update(app) }.onFailure { Log.w(TAG, "update failed", it) } }
    }

    /** Rebuilds every placed widget. Blocking: call off the main thread. */
    fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, UpcomingWidget::class.java))
        if (ids.isEmpty()) {
            cancel(context)
            return
        }
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val permitted = CalendarStore.hasPermission(context)
        val items = if (permitted) {
            AgendaFormat.upcoming(CalendarStore.instances(context, now - DAY, now + 90 * DAY), now, zone, MAX_ROWS)
        } else emptyList()

        val views = build(context, items, permitted, now, zone)
        manager.updateAppWidget(ids, views)

        // Re-evaluate when an event starts/ends ("Now") or the day rolls over; doesn't wake the phone.
        scheduleRefresh(context, AgendaFormat.nextRefresh(items, now, zone) + 1000)
        armCalendarJob(context)
    }

    private fun build(context: Context, items: List<dev.minimal.cal.agenda.AgendaItem>, permitted: Boolean, now: Long, zone: ZoneId): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_upcoming)
        val header = LocalDate.now(zone).format(DateTimeFormatter.ofPattern(
            DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), Locale.getDefault()))
        views.setTextViewText(R.id.widget_header, header)

        val openAgenda = PendingIntent.getActivity(
            context, 0, Intent(context, AgendaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_header, openAgenda)
        views.setOnClickPendingIntent(R.id.widget_empty, openAgenda)
        views.setTextViewText(R.id.widget_empty, context.getString(if (permitted) R.string.widget_empty else R.string.widget_no_permission))

        // Rows: one RemoteViews per event; taps fill in the event into a shared template intent.
        val words = AgendaWords(
            now = context.getString(R.string.agenda_now),
            tomorrow = context.getString(R.string.agenda_tomorrow),
            allDay = context.getString(R.string.agenda_all_day),
        )
        val is24 = DateFormat.is24HourFormat(context)
        val builder = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
        items.forEachIndexed { index, item ->
            val row = RemoteViews(context.packageName, R.layout.widget_row)
            row.setTextViewText(R.id.widget_row_label, AgendaFormat.label(item, now, zone, Locale.getDefault(), is24, words))
            row.setTextViewText(R.id.widget_row_title, if (item.repeating) "${item.title}  ↻" else item.title)
            row.setInt(R.id.widget_row_dot, "setColorFilter", item.color or 0xFF000000.toInt())
            row.setOnClickFillInIntent(R.id.widget_row, EventActivity.fillIn(item.eventId, item.begin, item.end))
            builder.addItem((item.eventId * 31 + item.begin + index).hashCode().toLong(), row)
        }
        views.setRemoteAdapter(R.id.widget_list, builder.build())
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)
        views.setPendingIntentTemplate(
            R.id.widget_list,
            PendingIntent.getActivity(
                context, 1, Intent(context, EventActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                // Mutable so each row's fill-in intent can add its event.
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        views.setViewVisibility(R.id.widget_list, if (items.isEmpty()) View.GONE else View.VISIBLE)
        return views
    }

    private fun refreshIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, UpcomingWidget::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // USE_EXACT_ALARM is declared (calendar use); canScheduleExactAlarms() still guards it.
    @SuppressLint("MissingPermission")
    private fun scheduleRefresh(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        try {
            // RTC (not _WAKEUP): fires when the phone is next awake, which is when anyone can see it.
            if (am.canScheduleExactAlarms()) am.setExact(AlarmManager.RTC, at, refreshIntent(context))
            else am.set(AlarmManager.RTC, at, refreshIntent(context))
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC, at, refreshIntent(context))
        }
    }

    private fun armCalendarJob(context: Context) {
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, WidgetCalendarJob::class.java))
            .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarStore.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setTriggerContentUpdateDelay(2_000)
            .setTriggerContentMaxDelay(30_000)
            .build()
        runCatching { context.getSystemService(JobScheduler::class.java).schedule(job) }
    }

    /** No widgets placed: stop refreshing. */
    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(refreshIntent(context))
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}
