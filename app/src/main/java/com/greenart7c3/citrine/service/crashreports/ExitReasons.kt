package com.greenart7c3.citrine.service.crashreports

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.greenart7c3.citrine.BuildConfig
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why the previous process died, when it did not die of a Java exception.
 *
 * The in-app crash dialog is fed by [CrashReportCache], which only ever sees exceptions the
 * runtime handed to the uncaught-exception handler. ANRs, native crashes and initialization
 * failures never get there, so nothing was reportable for them. Android keeps those records in
 * [ActivityManager.getHistoricalProcessExitReasons]; this object turns the ones that matter into
 * plain text the user can send from the crash report screen.
 *
 * System low-memory kills (`REASON_LOW_MEMORY`) are logged locally for diagnostics but not
 * surfaced as crashes: Android's `lmkd` routinely kills foreground services under device-wide
 * memory pressure with no actionable trace, whereas Citrine's own heap exhaustion is already
 * captured with full diagnostics by [UnexpectedCrashSaver] and `SubscriptionManager`, and
 * ActivityManager kills for excessive resource usage use `REASON_EXCESSIVE_RESOURCE_USAGE`.
 *
 * Records are de-duplicated with a "last seen" timestamp in plain SharedPreferences (this runs
 * very early, before the encrypted settings store is guaranteed to be usable), and exits older
 * than the current APK's `lastUpdateTime` are skipped so upgrading does not report stale history
 * from an earlier version. Formatted abnormal records are kept in a second key so both the log
 * line written from `Citrine.onCreate` and the crash screen see the same text; pending records
 * written before an upgrade (or before their update time was tracked) are dropped for the same
 * reason. New abnormal records are appended to the pending ones from the same install, so a crash
 * on startup does not erase the ANR that happened before it. Each record names the version that
 * died, as stamped through [ActivityManager.setProcessStateSummary], or the installed one.
 */
object ExitReasons {
    private const val PREFS = "exit_reasons"
    private const val KEY_LAST_SEEN = "last_seen_timestamp"
    private const val KEY_PENDING = "pending_records"
    private const val KEY_PENDING_UPDATE_TIME = "pending_update_time"
    private const val MAX_RECORDS = 5
    private const val MAX_TRACE_CHARS = 2_000

    private val detectionLock = Any()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var stateSummarySet = false

