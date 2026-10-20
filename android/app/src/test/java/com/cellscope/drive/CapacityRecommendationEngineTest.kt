package com.cellscope.drive

import com.cellscope.drive.analytics.CapacityRecommendationEngine
import com.cellscope.drive.analytics.CongestionAnalysisReport
import com.cellscope.drive.analytics.CongestionConfidenceLevel
import com.cellscope.drive.analytics.CoverageHoleRecord
import com.cellscope.drive.analytics.CoverageHoleSeverity
import com.cellscope.drive.analytics.HoleDataProvenance
import com.cellscope.drive.analytics.RecommendationCategory
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapacityRecommendationEngineTest {

    private val testSession = DriveSession(
        id = 1L,
        sessionName = "Test Session",
        startTimeMs = 1729245600000L
    )

    private fun createSample(
        id: Long,
        pci: Int = 101,
        rsrp: Int? = -85
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:0$id.000Z",
            timestampMs = 1729245600000L + id * 1000L,
            latitude = 23.8100 + id * 0.0001,
            longitude = 90.4120,
            altitudeM = 10.0,
            accuracyM = 3.0f,
            speedKmh = 25.0f,
            networkType = "LTE",
            operatorAlpha = "Grameenphone",
            operatorNumeric = "47001",
            servingPci = pci,
            tac = 24100,
            earfcn = 1600,
            bandwidthKhz = 20000,
            rsrpDbm = rsrp,
            rsrqDb = -11,
            rssiDbm = -60,
            cqi = 10,
            timingAdvance = 2,
            sinrDb = 15.0,
            isServing = true,
            isGpsValid = true
        )
    }

    @Test
    fun testRecommendsMacroSiteWhenSevereCoverageHolePresent() {
        val samples = (1L..10L).map { createSample(it, rsrp = -115) }
        val fakeHole = CoverageHoleRecord(
            id = 1,
            centerLat = 23.8125,
            centerLon = 90.4120,
            minLat = 23.8100,
            maxLat = 23.8150,
            minLon = 90.4120,
            maxLon = 90.4120,
            corridorLengthMeters = 550.0,
            estimatedAreaKm2 = 0.033,
            minRsrpDbm = -118,
            avgRsrpDbm = -114.0,
            avgRsrqDb = -16.0,
            sampleCount = 8,
            supportingPcis = listOf(101),
            severity = CoverageHoleSeverity.CRITICAL_DROPOUT,
            confidenceScore = 0.85f,
            provenance = HoleDataProvenance.MEASURED,
            isConfirmedOutage = false,
            engineeringEvidence = "Continuous weak corridor"
        )

        val report = CapacityRecommendationEngine.evaluateRecommendations(
            session = testSession,
            measurements = samples,
            transitions = emptyList(),
            coverageHoles = listOf(fakeHole)
        )

        val topRec = report.recommendations.firstOrNull()
        assertNotNull(topRec)
        assertEquals(RecommendationCategory.NEW_BASE_STATION, topRec?.category)
        assertEquals(1, topRec?.priorityRank)
        assertTrue(topRec?.missingEvidenceDisclosed?.any { it.contains("Digital Terrain") } == true)
        assertTrue(report.siteSuitability.overallSuitabilityScore > 0f)
    }

    @Test
    fun testRecommendsCapacityOffloadWhenCongested() {
        val samples = (1L..10L).map { createSample(it, rsrp = -78) }
        val congestion = CongestionAnalysisReport(
            confidenceLevel = CongestionConfidenceLevel.STRONG_CONGESTION_EVIDENCE,
            testCount = 3,
            avgDownloadMbps = 1.0,
            maxDownloadMbps = 1.5,
            avgUploadMbps = 0.5,
            avgLatencyMs = 210.0,
            minLatencyMs = 180L,
            maxLatencyMs = 240L,
            primaryServingPci = 101,
            radioQualityContext = "Pristine RF Context",
            primaryLimitingFactor = "Radio Access Network (RAN) Cell Congestion",
            alternativeExplanations = listOf("Multiple active users on sector"),
            missingInputs = listOf("PRB utilization counters"),
            engineeringEvidence = "Degraded throughput despite pristine RF"
        )

        val report = CapacityRecommendationEngine.evaluateRecommendations(
            session = testSession,
            measurements = samples,
            transitions = emptyList(),
            congestionReport = congestion
        )

        val categories = report.recommendations.map { it.category }
        assertTrue(
            categories.contains(RecommendationCategory.CELL_SPLITTING_SMALL_CELL) ||
            categories.contains(RecommendationCategory.ADDITIONAL_SPECTRUM_BANDWIDTH)
        )
        assertNotNull(report.siteSuitability.overallSuitabilityScore)
        assertTrue(report.siteSuitability.missingEnvironmentalInputs.isNotEmpty())
    }

    @Test
    fun testNominalRouteGeneratesRoutineMaintenance() {
        val samples = (1L..10L).map { createSample(it, rsrp = -82) }
        val report = CapacityRecommendationEngine.evaluateRecommendations(
            session = testSession,
            measurements = samples,
            transitions = emptyList()
        )

        assertEquals(1, report.recommendations.size)
        assertEquals(RecommendationCategory.ANTENNA_SECTOR_OPTIMIZATION, report.recommendations[0].category)
        assertEquals(5, report.recommendations[0].priorityRank)
    }
}
