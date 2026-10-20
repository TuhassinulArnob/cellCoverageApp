package com.cellscope.drive

import com.cellscope.drive.analytics.MapMetricLayer
import com.cellscope.drive.ui.map.GenerationFilter
import com.cellscope.drive.ui.map.MapDisplayMode
import com.cellscope.drive.ui.map.MapViewportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapViewportStateTest {

    @Test
    fun testViewportInitialState() {
        val state = MapViewportState()
        assertFalse(state.hasValidCamera)
        assertFalse(state.isUserInteracting)
        assertEquals(MapDisplayMode.ROUTE_SAMPLES, state.activeMode)
        assertEquals(MapMetricLayer.RSRP, state.activeMetric)
        assertEquals(GenerationFilter.ALL, state.activeGenerationFilter)
    }

    @Test
    fun testCameraUpdatesPersistState() {
        val state = MapViewportState()
        state.updateCamera(
            lat = 23.8103,
            lon = 90.4125,
            zoom = 15.2,
            bearing = 45.0,
            tilt = 10.0,
            fromUserGesture = true
        )

        assertTrue(state.hasValidCamera)
        assertTrue(state.isUserInteracting)
        assertEquals(23.8103, state.centerLat ?: 0.0, 0.0001)
        assertEquals(90.4125, state.centerLon ?: 0.0, 0.0001)
        assertEquals(15.2, state.zoom, 0.01)
        assertEquals(45.0, state.bearing, 0.01)
        assertEquals(10.0, state.tilt, 0.01)
    }

    @Test
    fun testFilterStatePersistence() {
        val state = MapViewportState()
        state.activeMode = MapDisplayMode.EVENT_PLANNING
        state.activeMetric = MapMetricLayer.SINR
        state.activeGenerationFilter = GenerationFilter.GEN_5G

        assertEquals(MapDisplayMode.EVENT_PLANNING, state.activeMode)
        assertEquals(MapMetricLayer.SINR, state.activeMetric)
        assertEquals(GenerationFilter.GEN_5G, state.activeGenerationFilter)
    }
}
