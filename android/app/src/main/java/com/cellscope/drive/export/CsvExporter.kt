package com.cellscope.drive.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.data.entity.CallReliabilityEventEntity
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.data.entity.NetworkContinuityEventEntity
import com.cellscope.drive.data.entity.ServiceHoleEntity
import java.io.File
import java.io.FileWriter
import com.cellscope.drive.util.TelecomTimeUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {

    // Standard Canonical CSV format (strictly preserves backend/dashboard import schema)
    private val standardHeader = listOf(
        "Timestamp",
        "Latitude",
        "Longitude",
        "Speed (km/h)",
        "Serving Cell ID",
        "Serving Cell RSRP",
        "Serving Cell RSRQ",
        "Serving Cell CINR",
        "Handover Trigger",
        "TAC",
        "EARFCN",
        "RSSI (dBm)",
        "Network Type",
        "Operator"
    ).joinToString(",")

    // Versioned Extended CSV format (preserves prefix order, appends engineering telemetry)
    private val extendedHeader = listOf(
        "Timestamp",
        "Latitude",
        "Longitude",
        "Speed (km/h)",
        "Serving Cell ID",
        "Serving Cell RSRP",
        "Serving Cell RSRQ",
        "Serving Cell CINR",
        "Handover Trigger",
        "TAC",
        "EARFCN",
        "RSSI (dBm)",
        "Network Type",
        "Operator",
        "Altitude (m)",
        "Accuracy (m)",
        "Bearing (deg)",
        "ECI",
        "CQI",
        "Timing Advance",
        "GPS Valid",
        "Transition Type"
    ).joinToString(",")

    private val networkObservationsHeader = listOf(
        "Timestamp",
        "Latitude",
        "Longitude",
        "Accuracy (m)",
        "Speed (km/h)",
        "Generation",
        "Data Network Type",
        "Voice Network Type",
        "Service State",
        "Data State",
        "Call State",
        "Serving PCI",
        "RSRP (dBm)",
        "RSRQ (dB)",
        "SINR (dB)",
        "RSSI (dBm)",
        "TAC",
        "EARFCN",
        "Operator",
        "GPS Valid"
    ).joinToString(",")

    private val networkTransitionsHeader = listOf(
        "Timestamp",
        "Latitude",
        "Longitude",
        "Accuracy (m)",
        "From Generation",
        "To Generation",
        "From Tech",
        "To Tech",
        "Transition Category",
        "From Signal (dBm)",
        "To Signal (dBm)",
        "Duration (s)",
        "Data Connectivity State",
        "Evidence Classification",
        "Details"
    ).joinToString(",")

    private val serviceHolesHeader = listOf(
        "Timestamp",
        "Latitude",
        "Longitude",
        "Accuracy (m)",
        "Hole Type",
        "Severity",
        "Duration (ms)",
        "Recovery Timestamp",
        "RSRP (dBm)",
        "Network Type",
        "Network Generation",
        "Engineering Evidence",
        "Resolution Status"
    ).joinToString(",")

    private val callReliabilityHeader = listOf(
        "Timestamp",
        "Latitude",
        "Longitude",
        "Accuracy (m)",
        "Call State",
        "Classification",
        "Previous State",
        "Network Generation",
        "Detailed Tech",
        "RSRP (dBm)",
        "Service State",
        "Call Duration (s)",
        "Risk Score Category",
        "Evidence Factors"
    ).joinToString(",")

    fun exportSessionToCsv(
        context: Context,
        session: DriveSession,
        measurements: List<LteMeasurementEntity>
    ): File {
        val exportDir = File(context.getExternalFilesDir(null), "exports").apply {
            if (!exists()) mkdirs()
        }

        val safeName = session.sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val timestamp = TelecomTimeUtils.formatBdtFilename(session.startTimeMs)
        val file = File(exportDir, "cellscope_${safeName}_$timestamp.csv")

        FileWriter(file).use { writer ->
            writer.appendLine(standardHeader)
            for (m in measurements) {
                val row = listOf(
                    escapeCsv(TelecomTimeUtils.formatCsvTimestamp(m.timestampMs, m.timestampIso)),
                    m.latitude?.toString() ?: "",
                    m.longitude?.toString() ?: "",
                    m.speedKmh?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                    m.servingPci?.toString() ?: "",
                    m.rsrpDbm?.toString() ?: "",
                    m.rsrqDb?.toString() ?: "",
                    m.sinrDb?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    m.handoverTrigger.toString(),
                    m.tac?.toString() ?: "",
                    m.earfcn?.toString() ?: "",
                    m.rssiDbm?.toString() ?: "",
                    escapeCsv(m.networkType),
                    escapeCsv(m.operatorAlpha ?: "")
                ).joinToString(",")
                writer.appendLine(row)
            }
        }

        return file
    }

    fun exportExtendedSessionToCsv(
        context: Context,
        session: DriveSession,
        measurements: List<LteMeasurementEntity>,
        activeQoeTests: List<ActiveQoeTestEntity> = emptyList(),
        continuityEvents: List<NetworkContinuityEventEntity> = emptyList(),
        serviceHoles: List<ServiceHoleEntity> = emptyList(),
        callEvents: List<CallReliabilityEventEntity> = emptyList()
    ): File {
        val exportDir = File(context.getExternalFilesDir(null), "exports").apply {
            if (!exists()) mkdirs()
        }

        val safeName = session.sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val timestamp = TelecomTimeUtils.formatBdtFilename(session.startTimeMs)
        val file = File(exportDir, "cellscope_extended_${safeName}_$timestamp.csv")

        FileWriter(file).use { writer ->
            writer.appendLine("# CellScope Extended Telemetry Format v2.0")
            writer.appendLine(extendedHeader)
            for (m in measurements) {
                val row = listOf(
                    escapeCsv(TelecomTimeUtils.formatCsvTimestamp(m.timestampMs, m.timestampIso)),
                    m.latitude?.toString() ?: "",
                    m.longitude?.toString() ?: "",
                    m.speedKmh?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                    m.servingPci?.toString() ?: "",
                    m.rsrpDbm?.toString() ?: "",
                    m.rsrqDb?.toString() ?: "",
                    m.sinrDb?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    m.handoverTrigger.toString(),
                    m.tac?.toString() ?: "",
                    m.earfcn?.toString() ?: "",
                    m.rssiDbm?.toString() ?: "",
                    escapeCsv(m.networkType),
                    escapeCsv(m.operatorAlpha ?: ""),
                    m.altitudeM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    m.accuracyM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    m.bearing?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    m.eci?.toString() ?: "",
                    m.cqi?.toString() ?: "",
                    m.timingAdvance?.toString() ?: "",
                    if (m.isGpsValid) "1" else "0",
                    escapeCsv(m.transitionType ?: "")
                ).joinToString(",")
                writer.appendLine(row)
            }

            if (activeQoeTests.isNotEmpty()) {
                writer.appendLine()
                writer.appendLine("# Active QoE Tests Section")
                writer.appendLine("TestTimestamp,Latitude,Longitude,TestType,LatencyMs,DownloadSpeedMbps,UploadSpeedMbps,BytesTransferred,DurationMs,Success,Error")
                for (t in activeQoeTests) {
                    val qoeRow = listOf(
                        escapeCsv(t.timestampIso),
                        t.latitude?.toString() ?: "",
                        t.longitude?.toString() ?: "",
                        escapeCsv(t.testType),
                        t.latencyMs?.toString() ?: "",
                        t.downloadSpeedMbps?.toString() ?: "",
                        t.uploadSpeedMbps?.toString() ?: "",
                        t.bytesTransferred.toString(),
                        t.durationMs.toString(),
                        if (t.success) "1" else "0",
                        escapeCsv(t.errorMessage ?: "")
                    ).joinToString(",")
                    writer.appendLine(qoeRow)
                }
            }

            if (continuityEvents.isNotEmpty()) {
                writer.appendLine()
                writer.appendLine("# Network Continuity Events Section")
                writer.appendLine(networkTransitionsHeader)
                for (t in continuityEvents) {
                    val tRow = listOf(
                        escapeCsv(t.timestampIso),
                        t.latitude?.toString() ?: "",
                        t.longitude?.toString() ?: "",
                        t.accuracyM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                        escapeCsv(t.fromGeneration),
                        escapeCsv(t.toGeneration),
                        escapeCsv(t.fromTech ?: ""),
                        escapeCsv(t.toTech ?: ""),
                        escapeCsv(t.transitionCategory),
                        t.fromSignalDbm?.toString() ?: "",
                        t.toSignalDbm?.toString() ?: "",
                        t.durationSeconds?.toString() ?: "",
                        escapeCsv(t.dataConnectivityState ?: ""),
                        escapeCsv(t.evidenceClassification),
                        escapeCsv(t.details)
                    ).joinToString(",")
                    writer.appendLine(tRow)
                }
            }

            if (serviceHoles.isNotEmpty()) {
                writer.appendLine()
                writer.appendLine("# Service Holes Section")
                writer.appendLine(serviceHolesHeader)
                for (h in serviceHoles) {
                    val hRow = listOf(
                        escapeCsv(h.timestampIso),
                        h.latitude?.toString() ?: "",
                        h.longitude?.toString() ?: "",
                        h.accuracyM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                        escapeCsv(h.holeType),
                        escapeCsv(h.severity),
                        h.durationMs?.toString() ?: "",
                        h.recoveryTimestampMs?.toString() ?: "",
                        h.rsrpDbm?.toString() ?: "",
                        escapeCsv(h.networkType ?: ""),
                        escapeCsv(h.networkGen ?: ""),
                        escapeCsv(h.engineeringEvidence),
                        escapeCsv(h.resolutionStatus)
                    ).joinToString(",")
                    writer.appendLine(hRow)
                }
            }

            if (callEvents.isNotEmpty()) {
                writer.appendLine()
                writer.appendLine("# Call Reliability Section")
                writer.appendLine(callReliabilityHeader)
                for (c in callEvents) {
                    val cRow = listOf(
                        escapeCsv(c.timestampIso),
                        c.latitude?.toString() ?: "",
                        c.longitude?.toString() ?: "",
                        c.accuracyM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                        escapeCsv(c.callState),
                        escapeCsv(c.classification),
                        escapeCsv(c.previousState ?: ""),
                        escapeCsv(c.networkGeneration ?: ""),
                        escapeCsv(c.detailedTech ?: ""),
                        c.rsrpDbm?.toString() ?: "",
                        escapeCsv(c.serviceState ?: ""),
                        c.callDurationSec?.toString() ?: "",
                        escapeCsv(c.riskScoreCategory),
                        escapeCsv(c.evidenceFactors)
                    ).joinToString(",")
                    writer.appendLine(cRow)
                }
            }
        }

        return file
    }

    fun exportNetworkObservationsCsv(
        context: Context,
        session: DriveSession,
        measurements: List<LteMeasurementEntity>
    ): File {
        val exportDir = File(context.getExternalFilesDir(null), "exports").apply {
            if (!exists()) mkdirs()
        }
        val safeName = session.sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val timestamp = TelecomTimeUtils.formatBdtFilename(session.startTimeMs)
        val file = File(exportDir, "cellscope_observations_${safeName}_$timestamp.csv")

        FileWriter(file).use { writer ->
            writer.appendLine(networkObservationsHeader)
            for (m in measurements) {
                val row = listOf(
                    escapeCsv(TelecomTimeUtils.formatCsvTimestamp(m.timestampMs, m.timestampIso)),
                    m.latitude?.toString() ?: "",
                    m.longitude?.toString() ?: "",
                    m.accuracyM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    m.speedKmh?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                    escapeCsv(m.networkGen ?: ""),
                    escapeCsv(m.dataNetworkType ?: m.networkType),
                    escapeCsv(m.voiceNetworkType ?: ""),
                    escapeCsv(m.serviceState ?: ""),
                    escapeCsv(m.dataActivityState ?: ""),
                    "",
                    m.servingPci?.toString() ?: "",
                    m.rsrpDbm?.toString() ?: "",
                    m.rsrqDb?.toString() ?: "",
                    m.sinrDb?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    m.rssiDbm?.toString() ?: "",
                    m.tac?.toString() ?: "",
                    m.earfcn?.toString() ?: "",
                    escapeCsv(m.operatorAlpha ?: ""),
                    if (m.isGpsValid) "1" else "0"
                ).joinToString(",")
                writer.appendLine(row)
            }
        }
        return file
    }

    fun exportNetworkTransitionsCsv(
        context: Context,
        session: DriveSession,
        transitions: List<NetworkContinuityEventEntity>
    ): File {
        val exportDir = File(context.getExternalFilesDir(null), "exports").apply {
            if (!exists()) mkdirs()
        }
        val safeName = session.sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val timestamp = TelecomTimeUtils.formatBdtFilename(session.startTimeMs)
        val file = File(exportDir, "cellscope_transitions_${safeName}_$timestamp.csv")

        FileWriter(file).use { writer ->
            writer.appendLine(networkTransitionsHeader)
            for (t in transitions) {
                val row = listOf(
                    escapeCsv(TelecomTimeUtils.formatCsvTimestamp(t.timestampMs, t.timestampIso)),
                    t.latitude?.toString() ?: "",
                    t.longitude?.toString() ?: "",
                    t.accuracyM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    escapeCsv(t.fromGeneration),
                    escapeCsv(t.toGeneration),
                    escapeCsv(t.fromTech ?: ""),
                    escapeCsv(t.toTech ?: ""),
                    escapeCsv(t.transitionCategory),
                    t.fromSignalDbm?.toString() ?: "",
                    t.toSignalDbm?.toString() ?: "",
                    t.durationSeconds?.toString() ?: "",
                    escapeCsv(t.dataConnectivityState ?: ""),
                    escapeCsv(t.evidenceClassification),
                    escapeCsv(t.details)
                ).joinToString(",")
                writer.appendLine(row)
            }
        }
        return file
    }

    fun exportServiceHolesCsv(
        context: Context,
        session: DriveSession,
        holes: List<ServiceHoleEntity>
    ): File {
        val exportDir = File(context.getExternalFilesDir(null), "exports").apply {
            if (!exists()) mkdirs()
        }
        val safeName = session.sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val timestamp = TelecomTimeUtils.formatBdtFilename(session.startTimeMs)
        val file = File(exportDir, "cellscope_service_holes_${safeName}_$timestamp.csv")

        FileWriter(file).use { writer ->
            writer.appendLine(serviceHolesHeader)
            for (h in holes) {
                val row = listOf(
                    escapeCsv(TelecomTimeUtils.formatCsvTimestamp(h.timestampMs, h.timestampIso)),
                    h.latitude?.toString() ?: "",
                    h.longitude?.toString() ?: "",
                    h.accuracyM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    escapeCsv(h.holeType),
                    escapeCsv(h.severity),
                    h.durationMs?.toString() ?: "",
                    h.recoveryTimestampMs?.let { TelecomTimeUtils.formatCsvTimestamp(it) } ?: "",
                    h.rsrpDbm?.toString() ?: "",
                    escapeCsv(h.networkType ?: ""),
                    escapeCsv(h.networkGen ?: ""),
                    escapeCsv(h.engineeringEvidence),
                    escapeCsv(h.resolutionStatus)
                ).joinToString(",")
                writer.appendLine(row)
            }
        }
        return file
    }

    fun exportCallReliabilityCsv(
        context: Context,
        session: DriveSession,
        callEvents: List<CallReliabilityEventEntity>
    ): File {
        val exportDir = File(context.getExternalFilesDir(null), "exports").apply {
            if (!exists()) mkdirs()
        }
        val safeName = session.sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val timestamp = TelecomTimeUtils.formatBdtFilename(session.startTimeMs)
        val file = File(exportDir, "cellscope_call_reliability_${safeName}_$timestamp.csv")

        FileWriter(file).use { writer ->
            writer.appendLine(callReliabilityHeader)
            for (c in callEvents) {
                val row = listOf(
                    escapeCsv(TelecomTimeUtils.formatCsvTimestamp(c.timestampMs, c.timestampIso)),
                    c.latitude?.toString() ?: "",
                    c.longitude?.toString() ?: "",
                    c.accuracyM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    escapeCsv(c.callState),
                    escapeCsv(c.classification),
                    escapeCsv(c.previousState ?: ""),
                    escapeCsv(c.networkGeneration ?: ""),
                    escapeCsv(c.detailedTech ?: ""),
                    c.rsrpDbm?.toString() ?: "",
                    escapeCsv(c.serviceState ?: ""),
                    c.callDurationSec?.toString() ?: "",
                    escapeCsv(c.riskScoreCategory),
                    escapeCsv(c.evidenceFactors)
                ).joinToString(",")
                writer.appendLine(row)
            }
        }
        return file
    }

    fun exportAllExtendedCsvs(
        context: Context,
        session: DriveSession,
        measurements: List<LteMeasurementEntity>,
        continuityEvents: List<NetworkContinuityEventEntity> = emptyList(),
        serviceHoles: List<ServiceHoleEntity> = emptyList(),
        callEvents: List<CallReliabilityEventEntity> = emptyList()
    ): List<File> {
        val files = mutableListOf<File>()
        files.add(exportNetworkObservationsCsv(context, session, measurements))
        if (continuityEvents.isNotEmpty()) {
            files.add(exportNetworkTransitionsCsv(context, session, transitions = continuityEvents))
        }
        if (serviceHoles.isNotEmpty()) {
            files.add(exportServiceHolesCsv(context, session, holes = serviceHoles))
        }
        if (callEvents.isNotEmpty()) {
            files.add(exportCallReliabilityCsv(context, session, callEvents = callEvents))
        }
        return files
    }

    fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }

    fun createShareIntent(context: Context, csvFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            csvFile
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "CellScope Drive Test Export: ${csvFile.name}")
            clipData = android.content.ClipData.newRawUri("CSV Telemetry", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
