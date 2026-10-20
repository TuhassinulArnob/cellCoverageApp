package com.cellscope.drive

import com.cellscope.drive.ui.map.MapDisplayMode
import com.cellscope.drive.ui.map.MapLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Independent Multi-Layer Map Selection.
 * Validates that layers can be toggled independently without disabling active layers,
 * ensuring concurrent multi-layer rendering (e.g., Heatmap + Handovers, Weak Zones + Handovers).
 */
class MapLayerMultiSelectTest {

    @Test
    fun testAllMapLayersExistWithDisplayNames() {
        assertEquals(8, MapLayer.values().size)

        assertEquals("Route Breadcrumbs", MapLayer.ROUTE_SAMPLES.displayName)
        assertEquals("Coverage Grid", MapLayer.COVERAGE_HEATMAP.displayName)
        assertEquals("Serving Radials", MapLayer.ESTIMATED_SITES.displayName)
        assertEquals("Transitions & Reselection", MapLayer.HANDOVERS.displayName)
        assertEquals("Weak Zones", MapLayer.WEAK_ZONES.displayName)
        assertEquals("Capacity Hotspots", MapLayer.CAPACITY_HOTSPOTS.displayName)
        assertEquals("Active QoE & Congestion", MapLayer.ACTIVE_QOE.displayName)
        assertEquals("Event Planning", MapLayer.EVENT_PLANNING.displayName)
    }

    @Test
    fun testIndependentLayerToggling() {
        val activeLayers = mutableSetOf<MapLayer>()

        fun toggleLayer(layer: MapLayer, enable: Boolean? = null) {
            val shouldEnable = enable ?: !activeLayers.contains(layer)
            if (shouldEnable) {
                activeLayers.add(layer)
            } else {
                activeLayers.remove(layer)
            }
        }

        // Initially only ROUTE_SAMPLES
        activeLayers.add(MapLayer.ROUTE_SAMPLES)
        assertEquals(1, activeLayers.size)
        assertTrue(activeLayers.contains(MapLayer.ROUTE_SAMPLES))

        // Enable COVERAGE_HEATMAP: ROUTE_SAMPLES must remain active (concurrent)
        toggleLayer(MapLayer.COVERAGE_HEATMAP, true)
        assertEquals(2, activeLayers.size)
        assertTrue(activeLayers.contains(MapLayer.ROUTE_SAMPLES))
        assertTrue(activeLayers.contains(MapLayer.COVERAGE_HEATMAP))

        // Enable HANDOVERS: both previous layers must remain active
        toggleLayer(MapLayer.HANDOVERS, true)
        assertEquals(3, activeLayers.size)
        assertTrue(activeLayers.contains(MapLayer.ROUTE_SAMPLES))
        assertTrue(activeLayers.contains(MapLayer.COVERAGE_HEATMAP))
        assertTrue(activeLayers.contains(MapLayer.HANDOVERS))

        // Enable ACTIVE_QOE: all 4 active concurrently
        toggleLayer(MapLayer.ACTIVE_QOE, true)
        assertEquals(4, activeLayers.size)
        assertTrue(activeLayers.contains(MapLayer.ACTIVE_QOE))

        // Toggle off ROUTE_SAMPLES: remaining 3 must stay active
        toggleLayer(MapLayer.ROUTE_SAMPLES, false)
        assertEquals(3, activeLayers.size)
        assertFalse(activeLayers.contains(MapLayer.ROUTE_SAMPLES))
        assertTrue(activeLayers.contains(MapLayer.COVERAGE_HEATMAP))
        assertTrue(activeLayers.contains(MapLayer.HANDOVERS))
        assertTrue(activeLayers.contains(MapLayer.ACTIVE_QOE))

        // Toggle toggle without explicit boolean (inverts)
        toggleLayer(MapLayer.COVERAGE_HEATMAP)
        assertFalse(activeLayers.contains(MapLayer.COVERAGE_HEATMAP))
        assertEquals(2, activeLayers.size)
    }

    @Test
    fun testEnableAllLayersAndClearAll() {
        val activeLayers = mutableSetOf<MapLayer>()

        fun enableAllLayers() {
            activeLayers.addAll(MapLayer.values())
        }

        fun clearAllLayers() {
            activeLayers.clear()
        }

        // Enable All
        enableAllLayers()
        assertEquals(8, activeLayers.size)
        for (layer in MapLayer.values()) {
            assertTrue("Layer $layer must be active in enableAll", activeLayers.contains(layer))
        }

        // Clear All
        clearAllLayers()
        assertTrue("Active layers must be empty after clearAll", activeLayers.isEmpty())

        // Reset to default Route & Samples
        activeLayers.add(MapLayer.ROUTE_SAMPLES)
        assertEquals(1, activeLayers.size)
        assertTrue(activeLayers.contains(MapLayer.ROUTE_SAMPLES))
    }

    @Test
    fun testMapDisplayModeValues() {
        val modes = MapDisplayMode.values()
        assertEquals(9, modes.size)
        assertTrue(modes.contains(MapDisplayMode.ROUTE_SAMPLES))
        assertTrue(modes.contains(MapDisplayMode.COVERAGE_HEATMAP))
        assertTrue(modes.contains(MapDisplayMode.ESTIMATED_SITES))
        assertTrue(modes.contains(MapDisplayMode.HANDOVERS))
        assertTrue(modes.contains(MapDisplayMode.WEAK_ZONES))
        assertTrue(modes.contains(MapDisplayMode.CAPACITY_HOTSPOTS))
        assertTrue(modes.contains(MapDisplayMode.ACTIVE_QOE))
        assertTrue(modes.contains(MapDisplayMode.EVENT_PLANNING))
        assertTrue(modes.contains(MapDisplayMode.ALL_LAYERS))
    }
}
