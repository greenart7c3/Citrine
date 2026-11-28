package com.greenart7c3.citrine.service

import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.Citrine.Companion.TAG
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.OlderThan
import com.greenart7c3.citrine.server.Settings
import com.vitorpamplona.quartz.utils.TimeUtils
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.measureTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Deletes ephemeral events after their minute, expired events, and events past the retention setting. */
class EventCleanup(private val appScope: CoroutineScope) {
    private val ephemeralCleanupScheduled = AtomicBoolean(false)

    private val ephemeralInsertGeneration = AtomicLong(1L)

    @Volatile
    private var mayHaveEphemeralEvents = true

    fun scheduleEphemeralCleanup(database: AppDatabase) {
        ephemeralInsertGeneration.incrementAndGet()
        mayHaveEphemeralEvents = true
        ensureEphemeralCleanupRunning(database)
    }

    private fun noEphemeralInsertSince(generation: Long): Boolean = ephemeralInsertGeneration.get() == generation

    private fun ensureEphemeralCleanupRunning(database: AppDatabase) {
        if (!mayHaveEphemeralEvents || !ephemeralCleanupScheduled.compareAndSet(false, true)) return
        appScope.launch(Dispatchers.IO) {
            try {
                while (isActive && mayHaveEphemeralEvents) {
                    delay(SWEEP_DELAY_JUST_PAST_EPHEMERAL_TTL_MS)
                    if (Citrine.isImportingEvents) continue
                    val generationBeforeSweep = ephemeralInsertGeneration.get()
                    database.eventDao().deleteEphemeralEvents(TimeUtils.oneMinuteAgo())
                    if (!database.eventDao().hasEphemeralEvents()) {
                        if (noEphemeralInsertSince(generationBeforeSweep)) {
                            mayHaveEphemeralEvents = false
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error deleting ephemeral events", e)
            } finally {
                ephemeralCleanupScheduled.set(false)
            }
            rearmIfStoredWhileReleasingSchedule(database)
        }
    }

    private fun rearmIfStoredWhileReleasingSchedule(database: AppDatabase) {
        if (mayHaveEphemeralEvents) {
            ensureEphemeralCleanupRunning(database)
        }
    }

    suspend fun eventsToDelete(database: AppDatabase, runAgeRetention: Boolean = true) {
        if (Citrine.isImportingEvents) return
        val verbose = Log.isLoggable(TAG, Log.DEBUG)
        if (verbose) Log.d(TAG, "entered eventsToDelete")
        Citrine.job?.join()
        Citrine.cancelJob()
        Citrine.job = appScope.launch(Dispatchers.IO) {
            try {
                if (isActive && mayHaveEphemeralEvents) deleteEphemeral(database, verbose)
                if (isActive) deleteExpired(database, verbose)
                if (runAgeRetention && Settings.deleteEventsOlderThan != OlderThan.NEVER && isActive) deleteOld(database, verbose)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error deleting events", e)
            }
        }
    }

    private suspend fun deleteEphemeral(database: AppDatabase, verbose: Boolean) {
        val duration = measureTime {
            if (verbose) Log.d(TAG, "Deleting ephemeral events older than one minute ago")
            val generationBeforeSweep = ephemeralInsertGeneration.get()
            database.eventDao().deleteEphemeralEvents(TimeUtils.oneMinuteAgo())
            if (!database.eventDao().hasEphemeralEvents() && noEphemeralInsertSince(generationBeforeSweep)) {
                mayHaveEphemeralEvents = false
            }
        }
        if (verbose) Log.d(TAG, "Deleted ephemeral events in $duration")
    }

    private suspend fun deleteExpired(database: AppDatabase, verbose: Boolean) {
        val duration = measureTime {
            if (verbose) Log.d(TAG, "Deleting expired events")
            database.eventDao().deleteEventsWithExpirations(TimeUtils.now())
        }
        if (verbose) Log.d(TAG, "Deleted expired events in $duration")
    }

    private suspend fun deleteOld(database: AppDatabase, verbose: Boolean) {
        val until = when (Settings.deleteEventsOlderThan) {
            OlderThan.DAY -> TimeUtils.oneDayAgo()
            OlderThan.WEEK -> TimeUtils.oneWeekAgo()
            OlderThan.MONTH -> TimeUtils.now() - TimeUtils.ONE_MONTH
            OlderThan.YEAR -> TimeUtils.now() - TimeUtils.ONE_YEAR
            else -> 0
        }
        if (until <= 0) return
        val duration = measureTime {
            if (verbose) Log.d(TAG, "Deleting old events (older than ${Settings.deleteEventsOlderThan})")
            val neverFrom = Settings.neverDeleteFrom
            val preserved = Settings.preservedKindsFromDeletion
            when {
                neverFrom.isEmpty() && preserved.isEmpty() ->
                    database.eventDao().deleteAll(until)
                neverFrom.isEmpty() ->
                    database.eventDao().deleteAll(until, preserved.toIntArray())
                preserved.isEmpty() ->
                    database.eventDao().deleteAll(until, neverFrom.toTypedArray())
                else ->
                    database.eventDao().deleteAll(until, neverFrom.toTypedArray(), preserved.toIntArray())
            }
        }
        if (verbose) Log.d(TAG, "Deleted old events in $duration")
    }

    private companion object {
        const val SWEEP_DELAY_JUST_PAST_EPHEMERAL_TTL_MS = 75_000L
    }
}
