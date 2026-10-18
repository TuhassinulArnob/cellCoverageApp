package com.cellscope.drive

import com.cellscope.drive.analytics.RadioQualityEngine
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.*
import org.junit.Test

class RadioQualityEngineTest {

    private fun createSample(
        id: Long,
        rsrp: Int,
        rsrq: Int,
        sinr: Double? = null,
        earfcn: Int? = 1600,
        neighborsRaw: String? = null
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-17T12:00:00.000Z",
            timestampMs = 1000L * id,
            latitude = 23.810,
            longitude = 90.410,
            altitudeM = 10.0,
            accuracyM = 3f,
            speedKmh = 20f,
            networkType = "LTE",
            operatorAlpha = "Grameenphone",
            operatorNumeric = "47001",
            servingPci = 288,
            tac = 18452,
            earfcn = earfcn,
            bandwidthKhz = 20000,
            rsrpDbm = rsrp,
            rsrqDb = rsrq,
            rssiDbm = -60,
            sinrDb = sinr,
            cqi = 12,
            timingAdvance = 1,
            isServing = true,
            neighborCellsJson = neighborsRaw,
            handoverTrigger = 0
        )
    }

    @Test
    fun testRsrpHistogramBinning() {
        val samples = listOf(
            createSample(1, rsrp = -75, rsrq = -9),  // Excellent (>= -80)
            createSample(2, rsrp = -85, rsrq = -11), // Good (-80..-90)
            createSample(3, rsrp = -95, rsrq = -14), // Fair (-90..-100)
            createSample(4, rsrp = -105, rsrq = -17), // Poor (-100..-110)
            createSample(5, rsrp = -115, rsrq = -20)  // Very Poor (< -110)
        )

        val report = RadioQualityEngine.evaluateSessionQuality(samples)
        val dist = report.rsrpDistribution

        assertEquals(5, dist.buckets.size)
        assertEquals(1, dist.buckets[0].count) // Excellent
        assertEquals(1, dist.buckets[1].count) // Good
        assertEquals(1, dist.buckets[2].count) // Fair
        assertEquals(1, dist.buckets[3].count) // Poor
        assertEquals(1, dist.buckets[4].count) // Very Poor

        assertEquals(-95.0, dist.mean!!, 0.1)
        assertEquals(-75.0, dist.max!!, 0.1)
        assertEquals(-115.0, dist.min!!, 0.1)
    }

    @Test
    fun testNeighborDeltaAndHandoverMargin() {
        val samples = listOf(
            // Serving = -85, Best Neighbor = -87 -> Delta = 2 dB (< 3 dB near handover margin)
            createSample(1, rsrp = -85, rsrq = -10, neighborsRaw = "PCI:289/RSRP:-87/RSRQ:-11/EARFCN:1600"),
            // Serving = -92, Best Neighbor = -88 -> Delta = -4 dB (Overdue handover, neighbor is stronger)
            createSample(2, rsrp = -92, rsrq = -12, neighborsRaw = "PCI:290/RSRP:-88/RSRQ:-10/EARFCN:1600"),
            // Serving = -80, Best Neighbor = -95 -> Delta = 15 dB (Clean serving dominance)
            createSample(3, rsrp = -80, rsrq = -8, neighborsRaw = "PCI:291/RSRP:-95/RSRQ:-15/EARFCN:1600")
        )

        val report = RadioQualityEngine.evaluateSessionQuality(samples)
        val delta = report.neighborDelta

        assertEquals(3, delta.totalEvaluatedSamples)
        assertEquals(1, delta.nearHandoverMarginCount)
        assertEquals(1, delta.overdueHandoverCount)
    }

    @Test
    fun testPilotPollutionRequiresIntraFrequencyAndDeduplication() {
        // Case 1: Serving is on EARFCN 1600. Neighbors are on EARFCN 3690 (Inter-frequency, e.g. Band 8 900MHz).
        // Inter-frequency neighbors do NOT collide reference signals -> Must NOT be marked as pilot pollution!
        val interFreqSample = createSample(
            1,
            rsrp = -106,
            rsrq = -16,
            earfcn = 1600,
            neighborsRaw = "101:-101:-12:3690;102:-102:-11:3690;103:-103:-13:3690"
        )
        val report1 = RadioQualityEngine.evaluateSessionQuality(listOf(interFreqSample))
        assertEquals("Inter-frequency neighbors must not trigger pilot pollution", 0, report1.neighborDelta.pilotPollutionCount)

        // Case 2: Duplicate PCIs (e.g. PCI 101 reported 3 times on same carrier).
        // Must be deduplicated so single physical cell is not counted as 3 competitors!
        val duplicatePciSample = createSample(
            2,
            rsrp = -106,
            rsrq = -16,
            earfcn = 1600,
            neighborsRaw = "101:-104:-12:1600;101:-105:-12:1600;101:-103:-11:1600"
        )
        val report2 = RadioQualityEngine.evaluateSessionQuality(listOf(duplicatePciSample))
        assertEquals("Duplicate PCIs must be deduplicated", 0, report2.neighborDelta.pilotPollutionCount)

        // Case 3: Genuine intra-frequency pilot pollution: 3 distinct physical cells on same EARFCN within 5 dB with high RSRQ degradation
        val genuinePollutionSample = createSample(
            3,
            rsrp = -106,
            rsrq = -16,
            earfcn = 1600,
            neighborsRaw = "101:-104:-15:1600;102:-105:-15:1600;103:-103:-15:1600"
        )
        val report3 = RadioQualityEngine.evaluateSessionQuality(listOf(genuinePollutionSample))
        assertEquals("3 distinct intra-frequency competitors with high degradation must be detected", 1, report3.neighborDelta.pilotPollutionCount)
    }

    @Test
    fun testMissingSinrDoesNotFabricateZeroDecibels() {
        // When modem does not support/expose SINR, all samples have null sinrDb
        val samples = listOf(
            createSample(1, rsrp = -90, rsrq = -11, sinr = null),
            createSample(2, rsrp = -92, rsrq = -12, sinr = null)
        )

        val report = RadioQualityEngine.evaluateSessionQuality(samples)
        val sinrDist = report.sinrDistribution

        assertNull("Mean SINR should be null when unexposed", sinrDist.mean)
        assertNull("Median SINR should be null when unexposed", sinrDist.median)
        assertTrue("Buckets should be empty when no valid SINR samples exist", sinrDist.buckets.isEmpty())
    }

    @Test
    fun testInsufficientEvidenceWhenFewerThanTenSamples() {
        val samples = (1..5).map { createSample(it.toLong(), rsrp = -85, rsrq = -10, sinr = 15.0) }
        val report = RadioQualityEngine.evaluateSessionQuality(samples)
        assertTrue(
            "Must report insufficient evidence when valid samples < 10",
            report.interferenceEstimate.interpretationSummary.contains("Insufficient evidence: Fewer than 10 valid telemetry samples")
        )
    }

    @Test
    fun testInconclusiveTelemetryWhenSinrAndNeighborsMissing() {
        val samples = (1..12).map { createSample(it.toLong(), rsrp = -85, rsrq = -10, sinr = null, neighborsRaw = null) }
        val report = RadioQualityEngine.evaluateSessionQuality(samples)
        assertTrue(
            "Must report inconclusive when SINR and neighbors are absent",
            report.interferenceEstimate.interpretationSummary.contains("Interference status inconclusive")
        )
    }

    @Test
    fun testMeasuredQualityWhenRealSinrExceedsTenDb() {
        val samples = (1..12).map { createSample(it.toLong(), rsrp = -85, rsrq = -10, sinr = 16.0, neighborsRaw = null) }
        val report = RadioQualityEngine.evaluateSessionQuality(samples)
        assertTrue(
            "Must report measured radio channel quality is good when SINR > 10 dB",
            report.interferenceEstimate.interpretationSummary.contains("Measured radio channel quality is good")
        )
    }

    @Test
    fun testMeasuredImpairmentWhenSinrIsNegative() {
        val samples = (1..12).map { createSample(it.toLong(), rsrp = -92, rsrq = -16, sinr = -3.5, neighborsRaw = null) }
        val report = RadioQualityEngine.evaluateSessionQuality(samples)
        assertTrue(
            "Must report measured impairment when SINR < 0 dB",
            report.interferenceEstimate.interpretationSummary.contains("Measured impairment")
        )
    }
}
