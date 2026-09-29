package dev.minimal.cal.sync

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.AbstractThreadedSyncAdapter
import android.content.ComponentName
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder

/** Owner of the subscription calendars. Accounts are only created by the app itself. */
class AuthenticatorService : Service() {
    private val authenticator by lazy {
        object : AbstractAccountAuthenticator(this) {
            override fun editProperties(r: AccountAuthenticatorResponse?, t: String?) = null
            override fun addAccount(r: AccountAuthenticatorResponse?, t: String?, a: String?, f: Array<out String>?, o: Bundle?) = null
            override fun confirmCredentials(r: AccountAuthenticatorResponse?, a: Account?, o: Bundle?) = null
            override fun getAuthToken(r: AccountAuthenticatorResponse?, a: Account?, t: String?, o: Bundle?) = null
            override fun getAuthTokenLabel(t: String?) = null
            override fun updateCredentials(r: AccountAuthenticatorResponse?, a: Account?, t: String?, o: Bundle?) = null
            override fun hasFeatures(r: AccountAuthenticatorResponse?, a: Account?, f: Array<out String>?) = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = authenticator.iBinder
}

/** Lets the system's "sync now" for the account (and calendar apps) trigger a refresh. */
class CalendarSyncService : Service() {
    private val adapter by lazy {
        object : AbstractThreadedSyncAdapter(applicationContext, true) {
            override fun onPerformSync(a: Account, e: Bundle, authority: String, p: ContentProviderClient, r: SyncResult) =
                SubscriptionSync.syncAll(context, force = e.getBoolean("force", false))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = adapter.syncAdapterBinder
}

/** Periodic refresh of all subscriptions, only when online. */
class SyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                SubscriptionSync.syncAll(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 4401
        private const val PERIOD_MS = 60L * 60 * 1000

        /** Hourly while there are subscriptions; cancelled when the last one is removed. */
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java)
            if (Subscriptions(context).all().isEmpty()) {
                js.cancel(JOB_ID)
                return
            }
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, SyncJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(PERIOD_MS, 15L * 60 * 1000)
                    .setPersisted(true)
                    .build()
            )
        }
    }
}
