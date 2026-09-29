package dev.minimal.cal.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.util.Log
import dev.minimal.cal.BuildConfig
import dev.minimal.cal.ics.EventRow
import dev.minimal.cal.ics.EventRows
import dev.minimal.cal.ics.Ics
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.TimeZone

/**
 * Keeps each subscribed calendar link mirrored into the device calendar:
 * download -> (skip if unchanged) -> parse -> diff by UID/occurrence -> batched provider writes.
 * Blocking; call off the main thread.
 */
object SubscriptionSync {
    private const val TAG = "SubscriptionSync"
    const val ACCOUNT_TYPE = "dev.minimal.cal.subscriptions"
    private const val ACCOUNT_NAME = "Mini Cal"
    private const val MAX_BYTES = 20 * 1024 * 1024
    private const val BATCH_OPS = 300

    val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)

    data class Result(val ok: Boolean, val events: Int, val message: String?)

    /** Normalises webcal(s):// to https:// and trims stray whitespace. */
    fun normalise(url: String): String {
        val u = url.trim()
        return when {
            u.startsWith("webcals://", true) -> "https://" + u.substring(10)
            u.startsWith("webcal://", true) -> "https://" + u.substring(9)
            else -> u
        }
    }

    @Synchronized
    fun syncAll(context: Context, force: Boolean = false) {
        val store = Subscriptions(context)
        removeOrphanCalendars(context, store.all())
        store.all().forEach { sync(context, it.id, force) }
    }

    /**
     * Deletes our calendars that no longer belong to a subscription (e.g. app data was cleared
     * while the account survived), so stale copies never linger in calendar apps.
     */
    fun removeOrphanCalendars(context: Context, subs: List<Subscription>) {
        // A calendar belongs to the subscription whose id is its NAME.
        val known = subs.map { it.id }.toSet()
        val orphans = ArrayList<Long>()
        runCatching {
            context.contentResolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.NAME),
                "${Calendars.ACCOUNT_TYPE}=?", arrayOf(ACCOUNT_TYPE), null,
            )?.use { c -> while (c.moveToNext()) if (c.getString(1) !in known) orphans += c.getLong(0) }
        }
        orphans.forEach { id ->
            runCatching { context.contentResolver.delete(asSyncAdapter(ContentUris.withAppendedId(Calendars.CONTENT_URI, id)), null, null) }
        }
    }

    @Synchronized
    fun sync(context: Context, id: String, force: Boolean = false): Result {
        val store = Subscriptions(context)
        val sub = store.find(id) ?: return Result(false, 0, "Unknown subscription")
        return try {
            ensureAccount(context)
            removeOrphanCalendars(context, store.all())
            val body = download(sub.url)
            val hash = sha256(body)
            var current = store.update(id) { it } ?: sub
            val calendarId = ensureCalendar(context, current)
            if (!force && hash == current.contentHash && current.calendarId == calendarId) {
                store.update(id) { it.copy(lastSync = System.currentTimeMillis(), lastError = null) }
                return Result(true, current.eventCount, null)
            }
            val cal = Ics.parse(body)
            val rows = EventRows.build(cal)
            apply(context, calendarId, rows)
            current = store.update(id) {
                it.copy(
                    feedName = cal.name ?: it.feedName, calendarId = calendarId, lastSync = System.currentTimeMillis(),
                    lastError = null, contentHash = hash, eventCount = cal.events.size,
                )
            } ?: current
            updateCalendarMeta(context, calendarId, current)
            Result(true, cal.events.size, if (cal.skipped > 0) "${cal.skipped} events couldn't be read" else null)
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed for ${sub.id}", e)
            val msg = e.message ?: e.javaClass.simpleName
            store.update(id) { it.copy(lastError = msg) }
            Result(false, 0, msg)
        }
    }

    /** Removes the subscription and its calendar (events go with it). */
    fun remove(context: Context, id: String) {
        val store = Subscriptions(context)
        store.find(id)?.calendarId?.let { calId ->
            runCatching { context.contentResolver.delete(asSyncAdapter(ContentUris.withAppendedId(Calendars.CONTENT_URI, calId)), null, null) }
        }
        store.remove(id)
    }

    /** Pushes name/colour changes to the provider calendar. */
    fun updateCalendarMeta(context: Context, calendarId: Long, sub: Subscription) {
        runCatching {
            context.contentResolver.update(
                asSyncAdapter(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId)),
                android.content.ContentValues().apply {
                    put(Calendars.CALENDAR_DISPLAY_NAME, sub.displayName)
                    put(Calendars.CALENDAR_COLOR, sub.color)
                },
                null, null,
            )
        }
    }

    // ---- network ----

    private fun download(url: String): ByteArray {
        var target = URL(normalise(url))
        repeat(5) {
            val conn = (target.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "text/calendar, */*;q=0.5")
                setRequestProperty("User-Agent", "MiniCal/${BuildConfig.VERSION_NAME} (Android)")
            }
            try {
                when (val code = conn.responseCode) {
                    in 200..299 -> return conn.inputStream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_BYTES) throw IllegalStateException("Feed is larger than 20 MB")
                        }
                        out.toByteArray()
                    }
                    301, 302, 303, 307, 308 -> {
                        val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("Redirect without location")
                        target = URL(target, loc) // may switch http <-> https
                    }
                    401, 403 -> throw IllegalStateException("Access denied (HTTP $code). Is the link still shared?")
                    404, 410 -> throw IllegalStateException("Link not found (HTTP $code). It may have been revoked.")
                    else -> throw IllegalStateException("Server error (HTTP $code)")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- provider ----

    private fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type)
        .build()

    private fun ensureAccount(context: Context) {
        val am = AccountManager.get(context)
        if (am.getAccountsByType(ACCOUNT_TYPE).none { it.name == ACCOUNT_NAME }) {
            am.addAccountExplicitly(account, null, null)
        }
    }

    /** Our calendar for this subscription; re-created if it was removed (e.g. account deleted). */
    private fun ensureCalendar(context: Context, sub: Subscription): Long {
        val cr = context.contentResolver
        sub.calendarId?.let { id ->
            // Match on NAME (our subscription id) too: provider row ids can be reused after a delete.
            cr.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID),
                "${Calendars._ID}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.NAME}=?",
                arrayOf(id.toString(), ACCOUNT_TYPE, sub.id), null,
            )?.use { if (it.moveToFirst()) return id }
        }
        val values = android.content.ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, account.name)
            put(Calendars.ACCOUNT_TYPE, account.type)
            put(Calendars.NAME, sub.id)
            put(Calendars.CALENDAR_DISPLAY_NAME, sub.displayName)
            put(Calendars.CALENDAR_COLOR, sub.color)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ) // mirrors a feed: read-only
            put(Calendars.OWNER_ACCOUNT, account.name)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
            put(Calendars.CAL_SYNC1, sub.url)
        }
        val uri = cr.insert(asSyncAdapter(Calendars.CONTENT_URI), values) ?: throw IllegalStateException("Couldn't create calendar")
        val id = ContentUris.parseId(uri)
        // Existing events of an old calendar id are gone; forget the old content hash.
        Subscriptions(context).update(sub.id) { it.copy(calendarId = id, contentHash = null) }
        return id
    }

    private class Existing(val id: Long, val key: String?, val hash: String?)

    /** Diff by row key: delete gone/duplicate rows, insert new ones, rewrite changed ones. */
    private fun apply(context: Context, calendarId: Long, rows: List<EventRow>) {
        val cr = context.contentResolver
        val existing = ArrayList<Existing>()
        cr.query(asSyncAdapter(Events.CONTENT_URI), arrayOf(Events._ID, Events.SYNC_DATA2, Events.SYNC_DATA1),
            "${Events.CALENDAR_ID}=?", arrayOf(calendarId.toString()), null,
        )?.use { c -> while (c.moveToNext()) existing += Existing(c.getLong(0), c.getString(1), c.getString(2)) }

        val wanted = rows.associateBy { it.key }
        val byKey = HashMap<String, Existing>()
        val ops = ArrayList<ContentProviderOperation>()
        val eventsUri = asSyncAdapter(Events.CONTENT_URI)
        for (e in existing) {
            val key = e.key
            if (key == null || key !in wanted || byKey.containsKey(key)) {
                ops += ContentProviderOperation.newDelete(ContentUris.withAppendedId(eventsUri, e.id)).build()
            } else {
                byKey[key] = e
            }
        }
        flush(context, ops)

        // Series first, then changed occurrences (which link to their series by UID).
        val ordered = rows.sortedBy { if (it.values[Events.ORIGINAL_SYNC_ID] != null) 1 else 0 }
        for (row in ordered) {
            val old = byKey[row.key]
            if (old != null && old.hash == row.hash) continue
            if (old == null) {
                val index = ops.size
                ops += ContentProviderOperation.newInsert(eventsUri)
                    .withValues(values(row.values).apply { put(Events.CALENDAR_ID, calendarId) }).build()
                children(row, ops) { it.withValueBackReference(Attendees.EVENT_ID, index) }
            } else {
                ops += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(eventsUri, old.id))
                    .withValues(values(row.values)).build()
                ops += ContentProviderOperation.newDelete(asSyncAdapter(Attendees.CONTENT_URI))
                    .withSelection("${Attendees.EVENT_ID}=?", arrayOf(old.id.toString())).build()
                ops += ContentProviderOperation.newDelete(asSyncAdapter(Reminders.CONTENT_URI))
                    .withSelection("${Reminders.EVENT_ID}=?", arrayOf(old.id.toString())).build()
                children(row, ops) { it.withValue(Attendees.EVENT_ID, old.id) }
            }
            // Flush between events only, so back-references stay inside one batch.
            if (ops.size >= BATCH_OPS) flush(context, ops)
        }
        flush(context, ops)
    }

    private fun children(
        row: EventRow,
        ops: MutableList<ContentProviderOperation>,
        link: (ContentProviderOperation.Builder) -> ContentProviderOperation.Builder,
    ) {
        for (a in row.attendees) {
            ops += link(ContentProviderOperation.newInsert(asSyncAdapter(Attendees.CONTENT_URI)).withValues(values(a))).build()
        }
        for (m in row.reminders) {
            ops += link(ContentProviderOperation.newInsert(asSyncAdapter(Reminders.CONTENT_URI))
                .withValue(Reminders.MINUTES, m).withValue(Reminders.METHOD, Reminders.METHOD_ALERT)).build()
        }
    }

    private fun flush(context: Context, ops: ArrayList<ContentProviderOperation>) {
        if (ops.isEmpty()) return
        context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
        ops.clear()
    }

    private fun values(map: Map<String, Any?>) = android.content.ContentValues().apply {
        for ((k, v) in map) when (v) {
            null -> putNull(k)
            is Int -> put(k, v)
            is Long -> put(k, v)
            is String -> put(k, v)
            is Boolean -> put(k, if (v) 1 else 0)
            else -> put(k, v.toString())
        }
    }
}
