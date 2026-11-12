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
import android.os.Build
import android.os.Debug
import com.greenart7c3.citrine.BuildConfig

/**
 * Text a user can paste into a report: device, build, failing thread, stack trace and heap
 * state. Built while the process may already be out of memory, so it stays allocation-light —
 * numbers and short strings only, no file listings or event counts.
 */
class ReportAssembler(
    private val context: Context? = null,
) {
    fun buildReport(e: Throwable): String = buildString {
        append(e.javaClass.simpleName)
        append(": ")
        appendLine(BuildConfig.VERSION_NAME)
        appendLine()

        // Device and Product Information
        appendLine("| Prop | Value |")
        appendLine("|------|-------|")
        append("| Manuf |")
        append(Build.MANUFACTURER)
        appendLine(" |")
        append("| Model |")
        append(Build.MODEL)
        appendLine(" |")
        append("| Prod |")
        append(Build.PRODUCT)
        appendLine(" |")

        // OS Information
        append("| Android |")
        append(Build.VERSION.RELEASE)
        appendLine(" |")
        append("| SDK Int |")
        append(Build.VERSION.SDK_INT.toString())
        appendLine(" |")

        // Hardware Information
        append("| Brand |")
        append(Build.BRAND)
        appendLine(" |")
        append("| Hardware |")
        append(Build.HARDWARE)
        appendLine(" |")

        // Other Useful Information
        append("| Device | ")
        append(Build.DEVICE)
        appendLine(" |")
        append("| Host | ")
        append(Build.HOST)
        appendLine(" |")
        append("| User | ")
        append(Build.USER)
        appendLine(" |")
        appendLine()

        appendLine("```")
        appendLine(e.toString())
        e.stackTrace.forEach {
            append("    ")
            appendLine(it.toString())
        }
        val cause = e.cause
        if (cause != null) {
            appendLine("\n\nCause:")
            append("    ")
            appendLine(cause.toString())
            cause.stackTrace.forEach {
                append("        ")
                appendLine(it.toString())
            }
        }
        appendLine("```")

        appendMemory()
    }

    /**
     * Thread and heap snapshot. For an `OutOfMemoryError` these are the numbers that say which
     * kind of memory ran out (Java heap vs native) and how large the databases were.
     */
    private fun StringBuilder.appendMemory() {
        val thread = Thread.currentThread()
        appendLine()
        appendLine("| Memory | Value |")
        appendLine("|--------|-------|")
        append("| Thread | ")
        append(thread.name)
        appendLine(" |")

        val runtime = Runtime.getRuntime()
        append("| Java heap | max=")
        append(mb(runtime.maxMemory()))
        append("MB total=")
        append(mb(runtime.totalMemory()))
        append("MB free=")
        append(mb(runtime.freeMemory()))
        appendLine("MB |")

        append("| Native heap | allocated=")
        append(mb(Debug.getNativeHeapAllocatedSize()))
        appendLine("MB |")

        val appContext = context ?: return
        listOf("citrine_database", "citrine_history_database").forEach { name ->
            val file = appContext.getDatabasePath(name)
            append("| DB ")
            append(name)
            append(" | ")
            append(mb(file.length()))
            append("MB, wal ")
            append(mb(appContext.getDatabasePath("$name-wal").length()))
            appendLine("MB |")
        }
    }

    private fun mb(bytes: Long): Long = bytes / (1024 * 1024)
}
