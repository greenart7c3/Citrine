package com.greenart7c3.citrine.logs

import android.content.Context
import com.greenart7c3.citrine.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drop-in replacement for [android.util.Log] that persists every log entry to a local Room
 * database (so logs survive even on release builds where logcat output is disabled) and only
 * forwards to the real logcat when running a debug build.
 *
 * Persisted logs are trimmed to the last week by [com.greenart7c3.citrine.service.WebSocketServerService].
 *
 * Logging never blocks the caller: entries go through a bounded buffer that drops the oldest
 * entries when the writer falls behind. Sequential non-error lines (a startup or an event burst)
 * are coalesced for a short window into a single SQLite write transaction instead of one disk
 * write per call, error lines flush immediately so fatal context reaches disk right away, and an
 * idle writer parks on the buffer with zero wakeups. A failing write is dropped silently.
 */
object Log {
    /** Mirrors [android.util.Log.DEBUG], for call sites that pass a level (e.g. [isLoggable]). */
    const val DEBUG = 3

    /** Mirrors [android.util.Log.ERROR], for call sites that pass a level (e.g. [isLoggable]). */
    const val ERROR = 6

    private const val LEVEL_VERBOSE = "V"
    private const val LEVEL_DEBUG = "D"
    private const val LEVEL_INFO = "I"
    private const val LEVEL_WARN = "W"
    private const val LEVEL_ERROR = "E"

    private const val BATCH_SIZE = 200
    private const val COALESCE_WINDOW_MS = 2_000L
    private const val PENDING_ENTRIES_CAPACITY = 2000

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val pendingEntries = Channel<LogEntity>(
        capacity = PENDING_ENTRIES_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile
    private var dao: LogDao? = null

    @Volatile
    private var started = false

    /** Wires up persistence. Safe to call multiple times; only the first call has any effect. */
    @Synchronized
    fun init(context: Context) {
        if (started) return
        started = true
        val logDao = LogDatabase.getDatabase(context).logDao()
        dao = logDao
        scope.launch { consume(logDao) }
    }

    private suspend fun consume(logDao: LogDao) {
        val buffer = ArrayList<LogEntity>(BATCH_SIZE)
        for (first in pendingEntries) {
            buffer.add(first)
            if (first.level != LEVEL_ERROR) {
                delayToCoalesceWithoutCancellingReceive()
            }
            while (true) {
                while (buffer.size < BATCH_SIZE) {
                    val next = pendingEntries.tryReceive().getOrNull() ?: break
                    buffer.add(next)
                }
                try {
                    logDao.insertAll(buffer)
                } catch (_: Exception) {
                }
                buffer.clear()
                val more = pendingEntries.tryReceive().getOrNull() ?: break
                buffer.add(more)
            }
        }
    }

    private suspend fun delayToCoalesceWithoutCancellingReceive() = delay(COALESCE_WINDOW_MS)

    private fun persist(level: String, tag: String, msg: String, tr: Throwable?) {
        val message = if (tr != null) {
            msg + "\n" + android.util.Log.getStackTraceString(tr)
        } else {
            msg
        }
        pendingEntries.trySend(
            LogEntity(
                timestamp = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = message,
            ),
        )
    }

    /**
     * Mirrors [android.util.Log.isLoggable]. Since logcat output only happens on debug builds,
     * a level is only "loggable" to logcat on a debug build.
     */
    @Suppress("UnusedParameter")
    fun isLoggable(tag: String, level: Int): Boolean = BuildConfig.DEBUG

    fun v(tag: String, msg: String, tr: Throwable? = null): Int {
        persist(LEVEL_VERBOSE, tag, msg, tr)
        return if (BuildConfig.DEBUG) android.util.Log.v(tag, msg, tr) else 0
    }

    fun d(tag: String, msg: String, tr: Throwable? = null): Int {
        persist(LEVEL_DEBUG, tag, msg, tr)
        return if (BuildConfig.DEBUG) android.util.Log.d(tag, msg, tr) else 0
    }

    fun i(tag: String, msg: String, tr: Throwable? = null): Int {
        persist(LEVEL_INFO, tag, msg, tr)
        return if (BuildConfig.DEBUG) android.util.Log.i(tag, msg, tr) else 0
    }

    fun w(tag: String, msg: String, tr: Throwable? = null): Int {
        persist(LEVEL_WARN, tag, msg, tr)
        return if (BuildConfig.DEBUG) android.util.Log.w(tag, msg, tr) else 0
    }

    fun e(tag: String, msg: String, tr: Throwable? = null): Int {
        persist(LEVEL_ERROR, tag, msg, tr)
        return if (BuildConfig.DEBUG) android.util.Log.e(tag, msg, tr) else 0
    }
}
