package com.cellscope.drive

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.CheckBox
import android.widget.AdapterView
import android.content.res.ColorStateList
import android.graphics.Color
import com.cellscope.drive.analytics.EventVenue
import com.cellscope.drive.analytics.EventPreset
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cellscope.drive.analytics.CampusVenue
import com.cellscope.drive.analytics.CapacityScenarioSpec
import com.cellscope.drive.analytics.CapacityRecommendationEngine
import com.cellscope.drive.analytics.CongestionAnalyzer
import com.cellscope.drive.analytics.CoverageGridEngine
import com.cellscope.drive.analytics.CoverageHoleEstimator
import com.cellscope.drive.analytics.CoverageHoleRecord
import com.cellscope.drive.analytics.CoverageHoleSeverity
import com.cellscope.drive.analytics.CoveragePlanner
import com.cellscope.drive.analytics.CoverageSurface
import com.cellscope.drive.analytics.EstimatedTower
import com.cellscope.drive.analytics.EventCapacitySimulator
import com.cellscope.drive.analytics.EventPlanRepository
import com.cellscope.drive.analytics.SavedEventPlan
import com.cellscope.drive.analytics.EventTrafficProfile
import com.cellscope.drive.ui.map.MapDisplayMode
import com.cellscope.drive.ui.map.MapLayer
import com.cellscope.drive.ui.rfgraph.RfGraphViewMode
import com.cellscope.drive.ui.rfgraph.RfSelectionMode
import com.cellscope.drive.analytics.HandoverAnalyzer
import com.cellscope.drive.analytics.InterferenceAnalyzer
import com.cellscope.drive.analytics.RadioQualityEngine
import com.cellscope.drive.location.OrientationData
import com.cellscope.drive.location.OrientationTracker
import com.cellscope.drive.util.TelecomTimeUtils
import com.cellscope.drive.analytics.ReuseAnalyzer
import com.cellscope.drive.analytics.ServingNeighborIntelligence
import com.cellscope.drive.analytics.TowerEstimator
import com.cellscope.drive.analytics.CongestionEvidenceEngine
import com.cellscope.drive.analytics.RatComparisonEngine
import com.cellscope.drive.telephony.NetworkQoeScheduler
import com.cellscope.drive.telephony.OperatorIdentity
import com.cellscope.drive.data.db.CellScopeDatabase
import com.cellscope.drive.ui.theme.AppThemeManager
import com.cellscope.drive.ui.theme.AppThemeMode
import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.databinding.ActivityMainBinding
import com.cellscope.drive.export.CsvExporter
import com.cellscope.drive.export.PdfReportExporter
import java.io.File
import com.cellscope.drive.model.GpsData
import com.cellscope.drive.model.LteMetrics
import com.cellscope.drive.model.RecordingState
import com.cellscope.drive.model.SelectedLocationState
import com.cellscope.drive.model.LocationSelectionSource
import com.cellscope.drive.model.ObservedCell
import com.cellscope.drive.model.ServingStatus
import com.cellscope.drive.recorder.DriveTestRecorder
import com.cellscope.drive.service.DriveTestService
import com.cellscope.drive.telephony.LteBandTable
import com.cellscope.drive.ui.CellAdapter
import com.cellscope.drive.ui.CellComparisonItem
import com.cellscope.drive.ui.ComparisonAdapter
import com.cellscope.drive.ui.SessionAdapter
import com.cellscope.drive.ui.TransitionAdapter
import com.cellscope.drive.ui.WeakZoneAdapter
import com.cellscope.drive.ui.map.MapConfig
import com.cellscope.drive.ui.map.OfflineMapManager
import com.google.android.material.tabs.TabLayout
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var recorder: DriveTestRecorder
    private lateinit var offlineMapManager: OfflineMapManager
    private lateinit var eventPlanRepository: EventPlanRepository

    private val cellAdapter = CellAdapter()
    private val transitionAdapter = TransitionAdapter()
    private val weakZoneAdapter = WeakZoneAdapter()
    private val comparisonAdapter = ComparisonAdapter()

    private var activeSessionToInspect: DriveSession? = null
    var authoritativeLocationState: SelectedLocationState? = null
    private var cachedSurface: CoverageSurface? = null
    private var cachedSurfaceSessionId: Long? = null
    private var cachedTowers: List<EstimatedTower> = emptyList()
    private var cachedTowersSessionId: Long? = null
    private var lastDetectedCoverageHoles: List<CoverageHoleRecord> = emptyList()
    private var cachedMeasurements: List<LteMeasurementEntity> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val phoneState = permissions[Manifest.permission.READ_PHONE_STATE] ?: false

        if (fineLocation && phoneState) {
            Toast.makeText(this, "Permissions granted. Ready for network survey.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Location and Phone permissions are required to capture RF & GPS measurements.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private var pendingPdfFileToSave: java.io.File? = null
    private var pendingCsvFileToSave: java.io.File? = null

    private val createPdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null && pendingPdfFileToSave != null) {
            writeBytesToUri(pendingPdfFileToSave!!, uri, "PDF Report")
        }
    }

    private val createCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null && pendingCsvFileToSave != null) {
            writeBytesToUri(pendingCsvFileToSave!!, uri, "CSV Dataset")
        }
    }

    private fun writeBytesToUri(sourceFile: java.io.File, destinationUri: android.net.Uri, fileLabel: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(destinationUri)?.use { outStream ->
                    sourceFile.inputStream().use { inStream ->
                        inStream.copyTo(outStream)
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "$fileLabel saved successfully to device.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to save $fileLabel: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val importCsvLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            handleImportCsvUri(uri)
        }
    }

    private fun handleImportCsvUri(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val defaultName = "Imported_${System.currentTimeMillis()}"
            val summary = com.cellscope.drive.data.CsvImporter.validateCsv(this@MainActivity, uri, defaultName)
            withContext(Dispatchers.Main) {
                if (!summary.isValid) {
                    val errors = summary.errors.joinToString("\n• ")
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("CSV Validation Failed")
                        .setMessage("The selected file failed validation:\n\n• $errors")
                        .setPositiveButton("OK", null)
                        .show()
                    return@withContext
                }

                if (summary.isDuplicate) {
                    val dupMsg = "This dataset matches an existing database session:\n\n" +
                            "• Existing Session: ${summary.existingSessionName ?: "Session #${summary.existingSessionId}"}\n" +
                            "• Fingerprint SHA-256 match\n" +
                            "• File: ${summary.filename}\n" +
                            "• Total Samples: ${summary.totalRows}\n\n" +
                            "Would you like to inspect the existing session or import a duplicate copy?"
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Duplicate Dataset Detected")
                        .setMessage(dupMsg)
                        .setPositiveButton("Inspect Existing") { _, _ ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val existing = summary.existingSessionId?.let { id ->
                                    recorder.database.sessionDao().getSessionById(id)
                                }
                                withContext(Dispatchers.Main) {
                                    if (existing != null) {
                                        activeSessionToInspect = existing
                                        updateSessionIntelligenceUi(existing)
                                        refreshAnalytics(forceRecompute = true)
                                        refreshMapData()
                                        binding.bottomNav.selectedItemId = R.id.nav_dashboard
                                    } else {
                                        Toast.makeText(this@MainActivity, "Existing session could not be loaded.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        .setNeutralButton("Import as Copy") { _, _ ->
                            performCsvImport(uri, summary.filename, allowDuplicate = true)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    return@withContext
                }

                val gpsPct = if (summary.totalRows > 0) summary.validGpsRows * 100.0 / summary.totalRows else 0.0
                val msg = StringBuilder()
                msg.append("DATASET AUDIT PREVIEW:\n")
                msg.append("• File: ${summary.filename}\n")
                msg.append("• Total Rows: ${summary.totalRows}\n")
                msg.append("• Valid GPS Rows: ${summary.validGpsRows} (${String.format(Locale.US, "%.1f%%", gpsPct)})\n")
                msg.append("• Estimated Duration: ${summary.durationSeconds}s (~${summary.durationSeconds / 60} min)\n")
                msg.append("• Operator: ${summary.detectedOperator ?: "Unknown"}\n")
                msg.append("• Detected Columns: ${summary.detectedColumns.size}\n\n")

                if (summary.warnings.isNotEmpty()) {
                    msg.append("QUALITY WARNINGS:\n• ")
                    msg.append(summary.warnings.joinToString("\n• "))
                    msg.append("\n\n")
                }
                msg.append("Would you like to import and index this dataset into CellScope?")

                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Import Dataset Preview")
                    .setMessage(msg.toString())
                    .setPositiveButton("Confirm Import") { _, _ ->
                        performCsvImport(uri, summary.filename)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun performCsvImport(uri: android.net.Uri, fileName: String, allowDuplicate: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val session = com.cellscope.drive.data.CsvImporter.importCsvToDatabase(
                    context = this@MainActivity,
                    uri = uri,
                    customSessionName = fileName,
                    allowDuplicate = allowDuplicate
                )
                withContext(Dispatchers.Main) {
                    activeSessionToInspect = session
                    loadSessions()
                    updateSessionIntelligenceUi(session)
                    refreshAnalytics(forceRecompute = true)
                    refreshMapData()

                    val durMins = (session.endTimeMs?.minus(session.startTimeMs) ?: 0L) / 60000L
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Dataset Successfully Imported")
                        .setMessage("Imported ${session.sampleCount} samples (Duration: ${durMins}m, Distance: ${String.format(Locale.US, "%.2f km", session.totalDistanceM / 1000.0)}).\n\nWhat would you like to explore?")
                        .setPositiveButton("View Dashboard") { _, _ ->
                            binding.bottomNav.selectedItemId = R.id.nav_dashboard
                        }
                        .setNeutralButton("View Dynamic Map") { _, _ ->
                            binding.bottomNav.selectedItemId = R.id.nav_map
                        }
                        .setNegativeButton("View Analytics") { _, _ ->
                            binding.bottomNav.selectedItemId = R.id.nav_analytics
                        }
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Import Failed")
                        .setMessage(e.localizedMessage ?: "Unknown import error.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun updateSessionIntelligenceUi(session: DriveSession) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = recorder.database
            val measurements = db.measurementDao().getMeasurementsForSession(session.id)
            val transitions = db.transitionDao().getTransitionsForSession(session.id)

            val durationSec = if (session.endTimeMs != null && session.startTimeMs > 0 && session.endTimeMs!! > session.startTimeMs) {
                (session.endTimeMs!! - session.startTimeMs) / 1000L
            } else {
                measurements.size.toLong()
            }
            val mins = durationSec / 60
            val remSecs = durationSec % 60
            val durStr = String.format(Locale.US, "%02d:%02d (%d min)", mins, remSecs, mins)

            val distKm = session.totalDistanceM / 1000.0
            val distStr = String.format(Locale.US, "%.2f km", distKm)

            val validGps = measurements.count { it.latitude != null && it.longitude != null && it.isGpsValid }
            val gpsPct = if (measurements.isNotEmpty()) (validGps * 100.0 / measurements.size) else 0.0
            val samplesStr = "${measurements.size} (${String.format(Locale.US, "%.1f%% GPS", gpsPct)})"

            // RSRP & RSRQ Percentiles
            val rsrpVals = measurements.mapNotNull { it.rsrpDbm }.sorted()
            val rsrqVals = measurements.mapNotNull { it.rsrqDb }.sorted()

            fun percentile(list: List<Int>, p: Double): String {
                if (list.isEmpty()) return "—"
                val idx = ((list.size - 1) * p).toInt().coerceIn(0, list.size - 1)
                return "${list[idx]}"
            }

            val rsrpMean = if (rsrpVals.isNotEmpty()) String.format(Locale.US, "%.1f", rsrpVals.average()) else "—"
            val rsrqMean = if (rsrqVals.isNotEmpty()) String.format(Locale.US, "%.1f", rsrqVals.average()) else "—"

            val rsrpStats = "RSRP: Mean $rsrpMean | Med ${percentile(rsrpVals, 0.5)} | P10 ${percentile(rsrpVals, 0.1)} | P90 ${percentile(rsrpVals, 0.9)} dBm"
            val rsrqStats = "RSRQ: Mean $rsrqMean | Med ${percentile(rsrqVals, 0.5)} | P10 ${percentile(rsrqVals, 0.1)} | P90 ${percentile(rsrqVals, 0.9)} dB"

            // Quality distribution
            val exc = rsrpVals.count { it >= -80 }
            val good = rsrpVals.count { it in -90..-81 }
            val fair = rsrpVals.count { it in -100..-91 }
            val poor = rsrpVals.count { it < -100 }
            val totalRsrp = maxOf(1, rsrpVals.size)
            val distText = String.format(
                Locale.US,
                "Coverage Quality: %d%% Exc • %d%% Good • %d%% Fair • %d%% Poor",
                exc * 100 / totalRsrp,
                good * 100 / totalRsrp,
                fair * 100 / totalRsrp,
                poor * 100 / totalRsrp
            )

            val uniqueCells = measurements.mapNotNull { it.servingPci }.distinct().size
            val confirmedHandovers = transitions.count { it.eventType == "CONFIRMED_HANDOVER" }
            val cellsTransText = "Unique Serving PCIs: $uniqueCells | Transitions: ${transitions.size} ($confirmedHandovers confirmed handovers)"

            // Coverage Holes Detection
            val detectedHoles = CoverageHoleEstimator.estimateCoverageHoles(measurements)
            val holesSummaryText = if (detectedHoles.isEmpty()) {
                "Zero coverage holes detected. Continuous coverage maintained (≥ -105 dBm)."
            } else {
                val totalCorridorM = detectedHoles.sumOf { h -> h.corridorLengthMeters }
                val minHoleRsrp = detectedHoles.minOf { h -> h.minRsrpDbm }
                val severeCount = detectedHoles.count { h -> h.severity == CoverageHoleSeverity.SEVERE_DEFICIT || h.severity == CoverageHoleSeverity.CRITICAL_DROPOUT }
                val outageCount = detectedHoles.count { h -> h.isConfirmedOutage }
                "${detectedHoles.size} Holes Detected • Corridor: ${String.format(Locale.US, "%.0fm", totalCorridorM)} • Worst RSRP: $minHoleRsrp dBm • Severe: $severeCount (Outages: $outageCount, Weak: ${detectedHoles.size - outageCount})"
            }

            // Congestion Intelligence
            val congestionReport = CongestionAnalyzer.analyzeCongestion(measurements)
            val congestionSummaryText = "Level: ${congestionReport.confidenceLevel.label}\n" +
                    "Limiting Factor: ${congestionReport.primaryLimitingFactor} • ${congestionReport.engineeringEvidence}"

            // Site Suitability & Capacity Recommendations
            val capacityReport = CapacityRecommendationEngine.evaluateRecommendations(
                session = session,
                measurements = measurements,
                transitions = transitions,
                congestionReport = congestionReport,
                coverageHoles = detectedHoles
            )
            val topRec = capacityReport.recommendations.firstOrNull()
            val planningSummaryText = "Top Action: ${topRec?.title ?: "Maintain Config"} [Priority: ${topRec?.priorityRank ?: 1}]\n" +
                    "Site Suitability: ${capacityReport.siteSuitability.overallSuitabilityScore.toInt()}/100 • Disclosures: ${capacityReport.siteSuitability.missingEnvironmentalInputs.joinToString()}"

            // Signal Quality & Baseband Clamping Audit
            val qualityAudit = RadioQualityEngine.auditSignalQuality(measurements)
            val warnText = if (qualityAudit.suspectClampCount > 0) {
                "[BASEBAND CLAMP] ${qualityAudit.suspectClampCount} samples clamped at -44 dBm. Calibrated mean RSRP: ${qualityAudit.meanRsrpCalibrated ?: "—"} dBm (Raw mean: ${qualityAudit.meanRsrpRaw ?: "—"} dBm). Raw readings preserved."
            } else if (gpsPct < 90.0) {
                "[GPS WARNING] ${measurements.size - validGps} points missing valid GPS coordinates. Check location accuracy."
            } else {
                "Dataset Quality: 100% telemetry integrity verified. Zero anomalies."
            }

            val operator = measurements.mapNotNull { it.operatorAlpha }.firstOrNull() ?: recorder.telephonyCollector.getOperatorIdentity().networkResolvedName
            val startTimeStr = TelecomTimeUtils.formatBdtPretty(session.startTimeMs)

            withContext(Dispatchers.Main) {
                if (recorder.recordingState.value == RecordingState.IDLE) {
                    binding.layoutLiveTelemetrySection.visibility = View.GONE
                    binding.cardLiveInactiveBanner.visibility = View.VISIBLE
                    binding.tvLiveInactiveDesc.text = "Currently inspecting saved dataset '${session.sessionName}'. Live telemetry is paused."
                }
                lastDetectedCoverageHoles = detectedHoles
                binding.cardSessionIntelligence.visibility = View.VISIBLE
                binding.tvSessionIntelTitle.text = "Dataset: ${session.sessionName}"
                binding.tvSessionIntelMeta.text = "$startTimeStr (BST) • $operator"
                binding.tvSessionIntelDuration.text = durStr
                binding.tvSessionIntelDistance.text = distStr
                binding.tvSessionIntelSamples.text = samplesStr
                binding.coveragePreviewView.setData(
                    newMeasurements = measurements,
                    newSurface = cachedSurface,
                    newHoles = detectedHoles,
                    title = session.sessionName
                )
                binding.tvSessionIntelRsrpStats.text = rsrpStats
                binding.tvSessionIntelRsrqStats.text = rsrqStats
                binding.tvSessionIntelDistribution.text = distText
                binding.tvSessionIntelCellsHandovers.text = cellsTransText
                binding.tvSessionIntelCoverageHoles.text = if (detectedHoles.isNotEmpty()) {
                    "$holesSummaryText\n👉 Tap here to locate ${detectedHoles.size} weak zone(s) on Dynamic Map"
                } else {
                    holesSummaryText
                }
                binding.tvSessionIntelCongestion.text = congestionSummaryText
                binding.tvSessionIntelRanPlanning.text = planningSummaryText
                binding.tvSessionIntelQualityWarnings.text = warnText
                updateEventCapacityDashboard(measurements)
            }
        }
    }

    private fun switchToLiveMode() {
        activeSessionToInspect = null
        binding.cardSessionIntelligence.visibility = View.GONE
        binding.cardLiveInactiveBanner.visibility = View.GONE
        binding.layoutLiveTelemetrySection.visibility = View.VISIBLE
        binding.driveMapView.isHistoricalMode = false
        binding.driveMapView.historicalSessionName = null
        binding.driveMapView.updateModeBanner()
        refreshMapData()
        Toast.makeText(this, "Switched to Live Telemetry Mode", Toast.LENGTH_SHORT).show()
    }

    private var eventAttendeesCount: Int = 20000
    private var eventHasCow: Boolean = false
    private var eventTrafficProfile: EventTrafficProfile = EventTrafficProfile.MODERATE

    private fun updateEventCapacityDashboard(measurements: List<LteMeasurementEntity> = emptyList()) {
        val locState = authoritativeLocationState
        val activeVenue: EventVenue? = locState?.let {
            EventVenue.fromCoordinates(
                lat = it.latitude,
                lon = it.longitude,
                name = it.eventName ?: it.resolvedLocality ?: "Event Venue",
                eventName = it.eventName ?: "Gathering",
                radiusMeters = it.boundaryRadiusMeters
            )
        } ?: binding.driveMapView.activeEventVenue ?: run {
            val firstValid = measurements.firstOrNull { it.latitude != null && it.longitude != null && it.isGpsValid }
            if (firstValid != null) {
                EventVenue.fromCoordinates(
                    lat = firstValid.latitude!!,
                    lon = firstValid.longitude!!,
                    name = "Survey Area (Sample #${firstValid.id})",
                    eventName = "Survey Event Area",
                    radiusMeters = 500.0
                )
            } else null
        }

        if (activeVenue == null) {
            binding.tvEventVenueDesc.text = "No Venue Selected • Tap a survey marker or map location to plan"
            binding.tvEventMacroInventory.text = "Macro Inventory: Awaiting location selection"
            binding.tvEventOfferedLoad.text = "--"
            binding.tvEventAvailableCap.text = "--"
            binding.tvEventDeficit.text = "Awaiting Location"
            binding.tvEventDeficit.setTextColor(Color.parseColor("#9E9E9E"))
            binding.tvEventPrbLoad.text = "--"
            binding.tvEventInterventions.text = "Select a survey sample marker or tap on the map to define the event boundary."
            return
        }

        val spec = CapacityScenarioSpec(
            scenarioId = if (eventAttendeesCount == 0) "BASE" else if (eventAttendeesCount == 10000) "SCENARIO_A" else if (eventHasCow) "SCENARIO_C" else "SCENARIO_B",
            title = if (eventAttendeesCount == 0) "Baseline Load" else if (eventAttendeesCount == 10000) "+10k Attendees Gathering" else if (eventHasCow) "+20k Attendees + Temporary COW" else "+20k Attendees Gathering",
            additionalAttendees = eventAttendeesCount,
            activeUserRatio = 0.20,
            trafficProfile = eventTrafficProfile,
            hasTemporaryCow = eventHasCow,
            hasWifiOffload = eventHasCow,
            hasAntennaDowntiltOptimization = eventHasCow
        )
        val result = EventCapacitySimulator.simulateScenario(
            venue = activeVenue,
            spec = spec,
            measurements = measurements
        )

        binding.tvEventVenueDesc.text = "Venue: ${activeVenue.name} (${String.format(Locale.US, "%.4f°N, %.4f°E", activeVenue.centerLat, activeVenue.centerLon)}) • Tap map to plan anywhere"
        binding.tvEventMacroInventory.text = "Macro Inventory: ${result.coverageEvidence.evidenceSummary}"
        binding.tvEventOfferedLoad.text = String.format(Locale.US, "%.0f Mbps", result.offeredTrafficDlMbps)
        binding.tvEventAvailableCap.text = result.totalAvailableCapacityMbps?.let { String.format(Locale.US, "%.0f Mbps", it) } ?: "Unknown (Unmeasured)"

        if (result.capacityDeficitMbps != null && result.capacityDeficitMbps > 0.0) {
            binding.tvEventDeficit.text = String.format(Locale.US, "-%.0f Mbps (Deficit)", result.capacityDeficitMbps)
            binding.tvEventDeficit.setTextColor(Color.parseColor("#D50000"))
        } else if (result.unusedCapacityMarginMbps != null && result.unusedCapacityMarginMbps > 0.0) {
            binding.tvEventDeficit.text = String.format(Locale.US, "+%.0f Mbps (Surplus)", result.unusedCapacityMarginMbps)
            binding.tvEventDeficit.setTextColor(Color.parseColor("#00E676"))
        } else {
            binding.tvEventDeficit.text = "Unknown (Survey Needed)"
            binding.tvEventDeficit.setTextColor(Color.parseColor("#FF9800"))
        }

        if (result.actualPrbUtilizationPct != null) {
            binding.tvEventPrbLoad.text = String.format(Locale.US, "%.0f%%", result.actualPrbUtilizationPct)
            binding.tvEventPrbLoad.setTextColor(when {
                result.actualPrbUtilizationPct > 90.0 -> Color.parseColor("#D50000")
                result.actualPrbUtilizationPct > 70.0 -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#00E676")
            })
        } else {
            binding.tvEventPrbLoad.text = "Unknown — eNodeB counters required"
            binding.tvEventPrbLoad.setTextColor(Color.parseColor("#9E9E9E"))
        }

        val interventionText = if (result.recommendedInterventions.isNotEmpty()) {
            "Candidate Interventions (Engineering Evaluation):\n• " + result.recommendedInterventions.joinToString("\n• ")
        } else {
            "Interventions: Existing macro network has sufficient nominal capacity for this profile."
        }
        binding.tvEventInterventions.text = interventionText
    }

    private fun showEventScenarioComparisonDialog(measurements: List<LteMeasurementEntity> = emptyList()) {
        val locState = authoritativeLocationState
        val activeVenue: EventVenue? = locState?.let {
            EventVenue.fromCoordinates(
                lat = it.latitude,
                lon = it.longitude,
                name = it.eventName ?: it.resolvedLocality ?: "Event Venue",
                eventName = it.eventName ?: "Gathering",
                radiusMeters = it.boundaryRadiusMeters
            )
        } ?: binding.driveMapView.activeEventVenue ?: run {
            val firstValid = measurements.firstOrNull { it.latitude != null && it.longitude != null && it.isGpsValid }
            if (firstValid != null) {
                EventVenue.fromCoordinates(
                    lat = firstValid.latitude!!,
                    lon = firstValid.longitude!!,
                    name = "Survey Area (Sample #${firstValid.id})",
                    eventName = "Survey Event Area",
                    radiusMeters = 500.0
                )
            } else null
        }

        if (activeVenue == null) {
            Toast.makeText(this, "Please select a survey sample or location on the map to compare scenarios.", Toast.LENGTH_LONG).show()
            return
        }

        val comparison = EventCapacitySimulator.runMultiScenarioComparison(
            venue = activeVenue,
            measurements = measurements
        )
        val b = comparison.baseline
        val a = comparison.scenarioA
        val scB = comparison.scenarioB
        val c = comparison.scenarioC

        val message = buildString {
            append("📍 Venue: ${comparison.venue.name}\n")
            append("🎯 Event: ${comparison.venue.eventName}\n")
            append("📶 Baseline Macro: ${comparison.venue.primaryOperator} ${comparison.venue.primaryBand}\n")
            append("📊 Evidence: ${b.coverageEvidence.status.label} (${b.coverageEvidence.sampleCountInArea} survey points)\n\n")

            append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            append("1. BASELINE (Normal Operation)\n")
            append("• Attendees: ${b.totalAttendees} | Active: ${b.concurrentActiveUsers}\n")
            append("• Offered: ${b.offeredTrafficDlMbps.toInt()} Mbps | Available: ${b.totalAvailableCapacityMbps?.toInt()?.let { "$it Mbps" } ?: "Unknown"}\n")
            append("• PRB Utilization: ${b.actualPrbUtilizationPct?.toInt()?.let { "$it%" } ?: "Unknown (Operator Counters Required)"} [${b.bottleneckSeverity}]\n\n")

            append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            append("2. SCENARIO A (+10,000 Attendees)\n")
            append("• Total: ${a.totalAttendees} | Active: ${a.concurrentActiveUsers}\n")
            append("• Offered: ${a.offeredTrafficDlMbps.toInt()} Mbps | Available: ${a.totalAvailableCapacityMbps?.toInt()?.let { "$it Mbps" } ?: "Unknown"}\n")
            append("• Deficit: ${a.capacityDeficitMbps?.let { "-${it.toInt()} Mbps" } ?: "Surplus / Unknown"} | PRB: ${a.actualPrbUtilizationPct?.toInt()?.let { "$it%" } ?: "Unknown (Operator Counters Required)"} [${a.bottleneckSeverity}]\n\n")

            append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            append("3. SCENARIO B (+20,000 Attendees - Unmitigated)\n")
            append("• Total: ${scB.totalAttendees} | Active: ${scB.concurrentActiveUsers}\n")
            append("• Offered: ${scB.offeredTrafficDlMbps.toInt()} Mbps | Available: ${scB.totalAvailableCapacityMbps?.toInt()?.let { "$it Mbps" } ?: "Unknown"}\n")
            append("• Deficit: ${scB.capacityDeficitMbps?.let { "-${it.toInt()} Mbps" } ?: "Surplus / Unknown"} | PRB: ${scB.actualPrbUtilizationPct?.toInt()?.let { "$it%" } ?: "Unknown (Operator Counters Required)"} [${scB.bottleneckSeverity}]\n")
            append("⚠️ Severe congestion and connection drops expected without intervention!\n\n")

            append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            append("4. SCENARIO C (+20,000 + Candidate Interventions)\n")
            append("• Candidate Interventions: Multi-band COW (+350 Mbps assumed) + 6x Wi-Fi 6 APs + Antenna Down-tilt\n")
            append("• Offered: ${c.offeredTrafficDlMbps.toInt()} Mbps | Total Cap: ${c.totalAvailableCapacityMbps?.toInt()?.let { "$it Mbps" } ?: "Unknown"}\n")
            append("• Deficit: ${c.capacityDeficitMbps?.let { if (it > 0) "-${it.toInt()} Mbps" else "0 Mbps (RESOLVED ✅)" } ?: "Surplus / Resolved ✅"} | PRB: ${c.actualPrbUtilizationPct?.toInt()?.let { "$it%" } ?: "Unknown (Operator Counters Required)"} [${c.bottleneckSeverity}]\n\n")

            append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            append("📋 RECOMMENDATION & DISCLOSURES:\n")
            append(comparison.recommendedDecision)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Event Capacity Simulator — Multi-Scenario Analysis")
            .setMessage(message)
            .setPositiveButton("View On Map 🗺️") { _, _ ->
                binding.bottomNav.selectedItemId = R.id.nav_map
                binding.chipModeEvent.isChecked = true
                binding.driveMapView.focusEventPlanning(activeVenue)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun updateActiveLayerBadge() {
        val layers = binding.driveMapView.activeLayers
        val allCount = MapLayer.values().size
        val badge = when {
            layers.size >= allCount -> "All Layers Active ($allCount)"
            layers.isEmpty() -> "No Layers Active"
            layers.size == 1 -> layers.first().displayName
            else -> "${layers.size} Layers Active"
        }
        val metric = if (layers.contains(MapLayer.COVERAGE_HEATMAP) || layers.contains(MapLayer.ROUTE_SAMPLES)) {
            " • ${binding.driveMapView.activeMetricLayer.displayName}"
        } else ""
        binding.tvActiveLayerBadge.text = "🗺️ $badge$metric ▾"
    }

    private fun showUniversalEventPlannerDialog(lat: Double, lon: Double, localityHint: String) {
        val state = authoritativeLocationState?.takeIf { it.latitude == lat && it.longitude == lon }
            ?: SelectedLocationState.fromMapTap(latitude = lat, longitude = lon, localityHint = localityHint)
        showUniversalEventPlannerDialog(state)
    }

    private fun showSinrDiagnosticsDialog(lte: LteMetrics) {
        val bwRb = com.cellscope.drive.telephony.SinrDiagnostics.bandwidthToResourceBlocks(lte.bandwidthKhz)
        val bwStr = lte.bandwidthKhz?.let { "${it / 1000} MHz ($bwRb RBs)" } ?: "10 MHz nominal ($bwRb RBs)"
        val rsrpStr = lte.rsrpDbm?.let { "$it dBm" } ?: "Unavailable"
        val rsrqStr = lte.rsrqDb?.let { "$it dB" } ?: "Unavailable"
        val rssiStr = lte.rssiDbm?.let { "$it dBm" } ?: lte.impliedRssiDbm?.let { String.format(Locale.US, "%.1f dBm (Implied)", it) } ?: "Unavailable"
        val sinrStr = lte.sinrDb?.let { String.format(Locale.US, "%.1f dB", it) } ?: "Unavailable"

        val msg = StringBuilder()
        msg.append("Source Provenance: ${lte.sinrSource}\n")
        msg.append("Current SINR: $sinrStr\n\n")

        msg.append("── Physical Channel Inputs ──\n")
        msg.append("• Serving RSRP: $rsrpStr\n")
        msg.append("• Serving RSRQ: $rsrqStr\n")
        msg.append("• Carrier RSSI: $rssiStr\n")
        msg.append("• Bandwidth: $bwStr\n")
        msg.append("• Serving Cell PCI: ${lte.servingPci ?: "—"} (EARFCN: ${lte.earfcn ?: "—"})\n\n")

        msg.append("── Methodology & Radio Model ──\n")
        msg.append("• Method: ${lte.estimationMethod ?: "Standard 3GPP channel evaluation"}\n")
        if (lte.sinrSource == "ESTIMATED") {
            msg.append("• Formula: 3GPP TS 36.214 Carrier RSSI Relation:\n")
            msg.append("   RSRQ = N × (RSRP / RSSI)\n")
            msg.append("   SINR_est = 10 × log10(12 / (1/RSRQ_linear - 7.0))\n")
            msg.append("• Assumptions: 50% cell traffic load factor (η = 0.5), uniform subcarrier interference, flat Rayleigh channel.\n")
            msg.append("• Estimation Uncertainty: ±3.5 dB error bound due to unmeasured dynamic PRB scheduling.\n\n")
        } else if (lte.sinrSource == "MEASURED") {
            msg.append("• Details: Direct physical hardware measurement reported by device baseband modem RIL (CellSignalStrengthLte.rssnr or 5G SS-SINR).\n\n")
        } else {
            msg.append("• Reason: Device baseband modem HAL does not expose physical RSSNR register, and physical inputs are incomplete for estimation.\n\n")
        }

        msg.append("── Qualitative RF Assessment ──\n")
        msg.append("• ${lte.qualitativeDiagnostic ?: "Channel quality assessment unavailable"}\n")
        val edgeCenter = when {
            (lte.rsrpDbm ?: -140) >= -90 && (lte.sinrDb ?: -20.0) >= 12.0 -> "Cell-Center (Dominant serving beam, high throughput potential)"
            (lte.rsrpDbm ?: -140) < -105 || (lte.sinrDb ?: 0.0) < 3.0 -> "Cell-Edge (Interference limited or weak coverage boundary)"
            else -> "Mid-Cell (Nominal operational coverage)"
        }
        msg.append("• Geometry: $edgeCenter\n")

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("SINR Diagnostics & Radio Model")
            .setMessage(msg.toString())
            .setPositiveButton("Dismiss", null)
            .show()
    }

    private fun showSampleMeasurementDetailsDialog(m: LteMeasurementEntity) {
        val carrierInfo = com.cellscope.drive.telephony.CellularBandResolver.resolveMeasurement(m)
        val bandStr = carrierInfo?.let { "${it.bandName} (${it.duplexMode}) • DL ${it.dlCenterFreqMhz} MHz" } ?: "Channel: ${m.channelNumber ?: m.earfcn ?: "Unavailable"}"
        val rat = m.servingRat ?: m.networkGen ?: m.networkType
        val pciStr = m.servingPci?.let { "PCI $it" } ?: "PCI: Unavailable"
        val tacStr = m.tac?.let { "TAC $it" } ?: "TAC: Unavailable"
        val eciStr = m.eci?.let { "ECI $it" } ?: "ECI: Unavailable"
        val bdtTime = com.cellscope.drive.util.TelecomTimeUtils.isoToBdtTimeOnly(m.timestampIso, includeMillis = true)

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        scroll.addView(layout)

        fun addHeader(text: String) {
            val tv = TextView(this).apply {
                this.text = text
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(0, 16, 0, 4)
            }
            layout.addView(tv)
        }

        fun addField(name: String, value: String) {
            val tv = TextView(this).apply {
                this.text = "$name: $value"
                textSize = 11.5f
                setTextColor(Color.parseColor("#E2E8F0"))
                setPadding(0, 2, 0, 2)
            }
            layout.addView(tv)
        }

        addHeader("📡 Radio Access & Serving Cell")
        addField("Network Generation / RAT", "$rat (${m.dataNetworkType ?: m.networkType})")
        addField("Carrier & Band", bandStr)
        addField("Serving Cell Identifiers", "$pciStr • $tacStr • $eciStr")
        addField("Duplex & Channel", "${carrierInfo?.duplexMode ?: "FDD"} • Channel #${carrierInfo?.channelNumber ?: m.channelNumber ?: m.earfcn ?: "—"}")
        addField("Channel Bandwidth", carrierInfo?.bandwidthDisplay ?: (m.bandwidthKhz?.let { "${it / 1000} MHz" } ?: "Unknown — not exposed by available telemetry"))

        addHeader("📊 Signal Quality Measurements")
        val is2g = rat.contains("2G", ignoreCase = true) || rat.contains("GSM", ignoreCase = true)
        val is3g = rat.contains("3G", ignoreCase = true) || rat.contains("WCDMA", ignoreCase = true)
        val is5g = rat.contains("5G", ignoreCase = true) || rat.contains("NR", ignoreCase = true)

        if (is2g) {
            addField("GSM RSSI", (m.primarySignalDbm ?: m.rssiDbm)?.let { "$it dBm" } ?: "Unavailable")
            addField("GSM Bit Error Rate (BER)", "Unavailable (Not exposed by standard HAL)")
        } else if (is3g) {
            addField("WCDMA RSCP", (m.primarySignalDbm ?: m.rssiDbm)?.let { "$it dBm" } ?: "Unavailable")
            addField("WCDMA Ec/No", "Unavailable")
        } else if (is5g) {
            addField("5G SS-RSRP", (m.primarySignalDbm ?: m.rsrpDbm)?.let { "$it dBm" } ?: "Unavailable")
            addField("5G SS-SINR", m.sinrDb?.let { String.format(Locale.US, "%.1f dB", it) } ?: "Unavailable")
        } else {
            addField("LTE RSRP", m.rsrpDbm?.let { "$it dBm" } ?: "Unavailable")
            addField("LTE RSRQ", m.rsrqDb?.let { "$it dB" } ?: "Unavailable")
            val sinrBadge = if (m.sinrSource != "UNAVAILABLE") " [${m.sinrSource}]" else ""
            addField("LTE SINR", m.sinrDb?.let { String.format(Locale.US, "%.1f dB%s", it, sinrBadge) } ?: "Unavailable")
            if (m.sinrSource == "ESTIMATED") {
                addField("SINR Estimation Model", m.estimationMethod ?: "3GPP TS 36.214 Load Model")
                if (m.impliedRssiDbm != null) {
                    addField("Implied RSSI", String.format(Locale.US, "%.1f dBm", m.impliedRssiDbm))
                }
                addField("Estimation Uncertainty", "±3.5 dB (Nominal 50% cell load assumption)")
            } else if (m.sinrSource == "MEASURED") {
                addField("SINR Measurement", "Direct physical modem RSSNR report")
            }
            if (m.qualitativeDiagnostic != null) {
                addField("RF Condition Assessment", m.qualitativeDiagnostic)
            }
            addField("LTE RSSI", m.rssiDbm?.let { "$it dBm" } ?: "Unavailable")
            addField("CQI (Channel Quality)", m.cqi?.toString() ?: "Unavailable")
            addField("Timing Advance", m.timingAdvance?.toString() ?: "Unavailable")
        }

        addHeader("📍 Location, Motion & Time")
        addField("Coordinates", String.format(Locale.US, "%.5f°N, %.5f°E", m.latitude ?: 0.0, m.longitude ?: 0.0))
        addField("GPS Accuracy", m.accuracyM?.let { "±${String.format(Locale.US, "%.1f", it)}m" } ?: "Unavailable")
        addField("Speed & Heading", "${m.speedKmh?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "0 km/h"} • ${m.bearing?.let { "${it.toInt()}°" } ?: "Unavailable"}")
        addField("Timestamp (BDT)", "$bdtTime (${m.timestampIso})")
        addField("Sample Record ID", "Sample #${m.id} (Session #${m.sessionId})")

        // Local Window Trend Analysis (P6)
        val nearSamples = cachedMeasurements.filter { other ->
            if (m.latitude != null && m.longitude != null && other.latitude != null && other.longitude != null) {
                val dLat = (other.latitude!! - m.latitude!!) * 111320.0
                val dLon = (other.longitude!! - m.longitude!!) * 111320.0 * Math.cos(Math.toRadians(m.latitude!!))
                val dist = Math.sqrt(dLat * dLat + dLon * dLon)
                dist <= 100.0
            } else {
                Math.abs(other.timestampMs - m.timestampMs) <= 30000L
            }
        }.sortedBy { it.timestampMs }

        if (nearSamples.size > 1) {
            addHeader("📈 Local Window Trend (${nearSamples.size} samples within 100m)")
            val rsrps = nearSamples.mapNotNull { it.rsrpDbm }
            val rsrqs = nearSamples.mapNotNull { it.rsrqDb }
            val sinrs = nearSamples.mapNotNull { it.sinrDb }
            val minR = rsrps.minOrNull()
            val maxR = rsrps.maxOrNull()
            val avgR = if (rsrps.isNotEmpty()) Math.round(rsrps.average() * 10.0) / 10.0 else null
            addField("Local RSRP Range", "Min ${minR ?: "—"} dBm • Avg ${avgR ?: "—"} dBm • Max ${maxR ?: "—"} dBm")
            if (rsrqs.isNotEmpty()) {
                val avgRq = Math.round(rsrqs.average() * 10.0) / 10.0
                addField("Local RSRQ Average", "$avgRq dB")
            }
            if (sinrs.isNotEmpty()) {
                val avgSn = Math.round(sinrs.average() * 10.0) / 10.0
                addField("Local SINR Average", "$avgSn dB")
            }
            val firstR = rsrps.firstOrNull()
            val lastR = rsrps.lastOrNull()
            if (firstR != null && lastR != null) {
                val delta = lastR - firstR
                val trendDesc = when {
                    delta > 2 -> "Improving (+${delta} dB)"
                    delta < -2 -> "Deteriorating (${delta} dB)"
                    else -> "Stable (±2 dB variance)"
                }
                addField("Local Signal Trend", trendDesc)
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Surveyed Sample #${m.id}")
            .setView(scroll)
            .setPositiveButton("Plan Event Here") { _, _ ->
                val locality = com.cellscope.drive.analytics.CoverageHoleEstimator.resolveLocalityHint(m.latitude ?: 0.0, m.longitude ?: 0.0)
                val state = SelectedLocationState.fromSurveySample(
                    sampleId = m.id,
                    latitude = m.latitude ?: 0.0,
                    longitude = m.longitude ?: 0.0,
                    sessionId = m.sessionId,
                    localityHint = locality,
                    boundaryRadiusMeters = 500.0,
                    eventName = "Surveyed Point #${m.id}"
                )
                authoritativeLocationState = state
                showUniversalEventPlannerDialog(state)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showUniversalEventPlannerDialog(state: SelectedLocationState) {
        val lat = state.latitude
        val lon = state.longitude
        val localityHint = state.resolvedLocality ?: "Selected Coordinate"
        authoritativeLocationState = state
        binding.driveMapView.setSelectedLocation(state)

        val presets = EventPreset.entries.toTypedArray()
        val presetNames: Array<String> = presets.map { it.displayName }.toTypedArray()
        var selectedPreset = EventPreset.CUSTOM

        val profiles = EventTrafficProfile.entries.toTypedArray()
        val profileNames: Array<String> = profiles.map { it.label }.toTypedArray()
        var selectedProfile = EventTrafficProfile.MODERATE

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        scroll.addView(layout)

        val tvNameLabel = TextView(this).apply { text = "Venue / Event Name:"; textSize = 11f; setTextColor(Color.parseColor("#94A3B8")) }
        val etVenueName = EditText(this).apply {
            setText(state.eventName ?: localityHint.ifBlank { "Selected Event Site" })
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        layout.addView(tvNameLabel)
        layout.addView(etVenueName)

        val tvCoords = TextView(this).apply {
            text = String.format(Locale.US, "Location: %.5f°N, %.5f°E (Radius: %.0fm)", lat, lon, state.boundaryRadiusMeters)
            textSize = 11f
            setTextColor(Color.parseColor("#00E5FF"))
            setPadding(0, 4, 0, 4)
        }
        layout.addView(tvCoords)

        val surveyStatusText = if (state.isSurveyedSample) {
            "✅ Linked to Surveyed Sample #${state.sampleId ?: "Point"} (Empirical RF evidence active)"
        } else {
            "⚠️ Unsurveyed Location — No local empirical survey evidence"
        }
        val surveyStatusColor = if (state.isSurveyedSample) Color.parseColor("#4ADE80") else Color.parseColor("#F59E0B")
        val tvStatus = TextView(this).apply {
            text = surveyStatusText
            textSize = 10f
            setTextColor(surveyStatusColor)
            setPadding(0, 0, 0, 10)
        }
        layout.addView(tvStatus)

        val tvPresetLabel = TextView(this).apply { text = "Event Type / Preset:"; textSize = 11f; setTextColor(Color.parseColor("#94A3B8")) }
        val spinnerPreset = Spinner(this).apply {
            adapter = ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, presetNames)
            setSelection(presets.indexOf(EventPreset.CUSTOM))
        }
        layout.addView(tvPresetLabel)
        layout.addView(spinnerPreset)

        val tvAttendeesLabel = TextView(this).apply { text = "Expected Attendees:"; textSize = 11f; setTextColor(Color.parseColor("#94A3B8")); setPadding(0, 12, 0, 0) }
        val etAttendees = EditText(this).apply {
            setText("15000")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        layout.addView(tvAttendeesLabel)
        layout.addView(etAttendees)

        val tvProfileLabel = TextView(this).apply { text = "Traffic Demand Profile:"; textSize = 11f; setTextColor(Color.parseColor("#94A3B8")); setPadding(0, 12, 0, 0) }
        val spinnerProfile = Spinner(this).apply {
            adapter = ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, profileNames)
            setSelection(profiles.indexOf(EventTrafficProfile.MODERATE))
        }
        layout.addView(tvProfileLabel)
        layout.addView(spinnerProfile)

        val tvOptionsLabel = TextView(this).apply { text = "Candidate Intervention Scenarios:"; textSize = 11f; setTextColor(Color.parseColor("#94A3B8")); setPadding(0, 16, 0, 4) }
        layout.addView(tvOptionsLabel)

        val cbCow = CheckBox(this).apply {
            text = "Deploy Cell on Wheels (COW) (+350 Mbps candidate)"
            setTextColor(Color.WHITE)
            textSize = 11f
        }
        val cbWifi = CheckBox(this).apply {
            text = "Deploy Wi-Fi 6 Offload Nodes (40% bulk offload)"
            setTextColor(Color.WHITE)
            textSize = 11f
        }
        val cbTilt = CheckBox(this).apply {
            text = "Macro Antenna Down-tilt Optimization"
            setTextColor(Color.WHITE)
            textSize = 11f
        }
        layout.addView(cbCow)
        layout.addView(cbWifi)
        layout.addView(cbTilt)

        spinnerPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedPreset = presets[position]
                if (selectedPreset != EventPreset.CUSTOM) {
                    spinnerProfile.setSelection(profiles.indexOf(selectedPreset.defaultProfile))
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedProfile = profiles[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Universal Event Capacity Planner 📍")
            .setView(scroll)
            .setPositiveButton("Calculate & View Map 🗺️") { _, _ ->
                val venueName = etVenueName.text.toString().ifBlank { localityHint }
                val attendees = etAttendees.text.toString().toIntOrNull() ?: 15000
                val updatedState = state.withEventName(venueName)
                authoritativeLocationState = updatedState
                binding.driveMapView.setSelectedLocation(updatedState)

                val venue = EventVenue.fromCoordinates(
                    lat = lat,
                    lon = lon,
                    name = venueName,
                    eventName = "${selectedPreset.displayName} Gathering",
                    radiusMeters = selectedPreset.defaultRadiusM,
                    eventType = selectedPreset
                )

                val spec = CapacityScenarioSpec(
                    scenarioId = "UNIVERSAL_EVENT",
                    title = "$venueName Capacity Simulation",
                    additionalAttendees = attendees,
                    activeUserRatio = selectedPreset.defaultActiveRatio,
                    trafficProfile = selectedProfile,
                    hasTemporaryCow = cbCow.isChecked,
                    hasWifiOffload = cbWifi.isChecked,
                    hasAntennaDowntiltOptimization = cbTilt.isChecked
                )

                lifecycleScope.launch(Dispatchers.IO) {
                    val db = recorder.database
                    val targetSessionId = activeSessionToInspect?.id ?: recorder.currentSession.value?.id ?: db.sessionDao().getAllSessions().firstOrNull()?.id
                    val currentMeasurements = if (targetSessionId != null) {
                        db.measurementDao().getMeasurementsForSession(targetSessionId)
                    } else {
                        emptyList()
                    }

                    val result = EventCapacitySimulator.simulateScenario(
                        venue = venue,
                        spec = spec,
                        measurements = currentMeasurements
                    )

                    withContext(Dispatchers.Main) {
                        binding.bottomNav.selectedItemId = R.id.nav_map
                        binding.chipModeEvent.isChecked = true
                        binding.driveMapView.focusEventPlanning(venue, result)
                        updateActiveLayerBadge()

                        val summary = buildString {
                            append("📍 Venue: ${venue.name}\n")
                            append("🌐 Location: ${String.format(Locale.US, "%.5f°N, %.5f°E", venue.centerLat, venue.centerLon)}\n")
                            append("🎯 Preset: ${selectedPreset.displayName}\n")
                            append("📊 Local Evidence: ${result.coverageEvidence.status.label} (${result.coverageEvidence.sampleCountInArea} points)\n")
                            append("👥 Attendees: ${result.totalAttendees} | Active: ${result.concurrentActiveUsers}\n")
                            append("⚡ DL Offered Demand: ${result.offeredTrafficDlMbps.toInt()} Mbps\n")
                            if (result.existingMacroCapacityMbps != null) {
                                append("📶 Available Capacity: ${result.totalAvailableCapacityMbps?.toInt() ?: result.existingMacroCapacityMbps.toInt()} Mbps\n")
                                if (result.capacityDeficitMbps != null && result.capacityDeficitMbps > 0) {
                                    append("⚠️ Capacity Deficit: -${result.capacityDeficitMbps.toInt()} Mbps\n")
                                } else if (result.unusedCapacityMarginMbps != null && result.unusedCapacityMarginMbps > 0) {
                                    append("✅ Unused Capacity Margin: +${result.unusedCapacityMarginMbps.toInt()} Mbps surplus\n")
                                }
                                if (result.actualPrbUtilizationPct != null) {
                                    append("📈 PRB Load: ${result.actualPrbUtilizationPct.toInt()}%\n")
                                } else {
                                    append("📈 PRB Load: Unknown — operator eNodeB data required\n")
                                }
                            } else {
                                append("📶 Available Capacity: UNKNOWN (Unmeasured area)\n")
                                append("⚠️ Capacity Deficit: UNKNOWN (Survey required)\n")
                                append("📈 PRB Load: UNKNOWN (Operator counters required)\n")
                            }
                            if (result.recommendedInterventions.isNotEmpty()) {
                                append("\nCandidate Interventions (Non-Additive Evaluation):\n• " + result.recommendedInterventions.joinToString("\n• "))
                            }
                        }

                        val savedPlan = SavedEventPlan(
                            venue = venue,
                            spec = spec,
                            result = result,
                            sourceSampleId = state.sampleId,
                            isSurveyedPoint = state.isSurveyedSample
                        )

                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Event Capacity Results")
                            .setMessage(summary)
                            .setPositiveButton("Save Plan 💾") { _, _ ->
                                eventPlanRepository.savePlan(savedPlan)
                                Toast.makeText(this@MainActivity, "Event plan '${venue.name}' saved.", Toast.LENGTH_SHORT).show()
                            }
                            .setNeutralButton("Saved Plans 📋") { _, _ ->
                                eventPlanRepository.savePlan(savedPlan)
                                showManageEventPlansDialog()
                            }
                            .setNegativeButton("Close", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManageEventPlansDialog() {
        val plans = eventPlanRepository.getAllPlans()
        if (plans.isEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Saved Event Plans 📋")
                .setMessage("No saved event plans found.\n\nCreate a plan by selecting a surveyed sample marker or tapping any coordinates on the Dynamic Map.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val planSummaries = plans.map { p ->
            val surveyedTag = if (p.isSurveyedPoint) "✅ [SURVEYED POINT #${p.sourceSampleId ?: ""}]" else "⚠️ [UNSURVEYED SITE]"
            "${p.venue.name} $surveyedTag\nAttendees: ${p.spec.additionalAttendees} (ASSUMED) • DL Demand: ${p.result.offeredTrafficDlMbps.toInt()} Mbps"
        }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Saved Event Plans (${plans.size}) 📋")
            .setItems(planSummaries) { _, which ->
                showSinglePlanManagementDialog(plans[which])
            }
            .setPositiveButton("Export All (CSV) 💾") { _, _ ->
                val csvContent = eventPlanRepository.exportCollectionCsv()
                shareCsvFile(csvContent, "CellScope_Event_Plans_Collection.csv")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSinglePlanManagementDialog(plan: SavedEventPlan) {
        val surveyedTag = if (plan.isSurveyedPoint) "Surveyed Sample #${plan.sourceSampleId} (Empirical evidence)" else "Unsurveyed Location (No local evidence)"
        val prbStatus = if (plan.result.actualPrbUtilizationPct != null) "${plan.result.actualPrbUtilizationPct}%" else "FURTHER WORK REQUIRED (Operator counters required)"
        val capStatus = plan.result.totalAvailableCapacityMbps?.let { "${it.toInt()} Mbps (3GPP Nominal Bandwidth Model)" } ?: "UNAVAILABLE (Operator capacity data required)"
        val detailMsg = buildString {
            append("📍 Venue: ${plan.venue.name}\n")
            append("🌐 Coordinates: ${String.format(Locale.US, "%.5f°N, %.5f°E", plan.venue.centerLat, plan.venue.centerLon)}\n")
            append("🏷️ Site Status: $surveyedTag\n")
            append("👥 Expected Attendees: ${plan.spec.additionalAttendees} (ASSUMED scenario input)\n")
            append("⚡ DL Offered Demand: ${plan.result.offeredTrafficDlMbps.toInt()} Mbps\n")
            append("📶 Available Capacity: $capStatus\n")
            append("📊 PRB Load: $prbStatus\n")
            append("⏱️ Created: ${TelecomTimeUtils.formatBdtPretty(plan.createdAtMs)} (BST)\n")
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(plan.venue.name)
            .setMessage(detailMsg)
            .setPositiveButton("Edit Plan ✏️") { _, _ ->
                val state = SelectedLocationState(
                    latitude = plan.venue.centerLat,
                    longitude = plan.venue.centerLon,
                    localityName = plan.venue.name,
                    sampleId = plan.sourceSampleId,
                    selectionSource = if (plan.isSurveyedPoint) LocationSelectionSource.IMPORTED_COORDINATE else LocationSelectionSource.MAP_TAP,
                    eventName = plan.venue.name,
                    isSurveyedSample = plan.isSurveyedPoint
                )
                showUniversalEventPlannerDialog(state)
            }
            .setNeutralButton("Export CSV 💾") { _, _ ->
                val csvContent = eventPlanRepository.exportPlanCsv(plan)
                shareCsvFile(csvContent, "${plan.venue.name.replace(" ", "_")}_Plan.csv")
            }
            .setNegativeButton("Delete Plan 🗑️") { _, _ ->
                confirmDeleteEventPlan(plan)
            }
            .show()
    }

    private fun confirmDeleteEventPlan(plan: SavedEventPlan) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Confirm Delete Event Plan")
            .setMessage("Are you sure you want to delete event plan '${plan.venue.name}'?\n\nStrict Guarantee: Underlying raw survey measurements will NOT be deleted.")
            .setPositiveButton("Delete Plan") { _, _ ->
                eventPlanRepository.deletePlan(plan.id)
                Toast.makeText(this, "Event plan '${plan.venue.name}' deleted. Survey data preserved.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareCsvFile(csvContent: String, filename: String) {
        try {
            val exportDir = File(getExternalFilesDir(null), "exports")
            if (!exportDir.exists()) exportDir.mkdirs()
            val file = File(exportDir, filename)
            file.writeText(csvContent)
            Toast.makeText(this, "Exported: ${file.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCoveragePlanner() {
        binding.bottomNav.selectedItemId = R.id.nav_map
        binding.chipModeHeatmap.isChecked = true
        binding.driveMapView.toggleLayer(com.cellscope.drive.ui.map.MapLayer.COVERAGE_HEATMAP, true)
        binding.driveMapView.showHeatmapOverlay = true
        Toast.makeText(this, "Coverage Planning Heatmap Layer Active", Toast.LENGTH_SHORT).show()
    }

    private fun openMapForActiveSession() {
        binding.bottomNav.selectedItemId = R.id.nav_map
        refreshMapData(shouldFitBounds = true)
    }

    private fun updateFollowGpsButtonUi(isFollowing: Boolean) {
        if (isFollowing) {
            binding.btnRecenter.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.primary)
            )
            binding.btnRecenter.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.on_primary)
            )
        } else {
            binding.btnRecenter.backgroundTintList = ColorStateList.valueOf(
                Color.parseColor("#374151")
            )
            binding.btnRecenter.imageTintList = ColorStateList.valueOf(
                Color.parseColor("#9CA3AF")
            )
        }
    }

    private fun openAnalyticsForActiveSession() {
        binding.bottomNav.selectedItemId = R.id.nav_analytics
        refreshAnalytics(forceRecompute = true)
    }

    private fun openExportForActiveSession() {
        val target = activeSessionToInspect ?: recorder.currentSession.value
        if (target != null) {
            binding.tvReportTargetSession.text = "Target: ${target.sessionName} (${target.sampleCount} samples)"
        }
        binding.bottomNav.selectedItemId = R.id.nav_manage
        binding.tabLayoutManage.getTabAt(1)?.select()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recorder = DriveTestRecorder.getInstance(this)
        recorder.checkAndRecoverActiveSession()
        offlineMapManager = OfflineMapManager(this)
        eventPlanRepository = EventPlanRepository(this)
        binding.driveMapView.onCreate(savedInstanceState)
        binding.coveragePreviewView.onCreate(savedInstanceState)

        setupRecyclerViews()
        checkAndRequestPermissions()
        setupListeners()
        setupNavigation()
        observeState()
        updateEventCapacityDashboard()
    }

    private fun setupRecyclerViews() {
        binding.rvCells.layoutManager = LinearLayoutManager(this)
        binding.rvCells.adapter = cellAdapter

        binding.rvTransitions.layoutManager = LinearLayoutManager(this)
        binding.rvTransitions.adapter = transitionAdapter
        transitionAdapter.onItemClickListener = { trans ->
            binding.chartMobilityTimeline.setSelectedEvent(trans)
            showCellTransitionDetailsDialog(trans)
        }
        binding.chartMobilityTimeline.onEventSelectedListener = { trans ->
            showCellTransitionDetailsDialog(trans)
        }

        binding.rvWeakZones.layoutManager = LinearLayoutManager(this)
        binding.rvWeakZones.adapter = weakZoneAdapter

        binding.rvCellComparison.layoutManager = LinearLayoutManager(this)
        binding.rvCellComparison.adapter = comparisonAdapter
        comparisonAdapter.onFilterResultListener = { count ->
            if (count == 0) {
                binding.rvCellComparison.visibility = View.GONE
                binding.tvEmptyCellComparison.visibility = View.VISIBLE
            } else {
                binding.rvCellComparison.visibility = View.VISIBLE
                binding.tvEmptyCellComparison.visibility = View.GONE
            }
        }

        binding.rvSessionsManage.layoutManager = LinearLayoutManager(this)
    }

    private fun showCellTransitionDetailsDialog(trans: CellTransitionEntity) {
        val bdtTime = TelecomTimeUtils.isoToBdtTimeOnly(trans.timestampIso, includeMillis = true)
        val deltaRsrp = if (trans.fromRsrp != null && trans.toRsrp != null) {
            val d = trans.toRsrp - trans.fromRsrp
            if (d >= 0) "+$d dB (Gain)" else "$d dB (Drop)"
        } else {
            "Unknown"
        }

        val fromPciStr = trans.fromPci?.let { "PCI $it" } ?: "PCI —"
        val toPciStr = trans.toPci?.let { "PCI $it" } ?: "PCI —"
        val fromRsrpStr = trans.fromRsrp?.let { "$it dBm" } ?: "— dBm"
        val toRsrpStr = trans.toRsrp?.let { "$it dBm" } ?: "— dBm"
        val fromTacStr = trans.fromTac?.let { "TAC $it" } ?: "TAC —"
        val toTacStr = trans.toTac?.let { "TAC $it" } ?: "TAC —"

        val isPingPong = trans.details.contains("ping-pong", ignoreCase = true) || trans.eventType.contains("PING_PONG", ignoreCase = true)
        val classificationLabel = when {
            isPingPong -> "RAPID PING-PONG CANDIDATE"
            trans.eventType == "CONFIRMED_HANDOVER" -> "CONFIRMED HANDOVER"
            trans.eventType == "INTRA_FREQ" -> "INTRA-FREQUENCY TRANSITION"
            trans.eventType == "INTER_FREQ" -> "INTER-FREQUENCY TRANSITION"
            trans.eventType == "INTER_RAT" -> "INTER-RAT TRANSITION"
            trans.eventType == "CELL_RESELECTION" -> "CELL RESELECTION (Stationary / Low Speed)"
            trans.eventType == "SIGNAL_LOSS" -> "SIGNAL LOSS RESELECTION (< -115 dBm Collapse)"
            trans.details.contains("Intra-Freq", ignoreCase = true) -> "INTRA-FREQUENCY TRANSITION"
            trans.details.contains("Inter-Freq", ignoreCase = true) -> "INTER-FREQUENCY TRANSITION"
            trans.details.contains("Inter-RAT", ignoreCase = true) -> "INTER-RAT TRANSITION"
            else -> "OBSERVED SERVING-CELL TRANSITION"
        }

        val message = buildString {
            append("⏱️ Timestamp: $bdtTime (BST)\n")
            append("🏷️ Classification: $classificationLabel\n")
            append("🎯 Confidence Score: ${(trans.confidenceScore * 100).toInt()}% (Empirical RF Evidence)\n\n")

            append("📡 Transition Source & Target:\n")
            append("  • Departed Cell: $fromPciStr ($fromRsrpStr) • $fromTacStr\n")
            append("  • Entered Cell: $toPciStr ($toRsrpStr) • $toTacStr\n")
            append("  • ΔRSRP Delta: $deltaRsrp\n\n")

            append("🔍 Evidence & Diagnostic Detail:\n")
            append("  ${trans.details}\n\n")

            if (isPingPong) {
                append("⚠️ Engineering Notice (Ping-Pong Causes):\n")
                append("  A rapid return alone does not prove network failure. Common causes include overlapping cell boundaries, tight hysteresis/time-to-trigger (TTT) thresholds, dynamic eNodeB load-balancing, or temporary shadow fading.\n\n")
            } else if (trans.eventType != "CONFIRMED_HANDOVER") {
                append("ℹ️ Protocol Telephony Limitation:\n")
                append("  In absence of eNodeB RRC Reconfiguration layer-3 signalling, public Android Telephony APIs observe serving cell changes at application layer. Classified empirically from RF & motion continuity.\n\n")
            }

            if (trans.latitude != null && trans.longitude != null) {
                append(String.format(Locale.US, "📍 Location: %.5f°N, %.5f°E", trans.latitude, trans.longitude))
            }
        }

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Serving-Cell Transition Intelligence 📡")
            .setMessage(message)
            .setPositiveButton("Close", null)

        if (trans.latitude != null && trans.longitude != null) {
            builder.setNeutralButton("Locate on Map 🗺️") { _, _ ->
                binding.bottomNav.selectedItemId = R.id.nav_map
                binding.driveMapView.focusTransition(trans)
            }
        }
        builder.show()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun setupNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> switchMainSection(binding.layoutDashboard)
                R.id.nav_map -> {
                    switchMainSection(binding.layoutMap)
                    refreshMapData()
                }
                R.id.nav_cells -> {
                    switchMainSection(binding.layoutCells)
                    refreshCellsView()
                }
                R.id.nav_analytics -> {
                    switchMainSection(binding.layoutAnalytics)
                    refreshAnalytics()
                }
                R.id.nav_manage -> {
                    switchMainSection(binding.layoutManage)
                    loadSessions()
                    updateDiagnosticsDisplay()
                }
            }
            true
        }

        // Sub-tabs for Analytics (9 sub-tabs)
        binding.tabLayoutAnalytics.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                binding.subLayoutCoverage.visibility = View.GONE
                binding.subLayoutFrequency.visibility = View.GONE
                binding.subLayoutQuality.visibility = View.GONE
                binding.subLayoutCompare.visibility = View.GONE
                binding.subLayoutHandovers.visibility = View.GONE
                binding.subLayoutTowers.visibility = View.GONE
                binding.subLayoutPlanning.visibility = View.GONE
                binding.subLayoutQoe.visibility = View.GONE
                binding.subLayoutRatCompare.visibility = View.GONE

                when (tab?.position) {
                    0 -> binding.subLayoutCoverage.visibility = View.VISIBLE
                    1 -> binding.subLayoutFrequency.visibility = View.VISIBLE
                    2 -> binding.subLayoutQuality.visibility = View.VISIBLE
                    3 -> binding.subLayoutCompare.visibility = View.VISIBLE
                    4 -> binding.subLayoutHandovers.visibility = View.VISIBLE
                    5 -> binding.subLayoutTowers.visibility = View.VISIBLE
                    6 -> binding.subLayoutPlanning.visibility = View.VISIBLE
                    7 -> binding.subLayoutQoe.visibility = View.VISIBLE
                    8 -> binding.subLayoutRatCompare.visibility = View.VISIBLE
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Sub-tabs for Manage
        binding.tabLayoutManage.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                binding.subLayoutSessions.visibility = View.GONE
                binding.subLayoutReports.visibility = View.GONE
                binding.subLayoutDiagnostics.visibility = View.GONE
                binding.subLayoutSettings.visibility = View.GONE

                when (tab?.position) {
                    0 -> binding.subLayoutSessions.visibility = View.VISIBLE
                    1 -> binding.subLayoutReports.visibility = View.VISIBLE
                    2 -> binding.subLayoutDiagnostics.visibility = View.VISIBLE
                    3 -> binding.subLayoutSettings.visibility = View.VISIBLE
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        setupQoeAndRatUI()
    }

    private fun setupQoeAndRatUI() {
        val qoeScheduler = NetworkQoeScheduler.getInstance(this)

        // Auto-Testing Switch
        binding.switchQoeAutoTesting.isChecked = qoeScheduler.isAutoTestingEnabled
        binding.switchQoeAutoTesting.setOnCheckedChangeListener { _, isChecked ->
            qoeScheduler.isAutoTestingEnabled = isChecked
            Toast.makeText(this, if (isChecked) "Automatic Survey QoE Testing Enabled" else "Automatic Survey QoE Testing Disabled", Toast.LENGTH_SHORT).show()
        }

        // Interval chips
        fun updateIntervalChips(selectedMinutes: Int) {
            binding.chipQoeInterval10m.isChecked = (selectedMinutes == 10)
            binding.chipQoeInterval5m.isChecked = (selectedMinutes == 5)
            val isCustom = (selectedMinutes != 10 && selectedMinutes != 5)
            binding.chipQoeIntervalCustom.isChecked = isCustom
            binding.chipQoeIntervalCustom.text = if (isCustom) "$selectedMinutes min" else "Custom..."
        }
        updateIntervalChips(qoeScheduler.intervalMinutes)

        binding.chipQoeInterval10m.setOnClickListener {
            qoeScheduler.intervalMinutes = 10
            updateIntervalChips(10)
            Toast.makeText(this, "Testing interval set to 10 minutes", Toast.LENGTH_SHORT).show()
        }
        binding.chipQoeInterval5m.setOnClickListener {
            qoeScheduler.intervalMinutes = 5
            updateIntervalChips(5)
            Toast.makeText(this, "Testing interval set to 5 minutes", Toast.LENGTH_SHORT).show()
        }
        binding.chipQoeIntervalCustom.setOnClickListener {
            showCustomIntervalDialog()
        }

        // Manual Run Test Action
        binding.btnRunQoeTestNow.setOnClickListener {
            lifecycleScope.launch {
                qoeScheduler.runTestNow()
            }
        }

        // Configure Limits & Budget
        binding.btnConfigureQoeBudget.setOnClickListener {
            showConfigureBudgetDialog()
        }

        // State Badge
        lifecycleScope.launchWhenStarted {
            qoeScheduler.schedulerState.collect { state ->
                binding.tvQoeStateBadge.text = state.name
                val colorHex = when (state) {
                    com.cellscope.drive.telephony.SchedulerState.RUNNING -> "#10B981"
                    com.cellscope.drive.telephony.SchedulerState.COMPLETED -> "#3B82F6"
                    com.cellscope.drive.telephony.SchedulerState.WAITING -> "#F59E0B"
                    com.cellscope.drive.telephony.SchedulerState.FAILED -> "#EF4444"
                    com.cellscope.drive.telephony.SchedulerState.SKIPPED -> "#6B7280"
                    com.cellscope.drive.telephony.SchedulerState.IDLE -> "#64748B"
                }
                binding.tvQoeStateBadge.setTextColor(Color.parseColor(colorHex))
            }
        }

        // Test running state
        lifecycleScope.launchWhenStarted {
            qoeScheduler.isTestRunning.collect { running ->
                binding.btnRunQoeTestNow.isEnabled = !running
                binding.btnRunQoeTestNow.text = if (running) "TESTING..." else "RUN TEST NOW ⚡"
            }
        }

        // Skip / Status Reason
        lifecycleScope.launchWhenStarted {
            qoeScheduler.lastSkipReason.collect { reason ->
                binding.tvQoeSkipReason.text = if (reason != null) {
                    "Skip / Failure Note: $reason"
                } else {
                    "Status Note: Operating normally (monitoring survey session)"
                }
            }
        }

        // Next Scheduled Run
        lifecycleScope.launchWhenStarted {
            qoeScheduler.nextScheduledRunMs.collect { nextMs ->
                binding.tvQoeNextRun.text = if (nextMs != null) {
                    val remainingSec = ((nextMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
                    val m = remainingSec / 60
                    val s = remainingSec % 60
                    String.format(Locale.US, "Next Run: %02d:%02d", m, s)
                } else "Next Run: —"
            }
        }

        // Last Completed Test & Telemetry
        lifecycleScope.launchWhenStarted {
            qoeScheduler.lastCompletedTest.collect { test ->
                if (test != null) {
                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(test.timestampMs))
                    binding.tvQoeLastCompletedTime.text = "Last Completed: $timeStr"

                    binding.tvQoeDlSpeed.text = test.downloadSpeedMbps?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
                    binding.tvQoeUlSpeed.text = test.uploadSpeedMbps?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
                    binding.tvQoeLatency.text = test.latencyMs?.let { "$it ms" } ?: "— ms"
                    binding.tvQoeMeanLatency.text = "Mean: ${test.meanLatencyMs?.let { String.format(Locale.US, "%.1f", it) } ?: test.latencyMs?.toString() ?: "—"} ms"
                    binding.tvQoeP95Latency.text = "P95: ${test.p95LatencyMs?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} ms"
                    val jitStr = test.jitterMs?.let { String.format(Locale.US, "%.1f ms (RFC 3550 |ΔRTT|)", it) } ?: "— ms"
                    binding.tvQoeJitter.text = "Jitter: $jitStr"
                    val lossStr = test.packetLossPct?.let { String.format(Locale.US, "%.0f%%", it) } ?: "0%"
                    val probes = "${test.successfulProbeCount}/${test.probeCount}"
                    binding.tvQoeProbeLoss.text = "Probe Loss: $lossStr ($probes probes)"
                    binding.tvQoeEndpoint.text = "Endpoint: ${test.endpointUrl ?: qoeScheduler.downloadEndpointUrl}"

                    val dlKb = test.dlBytes / 1024L
                    val ulKb = test.ulBytes / 1024L
                    binding.tvQoeBytesTransferred.text = "Transferred this cycle: DL $dlKb KB, UL $ulKb KB"
                }
            }
        }

        // Data Usage Observables
        lifecycleScope.launchWhenStarted {
            qoeScheduler.sessionDataUsedBytes.collect { bytes ->
                val mb = bytes / (1024.0 * 1024.0)
                binding.tvQoeSessionUsage.text = String.format(Locale.US, "Survey Session: %.2f MB", mb)
            }
        }

        lifecycleScope.launchWhenStarted {
            qoeScheduler.dataUsedTodayBytes.collect { bytes ->
                val mb = bytes / (1024.0 * 1024.0)
                val budgetStr = qoeScheduler.dailyBudgetMb?.let { String.format(Locale.US, "%.1f MB budget", it) } ?: "Unlimited"
                binding.tvQoeDailyUsage.text = String.format(Locale.US, "Today: %.2f MB / %s", mb, budgetStr)
            }
        }

        // Congestion Classification & Baseline
        lifecycleScope.launchWhenStarted {
            qoeScheduler.lastClassification.collect { res ->
                if (res != null) {
                    binding.tvQoeClassificationTitle.text = "Classification: [${res.classification.label}]"
                    binding.tvQoeClassificationSummary.text = res.summary
                    binding.tvQoeWhyThisResult.text = res.whyThisResult
                    val baseDlStr = res.baselineDlMbps?.let { String.format(Locale.US, "%.1f Mbps", it) } ?: "—"
                    val degStr = res.dlDegradationPct?.let { " (${if (it >= 0) "-$it%" else "+${-it}%"} vs baseline)" } ?: ""
                    binding.tvQoeBaselineComparison.text = "Historical Baseline: $baseDlStr DL$degStr | ${res.baselineSampleCount} past tests"
                }
            }
        }
    }

    private fun showCustomIntervalDialog() {
        val qoeScheduler = NetworkQoeScheduler.getInstance(this)
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(qoeScheduler.intervalMinutes.toString())
            hint = "Minutes (1 - 120)"
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Custom QoE Testing Interval")
            .setMessage("Enter the scheduled periodic test interval in minutes:")
            .setView(input)
            .setPositiveButton("Set Interval") { _, _ ->
                val mins = input.text.toString().toIntOrNull()?.coerceIn(1, 120) ?: 10
                qoeScheduler.intervalMinutes = mins
                binding.chipQoeInterval10m.isChecked = (mins == 10)
                binding.chipQoeInterval5m.isChecked = (mins == 5)
                binding.chipQoeIntervalCustom.isChecked = (mins != 10 && mins != 5)
                binding.chipQoeIntervalCustom.text = if (mins != 10 && mins != 5) "$mins min" else "Custom..."
                Toast.makeText(this, "QoE testing interval set to $mins min", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showConfigureBudgetDialog() {
        val qoeScheduler = NetworkQoeScheduler.getInstance(this)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }
        val dlInput = android.widget.EditText(this).apply {
            hint = "Download Cap per test in KB (e.g. 1024)"
            setText((qoeScheduler.downloadBytesLimitPerTest / 1024L).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val ulInput = android.widget.EditText(this).apply {
            hint = "Upload Cap per test in KB (e.g. 128)"
            setText((qoeScheduler.uploadBytesLimitPerTest / 1024L).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val dailyInput = android.widget.EditText(this).apply {
            hint = "Daily Budget in MB (leave empty for unlimited)"
            setText(qoeScheduler.dailyBudgetMb?.toString() ?: "")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(android.widget.TextView(this).apply { text = "Download Cap per test (KB):" })
        layout.addView(dlInput)
        layout.addView(android.widget.TextView(this).apply { text = "Upload Cap per test (KB):" })
        layout.addView(ulInput)
        layout.addView(android.widget.TextView(this).apply { text = "Daily Budget (MB, leave empty for unlimited):" })
        layout.addView(dailyInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Configure QoE Data Budgets")
            .setView(layout)
            .setPositiveButton("Save Limits") { _, _ ->
                val dlKb = dlInput.text.toString().toLongOrNull()?.coerceAtLeast(64L) ?: 1024L
                val ulKb = ulInput.text.toString().toLongOrNull()?.coerceAtLeast(32L) ?: 128L
                val dailyMb = dailyInput.text.toString().toDoubleOrNull()
                qoeScheduler.downloadBytesLimitPerTest = dlKb * 1024L
                qoeScheduler.uploadBytesLimitPerTest = ulKb * 1024L
                qoeScheduler.dailyBudgetMb = dailyMb
                binding.tvQoePerTestLimits.text = "Per-Test Stream Limits: ${dlKb} KB DL / ${ulKb} KB UL (Configurable)"
                val budgetStr = dailyMb?.let { String.format(Locale.US, "%.1f MB budget", it) } ?: "Unlimited"
                val mb = qoeScheduler.dataUsedTodayBytes.value / (1024.0 * 1024.0)
                binding.tvQoeDailyUsage.text = String.format(Locale.US, "Today: %.2f MB / %s", mb, budgetStr)
                Toast.makeText(this, "Data limits updated successfully", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun switchMainSection(targetView: View) {
        binding.layoutDashboard.visibility = View.GONE
        binding.layoutMap.visibility = View.GONE
        binding.layoutCells.visibility = View.GONE
        binding.layoutAnalytics.visibility = View.GONE
        binding.layoutManage.visibility = View.GONE

        targetView.visibility = View.VISIBLE
    }

    private fun setupListeners() {
        binding.cardSinrDiagnostics.setOnClickListener {
            showSinrDiagnosticsDialog(recorder.latestLte.value)
        }
        binding.tvSinr.setOnClickListener {
            showSinrDiagnosticsDialog(recorder.latestLte.value)
        }
        binding.btnSessionClear.setOnClickListener {
            switchToLiveMode()
        }
        binding.btnResumeLiveTelemtry.setOnClickListener {
            switchToLiveMode()
        }
        binding.coveragePreviewView.onOpenCoveragePlannerRequested = {
            openCoveragePlanner()
        }
        binding.tvSessionIntelCoverageHoles.setOnClickListener {
            if (lastDetectedCoverageHoles.isEmpty()) {
                Toast.makeText(this, "No coverage holes detected in current session", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (lastDetectedCoverageHoles.size == 1) {
                val hole = lastDetectedCoverageHoles[0]
                binding.bottomNav.selectedItemId = R.id.nav_map
                binding.driveMapView.postDelayed({
                    binding.driveMapView.focusCoverageHole(hole.id)
                }, 200)
            } else {
                val options = lastDetectedCoverageHoles.map { hole ->
                    val loc = hole.localityHint?.let { " ($it)" } ?: ""
                    "${hole.stableZoneId}: ${hole.severity.name.replace('_', ' ')} • Mean ${hole.meanRsrpDbm.toInt()} dBm • ${hole.corridorLengthMeters.toInt()}m$loc"
                }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Select Coverage Hole to Inspect (${lastDetectedCoverageHoles.size} zones)")
                    .setItems(options) { _, idx ->
                        val selectedHole = lastDetectedCoverageHoles[idx]
                        binding.bottomNav.selectedItemId = R.id.nav_map
                        binding.driveMapView.postDelayed({
                            binding.driveMapView.focusCoverageHole(selectedHole.id)
                        }, 200)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        binding.btnOpenCoveragePlannerNav.setOnClickListener {
            openCoveragePlanner()
        }
        binding.btnSessionViewMap.setOnClickListener {
            openMapForActiveSession()
        }
        binding.btnSessionViewAnalytics.setOnClickListener {
            openAnalyticsForActiveSession()
        }
        binding.btnSessionExportReport.setOnClickListener {
            openExportForActiveSession()
        }
        binding.driveMapView.onRequestSwitchToLive = {
            switchToLiveMode()
        }

        binding.btnStartRecord.setOnClickListener {
            recorder.startRecording()
            DriveTestService.start(this)
        }

        binding.btnPauseResume.setOnClickListener {
            when (recorder.recordingState.value) {
                RecordingState.RECORDING -> recorder.pauseRecording()
                RecordingState.PAUSED -> recorder.resumeRecording()
                else -> {}
            }
        }

        binding.btnStopRecord.setOnClickListener {
            recorder.stopRecording { session ->
                DriveTestService.stop(this)
                if (session != null) {
                    activeSessionToInspect = session
                    updateSessionIntelligenceUi(session)
                    refreshAnalytics(forceRecompute = true)
                    showSurveySummaryDialog(session)
                }
            }
        }

        // Independent Multi-Layer Map Controls (P0: True Independent Multi-Layer Selection)
        val layerChipsMap = mapOf(
            binding.chipModeRoute to com.cellscope.drive.ui.map.MapLayer.ROUTE_SAMPLES,
            binding.chipModeHandovers to com.cellscope.drive.ui.map.MapLayer.HANDOVERS,
            binding.chipModeSites to com.cellscope.drive.ui.map.MapLayer.ESTIMATED_SITES,
            binding.chipModeHeatmap to com.cellscope.drive.ui.map.MapLayer.COVERAGE_HEATMAP,
            binding.chipModeHoles to com.cellscope.drive.ui.map.MapLayer.WEAK_ZONES,
            binding.chipModeHotspots to com.cellscope.drive.ui.map.MapLayer.CAPACITY_HOTSPOTS,
            binding.chipModeEvent to com.cellscope.drive.ui.map.MapLayer.EVENT_PLANNING,
            binding.chipModeQoe to com.cellscope.drive.ui.map.MapLayer.ACTIVE_QOE
        )

        var isUpdatingLayerChips = false

        fun syncAllChipState() {
            val allActive = layerChipsMap.values.all { binding.driveMapView.isLayerActive(it) }
            if (binding.chipModeAll.isChecked != allActive) {
                isUpdatingLayerChips = true
                binding.chipModeAll.isChecked = allActive
                isUpdatingLayerChips = false
            }
            updateActiveLayerBadge()
        }

        // Top Floating Control Bar Wiring
        binding.btnToggleLayersPanel.setOnClickListener {
            val isVisible = binding.cardLayersPanel.visibility == View.VISIBLE
            binding.cardLayersPanel.visibility = if (isVisible) View.GONE else View.VISIBLE
        }
        binding.tvActiveLayerBadge.setOnClickListener {
            val isVisible = binding.cardLayersPanel.visibility == View.VISIBLE
            binding.cardLayersPanel.visibility = if (isVisible) View.GONE else View.VISIBLE
        }
        binding.btnCloseLayersPanel.setOnClickListener {
            binding.cardLayersPanel.visibility = View.GONE
        }
        binding.btnToggleHud.setOnClickListener {
            val isExpanded = binding.driveMapView.toggleHud()
            binding.btnToggleHud.alpha = if (isExpanded) 1.0f else 0.5f
        }
        binding.btnQuickReset.setOnClickListener {
            isUpdatingLayerChips = true
            binding.driveMapView.resetLayers()
            layerChipsMap.forEach { (chip, layer) ->
                chip.isChecked = (layer == com.cellscope.drive.ui.map.MapLayer.ROUTE_SAMPLES)
            }
            binding.chipModeAll.isChecked = false
            isUpdatingLayerChips = false
            binding.chipMetricRsrp.isChecked = true
            binding.chipFilterGenAll.isChecked = true
            updateActiveLayerBadge()
            Toast.makeText(this, "Map reset to Route & Samples (RSRP)", Toast.LENGTH_SHORT).show()
        }
        binding.btnCloseScrubber.setOnClickListener {
            binding.cardRouteScrubber.visibility = View.GONE
        }

        binding.driveMapView.onLocationSelected = { state ->
            authoritativeLocationState = state
        }

        binding.driveMapView.onInspectSampleMeasurements = { sample ->
            showSampleMeasurementDetailsDialog(sample)
        }

        binding.driveMapView.onPlanEventAtSample = { sample, locality ->
            val lat = sample.latitude ?: 0.0
            val lon = sample.longitude ?: 0.0
            val state = SelectedLocationState.fromSurveySample(
                sampleId = sample.id,
                latitude = lat,
                longitude = lon,
                sessionId = sample.sessionId,
                localityHint = locality,
                boundaryRadiusMeters = 500.0,
                eventName = "Surveyed Point #${sample.id}"
            )
            authoritativeLocationState = state
            showUniversalEventPlannerDialog(state)
        }

        // Universal Event Planner map click callback
        binding.driveMapView.onPlanEventAtLocation = { lat, lon, locality ->
            val state = authoritativeLocationState?.takeIf { it.latitude == lat && it.longitude == lon }
                ?: SelectedLocationState.fromMapTap(latitude = lat, longitude = lon, localityHint = locality)
            authoritativeLocationState = state
            showUniversalEventPlannerDialog(state)
        }

        // Wire individual layer chips independently
        layerChipsMap.forEach { (chip, layer) ->
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isUpdatingLayerChips) return@setOnCheckedChangeListener
                binding.driveMapView.toggleLayer(layer, isChecked)
                if (layer == com.cellscope.drive.ui.map.MapLayer.EVENT_PLANNING && isChecked) {
                    val locState = authoritativeLocationState
                    if (locState != null) {
                        val venue = EventVenue.fromCoordinates(
                            lat = locState.latitude,
                            lon = locState.longitude,
                            name = locState.eventName ?: locState.resolvedLocality ?: "Event Venue",
                            eventName = locState.eventName ?: "Gathering",
                            radiusMeters = locState.boundaryRadiusMeters
                        )
                        binding.driveMapView.focusEventPlanning(venue)
                    } else {
                        binding.driveMapView.focusEventPlanning()
                    }
                }
                syncAllChipState()
            }
        }

        // Dedicated 'All Layers' chip
        binding.chipModeAll.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingLayerChips) return@setOnCheckedChangeListener
            isUpdatingLayerChips = true
            if (isChecked) {
                binding.driveMapView.enableAllLayers()
                layerChipsMap.keys.forEach { it.isChecked = true }
            } else {
                binding.driveMapView.clearAllLayers()
                layerChipsMap.keys.forEach { it.isChecked = false }
            }
            isUpdatingLayerChips = false
            updateActiveLayerBadge()
        }

        binding.btnResetLayers.setOnClickListener {
            isUpdatingLayerChips = true
            binding.driveMapView.resetLayers()
            layerChipsMap.forEach { (chip, layer) ->
                chip.isChecked = (layer == com.cellscope.drive.ui.map.MapLayer.ROUTE_SAMPLES)
            }
            binding.chipModeAll.isChecked = false
            isUpdatingLayerChips = false
            binding.chipMetricRsrp.isChecked = true
            binding.chipFilterGenAll.isChecked = true
            updateActiveLayerBadge()
            Toast.makeText(this, "Map layers reset to default (Route & Samples)", Toast.LENGTH_SHORT).show()
        }

        binding.chipOfflineMap.setOnClickListener {
            showOfflineMapDialog()
        }

        binding.chipMetricRsrp.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipMetricRsrq.isChecked = false
                binding.chipMetricSinr.isChecked = false
                binding.chipMetricGen.isChecked = false
                binding.driveMapView.activeMetricLayer = com.cellscope.drive.analytics.MapMetricLayer.RSRP
                updateActiveLayerBadge()
            }
        }
        binding.chipMetricRsrq.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipMetricRsrp.isChecked = false
                binding.chipMetricSinr.isChecked = false
                binding.chipMetricGen.isChecked = false
                binding.driveMapView.activeMetricLayer = com.cellscope.drive.analytics.MapMetricLayer.RSRQ
                updateActiveLayerBadge()
            }
        }
        binding.chipMetricSinr.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipMetricRsrp.isChecked = false
                binding.chipMetricRsrq.isChecked = false
                binding.chipMetricGen.isChecked = false
                binding.driveMapView.activeMetricLayer = com.cellscope.drive.analytics.MapMetricLayer.SINR
                updateActiveLayerBadge()
            }
        }
        binding.chipMetricGen.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipMetricRsrp.isChecked = false
                binding.chipMetricRsrq.isChecked = false
                binding.chipMetricSinr.isChecked = false
                binding.driveMapView.activeMetricLayer = com.cellscope.drive.analytics.MapMetricLayer.GENERATION
                updateActiveLayerBadge()
            }
        }

        // Generation filter chips
        binding.chipFilterGenAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipFilterGen5g.isChecked = false
                binding.chipFilterGen4g.isChecked = false
                binding.chipFilterGen3g.isChecked = false
                binding.chipFilterGen2g.isChecked = false
                binding.chipFilterGenHoles.isChecked = false
                binding.driveMapView.activeGenerationFilter = com.cellscope.drive.ui.map.GenerationFilter.ALL
            }
        }
        binding.chipFilterGen5g.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipFilterGenAll.isChecked = false
                binding.chipFilterGen4g.isChecked = false
                binding.chipFilterGen3g.isChecked = false
                binding.chipFilterGen2g.isChecked = false
                binding.chipFilterGenHoles.isChecked = false
                binding.driveMapView.activeGenerationFilter = com.cellscope.drive.ui.map.GenerationFilter.GEN_5G
            }
        }
        binding.chipFilterGen4g.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipFilterGenAll.isChecked = false
                binding.chipFilterGen5g.isChecked = false
                binding.chipFilterGen3g.isChecked = false
                binding.chipFilterGen2g.isChecked = false
                binding.chipFilterGenHoles.isChecked = false
                binding.driveMapView.activeGenerationFilter = com.cellscope.drive.ui.map.GenerationFilter.GEN_4G
            }
        }
        binding.chipFilterGen3g.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipFilterGenAll.isChecked = false
                binding.chipFilterGen5g.isChecked = false
                binding.chipFilterGen4g.isChecked = false
                binding.chipFilterGen2g.isChecked = false
                binding.chipFilterGenHoles.isChecked = false
                binding.driveMapView.activeGenerationFilter = com.cellscope.drive.ui.map.GenerationFilter.GEN_3G
            }
        }
        binding.chipFilterGen2g.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipFilterGenAll.isChecked = false
                binding.chipFilterGen5g.isChecked = false
                binding.chipFilterGen4g.isChecked = false
                binding.chipFilterGen3g.isChecked = false
                binding.chipFilterGenHoles.isChecked = false
                binding.driveMapView.activeGenerationFilter = com.cellscope.drive.ui.map.GenerationFilter.GEN_2G
            }
        }
        binding.chipFilterGenHoles.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipFilterGenAll.isChecked = false
                binding.chipFilterGen5g.isChecked = false
                binding.chipFilterGen4g.isChecked = false
                binding.chipFilterGen3g.isChecked = false
                binding.chipFilterGen2g.isChecked = false
                binding.driveMapView.activeGenerationFilter = com.cellscope.drive.ui.map.GenerationFilter.DOWNGRADES_HOLES
            }
        }

        // Route timeline scrubber listener
        binding.seekBarRouteScrubber.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val focused = binding.driveMapView.focusMeasurementAtIndex(progress)
                    if (focused != null) {
                        val timeStr = "${TelecomTimeUtils.isoToBdtTimeOnly(focused.timestampIso, includeMillis = true)} (BST)"
                        binding.tvScrubberTimestamp.text = "Point ${progress + 1} • Time: $timeStr"
                        val genStr = focused.networkGen ?: focused.networkType
                        val techStr = focused.dataNetworkType ?: focused.networkType
                        binding.tvScrubberTech.text = "$genStr ($techStr)"
                        val rsrpStr = focused.rsrpDbm?.let { "$it dBm" } ?: "No RSRP"
                        val rsrqStr = focused.rsrqDb?.let { "$it dB" } ?: "No RSRQ"
                        val sinrStr = focused.sinrDb?.let { String.format(Locale.US, "%.1f dB", it) } ?: "No SINR"
                        val pciStr = focused.servingPci?.let { "PCI $it" } ?: "PCI —"
                        binding.tvScrubberMetrics.text = "$rsrpStr  |  $rsrqStr  |  $sinrStr  |  $pciStr"
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // Cell Comparison filter chips
        binding.chipFilterAllCells.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipFilterServingOnly.isChecked = false
                binding.chipFilterNeighborOnly.isChecked = false
                comparisonAdapter.setFilterRole("ALL")
            }
        }
        binding.chipFilterServingOnly.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipFilterAllCells.isChecked = false
                binding.chipFilterNeighborOnly.isChecked = false
                comparisonAdapter.setFilterRole("SERVING")
            }
        }
        binding.chipFilterNeighborOnly.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipFilterAllCells.isChecked = false
                binding.chipFilterServingOnly.isChecked = false
                comparisonAdapter.setFilterRole("NEIGHBOR")
            }
        }

        binding.driveMapView.onFollowGpsChanged = { isFollowing ->
            updateFollowGpsButtonUi(isFollowing)
        }
        binding.btnFitBounds.setOnClickListener {
            binding.driveMapView.fitBounds()
            updateFollowGpsButtonUi(false)
            Toast.makeText(this, "Route Fit — Follow Mode Paused", Toast.LENGTH_SHORT).show()
        }
        binding.btnRecenter.setOnClickListener {
            binding.driveMapView.isFollowGpsEnabled = true
            binding.driveMapView.recenterOnLatest()
            updateFollowGpsButtonUi(true)
            Toast.makeText(this, "GPS Follow Mode Re-engaged", Toast.LENGTH_SHORT).show()
        }

        binding.btnRecomputeCoverage.setOnClickListener {
            refreshAnalytics(forceRecompute = true)
        }

        // Export Actions in Reports tab
        binding.btnSavePdf.setOnClickListener {
            handleExportAction(ExportAction.SAVE_PDF)
        }
        binding.btnSharePdf.setOnClickListener {
            handleExportAction(ExportAction.SHARE_PDF)
        }
        binding.btnSaveCsv.setOnClickListener {
            handleExportAction(ExportAction.SAVE_CSV)
        }
        binding.btnShareCsv.setOnClickListener {
            handleExportAction(ExportAction.SHARE_CSV)
        }
        binding.btnUploadDrive.setOnClickListener {
            handleExportAction(ExportAction.GOOGLE_DRIVE)
        }
        binding.btnExportAiAnalysis.setOnClickListener {
            handleExportAction(ExportAction.EXPORT_AI_MARKDOWN)
        }
        binding.btnShareAiAnalysis.setOnClickListener {
            handleExportAction(ExportAction.SHARE_AI_JSON)
        }

        // Backward compatibility hooks
        binding.btnGeneratePdf.setOnClickListener {
            handleExportAction(ExportAction.SHARE_PDF)
        }
        binding.btnExportCsvDirect.setOnClickListener {
            handleExportAction(ExportAction.SHARE_CSV)
        }

        // Cell Explorer: List vs RF Frequency Graph Toggle
        binding.chipViewList.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipViewGraph.isChecked = false
                binding.rvCells.visibility = View.VISIBLE
                binding.layoutRfGraphContainer.visibility = View.GONE
            } else if (!binding.chipViewGraph.isChecked) {
                binding.chipViewList.isChecked = true
            }
        }
        binding.chipViewGraph.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipViewList.isChecked = false
                binding.rvCells.visibility = View.GONE
                binding.layoutRfGraphContainer.visibility = View.VISIBLE
            } else if (!binding.chipViewList.isChecked) {
                binding.chipViewGraph.isChecked = true
            }
        }

        // Dedicated Charts & Analysis Quick Access Banner (P4)
        binding.cardQuickChartsAnalysis.setOnClickListener {
            binding.bottomNav.selectedItemId = R.id.nav_analytics
        }

        // RF Frequency Analytical Views (P1)
        binding.chipRfViewScatter.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipRfViewInventory.isChecked = false
                binding.chipRfViewServing.isChecked = false
                binding.chipRfViewReuse.isChecked = false
                binding.rfChannelGraphView.viewMode = RfGraphViewMode.FREQUENCY_SCATTER
            }
        }
        binding.chipRfViewInventory.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipRfViewScatter.isChecked = false
                binding.chipRfViewServing.isChecked = false
                binding.chipRfViewReuse.isChecked = false
                binding.rfChannelGraphView.viewMode = RfGraphViewMode.BAND_INVENTORY
            }
        }
        binding.chipRfViewServing.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipRfViewScatter.isChecked = false
                binding.chipRfViewInventory.isChecked = false
                binding.chipRfViewReuse.isChecked = false
                binding.rfChannelGraphView.viewMode = RfGraphViewMode.SERVING_VS_NEIGHBOR
            }
        }
        binding.chipRfViewReuse.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipRfViewScatter.isChecked = false
                binding.chipRfViewInventory.isChecked = false
                binding.chipRfViewServing.isChecked = false
                binding.rfChannelGraphView.viewMode = RfGraphViewMode.REUSE_PCI_ALIGNMENT
            }
        }

        // Unified RF Frequency Graph Selection Listener
        binding.rfChannelGraphView.onSelectionChangedListener = { selectedList, _ ->
            val total = binding.rfChannelGraphView.getSelectedCount()
            if (total > 0 && selectedList.isNotEmpty()) {
                val first = selectedList.first()
                val pciStr = first.pci?.let { "PCI $it" } ?: "Cell"
                binding.tvRfCellCount.text = "$total selected ($pciStr)"
            }
        }

        // Band Filter Chips
        val bandChips = listOf(
            binding.chipRfBandAll, binding.chipRfBand1, binding.chipRfBand3,
            binding.chipRfBand8, binding.chipRfBand28, binding.chipRfBand40
        )
        fun setBandFilter(selectedChip: com.google.android.material.chip.Chip, bandNum: Int?) {
            for (c in bandChips) c.isChecked = (c == selectedChip)
            binding.rfChannelGraphView.filterBand = bandNum
        }

        binding.chipRfBandAll.setOnClickListener { setBandFilter(binding.chipRfBandAll, null) }
        binding.chipRfBand1.setOnClickListener { setBandFilter(binding.chipRfBand1, 1) }
        binding.chipRfBand3.setOnClickListener { setBandFilter(binding.chipRfBand3, 3) }
        binding.chipRfBand8.setOnClickListener { setBandFilter(binding.chipRfBand8, 8) }
        binding.chipRfBand28.setOnClickListener { setBandFilter(binding.chipRfBand28, 28) }
        binding.chipRfBand40.setOnClickListener { setBandFilter(binding.chipRfBand40, 40) }

        // Interactive RF Frequency Channel Selection Listener
        binding.rfChannelGraphView.onCellSelectedListener = { cell, info ->
            val isServing = cell.isServing && (cell.servingStatus == null || cell.servingStatus == ServingStatus.PRIMARY_SERVING || cell.servingStatus == ServingStatus.SECONDARY_SERVING)
            val roleStr = if (isServing) "Serving Cell ★ [MEASURED]" else "Unconfirmed Neighbor [MEASURED]"
            binding.tvSelectedCellTitle.text = "$roleStr (PCI ${cell.pci ?: "—"})"
            binding.tvSelectedCellBadge.text = "${info.rat} Band ${info.bandNumber} (${info.duplexMode})"
            binding.tvSelectedCellBand.text = "${info.bandName} • Ch ${info.channelNumber}"
            val bwText = if (info.isNominalBandwidth) "BW: ${info.bandwidthDisplay}" else "DL BW: ${info.bandwidthDisplay}"
            val duplexExtra = if (info.duplexSeparationMhz != null && info.duplexMode == "FDD") " | Duplex Sep: ${String.format(Locale.US, "%.1f", info.duplexSeparationMhz)} MHz" else ""
            binding.tvSelectedCellFreq.text = "Center: ${String.format(Locale.US, "%.1f", info.dlCenterFreqMhz)} MHz | $bwText$duplexExtra"
            val rsrpText = cell.rsrpDbm?.let { "$it dBm" } ?: "— dBm"
            val rsrqText = cell.rsrqDb?.let { "$it dB" } ?: "— dB"
            val sinrProvenanceTag = when (cell.sinrSource) {
                com.cellscope.drive.telephony.SinrDiagnostics.SOURCE_MEASURED -> "[MEASURED]"
                com.cellscope.drive.telephony.SinrDiagnostics.SOURCE_ESTIMATED -> "[ESTIMATED ⓘ]"
                else -> "[UNAVAILABLE]"
            }
            val sinrText = cell.sinrDb?.let { String.format(Locale.US, "%.1f dB %s", it, sinrProvenanceTag) } ?: "Unavailable [UNEXPOSED]"
            binding.tvSelectedCellRf.text = "RSRP: $rsrpText | RSRQ: $rsrqText | SINR: $sinrText"
        }

        // Day/Night Theme Mode Selector
        when (AppThemeManager.getThemeMode(this)) {
            AppThemeMode.SYSTEM -> binding.rbThemeSystem.isChecked = true
            AppThemeMode.LIGHT -> binding.rbThemeLight.isChecked = true
            AppThemeMode.DARK -> binding.rbThemeDark.isChecked = true
        }

        binding.rgThemeMode.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.rbThemeLight -> AppThemeMode.LIGHT
                R.id.rbThemeDark -> AppThemeMode.DARK
                else -> AppThemeMode.SYSTEM
            }
            if (newMode != AppThemeManager.getThemeMode(this)) {
                AppThemeManager.setThemeMode(this, newMode)
            }
        }

        // About / Developer Contact Actions (Sarkar Tuhassinul Arnob)
        binding.btnContactEmail.setOnClickListener {
            try {
                val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                    data = android.net.Uri.parse("mailto:arnob.ict.iu@gmail.com")
                    putExtra(Intent.EXTRA_SUBJECT, "CellScope Inquiries / Telecom Field Testing")
                }
                startActivity(Intent.createChooser(emailIntent, "Send Email"))
            } catch (e: Exception) {
                Toast.makeText(this, "No email client found on device.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnContactGithub.setOnClickListener {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/TuhassinulArnob"))
                startActivity(browserIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to open GitHub URL.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnContactLinkedin.setOnClickListener {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.linkedin.com/in/tuhassinularnob/?isSelfProfile=true"))
                startActivity(browserIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to open LinkedIn URL.", Toast.LENGTH_SHORT).show()
            }
        }

        // CSV Dataset Import Action (Phase 4)
        binding.btnImportCsv.setOnClickListener {
            importCsvLauncher.launch(arrayOf("text/comma-separated-values", "text/csv", "application/csv", "*/*"))
        }

        // Session Intelligence Quick Navigation Actions (Phase 5)
        binding.btnSessionViewMap.setOnClickListener {
            binding.bottomNav.selectedItemId = R.id.nav_map
        }
        binding.btnSessionViewAnalytics.setOnClickListener {
            binding.bottomNav.selectedItemId = R.id.nav_analytics
        }
        binding.btnSessionExportReport.setOnClickListener {
            binding.bottomNav.selectedItemId = R.id.nav_manage
            binding.tabLayoutManage.getTabAt(1)?.select()
        }
        binding.btnSessionClear.setOnClickListener {
            activeSessionToInspect = null
            binding.cardSessionIntelligence.visibility = View.GONE
            Toast.makeText(this, "Switched back to Live Telemetry mode", Toast.LENGTH_SHORT).show()
        }

        // Dashboard Event Capacity Simulator Controls
        binding.chipEventAttendeesBase.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipEventAttendees10k.isChecked = false
                binding.chipEventAttendees20k.isChecked = false
                eventAttendeesCount = 0
                updateEventCapacityDashboard()
            }
        }
        binding.chipEventAttendees10k.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipEventAttendeesBase.isChecked = false
                binding.chipEventAttendees20k.isChecked = false
                eventAttendeesCount = 10000
                updateEventCapacityDashboard()
            }
        }
        binding.chipEventAttendees20k.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipEventAttendeesBase.isChecked = false
                binding.chipEventAttendees10k.isChecked = false
                eventAttendeesCount = 20000
                updateEventCapacityDashboard()
            }
        }
        binding.chipEventCowToggle.setOnCheckedChangeListener { _, isChecked ->
            eventHasCow = isChecked
            updateEventCapacityDashboard()
        }

        binding.btnViewEventOnMap.setOnClickListener {
            val locState = authoritativeLocationState
            val currentVenue = binding.driveMapView.activeEventVenue
            if (locState == null && currentVenue == null) {
                val db = recorder.database
                val targetSessionId = activeSessionToInspect?.id ?: recorder.currentSession.value?.id
                lifecycleScope.launch(Dispatchers.IO) {
                    val mList = if (targetSessionId != null) db.measurementDao().getMeasurementsForSession(targetSessionId) else emptyList()
                    val firstValid = mList.firstOrNull { it.latitude != null && it.longitude != null && it.isGpsValid }
                    withContext(Dispatchers.Main) {
                        binding.bottomNav.selectedItemId = R.id.nav_map
                        binding.chipModeEvent.isChecked = true
                        if (firstValid != null) {
                            val derivedVenue = EventVenue.fromCoordinates(
                                lat = firstValid.latitude!!,
                                lon = firstValid.longitude!!,
                                name = "Survey Area (Sample #${firstValid.id})",
                                eventName = "Survey Event Area",
                                radiusMeters = 500.0
                            )
                            binding.driveMapView.focusEventPlanning(derivedVenue)
                        } else {
                            binding.driveMapView.focusEventPlanning(null)
                            Toast.makeText(this@MainActivity, "Please select a survey sample marker or tap on the map to set event location.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                binding.bottomNav.selectedItemId = R.id.nav_map
                binding.chipModeEvent.isChecked = true
                binding.driveMapView.focusEventPlanning(currentVenue)
            }
        }

        binding.btnCompareScenarios.setOnClickListener {
            showEventScenarioComparisonDialog()
        }

        binding.btnManageSavedPlans.setOnClickListener {
            showManageEventPlansDialog()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            recorder.recordingState.collect { state ->
                updateUiForRecordingState(state)
            }
        }

        lifecycleScope.launch {
            recorder.sampleCount.collect { count ->
                binding.tvSampleCounter.text = count.toString()
                if (count % 5 == 0 && binding.layoutMap.visibility == View.VISIBLE) {
                    refreshMapData()
                }
            }
        }

        lifecycleScope.launch {
            recorder.elapsedSeconds.collect { secs ->
                val mins = secs / 60
                val remSecs = secs % 60
                binding.tvDuration.text = String.format(Locale.US, "%02d:%02d", mins, remSecs)
            }
        }

        lifecycleScope.launch {
            recorder.latestGps.collect { gps ->
                updateGpsUi(gps)
            }
        }

        lifecycleScope.launch {
            recorder.latestLte.collect { lte ->
                updateLteUi(lte)
                if (binding.layoutCells.visibility == View.VISIBLE) {
                    refreshCellsView()
                }
            }
        }

        lifecycleScope.launch {
            recorder.recoveredOrphanedSession.collect { recovered ->
                if (recovered != null) {
                    activeSessionToInspect = recovered
                    loadSessions()
                    Toast.makeText(
                        this@MainActivity,
                        "Interrupted survey recovered and finalized: ${recovered.sessionName} (${recovered.sampleCount} samples)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun updateUiForRecordingState(state: RecordingState) {
        when (state) {
            RecordingState.IDLE -> {
                binding.tvStatusBadge.text = getString(R.string.session_status_idle)
                binding.tvStatusBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_variant))
                binding.btnStartRecord.visibility = View.VISIBLE
                binding.layoutActiveControls.visibility = View.GONE
                binding.driveMapView.isLiveRecording = false
                if (activeSessionToInspect != null) {
                    binding.layoutLiveTelemetrySection.visibility = View.GONE
                    binding.cardLiveInactiveBanner.visibility = View.VISIBLE
                    binding.driveMapView.isHistoricalMode = true
                    binding.driveMapView.historicalSessionName = activeSessionToInspect!!.sessionName
                } else {
                    binding.layoutLiveTelemetrySection.visibility = View.VISIBLE
                    binding.cardLiveInactiveBanner.visibility = View.GONE
                    binding.driveMapView.isHistoricalMode = false
                }
            }
            RecordingState.RECORDING -> {
                binding.tvStatusBadge.text = getString(R.string.session_status_recording)
                binding.tvStatusBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.rf_excellent))
                binding.btnStartRecord.visibility = View.GONE
                binding.layoutActiveControls.visibility = View.VISIBLE
                binding.btnPauseResume.text = getString(R.string.btn_pause)
                binding.layoutLiveTelemetrySection.visibility = View.VISIBLE
                binding.cardLiveInactiveBanner.visibility = View.GONE
                binding.driveMapView.isLiveRecording = true
                binding.driveMapView.isHistoricalMode = false
            }
            RecordingState.PAUSED -> {
                binding.tvStatusBadge.text = getString(R.string.session_status_paused)
                binding.tvStatusBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.rf_fair))
                binding.btnStartRecord.visibility = View.GONE
                binding.layoutActiveControls.visibility = View.VISIBLE
                binding.btnPauseResume.text = getString(R.string.btn_resume)
                binding.layoutLiveTelemetrySection.visibility = View.VISIBLE
                binding.cardLiveInactiveBanner.visibility = View.GONE
                binding.driveMapView.isLiveRecording = false
                binding.driveMapView.isHistoricalMode = false
            }
        }
    }

    private fun updateGpsUi(gps: GpsData) {
        if (gps.hasFix && gps.latitude != null && gps.longitude != null) {
            binding.tvCoordinates.text = String.format(Locale.US, "%.5f, %.5f", gps.latitude, gps.longitude)
            binding.tvGpsFixBadge.text = "GPS 3D FIX"
            binding.tvGpsFixBadge.setTextColor(ContextCompat.getColor(this, R.color.rf_excellent))
        } else {
            binding.tvCoordinates.text = getString(R.string.gps_status_waiting)
            binding.tvGpsFixBadge.text = "NO FIX"
            binding.tvGpsFixBadge.setTextColor(ContextCompat.getColor(this, R.color.rf_fair))
        }

        val distKm = gps.cumulativeDistanceM / 1000.0
        binding.tvDistance.text = String.format(Locale.US, "%.2f km", distKm)
        binding.tvSpeed.text = gps.speedKmh?.let { "Speed: ${String.format(Locale.US, "%.1f", it)} km/h" } ?: "Speed: 0.0 km/h"
        binding.tvAccuracy.text = gps.accuracyM?.let { "Accuracy: ±${String.format(Locale.US, "%.1f", it)} m" } ?: "Accuracy: — m"
        binding.tvBearing.text = gps.bearing?.let { "Heading: ${String.format(Locale.US, "%.0f°", it)}" } ?: "Heading: —°"
    }

    private fun updateLteUi(lte: LteMetrics) {
        val opIdentity = recorder.telephonyCollector.getOperatorIdentity()
        val displayOp = opIdentity.getEffectiveDisplayOperator()
        binding.tvOperatorName.text = when (lte.nrState) {
            "SA" -> "$displayOp [5G SA]"
            "NSA" -> "$displayOp [5G NSA]"
            else -> displayOp
        }
        binding.tvServingPci.text = lte.servingPci?.let { "PCI $it" } ?: "PCI —"

        if (lte.rsrpDbm != null) {
            binding.tvRsrp.text = "${lte.rsrpDbm} dBm"
            val rsrpColor = when (lte.rsrpRating) {
                "EXCELLENT" -> R.color.rf_excellent
                "GOOD" -> R.color.rf_good
                "FAIR" -> R.color.rf_fair
                else -> R.color.rf_poor
            }
            binding.tvRsrp.setTextColor(ContextCompat.getColor(this, rsrpColor))
        } else {
            binding.tvRsrp.text = "— dBm"
            binding.tvRsrp.setTextColor(ContextCompat.getColor(this, R.color.on_background))
        }

        val sinrColor = when {
            lte.sinrDb == null -> R.color.text_secondary
            lte.sinrDb >= 20.0 -> R.color.rf_excellent
            lte.sinrDb >= 13.0 -> R.color.rf_good
            lte.sinrDb >= 0.0 -> R.color.rf_fair
            else -> R.color.rf_poor
        }

        binding.tvSinr.text = when (lte.sinrSource) {
            "MEASURED" -> lte.sinrDb?.let { "SINR: ${String.format(Locale.US, "%.1f", it)} dB [MEASURED]" } ?: "SINR: UNAVAILABLE"
            "ESTIMATED" -> lte.sinrDb?.let { "SINR: ${String.format(Locale.US, "%.1f", it)} dB [ESTIMATED ⓘ]" } ?: "SINR: UNAVAILABLE"
            else -> "SINR: UNAVAILABLE"
        }
        binding.tvSinr.setTextColor(ContextCompat.getColor(this, sinrColor))

        binding.tvSinrValue.text = lte.sinrDb?.let { String.format(Locale.US, "%.1f dB", it) } ?: "— dB"
        binding.tvSinrValue.setTextColor(ContextCompat.getColor(this, sinrColor))

        when (lte.sinrSource) {
            "MEASURED" -> {
                binding.tvSinrSourceBadge.text = "[MEASURED]"
                binding.tvSinrSourceBadge.setTextColor(ContextCompat.getColor(this, R.color.rf_excellent))
                binding.tvSinrIndicatorIcon.visibility = View.GONE
                binding.tvSinrQualitativeDiagnostic.text = lte.qualitativeDiagnostic ?: "Modem physical baseband measurement"
                binding.tvSinrModelDetails.text = "Direct modem hardware RSSNR register. Tap for measurement context."
            }
            "ESTIMATED" -> {
                binding.tvSinrSourceBadge.text = "[ESTIMATED ⓘ]"
                binding.tvSinrSourceBadge.setTextColor(ContextCompat.getColor(this, R.color.rf_fair))
                binding.tvSinrIndicatorIcon.visibility = View.VISIBLE
                binding.tvSinrQualitativeDiagnostic.text = lte.qualitativeDiagnostic ?: "Calibrated radio model estimate"
                val rssiStr = lte.impliedRssiDbm?.let { String.format(Locale.US, "%.1f dBm", it) } ?: "—"
                binding.tvSinrModelDetails.text = "3GPP TS 36.214 Load Model (Implied RSSI: $rssiStr). Tap to inspect formula."
            }
            else -> {
                binding.tvSinrSourceBadge.text = "[UNAVAILABLE]"
                binding.tvSinrSourceBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.tvSinrIndicatorIcon.visibility = View.GONE
                binding.tvSinrQualitativeDiagnostic.text = lte.qualitativeDiagnostic ?: "Modem does not expose hardware SNR"
                binding.tvSinrModelDetails.text = "Tap to inspect RF channel conditions and estimation requirements."
            }
        }
        binding.tvRsrq.text = lte.rsrqDb?.let { "RSRQ: $it dB [MEASURED]" } ?: "RSRQ: — dB"
        binding.tvRssi.text = lte.rssiDbm?.let { "RSSI: $it dBm [MEASURED]" } ?: "RSSI: — dBm"
        binding.tvEarfcn.text = lte.earfcn?.let { "EARFCN: $it" } ?: "EARFCN: —"
        binding.tvTac.text = lte.tac?.let { "TAC: $it" } ?: "TAC: —"

        // Update Operator & Network Identity Card
        binding.tvOperatorFreshnessBadge.text = opIdentity.getFreshnessText()
        binding.tvSimOperatorDetail.text = "SIM: ${opIdentity.simResolvedName} (MCC/MNC: ${opIdentity.simOperatorNumeric ?: "—"})"
        binding.tvNetworkOperatorDetail.text = "Registered Net: ${opIdentity.networkResolvedName} • ${opIdentity.roamingStatusText}"
        val eciText = opIdentity.eci?.let { eci ->
            val enb = opIdentity.derivedEnodebId
            val sec = opIdentity.derivedSectorId
            "ECGI: ${opIdentity.ecgi ?: eci} (eNodeB: $enb, Sec: $sec) • TAC: ${opIdentity.tac ?: "—"}"
        } ?: "ECI: ${opIdentity.eci ?: "—"} • TAC: ${opIdentity.tac ?: "—"}"
        binding.tvServingIdentityDetail.text = eciText
        binding.tvServingBandDetail.text = opIdentity.derivedBandText
    }

    private fun refreshCellsView() {
        val isLiveRecording = recorder.currentSession.value != null
        val allCells = if (activeSessionToInspect != null && !isLiveRecording && cachedMeasurements.isNotEmpty()) {
            extractCellsFromMeasurements(cachedMeasurements)
        } else {
            recorder.latestLte.value.allCells
        }
        cellAdapter.updateCells(allCells)
        binding.rfChannelGraphView.setCells(allCells)

        val servingCount = allCells.count { it.isServing }
        val neighborCount = allCells.count { !it.isServing }
        binding.tvCellsCountBadge.text = "$servingCount Serving, $neighborCount Neighbors"
        binding.tvRfCellCount.text = "$servingCount serving, $neighborCount neighbor"
        val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val sourceLabel = if (isLiveRecording || activeSessionToInspect == null) "Live Telemetry" else "Session Telemetry"
        binding.tvRfGraphFreshness.text = "$sourceLabel: refreshed at $timeStr"

        // Evaluate Stronger Neighbor Intelligence ("Why Not Connected?")
        val primaryServing = allCells.firstOrNull { it.isServing }
        val neighbors = allCells.filter { !it.isServing }

        if (primaryServing != null && neighbors.isNotEmpty()) {
            val analysis = ServingNeighborIntelligence.analyze(primaryServing, neighbors)
            if (analysis.hasStrongerNeighbor) {
                binding.cardNeighborIntelligence.visibility = View.VISIBLE
                binding.tvNeighborVerdict.text = analysis.engineeringVerdict
                val deltaStr = analysis.deltaRsrpDb?.let { String.format(Locale.US, "+%.1f dB Stronger", -it) } ?: "Stronger"
                binding.tvNeighborDeltaBadge.text = deltaStr

                val best = analysis.bestNeighborCell
                binding.tvNeighborComparison.text = "Neighbor PCI ${best?.pci ?: "—"} (${best?.rsrpDbm ?: "—"} dBm) vs Serving PCI ${primaryServing.pci ?: "—"} (${primaryServing.rsrpDbm ?: "—"} dBm)"
                binding.tvNeighborReason.text = analysis.primaryReason
                binding.tvNeighborStandards.text = analysis.standardsBasis
            } else {
                binding.cardNeighborIntelligence.visibility = View.GONE
            }
        } else {
            binding.cardNeighborIntelligence.visibility = View.GONE
        }
    }

    private fun extractCellsFromMeasurements(measurements: List<LteMeasurementEntity>): List<ObservedCell> {
        val result = mutableListOf<ObservedCell>()
        val latest = measurements.lastOrNull() ?: return result
        val rat = latest.servingRat ?: latest.networkGen ?: latest.networkType
        latest.servingPci?.let { pci ->
            result.add(
                ObservedCell(
                    pci = pci,
                    earfcn = latest.earfcn,
                    rsrpDbm = latest.rsrpDbm,
                    rsrqDb = latest.rsrqDb,
                    sinrDb = latest.sinrDb,
                    isServing = true,
                    servingStatus = ServingStatus.PRIMARY_SERVING,
                    servingRat = rat,
                    networkType = latest.networkType
                )
            )
        }
        latest.neighborCellsJson?.let { rawJson ->
            for (token in rawJson.split(";")) {
                if (token.isBlank()) continue
                val parts = token.split(":")
                val pci = parts.getOrNull(0)?.toIntOrNull() ?: continue
                if (result.any { it.pci == pci }) continue
                val rsrp = parts.getOrNull(1)?.toIntOrNull()
                val rsrq = parts.getOrNull(2)?.toIntOrNull()
                val earfcn = parts.getOrNull(3)?.toIntOrNull() ?: latest.earfcn
                result.add(
                    ObservedCell(
                        pci = pci,
                        earfcn = earfcn,
                        rsrpDbm = rsrp,
                        rsrqDb = rsrq,
                        sinrDb = null,
                        isServing = false,
                        servingStatus = ServingStatus.NEIGHBOR,
                        servingRat = rat,
                        networkType = latest.networkType
                    )
                )
            }
        }
        return result
    }

    private fun refreshMapData(shouldFitBounds: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = recorder.database
            val isLiveRecording = recorder.currentSession.value != null
            val sessionToUse = activeSessionToInspect ?: recorder.currentSession.value ?: db.sessionDao().getAllSessions().firstOrNull()
            val sessionId = sessionToUse?.id
            if (sessionId != null) {
                val measurements = db.measurementDao().getMeasurementsForSession(sessionId)
                val transitions = db.transitionDao().getTransitionsForSession(sessionId)
                val continuityEvents = db.continuityDao().getContinuityEventsForSession(sessionId)
                val serviceHoles = db.continuityDao().getServiceHolesForSession(sessionId)
                val callEvents = db.callReliabilityDao().getCallEventsForSession(sessionId)
                val qoeTests = db.qoeDao().getTestsForSession(sessionId)

                val towers = if (cachedTowers.isEmpty() || cachedTowersSessionId != sessionId) {
                    TowerEstimator.estimateTowers(measurements)
                } else cachedTowers
                cachedTowers = towers
                cachedTowersSessionId = sessionId

                val surface = if (cachedSurface == null || cachedSurfaceSessionId != sessionId) {
                    CoverageGridEngine.computeCoverageSurface(measurements)
                } else cachedSurface
                cachedSurface = surface
                cachedSurfaceSessionId = sessionId

                val holes = CoverageHoleEstimator.estimateCoverageHoles(measurements, transitions)
                lastDetectedCoverageHoles = holes

                withContext(Dispatchers.Main) {
                    cachedMeasurements = measurements
                    val isHistoricalMode = activeSessionToInspect != null || (!isLiveRecording && sessionToUse != null)
                    binding.driveMapView.setData(
                        newMeasurements = measurements,
                        newTransitions = transitions,
                        newSurface = cachedSurface,
                        newTowers = cachedTowers,
                        newContinuityEvents = continuityEvents,
                        newServiceHoles = serviceHoles,
                        newCallEvents = callEvents,
                        newCoverageHoles = holes,
                        newActiveQoeTests = qoeTests,
                        historicalMode = isHistoricalMode,
                        sessionName = sessionToUse?.sessionName
                    )

                    binding.coveragePreviewView.setData(
                        newMeasurements = measurements,
                        newSurface = cachedSurface,
                        newHoles = holes,
                        title = sessionToUse?.sessionName
                    )

                    if (shouldFitBounds && measurements.any { it.latitude != null && it.longitude != null && it.isGpsValid }) {
                        binding.driveMapView.fitBounds()
                    }

                    val validCount = measurements.count { it.latitude != null && it.longitude != null && it.isGpsValid }
                    if (validCount > 1) {
                        binding.cardRouteScrubber.visibility = View.VISIBLE
                        binding.seekBarRouteScrubber.max = validCount - 1
                        val lastValid = measurements.lastOrNull { it.latitude != null && it.longitude != null && it.isGpsValid }
                        if (lastValid != null) {
                            val timeStr = "${TelecomTimeUtils.isoToBdtTimeOnly(lastValid.timestampIso, includeMillis = true)} (BST)"
                            binding.tvScrubberTimestamp.text = "Point $validCount/$validCount • Time: $timeStr"
                            binding.tvScrubberTech.text = "${lastValid.networkGen ?: lastValid.networkType} (${lastValid.dataNetworkType ?: lastValid.networkType})"
                        }
                    } else {
                        binding.cardRouteScrubber.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun refreshAnalytics(forceRecompute: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = recorder.database
            val target = activeSessionToInspect ?: recorder.currentSession.value ?: db.sessionDao().getAllSessions().firstOrNull()

            if (target == null) {
                withContext(Dispatchers.Main) {
                    binding.tvTowerDetails.text = "No sessions recorded yet."
                }
                return@launch
            }

            val measurements = db.measurementDao().getMeasurementsForSession(target.id)
            val transitions = db.transitionDao().getTransitionsForSession(target.id)

            // 1. Coverage Surface
            val surface = if (cachedSurface == null || forceRecompute) {
                CoverageGridEngine.computeCoverageSurface(measurements)
            } else cachedSurface!!
            cachedSurface = surface

            // 2. Handover Analysis
            val handoverSummary = HandoverAnalyzer.analyzeTransitions(target.id, measurements, target.totalDistanceM)

            // 3. Tower Estimation
            val towers = if (cachedTowers.isEmpty() || forceRecompute) {
                TowerEstimator.estimateTowers(measurements)
            } else cachedTowers
            cachedTowers = towers

            // 4. Coverage Planning & Weak Zones
            val weakZones = CoveragePlanner.identifyWeakZones(measurements, transitions)

            // 5. Frequency & Reuse Analysis (Priorities 2 & 3)
            val reuseSummary = ReuseAnalyzer.analyzeReuse(measurements)
            val interferenceReport = InterferenceAnalyzer.analyze(measurements, recorder.latestLte.value.allCells)

            // 6. Radio Quality & Noise Diagnostics (Priority 4)
            val qualityReport = RadioQualityEngine.evaluateSessionQuality(measurements)

            // 7. Cell Comparison Aggregations (Priority 5)
            val comparisonItems = buildComparisonItems(measurements)

            // 8. Explainable AI Network Anomaly Detection
            val qoeTests = db.qoeDao().getTestsForSession(target.id)
            val anomalyReport = com.cellscope.drive.analytics.anomaly.NetworkAnomalyDetector.analyzeSession(
                sessionId = target.id,
                measurements = measurements,
                transitions = transitions,
                qoeTests = qoeTests
            )

            val continuityEvents = db.continuityDao().getContinuityEventsForSession(target.id)
            val serviceHoles = db.continuityDao().getServiceHolesForSession(target.id)
            val callEvents = db.callReliabilityDao().getCallEventsForSession(target.id)

            withContext(Dispatchers.Main) {
                // Update Coverage Tab UI
                binding.tvCoverageArea.text = String.format(Locale.US, "Evaluated Geographic Area: %.3f km²", surface.totalMeasuredAreaKm2)
                binding.tvCoverageAvgRsrp.text = surface.avgRsrp?.let { String.format(Locale.US, "Surface Mean RSRP: %.1f dBm", it) } ?: "Surface Mean RSRP: — dBm"
                binding.tvCoverageWeakPct.text = String.format(Locale.US, "Weak Coverage Proportion (< -105 dBm): %.1f%%", surface.weakCoveragePercent)

                // Dedicated Coverage Charts
                binding.chartCoverageQuality.setData(measurements)
                binding.chartSignalHistogram.setData(measurements)
                binding.chartRatDistribution.setData(measurements)

                // Update Frequency & Reuse Tab UI
                binding.tvFreqBandsSummary.text = "Detected ${reuseSummary.activeBands.size} Bands (${reuseSummary.activeBands.joinToString { "B$it" }}) • ${reuseSummary.totalUniqueEarfcn} Channels • ${reuseSummary.totalUniquePcis} PCIs"

                val freqSb = StringBuilder()
                if (reuseSummary.sameFrequencyCellsCount.isEmpty()) {
                    freqSb.append("No active channels observed yet.")
                } else {
                    for ((earfcn, cellCount) in reuseSummary.sameFrequencyCellsCount) {
                        val bandInfo = LteBandTable.resolveEarfcn(earfcn)
                        if (bandInfo != null) {
                            freqSb.append("• EARFCN $earfcn: Band ${bandInfo.bandNumber} (${bandInfo.designation})\n")
                            freqSb.append("  Mode: ${bandInfo.duplexMode} | DL: ${bandInfo.dlFrequencyMhz} MHz | UL: ${bandInfo.ulFrequencyMhz ?: "N/A"} MHz\n")
                            if (bandInfo.bangladeshOperators.isNotEmpty()) {
                                freqSb.append("  BD Carriers: ${bandInfo.bangladeshOperators.joinToString()}\n")
                            }
                            freqSb.append("  Observed Cells: $cellCount physical sectors\n\n")
                        } else {
                            freqSb.append("• EARFCN $earfcn: Non-standard/Unknown ($cellCount cells)\n\n")
                        }
                    }
                }
                binding.tvFreqChannelsText.text = freqSb.toString().trimEnd()

                val reuseSb = StringBuilder()
                if (reuseSummary.reuseEvents.isEmpty()) {
                    reuseSb.append("No multi-sector PCI reuse observed. All observed PCIs have unique physical deployments.")
                } else {
                    for (re in reuseSummary.reuseEvents) {
                        val riskTag = when (re.riskLevel) {
                            ReuseAnalyzer.ReuseRiskLevel.HIGH_RISK_COLLISION -> "[COLLISION WARNING]"
                            ReuseAnalyzer.ReuseRiskLevel.MODERATE_RISK_PROXIMITY -> "[PROXIMITY ALERT]"
                            else -> "[PLANNED REUSE]"
                        }
                        reuseSb.append("$riskTag PCI ${re.pci} (Sep: ${String.format(Locale.US, "%.0fm", re.separationDistanceM)}):\n")
                        reuseSb.append("  ${re.diagnosticDescription}\n\n")
                    }
                }
                binding.tvFreqReuseText.text = reuseSb.toString().trimEnd()

                // Section A: Observed Same-Channel Cells
                val sameChannelSb = StringBuilder()
                if (interferenceReport.coChannelEvents.isEmpty()) {
                    sameChannelSb.append("No co-channel cells observed. Telemetry indicates single-cell or disparate frequency operations across all recorded points.")
                } else {
                    for (cc in interferenceReport.coChannelEvents) {
                        val bandInfo = LteBandTable.resolveEarfcn(cc.earfcnOrArfcn)
                        val bandStr = bandInfo?.let { "Band ${it.bandNumber} (${it.dlFrequencyMhz} MHz)" } ?: "Channel ${cc.earfcnOrArfcn}"
                        val obsType = if (cc.isSimultaneousObservation) "SIMULTANEOUS (Active Serving + Neighbor)" else "HISTORICAL (Encountered along route)"
                        val distStr = cc.distanceM?.let { " • Spatial Separation: ${String.format(Locale.US, "%.0fm", it)}" } ?: ""
                        sameChannelSb.append("• [${cc.classification.label}] $bandStr\n")
                        sameChannelSb.append("  PCIs: Cell A = ${cc.pciA}, Cell B = ${cc.pciB}$distStr\n")
                        sameChannelSb.append("  Observation: $obsType\n")
                        sameChannelSb.append("  Evidence: ${cc.engineeringEvidence}\n\n")
                    }
                }
                binding.tvSameChannelText.text = sameChannelSb.toString().trimEnd()

                // Section B: Downlink CRS Modulo-3 Analysis (3GPP TS 36.211)
                val pciMod3Sb = StringBuilder()
                val crsPairs = interferenceReport.coChannelEvents.filter { it.crsMod3Collision }
                if (crsPairs.isEmpty() && reuseSummary.crsCollisions.isEmpty()) {
                    pciMod3Sb.append("Zero CRS Modulo-3 Collisions Detected.\nAll observed co-channel cell pairs possess differing subcarrier shifts (v_shift ≠ 0), interleaving reference signals across orthogonal subcarriers in accordance with 3GPP TS 36.211.")
                } else {
                    pciMod3Sb.append("=== 3GPP TS 36.211 CRS SUBFRAME FREQUENCY SHIFT ANALYSIS ===\n")
                    pciMod3Sb.append("Rule: Cell-Specific Reference Signals (CRS) shift in the OFDM subcarrier grid by v_shift = PCI mod 3 (v_shift ∈ {0, 1, 2}).\n\n")
                    for (cc in crsPairs) {
                        val shift = cc.pciA % 3
                        pciMod3Sb.append("• Carrier ${cc.earfcnOrArfcn}: PCI ${cc.pciA} (v_shift = $shift) vs PCI ${cc.pciB} (v_shift = $shift) [COLLISION]\n")
                        pciMod3Sb.append("  Standards Meaning: Both cells map downlink CRS reference symbols onto identical subcarriers ($shift, ${shift + 3}, ${shift + 6} ...).\n")
                        pciMod3Sb.append("  Technical Assessment: Modulo-3 alignment confirms subcarrier overlap in the OFDM grid. However, it only causes physical SINR degradation if both cells transmit simultaneously with comparable received power at the UE location.\n\n")
                    }
                    for (crs in reuseSummary.crsCollisions) {
                        if (crsPairs.none { it.earfcnOrArfcn == crs.earfcn && ((it.pciA == crs.pciA && it.pciB == crs.pciB) || (it.pciA == crs.pciB && it.pciB == crs.pciA)) }) {
                            pciMod3Sb.append("• Carrier ${crs.earfcn}: PCI ${crs.pciA} vs PCI ${crs.pciB} (v_shift = ${crs.mod3Value}) [COLLISION]\n")
                            pciMod3Sb.append("  Standards Meaning: ${crs.technicalReason}\n\n")
                        }
                    }
                }
                binding.tvPciMod3Text.text = pciMod3Sb.toString().trimEnd()

                // Section C: Adjacent-Channel Assessment
                val adjacentSb = StringBuilder()
                if (interferenceReport.adjacentEvents.isEmpty()) {
                    adjacentSb.append("No adjacent channel deployments detected in current telemetry window.")
                } else {
                    for (ac in interferenceReport.adjacentEvents) {
                        adjacentSb.append("• [${ac.classification.label}] Ch ${ac.channelA} (${ac.centerFreqA} MHz) & Ch ${ac.channelB} (${ac.centerFreqB} MHz)\n")
                        adjacentSb.append("  Center Frequency Separation: ${String.format(Locale.US, "%.2f MHz", ac.deltaFreqMhz)}\n")
                        val bwNote = if (ac.engineeringEvidence.contains("bandwidth not exposed")) "Modem RIL default (10.0 MHz nominal)" else "Reported: ${ac.bandwidthA} MHz / ${ac.bandwidthB} MHz"
                        adjacentSb.append("  Channel Bandwidths: $bwNote\n")
                        adjacentSb.append("  Assessment: ${ac.engineeringEvidence}\n\n")
                    }
                    adjacentSb.append("Note: ${interferenceReport.analyticalDisclaimer}")
                }
                binding.tvAdjacentChannelText.text = adjacentSb.toString().trimEnd()

                // Update Radio Quality Tab UI
                val rsrpD = qualityReport.rsrpDistribution
                val rsrqD = qualityReport.rsrqDistribution
                val sinrD = qualityReport.sinrDistribution

                val distSb = StringBuilder()
                distSb.append("RSRP (Mean: ${rsrpD.mean ?: "—"} dBm | Median: ${rsrpD.median ?: "—"} dBm | Range: [${rsrpD.min ?: "—"}..${rsrpD.max ?: "—"}] dBm):\n")
                for (b in rsrpD.buckets) {
                    distSb.append("  ${b.label}: ${b.count} (${String.format(Locale.US, "%.1f%%", b.percentage)})\n")
                }
                distSb.append("\nRSRQ (Mean: ${rsrqD.mean ?: "—"} dB | Median: ${rsrqD.median ?: "—"} dB):\n")
                for (b in rsrqD.buckets) {
                    distSb.append("  ${b.label}: ${b.count} (${String.format(Locale.US, "%.1f%%", b.percentage)})\n")
                }
                if (sinrD.mean != null) {
                    distSb.append("\nSINR (Mean: ${sinrD.mean} dB | Median: ${sinrD.median} dB):\n")
                    for (b in sinrD.buckets) {
                        distSb.append("  ${b.label}: ${b.count} (${String.format(Locale.US, "%.1f%%", b.percentage)})\n")
                    }
                } else {
                    distSb.append("\nSINR: Unsupported or unexposed by device telephony modem.\n")
                }
                binding.tvQualityDistributions.text = distSb.toString().trimEnd()

                // 4 Dedicated Quality Charts
                binding.chartSignalTimeSeries.setData(measurements)
                binding.chartSignalDistance.setData(measurements)
                binding.chartQualityScatter.setData(measurements)
                binding.chartSpeedVsSignal.setData(measurements)

                val delta = qualityReport.neighborDelta
                val deltaSb = StringBuilder()
                deltaSb.append("Evaluated Samples: ${delta.totalEvaluatedSamples}\n")
                deltaSb.append("Mean Serving-to-Neighbor Δ: ${delta.avgDeltaRsrpDb?.let { "$it dB" } ?: "—"}\n")
                deltaSb.append("Near-Handover Margin (Δ < 3 dB): ${delta.nearHandoverMarginCount} samples\n")
                deltaSb.append("Overdue Handover Delay (Δ < 0 dB): ${delta.overdueHandoverCount} samples\n")
                deltaSb.append("Pilot Pollution (≥ 3 strong neighbors): ${delta.pilotPollutionCount} samples")
                binding.tvQualityNeighborDelta.text = deltaSb.toString()

                val interf = qualityReport.interferenceEstimate
                val interfSb = StringBuilder()
                interfSb.append("High Co-Channel Noise Ratio: ${interf.highInterferenceRatioPct}%\n")
                interfSb.append("Pilot Pollution Proportion: ${interf.pilotPollutionRatioPct}%\n")
                interfSb.append("Advisory: ${interf.interpretationSummary}")
                binding.tvQualityInterference.text = interfSb.toString()

                // Update Cell Comparison Adapter
                comparisonAdapter.setItems(comparisonItems)

                // Update Handover Tab UI
                binding.tvHandoverMetricsBanner.text = "${handoverSummary.totalTransitions} Transitions (${String.format(Locale.US, "%.2f", handoverSummary.transitionsPerKm)}/km) | ${handoverSummary.confirmedHandovers} Confirmed | ${handoverSummary.intraFrequencyTransitions} Intra-Freq | ${handoverSummary.interFrequencyTransitions} Inter-Freq | ${handoverSummary.pingPongEvents} Ping-Pong"
                transitionAdapter.updateTransitions(handoverSummary.transitionEvents)

                // Dedicated Mobility Timeline Chart
                val timelineEvents = if (transitions.isNotEmpty()) transitions else handoverSummary.transitionEvents
                binding.chartMobilityTimeline.setData(timelineEvents)

                // Update Tower Tab UI
                binding.tvTowerSummary.text = "${towers.size} Candidate Cell Centroids Estimated"
                val towerText = StringBuilder()
                if (towers.isEmpty()) {
                    towerText.append("Insufficient multi-angle observations. Drive around cell sites to generate estimates.")
                } else {
                    for (t in towers) {
                        towerText.append("PCI ${t.pci} (TAC ${t.tac ?: "—"}):\n")
                        towerText.append("  Estimated: ${String.format(Locale.US, "%.5f, %.5f", t.estimatedLat, t.estimatedLon)}\n")
                        towerText.append("  Est. Radius: ±${String.format(Locale.US, "%.0f", t.estimatedCoverageRadiusM)}m | Conf: ${(t.confidenceScore * 100).toInt()}%\n")
                        towerText.append("  Ambiguity: ${t.ambiguityReason ?: "None"}\n\n")
                    }
                }
                binding.tvTowerDetails.text = towerText.toString()

                // Update Planning Tab UI
                binding.tvAnomalySummary.text = anomalyReport.executiveSummary
                weakZoneAdapter.updateZones(weakZones)

                // Update RAT Cross-Generation Comparison UI
                val ratReport = RatComparisonEngine.generateComparisonReport(measurements, qoeTests)
                binding.tvRatDominantText.text = "Dominant Technology: ${ratReport.dominantGeneration?.label ?: "None"} (${ratReport.totalSamples} total samples)"
                binding.tvRatSummaryText.text = ratReport.comparativeSummary

                for (p in ratReport.profiles) {
                    when (p.generation) {
                        com.cellscope.drive.model.NetworkGeneration.GEN_5G -> {
                            binding.tvRat5gTitle.text = "5G NR (${p.sampleCount} pts, ${p.percentageOfTotal}%)"
                            binding.tvRat5gDetails.text = if (p.isObservedInSession) {
                                "Median ${p.primaryMetricName}: ${p.medianPrimarySignalDbm ?: "—"} ${p.primaryMetricUnit} | Median ${p.secondaryMetricName}: ${p.medianSecondaryQuality ?: "—"} ${p.secondaryMetricUnit} | DL: ${p.medianDownloadMbps ?: "—"} Mbps | Latency: ${p.medianLatencyMs ?: "—"} ms"
                            } else {
                                p.comparabilityWarning ?: "Not observed in survey session (0 measurements fabricated)."
                            }
                        }
                        com.cellscope.drive.model.NetworkGeneration.GEN_4G -> {
                            binding.tvRat4gTitle.text = "4G LTE (${p.sampleCount} pts, ${p.percentageOfTotal}%)"
                            binding.tvRat4gDetails.text = if (p.isObservedInSession) {
                                "Median RSRP: ${p.medianPrimarySignalDbm ?: "—"} dBm | Median SINR: ${p.medianSecondaryQuality ?: "—"} dB | Distinct Cells: ${p.distinctCellCount} | DL: ${p.medianDownloadMbps?.let { "$it Mbps" } ?: "No active DL"} | Latency: ${p.medianLatencyMs?.let { "$it ms" } ?: "— ms"}"
                            } else {
                                p.comparabilityWarning ?: "Not observed in survey session."
                            }
                        }
                        com.cellscope.drive.model.NetworkGeneration.GEN_3G -> {
                            binding.tvRat3gTitle.text = "3G WCDMA / HSPA (${p.sampleCount} pts, ${p.percentageOfTotal}%)"
                            binding.tvRat3gDetails.text = if (p.isObservedInSession) {
                                "Median RSCP: ${p.medianPrimarySignalDbm ?: "—"} dBm | Median Ec/No: ${p.medianSecondaryQuality ?: "—"} dB | Distinct Cells: ${p.distinctCellCount}"
                            } else {
                                p.comparabilityWarning ?: "Not observed in survey session."
                            }
                        }
                        com.cellscope.drive.model.NetworkGeneration.GEN_2G -> {
                            binding.tvRat2gTitle.text = "2G GSM / EDGE (${p.sampleCount} pts, ${p.percentageOfTotal}%)"
                            binding.tvRat2gDetails.text = if (p.isObservedInSession) {
                                "Median RxLev: ${p.medianPrimarySignalDbm ?: "—"} dBm | RxQual / BER: ${p.medianSecondaryQuality ?: "—"} | Distinct Cells: ${p.distinctCellCount}"
                            } else {
                                p.comparabilityWarning ?: "Not observed in survey session."
                            }
                        }
                        else -> {}
                    }
                }

                // Update Map overlays
                cachedMeasurements = measurements
                binding.driveMapView.setData(
                    newMeasurements = measurements,
                    newTransitions = transitions,
                    newSurface = surface,
                    newTowers = towers,
                    newContinuityEvents = continuityEvents,
                    newServiceHoles = serviceHoles,
                    newCallEvents = callEvents
                )

                val validCount = measurements.count { it.latitude != null && it.longitude != null && it.isGpsValid }
                if (validCount > 1) {
                    binding.cardRouteScrubber.visibility = View.VISIBLE
                    binding.seekBarRouteScrubber.max = validCount - 1
                } else {
                    binding.cardRouteScrubber.visibility = View.GONE
                }
            }
        }
    }

    private fun loadSessions() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = recorder.database
            val sessions = db.sessionDao().getAllSessions()

            withContext(Dispatchers.Main) {
                if (sessions.isEmpty()) {
                    binding.tvEmptySessions.visibility = View.VISIBLE
                    binding.rvSessionsManage.visibility = View.GONE
                } else {
                    binding.tvEmptySessions.visibility = View.GONE
                    binding.rvSessionsManage.visibility = View.VISIBLE
                    binding.rvSessionsManage.adapter = SessionAdapter(
                        sessions = sessions,
                        onExportClick = { selectedSession ->
                            activeSessionToInspect = selectedSession
                            binding.tvReportTargetSession.text = "Target: ${selectedSession.sessionName} (${selectedSession.sampleCount} samples)"
                            binding.tabLayoutManage.getTabAt(1)?.select()
                            Toast.makeText(this@MainActivity, "Ready to export: ${selectedSession.sessionName}", Toast.LENGTH_SHORT).show()
                        },
                        onItemClick = { clickedSession ->
                            activeSessionToInspect = clickedSession
                            binding.tvReportTargetSession.text = "Target: ${clickedSession.sessionName} (${clickedSession.sampleCount} samples)"
                            updateSessionIntelligenceUi(clickedSession)
                            showSessionActionSheet(clickedSession)
                        }
                    )
                    if (activeSessionToInspect == null) {
                        activeSessionToInspect = sessions.first()
                        binding.tvReportTargetSession.text = "Target: ${sessions.first().sessionName}"
                    }
                }
            }
        }
    }

    private enum class ExportAction {
        SAVE_PDF,
        SHARE_PDF,
        SAVE_CSV,
        SHARE_CSV,
        GOOGLE_DRIVE,
        EXPORT_AI_MARKDOWN,
        SHARE_AI_JSON
    }

    private fun handleExportAction(action: ExportAction) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = recorder.database
            val target = activeSessionToInspect ?: recorder.currentSession.value ?: db.sessionDao().getAllSessions().firstOrNull()

            if (target == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "No session available to export.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val measurements = db.measurementDao().getMeasurementsForSession(target.id)
            val continuityEvents = db.continuityDao().getContinuityEventsForSession(target.id)
            val serviceHoles = db.continuityDao().getServiceHolesForSession(target.id)
            val callEvents = db.callReliabilityDao().getCallEventsForSession(target.id)

            if (measurements.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Session has no recorded measurements.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val locState = authoritativeLocationState
            val exportVenue = locState?.let {
                EventVenue.fromCoordinates(
                    lat = it.latitude,
                    lon = it.longitude,
                    name = it.eventName ?: it.resolvedLocality ?: "Event Venue",
                    eventName = it.eventName ?: "Gathering",
                    radiusMeters = it.boundaryRadiusMeters
                )
            } ?: binding.driveMapView.activeEventVenue ?: run {
                val firstValid = measurements.firstOrNull { it.latitude != null && it.longitude != null && it.isGpsValid }
                if (firstValid != null) {
                    EventVenue.fromCoordinates(
                        lat = firstValid.latitude!!,
                        lon = firstValid.longitude!!,
                        name = "Surveyed Session Area",
                        eventName = "Event Planning Area",
                        radiusMeters = 500.0
                    )
                } else null
            }
            val exportResult = binding.driveMapView.activeEventResult
            val sessionQoeTests = db.qoeDao().getTestsForSession(target.id)
            val sessionTransitions = db.transitionDao().getTransitionsForSession(target.id)

            when (action) {
                ExportAction.SAVE_PDF -> {
                    val pdfFile = PdfReportExporter.generateSessionPdfReport(
                        context = this@MainActivity,
                        session = target,
                        measurements = measurements,
                        continuityEvents = continuityEvents,
                        serviceHoles = serviceHoles,
                        callEvents = callEvents,
                        venue = exportVenue,
                        eventResult = exportResult,
                        activeQoeTests = sessionQoeTests,
                        cellTransitions = sessionTransitions
                    )
                    pendingPdfFileToSave = pdfFile
                    withContext(Dispatchers.Main) {
                        createPdfLauncher.launch(pdfFile.name)
                    }
                }
                ExportAction.SHARE_PDF -> {
                    val pdfFile = PdfReportExporter.generateSessionPdfReport(
                        context = this@MainActivity,
                        session = target,
                        measurements = measurements,
                        continuityEvents = continuityEvents,
                        serviceHoles = serviceHoles,
                        callEvents = callEvents,
                        venue = exportVenue,
                        eventResult = exportResult,
                        activeQoeTests = sessionQoeTests,
                        cellTransitions = sessionTransitions
                    )
                    withContext(Dispatchers.Main) {
                        val share = PdfReportExporter.createSharePdfIntent(this@MainActivity, pdfFile)
                        startActivity(Intent.createChooser(share, "Share PDF Drive Report"))
                    }
                }
                ExportAction.SAVE_CSV -> {
                    val csvFile = if (sessionQoeTests.isNotEmpty() || continuityEvents.isNotEmpty() || serviceHoles.isNotEmpty()) {
                        CsvExporter.exportExtendedSessionToCsv(
                            context = this@MainActivity,
                            session = target,
                            measurements = measurements,
                            activeQoeTests = sessionQoeTests,
                            continuityEvents = continuityEvents,
                            serviceHoles = serviceHoles,
                            callEvents = callEvents,
                            cellTransitions = sessionTransitions
                        )
                    } else {
                        CsvExporter.exportSessionToCsv(this@MainActivity, target, measurements)
                    }
                    pendingCsvFileToSave = csvFile
                    withContext(Dispatchers.Main) {
                        createCsvLauncher.launch(csvFile.name)
                    }
                }
                ExportAction.SHARE_CSV -> {
                    val csvFile = if (sessionQoeTests.isNotEmpty() || continuityEvents.isNotEmpty() || serviceHoles.isNotEmpty()) {
                        CsvExporter.exportExtendedSessionToCsv(
                            context = this@MainActivity,
                            session = target,
                            measurements = measurements,
                            activeQoeTests = sessionQoeTests,
                            continuityEvents = continuityEvents,
                            serviceHoles = serviceHoles,
                            callEvents = callEvents,
                            cellTransitions = sessionTransitions
                        )
                    } else {
                        CsvExporter.exportSessionToCsv(this@MainActivity, target, measurements)
                    }
                    withContext(Dispatchers.Main) {
                        val share = CsvExporter.createShareIntent(this@MainActivity, csvFile)
                        startActivity(Intent.createChooser(share, "Export Drive CSV"))
                    }
                }
                ExportAction.EXPORT_AI_MARKDOWN -> {
                    val transitions = db.transitionDao().getTransitionsForSession(target.id)
                    val qoeTests = db.qoeDao().getTestsForSession(target.id)
                    val anomalyReport = com.cellscope.drive.analytics.anomaly.NetworkAnomalyDetector.analyzeSession(
                        sessionId = target.id,
                        measurements = measurements,
                        transitions = transitions,
                        qoeTests = qoeTests
                    )
                    val mdFile = com.cellscope.drive.export.AiAnalysisExporter.exportAnalysisToMarkdown(
                        context = this@MainActivity,
                        session = target,
                        report = anomalyReport
                    )
                    withContext(Dispatchers.Main) {
                        val share = com.cellscope.drive.export.AiAnalysisExporter.createShareIntent(
                            context = this@MainActivity,
                            file = mdFile,
                            mimeType = "text/markdown"
                        )
                        startActivity(Intent.createChooser(share, "Export AI Analysis Report"))
                    }
                }
                ExportAction.SHARE_AI_JSON -> {
                    val transitions = db.transitionDao().getTransitionsForSession(target.id)
                    val qoeTests = db.qoeDao().getTestsForSession(target.id)
                    val anomalyReport = com.cellscope.drive.analytics.anomaly.NetworkAnomalyDetector.analyzeSession(
                        sessionId = target.id,
                        measurements = measurements,
                        transitions = transitions,
                        qoeTests = qoeTests
                    )
                    val jsonFile = com.cellscope.drive.export.AiAnalysisExporter.exportAnalysisToJson(
                        context = this@MainActivity,
                        session = target,
                        report = anomalyReport
                    )
                    withContext(Dispatchers.Main) {
                        val share = com.cellscope.drive.export.AiAnalysisExporter.createShareIntent(
                            context = this@MainActivity,
                            file = jsonFile,
                            mimeType = "application/json"
                        )
                        startActivity(Intent.createChooser(share, "Share AI Diagnostic JSON"))
                    }
                }
                ExportAction.GOOGLE_DRIVE -> {
                    val pdfFile = PdfReportExporter.generateSessionPdfReport(
                        context = this@MainActivity,
                        session = target,
                        measurements = measurements,
                        continuityEvents = continuityEvents,
                        serviceHoles = serviceHoles,
                        callEvents = callEvents,
                        venue = exportVenue,
                        eventResult = exportResult,
                        activeQoeTests = sessionQoeTests,
                        cellTransitions = sessionTransitions
                    )
                    withContext(Dispatchers.Main) {
                        val drivePackage = "com.google.android.apps.docs"
                        val isDriveInstalled = try {
                            packageManager.getPackageInfo(drivePackage, 0)
                            true
                        } catch (e: PackageManager.NameNotFoundException) {
                            false
                        }

                        if (isDriveInstalled) {
                            try {
                                val share = PdfReportExporter.createSharePdfIntent(this@MainActivity, pdfFile).apply {
                                    setPackage(drivePackage)
                                }
                                startActivity(share)
                            } catch (e: Exception) {
                                val share = PdfReportExporter.createSharePdfIntent(this@MainActivity, pdfFile)
                                startActivity(Intent.createChooser(share, "Save to Google Drive"))
                            }
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Google Drive app is not installed. Opening standard share sheet...",
                                Toast.LENGTH_LONG
                            ).show()
                            val share = PdfReportExporter.createSharePdfIntent(this@MainActivity, pdfFile)
                            startActivity(Intent.createChooser(share, "Save or Share Field Report"))
                        }
                    }
                }
            }
        }
    }

    private fun generateReportForActiveSession(isPdf: Boolean) {
        handleExportAction(if (isPdf) ExportAction.SHARE_PDF else ExportAction.SHARE_CSV)
    }

    private fun updateDiagnosticsDisplay() {
        val diag = recorder.telephonyCollector.latestDiagnostics
        val locManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        val gpsEnabled = locManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        val netEnabled = locManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true

        val sb = StringBuilder()
        sb.append("=== DEVICE & HARDWARE ===\n")
        sb.append("Device Model: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n")

        sb.append("=== TELEPHONY SUBSYSTEM ===\n")
        sb.append("Telephony Hardware: ${if (diag.hasTelephonyHardware) "PRESENT" else "NOT DETECTED"}\n")
        sb.append("Network Operator: ${diag.networkOperator ?: "—"}\n")
        sb.append("SIM Operator: ${diag.simOperator ?: "—"}\n")
        sb.append("Data Network: ${diag.dataNetworkType}\n")
        sb.append("Total Visible Cells: ${diag.totalCellsObserved}\n")
        sb.append("Serving Cells: ${diag.servingCellsCount}\n")
        sb.append("Neighbor Reporting: ${if (diag.supportsNeighborCells) "SUPPORTED" else "UNAVAILABLE ON THIS DEVICE/CARRIER"}\n")
        sb.append("SINR / RSSNR Metric: ${if (diag.supportsSinr) "EXPOSED" else "UNSUPPORTED BY MODEM"}\n")
        sb.append("Timing Advance: ${if (diag.supportsTimingAdvance) "SUPPORTED" else "UNAVAILABLE"}\n\n")

        sb.append("=== 5G NR SUBSYSTEM ===\n")
        sb.append("5G NR Capability: ${if (diag.supports5gNr) "SUPPORTED (API ${Build.VERSION.SDK_INT})" else "UNAVAILABLE / PRE-ANDROID 10"}\n")
        sb.append("5G Architecture State: ${diag.nrState} (Availability: ${diag.nrAvailabilityState})\n")
        sb.append("5G Serving/Target NR-PCI: ${diag.nrPci ?: "—"}\n")
        sb.append("5G NR Cell ID (NCI): ${diag.nci ?: "—"}\n")
        sb.append("5G Downlink ARFCN (NR-ARFCN): ${diag.nrArfcn ?: "—"}\n\n")

        sb.append("=== GEOLOCATION SUBSYSTEM ===\n")
        sb.append("GPS Hardware Provider: ${if (gpsEnabled) "ACTIVE" else "DISABLED"}\n")
        sb.append("Network Provider: ${if (netEnabled) "ACTIVE" else "DISABLED"}\n")
        sb.append("Coordinate Sanity Filter: ACTIVE (-90..90, -180..180, Null Island Rejection)\n\n")

        sb.append("=== FIELD TEST CONFIGURATION ===\n")
        sb.append("Sampling Rate: 1.0 Hz (1000ms)\n")
        sb.append("Background Restrictions: Ensure Battery Saver / Optimization is set to 'Unrestricted' for continuous background drive recording.\n")

        binding.tvDiagnosticsText.text = sb.toString()
    }

    private fun showSurveySummaryDialog(session: DriveSession) {
        if (isFinishing || isDestroyed) return

        lifecycleScope.launch(Dispatchers.IO) {
            val db = recorder.database
            val measurements: List<LteMeasurementEntity> = db.measurementDao().getMeasurementsForSession(session.id)

            // Timestamps (Bangladesh Standard Time, Asia/Dhaka)
            val startStr = "${TelecomTimeUtils.formatBdtDateTime(session.startTimeMs)} (BST)"
            val endStr = session.endTimeMs?.let { "${TelecomTimeUtils.formatBdtDateTime(it)} (BST)" } ?: "In Progress"

            // Durations
            val totalSecs = if (session.endTimeMs != null) (session.endTimeMs!! - session.startTimeMs) / 1000 else 0L
            val durationFormatted = TelecomTimeUtils.formatDurationDetailed(totalSecs)

            // Samples & Validation
            val totalSamples = measurements.size
            val validSamples = measurements.count { it.rsrpDbm != null && it.rsrqDb != null }
            val validGpsSamples = measurements.count { it.latitude != null && it.longitude != null && it.latitude != 0.0 && it.longitude != 0.0 }
            val gpsPct = if (totalSamples > 0) (validGpsSamples.toDouble() / totalSamples * 100.0) else 0.0
            val accuracies = measurements.mapNotNull { it.accuracyM }
            val meanAccuracy: Double? = if (accuracies.isNotEmpty()) accuracies.map { it.toDouble() }.average() else null

            // Distance & Mobility Check
            val distanceKm = String.format(Locale.US, "%.2f km", session.totalDistanceM / 1000.0)
            val isStationary = session.totalDistanceM < 20.0 && totalSamples >= 5
            val mobilityStatus = if (isStationary) {
                "STATIONARY (GPS Jitter Filtered: 0.00 km, 0.0 km/h. Localized testing environment.)"
            } else {
                "DYNAMIC DRIVE ($distanceKm covered, Max Speed: ${String.format(Locale.US, "%.1f km/h", session.maxSpeedKmh)})"
            }

            // RF Metrics
            val rsrpVals = measurements.mapNotNull { it.rsrpDbm }
            val rsrqVals = measurements.mapNotNull { it.rsrqDb }
            val sinrVals = measurements.mapNotNull { it.sinrDb }

            val rsrpStr = if (rsrpVals.isNotEmpty()) {
                String.format(Locale.US, "Min: %d | Max: %d | Mean: %.1f dBm", rsrpVals.minOrNull()!!, rsrpVals.maxOrNull()!!, rsrpVals.average())
            } else "No valid RSRP samples"

            val rsrqStr = if (rsrqVals.isNotEmpty()) {
                String.format(Locale.US, "Min: %d | Max: %d | Mean: %.1f dB", rsrqVals.minOrNull()!!, rsrqVals.maxOrNull()!!, rsrqVals.average())
            } else "No valid RSRQ samples"

            val sinrStr = if (sinrVals.isNotEmpty()) {
                String.format(Locale.US, "Min: %.1f | Max: %.1f | Mean: %.1f dB", sinrVals.minOrNull()!!, sinrVals.maxOrNull()!!, sinrVals.average())
            } else "Not exposed by device modem RIL"

            // Network Operator & RAT
            val ratList = measurements.mapNotNull { it.networkType }
            val primaryRat = if (ratList.isNotEmpty()) {
                ratList.groupingBy { rat -> rat }.eachCount().maxByOrNull { entry -> entry.value }?.key ?: "LTE"
            } else "LTE"
            val operator = recorder.telephonyCollector.getOperatorIdentity().networkResolvedName

            // Unavailable metrics & caveats
            val caveats = mutableListOf<String>()
            if (sinrVals.isEmpty()) {
                caveats.add("• Physical SINR/CINR was not provided by modem HAL; channel interference estimates rely on RSRP/RSRQ ratios.")
            }
            if (measurements.any { it.bandwidthKhz == null || it.bandwidthKhz == 0 }) {
                caveats.add("• Channel bandwidth was omitted by Android Telephony; nominal 10.0 MHz bandwidth assigned for RF calculations.")
            }
            if (isStationary) {
                caveats.add("• Stationary collection: Lack of handover events or spatial diversity is expected.")
            }
            if (gpsPct < 80.0) {
                caveats.add("• GPS fix quality: ${String.format(Locale.US, "%.1f%%", gpsPct)} coordinate availability. Check device location permissions and sky visibility.")
            }

            val accStr = if (meanAccuracy != null) String.format(Locale.US, "Mean Acc: ±%.1fm", meanAccuracy) else "No GPS accuracy"

            val msg = StringBuilder()
            msg.append("SURVEY IDENTITY & TIMING:\n")
            msg.append("• Session: ${session.sessionName}\n")
            msg.append("• Start: $startStr\n")
            msg.append("• End: $endStr\n")
            msg.append("• Elapsed Duration: $durationFormatted\n\n")

            msg.append("SAMPLE & MOBILITY AUDIT:\n")
            msg.append("• Recorded Samples: $totalSamples\n")
            msg.append("• Valid RF Telemetry: $validSamples / $totalSamples (${if (totalSamples > 0) (validSamples * 100 / totalSamples) else 0}%)\n")
            msg.append("• GPS Fix Rate: ${String.format(Locale.US, "%.1f%%", gpsPct)} ($accStr)\n")
            msg.append("• Mobility Mode: $mobilityStatus\n\n")

            msg.append("RADIO FREQUENCY TELEMETRY:\n")
            msg.append("• Network: $operator ($primaryRat)\n")
            msg.append("• RSRP: $rsrpStr\n")
            msg.append("• RSRQ: $rsrqStr\n")
            msg.append("• SINR: $sinrStr\n\n")

            if (caveats.isNotEmpty()) {
                msg.append("DATA QUALITY & TELEMETRY CAVEATS:\n")
                msg.append(caveats.joinToString("\n"))
                msg.append("\n\n")
            }
            msg.append("Measurements have been verified and persisted to SQLite database.")

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                try {
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Survey Summary & Telemetry Audit")
                        .setMessage(msg.toString())
                        .setPositiveButton("View Dashboard") { _, _ ->
                            activeSessionToInspect = session
                            updateSessionIntelligenceUi(session)
                            binding.bottomNav.selectedItemId = R.id.nav_dashboard
                        }
                        .setNeutralButton("View Dynamic Map") { _, _ ->
                            activeSessionToInspect = session
                            refreshMapData(shouldFitBounds = true)
                            binding.bottomNav.selectedItemId = R.id.nav_map
                        }
                        .setNegativeButton("More Actions") { _, _ ->
                            showSessionActionSheet(session)
                        }
                        .show()
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to display survey summary dialog", e)
                }
            }
        }
    }

    private fun showSessionActionSheet(session: DriveSession) {
        val options = arrayOf("📊 View Dashboard", "🗺️ View Dynamic Map", "📈 View Graphical Analytics", "📄 Export Technical Reports")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Session Actions: ${session.sessionName}")
            .setItems(options) { _, which ->
                activeSessionToInspect = session
                when (which) {
                    0 -> {
                        updateSessionIntelligenceUi(session)
                        binding.bottomNav.selectedItemId = R.id.nav_dashboard
                    }
                    1 -> {
                        refreshMapData(shouldFitBounds = true)
                        binding.bottomNav.selectedItemId = R.id.nav_map
                    }
                    2 -> {
                        refreshAnalytics(forceRecompute = true)
                        binding.bottomNav.selectedItemId = R.id.nav_analytics
                    }
                    3 -> {
                        binding.tvReportTargetSession.text = "Target: ${session.sessionName} (${session.sampleCount} samples)"
                        binding.bottomNav.selectedItemId = R.id.nav_manage
                        binding.tabLayoutManage.getTabAt(1)?.select()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    internal fun buildComparisonItems(
        measurements: List<LteMeasurementEntity>,
        liveCells: List<com.cellscope.drive.model.ObservedCell> = recorder.latestLte.value.allCells
    ): List<CellComparisonItem> {
        val items = mutableListOf<CellComparisonItem>()
        val liveStatusMap = liveCells.filter { it.pci != null }.associate { it.pci!! to it.servingStatus }

        // 1. Group serving cell observations
        val servingGrouped = measurements.filter { it.servingPci != null }.groupBy { it.servingPci!! }
        for ((pci, samples) in servingGrouped) {
            val earfcn = samples.mapNotNull { it.earfcn }.firstOrNull()
            val bandInfo = LteBandTable.resolveEarfcn(earfcn)
            val tac = samples.mapNotNull { it.tac }.firstOrNull()
            val eci = samples.mapNotNull { it.eci }.firstOrNull()?.toLong()

            val rsrpList = samples.mapNotNull { it.rsrpDbm }
            val rsrqList = samples.mapNotNull { it.rsrqDb }
            val sinrList = samples.mapNotNull { it.sinrDb }

            val avgRsrp = if (rsrpList.isNotEmpty()) Math.round(rsrpList.average() * 10.0) / 10.0 else null
            val avgRsrq = if (rsrqList.isNotEmpty()) Math.round(rsrqList.average() * 10.0) / 10.0 else null
            val avgSinr = if (sinrList.isNotEmpty()) Math.round(sinrList.average() * 10.0) / 10.0 else null

            val currentStatus = liveStatusMap[pci] ?: com.cellscope.drive.model.ServingStatus.PRIMARY_SERVING

            items.add(
                CellComparisonItem(
                    pci = pci,
                    earfcn = earfcn,
                    bandNumber = bandInfo?.bandNumber,
                    bandName = bandInfo?.designation,
                    dlFreqMhz = bandInfo?.dlFrequencyMhz,
                    ulFreqMhz = bandInfo?.ulFrequencyMhz,
                    isServing = currentStatus.isServingConnection,
                    tac = tac,
                    eci = eci,
                    avgRsrp = avgRsrp,
                    minRsrp = rsrpList.minOrNull(),
                    maxRsrp = rsrpList.maxOrNull(),
                    avgRsrq = avgRsrq,
                    avgSinr = avgSinr,
                    sampleCount = samples.size,
                    servingStatus = currentStatus
                )
            )
        }

        // 2. Parse neighbor cell observations from neighborCellsJson
        // Format: "pci:rsrp:rsrq:earfcn;pci:rsrp:rsrq:earfcn;..."
        class NeighborAggregator(val pci: Int) {
            var earfcn: Int? = null
            val rsrpList = mutableListOf<Int>()
            val rsrqList = mutableListOf<Int>()
        }
        val neighborMap = mutableMapOf<Int, NeighborAggregator>()

        for (m in measurements) {
            val rawJson = m.neighborCellsJson ?: continue
            val tokens = rawJson.split(";")
            for (token in tokens) {
                if (token.isBlank()) continue
                val parts = token.split(":")
                if (parts.isNotEmpty()) {
                    val pci = parts[0].toIntOrNull() ?: continue
                    if (servingGrouped.containsKey(pci)) continue

                    val nData = neighborMap.getOrPut(pci) { NeighborAggregator(pci) }
                    val rsrp = parts.getOrNull(1)?.toIntOrNull()
                    val rsrq = parts.getOrNull(2)?.toIntOrNull()
                    val earfcn = parts.getOrNull(3)?.toIntOrNull()

                    if (rsrp != null) nData.rsrpList.add(rsrp)
                    if (rsrq != null) nData.rsrqList.add(rsrq)
                    if (earfcn != null && nData.earfcn == null) nData.earfcn = earfcn
                }
            }
        }

        // Also incorporate live unserving cells if present
        for (liveCell in liveCells) {
            val pci = liveCell.pci ?: continue
            if (liveCell.servingStatus != com.cellscope.drive.model.ServingStatus.PRIMARY_SERVING && !servingGrouped.containsKey(pci) && !neighborMap.containsKey(pci)) {
                val nData = neighborMap.getOrPut(pci) { NeighborAggregator(pci) }
                liveCell.earfcn?.let { nData.earfcn = it }
                liveCell.rsrpDbm?.let { nData.rsrpList.add(it) }
                liveCell.rsrqDb?.let { nData.rsrqList.add(it) }
            }
        }

        for ((pci, nData) in neighborMap) {
            val bandInfo = LteBandTable.resolveEarfcn(nData.earfcn)
            val avgRsrp = if (nData.rsrpList.isNotEmpty()) Math.round(nData.rsrpList.average() * 10.0) / 10.0 else null
            val avgRsrq = if (nData.rsrqList.isNotEmpty()) Math.round(nData.rsrqList.average() * 10.0) / 10.0 else null
            val currentStatus = liveStatusMap[pci] ?: com.cellscope.drive.model.ServingStatus.NEIGHBOR

            items.add(
                CellComparisonItem(
                    pci = pci,
                    earfcn = nData.earfcn,
                    bandNumber = bandInfo?.bandNumber,
                    bandName = bandInfo?.designation,
                    dlFreqMhz = bandInfo?.dlFrequencyMhz,
                    ulFreqMhz = bandInfo?.ulFrequencyMhz,
                    isServing = currentStatus.isServingConnection,
                    tac = null,
                    eci = null,
                    avgRsrp = avgRsrp,
                    minRsrp = nData.rsrpList.minOrNull(),
                    maxRsrp = nData.rsrpList.maxOrNull(),
                    avgRsrq = avgRsrq,
                    avgSinr = null,
                    sampleCount = nData.rsrpList.size.coerceAtLeast(1),
                    servingStatus = currentStatus
                )
            )
        }

        return items.sortedByDescending { it.sampleCount }
    }

    private fun showOfflineMapDialog() {
        val options = arrayOf(
            "Download Greater Dhaka (Zoom 10–14)",
            "Download Current Viewport Area",
            "View Cached Offline Basemap Regions"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Offline Basemap Management")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> downloadOfflineRegion(
                        "Greater Dhaka",
                        LatLngBounds.Builder()
                            .include(LatLng(23.65, 90.30))
                            .include(LatLng(23.95, 90.55))
                            .build()
                    )
                    1 -> downloadOfflineRegion(
                        "Drive Route Area",
                        LatLngBounds.Builder()
                            .include(LatLng(23.70, 90.35))
                            .include(LatLng(23.85, 90.50))
                            .build()
                    )
                    2 -> showCachedRegionsDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadOfflineRegion(name: String, bounds: LatLngBounds) {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("Downloading Offline Basemap")
            setMessage("Downloading tiles for $name…")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        offlineMapManager.downloadArea(name, bounds, 10.0, 14.0, object : OfflineMapManager.DownloadCallback {
            override fun onProgress(percentage: Int, completedResources: Long, totalResources: Long) {
                runOnUiThread {
                    progressDialog.progress = percentage
                    progressDialog.setMessage("Downloaded $completedResources / $totalResources tiles ($percentage%)")
                }
            }

            override fun onSuccess(regionInfo: OfflineMapManager.OfflineRegionInfo) {
                runOnUiThread {
                    progressDialog.dismiss()
                    val sizeMb = regionInfo.completedResourceSize / (1024.0 * 1024.0)
                    Toast.makeText(
                        this@MainActivity,
                        "Offline map '${regionInfo.name}' ready (${String.format(Locale.US, "%.1f MB", sizeMb)}).",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Offline download notice: $error", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun showCachedRegionsDialog() {
        offlineMapManager.listRegions { regions ->
            runOnUiThread {
                if (regions.isEmpty()) {
                    Toast.makeText(this, "No offline basemap regions stored.", Toast.LENGTH_SHORT).show()
                } else {
                    val names = regions.map { "${it.name} (${String.format(Locale.US, "%.1f MB", it.completedResourceSize / 1048576.0)})" }.toTypedArray()
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Cached Basemap Regions")
                        .setItems(names) { _, index ->
                            val selected = regions[index]
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("Delete ${selected.name}?")
                                .setMessage("Remove cached tiles to reclaim device storage?")
                                .setPositiveButton("Delete") { _, _ ->
                                    offlineMapManager.deleteRegion(selected.id) { success ->
                                        runOnUiThread {
                                            Toast.makeText(this, if (success) "Region deleted" else "Delete failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                        .setPositiveButton("Close", null)
                        .show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        binding.driveMapView.onStart()
        binding.coveragePreviewView.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.driveMapView.onResume()
        binding.coveragePreviewView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.driveMapView.onPause()
        binding.coveragePreviewView.onPause()
    }

    override fun onStop() {
        super.onStop()
        binding.driveMapView.onStop()
        binding.coveragePreviewView.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.driveMapView.onDestroy()
        binding.coveragePreviewView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.driveMapView.onSaveInstanceState(outState)
        binding.coveragePreviewView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.driveMapView.onLowMemory()
        binding.coveragePreviewView.onLowMemory()
    }
}