    private val ABNORMAL_REASONS = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_SIGNALED,
    )

    /**
     * True when [reason] is a death the in-app log and dialog should surface. [importance] keeps
     * ordinary user actions (force stop, swipe away, package update) and background cache trims
     * out of the report: Android reports those with the same reason codes. Lower is more
     * important, so the process must have been at least as important as a foreground service.
     */
    fun isAbnormal(reason: Int, importance: Int): Boolean = importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE && reason in ABNORMAL_REASONS

    internal fun cutoffTimestamp(lastSeen: Long, lastUpdateTime: Long): Long = maxOf(lastSeen, lastUpdateTime)

    internal fun isPendingCurrent(pendingUpdateTime: Long, currentUpdateTime: Long): Boolean = currentUpdateTime == 0L || pendingUpdateTime == currentUpdateTime

    internal fun resolveVersionLabel(processStateSummary: ByteArray?, fallback: String = currentVersionLabel()): String = processStateSummary
        ?.takeIf { it.isNotEmpty() }
        ?.toString(Charsets.UTF_8)
        ?.takeIf { it.isNotBlank() }
        ?: fallback

    private fun currentVersionLabel(): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    /** Short label for [reason], matching the constant names in [ApplicationExitInfo]. */
    fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "REASON_$reason"
    }

    /**
     * Reads the exit records newer than the last one already stored (and newer than the current
     * APK install/update time), appends the abnormal ones to the pending report text, and returns
     * them. Safe to call repeatedly: the stored timestamp only advances past the records that were
     * read. Also stamps the current process with its version via
     * [ActivityManager.setProcessStateSummary] so future exit records carry the version that died.
     */
    fun detect(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return synchronized(detectionLock) {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
            if (!stateSummarySet) {
                try {
                    manager.setProcessStateSummary(currentVersionLabel().toByteArray(Charsets.UTF_8))
                    stateSummarySet = true
                } catch (e: Exception) {
                    Log.e("ExitReasons", "Could not set process state summary", e)
                }
            }

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val updateTime = lastUpdateTime(context)
            val pendingIsCurrent = isPendingCurrent(prefs.getLong(KEY_PENDING_UPDATE_TIME, 0L), updateTime)
            if (!pendingIsCurrent && (prefs.contains(KEY_PENDING) || prefs.contains(KEY_PENDING_UPDATE_TIME))) {
                prefs.edit {
                    remove(KEY_PENDING)
                    remove(KEY_PENDING_UPDATE_TIME)
                }
            }

            val cutoff = cutoffTimestamp(
                lastSeen = prefs.getLong(KEY_LAST_SEEN, 0L),
                lastUpdateTime = updateTime,
            )

            val records = try {
                manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
            } catch (e: Exception) {
                Log.e("ExitReasons", "Could not read process exit reasons", e)
                null
            }
            if (records.isNullOrEmpty()) return emptyList()

            val unseen = records.filter { it.timestamp > cutoff }
            unseen
                .filter {
                    it.reason == ApplicationExitInfo.REASON_LOW_MEMORY &&
                        it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
                }
                .forEach { Log.i("ExitReasons", describe(it)) }

            val fresh = unseen
                .filter { isAbnormal(it.reason, it.importance) }
                .map { describe(it) }

            fresh.forEach { Log.w(Citrine.TAG, it) }

            prefs.edit {
                putLong(KEY_LAST_SEEN, maxOf(cutoff, records.maxOf { it.timestamp }))
                if (fresh.isNotEmpty()) {
                    val earlierUnreadFromThisInstall = if (pendingIsCurrent) prefs.getString(KEY_PENDING, null) else null
                    val combined = listOfNotNull(earlierUnreadFromThisInstall?.takeIf { it.isNotBlank() }, fresh.joinToString("\n\n"))
                    putString(KEY_PENDING, combined.joinToString("\n\n"))
                    putLong(KEY_PENDING_UPDATE_TIME, updateTime)
                }
            }
            fresh
        }
    }

    /** Abnormal exit records from the current install waiting to be shown to the user. */
    fun pending(context: Context): String? = synchronized(detectionLock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_PENDING, null) ?: return null
        val updateTime = lastUpdateTime(context)
        if (!isPendingCurrent(prefs.getLong(KEY_PENDING_UPDATE_TIME, 0L), updateTime)) {
            prefs.edit {
                remove(KEY_PENDING)
                remove(KEY_PENDING_UPDATE_TIME)
            }
            return null
        }
        stored
    }

    /** Drops the pending records once the user has seen (or sent) the report. */
    fun clearPending(context: Context) {
        synchronized(detectionLock) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                remove(KEY_PENDING)
                remove(KEY_PENDING_UPDATE_TIME)
            }
        }
    }

    private fun lastUpdateTime(context: Context): Long = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.lastUpdateTime
    } catch (e: Exception) {
        Log.e("ExitReasons", "Could not read package update time", e)
        0L
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describe(info: ApplicationExitInfo): String = buildString {
        append("Citrine ")
        append(resolveVersionLabel(info.processStateSummary))
        append(" · ")
        append(Build.MANUFACTURER)
        append(" ")
        append(Build.MODEL)
        append(" · Android ")
        appendLine(Build.VERSION.RELEASE)
        append("Previous exit: ")
        append(reasonLabel(info.reason))
        append(" at ")
        append(timestampFormat.format(Date(info.timestamp)))
        append(" (importance=")
        append(info.importance)
        append(", pss=")
        append(info.pss)
        append("kB, rss=")
        append(info.rss)
        append("kB)")

        val description = info.description
        if (!description.isNullOrBlank()) {
            append("\n  ")
            append(description)
        }

        if (info.reason == ApplicationExitInfo.REASON_ANR) {
            anrTraceHead(info)?.let {
                append("\n  ANR trace (first lines):\n")
                append(it)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun anrTraceHead(info: ApplicationExitInfo): String? = try {
        info.traceInputStream?.use { stream ->
            InputStreamReader(stream).use { reader ->
                val buffer = CharArray(MAX_TRACE_CHARS)
                val read = reader.read(buffer)
                if (read <= 0) null else String(buffer, 0, read)
            }
        }
    } catch (e: Exception) {
        Log.e("ExitReasons", "Could not read ANR trace", e)
        null
    }
}
