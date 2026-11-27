/**
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.greenart7c3.citrine.service.crashreports

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val STACK_TRACE_FILENAME = "stack.trace"

class CrashReportCache(
    val appContext: Context,
    currentUpdateTimeProvider: () -> Long = { packageLastUpdateTime(appContext) },
) {
    private val lock = Any()

    // Cached once at construction (Citrine.onCreate) so emergency writes from
    // UnexpectedCrashSaver during an OOM never make a PackageManager binder call.
    private val currentUpdateTime: Long = currentUpdateTimeProvider()

    private fun outputStream() = appContext.openFileOutput(STACK_TRACE_FILENAME, Context.MODE_PRIVATE)

    private fun deleteReport() = appContext.deleteFile(STACK_TRACE_FILENAME)

    private fun inputStreamOrNull() = try {
        appContext.openFileInput(STACK_TRACE_FILENAME)
    } catch (_: FileNotFoundException) {
        null
    }

    /**
     * Reads the cached report text only if it belongs to the currently installed APK. Checks the
     * first line before reading the body so an oversized or legacy file from a previous version is
     * discarded without loading it into the heap.
     */
    private fun readCurrentReportOrNull(): String? = inputStreamOrNull()?.use { inStream ->
        BufferedReader(InputStreamReader(inStream, Charsets.UTF_8)).use { reader ->
            val firstLine = reader.readLine() ?: return@use null
            if (firstLine.startsWith(UPDATE_TIME_PREFIX)) {
                val reportUpdateTime = firstLine.substring(UPDATE_TIME_PREFIX.length).toLongOrNull() ?: 0L
                if (!isReportCurrent(reportUpdateTime, currentUpdateTime)) {
                    return@use null
                }
                readBounded(reader, MAX_READ_CHARS).takeIf { it.isNotBlank() }
            } else {
                // Legacy file written before update-time stamping: treat as belonging to an older
                // install whenever we know the current APK's lastUpdateTime.
                if (!isReportCurrent(0L, currentUpdateTime)) {
                    return@use null
                }
                val rest = readBounded(reader, (MAX_READ_CHARS - firstLine.length - 1).coerceAtLeast(0))
                val full = if (rest.isEmpty()) firstLine else "$firstLine\n$rest"
                full.takeIf { it.isNotBlank() }
            }
        }
    }

    private fun writeEncoded(body: String) {
        outputStream().use { out ->
            out.write(encode(body, currentUpdateTime).toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Appends [report] to the cached text instead of replacing it. Used when the process
     * survives the error (a request that ran out of memory, for example) — the report of a
     * fatal crash from the same session must not be lost because a previous one was cached
     * later. Drops any stale pre-upgrade cache and caps total length to [MAX_CACHE_CHARS]
     * (retaining the newest reports) so the file stays bounded and fits in a NIP-17 DM.
     */
    fun appendReport(report: String) {
        synchronized(lock) {
            val existing = readCurrentReportOrNull()
            val combined = combineAndCap(existing, report)
            if (combined.isBlank()) return
            writeEncoded(combined)
        }
    }

    suspend fun loadAndDelete(): String? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val stack = readCurrentReportOrNull()
            deleteReport()
            stack
        }
    }

    companion object {
        private const val UPDATE_TIME_PREFIX = "#updateTime="
        internal const val REPORT_SEPARATOR = "\n\n---\n\n"

        // Keeps stack.trace bounded in memory/disk and comfortably under NIP-44's 65,535-byte
        // double-wrapped NIP-17 plaintext limit (with headroom for ExitReasons text).
        internal const val MAX_CACHE_CHARS = 32_000
        private const val MAX_READ_CHARS = MAX_CACHE_CHARS * 2

        internal fun isReportCurrent(reportUpdateTime: Long, currentUpdateTime: Long): Boolean = currentUpdateTime == 0L || reportUpdateTime == currentUpdateTime

        internal fun encode(report: String, updateTime: Long): String = "$UPDATE_TIME_PREFIX$updateTime\n$report"

        internal fun combineAndCap(
            existing: String?,
            incoming: String,
            maxChars: Int = MAX_CACHE_CHARS,
        ): String {
            if (incoming.isBlank()) return existing?.takeIf { it.isNotBlank() }.orEmpty()
            if (incoming.length >= maxChars) return incoming.take(maxChars)
            if (existing.isNullOrBlank()) return incoming

            val directLen = existing.length + REPORT_SEPARATOR.length + incoming.length
            if (directLen <= maxChars) return "$existing$REPORT_SEPARATOR$incoming"

            // Keep the newest complete reports that fit within maxChars.
            val parts = existing.split(REPORT_SEPARATOR) + incoming
            val kept = ArrayDeque<String>()
            var total = 0
            for (i in parts.lastIndex downTo 0) {
                val part = parts[i]
                if (part.isBlank()) continue
                val added = if (kept.isEmpty()) part.length else part.length + REPORT_SEPARATOR.length
                if (total + added > maxChars) break
                kept.addFirst(part)
                total += added
            }
            return kept.joinToString(REPORT_SEPARATOR)
        }

        private fun readBounded(reader: BufferedReader, maxChars: Int): String {
            if (maxChars <= 0) return ""
            val sb = StringBuilder()
            val buf = CharArray(4096)
            while (sb.length < maxChars) {
                val toRead = minOf(buf.size, maxChars - sb.length)
                val n = reader.read(buf, 0, toRead)
                if (n <= 0) break
                sb.append(buf, 0, n)
            }
            return sb.toString()
        }

        private fun packageLastUpdateTime(context: Context): Long = try {
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
        } catch (_: Exception) {
            0L
        }
    }
}
