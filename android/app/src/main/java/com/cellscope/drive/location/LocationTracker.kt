package com.cellscope.drive.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.cellscope.drive.model.GpsData

class LocationTracker(
    private val context: Context,
    private val onLocationUpdated: (GpsData) -> Unit
) : LocationListener {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var isTracking = false

    private val jitterFilter = GpsJitterFilter()
    val orientationTracker = OrientationTracker(context)

    @Volatile
    var latestGpsData: GpsData = GpsData()
        private set

    private var lastGpsFixTimeMs: Long = 0L
    private var lastFixTimeMs: Long = 0L

    var isPaused: Boolean = false
        private set

    val maxFilteredSpeedKmh: Float
        get() = jitterFilter.maxSpeedKmh

    val cumulativeDistanceMeters: Double
        get() = jitterFilter.cumulativeDistanceMeters

    val isStationary: Boolean
        get() = jitterFilter.isStationary

    fun resetDistance() {
        jitterFilter.reset()
        lastGpsFixTimeMs = 0L
        lastFixTimeMs = 0L
        isPaused = false
    }

    fun pauseTracking() {
        isPaused = true
    }

    fun resumeTracking() {
        isPaused = false
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking || locationManager == null) return

        try {
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (!isGpsEnabled && !isNetworkEnabled) {
                Log.w("LocationTracker", "Neither GPS nor Network provider is enabled.")
                return
            }

            val lastKnownGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastKnownNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val bestLast = lastKnownGps ?: lastKnownNet
            if (bestLast != null) {
                updateLocation(bestLast)
            }

            if (isGpsEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0.5f,
                    this,
                    Looper.getMainLooper()
                )
            }
            if (isNetworkEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1500L,
                    1.0f,
                    this,
                    Looper.getMainLooper()
                )
            }

            isTracking = true
            orientationTracker.startListening()
            Log.d("LocationTracker", "Enhanced location tracking and orientation listening started with GpsJitterFilter.")
        } catch (e: SecurityException) {
            Log.e("LocationTracker", "Location permission missing: ${e.message}")
        } catch (e: Exception) {
            Log.e("LocationTracker", "Error starting location tracking: ${e.message}")
        }
    }

    fun stopTracking() {
        if (!isTracking || locationManager == null) return
        try {
            locationManager.removeUpdates(this)
            orientationTracker.stopListening()
            isTracking = false
            Log.d("LocationTracker", "Location tracking and orientation listening stopped.")
        } catch (e: Exception) {
            Log.e("LocationTracker", "Error stopping location tracking: ${e.message}")
        }
    }

    override fun onLocationChanged(location: Location) {
        updateLocation(location)
    }

    private fun updateLocation(loc: Location) {
        val now = System.currentTimeMillis()
        val provider = loc.provider ?: "unknown"
        val isGpsProvider = provider.equals(LocationManager.GPS_PROVIDER, ignoreCase = true)

        if (isGpsProvider) {
            lastGpsFixTimeMs = now
        } else if (provider.equals(LocationManager.NETWORK_PROVIDER, ignoreCase = true)) {
            // If GPS provider is actively delivering fixes within the last 15 seconds,
            // ignore Network provider fixes to prevent jumping between GPS and cell-tower/WiFi coordinates
            if (lastGpsFixTimeMs > 0 && (now - lastGpsFixTimeMs) < 15000L) {
                return
            }
        }

        val timeSinceLast = if (lastFixTimeMs > 0) now - lastFixTimeMs else 0L
        lastFixTimeMs = now

        val rawSpeedKmh = if (loc.hasSpeed() && loc.speed >= 0f) {
            loc.speed * 3.6f
        } else {
            null
        }

        val alt = if (loc.hasAltitude()) loc.altitude else null
        val acc = if (loc.hasAccuracy()) loc.accuracy else null
        val bearing = if (loc.hasBearing()) loc.bearing else null
        val fixTimestamp = loc.time.takeIf { it > 0 } ?: now

        // Process through GpsJitterFilter
        val filterResult = jitterFilter.processFix(
            lat = loc.latitude,
            lon = loc.longitude,
            altitudeM = alt,
            accuracyM = acc,
            rawSpeedKmh = rawSpeedKmh,
            timestampMs = fixTimestamp,
            isPaused = isPaused
        )

        // Update coordinates in orientationTracker for declination calculation
        orientationTracker.updateCoordinates(loc.latitude, loc.longitude, alt)
        val orient = orientationTracker.latestOrientation

        val data = GpsData(
            latitude = loc.latitude,
            longitude = loc.longitude,
            altitudeM = alt,
            accuracyM = acc,
            speedKmh = filterResult.filteredSpeedKmh,
            rawSpeedKmh = rawSpeedKmh,
            isStationary = filterResult.isStationary,
            bearing = bearing,
            timestampMs = fixTimestamp,
            provider = provider,
            hasFix = true,
            isValid = filterResult.isValidFix,
            cumulativeDistanceM = filterResult.cumulativeDistanceM,
            rawCumulativeDistanceM = filterResult.rawCumulativeDistanceM,
            timeSinceLastFixMs = timeSinceLast,
            filterReason = filterResult.filterReason,
            deviceHeadingDeg = orient.trueHeadingDeg,
            magneticHeadingDeg = orient.magneticHeadingDeg,
            headingAccuracyDeg = orient.headingAccuracyDeg,
            orientationSensorType = orient.sensorType,
            isHeadingCalibrated = orient.isCalibrated
        )

        latestGpsData = data
        onLocationUpdated(data)
    }

    companion object {
        fun validateCoordinates(lat: Double, lon: Double, accuracyM: Float? = null): Boolean {
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return false
            if (Math.abs(lat) < 0.0001 && Math.abs(lon) < 0.0001) return false
            if (accuracyM != null && accuracyM > 100f) return false
            return true
        }

        fun validateGpsFix(loc: Location): Boolean {
            val acc = if (loc.hasAccuracy()) loc.accuracy else null
            return validateCoordinates(loc.latitude, loc.longitude, acc)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
