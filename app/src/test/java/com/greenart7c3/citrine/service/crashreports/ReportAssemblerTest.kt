package com.greenart7c3.citrine.service.crashreports

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay now reports OutOfMemoryError instead of suppressing it, so the generated text must
 * carry what identifies the failing request: the thread, the stack trace and the heap state.
 */
class ReportAssemblerTest {
    @Test
    fun outOfMemoryReportNamesTheThreadAndTheHeap() {
        val report = ReportAssembler().buildReport(OutOfMemoryError("Failed to allocate a 70MB allocation"))

        assertTrue(report.contains("OutOfMemoryError"))
        assertTrue(report.contains("Java heap"))
        assertTrue(report.contains("Native heap"))
        assertTrue(report.contains("| Thread | ${Thread.currentThread().name} |"))
    }

    @Test
    fun causeIsIncluded() {
        val report = ReportAssembler().buildReport(RuntimeException("wrapper", IllegalStateException("root cause")))

        assertTrue(report.contains("RuntimeException"))
        assertTrue(report.contains("root cause"))
    }
}
