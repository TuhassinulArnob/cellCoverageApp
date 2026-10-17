package com.cellscope.drive.ui.map

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.cellscope.drive.R
import com.cellscope.drive.analytics.CoverageSurface
import com.cellscope.drive.analytics.EstimatedTower
import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.telephony.LteBandTable
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

class DriveMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), OnMapReadyCallback {

    private val mapView: MapView
    private var mapLibreMap: MapLibreMap? = null
    private var isMapReady = false

    // Layer visibility flags
    var showRouteSegments: Boolean = true
        set(value) { field = value; updateOverlays() }
    var showHeatmapOverlay: Boolean = false
        set(value) { field = value; updateOverlays() }
    var showTransitions: Boolean = true
        set(value) { field = value; updateOverlays() }
    var showTowers: Boolean = false
        set(value) { field = value; updateOverlays() }

    // Telemetry Data lists
    private var measurements: List<LteMeasurementEntity> = emptyList()
    private var transitions: List<CellTransitionEntity> = emptyList()
    private var coverageSurface: CoverageSurface? = null
    private var estimatedTowers: List<EstimatedTower> = emptyList()

    // Active Annotations
    private val routePolylines = mutableListOf<Polyline>()
    private val heatmapPolygons = mutableListOf<Polygon>()
    private val towerMarkers = mutableListOf<Marker>()
    private val towerRingPolylines = mutableListOf<Polyline>()
    private val transitionMarkers = mutableListOf<Marker>()
    private val measurementMarkers = mutableListOf<Marker>()
    private var currentPositionMarker: Marker? = null

    // Point selection callback
    var onMeasurementSelected: ((LteMeasurementEntity?) -> Unit)? = null
    private var selectedMeasurement: LteMeasurementEntity? = null
    private var overlayUpdateRunnable: Runnable? = null

    // UI overlays
    private val statusBanner: TextView
    private val inspectionCard: LinearLayout
    private val tvInspectPci: TextView
    private val tvInspectMetrics: TextView
    private val tvInspectDetails: TextView

    init {
        // 1. Initialize MapLibre MapView
        mapView = MapView(context)
        val mapParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(mapView, mapParams)

        // 2. Status banner (shows loading, offline status, or tile warnings)
        statusBanner = TextView(context).apply {
            text = "Loading Bangladesh basemap…"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(24, 12, 24, 12)
            setBackgroundColor(Color.parseColor("#CC1E1E1E"))
            visibility = View.VISIBLE
        }
        val bannerParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 16
        }
        addView(statusBanner, bannerParams)

