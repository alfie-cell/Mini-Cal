package dev.minimal.cal

import android.app.Application
import android.content.Context
import android.os.StrictMode
import dev.minimal.cal.reminders.ReminderScheduler
import dev.minimal.cal.widget.WidgetUpdater

class CalApp : Application() {
    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectActivityLeaks().detectLeakedClosableObjects().penaltyLog().build()
            )
        }
        prefs = Prefs(this)
        // Process (re)start, e.g. after boot on ROMs that delay BOOT_COMPLETED: re-arm everything.
        if (prefs.remindersEnabled) ReminderScheduler.request(this)
        WidgetUpdater.request(this)
        dev.minimal.cal.sync.SyncJob.schedule(this)
    }
}

val Context.calApp: CalApp
    get() = applicationContext as CalApp
