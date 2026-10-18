package com.cellscope.drive.analytics.anomaly

import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import java.util.UUID

object RuleBasedAnomalyEngine {

    fun detectAnomalies(
        sessionId: Long,
        measurements: List<LteMeasurementEntity>,
        transitions: List<CellTransitionEntity> = emptyList(),
        qoeTests: List<ActiveQoeTestEntity> = emptyList()
    ): List<AnomalyResult> {
        if (measurements.isEmpty()) return emptyList()

        val sorted = measurements.sortedBy { it.timestampMs }
        val anomalies = mutableListOf<AnomalyResult>()

        // 1. Telemetry and GPS Gaps
        detectTelemetryAndGpsGaps(sessionId, sorted, anomalies)

        // 2. Abrupt Signal Drops
        detectAbruptSignalDrops(sessionId, sorted, anomalies)

        // 3. Sustained Weak Coverage Corridors
        detectSustainedWeakCorridors(sessionId, sorted, anomalies)

        // 4. Pilot Pollution / High Interference
        detectPilotPollution(sessionId, sorted, anomalies)

        // 5. Ping-Pong Mobility Anomalies
        detectPingPongMobility(sessionId, sorted, transitions, anomalies)

        // 6. Active Throughput Collapse
        detectActiveThroughputCollapse(sessionId, sorted, qoeTests, anomalies)

        return anomalies.sortedBy { it.timestampMs }
    }

