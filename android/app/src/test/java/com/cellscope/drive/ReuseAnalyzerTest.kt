package com.cellscope.drive

import com.cellscope.drive.analytics.ReuseAnalyzer
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.*
import org.junit.Test

class ReuseAnalyzerTest {

    private fun createSample(
        id: Long,
        pci: Int,
        earfcn: Int,
        eci: Long,
        lat: Double,
        lon: Double,
        rsrp: Int = -85,
        rsrq: Int = -10,
        sinr: Double? = null
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-17T12:00:00.000Z",
            timestampMs = 1000L * id,
            latitude = lat,
            longitude = lon,
            altitudeM = 10.0,
            accuracyM = 3f,
            speedKmh = 25f,
            networkType = "LTE",
            operatorAlpha = "Grameenphone",
            operatorNumeric = "47001",
            servingPci = pci,
            tac = 18452,
            eci = eci.toInt(),
            earfcn = earfcn,
            bandwidthKhz = 20000,
            rsrpDbm = rsrp,
            rsrqDb = rsrq,
            rssiDbm = -60,
            sinrDb = sinr,
            cqi = 12,
            timingAdvance = 1,
            isServing = true,
            handoverTrigger = 0
        )
    }

    @Test
    fun testPciReuseDetection() {
        // Two distinct cells with same PCI (288) separated by 4.0 km (planned reuse)
        val s1 = createSample(1, pci = 288, earfcn = 1600, eci = 1001L, lat = 23.810, lon = 90.410)
        val s2 = createSample(2, pci = 288, earfcn = 1600, eci = 2001L, lat = 23.846, lon = 90.410) // ~4.0 km north

        val summary = ReuseAnalyzer.analyzeReuse(listOf(s1, s2))

        assertEquals(1, summary.totalUniquePcis)
        assertEquals(1, summary.totalUniqueEarfcn)
        assertEquals(1, summary.reuseEvents.size)

        val event = summary.reuseEvents[0]
        assertEquals(288, event.pci)
        assertEquals(ReuseAnalyzer.ReuseRiskLevel.LOW_RISK_PLANNED_REUSE, event.riskLevel)
        assertEquals(ReuseAnalyzer.ReuseClassification.SAME_PCI_DIFFERENT_LOCATIONS, event.classification)
        assertTrue(event.separationDistanceM > 3500.0)
    }

    @Test
    fun testPciCollisionHazard() {
        // Two distinct cells with same PCI (105) separated by only 400m -> HIGH_RISK_COLLISION & POTENTIAL_PCI_CONFUSION_COLLISION
        val s1 = createSample(1, pci = 105, earfcn = 1600, eci = 5001L, lat = 23.8100, lon = 90.4100)
        val s2 = createSample(2, pci = 105, earfcn = 1600, eci = 5002L, lat = 23.8130, lon = 90.4120) // ~400m

        val summary = ReuseAnalyzer.analyzeReuse(listOf(s1, s2))

        assertEquals(1, summary.reuseEvents.size)
        assertEquals(ReuseAnalyzer.ReuseRiskLevel.HIGH_RISK_COLLISION, summary.reuseEvents[0].riskLevel)
        assertEquals(ReuseAnalyzer.ReuseClassification.POTENTIAL_PCI_CONFUSION_COLLISION, summary.reuseEvents[0].classification)
        assertTrue(summary.hasSevereCollisions)
    }

    @Test
    fun testCrsModulo3Collision() {
        // PCI 102 and PCI 105 on EARFCN 1600:
        // 102 % 3 = 0, 105 % 3 = 0 -> Modulo 3 alignment
        // Case 1: No degraded SINR -> Moderate alignment warning
        val s1 = createSample(1, pci = 102, earfcn = 1600, eci = 1111L, lat = 23.810, lon = 90.410, rsrp = -85)
        val s2 = createSample(2, pci = 105, earfcn = 1600, eci = 2222L, lat = 23.818, lon = 90.415, rsrp = -88) // ~1.0 km

        val summary1 = ReuseAnalyzer.analyzeReuse(listOf(s1, s2))
        assertEquals(1, summary1.crsCollisions.size)
        val collision1 = summary1.crsCollisions[0]
        assertEquals(1600, collision1.earfcn)
        assertEquals(0, collision1.mod3Value)
        assertEquals("MODERATE POTENTIAL", collision1.severity)
        assertFalse(collision1.isCorroboratedInterference)

        // Case 2: Corroborated with degraded SINR (< 3 dB) and close delta RSRP (3 dB <= 6 dB)
        val s3 = createSample(3, pci = 102, earfcn = 1600, eci = 3333L, lat = 23.810, lon = 90.410, rsrp = -85, sinr = 0.5)
        val s4 = createSample(4, pci = 105, earfcn = 1600, eci = 4444L, lat = 23.818, lon = 90.415, rsrp = -87, sinr = -1.0)
        val summary2 = ReuseAnalyzer.analyzeReuse(listOf(s3, s4))
        val collision2 = summary2.crsCollisions[0]
        assertEquals("HIGH INTERFERENCE CONCERN", collision2.severity)
        assertTrue(collision2.isCorroboratedInterference)
    }

    @Test
    fun testAdjacentChannelSeparationChecks() {
        // Band 3 EARFCN 1600 (DL 1845.0 MHz) and EARFCN 1700 (DL 1855.0 MHz) -> 10 MHz diff in same band -> Adjacent
        assertTrue(ReuseAnalyzer.isAdjacentChannel(1600, 1700))

        // Band 3 (1800 MHz) vs Band 1 (2100 MHz, EARFCN 300) -> Different bands separated by hundreds of MHz -> NOT adjacent
        assertFalse(ReuseAnalyzer.isAdjacentChannel(1600, 300))

        // Same channel -> NOT adjacent
        assertFalse(ReuseAnalyzer.isAdjacentChannel(1600, 1600))
    }
}
