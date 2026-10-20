package com.cellscope.drive.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.data.entity.CallReliabilityEventEntity
import com.cellscope.drive.data.entity.CellTransitionEntity
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
        "Transition Type",
        "Serving RAT",
        "Channel Number",
        "Channel Type",
        "Primary Signal (dBm)",
        "Signal Metric Type",
        "Secondary Signal (dB)"
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

    private val cellTransitionsHeader = listOf(
        "Timestamp",
        "Latitude",
        "Longitude",
        "Event Type",
        "From RAT",
        "To RAT",
        "From PCI",
        "To PCI",
        "From Channel",
        "To Channel",
        "From Cell ID",
        "To Cell ID",
        "From Signal (dBm)",
        "To Signal (dBm)",
        "From TAC/LAC",
        "To TAC/LAC",
        "Confidence Score",
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

    val activeQoeCanonicalHeader = listOf(
        "test_id",
        "session_id",
        "timestamp_iso",
        "timestamp_epoch_ms",
        "test_trigger",
        "test_status",
        "skipped_reason",
        "scheduled_epoch_ms",
        "execution_delay_ms",
        "latitude",
        "longitude",
        "gps_accuracy_m",
        "serving_rat",
        "serving_pci",
        "serving_channel",
        "serving_rsrp_dbm",
        "endpoint_url",
        "download_throughput_mbps",
        "upload_throughput_mbps",
        "download_bytes_transferred",
        "upload_bytes_transferred",
        "download_duration_ms",
        "upload_duration_ms",
        "rtt_median_ms",
        "rtt_mean_ms",
        "rtt_p95_ms",
        "jitter_ms",
        "probe_loss_pct",
        "probes_attempted",
        "probes_successful",
        "classification",
        "data_provenance"
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
                    (m.channelNumber ?: m.earfcn)?.toString() ?: "",
                    (m.rssiDbm ?: if (m.servingRat == "2G") m.primarySignalDbm else null)?.toString() ?: "",
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
        callEvents: List<CallReliabilityEventEntity> = emptyList(),
        cellTransitions: List<CellTransitionEntity> = emptyList()
    ): File {
        val exportDir = File(context.getExternalFilesDir(null), "exports").apply {
            if (!exists()) mkdirs()
        }

        val safeName = session.sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val timestamp = TelecomTimeUtils.formatBdtFilename(session.startTimeMs)
        val file = File(exportDir, "cellscope_extended_${safeName}_$timestamp.csv")

        FileWriter(file).use { writer ->
            writer.appendLine("# CellScope Extended Telemetry Format v3.0")
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
                    (m.channelNumber ?: m.earfcn)?.toString() ?: "",
                    (m.rssiDbm ?: if (m.servingRat == "2G") m.primarySignalDbm else null)?.toString() ?: "",
                    escapeCsv(m.networkType),
                    escapeCsv(m.operatorAlpha ?: ""),
                    m.altitudeM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    m.accuracyM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    m.bearing?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    m.eci?.toString() ?: "",
                    m.cqi?.toString() ?: "",
                    m.timingAdvance?.toString() ?: "",
                    if (m.isGpsValid) "1" else "0",
                    escapeCsv(m.transitionType ?: ""),
                    escapeCsv(m.servingRat ?: ""),
                    m.channelNumber?.toString() ?: "",
                    escapeCsv(m.channelType ?: ""),
                    m.primarySignalDbm?.toString() ?: "",
                    escapeCsv(m.signalMetricType ?: ""),
                    m.secondarySignalDb?.let { String.format(Locale.US, "%.1f", it) } ?: ""
                ).joinToString(",")
                writer.appendLine(row)
            }

            if (activeQoeTests.isNotEmpty()) {
                writer.appendLine()
                writer.appendLine("# Active QoE & Congestion Section")
                writer.appendLine(activeQoeCanonicalHeader)
                for (t in activeQoeTests) {
                    val execDelay = if (t.scheduledTimestampMs != null) (t.timestampMs - t.scheduledTimestampMs!!).coerceAtLeast(0L) else null
                    val qoeRow = listOf(
                        t.id.toString(),
                        t.sessionId.toString(),
                        escapeCsv(t.timestampIso),
                        t.timestampMs.toString(),
                        escapeCsv(t.testType),
                        escapeCsv(t.testStatus),
                        escapeCsv(t.skippedReason ?: ""),
                        t.scheduledTimestampMs?.toString() ?: "",
                        execDelay?.toString() ?: "",
                        t.latitude?.toString() ?: "",
                        t.longitude?.toString() ?: "",
                        t.gpsAccuracyM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                        escapeCsv(t.servingRat ?: ""),
                        t.servingPci?.toString() ?: "",
                        t.servingEarfcn?.toString() ?: "",
                        t.rsrpDbm?.toString() ?: "",
                        escapeCsv(t.endpointUrl ?: ""),
                        t.downloadSpeedMbps?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                        t.uploadSpeedMbps?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                        (if (t.dlBytes > 0) t.dlBytes else if (t.bytesTransferred > 0) t.bytesTransferred else null)?.toString() ?: "",
                        (if (t.ulBytes > 0) t.ulBytes else null)?.toString() ?: "",
                        (if (t.dlDurationMs > 0) t.dlDurationMs else if (t.durationMs > 0) t.durationMs else null)?.toString() ?: "",
                        (if (t.ulDurationMs > 0) t.ulDurationMs else null)?.toString() ?: "",
                        t.latencyMs?.toString() ?: "",
                        t.meanLatencyMs?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                        t.p95LatencyMs?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                        t.jitterMs?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                        t.packetLossPct?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                        t.probeCount.toString(),
                        t.successfulProbeCount.toString(),
                        escapeCsv(t.congestionClassification ?: ""),
                        escapeCsv(t.dataProvenance)
                    ).joinToString(",")
                    writer.appendLine(qoeRow)
                }
            }

            if (cellTransitions.isNotEmpty()) {
                writer.appendLine()
                writer.appendLine("# Cell Transitions (Mobility & Handover) Section")
                writer.appendLine(cellTransitionsHeader)
                for (t in cellTransitions) {
                    val cRow = listOf(
                        escapeCsv(TelecomTimeUtils.formatCsvTimestamp(t.timestampMs, t.timestampIso)),
                        t.latitude?.toString() ?: "",
                        t.longitude?.toString() ?: "",
                        escapeCsv(t.eventType),
                        escapeCsv(t.fromRat ?: ""),
                        escapeCsv(t.toRat ?: ""),
                        t.fromPci?.toString() ?: "",
                        t.toPci?.toString() ?: "",
                        t.fromChannel?.toString() ?: "",
                        t.toChannel?.toString() ?: "",
                        t.fromCellId?.toString() ?: "",
                        t.toCellId?.toString() ?: "",
                        t.fromRsrp?.toString() ?: "",
                        t.toRsrp?.toString() ?: "",
                        t.fromTac?.toString() ?: "",
                        t.toTac?.toString() ?: "",
                        String.format(Locale.US, "%.2f", t.confidenceScore),
                        escapeCsv(t.details)
                    ).joinToString(",")
                    writer.appendLine(cRow)
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
                    (m.rssiDbm ?: if (m.servingRat == "2G") m.primarySignalDbm else null)?.toString() ?: "",
                    m.tac?.toString() ?: "",
                    (m.channelNumber ?: m.earfcn)?.toString() ?: "",
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

    fun exportCellTransitionsCsv(
        context: Context,
        session: DriveSession,
        transitions: List<CellTransitionEntity>
    ): File {
        val exportDir = File(context.getExternalFilesDir(null), "exports").apply {
            if (!exists()) mkdirs()
        }
        val safeName = session.sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val timestamp = TelecomTimeUtils.formatBdtFilename(session.startTimeMs)
        val file = File(exportDir, "cellscope_cell_transitions_${safeName}_$timestamp.csv")

        FileWriter(file).use { writer ->
            writer.appendLine(cellTransitionsHeader)
            for (t in transitions) {
                val row = listOf(
                    escapeCsv(TelecomTimeUtils.formatCsvTimestamp(t.timestampMs, t.timestampIso)),
                    t.latitude?.toString() ?: "",
                    t.longitude?.toString() ?: "",
                    escapeCsv(t.eventType),
                    escapeCsv(t.fromRat ?: ""),
                    escapeCsv(t.toRat ?: ""),
                    t.fromPci?.toString() ?: "",
                    t.toPci?.toString() ?: "",
                    t.fromChannel?.toString() ?: "",
                    t.toChannel?.toString() ?: "",
                    t.fromCellId?.toString() ?: "",
                    t.toCellId?.toString() ?: "",
                    t.fromRsrp?.toString() ?: "",
                    t.toRsrp?.toString() ?: "",
                    t.fromTac?.toString() ?: "",
                    t.toTac?.toString() ?: "",
                    String.format(Locale.US, "%.2f", t.confidenceScore),
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

    fun exportActiveQoeCsv(
        context: Context,
        session: DriveSession,
        qoeTests: List<ActiveQoeTestEntity>
    ): File {
        val exportDir = File(context.getExternalFilesDir(null), "exports").apply {
            if (!exists()) mkdirs()
        }
        val safeName = session.sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val timestamp = TelecomTimeUtils.formatBdtFilename(session.startTimeMs)
        val file = File(exportDir, "cellscope_qoe_tests_${safeName}_$timestamp.csv")

        FileWriter(file).use { writer ->
            writer.appendLine(activeQoeCanonicalHeader)
            for (t in qoeTests) {
                val execDelay = if (t.scheduledTimestampMs != null) (t.timestampMs - t.scheduledTimestampMs!!).coerceAtLeast(0L) else null
                val row = listOf(
                    t.id.toString(),
                    t.sessionId.toString(),
                    escapeCsv(t.timestampIso),
                    t.timestampMs.toString(),
                    escapeCsv(t.testType),
                    escapeCsv(t.testStatus),
                    escapeCsv(t.skippedReason ?: ""),
                    t.scheduledTimestampMs?.toString() ?: "",
                    execDelay?.toString() ?: "",
                    t.latitude?.toString() ?: "",
                    t.longitude?.toString() ?: "",
                    t.gpsAccuracyM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    escapeCsv(t.servingRat ?: ""),
                    t.servingPci?.toString() ?: "",
                    t.servingEarfcn?.toString() ?: "",
                    t.rsrpDbm?.toString() ?: "",
                    escapeCsv(t.endpointUrl ?: ""),
                    t.downloadSpeedMbps?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                    t.uploadSpeedMbps?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                    (if (t.dlBytes > 0) t.dlBytes else if (t.bytesTransferred > 0) t.bytesTransferred else null)?.toString() ?: "",
                    (if (t.ulBytes > 0) t.ulBytes else null)?.toString() ?: "",
                    (if (t.dlDurationMs > 0) t.dlDurationMs else if (t.durationMs > 0) t.durationMs else null)?.toString() ?: "",
                    (if (t.ulDurationMs > 0) t.ulDurationMs else null)?.toString() ?: "",
                    t.latencyMs?.toString() ?: "",
                    t.meanLatencyMs?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    t.p95LatencyMs?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    t.jitterMs?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                    t.packetLossPct?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    t.probeCount.toString(),
                    t.successfulProbeCount.toString(),
                    escapeCsv(t.congestionClassification ?: ""),
                    escapeCsv(t.dataProvenance)
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
        callEvents: List<CallReliabilityEventEntity> = emptyList(),
        cellTransitions: List<CellTransitionEntity> = emptyList(),
        qoeTests: List<ActiveQoeTestEntity> = emptyList()
    ): List<File> {
        val files = mutableListOf<File>()
        files.add(exportNetworkObservationsCsv(context, session, measurements))
        if (continuityEvents.isNotEmpty()) {
            files.add(exportNetworkTransitionsCsv(context, session, transitions = continuityEvents))
        }
        if (cellTransitions.isNotEmpty()) {
            files.add(exportCellTransitionsCsv(context, session, transitions = cellTransitions))
        }
        if (serviceHoles.isNotEmpty()) {
            files.add(exportServiceHolesCsv(context, session, holes = serviceHoles))
        }
        if (callEvents.isNotEmpty()) {
            files.add(exportCallReliabilityCsv(context, session, callEvents = callEvents))
        }
        if (qoeTests.isNotEmpty()) {
            files.add(exportActiveQoeCsv(context, session, qoeTests = qoeTests))
        }
        return files
    }

    fun exportAnalysisSummaryToCsv(
        context: Context,
        session: DriveSession,
        measurements: List<LteMeasurementEntity>
    ): File {
        val exportDir = File(context.getExternalFilesDir(null), "exports").apply {
            if (!exists()) mkdirs()
        }
        val safeSessionName = session.sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "cellscope_summary_${safeSessionName}_${timestamp}.csv"
        val file = File(exportDir, fileName)

        val valid = measurements.filter { it.isGpsValid && it.latitude != null && it.longitude != null }
        val rsrpList = valid.mapNotNull { it.rsrpDbm }
        val rsrqList = valid.mapNotNull { it.rsrqDb }
        val sinrList = valid.mapNotNull { it.sinrDb }
        val speedList = valid.mapNotNull { it.speedKmh }

        val avgRsrp = if (rsrpList.isNotEmpty()) rsrpList.average() else null
        val minRsrp = rsrpList.minOrNull()
        val maxRsrp = rsrpList.maxOrNull()
        val avgRsrq = if (rsrqList.isNotEmpty()) rsrqList.average() else null
        val avgSinr = if (sinrList.isNotEmpty()) sinrList.average() else null

        val excellentCount = rsrpList.count { it >= -80 }
        val goodCount = rsrpList.count { it in -90..-81 }
        val fairCount = rsrpList.count { it in -100..-91 }
        val poorCount = rsrpList.count { it < -100 }
        val totalSignal = rsrpList.size.coerceAtLeast(1)

        val gen5g = measurements.count { (it.networkGen ?: it.networkType).contains("5G", ignoreCase = true) }
        val gen4g = measurements.count { (it.networkGen ?: it.networkType).contains("4G", ignoreCase = true) || (it.networkGen ?: it.networkType).contains("LTE", ignoreCase = true) }
        val gen3g = measurements.count { (it.networkGen ?: it.networkType).contains("3G", ignoreCase = true) || (it.networkGen ?: it.networkType).contains("UMTS", ignoreCase = true) || (it.networkGen ?: it.networkType).contains("HSPA", ignoreCase = true) }
        val gen2g = measurements.count { (it.networkGen ?: it.networkType).contains("2G", ignoreCase = true) || (it.networkGen ?: it.networkType).contains("GSM", ignoreCase = true) || (it.networkGen ?: it.networkType).contains("EDGE", ignoreCase = true) }
        val totalGen = measurements.size.coerceAtLeast(1)

        FileWriter(file).use { writer ->
            writer.write("Metric Category,Metric Name,Value,Unit,Engineering Standard / Note\n")
            writer.write("Session,Session ID,${session.id},ID,\n")
            writer.write("Session,Session Name,${escapeCsv(session.sessionName)},,\n")
            writer.write("Session,Start Time,${TelecomTimeUtils.formatBdtDateTime(session.startTimeMs)},ISO8601,Recorded in Bangladesh Standard Time (BDT)\n")
            writer.write("Session,End Time,${session.endTimeMs?.let { TelecomTimeUtils.formatBdtDateTime(it) } ?: "Active"},ISO8601,\n")
            writer.write("Session,Total Samples,${measurements.size},samples,\n")
            writer.write("Session,Valid GPS Samples,${valid.size},samples,${String.format(Locale.US, "%.1f%% GPS fix rate", valid.size * 100.0 / totalGen)}\n")
            writer.write("Session,Distance,${String.format(Locale.US, "%.2f", session.totalDistanceM / 1000.0)},km,Haversine corridor distance\n")
            writer.write("Session,Max Speed,${speedList.maxOrNull()?.let { String.format(Locale.US, "%.1f", it) } ?: "0.0"},km/h,\n")
            writer.write("Session,Avg Speed,${if (speedList.isNotEmpty()) String.format(Locale.US, "%.1f", speedList.average()) else "0.0"},km/h,\n")

            writer.write("Radio Signal,Mean RSRP,${avgRsrp?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"},dBm,Reference Signal Received Power\n")
            writer.write("Radio Signal,Min RSRP,${minRsrp?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"},dBm,Worst RF spot\n")
            writer.write("Radio Signal,Max RSRP,${maxRsrp?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"},dBm,Peak cell proximity\n")
            writer.write("Radio Signal,Mean RSRQ,${avgRsrq?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"},dB,Reference Signal Received Quality\n")
            writer.write("Radio Signal,Mean SINR,${avgSinr?.let { String.format(Locale.US, "%.1f", it) } ?: "Unknown"},dB,Signal-to-Interference-plus-Noise Ratio\n")

            writer.write("Quality Breakdown,Excellent (>= -80 dBm),${excellentCount},samples,${String.format(Locale.US, "%.1f%%", excellentCount * 100.0 / totalSignal)} 3GPP TS 36.133\n")
            writer.write("Quality Breakdown,Good (-90 to -81 dBm),${goodCount},samples,${String.format(Locale.US, "%.1f%%", goodCount * 100.0 / totalSignal)} 3GPP TS 36.133\n")
            writer.write("Quality Breakdown,Fair (-100 to -91 dBm),${fairCount},samples,${String.format(Locale.US, "%.1f%%", fairCount * 100.0 / totalSignal)} 3GPP TS 36.133\n")
            writer.write("Quality Breakdown,Poor (< -100 dBm),${poorCount},samples,${String.format(Locale.US, "%.1f%%", poorCount * 100.0 / totalSignal)} Coverage Deficit Threshold\n")

            writer.write("RAT Distribution,5G NR Share,${gen5g},samples,${String.format(Locale.US, "%.1f%%", gen5g * 100.0 / totalGen)}\n")
            writer.write("RAT Distribution,4G LTE Share,${gen4g},samples,${String.format(Locale.US, "%.1f%%", gen4g * 100.0 / totalGen)}\n")
            writer.write("RAT Distribution,3G UMTS Share,${gen3g},samples,${String.format(Locale.US, "%.1f%%", gen3g * 100.0 / totalGen)}\n")
            writer.write("RAT Distribution,2G GSM Share,${gen2g},samples,${String.format(Locale.US, "%.1f%%", gen2g * 100.0 / totalGen)}\n")
        }
        return file
    }

    fun exportEventScenarioCsv(
        context: Context,
        scenario: com.cellscope.drive.analytics.CapacityScenarioResult
    ): File {
        val exportDir = File(context.getExternalFilesDir(null), "exports").apply {
            if (!exists()) mkdirs()
        }
        val safeName = scenario.venue.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val timestamp = TelecomTimeUtils.formatBdtFilename(System.currentTimeMillis())
        val file = File(exportDir, "cellscope_event_scenario_${safeName}_$timestamp.csv")

        val header = listOf(
            "Event Name",
            "Center Latitude",
            "Center Longitude",
            "Radius (m)",
            "Sample Evidence Status",
            "Sample Count in Area",
            "Mean RSRP (dBm)",
            "Total Attendees",
            "Active Ratio (%)",
            "Active Users",
            "Traffic Profile",
            "DL Demand (Mbps)",
            "UL Demand (Mbps)",
            "Temporary COW",
            "WiFi Offload Ratio (%)",
            "Available Capacity (Mbps)",
            "Capacity Deficit (Mbps)",
            "Bottleneck Severity",
            "Recommended Interventions"
        ).joinToString(",")

        FileWriter(file).use { writer ->
            writer.appendLine(header)
            val row = listOf(
                escapeCsv(scenario.venue.eventName),
                scenario.venue.centerLat.toString(),
                scenario.venue.centerLon.toString(),
                scenario.venue.radiusMeters.toString(),
                escapeCsv(scenario.coverageEvidence.status.label),
                scenario.coverageEvidence.sampleCountInArea.toString(),
                scenario.coverageEvidence.meanRsrpDbm?.toString() ?: "",
                scenario.totalAttendees.toString(),
                String.format(Locale.US, "%.1f", scenario.spec.activeUserRatio * 100.0),
                scenario.totalActiveUsers.toString(),
                escapeCsv(scenario.spec.trafficProfile.label),
                String.format(Locale.US, "%.2f", scenario.offeredTrafficDlMbps),
                String.format(Locale.US, "%.2f", scenario.offeredTrafficUlMbps),
                if (scenario.spec.hasTemporaryCow) "YES" else "NO",
                String.format(Locale.US, "%.1f", scenario.spec.wifiOffloadRatio * 100.0),
                scenario.totalAvailableCapacityMbps?.let { String.format(Locale.US, "%.1f", it) } ?: "Unmeasured",
                scenario.capacityDeficitMbps?.let { String.format(Locale.US, "%.1f", it) } ?: "0.0",
                escapeCsv(scenario.bottleneckSeverity),
                escapeCsv(scenario.recommendedInterventions.joinToString("; "))
            ).joinToString(",")
            writer.appendLine(row)
        }
        return file
    }

    fun exportRatMobilityCsv(
        context: Context,
        session: DriveSession,
        mobilitySummary: com.cellscope.drive.analytics.RatMobilityDiagnosticsEngine.RatMobilitySummary
    ): File {
        val exportDir = File(context.getExternalFilesDir(null), "exports").apply {
            if (!exists()) mkdirs()
        }
        val safeName = session.sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val timestamp = TelecomTimeUtils.formatBdtFilename(session.startTimeMs)
        val file = File(exportDir, "cellscope_rat_mobility_${safeName}_$timestamp.csv")

        val header = listOf(
            "Timestamp",
            "Latitude",
            "Longitude",
            "From RAT",
            "To RAT",
            "From Generation",
            "To Generation",
            "From PCI",
            "To PCI",
            "From Signal (dBm)",
            "To Signal (dBm)",
            "Pre-Transition Mean RSRP (dBm)",
            "Pre-Transition Mean RSRQ (dB)",
            "Pre-Transition Mean SINR (dB)",
            "Pre-Transition Neighbors",
            "Speed (km/h)",
            "Is Ping-Pong",
            "Ping-Pong Duration (ms)",
            "Root Cause",
            "Confidence Score",
            "Diagnostic Evidence",
            "Engineering Remediation"
        ).joinToString(",")

        FileWriter(file).use { writer ->
            writer.appendLine(header)
            for (e in mobilitySummary.events) {
                val row = listOf(
                    escapeCsv(e.timestampIso),
                    e.latitude?.toString() ?: "",
                    e.longitude?.toString() ?: "",
                    escapeCsv(e.fromRat),
                    escapeCsv(e.toRat),
                    escapeCsv(e.fromGeneration.code),
                    escapeCsv(e.toGeneration.code),
                    e.fromPci?.toString() ?: "",
                    e.toPci?.toString() ?: "",
                    e.fromSignalDbm?.toString() ?: "",
                    e.toSignalDbm?.toString() ?: "",
                    e.preTransitionMeanRsrp?.toString() ?: "",
                    e.preTransitionMeanRsrq?.toString() ?: "",
                    e.preTransitionMeanSinr?.toString() ?: "",
                    e.preTransitionNeighborCount.toString(),
                    e.speedKmh?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    if (e.isPingPong) "1" else "0",
                    e.pingPongReturnDurationMs?.toString() ?: "",
                    escapeCsv(e.rootCause.label),
                    String.format(Locale.US, "%.2f", e.confidenceScore),
                    escapeCsv(e.diagnosticEvidence),
                    escapeCsv(e.engineeringRemediation)
                ).joinToString(",")
                writer.appendLine(row)
            }
        }
        return file
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
