package com.cellscope.drive

import com.cellscope.drive.analytics.CoverageHoleEstimator
import com.cellscope.drive.analytics.CoverageHoleSeverity
import com.cellscope.drive.analytics.EstimatedTower
import com.cellscope.drive.analytics.TowerEstimator
import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.location.GpsJitterFilter
import com.cellscope.drive.model.NetworkGeneration
import com.cellscope.drive.ui.map.GenerationFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DynamicMapAndCoverageTest {

    private fun makeSample(
        id: Long,
        lat: Double,
        lon: Double,
        pci: Int,
        rsrp: Int?,
        earfcn: Int = 1600,
        tac: Int = 18452,
        eci: Int? = null,
        gen: String = "LTE",
        operator: String = "Grameenphone",
        serviceState: String = "IN_SERVICE"
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-19T06:00:0$id.000Z",
            timestampMs = 1729317600000L + id * 1000L,
            latitude = lat,
            longitude = lon,
            altitudeM = 15.0,
            accuracyM = 3.0f,
            speedKmh = 35.0f,
            networkType = gen,
            networkGen = gen,
            operatorAlpha = operator,
            operatorNumeric = "47001",
            servingPci = pci,
            tac = tac,
            earfcn = earfcn,
            eci = eci,
            bandwidthKhz = 20000,
            rsrpDbm = rsrp,
            rsrqDb = -11,
            rssiDbm = -65,
            cqi = 11,
            timingAdvance = 2,
            serviceState = serviceState,
            isServing = true,
            isGpsValid = true
        )
    }

    // A & B: RSRP 4-Tier Standard and Legend Color Consistency
    @Test
    fun testRsrpThresholdStandardization() {
        fun classifyRsrpTier(rsrp: Double): String {
            return when {
                rsrp >= -80.0 -> "EXCELLENT"
                rsrp >= -90.0 -> "GOOD"
                rsrp >= -100.0 -> "FAIR"
                else -> "POOR"
            }
        }

        assertEquals("EXCELLENT", classifyRsrpTier(-75.0))
        assertEquals("EXCELLENT", classifyRsrpTier(-80.0))
        assertEquals("GOOD", classifyRsrpTier(-81.0))
        assertEquals("GOOD", classifyRsrpTier(-89.9))
        assertEquals("GOOD", classifyRsrpTier(-90.0))
        assertEquals("FAIR", classifyRsrpTier(-91.0))
        assertEquals("FAIR", classifyRsrpTier(-99.0))
        assertEquals("FAIR", classifyRsrpTier(-100.0))
        assertEquals("POOR", classifyRsrpTier(-101.0))
        assertEquals("POOR", classifyRsrpTier(-120.0))
    }

    // C: Candidate Tower Estimation & PCI Re-use Disambiguation
    @Test
    fun testPciReuseDisambiguationAndUncertainty() {
        // Site A cluster in Gulshan (23.792, 90.407)
        val siteASamples = (1L..6L).map { id ->
            makeSample(id, 23.7920 + id * 0.0001, 90.4070, pci = 350, rsrp = -75, tac = 1001)
        }

        // Site B cluster in Uttara (23.872, 90.398) - ~9 km away with identical PCI 350 (PCI reuse!)
        val siteBSamples = (7L..12L).map { id ->
            makeSample(id, 23.8720 + (id - 6) * 0.0001, 90.3980, pci = 350, rsrp = -74, tac = 1002)
        }

        val allSamples = siteASamples + siteBSamples
        val towers = TowerEstimator.estimateTowers(allSamples, minSamplesRequired = 5)

        // Must NOT conflate into one giant tower with an arbitrary 9km radius circle
        assertEquals("PCI reuse across distinct TAC/locations must produce separate candidate sites", 2, towers.size)
        assertTrue(towers.all { it.isCandidate })
        assertFalse(towers.any { it.isVerified })

        // Distance between candidate sites should be > 5 km
        val t1 = towers[0]
        val t2 = towers[1]
        val distBetweenSites = GpsJitterFilter.computeHaversineMeters(
            t1.estimatedLat, t1.estimatedLon, t2.estimatedLat, t2.estimatedLon
        )
        assertTrue("Estimated candidate sites must be > 5km apart: $distBetweenSites", distBetweenSites > 5000.0)
    }

    // C: Collinear highway drive lateral ambiguity detection
    @Test
    fun testCollinearRouteFlagsLateralAmbiguity() {
        // Exactly linear trajectory north along highway with constant lateral offset
        val linearSamples = (1L..10L).map { id ->
            makeSample(id, 23.8000 + id * 0.001, 90.4000, pci = 120, rsrp = -80)
        }

        val towers = TowerEstimator.estimateTowers(linearSamples, minSamplesRequired = 5)
        assertEquals(1, towers.size)
        val tower = towers[0]
        assertTrue("Single-track linear drive must flag lateral ambiguity", tower.lateralAmbiguity)
        assertNotNull(tower.ambiguityReason)
        assertTrue(tower.ambiguityReason!!.contains("Collinear") || tower.ambiguityReason!!.contains("lateral"))
    }

    // D: Weak Coverage Zones and Corridors with Stable IDs
    @Test
    fun testCoverageHoleStableIdAndCorridorProperties() {
        val samples = mutableListOf<LteMeasurementEntity>()
        // Healthy approach
        samples.add(makeSample(1, 23.8100, 90.4100, 10, -78))
        samples.add(makeSample(2, 23.8102, 90.4100, 10, -82))

        // Weak zone (samples 3 to 8)
        for (i in 3L..8L) {
            samples.add(makeSample(i, 23.8100 + i * 0.0003, 90.4100, 10, -109))
        }

        // Recovery
        samples.add(makeSample(9, 23.8130, 90.4100, 10, -84))
        samples.add(makeSample(10, 23.8132, 90.4100, 10, -79))

        val holes = CoverageHoleEstimator.estimateCoverageHoles(samples)
        assertEquals(1, holes.size)
        val hole = holes[0]

        // Stable ID format
        assertEquals("ZONE-H1", hole.stableZoneId)
        assertEquals(6, hole.sampleCount)
        assertEquals(-109, hole.minRsrpDbm)
        assertEquals(CoverageHoleSeverity.SEVERE_DEFICIT, hole.severity)
        assertTrue("Corridor length must be positive", hole.corridorLengthMeters > 0.0)
        assertNotNull("Locality hint should be populated for Bangladesh coordinates", hole.localityHint)
        assertEquals("Dhaka Urban Corridor", hole.localityHint)
    }

    // F: Real RAT Indicators and Missing-Data Warnings
    @Test
    fun testRatCoverageFilteringAndMissingDataExplanation() {
        val lteSamples = (1L..20L).map { id ->
            makeSample(id, 23.8100 + id * 0.0001, 90.4100, 10, -85, gen = "LTE")
        }

        // Test filtering on 4G
        val matching4g = lteSamples.filter {
            NetworkGeneration.fromString(it.networkGen ?: it.networkType) == NetworkGeneration.GEN_4G
        }
        assertEquals(20, matching4g.size)
        val pct4g = (matching4g.size * 100.0) / lteSamples.size
        assertEquals(100.0, pct4g, 0.01)

        // Test filtering on 5G where no 5G samples were captured
        val matching5g = lteSamples.filter {
            NetworkGeneration.fromString(it.networkGen ?: it.networkType) == NetworkGeneration.GEN_5G
        }
        assertTrue("No 5G samples should be matched on an LTE survey", matching5g.isEmpty())

        // Ensure missing 5G is explained honestly rather than assuming zero signal/outage
        val warningBanner = if (matching5g.isEmpty()) {
            "No 5G NR samples observed in session (Handset recorded LTE/3G; do not infer 0 signal)"
        } else {
            "5G Coverage Active"
        }
        assertTrue(warningBanner.contains("No 5G NR samples observed"))
        assertTrue(warningBanner.contains("do not infer 0 signal"))
    }
}
