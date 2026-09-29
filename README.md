# Mini Cal

A small, fast calendar companion for Android 13+ (API 33–36). Release APK about 390 KB.

It shows events from **every calendar on the phone** (Google, Exchange, local, and `.ics` subscriptions synced by apps like ICSx⁵), with reminders and a home-screen widget that works in any launcher, including Xiaomi's.

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
- **Widget ("Upcoming events"):** today's date and your next events. It's resizable, updates within seconds of calendar changes, and switches to "Now" when an event starts.

## Setup on Xiaomi / MIUI

MIUI stops background apps aggressively. In Mini Cal → Settings:
- **Allow running in background:** turn on Autostart.
- **Battery: no restrictions.**

Without these, reminders and widget updates can be late. To add the widget, long-press the home screen → Widgets → Mini Cal, or use **Add widget to home screen** in Settings.

To avoid getting reminders twice, turn off the phone calendar app's notifications (a shortcut is in Settings).

## Build

```sh
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # 44 JVM unit tests
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
  agenda/     AgendaFormat (labels, ordering, de-duplication)
```

App icon: Material Icons "event" (round), Apache License 2.0.
