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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cellscope.drive.analytics.CoverageGridEngine
import com.cellscope.drive.analytics.CoveragePlanner
import com.cellscope.drive.analytics.CoverageSurface
import com.cellscope.drive.analytics.EstimatedTower
import com.cellscope.drive.analytics.HandoverAnalyzer
import com.cellscope.drive.analytics.InterferenceAnalyzer
import com.cellscope.drive.analytics.RadioQualityEngine
import com.cellscope.drive.location.OrientationData
import com.cellscope.drive.location.OrientationTracker
import com.cellscope.drive.util.TelecomTimeUtils
import com.cellscope.drive.analytics.ReuseAnalyzer
import com.cellscope.drive.analytics.ServingNeighborIntelligence
import com.cellscope.drive.analytics.TowerEstimator
import com.cellscope.drive.ui.theme.AppThemeManager
import com.cellscope.drive.ui.theme.AppThemeMode
import com.cellscope.drive.telephony.OperatorIdentity
import com.cellscope.drive.telephony.BangladeshMccMnc
import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.databinding.ActivityMainBinding
import com.cellscope.drive.export.CsvExporter
import com.cellscope.drive.export.PdfReportExporter
import com.cellscope.drive.model.GpsData
import com.cellscope.drive.model.LteMetrics
import com.cellscope.drive.model.RecordingState
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

    private val cellAdapter = CellAdapter()
    private val transitionAdapter = TransitionAdapter()
    private val weakZoneAdapter = WeakZoneAdapter()
    private val comparisonAdapter = ComparisonAdapter()

    private var activeSessionToInspect: DriveSession? = null
    private var cachedSurface: CoverageSurface? = null
    private var cachedTowers: List<EstimatedTower> = emptyList()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recorder = DriveTestRecorder.getInstance(this)
        recorder.checkAndRecoverActiveSession()
        offlineMapManager = OfflineMapManager(this)
        binding.driveMapView.onCreate(savedInstanceState)

        setupRecyclerViews()
        checkAndRequestPermissions()
        setupListeners()
        setupNavigation()
        observeState()
    }

    private fun setupRecyclerViews() {
        binding.rvCells.layoutManager = LinearLayoutManager(this)
        binding.rvCells.adapter = cellAdapter

        binding.rvTransitions.layoutManager = LinearLayoutManager(this)
        binding.rvTransitions.adapter = transitionAdapter

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

        // Sub-tabs for Analytics (7 sub-tabs)
        binding.tabLayoutAnalytics.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                binding.subLayoutCoverage.visibility = View.GONE
                binding.subLayoutFrequency.visibility = View.GONE
                binding.subLayoutQuality.visibility = View.GONE
                binding.subLayoutCompare.visibility = View.GONE
                binding.subLayoutHandovers.visibility = View.GONE
                binding.subLayoutTowers.visibility = View.GONE
                binding.subLayoutPlanning.visibility = View.GONE

                when (tab?.position) {
                    0 -> binding.subLayoutCoverage.visibility = View.VISIBLE
                    1 -> binding.subLayoutFrequency.visibility = View.VISIBLE
                    2 -> binding.subLayoutQuality.visibility = View.VISIBLE
                    3 -> binding.subLayoutCompare.visibility = View.VISIBLE
                    4 -> binding.subLayoutHandovers.visibility = View.VISIBLE
                    5 -> binding.subLayoutTowers.visibility = View.VISIBLE
                    6 -> binding.subLayoutPlanning.visibility = View.VISIBLE
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
                    refreshAnalytics()
                    showSurveySummaryDialog(session)
                }
            }
        }

        // Map Chip layer toggles
        binding.chipRoute.setOnCheckedChangeListener { _, isChecked ->
            binding.driveMapView.showRouteSegments = isChecked
        }
        binding.chipHeatmap.setOnCheckedChangeListener { _, isChecked ->
            binding.driveMapView.showHeatmapOverlay = isChecked
        }
        binding.chipTransitions.setOnCheckedChangeListener { _, isChecked ->
            binding.driveMapView.showTransitions = isChecked
        }
        binding.chipTowers.setOnCheckedChangeListener { _, isChecked ->
            binding.driveMapView.showTowers = isChecked
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
            }
        }
        binding.chipMetricRsrq.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipMetricRsrp.isChecked = false
                binding.chipMetricSinr.isChecked = false
                binding.chipMetricGen.isChecked = false
                binding.driveMapView.activeMetricLayer = com.cellscope.drive.analytics.MapMetricLayer.RSRQ
            }
        }
        binding.chipMetricSinr.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipMetricRsrp.isChecked = false
                binding.chipMetricRsrq.isChecked = false
                binding.chipMetricGen.isChecked = false
                binding.driveMapView.activeMetricLayer = com.cellscope.drive.analytics.MapMetricLayer.SINR
            }
        }
        binding.chipMetricGen.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipMetricRsrp.isChecked = false
                binding.chipMetricRsrq.isChecked = false
                binding.chipMetricSinr.isChecked = false
                binding.driveMapView.activeMetricLayer = com.cellscope.drive.analytics.MapMetricLayer.GENERATION
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

        binding.btnFitBounds.setOnClickListener {
            binding.driveMapView.fitBounds()
        }
        binding.btnRecenter.setOnClickListener {
            binding.driveMapView.recenterOnLatest()
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

        // Interactive RF Frequency Channel Selection Listener
        binding.rfChannelGraphView.onCellSelectedListener = { cell, info ->
            val isServing = cell.isServing && (cell.servingStatus == null || cell.servingStatus == ServingStatus.PRIMARY_SERVING || cell.servingStatus == ServingStatus.SECONDARY_SERVING)
            val roleStr = if (isServing) "Serving Cell" else "Unconfirmed Neighbor"
            binding.tvSelectedCellTitle.text = "$roleStr (PCI ${cell.pci ?: "—"})"
            binding.tvSelectedCellBadge.text = "${info.rat} Band ${info.bandNumber}"
            binding.tvSelectedCellBand.text = "${info.bandName} (${info.duplexMode}) • Ch ${info.channelNumber}"
            val bwText = "${String.format(Locale.US, "%.1f", info.channelBandwidthMhz)} MHz"
            binding.tvSelectedCellFreq.text = "Center: ${String.format(Locale.US, "%.1f", info.dlCenterFreqMhz)} MHz | DL BW: $bwText"
            val rsrpText = cell.rsrpDbm?.let { "$it dBm" } ?: "— dBm"
            val rsrqText = cell.rsrqDb?.let { "$it dB" } ?: "— dB"
            val sinrText = cell.sinrDb?.let { String.format(Locale.US, "%.1f dB", it) } ?: "—"
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
            }
            RecordingState.RECORDING -> {
                binding.tvStatusBadge.text = getString(R.string.session_status_recording)
                binding.tvStatusBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.rf_excellent))
                binding.btnStartRecord.visibility = View.GONE
                binding.layoutActiveControls.visibility = View.VISIBLE
                binding.btnPauseResume.text = getString(R.string.btn_pause)
            }
            RecordingState.PAUSED -> {
                binding.tvStatusBadge.text = getString(R.string.session_status_paused)
                binding.tvStatusBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.rf_fair))
                binding.btnStartRecord.visibility = View.GONE
                binding.layoutActiveControls.visibility = View.VISIBLE
                binding.btnPauseResume.text = getString(R.string.btn_resume)
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
        binding.tvOperatorName.text = when (lte.nrState) {
            "SA" -> "${opIdentity.networkResolvedName} [5G SA]"
            "NSA" -> "${opIdentity.networkResolvedName} [5G NSA]"
            else -> opIdentity.networkResolvedName
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

        binding.tvSinr.text = lte.sinrDb?.let { "SINR: ${String.format(Locale.US, "%.1f", it)} dB" } ?: "SINR: Unavailable"
        binding.tvRsrq.text = lte.rsrqDb?.let { "RSRQ: $it dB" } ?: "RSRQ: — dB"
        binding.tvRssi.text = lte.rssiDbm?.let { "RSSI: $it dBm" } ?: "RSSI: — dBm"
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
        val lte = recorder.latestLte.value
        val allCells = lte.allCells
        cellAdapter.updateCells(allCells)
        binding.rfChannelGraphView.setCells(allCells)

        val servingCount = allCells.count { it.isServing }
        val neighborCount = allCells.count { !it.isServing }
        binding.tvCellsCountBadge.text = "$servingCount Serving, $neighborCount Neighbors"

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

    private fun refreshMapData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = recorder.database
            val sessionId = recorder.currentSession.value?.id ?: activeSessionToInspect?.id
            if (sessionId != null) {
                val measurements = db.measurementDao().getMeasurementsForSession(sessionId)
                val transitions = db.transitionDao().getTransitionsForSession(sessionId)
                val continuityEvents = db.continuityDao().getContinuityEventsForSession(sessionId)
                val serviceHoles = db.continuityDao().getServiceHolesForSession(sessionId)
                val callEvents = db.callReliabilityDao().getCallEventsForSession(sessionId)

                withContext(Dispatchers.Main) {
                    binding.driveMapView.setData(
                        newMeasurements = measurements,
                        newTransitions = transitions,
                        newSurface = cachedSurface,
                        newTowers = cachedTowers,
                        newContinuityEvents = continuityEvents,
                        newServiceHoles = serviceHoles,
                        newCallEvents = callEvents
                    )

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

            withContext(Dispatchers.Main) {
                // Update Coverage Tab UI
                binding.tvCoverageArea.text = String.format(Locale.US, "Evaluated Geographic Area: %.3f km²", surface.totalMeasuredAreaKm2)
                binding.tvCoverageAvgRsrp.text = surface.avgRsrp?.let { String.format(Locale.US, "Surface Mean RSRP: %.1f dBm", it) } ?: "Surface Mean RSRP: — dBm"
                binding.tvCoverageWeakPct.text = String.format(Locale.US, "Weak Coverage Proportion (< -105 dBm): %.1f%%", surface.weakCoveragePercent)

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

                val continuityEvents = db.continuityDao().getContinuityEventsForSession(target.id)
                val serviceHoles = db.continuityDao().getServiceHolesForSession(target.id)
                val callEvents = db.callReliabilityDao().getCallEventsForSession(target.id)

                // Update Map overlays
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
                            Toast.makeText(this@MainActivity, "Selected: ${selectedSession.sessionName}", Toast.LENGTH_SHORT).show()
                        },
                        onItemClick = { clickedSession ->
                            activeSessionToInspect = clickedSession
                            binding.tvReportTargetSession.text = "Target: ${clickedSession.sessionName} (${clickedSession.sampleCount} samples)"
                            showSurveySummaryDialog(clickedSession)
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

            when (action) {
                ExportAction.SAVE_PDF -> {
                    val pdfFile = PdfReportExporter.generateSessionPdfReport(this@MainActivity, target, measurements, continuityEvents, serviceHoles, callEvents)
                    pendingPdfFileToSave = pdfFile
                    withContext(Dispatchers.Main) {
                        createPdfLauncher.launch(pdfFile.name)
                    }
                }
                ExportAction.SHARE_PDF -> {
                    val pdfFile = PdfReportExporter.generateSessionPdfReport(this@MainActivity, target, measurements, continuityEvents, serviceHoles, callEvents)
                    withContext(Dispatchers.Main) {
                        val share = PdfReportExporter.createSharePdfIntent(this@MainActivity, pdfFile)
                        startActivity(Intent.createChooser(share, "Share PDF Drive Report"))
                    }
                }
                ExportAction.SAVE_CSV -> {
                    val csvFile = CsvExporter.exportSessionToCsv(this@MainActivity, target, measurements)
                    pendingCsvFileToSave = csvFile
                    withContext(Dispatchers.Main) {
                        createCsvLauncher.launch(csvFile.name)
                    }
                }
                ExportAction.SHARE_CSV -> {
                    val csvFile = CsvExporter.exportSessionToCsv(this@MainActivity, target, measurements)
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
                    val pdfFile = PdfReportExporter.generateSessionPdfReport(this@MainActivity, target, measurements, continuityEvents, serviceHoles, callEvents)
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
                        .setPositiveButton("View Analytics") { _, _ ->
                            activeSessionToInspect = session
                            refreshAnalytics()
                            binding.bottomNav.selectedItemId = R.id.nav_analytics
                        }
                        .setNeutralButton("Export Reports") { _, _ ->
                            activeSessionToInspect = session
                            binding.bottomNav.selectedItemId = R.id.nav_manage
                            binding.tabLayoutManage.getTabAt(1)?.select()
                        }
                        .setNegativeButton("Close", null)
                        .show()
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to display survey summary dialog", e)
                }
            }
        }
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
    }

    override fun onResume() {
        super.onResume()
        binding.driveMapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.driveMapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        binding.driveMapView.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.driveMapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.driveMapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.driveMapView.onLowMemory()
    }
}
