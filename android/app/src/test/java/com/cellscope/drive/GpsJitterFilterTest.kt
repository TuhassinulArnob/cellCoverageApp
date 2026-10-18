package com.cellscope.drive

import com.cellscope.drive.location.GpsFilterConfig
import com.cellscope.drive.location.GpsJitterFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class GpsJitterFilterTest {

    private lateinit var filter: GpsJitterFilter

    @Before
    fun setUp() {
        filter = GpsJitterFilter(
            GpsFilterConfig(
                stationarySpeedThresholdKmh = 1.8f,
                minAccuracyThresholdM = 30.0f,
                anchorRadiusMultiplier = 1.2f,
                minAnchorRadiusM = 8.0f,
                maxAnchorRadiusM = 25.0f,
                consecutiveMovementFixesRequired = 3,
                maxValidSpeedKmh = 200.0f,
                maxTimeGapMs = 15000L
            )
        )
    }

    @Test
    fun testIndoorStationaryJitterFiltering_182SamplesWandering() {
        // Simulates the exact user problem:
        // Lying still in a room, 182 GPS samples over ~3 minutes.
        // Positions wander randomly within ~9.8m radius.
        // Raw Doppler hardware speeds report intermittent spikes of 3.0 to 4.9 m/s (10.8 - 17.6 km/h).
        val centerLat = 23.8103
        val centerLon = 90.4125
        val baseTimeMs = 1700000000000L
        val rng = Random(42)

        var lastOutput = filter.processFix(
            lat = centerLat,
            lon = centerLon,
            altitudeM = 15.0,
            accuracyM = 9.8f,
            rawSpeedKmh = 0f,
            timestampMs = baseTimeMs
        )

        for (i in 1..181) {
            val timeMs = baseTimeMs + (i * 1000L)
            // Gaussian-like random jitter within ±8 meters
            // 1 deg lat is ~111320m -> 8m is ~0.000072 deg
            val dLat = (rng.nextDouble() - 0.5) * 0.00010
            val dLon = (rng.nextDouble() - 0.5) * 0.00010
            val lat = centerLat + dLat
            val lon = centerLon + dLon

            // Noisy hardware Doppler spikes up to 4.5 m/s (~16.2 km/h)
            val rawSpeedKmh = if (i % 7 == 0) (11.0f + rng.nextFloat() * 6.0f) else rng.nextFloat() * 2.0f

            lastOutput = filter.processFix(
                lat = lat,
                lon = lon,
                altitudeM = 15.0,
                accuracyM = 9.5f + (rng.nextFloat() * 1.0f),
                rawSpeedKmh = rawSpeedKmh,
                timestampMs = timeMs
            )
        }

        // Must remain classified as stationary throughout the entire indoor test
        assertTrue("Expected filter to detect stationary condition", lastOutput.isStationary)
        assertEquals("Speed should be suppressed to 0.0 km/h when stationary", 0.0f, lastOutput.speedKmh, 0.001f)
        assertEquals("Filtered distance must remain 0.0m for indoor jitter", 0.0, filter.cumulativeFilteredDistanceM, 0.001)
        assertEquals("Max filtered speed must remain 0.0 km/h for indoor jitter", 0.0f, filter.maxFilteredSpeedKmh, 0.001f)
    }

    @Test
    fun testGenuineWalkingMovement_ConfirmedAndAccumulated() {
        // Walking at 1.0 m/s (~3.6 km/h) along latitude:
        // ~1.0m per second = ~0.000009 deg lat per second
        val baseLat = 23.8103
        val baseLon = 90.4125
        val baseTimeMs = 1700000000000L

        // Initial anchor fix
        filter.processFix(baseLat, baseLon, 10.0, 4.0f, 0.0f, baseTimeMs)

        var lastOutput = filter.processFix(baseLat, baseLon, 10.0, 4.0f, 0.0f, baseTimeMs)

        // Walk for 30 seconds (30 meters total displacement)
        for (i in 1..30) {
            val timeMs = baseTimeMs + (i * 1000L)
            val lat = baseLat + (i * 0.000009) // ~1.0m per second north
            lastOutput = filter.processFix(
                lat = lat,
                lon = baseLon,
                altitudeM = 10.0,
                accuracyM = 4.0f,
                rawSpeedKmh = 3.6f, // 3.6 km/h
                timestampMs = timeMs
            )
        }

        // After moving beyond deadband radius and confirming 3 fixes, movement must be confirmed
        assertFalse("Genuine walk should transition to moving", lastOutput.isStationary)
        assertTrue("Filtered distance should accumulate displacement (> 15m)", filter.cumulativeFilteredDistanceM > 15.0)
        assertTrue("Filtered speed should reflect walking speed (~3.6 km/h)", lastOutput.speedKmh > 2.0f)
        assertTrue("Max filtered speed should be recorded", filter.maxFilteredSpeedKmh > 2.0f)
    }

    @Test
    fun testLargeImpossibleJumpRejection() {
        val baseLat = 23.8103
        val baseLon = 90.4125
        val baseTimeMs = 1700000000000L

        filter.processFix(baseLat, baseLon, 10.0, 5.0f, 0.0f, baseTimeMs)

        // 1 second later, sudden coordinate jump of 500m (~1800 km/h)
        // 500m ~ 0.0045 deg lat
        val jumpLat = baseLat + 0.0045
        val output = filter.processFix(
            lat = jumpLat,
            lon = baseLon,
            altitudeM = 10.0,
            accuracyM = 5.0f,
            rawSpeedKmh = 0.0f,
            timestampMs = baseTimeMs + 1000L
        )

        assertTrue(output.filterReason.contains("Impossible speed jump"))
        assertEquals(0.0, filter.cumulativeFilteredDistanceM, 0.001)
        assertEquals(0.0f, filter.maxFilteredSpeedKmh, 0.001f)
    }

    @Test
    fun testPoorAccuracyFixDiscarded() {
        val baseLat = 23.8103
        val baseLon = 90.4125
        val baseTimeMs = 1700000000000L

        filter.processFix(baseLat, baseLon, 10.0, 5.0f, 0.0f, baseTimeMs)

        // Fix with 45m accuracy (threshold is 30m)
        val output = filter.processFix(
            lat = baseLat + 0.0001,
            lon = baseLon,
            altitudeM = 10.0,
            accuracyM = 45.0f,
            rawSpeedKmh = 10.0f,
            timestampMs = baseTimeMs + 1000L
        )

        assertTrue(output.filterReason.contains("Accuracy") && output.filterReason.contains("exceeds threshold"))
        assertEquals(0.0, filter.cumulativeFilteredDistanceM, 0.001)
    }

    @Test
    fun testDuplicateAndOutOfOrderTimestampsRejected() {
        val baseLat = 23.8103
        val baseLon = 90.4125
        val baseTimeMs = 1700000000000L

        filter.processFix(baseLat, baseLon, 10.0, 5.0f, 0.0f, baseTimeMs)

        // Duplicate timestamp (dt = 0)
        val dupOutput = filter.processFix(
            lat = baseLat + 0.00001,
            lon = baseLon,
            altitudeM = 10.0,
            accuracyM = 5.0f,
            rawSpeedKmh = 0.0f,
            timestampMs = baseTimeMs
        )
        assertTrue(dupOutput.filterReason.contains("Duplicate or out-of-order timestamp"))

        // Out-of-order timestamp (dt < 0)
        val oldOutput = filter.processFix(
            lat = baseLat + 0.00001,
            lon = baseLon,
            altitudeM = 10.0,
            accuracyM = 5.0f,
            rawSpeedKmh = 0.0f,
            timestampMs = baseTimeMs - 5000L
        )
        assertTrue(oldOutput.filterReason.contains("Duplicate or out-of-order timestamp"))
    }

    @Test
    fun testTimeGapResetsAnchorWithoutSpeedSpike() {
        val baseLat = 23.8103
        val baseLon = 90.4125
        val baseTimeMs = 1700000000000L

        filter.processFix(baseLat, baseLon, 10.0, 5.0f, 0.0f, baseTimeMs)

        // 30 seconds gap (e.g. app paused or tunnel), then fix 100m away
        val newLat = baseLat + 0.0009
        val output = filter.processFix(
            lat = newLat,
            lon = baseLon,
            altitudeM = 10.0,
            accuracyM = 5.0f,
            rawSpeedKmh = 0.0f,
            timestampMs = baseTimeMs + 30000L
        )

        assertTrue(output.filterReason.contains("Time gap exceeds 15s"))
        // Should not treat 100m in 30s as a speed spike or false accumulated distance
        assertEquals(0.0, filter.cumulativeFilteredDistanceM, 0.001)
        assertEquals(0.0f, filter.maxFilteredSpeedKmh, 0.001f)
    }
}
