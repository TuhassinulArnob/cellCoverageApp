package com.cellscope.drive.analytics.qoe

import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.data.entity.LteMeasurementEntity
import kotlin.math.roundToInt

/**
 * Session-level Quality of Experience (QoE) and Measurement Reliability KPIs.
 *
 * All formulas, numerators, denominators, and units are strictly defined:
 * - GPS Availability (%): (samples with valid GPS fix / total samples) * 100
 * - Radio Availability (%): (samples with non-null RSRP or PCI / total samples) * 100
 * - Coverage Adequacy (%): (samples with RSRP >= -105 dBm / total samples with valid RSRP) * 100
 * - Deep Coverage Hole (%): (samples with RSRP < -115 dBm / total samples with valid RSRP) * 100
 * - Poor Quality Ratio (%): (samples with RSRQ < -15 dB or SINR < 0.0 dB / total evaluated samples) * 100
 * - Latency (ms): Round-trip HTTP request latency (Mean, Min, Max)
 * - Throughput (Mbps): (Total bytes transferred * 8) / (Elapsed transfer seconds * 1,000,000)
 */
data class QoeSessionReport(
    val totalSamples: Int,
    val validGpsSamples: Int,
    val gpsAvailabilityPct: Double,
    val validRadioSamples: Int,
    val radioAvailabilityPct: Double,
    val meanRsrpDbm: Double?,
    val medianRsrpDbm: Double?,
    val meanRsrqDb: Double?,
    val medianRsrqDb: Double?,
    val meanSinrDb: Double?,
    val medianSinrDb: Double?,
    val coverageAdequacyPct: Double,
    val deepCoverageHolePct: Double,
    val poorQualityRatioPct: Double,
    val totalDistanceMeters: Double,
    val sessionDurationSec: Long,
    val activeQoeSummary: ActiveQoeSummary?
)

data class ActiveQoeSummary(
    val totalTestsAttempted: Int,
    val successfulTests: Int,
    val testCompletionRatePct: Double,
    val avgLatencyMs: Double?,
    val minLatencyMs: Long?,
    val maxLatencyMs: Long?,
    val avgDownloadThroughputMbps: Double?,
    val maxDownloadThroughputMbps: Double?,
    val avgUploadThroughputMbps: Double?,
    val maxUploadThroughputMbps: Double?,
    val failureRatePct: Double
)

object QoeAnalyzer {

    fun analyzeSessionQoe(
        session: DriveSession,
        measurements: List<LteMeasurementEntity>,
        activeTests: List<ActiveQoeTestEntity> = emptyList()
    ): QoeSessionReport {
        val totalCount = measurements.size
        if (totalCount == 0) {
            val emptyActive = summarizeActiveTests(activeTests)
            return QoeSessionReport(
                totalSamples = 0,
                validGpsSamples = 0,
                gpsAvailabilityPct = 0.0,
                validRadioSamples = 0,
                radioAvailabilityPct = 0.0,
                meanRsrpDbm = null,
                medianRsrpDbm = null,
                meanRsrqDb = null,
                medianRsrqDb = null,
                meanSinrDb = null,
                medianSinrDb = null,
                coverageAdequacyPct = 0.0,
                deepCoverageHolePct = 0.0,
                poorQualityRatioPct = 0.0,
                totalDistanceMeters = session.totalDistanceM,
                sessionDurationSec = calculateDurationSec(session, measurements),
                activeQoeSummary = emptyActive
            )
        }

        // 1. GPS Availability KPI
        val validGpsSamples = measurements.count {
            it.isGpsValid && it.latitude != null && it.longitude != null
        }
        val gpsAvailabilityPct = round1((validGpsSamples.toDouble() / totalCount.toDouble()) * 100.0)

        // 2. Radio Availability KPI
        val validRadioSamples = measurements.count {
            it.rsrpDbm != null || it.servingPci != null
        }
        val radioAvailabilityPct = round1((validRadioSamples.toDouble() / totalCount.toDouble()) * 100.0)

        // 3. RSRP Statistics
        val rsrpList = measurements.mapNotNull { it.rsrpDbm?.toDouble() }
        val (meanRsrp, medianRsrp) = computeMeanAndMedian(rsrpList)

        // 4. RSRQ Statistics
        val rsrqList = measurements.mapNotNull { it.rsrqDb?.toDouble() }
        val (meanRsrq, medianRsrq) = computeMeanAndMedian(rsrqList)

        // 5. SINR Statistics
        val sinrList = measurements.mapNotNull { it.sinrDb }
        val (meanSinr, medianSinr) = computeMeanAndMedian(sinrList)

        // 6. Signal Threshold KPI Percentages
        val coverageAdequacyPct = if (rsrpList.isNotEmpty()) {
            val adequateCount = rsrpList.count { it >= -105.0 }
            round1((adequateCount.toDouble() / rsrpList.size.toDouble()) * 100.0)
        } else 0.0

        val deepCoverageHolePct = if (rsrpList.isNotEmpty()) {
            val holeCount = rsrpList.count { it < -115.0 }
            round1((holeCount.toDouble() / rsrpList.size.toDouble()) * 100.0)
        } else 0.0

        val evaluatedQualityCount = measurements.count { it.rsrqDb != null || it.sinrDb != null }
        val poorQualityRatioPct = if (evaluatedQualityCount > 0) {
            val poorCount = measurements.count {
                (it.rsrqDb != null && it.rsrqDb < -15) || (it.sinrDb != null && it.sinrDb < 0.0)
            }
            round1((poorCount.toDouble() / evaluatedQualityCount.toDouble()) * 100.0)
        } else 0.0

        val durationSec = calculateDurationSec(session, measurements)
        val activeSummary = summarizeActiveTests(activeTests)

        return QoeSessionReport(
            totalSamples = totalCount,
            validGpsSamples = validGpsSamples,
            gpsAvailabilityPct = gpsAvailabilityPct,
            validRadioSamples = validRadioSamples,
            radioAvailabilityPct = radioAvailabilityPct,
            meanRsrpDbm = meanRsrp,
            medianRsrpDbm = medianRsrp,
            meanRsrqDb = meanRsrq,
            medianRsrqDb = medianRsrq,
            meanSinrDb = meanSinr,
            medianSinrDb = medianSinr,
            coverageAdequacyPct = coverageAdequacyPct,
            deepCoverageHolePct = deepCoverageHolePct,
            poorQualityRatioPct = poorQualityRatioPct,
            totalDistanceMeters = session.totalDistanceM,
            sessionDurationSec = durationSec,
            activeQoeSummary = activeSummary
        )
    }

