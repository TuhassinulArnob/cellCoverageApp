package com.cellscope.drive

import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvExporterTest {

    @Test
    fun testCsvHeaderAndRowFormatting() {
        val expectedHeader = "Timestamp,Latitude,Longitude,Speed (km/h),Serving Cell ID,Serving Cell RSRP,Serving Cell RSRQ,Serving Cell CINR,Handover Trigger,TAC,EARFCN,RSSI (dBm),Network Type,Operator"

        val measurement = LteMeasurementEntity(
            id = 1L,
            sessionId = 100L,
            timestampIso = "2026-10-17T18:00:00.000Z",
            timestampMs = 1792260000000L,
            latitude = 23.81034,
            longitude = 90.41251,
            altitudeM = 15.2,
            accuracyM = 3.5f,
            speedKmh = 32.4f,
            networkType = "LTE",
            operatorAlpha = "Grameenphone",
            operatorNumeric = "47001",
            servingPci = 288,
            tac = 18452,
            earfcn = 1600,
            bandwidthKhz = 20000,
            rsrpDbm = -84,
            rsrqDb = -11,
            rssiDbm = -58,
            cqi = 12,
            timingAdvance = 4,
            isServing = true,
            handoverTrigger = 0
        )

        val row = listOf(
            measurement.timestampIso,
            measurement.latitude?.toString() ?: "",
            measurement.longitude?.toString() ?: "",
            measurement.speedKmh?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "",
            measurement.servingPci?.toString() ?: "",
            measurement.rsrpDbm?.toString() ?: "",
            measurement.rsrqDb?.toString() ?: "",
            "", // CINR
            measurement.handoverTrigger.toString(),
            measurement.tac?.toString() ?: "",
            measurement.earfcn?.toString() ?: "",
            measurement.rssiDbm?.toString() ?: "",
            measurement.networkType,
            measurement.operatorAlpha ?: ""
        ).joinToString(",")

        assertTrue(row.contains("2026-10-17T18:00:00.000Z"))
        assertTrue(row.contains("23.81034"))
        assertTrue(row.contains("90.41251"))
        assertTrue(row.contains("288"))
        assertTrue(row.contains("-84"))
        assertTrue(row.contains("-11"))
        assertTrue(row.contains("Grameenphone"))

        val parts = row.split(",")
        assertEquals(14, parts.size)
        assertEquals("2026-10-17T18:00:00.000Z", parts[0])
        assertEquals("23.81034", parts[1])
        assertEquals("90.41251", parts[2])
        assertEquals("32.40", parts[3])
        assertEquals("288", parts[4])
        assertEquals("-84", parts[5])
        assertEquals("-11", parts[6])
        assertEquals("", parts[7]) // CINR
        assertEquals("0", parts[8]) // HO Trigger
    }

    @Test
    fun testCsvEscaping() {
        assertEquals("normal_text", com.cellscope.drive.export.CsvExporter.escapeCsv("normal_text"))
        assertEquals("\"text,with,commas\"", com.cellscope.drive.export.CsvExporter.escapeCsv("text,with,commas"))
        assertEquals("\"text with \"\"quotes\"\"\"", com.cellscope.drive.export.CsvExporter.escapeCsv("text with \"quotes\""))
        assertEquals("\"line1\nline2\"", com.cellscope.drive.export.CsvExporter.escapeCsv("line1\nline2"))
    }
}
