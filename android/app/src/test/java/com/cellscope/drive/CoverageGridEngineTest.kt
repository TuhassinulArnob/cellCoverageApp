package com.cellscope.drive

import com.cellscope.drive.analytics.CoverageGridEngine
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageGridEngineTest {

    private fun createSample(
        id: Long,
        lat: Double,
        lon: Double,
        rsrp: Int,
        pci: Int = 288
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-17T12:00:0$id.000Z",
            timestampMs = 1000L * id,
            latitude = lat,
            longitude = lon,
            altitudeM = 10.0,
            accuracyM = 3.0f,
            speedKmh = 25.0f,
            networkType = "LTE",
            operatorAlpha = "Grameenphone",
            operatorNumeric = "47001",
            servingPci = pci,
            tac = 18452,
            earfcn = 1600,
            bandwidthKhz = 20000,
            rsrpDbm = rsrp,
            rsrqDb = -11,
            rssiDbm = -60,
            cqi = 12,
            timingAdvance = 2,
            isServing = true,
            handoverTrigger = 0,
            isGpsValid = true
        )
    }

    @Test
    fun testCoverageSurfaceComputation() {
        // Grid of 4 points in Dhaka
        val samples = listOf(
            createSample(1, 23.8100, 90.4120, -78), // Excellent
            createSample(2, 23.8102, 90.4120, -82), // Good
            createSample(3, 23.8104, 90.4120, -95), // Fair
            createSample(4, 23.8106, 90.4120, -112) // Poor
        )

        val surface = CoverageGridEngine.computeCoverageSurface(
            measurements = samples,
            gridResolutionMeters = 25.0,
            maxInterpolationRadiusMeters = 80.0
        )

        assertTrue(surface.cells.isNotEmpty())
        assertTrue(surface.totalMeasuredAreaKm2 > 0.0)
        assertNotNull(surface.avgRsrp)
        assertTrue(surface.avgRsrp!! < -80.0 && surface.avgRsrp!! > -110.0)

        // Confidence score should be between 0 and 1
        for (cell in surface.cells) {
            assertTrue(cell.confidenceScore in 0.0f..1.0f)
            assertNotNull(cell.interpolatedRsrp)
        }

        // Check weak coverage proportion
        assertTrue(surface.weakCoveragePercent > 0.0f)
    }

    @Test
    fun testEmptyMeasurementsHandling() {
        val emptySurface = CoverageGridEngine.computeCoverageSurface(emptyList())
        assertEquals(0, emptySurface.cells.size)
        assertEquals(0.0, emptySurface.totalMeasuredAreaKm2, 0.001)
    }
}
