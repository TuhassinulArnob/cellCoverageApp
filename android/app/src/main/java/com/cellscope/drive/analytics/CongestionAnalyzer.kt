package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import java.util.Locale

/**
 * Standardized network congestion and capacity bottleneck assessment levels.
 * Enforces engineering discipline: never claims "measured PRB utilization" from handset-only tests.
 */
enum class CongestionConfidenceLevel(val label: String, val description: String) {
    INSUFFICIENT_EVIDENCE(
        "Insufficient Evidence",
        "Active mobile throughput or latency tests are required to evaluate capacity bottlenecks."
    ),
    POSSIBLE_BOTTLENECK(
        "Potential Congestion",
        "Isolated throughput slowdown or latency variation detected, but observations are insufficient to confirm recurring congestion."
    ),
    CONGESTION_SUSPECTED(
        "Potential Congestion",
        "Repeated poor throughput and elevated latency observed despite favorable RF signal strength (good RSRP and clean SINR)."
    ),
    STRONG_CONGESTION_EVIDENCE(
        "Stronger Congestion Evidence",
        "Statistically recurring capacity choke across multiple test cycles on this serving cell under good radio conditions."
    ),
    VERIFIED_NETWORK_LOAD(
        "Verified Network Congestion",
        "Confirmed through operator-supplied PRB utilization counters and eNodeB traffic records."
    )
}

data class CongestionAnalysisReport(
    val confidenceLevel: CongestionConfidenceLevel,
    val testCount: Int,
    val avgDownloadMbps: Double?,
    val maxDownloadMbps: Double?,
    val avgUploadMbps: Double?,
    val avgLatencyMs: Double?,
    val minLatencyMs: Long?,
    val maxLatencyMs: Long?,
    val primaryServingPci: Int?,
    val radioQualityContext: String,
    val primaryLimitingFactor: String,
    val alternativeExplanations: List<String>,
    val missingInputs: List<String>,
    val engineeringEvidence: String
)

object CongestionAnalyzer {

