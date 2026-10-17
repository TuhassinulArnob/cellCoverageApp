package com.cellscope.drive.ui.map

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

object MapConfig {

    // Default Bangladesh Geographic Center (Dhaka)
    val BANGLADESH_CENTER = LatLng(23.8103, 90.4125)
    const val DEFAULT_ZOOM = 13.0
    const val MIN_ZOOM = 3.0
    const val MAX_ZOOM = 19.0

    // Bangladesh Bounding Box
    val BANGLADESH_BOUNDS = LatLngBounds.Builder()
        .include(LatLng(20.50, 88.00)) // Southwest
        .include(LatLng(26.70, 92.70)) // Northeast
        .build()

    // Street view style JSON using OpenStreetMap standard raster tiles
    val OSM_STREET_STYLE_JSON = """
    {
      "version": 8,
      "name": "CellScope OSM Streets",
      "sources": {
        "osm-source": {
          "type": "raster",
          "tiles": [
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
          ],
          "tileSize": 256,
          "attribution": "© OpenStreetMap contributors"
        }
      },
      "layers": [
        {
          "id": "osm-layer",
          "type": "raster",
          "source": "osm-source",
          "minzoom": 0,
          "maxzoom": 19
        }
      ]
    }
    """.trimIndent()

    // ESRI World Street Map Style JSON (Keyless, high-reliability global coverage)
    val ESRI_STREETS_STYLE_JSON = """
    {
      "version": 8,
      "name": "CellScope ESRI Streets",
      "sources": {
        "esri-source": {
          "type": "raster",
          "tiles": [
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}.png"
          ],
          "tileSize": 256,
          "attribution": "Tiles © Esri"
        }
      },
      "layers": [
        {
          "id": "esri-layer",
          "type": "raster",
          "source": "esri-source",
          "minzoom": 0,
          "maxzoom": 19
        }
      ]
    }
    """.trimIndent()
}
