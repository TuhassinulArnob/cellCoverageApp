package com.cellscope.drive.analytics.anomaly

enum class AnomalyType(val displayName: String) {
    ABRUPT_SIGNAL_DROP("Abrupt Signal Drop"),
    SUSTAINED_WEAK_CORRIDOR("Sustained Weak Coverage Corridor"),
    PING_PONG_MOBILITY("Excessive Ping-Pong Mobility"),
    PILOT_POLLUTION("Pilot Pollution / High Interference"),
    TELEMETRY_OR_GPS_GAP("Telemetry / GPS Data Gap"),
    ACTIVE_THROUGHPUT_COLLAPSE("Active QoE Throughput Collapse")
}

enum class AnomalySeverity {
    INFO,
    WARNING,
    CRITICAL
}

data class AnomalyResult(
    val id: String,
    val sessionId: Long,
    val timestampIso: String,
    val timestampMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val anomalyType: AnomalyType,
    val severity: AnomalySeverity,
    val title: String,
    val detectionReason: String,
    val candidateHypothesis: String,
    val supportingMetrics: Map<String, Any?>,
    val confidenceScore: Float,
    val dataQualityWarning: String? = null,
    val mlAnomalyScore: Double? = null,
    val primaryContributingFeature: String? = null
)

data class AnomalyReport(
    val sessionId: Long,
    val totalAnomalies: Int,
    val criticalCount: Int,
    val warningCount: Int,
    val infoCount: Int,
    val anomalies: List<AnomalyResult>,
    val mlAgreementRatioPct: Double,
    val executiveSummary: String
)
