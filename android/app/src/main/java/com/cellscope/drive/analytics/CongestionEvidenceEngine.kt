package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import java.util.Locale
import kotlin.math.abs

/**
 * Standardized evidence classification for congestion and QoE Choke analysis.
 * Strictly adheres to 3GPP and scientific principles:
 * Never asserts "100% confirmed PRB congestion" solely from handset telemetry.
 */
enum class EvidenceClassification(val label: String, val shortTitle: String) {
    INSUFFICIENT_EVIDENCE("Insufficient Evidence", "Insufficient"),
    RF_LIMITED("RF-Limited / Radio Quality Degradation", "RF-Limited"),
    POSSIBLE_CONGESTION("Possible Congestion", "Possible"),
    STRONGER_CONGESTION_EVIDENCE("Stronger Congestion Evidence", "Stronger Evidence"),
    MIXED_AMBIGUOUS("Mixed / Ambiguous Evidence", "Mixed/Ambiguous")
}

/**
 * Time-binned statistical summary of QoE metrics for trend and time-series plotting.
 */
data class QoeTimeBin(
    val binTimestampMs: Long,
    val sampleCount: Int,
    val medianDlMbps: Double?,
    val medianUlMbps: Double?,
    val medianLatencyMs: Double?,
    val p95LatencyMs: Double?,
    val meanJitterMs: Double?,
    val probeLossPct: Double?,
    val medianRsrpDbm: Double?,
    val medianRsrqDb: Double?,
    val binMinutes: Int = 10,
    val meanDlMbps: Double? = null,
    val meanUlMbps: Double? = null,
    val meanLatencyMs: Double? = null,
    val medianSinrDb: Double? = null,
    val isSufficientEvidence: Boolean = (sampleCount >= 3),
    val evidenceLevel: String = if (sampleCount >= 3) "Measured" else if (sampleCount > 0) "Insufficient Evidence" else "Unavailable"
)

/**
 * Historical baseline reference built from verified, comparable past measurements.
 */
data class HistoricalQoeBaseline(
    val rat: String,
    val contextKey: String, // e.g. "PCI_177" or "CLUSTER_23.81_90.41"
    val sampleCount: Int,
    val medianDlMbps: Double?,
    val medianUlMbps: Double?,
    val medianLatencyMs: Double?,
    val p95LatencyMs: Double?,
    val meanJitterMs: Double?,
    val probeLossPct: Double?,
    val medianRsrpDbm: Double?,
    val medianSinrDb: Double?,
    val isReliable: Boolean
)

/**
 * Detailed engineering evaluation outcome with complete transparency.
 */
data class CongestionEvidenceResult(
    val classification: EvidenceClassification,
    val summary: String,
    val whyThisResult: String,
    val currentDlMbps: Double?,
    val baselineDlMbps: Double?,
    val dlDegradationPct: Double?,
    val currentUlMbps: Double?,
    val baselineUlMbps: Double?,
    val ulDegradationPct: Double?,
    val currentLatencyMs: Double?,
    val baselineLatencyMs: Double?,
    val latencyIncreasePct: Double?,
    val currentJitterMs: Double?,
    val currentProbeLossPct: Double?,
    val currentRsrpDbm: Int?,
    val baselineRsrpDbm: Double?,
    val rsrpDeltaDb: Double?,
    val isRfStable: Boolean,
    val currentSampleCount: Int,
    val baselineSampleCount: Int,
    val servingRat: String?,
    val servingPci: Int?,
    val alternativeExplanations: List<String>,
    val recommendedAction: String,
    val operatorDisclaimer: String = "Handset-side evidence only. True PRB scheduler utilization requires operator ENM/OSS counters."
)

object CongestionEvidenceEngine {

