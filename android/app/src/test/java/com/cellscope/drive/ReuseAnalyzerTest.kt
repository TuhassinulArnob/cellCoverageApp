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
        rsrp: Int = -85
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
            rsrqDb = -10,
            rssiDbm = -60,
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
        assertTrue(event.separationDistanceM > 3500.0)
    }

    @Test
    fun testPciCollisionHazard() {
        // Two distinct cells with same PCI (105) separated by only 400m -> HIGH_RISK_COLLISION
        val s1 = createSample(1, pci = 105, earfcn = 1600, eci = 5001L, lat = 23.8100, lon = 90.4100)
        val s2 = createSample(2, pci = 105, earfcn = 1600, eci = 5002L, lat = 23.8130, lon = 90.4120) // ~400m

        val summary = ReuseAnalyzer.analyzeReuse(listOf(s1, s2))

        assertEquals(1, summary.reuseEvents.size)
        assertEquals(ReuseAnalyzer.ReuseRiskLevel.HIGH_RISK_COLLISION, summary.reuseEvents[0].riskLevel)
        assertTrue(summary.hasSevereCollisions)
    }

    @Test
    fun testCrsModulo3Collision() {
        // PCI 102 and PCI 105 on EARFCN 1600:
        // 102 % 3 = 0, 105 % 3 = 0 -> Modulo 3 collision!
        val s1 = createSample(1, pci = 102, earfcn = 1600, eci = 1111L, lat = 23.810, lon = 90.410)
        val s2 = createSample(2, pci = 105, earfcn = 1600, eci = 2222L, lat = 23.818, lon = 90.415) // ~1.0 km

        val summary = ReuseAnalyzer.analyzeReuse(listOf(s1, s2))

        assertEquals(1, summary.crsCollisions.size)
        val collision = summary.crsCollisions[0]
        assertEquals(1600, collision.earfcn)
        assertEquals(0, collision.mod3Value)
        assertEquals("HIGH INTERFERENCE", collision.severity)
    }
}
