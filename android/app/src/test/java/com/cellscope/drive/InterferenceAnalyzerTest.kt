package com.cellscope.drive

import com.cellscope.drive.analytics.InterferenceAnalyzer
import com.cellscope.drive.analytics.InterferenceClassification
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.model.ObservedCell
import org.junit.Assert.*
import org.junit.Test

class InterferenceAnalyzerTest {

    private fun createMeasurement(
        id: Long,
        pci: Int,
        earfcn: Int,
        lat: Double = 23.810,
        lon: Double = 90.410,
        rsrp: Int = -85,
        sinr: Double = 12.0
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
            speedKmh = 20f,
            networkType = "LTE",
            operatorAlpha = "Grameenphone",
            operatorNumeric = "47001",
            servingPci = pci,
            tac = 18452,
            earfcn = earfcn,
            bandwidthKhz = 20000,
            rsrpDbm = rsrp,
            rsrqDb = -10,
            rssiDbm = -60,
            cqi = 12,
            timingAdvance = 1,
            isServing = true,
            handoverTrigger = 0,
            sinrDb = sinr
        )
    }

    @Test
    fun testCoChannelCrsMod3Collision() {
        // Two cells simultaneously visible: Serving PCI 100 and Neighbor PCI 103 on same EARFCN 1650
        // 100 % 3 = 1, 103 % 3 = 1 -> Direct CRS Subcarrier Collision
        val activeCells = listOf(
            ObservedCell(pci = 100, earfcn = 1650, isServing = true, networkType = "LTE", rsrpDbm = -82),
            ObservedCell(pci = 103, earfcn = 1650, isServing = false, networkType = "LTE", rsrpDbm = -88)
        )

        val report = InterferenceAnalyzer.analyze(emptyList(), activeCells)

        assertEquals(1, report.totalCoChannelPairs)
        assertEquals(1, report.crsCollisionsCount)

        val event = report.coChannelEvents.first()
        assertEquals(1650, event.earfcnOrArfcn)
        assertEquals(100, event.pciA)
        assertEquals(103, event.pciB)
        assertTrue(event.crsMod3Collision)
        assertTrue(event.isSimultaneousObservation)
        assertEquals(InterferenceClassification.POSSIBLE_INTERFERENCE_RISK, event.classification)
        assertTrue(event.engineeringEvidence.contains("CRS Modulo-3 Collision"))
    }

    @Test
    fun testIdenticalPciCollisionOnSameChannel() {
        // Serving PCI 42 and Neighbor PCI 42 on same EARFCN -> Critical Collision
        val activeCells = listOf(
            ObservedCell(pci = 42, earfcn = 1650, isServing = true, networkType = "LTE", rsrpDbm = -80),
            ObservedCell(pci = 42, earfcn = 1650, isServing = false, networkType = "LTE", rsrpDbm = -84)
        )

        val report = InterferenceAnalyzer.analyze(emptyList(), activeCells)

        assertEquals(1, report.totalCoChannelPairs)
        assertEquals(1, report.pciReuseConcernsCount)

        val event = report.coChannelEvents.first()
        assertEquals(InterferenceClassification.POTENTIAL_PCI_REUSE_CONCERN, event.classification)
        assertTrue(event.engineeringEvidence.contains("Identical PCI Collision"))
    }

    @Test
    fun testAdjacentChannelPassbandOverlap() {
        // Two cells on different EARFCNs whose 20 MHz nominal channels overlap
        // EARFCN 300 (Band 1, 2140.0 MHz, BW 20MHz -> 2130..2150 MHz)
        // EARFCN 350 (Band 1, 2145.0 MHz, BW 20MHz -> 2135..2155 MHz)
        // Delta = 5.0 MHz < 20.0 MHz -> Passband Overlap
        val activeCells = listOf(
            ObservedCell(pci = 10, earfcn = 300, bandwidthKhz = 20000, isServing = true, networkType = "LTE"),
            ObservedCell(pci = 20, earfcn = 350, bandwidthKhz = 20000, isServing = false, networkType = "LTE")
        )

        val report = InterferenceAnalyzer.analyze(emptyList(), activeCells)

        assertEquals(1, report.totalAdjacentPairs)
        val adjacent = report.adjacentEvents.first()
        assertTrue(adjacent.overlapsNominalPassband)
        assertEquals(5.0, adjacent.deltaFreqMhz, 0.01)
        assertEquals(InterferenceClassification.POSSIBLE_INTERFERENCE_RISK, adjacent.classification)
        assertTrue(adjacent.engineeringEvidence.contains("Overlapping Passbands"))
    }

    @Test
    fun testHistoricalCoChannelProximityHypothesis() {
        // Two measurements with PCI 50 on EARFCN 3625 separated by 1500 meters
        val m1 = createMeasurement(id = 1, pci = 50, earfcn = 3625, lat = 23.8100, lon = 90.4100)
        val m2 = createMeasurement(id = 2, pci = 50, earfcn = 3625, lat = 23.8235, lon = 90.4100) // ~1.5 km away

        val report = InterferenceAnalyzer.analyze(listOf(m1, m2), emptyList())

        assertEquals(1, report.totalCoChannelPairs)
        val event = report.coChannelEvents.first()
        assertNotNull(event.distanceM)
        assertTrue(event.distanceM!! in 1400.0..1600.0)
        assertEquals(InterferenceClassification.POTENTIAL_PCI_REUSE_CONCERN, event.classification)
    }

    @Test
    fun testAnalyticalDisclaimerCompleteness() {
        val report = InterferenceAnalyzer.analyze(emptyList(), emptyList())
        assertNotNull(report.analyticalDisclaimer)
        assertTrue(report.analyticalDisclaimer.contains("ACLR"))
        assertTrue(report.analyticalDisclaimer.contains("TelephonyManager"))
    }

    @Test
    fun testAdjacentChannelWithNominalBandwidthReportsInsufficientData() {
        // When bandwidth is not exposed by modem RIL, nominal 10.0 MHz bandwidth is assigned.
        // InterferenceAnalyzer must report INSUFFICIENT_DATA and avoid fabricating channel overlap.
        val activeCells = listOf(
            ObservedCell(pci = 10, earfcn = 300, bandwidthKhz = null, isServing = true, networkType = "LTE"),
            ObservedCell(pci = 20, earfcn = 350, bandwidthKhz = null, isServing = false, networkType = "LTE")
        )

        val report = InterferenceAnalyzer.analyze(emptyList(), activeCells)
        assertEquals(1, report.totalAdjacentPairs)
        val adjacent = report.adjacentEvents.first()
        assertEquals(InterferenceClassification.INSUFFICIENT_DATA, adjacent.classification)
        assertFalse(adjacent.overlapsNominalPassband)
        assertTrue(adjacent.engineeringEvidence.contains("bandwidth not exposed by modem RIL"))
    }
}
