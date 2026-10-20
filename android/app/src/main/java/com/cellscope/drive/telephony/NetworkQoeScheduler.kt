package com.cellscope.drive.telephony

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.cellscope.drive.analytics.CongestionEvidenceEngine
import com.cellscope.drive.analytics.CongestionEvidenceResult
import com.cellscope.drive.analytics.EvidenceClassification
import com.cellscope.drive.data.db.CellScopeDatabase
import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.recorder.DriveTestRecorder
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standard lifecycle states for the Active Network QoE Scheduler.
 */
enum class SchedulerState(val label: String) {
    IDLE("Idle"),
    WAITING("Waiting"),
    RUNNING("Running"),
    SKIPPED("Skipped"),
    FAILED("Failed"),
    COMPLETED("Completed")
}

/**
 * Scheduled and on-demand Active Network Performance Measurement Scheduler.
 *
 * Implements bounded, non-continuous 5-minute / 10-minute periodic testing,
 * real HTTP byte streaming, multi-probe RTT/jitter/loss, baseline accumulation,
 * and user-configurable data/battery budget enforcement without arbitrary hardcoded caps.
 */
class NetworkQoeScheduler private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val database = CellScopeDatabase.getDatabase(context)
    private val qoeDao = database.qoeDao()
    private val testMutex = Mutex()

    private var scheduledJob: Job? = null

    // Configuration
    var intervalMinutes: Int = 10 // Default: 10 minutes
    var isAutoTestingEnabled: Boolean = true
    var dailyBudgetMb: Double? = null // Optional! null = unlimited by default
    var warningThresholdMb: Double? = 100.0 // Warning before enabling repeated high-volume mobile testing
    var downloadBytesLimitPerTest: Long = 1024 * 1024L // 1 MB cap per test
    var uploadBytesLimitPerTest: Long = 131072L // 128 KB cap per test
    var pingEndpointUrl: String = "https://connectivitycheck.gstatic.com/generate_204"
    var downloadEndpointUrl: String = "https://speed.cloudflare.com/__down?bytes=524288" // 512 KB
    var uploadEndpointUrl: String = "https://speed.cloudflare.com/__up"
    var timeoutMs: Int = 5000

    // Observables
    private val _schedulerState = MutableStateFlow(SchedulerState.IDLE)
    val schedulerState: StateFlow<SchedulerState> = _schedulerState.asStateFlow()

    private val _lastSkipReason = MutableStateFlow<String?>(null)
    val lastSkipReason: StateFlow<String?> = _lastSkipReason.asStateFlow()

    private val _isMonitoringActive = MutableStateFlow(false)
    val isMonitoringActive: StateFlow<Boolean> = _isMonitoringActive.asStateFlow()

    private val _isTestRunning = MutableStateFlow(false)
    val isTestRunning: StateFlow<Boolean> = _isTestRunning.asStateFlow()

    private val _nextScheduledRunMs = MutableStateFlow<Long?>(null)
    val nextScheduledRunMs: StateFlow<Long?> = _nextScheduledRunMs.asStateFlow()

    private val _lastCompletedTest = MutableStateFlow<ActiveQoeTestEntity?>(null)
    val lastCompletedTest: StateFlow<ActiveQoeTestEntity?> = _lastCompletedTest.asStateFlow()

    private val _lastClassification = MutableStateFlow<CongestionEvidenceResult?>(null)
    val lastClassification: StateFlow<CongestionEvidenceResult?> = _lastClassification.asStateFlow()

    private val _dataUsedTodayBytes = MutableStateFlow(0L)
    val dataUsedTodayBytes: StateFlow<Long> = _dataUsedTodayBytes.asStateFlow()

    private val _sessionDataUsedBytes = MutableStateFlow(0L)
    val sessionDataUsedBytes: StateFlow<Long> = _sessionDataUsedBytes.asStateFlow()

    private var activeSessionId: Long? = null
    private var activeSessionName: String? = null

    /**
     * Automatically called when a survey recording starts.
     * Enforces exactly one scheduled worker per session.
     */
    fun startMonitoringForSession(sessionId: Long, sessionName: String? = null, intervalMins: Int = intervalMinutes) {
        if (!isAutoTestingEnabled) {
            Log.i("NetworkQoeScheduler", "Automatic QoE testing disabled in user settings.")
            return
        }

        // Cancel previous schedule if any
        scheduledJob?.cancel()
        scheduledJob = null

        this.activeSessionId = sessionId
        this.activeSessionName = sessionName
        this.intervalMinutes = intervalMins
        _sessionDataUsedBytes.value = 0L
        _isMonitoringActive.value = true
        _schedulerState.value = SchedulerState.WAITING
        _lastSkipReason.value = null

        // First test runs promptly after survey recording begins (2.5s initial delay)
        val firstRunMs = System.currentTimeMillis() + 2500L
        _nextScheduledRunMs.value = firstRunMs
        startLoop()
    }

    fun startMonitoring(intervalMins: Int = intervalMinutes) {
        val recorder = DriveTestRecorder.getInstance(context)
        val session = recorder.currentSession.value
        val sid = session?.id ?: activeSessionId ?: 1L
        startMonitoringForSession(sid, session?.sessionName, intervalMins)
    }

    fun stopMonitoring() {
        _isMonitoringActive.value = false
        _nextScheduledRunMs.value = null
        _schedulerState.value = SchedulerState.IDLE
        scheduledJob?.cancel()
        scheduledJob = null
        activeSessionId = null
        activeSessionName = null
    }

    fun pauseMonitoring() {
        _isMonitoringActive.value = false
        scheduledJob?.cancel()
        scheduledJob = null
        if (_schedulerState.value == SchedulerState.WAITING || _schedulerState.value == SchedulerState.RUNNING) {
            _schedulerState.value = SchedulerState.IDLE
        }
    }

    fun resumeMonitoring() {
        if (activeSessionId != null && isAutoTestingEnabled && !_isMonitoringActive.value) {
            _isMonitoringActive.value = true
            _schedulerState.value = SchedulerState.WAITING
            _nextScheduledRunMs.value = System.currentTimeMillis() + 1500L
            startLoop()
        }
    }

    private fun startLoop() {
        scheduledJob?.cancel()
        scheduledJob = scope.launch {
            while (isActive && _isMonitoringActive.value) {
                val nextMs = _nextScheduledRunMs.value ?: (System.currentTimeMillis() + intervalMinutes * 60 * 1000L)
                val delayTime = (nextMs - System.currentTimeMillis()).coerceAtLeast(0L)
                delay(delayTime)

                if (!isActive || !_isMonitoringActive.value) break

                try {
                    executeActiveMeasurementCycle("SCHEDULED")
                } catch (e: Exception) {
                    Log.e("NetworkQoeScheduler", "Scheduled test execution failure", e)
                }

                // Schedule next interval
                val nextRun = System.currentTimeMillis() + intervalMinutes * 60 * 1000L
                _nextScheduledRunMs.value = nextRun
                if (_isMonitoringActive.value && _schedulerState.value != SchedulerState.RUNNING) {
                    _schedulerState.value = SchedulerState.WAITING
                }
            }
        }
    }

    /**
     * Executes an immediate, on-demand active network QoE test.
     */
    suspend fun runTestNow(triggerSource: String = "MANUAL"): ActiveQoeTestEntity? = withContext(Dispatchers.Default) {
        if (_isTestRunning.value) {
            Log.w("NetworkQoeScheduler", "Test already in progress; skipping duplicate run.")
            return@withContext null
        }
        executeActiveMeasurementCycle(triggerSource)
    }

    private suspend fun executeActiveMeasurementCycle(triggerSource: String): ActiveQoeTestEntity? = withContext(Dispatchers.IO) {
        if (!testMutex.tryLock()) {
            Log.w("NetworkQoeScheduler", "Concurrent execution prevented; test already running.")
            return@withContext null
        }

        val startWallMs = System.currentTimeMillis()
        val startIso = TelecomTimeUtils.formatIsoUtc(startWallMs)

        // 1. Snapshot current radio and position context from recorder
        val recorder = DriveTestRecorder.getInstance(context)
        val session = recorder.currentSession.value
        val sessionId = session?.id ?: activeSessionId ?: 1L
        val gps = recorder.latestGps.value
        val lat = if (gps.isValid) gps.latitude else null
        val lon = if (gps.isValid) gps.longitude else null
        val accuracy = if (gps.isValid) gps.accuracyM else null

        val telephony = recorder.telephonyCollector
        val metrics = telephony.latestMetrics
        val rat = metrics.networkGen.label
        val pci = metrics.servingPci
        val earfcn = metrics.earfcn
        val rsrp = metrics.rsrpDbm
        val rsrq = metrics.rsrqDb
        val sinr = metrics.sinrDb
        val operator = metrics.operatorAlpha
        val opIdentity = telephony.getOperatorIdentity()
        val activeSimOp = opIdentity.simOperatorName
        val simSlot = opIdentity.simSlotIndex

        // 2. Check Network Connectivity / Online status
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNet = connectivityManager?.activeNetwork
        val caps = connectivityManager?.getNetworkCapabilities(activeNet)
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        if (!hasInternet) {
            val skipReason = "Network disconnected / No internet capability"
            _lastSkipReason.value = skipReason
            _schedulerState.value = SchedulerState.SKIPPED
            Log.w("NetworkQoeScheduler", "Active test skipped: $skipReason")

            val skippedEntity = ActiveQoeTestEntity(
                sessionId = sessionId,
                timestampIso = startIso,
                timestampMs = startWallMs,
                latitude = lat,
                longitude = lon,
                testType = "FULL_CYCLE",
                latencyMs = null,
                downloadSpeedMbps = null,
                uploadSpeedMbps = null,
                bytesTransferred = 0L,
                durationMs = 0L,
                success = false,
                errorMessage = skipReason,
                testStatus = "SKIPPED",
                skippedReason = skipReason,
                servingRat = rat,
                servingPci = pci,
                servingEarfcn = earfcn,
                rsrpDbm = rsrp,
                rsrqDb = rsrq,
                sinrDb = sinr,
                operator = operator,
                activeSimOperator = activeSimOp,
                simSlotIndex = simSlot,
                gpsAccuracyM = accuracy,
                endpointUrl = downloadEndpointUrl
            )
            qoeDao.insertQoeTest(skippedEntity)
            testMutex.unlock()
            return@withContext skippedEntity
        }

        // 3. User-Configured Daily Data Budget Check
        val budgetMb = dailyBudgetMb
        if (budgetMb != null) {
            val budgetBytes = (budgetMb * 1024 * 1024).toLong()
            if (_dataUsedTodayBytes.value + downloadBytesLimitPerTest + uploadBytesLimitPerTest > budgetBytes) {
                val skipReason = "User-configured daily data budget reached (${budgetMb} MB)"
                _lastSkipReason.value = skipReason
                _schedulerState.value = SchedulerState.SKIPPED
                Log.w("NetworkQoeScheduler", "Active test skipped: $skipReason")

                val skippedEntity = ActiveQoeTestEntity(
                    sessionId = sessionId,
                    timestampIso = startIso,
                    timestampMs = startWallMs,
                    latitude = lat,
                    longitude = lon,
                    testType = "FULL_CYCLE",
                    latencyMs = null,
                    downloadSpeedMbps = null,
                    uploadSpeedMbps = null,
                    bytesTransferred = 0L,
                    durationMs = 0L,
                    success = false,
                    errorMessage = skipReason,
                    testStatus = "SKIPPED",
                    skippedReason = skipReason,
                    servingRat = rat,
                    servingPci = pci,
                    servingEarfcn = earfcn,
                    rsrpDbm = rsrp,
                    rsrqDb = rsrq,
                    sinrDb = sinr,
                    operator = operator,
                    activeSimOperator = activeSimOp,
                    simSlotIndex = simSlot,
                    gpsAccuracyM = accuracy,
                    endpointUrl = downloadEndpointUrl
                )
                qoeDao.insertQoeTest(skippedEntity)
                testMutex.unlock()
                return@withContext skippedEntity
            }
        }

        _isTestRunning.value = true
        _schedulerState.value = SchedulerState.RUNNING
        var totalTransferredBytes = 0L

        try {
            // 4. Multi-probe RTT & Jitter test (4 probes)
            val rtts = mutableListOf<Long>()
            var failedProbes = 0
            val totalProbes = 4

            for (i in 0 until totalProbes) {
                val pingRes = executeHttpProbe(pingEndpointUrl, timeoutMs)
                if (pingRes != null) {
                    rtts.add(pingRes)
                } else {
                    failedProbes++
                }
                delay(80)
            }

            val medianLatencyMs = computeMedianLong(rtts)
            val meanLatencyMs = if (rtts.isNotEmpty()) rtts.average() else null
            val p95LatencyMs = CongestionEvidenceEngine.computeP95(rtts.map { it.toDouble() })
            val jitterMs = CongestionEvidenceEngine.calculateJitter(rtts)
            val probeLossPct = CongestionEvidenceEngine.calculateProbeLossPct(failedProbes, totalProbes)
            val rttJson = rtts.joinToString(prefix = "[", postfix = "]")

            // 5. Bounded HTTP Download Throughput Test
            val dlResult = executeDownloadStream(downloadEndpointUrl, downloadBytesLimitPerTest, timeoutMs)
            totalTransferredBytes += dlResult.bytesTransferred

            // 6. Bounded HTTP Upload Throughput Test
            val ulResult = executeUploadStream(uploadEndpointUrl, uploadBytesLimitPerTest, timeoutMs)
            totalTransferredBytes += ulResult.bytesTransferred

            // Update usage accumulators
            _sessionDataUsedBytes.value += totalTransferredBytes
            _dataUsedTodayBytes.value += totalTransferredBytes

            val anySuccess = rtts.isNotEmpty() || dlResult.speedMbps != null || ulResult.speedMbps != null
            val elapsedTotalMs = System.currentTimeMillis() - startWallMs
            val endWallMs = System.currentTimeMillis()

            // 7. Baseline comparison and Congestion Evidence classification
            val historicalTests = qoeDao.getSuccessfulTestsForSession(sessionId)
            val baseline = CongestionEvidenceEngine.buildHistoricalBaseline(
                historicalTests = historicalTests,
                targetRat = rat,
                targetPci = pci
            )

            val candidateEntity = ActiveQoeTestEntity(
                sessionId = sessionId,
                timestampIso = startIso,
                timestampMs = startWallMs,
                latitude = lat,
                longitude = lon,
                testType = "FULL_CYCLE",
                latencyMs = medianLatencyMs,
                downloadSpeedMbps = dlResult.speedMbps,
                uploadSpeedMbps = ulResult.speedMbps,
                bytesTransferred = totalTransferredBytes,
                durationMs = elapsedTotalMs,
                success = anySuccess,
                httpStatusCode = dlResult.statusCode ?: ulResult.statusCode ?: 200,
                errorMessage = dlResult.errorMessage ?: ulResult.errorMessage,
                servingRat = rat,
                servingPci = pci,
                servingEarfcn = earfcn,
                rsrpDbm = rsrp,
                rsrqDb = rsrq,
                sinrDb = sinr,
                jitterMs = jitterMs,
                packetLossPct = probeLossPct,
                congestionClassification = null,
                operator = operator,
                testStatus = if (anySuccess) "COMPLETED" else "FAILED",
                skippedReason = null,
                scheduledTimestampMs = startWallMs,
                endTimestampMs = endWallMs,
                gpsAccuracyM = accuracy,
                endpointUrl = downloadEndpointUrl,
                dlBytes = dlResult.bytesTransferred,
                dlDurationMs = dlResult.durationMs,
                ulBytes = ulResult.bytesTransferred,
                ulDurationMs = ulResult.durationMs,
                probeCount = totalProbes,
                successfulProbeCount = rtts.size,
                p95LatencyMs = p95LatencyMs,
                meanLatencyMs = meanLatencyMs,
                rttSamplesJson = rttJson,
                activeSimOperator = activeSimOp,
                simSlotIndex = simSlot,
                dataProvenance = "MEASURED"
            )

            val evaluation = CongestionEvidenceEngine.evaluateCongestion(
                currentTests = listOf(candidateEntity),
                baseline = baseline
            )

            val finalEntity = candidateEntity.copy(
                congestionClassification = evaluation.classification.name
            )

            val insertedId = qoeDao.insertQoeTest(finalEntity)
            val persistedEntity = finalEntity.copy(id = insertedId)

            _lastCompletedTest.value = persistedEntity
            _lastClassification.value = evaluation
            _schedulerState.value = if (anySuccess) SchedulerState.COMPLETED else SchedulerState.FAILED

            return@withContext persistedEntity
        } finally {
            _isTestRunning.value = false
            testMutex.unlock()
        }
    }

    private fun executeHttpProbe(targetUrl: String, timeoutMs: Int): Long? {
        val startNs = System.nanoTime()
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(targetUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                useCaches = false
            }
            val code = conn.responseCode
            if (code in 200..399) {
                (System.nanoTime() - startNs) / 1_000_000L
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    data class StreamResult(
        val bytesTransferred: Long,
        val durationMs: Long,
        val speedMbps: Double?,
        val statusCode: Int?,
        val errorMessage: String?
    )

    private fun executeDownloadStream(endpoint: String, maxBytes: Long, timeoutMs: Int): StreamResult {
        val startNs = System.nanoTime()
        var conn: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var totalBytes = 0L

        return try {
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
            }
            val code = conn.responseCode
            if (code !in 200..399) {
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                return StreamResult(0L, elapsedMs, null, code, "HTTP error $code")
            }

            inputStream = conn.inputStream
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                totalBytes += bytesRead
                if (totalBytes >= maxBytes) break
            }

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            val speedMbps = CongestionEvidenceEngine.calculateThroughputMbps(totalBytes, elapsedMs)
            StreamResult(totalBytes, elapsedMs, speedMbps, code, null)
        } catch (e: SocketTimeoutException) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            StreamResult(totalBytes, elapsedMs, null, null, "Download timeout after ${timeoutMs}ms")
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            StreamResult(totalBytes, elapsedMs, null, null, e.message)
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
            conn?.disconnect()
        }
    }

    private fun executeUploadStream(endpoint: String, payloadBytes: Long, timeoutMs: Int): StreamResult {
        val startNs = System.nanoTime()
        var conn: HttpURLConnection? = null
        var outputStream: OutputStream? = null

        return try {
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/octet-stream")
                setFixedLengthStreamingMode(payloadBytes.toInt())
            }

            outputStream = conn.outputStream
            val chunk = ByteArray(4096) { 0x55.toByte() }
            var written = 0L
            while (written < payloadBytes) {
                val toWrite = Math.min(chunk.size.toLong(), payloadBytes - written).toInt()
                outputStream.write(chunk, 0, toWrite)
                written += toWrite
            }
            outputStream.flush()

            val code = conn.responseCode
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            val speedMbps = if (code in 200..399) {
                CongestionEvidenceEngine.calculateThroughputMbps(payloadBytes, elapsedMs)
            } else null

            StreamResult(payloadBytes, elapsedMs, speedMbps, code, if (code in 200..399) null else "HTTP error $code")
        } catch (e: SocketTimeoutException) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            StreamResult(0L, elapsedMs, null, null, "Upload timeout after ${timeoutMs}ms")
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            StreamResult(0L, elapsedMs, null, null, e.message)
        } finally {
            try { outputStream?.close() } catch (_: Exception) {}
            conn?.disconnect()
        }
    }

    private fun computeMedianLong(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    companion object {
        @Volatile
        private var instance: NetworkQoeScheduler? = null

        fun getInstance(context: Context): NetworkQoeScheduler {
            return instance ?: synchronized(this) {
                instance ?: NetworkQoeScheduler(context.applicationContext).also { instance = it }
            }
        }
    }
}
