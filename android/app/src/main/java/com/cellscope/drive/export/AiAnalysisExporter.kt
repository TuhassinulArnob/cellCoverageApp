package com.cellscope.drive.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.cellscope.drive.analytics.anomaly.AnomalyReport
import com.cellscope.drive.data.entity.DriveSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AiAnalysisExporter {

    fun exportAnalysisToMarkdown(
        context: Context,
        session: DriveSession,
        report: AnomalyReport
    ): File {
        val exportDir = File(context.getExternalFilesDir(null), "analysis_reports").apply {
            if (!exists()) mkdirs()
        }

        val safeName = session.sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(session.startTimeMs))
        val file = File(exportDir, "CellScope_AI_Analysis_${safeName}_$timestamp.md")

        val sb = StringBuilder()
        sb.append("# CellScope Explainable AI Network Anomaly & Optimization Report\n\n")
        sb.append("> **Project Lead & Telecom Engineer:** Sarkar Tuhassinul Arnob  \n")
        sb.append("> **Contact:** arnob.ict.iu@gmail.com  \n")
        sb.append("> **Generated Timestamp:** ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}  \n\n")

        sb.append("## 1. Survey & Session Context\n\n")
        sb.append("- **Session Name:** ${session.sessionName}\n")
        sb.append("- **Session ID:** #${session.id}\n")
        sb.append("- **Start Time:** ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(session.startTimeMs))}\n")
        val durationSec = if (session.endTimeMs != null) (session.endTimeMs!! - session.startTimeMs) / 1000 else 0
        sb.append("- **Duration:** ${String.format(Locale.US, "%02d:%02d:%02d", durationSec / 3600, (durationSec % 3600) / 60, durationSec % 60)}\n")
        sb.append("- **Total Distance:** ${String.format(Locale.US, "%.2f km", session.totalDistanceM / 1000.0)}\n")
        sb.append("- **Recorded Telemetry Samples:** ${session.sampleCount}\n")
        sb.append("- **Mean RSRP:** ${session.avgRsrpDbm?.let { String.format(Locale.US, "%.1f dBm", it) } ?: "—"}\n\n")

        sb.append("## 2. AI Anomaly Executive Summary\n\n")
        sb.append("${report.executiveSummary}\n\n")
        sb.append("- **Total Identified Anomalies:** ${report.totalAnomalies}\n")
        sb.append("  - **Critical Severity:** ${report.criticalCount}\n")
        sb.append("  - **Warning Severity:** ${report.warningCount}\n")
        sb.append("  - **Advisory Info:** ${report.infoCount}\n")
        sb.append("- **ML & Rule Agreement Consensus:** ${String.format(Locale.US, "%.1f%%", report.mlAgreementRatioPct)}\n\n")

        sb.append("## 3. Detailed Diagnostic Findings & Hypotheses\n\n")
        if (report.anomalies.isEmpty()) {
            sb.append("No network anomalies or telemetry disruptions met the detection thresholds for this survey.\n\n")
        } else {
            for ((idx, a) in report.anomalies.withIndex()) {
                sb.append("### Finding #${idx + 1}: ${a.title} [${a.severity}]\n\n")
                sb.append("- **Finding ID:** `${a.id}`\n")
                sb.append("- **Category:** ${a.anomalyType.displayName}\n")
                sb.append("- **Timestamp:** ${a.timestampIso}\n")
                if (a.latitude != null && a.longitude != null) {
                    sb.append("- **Geographic Coordinates:** ${String.format(Locale.US, "%.5f, %.5f", a.latitude, a.longitude)}\n")
                }
                sb.append("- **Detection Reason:** ${a.detectionReason}\n")
                sb.append("- **Root-Cause Hypothesis:** ${a.candidateHypothesis}\n")
                sb.append("- **Model Confidence Score:** ${(a.confidenceScore * 100).toInt()}%\n")
                if (a.mlAnomalyScore != null) {
                    sb.append("- **Isolation Forest Anomaly Score:** ${String.format(Locale.US, "%.3f", a.mlAnomalyScore)}\n")
                }
                if (a.primaryContributingFeature != null) {
                    sb.append("- **Primary Contributing Metric:** `${a.primaryContributingFeature}`\n")
                }
                if (a.supportingMetrics.isNotEmpty()) {
                    sb.append("- **Supporting Telemetry:**\n")
                    for ((k, v) in a.supportingMetrics) {
                        sb.append("  - **$k:** $v\n")
                    }
                }
                if (a.dataQualityWarning != null) {
                    sb.append("- **Data Quality Caveat:** ${a.dataQualityWarning}\n")
                }
                sb.append("\n**Recommended Engineering Action:**\n")
                val recAction = when (a.anomalyType) {
                    com.cellscope.drive.analytics.anomaly.AnomalyType.ABRUPT_SIGNAL_DROP ->
                        "Inspect physical clutter and terrain along the route. Verify antenna down-tilt, azimuth alignment, and possible building shadowing."
                    com.cellscope.drive.analytics.anomaly.AnomalyType.SUSTAINED_WEAK_CORRIDOR ->
                        "Target area for small cell deployment, indoor DAS, or remote radio head (RRH) coverage extension."
                    com.cellscope.drive.analytics.anomaly.AnomalyType.PING_PONG_MOBILITY ->
                        "Optimize handover hysteresis parameters (Event A3 offset: Hysteresis Hys, Time-to-Trigger TTT). Ensure serving cell threshold prevents rapid oscillation."
                    com.cellscope.drive.analytics.anomaly.AnomalyType.PILOT_POLLUTION ->
                        "Reduce antenna tilt or transmission power on non-dominant overlapping sectors to restore a single dominant serving pilot."
                    com.cellscope.drive.analytics.anomaly.AnomalyType.TELEMETRY_OR_GPS_GAP ->
                        "Verify device battery optimization settings. Ensure background location is set to 'Allow all the time' during surveys."
                    com.cellscope.drive.analytics.anomaly.AnomalyType.ACTIVE_THROUGHPUT_COLLAPSE ->
                        "Correlate throughput drop with radio layer SINR/CQI. If radio quality is strong, verify backhaul capacity, core network latency, or transport congestion."
                }
                sb.append("> $recAction\n\n---\n\n")
            }
        }

        sb.append("## 4. Methodology, Limitations & Telecom Disclaimers\n\n")
        sb.append("1. **Upper-Layer Telemetry:** Diagnostics are derived from native Android TelephonyManager and LocationManager telemetry. Standard user equipment modems do not expose raw RF spectrum, hardware ACLR, or internal baseband scheduling queues.\n")
        sb.append("2. **Hypothesis Distinction:** All flagged anomalies represent mathematical and rule-based diagnostic hypotheses to guide field optimization teams. They do not constitute formal carrier NOC incident alerts.\n")
        sb.append("3. **Confidentiality:** CellScope v2.5 Enterprise field assessment document.\n")

        FileWriter(file).use { writer ->
            writer.write(sb.toString())
        }

        return file
    }

    fun exportAnalysisToJson(
        context: Context,
        session: DriveSession,
        report: AnomalyReport
    ): File {
        val exportDir = File(context.getExternalFilesDir(null), "analysis_reports").apply {
            if (!exists()) mkdirs()
        }

        val safeName = session.sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(session.startTimeMs))
        val file = File(exportDir, "CellScope_AI_Analysis_${safeName}_$timestamp.json")

        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"project\": \"CellScope\",\n")
        sb.append("  \"lead\": \"Sarkar Tuhassinul Arnob\",\n")
        sb.append("  \"contact\": \"arnob.ict.iu@gmail.com\",\n")
        sb.append("  \"reportGeneratedAt\": \"${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())}\",\n")
        sb.append("  \"sessionId\": ${session.id},\n")
        sb.append("  \"sessionName\": \"${escapeJson(session.sessionName)}\",\n")
        sb.append("  \"totalDistanceM\": ${session.totalDistanceM},\n")
        sb.append("  \"sampleCount\": ${session.sampleCount},\n")
        sb.append("  \"totalAnomalies\": ${report.totalAnomalies},\n")
        sb.append("  \"criticalCount\": ${report.criticalCount},\n")
        sb.append("  \"warningCount\": ${report.warningCount},\n")
        sb.append("  \"infoCount\": ${report.infoCount},\n")
        sb.append("  \"mlAgreementRatioPct\": ${report.mlAgreementRatioPct},\n")
        sb.append("  \"executiveSummary\": \"${escapeJson(report.executiveSummary)}\",\n")
        sb.append("  \"anomalies\": [\n")

        val anomalyStrings = report.anomalies.map { a ->
            val ab = StringBuilder()
            ab.append("    {\n")
            ab.append("      \"id\": \"${a.id}\",\n")
            ab.append("      \"type\": \"${a.anomalyType.name}\",\n")
            ab.append("      \"severity\": \"${a.severity.name}\",\n")
            ab.append("      \"title\": \"${escapeJson(a.title)}\",\n")
            ab.append("      \"timestampIso\": \"${a.timestampIso}\",\n")
            ab.append("      \"latitude\": ${a.latitude ?: "null"},\n")
            ab.append("      \"longitude\": ${a.longitude ?: "null"},\n")
            ab.append("      \"reason\": \"${escapeJson(a.detectionReason)}\",\n")
            ab.append("      \"hypothesis\": \"${escapeJson(a.candidateHypothesis)}\",\n")
            ab.append("      \"confidenceScore\": ${a.confidenceScore},\n")
            ab.append("      \"mlAnomalyScore\": ${a.mlAnomalyScore ?: "null"},\n")
            ab.append("      \"primaryFeature\": ${a.primaryContributingFeature?.let { "\"${escapeJson(it)}\"" } ?: "null"},\n")
            ab.append("      \"dataQualityWarning\": ${a.dataQualityWarning?.let { "\"${escapeJson(it)}\"" } ?: "null"},\n")
            val metricEntries = a.supportingMetrics.entries.map { (k, v) ->
                "        \"${escapeJson(k)}\": \"${escapeJson(v?.toString() ?: "")}\""
            }.joinToString(",\n")
            ab.append("      \"supportingMetrics\": {\n$metricEntries\n      }\n")
            ab.append("    }")
            ab.toString()
        }
        sb.append(anomalyStrings.joinToString(",\n"))
        sb.append("\n  ]\n}")

        FileWriter(file).use { writer ->
            writer.write(sb.toString())
        }

        return file
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    fun createShareIntent(context: Context, file: File, mimeType: String): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "CellScope AI Analysis Report: ${file.name}")
            clipData = android.content.ClipData.newRawUri("AI Analysis Report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
