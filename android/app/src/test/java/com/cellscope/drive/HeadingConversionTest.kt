package com.cellscope.drive

import com.cellscope.drive.location.OrientationTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadingConversionTest {

    @Test
    fun testAzimuthToHeadingConversion() {
        // 0 rad -> 0 deg (North)
        assertEquals(0f, OrientationTracker.azimuthToHeading(0f), 0.01f)

        // pi/2 rad -> 90 deg (East)
        assertEquals(90f, OrientationTracker.azimuthToHeading((Math.PI / 2.0).toFloat()), 0.01f)

        // pi rad -> 180 deg (South)
        assertEquals(180f, OrientationTracker.azimuthToHeading(Math.PI.toFloat()), 0.01f)

        // -pi/2 rad -> 270 deg (West)
        assertEquals(270f, OrientationTracker.azimuthToHeading((-Math.PI / 2.0).toFloat()), 0.01f)

        // -pi/4 rad -> 315 deg (NW)
        assertEquals(315f, OrientationTracker.azimuthToHeading((-Math.PI / 4.0).toFloat()), 0.01f)
    }

    @Test
    fun testTrueHeadingWithPositiveDeclination() {
        // Magnetic heading 10°, Declination +5° East -> True heading 15°
        val trueHeading = OrientationTracker.computeTrueHeading(10f, 5f)
        assertEquals(15f, trueHeading, 0.01f)

        // Near 360 boundary: Magnetic 358°, Declination +5° -> True heading 3°
        val wrappedHeading = OrientationTracker.computeTrueHeading(358f, 5f)
        assertEquals(3f, wrappedHeading, 0.01f)
    }

    @Test
    fun testTrueHeadingWithNegativeDeclination() {
        // Magnetic heading 20°, Declination -5° West -> True heading 15°
        val trueHeading = OrientationTracker.computeTrueHeading(20f, -5f)
        assertEquals(15f, trueHeading, 0.01f)

        // Underflow wrap: Magnetic 2°, Declination -5° -> True heading 357°
        val wrappedHeading = OrientationTracker.computeTrueHeading(2f, -5f)
        assertEquals(357f, wrappedHeading, 0.01f)
    }

    @Test
    fun testAccuracyLevelMapping() {
        assertEquals(5.0f, OrientationTracker.accuracyLevelToDegrees(android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH) ?: 0f, 0.01f)
        assertEquals(15.0f, OrientationTracker.accuracyLevelToDegrees(android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) ?: 0f, 0.01f)
        assertEquals(30.0f, OrientationTracker.accuracyLevelToDegrees(android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW) ?: 0f, 0.01f)
        assertNull(OrientationTracker.accuracyLevelToDegrees(android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE))
    }

    @Test
    fun testDeclinationCoordinateBounds() {
        // Invalid latitude (> 90) returns null
        assertNull(OrientationTracker.computeDeclination(95.0, 90.0, 0.0, System.currentTimeMillis()))
        // Invalid longitude (> 180) returns null
        assertNull(OrientationTracker.computeDeclination(23.8, 195.0, 0.0, System.currentTimeMillis()))
        // Null coords returns null
        assertNull(OrientationTracker.computeDeclination(null, null, null, System.currentTimeMillis()))
    }
}
