package com.cellscope.drive.model

data class GpsData(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeM: Double? = null,
    val accuracyM: Float? = null,
    val speedKmh: Float? = null,
    val bearing: Float? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val provider: String = "none",
    val hasFix: Boolean = false,
    val isValid: Boolean = true,
    val cumulativeDistanceM: Double = 0.0,
    val timeSinceLastFixMs: Long = 0L
)
