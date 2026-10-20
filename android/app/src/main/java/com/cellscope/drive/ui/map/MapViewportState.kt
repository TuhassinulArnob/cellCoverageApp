package com.cellscope.drive.ui.map

import com.cellscope.drive.analytics.MapMetricLayer

/**
 * Encapsulates the complete, persistent viewport state for the dynamic map across
 * screen navigation, recompositions, tab changes, and background updates.
 */
data class MapViewportState(
    var centerLat: Double? = null,
    var centerLon: Double? = null,
    var zoom: Double = MapConfig.DEFAULT_ZOOM,
    var bearing: Double = 0.0,
    var tilt: Double = 0.0,
    var isUserControlled: Boolean = false,
    var activeMode: MapDisplayMode = MapDisplayMode.ROUTE_SAMPLES,
    var activeMetric: MapMetricLayer = MapMetricLayer.RSRP,
    var activeGeneration: GenerationFilter = GenerationFilter.ALL,
    var isFollowGps: Boolean = false,
    var lastInteractedTimestampMs: Long = 0L
) {
    val isUserInteracting: Boolean
        get() = isUserControlled

    var activeGenerationFilter: GenerationFilter
        get() = activeGeneration
        set(value) { activeGeneration = value }

    val hasValidCamera: Boolean
        get() = centerLat != null && centerLon != null

    fun updateCamera(
        lat: Double,
        lon: Double,
        zoom: Double = MapConfig.DEFAULT_ZOOM,
        bearing: Double = 0.0,
        tilt: Double = 0.0,
        fromUserGesture: Boolean = false
    ) {
        centerLat = lat
        centerLon = lon
        this.zoom = zoom
        this.bearing = bearing
        this.tilt = tilt
        if (fromUserGesture) {
            isUserControlled = true
            isFollowGps = false
            lastInteractedTimestampMs = System.currentTimeMillis()
        }
    }

    fun resetToDefaults() {
        centerLat = null
        centerLon = null
        zoom = MapConfig.DEFAULT_ZOOM
        bearing = 0.0
        tilt = 0.0
        isUserControlled = false
        activeMode = MapDisplayMode.ROUTE_SAMPLES
        activeMetric = MapMetricLayer.RSRP
        activeGeneration = GenerationFilter.ALL
        isFollowGps = false
        lastInteractedTimestampMs = 0L
    }
}
