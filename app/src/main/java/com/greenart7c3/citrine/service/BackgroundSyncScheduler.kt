package com.greenart7c3.citrine.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.server.Settings
import java.util.concurrent.TimeUnit

object BackgroundSyncScheduler {
    private const val UNIQUE_WORK_NAME = "citrine-background-sync"

    fun reschedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        if (!Settings.backgroundSyncEnabled || Settings.backgroundSyncPubkey.isBlank()) {
            Log.d(Citrine.TAG, "BackgroundSyncScheduler: Cancelling background sync")
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }

        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(if (Settings.backgroundSyncWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

        val request =
            PeriodicWorkRequestBuilder<EventSyncWorker>(
                Settings.backgroundSyncIntervalHours.toLong().coerceIn(1, 24),
                TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()

        Log.d(
            Citrine.TAG,
            "BackgroundSyncScheduler: Scheduling sync every ${Settings.backgroundSyncIntervalHours}h, wifiOnly=${Settings.backgroundSyncWifiOnly}",
        )
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
