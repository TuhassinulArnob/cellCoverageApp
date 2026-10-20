package com.cellscope.drive

import com.cellscope.drive.analytics.CongestionEvidenceEngine
import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Multi-Scale Analytical Time Bins, Time-of-Day Segregation,
 * and Session Data Usage accounting in CongestionEvidenceEngine.
 */
class CongestionMultiScaleTest {

    private fun createTestEntity(
        id: Long,
        timeMs: Long,
        dlMbps: Double? = 25.0,
        ulMbps: Double? = 12.0,
        latencyMs: Long? = 35L,
        jitterMs: Double? = 4.0,
        lossPct: Double? = 0.0,
        rsrpDbm: Int? = -82,
        sinrDb: Double? = 16.0,
        dlBytes: Long = 524288L,
        ulBytes: Long = 131072L,
        bytesTransferred: Long = 655360L,
        testStatus: String = "COMPLETED",
        success: Boolean = true,
        skippedReason: String? = null
    ): ActiveQoeTestEntity {
        return ActiveQoeTestEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-19T10:00:00.000Z",
            timestampMs = timeMs,
            testType = "PERIODIC_AUTO",
            latencyMs = latencyMs,
            meanLatencyMs = latencyMs?.toDouble(),
            downloadSpeedMbps = dlMbps,
            uploadSpeedMbps = ulMbps,
            dlBytes = dlBytes,
            ulBytes = ulBytes,
            bytesTransferred = bytesTransferred,
            durationMs = 1000L,
            success = success,
            errorMessage = if (success) null else "Failed",
            testStatus = testStatus,
            skippedReason = skippedReason,
            servingRat = "LTE",
            servingPci = 177,
            servingEarfcn = 1600,
            rsrpDbm = rsrpDbm,
            sinrDb = sinrDb,
            jitterMs = jitterMs,
            packetLossPct = lossPct
        )
    }

    @Test
    fun testMultiScaleTimeBins_5Minutes() {
        val baseTime = 1729332000000L // 10:00:00 GMT
        val tests = listOf(
            createTestEntity(1, baseTime + 1 * 60_000L, dlMbps = 20.0),  // 10:01 -> Bin 10:00
            createTestEntity(2, baseTime + 3 * 60_000L, dlMbps = 30.0),  // 10:03 -> Bin 10:00
            createTestEntity(3, baseTime + 6 * 60_000L, dlMbps = 40.0),  // 10:06 -> Bin 10:05
            createTestEntity(4, baseTime + 8 * 60_000L, dlMbps = 50.0),  // 10:08 -> Bin 10:05
            createTestEntity(5, baseTime + 16 * 60_000L, dlMbps = 60.0) // 10:16 -> Bin 10:15
        )

        val bins = CongestionEvidenceEngine.binTimeSeries(tests, binMinutes = 5)

        // 3 non-empty bins (10:00, 10:05, 10:15) - strictly no zero-filling of empty 10:10 bin
        assertEquals(3, bins.size)

        assertEquals(5, bins[0].binMinutes)
        assertEquals(2, bins[0].sampleCount)
        assertEquals(25.0, bins[0].medianDlMbps!!, 0.01)
        assertEquals(25.0, bins[0].meanDlMbps!!, 0.01)

        assertEquals(2, bins[1].sampleCount)
        assertEquals(45.0, bins[1].medianDlMbps!!, 0.01)

        assertEquals(1, bins[2].sampleCount)
        assertEquals(60.0, bins[2].medianDlMbps!!, 0.01)
    }

    @Test
    fun testMultiScaleTimeBins_10Minutes() {
        val baseTime = 1729332000000L // 10:00:00 GMT
        val tests = listOf(
            createTestEntity(1, baseTime + 2 * 60_000L, dlMbps = 20.0),
            createTestEntity(2, baseTime + 7 * 60_000L, dlMbps = 30.0),
            createTestEntity(3, baseTime + 12 * 60_000L, dlMbps = 40.0),
            createTestEntity(4, baseTime + 22 * 60_000L, dlMbps = 50.0)
        )

        val bins = CongestionEvidenceEngine.binTimeSeries(tests, binMinutes = 10)

        // 10:02 and 10:07 fall in 10:00-10:10 bin
        // 10:12 falls in 10:10-10:20 bin
        // 10:22 falls in 10:20-10:30 bin
        assertEquals(3, bins.size)
        assertEquals(10, bins[0].binMinutes)
        assertEquals(2, bins[0].sampleCount)
        assertEquals(25.0, bins[0].medianDlMbps!!, 0.01)
        assertEquals(1, bins[1].sampleCount)
        assertEquals(40.0, bins[1].medianDlMbps!!, 0.01)
        assertEquals(1, bins[2].sampleCount)
        assertEquals(50.0, bins[2].medianDlMbps!!, 0.01)
    }

    @Test
    fun testMultiScaleTimeBins_15Minutes() {
        val baseTime = 1729332000000L // 10:00:00
        val tests = listOf(
            createTestEntity(1, baseTime + 5 * 60_000L, dlMbps = 15.0),
            createTestEntity(2, baseTime + 10 * 60_000L, dlMbps = 25.0),
            createTestEntity(3, baseTime + 20 * 60_000L, dlMbps = 35.0)
        )

        val bins = CongestionEvidenceEngine.binTimeSeries(tests, binMinutes = 15)

        assertEquals(2, bins.size)
        assertEquals(15, bins[0].binMinutes)
        assertEquals(2, bins[0].sampleCount)
        assertEquals(20.0, bins[0].medianDlMbps!!, 0.01)
        assertEquals(1, bins[1].sampleCount)
        assertEquals(35.0, bins[1].medianDlMbps!!, 0.01)
    }

    @Test
    fun testMultiScaleTimeBins_20Minutes() {
        val baseTime = 1729332000000L
        val tests = listOf(
            createTestEntity(1, baseTime + 5 * 60_000L, dlMbps = 22.0),
            createTestEntity(2, baseTime + 18 * 60_000L, dlMbps = 28.0),
            createTestEntity(3, baseTime + 25 * 60_000L, dlMbps = 34.0)
        )

        val bins = CongestionEvidenceEngine.binTimeSeries(tests, binMinutes = 20)

        assertEquals(2, bins.size)
        assertEquals(20, bins[0].binMinutes)
        assertEquals(2, bins[0].sampleCount)
        assertEquals(25.0, bins[0].medianDlMbps!!, 0.01)
    }

    @Test
    fun testMultiScaleTimeBins_60Minutes() {
        val baseTime = 1729332000000L // 10:00:00
        val tests = listOf(
            createTestEntity(1, baseTime + 10 * 60_000L, dlMbps = 20.0),
            createTestEntity(2, baseTime + 45 * 60_000L, dlMbps = 30.0),
            createTestEntity(3, baseTime + 75 * 60_000L, dlMbps = 40.0) // 11:15:00
        )

        val bins = CongestionEvidenceEngine.binTimeSeries(tests, binMinutes = 60)

        assertEquals(2, bins.size)
        assertEquals(60, bins[0].binMinutes)
        assertEquals(2, bins[0].sampleCount)
        assertEquals(25.0, bins[0].medianDlMbps!!, 0.01)
        assertEquals(1, bins[1].sampleCount)
        assertEquals(40.0, bins[1].medianDlMbps!!, 0.01)
    }

    @Test
    fun testCalculateSessionDataUsage_MixedTests() {
        val baseTime = 1729332000000L
        val tests = listOf(
            createTestEntity(1, baseTime, dlBytes = 524288L, ulBytes = 131072L, bytesTransferred = 655360L, testStatus = "COMPLETED", success = true),
            createTestEntity(2, baseTime + 60_000L, dlBytes = 524288L, ulBytes = 131072L, bytesTransferred = 655360L, testStatus = "COMPLETED", success = true),
            createTestEntity(3, baseTime + 120_000L, dlBytes = 0L, ulBytes = 0L, bytesTransferred = 0L, testStatus = "SKIPPED", success = false, skippedReason = "Daily budget reached"),
            createTestEntity(4, baseTime + 180_000L, dlBytes = 0L, ulBytes = 0L, bytesTransferred = 0L, testStatus = "FAILED", success = false)
        )

        val usage = CongestionEvidenceEngine.calculateSessionDataUsage(tests)

        assertEquals(1048576L, usage.downloadBytes) // 524288 * 2 = 1,048,576
        assertEquals(262144L, usage.uploadBytes) // 131072 * 2 = 262,144
        assertEquals(1310720L, usage.totalBytes) // 655360 * 2 = 1,310,720
        assertEquals(1.25, usage.totalMb, 0.01) // 1,310,720 / 1,048,576 = 1.25 MB
        assertEquals(1.0, usage.downloadMb, 0.01)
        assertEquals(0.25, usage.uploadMb, 0.01)
    }

    @Test
    fun testCalculateSessionDataUsage_Empty() {
        val usage = CongestionEvidenceEngine.calculateSessionDataUsage(emptyList())

        assertEquals(0L, usage.downloadBytes)
        assertEquals(0L, usage.uploadBytes)
        assertEquals(0L, usage.totalBytes)
        assertEquals(0.0, usage.totalMb, 0.001)
    }

    @Test
    fun testAnalyzeTimeOfDayWindows() {
        val cal = java.util.Calendar.getInstance()
        cal.set(2026, java.util.Calendar.OCTOBER, 19, 9, 30, 0) // 09:30 AM -> Morning Peak (8-12)
        val morningTime = cal.timeInMillis

        cal.set(2026, java.util.Calendar.OCTOBER, 19, 14, 0, 0) // 02:00 PM -> Midday (12-17)
        val middayTime = cal.timeInMillis

        cal.set(2026, java.util.Calendar.OCTOBER, 19, 19, 0, 0) // 07:00 PM -> Evening Peak (17-22)
        val eveningTime = cal.timeInMillis

        cal.set(2026, java.util.Calendar.OCTOBER, 19, 23, 30, 0) // 11:30 PM -> Night Off-Peak (22-8)
        val nightTime = cal.timeInMillis

        val tests = listOf(
            createTestEntity(1, morningTime, dlMbps = 35.0, latencyMs = 25L),
            createTestEntity(2, middayTime, dlMbps = 28.0, latencyMs = 32L),
            createTestEntity(3, eveningTime, dlMbps = 14.0, latencyMs = 65L),
            createTestEntity(4, nightTime, dlMbps = 55.0, latencyMs = 18L)
        )

        val windows = CongestionEvidenceEngine.analyzeTimeOfDayWindows(tests)

        assertEquals(4, windows.size)
        val morning = windows[CongestionEvidenceEngine.TimeOfDayWindow.MORNING_PEAK]
        val midday = windows[CongestionEvidenceEngine.TimeOfDayWindow.MIDDAY]
        val evening = windows[CongestionEvidenceEngine.TimeOfDayWindow.EVENING_PEAK]
        val night = windows[CongestionEvidenceEngine.TimeOfDayWindow.NIGHT_OFF_PEAK]

        assertNotNull(morning)
        assertEquals(1, morning!!.sampleCount)
        assertEquals(35.0, morning.meanDlMbps!!, 0.1)

        assertNotNull(midday)
        assertEquals(1, midday!!.sampleCount)
        assertEquals(28.0, midday.meanDlMbps!!, 0.1)

        assertNotNull(evening)
        assertEquals(1, evening!!.sampleCount)
        assertEquals(14.0, evening.meanDlMbps!!, 0.1)

        assertNotNull(night)
        assertEquals(1, night!!.sampleCount)
        assertEquals(55.0, night.meanDlMbps!!, 0.1)
    }
}
