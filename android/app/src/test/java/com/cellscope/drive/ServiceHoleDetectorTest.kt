package com.cellscope.drive

import com.cellscope.drive.analytics.ServiceHoleDetector
import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.data.entity.NetworkContinuityEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceHoleDetectorTest {

    private fun createSample(
        id: Long,
        timeMs: Long,
        rsrp: Int? = -85,
        serviceState: String = "IN_SERVICE",
        dataState: String = "CONNECTED",
        gen: String = "4G"
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:0$id.000Z",
            timestampMs = timeMs,
            latitude = 23.8100 + id * 0.0001,
            longitude = 90.4120 + id * 0.0001,
            altitudeM = 10.0,
            accuracyM = 3.0f,
            speedKmh = 25.0f,
            networkType = "LTE",
            operatorAlpha = "Grameenphone",
            operatorNumeric = "47001",
            servingPci = 100,
            tac = 18452,
            earfcn = 1600,
            bandwidthKhz = 20000,
            rsrpDbm = rsrp,
            rsrqDb = -11,
            rssiDbm = -60,
            cqi = 12,
            timingAdvance = 2,
            isServing = true,
            handoverTrigger = 0,
            networkGen = gen,
            serviceState = serviceState,
            dataActivityState = dataState,
            isGpsValid = true
        )
    }

    @Test
    fun testDetectRadioOutOfService() {
        val samples = listOf(
            createSample(1, 1000L, -85, serviceState = "IN_SERVICE"),
            createSample(2, 2000L, null, serviceState = "OUT_OF_SERVICE", gen = "OOS"),
            createSample(3, 4000L, null, serviceState = "OUT_OF_SERVICE", gen = "OOS"),
            createSample(4, 5000L, -88, serviceState = "IN_SERVICE") // recovered
        )

        val summary = ServiceHoleDetector.detectHoles(1L, samples)
        assertEquals(1, summary.radioOutagesCount)

        val oosHole = summary.holes.find { it.holeType == "RADIO_OUT_OF_SERVICE" }
        org.junit.Assert.assertNotNull(oosHole)
        assertEquals("CRITICAL", oosHole!!.severity)
        assertEquals(3000L, oosHole.durationMs)
        assertEquals("RECOVERED", oosHole.resolutionStatus)
    }

    @Test
    fun testDetectCellularDataDisconnected() {
        val samples = listOf(
            createSample(1, 1000L, -85, dataState = "CONNECTED"),
            createSample(2, 2000L, -85, dataState = "DISCONNECTED"),
            createSample(3, 4000L, -85, dataState = "DISCONNECTED"),
            createSample(4, 5000L, -85, dataState = "CONNECTED") // reconnected
        )

        val summary = ServiceHoleDetector.detectHoles(1L, samples)
        assertEquals(1, summary.dataDisconnectionsCount)

        val dataHole = summary.holes.find { it.holeType == "CELLULAR_DATA_DISCONNECTED" }
        org.junit.Assert.assertNotNull(dataHole)
        assertEquals("HIGH", dataHole!!.severity)
        assertEquals(3000L, dataHole.durationMs)
        assertEquals("RECOVERED", dataHole.resolutionStatus)
    }

    @Test
    fun testDetectWeakRadioSignal() {
        val samples = listOf(
            createSample(1, 1000L, -85),
            createSample(2, 2000L, -118), // below -115 dBm
            createSample(3, 3000L, -120),
            createSample(4, 4000L, -85)   // recovered
        )

        val summary = ServiceHoleDetector.detectHoles(1L, samples)
        assertEquals(1, summary.weakSignalHolesCount)

        val weakHole = summary.holes.find { it.holeType == "WEAK_RADIO_SIGNAL" }
        org.junit.Assert.assertNotNull(weakHole)
        assertEquals("MEDIUM", weakHole!!.severity)
        assertTrue(weakHole.engineeringEvidence.contains("below -115 dBm"))
    }

    @Test
    fun testDetectTelemetryGap() {
        val samples = listOf(
            createSample(1, 1000L),
            createSample(2, 16000L) // 15s gap (> 10s)
        )

        val summary = ServiceHoleDetector.detectHoles(1L, samples)
        assertEquals(1, summary.telemetryGapsCount)

        val gapHole = summary.holes.find { it.holeType == "TELEMETRY_GAP" }
        org.junit.Assert.assertNotNull(gapHole)
        assertEquals(15000L, gapHole!!.durationMs)
    }

    @Test
    fun testDetectInternetTestFailuresAndPoorPerformance() {
        val tests = listOf(
            ActiveQoeTestEntity(
                sessionId = 1L,
                timestampIso = "2026-10-18T10:00:00.000Z",
                timestampMs = 1000L,
                latitude = 23.81,
                longitude = 90.41,
                testType = "PING",
                latencyMs = null,
                downloadSpeedMbps = null,
                uploadSpeedMbps = null,
                bytesTransferred = 0,
                durationMs = 5000,
                success = false,
                errorMessage = "Host unreachable"
            ),
            ActiveQoeTestEntity(
                sessionId = 1L,
                timestampIso = "2026-10-18T10:00:10.000Z",
                timestampMs = 10000L,
                latitude = 23.81,
                longitude = 90.41,
                testType = "DOWNLOAD",
                latencyMs = 50,
                downloadSpeedMbps = 0.45, // < 1.0 Mbps
                uploadSpeedMbps = null,
                bytesTransferred = 500000,
                durationMs = 5000,
                success = true,
                errorMessage = null
            )
        )

        val summary = ServiceHoleDetector.detectHoles(1L, emptyList(), activeTests = tests)
        assertEquals(1, summary.testFailuresCount)
        assertEquals(1, summary.poorPerformanceCount)

        val failureHole = summary.holes.find { it.holeType == "INTERNET_TEST_FAILURE" }
        val poorHole = summary.holes.find { it.holeType == "POOR_INTERNET_PERFORMANCE" }

        org.junit.Assert.assertNotNull(failureHole)
        org.junit.Assert.assertNotNull(poorHole)
        assertEquals("CRITICAL", failureHole!!.severity)
        assertEquals("MEDIUM", poorHole!!.severity)
    }

    @Test
    fun testDetectGenerationDowngrades() {
        val continuityEvents = listOf(
            NetworkContinuityEventEntity(
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
                toSignalDbm = -90,
                durationSeconds = 120,
                dataConnectivityState = "CONNECTED",
                evidenceClassification = "Network generation change — cause unconfirmed",
                details = "5G NR to 4G LTE shift"
            )
        )

        val summary = ServiceHoleDetector.detectHoles(1L, emptyList(), continuityEvents = continuityEvents)
        assertEquals(1, summary.downgradesCount)

        val dgHole = summary.holes.find { it.holeType == "GENERATION_DOWNGRADE" }
        org.junit.Assert.assertNotNull(dgHole)
        assertEquals("5G", dgHole!!.networkGen?.let { "5G" })
    }
}
