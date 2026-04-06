package com.nybroadband.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.nybroadband.mobile.service.ActiveTestService
import com.nybroadband.mobile.service.PassiveCollectionService
import com.nybroadband.mobile.service.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class NYBroadbandApp : Application(), Configuration.Provider {

    // Hilt-aware WorkerFactory — required so WorkManager can inject into workers
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        initLogging()
        createNotificationChannels()
        schedulePeriodicalSync()
    }

    /**
     * Schedule a periodic background sync every 15 minutes (WorkManager minimum).
     * Uses KEEP policy so re-launches don't reset the timer.
     * Requires any network connection — WorkManager defers execution until connected.
     */
    private fun schedulePeriodicalSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(SyncWorker.TAG)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    // Provide Hilt-aware WorkManager configuration.
    // Pair with manifest removal of WorkManagerInitializer (see manifest comment).
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * Notification channels must be registered before any service calls startForeground().
     * Min SDK is 26 (Oreo) so NotificationChannel is always available — no API guard needed.
     */
    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // Passive collection — low importance so it never pops as a heads-up
        nm.createNotificationChannel(
            NotificationChannel(
                PassiveCollectionService.NOTIFICATION_CHANNEL_ID,
                getString(R.string.notif_channel_passive_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_passive_desc)
                setShowBadge(false)
            }
        )

        // Active test — default importance so test-complete notification is visible
        nm.createNotificationChannel(
            NotificationChannel(
                ActiveTestService.NOTIFICATION_CHANNEL_ID,
                getString(R.string.notif_channel_active_test_title),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notif_channel_active_test_desc)
                setShowBadge(false)
            }
        )
    }

    private fun initLogging() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(CrashReportingTree())
        }
    }

    /**
     * Release logging tree. Sends warnings and errors to Crashlytics.
     * GPS coordinates are never logged — strips any decimal-degree patterns (LD-08).
     */
    private inner class CrashReportingTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority < Log.WARN) return
            val safe = message.replace(Regex("""-?\d+\.\d{4,}"""), "[LOCATION_REDACTED]")
            FirebaseCrashlytics.getInstance().log(safe)
            t?.let { FirebaseCrashlytics.getInstance().recordException(it) }
        }
    }
}
