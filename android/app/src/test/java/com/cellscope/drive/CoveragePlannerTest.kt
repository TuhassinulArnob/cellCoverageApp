package com.cellscope.drive

import com.cellscope.drive.analytics.CoveragePlanner
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoveragePlannerTest {

    private fun createSample(
        id: Long,
        rsrp: Int,
        rsrq: Int = -11
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-17T12:00:0$id.000Z",
            timestampMs = 1000L * id,
            latitude = 23.810 + (id * 0.0002),
            longitude = 90.412 + (id * 0.0002),
            altitudeM = 10.0,
            accuracyM = 3.0f,
            speedKmh = 25.0f,
            networkType = "LTE",
            operatorAlpha = "Grameenphone",
            operatorNumeric = "47001",
            servingPci = 288,
            tac = 18452,
            earfcn = 1600,
            bandwidthKhz = 20000,
            rsrpDbm = rsrp,
            rsrqDb = rsrq,
            rssiDbm = -60,
            cqi = 12,
            timingAdvance = 2,
            isServing = true,
            handoverTrigger = 0,
            isGpsValid = true
        )
    }

    @Test
    fun testDeepCoverageHoleDetection() {
        val samples = listOf(
            createSample(1, rsrp = -85),
            createSample(2, rsrp = -88),
            createSample(3, rsrp = -118), // Weak zone start
            createSample(4, rsrp = -120),
            createSample(5, rsrp = -119), // Weak zone end
            createSample(6, rsrp = -86)
        )

        val weakZones = CoveragePlanner.identifyWeakZones(samples, rsrpThresholdDbm = -105)

        assertEquals(1, weakZones.size)
        val zone = weakZones.first()
        assertEquals(3, zone.sampleCount)
        assertEquals(-120, zone.minRsrp)
        assertTrue(zone.diagnosis.contains("Deep Coverage Hole"))
        assertTrue(zone.remediationOptions.any { it.category == "SMALL_CELL" })
    }

    @Test
    fun testPilotPollutionDetection() {
        val samples = listOf(
            createSample(1, rsrp = -92, rsrq = -18),
            createSample(2, rsrp = -94, rsrq = -19),
            createSample(3, rsrp = -93, rsrq = -18)
        )

        val weakZones = CoveragePlanner.identifyWeakZones(samples, rsrqThresholdDb = -16)

        assertEquals(1, weakZones.size)
        val zone = weakZones.first()
        assertTrue(zone.diagnosis.contains("Pilot Pollution"))
        assertTrue(zone.remediationOptions.any { it.category == "ANTENNA_OPTIMIZATION" })
    }
}
