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
import com.cellscope.drive.analytics.CampusVenue
import com.cellscope.drive.analytics.CapacityScenarioResult
import com.cellscope.drive.analytics.CoverageCell
import com.cellscope.drive.analytics.CoverageHoleEstimator
import com.cellscope.drive.analytics.CoverageHoleRecord
import com.cellscope.drive.analytics.CoverageHoleSeverity
import com.cellscope.drive.analytics.CoverageSurface
import com.cellscope.drive.analytics.EstimatedTower
import com.cellscope.drive.analytics.EventVenue
import com.cellscope.drive.analytics.HoleDataProvenance
import com.cellscope.drive.analytics.MapMetricLayer
import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.data.entity.CallReliabilityEventEntity
import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.data.entity.NetworkContinuityEventEntity
import com.cellscope.drive.data.entity.ServiceHoleEntity
import com.cellscope.drive.model.NetworkGeneration
import com.cellscope.drive.model.SelectedLocationState
import com.cellscope.drive.model.LocationSelectionSource
import com.cellscope.drive.telephony.LteBandTable
import com.cellscope.drive.util.TelecomTimeUtils
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Icon
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import kotlin.math.sqrt

enum class MapLayer(val displayName: String) {
    ROUTE_SAMPLES("Route Breadcrumbs"),
    COVERAGE_HEATMAP("Coverage Grid"),
    ESTIMATED_SITES("Serving Radials"),
    HANDOVERS("Transitions & Reselection"),
    WEAK_ZONES("Weak Zones"),
    CAPACITY_HOTSPOTS("Capacity Hotspots"),
    ACTIVE_QOE("Active QoE & Congestion"),
    EVENT_PLANNING("Event Planning")
}

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

    // Multi-layer independent activation set
    val activeLayers = mutableSetOf<MapLayer>(MapLayer.ROUTE_SAMPLES)

    fun toggleLayer(layer: MapLayer, enable: Boolean? = null) {
        val shouldEnable = enable ?: !activeLayers.contains(layer)
        if (shouldEnable) {
            activeLayers.add(layer)
        } else {
            activeLayers.remove(layer)
        }
        updateLegendPanel()
        updateOverlays()
    }

    fun enableAllLayers() {
        activeLayers.addAll(MapLayer.values())
        updateLegendPanel()
        updateOverlays()
    }

    fun clearAllLayers() {
        activeLayers.clear()
        updateLegendPanel()
        updateOverlays()
    }

    fun isLayerActive(layer: MapLayer): Boolean = activeLayers.contains(layer)

    // Active map mode controller (enforces layer isolation and updates activeLayers)
    var activeMapMode: MapDisplayMode = MapDisplayMode.ROUTE_SAMPLES
        set(value) {
            field = value
            when (value) {
                MapDisplayMode.ROUTE_SAMPLES -> {
                    activeLayers.clear()
                    activeLayers.add(MapLayer.ROUTE_SAMPLES)
                }
                MapDisplayMode.COVERAGE_HEATMAP -> {
                    activeLayers.clear()
                    activeLayers.add(MapLayer.COVERAGE_HEATMAP)
                }
                MapDisplayMode.ESTIMATED_SITES -> {
                    activeLayers.clear()
                    activeLayers.add(MapLayer.ESTIMATED_SITES)
                }
                MapDisplayMode.HANDOVERS -> {
                    activeLayers.clear()
                    activeLayers.add(MapLayer.HANDOVERS)
                }
                MapDisplayMode.WEAK_ZONES -> {
                    activeLayers.clear()
                    activeLayers.add(MapLayer.WEAK_ZONES)
                }
                MapDisplayMode.CAPACITY_HOTSPOTS -> {
                    activeLayers.clear()
                    activeLayers.add(MapLayer.CAPACITY_HOTSPOTS)
                }
                MapDisplayMode.EVENT_PLANNING -> {
                    activeLayers.add(MapLayer.EVENT_PLANNING)
                }
                MapDisplayMode.ACTIVE_QOE -> {
                    activeLayers.clear()
                    activeLayers.add(MapLayer.ACTIVE_QOE)
                }
                MapDisplayMode.ALL_LAYERS -> {
                    activeLayers.addAll(MapLayer.values())
                }
            }
            updateLegendPanel()
            updateOverlays()
        }

    // Metric Layer selector
    var activeMetricLayer: MapMetricLayer = MapMetricLayer.RSRP
        set(value) {
            field = value
            updateLegendPanel()
            updateOverlays()
        }

    // Generation filter selector
    var activeGenerationFilter: GenerationFilter = GenerationFilter.ALL
        set(value) {
            field = value
            updateGenerationBanner()
            updateOverlays()
        }

    // Layer visibility flags (for backward compatibility and fine-grained control in ALL_LAYERS mode)
    var showRouteSegments: Boolean = true
        set(value) { field = value; updateOverlays() }
    var showHeatmapOverlay: Boolean = false
        set(value) { field = value; updateOverlays() }
    var showTransitions: Boolean = true
        set(value) { field = value; updateOverlays() }
    var showTowers: Boolean = false
        set(value) { field = value; updateOverlays() }
    var showCandidateTowers: Boolean = true
        set(value) { field = value; updateOverlays() }
    var showVerifiedTowers: Boolean = true
        set(value) { field = value; updateOverlays() }
    var showCoverageHoles: Boolean = true
        set(value) { field = value; updateOverlays() }

    // Follow GPS mode and camera state management
    var isFollowGpsEnabled: Boolean = false
        set(value) {
            field = value
            updateModeBanner()
        }
    var onFollowGpsChanged: ((Boolean) -> Unit)? = null
    private var isCameraInitialized: Boolean = false

    // Historical vs Live Mode indicators
    var isHistoricalMode: Boolean = false
        set(value) {
            field = value
            if (value) isFollowGpsEnabled = false
            updateModeBanner()
            updateHUD()
            updateOverlays()
        }
    var historicalSessionName: String? = null
        set(value) {
            field = value
            updateModeBanner()
            updateHUD()
        }
    var isLiveRecording: Boolean = false
        set(value) {
            field = value
            updateModeBanner()
            updateHUD()
        }
    var onRequestSwitchToLive: (() -> Unit)? = null

    // Telemetry Data lists
    private var measurements: List<LteMeasurementEntity> = emptyList()
    private var transitions: List<CellTransitionEntity> = emptyList()
    private var coverageSurface: CoverageSurface? = null
    private var estimatedTowers: List<EstimatedTower> = emptyList()
    private var continuityEvents: List<NetworkContinuityEventEntity> = emptyList()
    private var serviceHoles: List<ServiceHoleEntity> = emptyList()
    private var callEvents: List<CallReliabilityEventEntity> = emptyList()
    private var coverageHoles: List<CoverageHoleRecord> = emptyList()
    private var activeQoeTests: List<ActiveQoeTestEntity> = emptyList()

    // Active Annotations
    private val routePolylines = mutableListOf<Polyline>()
    private val heatmapPolygons = mutableListOf<Polygon>()
    private val towerMarkers = mutableListOf<Marker>()
    private val towerRingPolylines = mutableListOf<Polyline>()
    private val transitionMarkers = mutableListOf<Marker>()
    private val continuityMarkers = mutableListOf<Marker>()
    private val holeMarkers = mutableListOf<Marker>()
    private val coverageHolePolygons = mutableListOf<Polygon>()
    private val coverageHoleMarkers = mutableListOf<Marker>()
    private val hotspotMarkers = mutableListOf<Marker>()
    private val hotspotPolygons = mutableListOf<Polygon>()
    private val activeQoeMarkers = mutableListOf<Marker>()
    private val eventPolygons = mutableListOf<Polygon>()
    private val eventMarkers = mutableListOf<Marker>()
    private val eventLineOverlays = mutableListOf<Polyline>()
    private val measurementMarkers = mutableListOf<Marker>()
    private var currentPositionMarker: Marker? = null
    private var scrubberMarker: Marker? = null
    private var selectedPointMarker: Marker? = null
    private var selectedPointPolygon: Polygon? = null

    // Persistent Viewport & Authoritative Selected Location State
    val viewportState = MapViewportState()
    var selectedLocationState: SelectedLocationState? = null
    var onLocationSelected: ((SelectedLocationState) -> Unit)? = null

    // Universal Event Planning State
    var activeEventVenue: EventVenue? = null
    var activeEventResult: CapacityScenarioResult? = null
    var onPlanEventAtLocation: ((Double, Double, String) -> Unit)? = null
    var onPlanEventAtSample: ((LteMeasurementEntity, String) -> Unit)? = null
    var onInspectSampleMeasurements: ((LteMeasurementEntity) -> Unit)? = null

    // Point selection callback & contextual multi-marker disambiguation
    var onMeasurementSelected: ((LteMeasurementEntity?) -> Unit)? = null
    private var selectedMeasurement: LteMeasurementEntity? = null
    private var currentNearbySamples: List<LteMeasurementEntity> = emptyList()
    private var currentNearbyIndex: Int = 0
    private var overlayUpdateRunnable: Runnable? = null

    // UI overlays
    private val statusBanner: TextView
    private val legendContainer: LinearLayout
    private val tvLegendTitle: TextView
    private val tvLegendToggle: TextView
    private val legendRowsContainer: LinearLayout
    private var isLegendExpanded: Boolean = true

    private val hudContainer: LinearLayout
    private val tvHudBadge: TextView
    private val tvHudCoords: TextView
    private val tvHudCell: TextView
    private val tvHudAccuracyTime: TextView
    private var isHudExpanded: Boolean = true

    private val inspectionCard: LinearLayout
    private val disambiguationContainer: LinearLayout
    private val tvDisambiguationLabel: TextView
    private val btnPrevSample: TextView
    private val btnNextSample: TextView
    private val tvInspectPci: TextView
    private val tvInspectMetrics: TextView
    private val tvInspectDetails: TextView
    private val btnInspectPlanEvent: TextView
    private val btnInspectMeasurements: TextView
    private val btnInspectDismiss: TextView

    // Location selection and event action card
    private val locationCard: LinearLayout
    private val tvLocationTitle: TextView
    private val tvLocationCoords: TextView
    private val tvLocationEvidence: TextView
    private val btnPlanEventHere: TextView
    private val btnDismissLocation: TextView

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
        statusBanner.setOnClickListener {
            if (isHistoricalMode) {
                onRequestSwitchToLive?.invoke()
            }
        }
        addView(statusBanner, bannerParams)

        // 3. Floating Collapsible Metric/Layer Legend Panel
        legendContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 12, 18, 12)
            setBackgroundColor(Color.parseColor("#E618181B"))
            visibility = View.VISIBLE
        }
        val legendHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvLegendTitle = TextView(context).apply {
            text = "RSRP Legend"
            setTextColor(Color.WHITE)
            textSize = 10f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvLegendToggle = TextView(context).apply {
            text = "▲"
            setTextColor(Color.parseColor("#9E9E9E"))
            textSize = 9f
            setPadding(8, 0, 0, 0)
        }
        legendHeader.addView(tvLegendTitle)
        legendHeader.addView(tvLegendToggle)
        legendHeader.setOnClickListener {
            isLegendExpanded = !isLegendExpanded
            legendRowsContainer.visibility = if (isLegendExpanded) View.VISIBLE else View.GONE
            tvLegendToggle.text = if (isLegendExpanded) "▲" else "▼"
        }
        legendRowsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 2)
            visibility = View.VISIBLE
        }
        legendContainer.addView(legendHeader)
        legendContainer.addView(legendRowsContainer)
        val legendParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = 64 // Positioned safely below floating top bar
            leftMargin = 12
        }
        addView(legendContainer, legendParams)

        // 4. Floating Live Telemetry HUD Card
        hudContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 10, 16, 10)
            setBackgroundColor(Color.parseColor("#E618181B"))
            visibility = View.VISIBLE
        }
        tvHudBadge = TextView(context).apply {
            text = "🟢 LIVE TELEMETRY"
            setTextColor(Color.parseColor("#4ADE80"))
            textSize = 10f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        tvHudCoords = TextView(context).apply {
            text = "Waiting for GPS fix…"
            setTextColor(Color.WHITE)
            textSize = 9.5f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setPadding(0, 2, 0, 0)
        }
        tvHudCell = TextView(context).apply {
            text = "Cell: Searching"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 9f
            setPadding(0, 2, 0, 0)
        }
        tvHudAccuracyTime = TextView(context).apply {
            text = "Accuracy: —"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 8.5f
            setPadding(0, 2, 0, 0)
        }
        hudContainer.addView(tvHudBadge)
        hudContainer.addView(tvHudCoords)
        hudContainer.addView(tvHudCell)
        hudContainer.addView(tvHudAccuracyTime)
        hudContainer.setOnClickListener {
            isHudExpanded = !isHudExpanded
            tvHudCell.visibility = if (isHudExpanded) View.VISIBLE else View.GONE
            tvHudAccuracyTime.visibility = if (isHudExpanded) View.VISIBLE else View.GONE
        }
        val hudParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 64 // Positioned safely below floating top bar
            rightMargin = 12
        }
        addView(hudContainer, hudParams)

        // 5. Inspection card popup (Floats above bottom timeline scrubber)
        inspectionCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.parseColor("#E61E1E1E"))
            visibility = View.GONE
        }

        // Multi-marker overlapping disambiguation container
        disambiguationContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
            visibility = View.GONE
        }
        tvDisambiguationLabel = TextView(context).apply {
            text = "Overlapping Samples"
            setTextColor(Color.parseColor("#F59E0B"))
            textSize = 10f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnPrevSample = TextView(context).apply {
            text = "◀ Prev"
            setTextColor(Color.WHITE)
            textSize = 9.5f
            setBackgroundColor(Color.parseColor("#334155"))
            setPadding(12, 6, 12, 6)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (currentNearbySamples.size > 1) {
                    currentNearbyIndex = if (currentNearbyIndex > 0) currentNearbyIndex - 1 else currentNearbySamples.size - 1
                    val chosen = currentNearbySamples[currentNearbyIndex]
                    selectedMeasurement = chosen
                    showInspectionCard(chosen)
                    onMeasurementSelected?.invoke(chosen)
                }
            }
        }
        btnNextSample = TextView(context).apply {
            text = "Next ▶"
            setTextColor(Color.WHITE)
            textSize = 9.5f
            setBackgroundColor(Color.parseColor("#334155"))
            setPadding(12, 6, 12, 6)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (currentNearbySamples.size > 1) {
                    currentNearbyIndex = (currentNearbyIndex + 1) % currentNearbySamples.size
                    val chosen = currentNearbySamples[currentNearbyIndex]
                    selectedMeasurement = chosen
                    showInspectionCard(chosen)
                    onMeasurementSelected?.invoke(chosen)
                }
            }
        }
        disambiguationContainer.addView(tvDisambiguationLabel)
        disambiguationContainer.addView(btnPrevSample)
        disambiguationContainer.addView(btnNextSample)
        inspectionCard.addView(disambiguationContainer)

        tvInspectPci = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        tvInspectMetrics = TextView(context).apply {
            setTextColor(Color.parseColor("#00E676"))
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        tvInspectDetails = TextView(context).apply {
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 10.5f
        }
        inspectionCard.addView(tvInspectPci)
        inspectionCard.addView(tvInspectMetrics)
        inspectionCard.addView(tvInspectDetails)

        // Contextual action buttons row
        val inspectActionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 2)
        }
        btnInspectPlanEvent = TextView(context).apply {
            text = "PLAN EVENT AT THIS POINT 📍"
            setTextColor(Color.WHITE)
            textSize = 9.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#6366F1"))
            setPadding(14, 8, 14, 8)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val sample = selectedMeasurement ?: return@setOnClickListener
                val lat = sample.latitude ?: return@setOnClickListener
                val lon = sample.longitude ?: return@setOnClickListener
                val localityHint = CoverageHoleEstimator.resolveLocalityHint(lat, lon)
                val state = SelectedLocationState.fromSurveySample(
                    sampleId = sample.id,
                    latitude = lat,
                    longitude = lon,
                    sessionId = sample.sessionId,
                    localityHint = localityHint,
                    boundaryRadiusMeters = 500.0,
                    eventName = "Surveyed Point #${sample.id}"
                )
                selectedLocationState = state
                inspectionCard.visibility = View.GONE
                mapLibreMap?.let { drawSelectedLocationMarker(it) }
                onLocationSelected?.invoke(state)
                onPlanEventAtLocation?.invoke(lat, lon, localityHint)
                onPlanEventAtSample?.invoke(sample, localityHint)
            }
        }
        btnInspectMeasurements = TextView(context).apply {
            text = "VIEW MEASUREMENTS 🔍"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 9.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(12, 8, 12, 8)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val sample = selectedMeasurement ?: return@setOnClickListener
                onInspectSampleMeasurements?.invoke(sample)
            }
        }
        btnInspectDismiss = TextView(context).apply {
            text = "CLOSE ✕"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 9.5f
            setPadding(12, 8, 12, 8)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                inspectionCard.visibility = View.GONE
                selectedMeasurement = null
                currentNearbySamples = emptyList()
                onMeasurementSelected?.invoke(null)
            }
        }
        inspectActionsRow.addView(btnInspectPlanEvent)
        inspectActionsRow.addView(btnInspectMeasurements)
        inspectActionsRow.addView(btnInspectDismiss)
        inspectionCard.addView(inspectActionsRow)

        val cardParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = 84 // Clear bottom route scrubber
            leftMargin = 16
            rightMargin = 16
        }
        addView(inspectionCard, cardParams)

        // 6. Location selection and event action card
        locationCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.parseColor("#F218181E"))
            visibility = View.GONE
        }
        tvLocationTitle = TextView(context).apply {
            text = "📍 Selected Location"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        tvLocationCoords = TextView(context).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setPadding(0, 2, 0, 2)
        }
        tvLocationEvidence = TextView(context).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 10f
            setPadding(0, 0, 0, 6)
        }
        val locBtnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnPlanEventHere = TextView(context).apply {
            text = "PLAN EVENT HERE 📍"
            setTextColor(Color.WHITE)
            textSize = 10.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#E66366F1"))
            setPadding(16, 8, 16, 8)
            isClickable = true
            isFocusable = true
        }
        btnDismissLocation = TextView(context).apply {
            text = "DISMISS ✕"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10.5f
            setPadding(16, 8, 16, 8)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                locationCard.visibility = View.GONE
            }
        }
        locBtnRow.addView(btnPlanEventHere)
        locBtnRow.addView(btnDismissLocation)
        locationCard.addView(tvLocationTitle)
        locationCard.addView(tvLocationCoords)
        locationCard.addView(tvLocationEvidence)
        locationCard.addView(locBtnRow)

        val locParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = 84 // Clear bottom route scrubber
            leftMargin = 16
            rightMargin = 16
        }
        addView(locationCard, locParams)

        mapView.addOnDidFailLoadingMapListener { error ->
            android.util.Log.e("DriveMapView", "Map tile loading error: $error")
            post {
                statusBanner.text = "Map Tile Alert: $error"
                statusBanner.visibility = View.VISIBLE
            }
        }

        mapView.getMapAsync(this)
    }

    fun toggleHud(): Boolean {
        isHudExpanded = !isHudExpanded
        hudContainer.visibility = if (isHudExpanded) View.VISIBLE else View.GONE
        return isHudExpanded
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

        // Register camera movement and gesture listeners to pause follow GPS mode upon manual user interaction
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                viewportState.isUserControlled = true
                if (isFollowGpsEnabled) {
                    isFollowGpsEnabled = false
                    onFollowGpsChanged?.invoke(false)
                    updateModeBanner()
                }
            }
        }
        map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
            override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                viewportState.isUserControlled = true
                if (isFollowGpsEnabled) {
                    isFollowGpsEnabled = false
                    onFollowGpsChanged?.invoke(false)
                    updateModeBanner()
                }
            }
            override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
            override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                val cam = map.cameraPosition
                val target = cam.target
                if (target != null) {
                    viewportState.updateCamera(target.latitude, target.longitude, cam.zoom, cam.bearing, cam.tilt, fromUserGesture = true)
                }
            }
        })

        map.addOnCameraMoveListener {
            val cam = map.cameraPosition
            val target = cam.target
            if (target != null) {
                viewportState.updateCamera(target.latitude, target.longitude, cam.zoom, cam.bearing, cam.tilt, fromUserGesture = false)
            }
        }

        // Load keyless basemap style (OpenStreetMap Standard)
        map.setStyle(Style.Builder().fromUri("asset://carto_dark.json")) { style ->
            statusBanner.text = "Carto Dark Basemap • OpenStreetMap"
            statusBanner.postDelayed({
                if (statusBanner.text.contains("OpenStreetMap")) {
                    statusBanner.visibility = View.GONE
                }
            }, 3000)

            // Initial camera position based on persistent viewport or survey bounds
            if (!isCameraInitialized) {
                if (viewportState.hasValidCamera) {
                    val pos = LatLng(viewportState.centerLat!!, viewportState.centerLon!!)
                    val initialPos = CameraPosition.Builder()
                        .target(pos)
                        .zoom(viewportState.zoom)
                        .bearing(viewportState.bearing)
                        .tilt(viewportState.tilt)
                        .build()
                    map.cameraPosition = initialPos
                    isCameraInitialized = true
                } else {
                    val valid = measurements.filter { it.latitude != null && it.longitude != null && it.isGpsValid }
                    if (valid.isNotEmpty()) {
                        val builder = LatLngBounds.Builder()
                        for (m in valid) {
                            builder.include(LatLng(m.latitude!!, m.longitude!!))
                        }
                        try {
                            val last = valid.last()
                            map.cameraPosition = CameraPosition.Builder()
                                .target(LatLng(last.latitude!!, last.longitude!!))
                                .zoom(14.0)
                                .build()
                            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 80))
                            isCameraInitialized = true
                        } catch (e: Exception) {
                            val first = valid.first()
                            map.cameraPosition = CameraPosition.Builder()
                                .target(LatLng(first.latitude!!, first.longitude!!))
                                .zoom(14.0)
                                .build()
                            isCameraInitialized = true
                        }
                    } else {
                        statusBanner.text = "Location Unavailable — No GPS fix or survey route loaded"
                        statusBanner.setBackgroundColor(Color.parseColor("#E6374151"))
                        statusBanner.visibility = View.VISIBLE
                    }
                }
            }

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
                val nearby = valid.filter { m ->
                    val dLat = (m.latitude ?: 0.0) - clickLatLng.latitude
                    val dLon = (m.longitude ?: 0.0) - clickLatLng.longitude
                    (dLat * dLat + dLon * dLon) < (0.0008 * 0.0008)
                }.sortedBy { m ->
                    val dLat = (m.latitude ?: 0.0) - clickLatLng.latitude
                    val dLon = (m.longitude ?: 0.0) - clickLatLng.longitude
                    dLat * dLat + dLon * dLon
                }

                if (nearby.isNotEmpty()) {
                    currentNearbySamples = nearby
                    currentNearbyIndex = 0
                    val chosen = nearby[0]
                    selectedMeasurement = chosen
                    showInspectionCard(chosen)
                    onMeasurementSelected?.invoke(chosen)
                    foundNearby = true
                }
            }

            // If no direct track point was tapped, allow inspecting coverage holes
            if (!foundNearby && showCoverageHoles && coverageHoles.isNotEmpty()) {
                val closestHole = coverageHoles.minByOrNull { h ->
                    val dLat = h.centerLat - clickLatLng.latitude
                    val dLon = h.centerLon - clickLatLng.longitude
                    dLat * dLat + dLon * dLon
                }
                if (closestHole != null) {
                    val dLat = closestHole.centerLat - clickLatLng.latitude
                    val dLon = closestHole.centerLon - clickLatLng.longitude
                    val distSq = dLat * dLat + dLon * dLon
                    if (distSq < 0.002 * 0.002) { // within ~200m
                        showCoverageHoleCard(closestHole)
                        foundNearby = true
                    }
                }
            }

            // If no track point or hole was tapped, allow inspecting interpolated coverage cell
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
                // Empty map space tapped: dismiss inspection/location popups.
                // Normal pan/zoom gestures are preserved.
                // Do NOT open the event planner.
                inspectionCard.visibility = View.GONE
                locationCard.visibility = View.GONE
                selectedMeasurement = null
                currentNearbySamples = emptyList()
                onMeasurementSelected?.invoke(null)
            } else {
                locationCard.visibility = View.GONE
            }
            true
        }
    }

    fun updateLegendPanel() {
        legendRowsContainer.removeAllViews()
        when (activeMapMode) {
            MapDisplayMode.ROUTE_SAMPLES, MapDisplayMode.COVERAGE_HEATMAP, MapDisplayMode.ALL_LAYERS -> {
                tvLegendTitle.text = "${activeMetricLayer.displayName} Legend"
                when (activeMetricLayer) {
                    MapMetricLayer.RSRP -> {
                        addLegendRow("#00E676", "Excellent (≥ -80 dBm)")
                        addLegendRow("#76FF03", "Good (-90..-81 dBm)")
                        addLegendRow("#FFD600", "Fair (-100..-91 dBm)")
                        addLegendRow("#D50000", "Poor (< -100 dBm)")
                        addLegendRow("#00E5FF", "Spatial Uncertainty (±m)", isOutline = true)
                        addLegendRow("#757575", "No Coverage / Gray")
                    }
                    MapMetricLayer.RSRQ -> {
                        addLegendRow("#00E676", "Excellent (≥ -10 dB)")
                        addLegendRow("#76FF03", "Good (-15..-11 dB)")
                        addLegendRow("#FFD600", "Fair (-19..-16 dB)")
                        addLegendRow("#D50000", "Poor (< -19 dB)")
                    }
                    MapMetricLayer.SINR -> {
                        addLegendRow("#00E676", "Clean (≥ 15 dB)")
                        addLegendRow("#76FF03", "Normal (5..14 dB)")
                        addLegendRow("#FFD600", "Noisy (0..4 dB)")
                        addLegendRow("#D50000", "Interference (< 0 dB)")
                    }
                    MapMetricLayer.GENERATION -> {
                        addLegendRow("#7C4DFF", "5G NR")
                        addLegendRow("#2196F3", "4G LTE")
                        addLegendRow("#FF9800", "3G UMTS/HSPA")
                        addLegendRow("#795548", "2G GSM/EDGE")
                        addLegendRow("#D50000", "Out of Service")
                    }
                    MapMetricLayer.CONFIDENCE -> {
                        addLegendRow("#00E676", "Measured (100% Conf)")
                        addLegendRow("#76FF03", "High (≥ 70% Conf)")
                        addLegendRow("#FFD600", "Moderate (≥ 35% Conf)")
                        addLegendRow("#757575", "Unsupported (< 35%)")
                    }
                    MapMetricLayer.THROUGHPUT -> {
                        addLegendRow("#00E676", "≥ 50 Mbps")
                        addLegendRow("#76FF03", "≥ 20 Mbps")
                        addLegendRow("#FFD600", "≥ 5 Mbps")
                        addLegendRow("#D50000", "< 5 Mbps")
                    }
                }
            }
            MapDisplayMode.HANDOVERS -> {
                tvLegendTitle.text = "Handover Transitions"
                addLegendRow("#00E676", "Confirmed Handover")
                addLegendRow("#00E5FF", "Cell Reselection / Boundary")
                addLegendRow("#FF9800", "RAT Downgrade (4G→3G/2G)")
                addLegendRow("#D50000", "Service Interruption Event")
            }
            MapDisplayMode.ESTIMATED_SITES -> {
                tvLegendTitle.text = "Candidate Sites"
                addLegendRow("#00E676", "Verified Physical Site")
                addLegendRow("#00E5FF", "Candidate Base Station Centroid")
                addLegendRow("#00E5FF", "Uncertainty Ring (±m, Non-RSRP)", isOutline = true)
                addLegendRow("#757575", "Insufficient Evidence")
            }
            MapDisplayMode.WEAK_ZONES -> {
                tvLegendTitle.text = "Coverage Deficit Zones"
                addLegendRow("#D50000", "Confirmed Outage (< -110 dBm)")
                addLegendRow("#FF5722", "Severe Deficit (< -105 dBm)")
                addLegendRow("#FFD600", "Moderate Deficit (< -95 dBm)")
                addLegendRow("#80CBC4", "Marginal Warning (< -90 dBm)")
            }
            MapDisplayMode.CAPACITY_HOTSPOTS -> {
                tvLegendTitle.text = "Capacity Bottlenecks"
                addLegendRow("#D50000", "High PRB Congestion / Deficit")
                addLegendRow("#FF9800", "Stress / Interference Sector")
                addLegendRow("#00E676", "Nominal Capacity Sector")
            }
            MapDisplayMode.EVENT_PLANNING -> {
                tvLegendTitle.text = "Universal Event Planner"
                addLegendRow("#F59E0B", "Event Center & Perimeter")
                addLegendRow("#00E676", "Survey Evidence (in perimeter)")
                addLegendRow("#00E5FF", "Unmeasured Area Uncertainty", isOutline = true)
                addLegendRow("#6366F1", "Candidate COW Option (if active)")
                addLegendRow("#8B5CF6", "Candidate Wi-Fi AP Nodes")
            }
            MapDisplayMode.ACTIVE_QOE -> {
                tvLegendTitle.text = "Active QoE & Congestion"
                addLegendRow("#00E676", "Healthy Performance")
                addLegendRow("#D50000", "Congestion Evidence (Stable RF, Low Thrpt)")
                addLegendRow("#FF9800", "Interference Limited (Poor SINR)")
                addLegendRow("#FFD600", "Coverage Limitation (Low RSRP)")
                addLegendRow("#757575", "Insufficient Evidence / Mixed")
            }
        }
    }

    private fun addLegendRow(colorHex: String, label: String, isOutline: Boolean = false) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 3, 0, 3)
        }
        val swatch = View(context).apply {
            val sizePx = (8 * context.resources.displayMetrics.density).toInt().coerceAtLeast(6)
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                rightMargin = (6 * context.resources.displayMetrics.density).toInt().coerceAtLeast(4)
            }
            val c = Color.parseColor(colorHex)
            if (isOutline) {
                val gd = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setStroke((1.5f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1), c)
                    setColor(Color.TRANSPARENT)
                }
                background = gd
            } else {
                setBackgroundColor(c)
            }
        }
        val tv = TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 9f
        }
        row.addView(swatch)
        row.addView(tv)
        legendRowsContainer.addView(row)
    }

    fun updateHUD() {
        val valid = measurements.filter { it.latitude != null && it.longitude != null && it.isGpsValid }
        val latest = valid.lastOrNull()
        if (latest == null) {
            tvHudBadge.text = if (isLiveRecording) "🔴 RECORDING" else if (isHistoricalMode) "📁 HISTORICAL" else "🟢 IDLE"
            tvHudCoords.text = "Waiting for GPS fix…"
            tvHudCell.text = "Cell: Searching"
            tvHudAccuracyTime.text = "Accuracy: — • 0 pts"
            return
        }

        tvHudBadge.text = when {
            isLiveRecording -> "🔴 RECORDING SURVEY"
            isHistoricalMode -> "📁 HISTORICAL PLAYBACK"
            else -> "🟢 LIVE TELEMETRY"
        }
        tvHudBadge.setTextColor(when {
            isLiveRecording -> Color.parseColor("#FF5252")
            isHistoricalMode -> Color.parseColor("#38BDF8")
            else -> Color.parseColor("#4ADE80")
        })

        val latStr = String.format(Locale.US, "%.5f°N", latest.latitude ?: 0.0)
        val lonStr = String.format(Locale.US, "%.5f°E", latest.longitude ?: 0.0)
        val accStr = latest.accuracyM?.let { "±${it.toInt()}m" } ?: "±—"
        tvHudCoords.text = "$latStr, $lonStr ($accStr)"

        val pciStr = latest.servingPci?.let { "PCI $it" } ?: "No PCI"
        val rsrpStr = latest.rsrpDbm?.let { "$it dBm" } ?: "— dBm"
        val rsrqStr = latest.rsrqDb?.let { "$it dB" } ?: "— dB"
        tvHudCell.text = "$pciStr  •  RSRP: $rsrpStr  |  RSRQ: $rsrqStr"

        val timeStr = "${TelecomTimeUtils.isoToBdtTimeOnly(latest.timestampIso, includeMillis = false)} BST"
        tvHudAccuracyTime.text = "$timeStr  •  ${valid.size} samples"
    }

    private fun updateLegendBanner() {
        updateLegendPanel()
    }

    fun updateGenerationBanner() {
        val valid = measurements.filter { it.latitude != null && it.longitude != null && it.isGpsValid }
        if (activeGenerationFilter == GenerationFilter.ALL) {
            updateModeBanner()
            return
        }
        if (activeGenerationFilter == GenerationFilter.DOWNGRADES_HOLES) {
            statusBanner.text = "FILTER: Downgrades & Holes (${serviceHoles.size} holes, ${continuityEvents.size} events)"
            statusBanner.setBackgroundColor(Color.parseColor("#E6C62828"))
            statusBanner.visibility = View.VISIBLE
            return
        }
        val targetGen = when (activeGenerationFilter) {
            GenerationFilter.GEN_5G -> NetworkGeneration.GEN_5G
            GenerationFilter.GEN_4G -> NetworkGeneration.GEN_4G
            GenerationFilter.GEN_3G -> NetworkGeneration.GEN_3G
            GenerationFilter.GEN_2G -> NetworkGeneration.GEN_2G
            else -> null
        }
        val matching = valid.filter {
            val gen = NetworkGeneration.fromString(it.networkGen ?: it.networkType)
            gen == targetGen
        }
        if (matching.isEmpty()) {
            val genLabel = targetGen?.label ?: activeGenerationFilter.label
            val reason = if (targetGen == NetworkGeneration.GEN_5G) {
                "No 5G NR samples observed in session (Handset recorded LTE/3G; do not infer 0 signal)"
            } else {
                "No $genLabel samples observed in session (Network operated on other RAT)"
            }
            statusBanner.text = "⚠️ $genLabel Filter: $reason"
            statusBanner.setBackgroundColor(Color.parseColor("#E637474F"))
            statusBanner.visibility = View.VISIBLE
        } else {
            val pct = (matching.size * 100.0) / valid.size
            val avgR = matching.mapNotNull { it.rsrpDbm }.average()
            val rStr = if (!avgR.isNaN()) String.format(Locale.US, "%.1f dBm", avgR) else "—"

            // Corridor length / extent calculation
            var corridorM = 0.0
            for (i in 0 until matching.size - 1) {
                val p1 = matching[i]
                val p2 = matching[i + 1]
                if (p1.latitude != null && p1.longitude != null && p2.latitude != null && p2.longitude != null) {
                    corridorM += com.cellscope.drive.location.GpsJitterFilter.computeHaversineMeters(
                        p1.latitude!!, p1.longitude!!, p2.latitude!!, p2.longitude!!
                    )
                }
            }
            val extentStr = if (corridorM >= 1000.0) {
                String.format(Locale.US, "%.1f km", corridorM / 1000.0)
            } else {
                "${corridorM.toInt()}m"
            }

            // Quality distribution
            val rsrpVals = matching.mapNotNull { it.rsrpDbm }
            val exc = rsrpVals.count { it >= -80 }
            val good = rsrpVals.count { it in -90..-81 }
            val fair = rsrpVals.count { it in -100..-91 }
            val poor = rsrpVals.count { it < -100 }
            val totalR = maxOf(1, rsrpVals.size)
            val distStr = "${exc * 100 / totalR}% Exc, ${good * 100 / totalR}% Good, ${poor * 100 / totalR}% Poor"

            // Handover / RAT-transition count
            val ratTransitions = continuityEvents.count {
                it.fromGeneration == targetGen?.label || it.toGeneration == targetGen?.label
            }

            statusBanner.text = "${activeGenerationFilter.label}: ${matching.size} pts (${String.format(Locale.US, "%.1f%%", pct)}) • Extent: $extentStr • RSRP: $rStr ($distStr) • Trans: $ratTransitions"
            statusBanner.setBackgroundColor(Color.parseColor("#E60284C7"))
            statusBanner.visibility = View.VISIBLE
        }
    }

    fun updateModeBanner() {
        if (activeGenerationFilter != GenerationFilter.ALL) {
            updateGenerationBanner()
            return
        }
        when {
            isLiveRecording -> {
                val followText = if (isFollowGpsEnabled) " • GPS Follow Active" else " • Free Pan/Zoom"
                statusBanner.text = "🔴 LIVE RECORDING SURVEY$followText"
                statusBanner.setBackgroundColor(Color.parseColor("#E6C62828"))
                statusBanner.visibility = View.VISIBLE
            }
            isHistoricalMode -> {
                val name = historicalSessionName ?: "Imported Dataset"
                statusBanner.text = "📁 HISTORICAL SESSION: $name • Playback Mode (Tap for Live)"
                statusBanner.setBackgroundColor(Color.parseColor("#E60284C7"))
                statusBanner.visibility = View.VISIBLE
            }
            else -> {
                val followText = if (isFollowGpsEnabled) " • GPS Follow Active" else " • Free Pan/Zoom (Tap Recenter to follow)"
                statusBanner.text = "🟢 LIVE TELEMETRY$followText"
                statusBanner.setBackgroundColor(Color.parseColor("#E61E1E1E"))
                statusBanner.visibility = View.VISIBLE
            }
        }
    }

    fun setData(
        newMeasurements: List<LteMeasurementEntity>,
        newTransitions: List<CellTransitionEntity> = emptyList(),
        newSurface: CoverageSurface? = null,
        newTowers: List<EstimatedTower> = emptyList(),
        newContinuityEvents: List<NetworkContinuityEventEntity> = emptyList(),
        newServiceHoles: List<ServiceHoleEntity> = emptyList(),
        newCallEvents: List<CallReliabilityEventEntity> = emptyList(),
        newCoverageHoles: List<CoverageHoleRecord> = emptyList(),
        newActiveQoeTests: List<ActiveQoeTestEntity> = emptyList(),
        historicalMode: Boolean = false,
        sessionName: String? = null
    ) {
        this.measurements = newMeasurements
        this.transitions = newTransitions
        this.coverageSurface = newSurface
        this.estimatedTowers = newTowers
        this.continuityEvents = newContinuityEvents
        this.serviceHoles = newServiceHoles
        this.callEvents = newCallEvents
        this.coverageHoles = newCoverageHoles
        this.activeQoeTests = newActiveQoeTests
        this.isHistoricalMode = historicalMode
        this.historicalSessionName = sessionName

        updateModeBanner()
        updateHUD()
        updateOverlays()

        val map = mapLibreMap ?: return
        if (historicalMode) {
            isFollowGpsEnabled = false
            onFollowGpsChanged?.invoke(false)
        } else {
            val latest = measurements.lastOrNull { it.latitude != null && it.longitude != null && it.isGpsValid }
            if (latest != null) {
                val pos = LatLng(latest.latitude!!, latest.longitude!!)
                if (!isCameraInitialized) {
                    isCameraInitialized = true
                    isFollowGpsEnabled = true
                    onFollowGpsChanged?.invoke(true)
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 15.0))
                } else if (isFollowGpsEnabled) {
                    // Update camera position without resetting user's manual zoom!
                    map.animateCamera(CameraUpdateFactory.newLatLng(pos))
                }
            }
        }
    }

    private fun updateOverlays() {
        val map = mapLibreMap ?: return
        if (!isMapReady) return

        overlayUpdateRunnable?.let { removeCallbacks(it) }
        val runnable = Runnable {
            if (!isMapReady) return@Runnable
            clearAnnotations(map)

            if (activeLayers.contains(MapLayer.COVERAGE_HEATMAP) && coverageSurface != null) {
                drawCoverageHeatmap(map, coverageSurface!!)
            }
            if (activeLayers.contains(MapLayer.ROUTE_SAMPLES) && measurements.isNotEmpty()) {
                drawRouteTrack(map)
            }
            if (activeLayers.contains(MapLayer.ESTIMATED_SITES) && estimatedTowers.isNotEmpty()) {
                drawEstimatedTowers(map)
            }
            if (activeLayers.contains(MapLayer.HANDOVERS)) {
                if (transitions.isNotEmpty()) drawTransitionMarkers(map)
                if (continuityEvents.isNotEmpty() || serviceHoles.isNotEmpty()) drawContinuityAndHoleMarkers(map)
            }
            if (activeLayers.contains(MapLayer.WEAK_ZONES) && coverageHoles.isNotEmpty()) {
                drawCoverageHoles(map)
            }
            if (activeLayers.contains(MapLayer.CAPACITY_HOTSPOTS)) {
                drawCapacityHotspots(map)
            }
            if (activeLayers.contains(MapLayer.ACTIVE_QOE) && activeQoeTests.isNotEmpty()) {
                drawActiveQoeMarkers(map)
            }
            if (activeLayers.contains(MapLayer.EVENT_PLANNING)) {
                drawEventPlanningOverlays(map)
            }

            drawCurrentPositionMarker(map)
            drawSelectedLocationMarker(map)
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

        for (p in coverageHolePolygons) map.removePolygon(p)
        coverageHolePolygons.clear()

        for (m in coverageHoleMarkers) map.removeMarker(m)
        coverageHoleMarkers.clear()

        for (m in hotspotMarkers) map.removeMarker(m)
        hotspotMarkers.clear()

        for (p in hotspotPolygons) map.removePolygon(p)
        hotspotPolygons.clear()

        for (m in activeQoeMarkers) map.removeMarker(m)
        activeQoeMarkers.clear()

        for (m in eventMarkers) map.removeMarker(m)
        eventMarkers.clear()

        for (p in eventPolygons) map.removePolygon(p)
        eventPolygons.clear()

        for (l in eventLineOverlays) map.removePolyline(l)
        eventLineOverlays.clear()

        for (m in measurementMarkers) map.removeMarker(m)
        measurementMarkers.clear()

        scrubberMarker?.let { map.removeMarker(it) }
        scrubberMarker = null

        currentPositionMarker?.let { map.removeMarker(it) }
        currentPositionMarker = null

        selectedPointMarker?.let { map.removeMarker(it) }
        selectedPointMarker = null

        selectedPointPolygon?.let { map.removePolygon(it) }
        selectedPointPolygon = null
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
                    MapMetricLayer.CONFIDENCE -> null
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
                        MapMetricLayer.RSRP -> (lastPlotted.rsrpDbm ?: lastPlotted.primarySignalDbm ?: lastPlotted.rssiDbm)?.toDouble()
                        MapMetricLayer.RSRQ -> lastPlotted.rsrqDb?.toDouble()
                        MapMetricLayer.SINR -> lastPlotted.sinrDb
                        MapMetricLayer.THROUGHPUT -> null
                        MapMetricLayer.GENERATION -> null
                        MapMetricLayer.CONFIDENCE -> null
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
        val radiusDeg = surface.resolutionDegrees * 0.58
        // Suppress unmeasured low-confidence extrapolation far from survey track
        val validCells = surface.cells.filter { it.isDirectlyMeasured || it.confidenceScore >= 0.20f }
        // Render up to 1000 cells with uniform spatial sampling to represent the full route
        val cellsToRender = if (validCells.size <= 1000) {
            validCells
        } else {
            val step = validCells.size / 1000.0
            (0 until 1000).map { i -> validCells[(i * step).toInt().coerceAtMost(validCells.size - 1)] }
        }

        for (cell in cellsToRender) {
            val metricVal = cell.getMetricValue(activeMetricLayer)
            if (metricVal == null && activeMetricLayer != MapMetricLayer.GENERATION) continue

            val color = getMetricColor(metricVal, activeMetricLayer)
            val alpha = if (cell.isDirectlyMeasured) 180 else if (cell.confidenceScore >= 0.50f) 120 else 70
            val blendedColor = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

            // True regular hexagonal cell geometry (6 vertices) with geographic aspect ratio
            val points = createCirclePolygon(cell.lat, cell.lon, radiusDeg, 6)
            val polygon = map.addPolygon(
                PolygonOptions()
                    .addAll(points)
                    .fillColor(blendedColor)
                    .strokeColor(blendedColor) // Eliminate white stroke artifact by matching strokeColor to fillColor
            )
            heatmapPolygons.add(polygon)
        }
    }

    private fun drawEstimatedTowers(map: MapLibreMap) {
        for (tower in estimatedTowers) {
            if (tower.isCandidate && !showCandidateTowers) continue
            if (tower.isVerified && !showVerifiedTowers) continue

            val pos = LatLng(tower.estimatedLat, tower.estimatedLon)

            val towerTitle = if (tower.isVerified) {
                "[VERIFIED SITE] eNodeB ${tower.enodebId ?: "—"} / PCI ${tower.pci}"
            } else if (tower.confidenceScore < 0.35f || tower.ambiguityReason?.contains("Insufficient") == true) {
                "[INSUFFICIENT EVIDENCE] PCI ${tower.pci}"
            } else {
                "[CANDIDATE SITE] eNodeB ${tower.enodebId ?: "—"} / PCI ${tower.pci} (Cluster #${tower.clusterId})"
            }

            val towerSnippet = "${tower.estimationMethod} • Uncert: ±${tower.uncertaintyRadiusM.toInt()}m • Conf: ${(tower.confidenceScore * 100).toInt()}%"

            // Tower Marker
            val marker = map.addMarker(
                MarkerOptions()
                    .position(pos)
                    .title(towerTitle)
                    .snippet(towerSnippet)
            )
            towerMarkers.add(marker)

            // Draw uncertainty boundary outline as thin polyline (no arbitrary solid coverage zone)
            if (tower.isCandidate && tower.uncertaintyRadiusM in 20.0..1000.0) {
                val radiusDeg = (tower.uncertaintyRadiusM / 111320.0).coerceIn(0.001, 0.015)
                val ringPoints = createCirclePolygon(tower.estimatedLat, tower.estimatedLon, radiusDeg, 20)
                val closedPoints = ringPoints + ringPoints.first()
                val ringColor = Color.parseColor("#00E5FF") // Cyan = Spatial Uncertainty
                val ring = map.addPolyline(
                    PolylineOptions()
                        .addAll(closedPoints)
                        .color(ringColor)
                        .width(1.5f)
                )
                towerRingPolylines.add(ring)
            }
        }
    }

    private fun drawCoverageHoles(map: MapLibreMap) {
        for (hole in coverageHoles) {
            val colorHex = hole.severity.colorHex
            val color = Color.parseColor(colorHex)
            val pos = LatLng(hole.centerLat, hole.centerLon)

            // Draw bounding box / corridor polygon around hole
            val padLat = ((hole.maxLat - hole.minLat).coerceAtLeast(0.0003)) / 2.0
            val padLon = ((hole.maxLon - hole.minLon).coerceAtLeast(0.0003)) / 2.0
            val cLat = hole.centerLat
            val cLon = hole.centerLon
            val polyPoints = listOf(
                LatLng(cLat - padLat, cLon - padLon),
                LatLng(cLat - padLat, cLon + padLon),
                LatLng(cLat + padLat, cLon + padLon),
                LatLng(cLat + padLat, cLon - padLon)
            )

            val polygon = map.addPolygon(
                PolygonOptions()
                    .addAll(polyPoints)
                    .fillColor(Color.argb(80, Color.red(color), Color.green(color), Color.blue(color)))
                    .strokeColor(color)
            )
            coverageHolePolygons.add(polygon)

            val title = if (hole.isConfirmedOutage) {
                "[COVERAGE HOLE] ${hole.stableZoneId} [${hole.severity.label}]"
            } else {
                "[WEAK ZONE] ${hole.stableZoneId} [${hole.severity.label}]"
            }
            val snippet = "${hole.sampleCount} samples • Min: ${hole.minRsrpDbm} dBm, Avg: ${String.format(Locale.US, "%.1f", hole.avgRsrpDbm)} dBm • Corridor: ${hole.corridorLengthMeters.toInt()}m"

            val marker = map.addMarker(
                MarkerOptions()
                    .position(pos)
                    .title(title)
                    .snippet(snippet)
            )
            coverageHoleMarkers.add(marker)
        }
    }

    private val transitionIconCache = mutableMapOf<Int, Icon>()

    private fun getOrCreateTransitionIcon(colorInt: Int): Icon {
        return transitionIconCache.getOrPut(colorInt) {
            val size = (16f * resources.displayMetrics.density).toInt().coerceAtLeast(24)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorInt
                style = Paint.Style.FILL
            }
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2f * resources.displayMetrics.density
            }
            val center = size / 2f
            val radius = (size / 2f) - (2f * resources.displayMetrics.density)
            canvas.drawCircle(center, center, radius, paint)
            canvas.drawCircle(center, center, radius, strokePaint)
            IconFactory.getInstance(context).fromBitmap(bitmap)
        }
    }

    private fun drawTransitionMarkers(map: MapLibreMap) {
        for (t in transitions) {
            val lat = t.latitude ?: continue
            val lon = t.longitude ?: continue

            val isPingPong = t.details.contains("ping-pong", ignoreCase = true) || t.eventType.contains("PING_PONG", ignoreCase = true) || t.eventType == "POSSIBLE_HANDOVER"
            val isConfirmed = t.eventType == "CONFIRMED_HANDOVER"
            val isIntra = t.eventType.contains("INTRA") || t.details.contains("intra-freq", ignoreCase = true)
            val isInter = t.eventType.contains("INTER_FREQ") || t.details.contains("inter-freq", ignoreCase = true)
            val isRat = t.eventType.contains("RAT") || t.details.contains("inter-rat", ignoreCase = true)

            val title = when {
                isPingPong -> "[PING-PONG] PCI ${t.fromPci ?: "?"} ↔ PCI ${t.toPci ?: "?"}"
                isConfirmed -> "[CONFIRMED HANDOVER] PCI ${t.fromPci ?: "?"} → PCI ${t.toPci ?: "?"}"
                isRat -> "[INTER-RAT TRANSITION] PCI ${t.fromPci ?: "?"} → PCI ${t.toPci ?: "?"}"
                isIntra -> "[INTRA-FREQUENCY TRANSITION] PCI ${t.fromPci ?: "?"} → PCI ${t.toPci ?: "?"}"
                isInter -> "[INTER-FREQUENCY TRANSITION] PCI ${t.fromPci ?: "?"} → PCI ${t.toPci ?: "?"}"
                t.eventType == "CELL_RESELECTION" -> "[CELL RESELECTION] PCI ${t.fromPci ?: "?"} → PCI ${t.toPci ?: "?"}"
                t.eventType == "SIGNAL_LOSS" -> "[SIGNAL LOSS] PCI ${t.fromPci ?: "?"} → PCI ${t.toPci ?: "?"}"
                else -> "[OBSERVED CELL CHANGE] PCI ${t.fromPci ?: "?"} → PCI ${t.toPci ?: "?"}"
            }

            val markerColor = when {
                isConfirmed -> Color.parseColor("#00E676") // Green (#00E676)
                isPingPong -> Color.parseColor("#EC4899")  // Pink
                isRat -> Color.parseColor("#FF9100")       // Orange
                isIntra -> Color.parseColor("#00E5FF")     // Cyan
                isInter -> Color.parseColor("#3B82F6")     // Blue
                else -> Color.parseColor("#94A3B8")        // Slate
            }

            val deltaStr = if (t.fromRsrp != null && t.toRsrp != null) {
                val d = t.toRsrp - t.fromRsrp
                " (${t.fromRsrp} → ${t.toRsrp} dBm, Δ${if (d >= 0) "+$d" else "$d"} dB)"
            } else ""
            val snippet = "PCI ${t.fromPci ?: "?"} → PCI ${t.toPci ?: "?"}$deltaStr • Conf: ${(t.confidenceScore * 100).toInt()}%"

            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(lat, lon))
                    .title(title)
                    .snippet(snippet)
                    .icon(getOrCreateTransitionIcon(markerColor))
            )
            transitionMarkers.add(marker)
        }
    }

    private fun drawContinuityAndHoleMarkers(map: MapLibreMap) {
        for (c in continuityEvents) {
            val lat = c.latitude ?: continue
            val lon = c.longitude ?: continue
            val title = when (c.transitionCategory) {
                "DOWNGRADE" -> "[RAT DOWNGRADE] ${c.fromGeneration} → ${c.toGeneration}"
                "UPGRADE" -> "[RAT RECOVERY] ${c.fromGeneration} → ${c.toGeneration}"
                else -> "[RAT TRANSITION] ${c.fromGeneration} → ${c.toGeneration}"
            }
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
            val title = "[COVERAGE HOLE] ${h.holeType} [${h.severity}]"
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
        if (isHistoricalMode) return
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

    private fun drawCapacityHotspots(map: MapLibreMap) {
        val valid = measurements.filter { it.latitude != null && it.longitude != null && it.isGpsValid }
        if (valid.isEmpty()) return

        // Group measurements by serving PCI to detect high-density load sectors
        val pciGroups = valid.groupBy { it.servingPci }
        for ((pci, pts) in pciGroups) {
            if (pci == null) continue
            val avgRsrp = pts.mapNotNull { it.rsrpDbm }.average()
            val poorRsrpCount = pts.count { (it.rsrpDbm ?: 0) < -100 }
            val centerLat = pts.mapNotNull { it.latitude }.average()
            val centerLon = pts.mapNotNull { it.longitude }.average()
            if (centerLat.isNaN() || centerLon.isNaN()) continue

            // Determine hotspot stress
            val isSevere = poorRsrpCount > pts.size * 0.4 || avgRsrp < -98.0
            val colorHex = if (isSevere) "#D50000" else "#FF9800"
            val color = Color.parseColor(colorHex)

            // Draw stress circle around centroid
            val circlePoints = createCirclePolygon(centerLat, centerLon, 0.0005, 16)
            val polygon = map.addPolygon(
                PolygonOptions()
                    .addAll(circlePoints)
                    .fillColor(Color.argb(80, Color.red(color), Color.green(color), Color.blue(color)))
                    .strokeColor(color)
            )
            hotspotPolygons.add(polygon)

            val title = if (isSevere) "[HIGH PRB CONGESTION] Sector PCI $pci" else "[CAPACITY BOTTLENECK] Sector PCI $pci"
            val snippet = "${pts.size} samples • Avg RSRP: ${String.format(Locale.US, "%.1f", avgRsrp)} dBm • High load / resource-block strain"
            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(centerLat, centerLon))
                    .title(title)
                    .snippet(snippet)
            )
            hotspotMarkers.add(marker)
        }
    }

    private fun drawActiveQoeMarkers(map: MapLibreMap) {
        for (q in activeQoeTests) {
            val lat = q.latitude ?: continue
            val lon = q.longitude ?: continue
            val classif = q.congestionClassification ?: "Measured"
            val dlStr = q.downloadSpeedMbps?.let { String.format(Locale.US, "%.1f Mbps DL", it) } ?: "— Mbps"
            val ulStr = q.uploadSpeedMbps?.let { String.format(Locale.US, "%.1f Mbps UL", it) } ?: ""
            val pingStr = q.latencyMs?.let { "${it}ms RTT" } ?: "— ms"
            val title = "[$classif] $dlStr"
            val snippet = "$pingStr ${if (ulStr.isNotEmpty()) "• $ulStr " else ""}• RAT: ${q.servingRat ?: "—"} • PCI ${q.servingPci ?: "—"}"

            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(lat, lon))
                    .title(title)
                    .snippet(snippet)
            )
            activeQoeMarkers.add(marker)
        }
    }

    private fun drawEventPlanningOverlays(map: MapLibreMap) {
        val venue = activeEventVenue ?: return
        val result = activeEventResult

        // 1. Venue boundary circle
        val boundaryRadiusDeg = venue.radiusMeters / 111320.0
        val boundaryPoints = createCirclePolygon(venue.centerLat, venue.centerLon, boundaryRadiusDeg, 32)
        val boundaryColor = Color.parseColor("#6366F1") // Indigo
        val boundaryPolygon = map.addPolygon(
            PolygonOptions()
                .addAll(boundaryPoints)
                .fillColor(Color.argb(35, Color.red(boundaryColor), Color.green(boundaryColor), Color.blue(boundaryColor)))
                .strokeColor(boundaryColor)
        )
        eventPolygons.add(boundaryPolygon)

        // 2. High-Density Core / Staging Area circle (approx 35% of event radius)
        val coreRadiusDeg = (venue.radiusMeters * 0.35) / 111320.0
        val corePoints = createCirclePolygon(venue.centerLat, venue.centerLon, coreRadiusDeg.coerceAtLeast(0.0006), 24)
        val coreColor = Color.parseColor("#F59E0B") // Amber
        val corePolygon = map.addPolygon(
            PolygonOptions()
                .addAll(corePoints)
                .fillColor(Color.argb(70, Color.red(coreColor), Color.green(coreColor), Color.blue(coreColor)))
                .strokeColor(coreColor)
        )
        eventPolygons.add(corePolygon)

        // 3. Center Venue Marker with Honest Evidence Classification
        val evidenceLabel = result?.coverageEvidence?.status?.label ?: run {
            val localCount = measurements.count { m ->
                if (m.latitude == null || m.longitude == null || !m.isGpsValid) false
                else {
                    val dLatM = (m.latitude!! - venue.centerLat) * 111320.0
                    val cosLat = cos(Math.toRadians(venue.centerLat)).coerceAtLeast(0.1)
                    val dLonM = (m.longitude!! - venue.centerLon) * 111320.0 * cosLat
                    sqrt(dLatM * dLatM + dLonM * dLonM) <= venue.radiusMeters
                }
            }
            if (localCount >= 10) "LOCAL EVIDENCE ($localCount points)"
            else if (localCount > 0) "PARTIAL EVIDENCE ($localCount points)"
            else "NO LOCAL EVIDENCE"
        }

        val capacityNote = if (result != null) {
            if (result.existingMacroCapacityMbps != null) {
                "Macro: ${result.existingMacroCapacityMbps.toInt()} Mbps | Deficit: ${result.capacityDeficitMbps?.toInt() ?: 0} Mbps"
            } else {
                "Macro Capacity: UNKNOWN (Unmeasured) • Field survey required"
            }
        } else {
            "Preset: ${venue.eventType.displayName} • Baseline Active: ${venue.baselineActiveUsers}"
        }

        val venueMarker = map.addMarker(
            MarkerOptions()
                .position(LatLng(venue.centerLat, venue.centerLon))
                .title("[EVENT VENUE] ${venue.name}")
                .snippet("${venue.eventType.displayName} • $evidenceLabel\n$capacityNote")
        )
        eventMarkers.add(venueMarker)

        // 4. If No Local Evidence, render an explicit Cyan Uncertainty Ring
        val isUnmeasured = (result != null && result.coverageEvidence.status == com.cellscope.drive.analytics.CoverageEvidenceStatus.NO_LOCAL_EVIDENCE) ||
                (result == null && evidenceLabel.contains("NO LOCAL"))
        if (isUnmeasured) {
            val unmeasPoints = createCirclePolygon(venue.centerLat, venue.centerLon, boundaryRadiusDeg * 1.05, 24)
            val unmeasColor = Color.parseColor("#00E5FF") // Cyan
            val unmeasPolygon = map.addPolygon(
                PolygonOptions()
                    .addAll(unmeasPoints)
                    .fillColor(Color.argb(20, Color.red(unmeasColor), Color.green(unmeasColor), Color.blue(unmeasColor)))
                    .strokeColor(unmeasColor)
            )
            eventPolygons.add(unmeasPolygon)
        }

        // 5. Render Candidate Interventions (if scenario calculated)
        if (result != null) {
            // Render COW Candidate Marker if configured or recommended
            val hasCow = result.spec.hasTemporaryCow || result.recommendedInterventions.any { it.contains("Cell On Wheels") || it.contains("COW") }
            if (hasCow) {
                val cowMarker = map.addMarker(
                    MarkerOptions()
                        .position(LatLng(venue.centerLat + 0.0003, venue.centerLon + 0.0003))
                        .title("[CANDIDATE COW OPTION] ${venue.name}")
                        .snippet("Candidate Option: Multi-band portable COW (+${result.spec.cowCapacityMbps.toInt()} Mbps DL assumed) • Engineering evaluation")
                )
                eventMarkers.add(cowMarker)
            }

            // Render Small Cell / Micro Sector Candidate
            val hasSmallCell = result.recommendedInterventions.any { it.contains("Small Cell") || it.contains("micro-cell") }
            if (hasSmallCell) {
                val scMarker = map.addMarker(
                    MarkerOptions()
                        .position(LatLng(venue.centerLat - 0.0003, venue.centerLon - 0.0003))
                        .title("[CANDIDATE SMALL CELL OPTION] Traffic Hotspot")
                        .snippet("Candidate Option: Dedicated micro-sector (+120 Mbps assumed) • Engineering proposal for review")
                )
                eventMarkers.add(scMarker)
            }

            // Render Wi-Fi 6 Offload Nodes around core perimeter
            val hasWifi = result.spec.hasWifiOffload || result.recommendedInterventions.any { it.contains("Wi-Fi") }
            if (hasWifi) {
                val apCount = 6
                for (i in 0 until apCount) {
                    val angleDeg = i * (360.0 / apCount)
                    val angleRad = Math.toRadians(angleDeg)
                    val apLat = venue.centerLat + (coreRadiusDeg * sin(angleRad))
                    val cosLat = cos(Math.toRadians(venue.centerLat)).coerceAtLeast(0.1)
                    val apLon = venue.centerLon + ((coreRadiusDeg * cos(angleRad)) / cosLat)
                    val apMarker = map.addMarker(
                        MarkerOptions()
                            .position(LatLng(apLat, apLon))
                            .title("[CANDIDATE WI-FI 6 OFFLOAD] Node #${i + 1}")
                            .snippet("Assumed 802.11ax local offload for video/social streams")
                    )
                    eventMarkers.add(apMarker)
                }
            }

            // Macro Antenna Tilt Optimization Vector
            val hasTilt = result.spec.hasAntennaDowntiltOptimization || result.recommendedInterventions.any { it.contains("down-tilt") || it.contains("tilt") }
            if (hasTilt) {
                val nearestPt = measurements.minByOrNull { m ->
                    if (m.latitude == null || m.longitude == null || !m.isGpsValid) Double.MAX_VALUE
                    else {
                        val dLat = m.latitude!! - venue.centerLat
                        val dLon = m.longitude!! - venue.centerLon
                        dLat * dLat + dLon * dLon
                    }
                }
                val startLat = nearestPt?.latitude ?: (venue.centerLat + 0.004)
                val startLon = nearestPt?.longitude ?: (venue.centerLon - 0.003)
                val vectorLine = map.addPolyline(
                    PolylineOptions()
                        .add(LatLng(startLat, startLon), LatLng(venue.centerLat, venue.centerLon))
                        .color(Color.parseColor("#10B981"))
                        .width(3.0f)
                )
                eventLineOverlays.add(vectorLine)
            }
        }
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
        val markerTitle = if (isHistoricalMode) "INSPECTED POINT [${clampedIndex + 1}/${valid.size}]" else "SCRUBBER [${clampedIndex + 1}/${valid.size}]"
        scrubberMarker = map.addMarker(
            MarkerOptions()
                .position(pos)
                .title(markerTitle)
                .snippet("${target.networkGen ?: target.networkType} • ${target.rsrpDbm?.let { "$it dBm" } ?: "No Signal"}")
        )
        showInspectionCard(target)
        selectedMeasurement = target
        onMeasurementSelected?.invoke(target)
        return target
    }

    private fun handleMarkerClick(marker: Marker) {
        val pos = marker.position

        // Check if handover transition was clicked
        val clickedTransition = transitions.find { t ->
            t.latitude != null && t.longitude != null &&
            abs(t.latitude - pos.latitude) < 0.0004 && abs(t.longitude - pos.longitude) < 0.0004
        }
        if (clickedTransition != null) {
            showTransitionCard(clickedTransition)
            return
        }

        // Check if network continuity transition was clicked
        val clickedContinuity = continuityEvents.find { c ->
            c.latitude != null && c.longitude != null &&
            abs(c.latitude - pos.latitude) < 0.0004 && abs(c.longitude - pos.longitude) < 0.0004
        }
        if (clickedContinuity != null) {
            showContinuityCard(clickedContinuity)
            return
        }

        // Check if service hole was clicked
        val clickedServiceHole = serviceHoles.find { h ->
            h.latitude != null && h.longitude != null &&
            abs(h.latitude - pos.latitude) < 0.0004 && abs(h.longitude - pos.longitude) < 0.0004
        }
        if (clickedServiceHole != null) {
            showServiceHoleEntityCard(clickedServiceHole)
            return
        }

        // Check if coverage hole was clicked
        val clickedHole = coverageHoles.find { h ->
            abs(h.centerLat - pos.latitude) < 0.0004 && abs(h.centerLon - pos.longitude) < 0.0004
        }
        if (clickedHole != null) {
            showCoverageHoleCard(clickedHole)
            return
        }

        // Check if estimated tower was clicked
        val clickedTower = estimatedTowers.find { t ->
            abs(t.estimatedLat - pos.latitude) < 0.0004 && abs(t.estimatedLon - pos.longitude) < 0.0004
        }
        if (clickedTower != null) {
            showTowerInspectionCard(clickedTower)
            return
        }

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

    private fun showTransitionCard(t: CellTransitionEntity) {
        val titleStr = when (t.eventType) {
            "CONFIRMED_HANDOVER" -> "Confirmed Handover"
            "CELL_RESELECTION" -> "Cell Reselection"
            "SIGNAL_LOSS" -> "Signal Loss Reselection"
            else -> "Cell Transition"
        }
        val fromCellStr = "PCI ${t.fromPci ?: "—"}${t.fromTac?.let { " (TAC $it)" } ?: ""}"
        val toCellStr = "PCI ${t.toPci ?: "—"}${t.toTac?.let { " (TAC $it)" } ?: ""}"
        tvInspectPci.text = "$titleStr: $fromCellStr → $toCellStr"

        val fromRsrpStr = t.fromRsrp?.let { "$it dBm" } ?: "— dBm"
        val toRsrpStr = t.toRsrp?.let { "$it dBm" } ?: "— dBm"
        val deltaRsrp = if (t.fromRsrp != null && t.toRsrp != null) {
            val d = t.toRsrp - t.fromRsrp
            if (d >= 0) "+$d dB" else "$d dB"
        } else "—"

        tvInspectMetrics.text = "Pre RSRP: $fromRsrpStr  |  Post RSRP: $toRsrpStr  |  Δ: $deltaRsrp"
        tvInspectMetrics.setTextColor(if (t.eventType == "CONFIRMED_HANDOVER" || t.details.contains("confirmed", ignoreCase = true)) Color.parseColor("#00E676") else Color.parseColor("#00E5FF"))

        val timeStr = "${TelecomTimeUtils.isoToBdtTimeOnly(t.timestampIso, includeMillis = true)} (BST)"
        val locStr = String.format(Locale.US, "%.5f°N, %.5f°E", t.latitude ?: 0.0, t.longitude ?: 0.0)
        tvInspectDetails.text = "Type: ${t.eventType} • Confidence: ${(t.confidenceScore * 100).toInt()}%\n" +
                "Location: $locStr • Time: $timeStr\n" +
                "Details: ${t.details}\n(Tap to dismiss)"

        prepareNonSampleInspectionCard()
        inspectionCard.visibility = View.VISIBLE
    }

    private fun showContinuityCard(c: NetworkContinuityEventEntity) {
        tvInspectPci.text = "${c.transitionCategory}: ${c.fromGeneration} → ${c.toGeneration}"
        tvInspectMetrics.text = "Evidence: ${c.evidenceClassification}  |  Duration: ${c.durationSeconds?.let { "${it}s" } ?: "—"}"
        tvInspectMetrics.setTextColor(if (c.transitionCategory == "DOWNGRADE") Color.parseColor("#FF9800") else Color.parseColor("#00E5FF"))

        val timeStr = "${TelecomTimeUtils.isoToBdtTimeOnly(c.timestampIso, includeMillis = true)} (BST)"
        val locStr = String.format(Locale.US, "%.5f°N, %.5f°E", c.latitude ?: 0.0, c.longitude ?: 0.0)
        tvInspectDetails.text = "From: ${c.fromTech ?: c.fromGeneration} (${c.fromSignalDbm?.let { "$it dBm" } ?: "—"})\n" +
                "To: ${c.toTech ?: c.toGeneration} (${c.toSignalDbm?.let { "$it dBm" } ?: "—"})\n" +
                "Location: $locStr • Time: $timeStr\n" +
                "Analysis: ${c.details}\n(Tap to dismiss)"

        prepareNonSampleInspectionCard()
        inspectionCard.visibility = View.VISIBLE
    }

    private fun showServiceHoleEntityCard(h: ServiceHoleEntity) {
        tvInspectPci.text = "Service Hole: ${h.holeType} [${h.severity}]"
        val durStr = h.durationMs?.let { "${it / 1000}s" } ?: "Ongoing"
        tvInspectMetrics.text = "Severity: ${h.severity}  |  Duration: $durStr  |  RSRP: ${h.rsrpDbm?.let { "$it dBm" } ?: "OOS"}"
        tvInspectMetrics.setTextColor(Color.parseColor("#D50000"))

        val timeStr = "${TelecomTimeUtils.isoToBdtTimeOnly(h.timestampIso, includeMillis = true)} (BST)"
        val locStr = String.format(Locale.US, "%.5f°N, %.5f°E", h.latitude ?: 0.0, h.longitude ?: 0.0)
        tvInspectDetails.text = "RAT: ${h.networkGen ?: h.networkType ?: "Unknown"} • Status: ${h.resolutionStatus}\n" +
                "Location: $locStr • Time: $timeStr\n" +
                "Evidence: ${h.engineeringEvidence}\n(Tap to dismiss)"

        prepareNonSampleInspectionCard()
        inspectionCard.visibility = View.VISIBLE
    }

    private fun showTowerInspectionCard(tower: EstimatedTower) {
        val titleStr = if (tower.isVerified) {
            "Verified Site: ${tower.enodebId?.let { "eNodeB $it / " } ?: ""}PCI ${tower.pci}"
        } else if (tower.confidenceScore < 0.35f || tower.ambiguityReason?.contains("Insufficient") == true) {
            "Insufficient Evidence: Candidate PCI ${tower.pci}"
        } else {
            "Candidate Base Station: ${tower.enodebId?.let { "eNodeB $it / " } ?: ""}PCI ${tower.pci} (Cluster #${tower.clusterId})"
        }

        tvInspectPci.text = titleStr
        tvInspectMetrics.text = "Peak RSRP: ${tower.peakRsrpDbm?.let { "$it dBm" } ?: "—"}  |  Confidence: ${(tower.confidenceScore * 100).toInt()}%  |  Uncertainty: ±${tower.uncertaintyRadiusM.toInt()}m"
        tvInspectMetrics.setTextColor(if (tower.isVerified) Color.parseColor("#00E676") else Color.parseColor("#00E5FF"))

        val taStr = tower.timingAdvanceRangeMeters?.let { "TA Range: ${it.first.toInt()}–${it.second.toInt()}m" } ?: "TA: Not exposed"
        val hoStr = "Handover Boundaries: ${tower.handoverBoundaryConstraintsCount}"
        val earfcnStr = tower.earfcn?.let { "EARFCN $it" } ?: "EARFCN: —"
        val tacStr = tower.tac?.let { "TAC $it" } ?: "TAC: —"

        tvInspectDetails.text = "Operator: ${tower.operator ?: "Unknown"}  •  $earfcnStr  •  $tacStr  •  $taStr  •  $hoStr\n" +
                "Method: ${tower.estimationMethod}\n" +
                "Analysis: ${tower.ambiguityReason ?: "Nominal candidate centroid"}\n(Tap to dismiss)"

        prepareNonSampleInspectionCard()
        inspectionCard.visibility = View.VISIBLE
    }

    fun showCoverageHoleCard(hole: CoverageHoleRecord) {
        val typeStr = if (hole.isConfirmedOutage) "CONFIRMED OUTAGE" else "WEAK COVERAGE CORRIDOR"
        val pcisStr = hole.supportingPcis.joinToString(", ").ifEmpty { "None" }

        tvInspectPci.text = "${hole.stableZoneId} • $typeStr [${hole.severity.label}]"
        tvInspectMetrics.text = "Min RSRP: ${hole.minRsrpDbm} dBm  |  Avg RSRP: ${String.format(Locale.US, "%.1f dBm", hole.avgRsrpDbm)}  |  Samples: ${hole.sampleCount}"
        tvInspectMetrics.setTextColor(Color.parseColor(hole.severity.colorHex))

        tvInspectDetails.text = "Corridor Length: ${hole.corridorLengthMeters.toInt()}m  •  Est. Area: ${String.format(Locale.US, "%.3f km²", hole.estimatedAreaKm2)}\n" +
                "Dominant PCIs: $pcisStr  •  Provenance: ${hole.provenance.label}\n" +
                "Location: ${hole.localityHint}  •  Confidence: ${(hole.confidenceScore * 100).toInt()}%\n" +
                "Evidence: ${hole.engineeringEvidence}\n(Tap to dismiss)"

        prepareNonSampleInspectionCard()
        if (hole.sampleCount > 0 && measurements.isNotEmpty()) {
            btnInspectMeasurements.text = "Inspect Linked Samples (${hole.sampleCount})"
            btnInspectMeasurements.visibility = View.VISIBLE
            btnInspectMeasurements.setOnClickListener {
                val nearest = measurements.minByOrNull { m ->
                    val lat = m.latitude ?: 0.0
                    val lon = m.longitude ?: 0.0
                    val dLat = lat - hole.centerLat
                    val dLon = lon - hole.centerLon
                    dLat * dLat + dLon * dLon
                }
                if (nearest != null) {
                    showInspectionCard(nearest)
                    selectedMeasurement = nearest
                    onMeasurementSelected?.invoke(nearest)
                }
            }
        }
        inspectionCard.visibility = View.VISIBLE
    }

    fun focusCoverageHole(holeId: Int): CoverageHoleRecord? {
        val hole = coverageHoles.find { it.id == holeId } ?: return null
        val map = mapLibreMap ?: return null
        map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(hole.centerLat, hole.centerLon)))
        showCoverageHoleCard(hole)
        return hole
    }

    fun focusTransition(trans: CellTransitionEntity) {
        val lat = trans.latitude ?: return
        val lon = trans.longitude ?: return
        val map = mapLibreMap ?: return
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 16.5))
        showTransitionCard(trans)
    }

    private fun prepareNonSampleInspectionCard() {
        disambiguationContainer.visibility = View.GONE
        btnInspectPlanEvent.visibility = View.GONE
        btnInspectMeasurements.visibility = View.GONE
        btnInspectDismiss.visibility = View.VISIBLE
    }

    fun showLocationCard(pos: LatLng) {
        showUnsurveyedLocationCard(pos)
    }

    fun showUnsurveyedLocationCard(pos: LatLng) {
        val localityHint = CoverageHoleEstimator.resolveLocalityHint(pos.latitude, pos.longitude)
        val defaultRadiusMeters = 500.0

        val state = SelectedLocationState.fromUnsurveyedLocation(
            latitude = pos.latitude,
            longitude = pos.longitude,
            localityHint = localityHint,
            boundaryRadiusMeters = defaultRadiusMeters,
            eventName = "Unsurveyed Site (${localityHint.ifBlank { "Custom Location" }})"
        )
        selectedLocationState = state
        mapLibreMap?.let { drawSelectedLocationMarker(it) }
        onLocationSelected?.invoke(state)

        tvLocationTitle.text = "📍 Unsurveyed Location: $localityHint"
        tvLocationCoords.text = String.format(Locale.US, "%.5f°N, %.5f°E", pos.latitude, pos.longitude)
        tvLocationEvidence.text = "⚠️ No local survey evidence at these coordinates.\n(Macro capacity & PRB load unmeasured until surveyed)"
        tvLocationEvidence.setTextColor(Color.parseColor("#F59E0B"))

        btnPlanEventHere.text = "CREATE PLAN AT UNSURVEYED LOCATION 📍"
        btnPlanEventHere.setOnClickListener {
            locationCard.visibility = View.GONE
            onPlanEventAtLocation?.invoke(pos.latitude, pos.longitude, localityHint)
        }

        locationCard.visibility = View.VISIBLE
    }

    private fun drawSelectedLocationMarker(map: MapLibreMap) {
        selectedPointMarker?.let { map.removeMarker(it) }
        selectedPointMarker = null
        selectedPointPolygon?.let { map.removePolygon(it) }
        selectedPointPolygon = null

        val state = selectedLocationState ?: return
        val pos = LatLng(state.latitude, state.longitude)

        val radiusDeg = state.boundaryRadiusMeters / 111320.0
        val circlePoints = createCirclePolygon(state.latitude, state.longitude, radiusDeg, 36)
        selectedPointPolygon = map.addPolygon(
            PolygonOptions()
                .addAll(circlePoints)
                .fillColor(Color.parseColor("#33F59E0B"))
                .strokeColor(Color.parseColor("#F59E0B"))
        )

        selectedPointMarker = map.addMarker(
            MarkerOptions()
                .position(pos)
                .title("📍 ${state.resolvedLocality ?: "Selected Location"}")
                .snippet(String.format(Locale.US, "%.5f°N, %.5f°E • Radius: %.0fm • Source: %s",
                    state.latitude, state.longitude, state.boundaryRadiusMeters, state.source.name))
        )
    }

    fun setSelectedLocation(state: SelectedLocationState?) {
        selectedLocationState = state
        val map = mapLibreMap ?: return
        drawSelectedLocationMarker(map)
    }

    fun recenterOnSelectedPoint() {
        val state = selectedLocationState ?: return
        val map = mapLibreMap ?: return
        val pos = LatLng(state.latitude, state.longitude)
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 15.5))
    }

    fun focusEventPlanning(venue: EventVenue? = null, result: CapacityScenarioResult? = null) {
        activeMapMode = MapDisplayMode.EVENT_PLANNING
        if (venue != null) {
            activeEventVenue = venue
        }
        if (result != null) {
            activeEventResult = result
        }
        updateLegendPanel()
        updateOverlays()
        val targetVenue = activeEventVenue
        val map = mapLibreMap ?: return
        if (targetVenue != null) {
            val pos = LatLng(targetVenue.centerLat, targetVenue.centerLon)
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 15.5))
        } else {
            // No venue set: focus on survey measurements bounds if available, never jump to Dhaka
            val valid = measurements.filter { it.latitude != null && it.longitude != null && it.isGpsValid }
            if (valid.isNotEmpty()) {
                val lats = valid.mapNotNull { it.latitude }
                val lons = valid.mapNotNull { it.longitude }
                val bounds = LatLngBounds.Builder()
                    .include(LatLng(lats.minOrNull()!!, lons.minOrNull()!!))
                    .include(LatLng(lats.maxOrNull()!!, lons.maxOrNull()!!))
                    .build()
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 64))
            }
        }
    }

    fun focusCapacityHotspot(lat: Double, lon: Double) {
        activeMapMode = MapDisplayMode.CAPACITY_HOTSPOTS
        val map = mapLibreMap ?: return
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 16.0))
    }

    fun resetLayers() {
        activeMapMode = MapDisplayMode.ROUTE_SAMPLES
        activeMetricLayer = MapMetricLayer.RSRP
        activeGenerationFilter = GenerationFilter.ALL
        showRouteSegments = true
        showHeatmapOverlay = false
        showTransitions = true
        showTowers = false
        showCandidateTowers = true
        showVerifiedTowers = true
        showCoverageHoles = true
        updateLegendPanel()
        updateOverlays()
    }

    private fun showInspectionCard(m: LteMeasurementEntity) {
        val carrierInfo = com.cellscope.drive.telephony.CellularBandResolver.resolveMeasurement(m)
        val bandText = carrierInfo?.let {
            "${it.bandName} • DL ${it.dlCenterFreqMhz} MHz"
        } ?: LteBandTable.resolveEarfcn(m.earfcn)?.let {
            "Band ${it.bandNumber} (${it.designation}) • DL ${it.dlFrequencyMhz} MHz"
        } ?: "Channel ${m.channelNumber ?: m.earfcn ?: "—"}"

        val genEnum = NetworkGeneration.fromString(m.networkGen ?: m.networkType)
        val genDisplay = "${genEnum.label} (${m.dataNetworkType ?: m.networkType})"
        val voiceDisplay = m.voiceNetworkType ?: "Unknown"
        val dataStateDisplay = m.dataActivityState ?: "Unknown"
        val serviceStateDisplay = m.serviceState ?: "Unknown"

        val rat = m.servingRat ?: m.networkGen ?: m.networkType
        val is2g = rat.contains("2G", ignoreCase = true) || rat.contains("GSM", ignoreCase = true)
        val is3g = rat.contains("3G", ignoreCase = true) || rat.contains("WCDMA", ignoreCase = true)
        val is5g = rat.contains("5G", ignoreCase = true) || rat.contains("NR", ignoreCase = true)

        val primarySignalStr = when {
            is2g -> (m.primarySignalDbm ?: m.rssiDbm)?.let { "$it dBm (GSM RSSI)" } ?: "—"
            is3g -> (m.primarySignalDbm ?: m.rssiDbm)?.let { "$it dBm (WCDMA RSCP)" } ?: "—"
            is5g -> (m.primarySignalDbm ?: m.rsrpDbm)?.let { "$it dBm (SS-RSRP)" } ?: "—"
            else -> m.rsrpDbm?.let { "$it dBm (LTE RSRP)" } ?: "—"
        }

        val activeValStr = when (activeMetricLayer) {
            MapMetricLayer.RSRP -> when {
                is2g -> (m.primarySignalDbm ?: m.rssiDbm)?.let { "$it dBm (GSM RSSI)" } ?: "Unavailable"
                is3g -> (m.primarySignalDbm ?: m.rssiDbm)?.let { "$it dBm (WCDMA RSCP)" } ?: "Unavailable"
                is5g -> (m.primarySignalDbm ?: m.rsrpDbm)?.let { "$it dBm (SS-RSRP)" } ?: "Unavailable"
                else -> m.rsrpDbm?.let { "$it dBm" } ?: "Unavailable"
            }
            MapMetricLayer.RSRQ -> m.rsrqDb?.let { "$it dB" } ?: "Unavailable"
            MapMetricLayer.SINR -> m.sinrDb?.let { String.format(Locale.US, "%.1f dB", it) } ?: "Unavailable"
            MapMetricLayer.THROUGHPUT -> "No active test at this point"
            MapMetricLayer.GENERATION -> genDisplay
            MapMetricLayer.CONFIDENCE -> "Direct Field Measurement (100% confidence)"
        }

        val activeValNum = when (activeMetricLayer) {
            MapMetricLayer.RSRP -> (m.rsrpDbm ?: m.primarySignalDbm ?: m.rssiDbm)?.toDouble()
            MapMetricLayer.RSRQ -> m.rsrqDb?.toDouble()
            MapMetricLayer.SINR -> m.sinrDb
            MapMetricLayer.THROUGHPUT -> null
            MapMetricLayer.GENERATION -> null
            MapMetricLayer.CONFIDENCE -> 100.0
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

        if (currentNearbySamples.size > 1) {
            disambiguationContainer.visibility = View.VISIBLE
            tvDisambiguationLabel.text = "Overlapping Sample ${currentNearbyIndex + 1} of ${currentNearbySamples.size} (ID #${m.id})"
        } else {
            disambiguationContainer.visibility = View.GONE
        }
        btnInspectPlanEvent.visibility = View.VISIBLE
        btnInspectMeasurements.visibility = View.VISIBLE

        tvInspectPci.text = "PCI: ${m.servingPci ?: "—"}  •  TAC: ${m.tac ?: "—"}  •  ECI: ${m.eci ?: "—"}"
        tvInspectMetrics.text = "${activeMetricLayer.displayName}: $activeValStr  |  Signal: $primarySignalStr  |  SINR: ${m.sinrDb?.let { String.format(Locale.US, "%.1f dB", it) } ?: "—"}"
        tvInspectMetrics.setTextColor(cardColor)

        val speedText = m.speedKmh?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "0 km/h"
        val timeText = "${TelecomTimeUtils.isoToBdtTimeOnly(m.timestampIso, includeMillis = true)} (BST)"
        tvInspectDetails.text = "$bandText\nTech: $genDisplay | Voice: $voiceDisplay | Svc: $serviceStateDisplay | Data: $dataStateDisplay\nSpeed: $speedText  •  Time: $timeText$eventNote"

        inspectionCard.visibility = View.VISIBLE
    }

    private fun showCellInspectionCard(cell: CoverageCell) {
        val activeValStr = when (activeMetricLayer) {
            MapMetricLayer.RSRP -> cell.interpolatedRsrp?.let { String.format(Locale.US, "%.1f dBm", it) } ?: "Unavailable"
            MapMetricLayer.RSRQ -> cell.interpolatedRsrq?.let { String.format(Locale.US, "%.1f dB", it) } ?: "Unavailable"
            MapMetricLayer.SINR -> cell.interpolatedSinr?.let { String.format(Locale.US, "%.1f dB", it) } ?: "Unavailable"
            MapMetricLayer.THROUGHPUT -> "Unavailable"
            MapMetricLayer.GENERATION -> "Spatial Cell"
            MapMetricLayer.CONFIDENCE -> "Confidence: ${(cell.confidenceScore * 100).toInt()}%"
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
        isFollowGpsEnabled = false
        onFollowGpsChanged?.invoke(false)
        updateModeBanner()
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
            val latest = valid.last()
            map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(latest.latitude!!, latest.longitude!!)))
        }
    }

    fun recenterOnLatest(forceZoom: Double? = null) {
        val map = mapLibreMap ?: return
        isFollowGpsEnabled = true
        onFollowGpsChanged?.invoke(true)
        updateModeBanner()
        val latest = measurements.lastOrNull { it.latitude != null && it.longitude != null && it.isGpsValid }
        if (latest != null) {
            val pos = LatLng(latest.latitude!!, latest.longitude!!)
            if (forceZoom != null) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, forceZoom))
            } else if (map.cameraPosition.zoom < 11.0) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 15.0))
            } else {
                // Keep the user's manual zoom intact without resetting!
                map.animateCamera(CameraUpdateFactory.newLatLng(pos))
            }
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
                else -> Color.parseColor("#D50000") // Poor (< -100 dBm)
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
            MapMetricLayer.CONFIDENCE -> when {
                value >= 70.0 -> Color.parseColor("#00E676") // High Confidence
                value >= 35.0 -> Color.parseColor("#FFD600") // Moderate / Low Confidence
                else -> Color.parseColor("#757575") // Unsupported / negligible
            }
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
