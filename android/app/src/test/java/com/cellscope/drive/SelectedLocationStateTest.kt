package com.cellscope.drive

import com.cellscope.drive.model.LocationSource
import com.cellscope.drive.model.SelectedLocationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedLocationStateTest {

    @Test
    fun testPointAStateHoldsExactCoordinatesWithoutDrift() {
        val pointA = SelectedLocationState.fromMapTap(
            latitude = 23.8103,
            longitude = 90.4125,
            localityHint = "Dhaka City Center",
            boundaryRadiusMeters = 500.0
        )

        assertEquals(23.8103, pointA.latitude, 0.000001)
        assertEquals(90.4125, pointA.longitude, 0.000001)
        assertEquals("Dhaka City Center", pointA.resolvedLocality)
        assertEquals(500.0, pointA.boundaryRadiusMeters, 0.001)
        assertEquals(LocationSource.MAP_TAP, pointA.source)

        // Add event name to Point A -> coordinates remain strictly unchanged
        val pointAPlanned = pointA.withEventName("Dhaka Marathon")
        assertEquals(23.8103, pointAPlanned.latitude, 0.000001)
        assertEquals(90.4125, pointAPlanned.longitude, 0.000001)
        assertEquals("Dhaka Marathon", pointAPlanned.eventName)
        assertEquals(pointA.resolvedLocality, pointAPlanned.resolvedLocality)
    }

    @Test
    fun testPointBStateHoldsExactCoordinatesWithoutDrift() {
        val pointB = SelectedLocationState.fromMapTap(
            latitude = 22.3569,
            longitude = 91.7832,
            localityHint = "Chittagong Port Area",
            boundaryRadiusMeters = 750.0
        )

        assertEquals(22.3569, pointB.latitude, 0.000001)
        assertEquals(91.7832, pointB.longitude, 0.000001)
        assertEquals("Chittagong Port Area", pointB.resolvedLocality)
        assertEquals(750.0, pointB.boundaryRadiusMeters, 0.001)

        val pointBPlanned = pointB.withEventName("Trade Expo")
        assertEquals(22.3569, pointBPlanned.latitude, 0.000001)
        assertEquals(91.7832, pointBPlanned.longitude, 0.000001)
        assertEquals("Trade Expo", pointBPlanned.eventName)
    }

    @Test
    fun testManualEntryAndGpsSources() {
        val manual = SelectedLocationState.fromManualEntry(
            latitude = 24.3636,
            longitude = 88.6241,
            localityHint = "Rajshahi",
            eventName = "Science Fair"
        )
        assertEquals(LocationSource.MANUAL_ENTRY, manual.source)
        assertEquals(24.3636, manual.latitude, 0.000001)
        assertEquals("Science Fair", manual.eventName)

        val gps = SelectedLocationState.fromGps(
            latitude = 24.8949,
            longitude = 91.8687,
            localityHint = "Sylhet"
        )
        assertEquals(LocationSource.GPS, gps.source)
        assertEquals(24.8949, gps.latitude, 0.000001)
    }

    @Test
    fun testFormattedCoordinates() {
        val state = SelectedLocationState.fromMapTap(
            latitude = 23.72251,
            longitude = 90.40782
        )
        val formatted = state.formatCoordinates()
        assertTrue(formatted.contains("23.72251"))
        assertTrue(formatted.contains("90.40782"))
    }
}
