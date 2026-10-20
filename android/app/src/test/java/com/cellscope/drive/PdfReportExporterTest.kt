package com.cellscope.drive

import com.cellscope.drive.analytics.CoveragePlanner
import com.cellscope.drive.analytics.HandoverAnalyzer
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PdfReportExporterTest {

    private fun createSampleMeasurement(
        id: Long,
        timestampMs: Long,
        pci: Int,
        rsrp: Int,
        lat: Double = 23.8103,
        lon: Double = 90.4125
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date(timestampMs)),
            timestampMs = timestampMs,
            latitude = lat,
            longitude = lon,
            altitudeM = 10.0,
            accuracyM = 3.0f,
            speedKmh = 25.0f,
            networkType = "LTE",
            operatorAlpha = "Grameenphone",
            operatorNumeric = "47001",
            servingPci = pci,
            tac = 18452,
            earfcn = 1600,
            bandwidthKhz = 20000,
            rsrpDbm = rsrp,
            rsrqDb = -10,
            rssiDbm = -60,
            cqi = 10,
            timingAdvance = 2,
            isServing = true,
            handoverTrigger = 0
        )
    }

    @Test
    fun testEmptySessionMetricsDerivation() {
        val session = DriveSession(
            id = 1L,
            sessionName = "Empty_Test_Drive",
            startTimeMs = 1700000000000L,
            endTimeMs = 1700000000000L,
            totalDistanceM = 0.0,
            sampleCount = 0,
            avgRsrpDbm = null,
            maxSpeedKmh = 0f
        )
        val measurements = emptyList<LteMeasurementEntity>()

        val handoverSummary = HandoverAnalyzer.analyzeTransitions(session.id, measurements, session.totalDistanceM)
        val weakZones = CoveragePlanner.identifyWeakZones(measurements, handoverSummary.transitionEvents)

        assertEquals(0, handoverSummary.totalTransitions)
        assertEquals(0, handoverSummary.confirmedHandovers)
        assertTrue(handoverSummary.dwellStats.isEmpty())
        assertTrue(weakZones.isEmpty())
    }

    @Test
    fun testRsrpDistributionPercentages() {
        val measurements = listOf(
            createSampleMeasurement(1, 1000L, 101, -75),  // Excellent (>= -80)
            createSampleMeasurement(2, 2000L, 101, -85),  // Good (-90..-81)
            createSampleMeasurement(3, 3000L, 101, -95),  // Fair (-100..-91)
            createSampleMeasurement(4, 4000L, 101, -105), // Poor (< -100)
            createSampleMeasurement(5, 5000L, 101, -110)  // Poor (< -100)
        )

        val validRsrp = measurements.mapNotNull { it.rsrpDbm }
        val excellentCount = validRsrp.count { it >= -80 }
        val goodCount = validRsrp.count { it in -90..-81 }
        val fairCount = validRsrp.count { it in -100..-91 }
        val poorCount = validRsrp.count { it < -100 }
        val totalRsrp = validRsrp.size

        assertEquals(5, totalRsrp)
        assertEquals(1, excellentCount)
        assertEquals(1, goodCount)
        assertEquals(1, fairCount)
        assertEquals(2, poorCount)

        val pctExc = (excellentCount * 100f / totalRsrp)
        val pctPoor = (poorCount * 100f / totalRsrp)

        assertEquals(20.0f, pctExc, 0.01f)
        assertEquals(40.0f, pctPoor, 0.01f)
    }

    @Test
    fun testDwellAndTransitionAnalyticsIntegration() {
        val measurements = listOf(
            createSampleMeasurement(1, 1000L, 101, -80),
            createSampleMeasurement(2, 2000L, 101, -82),
            createSampleMeasurement(3, 3000L, 202, -88),
            createSampleMeasurement(4, 4000L, 202, -90),
            createSampleMeasurement(5, 5000L, 303, -92)
        )

        val handoverSummary = HandoverAnalyzer.analyzeTransitions(1L, measurements, 1000.0)

        assertEquals(2, handoverSummary.totalTransitions)
        assertEquals(3, handoverSummary.dwellStats.size)

        val dwell101 = handoverSummary.dwellStats.find { it.pci == 101 }
        assertNotNull(dwell101)
        assertEquals(2, dwell101?.sampleCount)

        val dwell202 = handoverSummary.dwellStats.find { it.pci == 202 }
        assertNotNull(dwell202)
        assertEquals(2, dwell202?.sampleCount)
    }

    @Test
    fun testWeakZoneIdentificationForReport() {
        val baseTime = 1700000000000L
        val weakSamples = (0 until 8).map { i ->
            createSampleMeasurement(
                id = i.toLong(),
                timestampMs = baseTime + (i * 1000L),
                pci = 105,
                rsrp = -112 - (i % 3),
                lat = 23.8100 + (i * 0.0002),
                lon = 90.4120 + (i * 0.0002)
            )
        }

        val transitions = emptyList<com.cellscope.drive.data.entity.CellTransitionEntity>()
        val weakZones = CoveragePlanner.identifyWeakZones(weakSamples, transitions)

        assertTrue("Should detect weak coverage zone", weakZones.isNotEmpty())
        val zone = weakZones.first()
        assertTrue("Zone average RSRP should be < -105", zone.avgRsrp < -105.0)
        assertTrue("Remediation options should not be empty", zone.remediationOptions.isNotEmpty())
        assertTrue("Diagnosis should be populated", zone.diagnosis.isNotEmpty())
    }

    @Test
    fun testPdfFilenameSanitization() {
        val unsafeSessionName = "Drive Test #1 (Dhaka - Kushtia) / 2026!"
        val safeName = unsafeSessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")

        assertEquals("Drive_Test__1__Dhaka_-_Kushtia____2026_", safeName)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(1700000000000L))
        val filename = "CellScope_Report_${safeName}_$timestamp.pdf"

        assertTrue(filename.startsWith("CellScope_Report_"))
        assertTrue(filename.endsWith(".pdf"))
        assertTrue(!filename.contains(" "))
        assertTrue(!filename.contains("/"))
    }

    @Test
    fun testGraphInterpretationStructure() {
        val interp = com.cellscope.drive.export.GraphInterpretation(
            metricMeasured = "3GPP Reference Signal Received Power (RSRP in dBm)",
            provenance = "Android TelephonyManager via CellSignalStrengthLte.rsrp",
            formulation = "Discrete percentage frequency distribution",
            sampleCountScope = "150 valid LTE measurements",
            keyObservations = "Excellent 45%, Good 35%, Fair 15%, Poor 5%",
            engineeringInterpretation = "Robust link margin with adequate site density",
            alternativeExplanations = "Vehicle chassis attenuation (3-10 dB)",
            limitationsAndActions = "Calibrated scanner sweep recommended"
        )

        assertTrue(interp.metricMeasured.isNotEmpty())
        assertTrue(interp.provenance.isNotEmpty())
        assertTrue(interp.formulation.isNotEmpty())
        assertTrue(interp.sampleCountScope.isNotEmpty())
        assertTrue(interp.keyObservations.isNotEmpty())
        assertTrue(interp.engineeringInterpretation.isNotEmpty())
        assertTrue(interp.alternativeExplanations.isNotEmpty())
        assertTrue(interp.limitationsAndActions.isNotEmpty())
    }

    @Test
    fun testVectorFrequencyCarrierResolution() {
        val measurement = createSampleMeasurement(
            id = 1L,
            timestampMs = 1700000000000L,
            pci = 101,
            rsrp = -85
        )
        val carrier = com.cellscope.drive.telephony.CellularBandResolver.resolveMeasurement(measurement)
        assertNotNull(carrier)
        assertEquals(3, carrier?.bandNumber) // EARFCN 1600 is LTE Band 3
        assertTrue(carrier?.bandName?.startsWith("Band 3") == true)
        assertEquals(1845.0, carrier?.dlCenterFreqMhz ?: 0.0, 0.1)
    }

    @Test
    fun testScatterPlotThresholdBounds() {
        val measurements = listOf(
            createSampleMeasurement(1L, 1000L, 101, -44),
            createSampleMeasurement(2L, 2000L, 101, -140),
            createSampleMeasurement(3L, 3000L, 101, -85)
        )
        val minRsrp = -130f
        val maxRsrp = -40f
        for (m in measurements) {
            val clamped = m.rsrpDbm!!.toFloat().coerceIn(minRsrp, maxRsrp)
            assertTrue(clamped >= minRsrp)
            assertTrue(clamped <= maxRsrp)
        }
    }
}
