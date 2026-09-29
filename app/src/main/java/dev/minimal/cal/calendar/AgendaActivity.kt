package dev.minimal.cal.calendar

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.minimal.cal.Actions
import dev.minimal.cal.R
import dev.minimal.cal.agenda.AgendaFormat
import dev.minimal.cal.agenda.AgendaItem
import dev.minimal.cal.calApp
import dev.minimal.cal.reminders.ReminderScheduler
import dev.minimal.cal.widget.WidgetUpdater
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** Upcoming events for the next 90 days, grouped by day. Opened by tapping the date on home. */
class AgendaActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var adapter: RowsAdapter
    private lateinit var empty: TextView

    private sealed class Row {
        data class Header(val text: String) : Row()
        data class Event(val item: AgendaItem, val label: String) : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        setContentView(R.layout.activity_agenda)
        empty = findViewById(R.id.agenda_empty)
        val list = findViewById<RecyclerView>(R.id.agenda_list)
        adapter = RowsAdapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null

        val root = findViewById<View>(R.id.agenda_root)
        val top = root.paddingTop
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(v.paddingLeft, top + bars.top, v.paddingRight, 0)
            list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, bars.bottom + 24)
            insets
        }
        findViewById<View>(R.id.agenda_open_calendar).setOnClickListener {
            startActivity(Intent(this, CalendarSettingsActivity::class.java))
        }
        empty.setOnClickListener { if (!CalendarStore.hasPermission(this)) requestAccess(force = true) }
    }

    override fun onResume() {
        super.onResume()
        if (!CalendarStore.hasPermission(this) || !ReminderScheduler.canNotify(this)) requestAccess(force = false)
        load()
    }

    /** First run asks for calendar + notification access; later taps re-ask or open app settings. */
    private fun requestAccess(force: Boolean) {
        val prefs = calApp.prefs
        val missing = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return
        if (!prefs.permissionsAsked) {
            prefs.permissionsAsked = true
            requestPermissions(missing.toTypedArray(), REQUEST_ACCESS)
        } else if (force) {
            if (missing.any { shouldShowRequestPermissionRationale(it) }) requestPermissions(missing.toTypedArray(), REQUEST_ACCESS)
            else Actions.safeStart(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", packageName, null)))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_ACCESS) return
        ReminderScheduler.request(this)
        WidgetUpdater.request(this)
        load()
    }

    private fun load() {
        val app = applicationContext
        val is24 = DateFormat.is24HourFormat(this)
        val today = getString(R.string.today)
        val tomorrow = getString(R.string.agenda_tomorrow)
        val allDay = getString(R.string.agenda_all_day)
        val nowWord = getString(R.string.agenda_now)
        EXECUTOR.execute {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val permitted = CalendarStore.hasPermission(app)
            val items = AgendaFormat.upcoming(
                CalendarStore.instances(app, now - DAY, now + 90 * DAY), now, zone, limit = 1000,
            )
            val rows = ArrayList<Row>()
            val todayDate = LocalDate.now(zone)
            val headerFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())
            val timeFmt = DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a", Locale.getDefault())
            var currentDay: LocalDate? = null
            for (item in items) {
                val day = Instant.ofEpochMilli(AgendaFormat.sortKey(item, now, zone)).atZone(zone).toLocalDate()
                if (day != currentDay) {
                    currentDay = day
                    rows += Row.Header(
                        when (day) {
                            todayDate -> today
                            todayDate.plusDays(1) -> tomorrow
                            else -> day.format(headerFmt)
                        }
                    )
                }
                val label = when {
                    item.allDay -> allDay
                    item.begin <= now && now < item.end -> nowWord
                    else -> Instant.ofEpochMilli(item.begin).atZone(zone).format(timeFmt)
                }
                rows += Row.Event(item, label)
            }
            main.post {
                if (isDestroyed) return@post
                adapter.submit(rows)
                empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                empty.setText(if (permitted) R.string.agenda_empty else R.string.agenda_no_permission)
            }
        }
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Row>) {
            rows = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val layout = if (viewType == 0) R.layout.item_agenda_header else R.layout.item_agenda_event
            return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder.itemView as TextView).text = row.text
                is Row.Event -> {
                    val v = holder.itemView
                    v.findViewById<TextView>(R.id.row_time).text = row.label
                    v.findViewById<View>(R.id.row_dot).backgroundTintList =
                        ColorStateList.valueOf(row.item.color or 0xFF000000.toInt())
                    v.findViewById<TextView>(R.id.row_title).text =
                        if (row.item.repeating) "${row.item.title}  ↻" else row.item.title
                    v.contentDescription = "${row.label}, ${row.item.title}"
                    v.setOnClickListener {
                        startActivity(EventActivity.intent(this@AgendaActivity, row.item.eventId, row.item.begin, row.item.end))
                    }
                }
            }
        }
    }

    private companion object {
        const val REQUEST_ACCESS = 1
        const val DAY = 24L * 60 * 60 * 1000
        val EXECUTOR = Executors.newSingleThreadExecutor { r -> Thread(r, "agenda-screen") }
    }
}