        // 3. Inspection card popup
        inspectionCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(Color.parseColor("#E61E1E1E"))
            visibility = View.GONE
        }
        tvInspectPci = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        tvInspectMetrics = TextView(context).apply {
            setTextColor(Color.parseColor("#00E676"))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        tvInspectDetails = TextView(context).apply {
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 11f
        }
        inspectionCard.addView(tvInspectPci)
        inspectionCard.addView(tvInspectMetrics)
        inspectionCard.addView(tvInspectDetails)

        val cardParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = 16
            leftMargin = 24
            rightMargin = 24
        }
        addView(inspectionCard, cardParams)

        inspectionCard.setOnClickListener {
            inspectionCard.visibility = View.GONE
        }

        mapView.addOnDidFailLoadingMapListener { error ->
            android.util.Log.e("DriveMapView", "Map tile loading error: $error")
            post {
                statusBanner.text = "Map Tile Alert: $error"
                statusBanner.visibility = View.VISIBLE
            }
        }

        mapView.getMapAsync(this)
    }

    override fun onMapReady(map: MapLibreMap) {
        this.mapLibreMap = map
        this.isMapReady = true

        // Configure map gestures and settings
        map.uiSettings.isAttributionEnabled = true
        map.uiSettings.isLogoEnabled = false
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isRotateGesturesEnabled = true
        map.uiSettings.isTiltGesturesEnabled = true
        map.uiSettings.isZoomGesturesEnabled = true

        // Load keyless Bangladesh basemap style (OpenStreetMap Standard)
        map.setStyle(Style.Builder().fromUri("asset://carto_dark.json")) { style ->
            statusBanner.text = "Bangladesh Basemap • OpenStreetMap"
            statusBanner.postDelayed({
                if (statusBanner.text.contains("OpenStreetMap")) {
                    statusBanner.visibility = View.GONE
                }
            }, 3000)

            // Initial camera position over Dhaka, Bangladesh
            val initialPos = CameraPosition.Builder()
                .target(MapConfig.BANGLADESH_CENTER)
                .zoom(MapConfig.DEFAULT_ZOOM)
                .build()
            map.cameraPosition = initialPos

            // If measurements arrived prior to map style ready, render them now
            updateOverlays()
        }

        map.setOnMarkerClickListener { marker ->
            handleMarkerClick(marker)
            true
        }

        map.addOnMapClickListener { clickLatLng ->
            val valid = measurements.filter { it.latitude != null && it.longitude != null && it.isGpsValid }
            var foundNearby = false
            if (valid.isNotEmpty()) {
                val closest = valid.minByOrNull { m ->
                    val dLat = (m.latitude ?: 0.0) - clickLatLng.latitude
                    val dLon = (m.longitude ?: 0.0) - clickLatLng.longitude
                    dLat * dLat + dLon * dLon
                }
                if (closest != null) {
                    val dLat = (closest.latitude ?: 0.0) - clickLatLng.latitude
                    val dLon = (closest.longitude ?: 0.0) - clickLatLng.longitude
                    val distSq = dLat * dLat + dLon * dLon
                    // ~0.0008 deg is ~80m radius at Bangladesh latitudes
                    if (distSq < 0.0008 * 0.0008) {
                        showInspectionCard(closest)
                        selectedMeasurement = closest
                        onMeasurementSelected?.invoke(closest)
                        foundNearby = true
                    }
                }
            }

            if (!foundNearby) {
                inspectionCard.visibility = View.GONE
                selectedMeasurement = null
                onMeasurementSelected?.invoke(null)
            }
            true
        }
    }

    fun setData(
        newMeasurements: List<LteMeasurementEntity>,
        newTransitions: List<CellTransitionEntity> = emptyList(),
        newSurface: CoverageSurface? = null,
        newTowers: List<EstimatedTower> = emptyList()
    ) {
        this.measurements = newMeasurements
        this.transitions = newTransitions
        this.coverageSurface = newSurface
        this.estimatedTowers = newTowers

        updateOverlays()

        // Auto center on initial valid fix if not moved yet
        val latest = measurements.lastOrNull { it.latitude != null && it.longitude != null && it.isGpsValid }
        if (latest != null && measurements.size == 1) {
            recenterOnLatest()
        }
    }

    private fun updateOverlays() {
        val map = mapLibreMap ?: return
        if (!isMapReady) return

        overlayUpdateRunnable?.let { removeCallbacks(it) }
        val runnable = Runnable {
            if (!isMapReady) return@Runnable
            clearAnnotations(map)

            // 1. Draw Heatmap surface layer
            if (showHeatmapOverlay && coverageSurface != null) {
                drawCoverageHeatmap(map, coverageSurface!!)
            }

            // 2. Draw colored route segments
            if (showRouteSegments && measurements.isNotEmpty()) {
                drawRouteTrack(map)
            }

            // 3. Draw estimated towers
            if (showTowers && estimatedTowers.isNotEmpty()) {
                drawEstimatedTowers(map)
            }

            // 4. Draw cell transition pins
            if (showTransitions && transitions.isNotEmpty()) {
                drawTransitionMarkers(map)
            }

            // 5. Draw current location marker
            drawCurrentPositionMarker(map)
        }
        overlayUpdateRunnable = runnable
        post(runnable)
    }

    private fun clearAnnotations(map: MapLibreMap) {
        for (p in routePolylines) map.removePolyline(p)
        routePolylines.clear()

        for (p in heatmapPolygons) map.removePolygon(p)
        heatmapPolygons.clear()

        for (m in towerMarkers) map.removeMarker(m)
        towerMarkers.clear()

        for (r in towerRingPolylines) map.removePolyline(r)
        towerRingPolylines.clear()

        for (m in transitionMarkers) map.removeMarker(m)
        transitionMarkers.clear()

        for (m in measurementMarkers) map.removeMarker(m)
        measurementMarkers.clear()

        currentPositionMarker?.let { map.removeMarker(it) }
        currentPositionMarker = null
    }

    private fun drawRouteTrack(map: MapLibreMap) {
        val valid = measurements.filter { it.latitude != null && it.longitude != null && it.isGpsValid }
        if (valid.isEmpty()) return

        // Segment consecutive points and color according to RSRP
        for (i in 0 until valid.size - 1) {
            val p1 = valid[i]
            val p2 = valid[i + 1]

            val lat1 = p1.latitude ?: continue
            val lon1 = p1.longitude ?: continue
            val lat2 = p2.latitude ?: continue
            val lon2 = p2.longitude ?: continue

            val segmentColor = getRsrpColor(p1.rsrpDbm?.toDouble())
            val polyline = map.addPolyline(
                PolylineOptions()
                    .add(LatLng(lat1, lon1), LatLng(lat2, lon2))
                    .color(segmentColor)
                    .width(5f)
            )
            routePolylines.add(polyline)
        }
        // Clutter-free: default shadow-casting markers are omitted so route stays clear.
        // Tap inspection along the route is handled via map.addOnMapClickListener.
    }

    private fun drawCoverageHeatmap(map: MapLibreMap, surface: CoverageSurface) {
        val radiusDeg = surface.resolutionDegrees / 2.0
        // Cap max cells rendered to preserve interactive pan/zoom responsiveness
        val cellsToRender = surface.cells.take(400)

        for (cell in cellsToRender) {
            val color = getRsrpColor(cell.interpolatedRsrp)
            // Subtle translucent alpha (15..55 out of 255) ensures basemap labels and streets remain clearly visible
            val alpha = (cell.confidenceScore * 50).toInt().coerceIn(15, 55)
            val blendedColor = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

            // Approximate circular polygon around cell centroid
            val points = createCirclePolygon(cell.lat, cell.lon, radiusDeg, 8)
            val polygon = map.addPolygon(
                PolygonOptions()
                    .addAll(points)
                    .fillColor(blendedColor)
                    .strokeColor(Color.TRANSPARENT)
            )
            heatmapPolygons.add(polygon)
        }
    }

    private fun drawEstimatedTowers(map: MapLibreMap) {
        for (tower in estimatedTowers) {
            val pos = LatLng(tower.estimatedLat, tower.estimatedLon)

            // Tower Marker
            val marker = map.addMarker(
                MarkerOptions()
                    .position(pos)
                    .title("TOWER: PCI ${tower.pci}")
                    .snippet("Est. Radius: ±${tower.estimatedCoverageRadiusM.toInt()}m | Conf: ${(tower.confidenceScore * 100).toInt()}%")
            )
            towerMarkers.add(marker)

            // Coverage ring outline as thin polyline (no massive polygon fill)
            val radiusDeg = (tower.estimatedCoverageRadiusM / 111320.0).coerceIn(0.002, 0.02)
            val ringPoints = createCirclePolygon(tower.estimatedLat, tower.estimatedLon, radiusDeg, 24)
            val closedPoints = ringPoints + ringPoints.first()
            val ring = map.addPolyline(
                PolylineOptions()
                    .addAll(closedPoints)
                    .color(Color.parseColor("#00E5FF"))
                    .width(2f)
            )
            towerRingPolylines.add(ring)
        }
    }

    private fun drawTransitionMarkers(map: MapLibreMap) {
        for (t in transitions) {
            val lat = t.latitude ?: continue
            val lon = t.longitude ?: continue

            val title = if (t.eventType == "CONFIRMED_HANDOVER") "Confirmed Handover" else "Cell Transition"
            val snippet = "${t.fromPci ?: "?"} → ${t.toPci ?: "?"} (${t.details})"

            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(lat, lon))
                    .title(title)
                    .snippet(snippet)
            )
            transitionMarkers.add(marker)
        }
    }

    private fun drawCurrentPositionMarker(map: MapLibreMap) {
        val latest = measurements.lastOrNull { it.latitude != null && it.longitude != null && it.isGpsValid } ?: return
        val pos = LatLng(latest.latitude!!, latest.longitude!!)

        val marker = map.addMarker(
            MarkerOptions()
                .position(pos)
                .title("CURRENT POSITION")
                .snippet("PCI ${latest.servingPci ?: "—"} • ${latest.rsrpDbm?.let { "$it dBm" } ?: "No Signal"}")
        )
        currentPositionMarker = marker
    }

    private fun handleMarkerClick(marker: Marker) {
        val pos = marker.position
        val closest = measurements.minByOrNull {
            if (it.latitude == null || it.longitude == null) Double.MAX_VALUE
            else {
                val dLat = it.latitude!! - pos.latitude
                val dLon = it.longitude!! - pos.longitude
                dLat * dLat + dLon * dLon
            }
        }

        if (closest != null) {
            showInspectionCard(closest)
            selectedMeasurement = closest
            onMeasurementSelected?.invoke(closest)
        }
    }

    private fun showInspectionCard(m: LteMeasurementEntity) {
        val bandInfo = LteBandTable.resolveEarfcn(m.earfcn)
        val bandText = bandInfo?.let { "Band ${it.bandNumber} (${it.designation}) • DL ${it.dlFrequencyMhz} MHz" } ?: "EARFCN ${m.earfcn ?: "—"}"

        tvInspectPci.text = "PCI: ${m.servingPci ?: "—"}  •  TAC: ${m.tac ?: "—"}  •  ECI: ${m.eci ?: "—"}"
        tvInspectMetrics.text = "RSRP: ${m.rsrpDbm?.let { "$it dBm" } ?: "—"}  |  RSRQ: ${m.rsrqDb?.let { "$it dB" } ?: "—"}  |  SINR: ${m.sinrDb?.let { "$it dB" } ?: "—"}"
        tvInspectMetrics.setTextColor(getRsrpColor(m.rsrpDbm?.toDouble()))

        val speedText = m.speedKmh?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "0 km/h"
        val timeText = m.timestampIso.substringAfter("T").substringBefore("Z")
        tvInspectDetails.text = "$bandText\nSpeed: $speedText  •  Time: $timeText (Tap to dismiss)"

        inspectionCard.visibility = View.VISIBLE
    }

    fun fitBounds() {
        val map = mapLibreMap ?: return
        val valid = measurements.filter { it.latitude != null && it.longitude != null && it.isGpsValid }
        if (valid.isEmpty()) return

        val builder = LatLngBounds.Builder()
        for (m in valid) {
            builder.include(LatLng(m.latitude!!, m.longitude!!))
        }

        try {
            val bounds = builder.build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
        } catch (e: Exception) {
            recenterOnLatest()
        }
    }

    fun recenterOnLatest() {
        val map = mapLibreMap ?: return
        val latest = measurements.lastOrNull { it.latitude != null && it.longitude != null && it.isGpsValid }
        if (latest != null) {
            val pos = LatLng(latest.latitude!!, latest.longitude!!)
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 15.0))
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(MapConfig.BANGLADESH_CENTER, MapConfig.DEFAULT_ZOOM))
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

    private fun getRsrpColor(rsrp: Double?): Int {
        return when {
            rsrp == null -> Color.parseColor("#757575")
            rsrp >= -80.0 -> Color.parseColor("#00E676") // Excellent
            rsrp >= -90.0 -> Color.parseColor("#76FF03") // Good
            rsrp >= -100.0 -> Color.parseColor("#FFD600") // Fair
            rsrp >= -110.0 -> Color.parseColor("#FF6D00") // Poor
            else -> Color.parseColor("#D50000") // Very Poor
        }
    }

    // MapView Lifecycle Callbacks
    fun onCreate(savedInstanceState: Bundle?) {
        mapView.onCreate(savedInstanceState)
    }

    fun onStart() {
        mapView.onStart()
    }

    fun onResume() {
        mapView.onResume()
    }

    fun onPause() {
        mapView.onPause()
    }

    fun onStop() {
        mapView.onStop()
    }

    fun onDestroy() {
        mapView.onDestroy()
    }

    fun onSaveInstanceState(outState: Bundle) {
        mapView.onSaveInstanceState(outState)
    }

    fun onLowMemory() {
        mapView.onLowMemory()
    }
}
