package dev.minimal.cal.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A calendar link Mini Cal keeps in sync. */
data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    /** User-chosen name; null = use the feed's own name. */
    val customName: String? = null,
    val feedName: String? = null,
    val color: Int,
    /** Row id of our calendar in the provider, once created. */
    val calendarId: Long? = null,
    val lastSync: Long = 0,
    val lastError: String? = null,
    /** SHA-256 of the last feed body applied (servers here send no ETag/Last-Modified). */
    val contentHash: String? = null,
    val eventCount: Int = 0,
) {
    val displayName: String get() = customName ?: feedName ?: hostOf(url)

    companion object {
        fun hostOf(url: String): String = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: "Calendar"
    }
}

/** Small JSON-in-prefs store; a handful of subscriptions at most. Thread-safe via synchronized. */
class Subscriptions(context: Context) {
    private val sp = context.getSharedPreferences("subscriptions", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<Subscription> = runCatching {
        val arr = JSONArray(sp.getString("list", "[]"))
        (0 until arr.length()).map { decode(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun find(id: String): Subscription? = all().firstOrNull { it.id == id }

    @Synchronized
    fun save(sub: Subscription) {
        val list = all().filterNot { it.id == sub.id } + sub
        write(list)
    }

    /** Applies [change] to the latest stored copy (avoids lost updates between UI and sync). */
    @Synchronized
    fun update(id: String, change: (Subscription) -> Subscription): Subscription? {
        val current = find(id) ?: return null
        val updated = change(current)
        write(all().map { if (it.id == id) updated else it })
        return updated
    }

    @Synchronized
    fun remove(id: String) = write(all().filterNot { it.id == id })

    private fun write(list: List<Subscription>) {
        val arr = JSONArray()
        list.forEach { arr.put(encode(it)) }
        sp.edit().putString("list", arr.toString()).apply()
    }

    private fun encode(s: Subscription) = JSONObject().apply {
        put("id", s.id); put("url", s.url); put("color", s.color)
        s.customName?.let { put("customName", it) }
        s.feedName?.let { put("feedName", it) }
        s.calendarId?.let { put("calendarId", it) }
        put("lastSync", s.lastSync)
        s.lastError?.let { put("lastError", it) }
        s.contentHash?.let { put("contentHash", it) }
        put("eventCount", s.eventCount)
    }

    private fun decode(o: JSONObject) = Subscription(
        id = o.getString("id"),
        url = o.getString("url"),
        customName = o.optString("customName").takeIf { o.has("customName") },
        feedName = o.optString("feedName").takeIf { o.has("feedName") },
        color = o.getInt("color"),
        calendarId = if (o.has("calendarId")) o.getLong("calendarId") else null,
        lastSync = o.optLong("lastSync"),
        lastError = o.optString("lastError").takeIf { o.has("lastError") },
        contentHash = o.optString("contentHash").takeIf { o.has("contentHash") },
        eventCount = o.optInt("eventCount"),
    )
}
