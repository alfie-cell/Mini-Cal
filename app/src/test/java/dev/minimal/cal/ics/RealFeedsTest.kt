package dev.minimal.cal.ics

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.ZoneOffset

/**
 * Optional check against real feeds on the developer's machine; skipped unless ICS_FEEDS_DIR
 * points at a folder of .ics files. Prints only counts/shapes, never event contents.
 */
class RealFeedsTest {
    @Test fun parsesEveryEventInRealFeeds() {
        val dir = System.getenv("ICS_FEEDS_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val files = dir!!.listFiles { f -> f.name.endsWith(".ics") }.orEmpty()
        assumeTrue(files.isNotEmpty())
        for (f in files.sortedBy { it.name }) {
            val bytes = f.readBytes()
            val vevents = Regex("BEGIN:VEVENT").findAll(String(bytes)).count()
            val cal = Ics.parse(bytes)
            val rows = EventRows.build(cal)
            val zones = cal.events.flatMap { listOf(it.start, it.end) }.filterIsInstance<IcsTime.Timed>().map { it.zone }
            val offsetFallbacks = zones.count { it is ZoneOffset && it != ZoneOffset.UTC }
            println(
                "${f.name}: VEVENTs=$vevents parsed=${cal.events.size} skipped=${cal.skipped} rows=${rows.size} " +
                    "series=${rows.count { it.values["rrule"] != null }} exceptions=${rows.count { it.values["original_sync_id"] != null }} " +
                    "allDay=${rows.count { it.values["allDay"] == 1 }} cancelled=${rows.count { it.values["eventStatus"] == 2 }} " +
                    "zones=${zones.map { it.id }.distinct()} fixedOffsetFallbacks=$offsetFallbacks refresh=${cal.refreshInterval}"
            )
            check(cal.skipped == 0) { "${f.name}: ${cal.skipped} events could not be read" }
            check(cal.events.size == vevents) { "${f.name}: parsed ${cal.events.size} of $vevents" }
            check(offsetFallbacks == 0) { "${f.name}: some TZIDs fell back to fixed offsets" }
            check(rows.map { it.key }.distinct().size == rows.size) { "${f.name}: duplicate row keys" }
        }
    }
}
