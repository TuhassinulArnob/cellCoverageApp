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
import com.cellscope.drive.analytics.CoverageCell
import com.cellscope.drive.analytics.CoverageSurface
import com.cellscope.drive.analytics.EstimatedTower
import com.cellscope.drive.analytics.MapMetricLayer
import com.cellscope.drive.data.entity.CallReliabilityEventEntity
import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.data.entity.NetworkContinuityEventEntity
import com.cellscope.drive.data.entity.ServiceHoleEntity
import com.cellscope.drive.model.NetworkGeneration
import com.cellscope.drive.telephony.LteBandTable
import com.cellscope.drive.util.TelecomTimeUtils
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class GenerationFilter(val label: String) {
    ALL("All"),
    GEN_5G("5G NR"),
    GEN_4G("4G LTE"),
    GEN_3G("3G"),
    GEN_2G("2G"),
    DOWNGRADES_HOLES("Downgrades & Holes")
}

class DriveMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), OnMapReadyCallback {

    private val mapView: MapView
    private var mapLibreMap: MapLibreMap? = null
    private var isMapReady = false

    // Metric Layer selector
    var activeMetricLayer: MapMetricLayer = MapMetricLayer.RSRP
        set(value) {
            field = value
            updateLegendBanner()
            updateOverlays()
        }

    // Generation filter selector
    var activeGenerationFilter: GenerationFilter = GenerationFilter.ALL
        set(value) {
            field = value
            updateOverlays()
        }

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
    private var continuityEvents: List<NetworkContinuityEventEntity> = emptyList()
    private var serviceHoles: List<ServiceHoleEntity> = emptyList()
    private var callEvents: List<CallReliabilityEventEntity> = emptyList()

    // Active Annotations
    private val routePolylines = mutableListOf<Polyline>()
    private val heatmapPolygons = mutableListOf<Polygon>()
    private val towerMarkers = mutableListOf<Marker>()
    private val towerRingPolylines = mutableListOf<Polyline>()
    private val transitionMarkers = mutableListOf<Marker>()
    private val continuityMarkers = mutableListOf<Marker>()
    private val holeMarkers = mutableListOf<Marker>()
    private val measurementMarkers = mutableListOf<Marker>()
    private var currentPositionMarker: Marker? = null
    private var scrubberMarker: Marker? = null

    // Point selection callback
    var onMeasurementSelected: ((LteMeasurementEntity?) -> Unit)? = null
    private var selectedMeasurement: LteMeasurementEntity? = null
    private var overlayUpdateRunnable: Runnable? = null

    // UI overlays
    private val statusBanner: TextView
    private val legendBanner: TextView
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

