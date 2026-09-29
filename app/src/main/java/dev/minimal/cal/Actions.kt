package dev.minimal.cal

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log
import android.widget.Toast

/** Outward actions; every one is failure-safe. */
object Actions {
    private const val TAG = "Actions"

    fun safeStart(context: Context, intent: Intent): Boolean = try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Cannot start $intent", e)
        Toast.makeText(context, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        false
    }

    /**
     * Opens an event in another calendar app. Mini Cal handles calendar-event links itself, so it
     * excludes its own package (otherwise this would just reopen Mini Cal).
     */
    fun openInOtherCalendarApp(context: Context, eventId: Long, begin: Long, end: Long) {
        val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        val other = context.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo }
            .firstOrNull { it.packageName != context.packageName }
        if (other == null) {
            Toast.makeText(context, R.string.no_other_calendar_app, Toast.LENGTH_SHORT).show()
            return
        }
        safeStart(context, intent.setClassName(other.packageName, other.name))
    }
}
