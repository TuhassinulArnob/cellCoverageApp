package com.cellscope.drive

import com.cellscope.drive.data.entity.CallReliabilityEventEntity
import com.cellscope.drive.data.entity.NetworkContinuityEventEntity
import com.cellscope.drive.data.entity.ServiceHoleEntity
import com.cellscope.drive.export.CsvExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiGenCsvExportTest {

    @Test
    fun testObservationRowFormatting() {
        val row = listOf(
            CsvExporter.escapeCsv("2026-10-18T10:00:00.000Z"),
            "23.8103",
            "90.4125",
            "3.5",
            "25.40",
            CsvExporter.escapeCsv("5G"),
            CsvExporter.escapeCsv("5G NR (NSA EN-DC)"),
            CsvExporter.escapeCsv("4G LTE"),
            CsvExporter.escapeCsv("IN_SERVICE"),
            CsvExporter.escapeCsv("CONNECTED"),
            "",
            "288",
            "-84",
            "-11",
            "15.2",
            "-58",
            "18452",
            "1600",
            CsvExporter.escapeCsv("Grameenphone"),
            "1"
        ).joinToString(",")

        val parts = row.split(",")
        assertEquals(20, parts.size)
        assertEquals("2026-10-18T10:00:00.000Z", parts[0])
        assertEquals("5G", parts[5])
        assertEquals("5G NR (NSA EN-DC)", parts[6])
        assertEquals("4G LTE", parts[7])
        assertEquals("IN_SERVICE", parts[8])
        assertEquals("CONNECTED", parts[9])
    }

    @Test
    fun testContinuityTransitionRowFormatting() {
        val event = NetworkContinuityEventEntity(
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:00.000Z",
            timestampMs = 1000L,
            latitude = 23.81,
            longitude = 90.41,
            accuracyM = 3.0f,
            fromGeneration = "5G",
            toGeneration = "4G",
            fromTech = "5G NR",
            toTech = "4G LTE",
            transitionCategory = "DOWNGRADE",
            fromSignalDbm = -85,
            toSignalDbm = -89,
            durationSeconds = 120,
            dataConnectivityState = "CONNECTED",
            evidenceClassification = "Network generation change — cause unconfirmed",
            details = "5G to 4G handoff"
        )

        val row = listOf(
            CsvExporter.escapeCsv(event.timestampIso),
            event.latitude?.toString() ?: "",
            event.longitude?.toString() ?: "",
            event.accuracyM?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "",
            CsvExporter.escapeCsv(event.fromGeneration),
            CsvExporter.escapeCsv(event.toGeneration),
            CsvExporter.escapeCsv(event.fromTech ?: ""),
            CsvExporter.escapeCsv(event.toTech ?: ""),
            CsvExporter.escapeCsv(event.transitionCategory),
            event.fromSignalDbm?.toString() ?: "",
            event.toSignalDbm?.toString() ?: "",
            event.durationSeconds?.toString() ?: "",
            CsvExporter.escapeCsv(event.dataConnectivityState ?: ""),
            CsvExporter.escapeCsv(event.evidenceClassification),
            CsvExporter.escapeCsv(event.details)
        ).joinToString(",")

        val parts = row.split(",")
        assertEquals(15, parts.size)
        assertEquals("5G", parts[4])
        assertEquals("4G", parts[5])
        assertEquals("DOWNGRADE", parts[8])
        assertEquals("Network generation change — cause unconfirmed", parts[13])
    }

    @Test
    fun testServiceHoleRowFormatting() {
        val hole = ServiceHoleEntity(
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:00.000Z",
            timestampMs = 1000L,
            latitude = 23.81,
            longitude = 90.41,
            accuracyM = 3.0f,
            holeType = "RADIO_OUT_OF_SERVICE",
            severity = "CRITICAL",
            durationMs = 8000L,
            recoveryTimestampMs = 9000L,
            rsrpDbm = null,
            networkType = "UNKNOWN",
            networkGen = "OOS",
            engineeringEvidence = "Total radio blackout",
            resolutionStatus = "RECOVERED"
        )

        val row = listOf(
            CsvExporter.escapeCsv(hole.timestampIso),
            hole.latitude?.toString() ?: "",
            hole.longitude?.toString() ?: "",
            hole.accuracyM?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "",
            CsvExporter.escapeCsv(hole.holeType),
            CsvExporter.escapeCsv(hole.severity),
            hole.durationMs?.toString() ?: "",
            hole.recoveryTimestampMs?.toString() ?: "",
            hole.rsrpDbm?.toString() ?: "",
            CsvExporter.escapeCsv(hole.networkType ?: ""),
            CsvExporter.escapeCsv(hole.networkGen ?: ""),
            CsvExporter.escapeCsv(hole.engineeringEvidence),
            CsvExporter.escapeCsv(hole.resolutionStatus)
        ).joinToString(",")

        val parts = row.split(",")
        assertEquals(13, parts.size)
        assertEquals("RADIO_OUT_OF_SERVICE", parts[4])
        assertEquals("CRITICAL", parts[5])
        assertEquals("8000", parts[6])
        assertEquals("RECOVERED", parts[12])
    }

    @Test
    fun testCallReliabilityRowFormatting() {
        val callEvent = CallReliabilityEventEntity(
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:00.000Z",
            timestampMs = 1000L,
            latitude = 23.81,
            longitude = 90.41,
            accuracyM = 3.0f,
            callState = "IDLE",
            classification = "SUSPECTED_CALL_DROP",
            previousState = "OFFHOOK",
            networkGeneration = "4G",
            detailedTech = "4G LTE",
            rsrpDbm = -122,
            serviceState = "IN_SERVICE",
            callDurationSec = 45L,
            riskScoreCategory = "HIGH",
            evidenceFactors = "Severe signal drop"
        )

        val row = listOf(
            CsvExporter.escapeCsv(callEvent.timestampIso),
            callEvent.latitude?.toString() ?: "",
            callEvent.longitude?.toString() ?: "",
            callEvent.accuracyM?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "",
            CsvExporter.escapeCsv(callEvent.callState),
            CsvExporter.escapeCsv(callEvent.classification),
            CsvExporter.escapeCsv(callEvent.previousState ?: ""),
            CsvExporter.escapeCsv(callEvent.networkGeneration ?: ""),
            CsvExporter.escapeCsv(callEvent.detailedTech ?: ""),
            callEvent.rsrpDbm?.toString() ?: "",
            CsvExporter.escapeCsv(callEvent.serviceState ?: ""),
            callEvent.callDurationSec?.toString() ?: "",
            CsvExporter.escapeCsv(callEvent.riskScoreCategory),
            CsvExporter.escapeCsv(callEvent.evidenceFactors)
        ).joinToString(",")

        val parts = row.split(",")
        assertEquals(14, parts.size)
        assertEquals("IDLE", parts[4])
        assertEquals("SUSPECTED_CALL_DROP", parts[5])
        assertEquals("OFFHOOK", parts[6])
        assertEquals("HIGH", parts[12])
    }

    @Test
    fun testExtendedCsvHeaderAndRowContainsRatIsolatedFields() {
        val measurement = com.cellscope.drive.data.entity.LteMeasurementEntity(
            id = 1L,
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:00.000Z",
            timestampMs = 1000L,
            latitude = 23.81,
            longitude = 90.41,
            altitudeM = 12.0,
            accuracyM = 3.0f,
            speedKmh = 15.0f,
            networkType = "2G_GSM",
            operatorAlpha = "Robi",
            operatorNumeric = "47002",
            servingPci = null,
            tac = 5001,
            earfcn = null,
            bandwidthKhz = null,
            rsrpDbm = null,
            rsrqDb = null,
            rssiDbm = -44,
            sinrDb = null,
            cqi = null,
            timingAdvance = null,
            isServing = true,
            handoverTrigger = 0,
            servingRat = "2G",
            channelNumber = 60,
            channelType = "ARFCN",
            primarySignalDbm = -44,
            signalMetricType = "GSM_RSSI",
            secondarySignalDb = null
        )

        val row = listOf(
            CsvExporter.escapeCsv(measurement.timestampIso),
            measurement.latitude?.toString() ?: "",
            measurement.longitude?.toString() ?: "",
            measurement.speedKmh?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "",
            measurement.servingPci?.toString() ?: "",
            measurement.rsrpDbm?.toString() ?: "",
            measurement.rsrqDb?.toString() ?: "",
            measurement.sinrDb?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "",
            measurement.handoverTrigger.toString(),
            measurement.tac?.toString() ?: "",
            (measurement.channelNumber ?: measurement.earfcn)?.toString() ?: "",
            (measurement.rssiDbm ?: if (measurement.servingRat == "2G") measurement.primarySignalDbm else null)?.toString() ?: "",
            CsvExporter.escapeCsv(measurement.networkType),
            CsvExporter.escapeCsv(measurement.operatorAlpha ?: ""),
            measurement.altitudeM?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "",
            measurement.accuracyM?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "",
            measurement.bearing?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "",
            measurement.eci?.toString() ?: "",
            measurement.cqi?.toString() ?: "",
            measurement.timingAdvance?.toString() ?: "",
            if (measurement.isGpsValid) "1" else "0",
            CsvExporter.escapeCsv(measurement.transitionType ?: ""),
            CsvExporter.escapeCsv(measurement.servingRat ?: ""),
            measurement.channelNumber?.toString() ?: "",
            CsvExporter.escapeCsv(measurement.channelType ?: ""),
            measurement.primarySignalDbm?.toString() ?: "",
            CsvExporter.escapeCsv(measurement.signalMetricType ?: ""),
            measurement.secondarySignalDb?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: ""
        ).joinToString(",")

        val parts = row.split(",")
        assertEquals(28, parts.size)
        // RSRP column (index 5) must be empty for 2G
        assertEquals("", parts[5])
        // Channel column (index 10) must be 60 (ARFCN)
        assertEquals("60", parts[10])
        // RSSI column (index 11) must be -44
        assertEquals("-44", parts[11])
        // Serving RAT (index 22)
        assertEquals("2G", parts[22])
        // Channel Number (index 23)
        assertEquals("60", parts[23])
        // Channel Type (index 24)
        assertEquals("ARFCN", parts[24])
        // Primary Signal (index 25)
        assertEquals("-44", parts[25])
        // Signal Metric Type (index 26)
        assertEquals("GSM_RSSI", parts[26])
    }
}
