package com.cellscope.drive

import com.cellscope.drive.analytics.HandoverAnalyzer
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoverAnalyzerTest {

    private fun createSample(
        id: Long,
        pci: Int?,
        tac: Int? = 18452,
        earfcn: Int? = 1600,
        rsrp: Int? = -85,
        speedKmh: Float = 30f,
        timeMs: Long = 1000L * id
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-17T12:00:0$id.000Z",
            timestampMs = timeMs,
            latitude = 23.810 + (id * 0.0001),
            longitude = 90.412 + (id * 0.0001),
            altitudeM = 10.0,
            accuracyM = 3.0f,
            speedKmh = speedKmh,
            networkType = "LTE",
            operatorAlpha = "Grameenphone",
            operatorNumeric = "47001",
            servingPci = pci,
            tac = tac,
            earfcn = earfcn,
            bandwidthKhz = 20000,
            rsrpDbm = rsrp,
            rsrqDb = -11,
            rssiDbm = -60,
            cqi = 12,
            timingAdvance = 2,
            isServing = true,
            handoverTrigger = 0
        )
    }

    @Test
    fun testStableServingCellNoTransitions() {
        val samples = listOf(
            createSample(1, pci = 101, rsrp = -80),
            createSample(2, pci = 101, rsrp = -82),
            createSample(3, pci = 101, rsrp = -81)
        )

        val summary = HandoverAnalyzer.analyzeTransitions(1L, samples, 500.0)
        assertEquals(0, summary.totalTransitions)
        assertEquals(0, summary.confirmedHandovers)
        assertEquals(0, summary.observedTransitions)
        assertEquals(1, summary.dwellStats.size)
        assertEquals(101, summary.dwellStats[0].pci)
        assertEquals(100.0f, summary.dwellStats[0].percentageOfSession, 0.01f)
    }

    @Test
    fun testTransitionDetectionAndClassification() {
        val samples = listOf(
            createSample(1, pci = 288, tac = 100, rsrp = -80, speedKmh = 35f),
            createSample(2, pci = 288, tac = 100, rsrp = -82, speedKmh = 35f),
            createSample(3, pci = 290, tac = 200, rsrp = -86, speedKmh = 35f), // Inter-TAC change -> CONFIRMED_HANDOVER
            createSample(4, pci = 290, tac = 200, rsrp = -118, speedKmh = 0f), // Drops below -115 dBm
            createSample(5, pci = 292, tac = 200, rsrp = -88, speedKmh = 0f)  // Stationary & low rsrp drop -> SIGNAL_LOSS
        )

        val summary = HandoverAnalyzer.analyzeTransitions(
            sessionId = 1L,
            measurements = samples,
            totalDistanceMeters = 2000.0 // 2 km
        )

        assertEquals(2, summary.totalTransitions)
        assertEquals(1, summary.confirmedHandovers)
        assertEquals(1, summary.signalLossTransitions)
        assertEquals(1.0f, summary.transitionsPerKm, 0.01f)

        // Dwell statistics check
        assertTrue(summary.dwellStats.isNotEmpty())
        val topDwell = summary.dwellStats.first()
        assertTrue(topDwell.sampleCount >= 2)
    }

    @Test
    fun testStationaryCellReselection() {
        val samples = listOf(
            createSample(1, pci = 100, rsrp = -85, speedKmh = 1.0f),
            createSample(2, pci = 100, rsrp = -85, speedKmh = 1.5f),
            createSample(3, pci = 102, rsrp = -83, speedKmh = 2.0f) // Stationary switch -> CELL_RESELECTION
        )

        val summary = HandoverAnalyzer.analyzeTransitions(1L, samples, 100.0)
        assertEquals(1, summary.totalTransitions)
        assertEquals(1, summary.cellReselections)
        assertEquals("CELL_RESELECTION", summary.transitionEvents[0].eventType)
    }

    @Test
    fun testRapidPingPongDetection() {
        // A -> B -> A within 8 seconds
        val samples = listOf(
            createSample(1, pci = 101, rsrp = -85, timeMs = 1000L),
            createSample(2, pci = 101, rsrp = -86, timeMs = 2000L),
            createSample(3, pci = 102, rsrp = -87, timeMs = 4000L), // Switch to 102
            createSample(4, pci = 102, rsrp = -88, timeMs = 6000L),
            createSample(5, pci = 101, rsrp = -85, timeMs = 8000L)  // Switch back to 101 within 4s of leaving -> Ping-Pong!
        )

        val summary = HandoverAnalyzer.analyzeTransitions(1L, samples, 500.0)
        assertEquals(2, summary.totalTransitions)
        assertEquals(1, summary.pingPongEvents)
        assertEquals(50.0f, summary.pingPongRatePct, 0.1f)
        assertEquals("POSSIBLE_HANDOVER", summary.transitionEvents[1].eventType)
        assertTrue(summary.transitionEvents[1].details.contains("ping-pong"))
    }

    @Test
    fun testIntraVsInterFrequencyTransitions() {
        val samples = listOf(
            createSample(1, pci = 101, earfcn = 1600, rsrp = -85),
            createSample(2, pci = 102, earfcn = 1600, rsrp = -82), // Same EARFCN -> Intra-Freq
            createSample(3, pci = 103, earfcn = 3750, rsrp = -80)  // Different EARFCN -> Inter-Freq
        )

        val summary = HandoverAnalyzer.analyzeTransitions(1L, samples, 1000.0)
        assertEquals(2, summary.totalTransitions)
        assertEquals(1, summary.intraFrequencyTransitions)
        assertEquals(1, summary.interFrequencyTransitions)
    }

    @Test
    fun testSignalDeltaCalculation() {
        val samples = listOf(
            createSample(1, pci = 101, rsrp = -95),
            createSample(2, pci = 102, rsrp = -85) // Gain of +10 dB
        )

        val summary = HandoverAnalyzer.analyzeTransitions(1L, samples, 500.0)
        assertEquals(1, summary.totalTransitions)
        assertEquals(10.0, summary.avgDeltaRsrpDb!!, 0.01)
        assertTrue(summary.transitionEvents[0].details.contains("ΔRSRP: +10.0 dB"))
    }

    @Test
    fun testOutOfOrderSamplesSorting() {
        // Samples arriving out of order
        val samples = listOf(
            createSample(3, pci = 102, timeMs = 3000L),
            createSample(1, pci = 101, timeMs = 1000L),
            createSample(2, pci = 101, timeMs = 2000L)
        )

        val summary = HandoverAnalyzer.analyzeTransitions(1L, samples, 500.0)
        assertEquals(1, summary.totalTransitions)
        assertEquals(101, summary.transitionEvents[0].fromPci)
        assertEquals(102, summary.transitionEvents[0].toPci)
    }

    @Test
    fun testMissingPciHandling() {
        val samples = listOf(
            createSample(1, pci = null),
            createSample(2, pci = 101),
            createSample(3, pci = null),
            createSample(4, pci = 102)
        )

        val summary = HandoverAnalyzer.analyzeTransitions(1L, samples, 500.0)
        assertEquals(1, summary.totalTransitions)
        assertEquals(101, summary.transitionEvents[0].fromPci)
        assertEquals(102, summary.transitionEvents[0].toPci)
    }
}
