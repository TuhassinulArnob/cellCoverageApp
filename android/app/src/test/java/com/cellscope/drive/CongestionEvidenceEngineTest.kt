package com.cellscope.drive

import com.cellscope.drive.analytics.CongestionEvidenceEngine
import com.cellscope.drive.analytics.EvidenceClassification
import com.cellscope.drive.analytics.HistoricalQoeBaseline
import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CongestionEvidenceEngineTest {

    private fun createTestEntity(
        id: Long,
        dlMbps: Double? = 30.0,
        ulMbps: Double? = 15.0,
        latencyMs: Long? = 30L,
        jitterMs: Double? = 5.0,
        lossPct: Double? = 0.0,
        rsrpDbm: Int? = -85,
        sinrDb: Double? = 18.0,
        rat: String = "LTE",
        pci: Int = 177,
        timeMs: Long = 1729245600000L + id * 60_000L,
        success: Boolean = true
    ): ActiveQoeTestEntity {
        return ActiveQoeTestEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:00.000Z",
            timestampMs = timeMs,
            latitude = 23.8103,
            longitude = 90.4125,
            testType = "FULL_CYCLE",
            latencyMs = latencyMs,
            downloadSpeedMbps = dlMbps,
            uploadSpeedMbps = ulMbps,
            bytesTransferred = 1_000_000L,
            durationMs = 1000L,
            success = success,
            errorMessage = if (success) null else "Timeout",
            servingRat = rat,
            servingPci = pci,
            servingEarfcn = 1600,
            rsrpDbm = rsrpDbm,
            sinrDb = sinrDb,
            jitterMs = jitterMs,
            packetLossPct = lossPct,
            congestionClassification = null,
            operator = "Grameenphone"
        )
    }

    @Test
    fun testThroughputCalculation() {
        // 1,000,000 bytes in 1000 ms = 8,000,000 bits / 1s = 8.0 Mbps
        val mbps = CongestionEvidenceEngine.calculateThroughputMbps(1_000_000L, 1000L)
        assertNotNull(mbps)
        assertEquals(8.0, mbps!!, 0.01)

        // Zero or negative handling
        assertNull(CongestionEvidenceEngine.calculateThroughputMbps(0L, 1000L))
        assertNull(CongestionEvidenceEngine.calculateThroughputMbps(1_000_000L, 0L))
        assertNull(CongestionEvidenceEngine.calculateThroughputMbps(-500L, 1000L))
        assertNull(CongestionEvidenceEngine.calculateThroughputMbps(1_000_000L, -100L))
    }

    @Test
    fun testJitterCalculation() {
        // Under 2 samples -> null
        assertNull(CongestionEvidenceEngine.calculateJitter(emptyList()))
        assertNull(CongestionEvidenceEngine.calculateJitter(listOf(25L)))

        // RTTs: [20, 25, 22, 30]
        // Deltas: |25-20|=5, |22-25|=3, |30-22|=8. Sum = 16, Count = 3 -> 16/3 = 5.33 -> 5.3
        val jitter = CongestionEvidenceEngine.calculateJitter(listOf(20L, 25L, 22L, 30L))
        assertNotNull(jitter)
        assertEquals(5.3, jitter!!, 0.05)
    }

    @Test
    fun testProbeLossPercentage() {
        // Edge cases: total <= 0
        assertEquals(0.0, CongestionEvidenceEngine.calculateProbeLossPct(0, 0), 0.001)
        assertEquals(0.0, CongestionEvidenceEngine.calculateProbeLossPct(1, -1), 0.001)

        // 1 failed out of 4 probes = 25.0%
        assertEquals(25.0, CongestionEvidenceEngine.calculateProbeLossPct(1, 4), 0.01)

        // 0 failed out of 4 probes = 0.0%
        assertEquals(0.0, CongestionEvidenceEngine.calculateProbeLossPct(0, 4), 0.01)

        // 4 failed out of 4 probes = 100.0%
        assertEquals(100.0, CongestionEvidenceEngine.calculateProbeLossPct(4, 4), 0.01)
    }

    @Test
    fun testComputeP95() {
        assertNull(CongestionEvidenceEngine.computeP95(emptyList()))

        val values = listOf(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0)
        val p95 = CongestionEvidenceEngine.computeP95(values)
        assertNotNull(p95)
        // 0.95 * 9 = 8.55 -> index 8 -> 90.0
        assertEquals(90.0, p95!!, 0.01)
    }

    @Test
    fun testHistoricalBaselineBuilding() {
        val tests = listOf(
            createTestEntity(1, dlMbps = 30.0, ulMbps = 15.0, latencyMs = 25L, pci = 177, success = true),
            createTestEntity(2, dlMbps = 32.0, ulMbps = 16.0, latencyMs = 28L, pci = 177, success = true),
            createTestEntity(3, dlMbps = 28.0, ulMbps = 14.0, latencyMs = 30L, pci = 177, success = true),
            createTestEntity(4, dlMbps = 5.0, ulMbps = 2.0, latencyMs = 80L, pci = 999, success = true), // different PCI
            createTestEntity(5, dlMbps = 0.0, ulMbps = 0.0, latencyMs = null, pci = 177, success = false) // failed
        )

        val baseline = CongestionEvidenceEngine.buildHistoricalBaseline(tests, targetRat = "LTE", targetPci = 177)
        assertEquals(3, baseline.sampleCount)
        assertTrue(baseline.isReliable)
        assertEquals("PCI_177", baseline.contextKey)
        assertEquals(30.0, baseline.medianDlMbps!!, 0.1)
        assertEquals(15.0, baseline.medianUlMbps!!, 0.1)
        assertEquals(28.0, baseline.medianLatencyMs!!, 0.1)
    }

    @Test
    fun testEvaluateCongestion_InsufficientEvidence_NoSuccessfulTests() {
        val baseline = HistoricalQoeBaseline(
            rat = "LTE",
            contextKey = "PCI_177",
            sampleCount = 5,
            medianDlMbps = 30.0,
            medianUlMbps = 15.0,
            medianLatencyMs = 30.0,
            p95LatencyMs = 45.0,
            meanJitterMs = 5.0,
            probeLossPct = 0.0,
            medianRsrpDbm = -85.0,
            medianSinrDb = 18.0,
            isReliable = true
        )

        val failedTests = listOf(createTestEntity(1, success = false))
        val result = CongestionEvidenceEngine.evaluateCongestion(failedTests, baseline)

        assertEquals(EvidenceClassification.INSUFFICIENT_EVIDENCE, result.classification)
        assertFalse(result.isRfStable)
        assertEquals(0, result.currentSampleCount)
    }

    @Test
    fun testEvaluateCongestion_InsufficientEvidence_UnreliableBaseline() {
        val weakBaseline = HistoricalQoeBaseline(
            rat = "LTE",
            contextKey = "PCI_177",
            sampleCount = 1,
            medianDlMbps = 30.0,
            medianUlMbps = 15.0,
            medianLatencyMs = 30.0,
            p95LatencyMs = 45.0,
            meanJitterMs = 5.0,
            probeLossPct = 0.0,
            medianRsrpDbm = -85.0,
            medianSinrDb = 18.0,
            isReliable = false
        )

        val current = listOf(createTestEntity(1, dlMbps = 5.0, latencyMs = 120L))
        val result = CongestionEvidenceEngine.evaluateCongestion(current, weakBaseline)

        assertEquals(EvidenceClassification.INSUFFICIENT_EVIDENCE, result.classification)
        assertTrue(result.summary.contains("Insufficient historical baseline"))
    }

    @Test
    fun testEvaluateCongestion_RfLimited() {
        val baseline = HistoricalQoeBaseline(
            rat = "LTE",
            contextKey = "PCI_177",
            sampleCount = 5,
            medianDlMbps = 30.0,
            medianUlMbps = 15.0,
            medianLatencyMs = 30.0,
            p95LatencyMs = 45.0,
            meanJitterMs = 5.0,
            probeLossPct = 0.0,
            medianRsrpDbm = -80.0,
            medianSinrDb = 18.0,
            isReliable = true
        )

        // Signal drops from -80 dBm baseline to -110 dBm (delta = -30 dB, also < -105 dBm)
        // DL drops from 30 Mbps to 8 Mbps (66% degradation)
        val current = listOf(createTestEntity(1, dlMbps = 8.0, latencyMs = 70L, rsrpDbm = -110, sinrDb = -2.0))
        val result = CongestionEvidenceEngine.evaluateCongestion(current, baseline)

        assertEquals(EvidenceClassification.RF_LIMITED, result.classification)
        assertFalse(result.isRfStable)
        assertTrue(result.whyThisResult.contains("physical layer path loss"))
    }

    @Test
    fun testEvaluateCongestion_StrongerCongestionEvidence() {
        val baseline = HistoricalQoeBaseline(
            rat = "LTE",
            contextKey = "PCI_177",
            sampleCount = 5,
            medianDlMbps = 30.0,
            medianUlMbps = 15.0,
            medianLatencyMs = 30.0,
            p95LatencyMs = 45.0,
            meanJitterMs = 5.0,
            probeLossPct = 0.0,
            medianRsrpDbm = -85.0,
            medianSinrDb = 18.0,
            isReliable = true
        )

        // Stable clean RF (RSRP = -84 dBm, delta = +1 dB), but 3 consecutive tests with severe degradation:
        // DL drops to 10 Mbps (66% degradation), Latency spikes to 80 ms (> 100% increase), Jitter = 35 ms
        val current = listOf(
            createTestEntity(1, dlMbps = 10.0, latencyMs = 75L, jitterMs = 35.0, rsrpDbm = -84, sinrDb = 17.0),
            createTestEntity(2, dlMbps = 11.0, latencyMs = 80L, jitterMs = 32.0, rsrpDbm = -84, sinrDb = 16.0),
            createTestEntity(3, dlMbps = 9.0, latencyMs = 85L, jitterMs = 36.0, rsrpDbm = -84, sinrDb = 17.0)
        )
        val result = CongestionEvidenceEngine.evaluateCongestion(current, baseline)

        assertEquals(EvidenceClassification.STRONGER_CONGESTION_EVIDENCE, result.classification)
        assertTrue(result.isRfStable)
        assertTrue(result.whyThisResult.contains("eNodeB scheduler queuing"))
        assertNotNull(result.operatorDisclaimer)
    }

    @Test
    fun testEvaluateCongestion_PossibleCongestion() {
        val baseline = HistoricalQoeBaseline(
            rat = "LTE",
            contextKey = "PCI_177",
            sampleCount = 5,
            medianDlMbps = 30.0,
            medianUlMbps = 15.0,
            medianLatencyMs = 30.0,
            p95LatencyMs = 45.0,
            meanJitterMs = 5.0,
            probeLossPct = 0.0,
            medianRsrpDbm = -85.0,
            medianSinrDb = 18.0,
            isReliable = true
        )

        // Stable RF (RSRP = -85 dBm), but only 1 sample showing DL degradation (15 Mbps, 50% drop)
        val current = listOf(
            createTestEntity(1, dlMbps = 15.0, latencyMs = 32L, jitterMs = 8.0, rsrpDbm = -85, sinrDb = 17.0)
        )
        val result = CongestionEvidenceEngine.evaluateCongestion(current, baseline)

        assertEquals(EvidenceClassification.POSSIBLE_CONGESTION, result.classification)
        assertTrue(result.isRfStable)
        assertTrue(result.summary.contains("Isolated throughput degradation"))
    }

    @Test
    fun testEvaluateCongestion_MixedAmbiguous() {
        val baseline = HistoricalQoeBaseline(
            rat = "LTE",
            contextKey = "PCI_177",
            sampleCount = 5,
            medianDlMbps = 30.0,
            medianUlMbps = 10.0,
            medianLatencyMs = 30.0,
            p95LatencyMs = 45.0,
            meanJitterMs = 5.0,
            probeLossPct = 0.0,
            medianRsrpDbm = -85.0,
            medianSinrDb = 18.0,
            isReliable = true
        )

        // Contradictory: DL dropped 50% (15 Mbps), but UL improved by 50% (15 Mbps, ulDegradationPct = -50%)
        val current = listOf(
            createTestEntity(1, dlMbps = 15.0, ulMbps = 15.0, latencyMs = 32L, rsrpDbm = -85, sinrDb = 17.0)
        )
        val result = CongestionEvidenceEngine.evaluateCongestion(current, baseline)

        assertEquals(EvidenceClassification.MIXED_AMBIGUOUS, result.classification)
        assertTrue(result.whyThisResult.contains("DL throughput declined while UL improved"))
    }

    @Test
    fun testEvaluateCongestion_HealthyNominalPerformance() {
        val baseline = HistoricalQoeBaseline(
            rat = "LTE",
            contextKey = "PCI_177",
            sampleCount = 5,
            medianDlMbps = 30.0,
            medianUlMbps = 10.0,
            medianLatencyMs = 30.0,
            p95LatencyMs = 45.0,
            meanJitterMs = 5.0,
            probeLossPct = 0.0,
            medianRsrpDbm = -85.0,
            medianSinrDb = 18.0,
            isReliable = true
        )

        // Nominal values close to baseline
        val current = listOf(
            createTestEntity(1, dlMbps = 29.0, ulMbps = 9.8, latencyMs = 31L, rsrpDbm = -85, sinrDb = 18.0)
        )
        val result = CongestionEvidenceEngine.evaluateCongestion(current, baseline)

        assertEquals(EvidenceClassification.INSUFFICIENT_EVIDENCE, result.classification)
        assertTrue(result.summary.contains("Normal throughput and latency performance"))
    }

    @Test
    fun testBinTimeSeries_NoZeroFilling() {
        val baseTime = 1729245600000L // 10:00:00
        val tests = listOf(
            createTestEntity(1, dlMbps = 20.0, latencyMs = 30L, timeMs = baseTime + 2 * 60_000L),  // 10:02 (Bin 10:00)
            createTestEntity(2, dlMbps = 25.0, latencyMs = 35L, timeMs = baseTime + 5 * 60_000L),  // 10:05 (Bin 10:00)
            // Empty interval between 10:10 and 10:20
            createTestEntity(3, dlMbps = 30.0, latencyMs = 25L, timeMs = baseTime + 25 * 60_000L) // 10:25 (Bin 10:20)
        )

        val bins = CongestionEvidenceEngine.binTimeSeries(tests, binMinutes = 10)
        // Should produce exactly 2 bins (10:00 and 10:20), no zero-filled bin for 10:10
        assertEquals(2, bins.size)

        val bin0 = bins[0]
        assertEquals(2, bin0.sampleCount)
        assertEquals(22.5, bin0.medianDlMbps!!, 0.1)

        val bin1 = bins[1]
        assertEquals(1, bin1.sampleCount)
        assertEquals(30.0, bin1.medianDlMbps!!, 0.1)
    }
}
