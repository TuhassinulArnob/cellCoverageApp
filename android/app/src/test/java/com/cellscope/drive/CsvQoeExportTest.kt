package com.cellscope.drive

import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.export.CsvExporter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Unit tests for Canonical Active QoE CSV Schema & Non-Zero-Filling Verification.
 * Validates the 32-column canonical header and precise row serialization.
 */
class CsvQoeExportTest {

    @Test
    fun testActiveQoeCanonicalHeaderColumns() {
        val header = CsvExporter.activeQoeCanonicalHeader
        val columns = header.split(",")

        assertEquals("Canonical Active QoE CSV schema must have exactly 32 columns", 32, columns.size)

        val expectedColumns = listOf(
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
        )

        for (i in expectedColumns.indices) {
            assertEquals("Column $i mismatch", expectedColumns[i], columns[i])
        }
    }

    private fun formatQoeRow(t: ActiveQoeTestEntity): String {
        val execDelay = if (t.scheduledTimestampMs != null) (t.timestampMs - t.scheduledTimestampMs!!).coerceAtLeast(0L) else null
        return listOf(
            t.id.toString(),
            t.sessionId.toString(),
            CsvExporter.escapeCsv(t.timestampIso),
            t.timestampMs.toString(),
            CsvExporter.escapeCsv(t.testType),
            CsvExporter.escapeCsv(t.testStatus),
            CsvExporter.escapeCsv(t.skippedReason ?: ""),
            t.scheduledTimestampMs?.toString() ?: "",
            execDelay?.toString() ?: "",
            t.latitude?.toString() ?: "",
            t.longitude?.toString() ?: "",
            t.gpsAccuracyM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            CsvExporter.escapeCsv(t.servingRat ?: ""),
            t.servingPci?.toString() ?: "",
            t.servingEarfcn?.toString() ?: "",
            t.rsrpDbm?.toString() ?: "",
            CsvExporter.escapeCsv(t.endpointUrl ?: ""),
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
            CsvExporter.escapeCsv(t.congestionClassification ?: ""),
            CsvExporter.escapeCsv(t.dataProvenance)
        ).joinToString(",")
    }

    @Test
    fun testSuccessfulTestRowFormatting() {
        val entity = ActiveQoeTestEntity(
            id = 501L,
            sessionId = 10L,
            timestampIso = "2026-10-19T10:00:02.500Z",
            timestampMs = 1729332002500L,
            testType = "SCHEDULED_PERIODIC",
            testStatus = "COMPLETED",
            success = true,
            skippedReason = null,
            scheduledTimestampMs = 1729332000000L,
            latitude = 23.8103,
            longitude = 90.4125,
            gpsAccuracyM = 3.2f,
            servingRat = "5G",
            servingPci = 350,
            servingEarfcn = 632000,
            rsrpDbm = -82,
            endpointUrl = "https://speed.cloudflare.com/__down",
            downloadSpeedMbps = 65.40,
            uploadSpeedMbps = 24.80,
            dlBytes = 1048576L,
            ulBytes = 262144L,
            dlDurationMs = 128L,
            ulDurationMs = 84L,
            latencyMs = 22L,
            meanLatencyMs = 23.5,
            p95LatencyMs = 31.0,
            jitterMs = 2.8,
            packetLossPct = 0.0,
            probeCount = 4,
            successfulProbeCount = 4,
            congestionClassification = "NOMINAL",
            dataProvenance = "MEASURED"
        )

        val row = formatQoeRow(entity)
        val parts = row.split(",")
        assertEquals(32, parts.size)

        assertEquals("501", parts[0]) // test_id
        assertEquals("10", parts[1]) // session_id
        assertEquals("2026-10-19T10:00:02.500Z", parts[2]) // timestamp_iso
        assertEquals("1729332002500", parts[3]) // timestamp_epoch_ms
        assertEquals("SCHEDULED_PERIODIC", parts[4]) // test_trigger
        assertEquals("COMPLETED", parts[5]) // test_status
        assertEquals("", parts[6]) // skipped_reason (empty)
        assertEquals("1729332000000", parts[7]) // scheduled_epoch_ms
        assertEquals("2500", parts[8]) // execution_delay_ms (1729332002500 - 1729332000000)
        assertEquals("23.8103", parts[9]) // latitude
        assertEquals("90.4125", parts[10]) // longitude
        assertEquals("3.2", parts[11]) // gps_accuracy_m
        assertEquals("5G", parts[12]) // serving_rat
        assertEquals("350", parts[13]) // serving_pci
        assertEquals("632000", parts[14]) // serving_channel
        assertEquals("-82", parts[15]) // serving_rsrp_dbm
        assertEquals("https://speed.cloudflare.com/__down", parts[16]) // endpoint_url
        assertEquals("65.40", parts[17]) // download_throughput_mbps
        assertEquals("24.80", parts[18]) // upload_throughput_mbps
        assertEquals("1048576", parts[19]) // download_bytes_transferred
        assertEquals("262144", parts[20]) // upload_bytes_transferred
        assertEquals("128", parts[21]) // download_duration_ms
        assertEquals("84", parts[22]) // upload_duration_ms
        assertEquals("22", parts[23]) // rtt_median_ms
        assertEquals("23.5", parts[24]) // rtt_mean_ms
        assertEquals("31.0", parts[25]) // rtt_p95_ms
        assertEquals("2.80", parts[26]) // jitter_ms
        assertEquals("0.0", parts[27]) // probe_loss_pct
        assertEquals("4", parts[28]) // probes_attempted
        assertEquals("4", parts[29]) // probes_successful
        assertEquals("NOMINAL", parts[30]) // classification
        assertEquals("MEASURED", parts[31]) // data_provenance
    }

