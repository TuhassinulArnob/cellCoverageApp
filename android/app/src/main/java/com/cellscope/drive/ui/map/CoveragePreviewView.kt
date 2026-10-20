package com.cellscope.drive.ui.map

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.cellscope.drive.analytics.CoverageHoleRecord
import com.cellscope.drive.analytics.CoverageSurface
import com.cellscope.drive.analytics.MapMetricLayer
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polygon
import org.maplibre.android.annotations.PolygonOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Real basemap-backed geographic coverage preview for the CellScope Dashboard.
 * Powered by the MapLibre engine with actual roads, landmarks, and dataset coordinates.
 * Renders measured sample routes, IDW heatmap surfaces, and weak-coverage zone overlays.
 */
class CoveragePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), OnMapReadyCallback {

    private val mapView: MapView
    private var mapLibreMap: MapLibreMap? = null
    private var isMapReady = false

    private var measurements: List<LteMeasurementEntity> = emptyList()
    private var coverageSurface: CoverageSurface? = null
    private var coverageHoles: List<CoverageHoleRecord> = emptyList()

    private val routePolylines = mutableListOf<Polyline>()
    private val heatmapPolygons = mutableListOf<Polygon>()
    private val holePolygons = mutableListOf<Polygon>()
    private val holeMarkers = mutableListOf<Marker>()

    var onOpenCoveragePlannerRequested: (() -> Unit)? = null

    private val statusBanner: TextView
    private val legendBanner: TextView
    private val btnOpenPlanner: TextView

    init {
        // 1. Initialize MapLibre MapView for true geographic basemap
        mapView = MapView(context)
        val mapParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(mapView, mapParams)

        // 2. Status banner (offline / tile status)
        statusBanner = TextView(context).apply {
            text = "Loading geographic basemap…"
            setTextColor(Color.WHITE)
            textSize = 9.5f
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#CC1E1E1E"))
            visibility = View.VISIBLE
        }
        val statusParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = 10
            leftMargin = 10
        }
        addView(statusBanner, statusParams)

