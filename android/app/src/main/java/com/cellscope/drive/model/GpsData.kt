package com.cellscope.drive.model

data class GpsData(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeM: Double? = null,
    val accuracyM: Float? = null,
    val speedKmh: Float? = null,                   // Quality-filtered movement speed (0.0 when stationary)
    val rawSpeedKmh: Float? = null,                // Raw hardware/Doppler speed from Location.speed * 3.6
    val isStationary: Boolean = true,              // True if within stationary jitter deadband
    val bearing: Float? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val provider: String = "none",
    val hasFix: Boolean = false,
    val isValid: Boolean = true,
    val cumulativeDistanceM: Double = 0.0,         // Jitter-filtered cumulative movement distance
    val rawCumulativeDistanceM: Double = 0.0,      // Raw unfiltered cumulative distance (for auditing)
    val timeSinceLastFixMs: Long = 0L,
    val filterReason: String = "INITIAL",
    val deviceHeadingDeg: Float? = null,           // True compass heading (corrected for declination)
    val magneticHeadingDeg: Float? = null,         // Raw magnetic heading
    val headingAccuracyDeg: Float? = null,         // Heading error in degrees
    val orientationSensorType: String = "NONE",
    val isHeadingCalibrated: Boolean = false
)