    const val MIN_BASELINE_SAMPLES = 3
    const val DL_DEGRADATION_THRESHOLD_PCT = 35.0 // 35% drop vs baseline
    const val LATENCY_INCREASE_THRESHOLD_PCT = 50.0 // 50% increase vs baseline
    const val RSRP_STABLE_WINDOW_DB = 6.0 // Within 6 dB considered radio-stable
    const val RF_DEGRADATION_THRESHOLD_DB = -10.0 // 10 dB attenuation considered RF-limited

    /**
     * Calculates empirical network throughput in Megabits per second (Mbps).
     * Formula: transferredBytes * 8 / (durationMs / 1000) / 1,000,000
     */
    fun calculateThroughputMbps(bytesTransferred: Long, durationMs: Long): Double? {
        if (bytesTransferred <= 0L || durationMs <= 0L) return null
        val durationSec = durationMs / 1000.0
        val bits = bytesTransferred * 8.0
        val mbps = bits / (durationSec * 1_000_000.0)
        return Math.round(mbps * 100.0) / 100.0
    }

    /**
     * Calculates the application/probe jitter using the mean absolute consecutive RTT difference.
     * Formula: mean(|RTT[i] - RTT[i-1]|) for all valid consecutive probe pairs.
     */
    fun calculateJitter(rtts: List<Long>): Double? {
        if (rtts.size < 2) return null
        var totalDelta = 0.0
        for (i in 1 until rtts.size) {
            totalDelta += abs(rtts[i] - rtts[i - 1])
        }
        val jitter = totalDelta / (rtts.size - 1)
        return Math.round(jitter * 10.0) / 10.0
    }

    /**
     * Calculates probe loss percentage.
     * Formula: 100.0 * failedProbes / totalProbes
     */
    fun calculateProbeLossPct(failedProbes: Int, totalProbes: Int): Double {
        if (totalProbes <= 0) return 0.0
        val loss = (failedProbes.toDouble() / totalProbes.toDouble()) * 100.0
        return Math.round(loss.coerceIn(0.0, 100.0) * 10.0) / 10.0
    }

