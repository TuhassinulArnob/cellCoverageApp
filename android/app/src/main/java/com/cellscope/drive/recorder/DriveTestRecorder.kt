package com.cellscope.drive.recorder

import android.content.Context
import android.util.Log
import com.cellscope.drive.data.db.CellScopeDatabase
import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.location.LocationTracker
import com.cellscope.drive.model.GpsData
import com.cellscope.drive.model.LteMetrics
import com.cellscope.drive.model.RecordingState
import com.cellscope.drive.telephony.TelephonyCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class DriveTestRecorder(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    val database = CellScopeDatabase.getDatabase(context)
    private val sessionDao = database.sessionDao()
    private val measurementDao = database.measurementDao()
    private val transitionDao = database.transitionDao()

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _currentSession = MutableStateFlow<DriveSession?>(null)
    val currentSession: StateFlow<DriveSession?> = _currentSession.asStateFlow()

    private val _sampleCount = MutableStateFlow(0)
    val sampleCount: StateFlow<Int> = _sampleCount.asStateFlow()

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
    private var lastRecordedPci: Int? = null
    private var lastRecordedRsrp: Int? = null
    private var lastRecordedTac: Int? = null
    private var maxRecordedSpeedKmh: Float = 0f
    private var rsrpSum: Long = 0L
    private var rsrpCount: Int = 0

    var samplingIntervalMs: Long = 1000L

    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    fun startRecording(customSessionName: String? = null) {
        if (_recordingState.value == RecordingState.RECORDING) return

        scope.launch {
            val now = System.currentTimeMillis()
            val name = customSessionName ?: "Drive_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))}"
            val session = DriveSession(
                sessionName = name,
                startTimeMs = now
            )
            val sessionId = sessionDao.insertSession(session)
            val created = session.copy(id = sessionId)

            _currentSession.value = created
            _sampleCount.value = 0
            _recordingState.value = RecordingState.RECORDING
            lastRecordedPci = null
            lastRecordedRsrp = null
            lastRecordedTac = null
            maxRecordedSpeedKmh = 0f
            rsrpSum = 0L
            rsrpCount = 0

            locationTracker.resetDistance()
            locationTracker.startTracking()
            startSamplingLoop(sessionId)
        }
    }

    fun pauseRecording() {
        if (_recordingState.value != RecordingState.RECORDING) return
        _recordingState.value = RecordingState.PAUSED
        sampleJob?.cancel()
    }

    fun resumeRecording() {
        if (_recordingState.value != RecordingState.PAUSED) return
        val current = _currentSession.value ?: return
        _recordingState.value = RecordingState.RECORDING
        startSamplingLoop(current.id)
    }

    fun stopRecording(onFinished: ((DriveSession?) -> Unit)? = null) {
        if (_recordingState.value == RecordingState.IDLE) return

        sampleJob?.cancel()
        locationTracker.stopTracking()

        scope.launch {
            val current = _currentSession.value
            if (current != null) {
                current.endTimeMs = System.currentTimeMillis()
                current.sampleCount = _sampleCount.value
                current.totalDistanceM = locationTracker.latestGpsData.cumulativeDistanceM
                current.maxSpeedKmh = maxRecordedSpeedKmh
                current.avgRsrpDbm = if (rsrpCount > 0) rsrpSum.toDouble() / rsrpCount else null
                sessionDao.updateSession(current)
            }

            _recordingState.value = RecordingState.IDLE
            val finished = _currentSession.value
            _currentSession.value = null
            onFinished?.invoke(finished)
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

                    // Update max speed
                    if (gps.speedKmh != null && gps.speedKmh > maxRecordedSpeedKmh) {
                        maxRecordedSpeedKmh = gps.speedKmh
                    }

                    // Accumulate RSRP
                    if (metrics.rsrpDbm != null) {
                        rsrpSum += metrics.rsrpDbm
                        rsrpCount++
                    }

                    // Detect PCI transition (Serving cell handover trigger indication)
                    var hoTrigger = 0
                    var transType: String? = null

                    if (metrics.servingPci != null) {
                        if (lastRecordedPci != null && lastRecordedPci != metrics.servingPci) {
                            hoTrigger = 1
                            val isStationary = (gps.speedKmh ?: 0f) < 4.0f
                            val isSignalDrop = (lastRecordedRsrp ?: -90) < -115

                            transType = when {
                                isSignalDrop -> "SIGNAL_LOSS"
                                isStationary -> "CELL_RESELECTION"
                                lastRecordedTac != null && metrics.tac != null && lastRecordedTac != metrics.tac -> "CONFIRMED_HANDOVER"
                                else -> "OBSERVED_TRANSITION"
                            }

                            val now = System.currentTimeMillis()
                            val isoTimestamp = isoDateFormat.format(Date(now))

                            // Persist transition event
                            transitionDao.insertTransition(
                                CellTransitionEntity(
                                    sessionId = sessionId,
                                    timestampIso = isoTimestamp,
                                    timestampMs = now,
                                    latitude = if (gps.hasFix && gps.isValid) gps.latitude else null,
                                    longitude = if (gps.hasFix && gps.isValid) gps.longitude else null,
                                    fromPci = lastRecordedPci,
                                    toPci = metrics.servingPci,
                                    fromRsrp = lastRecordedRsrp,
                                    toRsrp = metrics.rsrpDbm,
                                    fromTac = lastRecordedTac,
                                    toTac = metrics.tac,
                                    eventType = transType,
                                    confidenceScore = if (transType == "CONFIRMED_HANDOVER") 0.95f else 0.75f,
                                    details = "Transition from PCI $lastRecordedPci to PCI ${metrics.servingPci}"
                                )
                            )
                            Log.i("DriveTestRecorder", "Cell transition: $lastRecordedPci -> ${metrics.servingPci} ($transType)")
                        }
                        lastRecordedPci = metrics.servingPci
                        lastRecordedRsrp = metrics.rsrpDbm
                        lastRecordedTac = metrics.tac
                    }

                    val now = System.currentTimeMillis()
                    val isoTimestamp = isoDateFormat.format(Date(now))

                    // Serialize neighbor cells
                    val neighborsJson = if (metrics.neighborCells.isNotEmpty()) {
                        metrics.neighborCells.joinToString(";") { cell ->
                            "${cell.pci}:${cell.rsrpDbm ?: ""}:${cell.rsrqDb ?: ""}:${cell.earfcn ?: ""}"
                        }
                    } else null

                    val record = LteMeasurementEntity(
                        sessionId = sessionId,
                        timestampIso = isoTimestamp,
                        timestampMs = now,
                        latitude = if (gps.hasFix) gps.latitude else null,
                        longitude = if (gps.hasFix) gps.longitude else null,
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
                        isGpsValid = gps.isValid
                    )

                    measurementDao.insertMeasurement(record)
                    _sampleCount.value += 1
                } catch (e: Exception) {
                    Log.e("DriveTestRecorder", "Error sampling drive test: ${e.message}")
                }

                delay(samplingIntervalMs)
            }
        }
    }
}
