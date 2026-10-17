package com.cellscope.drive

import com.cellscope.drive.location.LocationTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationValidationTest {

    @Test
    fun testValidDhakaCoordinates() {
        val valid = LocationTracker.validateCoordinates(23.8103, 90.4125, 4.2f)
        assertTrue(valid)
    }

    @Test
    fun testNullIslandRejection() {
        val nullIsland = LocationTracker.validateCoordinates(0.0, 0.0, 5.0f)
        assertFalse(nullIsland)

        val nearZero = LocationTracker.validateCoordinates(0.00001, 0.00001, 5.0f)
        assertFalse(nearZero)
    }

    @Test
    fun testOutOfBoundsCoordinates() {
        val latTooHigh = LocationTracker.validateCoordinates(95.0, 90.0)
        assertFalse(latTooHigh)

        val lonTooLow = LocationTracker.validateCoordinates(23.0, -185.0)
        assertFalse(lonTooLow)
    }

    @Test
    fun testHighAccuracyDegradation() {
        // Horizontally degraded fix > 100m
        val degraded = LocationTracker.validateCoordinates(23.81, 90.41, 150.0f)
        assertFalse(degraded)
    }
}
