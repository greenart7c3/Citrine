package com.greenart7c3.citrine.service.crashreports

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exit records are the only trace of ANRs and native crashes. The filter must include those while
 * keeping ordinary user actions, background kills and system low-memory kills out of the report.
 */
class ExitReasonsTest {
    private val foreground = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    private val foregroundService = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    private val cached = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED

    @Test
    fun abnormalReasonsAreLabelled() {
        assertEquals("ANR", ExitReasons.reasonLabel(ApplicationExitInfo.REASON_ANR))
        assertEquals("LOW_MEMORY", ExitReasons.reasonLabel(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("CRASH_NATIVE", ExitReasons.reasonLabel(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("CRASH", ExitReasons.reasonLabel(ApplicationExitInfo.REASON_CRASH))
        assertEquals("OTHER", ExitReasons.reasonLabel(ApplicationExitInfo.REASON_OTHER))
    }

    @Test
    fun abnormalReasonInAForegroundProcessIsReported() {
        assertTrue(ExitReasons.isAbnormal(ApplicationExitInfo.REASON_ANR, foregroundService))
        assertTrue(ExitReasons.isAbnormal(ApplicationExitInfo.REASON_CRASH, foregroundService))
        assertTrue(ExitReasons.isAbnormal(ApplicationExitInfo.REASON_CRASH_NATIVE, foregroundService))
        assertTrue(ExitReasons.isAbnormal(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, foregroundService))
        assertTrue(ExitReasons.isAbnormal(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, foregroundService))
        assertTrue(ExitReasons.isAbnormal(ApplicationExitInfo.REASON_SIGNALED, foregroundService))
    }

    @Test
    fun lowMemoryKillIsNotReported() {
        assertFalse(ExitReasons.isAbnormal(ApplicationExitInfo.REASON_LOW_MEMORY, foreground))
        assertFalse(ExitReasons.isAbnormal(ApplicationExitInfo.REASON_LOW_MEMORY, foregroundService))
        assertFalse(ExitReasons.isAbnormal(ApplicationExitInfo.REASON_LOW_MEMORY, cached))
    }

    @Test
    fun ordinaryExitIsNotReported() {
        assertFalse(ExitReasons.isAbnormal(ApplicationExitInfo.REASON_EXIT_SELF, foregroundService))
        assertFalse(ExitReasons.isAbnormal(ApplicationExitInfo.REASON_USER_REQUESTED, foregroundService))
        assertFalse(ExitReasons.isAbnormal(ApplicationExitInfo.REASON_OTHER, foregroundService))
    }

    @Test
    fun backgroundKillIsNotReported() {
        assertFalse(ExitReasons.isAbnormal(ApplicationExitInfo.REASON_ANR, cached))
        assertFalse(ExitReasons.isAbnormal(ApplicationExitInfo.REASON_CRASH_NATIVE, cached))
    }

    @Test
    fun cutoffSkipsExitsOlderThanCurrentInstall() {
        assertEquals(1_000L, ExitReasons.cutoffTimestamp(lastSeen = 0L, lastUpdateTime = 1_000L))
        assertEquals(2_000L, ExitReasons.cutoffTimestamp(lastSeen = 1_500L, lastUpdateTime = 2_000L))
        assertEquals(3_000L, ExitReasons.cutoffTimestamp(lastSeen = 3_000L, lastUpdateTime = 2_000L))
    }

    @Test
    fun pendingRecordsFromEarlierInstallsAreRejected() {
        assertTrue(ExitReasons.isPendingCurrent(pendingUpdateTime = 1_000L, currentUpdateTime = 1_000L))
        assertFalse(ExitReasons.isPendingCurrent(pendingUpdateTime = 0L, currentUpdateTime = 1_000L))
        assertFalse(ExitReasons.isPendingCurrent(pendingUpdateTime = 1_000L, currentUpdateTime = 2_000L))
        assertTrue(ExitReasons.isPendingCurrent(pendingUpdateTime = 1_000L, currentUpdateTime = 0L))
    }

    @Test
    fun versionLabelPrefersProcessStateSummary() {
        assertEquals(
            "3.3.1 (73)",
            ExitReasons.resolveVersionLabel("3.3.1 (73)".toByteArray(Charsets.UTF_8), fallback = "3.3.2 (74)"),
        )
        assertEquals(
            "3.3.2 (74)",
            ExitReasons.resolveVersionLabel(null, fallback = "3.3.2 (74)"),
        )
        assertEquals(
            "3.3.2 (74)",
            ExitReasons.resolveVersionLabel(ByteArray(0), fallback = "3.3.2 (74)"),
        )
        assertEquals(
            "3.3.2 (74)",
            ExitReasons.resolveVersionLabel("   ".toByteArray(Charsets.UTF_8), fallback = "3.3.2 (74)"),
        )
    }
}
