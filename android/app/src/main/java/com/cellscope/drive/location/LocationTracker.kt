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

    @Volatile
    var latestGpsData: GpsData = GpsData()
        private set

    private var lastValidLocation: Location? = null
    private var lastFixTimeMs: Long = 0L
    private var cumulativeDistanceMeters: Double = 0.0

    fun resetDistance() {
        cumulativeDistanceMeters = 0.0
        lastValidLocation = null
        lastFixTimeMs = 0L
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
            Log.d("LocationTracker", "Enhanced location tracking started.")
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
            isTracking = false
            Log.d("LocationTracker", "Location tracking stopped.")
        } catch (e: Exception) {
            Log.e("LocationTracker", "Error stopping location tracking: ${e.message}")
        }
    }

    override fun onLocationChanged(location: Location) {
        updateLocation(location)
    }

    private fun updateLocation(loc: Location) {
        val now = System.currentTimeMillis()
        val timeSinceLast = if (lastFixTimeMs > 0) now - lastFixTimeMs else 0L

        // Validation against GPS glitches and out-of-bounds coords
        val isValidFix = validateGpsFix(loc)

        if (isValidFix) {
            lastValidLocation?.let { prev ->
                val distance = loc.distanceTo(prev)
                val dtSec = (loc.time - prev.time).coerceAtLeast(1) / 1000.0
                val calculatedSpeedKmh = (distance / dtSec) * 3.6

                // Reject impossible speed jumps (> 250 km/h) typical of antenna glitch/outliers
                if (calculatedSpeedKmh <= 250.0 && distance in 1.0..1000.0) {
                    cumulativeDistanceMeters += distance
                }
            }
            lastValidLocation = loc
            lastFixTimeMs = now
        }

        val speedKmh = if (loc.hasSpeed() && loc.speed >= 0f) {
            loc.speed * 3.6f
        } else {
            null
        }

        val alt = if (loc.hasAltitude()) loc.altitude else null
        val acc = if (loc.hasAccuracy()) loc.accuracy else null
        val bearing = if (loc.hasBearing()) loc.bearing else null

        val data = GpsData(
            latitude = loc.latitude,
            longitude = loc.longitude,
            altitudeM = alt,
            accuracyM = acc,
            speedKmh = speedKmh,
            bearing = bearing,
            timestampMs = loc.time.takeIf { it > 0 } ?: now,
            provider = loc.provider ?: "gps",
            hasFix = true,
            isValid = isValidFix,
            cumulativeDistanceM = cumulativeDistanceMeters,
            timeSinceLastFixMs = timeSinceLast
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