    @Test
    fun testSkippedTestRowFormatting_NoZeroFilling() {
        val skippedEntity = ActiveQoeTestEntity(
            id = 502L,
            sessionId = 10L,
            timestampIso = "2026-10-19T10:10:00.000Z",
            timestampMs = 1729332600000L,
            testType = "SCHEDULED_PERIODIC",
            testStatus = "SKIPPED",
            success = false,
            skippedReason = "Daily budget of 50.0 MB exceeded (used: 52.4 MB)",
            scheduledTimestampMs = 1729332600000L,
            latitude = 23.8104,
            longitude = 90.4126,
            servingRat = "4G LTE",
            servingPci = 177,
            downloadSpeedMbps = null,
            uploadSpeedMbps = null,
            latencyMs = null,
            meanLatencyMs = null,
            p95LatencyMs = null,
            jitterMs = null,
            packetLossPct = null,
            probeCount = 0,
            successfulProbeCount = 0,
            congestionClassification = "UNAVAILABLE",
            dataProvenance = "SKIPPED"
        )

        val row = formatQoeRow(skippedEntity)
        val parts = row.split(",")
        assertEquals(32, parts.size)

        assertEquals("502", parts[0])
        assertEquals("SKIPPED", parts[5])
        assertEquals("Daily budget of 50.0 MB exceeded (used: 52.4 MB)", parts[6])
        assertEquals("0", parts[8]) // execution delay: 0ms

        // CRITICAL ACCEPTANCE CRITERIA: Strictly empty string, NOT "0.0" or "0"
        assertEquals("Download throughput must be empty for skipped test", "", parts[17])
        assertEquals("Upload throughput must be empty for skipped test", "", parts[18])
        assertEquals("RTT median must be empty for skipped test", "", parts[23])
        assertEquals("RTT mean must be empty for skipped test", "", parts[24])
        assertEquals("RTT p95 must be empty for skipped test", "", parts[25])
        assertEquals("Jitter must be empty for skipped test", "", parts[26])
        assertEquals("Probe loss must be empty for skipped test", "", parts[27])
    }

    @Test
    fun testFailedTestRowFormatting_NoZeroFilling() {
        val failedEntity = ActiveQoeTestEntity(
            id = 503L,
            sessionId = 10L,
            timestampIso = "2026-10-19T10:20:01.000Z",
            timestampMs = 1729333201000L,
            testType = "SCHEDULED_PERIODIC",
            testStatus = "FAILED",
            success = false,
            skippedReason = null,
            scheduledTimestampMs = 1729333200000L,
            downloadSpeedMbps = null,
            uploadSpeedMbps = null,
            latencyMs = null,
            meanLatencyMs = null,
            p95LatencyMs = null,
            jitterMs = null,
            packetLossPct = 100.0,
            probeCount = 4,
            successfulProbeCount = 0,
            congestionClassification = "FAILED",
            dataProvenance = "MEASURED"
        )

        val row = formatQoeRow(failedEntity)
        val parts = row.split(",")
        assertEquals(32, parts.size)

        assertEquals("FAILED", parts[5])
        assertEquals("", parts[6]) // No skip reason

        // Throughput and latency must NOT be zero-filled
        assertEquals("", parts[17]) // DL throughput
        assertEquals("", parts[18]) // UL throughput
        assertEquals("", parts[23]) // RTT median
        assertEquals("", parts[24]) // RTT mean
        assertEquals("", parts[25]) // RTT p95
        assertEquals("", parts[26]) // Jitter
        assertEquals("100.0", parts[27]) // Probe loss 100%
        assertEquals("4", parts[28]) // Probe attempted
        assertEquals("0", parts[29]) // Probe successful
    }
}