    private fun detectTelemetryAndGpsGaps(
        sessionId: Long,
        sorted: List<LteMeasurementEntity>,
        out: MutableList<AnomalyResult>
    ) {
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val curr = sorted[i]
            val gapMs = curr.timestampMs - prev.timestampMs

            if (gapMs >= 10000L) { // Gap > 10 seconds
                val gapSec = gapMs / 1000.0
                out.add(
                    AnomalyResult(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        timestampIso = curr.timestampIso,
                        timestampMs = curr.timestampMs,
                        latitude = curr.latitude ?: prev.latitude,
                        longitude = curr.longitude ?: prev.longitude,
                        anomalyType = AnomalyType.TELEMETRY_OR_GPS_GAP,
                        severity = if (gapSec >= 30.0) AnomalySeverity.CRITICAL else AnomalySeverity.WARNING,
                        title = "Telemetry Acquisition Gap (${String.format(java.util.Locale.US, "%.1fs", gapSec)})",
                        detectionReason = "Time interval between consecutive samples was ${String.format(java.util.Locale.US, "%.1fs", gapSec)} (nominal interval: 1.0s).",
                        candidateHypothesis = "Candidate OS background process throttle, deep sleep doze cycle, or temporary application priority preemption.",
                        supportingMetrics = mapOf(
                            "gapSeconds" to gapSec,
                            "prevTimestamp" to prev.timestampIso,
                            "currTimestamp" to curr.timestampIso
                        ),
                        confidenceScore = 0.95f,
                        dataQualityWarning = "Telemetry gap will bias route spatial interpolation."
                    )
                )
            }
        }
    }

    private fun detectAbruptSignalDrops(
        sessionId: Long,
        sorted: List<LteMeasurementEntity>,
        out: MutableList<AnomalyResult>
    ) {
        // Look within 3 to 5 sample sliding window (approx 3 to 5 seconds)
        val windowSize = 4
        for (i in windowSize until sorted.size) {
            val curr = sorted[i]
            val baseline = sorted[i - windowSize]

            val currRsrp = curr.rsrpDbm ?: continue
            val baseRsrp = baseline.rsrpDbm ?: continue
            val deltaRsrp = currRsrp - baseRsrp // negative indicates drop

            if (deltaRsrp <= -20) { // >= 20 dB drop in 4 seconds
                val speed = curr.speedKmh ?: 0f
                if (speed < 110f) { // Not an ultra-high-speed bullet train crossing
                    out.add(
                        AnomalyResult(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            timestampIso = curr.timestampIso,
                            timestampMs = curr.timestampMs,
                            latitude = curr.latitude,
                            longitude = curr.longitude,
                            anomalyType = AnomalyType.ABRUPT_SIGNAL_DROP,
                            severity = if (deltaRsrp <= -28 || currRsrp < -115) AnomalySeverity.CRITICAL else AnomalySeverity.WARNING,
                            title = "Abrupt Signal Drop ($deltaRsrp dB in ${(curr.timestampMs - baseline.timestampMs) / 1000}s)",
                            detectionReason = "Serving cell RSRP collapsed from $baseRsrp dBm to $currRsrp dBm ($deltaRsrp dB) within ${(curr.timestampMs - baseline.timestampMs) / 1000}s.",
                            candidateHypothesis = "Candidate sharp shadow fading from overhead flyover, tunnel entrance, reinforced concrete structure, or antenna feeder defect.",
                            supportingMetrics = mapOf(
                                "deltaRsrpDb" to deltaRsrp,
                                "fromRsrpDbm" to baseRsrp,
                                "toRsrpDbm" to currRsrp,
                                "speedKmh" to speed,
                                "pci" to curr.servingPci
                            ),
                            confidenceScore = 0.90f
                        )
                    )
                }
            }
        }
    }

    private fun detectSustainedWeakCorridors(
        sessionId: Long,
        sorted: List<LteMeasurementEntity>,
        out: MutableList<AnomalyResult>
    ) {
        var corridorStartIdx = -1
        var corridorSampleCount = 0

        for (i in sorted.indices) {
            val m = sorted[i]
            val rsrp = m.rsrpDbm

            val isWeak = rsrp != null && rsrp <= -115
            if (isWeak) {
                if (corridorStartIdx == -1) corridorStartIdx = i
                corridorSampleCount++
            } else {
                if (corridorSampleCount >= 15) { // 15 consecutive samples (approx 15 seconds)
                    recordCorridor(sessionId, sorted, corridorStartIdx, i - 1, out)
                }
                corridorStartIdx = -1
                corridorSampleCount = 0
            }
        }
        if (corridorSampleCount >= 15) {
            recordCorridor(sessionId, sorted, corridorStartIdx, sorted.size - 1, out)
        }
    }

    private fun recordCorridor(
        sessionId: Long,
        sorted: List<LteMeasurementEntity>,
        startIdx: Int,
        endIdx: Int,
        out: MutableList<AnomalyResult>
    ) {
        val segment = sorted.subList(startIdx, endIdx + 1)
        val first = segment.first()
        val last = segment.last()
        val durationSec = (last.timestampMs - first.timestampMs) / 1000.0
        val avgRsrp = segment.mapNotNull { it.rsrpDbm }.average()
        val minRsrp = segment.mapNotNull { it.rsrpDbm }.minOrNull() ?: -125
        val avgSpeed = segment.mapNotNull { it.speedKmh?.toDouble() }.average()

        out.add(
            AnomalyResult(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                timestampIso = first.timestampIso,
                timestampMs = first.timestampMs,
                latitude = first.latitude,
                longitude = first.longitude,
                anomalyType = AnomalyType.SUSTAINED_WEAK_CORRIDOR,
                severity = if (avgRsrp < -118.0) AnomalySeverity.CRITICAL else AnomalySeverity.WARNING,
                title = "Sustained Weak Coverage Corridor (${String.format(java.util.Locale.US, "%.0fs", durationSec)}, Mean ${String.format(java.util.Locale.US, "%.1f", avgRsrp)} dBm)",
                detectionReason = "Signal remained continuously below -115 dBm for ${String.format(java.util.Locale.US, "%.0f", durationSec)}s across ${segment.size} samples (Min: $minRsrp dBm).",
                candidateHypothesis = "Candidate cellular dead zone, cell boundary coverage hole, or insufficient base-station EIRP down-link coverage.",
                supportingMetrics = mapOf(
                    "durationSeconds" to durationSec,
                    "sampleCount" to segment.size,
                    "avgRsrpDbm" to avgRsrp,
                    "minRsrpDbm" to minRsrp,
                    "avgSpeedKmh" to avgSpeed
                ),
                confidenceScore = 0.92f
            )
        )
    }

    private fun detectPilotPollution(
        sessionId: Long,
        sorted: List<LteMeasurementEntity>,
        out: MutableList<AnomalyResult>
    ) {
        for (m in sorted) {
            val servingRsrp = m.rsrpDbm ?: continue
            val neighborsStr = m.neighborCellsJson ?: continue
            if (neighborsStr.isBlank()) continue

            // Parse neighbor cells: "pci:rsrp:rsrq:earfcn;..."
            val tokens = neighborsStr.split(";")
            var strongCompetitors = 0

            for (t in tokens) {
                val parts = t.split(":")
                if (parts.size >= 2) {
                    val nRsrp = parts[1].toIntOrNull()
                    if (nRsrp != null && nRsrp >= servingRsrp - 5) {
                        strongCompetitors++
                    }
                }
            }

            // Pilot pollution condition: >= 3 competitors within 5 dB of serving, and degraded SINR (< 0 dB) or RSRQ (< -16 dB)
            val sinrBad = m.sinrDb != null && m.sinrDb < 0.0
            val rsrqBad = m.rsrqDb != null && m.rsrqDb <= -16

            if (strongCompetitors >= 3 && (sinrBad || rsrqBad)) {
                out.add(
                    AnomalyResult(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        timestampIso = m.timestampIso,
                        timestampMs = m.timestampMs,
                        latitude = m.latitude,
                        longitude = m.longitude,
                        anomalyType = AnomalyType.PILOT_POLLUTION,
                        severity = AnomalySeverity.WARNING,
                        title = "Pilot Pollution ($strongCompetitors Competing Sectors)",
                        detectionReason = "$strongCompetitors neighbor cells observed within 5 dB of serving cell (Serving: $servingRsrp dBm, SINR: ${m.sinrDb?.let { "$it dB" } ?: "—"}, RSRQ: ${m.rsrqDb ?: "—"} dB).",
                        candidateHypothesis = "Candidate pilot pollution / lack of dominant server causing destructive co-channel interference. Recommend sector down-tilt or azimuth audit.",
                        supportingMetrics = mapOf(
                            "strongCompetitorsCount" to strongCompetitors,
                            "servingPci" to m.servingPci,
                            "servingRsrpDbm" to servingRsrp,
                            "sinrDb" to m.sinrDb,
                            "rsrqDb" to m.rsrqDb
                        ),
                        confidenceScore = 0.85f
                    )
                )
            }
        }
    }

    private fun detectPingPongMobility(
        sessionId: Long,
        sorted: List<LteMeasurementEntity>,
        transitions: List<CellTransitionEntity>,
        out: MutableList<AnomalyResult>
    ) {
        if (transitions.size < 2) return

        val sortedTrans = transitions.sortedBy { it.timestampMs }
        for (i in 1 until sortedTrans.size) {
            val prev = sortedTrans[i - 1]
            val curr = sortedTrans[i]

            // Ping-pong condition: A -> B -> A within 15 seconds
            val isReturn = prev.fromPci != null && curr.toPci != null && prev.fromPci == curr.toPci
            val timeDiffMs = curr.timestampMs - prev.timestampMs

            if (isReturn && timeDiffMs <= 15000L) {
                out.add(
                    AnomalyResult(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        timestampIso = curr.timestampIso,
                        timestampMs = curr.timestampMs,
                        latitude = curr.latitude,
                        longitude = curr.longitude,
                        anomalyType = AnomalyType.PING_PONG_MOBILITY,
                        severity = AnomalySeverity.WARNING,
                        title = "Rapid Handover Ping-Pong (PCI ${prev.fromPci} ⇄ ${curr.fromPci})",
                        detectionReason = "Oscillating handover from PCI ${prev.fromPci} to ${curr.fromPci} and back to ${curr.toPci} occurred in ${(timeDiffMs / 1000.0)}s.",
                        candidateHypothesis = "Candidate inadequate handover hysteresis / Time-To-Trigger (TTT) parameterization or overlapping coverage boundaries with rapid fading.",
                        supportingMetrics = mapOf(
                            "pciA" to prev.fromPci,
                            "pciB" to curr.fromPci,
                            "returnTimeSeconds" to (timeDiffMs / 1000.0),
                            "fromRsrp" to curr.fromRsrp,
                            "toRsrp" to curr.toRsrp
                        ),
                        confidenceScore = 0.88f
                    )
                )
            }
        }
    }

    private fun detectActiveThroughputCollapse(
        sessionId: Long,
        sorted: List<LteMeasurementEntity>,
        qoeTests: List<ActiveQoeTestEntity>,
        out: MutableList<AnomalyResult>
    ) {
        if (qoeTests.isEmpty()) return

        for (test in qoeTests) {
            val speedMbps = test.downloadSpeedMbps ?: test.uploadSpeedMbps ?: continue
            val testTime = test.timestampMs

            // Find closest RF measurement at test time
            val closestRf = sorted.minByOrNull { kotlin.math.abs(it.timestampMs - testTime) } ?: continue

            val rsrp = closestRf.rsrpDbm
            val sinr = closestRf.sinrDb

            // If radio signal is Good/Excellent (RSRP >= -95 dBm and SINR >= 5.0 dB) but active throughput collapsed (< 0.5 Mbps)
            val goodRadio = (rsrp != null && rsrp >= -95) && (sinr != null && sinr >= 5.0)
            val collapsedThroughput = speedMbps < 0.5 && (test.success || test.bytesTransferred > 50000L)

            if (goodRadio && collapsedThroughput) {
                out.add(
                    AnomalyResult(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        timestampIso = test.timestampIso,
                        timestampMs = test.timestampMs,
                        latitude = closestRf.latitude,
                        longitude = closestRf.longitude,
                        anomalyType = AnomalyType.ACTIVE_THROUGHPUT_COLLAPSE,
                        severity = AnomalySeverity.CRITICAL,
                        title = "Active QoE Throughput Collapse (${String.format(java.util.Locale.US, "%.2f", speedMbps)} Mbps)",
                        detectionReason = "Active ${test.testType} achieved only ${String.format(java.util.Locale.US, "%.2f", speedMbps)} Mbps despite favorable RF conditions (RSRP $rsrp dBm, SINR ${sinr} dB).",
                        candidateHypothesis = "Candidate backhaul transport bottleneck, eNodeB/gNodeB PRB congestion, core network throttling, or server-side bandwidth exhaustion.",
                        supportingMetrics = mapOf(
                            "throughputMbps" to speedMbps,
                            "testType" to test.testType,
                            "servingRsrpDbm" to rsrp,
                            "sinrDb" to sinr,
                            "bytesTransferred" to test.bytesTransferred
                        ),
                        confidenceScore = 0.92f
                    )
                )
            }
        }
    }
}
