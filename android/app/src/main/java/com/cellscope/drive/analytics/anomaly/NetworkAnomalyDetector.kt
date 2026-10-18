package com.cellscope.drive.analytics.anomaly

import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import java.util.Locale
import java.util.UUID

object NetworkAnomalyDetector {

    fun analyzeSession(
        sessionId: Long,
        measurements: List<LteMeasurementEntity>,
        transitions: List<CellTransitionEntity> = emptyList(),
        qoeTests: List<ActiveQoeTestEntity> = emptyList(),
        scorer: ForestAnomalyScorer = ForestAnomalyScorer()
    ): AnomalyReport {
        if (measurements.isEmpty()) {
            return AnomalyReport(
                sessionId = sessionId,
                totalAnomalies = 0,
                criticalCount = 0,
                warningCount = 0,
                infoCount = 0,
                anomalies = emptyList(),
                mlAgreementRatioPct = 100.0,
                executiveSummary = "No drive-test measurements available for anomaly analysis."
            )
        }

        val sorted = measurements.sortedBy { it.timestampMs }

        // 1. Execute Rule-Based Engine
        val ruleAnomalies = RuleBasedAnomalyEngine.detectAnomalies(
            sessionId = sessionId,
            measurements = sorted,
            transitions = transitions,
            qoeTests = qoeTests
        ).toMutableList()

        // 2. Score samples using Isolation Forest
        val enrichedAnomalies = mutableListOf<AnomalyResult>()
        var mlAgreeCount = 0

        for (a in ruleAnomalies) {
            // Find closest measurement
            val closest = sorted.minByOrNull { kotlin.math.abs(it.timestampMs - a.timestampMs) }
            val mlResult = closest?.let { m ->
                val prevM = sorted.getOrNull(sorted.indexOf(m) - 1)
                val dt = if (prevM != null) (m.timestampMs - prevM.timestampMs) / 1000.0 else 1.0
                val dRsrp = if (prevM != null && m.rsrpDbm != null && prevM.rsrpDbm != null) {
                    (m.rsrpDbm - prevM.rsrpDbm).toDouble()
                } else 0.0

                scorer.score(
                    rsrpDbm = m.rsrpDbm?.toDouble() ?: -95.0,
                    rsrqDb = m.rsrqDb?.toDouble() ?: -12.0,
                    sinrDb = m.sinrDb ?: 10.0,
                    speedKmh = m.speedKmh?.toDouble() ?: 0.0,
                    deltaRsrp = dRsrp,
                    sampleDt = dt
                )
            }

            if (mlResult != null && mlResult.anomalyScore >= 0.52) {
                mlAgreeCount++
            }

            enrichedAnomalies.add(
                a.copy(
                    mlAnomalyScore = mlResult?.anomalyScore,
                    primaryContributingFeature = mlResult?.primaryContributingFeature
                )
            )
        }

        // 3. Scan for subtle statistical anomalies flagged strongly by ML (Score >= 0.70)
        // that evaded deterministic rule limits
        val mlOnlyCandidates = mutableListOf<AnomalyResult>()
        for (i in 1 until sorted.size) {
            val curr = sorted[i]
            val prev = sorted[i - 1]
            val dt = ((curr.timestampMs - prev.timestampMs) / 1000.0).coerceAtLeast(0.1)
            val dRsrp = if (curr.rsrpDbm != null && prev.rsrpDbm != null) (curr.rsrpDbm - prev.rsrpDbm).toDouble() else 0.0

            val ml = scorer.score(
                rsrpDbm = curr.rsrpDbm?.toDouble() ?: -95.0,
                rsrqDb = curr.rsrqDb?.toDouble() ?: -12.0,
                sinrDb = curr.sinrDb ?: 10.0,
                speedKmh = curr.speedKmh?.toDouble() ?: 0.0,
                deltaRsrp = dRsrp,
                sampleDt = dt
            )

            // If ML isolation score is very high (>= 0.72) and not already near an existing anomaly
            if (ml.anomalyScore >= 0.72) {
                val alreadyCovered = enrichedAnomalies.any { kotlin.math.abs(it.timestampMs - curr.timestampMs) < 5000L }
                if (!alreadyCovered) {
                    mlOnlyCandidates.add(
                        AnomalyResult(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            timestampIso = curr.timestampIso,
                            timestampMs = curr.timestampMs,
                            latitude = curr.latitude,
                            longitude = curr.longitude,
                            anomalyType = AnomalyType.PILOT_POLLUTION,
                            severity = AnomalySeverity.INFO,
                            title = "Multi-Variate Telemetry Outlier (Score: ${String.format(Locale.US, "%.2f", ml.anomalyScore)})",
                            detectionReason = "Isolation Forest isolated telemetry in unusually shallow tree paths (depth ${ml.averagePathLength}). Top discriminating dimension: ${ml.primaryContributingFeature}.",
                            candidateHypothesis = "Candidate non-linear multi-variate RF degradation (abnormal joint distribution of RSRP, SINR, and mobility velocity).",
                            supportingMetrics = mapOf(
                                "rsrpDbm" to curr.rsrpDbm,
                                "rsrqDb" to curr.rsrqDb,
                                "sinrDb" to curr.sinrDb,
                                "speedKmh" to curr.speedKmh,
                                "mlAnomalyScore" to ml.anomalyScore,
                                "primaryFeature" to ml.primaryContributingFeature
                            ),
                            confidenceScore = 0.75f,
                            mlAnomalyScore = ml.anomalyScore,
                            primaryContributingFeature = ml.primaryContributingFeature
                        )
                    )
                }
            }
        }

        // Limit ML-only outliers to avoid alert spam
        val finalAnomalies = (enrichedAnomalies + mlOnlyCandidates.take(5)).sortedBy { it.timestampMs }

        val critCount = finalAnomalies.count { it.severity == AnomalySeverity.CRITICAL }
        val warnCount = finalAnomalies.count { it.severity == AnomalySeverity.WARNING }
        val infoCount = finalAnomalies.count { it.severity == AnomalySeverity.INFO }

        val agreementRate = if (ruleAnomalies.isNotEmpty()) {
            (mlAgreeCount.toDouble() / ruleAnomalies.size) * 100.0
        } else 100.0

        val execSummary = buildExecutiveSummary(
            total = finalAnomalies.size,
            critical = critCount,
            warning = warnCount,
            info = infoCount,
            agreement = agreementRate,
            anomalies = finalAnomalies
        )

        return AnomalyReport(
            sessionId = sessionId,
            totalAnomalies = finalAnomalies.size,
            criticalCount = critCount,
            warningCount = warnCount,
            infoCount = infoCount,
            anomalies = finalAnomalies,
            mlAgreementRatioPct = (agreementRate * 10.0).toInt() / 10.0,
            executiveSummary = execSummary
        )
    }

    private fun buildExecutiveSummary(
        total: Int,
        critical: Int,
        warning: Int,
        info: Int,
        agreement: Double,
        anomalies: List<AnomalyResult>
    ): String {
        val sb = StringBuilder()
        if (total == 0) {
            sb.append("Clean Drive Route: Zero network anomalies or radio impairments detected. Telemetry consistent with 3GPP nominal operating envelopes.")
        } else {
            sb.append("Detected $total candidate network anomalies ($critical Critical, $warning Warning, $info Advisory). ")
            sb.append("Machine learning Isolation Forest consensus agreement: ${String.format(Locale.US, "%.1f%%", agreement)}.\n\n")

            val grouped = anomalies.groupBy { it.anomalyType }
            for ((type, items) in grouped) {
                sb.append("• ${type.displayName}: ${items.size} occurrence(s) - e.g., ${items.first().title}\n")
            }
        }
        return sb.toString().trimEnd()
    }
}