        // 3. Floating Metric Legend Banner
        legendBanner = TextView(context).apply {
            text = "Metric: RSRP (dBm) • Exc ≥ -80 • Good -90 • Fair -100 • Poor < -100"
            setTextColor(Color.WHITE)
            textSize = 9.5f
            setPadding(20, 8, 20, 8)
            setBackgroundColor(Color.parseColor("#CC1E1E1E"))
            visibility = View.VISIBLE
        }
        val legendParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = 16
            leftMargin = 16
        }
        addView(legendBanner, legendParams)

        // 4. Inspection card popup
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

            // If no direct track point was tapped, allow inspecting interpolated coverage cell
            if (!foundNearby && showHeatmapOverlay && coverageSurface != null) {
                val closestCell = coverageSurface!!.cells.minByOrNull { c ->
                    val dLat = c.lat - clickLatLng.latitude
                    val dLon = c.lon - clickLatLng.longitude
                    dLat * dLat + dLon * dLon
                }
                if (closestCell != null) {
                    val dLat = closestCell.lat - clickLatLng.latitude
                    val dLon = closestCell.lon - clickLatLng.longitude
                    val distSq = dLat * dLat + dLon * dLon
                    if (distSq < 0.0012 * 0.0012) { // within ~120m search radius
                        showCellInspectionCard(closestCell)
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

    private fun updateLegendBanner() {
        legendBanner.text = when (activeMetricLayer) {
            MapMetricLayer.RSRP -> "Metric: RSRP (dBm) • Exc ≥ -80 • Good -90 • Fair -100 • Poor < -100"
            MapMetricLayer.RSRQ -> "Metric: RSRQ (dB) • Exc ≥ -10 • Good -15 • Fair -19 • Poor < -19"
            MapMetricLayer.SINR -> "Metric: SINR (dB) • Clean ≥ 15 • Normal 5 • Noisy 0 • Interf < 0"
            MapMetricLayer.THROUGHPUT -> "Metric: DL Throughput (Mbps) • ≥ 50 • ≥ 20 • ≥ 5 • < 5"
            MapMetricLayer.GENERATION -> "Generation • 5G NR (Violet) • 4G LTE (Blue) • 3G (Orange) • 2G (Brown) • OOS (Red)"
        }
    }

    fun setData(
        newMeasurements: List<LteMeasurementEntity>,
        newTransitions: List<CellTransitionEntity> = emptyList(),
        newSurface: CoverageSurface? = null,
        newTowers: List<EstimatedTower> = emptyList(),
        newContinuityEvents: List<NetworkContinuityEventEntity> = emptyList(),
        newServiceHoles: List<ServiceHoleEntity> = emptyList(),
        newCallEvents: List<CallReliabilityEventEntity> = emptyList()
    ) {
        this.measurements = newMeasurements
        this.transitions = newTransitions
        this.coverageSurface = newSurface
        this.estimatedTowers = newTowers
        this.continuityEvents = newContinuityEvents
        this.serviceHoles = newServiceHoles
        this.callEvents = newCallEvents

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

            // 4. Draw cell transition and continuity pins
            if (showTransitions) {
                if (transitions.isNotEmpty()) {
                    drawTransitionMarkers(map)
                }
                if (continuityEvents.isNotEmpty() || serviceHoles.isNotEmpty()) {
                    drawContinuityAndHoleMarkers(map)
                }
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

        for (m in continuityMarkers) map.removeMarker(m)
        continuityMarkers.clear()

        for (m in holeMarkers) map.removeMarker(m)
        holeMarkers.clear()

        for (m in measurementMarkers) map.removeMarker(m)
        measurementMarkers.clear()

        scrubberMarker?.let { map.removeMarker(it) }
        scrubberMarker = null

        currentPositionMarker?.let { map.removeMarker(it) }
        currentPositionMarker = null
    }

    private fun drawRouteTrack(map: MapLibreMap) {
        val valid = measurements.filter { it.latitude != null && it.longitude != null && it.isGpsValid }
        if (valid.isEmpty()) return

        // Check if entire session is stationary (e.g. indoor test where all coordinates cluster within 25m)
        val lats = valid.mapNotNull { it.latitude }
        val lons = valid.mapNotNull { it.longitude }
        val minLat = lats.minOrNull() ?: 0.0
        val maxLat = lats.maxOrNull() ?: 0.0
        val minLon = lons.minOrNull() ?: 0.0
        val maxLon = lons.maxOrNull() ?: 0.0

        val latSpanM = (maxLat - minLat) * 111320.0
        val lonSpanM = (maxLon - minLon) * 111320.0 * Math.cos(Math.toRadians((minLat + maxLat) / 2.0))
        val maxSpanM = Math.sqrt(latSpanM * latSpanM + lonSpanM * lonSpanM)

        if (maxSpanM < 25.0 && valid.size >= 3) {
            // Stationary survey session: suppress unreadable spiderweb of jitter lines.
            // Draw a single representative stationary survey anchor polygon disc at the centroid
            val centerLat = (minLat + maxLat) / 2.0
            val centerLon = (minLon + maxLon) / 2.0
            val latest = valid.last()
            val color = if (activeMetricLayer == MapMetricLayer.GENERATION) {
                val gen = NetworkGeneration.fromString(latest.networkGen ?: latest.networkType)
                Color.parseColor(gen.colorHex)
            } else {
                val metricVal = when (activeMetricLayer) {
                    MapMetricLayer.RSRP -> latest.rsrpDbm?.toDouble()
                    MapMetricLayer.RSRQ -> latest.rsrqDb?.toDouble()
                    MapMetricLayer.SINR -> latest.sinrDb
                    MapMetricLayer.THROUGHPUT -> null
                    MapMetricLayer.GENERATION -> null
                }
                getMetricColor(metricVal, activeMetricLayer)
            }
            val circlePoints = createCirclePolygon(centerLat, centerLon, 0.00010, 16) // ~10m radius
            val polygon = map.addPolygon(
                PolygonOptions()
                    .addAll(circlePoints)
                    .fillColor(Color.argb(120, Color.red(color), Color.green(color), Color.blue(color)))
                    .strokeColor(color)
            )
            heatmapPolygons.add(polygon)
            return
        }

        // Dynamic drive track: filter consecutive sub-4m jitter micro-segments so line is smooth
        // Also break route segments when time delta > 10s or distance > 150m
        var lastPlotted = valid.first()
        for (i in 1 until valid.size) {
            val curr = valid[i]
            val lat1 = lastPlotted.latitude ?: continue
            val lon1 = lastPlotted.longitude ?: continue
            val lat2 = curr.latitude ?: continue
            val lon2 = curr.longitude ?: continue

            val dLatM = (lat2 - lat1) * 111320.0
            val dLonM = (lon2 - lon1) * 111320.0 * Math.cos(Math.toRadians(lat1))
            val distM = Math.sqrt(dLatM * dLatM + dLonM * dLonM)

            // Break segment across time gap (>10s) or coordinate distance jump (>150m)
            val timeDeltaMs = curr.timestampMs - lastPlotted.timestampMs
            if (timeDeltaMs > 10000L || distM > 150.0) {
                lastPlotted = curr
                continue
            }

            // Apply generation filter
            val currGen = NetworkGeneration.fromString(curr.networkGen ?: curr.networkType)
            val matchFilter = when (activeGenerationFilter) {
                GenerationFilter.ALL -> true
                GenerationFilter.GEN_5G -> currGen == NetworkGeneration.GEN_5G
                GenerationFilter.GEN_4G -> currGen == NetworkGeneration.GEN_4G
                GenerationFilter.GEN_3G -> currGen == NetworkGeneration.GEN_3G
                GenerationFilter.GEN_2G -> currGen == NetworkGeneration.GEN_2G
                GenerationFilter.DOWNGRADES_HOLES -> {
                    val nearHole = serviceHoles.any { abs(it.timestampMs - curr.timestampMs) <= 5000L }
                    val nearTransition = continuityEvents.any { abs(it.timestampMs - curr.timestampMs) <= 5000L }
                    nearHole || nearTransition
                }
            }
            if (!matchFilter) {
                lastPlotted = curr
                continue
            }

            // Plot segment if displacement >= 4.0m or if it's the final point of the session
            if (distM >= 4.0 || i == valid.size - 1) {
                val segmentColor = if (activeMetricLayer == MapMetricLayer.GENERATION) {
                    val segGen = NetworkGeneration.fromString(lastPlotted.networkGen ?: lastPlotted.networkType)
                    Color.parseColor(segGen.colorHex)
                } else {
                    val metricVal = when (activeMetricLayer) {
                        MapMetricLayer.RSRP -> lastPlotted.rsrpDbm?.toDouble()
                        MapMetricLayer.RSRQ -> lastPlotted.rsrqDb?.toDouble()
                        MapMetricLayer.SINR -> lastPlotted.sinrDb
                        MapMetricLayer.THROUGHPUT -> null
                        MapMetricLayer.GENERATION -> null
                    }
                    getMetricColor(metricVal, activeMetricLayer)
                }

                val polyline = map.addPolyline(
                    PolylineOptions()
                        .add(LatLng(lat1, lon1), LatLng(lat2, lon2))
                        .color(segmentColor)
                        .width(5f)
                )
                routePolylines.add(polyline)
                lastPlotted = curr
            }
        }
    }

    private fun drawCoverageHeatmap(map: MapLibreMap, surface: CoverageSurface) {
        val radiusDeg = surface.resolutionDegrees / 2.0
        // Cap max cells rendered to preserve interactive pan/zoom responsiveness
        val cellsToRender = surface.cells.take(400)

        for (cell in cellsToRender) {
            val metricVal = cell.getMetricValue(activeMetricLayer)
            val color = getMetricColor(metricVal, activeMetricLayer)
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

    private fun drawContinuityAndHoleMarkers(map: MapLibreMap) {
        for (c in continuityEvents) {
            val lat = c.latitude ?: continue
            val lon = c.longitude ?: continue
            val title = "${c.transitionCategory}: ${c.fromGeneration} → ${c.toGeneration}"
            val snippet = "${c.evidenceClassification} • ${c.details}"

            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(lat, lon))
                    .title(title)
                    .snippet(snippet)
            )
            continuityMarkers.add(marker)
        }

        for (h in serviceHoles) {
            val lat = h.latitude ?: continue
            val lon = h.longitude ?: continue
            val title = "HOLE: ${h.holeType} [${h.severity}]"
            val snippet = h.engineeringEvidence

            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(lat, lon))
                    .title(title)
                    .snippet(snippet)
            )
            holeMarkers.add(marker)
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

    fun focusMeasurementAtIndex(index: Int): LteMeasurementEntity? {
        val valid = measurements.filter { it.latitude != null && it.longitude != null && it.isGpsValid }
        if (valid.isEmpty()) return null
        val clampedIndex = index.coerceIn(0, valid.size - 1)
        val target = valid[clampedIndex]
        val lat = target.latitude ?: return null
        val lon = target.longitude ?: return null
        val pos = LatLng(lat, lon)

        val map = mapLibreMap ?: return null
        scrubberMarker?.let { map.removeMarker(it) }
        scrubberMarker = map.addMarker(
            MarkerOptions()
                .position(pos)
                .title("SCRUBBER [${clampedIndex + 1}/${valid.size}]")
                .snippet("${target.networkGen ?: target.networkType} • ${target.rsrpDbm?.let { "$it dBm" } ?: "No Signal"}")
        )
        showInspectionCard(target)
        selectedMeasurement = target
        onMeasurementSelected?.invoke(target)
        return target
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

        val genEnum = NetworkGeneration.fromString(m.networkGen ?: m.networkType)
        val genDisplay = "${genEnum.label} (${m.dataNetworkType ?: m.networkType})"
        val voiceDisplay = m.voiceNetworkType ?: "Unknown"
        val dataStateDisplay = m.dataActivityState ?: "Unknown"
        val serviceStateDisplay = m.serviceState ?: "Unknown"

        val activeValStr = when (activeMetricLayer) {
            MapMetricLayer.RSRP -> m.rsrpDbm?.let { "$it dBm" } ?: "Unavailable"
            MapMetricLayer.RSRQ -> m.rsrqDb?.let { "$it dB" } ?: "Unavailable"
            MapMetricLayer.SINR -> m.sinrDb?.let { String.format(Locale.US, "%.1f dB", it) } ?: "Unavailable"
            MapMetricLayer.THROUGHPUT -> "No active test at this point"
            MapMetricLayer.GENERATION -> genDisplay
        }

        val activeValNum = when (activeMetricLayer) {
            MapMetricLayer.RSRP -> m.rsrpDbm?.toDouble()
            MapMetricLayer.RSRQ -> m.rsrqDb?.toDouble()
            MapMetricLayer.SINR -> m.sinrDb
            MapMetricLayer.THROUGHPUT -> null
            MapMetricLayer.GENERATION -> null
        }

        val cardColor = if (activeMetricLayer == MapMetricLayer.GENERATION) {
            Color.parseColor(genEnum.colorHex)
        } else {
            getMetricColor(activeValNum, activeMetricLayer)
        }

        // Check for nearby service hole or continuity event
        val nearbyHole = serviceHoles.minByOrNull { abs(it.timestampMs - m.timestampMs) }
        val hasNearbyHole = nearbyHole != null && abs(nearbyHole.timestampMs - m.timestampMs) <= 3000L

        val nearbyTransition = continuityEvents.minByOrNull { abs(it.timestampMs - m.timestampMs) }
        val hasNearbyTransition = nearbyTransition != null && abs(nearbyTransition.timestampMs - m.timestampMs) <= 3000L

        var eventNote = ""
        if (hasNearbyHole) {
            eventNote = "\n[SERVICE HOLE] ${nearbyHole!!.holeType} (${nearbyHole.severity}) • ${nearbyHole.engineeringEvidence}"
        } else if (hasNearbyTransition) {
            eventNote = "\n[TRANSITION] ${nearbyTransition!!.fromGeneration} → ${nearbyTransition.toGeneration} (${nearbyTransition.transitionCategory}) • ${nearbyTransition.evidenceClassification}"
        }

        tvInspectPci.text = "PCI: ${m.servingPci ?: "—"}  •  TAC: ${m.tac ?: "—"}  •  ECI: ${m.eci ?: "—"}"
        tvInspectMetrics.text = "${activeMetricLayer.displayName}: $activeValStr  |  RSRP: ${m.rsrpDbm?.let { "$it dBm" } ?: "—"}  |  SINR: ${m.sinrDb?.let { String.format(Locale.US, "%.1f dB", it) } ?: "—"}"
        tvInspectMetrics.setTextColor(cardColor)

        val speedText = m.speedKmh?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "0 km/h"
        val timeText = "${TelecomTimeUtils.isoToBdtTimeOnly(m.timestampIso, includeMillis = true)} (BST)"
        tvInspectDetails.text = "$bandText\nTech: $genDisplay | Voice: $voiceDisplay | Svc: $serviceStateDisplay | Data: $dataStateDisplay\nSpeed: $speedText  •  Time: $timeText$eventNote\n(Tap to dismiss)"

        inspectionCard.visibility = View.VISIBLE
    }

    private fun showCellInspectionCard(cell: CoverageCell) {
        val activeValStr = when (activeMetricLayer) {
            MapMetricLayer.RSRP -> cell.interpolatedRsrp?.let { String.format(Locale.US, "%.1f dBm", it) } ?: "Unavailable"
            MapMetricLayer.RSRQ -> cell.interpolatedRsrq?.let { String.format(Locale.US, "%.1f dB", it) } ?: "Unavailable"
            MapMetricLayer.SINR -> cell.interpolatedSinr?.let { String.format(Locale.US, "%.1f dB", it) } ?: "Unavailable"
            MapMetricLayer.THROUGHPUT -> "Unavailable"
            MapMetricLayer.GENERATION -> "Spatial Cell"
        }
        val sourceStr = if (cell.isDirectlyMeasured) "Direct Field Observation (Near Site)" else "IDW Spatial Interpolation (Confidence: ${(cell.confidenceScore * 100).toInt()}%)"

        tvInspectPci.text = "Spatial Grid Cell • Dominant PCI: ${cell.dominantPci ?: "—"} (Samples: ${cell.sampleCount})"
        tvInspectMetrics.text = "${activeMetricLayer.displayName}: $activeValStr  |  Est. RSRP: ${cell.interpolatedRsrp?.let { String.format(Locale.US, "%.1f dBm", it) } ?: "—"}"
        tvInspectMetrics.setTextColor(getMetricColor(cell.getMetricValue(activeMetricLayer), activeMetricLayer))
        tvInspectDetails.text = "$sourceStr\nCoord: ${String.format(Locale.US, "%.5f", cell.lat)}, ${String.format(Locale.US, "%.5f", cell.lon)} (Tap to dismiss)"

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

    fun getMetricColor(value: Double?, metric: MapMetricLayer = activeMetricLayer): Int {
        if (metric == MapMetricLayer.GENERATION) {
            return Color.parseColor("#7C4DFF")
        }
        if (value == null) return Color.parseColor("#757575")
        return when (metric) {
            MapMetricLayer.RSRP -> when {
                value >= -80.0 -> Color.parseColor("#00E676") // Excellent
                value >= -90.0 -> Color.parseColor("#76FF03") // Good
                value >= -100.0 -> Color.parseColor("#FFD600") // Fair
                value >= -110.0 -> Color.parseColor("#FF6D00") // Poor
                else -> Color.parseColor("#D50000") // Very Poor
            }
            MapMetricLayer.RSRQ -> when {
                value >= -10.0 -> Color.parseColor("#00E676")
                value >= -15.0 -> Color.parseColor("#76FF03")
                value >= -19.0 -> Color.parseColor("#FFD600")
                else -> Color.parseColor("#D50000")
            }
            MapMetricLayer.SINR -> when {
                value >= 15.0 -> Color.parseColor("#00E676")
                value >= 5.0 -> Color.parseColor("#76FF03")
                value >= 0.0 -> Color.parseColor("#FFD600")
                else -> Color.parseColor("#D50000")
            }
            MapMetricLayer.THROUGHPUT -> when {
                value >= 50.0 -> Color.parseColor("#00E676")
                value >= 20.0 -> Color.parseColor("#76FF03")
                value >= 5.0 -> Color.parseColor("#FFD600")
                else -> Color.parseColor("#D50000")
            }
            MapMetricLayer.GENERATION -> Color.parseColor("#7C4DFF")
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
