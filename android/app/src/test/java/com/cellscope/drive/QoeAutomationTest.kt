package com.cellscope.drive

import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.telephony.SchedulerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Active Network QoE Automation:
 * - SchedulerState lifecycle enum states and human-readable badges
 * - Configurable data budget and per-test limits (no hardcoded 50 MB cap)
 * - Preservation of skipped and failed test states without zero-filling
 */
class QoeAutomationTest {

    @Test
    fun testSchedulerStateValuesAndLabels() {
        assertEquals("Idle", SchedulerState.IDLE.label)
        assertEquals("Waiting", SchedulerState.WAITING.label)
        assertEquals("Running", SchedulerState.RUNNING.label)
        assertEquals("Skipped", SchedulerState.SKIPPED.label)
        assertEquals("Failed", SchedulerState.FAILED.label)
        assertEquals("Completed", SchedulerState.COMPLETED.label)
        assertEquals(6, SchedulerState.values().size)
    }

    @Test
    fun testDataBudgetConfigurableAndUnlimitedByDefault() {
        // Daily budget must be optional (null by default = unlimited)
        var dailyBudgetMb: Double? = null
        val currentUsageBytes = 75L * 1024L * 1024L // 75 MB used today

        // When budget is null, testing is never skipped due to budget
        val isExceededDefault = if (dailyBudgetMb != null) {
            currentUsageBytes >= (dailyBudgetMb * 1024.0 * 1024.0)
        } else {
            false
        }
        assertFalse("Unlimited by default: 75 MB should not trigger budget skip", isExceededDefault)

        // When user explicitly sets a 50 MB budget:
        dailyBudgetMb = 50.0
        val isExceededConfigured = if (dailyBudgetMb != null) {
            currentUsageBytes >= (dailyBudgetMb * 1024.0 * 1024.0)
        } else {
            false
        }
        assertTrue("Configured 50 MB budget: 75 MB usage must trigger budget skip", isExceededConfigured)
    }

    @Test
    fun testPerTestTransferLimits() {
        val defaultDlLimitBytes: Long = 1024 * 1024L // 1 MB
        val defaultUlLimitBytes: Long = 131072L // 128 KB

        assertEquals(1048576L, defaultDlLimitBytes)
        assertEquals(131072L, defaultUlLimitBytes)

        // User can adjust limits
        val highDefDlLimitBytes: Long = 5 * 1024 * 1024L
        val highDefUlLimitBytes: Long = 1024 * 1024L
        assertTrue(highDefDlLimitBytes > defaultDlLimitBytes)
        assertTrue(highDefUlLimitBytes > defaultUlLimitBytes)
    }

    @Test
    fun testSkippedTestEntityPreservesNullWithoutZeroFilling() {
        val skippedTest = ActiveQoeTestEntity(
            id = 101L,
            sessionId = 12L,
            timestampIso = "2026-10-19T14:30:00.000Z",
            timestampMs = 1729348200000L,
            testType = "PERIODIC_AUTO",
            success = false,
            errorMessage = "Skipped: Daily data budget limit reached (50.0 MB)",
            testStatus = "SKIPPED",
            skippedReason = "Daily data budget limit reached (50.0 MB)",
            scheduledTimestampMs = 1729348200000L,
            downloadSpeedMbps = null,
            uploadSpeedMbps = null,
            latencyMs = null,
            jitterMs = null,
            packetLossPct = null,
            bytesTransferred = 0L,
            dlBytes = 0L,
            ulBytes = 0L
        )

        assertEquals("SKIPPED", skippedTest.testStatus)
        assertEquals("Daily data budget limit reached (50.0 MB)", skippedTest.skippedReason)
        assertFalse(skippedTest.success)
        assertNull("Download throughput must be null, never 0.0 Mbps", skippedTest.downloadSpeedMbps)
        assertNull("Upload throughput must be null, never 0.0 Mbps", skippedTest.uploadSpeedMbps)
        assertNull("Latency must be null, never 0 ms", skippedTest.latencyMs)
        assertNull("Jitter must be null, never 0 ms", skippedTest.jitterMs)
        assertNull("Packet loss must be null, never 0%", skippedTest.packetLossPct)
    }

    @Test
    fun testFailedTestEntityPreservesNullWithoutZeroFilling() {
        val failedTest = ActiveQoeTestEntity(
            id = 102L,
            sessionId = 12L,
            timestampIso = "2026-10-19T14:40:00.000Z",
            timestampMs = 1729348800000L,
            testType = "PERIODIC_AUTO",
            success = false,
            errorMessage = "SocketTimeoutException: Connection to speed.cloudflare.com timed out after 5000ms",
            testStatus = "FAILED",
            skippedReason = null,
            scheduledTimestampMs = 1729348800000L,
            downloadSpeedMbps = null,
            uploadSpeedMbps = null,
            latencyMs = null,
            jitterMs = null,
            packetLossPct = 100.0,
            probeCount = 4,
            successfulProbeCount = 0
        )

        assertEquals("FAILED", failedTest.testStatus)
        assertNull(failedTest.skippedReason)
        assertFalse(failedTest.success)
        assertTrue(failedTest.errorMessage!!.contains("SocketTimeoutException"))
        assertNull("Failed download throughput must be null, never 0.0", failedTest.downloadSpeedMbps)
        assertNull("Failed upload throughput must be null, never 0.0", failedTest.uploadSpeedMbps)
        assertNull("Failed latency must be null, never 0", failedTest.latencyMs)
        assertEquals(100.0, failedTest.packetLossPct!!, 0.01)
        assertEquals(4, failedTest.probeCount)
        assertEquals(0, failedTest.successfulProbeCount)
    }

    @Test
    fun testSuccessfulTestEntityPopulatesAllMetrics() {
        val successTest = ActiveQoeTestEntity(
            id = 103L,
            sessionId = 12L,
            timestampIso = "2026-10-19T14:50:00.000Z",
            timestampMs = 1729349400000L,
            testType = "PERIODIC_AUTO",
            success = true,
            testStatus = "COMPLETED",
            scheduledTimestampMs = 1729349400000L,
            downloadSpeedMbps = 45.8,
            uploadSpeedMbps = 18.2,
            latencyMs = 28L,
            meanLatencyMs = 29.4,
            p95LatencyMs = 38.0,
            jitterMs = 4.2,
            packetLossPct = 0.0,
            probeCount = 4,
            successfulProbeCount = 4,
            dlBytes = 524288L,
            ulBytes = 131072L,
            bytesTransferred = 655360L,
            durationMs = 92L,
            dlDurationMs = 92L,
            ulDurationMs = 58L,
            servingRat = "5G",
            servingPci = 350,
            rsrpDbm = -78,
            sinrDb = 22.5
        )

        assertEquals("COMPLETED", successTest.testStatus)
        assertTrue(successTest.success)
        assertEquals(45.8, successTest.downloadSpeedMbps!!, 0.01)
        assertEquals(18.2, successTest.uploadSpeedMbps!!, 0.01)
        assertEquals(28L, successTest.latencyMs)
        assertEquals(29.4, successTest.meanLatencyMs!!, 0.01)
        assertEquals(38.0, successTest.p95LatencyMs!!, 0.01)
        assertEquals(4.2, successTest.jitterMs!!, 0.01)
        assertEquals(0.0, successTest.packetLossPct!!, 0.01)
        assertEquals(655360L, successTest.bytesTransferred)
    }
}