        // 3. Open Coverage Planner Action Button
        btnOpenPlanner = TextView(context).apply {
            text = "OPEN COVERAGE PLANNER ↗"
            setTextColor(Color.WHITE)
            textSize = 9f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(18, 10, 18, 10)
            setBackgroundColor(Color.parseColor("#E60284C7"))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onOpenCoveragePlannerRequested?.invoke()
            }
        }
        val btnParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 10
            rightMargin = 10
        }
        addView(btnOpenPlanner, btnParams)

        // 4. Bottom RSRP Legend Banner
        legendBanner = TextView(context).apply {
            text = "RSRP: Exc ≥ -80 (Green) • Good -90..-81 (Lime) • Fair -100..-91 (Yellow) • Poor < -100 (Red)"
            setTextColor(Color.WHITE)
            textSize = 8.5f
            setPadding(16, 6, 16, 6)
            setBackgroundColor(Color.parseColor("#E6111827"))
            visibility = View.VISIBLE
        }
        val legendParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        }
        addView(legendBanner, legendParams)

        mapView.addOnDidFailLoadingMapListener { error ->
            post {
                statusBanner.text = "OpenStreetMap Basemap (Offline Cache)"
                statusBanner.visibility = View.VISIBLE
            }
        }

        mapView.getMapAsync(this)
    }

    override fun onMapReady(map: MapLibreMap) {
        this.mapLibreMap = map
        this.isMapReady = true

        map.uiSettings.isAttributionEnabled = false
        map.uiSettings.isLogoEnabled = false
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.isRotateGesturesEnabled = false
        map.uiSettings.isTiltGesturesEnabled = false
        map.uiSettings.isZoomGesturesEnabled = true
        map.uiSettings.isScrollGesturesEnabled = true

        // Load keyless Bangladesh Carto Dark / OpenStreetMap basemap style
        map.setStyle(Style.Builder().fromUri("asset://carto_dark.json")) {
            statusBanner.text = "OpenStreetMap Geographic Basemap"
            statusBanner.postDelayed({
                if (statusBanner.text.contains("OpenStreetMap")) {
                    statusBanner.visibility = View.GONE
                }
            }, 2500)

            // Initial center over Bangladesh
            val initialPos = CameraPosition.Builder()
                .target(MapConfig.BANGLADESH_CENTER)
                .zoom(MapConfig.DEFAULT_ZOOM)
                .build()
            map.cameraPosition = initialPos

            renderDatasetOverlays()
        }

        map.addOnMapClickListener {
            onOpenCoveragePlannerRequested?.invoke()
            true
        }
    }

    fun setData(
        newMeasurements: List<LteMeasurementEntity>,
        newSurface: CoverageSurface? = null,
        newHoles: List<CoverageHoleRecord> = emptyList(),
        title: String? = null
    ) {
        this.measurements = newMeasurements
        this.coverageSurface = newSurface
        this.coverageHoles = newHoles
        renderDatasetOverlays()
    }

    fun setMeasurements(newMeasurements: List<LteMeasurementEntity>) {
        this.measurements = newMeasurements
        renderDatasetOverlays()
    }

    private fun renderDatasetOverlays() {
        val map = mapLibreMap ?: return
        if (!isMapReady) return

        clearAnnotations(map)

        val valid = measurements.filter { it.latitude != null && it.longitude != null && it.isGpsValid }
        if (valid.isEmpty()) {
            statusBanner.text = "No GPS route points in selected session"
            statusBanner.visibility = View.VISIBLE
            return
        }

        // 1. Draw continuous IDW Heatmap surface if present
        coverageSurface?.let { surface ->
            val radiusDeg = surface.resolutionDegrees / 2.0
            val cellsToRender = if (surface.cells.size <= 400) {
                surface.cells
            } else {
                val step = surface.cells.size / 400.0
                (0 until 400).map { i -> surface.cells[(i * step).toInt().coerceAtMost(surface.cells.size - 1)] }
            }

            for (cell in cellsToRender) {
                val rsrpVal = cell.interpolatedRsrp ?: continue
                // Suppress unmeasured low-confidence extrapolation far from survey track
                if (cell.confidenceScore < 0.20f && !cell.isDirectlyMeasured) continue
                val color = getRsrpColor(rsrpVal)
                val alpha = if (cell.isDirectlyMeasured) 160 else if (cell.confidenceScore >= 0.50f) 110 else 70
                val blendedColor = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

                val points = createCirclePolygon(cell.lat, cell.lon, radiusDeg, 6)
                val poly = map.addPolygon(
                    PolygonOptions()
                        .addAll(points)
                        .fillColor(blendedColor)
                        .strokeColor(blendedColor) // Eliminate white stroke artifact by matching strokeColor to fillColor
                )
                heatmapPolygons.add(poly)
            }
        }

        // 2. Draw measured route track polyline
        var lastPlotted = valid.first()
        for (i in 1 until valid.size) {
            val curr = valid[i]
            val lat1 = lastPlotted.latitude ?: continue
            val lon1 = lastPlotted.longitude ?: continue
            val lat2 = curr.latitude ?: continue
            val lon2 = curr.longitude ?: continue

            val dLatM = (lat2 - lat1) * 111320.0
            val dLonM = (lon2 - lon1) * 111320.0 * cos(Math.toRadians(lat1))
            val distM = Math.sqrt(dLatM * dLatM + dLonM * dLonM)

            // Break segment across long gaps
            if (curr.timestampMs - lastPlotted.timestampMs > 15000L || distM > 250.0) {
                lastPlotted = curr
                continue
            }

            if (distM >= 6.0 || i == valid.size - 1) {
                val segmentColor = getRsrpColor(lastPlotted.rsrpDbm?.toDouble() ?: -115.0)
                val polyline = map.addPolyline(
                    PolylineOptions()
                        .add(LatLng(lat1, lon1), LatLng(lat2, lon2))
                        .color(segmentColor)
                        .width(4.5f)
                )
                routePolylines.add(polyline)
                lastPlotted = curr
            }
        }

        // 3. Draw weak-coverage zones
        for (hole in coverageHoles) {
            val color = Color.parseColor(hole.severity.colorHex)
            val padLat = ((hole.maxLat - hole.minLat).coerceAtLeast(0.0004)) / 2.0
            val padLon = ((hole.maxLon - hole.minLon).coerceAtLeast(0.0004)) / 2.0
            val polyPoints = listOf(
                LatLng(hole.centerLat - padLat, hole.centerLon - padLon),
                LatLng(hole.centerLat - padLat, hole.centerLon + padLon),
                LatLng(hole.centerLat + padLat, hole.centerLon + padLon),
                LatLng(hole.centerLat + padLat, hole.centerLon - padLon)
            )
            val poly = map.addPolygon(
                PolygonOptions()
                    .addAll(polyPoints)
                    .fillColor(Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)))
                    .strokeColor(color)
            )
            holePolygons.add(poly)

            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(hole.centerLat, hole.centerLon))
                    .title("[${hole.stableZoneId}] ${hole.severity.label}")
                    .snippet("Worst RSRP: ${hole.minRsrpDbm} dBm")
            )
            holeMarkers.add(marker)
        }

        // 4. Fit bounds to surveyed route with padding
        val builder = LatLngBounds.Builder()
        for (m in valid) {
            builder.include(LatLng(m.latitude!!, m.longitude!!))
        }
        try {
            val bounds = builder.build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 40))
        } catch (e: Exception) {
            val latest = valid.last()
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latest.latitude!!, latest.longitude!!), 13.0))
        }
    }

    private fun clearAnnotations(map: MapLibreMap) {
        for (p in routePolylines) map.removePolyline(p)
        routePolylines.clear()
        for (p in heatmapPolygons) map.removePolygon(p)
        heatmapPolygons.clear()
        for (p in holePolygons) map.removePolygon(p)
        holePolygons.clear()
        for (m in holeMarkers) map.removeMarker(m)
        holeMarkers.clear()
    }

    private fun getRsrpColor(rsrpVal: Double): Int {
        return when {
            rsrpVal >= -80.0 -> Color.parseColor("#00E676") // Excellent
            rsrpVal >= -90.0 -> Color.parseColor("#76FF03") // Good
            rsrpVal >= -100.0 -> Color.parseColor("#FFD600") // Fair
            else -> Color.parseColor("#D50000") // Poor (< -100 dBm)
        }
    }

    private fun createCirclePolygon(centerLat: Double, centerLon: Double, radiusDeg: Double, pointsCount: Int): List<LatLng> {
        val points = mutableListOf<LatLng>()
        for (i in 0 until pointsCount) {
            val angle = Math.toRadians((i * 360.0) / pointsCount)
            val dLat = radiusDeg * sin(angle)
            val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.1)
            val dLon = (radiusDeg * cos(angle)) / cosLat
            points.add(LatLng(centerLat + dLat, centerLon + dLon))
        }
        return points
    }

    // Forward MapView Lifecycle methods
    fun onCreate(savedInstanceState: Bundle?) { mapView.onCreate(savedInstanceState) }
    fun onStart() { mapView.onStart() }
    fun onResume() { mapView.onResume() }
    fun onPause() { mapView.onPause() }
    fun onStop() { mapView.onStop() }
    fun onDestroy() { mapView.onDestroy() }
    fun onSaveInstanceState(outState: Bundle) { mapView.onSaveInstanceState(outState) }
    fun onLowMemory() { mapView.onLowMemory() }
}
