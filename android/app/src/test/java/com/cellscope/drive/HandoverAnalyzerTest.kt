package com.cellscope.drive

import com.cellscope.drive.analytics.HandoverAnalyzer
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoverAnalyzerTest {

    private fun createSample(
        id: Long,
        pci: Int,
        tac: Int = 18452,
        rsrp: Int = -85,
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
            earfcn = 1600,
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
}
