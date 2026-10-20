package com.cellscope.drive

import com.cellscope.drive.analytics.CoverageHoleEstimator
import com.cellscope.drive.analytics.CoverageHoleSeverity
import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageHoleEstimatorTest {

    private fun createSample(
        id: Long,
        lat: Double,
        lon: Double,
        rsrp: Int?,
        serviceState: String = "IN_SERVICE",
        pci: Int = 101
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:0$id.000Z",
            timestampMs = 1729245600000L + id * 1000L,
            latitude = lat,
            longitude = lon,
            altitudeM = 12.0,
            accuracyM = 3.5f,
            speedKmh = 30.0f,
            networkType = "LTE",
            operatorAlpha = "Grameenphone",
            operatorNumeric = "47001",
            servingPci = pci,
            tac = 24100,
            earfcn = 1600,
            bandwidthKhz = 20000,
            rsrpDbm = rsrp,
            rsrqDb = -12,
            rssiDbm = -65,
            cqi = 10,
            timingAdvance = 2,
            serviceState = serviceState,
            isServing = true,
            isGpsValid = true
        )
    }

    @Test
    fun testNoHolesDetectedWhenSignalsHealthy() {
        val samples = (1L..20L).map { id ->
            createSample(id, lat = 23.8100 + id * 0.0001, lon = 90.4120, rsrp = -82)
        }
        val holes = CoverageHoleEstimator.estimateCoverageHoles(samples)
        assertTrue(holes.isEmpty())
    }

    @Test
    fun testWeakCoverageCorridorDetectedAndGrouped() {
        // Healthy approach, then 6 weak samples (< -105 dBm), then recovery
        val samples = mutableListOf<LteMeasurementEntity>()
        samples.add(createSample(1, 23.8100, 90.4120, rsrp = -80))
        samples.add(createSample(2, 23.8102, 90.4120, rsrp = -85))

        // Weak corridor (points 3 to 8)
        for (i in 3L..8L) {
            samples.add(createSample(i, 23.8100 + i * 0.0002, 90.4120, rsrp = -112))
        }

        // Recovery
        samples.add(createSample(9, 23.8120, 90.4120, rsrp = -84))
        samples.add(createSample(10, 23.8122, 90.4120, rsrp = -80))

        val holes = CoverageHoleEstimator.estimateCoverageHoles(samples)
        assertEquals(1, holes.size)
        val hole = holes[0]
        assertEquals("ZONE-H1", hole.stableZoneId)
        assertEquals(6, hole.sampleCount)
        assertEquals(-112, hole.minRsrpDbm)
        assertFalse(hole.isConfirmedOutage)
        assertTrue(hole.corridorLengthMeters > 0.0)
        assertEquals(listOf(101), hole.supportingPcis)
    }

    @Test
    fun testConfirmedOutageFlaggedWhenOutOfServiceOrVeryWeak() {
        val samples = (1L..5L).map { id ->
            createSample(id, lat = 23.8100 + id * 0.0002, lon = 90.4120, rsrp = -118, serviceState = "OUT_OF_SERVICE")
        }
        val holes = CoverageHoleEstimator.estimateCoverageHoles(samples)
        assertEquals(1, holes.size)
        val hole = holes[0]
        assertTrue(hole.isConfirmedOutage)
        assertEquals(CoverageHoleSeverity.CRITICAL_DROPOUT, hole.severity)
    }

    @Test
    fun testTransitionsCorrelatedWithHole() {
        val samples = (1L..5L).map { id ->
            createSample(id, lat = 23.8100 + id * 0.0001, lon = 90.4120, rsrp = -110)
        }
        val transition = CellTransitionEntity(
            id = 1L,
            sessionId = 1L,
            timestampMs = 1729245602000L,
            timestampIso = "2026-10-18T10:00:02.000Z",
            latitude = 23.8102,
            longitude = 90.4120,
            fromPci = 101,
            toPci = 102,
            fromRsrp = -110,
            toRsrp = -108,
            eventType = "CONFIRMED_HANDOVER",
            confidenceScore = 0.8f,
            details = "Serving cell changed at edge"
        )
        val holes = CoverageHoleEstimator.estimateCoverageHoles(samples, transitions = listOf(transition))
        assertEquals(1, holes.size)
        assertTrue(holes[0].engineeringEvidence.contains("transition(s) observed"))
    }
}
