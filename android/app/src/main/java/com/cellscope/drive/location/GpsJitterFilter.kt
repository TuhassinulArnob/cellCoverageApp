package com.cellscope.drive.location

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GpsFilterConfig(
    val stationarySpeedThresholdKmh: Float = 1.8f,     // Speeds below 1.8 km/h (~0.5 m/s) are within stationary noise floor
    val minAccuracyThresholdM: Float = 30.0f,          // Fixes with accuracy worse than 30m are rejected for speed/distance
    val anchorRadiusMultiplier: Float = 1.2f,          // Deadband = max(minAnchorRadiusM, multiplier * accuracy)
    val minAnchorRadiusM: Float = 8.0f,                // Minimum deadband radius in meters
    val maxAnchorRadiusM: Float = 25.0f,               // Maximum deadband radius cap in meters
    val consecutiveMovementFixesRequired: Int = 3,     // Fixes required to confirm transition from STATIONARY to MOVING
    val maxValidSpeedKmh: Float = 200.0f,              // Above 200 km/h is considered an impossible speed spike for drive testing
    val maxTimeGapMs: Long = 15000L                    // Time gap to reset anchor baseline
)

data class FilteredGpsResult(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val accuracyM: Float?,
    val rawSpeedKmh: Float?,
    val filteredSpeedKmh: Float,
    val isStationary: Boolean,
    val addedDistanceM: Double,
    val cumulativeDistanceM: Double,
    val rawCumulativeDistanceM: Double,
    val filterReason: String,
    val isValidFix: Boolean
) {
    val speedKmh: Float get() = filteredSpeedKmh
}

/**
 * High-precision GPS jitter and speed filter for cellular drive testing.
 * Strictly prevents stationary indoor antenna noise from inflating route distance
 * and generating spurious maximum speed spikes (e.g. 11-17 km/h while stationary).
 */