    private fun summarizeActiveTests(tests: List<ActiveQoeTestEntity>): ActiveQoeSummary? {
        if (tests.isEmpty()) return null

        val totalAttempted = tests.size
        val successful = tests.filter { it.success }
        val successCount = successful.size
        val completionRatePct = round1((successCount.toDouble() / totalAttempted.toDouble()) * 100.0)
        val failureRatePct = round1(((totalAttempted - successCount).toDouble() / totalAttempted.toDouble()) * 100.0)

        val latencies = successful.mapNotNull { it.latencyMs }
        val avgLatency = if (latencies.isNotEmpty()) round1(latencies.average()) else null
        val minLatency = latencies.minOrNull()
        val maxLatency = latencies.maxOrNull()

        val downloadSpeeds = successful.mapNotNull { it.downloadSpeedMbps }
        val avgDownload = if (downloadSpeeds.isNotEmpty()) round2(downloadSpeeds.average()) else null
        val maxDownload = downloadSpeeds.maxOrNull()?.let { round2(it) }

        val uploadSpeeds = successful.mapNotNull { it.uploadSpeedMbps }
        val avgUpload = if (uploadSpeeds.isNotEmpty()) round2(uploadSpeeds.average()) else null
        val maxUpload = uploadSpeeds.maxOrNull()?.let { round2(it) }

        return ActiveQoeSummary(
            totalTestsAttempted = totalAttempted,
            successfulTests = successCount,
            testCompletionRatePct = completionRatePct,
            avgLatencyMs = avgLatency,
            minLatencyMs = minLatency,
            maxLatencyMs = maxLatency,
            avgDownloadThroughputMbps = avgDownload,
            maxDownloadThroughputMbps = maxDownload,
            avgUploadThroughputMbps = avgUpload,
            maxUploadThroughputMbps = maxUpload,
            failureRatePct = failureRatePct
        )
    }

    private fun computeMeanAndMedian(values: List<Double>): Pair<Double?, Double?> {
        if (values.isEmpty()) return Pair(null, null)
        val mean = round1(values.average())
        val sorted = values.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        return Pair(mean, round1(median))
    }

    private fun calculateDurationSec(session: DriveSession, measurements: List<LteMeasurementEntity>): Long {
        return if (session.endTimeMs != null && session.endTimeMs!! > session.startTimeMs) {
            (session.endTimeMs!! - session.startTimeMs) / 1000L
        } else if (measurements.isNotEmpty()) {
            val lastTime = measurements.last().timestampMs
            ((lastTime - session.startTimeMs) / 1000L).coerceAtLeast(0L)
        } else 0L
    }

    private fun round1(v: Double): Double = (v * 10.0).roundToInt() / 10.0
    private fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0
}
