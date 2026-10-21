package com.cellscope.drive.data

import android.content.Context
import android.net.Uri
import com.cellscope.drive.data.db.CellScopeDatabase
import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.data.entity.CallReliabilityEventEntity
import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.data.entity.NetworkContinuityEventEntity
import com.cellscope.drive.data.entity.ServiceHoleEntity
import com.cellscope.drive.util.TelecomTimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.*

enum class CsvSectionType {
    TELEMETRY,
    ACTIVE_QOE,
    CELL_TRANSITIONS,
    NETWORK_CONTINUITY,
    SERVICE_HOLES,
    CALL_RELIABILITY
}

data class CsvValidationResult(
    val isValid: Boolean,
    val filename: String,
    val totalRows: Int,
    val validRows: Int,
    val malformedRows: Int,
    val validGpsRows: Int,
    val missingGpsRows: Int,
    val placeholderRsrpCount: Int,
    val detectedColumns: List<String>,
    val detectedOperator: String?,
    val detectedRats: List<String>,
    val startTimestampIso: String?,
    val endTimestampIso: String?,
    val durationSeconds: Long,
    val totalDistanceKm: Double,
    val warnings: List<String>,
    val errors: List<String>,
    val isDuplicate: Boolean,
    val existingSessionName: String? = null,
    val existingSessionId: Long? = null,
    val calibratedRsrpMean: Double? = null,
    val rawRsrpMean: Double? = null,
    val fingerprint: String? = null,
    // Version-aware multi-section metadata
    val sectionCounts: Map<String, Int> = emptyMap(),
    val malformedSectionRows: List<String> = emptyList(),
    val qoeStatusCounts: Map<String, Int> = emptyMap(),
    val transitionTypeCounts: Map<String, Int> = emptyMap()
)

object CsvImporter {

    private val DHAKA_ZONE = ZoneId.of("Asia/Dhaka")

    // Canonical column patterns for Telemetry header matching
    private val TELEMETRY_PATTERNS = mapOf(
        "timestamp" to listOf(Pattern.compile("^timestamp\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^time\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^pc\\s+timestamp\\s*$", Pattern.CASE_INSENSITIVE)),
        "latitude" to listOf(Pattern.compile("^latitude\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^lat\\s*$", Pattern.CASE_INSENSITIVE)),
        "longitude" to listOf(Pattern.compile("^longitude\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^lon\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^long\\s*$", Pattern.CASE_INSENSITIVE)),
        "speed" to listOf(Pattern.compile("^speed\\s*\\(\\s*km/h\\s*\\)\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^speed\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^speed_kmh\\s*$", Pattern.CASE_INSENSITIVE)),
        "servingPci" to listOf(Pattern.compile("^serving\\s+cell\\s+id\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^serving_cell_id\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^pci\\s*$", Pattern.CASE_INSENSITIVE)),
        "rsrp" to listOf(Pattern.compile("^serving\\s+cell\\s+rsrp\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^rsrp\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^serving_cell_rsrp\\s*$", Pattern.CASE_INSENSITIVE)),
        "rsrq" to listOf(Pattern.compile("^serving\\s+cell\\s+rsrq\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^rsrq\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^serving_cell_rsrq\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^serving\\s+cell\\s+rsrpq\\s*$", Pattern.CASE_INSENSITIVE)),
        "cinr" to listOf(Pattern.compile("^serving\\s+cell\\s+cinr\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^cinr\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^sinr\\s*$", Pattern.CASE_INSENSITIVE)),
        "handoverTrigger" to listOf(Pattern.compile("^handover\\s+trigger\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^handover_trigger\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^ho_trigger\\s*$", Pattern.CASE_INSENSITIVE)),
        "tac" to listOf(Pattern.compile("^tac\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^tracking_area_code\\s*$", Pattern.CASE_INSENSITIVE)),
        "earfcn" to listOf(Pattern.compile("^earfcn\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^arfcn\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^channel\\s*$", Pattern.CASE_INSENSITIVE)),
        "rssi" to listOf(Pattern.compile("^rssi\\s*\\(\\s*dbm\\s*\\)\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^rssi\\s*$", Pattern.CASE_INSENSITIVE)),
        "networkType" to listOf(Pattern.compile("^network\\s+type\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^network_type\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^tech\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^rat\\s*$", Pattern.CASE_INSENSITIVE)),
        "operator" to listOf(Pattern.compile("^operator\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^carrier\\s*$", Pattern.CASE_INSENSITIVE)),
        "altitude" to listOf(Pattern.compile("^altitude\\s*\\(\\s*m\\s*\\)\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^altitude\\s*$", Pattern.CASE_INSENSITIVE)),
        "accuracy" to listOf(Pattern.compile("^accuracy\\s*\\(\\s*m\\s*\\)\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^accuracy\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^gps_accuracy\\s*$", Pattern.CASE_INSENSITIVE)),
        "bearing" to listOf(Pattern.compile("^bearing\\s*\\(\\s*deg\\s*\\)\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^bearing\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^heading\\s*$", Pattern.CASE_INSENSITIVE)),
        "eci" to listOf(Pattern.compile("^eci\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^cell_id\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^ci\\s*$", Pattern.CASE_INSENSITIVE)),
        "cqi" to listOf(Pattern.compile("^cqi\\s*$", Pattern.CASE_INSENSITIVE)),
        "timingAdvance" to listOf(Pattern.compile("^timing\\s+advance\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^timing_advance\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^ta\\s*$", Pattern.CASE_INSENSITIVE)),
        "isGpsValid" to listOf(Pattern.compile("^gps\\s+valid\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^is_gps_valid\\s*$", Pattern.CASE_INSENSITIVE)),
        "transitionType" to listOf(Pattern.compile("^transition\\s+type\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^transition_type\\s*$", Pattern.CASE_INSENSITIVE)),
        "servingRat" to listOf(Pattern.compile("^serving\\s+rat\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^serving_rat\\s*$", Pattern.CASE_INSENSITIVE)),
        "channelNumber" to listOf(Pattern.compile("^channel\\s+number\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^channel_number\\s*$", Pattern.CASE_INSENSITIVE)),
        "channelType" to listOf(Pattern.compile("^channel\\s+type\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^channel_type\\s*$", Pattern.CASE_INSENSITIVE)),
        "primarySignalDbm" to listOf(Pattern.compile("^primary\\s+signal\\s*\\(\\s*dbm\\s*\\)\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^primary\\s+signal\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^primary_signal_dbm\\s*$", Pattern.CASE_INSENSITIVE)),
        "signalMetricType" to listOf(Pattern.compile("^signal\\s+metric\\s+type\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^signal_metric_type\\s*$", Pattern.CASE_INSENSITIVE)),
        "secondarySignalDb" to listOf(Pattern.compile("^secondary\\s+signal\\s*\\(\\s*db\\s*\\)\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^secondary\\s+signal\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^secondary_signal_db\\s*$", Pattern.CASE_INSENSITIVE)),
        "sinrSource" to listOf(Pattern.compile("^sinr\\s+source\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^sinr_source\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^cinr_source\\s*$", Pattern.CASE_INSENSITIVE)),
        "estimationMethod" to listOf(Pattern.compile("^estimation\\s+method\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^estimation_method\\s*$", Pattern.CASE_INSENSITIVE), Pattern.compile("^sinr_method\\s*$", Pattern.CASE_INSENSITIVE))
    )

    suspend fun validateCsv(
        context: Context,
        uri: Uri,
        filename: String
    ): CsvValidationResult = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return@withContext CsvValidationResult(
                isValid = false,
                filename = filename,
                totalRows = 0,
                validRows = 0,
                malformedRows = 0,
                validGpsRows = 0,
                missingGpsRows = 0,
                placeholderRsrpCount = 0,
                detectedColumns = emptyList(),
                detectedOperator = null,
                detectedRats = emptyList(),
                startTimestampIso = null,
                endTimestampIso = null,
                durationSeconds = 0L,
                totalDistanceKm = 0.0,
                warnings = emptyList(),
                errors = listOf("Unable to open input stream for URI: $uri"),
                isDuplicate = false
            )

