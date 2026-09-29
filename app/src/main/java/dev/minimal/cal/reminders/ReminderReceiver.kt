package dev.minimal.cal.reminders

import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.minimal.cal.widget.WidgetUpdater

/** Our own alarm + notification actions (not exported). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SNOOZE -> cancelNotification(context, intent)
            ACTION_DISMISS -> {
                cancelNotification(context, intent)
                return
            }
        }
        val pending = goAsync()
        Thread {
            try {
                // Preference I/O stays off the main thread.
                if (intent.action == ACTION_SNOOZE) {
                    ReminderScheduler.snooze(context, intent.getLongExtra(EXTRA_EVENT_ID, -1), intent.getLongExtra(EXTRA_BEGIN, -1))
                }
                ReminderScheduler.reschedule(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun cancelNotification(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        context.getSystemService(NotificationManager::class.java).cancel(id)
    }

    companion object {
        const val ACTION_FIRE = "dev.minimal.cal.REMINDER_FIRE"
        const val ACTION_SNOOZE = "dev.minimal.cal.REMINDER_SNOOZE"
        const val ACTION_DISMISS = "dev.minimal.cal.REMINDER_DISMISS"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_BEGIN = "begin"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}

/** System events that invalidate the plan: boot, app update, clock/zone changes, provider reminders. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Exported for system broadcasts; ignore anything else another app might send.
        if (intent.action !in HANDLED) return
        val pending = goAsync()
        Thread {
            try {
                WidgetUpdater.update(context.applicationContext)
                ReminderScheduler.reschedule(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

private val HANDLED = setOf(
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_MY_PACKAGE_REPLACED,
    Intent.ACTION_TIME_CHANGED,
    Intent.ACTION_TIMEZONE_CHANGED,
    "android.intent.action.EVENT_REMINDER",
)

/** Runs when the calendar database changes (content-URI trigger); re-plans and re-arms itself. */
class CalendarChangeJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                ReminderScheduler.reschedule(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
