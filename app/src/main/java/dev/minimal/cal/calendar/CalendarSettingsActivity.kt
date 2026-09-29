package dev.minimal.cal.calendar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.PowerManager
import android.widget.Toast
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import dev.minimal.cal.Actions
import dev.minimal.cal.R
import dev.minimal.cal.calApp
import dev.minimal.cal.reminders.CalendarReminderSettings
import dev.minimal.cal.reminders.ReminderPicker
import dev.minimal.cal.reminders.ReminderScheduler
import dev.minimal.cal.widget.UpcomingWidget
import java.util.concurrent.Executors

/** Settings → Calendar & reminders: on/off, and per-calendar reminder, all-day time and sound. */
class CalendarSettingsActivity : Activity() {

    private val prefs get() = calApp.prefs
    private val main = Handler(Looper.getMainLooper())
    private lateinit var enabled: Switch
    private lateinit var calendarsView: LinearLayout
    private var calendars: List<CalendarInfo> = emptyList()
    private var soundPickerCalendar = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar_settings)
        enabled = findViewById(R.id.switch_reminders)
        calendarsView = findViewById(R.id.calendar_rows)
        enabled.isChecked = prefs.remindersEnabled && hasPermissions()
        enabled.setOnCheckedChangeListener { _, checked -> if (checked) enable() else setEnabled(false) }
        findViewById<View>(R.id.row_system_calendar_notifications).setOnClickListener { openSystemCalendarNotifications() }
        findViewById<View>(R.id.row_add_widget).setOnClickListener { pinWidget() }
        findViewById<View>(R.id.row_background).setOnClickListener { openAutostart() }
        findViewById<View>(R.id.row_battery).setOnClickListener { requestNoBatteryLimits() }
    }

    override fun onResume() {
        super.onResume()
        loadCalendars()
    }

    private fun hasPermissions() = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.POST_NOTIFICATIONS)
        .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun enable() {
        val missing = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) setEnabled(true) else requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val ok = hasPermissions()
        setEnabled(ok)
        if (!ok) {
            enabled.isChecked = false
            // Denied permanently: the dialog won't show again, so offer app settings.
            Actions.safeStart(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", packageName, null)))
        }
        loadCalendars()
    }

    private fun setEnabled(on: Boolean) {
        prefs.remindersEnabled = on
        ReminderScheduler.request(this)
    }

    private fun loadCalendars() {
        val app = applicationContext
        EXECUTOR.execute {
            val list = CalendarStore.calendars(app)
            main.post { if (!isDestroyed) renderCalendars(list) }
        }
    }

    private fun renderCalendars(list: List<CalendarInfo>) {
        calendars = list
        calendarsView.removeAllViews()
        for (cal in list) {
            val row = layoutInflater.inflate(R.layout.item_calendar_setting, calendarsView, false)
            row.findViewById<View>(R.id.cal_dot).backgroundTintList =
                android.content.res.ColorStateList.valueOf(cal.color or 0xFF000000.toInt())
            row.findViewById<TextView>(R.id.cal_name).text = cal.name
            row.findViewById<TextView>(R.id.cal_summary).text = summary(prefs.reminderSettings(cal.id))
            row.setOnClickListener { editCalendar(cal) }
            calendarsView.addView(row)
        }
        findViewById<View>(R.id.calendars_empty).visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun summary(s: CalendarReminderSettings) = listOf(
        ReminderPicker.labels(this, s.timedMinutes, allDay = false),
        getString(R.string.reminder_allday_prefix, ReminderPicker.labels(this, s.allDayOffsets, allDay = true)),
        soundLabel(s.soundUri),
    ).joinToString(" · ")

    private fun editCalendar(cal: CalendarInfo) {
        val s = prefs.reminderSettings(cal.id)
        val items = arrayOf(
            getString(R.string.reminder_timed_title) + ": " + ReminderPicker.labels(this, s.timedMinutes, allDay = false),
            getString(R.string.reminder_allday_title) + ": " + ReminderPicker.labels(this, s.allDayOffsets, allDay = true),
            getString(R.string.reminder_sound_title) + ": " + soundLabel(s.soundUri),
        )
        AlertDialog.Builder(this)
            .setTitle(cal.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseTimed(cal)
                    1 -> chooseAllDay(cal)
                    2 -> chooseSound(cal)
                }
            }
            .show()
    }

    private fun chooseTimed(cal: CalendarInfo) {
        val s = prefs.reminderSettings(cal.id)
        ReminderPicker.show(this, getString(R.string.reminder_timed_title) + " · " + cal.name, allDay = false, current = s.timedMinutes,
            onSave = { save(cal, prefs.reminderSettings(cal.id).copy(timedMinutes = it)) })
    }

    private fun chooseAllDay(cal: CalendarInfo) {
        val s = prefs.reminderSettings(cal.id)
        ReminderPicker.show(this, getString(R.string.reminder_allday_title) + " · " + cal.name, allDay = true, current = s.allDayOffsets,
            onSave = { save(cal, prefs.reminderSettings(cal.id).copy(allDayOffsets = it)) })
    }

    @Suppress("DEPRECATION")
    private fun chooseSound(cal: CalendarInfo) {
        val current = prefs.reminderSettings(cal.id).soundUri
        soundPickerCalendar = cal.id
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.reminder_sound_title) + " · " + cal.name)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                when {
                    current == null -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    current.isEmpty() -> null
                    else -> Uri.parse(current)
                },
            )
        try {
            startActivityForResult(intent, REQUEST_SOUND)
        } catch (e: Exception) {
            Actions.safeStart(this, Intent(Settings.ACTION_SOUND_SETTINGS))
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SOUND || resultCode != RESULT_OK || soundPickerCalendar < 0) return
        val picked: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val sound = when {
            picked == null -> ""                 // "Silent"
            picked == default || RingtoneManager.isDefault(picked) -> null
            else -> picked.toString()
        }
        val s = prefs.reminderSettings(soundPickerCalendar)
        prefs.setReminderSettings(soundPickerCalendar, s.copy(soundUri = sound))
        ReminderScheduler.request(this)
        loadCalendars()
    }

    private fun save(cal: CalendarInfo, s: CalendarReminderSettings) {
        prefs.setReminderSettings(cal.id, s)
        ReminderScheduler.request(this)
        loadCalendars()
    }

    /** Asks the launcher to place the widget (Pixel and most launchers support this). */
    private fun pinWidget() {
        val manager = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, UpcomingWidget::class.java)
        val ok = manager.isRequestPinAppWidgetSupported && runCatching { manager.requestPinAppWidget(provider, null, null) }.getOrDefault(false)
        // MIUI accepts the request but silently drops it unless the app has Xiaomi's own
        // "Home screen shortcuts" permission (off by default), so explain and link to it.
        if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.pref_add_widget)
                .setMessage(R.string.widget_xiaomi_permission)
                .setPositiveButton(R.string.widget_open_permissions) { _, _ -> openXiaomiPermissions() }
                .setNegativeButton(android.R.string.ok, null)
                .show()
        } else if (!ok) {
            Toast.makeText(this, R.string.pref_widget_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    /** Xiaomi's per-app permission editor (has "Home screen shortcuts"), else app settings. */
    private fun openXiaomiPermissions() {
        val miui = Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", packageName)
        val started = runCatching { startActivity(miui); true }.getOrDefault(false)
        if (!started) Actions.safeStart(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", packageName, null)))
    }

    /** Xiaomi's Autostart screen when present, else this app's system settings page. */
    private fun openAutostart() {
        val miui = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        val started = runCatching { startActivity(miui); true }.getOrDefault(false)
        if (!started) Actions.safeStart(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", packageName, null)))
    }

    @SuppressLint("BatteryLife") // A calendar app's reminders must not be deferred; the user confirms in a system dialog.
    private fun requestNoBatteryLimits() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Actions.safeStart(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", packageName, null)))
        } else {
            Actions.safeStart(this, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.fromParts("package", packageName, null)))
        }
    }

    /** Opens the notification settings of the phone's own calendar app, to avoid double reminders. */
    private fun openSystemCalendarNotifications() {
        val pkg = packageManager.resolveActivity(
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR), 0,
        )?.activityInfo?.packageName
        if (pkg == null) {
            Actions.safeStart(this, Intent(Settings.ACTION_SETTINGS))
            return
        }
        Actions.safeStart(this, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
    }

    private fun soundLabel(uri: String?): String = when {
        uri == null -> getString(R.string.reminder_sound_default)
        uri.isEmpty() -> getString(R.string.reminder_sound_silent)
        else -> runCatching { RingtoneManager.getRingtone(this, Uri.parse(uri))?.getTitle(this) }.getOrNull()
            ?: getString(R.string.reminder_sound_custom)
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 10
        const val REQUEST_SOUND = 11
        val EXECUTOR = Executors.newSingleThreadExecutor { r -> Thread(r, "calendar-settings") }
    }
}
