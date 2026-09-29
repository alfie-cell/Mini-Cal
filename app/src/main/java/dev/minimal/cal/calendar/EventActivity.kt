package dev.minimal.cal.calendar

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.text.Html
import android.text.format.DateFormat
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import dev.minimal.cal.Actions
import dev.minimal.cal.R
import dev.minimal.cal.agenda.AgendaItem
import dev.minimal.cal.calApp
import dev.minimal.cal.reminders.ReminderOverride
import dev.minimal.cal.reminders.ReminderPicker
import dev.minimal.cal.reminders.ReminderPlanner
import dev.minimal.cal.reminders.ReminderScheduler
import dev.minimal.cal.reminders.ReminderSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** Minimal's own event details screen (replaces jumping out to the phone's calendar app). */
class EventActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var eventId = -1L
    private var begin = 0L
    private var end = 0L
    private var loaded: Loaded? = null
    private var pendingSoundKey: String? = null

    /** Everything the screen shows, loaded off the main thread. */
    private class Loaded(
        val details: EventDetails,
        val attendees: List<Attendee>,
        val ownReminders: List<Int>,
        val seriesRule: String?,
        val nextDates: List<Long>,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        setContentView(R.layout.activity_event)
        readIntent(intent)

        val content = findViewById<View>(R.id.event_content)
        val padTop = content.paddingTop
        val padBottom = content.paddingBottom
        content.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(v.paddingLeft, padTop + bars.top, v.paddingRight, padBottom + bars.bottom)
            insets
        }
        findViewById<View>(R.id.event_open_calendar).setOnClickListener {
            Actions.openInOtherCalendarApp(this, eventId, begin, end)
        }
        load()
    }

    /**
     * Our own extras, or a standard calendar link (VIEW content://com.android.calendar/events/ID
     * with CalendarContract.EXTRA_EVENT_BEGIN_TIME/END_TIME), e.g. from the launcher or other apps.
     */
    private fun readIntent(intent: Intent) {
        eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1)
        begin = intent.getLongExtra(EXTRA_BEGIN, 0)
        end = intent.getLongExtra(EXTRA_END, 0)
        val data = intent.data
        if (eventId < 0 && data != null) {
            eventId = runCatching { ContentUris.parseId(data) }.getOrDefault(-1)
            begin = intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0)
            end = intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0)
        }
    }

    private fun load() {
        val app = applicationContext
        EXECUTOR.execute {
            // A bare event link has no occurrence time: use the next (or current) occurrence.
            if (begin == 0L && eventId >= 0) {
                val now = System.currentTimeMillis()
                CalendarStore.instances(app, now - DAY, now + 400L * DAY, eventId = eventId, max = 1).firstOrNull()?.let {
                    begin = it.begin
                    end = it.end
                }
            }
            val details = CalendarStore.event(app, eventId)
            val loaded = details?.let { d ->
                // A changed occurrence belongs to its original series.
                val seriesId = d.originalId ?: d.id
                val seriesRule = d.rrule ?: d.originalId?.let { CalendarStore.event(app, it)?.rrule }
                val next = if (seriesRule != null) {
                    CalendarStore.instances(app, begin + 1, begin + 400L * DAY, eventId = seriesId, max = 4)
                        .map { it.begin }.filter { it > begin }.take(3)
                } else emptyList()
                Loaded(
                    details = d,
                    attendees = CalendarStore.attendees(app, d.id),
                    ownReminders = CalendarStore.eventReminders(app, listOf(d.id))[d.id].orEmpty(),
                    seriesRule = seriesRule,
                    nextDates = next,
                )
            }
            main.post { if (!isDestroyed) bind(loaded) }
        }
    }

    private fun bind(loaded: Loaded?) {
        val title = findViewById<TextView>(R.id.event_title)
        if (loaded == null) {
            title.setText(R.string.event_not_found)
            return
        }
        val d = loaded.details
        title.text = d.title.ifEmpty { getString(R.string.event_untitled) }
        findViewById<View>(R.id.event_dot).backgroundTintList =
            android.content.res.ColorStateList.valueOf(d.color or 0xFF000000.toInt())
        findViewById<TextView>(R.id.event_calendar).text = d.calendarName
        findViewById<TextView>(R.id.event_when).text = whenText(d.allDay)

        // Repeats: rule in words, this occurrence's place in the series, next dates.
        val repeatView = findViewById<TextView>(R.id.event_repeat)
        if (loaded.seriesRule != null) {
            val startDate = if (d.allDay) Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
            else Instant.ofEpochMilli(begin).atZone(ZoneId.systemDefault()).toLocalDate()
            val lines = mutableListOf("↻ " + (RepeatText.describe(loaded.seriesRule, startDate, Locale.getDefault()) ?: getString(R.string.event_repeats)))
            if (d.originalId != null) lines += getString(R.string.event_occurrence_changed)
            if (loaded.nextDates.isNotEmpty()) {
                val fmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
                val zone = if (d.allDay) ZoneOffset.UTC else ZoneId.systemDefault()
                lines += getString(R.string.event_next, loaded.nextDates.joinToString(" · ") {
                    Instant.ofEpochMilli(it).atZone(zone).format(fmt)
                })
            }
            repeatView.text = lines.joinToString("\n")
            repeatView.visibility = View.VISIBLE
        }

        // Location (tap for Maps) and a Join button for online meetings.
        d.location?.let { loc ->
            findViewById<TextView>(R.id.event_location).apply {
                text = loc
                visibility = View.VISIBLE
                setOnClickListener {
                    Actions.safeStart(this@EventActivity, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(loc))))
                }
            }
        }
        EventText.meetingLink(listOfNotNull(d.location, d.description).joinToString("\n"))?.let { link ->
            findViewById<TextView>(R.id.event_join).apply {
                visibility = View.VISIBLE
                setOnClickListener { Actions.safeStart(this@EventActivity, Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
            }
        }

        d.description?.let { raw ->
            val text = EventText.cleanDescription(raw) { html -> Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString() }
            if (text.isNotBlank()) {
                findViewById<TextView>(R.id.event_description_label).visibility = View.VISIBLE
                findViewById<TextView>(R.id.event_description).apply {
                    this.text = text
                    visibility = View.VISIBLE
                    movementMethod = LinkMovementMethod.getInstance()
                }
            }
        }

        bindPeople(d, loaded.attendees)
        this.loaded = loaded
        bindReminders()
        findViewById<View>(R.id.event_edit_reminders).setOnClickListener { editReminders() }
        findViewById<View>(R.id.event_sound).setOnClickListener { editSound() }
    }

    private fun bindPeople(d: EventDetails, attendees: List<Attendee>) {
        val people = attendees.filter { it.email.isNotEmpty() || it.name.isNotEmpty() }
        if (people.isEmpty() && d.organizer == null) return
        findViewById<TextView>(R.id.event_people_label).visibility = View.VISIBLE
        val container = findViewById<LinearLayout>(R.id.event_people)
        container.visibility = View.VISIBLE
        val rows = people.ifEmpty { listOf(Attendee("", d.organizer.orEmpty(), Attendees.ATTENDEE_STATUS_NONE, true)) }
        for (p in rows.sortedByDescending { it.organizer }) {
            val row = layoutInflater.inflate(R.layout.item_event_person, container, false) as TextView
            val who = p.name.ifEmpty { p.email }
            val status = when {
                p.organizer -> getString(R.string.event_organizer)
                p.status == Attendees.ATTENDEE_STATUS_ACCEPTED -> getString(R.string.event_accepted)
                p.status == Attendees.ATTENDEE_STATUS_DECLINED -> getString(R.string.event_declined)
                p.status == Attendees.ATTENDEE_STATUS_TENTATIVE -> getString(R.string.event_tentative)
                else -> null
            }
            row.text = listOfNotNull(who, status).joinToString(" · ")
            container.addView(row)
        }
    }

    // ---- Reminders: which apply, and per-event / per-series overrides ----

    /** This occurrence as the planner sees it (keys use the UID, so overrides survive re-syncs). */
    private fun item(l: Loaded) = AgendaItem(
        eventId, l.details.title, begin, end, l.details.allDay, l.details.color, l.details.calendarId,
        repeating = l.seriesRule != null, uid = l.details.uid,
    )

    private fun resolution(l: Loaded) = calApp.prefs.let { p ->
        ReminderPlanner.resolve(item(l), l.ownReminders, p.reminderSettings(l.details.calendarId), p::reminderOverride)
    }

    private fun bindReminders() {
        val l = loaded ?: return
        val r = resolution(l)
        val lines = mutableListOf(
            getString(R.string.event_reminders_label, ReminderPicker.labels(this, r.values, l.details.allDay)),
            when (r.source) {
                ReminderSource.OCCURRENCE -> getString(R.string.event_reminder_source_occurrence)
                // Non-repeating events store their override on the series key too; say "this event".
                ReminderSource.SERIES -> getString(
                    if (l.seriesRule != null) R.string.event_reminder_source_series else R.string.event_reminder_source_occurrence
                )
                ReminderSource.EVENT -> getString(R.string.event_reminder_source_event)
                ReminderSource.CALENDAR -> getString(R.string.event_reminder_source_calendar, l.details.calendarName)
            },
            getString(R.string.event_sound_label, soundName(r.soundUri)),
        )
        if (!calApp.prefs.remindersEnabled) lines += getString(R.string.event_reminders_off)
        findViewById<TextView>(R.id.event_reminder).text = lines.joinToString("\n")
    }

    /** For repeating events, ask whether a change is for this occurrence or the whole series. */
    private fun withScope(l: Loaded, action: (key: String, series: Boolean) -> Unit) {
        val it = item(l)
        if (l.seriesRule == null) {
            action(ReminderPlanner.seriesKey(it), true)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.event_scope_title)
            .setItems(arrayOf(getString(R.string.event_scope_this), getString(R.string.event_scope_series))) { _, which ->
                if (which == 0) action(ReminderPlanner.occurrenceKey(it), false)
                else action(ReminderPlanner.seriesKey(it), true)
            }
            .show()
    }

    private fun editReminders() {
        val l = loaded ?: return
        val prefs = calApp.prefs
        withScope(l) { key, series ->
            val existing = prefs.reminderOverride(key)
            ReminderPicker.show(
                this, getString(R.string.event_edit_reminders), l.details.allDay, resolution(l).values,
                onSave = { values ->
                    prefs.setReminderOverride(key, ReminderOverride(values, existing?.soundUri))
                    // A series change should be visible here too: drop this occurrence's own times.
                    if (series) {
                        val occKey = ReminderPlanner.occurrenceKey(item(l))
                        prefs.reminderOverride(occKey)?.let { prefs.setReminderOverride(occKey, it.copy(minutes = null)) }
                    }
                    changed()
                },
                onReset = {
                    prefs.setReminderOverride(key, existing?.soundUri?.let { ReminderOverride(null, it) })
                    changed()
                },
            )
        }
    }

    private fun editSound() {
        val l = loaded ?: return
        val prefs = calApp.prefs
        withScope(l) { key, _ ->
            AlertDialog.Builder(this)
                .setItems(arrayOf(getString(R.string.event_sound_choose), getString(R.string.event_sound_calendar))) { _, which ->
                    if (which == 1) {
                        prefs.setReminderOverride(key, prefs.reminderOverride(key)?.copy(soundUri = null))
                        changed()
                    } else {
                        pendingSoundKey = key
                        pickSound(resolution(l).soundUri)
                    }
                }
                .show()
        }
    }

    @Suppress("DEPRECATION")
    private fun pickSound(current: String?) {
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, default)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, when {
                current == null -> default
                current.isEmpty() -> null
                else -> Uri.parse(current)
            })
        try {
            startActivityForResult(intent, REQUEST_SOUND)
        } catch (_: Exception) {
            pendingSoundKey = null
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val key = pendingSoundKey ?: return
        pendingSoundKey = null
        if (requestCode != REQUEST_SOUND || resultCode != RESULT_OK) return
        val picked: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        val sound = when {
            picked == null -> ""                                   // Silent
            RingtoneManager.isDefault(picked) || picked == RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ->
                ReminderOverride.SOUND_DEFAULT
            else -> picked.toString()
        }
        val prefs = calApp.prefs
        prefs.setReminderOverride(key, (prefs.reminderOverride(key) ?: ReminderOverride(null)).copy(soundUri = sound))
        changed()
    }

    private fun changed() {
        ReminderScheduler.request(this)
        bindReminders()
    }

    private fun soundName(uri: String?): String = when {
        uri == null -> getString(R.string.reminder_sound_default)
        uri.isEmpty() -> getString(R.string.reminder_sound_silent)
        else -> runCatching { RingtoneManager.getRingtone(this, Uri.parse(uri))?.getTitle(this) }.getOrNull()
            ?: getString(R.string.reminder_sound_custom)
    }

    /** "Wednesday 30 September\n14:00 – 15:00", multi-day ranges, or "All day". */
    private fun whenText(allDay: Boolean): String {
        val locale = Locale.getDefault()
        val long = DateTimeFormatter.ofPattern("EEEE d MMMM", locale)
        val short = DateTimeFormatter.ofPattern("EEE d MMM", locale)
        if (allDay) {
            val first = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
            val last = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1).let { if (it < first) first else it }
            val dates = if (last == first) first.format(long) else "${first.format(short)} – ${last.format(short)}"
            return "$dates\n${getString(R.string.agenda_all_day)}"
        }
        val zone = ZoneId.systemDefault()
        val time = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a", locale)
        val s = Instant.ofEpochMilli(begin).atZone(zone)
        val e = Instant.ofEpochMilli(end).atZone(zone)
        return if (s.toLocalDate() == e.toLocalDate() || end <= begin) {
            "${s.format(long)}\n${s.format(time)} – ${e.format(time)}"
        } else {
            "${s.format(short)}, ${s.format(time)} –\n${e.format(short)}, ${e.format(time)}"
        }
    }

    companion object {
        private const val EXTRA_EVENT_ID = "event_id"
        private const val EXTRA_BEGIN = "begin"
        private const val EXTRA_END = "end"
        private const val DAY = 24L * 60 * 60 * 1000
        private const val REQUEST_SOUND = 21
        private val EXECUTOR = Executors.newSingleThreadExecutor { r -> Thread(r, "event-view") }

        /** Extras only (no component): the per-row fill-in for the widget's click template. */
        fun fillIn(eventId: Long, begin: Long, end: Long): Intent = Intent()
            .putExtra(EXTRA_EVENT_ID, eventId)
            .putExtra(EXTRA_BEGIN, begin)
            .putExtra(EXTRA_END, end)

        fun intent(context: Context, eventId: Long, begin: Long, end: Long): Intent =
            Intent(context, EventActivity::class.java)
                .putExtra(EXTRA_EVENT_ID, eventId)
                .putExtra(EXTRA_BEGIN, begin)
                .putExtra(EXTRA_END, end)
    }
}
