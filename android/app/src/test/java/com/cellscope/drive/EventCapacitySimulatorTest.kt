package com.cellscope.drive

import com.cellscope.drive.analytics.CapacityScenarioSpec
import com.cellscope.drive.analytics.EventCapacitySimulator
import com.cellscope.drive.analytics.EventPreset
import com.cellscope.drive.analytics.EventTrafficProfile
import com.cellscope.drive.analytics.EventVenue
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventCapacitySimulatorTest {

    @Test
    fun testDefaultVenueInitialization() {
        val venue = EventVenue.defaultVenue()
        assertEquals("Selected Map Location", venue.name)
        assertEquals("Special Event Gathering", venue.eventName)
        assertEquals(23.8103, venue.centerLat, 0.0001)
        assertEquals(90.4125, venue.centerLon, 0.0001)
        assertEquals(500.0, venue.radiusMeters, 0.1)
        assertTrue(venue.contains(23.8103, 90.4125))
        assertFalse(venue.contains(24.3636, 88.6241))
    }

    @Test
    fun testUnmeasuredVenueReturnsNullCapacityAndDeficit() {
        // Without survey evidence, the engine refuses to invent fake macro capacity
        val spec = CapacityScenarioSpec(
            scenarioId = "BASE",
            title = "Unmeasured Baseline Load",
            additionalAttendees = 0,
            activeUserRatio = 0.20,
            trafficProfile = EventTrafficProfile.LIGHT
        )
        val result = EventCapacitySimulator.simulateScenario(
            venue = EventVenue.defaultVenue(),
            spec = spec,
            measurements = emptyList()
        )

        assertEquals(1200, result.totalAttendees)
        assertEquals(1200, result.concurrentActiveUsers)
        // 1200 users * 50 kbps DL / 1000 = 60 Mbps DL
        assertEquals(60.0, result.offeredTrafficDlMbps, 0.5)
        // Strictly null when unmeasured — no fake capacity
        assertNull(result.existingMacroCapacityMbps)
        assertNull(result.totalAvailableCapacityMbps)
        assertNull(result.capacityDeficitMbps)
        assertEquals("UNKNOWN_UNMEASURED", result.bottleneckSeverity)
        assertTrue(result.calculationDisclosures.any { it.contains("No local drive-test measurements exist") })
    }

    @Test
    fun testMeasuredVenueCalculatesHonestCapacityFromObservedSectors() {
        val venue = EventVenue.fromCoordinates(
            lat = 23.8103,
            lon = 90.4125,
            name = "Dhaka Central Park",
            radiusMeters = 500.0
        )
        // 2 distinct observed PCIs in survey
        val sample1 = LteMeasurementEntity(
            sessionId = 1L,
            timestampIso = "2026-03-20T10:00:00Z",
            timestampMs = 1774000800000L,
            latitude = 23.8103,
            longitude = 90.4125,
            altitudeM = 15.0,
            accuracyM = 3.5f,
            speedKmh = 0.0f,
            networkType = "LTE",
            operatorAlpha = "TestOp",
            operatorNumeric = "47001",
            servingPci = 101,
            tac = 10001,
            earfcn = 1800,
            bandwidthKhz = 20000,
            rsrpDbm = -85,
            rsrqDb = -10,
            rssiDbm = -65,
            cqi = 10,
            timingAdvance = 2,
            isServing = true,
            isGpsValid = true
        )
        val sample2 = sample1.copy(servingPci = 102, rsrpDbm = -90)

        val spec = CapacityScenarioSpec(
            scenarioId = "SCENARIO_A",
            title = "+10,000 Attendees Gathering",
            additionalAttendees = 10000,
            activeUserRatio = 0.20,
            trafficProfile = EventTrafficProfile.MODERATE // 250 kbps DL
        )
        val result = EventCapacitySimulator.simulateScenario(
            venue = venue,
            spec = spec,
            measurements = listOf(sample1, sample2)
        )

        assertEquals(11200, result.totalAttendees)
        // 1200 baseline + (10000 * 0.20) = 3200 active users
        assertEquals(3200, result.concurrentActiveUsers)
        // 3200 users * 250 kbps / 1000 = 800 Mbps DL
        assertEquals(800.0, result.offeredTrafficDlMbps, 0.5)
        // 2 observed sectors * 44 Mbps = 88.0 Mbps
        assertEquals(88.0, result.existingMacroCapacityMbps ?: 0.0, 0.5)
        assertEquals(88.0, result.totalAvailableCapacityMbps ?: 0.0, 0.5)
        // Deficit: 800 - 88 = 712 Mbps
        assertEquals(712.0, result.capacityDeficitMbps ?: 0.0, 0.5)
        assertTrue((result.predictedPrbUtilizationPct ?: 0.0) > 200.0)
        assertEquals("CRITICAL_BLACKOUT", result.bottleneckSeverity)
        // Bottleneck cells report observed PCIs, not fabricated names
        assertTrue(result.bottleneckCells.contains("Observed Sector PCI 101"))
        assertTrue(result.bottleneckCells.contains("Observed Sector PCI 102"))
    }

    @Test
    fun testScenarioWithTemporaryCowAndWifiOffload() {
        val venue = EventVenue.fromCoordinates(
            lat = 23.8103,
            lon = 90.4125,
            name = "Dhaka Central Park",
            radiusMeters = 500.0
        )
        val sample = LteMeasurementEntity(
            sessionId = 1L,
            timestampIso = "2026-03-20T10:00:00Z",
            timestampMs = 1774000800000L,
            latitude = 23.8103,
            longitude = 90.4125,
            altitudeM = 15.0,
            accuracyM = 3.5f,
            speedKmh = 0.0f,
            networkType = "LTE",
            operatorAlpha = "TestOp",
            operatorNumeric = "47001",
            servingPci = 101,
            tac = 10001,
            earfcn = 1800,
            bandwidthKhz = 20000,
            rsrpDbm = -85,
            rsrqDb = -10,
            rssiDbm = -65,
            cqi = 10,
            timingAdvance = 2,
            isServing = true,
            isGpsValid = true
        )
        // 2 sectors -> 88 Mbps macro
        val measurements = listOf(sample, sample.copy(servingPci = 102))

        val spec = CapacityScenarioSpec(
            scenarioId = "SCENARIO_C",
            title = "+20,000 Attendees + Temporary COW + Wi-Fi 6",
            additionalAttendees = 20000,
            activeUserRatio = 0.20,
            trafficProfile = EventTrafficProfile.MODERATE,
            hasTemporaryCow = true,
            hasWifiOffload = true,
            hasAntennaDowntiltOptimization = true
        )
        val result = EventCapacitySimulator.simulateScenario(
            venue = venue,
            spec = spec,
            measurements = measurements
        )

        assertEquals(21200, result.totalAttendees)
        assertEquals(5200, result.concurrentActiveUsers)
        // Raw DL was 1300 Mbps. With 40% Wi-Fi 6 offload -> 1300 * 0.6 = 780 Mbps
        assertEquals(780.0, result.offeredTrafficDlMbps, 0.5)

        // Existing macro DL: 88 Mbps. Down-tilt boost: +13.2 Mbps. COW boost: +350 Mbps.
        // Total available capacity: 88 + 13.2 + 350 = 451.2 Mbps
        assertEquals(451.2, result.totalAvailableCapacityMbps ?: 0.0, 0.5)
        assertTrue(result.recommendedInterventions.any { it.contains("COW") })
        assertTrue(result.recommendedInterventions.any { it.contains("Wi-Fi") })
    }

    @Test
    fun testMultiScenarioComparisonReport() {
        val venue = EventVenue.fromCoordinates(
            lat = 22.3569,
            lon = 91.7832,
            name = "Chittagong Stadium Area",
            eventName = "Sports Tournament",
            radiusMeters = 500.0,
            eventType = EventPreset.SPORTS_EVENT
        )
        val comparison = EventCapacitySimulator.runMultiScenarioComparison(venue = venue)
        assertNotNull(comparison.baseline)
        assertNotNull(comparison.scenarioA)
        assertNotNull(comparison.scenarioB)
        assertNotNull(comparison.scenarioC)
        assertEquals(venue, comparison.venue)
        assertTrue(comparison.executiveSummary.contains("Chittagong Stadium Area"))
        assertTrue(comparison.recommendedDecision.contains("COW") || comparison.recommendedDecision.contains("Cell on Wheels"))
    }

    @Test
    fun testArbitraryMapLocationPlanning() {
        val arbitraryVenue = EventVenue.fromCoordinates(
            lat = 24.3636,
            lon = 88.6241,
            name = "Rajshahi University Perimeter",
            radiusMeters = 400.0,
            eventType = EventPreset.CONFERENCE
        )
        assertEquals("Rajshahi University Perimeter", arbitraryVenue.name)
        assertEquals(24.3636, arbitraryVenue.centerLat, 0.0001)
        assertEquals(88.6241, arbitraryVenue.centerLon, 0.0001)
        assertEquals(400.0, arbitraryVenue.radiusMeters, 0.1)
        assertFalse(arbitraryVenue.isPreset)
        assertTrue(arbitraryVenue.contains(24.3636, 88.6241))
    }

    @Test
    fun testDisclosuresDisclaimAutomaticCellSplitting() {
        val spec = CapacityScenarioSpec(
            scenarioId = "CANDIDATE_TEST",
            title = "Heavy Load",
            additionalAttendees = 25000,
            activeUserRatio = 0.30,
            trafficProfile = EventTrafficProfile.HEAVY
        )
        val testVenue = EventVenue.fromCoordinates(
            lat = 23.8103,
            lon = 90.4125,
            name = "Candidate Venue",
            radiusMeters = 500.0
        )
        val result = EventCapacitySimulator.simulateScenario(venue = testVenue, spec = spec)
        // Verify candidate options explicitly disclose no automated physical cell splitting
        assertTrue(result.calculationDisclosures.any { it.contains("Candidate interventions are preliminary recommendations") })
        assertTrue(result.calculationDisclosures.any { it.contains("no automated physical cell splitting") })
    }

    @Test
    fun testPrbUtilizationNeverExceeds100PercentAndExposesDemandToCapacityRatio() {
        val venue = EventVenue.fromCoordinates(
            lat = 23.8103,
            lon = 90.4125,
            name = "Test Venue",
            radiusMeters = 500.0
        )
        val sample1 = LteMeasurementEntity(
            sessionId = 1L,
            timestampIso = "2026-03-20T10:00:00Z",
            timestampMs = 1774000800000L,
            latitude = 23.8103,
            longitude = 90.4125,
            servingPci = 101,
            isGpsValid = true
        )
        val spec = CapacityScenarioSpec(
            scenarioId = "HIGH_DEMAND",
            title = "Heavy Load",
            additionalAttendees = 20000,
            activeUserRatio = 0.20,
            trafficProfile = EventTrafficProfile.HEAVY
        )
        val result = EventCapacitySimulator.simulateScenario(
            venue = venue,
            spec = spec,
            measurements = listOf(sample1)
        )

        // Handset drive tests lack eNodeB scheduler counters: actual PRB must be null or <= 100%
        assertNull(result.actualPrbUtilizationPct)
        // Demand to capacity ratio reflects the overload without calling it PRB
        assertTrue(result.demandToCapacityRatioPct != null && result.demandToCapacityRatioPct!! > 100.0)
        // Capacity deficit is positive under overload
        assertTrue(result.capacityDeficitMbps != null && result.capacityDeficitMbps!! > 0.0)
        assertEquals(0.0, result.unusedCapacityMarginMbps ?: -1.0, 0.001)
    }

    @Test
    fun testSurplusCapacityYieldsMarginAndZeroDeficit() {
        val venue = EventVenue.fromCoordinates(
            lat = 23.8103,
            lon = 90.4125,
            name = "Test Venue",
            radiusMeters = 500.0
        )
        // 2 sectors -> 88 Mbps capacity
        val sample1 = LteMeasurementEntity(
            sessionId = 1L,
            timestampIso = "2026-03-20T10:00:00Z",
            timestampMs = 1774000800000L,
            latitude = 23.8103,
            longitude = 90.4125,
            servingPci = 101,
            isGpsValid = true
        )
        val sample2 = sample1.copy(servingPci = 102)

        // Very light load: 100 attendees * 0.10 active = 10 active users * 50 kbps = 0.5 Mbps
        val spec = CapacityScenarioSpec(
            scenarioId = "LIGHT_LOAD",
            title = "Small Meeting",
            additionalAttendees = 100,
            activeUserRatio = 0.10,
            trafficProfile = EventTrafficProfile.LIGHT
        )
        val result = EventCapacitySimulator.simulateScenario(
            venue = venue,
            spec = spec,
            measurements = listOf(sample1, sample2)
        )

        // Demand is 60.5 Mbps (including baseline) vs 88 Mbps capacity
        // Capacity exceeds demand -> Deficit must be 0.0, unused margin must be positive
        assertEquals(0.0, result.capacityDeficitMbps ?: -1.0, 0.001)
        assertTrue(result.unusedCapacityMarginMbps != null && result.unusedCapacityMarginMbps!! > 0.0)
        assertTrue(result.interventionOverlapNote.contains("Intervention capacities are non-additive"))
    }
}
