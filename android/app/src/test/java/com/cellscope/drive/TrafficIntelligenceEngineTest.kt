package com.cellscope.drive

import com.cellscope.drive.analytics.TrafficIntelligenceEngine
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.*
import org.junit.Test

class TrafficIntelligenceEngineTest {

    private fun createSample(
        id: Long,
        timestampMs: Long,
        lat: Double,
        lon: Double,
        rsrp: Int = -85,
        rsrq: Int = -10,
        sinr: Double? = 15.0
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:00.000Z",
            timestampMs = timestampMs,
            latitude = lat,
            longitude = lon,
            altitudeM = 10.0,
            accuracyM = 3.0f,
            speedKmh = 25f,
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
            sinrDb = sinr,
            cqi = 12,
            timingAdvance = 1,
            isServing = true,
            handoverTrigger = 0,
            servingRat = "4G"
        )
    }

    @Test
    fun testStationaryRouteClassification() {
        // Samples within 50 meters
        val samples = (1..10).map {
            createSample(
                it.toLong(),
                1000L * it,
                lat = 23.8100 + it * 0.00001,
                lon = 90.4100 + it * 0.00001
            )
        }

        val summary = TrafficIntelligenceEngine.analyzeTrafficIntelligence(samples)
        assertEquals(TrafficIntelligenceEngine.RoutePatternClassification.STATIONARY_OR_LOCALIZED, summary.routeClassification)
        assertTrue(summary.routeExtentMeters < 300.0)
    }

    @Test
    fun testBroadDrivingRouteClassificationAndCautionNotice() {
        // Broad drive over ~5 km
        val samples = listOf(
            createSample(1, 1000L, lat = 23.8100, lon = 90.4100),
            createSample(2, 60000L, lat = 23.8200, lon = 90.4150),
            createSample(3, 120000L, lat = 23.8350, lon = 90.4200),
            createSample(4, 180000L, lat = 23.8500, lon = 90.4300),
            createSample(5, 240000L, lat = 23.8600, lon = 90.4400) // ~6 km
        )

        val summary = TrafficIntelligenceEngine.analyzeTrafficIntelligence(samples)
        assertEquals(TrafficIntelligenceEngine.RoutePatternClassification.BROAD_DRIVING_ROUTE, summary.routeClassification)
        assertTrue(summary.routeExtentMeters > 3000.0)
        assertTrue(summary.trafficInterpretationNotice.contains("Broad Driving Route detected"))
        assertTrue(summary.trafficInterpretationNotice.contains("Do NOT conflate route progression into a single-location diurnal traffic curve"))
    }

    @Test
    fun testNoZeroFillingMissingHours() {
        // Two bursts: 10:00 (hour 0) and 14:00 (hour 4, 4 hours later). No samples at 11:00, 12:00, 13:00.
        val baseMs = 1700000000000L
        val hourMs = 3600000L

        val samples = listOf(
            createSample(1, baseMs, 23.81, 90.41, rsrp = -80),
            createSample(2, baseMs + 60000L, 23.81, 90.41, rsrp = -82),
            // Hour 4
            createSample(3, baseMs + 4 * hourMs, 23.81, 90.41, rsrp = -95),
            createSample(4, baseMs + 4 * hourMs + 60000L, 23.81, 90.41, rsrp = -97)
        )

        val bins = TrafficIntelligenceEngine.computeTimeBins(samples, binMinutes = 60)
        // Must contain ONLY the 2 non-empty hours, NEVER 5 hours with zero-filled records!
        assertEquals("Must NOT zero-fill empty hours", 2, bins.size)
        assertEquals(-80.0, bins[0].medianRsrp!!, 2.0)
        assertEquals(-95.0, bins[1].medianRsrp!!, 2.0)
        assertFalse(bins[0].isThroughputMeasured)
        assertEquals("Radio Quality Variation (Throughput Unmeasured)", bins[0].metricCategoryLabel)
    }

    @Test
    fun testTrafficFeasibilityBroadTransitAssessment() {
        val samples = listOf(
            createSample(1, 1000L, lat = 23.8100, lon = 90.4100),
            createSample(2, 60000L, lat = 23.8200, lon = 90.4150),
            createSample(3, 120000L, lat = 23.8350, lon = 90.4200),
            createSample(4, 180000L, lat = 23.8500, lon = 90.4300),
            createSample(5, 240000L, lat = 23.8600, lon = 90.4400)
        )
        val summary = TrafficIntelligenceEngine.analyzeTrafficIntelligence(samples)
        val feasibility = summary.feasibilityAssessment

        assertEquals("BROAD_TRANSIT_UNSUITABLE", feasibility.feasibilityLevel)
        assertFalse(feasibility.isFeasibleForTrafficAnalysis)
        assertTrue(feasibility.feasibilitySummary.contains("broad transit route"))
        assertTrue(feasibility.missingPrerequisites.isNotEmpty())
    }

    @Test
    fun testTrafficFeasibilityStationaryWithoutThroughput() {
        val samples = (1..10).map {
            createSample(it.toLong(), 1000L * it, lat = 23.8100 + it * 0.00001, lon = 90.4100 + it * 0.00001)
        }
        val summary = TrafficIntelligenceEngine.analyzeTrafficIntelligence(samples)
        val feasibility = summary.feasibilityAssessment

        assertEquals("RF_VARIATION_ONLY", feasibility.feasibilityLevel)
        assertFalse(feasibility.isFeasibleForTrafficAnalysis)
        assertTrue(feasibility.feasibilitySummary.contains("temporal RF quality tracking"))
        assertTrue(feasibility.missingPrerequisites.contains("Active download/upload throughput tests"))
    }
}

