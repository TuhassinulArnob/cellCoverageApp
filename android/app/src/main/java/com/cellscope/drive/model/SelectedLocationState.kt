package com.cellscope.drive.model

import com.cellscope.drive.analytics.EventPreset
import com.cellscope.drive.analytics.EventVenue
import java.util.Locale

/**
 * Classification of how the location was selected.
 */
enum class LocationSelectionSource(val label: String) {
    MAP_TAP("Map Tap"),
    GPS("Live GPS Position"),
    IMPORTED_COORDINATE("Imported Survey Coordinate"),
    MANUAL_ENTRY("Manual Coordinate Entry")
}

typealias LocationSource = LocationSelectionSource

/**
 * Single authoritative state holder for user-selected geographic location across
 * the map, event planner dialog, capacity calculation, map marker, and report exports.
 */
data class SelectedLocationState(
    val latitude: Double,
    val longitude: Double,
    val selectionSource: LocationSelectionSource = LocationSelectionSource.MAP_TAP,
    val selectionTimestampMs: Long = System.currentTimeMillis(),
    val localityName: String? = null,
    val eventName: String = "Selected Event Site",
    val boundaryRadiusMeters: Double = 500.0,
    val eventPreset: EventPreset = EventPreset.CUSTOM,
    val sampleId: Long? = null,
    val sessionId: Long? = null,
    val isSurveyedSample: Boolean = false
) {
    val resolvedLocality: String? get() = localityName
    val source: LocationSelectionSource get() = selectionSource

    fun withEventName(name: String): SelectedLocationState = copy(eventName = name)
    fun formatCoordinates(): String = formattedCoordinates()

    /**
     * Converts authoritative selected location into an EventVenue for capacity simulation.
     */
    fun toEventVenue(): EventVenue {
        val resolvedName = localityName?.ifBlank { "Selected Location" } ?: "Selected Location"
        return EventVenue.fromCoordinates(
            lat = latitude,
            lon = longitude,
            name = resolvedName,
            eventName = eventName.ifBlank { "Custom Event" },
            radiusMeters = boundaryRadiusMeters,
            eventType = eventPreset
        )
    }

    /**
     * Returns a formatted coordinate string (e.g. "23.90123°N, 89.12345°E").
     */
    fun formattedCoordinates(): String {
        val latDir = if (latitude >= 0) "N" else "S"
        val lonDir = if (longitude >= 0) "E" else "W"
        return String.format(Locale.US, "%.5f°%s, %.5f°%s", Math.abs(latitude), latDir, Math.abs(longitude), lonDir)
    }

    /**
     * Checks if coordinates match another point within a small geographic delta (~1 meter).
     */
    fun isSameLocation(lat: Double, lon: Double, toleranceDeg: Double = 0.00001): Boolean {
        return Math.abs(latitude - lat) <= toleranceDeg && Math.abs(longitude - lon) <= toleranceDeg
    }

    companion object {
        /**
         * Creates an initial location state from a map tap.
         */
        fun fromMapTap(
            latitude: Double,
            longitude: Double,
            localityHint: String? = null,
            boundaryRadiusMeters: Double = 500.0,
            eventName: String = "Selected Event Site"
        ): SelectedLocationState {
            return SelectedLocationState(
                latitude = latitude,
                longitude = longitude,
                selectionSource = LocationSelectionSource.MAP_TAP,
                selectionTimestampMs = System.currentTimeMillis(),
                localityName = localityHint,
                eventName = eventName,
                boundaryRadiusMeters = boundaryRadiusMeters
            )
        }

        /**
         * Creates a location state from manual coordinate input.
         */
        fun fromManualEntry(
            latitude: Double,
            longitude: Double,
            localityHint: String? = null,
            eventName: String = "Manual Event Site",
            boundaryRadiusMeters: Double = 500.0
        ): SelectedLocationState {
            return SelectedLocationState(
                latitude = latitude,
                longitude = longitude,
                selectionSource = LocationSelectionSource.MANUAL_ENTRY,
                selectionTimestampMs = System.currentTimeMillis(),
                localityName = localityHint,
                eventName = eventName,
                boundaryRadiusMeters = boundaryRadiusMeters
            )
        }

        /**
         * Creates a location state from live GPS.
         */
        fun fromGps(
            latitude: Double,
            longitude: Double,
            localityHint: String? = null,
            boundaryRadiusMeters: Double = 500.0,
            eventName: String = "GPS Location"
        ): SelectedLocationState {
            return SelectedLocationState(
                latitude = latitude,
                longitude = longitude,
                selectionSource = LocationSelectionSource.GPS,
                selectionTimestampMs = System.currentTimeMillis(),
                localityName = localityHint,
                eventName = eventName,
                boundaryRadiusMeters = boundaryRadiusMeters
            )
        }

        /**
         * Creates a location state specifically from a surveyed drive-test sample.
         */
        fun fromSurveySample(
            sampleId: Long,
            latitude: Double,
            longitude: Double,
            sessionId: Long? = null,
            localityHint: String? = null,
            boundaryRadiusMeters: Double = 500.0,
            eventName: String = "Surveyed Site (Sample #$sampleId)"
        ): SelectedLocationState {
            return SelectedLocationState(
                latitude = latitude,
                longitude = longitude,
                selectionSource = LocationSelectionSource.MAP_TAP,
                selectionTimestampMs = System.currentTimeMillis(),
                localityName = localityHint,
                eventName = eventName,
                boundaryRadiusMeters = boundaryRadiusMeters,
                sampleId = sampleId,
                sessionId = sessionId,
                isSurveyedSample = true
            )
        }

        /**
         * Creates an explicit plan at an unsurveyed location.
         */
        fun fromUnsurveyedLocation(
            latitude: Double,
            longitude: Double,
            localityHint: String? = null,
            boundaryRadiusMeters: Double = 500.0,
            eventName: String = "Unsurveyed Site"
        ): SelectedLocationState {
            return SelectedLocationState(
                latitude = latitude,
                longitude = longitude,
                selectionSource = LocationSelectionSource.MANUAL_ENTRY,
                selectionTimestampMs = System.currentTimeMillis(),
                localityName = localityHint ?: "Unsurveyed Point",
                eventName = eventName,
                boundaryRadiusMeters = boundaryRadiusMeters,
                sampleId = null,
                sessionId = null,
                isSurveyedSample = false
            )
        }
    }
}