    /**
     * Computes the 95th percentile (P95) from a list of numerical values.
     */
    fun computeP95(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val index = (0.95 * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    /**
     * Builds a historical baseline from a list of past active QoE tests matching the target radio/cell context.
     */
    fun buildHistoricalBaseline(
        historicalTests: List<ActiveQoeTestEntity>,
        targetRat: String? = null,
        targetPci: Int? = null,
        targetCellId: String? = null
    ): HistoricalQoeBaseline {
        val valid = historicalTests.filter { it.success }
        val matching = valid.filter { test ->
            val ratMatch = targetRat == null || test.servingRat.equals(targetRat, ignoreCase = true)
            val pciMatch = targetPci == null || test.servingPci == targetPci
            ratMatch && pciMatch
        }

        val dlList = matching.mapNotNull { it.downloadSpeedMbps }.filter { it > 0.0 }
        val ulList = matching.mapNotNull { it.uploadSpeedMbps }.filter { it > 0.0 }
        val latList = matching.mapNotNull { it.latencyMs?.toDouble() }.filter { it > 0.0 }
        val jitList = matching.mapNotNull { it.jitterMs }.filter { it >= 0.0 }
        val lossList = matching.mapNotNull { it.packetLossPct }
        val rsrpList = matching.mapNotNull { it.rsrpDbm?.toDouble() }
        val sinrList = matching.mapNotNull { it.sinrDb }

        val contextKey = when {
            targetCellId != null -> "CELL_$targetCellId"
            targetPci != null -> "PCI_$targetPci"
            targetRat != null -> "RAT_$targetRat"
            else -> "ALL_CONTEXTS"
        }

        return HistoricalQoeBaseline(
            rat = targetRat ?: "LTE",
            contextKey = contextKey,
            sampleCount = matching.size,
            medianDlMbps = computeMedian(dlList),
            medianUlMbps = computeMedian(ulList),
            medianLatencyMs = computeMedian(latList),
            p95LatencyMs = computeP95(latList),
            meanJitterMs = if (jitList.isNotEmpty()) Math.round(jitList.average() * 10.0) / 10.0 else null,
            probeLossPct = if (lossList.isNotEmpty()) Math.round(lossList.average() * 10.0) / 10.0 else null,
            medianRsrpDbm = computeMedian(rsrpList),
            medianSinrDb = computeMedian(sinrList),
            isReliable = matching.size >= MIN_BASELINE_SAMPLES
        )
    }

    /**
     * Core Congestion Evidence Engine.
     * Evaluates active test measurements against historical baselines and radio metrics.
     */
    fun evaluateCongestion(
        currentTests: List<ActiveQoeTestEntity>,
        baseline: HistoricalQoeBaseline,
        minRequiredSamples: Int = MIN_BASELINE_SAMPLES
    ): CongestionEvidenceResult {
        val successful = currentTests.filter { it.success }
        if (successful.isEmpty()) {
            return CongestionEvidenceResult(
                classification = EvidenceClassification.INSUFFICIENT_EVIDENCE,
                summary = "No successful active throughput or latency measurements in current evaluation window.",
                whyThisResult = "At least 1 valid completed active test is required to evaluate capacity.",
                currentDlMbps = null,
                baselineDlMbps = baseline.medianDlMbps,
                dlDegradationPct = null,
                currentUlMbps = null,
                baselineUlMbps = baseline.medianUlMbps,
                ulDegradationPct = null,
                currentLatencyMs = null,
                baselineLatencyMs = baseline.medianLatencyMs,
                latencyIncreasePct = null,
                currentJitterMs = null,
                currentProbeLossPct = null,
                currentRsrpDbm = null,
                baselineRsrpDbm = baseline.medianRsrpDbm,
                rsrpDeltaDb = null,
                isRfStable = false,
                currentSampleCount = 0,
                baselineSampleCount = baseline.sampleCount,
                servingRat = null,
                servingPci = null,
                alternativeExplanations = listOf("Test failed, timed out, or no connectivity during probe."),
                recommendedAction = "Verify cellular data connectivity and re-run active test."
            )
        }

        // Aggregate current observations
        val currentDls = successful.mapNotNull { it.downloadSpeedMbps }.filter { it > 0.0 }
        val currentUls = successful.mapNotNull { it.uploadSpeedMbps }.filter { it > 0.0 }
        val currentLats = successful.mapNotNull { it.latencyMs?.toDouble() }.filter { it > 0.0 }
        val currentJits = successful.mapNotNull { it.jitterMs }
        val currentLosses = successful.mapNotNull { it.packetLossPct }
        val currentRsrps = successful.mapNotNull { it.rsrpDbm }
        val currentSinrs = successful.mapNotNull { it.sinrDb }

        val curDl = computeMedian(currentDls)
        val curUl = computeMedian(currentUls)
        val curLat = computeMedian(currentLats)
        val curJit = if (currentJits.isNotEmpty()) currentJits.average() else null
        val curLoss = if (currentLosses.isNotEmpty()) currentLosses.average() else null
        val curRsrp = if (currentRsrps.isNotEmpty()) currentRsrps.average().toInt() else null
        val curSinr = if (currentSinrs.isNotEmpty()) currentSinrs.average() else null

        val latest = successful.last()
        val rat = latest.servingRat
        val pci = latest.servingPci

        // Baseline availability check
        if (!baseline.isReliable || baseline.sampleCount < minRequiredSamples) {
            return CongestionEvidenceResult(
                classification = EvidenceClassification.INSUFFICIENT_EVIDENCE,
                summary = "Insufficient historical baseline samples in this radio context (${baseline.sampleCount}/$minRequiredSamples samples).",
                whyThisResult = "A credible baseline requires at least $minRequiredSamples historical samples under comparable RAT/cell context to avoid spurious Choke detection.",
                currentDlMbps = curDl,
                baselineDlMbps = baseline.medianDlMbps,
                dlDegradationPct = null,
                currentUlMbps = curUl,
                baselineUlMbps = baseline.medianUlMbps,
                ulDegradationPct = null,
                currentLatencyMs = curLat,
                baselineLatencyMs = baseline.medianLatencyMs,
                latencyIncreasePct = null,
                currentJitterMs = curJit,
                currentProbeLossPct = curLoss,
                currentRsrpDbm = curRsrp,
                baselineRsrpDbm = baseline.medianRsrpDbm,
                rsrpDeltaDb = null,
                isRfStable = false,
                currentSampleCount = successful.size,
                baselineSampleCount = baseline.sampleCount,
                servingRat = rat,
                servingPci = pci,
                alternativeExplanations = listOf(
                    "Session is newly started; baseline accumulation in progress.",
                    "Handset transitioned to an unbenchmarked sector or frequency channel."
                ),
                recommendedAction = "Continue survey recording or scheduled testing to establish an authoritative baseline."
            )
        }

        // Relative degradation calculations
        val dlDegradationPct = if (baseline.medianDlMbps != null && baseline.medianDlMbps > 0.001 && curDl != null) {
            val deg = 100.0 * (baseline.medianDlMbps - curDl) / baseline.medianDlMbps
            Math.round(deg * 10.0) / 10.0
        } else null

        val ulDegradationPct = if (baseline.medianUlMbps != null && baseline.medianUlMbps > 0.001 && curUl != null) {
            val deg = 100.0 * (baseline.medianUlMbps - curUl) / baseline.medianUlMbps
            Math.round(deg * 10.0) / 10.0
        } else null

        val latencyIncreasePct = if (baseline.medianLatencyMs != null && baseline.medianLatencyMs > 0.001 && curLat != null) {
            val inc = 100.0 * (curLat - baseline.medianLatencyMs) / baseline.medianLatencyMs
            Math.round(inc * 10.0) / 10.0
        } else null

        // RF stability evaluation
        val rsrpDeltaDb = if (curRsrp != null && baseline.medianRsrpDbm != null) {
            val d = curRsrp.toDouble() - baseline.medianRsrpDbm
            Math.round(d * 10.0) / 10.0
        } else 0.0

        val isRfDegraded = rsrpDeltaDb <= RF_DEGRADATION_THRESHOLD_DB || (curRsrp != null && curRsrp < -105) || (curSinr != null && curSinr < 0.0)
        val isRfStable = abs(rsrpDeltaDb) <= RSRP_STABLE_WINDOW_DB && (curRsrp == null || curRsrp >= -98) && (curSinr == null || curSinr >= 2.0)

        // Classify based on evidence rules
        return when {
            // Rule 1: Performance dropped, but RF signal also collapsed -> RF-Limited
            isRfDegraded && ((dlDegradationPct ?: 0.0) >= DL_DEGRADATION_THRESHOLD_PCT || (latencyIncreasePct ?: 0.0) >= LATENCY_INCREASE_THRESHOLD_PCT) -> {
                CongestionEvidenceResult(
                    classification = EvidenceClassification.RF_LIMITED,
                    summary = "Performance slowdown coincides with RF signal attenuation (ΔRSRP: ${rsrpDeltaDb} dB).",
                    whyThisResult = "Throughput drop is primarily explained by physical layer path loss, shadow fading, or high interference rather than scheduler buffer bloat.",
                    currentDlMbps = curDl,
                    baselineDlMbps = baseline.medianDlMbps,
                    dlDegradationPct = dlDegradationPct,
                    currentUlMbps = curUl,
                    baselineUlMbps = baseline.medianUlMbps,
                    ulDegradationPct = ulDegradationPct,
                    currentLatencyMs = curLat,
                    baselineLatencyMs = baseline.medianLatencyMs,
                    latencyIncreasePct = latencyIncreasePct,
                    currentJitterMs = curJit,
                    currentProbeLossPct = curLoss,
                    currentRsrpDbm = curRsrp,
                    baselineRsrpDbm = baseline.medianRsrpDbm,
                    rsrpDeltaDb = rsrpDeltaDb,
                    isRfStable = false,
                    currentSampleCount = successful.size,
                    baselineSampleCount = baseline.sampleCount,
                    servingRat = rat,
                    servingPci = pci,
                    alternativeExplanations = listOf(
                        "Indoor or building penetration attenuation.",
                        "Inter-cell co-channel interference or SINR degradation.",
                        "Cell edge path loss geometry."
                    ),
                    recommendedAction = "Inspect physical coverage map and antenna tilt before concluding cell capacity exhaust."
                )
            }

            // Rule 2: Strong Congestion Evidence -> RF is stable and clean, multiple repeated samples show significant degradation
            isRfStable && successful.size >= 3 && (dlDegradationPct ?: 0.0) >= 40.0 && ((latencyIncreasePct ?: 0.0) >= 40.0 || (curJit ?: 0.0) > 30.0) -> {
                CongestionEvidenceResult(
                    classification = EvidenceClassification.STRONGER_CONGESTION_EVIDENCE,
                    summary = "Repeated DL throughput collapse (-${dlDegradationPct}%) and latency elevation (+${latencyIncreasePct}%) under stable RF signal.",
                    whyThisResult = "Observed across ${successful.size} consecutive measurement cycles on PCI $pci with stable RSRP (${curRsrp ?: "—"} dBm). Strong multi-factor evidence of eNodeB scheduler queuing or PRB exhaustion.",
                    currentDlMbps = curDl,
                    baselineDlMbps = baseline.medianDlMbps,
                    dlDegradationPct = dlDegradationPct,
                    currentUlMbps = curUl,
                    baselineUlMbps = baseline.medianUlMbps,
                    ulDegradationPct = ulDegradationPct,
                    currentLatencyMs = curLat,
                    baselineLatencyMs = baseline.medianLatencyMs,
                    latencyIncreasePct = latencyIncreasePct,
                    currentJitterMs = curJit,
                    currentProbeLossPct = curLoss,
                    currentRsrpDbm = curRsrp,
                    baselineRsrpDbm = baseline.medianRsrpDbm,
                    rsrpDeltaDb = rsrpDeltaDb,
                    isRfStable = true,
                    currentSampleCount = successful.size,
                    baselineSampleCount = baseline.sampleCount,
                    servingRat = rat,
                    servingPci = pci,
                    alternativeExplanations = listOf(
                        "Operator backhaul / transmission bottleneck between gNB/eNodeB and EPC/5GC.",
                        "Core network SGW/UPF throttling or external transit link congestion.",
                        "Endpoint CDN server contention during peak load hours."
                    ),
                    recommendedAction = "Correlate with operator ENM PM counters (pmPrbUsedDl, pmPrbAvailDl) to verify scheduler PRB allocation."
                )
            }

            // Rule 3: Conflicting signals -> Mixed / Ambiguous Evidence (takes precedence over isolated degradation)
            ((dlDegradationPct ?: 0.0) >= 40.0 && (ulDegradationPct ?: 0.0) <= -20.0) || ((curLoss ?: 0.0) > 15.0 && (latencyIncreasePct ?: 0.0) < 10.0) -> {
                CongestionEvidenceResult(
                    classification = EvidenceClassification.MIXED_AMBIGUOUS,
                    summary = "Contradictory performance indicators detected (asymmetric DL/UL trend or packet loss without RTT rise).",
                    whyThisResult = "DL throughput declined while UL improved, or packet loss occurred with nominal latency. Likely asymmetric routing or bursty transport behavior.",
                    currentDlMbps = curDl,
                    baselineDlMbps = baseline.medianDlMbps,
                    dlDegradationPct = dlDegradationPct,
                    currentUlMbps = curUl,
                    baselineUlMbps = baseline.medianUlMbps,
                    ulDegradationPct = ulDegradationPct,
                    currentLatencyMs = curLat,
                    baselineLatencyMs = baseline.medianLatencyMs,
                    latencyIncreasePct = latencyIncreasePct,
                    currentJitterMs = curJit,
                    currentProbeLossPct = curLoss,
                    currentRsrpDbm = curRsrp,
                    baselineRsrpDbm = baseline.medianRsrpDbm,
                    rsrpDeltaDb = rsrpDeltaDb,
                    isRfStable = isRfStable,
                    currentSampleCount = successful.size,
                    baselineSampleCount = baseline.sampleCount,
                    servingRat = rat,
                    servingPci = pci,
                    alternativeExplanations = listOf(
                        "Asymmetric RAN PRB scheduling or TDD DL/UL frame config.",
                        "Transient route flappings in upstream IP transport."
                    ),
                    recommendedAction = "Execute a full bi-directional probe cycle with ICMP and HTTP comparisons."
                )
            }

            // Rule 4: Possible Congestion -> RF is stable, DL degraded or latency spiked, but fewer samples or single test
            isRfStable && ((dlDegradationPct ?: 0.0) >= DL_DEGRADATION_THRESHOLD_PCT || (latencyIncreasePct ?: 0.0) >= LATENCY_INCREASE_THRESHOLD_PCT) -> {
                CongestionEvidenceResult(
                    classification = EvidenceClassification.POSSIBLE_CONGESTION,
                    summary = "Isolated throughput degradation or latency spike under clean radio conditions.",
                    whyThisResult = "DL throughput is degraded by ${dlDegradationPct ?: 0.0}% relative to baseline while RSRP is healthy (${curRsrp ?: "—"} dBm). Recurring evidence required to rule out transient transport bursts.",
                    currentDlMbps = curDl,
                    baselineDlMbps = baseline.medianDlMbps,
                    dlDegradationPct = dlDegradationPct,
                    currentUlMbps = curUl,
                    baselineUlMbps = baseline.medianUlMbps,
                    ulDegradationPct = ulDegradationPct,
                    currentLatencyMs = curLat,
                    baselineLatencyMs = baseline.medianLatencyMs,
                    latencyIncreasePct = latencyIncreasePct,
                    currentJitterMs = curJit,
                    currentProbeLossPct = curLoss,
                    currentRsrpDbm = curRsrp,
                    baselineRsrpDbm = baseline.medianRsrpDbm,
                    rsrpDeltaDb = rsrpDeltaDb,
                    isRfStable = true,
                    currentSampleCount = successful.size,
                    baselineSampleCount = baseline.sampleCount,
                    servingRat = rat,
                    servingPci = pci,
                    alternativeExplanations = listOf(
                        "Transient TCP buffer buildup or TCP slow start.",
                        "Temporary server-side processing delay.",
                        "Intermittent device background update."
                    ),
                    recommendedAction = "Allow scheduled test cycles to run for at least 3-5 intervals (15-25 mins) to confirm persistence."
                )
            }

            // Rule 5: Healthy Performance / Insufficient Evidence of Congestion
            else -> {
                CongestionEvidenceResult(
                    classification = EvidenceClassification.INSUFFICIENT_EVIDENCE,
                    summary = "Normal throughput and latency performance relative to baseline; no evidence of RAN congestion.",
                    whyThisResult = "Throughput and latency align within expected variance of the historical baseline for this cell.",
                    currentDlMbps = curDl,
                    baselineDlMbps = baseline.medianDlMbps,
                    dlDegradationPct = dlDegradationPct,
                    currentUlMbps = curUl,
                    baselineUlMbps = baseline.medianUlMbps,
                    ulDegradationPct = ulDegradationPct,
                    currentLatencyMs = curLat,
                    baselineLatencyMs = baseline.medianLatencyMs,
                    latencyIncreasePct = latencyIncreasePct,
                    currentJitterMs = curJit,
                    currentProbeLossPct = curLoss,
                    currentRsrpDbm = curRsrp,
                    baselineRsrpDbm = baseline.medianRsrpDbm,
                    rsrpDeltaDb = rsrpDeltaDb,
                    isRfStable = isRfStable,
                    currentSampleCount = successful.size,
                    baselineSampleCount = baseline.sampleCount,
                    servingRat = rat,
                    servingPci = pci,
                    alternativeExplanations = listOf("Network sector operating nominally within capacity headroom."),
                    recommendedAction = "Continue regular monitoring."
                )
            }
        }
    }

    /**
     * Aggregates time-series data into discrete non-zero bins (e.g. 5, 10, 15, 20, 60 minutes).
     * Missing intervals are omitted rather than fabricated as zeros.
     */
    fun binTimeSeries(tests: List<ActiveQoeTestEntity>, binMinutes: Int = 10): List<QoeTimeBin> {
        val binMs = binMinutes * 60 * 1000L
        if (binMs <= 0L) return emptyList()

        val valid = tests.filter { it.success }
        val groups = valid.groupBy { (it.timestampMs / binMs) * binMs }

        return groups.keys.sorted().map { binStart ->
            val binTests = groups[binStart] ?: emptyList()
            val dlList = binTests.mapNotNull { it.downloadSpeedMbps }.filter { it > 0.0 }
            val ulList = binTests.mapNotNull { it.uploadSpeedMbps }.filter { it > 0.0 }
            val latList = binTests.mapNotNull { it.latencyMs?.toDouble() }.filter { it > 0.0 }
            val jitList = binTests.mapNotNull { it.jitterMs }
            val lossList = binTests.mapNotNull { it.packetLossPct }
            val rsrpList = binTests.mapNotNull { it.rsrpDbm?.toDouble() }
            val rsrqList = binTests.mapNotNull { it.rsrqDb?.toDouble() }
            val sinrList = binTests.mapNotNull { it.sinrDb }

            QoeTimeBin(
                binTimestampMs = binStart,
                sampleCount = binTests.size,
                medianDlMbps = computeMedian(dlList),
                medianUlMbps = computeMedian(ulList),
                medianLatencyMs = computeMedian(latList),
                p95LatencyMs = computeP95(latList),
                meanJitterMs = if (jitList.isNotEmpty()) Math.round(jitList.average() * 10.0) / 10.0 else null,
                probeLossPct = if (lossList.isNotEmpty()) Math.round(lossList.average() * 10.0) / 10.0 else null,
                medianRsrpDbm = computeMedian(rsrpList),
                medianRsrqDb = computeMedian(rsrqList),
                binMinutes = binMinutes,
                meanDlMbps = if (dlList.isNotEmpty()) Math.round(dlList.average() * 10.0) / 10.0 else null,
                meanUlMbps = if (ulList.isNotEmpty()) Math.round(ulList.average() * 10.0) / 10.0 else null,
                meanLatencyMs = if (latList.isNotEmpty()) Math.round(latList.average() * 10.0) / 10.0 else null,
                medianSinrDb = computeMedian(sinrList),
                isSufficientEvidence = binTests.size >= 3,
                evidenceLevel = if (binTests.size >= 3) "Measured" else if (binTests.isNotEmpty()) "Insufficient Evidence" else "Unavailable"
            )
        }
    }

    data class SessionDataUsage(
        val downloadBytes: Long,
        val uploadBytes: Long,
        val totalBytes: Long,
        val totalMb: Double,
        val downloadMb: Double,
        val uploadMb: Double
    )

    fun calculateSessionDataUsage(tests: List<ActiveQoeTestEntity>): SessionDataUsage {
        val dl = tests.mapNotNull { it.dlBytes }.sum()
        val ul = tests.mapNotNull { it.ulBytes }.sum()
        val total = dl + ul
        val toMb = { b: Long -> Math.round((b / (1024.0 * 1024.0)) * 100.0) / 100.0 }
        return SessionDataUsage(
            downloadBytes = dl,
            uploadBytes = ul,
            totalBytes = total,
            totalMb = toMb(total),
            downloadMb = toMb(dl),
            uploadMb = toMb(ul)
        )
    }

    enum class TimeOfDayWindow(val label: String, val startHour: Int, val endHour: Int) {
        MORNING_PEAK("Morning Peak (08:00 - 12:00)", 8, 12),
        MIDDAY("Midday (12:00 - 17:00)", 12, 17),
        EVENING_PEAK("Evening Peak (17:00 - 22:00)", 17, 22),
        NIGHT_OFF_PEAK("Night Off-Peak (22:00 - 08:00)", 22, 8)
    }

    fun analyzeTimeOfDayWindows(tests: List<ActiveQoeTestEntity>): Map<TimeOfDayWindow, QoeTimeBin> {
        val valid = tests.filter { it.success }
        val calendar = java.util.Calendar.getInstance()

        val windowMap = mutableMapOf<TimeOfDayWindow, MutableList<ActiveQoeTestEntity>>()
        TimeOfDayWindow.values().forEach { windowMap[it] = mutableListOf() }

        for (test in valid) {
            calendar.timeInMillis = test.timestampMs
            val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
            val window = when {
                hour in 8 until 12 -> TimeOfDayWindow.MORNING_PEAK
                hour in 12 until 17 -> TimeOfDayWindow.MIDDAY
                hour in 17 until 22 -> TimeOfDayWindow.EVENING_PEAK
                else -> TimeOfDayWindow.NIGHT_OFF_PEAK
            }
            windowMap[window]?.add(test)
        }

        return windowMap.mapValues { (_, windowTests) ->
            val dlList = windowTests.mapNotNull { it.downloadSpeedMbps }.filter { it > 0.0 }
            val ulList = windowTests.mapNotNull { it.uploadSpeedMbps }.filter { it > 0.0 }
            val latList = windowTests.mapNotNull { it.latencyMs?.toDouble() }.filter { it > 0.0 }
            val jitList = windowTests.mapNotNull { it.jitterMs }
            val lossList = windowTests.mapNotNull { it.packetLossPct }
            val rsrpList = windowTests.mapNotNull { it.rsrpDbm?.toDouble() }
            val rsrqList = windowTests.mapNotNull { it.rsrqDb?.toDouble() }
            val sinrList = windowTests.mapNotNull { it.sinrDb }

            QoeTimeBin(
                binTimestampMs = windowTests.firstOrNull()?.timestampMs ?: 0L,
                sampleCount = windowTests.size,
                medianDlMbps = computeMedian(dlList),
                medianUlMbps = computeMedian(ulList),
                medianLatencyMs = computeMedian(latList),
                p95LatencyMs = computeP95(latList),
                meanJitterMs = if (jitList.isNotEmpty()) Math.round(jitList.average() * 10.0) / 10.0 else null,
                probeLossPct = if (lossList.isNotEmpty()) Math.round(lossList.average() * 10.0) / 10.0 else null,
                medianRsrpDbm = computeMedian(rsrpList),
                medianRsrqDb = computeMedian(rsrqList),
                binMinutes = 60,
                meanDlMbps = if (dlList.isNotEmpty()) Math.round(dlList.average() * 10.0) / 10.0 else null,
                meanUlMbps = if (ulList.isNotEmpty()) Math.round(ulList.average() * 10.0) / 10.0 else null,
                meanLatencyMs = if (latList.isNotEmpty()) Math.round(latList.average() * 10.0) / 10.0 else null,
                medianSinrDb = computeMedian(sinrList),
                isSufficientEvidence = windowTests.size >= 3,
                evidenceLevel = if (windowTests.size >= 3) "Measured" else if (windowTests.isNotEmpty()) "Insufficient Evidence" else "Unavailable"
            )
        }
    }

    private fun computeMedian(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
        return Math.round(median * 10.0) / 10.0
    }
}