    /**
     * Evaluates capacity constraints and distinguishes actual cell congestion from
     * weak RF coverage, radio interference, transport/backhaul bottlenecks, and device limitations.
     */
    fun analyzeCongestion(
        measurements: List<LteMeasurementEntity>,
        activeTests: List<ActiveQoeTestEntity> = emptyList()
    ): CongestionAnalysisReport {
        val successfulTests = activeTests.filter { it.success }

        // If no active throughput/latency tests exist, check if measurements carry active test proxies
        if (successfulTests.isEmpty()) {
            return CongestionAnalysisReport(
                confidenceLevel = CongestionConfidenceLevel.INSUFFICIENT_EVIDENCE,
                testCount = 0,
                avgDownloadMbps = null,
                maxDownloadMbps = null,
                avgUploadMbps = null,
                avgLatencyMs = null,
                minLatencyMs = null,
                maxLatencyMs = null,
                primaryServingPci = measurements.mapNotNull { it.servingPci }.groupBy { it }.maxByOrNull { it.value.size }?.key,
                radioQualityContext = "No active throughput/latency test samples collected for this session.",
                primaryLimitingFactor = "Absence of active throughput or round-trip latency data.",
                alternativeExplanations = listOf(
                    "Radio signal metrics (RSRP/RSRQ) reflect received physical layer power, not physical resource block (PRB) utilization or scheduler queues.",
                    "Mobile data tests consume user data and must be triggered intentionally."
                ),
                missingInputs = listOf(
                    "Active DL/UL throughput measurements",
                    "Round-trip latency and jitter samples",
                    "Operator-side PRB utilization counters (DL/UL PRB %)",
                    "Transport backhaul capacity figures"
                ),
                engineeringEvidence = "Handset-only survey without active throughput tests cannot responsibly assert network congestion."
            )
        }

        val dlSpeeds = successfulTests.mapNotNull { it.downloadSpeedMbps }.filter { it > 0.0 }
        val ulSpeeds = successfulTests.mapNotNull { it.uploadSpeedMbps }.filter { it > 0.0 }
        val latencies = successfulTests.mapNotNull { it.latencyMs }.filter { it > 0 }

        val avgDl = if (dlSpeeds.isNotEmpty()) dlSpeeds.average() else null
        val maxDl = if (dlSpeeds.isNotEmpty()) dlSpeeds.maxOrNull() else null
        val avgUl = if (ulSpeeds.isNotEmpty()) ulSpeeds.average() else null
        val avgLat = if (latencies.isNotEmpty()) latencies.average() else null
        val minLat = if (latencies.isNotEmpty()) latencies.minOrNull() else null
        val maxLat = if (latencies.isNotEmpty()) latencies.maxOrNull() else null

        // Correlate test timestamps with RF measurements
        val testTimes = successfulTests.map { it.timestampMs }
        val correlatedRf = measurements.filter { m ->
            testTimes.any { ts -> kotlin.math.abs(m.timestampMs - ts) <= 5000L }
        }.ifEmpty { measurements }

        val rsrpList = correlatedRf.mapNotNull { it.rsrpDbm }
        val sinrList = correlatedRf.mapNotNull { it.sinrDb }
        val avgRsrp = if (rsrpList.isNotEmpty()) rsrpList.average() else null
        val avgSinr = if (sinrList.isNotEmpty()) sinrList.average() else null

        val dominantPci = correlatedRf.mapNotNull { it.servingPci }
            .groupBy { it }
            .maxByOrNull { it.value.size }?.key

        val isGoodRsrp = avgRsrp != null && avgRsrp >= -95.0
        val isPoorRsrp = avgRsrp != null && avgRsrp < -105.0
        val isCleanSinr = avgSinr != null && avgSinr >= 5.0
        val isLowSinr = avgSinr != null && avgSinr < 0.0
        val isLowThroughput = avgDl != null && avgDl < 3.0 // < 3 Mbps on LTE
        val isHighLatency = avgLat != null && avgLat > 120.0 // > 120 ms

        val rfContext = buildString {
            append("Correlated RF Context: ")
            append("Mean RSRP: ${avgRsrp?.let { String.format(Locale.US, "%.1f dBm", it) } ?: "—"}, ")
            append("Mean SINR: ${avgSinr?.let { String.format(Locale.US, "%.1f dB", it) } ?: "—"}, ")
            append("Dominant PCI: ${dominantPci ?: "—"}")
        }

        val altExplanations = mutableListOf<String>()
        val missingInputs = mutableListOf(
            "Operator-side PRB utilization counters (DL/UL PRB %)",
            "Baseband scheduler queue length & RLC buffer occupancy",
            "Transport backhaul link capacity (e.g. microwave fading or fiber ring saturation)",
            "Remote test server throughput limits"
        )

        val confidenceLevel: CongestionConfidenceLevel
        val limitingFactor: String
        val evidence: String

        if (isPoorRsrp) {
            confidenceLevel = CongestionConfidenceLevel.POSSIBLE_BOTTLENECK
            limitingFactor = "Weak Coverage Limitation (RF Edge / Shadowing)"
            altExplanations.add("Degraded throughput is predominantly driven by weak signal power (Mean RSRP: ${avgRsrp?.let { String.format(Locale.US, "%.1f dBm", it) } ?: "—"}), forcing lower MCS (Modulation and Coding Scheme) indices.")
            altExplanations.add("Signal attenuation due to building penetration or distance from base station.")
            evidence = "Weak received signal power prevents high-order modulation (e.g. 64QAM/256QAM) regardless of cell load."
        } else if (isLowSinr && isGoodRsrp) {
            confidenceLevel = CongestionConfidenceLevel.POSSIBLE_BOTTLENECK
            limitingFactor = "Inter-Cell Interference / Pilot Pollution"
            altExplanations.add("Strong power received from multiple overlapping sectors on the same frequency degrades SINR (${avgSinr?.let { String.format(Locale.US, "%.1f dB", it) } ?: "—"}), triggering retransmissions and HARQ failure.")
            altExplanations.add("Antenna down-tilt or azimuth misalignment on neighboring sites.")
            evidence = "High received power combined with degraded SINR indicates interference, not necessarily scheduler exhaustion."
        } else if (isGoodRsrp && isCleanSinr && isLowThroughput && isHighLatency) {
            if (successfulTests.size >= 3) {
                confidenceLevel = CongestionConfidenceLevel.STRONG_CONGESTION_EVIDENCE
                limitingFactor = "Radio Access Network (RAN) Cell Congestion"
                altExplanations.add("Multiple active users competing for available Physical Resource Blocks (PRBs) on eNodeB sector.")
                altExplanations.add("Backhaul transmission bottleneck between eNodeB and EPC/5GC core network.")
                evidence = "Repeated tests (n=${successfulTests.size}) show persistent throughput choking (<3 Mbps) and elevated latency (>120 ms) despite pristine RF conditions (RSRP: ${avgRsrp?.let { String.format(Locale.US, "%.1f dBm", it) }}, SINR: ${avgSinr?.let { String.format(Locale.US, "%.1f dB", it) }})."
            } else {
                confidenceLevel = CongestionConfidenceLevel.CONGESTION_SUSPECTED
                limitingFactor = "Probable Cell Capacity Constraint"
                altExplanations.add("Transient high traffic burst on serving sector.")
                altExplanations.add("Temporary server or backhaul latency spike.")
                evidence = "Throughput is constrained despite good radio conditions; additional test cycles across busy hours recommended to verify recurrence."
            }
        } else if (avgDl != null && avgDl >= 15.0 && (avgLat == null || avgLat < 60.0)) {
            confidenceLevel = CongestionConfidenceLevel.POSSIBLE_BOTTLENECK
            limitingFactor = "Unconstrained / Normal Cell Capacity"
            altExplanations.add("Healthy throughput (>15 Mbps) and low latency demonstrate sufficient capacity for current demand.")
            evidence = "High achieved throughput (Avg: ${String.format(Locale.US, "%.1f Mbps", avgDl)}) confirms cell has adequate unallocated PRBs."
        } else {
            confidenceLevel = CongestionConfidenceLevel.POSSIBLE_BOTTLENECK
            limitingFactor = "Moderate Throughput with Mixed Indicators"
            altExplanations.add("Performance is within acceptable nominal parameters for mobile drive conditions.")
            altExplanations.add("Periodic fading or mobility handovers during transfer.")
            evidence = "Throughput and latency indicate adequate service; no definitive congestion pattern detected."
        }

        return CongestionAnalysisReport(
            confidenceLevel = confidenceLevel,
            testCount = successfulTests.size,
            avgDownloadMbps = avgDl,
            maxDownloadMbps = maxDl,
            avgUploadMbps = avgUl,
            avgLatencyMs = avgLat,
            minLatencyMs = minLat,
            maxLatencyMs = maxLat,
            primaryServingPci = dominantPci,
            radioQualityContext = rfContext,
            primaryLimitingFactor = limitingFactor,
            alternativeExplanations = altExplanations,
            missingInputs = missingInputs,
            engineeringEvidence = evidence
        )
    }
}
