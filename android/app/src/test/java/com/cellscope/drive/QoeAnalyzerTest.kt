package com.cellscope.drive

import com.cellscope.drive.analytics.qoe.QoeAnalyzer
import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QoeAnalyzerTest {

    private val baseSession = DriveSession(
        id = 1L,
        sessionName = "QoE_Test_Session",
        startTimeMs = 1700000000000L,
        endTimeMs = 1700000060000L,
        totalDistanceM = 1250.0
    )

    private fun createMeasurement(
        id: Long,
        lat: Double? = 23.8103,
        lon: Double? = 90.4125,
        isGpsValid: Boolean = true,
        rsrp: Int? = -95,
        rsrq: Int? = -12,
        sinr: Double? = 8.5,
        pci: Int? = 101
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-17T12:00:00.000Z",
            timestampMs = 1700000000000L + id * 1000L,
            latitude = lat,
            longitude = lon,
            altitudeM = 15.0,
            accuracyM = 5.0f,
            speedKmh = 35.0f,
            networkType = "LTE",
            operatorAlpha = "Grameenphone",
            operatorNumeric = "47001",
            servingPci = pci,
            tac = 20500,
            earfcn = 1399,
            bandwidthKhz = 20000,
            rsrpDbm = rsrp,
            rsrqDb = rsrq,
            rssiDbm = -65,
            cqi = 12,
            timingAdvance = 4,
            isServing = true,
            handoverTrigger = 0,
            sinrDb = sinr,
            isGpsValid = isGpsValid
        )
    }

    @Test
    fun testEmptySessionHandling() {
        val report = QoeAnalyzer.analyzeSessionQoe(baseSession, emptyList(), emptyList())
        assertEquals(0, report.totalSamples)
        assertEquals(0, report.validGpsSamples)
        assertEquals(0.0, report.gpsAvailabilityPct, 0.01)
        assertEquals(0.0, report.radioAvailabilityPct, 0.01)
        assertNull(report.meanRsrpDbm)
        assertNull(report.medianRsrpDbm)
        assertNull(report.activeQoeSummary)
    }

    @Test
    fun testGpsAndRadioAvailabilityRatios() {
        val measurements = listOf(
            createMeasurement(1, lat = 23.81, lon = 90.41, isGpsValid = true, rsrp = -90, pci = 101),
            createMeasurement(2, lat = 23.82, lon = 90.42, isGpsValid = true, rsrp = -92, pci = 101),
            createMeasurement(3, lat = null, lon = null, isGpsValid = false, rsrp = -95, pci = 101), // Missing GPS
            createMeasurement(4, lat = 23.84, lon = 90.44, isGpsValid = false, rsrp = null, pci = null) // Invalid GPS & Missing Radio
        )

        val report = QoeAnalyzer.analyzeSessionQoe(baseSession, measurements)

        assertEquals(4, report.totalSamples)
        assertEquals(2, report.validGpsSamples)
        assertEquals(50.0, report.gpsAvailabilityPct, 0.01) // 2 / 4 = 50.0%

        assertEquals(3, report.validRadioSamples)
        assertEquals(75.0, report.radioAvailabilityPct, 0.01) // 3 / 4 = 75.0%
    }

    @Test
    fun testSignalMeanAndMedianCalculation() {
        val measurements = listOf(
            createMeasurement(1, rsrp = -80, rsrq = -8, sinr = 15.0),
            createMeasurement(2, rsrp = -90, rsrq = -12, sinr = 10.0),
            createMeasurement(3, rsrp = -100, rsrq = -16, sinr = 5.0)
        )

        val report = QoeAnalyzer.analyzeSessionQoe(baseSession, measurements)

        assertEquals(-90.0, report.meanRsrpDbm!!, 0.01)
        assertEquals(-90.0, report.medianRsrpDbm!!, 0.01)

        assertEquals(-12.0, report.meanRsrqDb!!, 0.01)
        assertEquals(-12.0, report.medianRsrqDb!!, 0.01)

        assertEquals(10.0, report.meanSinrDb!!, 0.01)
        assertEquals(10.0, report.medianSinrDb!!, 0.01)
    }

    @Test
    fun testCoverageThresholdKpis() {
        val measurements = listOf(
            createMeasurement(1, rsrp = -85, rsrq = -10, sinr = 12.0),  // Good
            createMeasurement(2, rsrp = -100, rsrq = -14, sinr = 4.0),  // Adequate
            createMeasurement(3, rsrp = -108, rsrq = -18, sinr = -2.0), // Weak
            createMeasurement(4, rsrp = -120, rsrq = -20, sinr = -5.0)  // Deep hole
        )

        val report = QoeAnalyzer.analyzeSessionQoe(baseSession, measurements)

        // Adequate (>= -105): -85, -100 -> 2 out of 4 = 50%
        assertEquals(50.0, report.coverageAdequacyPct, 0.01)

        // Deep hole (< -115): -120 -> 1 out of 4 = 25%
        assertEquals(25.0, report.deepCoverageHolePct, 0.01)

        // Poor quality (RSRQ < -15 or SINR < 0): sample 3 (-18, -2) and sample 4 (-20, -5) -> 2 out of 4 = 50%
        assertEquals(50.0, report.poorQualityRatioPct, 0.01)
    }

    @Test
    fun testActiveQoeSummaryAggregation() {
        val activeTests = listOf(
            ActiveQoeTestEntity(
                id = 1L,
                sessionId = 1L,
                timestampIso = "2026-10-17T12:00:00.000Z",
                timestampMs = 1700000000000L,
                testType = "PING",
                latencyMs = 45L,
                success = true
            ),
            ActiveQoeTestEntity(
                id = 2L,
                sessionId = 1L,
                timestampIso = "2026-10-17T12:00:05.000Z",
                timestampMs = 1700000005000L,
                testType = "DOWNLOAD",
                downloadSpeedMbps = 32.5,
                bytesTransferred = 524288L,
                durationMs = 129L,
                success = true
            ),
            ActiveQoeTestEntity(
                id = 3L,
                sessionId = 1L,
                timestampIso = "2026-10-17T12:00:10.000Z",
                timestampMs = 1700000010000L,
                testType = "UPLOAD",
                uploadSpeedMbps = 12.8,
                bytesTransferred = 131072L,
                durationMs = 82L,
                success = true
            ),
            ActiveQoeTestEntity(
                id = 4L,
                sessionId = 1L,
                timestampIso = "2026-10-17T12:00:15.000Z",
                timestampMs = 1700000015000L,
                testType = "DOWNLOAD",
                success = false,
                errorMessage = "Connection timed out"
            )
        )

        val report = QoeAnalyzer.analyzeSessionQoe(baseSession, listOf(createMeasurement(1)), activeTests)
        val active = report.activeQoeSummary

        assertNotNull(active)
        assertEquals(4, active!!.totalTestsAttempted)
        assertEquals(3, active.successfulTests)
        assertEquals(75.0, active.testCompletionRatePct, 0.01)
        assertEquals(25.0, active.failureRatePct, 0.01)
        assertEquals(45.0, active.avgLatencyMs!!, 0.01)
        assertEquals(32.5, active.avgDownloadThroughputMbps!!, 0.01)
        assertEquals(12.8, active.avgUploadThroughputMbps!!, 0.01)
    }
}