        validateInputStream(inputStream, filename, context)
    }

    fun validateInputStream(
        inputStream: InputStream,
        filename: String,
        context: Context? = null
    ): CsvValidationResult {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val malformedSectionRows = mutableListOf<String>()

        var currentSection = CsvSectionType.TELEMETRY
        var pendingSectionSwitch: CsvSectionType? = null

        val sectionHeaders = mutableMapOf<CsvSectionType, List<String>>()
        val sectionColMaps = mutableMapOf<CsvSectionType, Map<String, Int>>()
        val sectionCounts = mutableMapOf<String, Int>()
        val qoeStatusCounts = mutableMapOf<String, Int>()
        val transitionTypeCounts = mutableMapOf<String, Int>()

        var totalTelemetryRows = 0
        var validTelemetryRows = 0
        var malformedRowsCount = 0
        var validGpsRows = 0
        var missingGpsRows = 0
        var placeholderRsrpCount = 0
        val detectedRats = mutableSetOf<String>()
        val operators = mutableSetOf<String>()

        var firstTimestampMs: Long? = null
        var lastTimestampMs: Long? = null
        var firstTimestampIso: String? = null
        var lastTimestampIso: String? = null

        var prevLat: Double? = null
        var prevLon: Double? = null
        var totalDistanceM = 0.0

        var globalLineNum = 0

        reader.forEachLine { rawLine ->
            globalLineNum++
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEachLine

            // 1. Check Section Comment Markers
            if (trimmed.startsWith("#")) {
                val lower = trimmed.lowercase(Locale.US)
                when {
                    lower.contains("active qoe") || lower.contains("active_qoe") -> {
                        pendingSectionSwitch = CsvSectionType.ACTIVE_QOE
                    }
                    lower.contains("cell transition") || lower.contains("cell_transition") -> {
                        pendingSectionSwitch = CsvSectionType.CELL_TRANSITIONS
                    }
                    lower.contains("network continuity") || lower.contains("network_continuity") -> {
                        pendingSectionSwitch = CsvSectionType.NETWORK_CONTINUITY
                    }
                    lower.contains("service hole") || lower.contains("service_hole") -> {
                        pendingSectionSwitch = CsvSectionType.SERVICE_HOLES
                    }
                    lower.contains("call reliability") || lower.contains("call_reliability") -> {
                        pendingSectionSwitch = CsvSectionType.CALL_RELIABILITY
                    }
                    lower.contains("telemetry") -> {
                        pendingSectionSwitch = CsvSectionType.TELEMETRY
                    }
                }
                return@forEachLine
            }

            // 2. Detect section switch directly from header signature if unannounced by comment
            val tokens = splitCsvLine(trimmed)
            if (tokens.isNotEmpty()) {
                val firstCol = tokens[0].trim().lowercase(Locale.US)
                if (firstCol == "test_id" || trimmed.contains("download_throughput_mbps", ignoreCase = true)) {
                    currentSection = CsvSectionType.ACTIVE_QOE
                    pendingSectionSwitch = null
                    sectionHeaders[currentSection] = tokens
                    sectionColMaps[currentSection] = tokens.mapIndexed { idx, s -> s.trim().lowercase(Locale.US) to idx }.toMap()
                    return@forEachLine
                } else if (trimmed.contains("Event Type,From RAT", ignoreCase = true) || trimmed.contains("From PCI,To PCI", ignoreCase = true)) {
                    currentSection = CsvSectionType.CELL_TRANSITIONS
                    pendingSectionSwitch = null
                    sectionHeaders[currentSection] = tokens
                    sectionColMaps[currentSection] = tokens.mapIndexed { idx, s -> s.trim().lowercase(Locale.US) to idx }.toMap()
                    return@forEachLine
                } else if (trimmed.contains("From Generation,To Generation", ignoreCase = true) || trimmed.contains("Transition Category", ignoreCase = true)) {
                    currentSection = CsvSectionType.NETWORK_CONTINUITY
                    pendingSectionSwitch = null
                    sectionHeaders[currentSection] = tokens
                    sectionColMaps[currentSection] = tokens.mapIndexed { idx, s -> s.trim().lowercase(Locale.US) to idx }.toMap()
                    return@forEachLine
                } else if (trimmed.contains("Hole Type,Severity", ignoreCase = true) || trimmed.contains("Engineering Evidence,Resolution Status", ignoreCase = true)) {
                    currentSection = CsvSectionType.SERVICE_HOLES
                    pendingSectionSwitch = null
                    sectionHeaders[currentSection] = tokens
                    sectionColMaps[currentSection] = tokens.mapIndexed { idx, s -> s.trim().lowercase(Locale.US) to idx }.toMap()
                    return@forEachLine
                } else if (trimmed.contains("Call State,Classification", ignoreCase = true) || trimmed.contains("Risk Score Category,Evidence Factors", ignoreCase = true)) {
                    currentSection = CsvSectionType.CALL_RELIABILITY
                    pendingSectionSwitch = null
                    sectionHeaders[currentSection] = tokens
                    sectionColMaps[currentSection] = tokens.mapIndexed { idx, s -> s.trim().lowercase(Locale.US) to idx }.toMap()
                    return@forEachLine
                }
            }

            // 3. Handle pending section header line
            if (pendingSectionSwitch != null) {
                currentSection = pendingSectionSwitch!!
                pendingSectionSwitch = null
                sectionHeaders[currentSection] = tokens
                sectionColMaps[currentSection] = if (currentSection == CsvSectionType.TELEMETRY) {
                    matchHeaders(tokens)
                } else {
                    tokens.mapIndexed { idx, s -> s.trim().lowercase(Locale.US) to idx }.toMap()
                }
                return@forEachLine
            }

            // If header hasn't been set for initial section (Telemetry)
            if (!sectionHeaders.containsKey(currentSection)) {
                sectionHeaders[currentSection] = tokens
                sectionColMaps[currentSection] = matchHeaders(tokens)
                return@forEachLine
            }

            // 4. Parse section data row independently
            val headerCols = sectionHeaders[currentSection] ?: tokens
            val colMap = sectionColMaps[currentSection] ?: emptyMap()

            when (currentSection) {
                CsvSectionType.TELEMETRY -> {
                    totalTelemetryRows++
                    if (tokens.size < headerCols.size * 0.5) {
                        malformedRowsCount++
                        malformedSectionRows.add("TELEMETRY: line $globalLineNum: Malformed row (${tokens.size} columns, expected ${headerCols.size})")
                        return@forEachLine
                    }

                    val timeIdx = colMap["timestamp"]
                    val latIdx = colMap["latitude"]
                    val lonIdx = colMap["longitude"]
                    val rsrpIdx = colMap["rsrp"]
                    val rsrqIdx = colMap["rsrq"]
                    val rssiIdx = colMap["rssi"]
                    val netIdx = colMap["networkType"]
                    val opIdx = colMap["operator"]

                    val rawTs = timeIdx?.let { if (it < tokens.size) tokens[it] else null }
                    val tsMs = parseTimestampToMs(rawTs)
                    if (tsMs != null) {
                        if (firstTimestampMs == null) {
                            firstTimestampMs = tsMs
                            firstTimestampIso = rawTs
                        }
                        lastTimestampMs = tsMs
                        lastTimestampIso = rawTs
                    }

                    val rawLat = latIdx?.let { if (it < tokens.size) tokens[it] else null }
                    val rawLon = lonIdx?.let { if (it < tokens.size) tokens[it] else null }
                    val lat = rawLat?.toDoubleOrNull()
                    val lon = rawLon?.toDoubleOrNull()

                    val hasValidGps = lat != null && lon != null && !lat.isNaN() && !lon.isNaN() &&
                            lat in -90.0..90.0 && lon in -180.0..180.0 &&
                            !(lat == 0.0 && lon == 0.0)

                    if (hasValidGps) {
                        validGpsRows++
                        if (prevLat != null && prevLon != null) {
                            val stepDist = calculateHaversineMeters(prevLat!!, prevLon!!, lat!!, lon!!)
                            if (stepDist in 1.0..5000.0) totalDistanceM += stepDist
                        }
                        prevLat = lat
                        prevLon = lon
                    } else {
                        missingGpsRows++
                    }

                    val rawRsrp = rsrpIdx?.let { if (it < tokens.size) tokens[it] else null }
                    val rsrpVal = rawRsrp?.toIntOrNull()
                    val rawRsrq = rsrqIdx?.let { if (it < tokens.size) tokens[it] else null }
                    val rsrqVal = rawRsrq?.toIntOrNull()
                    val rawRssi = rssiIdx?.let { if (it < tokens.size) tokens[it] else null }
                    val rssiVal = rawRssi?.toIntOrNull()
                    val rawNet = netIdx?.let { if (it < tokens.size) tokens[it] else null }
                    val is2g = rawNet?.contains("2G", ignoreCase = true) == true || rawNet?.contains("GSM", ignoreCase = true) == true

                    if (rsrpVal != null && !is2g && rsrpVal == -44 && rsrqVal == -3 && (rssiVal == null || rssiVal <= -100)) {
                        placeholderRsrpCount++
                    }

                    if (!rawNet.isNullOrBlank()) detectedRats.add(rawNet.trim())
                    val rawOp = opIdx?.let { if (it < tokens.size) tokens[it] else null }
                    if (!rawOp.isNullOrBlank()) operators.add(rawOp.trim())

                    validTelemetryRows++
                    sectionCounts["TELEMETRY"] = (sectionCounts["TELEMETRY"] ?: 0) + 1
                }

                CsvSectionType.ACTIVE_QOE -> {
                    if (tokens.size < 10) {
                        malformedRowsCount++
                        malformedSectionRows.add("ACTIVE_QOE: line $globalLineNum: Malformed row (${tokens.size} columns, expected ${headerCols.size})")
                        return@forEachLine
                    }
                    val statusIdx = colMap["test_status"] ?: 5
                    val status = if (statusIdx < tokens.size && tokens[statusIdx].isNotBlank()) tokens[statusIdx].trim() else "COMPLETED"
                    qoeStatusCounts[status] = (qoeStatusCounts[status] ?: 0) + 1
                    sectionCounts["ACTIVE_QOE"] = (sectionCounts["ACTIVE_QOE"] ?: 0) + 1
                }

                CsvSectionType.CELL_TRANSITIONS -> {
                    if (tokens.size < 8) {
                        malformedRowsCount++
                        malformedSectionRows.add("CELL_TRANSITIONS: line $globalLineNum: Malformed row (${tokens.size} columns, expected ${headerCols.size})")
                        return@forEachLine
                    }
                    val typeIdx = colMap["event type"] ?: colMap["event_type"] ?: 3
                    val eventType = if (typeIdx < tokens.size && tokens[typeIdx].isNotBlank()) tokens[typeIdx].trim() else "TRANSITION"
                    transitionTypeCounts[eventType] = (transitionTypeCounts[eventType] ?: 0) + 1
                    sectionCounts["CELL_TRANSITIONS"] = (sectionCounts["CELL_TRANSITIONS"] ?: 0) + 1
                }

                CsvSectionType.NETWORK_CONTINUITY -> {
                    if (tokens.size < 8) {
                        malformedRowsCount++
                        malformedSectionRows.add("NETWORK_CONTINUITY: line $globalLineNum: Malformed row (${tokens.size} columns, expected ${headerCols.size})")
                        return@forEachLine
                    }
                    sectionCounts["NETWORK_CONTINUITY"] = (sectionCounts["NETWORK_CONTINUITY"] ?: 0) + 1
                }

                CsvSectionType.SERVICE_HOLES -> {
                    if (tokens.size < 6) {
                        malformedRowsCount++
                        malformedSectionRows.add("SERVICE_HOLES: line $globalLineNum: Malformed row (${tokens.size} columns, expected ${headerCols.size})")
                        return@forEachLine
                    }
                    sectionCounts["SERVICE_HOLES"] = (sectionCounts["SERVICE_HOLES"] ?: 0) + 1
                }

                CsvSectionType.CALL_RELIABILITY -> {
                    if (tokens.size < 6) {
                        malformedRowsCount++
                        malformedSectionRows.add("CALL_RELIABILITY: line $globalLineNum: Malformed row (${tokens.size} columns, expected ${headerCols.size})")
                        return@forEachLine
                    }
                    sectionCounts["CALL_RELIABILITY"] = (sectionCounts["CALL_RELIABILITY"] ?: 0) + 1
                }
            }
        }

        val telemetryHeader = sectionHeaders[CsvSectionType.TELEMETRY]
        if (telemetryHeader == null) {
            return CsvValidationResult(
                isValid = false,
                filename = filename,
                totalRows = 0,
                validRows = 0,
                malformedRows = 0,
                validGpsRows = 0,
                missingGpsRows = 0,
                placeholderRsrpCount = 0,
                detectedColumns = emptyList(),
                detectedOperator = null,
                detectedRats = emptyList(),
                startTimestampIso = null,
                endTimestampIso = null,
                durationSeconds = 0L,
                totalDistanceKm = 0.0,
                warnings = emptyList(),
                errors = listOf("CSV file contains no valid telemetry header."),
                isDuplicate = false
            )
        }

        val colIndexMap = sectionColMaps[CsvSectionType.TELEMETRY] ?: matchHeaders(telemetryHeader)
        val reqCols = listOf("timestamp", "latitude", "longitude", "servingPci", "rsrp")
        val missingReq = reqCols.filter { !colIndexMap.containsKey(it) }
        if (missingReq.isNotEmpty()) {
            errors.add("Missing required telemetry columns: ${missingReq.joinToString(", ")}")
        }

        if (missingGpsRows > 0) {
            warnings.add("$missingGpsRows row(s) missing valid GPS coordinates.")
        }
        if (placeholderRsrpCount > 0) {
            warnings.add("$placeholderRsrpCount sample(s) have baseband placeholder RSRP (-44 dBm / -3 dB). These indicate modem radio fading.")
        }
        if (malformedRowsCount > 0) {
            warnings.add("$malformedRowsCount malformed row(s) encountered and reported.")
        }

        val durationSeconds = if (firstTimestampMs != null && lastTimestampMs != null && lastTimestampMs!! >= firstTimestampMs!!) {
            (lastTimestampMs!! - firstTimestampMs!!) / 1000L
        } else {
            totalTelemetryRows.toLong()
        }

        val fingerprint = "FP_${validTelemetryRows}_${firstTimestampMs ?: 0L}_${lastTimestampMs ?: 0L}_${(totalDistanceM).toInt()}"

        var isDuplicate = false
        var existingName: String? = null
        var existingId: Long? = null
        if (context != null) {
            try {
                val db = CellScopeDatabase.getDatabase(context)
                val all = kotlinx.coroutines.runBlocking { db.sessionDao().getAllSessions() }
                val match = all.firstOrNull { s ->
                    s.sessionName.equals(filename.substringBeforeLast("."), ignoreCase = true) ||
                            (firstTimestampMs != null && s.startTimeMs == firstTimestampMs && s.sampleCount == validTelemetryRows) ||
                            (s.notes != null && s.notes!!.contains(fingerprint))
                }
                if (match != null) {
                    isDuplicate = true
                    existingName = match.sessionName
                    existingId = match.id
                    warnings.add("Duplicate dataset detected: Matches existing session '${match.sessionName}' (ID: ${match.id}).")
                }
            } catch (_: Exception) {}
        }

        return CsvValidationResult(
            isValid = errors.isEmpty() && validTelemetryRows > 0,
            filename = filename,
            totalRows = totalTelemetryRows,
            validRows = validTelemetryRows,
            malformedRows = malformedRowsCount,
            validGpsRows = validGpsRows,
            missingGpsRows = missingGpsRows,
            placeholderRsrpCount = placeholderRsrpCount,
            detectedColumns = telemetryHeader,
            detectedOperator = operators.firstOrNull(),
            detectedRats = detectedRats.toList(),
            startTimestampIso = firstTimestampIso,
            endTimestampIso = lastTimestampIso,
            durationSeconds = durationSeconds,
            totalDistanceKm = round(totalDistanceM / 10.0) / 100.0,
            warnings = warnings,
            errors = errors,
            isDuplicate = isDuplicate,
            existingSessionName = existingName,
            existingSessionId = existingId,
            fingerprint = fingerprint,
            sectionCounts = sectionCounts,
            malformedSectionRows = malformedSectionRows,
            qoeStatusCounts = qoeStatusCounts,
            transitionTypeCounts = transitionTypeCounts
        )
    }

    suspend fun importCsvToDatabase(
        context: Context,
        uri: Uri,
        customSessionName: String? = null,
        allowDuplicate: Boolean = false,
        onProgress: ((Int, Int) -> Unit)? = null
    ): DriveSession = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open CSV input stream: $uri")

        val filename = customSessionName ?: "Imported_${System.currentTimeMillis()}"
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))

        var currentSection = CsvSectionType.TELEMETRY
        var pendingSectionSwitch: CsvSectionType? = null

        val sectionHeaders = mutableMapOf<CsvSectionType, List<String>>()
        val sectionColMaps = mutableMapOf<CsvSectionType, Map<String, Int>>()

        val parsedTelemetry = mutableListOf<LteMeasurementEntity>()
        val parsedQoeTests = mutableListOf<ActiveQoeTestEntity>()
        val parsedExplicitTransitions = mutableListOf<CellTransitionEntity>()
        val parsedContinuityEvents = mutableListOf<NetworkContinuityEventEntity>()
        val parsedServiceHoles = mutableListOf<ServiceHoleEntity>()
        val parsedCallEvents = mutableListOf<CallReliabilityEventEntity>()

        // Fallback reconstructed transitions if explicit section is omitted
        val reconstructedTransitions = mutableListOf<CellTransitionEntity>()
        var lastPci: Int? = null
        var lastTac: Int? = null
        var lastRsrp: Int? = null
        var lastEarfcn: Int? = null

        var firstTsMs = System.currentTimeMillis()
        var lastTsMs = firstTsMs
        var detectedOp: String? = null
        var maxSpeed = 0f
        var totalDistM = 0.0
        var prevLat: Double? = null
        var prevLon: Double? = null
        var rsrpSum = 0L
        var rsrpCount = 0

        var globalLineNum = 0

        reader.forEachLine { rawLine ->
            globalLineNum++
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEachLine

            // 1. Comment Header detection
            if (trimmed.startsWith("#")) {
                val lower = trimmed.lowercase(Locale.US)
                when {
                    lower.contains("active qoe") || lower.contains("active_qoe") -> {
                        pendingSectionSwitch = CsvSectionType.ACTIVE_QOE
                    }
                    lower.contains("cell transition") || lower.contains("cell_transition") -> {
                        pendingSectionSwitch = CsvSectionType.CELL_TRANSITIONS
                    }
                    lower.contains("network continuity") || lower.contains("network_continuity") -> {
                        pendingSectionSwitch = CsvSectionType.NETWORK_CONTINUITY
                    }
                    lower.contains("service hole") || lower.contains("service_hole") -> {
                        pendingSectionSwitch = CsvSectionType.SERVICE_HOLES
                    }
                    lower.contains("call reliability") || lower.contains("call_reliability") -> {
                        pendingSectionSwitch = CsvSectionType.CALL_RELIABILITY
                    }
                    lower.contains("telemetry") -> {
                        pendingSectionSwitch = CsvSectionType.TELEMETRY
                    }
                }
                return@forEachLine
            }

            // 2. Direct section detection from header tokens
            val tokens = splitCsvLine(trimmed)
            if (tokens.isNotEmpty()) {
                val firstCol = tokens[0].trim().lowercase(Locale.US)
                if (firstCol == "test_id" || trimmed.contains("download_throughput_mbps", ignoreCase = true)) {
                    currentSection = CsvSectionType.ACTIVE_QOE
                    pendingSectionSwitch = null
                    sectionHeaders[currentSection] = tokens
                    sectionColMaps[currentSection] = tokens.mapIndexed { idx, s -> s.trim().lowercase(Locale.US) to idx }.toMap()
                    return@forEachLine
                } else if (trimmed.contains("Event Type,From RAT", ignoreCase = true) || trimmed.contains("From PCI,To PCI", ignoreCase = true)) {
                    currentSection = CsvSectionType.CELL_TRANSITIONS
                    pendingSectionSwitch = null
                    sectionHeaders[currentSection] = tokens
                    sectionColMaps[currentSection] = tokens.mapIndexed { idx, s -> s.trim().lowercase(Locale.US) to idx }.toMap()
                    return@forEachLine
                } else if (trimmed.contains("From Generation,To Generation", ignoreCase = true) || trimmed.contains("Transition Category", ignoreCase = true)) {
                    currentSection = CsvSectionType.NETWORK_CONTINUITY
                    pendingSectionSwitch = null
                    sectionHeaders[currentSection] = tokens
                    sectionColMaps[currentSection] = tokens.mapIndexed { idx, s -> s.trim().lowercase(Locale.US) to idx }.toMap()
                    return@forEachLine
                } else if (trimmed.contains("Hole Type,Severity", ignoreCase = true) || trimmed.contains("Engineering Evidence,Resolution Status", ignoreCase = true)) {
                    currentSection = CsvSectionType.SERVICE_HOLES
                    pendingSectionSwitch = null
                    sectionHeaders[currentSection] = tokens
                    sectionColMaps[currentSection] = tokens.mapIndexed { idx, s -> s.trim().lowercase(Locale.US) to idx }.toMap()
                    return@forEachLine
                } else if (trimmed.contains("Call State,Classification", ignoreCase = true) || trimmed.contains("Risk Score Category,Evidence Factors", ignoreCase = true)) {
                    currentSection = CsvSectionType.CALL_RELIABILITY
                    pendingSectionSwitch = null
                    sectionHeaders[currentSection] = tokens
                    sectionColMaps[currentSection] = tokens.mapIndexed { idx, s -> s.trim().lowercase(Locale.US) to idx }.toMap()
                    return@forEachLine
                }
            }

            if (pendingSectionSwitch != null) {
                currentSection = pendingSectionSwitch!!
                pendingSectionSwitch = null
                sectionHeaders[currentSection] = tokens
                sectionColMaps[currentSection] = if (currentSection == CsvSectionType.TELEMETRY) {
                    matchHeaders(tokens)
                } else {
                    tokens.mapIndexed { idx, s -> s.trim().lowercase(Locale.US) to idx }.toMap()
                }
                return@forEachLine
            }

            if (!sectionHeaders.containsKey(currentSection)) {
                sectionHeaders[currentSection] = tokens
                sectionColMaps[currentSection] = matchHeaders(tokens)
                return@forEachLine
            }

            val headerCols = sectionHeaders[currentSection] ?: tokens
            val colMap = sectionColMaps[currentSection] ?: emptyMap()

            when (currentSection) {
                CsvSectionType.TELEMETRY -> {
                    if (tokens.size < headerCols.size * 0.5) return@forEachLine

                    val timeIdx = colMap["timestamp"]
                    val latIdx = colMap["latitude"]
                    val lonIdx = colMap["longitude"]
                    val speedIdx = colMap["speed"]
                    val pciIdx = colMap["servingPci"]
                    val rsrpIdx = colMap["rsrp"]
                    val rsrqIdx = colMap["rsrq"]
                    val cinrIdx = colMap["cinr"]
                    val hoIdx = colMap["handoverTrigger"]
                    val tacIdx = colMap["tac"]
                    val earfcnIdx = colMap["earfcn"]
                    val rssiIdx = colMap["rssi"]
                    val netIdx = colMap["networkType"]
                    val opIdx = colMap["operator"]
                    val altIdx = colMap["altitude"]
                    val accIdx = colMap["accuracy"]
                    val bearIdx = colMap["bearing"]
                    val eciIdx = colMap["eci"]
                    val cqiIdx = colMap["cqi"]
                    val taIdx = colMap["timingAdvance"]
                    val gpsValidIdx = colMap["isGpsValid"]
                    val transTypeIdx = colMap["transitionType"]
                    val servingRatIdx = colMap["servingRat"]
                    val chanNumIdx = colMap["channelNumber"]
                    val chanTypeIdx = colMap["channelType"]
                    val primSigIdx = colMap["primarySignalDbm"]
                    val sigMetricTypeIdx = colMap["signalMetricType"]
                    val secSigIdx = colMap["secondarySignalDb"]
                    val sinrSourceIdx = colMap["sinrSource"]
                    val estMethodIdx = colMap["estimationMethod"]

                    val rawTs = timeIdx?.let { if (it < tokens.size) tokens[it] else null }
                    val tsMs = parseTimestampToMs(rawTs) ?: (firstTsMs + parsedTelemetry.size * 1000L)
                    val isoTs = rawTs ?: TelecomTimeUtils.formatIsoUtc(tsMs)

                    if (parsedTelemetry.isEmpty()) firstTsMs = tsMs
                    lastTsMs = tsMs

                    val lat = latIdx?.let { if (it < tokens.size) tokens[it].toDoubleOrNull() else null }
                    val lon = lonIdx?.let { if (it < tokens.size) tokens[it].toDoubleOrNull() else null }
                    val hasValidGps = lat != null && lon != null && !lat.isNaN() && !lon.isNaN() &&
                            lat in -90.0..90.0 && lon in -180.0..180.0 &&
                            !(lat == 0.0 && lon == 0.0)

                    if (hasValidGps) {
                        if (prevLat != null && prevLon != null) {
                            val step = calculateHaversineMeters(prevLat!!, prevLon!!, lat!!, lon!!)
                            if (step in 1.0..5000.0) totalDistM += step
                        }
                        prevLat = lat
                        prevLon = lon
                    }

                    val speed = speedIdx?.let { if (it < tokens.size) tokens[it].toFloatOrNull() else null }
                    if (speed != null && speed > maxSpeed) maxSpeed = speed

                    val pci = pciIdx?.let { if (it < tokens.size) tokens[it].toIntOrNull() else null }
                    val rsrp = rsrpIdx?.let { if (it < tokens.size) tokens[it].toIntOrNull() else null }
                    val rsrq = rsrqIdx?.let { if (it < tokens.size) tokens[it].toIntOrNull() else null }
                    val cinr = cinrIdx?.let { if (it < tokens.size) tokens[it].takeIf { s -> s.isNotBlank() }?.toDoubleOrNull() else null }
                    val hoTrig = hoIdx?.let { if (it < tokens.size) tokens[it].toIntOrNull() ?: 0 else 0 } ?: 0
                    val tac = tacIdx?.let { if (it < tokens.size) tokens[it].toIntOrNull() else null }
                    val earfcn = earfcnIdx?.let { if (it < tokens.size) tokens[it].toIntOrNull() else null }
                    val rssi = rssiIdx?.let { if (it < tokens.size) tokens[it].toIntOrNull() else null }
                    val netType = netIdx?.let { if (it < tokens.size) tokens[it].takeIf { s -> s.isNotBlank() } else null } ?: "LTE"
                    val op = opIdx?.let { if (it < tokens.size) tokens[it].takeIf { s -> s.isNotBlank() } else null }
                    if (detectedOp == null && op != null) detectedOp = op

                    val alt = altIdx?.let { if (it < tokens.size) tokens[it].toDoubleOrNull() else null }
                    val acc = accIdx?.let { if (it < tokens.size) tokens[it].toFloatOrNull() else null }
                    val bear = bearIdx?.let { if (it < tokens.size) tokens[it].toFloatOrNull() else null }
                    val eci = eciIdx?.let { if (it < tokens.size) tokens[it].toIntOrNull() else null }
                    val cqi = cqiIdx?.let { if (it < tokens.size) tokens[it].toIntOrNull() else null }
                    val ta = taIdx?.let { if (it < tokens.size) tokens[it].toIntOrNull() else null }
                    val gpsValid = gpsValidIdx?.let { if (it < tokens.size) tokens[it] == "1" || tokens[it].equals("true", true) else hasValidGps } ?: hasValidGps
                    val transType = transTypeIdx?.let { if (it < tokens.size) tokens[it].takeIf { s -> s.isNotBlank() } else null }

                    val explicitRat = servingRatIdx?.let { if (it < tokens.size) tokens[it].takeIf { s -> s.isNotBlank() } else null }
                    val explicitChanNum = chanNumIdx?.let { if (it < tokens.size) tokens[it].toIntOrNull() else null }
                    val explicitChanType = chanTypeIdx?.let { if (it < tokens.size) tokens[it].takeIf { s -> s.isNotBlank() } else null }
                    val explicitPrimSig = primSigIdx?.let { if (it < tokens.size) tokens[it].toIntOrNull() else null }
                    val explicitSigMetricType = sigMetricTypeIdx?.let { if (it < tokens.size) tokens[it].takeIf { s -> s.isNotBlank() } else null }
                    val explicitSecSig = secSigIdx?.let { if (it < tokens.size) tokens[it].toDoubleOrNull() else null }

                    val inferredRat = when {
                        explicitRat != null -> explicitRat
                        netType.contains("5G", ignoreCase = true) || netType.contains("NR", ignoreCase = true) -> "5G"
                        netType.contains("4G", ignoreCase = true) || netType.contains("LTE", ignoreCase = true) -> "4G"
                        netType.contains("3G", ignoreCase = true) || netType.contains("WCDMA", ignoreCase = true) || netType.contains("UMTS", ignoreCase = true) -> "3G"
                        netType.contains("2G", ignoreCase = true) || netType.contains("GSM", ignoreCase = true) || netType.contains("EDGE", ignoreCase = true) -> "2G"
                        else -> "4G"
                    }

                    val channelNumber = explicitChanNum ?: earfcn
                    val channelType = explicitChanType ?: when (inferredRat) {
                        "2G" -> "ARFCN"
                        "3G" -> "UARFCN"
                        "4G" -> "EARFCN"
                        "5G" -> "NR_ARFCN"
                        else -> "EARFCN"
                    }

                    val signalMetricType = explicitSigMetricType ?: when (inferredRat) {
                        "2G" -> "GSM_RSSI"
                        "3G" -> "WCDMA_RSCP"
                        "4G" -> "LTE_RSRP"
                        "5G" -> "NR_SS_RSRP"
                        else -> "LTE_RSRP"
                    }

                    val finalRsrp: Int?
                    val finalRssi: Int?
                    val primarySignalDbm: Int?
                    val secondarySignalDb: Double?

                    when (inferredRat) {
                        "2G" -> {
                            primarySignalDbm = explicitPrimSig ?: rssi ?: rsrp
                            finalRssi = primarySignalDbm
                            finalRsrp = null
                            secondarySignalDb = explicitSecSig
                        }
                        "3G" -> {
                            primarySignalDbm = explicitPrimSig ?: rsrp ?: rssi
                            finalRssi = primarySignalDbm
                            finalRsrp = null
                            secondarySignalDb = explicitSecSig ?: cinr ?: rsrq?.toDouble()
                        }
                        else -> {
                            primarySignalDbm = explicitPrimSig ?: rsrp
                            finalRsrp = primarySignalDbm
                            finalRssi = rssi
                            secondarySignalDb = explicitSecSig ?: cinr ?: rsrq?.toDouble()
                        }
                    }

                    if (finalRsrp != null && !(finalRsrp == -44 && rsrq == -3 && (rssi == null || rssi <= -100))) {
                        rsrpSum += finalRsrp
                        rsrpCount++
                    }

                    val entity = LteMeasurementEntity(
                        sessionId = 0L,
                        timestampIso = isoTs,
                        timestampMs = tsMs,
                        latitude = if (hasValidGps) lat else null,
                        longitude = if (hasValidGps) lon else null,
                        altitudeM = alt,
                        accuracyM = acc,
                        speedKmh = speed,
                        networkType = netType,
                        operatorAlpha = op,
                        operatorNumeric = null,
                        servingPci = pci,
                        tac = tac,
                        earfcn = if (inferredRat == "4G") channelNumber else earfcn,
                        bandwidthKhz = null,
                        rsrpDbm = finalRsrp,
                        rsrqDb = rsrq,
                        rssiDbm = finalRssi,
                        cqi = cqi,
                        timingAdvance = ta,
                        isServing = pci != null,
                        handoverTrigger = hoTrig,
                        bearing = bear,
                        sinrDb = cinr,
                        eci = eci,
                        neighborCellsJson = null,
                        transitionType = transType,
                        isGpsValid = gpsValid,
                        dataNetworkType = netType,
                        voiceNetworkType = null,
                        networkGen = inferredRat,
                        serviceState = "IN_SERVICE",
                        dataActivityState = "CONNECTED",
                        servingRat = inferredRat,
                        channelNumber = channelNumber,
                        channelType = channelType,
                        primarySignalDbm = primarySignalDbm,
                        signalMetricType = signalMetricType,
                        secondarySignalDb = secondarySignalDb,
                        sinrSource = run {
                            val explicit = sinrSourceIdx?.let { if (it < tokens.size) tokens[it].takeIf { s -> s.isNotBlank() } else null }
                            if (explicit != null) explicit
                            else if (cinr != null && cinr != 0.0) "MEASURED"
                            else if (rsrp != null && rsrq != null && rsrp in -140..-44 && rsrq in -25..-3) "ESTIMATED"
                            else "UNAVAILABLE"
                        },
                        estimationMethod = run {
                            val explicitSrc = sinrSourceIdx?.let { if (it < tokens.size) tokens[it].takeIf { s -> s.isNotBlank() } else null }
                            val explicitMethod = estMethodIdx?.let { if (it < tokens.size) tokens[it].takeIf { s -> s.isNotBlank() } else null }
                            if (explicitMethod != null) explicitMethod
                            else if (explicitSrc == "ESTIMATED" || (explicitSrc == null && (cinr == null || cinr == 0.0) && rsrp != null && rsrq != null && rsrp in -140..-44 && rsrq in -25..-3)) "3GPP_TS_36_214_RSRQ_LOAD_MODEL"
                            else if (cinr != null && cinr != 0.0) "CSV_SURVEY_RECORD"
                            else null
                        },
                        qualitativeDiagnostic = if (cinr != null) "Imported survey measurement ($cinr dB)" else "SINR unavailable in survey file"
                    )
                    parsedTelemetry.add(entity)

                    // Reconstruct transition if PCI changes
                    if (pci != null && lastPci != null && pci != lastPci) {
                        val isTacChange = lastTac != null && tac != null && lastTac != tac
                        val isIntraFreq = (lastEarfcn != null && earfcn != null && lastEarfcn == earfcn)
                        val isInterFreq = (lastEarfcn != null && earfcn != null && lastEarfcn != earfcn)
                        val isMoving = speed != null && speed >= 2.5f
                        val isSignalDrop = (lastRsrp ?: -90) < -115
                        val isStationary = speed != null && speed < 2.5f
                        val isExplicitConfirmed = (hoTrig == 1 || transType == "CONFIRMED_HANDOVER")
                        val tType = when {
                            isExplicitConfirmed -> "CONFIRMED_HANDOVER"
                            isSignalDrop -> "SIGNAL_LOSS"
                            isStationary -> "CELL_RESELECTION"
                            isInterFreq -> "INTER_FREQ"
                            isIntraFreq -> "INTRA_FREQ"
                            else -> "OBSERVED_TRANSITION"
                        }
                        val conf = if (isExplicitConfirmed) 0.95f else 0.85f
                        val tacNote = if (isTacChange) " (TAC $lastTac -> $tac)" else ""
                        val detail = when {
                            isExplicitConfirmed && isIntraFreq -> "Confirmed intra-frequency handover on Ch $earfcn (PCI: $lastPci -> $pci)$tacNote"
                            isExplicitConfirmed && isInterFreq -> "Confirmed inter-frequency handover: Ch $lastEarfcn -> $earfcn (PCI: $lastPci -> $pci)$tacNote"
                            isExplicitConfirmed -> "Confirmed handover: PCI $lastPci -> $pci$tacNote"
                            tType == "SIGNAL_LOSS" -> "Signal drop before transition to PCI $pci$tacNote"
                            tType == "CELL_RESELECTION" -> "Cell reselection while stationary: PCI $lastPci -> $pci$tacNote"
                            tType == "INTER_FREQ" -> "Inter-frequency transition: Ch $lastEarfcn -> $earfcn (PCI: $lastPci -> $pci)$tacNote"
                            tType == "INTRA_FREQ" -> "Intra-frequency transition on Ch $earfcn (PCI: $lastPci -> $pci)$tacNote"
                            else -> "Observed transition from PCI $lastPci to PCI $pci"
                        }
                        reconstructedTransitions.add(
                            CellTransitionEntity(
                                sessionId = 0L,
                                timestampIso = isoTs,
                                timestampMs = tsMs,
                                latitude = if (hasValidGps) lat else null,
                                longitude = if (hasValidGps) lon else null,
                                fromPci = lastPci,
                                toPci = pci,
                                fromRsrp = lastRsrp,
                                toRsrp = rsrp,
                                fromTac = lastTac,
                                toTac = tac,
                                eventType = tType,
                                confidenceScore = conf,
                                details = detail,
                                fromChannel = lastEarfcn,
                                toChannel = earfcn
                            )
                        )
                    }
                    if (pci != null) {
                        lastPci = pci
                        if (tac != null) lastTac = tac
                        lastRsrp = rsrp
                        if (earfcn != null) lastEarfcn = earfcn
                    }
                }

                CsvSectionType.ACTIVE_QOE -> {
                    if (tokens.size >= 10) {
                        val getVal: (String) -> String? = { k -> colMap[k]?.let { if (it < tokens.size) tokens[it].trim().takeIf { s -> s.isNotEmpty() } else null } }
                        val tsIso = getVal("timestamp_iso") ?: TelecomTimeUtils.formatIsoUtc(System.currentTimeMillis())
                        val tsMs = getVal("timestamp_epoch_ms")?.toLongOrNull() ?: parseTimestampToMs(tsIso) ?: System.currentTimeMillis()
                        val testType = getVal("test_trigger") ?: "FULL_CYCLE"
                        val testStatus = getVal("test_status") ?: "COMPLETED"
                        val skippedReason = getVal("skipped_reason")
                        val schedMs = getVal("scheduled_epoch_ms")?.toLongOrNull()
                        val lat = getVal("latitude")?.toDoubleOrNull()
                        val lon = getVal("longitude")?.toDoubleOrNull()
                        val acc = getVal("gps_accuracy_m")?.toFloatOrNull()
                        val rat = getVal("serving_rat")
                        val pci = getVal("serving_pci")?.toIntOrNull()
                        val chan = getVal("serving_channel")?.toIntOrNull()
                        val rsrp = getVal("serving_rsrp_dbm")?.toIntOrNull()
                        val endpoint = getVal("endpoint_url")
                        val dlMbps = getVal("download_throughput_mbps")?.toDoubleOrNull()
                        val ulMbps = getVal("upload_throughput_mbps")?.toDoubleOrNull()
                        val dlBytes = getVal("download_bytes_transferred")?.toLongOrNull() ?: 0L
                        val ulBytes = getVal("upload_bytes_transferred")?.toLongOrNull() ?: 0L
                        val dlDurMs = getVal("download_duration_ms")?.toLongOrNull() ?: 0L
                        val ulDurMs = getVal("upload_duration_ms")?.toLongOrNull() ?: 0L
                        val rttMed = getVal("rtt_median_ms")?.toDoubleOrNull()?.roundToLong()
                        val rttMean = getVal("rtt_mean_ms")?.toDoubleOrNull()
                        val rttP95 = getVal("rtt_p95_ms")?.toDoubleOrNull()
                        val jitter = getVal("jitter_ms")?.toDoubleOrNull()
                        val loss = getVal("probe_loss_pct")?.toDoubleOrNull()
                        val attempted = getVal("probes_attempted")?.toIntOrNull() ?: 4
                        val successful = getVal("probes_successful")?.toIntOrNull() ?: if (testStatus == "COMPLETED") 4 else 0
                        val classification = getVal("classification")
                        val provenance = getVal("data_provenance") ?: "MEASURED"

                        parsedQoeTests.add(
                            ActiveQoeTestEntity(
                                id = 0L,
                                sessionId = 0L,
                                timestampIso = tsIso,
                                timestampMs = tsMs,
                                latitude = lat,
                                longitude = lon,
                                testType = testType,
                                latencyMs = rttMed,
                                downloadSpeedMbps = dlMbps,
                                uploadSpeedMbps = ulMbps,
                                bytesTransferred = dlBytes + ulBytes,
                                durationMs = dlDurMs + ulDurMs,
                                success = testStatus.equals("COMPLETED", ignoreCase = true),
                                httpStatusCode = if (testStatus.equals("COMPLETED", ignoreCase = true)) 200 else null,
                                errorMessage = skippedReason,
                                servingRat = rat,
                                servingPci = pci,
                                servingEarfcn = chan,
                                rsrpDbm = rsrp,
                                jitterMs = jitter,
                                packetLossPct = loss,
                                congestionClassification = classification,
                                testStatus = testStatus,
                                skippedReason = skippedReason,
                                scheduledTimestampMs = schedMs,
                                gpsAccuracyM = acc,
                                endpointUrl = endpoint,
                                dlBytes = dlBytes,
                                dlDurationMs = dlDurMs,
                                ulBytes = ulBytes,
                                ulDurationMs = ulDurMs,
                                probeCount = attempted,
                                successfulProbeCount = successful,
                                p95LatencyMs = rttP95,
                                meanLatencyMs = rttMean,
                                dataProvenance = provenance
                            )
                        )
                    }
                }

                CsvSectionType.CELL_TRANSITIONS -> {
                    if (tokens.size >= 8) {
                        val getVal: (String) -> String? = { k -> colMap[k]?.let { if (it < tokens.size) tokens[it].trim().takeIf { s -> s.isNotEmpty() } else null } }
                        val tsIso = getVal("timestamp") ?: TelecomTimeUtils.formatIsoUtc(System.currentTimeMillis())
                        val tsMs = parseTimestampToMs(tsIso) ?: System.currentTimeMillis()
                        val lat = getVal("latitude")?.toDoubleOrNull()
                        val lon = getVal("longitude")?.toDoubleOrNull()
                        val rawEv = getVal("event type") ?: getVal("event_type") ?: "OBSERVED_TRANSITION"
                        val fRat = getVal("from rat")
                        val tRat = getVal("to rat")
                        val fPci = getVal("from pci")?.toIntOrNull()
                        val tPci = getVal("to pci")?.toIntOrNull()
                        val fChan = getVal("from channel")?.toIntOrNull()
                        val tChan = getVal("to channel")?.toIntOrNull()
                        val fCell = getVal("from cell id")?.toIntOrNull()
                        val tCell = getVal("to cell id")?.toIntOrNull()
                        val fRsrp = getVal("from signal (dbm)")?.toIntOrNull()
                        val tRsrp = getVal("to signal (dbm)")?.toIntOrNull()
                        val fTac = getVal("from tac/lac")?.toIntOrNull()
                        val tTac = getVal("to tac/lac")?.toIntOrNull()
                        val det = getVal("details") ?: "Cell transition $rawEv"

                        val isTacChange = (fTac != null && tTac != null && fTac != tTac)
                        val isExplicitConfirmed = rawEv.contains("CONFIRMED", ignoreCase = true) || det.contains("confirmed", ignoreCase = true)
                        val isConfirmed = isTacChange || isExplicitConfirmed
                        val evType = if (isConfirmed) "CONFIRMED_HANDOVER" else rawEv
                        val conf = if (isTacChange || isExplicitConfirmed) 0.95f else (getVal("confidence score")?.toFloatOrNull() ?: 0.85f)

                        parsedExplicitTransitions.add(
                            CellTransitionEntity(
                                id = 0L,
                                sessionId = 0L,
                                timestampIso = tsIso,
                                timestampMs = tsMs,
                                latitude = lat,
                                longitude = lon,
                                fromPci = fPci,
                                toPci = tPci,
                                fromRsrp = fRsrp,
                                toRsrp = tRsrp,
                                fromTac = fTac,
                                toTac = tTac,
                                eventType = evType,
                                confidenceScore = conf,
                                details = det,
                                fromRat = fRat,
                                toRat = tRat,
                                fromChannel = fChan,
                                toChannel = tChan,
                                fromCellId = fCell,
                                toCellId = tCell
                            )
                        )
                    }
                }

                CsvSectionType.NETWORK_CONTINUITY -> {
                    if (tokens.size >= 8) {
                        val getVal: (String) -> String? = { k -> colMap[k]?.let { if (it < tokens.size) tokens[it].trim().takeIf { s -> s.isNotEmpty() } else null } }
                        val tsIso = getVal("timestamp") ?: TelecomTimeUtils.formatIsoUtc(System.currentTimeMillis())
                        val tsMs = parseTimestampToMs(tsIso) ?: System.currentTimeMillis()
                        val lat = getVal("latitude")?.toDoubleOrNull()
                        val lon = getVal("longitude")?.toDoubleOrNull()
                        val acc = getVal("accuracy (m)")?.toFloatOrNull()
                        val fGen = getVal("from generation") ?: "4G"
                        val tGen = getVal("to generation") ?: "2G"
                        val fTech = getVal("from tech")
                        val tTech = getVal("to tech")
                        val cat = getVal("transition category") ?: "DOWNGRADE"
                        val fSig = getVal("from signal (dbm)")?.toIntOrNull()
                        val tSig = getVal("to signal (dbm)")?.toIntOrNull()
                        val dur = getVal("duration (s)")?.toLongOrNull()
                        val dataConn = getVal("data connectivity state")
                        val evidence = getVal("evidence classification") ?: "CONFIRMED_GENERATION_TRANSITION"
                        val det = getVal("details") ?: "Continuity event"

                        parsedContinuityEvents.add(
                            NetworkContinuityEventEntity(
                                id = 0L,
                                sessionId = 0L,
                                timestampIso = tsIso,
                                timestampMs = tsMs,
                                latitude = lat,
                                longitude = lon,
                                accuracyM = acc,
                                fromGeneration = fGen,
                                toGeneration = tGen,
                                fromTech = fTech,
                                toTech = tTech,
                                transitionCategory = cat,
                                fromSignalDbm = fSig,
                                toSignalDbm = tSig,
                                durationSeconds = dur,
                                dataConnectivityState = dataConn,
                                evidenceClassification = evidence,
                                details = det
                            )
                        )
                    }
                }

                CsvSectionType.SERVICE_HOLES -> {
                    if (tokens.size >= 6) {
                        val getVal: (String) -> String? = { k -> colMap[k]?.let { if (it < tokens.size) tokens[it].trim().takeIf { s -> s.isNotEmpty() } else null } }
                        val tsIso = getVal("timestamp") ?: TelecomTimeUtils.formatIsoUtc(System.currentTimeMillis())
                        val tsMs = parseTimestampToMs(tsIso) ?: System.currentTimeMillis()
                        val lat = getVal("latitude")?.toDoubleOrNull()
                        val lon = getVal("longitude")?.toDoubleOrNull()
                        val acc = getVal("accuracy (m)")?.toFloatOrNull()
                        val hType = getVal("hole type") ?: "RADIO_OUT_OF_SERVICE"
                        val sev = getVal("severity") ?: "HIGH"
                        val dur = getVal("duration (ms)")?.toLongOrNull()
                        val recTs = getVal("recovery timestamp")?.toLongOrNull()
                        val rsrp = getVal("rsrp (dbm)")?.toIntOrNull()
                        val nType = getVal("network type")
                        val nGen = getVal("network generation")
                        val evid = getVal("engineering evidence") ?: "Observed RF hole"
                        val res = getVal("resolution status") ?: "RECOVERED"

                        parsedServiceHoles.add(
                            ServiceHoleEntity(
                                id = 0L,
                                sessionId = 0L,
                                timestampIso = tsIso,
                                timestampMs = tsMs,
                                latitude = lat,
                                longitude = lon,
                                accuracyM = acc,
                                holeType = hType,
                                severity = sev,
                                durationMs = dur,
                                recoveryTimestampMs = recTs,
                                rsrpDbm = rsrp,
                                networkType = nType,
                                networkGen = nGen,
                                engineeringEvidence = evid,
                                resolutionStatus = res
                            )
                        )
                    }
                }

                CsvSectionType.CALL_RELIABILITY -> {
                    if (tokens.size >= 6) {
                        val getVal: (String) -> String? = { k -> colMap[k]?.let { if (it < tokens.size) tokens[it].trim().takeIf { s -> s.isNotEmpty() } else null } }
                        val tsIso = getVal("timestamp") ?: TelecomTimeUtils.formatIsoUtc(System.currentTimeMillis())
                        val tsMs = parseTimestampToMs(tsIso) ?: System.currentTimeMillis()
                        val lat = getVal("latitude")?.toDoubleOrNull()
                        val lon = getVal("longitude")?.toDoubleOrNull()
                        val acc = getVal("accuracy (m)")?.toFloatOrNull()
                        val cState = getVal("call state") ?: "IDLE"
                        val classif = getVal("classification") ?: "NORMAL_CALL_END"
                        val prevState = getVal("previous state")
                        val nGen = getVal("network generation")
                        val dTech = getVal("detailed tech")
                        val rsrp = getVal("rsrp (dbm)")?.toIntOrNull()
                        val sState = getVal("service state")
                        val dur = getVal("call duration (s)")?.toLongOrNull()
                        val risk = getVal("risk score category") ?: "LOW"
                        val evid = getVal("evidence factors") ?: "Call event"

                        parsedCallEvents.add(
                            CallReliabilityEventEntity(
                                id = 0L,
                                sessionId = 0L,
                                timestampIso = tsIso,
                                timestampMs = tsMs,
                                latitude = lat,
                                longitude = lon,
                                accuracyM = acc,
                                callState = cState,
                                classification = classif,
                                previousState = prevState,
                                networkGeneration = nGen,
                                detailedTech = dTech,
                                rsrpDbm = rsrp,
                                serviceState = sState,
                                callDurationSec = dur,
                                riskScoreCategory = risk,
                                evidenceFactors = evid
                            )
                        )
                    }
                }
            }

            if (parsedTelemetry.size % 250 == 0) {
                onProgress?.invoke(parsedTelemetry.size, parsedTelemetry.size + 100)
            }
        }

        val baseSessionName = filename.substringBeforeLast(".")
        val fingerprint = "FP_${parsedTelemetry.size}_${firstTsMs}_${lastTsMs}_${(totalDistM).toInt()}"

        val db = CellScopeDatabase.getDatabase(context)
        val sessionDao = db.sessionDao()
        val measurementDao = db.measurementDao()
        val transitionDao = db.transitionDao()
        val qoeDao = db.qoeDao()
        val continuityDao = db.continuityDao()
        val callDao = db.callReliabilityDao()

        val existingSessions = sessionDao.getAllSessions()
        val exactMatch = existingSessions.firstOrNull { s ->
            s.sessionName.equals(baseSessionName, ignoreCase = true) ||
                    (firstTsMs > 0 && s.startTimeMs == firstTsMs && s.sampleCount == parsedTelemetry.size) ||
                    (s.notes != null && s.notes!!.contains(fingerprint))
        }

        if (exactMatch != null && !allowDuplicate) {
            return@withContext exactMatch
        }

        val sessionName = if (allowDuplicate && exactMatch != null) {
            "${baseSessionName}_Copy_${System.currentTimeMillis() % 1000}"
        } else {
            baseSessionName
        }

        val avgRsrp = if (rsrpCount > 0) rsrpSum.toDouble() / rsrpCount else null
        val importTimestampStr = TelecomTimeUtils.formatBdtPretty(System.currentTimeMillis())
        val flaggedClampCount = parsedTelemetry.count { it.rsrpDbm == -44 }

        val session = DriveSession(
            sessionName = sessionName,
            startTimeMs = firstTsMs,
            endTimeMs = lastTsMs,
            sampleCount = parsedTelemetry.size,
            notes = "Source: $filename | Imported: $importTimestampStr (BST) | Samples: ${parsedTelemetry.size} | QoE: ${parsedQoeTests.size} | Transitions: ${if (parsedExplicitTransitions.isNotEmpty()) parsedExplicitTransitions.size else reconstructedTransitions.size} | Fingerprint: $fingerprint | FlaggedClamp: $flaggedClampCount",
            totalDistanceM = totalDistM,
            maxSpeedKmh = maxSpeed,
            avgRsrpDbm = avgRsrp,
            gpsAvailabilityPct = if (parsedTelemetry.isNotEmpty()) (parsedTelemetry.count { it.isGpsValid }.toFloat() / parsedTelemetry.size * 100f) else 0f,
            radioAvailabilityPct = 100f
        )

        val sessionId = sessionDao.insertSession(session)
        val finalizedSession = session.copy(id = sessionId)

        // Bind sessionId to all entities
        val boundTelemetry = parsedTelemetry.map { it.copy(sessionId = sessionId) }
        val effectiveTransitions = if (parsedExplicitTransitions.isNotEmpty()) parsedExplicitTransitions else reconstructedTransitions
        val boundTransitions = effectiveTransitions.map { it.copy(sessionId = sessionId) }
        val boundQoe = parsedQoeTests.map { it.copy(sessionId = sessionId) }
        val boundContinuity = parsedContinuityEvents.map { it.copy(sessionId = sessionId) }
        val boundHoles = parsedServiceHoles.map { it.copy(sessionId = sessionId) }
        val boundCalls = parsedCallEvents.map { it.copy(sessionId = sessionId) }

        // Batch inserts
        boundTelemetry.chunked(500).forEach { chunk ->
            measurementDao.insertAll(chunk)
        }
        if (boundTransitions.isNotEmpty()) {
            boundTransitions.chunked(500).forEach { chunk ->
                transitionDao.insertAll(chunk)
            }
        }
        if (boundQoe.isNotEmpty()) {
            qoeDao.insertQoeTests(boundQoe)
        }
        if (boundContinuity.isNotEmpty()) {
            continuityDao.insertContinuityEvents(boundContinuity)
        }
        if (boundHoles.isNotEmpty()) {
            continuityDao.insertServiceHoles(boundHoles)
        }
        if (boundCalls.isNotEmpty()) {
            callDao.insertCallEvents(boundCalls)
        }

        onProgress?.invoke(parsedTelemetry.size, parsedTelemetry.size)
        return@withContext finalizedSession
    }

    private fun matchHeaders(headers: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        for ((idx, raw) in headers.withIndex()) {
            val trimmed = raw.trim()
            for ((canon, patterns) in TELEMETRY_PATTERNS) {
                if (map.containsKey(canon)) continue
                for (p in patterns) {
                    if (p.matcher(trimmed).matches()) {
                        map[canon] = idx
                        break
                    }
                }
            }
        }
        return map
    }

    fun splitCsvLine(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false

        for (c in line) {
            when {
                c == '\"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    tokens.add(sb.toString().trim())
                    sb.clear()
                }
                else -> sb.append(c)
            }
        }
        tokens.add(sb.toString().trim())
        return tokens
    }

    fun parseTimestampToMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val str = raw.trim()

        try {
            val parsed = DateTimeFormatter.ISO_DATE_TIME.parse(str)
            return Instant.from(parsed).toEpochMilli()
        } catch (_: Exception) {}

        try {
            return Instant.parse(str).toEpochMilli()
        } catch (_: Exception) {}

        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy/MM/dd HH:mm:ss",
            "dd-MM-yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm:ss"
        )
        for (pat in patterns) {
            try {
                val dtf = DateTimeFormatter.ofPattern(pat, Locale.US)
                val ldt = java.time.LocalDateTime.parse(str, dtf)
                return ldt.atZone(DHAKA_ZONE).toInstant().toEpochMilli()
            } catch (_: Exception) {}
        }

        return str.toLongOrNull()
    }

    fun calculateHaversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
