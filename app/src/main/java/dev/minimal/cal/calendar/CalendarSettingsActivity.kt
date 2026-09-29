package dev.minimal.cal.calendar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.PowerManager
import android.text.InputType
import android.text.format.DateUtils
import android.widget.EditText
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
import dev.minimal.cal.sync.Subscription
import dev.minimal.cal.sync.SubscriptionSync
import dev.minimal.cal.sync.Subscriptions
import dev.minimal.cal.sync.SyncJob
import java.util.concurrent.Executors

/** Settings → Calendar & reminders: on/off, and per-calendar reminder, all-day time and sound. */
class CalendarSettingsActivity : Activity() {

    private val prefs get() = calApp.prefs
    private val main = Handler(Looper.getMainLooper())
    private lateinit var enabled: Switch
    private lateinit var calendarsView: LinearLayout
    private var calendars: List<CalendarInfo> = emptyList()
    private var soundPickerCalendar = -1L
    private lateinit var linkRows: LinearLayout
    private var pendingLink: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar_settings)
        enabled = findViewById(R.id.switch_reminders)
        calendarsView = findViewById(R.id.calendar_rows)
        enabled.isChecked = prefs.remindersEnabled && hasPermissions()
        enabled.setOnCheckedChangeListener { _, checked -> if (checked) enable() else setEnabled(false) }
        linkRows = findViewById(R.id.link_rows)
        findViewById<View>(R.id.row_add_link).setOnClickListener { addLinkDialog(null) }
        intent?.data?.let { addLinkDialog(it.toString()) } // opened from a webcal:// link
        findViewById<View>(R.id.row_system_calendar_notifications).setOnClickListener { openSystemCalendarNotifications() }
        findViewById<View>(R.id.row_add_widget).setOnClickListener { pinWidget() }
        findViewById<View>(R.id.row_background).setOnClickListener { openAutostart() }
        findViewById<View>(R.id.row_battery).setOnClickListener { requestNoBatteryLimits() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { addLinkDialog(it.toString()) }
    }

    override fun onResume() {
        super.onResume()
        renderLinks()
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
        if (requestCode == REQUEST_WRITE) {
            val url = pendingLink
            pendingLink = null
            if (url != null && canWriteCalendar()) addLink(url)
            return
        }
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

    // ---- Calendar links (subscriptions) ----

    private fun canWriteCalendar() = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun renderLinks() {
        val app = applicationContext
        EXECUTOR.execute {
            val subs = Subscriptions(app).all()
            main.post {
                if (isDestroyed) return@post
                linkRows.removeAllViews()
                for (sub in subs) {
                    val row = layoutInflater.inflate(R.layout.item_calendar_setting, linkRows, false)
                    row.findViewById<View>(R.id.cal_dot).backgroundTintList = android.content.res.ColorStateList.valueOf(sub.color)
                    row.findViewById<TextView>(R.id.cal_name).text = sub.displayName
                    row.findViewById<TextView>(R.id.cal_summary).text = linkStatus(sub)
                    row.setOnClickListener { linkOptions(sub) }
                    linkRows.addView(row)
                }
            }
        }
    }

    private fun linkStatus(sub: Subscription): String = when {
        sub.lastError != null -> getString(R.string.links_status_error, sub.lastError)
        sub.lastSync == 0L -> getString(R.string.links_status_never)
        else -> getString(
            R.string.links_status_ok,
            DateUtils.getRelativeTimeSpanString(sub.lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            sub.eventCount,
        )
    }

    private fun addLinkDialog(prefill: String?) {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val input = EditText(this).apply {
            hint = getString(R.string.links_add_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            prefill?.let { setText(it) }
        }
        val box = LinearLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.links_add_title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val url = SubscriptionSync.normalise(input.text.toString())
                if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) {
                    Toast.makeText(this, R.string.links_invalid, Toast.LENGTH_SHORT).show()
                } else if (!canWriteCalendar()) {
                    pendingLink = url
                    requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), REQUEST_WRITE)
                } else {
                    addLink(url)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addLink(url: String) {
        val app = applicationContext
        val store = Subscriptions(app)
        // Already subscribed: just refresh that one.
        store.all().firstOrNull { SubscriptionSync.normalise(it.url).equals(url, ignoreCase = true) }?.let { existing ->
            Toast.makeText(this, R.string.links_already, Toast.LENGTH_SHORT).show()
            syncNow(existing)
            return
        }
        val used = store.all().map { it.color }.toSet()
        val sub = Subscription(url = url, color = PALETTE.map { it.second }.firstOrNull { it !in used } ?: PALETTE[0].second)
        store.save(sub)
        Toast.makeText(this, R.string.links_adding, Toast.LENGTH_SHORT).show()
        renderLinks()
        EXECUTOR.execute {
            val result = SubscriptionSync.sync(app, sub.id, force = true)
            if (!result.ok) SubscriptionSync.remove(app, sub.id)
            SyncJob.schedule(app)
            main.post {
                if (isDestroyed) return@post
                if (result.ok) {
                    val name = Subscriptions(app).find(sub.id)?.displayName ?: sub.displayName
                    Toast.makeText(this, getString(R.string.links_added, name, result.events), Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(this).setTitle(R.string.links_failed).setMessage(result.message)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
                renderLinks()
                loadCalendars()
            }
        }
    }

    private fun linkOptions(sub: Subscription) {
        val items = arrayOf(
            getString(R.string.links_sync_now), getString(R.string.links_rename),
            getString(R.string.links_colour), getString(R.string.links_remove),
        )
        AlertDialog.Builder(this)
            .setTitle(sub.displayName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> syncNow(sub)
                    1 -> rename(sub)
                    2 -> chooseColour(sub)
                    3 -> confirmRemove(sub)
                }
            }
            .show()
    }

    private fun syncNow(sub: Subscription) {
        val app = applicationContext
        Toast.makeText(this, R.string.links_syncing, Toast.LENGTH_SHORT).show()
        EXECUTOR.execute {
            val result = SubscriptionSync.sync(app, sub.id, force = true)
            main.post {
                if (isDestroyed) return@post
                Toast.makeText(this, if (result.ok) getString(R.string.links_synced, result.events) else (result.message ?: getString(R.string.links_failed)), Toast.LENGTH_LONG).show()
                renderLinks()
            }
        }
    }

    private fun rename(sub: Subscription) {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val input = EditText(this).apply { setText(sub.displayName); isSingleLine = true; setSelectAllOnFocus(true) }
        val box = LinearLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.links_rename)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ -> updateLink(sub.id) { it.copy(customName = input.text.toString().trim().ifEmpty { null }) } }
            .setNeutralButton(R.string.links_name_default) { _, _ -> updateLink(sub.id) { it.copy(customName = null) } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseColour(sub: Subscription) {
        val names = PALETTE.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.links_colour)
            .setSingleChoiceItems(names, PALETTE.indexOfFirst { it.second == sub.color }) { d, which ->
                updateLink(sub.id) { it.copy(color = PALETTE[which].second) }
                d.dismiss()
            }
            .show()
    }

    private fun confirmRemove(sub: Subscription) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.links_remove_confirm, sub.displayName))
            .setPositiveButton(R.string.links_remove) { _, _ ->
                val app = applicationContext
                EXECUTOR.execute {
                    SubscriptionSync.remove(app, sub.id)
                    SyncJob.schedule(app)
                    main.post { if (!isDestroyed) { renderLinks(); loadCalendars() } }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateLink(id: String, change: (Subscription) -> Subscription) {
        val app = applicationContext
        EXECUTOR.execute {
            val updated = Subscriptions(app).update(id, change)
            updated?.calendarId?.let { SubscriptionSync.updateCalendarMeta(app, it, updated) }
            main.post { if (!isDestroyed) { renderLinks(); loadCalendars() } }
        }
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
        const val REQUEST_WRITE = 12
        val PALETTE = listOf(
            "Blue" to 0xFF4285F4.toInt(), "Green" to 0xFF33B679.toInt(), "Tomato" to 0xFFD50000.toInt(),
            "Tangerine" to 0xFFF4511E.toInt(), "Banana" to 0xFFF6BF26.toInt(), "Grape" to 0xFF8E24AA.toInt(),
            "Teal" to 0xFF009688.toInt(), "Flamingo" to 0xFFE67C73.toInt(), "Lavender" to 0xFF7986CB.toInt(),
            "Graphite" to 0xFF616161.toInt(),
        )
        val EXECUTOR = Executors.newSingleThreadExecutor { r -> Thread(r, "calendar-settings") }
    }
}
