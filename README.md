# Mini Cal

A small, fast calendar companion for Android 13+ (API 33–36). Release APK about 460 KB.

It shows events from **every calendar on the phone** (Google, Exchange, local), **subscribes to shared calendar links** itself (`.ics` / `webcal`, e.g. from Proton or Outlook), and has reminders and a home-screen widget that works in any launcher, including Xiaomi's.

- **Agenda:** the next 90 days grouped by day. Repeating events are marked ↻, and duplicates (the same event in two calendars, or imported twice) are merged.
- **Event view:**
  - time range, repeat rule in plain words ("Every 2 weeks on Mon, Wed until 12 Dec"), the next dates in the series, and a note when an occurrence was moved
  - location (opens Maps), and a **Join** button for Teams, Zoom and Meet links
  - cleaned-up description and attendees
  - Mini Cal handles standard calendar-event links, so other apps can open events in it
- **Reminders:**
  - any number of default reminders per calendar, including all-day events at a time you choose, and a sound per calendar
  - per-event or per-series overrides from the event view
  - an event's own reminders win over calendar defaults; overrides win over both
  - exact alarms, with Snooze and Dismiss on the notification
- **Calendar links:** Settings → *+ Add calendar link*, or tap any `webcal://` link. Each link becomes a read-only calendar that syncs hourly (and on demand). Syncs are diff-based: events are matched by UID and occurrence, and only changes are written, so event IDs stay stable. The built-in parser handles:
  - folded lines and escaped text
  - quoted parameters
  - all-day, UTC, floating and timezone times, including **Windows timezone names** from Outlook/Exchange
  - repeat rules with exclusions and extra dates, moved and cancelled occurrences
  - attendees and organisers, busy/free status, built-in reminders, and Proton Meet links

  Removing a link (or uninstalling) deletes its events.
- **Widget ("Upcoming events"):** today's date and your next events. It's resizable, updates within seconds of calendar changes, and switches to "Now" when an event starts.

## Setup on Xiaomi / MIUI

MIUI stops background apps aggressively. In Mini Cal → Settings:
- **Allow running in background:** turn on Autostart.
- **Battery: no restrictions.**

Without these, reminders and widget updates can be late.

To add the widget, long-press the home screen → Widgets → Mini Cal. You can also use **Add widget to home screen** in Settings, but on Xiaomi that needs the app's **Home screen shortcuts** permission first (App info → Other permissions). MIUI silently ignores the request without it.

To avoid getting reminders twice, turn off the phone calendar app's notifications (a shortcut is in Settings).

## Build

```sh
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # 66 JVM unit tests (set ICS_FEEDS_DIR to also check local .ics files)
```

Requires JDK 17+ (Android Studio's bundled JDK works). Release builds are signed with the debug key for sideloading.

## Layout

```
app/src/main/java/dev/minimal/cal/
  calendar/   agenda, event view, settings, CalendarStore (read-only calendar access),
              RepeatText (RRULE → words), EventText (meeting links, description cleanup)
  reminders/  ReminderPlanner (pure rules, unit tested), ReminderScheduler (exact alarm,
              notifications), receivers, ReminderPicker
  widget/     UpcomingWidget + WidgetUpdater (RemoteViews collection, content-change job)
  ics/        Ics (RFC 5545 reader), EventRows (events -> provider rows + diff hash), WindowsZones
  sync/       SubscriptionSync (download, diff, batched writes), account/sync services, SyncJob
  agenda/     AgendaFormat (labels, ordering, de-duplication)
```

App icon: Material Icons "event" (round), Apache License 2.0. Windows→IANA timezone table from Unicode CLDR (Unicode License v3).
