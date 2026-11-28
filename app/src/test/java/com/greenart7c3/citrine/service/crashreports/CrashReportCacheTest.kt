package com.greenart7c3.citrine.service.crashreports

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CrashReportCacheTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteFile("stack.trace")
    }

    @Test
    fun roundTripsReportsForCurrentInstall() = runBlocking {
        val cache = CrashReportCache(context) { 1_000L }

        cache.appendReport("first report")
        cache.appendReport("second report")

        val loaded = cache.loadAndDelete()
        assertEquals("first report${CrashReportCache.REPORT_SEPARATOR}second report", loaded)
        assertNull(cache.loadAndDelete())
    }

    @Test
    fun dropsStaleReportWrittenByPreviousInstall() = runBlocking {
        val oldInstall = CrashReportCache(context) { 1_000L }
        oldInstall.appendReport("old crash from 3.3.1")

        val upgradedInstall = CrashReportCache(context) { 2_000L }
        assertNull(upgradedInstall.loadAndDelete())
    }

    @Test
    fun appendAfterUpgradeDiscardsPreviousInstallReports() = runBlocking {
        val oldInstall = CrashReportCache(context) { 1_000L }
        oldInstall.appendReport("old crash from 3.3.1")

        val upgradedInstall = CrashReportCache(context) { 2_000L }
        upgradedInstall.appendReport("new crash from 3.3.4")

        assertEquals("new crash from 3.3.4", upgradedInstall.loadAndDelete())
    }

    @Test
    fun dropsLegacyUnstampedFileOnKnownInstall() = runBlocking {
        context.openFileOutput("stack.trace", Context.MODE_PRIVATE).use {
            it.write("OutOfMemoryError: 3.3.1\n\nlegacy trace".toByteArray(Charsets.UTF_8))
        }

        val cache = CrashReportCache(context) { 2_000L }
        assertNull(cache.loadAndDelete())
    }

    @Test
    fun keepsLegacyUnstampedFileWhenUpdateTimeUnavailable() = runBlocking {
        context.openFileOutput("stack.trace", Context.MODE_PRIVATE).use {
            it.write("OutOfMemoryError: 3.3.1\n\nlegacy trace".toByteArray(Charsets.UTF_8))
        }

        val cache = CrashReportCache(context) { 0L }
        assertEquals("OutOfMemoryError: 3.3.1\n\nlegacy trace", cache.loadAndDelete())
    }

    @Test
    fun combineAndCapKeepsNewestCompleteReports() {
        val r1 = "A".repeat(40)
        val r2 = "B".repeat(40)
        val r3 = "C".repeat(40)
        val sep = CrashReportCache.REPORT_SEPARATOR

        val afterTwo = CrashReportCache.combineAndCap(r1, r2, maxChars = 100)
        assertEquals("$r1$sep$r2", afterTwo)

        val afterThreeDroppingTheOldest = CrashReportCache.combineAndCap(afterTwo, r3, maxChars = 100)
        assertEquals("$r2$sep$r3", afterThreeDroppingTheOldest)
        assertTrue(afterThreeDroppingTheOldest.length <= 100)
    }

    @Test
    fun combineAndCapTruncatesOversizedSingleReportFromEnd() {
        val huge = "HEADER:" + "X".repeat(200)
        val result = CrashReportCache.combineAndCap("older", huge, maxChars = 50)
        assertEquals(50, result.length)
        assertTrue(result.startsWith("HEADER:"))
    }
}
