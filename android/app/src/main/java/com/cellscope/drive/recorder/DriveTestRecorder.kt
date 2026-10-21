package com.cellscope.drive.recorder

import android.content.Context
import android.util.Log
import com.cellscope.drive.analytics.CallDropRiskEngine
import com.cellscope.drive.analytics.NetworkContinuityAnalyzer
import com.cellscope.drive.analytics.ServiceHoleDetector
import com.cellscope.drive.data.db.CellScopeDatabase
import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.data.entity.NetworkContinuityEventEntity
import com.cellscope.drive.location.LocationTracker
import com.cellscope.drive.model.GpsData
import com.cellscope.drive.model.LteMetrics
import com.cellscope.drive.model.NetworkGeneration
import com.cellscope.drive.model.RecordingState
import com.cellscope.drive.telephony.NetworkQoeScheduler
import com.cellscope.drive.telephony.TelephonyCollector
import com.cellscope.drive.util.TelecomTimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DriveTestRecorder private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database = CellScopeDatabase.getDatabase(context)
    private val sessionDao = database.sessionDao()
    private val measurementDao = database.measurementDao()
    private val transitionDao = database.transitionDao()
    private val continuityDao = database.continuityDao()
    private val callReliabilityDao = database.callReliabilityDao()

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _currentSession = MutableStateFlow<DriveSession?>(null)
    val currentSession: StateFlow<DriveSession?> = _currentSession.asStateFlow()

    private val _sampleCount = MutableStateFlow(0)
    val sampleCount: StateFlow<Int> = _sampleCount.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _latestGps = MutableStateFlow(GpsData())
    val latestGps: StateFlow<GpsData> = _latestGps.asStateFlow()

    private val _latestLte = MutableStateFlow(LteMetrics())
    val latestLte: StateFlow<LteMetrics> = _latestLte.asStateFlow()

    val locationTracker = LocationTracker(context) { gps ->
        _latestGps.value = gps
    }

    val telephonyCollector = TelephonyCollector(context) { lte ->
        _latestLte.value = lte
    }

    private var sampleJob: Job? = null
    private var timerJob: Job? = null
    private var lastRecordedPci: Int? = null
    private var lastRecordedRsrp: Int? = null
    private var lastRecordedTac: Int? = null
    private var lastRecordedRat: String? = null
    private var lastRecordedChannel: Int? = null
    private var lastRecordedCellId: Int? = null
    private var lastRecordedGen: NetworkGeneration? = null
    private var lastRecordedTech: String? = null
    private var lastCallState: String? = null
    private var maxRecordedSpeedKmh: Float = 0f
    private var rsrpSum: Long = 0L
    private var rsrpCount: Int = 0

    var samplingIntervalMs: Long = 1000L

    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    private val _recoveredOrphanedSession = MutableStateFlow<DriveSession?>(null)
    val recoveredOrphanedSession: StateFlow<DriveSession?> = _recoveredOrphanedSession.asStateFlow()

    private val isStopping = java.util.concurrent.atomic.AtomicBoolean(false)

    fun checkAndRecoverActiveSession() {
        if (_recordingState.value != RecordingState.IDLE) return
        scope.launch(Dispatchers.IO) {
            val unfinalized = sessionDao.getActiveSession()
            if (unfinalized != null) {
                val latestMeasurement = measurementDao.getLatestForSession(unfinalized.id)
                val count = measurementDao.getCountForSession(unfinalized.id)
                val finishTime = latestMeasurement?.timestampMs ?: unfinalized.startTimeMs

                unfinalized.endTimeMs = finishTime
                unfinalized.sampleCount = count
                sessionDao.updateSession(unfinalized)
                Log.i("DriveTestRecorder", "Recovered unfinalized session: ${unfinalized.sessionName} (id=${unfinalized.id}) with $count samples")
                _recoveredOrphanedSession.value = unfinalized
            }
        }
    }

    fun clearRecoveredSession() {
        _recoveredOrphanedSession.value = null
    }

    fun startRecording(customSessionName: String? = null) {
        if (_recordingState.value == RecordingState.RECORDING) return

        scope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val name = customSessionName ?: "Survey_${TelecomTimeUtils.formatBdtFilename(now)}"
            val session = DriveSession(
                sessionName = name,
                startTimeMs = now
            )
            val sessionId = sessionDao.insertSession(session)
            val created = session.copy(id = sessionId)

            _currentSession.value = created
            _sampleCount.value = 0
            _elapsedSeconds.value = 0L
            _recordingState.value = RecordingState.RECORDING
            lastRecordedPci = null
            lastRecordedRsrp = null
            lastRecordedTac = null
            lastRecordedRat = null
            lastRecordedChannel = null
            lastRecordedCellId = null
            lastRecordedGen = null
            lastRecordedTech = null
            lastCallState = null
            maxRecordedSpeedKmh = 0f
            rsrpSum = 0L
            rsrpCount = 0

            locationTracker.resetDistance()
            locationTracker.startTracking()
            startSamplingLoop(sessionId)
            startTimerLoop()
            NetworkQoeScheduler.getInstance(context).startMonitoringForSession(sessionId, created.sessionName)
        }
    }

    fun pauseRecording() {
        if (_recordingState.value != RecordingState.RECORDING) return
        _recordingState.value = RecordingState.PAUSED
        locationTracker.pauseTracking()
        sampleJob?.cancel()
        timerJob?.cancel()
        NetworkQoeScheduler.getInstance(context).pauseMonitoring()
    }

    fun resumeRecording() {
        if (_recordingState.value != RecordingState.PAUSED) return
        val current = _currentSession.value ?: return
        _recordingState.value = RecordingState.RECORDING
        locationTracker.resumeTracking()
        startSamplingLoop(current.id)
        startTimerLoop()
        NetworkQoeScheduler.getInstance(context).resumeMonitoring()
    }

    fun stopRecording(onFinished: ((DriveSession?) -> Unit)? = null) {
        if (_recordingState.value == RecordingState.IDLE) return
        if (!isStopping.compareAndSet(false, true)) return

        sampleJob?.cancel()
        timerJob?.cancel()
        locationTracker.stopTracking()
        NetworkQoeScheduler.getInstance(context).stopMonitoring()

        scope.launch(Dispatchers.IO) {
            val finalizedSession: DriveSession? = try {
                val current = _currentSession.value
                if (current != null) {
                    val finishTime = System.currentTimeMillis()
                    current.endTimeMs = finishTime
                    current.sampleCount = _sampleCount.value
                    val filteredDist = locationTracker.cumulativeDistanceMeters
                    val filteredMaxSpeed = locationTracker.maxFilteredSpeedKmh.coerceAtLeast(maxRecordedSpeedKmh)
                    val isStationarySession = locationTracker.isStationary || filteredDist < 15.0

                    current.totalDistanceM = if (isStationarySession && filteredDist < 15.0) 0.0 else filteredDist
                    current.maxSpeedKmh = if (isStationarySession && filteredDist < 15.0) 0f else filteredMaxSpeed
                    current.avgRsrpDbm = if (rsrpCount > 0) rsrpSum.toDouble() / rsrpCount else null

                    // Compute and store session-level QoE KPIs
                    val measurements = measurementDao.getMeasurementsForSession(current.id)
                    val activeTests = database.qoeDao().getTestsForSession(current.id)
                    val qoeReport = com.cellscope.drive.analytics.qoe.QoeAnalyzer.analyzeSessionQoe(
                        current,
                        measurements,
                        activeTests
                    )
                    current.gpsAvailabilityPct = qoeReport.gpsAvailabilityPct.toFloat()
                    current.radioAvailabilityPct = qoeReport.radioAvailabilityPct.toFloat()
                    current.avgLatencyMs = qoeReport.activeQoeSummary?.avgLatencyMs
                    current.downloadThroughputMbps = qoeReport.activeQoeSummary?.avgDownloadThroughputMbps
                    current.uploadThroughputMbps = qoeReport.activeQoeSummary?.avgUploadThroughputMbps

                    // Reconcile and persist service holes
                    val continuitySummary = NetworkContinuityAnalyzer.analyzeSessionContinuity(current.id, measurements)
                    val holeSummary = ServiceHoleDetector.detectHoles(
                        sessionId = current.id,
                        measurements = measurements,
                        activeTests = activeTests,
                        continuityEvents = continuitySummary.continuityEvents
                    )
                    continuityDao.insertServiceHoles(holeSummary.holes)

                    sessionDao.updateSession(current)
                    current
                } else null
            } catch (e: Exception) {
                Log.e("DriveTestRecorder", "Error finalizing survey session", e)
                _currentSession.value
            } finally {
                _recordingState.value = RecordingState.IDLE
                _elapsedSeconds.value = 0L
                _currentSession.value = null
                isStopping.set(false)
            }

            withContext(Dispatchers.Main) {
                onFinished?.invoke(finalizedSession)
            }
        }
    }

    private fun startTimerLoop() {
        timerJob?.cancel()
        timerJob = scope.launch(Dispatchers.Default) {
            while (isActive && _recordingState.value == RecordingState.RECORDING) {
                delay(1000L)
                _elapsedSeconds.value += 1L
            }
        }
    }

    private fun startSamplingLoop(sessionId: Long) {
        sampleJob?.cancel()
        sampleJob = scope.launch(Dispatchers.IO) {
            while (isActive && _recordingState.value == RecordingState.RECORDING) {
                try {
                    // Sample cellular telephony
                    val metrics = telephonyCollector.sampleCurrentMetrics()
                    val gps = locationTracker.latestGpsData

                    // Update max speed from quality-filtered observations when genuine movement is confirmed
                    if (!gps.isStationary && gps.speedKmh != null && gps.speedKmh > maxRecordedSpeedKmh) {
                        maxRecordedSpeedKmh = gps.speedKmh
                    }

                    // Accumulate RSRP
                    if (metrics.rsrpDbm != null) {
                        rsrpSum += metrics.rsrpDbm
                        rsrpCount++
                    }

                    val now = System.currentTimeMillis()
                    val isoTimestamp = TelecomTimeUtils.formatIsoUtc(now)

                    // 1. Detect Handover / Cell / RAT Transition
                    var hoTrigger = 0
                    var transType: String? = null
                    var transDetails: String? = null
                    var confidence = 0.80f

                    val currentRat = metrics.servingRat ?: metrics.networkGen.code
                    val currentChannel = metrics.channelNumber ?: metrics.earfcn ?: lastRecordedChannel
                    val currentCellId = metrics.eci ?: lastRecordedCellId
                    val currentPci = metrics.servingPci
                    val currentRsrp = metrics.rsrpDbm ?: metrics.primarySignalDbm
                    val currentTac = metrics.tac ?: lastRecordedTac

                    val hasPrevious = (lastRecordedRat != null || lastRecordedPci != null || lastRecordedCellId != null)

                    if (hasPrevious) {
                        val isStationary = gps.isStationary || (gps.speedKmh ?: 0f) < 2.5f
                        val isSignalDrop = (lastRecordedRsrp ?: -90) < -115
                        val isTacChanged = lastRecordedTac != null && currentTac != null && lastRecordedTac != currentTac
                        val isIntraFreq = lastRecordedChannel != null && currentChannel != null && lastRecordedChannel == currentChannel
                        val isInterFreq = lastRecordedChannel != null && currentChannel != null && lastRecordedChannel != currentChannel
                        val isMoving = (gps.speedKmh ?: 0f) >= 2.5f && !gps.isStationary
                        // A. Inter-RAT Transition (e.g. 4G -> 2G, 2G -> 4G, 4G -> 3G, 5G -> 4G)
                        if (lastRecordedRat != null && currentRat != null && lastRecordedRat != currentRat) {
                            hoTrigger = 0
                            transType = "INTER_RAT"
                            confidence = 0.95f
                            transDetails = "Inter-RAT transition: $lastRecordedRat -> $currentRat (Ch: $lastRecordedChannel -> $currentChannel)"
                        }
                        // B. Physical Serving Cell Change (PCI changed)
                        else if (lastRecordedPci != null && currentPci != null && lastRecordedPci != currentPci) {
                            hoTrigger = 0
                            val tacNote = if (isTacChanged) " (TAC $lastRecordedTac -> $currentTac)" else ""
                            if (isSignalDrop) {
                                transType = "SIGNAL_LOSS"
                                confidence = 0.85f
                                transDetails = "Signal collapse (${lastRecordedRsrp ?: -120} dBm) before transition to PCI $currentPci$tacNote"
                            } else if (isStationary) {
                                transType = "CELL_RESELECTION"
                                confidence = 0.85f
                                transDetails = "Stationary cell reselection on $currentRat: PCI $lastRecordedPci -> $currentPci$tacNote"
                            } else if (isInterFreq) {
                                transType = "INTER_FREQ"
                                confidence = 0.60f
                                transDetails = "Inter-frequency transition on $currentRat: Ch $lastRecordedChannel -> $currentChannel (PCI: $lastRecordedPci -> $currentPci)$tacNote"
                            } else if (isIntraFreq) {
                                transType = "INTRA_FREQ"
                                confidence = 0.60f
                                val chStr = currentChannel?.let { "Ch $it" } ?: "Carrier"
                                transDetails = "Intra-frequency transition on $currentRat: $chStr (PCI: $lastRecordedPci -> $currentPci)$tacNote"
                            } else {
                                transType = "OBSERVED_TRANSITION"
                                confidence = 0.50f
                                transDetails = "Observed cell change on $currentRat: PCI $lastRecordedPci -> $currentPci$tacNote"
                            }
                        }
                        // C. Inter-Frequency Transition (Channel changed on same RAT without PCI change or when PCI null)
                        else if (lastRecordedChannel != null && currentChannel != null && lastRecordedChannel != currentChannel) {
                            hoTrigger = 0
                            transType = "INTER_FREQ"
                            confidence = 0.60f
                            transDetails = "Inter-frequency transition on $currentRat: Ch $lastRecordedChannel -> $currentChannel (PCI: ${lastRecordedPci ?: currentPci ?: "?"})"
                        }
                        // D. 2G Cell Reselection / Handover (where PCI is null, but Cell ID changed)
                        else if (currentRat == "2G" && lastRecordedCellId != null && currentCellId != null && lastRecordedCellId != currentCellId) {
                            hoTrigger = 1
                            transType = if (isStationary) "CELL_RESELECTION" else "INTRA_RAT"
                            confidence = 0.85f
                            transDetails = "2G Cell reselection: Cell ID $lastRecordedCellId -> $currentCellId"
                        }
                    }

                    if (hoTrigger == 1 && transType != null) {
                        transitionDao.insertTransition(
                            CellTransitionEntity(
                                sessionId = sessionId,
                                timestampIso = isoTimestamp,
                                timestampMs = now,
                                latitude = if (gps.hasFix && gps.isValid) gps.latitude else null,
                                longitude = if (gps.hasFix && gps.isValid) gps.longitude else null,
                                fromPci = lastRecordedPci,
                                toPci = currentPci,
                                fromRsrp = lastRecordedRsrp,
                                toRsrp = currentRsrp,
                                fromTac = lastRecordedTac,
                                toTac = currentTac,
                                eventType = transType,
                                confidenceScore = confidence,
                                details = transDetails ?: "Transition on $currentRat",
                                fromRat = lastRecordedRat,
                                toRat = currentRat,
                                fromChannel = lastRecordedChannel,
                                toChannel = currentChannel,
                                fromCellId = lastRecordedCellId,
                                toCellId = currentCellId
                            )
                        )
                        Log.i("DriveTestRecorder", "Cell transition: $transType | $transDetails")
                    }

                    // Always update last recorded metrics (never conditional on servingPci != null)
                    if (currentRat != null || currentPci != null || currentCellId != null) {
                        lastRecordedPci = currentPci ?: lastRecordedPci
                        lastRecordedRsrp = currentRsrp ?: lastRecordedRsrp
                        lastRecordedTac = currentTac ?: lastRecordedTac
                        lastRecordedRat = currentRat ?: lastRecordedRat
                        lastRecordedChannel = currentChannel ?: lastRecordedChannel
                        lastRecordedCellId = currentCellId ?: lastRecordedCellId
                    }

                    // 2. Detect Generation continuity transition (e.g. 5G -> 4G -> 3G -> 2G or Out of Service)
                    if (lastRecordedGen != null && lastRecordedGen != metrics.networkGen) {
                        val category = when {
                            metrics.networkGen == NetworkGeneration.OUT_OF_SERVICE -> "OUT_OF_SERVICE"
                            lastRecordedGen == NetworkGeneration.OUT_OF_SERVICE && metrics.networkGen.isCellularService -> "RECOVERY"
                            lastRecordedGen!!.generationRank > metrics.networkGen.generationRank -> "DOWNGRADE"
                            lastRecordedGen!!.generationRank < metrics.networkGen.generationRank -> "UPGRADE"
                            else -> "GENERATION_CHANGE"
                        }
                        val evidenceClass = when {
                            category == "OUT_OF_SERVICE" -> "CONFIRMED_OUT_OF_SERVICE"
                            category == "RECOVERY" -> "SERVICE_RECOVERY"
                            category == "DOWNGRADE" && (lastRecordedRsrp ?: 0) < -115 -> "COVERAGE_HOLE_DOWNGRADE"
                            category == "DOWNGRADE" && (lastRecordedRsrp ?: -100) >= -95 -> "Network generation change — cause unconfirmed"
                            else -> "CELLULAR_RESELECTION"
                        }
                        continuityDao.insertContinuityEvent(
                            NetworkContinuityEventEntity(
                                sessionId = sessionId,
                                timestampIso = isoTimestamp,
                                timestampMs = now,
                                latitude = if (gps.hasFix && gps.isValid) gps.latitude else null,
                                longitude = if (gps.hasFix && gps.isValid) gps.longitude else null,
                                accuracyM = gps.accuracyM,
                                fromGeneration = lastRecordedGen!!.code,
                                toGeneration = metrics.networkGen.code,
                                fromTech = lastRecordedTech,
                                toTech = metrics.dataNetworkType,
                                transitionCategory = category,
                                fromSignalDbm = lastRecordedRsrp,
                                toSignalDbm = metrics.rsrpDbm,
                                durationSeconds = null,
                                dataConnectivityState = metrics.dataState,
                                evidenceClassification = evidenceClass,
                                details = "Generation transition from ${lastRecordedGen!!.code} to ${metrics.networkGen.code} ($category)"
                            )
                        )
                    }
                    lastRecordedGen = metrics.networkGen
                    lastRecordedTech = metrics.dataNetworkType

                    // 3. Serialize neighbor cells
                    val neighborsJson = if (metrics.neighborCells.isNotEmpty()) {
                        metrics.neighborCells.joinToString(";") { cell ->
                            "${cell.pci}:${cell.rsrpDbm ?: ""}:${cell.rsrqDb ?: ""}:${cell.earfcn ?: ""}"
                        }
                    } else null

                    val record = LteMeasurementEntity(
                        sessionId = sessionId,
                        timestampIso = isoTimestamp,
                        timestampMs = now,
                        latitude = if (gps.hasFix && gps.isValid) gps.latitude else null,
                        longitude = if (gps.hasFix && gps.isValid) gps.longitude else null,
                        altitudeM = gps.altitudeM,
                        accuracyM = gps.accuracyM,
                        speedKmh = gps.speedKmh,
                        networkType = metrics.networkType,
                        operatorAlpha = metrics.operatorAlpha,
                        operatorNumeric = metrics.operatorNumeric,
                        servingPci = metrics.servingPci,
                        tac = metrics.tac,
                        earfcn = metrics.earfcn,
                        bandwidthKhz = metrics.bandwidthKhz,
                        rsrpDbm = metrics.rsrpDbm,
                        rsrqDb = metrics.rsrqDb,
                        rssiDbm = metrics.rssiDbm,
                        cqi = metrics.cqi,
                        timingAdvance = metrics.timingAdvance,
                        isServing = metrics.isServing,
                        handoverTrigger = hoTrigger,
                        bearing = gps.bearing,
                        sinrDb = metrics.sinrDb,
                        eci = metrics.eci,
                        neighborCellsJson = neighborsJson,
                        transitionType = transType,
                        isGpsValid = gps.isValid,
                        nci = metrics.nci,
                        nrArfcn = metrics.nrArfcn,
                        csiRsrp = metrics.csiRsrpDbm,
                        csiSinr = metrics.csiSinrDb,
                        dataNetworkType = metrics.dataNetworkType,
                        voiceNetworkType = metrics.voiceNetworkType,
                        networkGen = metrics.networkGen.code,
                        serviceState = metrics.serviceState,
                        dataActivityState = metrics.dataState,
                        headingDeg = gps.deviceHeadingDeg,
                        magneticHeadingDeg = gps.magneticHeadingDeg,
                        headingAccuracyDeg = gps.headingAccuracyDeg,
                        orientationSensorType = gps.orientationSensorType,
                        headingValid = gps.isHeadingCalibrated,
                        servingRat = metrics.servingRat,
                        channelNumber = metrics.channelNumber,
                        channelType = metrics.channelType,
                        primarySignalDbm = metrics.primarySignalDbm,
                        signalMetricType = metrics.signalMetricType,
                        secondarySignalDb = metrics.secondarySignalDb,
                        sinrSource = metrics.sinrSource,
                        estimationMethod = metrics.estimationMethod,
                        estimationAssumptions = metrics.estimationAssumptions,
                        sinrConfidence = metrics.sinrConfidence,
                        qualitativeDiagnostic = metrics.qualitativeDiagnostic,
                        impliedRssiDbm = metrics.impliedRssiDbm
                    )

                    // 4. Detect Call State transition
                    if (lastCallState != null && lastCallState != metrics.callState) {
                        val callEvent = CallDropRiskEngine.classifyCallEvent(
                            sessionId = sessionId,
                            timestampIso = isoTimestamp,
                            timestampMs = now,
                            previousCallState = lastCallState!!,
                            newCallState = metrics.callState,
                            currentMeasurement = record
                        )
                        callReliabilityDao.insertCallEvent(callEvent)
                    }
                    lastCallState = metrics.callState

                    measurementDao.insertMeasurement(record)
                    _sampleCount.value += 1
                } catch (e: Exception) {
                    Log.e("DriveTestRecorder", "Error sampling drive test: ${e.message}")
                }

                delay(samplingIntervalMs)
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: DriveTestRecorder? = null

        fun getInstance(context: Context): DriveTestRecorder {
            return INSTANCE ?: synchronized(this) {
                val instance = DriveTestRecorder(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
