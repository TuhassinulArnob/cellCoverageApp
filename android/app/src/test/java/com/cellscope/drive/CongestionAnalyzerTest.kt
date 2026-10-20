package com.cellscope.drive

import com.cellscope.drive.analytics.CongestionAnalyzer
import com.cellscope.drive.analytics.CongestionConfidenceLevel
import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CongestionAnalyzerTest {

    private fun createSample(
        id: Long,
        rsrp: Int?,
        sinr: Double?,
        rsrq: Int?,
        pci: Int = 101,
        timeMs: Long = 1729245600000L + id * 1000L
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:0$id.000Z",
            timestampMs = timeMs,
            latitude = 23.8103 + id * 0.0001,
            longitude = 90.4125 + id * 0.0001,
            altitudeM = 15.0,
            accuracyM = 4.0f,
            speedKmh = 20.0f,
            networkType = "LTE",
            networkGen = "4G",
            operatorAlpha = "Grameenphone",
            operatorNumeric = "47001",
            servingPci = pci,
            tac = 24100,
            earfcn = 1600,
            bandwidthKhz = 20000,
            rsrpDbm = rsrp,
            rsrqDb = rsrq,
            rssiDbm = -60,
            sinrDb = sinr,
            cqi = 12,
            timingAdvance = 2,
            isServing = true,
            isGpsValid = true
        )
    }

    private fun createActiveTest(
        id: Long,
        dlMbps: Double? = 25.0,
        ulMbps: Double? = 10.0,
        latencyMs: Long? = 35L,
        timeMs: Long = 1729245600000L + id * 1000L,
        success: Boolean = true
    ): ActiveQoeTestEntity {
        return ActiveQoeTestEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:0$id.000Z",
            timestampMs = timeMs,
            latitude = 23.8103,
            longitude = 90.4125,
            testType = "FULL_CYCLE",
            latencyMs = latencyMs,
            downloadSpeedMbps = dlMbps,
            uploadSpeedMbps = ulMbps,
            durationMs = 5000L,
            bytesTransferred = 10_000_000L,
            success = success
        )
    }

    @Test
    fun testEmptyTestsYieldsInsufficientEvidence() {
        val samples = (1L..5L).map { createSample(it, rsrp = -85, sinr = 15.0, rsrq = -10) }
        val result = CongestionAnalyzer.analyzeCongestion(samples, emptyList())
        assertEquals(CongestionConfidenceLevel.INSUFFICIENT_EVIDENCE, result.confidenceLevel)
        assertEquals(0, result.testCount)
        assertTrue(result.engineeringEvidence.contains("cannot responsibly assert network congestion"))
    }

    @Test
    fun testWeakCoverageIdentifiedAsLimitingFactor() {
        // Poor RSRP (-115 dBm)
        val samples = (1L..5L).map { createSample(it, rsrp = -115, sinr = -2.0, rsrq = -18) }
        val activeTests = listOf(createActiveTest(1, dlMbps = 1.0, latencyMs = 250L))
        val result = CongestionAnalyzer.analyzeCongestion(samples, activeTests)

        assertEquals(CongestionConfidenceLevel.POSSIBLE_BOTTLENECK, result.confidenceLevel)
        assertTrue(result.primaryLimitingFactor.contains("Weak Coverage"))
    }

    @Test
    fun testRadioInterferenceIdentifiedWhenSinrDegradedUnderGoodRsrp() {
        // Good RSRP (-80 dBm), but severe negative SINR (-4.0 dB)
        val samples = (1L..5L).map { createSample(it, rsrp = -80, sinr = -4.0, rsrq = -18) }
        val activeTests = listOf(createActiveTest(1, dlMbps = 1.5, latencyMs = 180L))
        val result = CongestionAnalyzer.analyzeCongestion(samples, activeTests)

        assertTrue(result.primaryLimitingFactor.contains("Interference"))
    }

    @Test
    fun testRecurringCongestionIdentifiedUnderPristineRf() {
        // Pristine RF: RSRP -78 dBm, SINR 18.0 dB
        val samples = (1L..10L).map { createSample(it, rsrp = -78, sinr = 18.0, rsrq = -8) }
        // 3 consecutive throttled tests
        val activeTests = listOf(
            createActiveTest(1, dlMbps = 1.2, latencyMs = 160L),
            createActiveTest(2, dlMbps = 0.9, latencyMs = 210L),
            createActiveTest(3, dlMbps = 1.5, latencyMs = 180L)
        )
        val result = CongestionAnalyzer.analyzeCongestion(samples, activeTests)

        assertEquals(CongestionConfidenceLevel.STRONG_CONGESTION_EVIDENCE, result.confidenceLevel)
        assertTrue(result.primaryLimitingFactor.contains("Congestion"))
        assertTrue(result.engineeringEvidence.contains("Repeated tests"))
    }

    @Test
    fun testHealthyPerformanceYieldsNormalCapacity() {
        // Great RF: RSRP -75 dBm, SINR 22.0 dB, DL Throughput 55.0 Mbps, latency 28ms
        val samples = (1L..5L).map { createSample(it, rsrp = -75, sinr = 22.0, rsrq = -7) }
        val activeTests = listOf(createActiveTest(1, dlMbps = 55.0, latencyMs = 28L))
        val result = CongestionAnalyzer.analyzeCongestion(samples, activeTests)

        assertTrue(result.primaryLimitingFactor.contains("Normal Cell Capacity"))
    }
}
