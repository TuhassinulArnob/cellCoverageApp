package com.cellscope.drive

import com.cellscope.drive.data.CsvImporter
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

class MultiSectionCsvRegressionTest {

    private fun findFixture(filename: String): File {
        val candidates = listOf(
            File("../../docs", filename),
            File("../docs", filename),
            File("docs", filename),
            File(System.getProperty("user.dir") ?: ".", "docs/$filename"),
            File(System.getProperty("user.dir") ?: ".", "../../docs/$filename"),
            File("src/test/resources", filename),
            File("android/app/src/test/resources", filename)
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not find fixture $filename in: ${candidates.map { it.absolutePath }}")
    }

    @Test
    fun testValidateSuppliedExtendedCsvFixture() {
        val file = findFixture("cellscope_extended_Survey_20261020_050657_20261020_050657.csv")
        assertTrue("Supplied extended CSV fixture must exist", file.exists())

        val result = FileInputStream(file).use { stream ->
            CsvImporter.validateInputStream(stream, file.name)
        }

        assertTrue("Validation result must be valid", result.isValid)
        assertEquals("cellscope_extended_Survey_20261020_050657_20261020_050657.csv", result.filename)

        // Section counts verification
        assertEquals(11, result.sectionCounts["TELEMETRY"])
        assertEquals(4, result.sectionCounts["ACTIVE_QOE"])
        assertEquals(3, result.sectionCounts["CELL_TRANSITIONS"])
        assertEquals(2, result.sectionCounts["NETWORK_CONTINUITY"])
        assertEquals(2, result.sectionCounts["SERVICE_HOLES"])
        assertEquals(2, result.sectionCounts["CALL_RELIABILITY"])

        // Malformed records reporting (without discarding valid records)
        assertTrue("Must report malformed rows with line numbers", result.malformedSectionRows.isNotEmpty())
        assertTrue("Must report telemetry malformed row", result.malformedSectionRows.any { it.contains("TELEMETRY") })
        assertTrue("Must report QoE malformed row", result.malformedSectionRows.any { it.contains("ACTIVE_QOE") })

        // QoE status breakdown
        assertEquals(2, result.qoeStatusCounts["COMPLETED"])
        assertEquals(1, result.qoeStatusCounts["FAILED"])
        assertEquals(1, result.qoeStatusCounts["SKIPPED"])

        // Transition types breakdown
        assertEquals(1, result.transitionTypeCounts["INTRA_LTE"])
        assertEquals(1, result.transitionTypeCounts["INTER_RAT"])
        assertEquals(1, result.transitionTypeCounts["CELL_RESELECTION"])

        // Distinct RATs
        assertTrue("Must detect 4G", result.detectedRats.any { it.contains("4G") || it.contains("LTE") })
        assertTrue("Must detect 2G", result.detectedRats.any { it.contains("2G") || it.contains("GSM") })
        assertTrue("Must detect 5G", result.detectedRats.any { it.contains("5G") || it.contains("NR") })

        // GPS and timestamps
        assertEquals(10, result.validGpsRows)
        assertEquals(1, result.missingGpsRows)
        assertEquals("Robi", result.detectedOperator)
        assertNotNull("Start timestamp must be parsed", result.startTimestampIso)
        assertNotNull("End timestamp must be parsed", result.endTimestampIso)
        assertTrue("Duration must be non-negative", result.durationSeconds >= 0L)
    }

    @Test
    fun testMalformedRowInOptionalSectionDoesNotDiscardOtherSections() {
        val csvContent = """
            # CellScope Extended Telemetry Format v3.0
            Timestamp,Latitude,Longitude,Speed (km/h),Serving Cell ID,Serving Cell RSRP,Serving Cell RSRQ,Serving Cell CINR,Handover Trigger,TAC,EARFCN,RSSI (dBm),Network Type,Operator
            2026-10-20T05:06:57.100+06:00,23.810331,90.412518,12.50,4,-85,-8,14.5,0,50030,1399,-80,LTE,Robi
            2026-10-20T05:06:58.100+06:00,23.810450,90.412620,12.80,4,-86,-8,14.0,0,50030,1399,-81,LTE,Robi

            # Active QoE & Congestion Section
            test_id,session_id,timestamp_iso,timestamp_epoch_ms,test_trigger,test_status,skipped_reason,scheduled_epoch_ms,execution_delay_ms,latitude,longitude,gps_accuracy_m,serving_rat,serving_pci,serving_channel,serving_rsrp_dbm,endpoint_url,download_throughput_mbps,upload_throughput_mbps,download_bytes_transferred,upload_bytes_transferred,download_duration_ms,upload_duration_ms,rtt_median_ms,rtt_mean_ms,rtt_p95_ms,jitter_ms,probe_loss_pct,probes_attempted,probes_successful,classification,data_provenance
            CORRUPT_QOE_LINE_WITH_THREE_TOKENS,A,B
            101,1,2026-10-20T05:06:57.500+06:00,1792451217500,SURVEY_AUTOMATION,COMPLETED,,1792451217000,500,23.810331,90.412518,3.5,4G,4,1399,-85,https://speed.cloudflare.com/__down?bytes=10000000,45.20,12.80,10485760,2097152,1855,1310,28,29.2,35.0,3.20,0.0,4,4,HEALTHY_UNCONGESTED,MEASURED_ACTIVE_TEST

            # Cell Transitions (Mobility & Handover) Section
            Timestamp,Latitude,Longitude,Event Type,From RAT,To RAT,From PCI,To PCI,From Channel,To Channel,From Cell ID,To Cell ID,From Signal (dBm),To Signal (dBm),From TAC/LAC,To TAC/LAC,Confidence Score,Details
            2026-10-20T05:07:00.100+06:00,23.810690,90.412840,INTRA_LTE,4G,4G,4,5,1399,1399,268435457,268435458,-88,-92,50030,50030,0.95,Intra-LTE handover from PCI 4 to PCI 5
        """.trimIndent()

        val result = ByteArrayInputStream(csvContent.toByteArray(Charsets.UTF_8)).use { stream ->
            CsvImporter.validateInputStream(stream, "synthetic_test.csv")
        }

        assertTrue("Telemetry must be preserved even if QoE section has malformed line", result.isValid)
        assertEquals(2, result.validRows)
        assertEquals(2, result.sectionCounts["TELEMETRY"])
        assertEquals(1, result.sectionCounts["ACTIVE_QOE"])
        assertEquals(1, result.sectionCounts["CELL_TRANSITIONS"])
        assertTrue("Must report malformed QoE row", result.malformedSectionRows.any { it.contains("ACTIVE_QOE") })
    }
}