class GpsJitterFilter(
    val config: GpsFilterConfig = GpsFilterConfig()
) {

    private var anchorLat: Double? = null
    private var anchorLon: Double? = null
    private var anchorTimeMs: Long = 0L
    private var anchorAccuracyM: Float = 10f

    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastTimeMs: Long = 0L
    private var lastAccuracyM: Float = 10f

    private var isCurrentlyStationary: Boolean = true
    private var consecutiveMovementFixes: Int = 0
    private var consecutiveStationaryFixes: Int = 0

    var cumulativeDistanceMeters: Double = 0.0
        private set

    var rawCumulativeDistanceMeters: Double = 0.0
        private set

    var maxSpeedKmh: Float = 0.0f
        private set

    val isStationary: Boolean
        get() = isCurrentlyStationary

    val cumulativeFilteredDistanceM: Double
        get() = cumulativeDistanceMeters

    val maxFilteredSpeedKmh: Float
        get() = maxSpeedKmh

    fun reset() {
        anchorLat = null
        anchorLon = null
        anchorTimeMs = 0L
        anchorAccuracyM = 10f
        lastLat = null
        lastLon = null
        lastTimeMs = 0L
        lastAccuracyM = 10f
        isCurrentlyStationary = true
        consecutiveMovementFixes = 0
        consecutiveStationaryFixes = 0
        cumulativeDistanceMeters = 0.0
        rawCumulativeDistanceMeters = 0.0
        maxSpeedKmh = 0.0f
    }

    /**
     * Process an incoming GPS fix and returns a FilteredGpsResult.
     */
    fun processFix(
        lat: Double,
        lon: Double,
        altitudeM: Double?,
        accuracyM: Float?,
        rawSpeedKmh: Float?,
        timestampMs: Long,
        isPaused: Boolean = false
    ): FilteredGpsResult {
        val acc = accuracyM ?: 10f

        // 1. Basic sanity validation (bounds and null island)
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0 || (Math.abs(lat) < 0.0001 && Math.abs(lon) < 0.0001)) {
            return FilteredGpsResult(
                latitude = lat,
                longitude = lon,
                altitudeM = altitudeM,
                accuracyM = accuracyM,
                rawSpeedKmh = rawSpeedKmh,
                filteredSpeedKmh = 0.0f,
                isStationary = isCurrentlyStationary,
                addedDistanceM = 0.0,
                cumulativeDistanceM = cumulativeDistanceMeters,
                rawCumulativeDistanceM = rawCumulativeDistanceMeters,
                filterReason = "Invalid coordinates or null island",
                isValidFix = false
            )
        }

        // 2. Cold start / initial fix initialization
        val prevLat = lastLat
        val prevLon = lastLon
        val prevTime = lastTimeMs

        if (prevLat == null || prevLon == null) {
            setAnchor(lat, lon, acc, timestampMs)
            lastLat = lat
            lastLon = lon
            lastTimeMs = timestampMs
            lastAccuracyM = acc
            return FilteredGpsResult(
                latitude = lat,
                longitude = lon,
                altitudeM = altitudeM,
                accuracyM = accuracyM,
                rawSpeedKmh = rawSpeedKmh,
                filteredSpeedKmh = 0.0f,
                isStationary = true,
                addedDistanceM = 0.0,
                cumulativeDistanceM = cumulativeDistanceMeters,
                rawCumulativeDistanceM = rawCumulativeDistanceMeters,
                filterReason = "Initial baseline fix established",
                isValidFix = true
            )
        }

        // 3. Timestamp interval sanity
        val dtMs = timestampMs - prevTime
        if (dtMs <= 0L) {
            return FilteredGpsResult(
                latitude = lat,
                longitude = lon,
                altitudeM = altitudeM,
                accuracyM = accuracyM,
                rawSpeedKmh = rawSpeedKmh,
                filteredSpeedKmh = if (isCurrentlyStationary) 0.0f else (rawSpeedKmh ?: 0.0f),
                isStationary = isCurrentlyStationary,
                addedDistanceM = 0.0,
                cumulativeDistanceM = cumulativeDistanceMeters,
                rawCumulativeDistanceM = rawCumulativeDistanceMeters,
                filterReason = "Duplicate or out-of-order timestamp (dt <= 0ms)",
                isValidFix = false
            )
        }

        if (dtMs > config.maxTimeGapMs) {
            // Signal gap > 15 seconds (e.g. tunnel or screen-off): re-anchor without computing jump
            setAnchor(lat, lon, acc, timestampMs)
            lastLat = lat
            lastLon = lon
            lastTimeMs = timestampMs
            lastAccuracyM = acc
            return FilteredGpsResult(
                latitude = lat,
                longitude = lon,
                altitudeM = altitudeM,
                accuracyM = accuracyM,
                rawSpeedKmh = rawSpeedKmh,
                filteredSpeedKmh = 0.0f,
                isStationary = isCurrentlyStationary,
                addedDistanceM = 0.0,
                cumulativeDistanceM = cumulativeDistanceMeters,
                rawCumulativeDistanceM = rawCumulativeDistanceMeters,
                filterReason = "Time gap exceeds 15s; baseline re-anchored",
                isValidFix = true
            )
        }

        // 4. Accuracy gate for movement consideration
        if (acc > config.minAccuracyThresholdM) {
            lastTimeMs = timestampMs
            return FilteredGpsResult(
                latitude = lat,
                longitude = lon,
                altitudeM = altitudeM,
                accuracyM = accuracyM,
                rawSpeedKmh = rawSpeedKmh,
                filteredSpeedKmh = if (isCurrentlyStationary) 0.0f else (rawSpeedKmh ?: 0.0f),
                isStationary = isCurrentlyStationary,
                addedDistanceM = 0.0,
                cumulativeDistanceM = cumulativeDistanceMeters,
                rawCumulativeDistanceM = rawCumulativeDistanceMeters,
                filterReason = String.format(Locale.US, "Accuracy (±%.1fm) exceeds threshold (%.0fm)", acc, config.minAccuracyThresholdM),
                isValidFix = false
            )
        }

        // 5. Compute displacement and raw step speed
        val stepDistance = computeHaversineMeters(prevLat, prevLon, lat, lon)
        rawCumulativeDistanceMeters += stepDistance

        val dtSec = dtMs / 1000.0
        val coordinateSpeedKmh = ((stepDistance / dtSec) * 3.6).toFloat()

        // Reject impossible speed jumps
        if (coordinateSpeedKmh > config.maxValidSpeedKmh) {
            return FilteredGpsResult(
                latitude = lat,
                longitude = lon,
                altitudeM = altitudeM,
                accuracyM = accuracyM,
                rawSpeedKmh = rawSpeedKmh,
                filteredSpeedKmh = 0.0f,
                isStationary = isCurrentlyStationary,
                addedDistanceM = 0.0,
                cumulativeDistanceM = cumulativeDistanceMeters,
                rawCumulativeDistanceM = rawCumulativeDistanceMeters,
                filterReason = String.format(Locale.US, "Impossible speed jump (%.1f km/h > %.0f km/h) rejected", coordinateSpeedKmh, config.maxValidSpeedKmh),
                isValidFix = false
            )
        }

        // 6. Stationary Anchor Evaluation
        val aLat = anchorLat ?: lat
        val aLon = anchorLon ?: lon
        val distFromAnchor = computeHaversineMeters(aLat, aLon, lat, lon)

        // Dynamic deadband radius based on fix accuracy
        val rawDeadband = config.anchorRadiusMultiplier * maxOf(anchorAccuracyM, acc)
        val deadbandRadius = rawDeadband.coerceIn(config.minAnchorRadiusM, config.maxAnchorRadiusM)

        var addedDistance = 0.0
        var filteredSpeed = 0.0f
        var reason = ""

        if (isCurrentlyStationary) {
            if (distFromAnchor < deadbandRadius) {
                // Device is drifting within the stationary deadband ellipse
                consecutiveMovementFixes = 0
                filteredSpeed = 0.0f
                addedDistance = 0.0
                reason = String.format(Locale.US, "Stationary jitter within deadband (%.1fm < %.1fm deadband)", distFromAnchor, deadbandRadius)
            } else {
                // Device is outside the stationary deadband
                val hasSpeedSignal = rawSpeedKmh != null && rawSpeedKmh >= config.stationarySpeedThresholdKmh
                val significantBreakout = distFromAnchor >= (deadbandRadius * 1.8f)

                if (hasSpeedSignal || significantBreakout) {
                    consecutiveMovementFixes++
                } else {
                    consecutiveMovementFixes++
                }

                if (consecutiveMovementFixes >= config.consecutiveMovementFixesRequired || significantBreakout) {
                    // Confirmed genuine movement!
                    isCurrentlyStationary = false
                    consecutiveMovementFixes = 0
                    consecutiveStationaryFixes = 0

                    if (!isPaused) {
                        addedDistance = stepDistance
                        cumulativeDistanceMeters += addedDistance
                    }

                    filteredSpeed = selectBestSpeed(rawSpeedKmh, coordinateSpeedKmh)
                    updateMaxSpeed(filteredSpeed, acc)
                    reason = String.format(Locale.US, "Movement confirmed (%.1fm from anchor, speed %.1f km/h)", distFromAnchor, filteredSpeed)
                } else {
                    // Candidate movement, awaiting multi-fix confirmation
                    filteredSpeed = 0.0f
                    addedDistance = 0.0
                    reason = String.format(Locale.US, "Movement candidate (%d/%d fixes, %.1fm from anchor)", consecutiveMovementFixes, config.consecutiveMovementFixesRequired, distFromAnchor)
                }
            }
        } else {
            // Currently MOVING state: check if device has stopped
            val lowCoordinateSpeed = coordinateSpeedKmh < config.stationarySpeedThresholdKmh
            val lowRawSpeed = rawSpeedKmh == null || rawSpeedKmh < config.stationarySpeedThresholdKmh

            if (lowCoordinateSpeed && lowRawSpeed) {
                consecutiveStationaryFixes++
                if (consecutiveStationaryFixes >= 3) {
                    // Movement has ceased: establish new stationary anchor
                    isCurrentlyStationary = true
                    consecutiveStationaryFixes = 0
                    consecutiveMovementFixes = 0
                    setAnchor(lat, lon, acc, timestampMs)
                    filteredSpeed = 0.0f
                    addedDistance = 0.0
                    reason = "Motion ceased; new stationary anchor established"
                } else {
                    // Brief pause / slowing down
                    if (!isPaused) {
                        addedDistance = stepDistance
                        cumulativeDistanceMeters += addedDistance
                    }
                    filteredSpeed = selectBestSpeed(rawSpeedKmh, coordinateSpeedKmh)
                    updateMaxSpeed(filteredSpeed, acc)
                    reason = String.format(Locale.US, "Decelerating towards stationary (%d/3 fixes)", consecutiveStationaryFixes)
                }
            } else {
                // Ongoing movement
                consecutiveStationaryFixes = 0
                if (!isPaused) {
                    addedDistance = stepDistance
                    cumulativeDistanceMeters += addedDistance
                }
                filteredSpeed = selectBestSpeed(rawSpeedKmh, coordinateSpeedKmh)
                updateMaxSpeed(filteredSpeed, acc)
                reason = String.format(Locale.US, "Confirmed route movement (speed: %.1f km/h)", filteredSpeed)
            }
        }

        lastLat = lat
        lastLon = lon
        lastTimeMs = timestampMs
        lastAccuracyM = acc

        return FilteredGpsResult(
            latitude = lat,
            longitude = lon,
            altitudeM = altitudeM,
            accuracyM = accuracyM,
            rawSpeedKmh = rawSpeedKmh,
            filteredSpeedKmh = filteredSpeed,
            isStationary = isCurrentlyStationary,
            addedDistanceM = addedDistance,
            cumulativeDistanceM = cumulativeDistanceMeters,
            rawCumulativeDistanceM = rawCumulativeDistanceMeters,
            filterReason = reason,
            isValidFix = true
        )
    }

    private fun setAnchor(lat: Double, lon: Double, acc: Float, timeMs: Long) {
        anchorLat = lat
        anchorLon = lon
        anchorAccuracyM = acc
        anchorTimeMs = timeMs
    }

    private fun selectBestSpeed(rawSpeedKmh: Float?, coordinateSpeedKmh: Float): Float {
        return if (rawSpeedKmh != null && rawSpeedKmh >= config.stationarySpeedThresholdKmh) {
            // Doppler hardware speed has higher instantaneous fidelity when above noise floor
            rawSpeedKmh
        } else if (coordinateSpeedKmh >= config.stationarySpeedThresholdKmh) {
            coordinateSpeedKmh
        } else {
            0.0f
        }
    }

    private fun updateMaxSpeed(speed: Float, acc: Float) {
        // Only update max speed if the fix is accurate and speed is within physical limits
        if (acc <= config.minAccuracyThresholdM && speed <= config.maxValidSpeedKmh && speed > maxSpeedKmh) {
            maxSpeedKmh = speed
        }
    }

    companion object {
        fun computeHaversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0 // Earth radius in meters
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2.0).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2.0).pow(2.0)
            val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
            return r * c
        }
    }
}
