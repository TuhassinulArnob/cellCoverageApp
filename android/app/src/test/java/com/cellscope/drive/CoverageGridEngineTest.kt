package com.cellscope.drive

import com.cellscope.drive.analytics.CoverageGridEngine
import com.cellscope.drive.analytics.MapMetricLayer
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageGridEngineTest {

    private fun createSample(
        id: Long,
        lat: Double,
        lon: Double,
        rsrp: Int?,
        rsrq: Int? = -11,
        sinr: Double? = 12.0,
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
            rsrqDb = rsrq,
            rssiDbm = -60,
            cqi = 12,
            timingAdvance = 2,
            isServing = true,
            handoverTrigger = 0,
            sinrDb = sinr,
            isGpsValid = true
        )
    }

    @Test
    fun testCoverageSurfaceComputation() {
        // Grid of 4 points in Dhaka
        val samples = listOf(
            createSample(1, 23.8100, 90.4120, -78, -9, 16.0), // Excellent
            createSample(2, 23.8102, 90.4120, -82, -11, 12.0), // Good
            createSample(3, 23.8104, 90.4120, -95, -14, 6.0),  // Fair
            createSample(4, 23.8106, 90.4120, -112, -18, -2.0) // Poor
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
            assertNotNull(cell.interpolatedRsrq)
            assertNotNull(cell.interpolatedSinr)
        }

        // Check weak coverage proportion
        assertTrue(surface.weakCoveragePercent > 0.0f)
    }

    @Test
    fun testDirectObservationVsInterpolatedFlag() {
        val samples = listOf(
            createSample(1, 23.8100, 90.4120, -85)
        )

        val surface = CoverageGridEngine.computeCoverageSurface(
            measurements = samples,
            gridResolutionMeters = 20.0,
            maxInterpolationRadiusMeters = 60.0
        )

        assertTrue(surface.cells.isNotEmpty())
        // At least one cell closest to the exact observation should be flagged as isDirectlyMeasured
        val directCell = surface.cells.find { it.isDirectlyMeasured }
        assertNotNull(directCell)
        assertTrue(directCell!!.confidenceScore > 0.5f)
    }

    @Test
    fun testNullMetricSafetyNeverFakesZero() {
        // Samples with null SINR
        val samples = listOf(
            createSample(1, 23.8100, 90.4120, -85, -12, sinr = null),
            createSample(2, 23.8102, 90.4120, -88, -13, sinr = null)
        )

        val surface = CoverageGridEngine.computeCoverageSurface(
            measurements = samples,
            gridResolutionMeters = 25.0,
            maxInterpolationRadiusMeters = 60.0
        )

        assertTrue(surface.cells.isNotEmpty())
        for (cell in surface.cells) {
            assertNotNull(cell.interpolatedRsrp)
            // SINR must be null, never converted to fake 0.0!
            assertNull(cell.interpolatedSinr)
            assertNull(cell.getMetricValue(MapMetricLayer.SINR))
        }
    }

    @Test
    fun testSparseRouteDoesNotOverExtrapolate() {
        // Two points separated by 5 km (beyond max interpolation radius 120m)
        val samples = listOf(
            createSample(1, 23.8000, 90.4000, -85),
            createSample(2, 23.8500, 90.4500, -90)
        )

        val surface = CoverageGridEngine.computeCoverageSurface(
            measurements = samples,
            gridResolutionMeters = 35.0,
            maxInterpolationRadiusMeters = 100.0
        )

        // Midpoint between the two sites (~3.5km away) should NOT contain any interpolated cell
        val midLat = (23.8000 + 23.8500) / 2.0
        val midLon = (90.4000 + 90.4500) / 2.0

        val cellNearMid = surface.cells.find {
            kotlin.math.abs(it.lat - midLat) < 0.005 && kotlin.math.abs(it.lon - midLon) < 0.005
        }
        assertNull("Grid engine must not extrapolate across large unmeasured gap", cellNearMid)
    }

    @Test
    fun testDuplicateCoordinatesHandling() {
        // Stationary samples at identical location
        val samples = listOf(
            createSample(1, 23.8100, 90.4120, -80),
            createSample(2, 23.8100, 90.4120, -84),
            createSample(3, 23.8100, 90.4120, -82)
        )

        val surface = CoverageGridEngine.computeCoverageSurface(
            measurements = samples,
            gridResolutionMeters = 25.0,
            maxInterpolationRadiusMeters = 60.0
        )

        assertTrue(surface.cells.isNotEmpty())
        val center = surface.cells.minByOrNull {
            kotlin.math.abs(it.lat - 23.8100) + kotlin.math.abs(it.lon - 90.4120)
        }
        assertNotNull(center)
        assertEquals(3, center!!.sampleCount)
        assertEquals(-82.0, center.interpolatedRsrp!!, 0.5)
    }

    @Test
    fun testEmptyMeasurementsHandling() {
        val emptySurface = CoverageGridEngine.computeCoverageSurface(emptyList())
        assertEquals(0, emptySurface.cells.size)
        assertEquals(0.0, emptySurface.totalMeasuredAreaKm2, 0.001)
    }
}
