package com.cellscope.drive

import com.cellscope.drive.analytics.NetworkContinuityAnalyzer
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.model.NetworkGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkContinuityAnalyzerTest {

    private fun createSample(
        id: Long,
        timeMs: Long,
        gen: String,
        tech: String,
        rsrp: Int? = -85,
        lat: Double = 23.8100,
        lon: Double = 90.4120,
        serviceState: String = "IN_SERVICE",
        dataState: String = "CONNECTED"
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:0$id.000Z",
            timestampMs = timeMs,
            latitude = lat,
            longitude = lon,
            altitudeM = 12.0,
            accuracyM = 3.0f,
            speedKmh = 20.0f,
            networkType = tech,
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
            dataNetworkType = tech,
            voiceNetworkType = "4G LTE",
            serviceState = serviceState,
            dataActivityState = dataState,
            isGpsValid = true
        )
    }

    @Test
    fun testStableGenerationNoTransitions() {
        val samples = listOf(
            createSample(1, 1000L, "4G", "LTE", -80),
            createSample(2, 2000L, "4G", "LTE", -82),
            createSample(3, 3000L, "4G", "LTE", -81)
        )

        val report = NetworkContinuityAnalyzer.analyzeSessionContinuity(1L, samples)
        assertEquals(0, report.totalTransitions)
        assertEquals(0, report.totalDowngrades)
        assertEquals(1, report.generationDwellStats.size)
        val dwell4g = report.generationDwellStats[NetworkGeneration.GEN_4G]
        assertNotNull(dwell4g)
        assertEquals(100.0, dwell4g!!.percentOfSamples, 0.1)
    }

    @Test
    fun test5gTo4gDowngradeDetection() {
        val samples = listOf(
            createSample(1, 1000L, "5G", "5G NR", -82),
            createSample(2, 2000L, "5G", "5G NR", -85),
            createSample(3, 3000L, "4G", "LTE", -88) // 5G -> 4G transition
        )

        val report = NetworkContinuityAnalyzer.analyzeSessionContinuity(1L, samples)
        assertEquals(1, report.totalTransitions)
        assertEquals(1, report.totalDowngrades)

        val event = report.continuityEvents[0]
        assertEquals("5G", event.fromGeneration)
        assertEquals("4G", event.toGeneration)
        assertEquals("DOWNGRADE", event.transitionCategory)
        // From signal was -85 dBm (strong), so cause unconfirmed
        assertEquals("Network generation change — cause unconfirmed", event.evidenceClassification)
    }

    @Test
    fun testWeakSignalCoverageHoleDowngrade() {
        val samples = listOf(
            createSample(1, 1000L, "4G", "LTE", -118), // Very weak RSRP
            createSample(2, 2000L, "2G", "EDGE", -95)   // Fallback to 2G
        )

        val report = NetworkContinuityAnalyzer.analyzeSessionContinuity(1L, samples)
        assertEquals(1, report.totalTransitions)
        assertEquals(1, report.totalDowngrades)

        val event = report.continuityEvents[0]
        assertEquals("4G", event.fromGeneration)
        assertEquals("2G", event.toGeneration)
        assertEquals("DOWNGRADE", event.transitionCategory)
        assertEquals("COVERAGE_HOLE_DOWNGRADE", event.evidenceClassification)
        assertTrue(event.details.contains("severe signal degradation"))
    }

    @Test
    fun testRecoveryToHigherGeneration() {
        val samples = listOf(
            createSample(1, 1000L, "2G", "EDGE", -90),
            createSample(2, 2000L, "4G", "LTE", -84)
        )

        val report = NetworkContinuityAnalyzer.analyzeSessionContinuity(1L, samples)
        assertEquals(1, report.totalTransitions)
        assertEquals(0, report.totalDowngrades)

        val event = report.continuityEvents[0]
        assertEquals("2G", event.fromGeneration)
        assertEquals("4G", event.toGeneration)
        assertEquals("UPGRADE", event.transitionCategory)
    }

    @Test
    fun testOutOfServiceTransition() {
        val samples = listOf(
            createSample(1, 1000L, "4G", "LTE", -90),
            createSample(2, 2000L, "OOS", "UNKNOWN", null, serviceState = "OUT_OF_SERVICE")
        )

        val report = NetworkContinuityAnalyzer.analyzeSessionContinuity(1L, samples)
        assertEquals(1, report.totalTransitions)
        assertEquals(1, report.totalOutages)

        val event = report.continuityEvents[0]
        assertEquals("4G", event.fromGeneration)
        assertEquals("OOS", event.toGeneration)
        assertEquals("OUT_OF_SERVICE", event.transitionCategory)
    }

    @Test
    fun testTelemetryGapDetection() {
        val samples = listOf(
            createSample(1, 1000L, "4G", "LTE", -80),
            createSample(2, 16000L, "4G", "LTE", -82) // 15s gap (> 10s)
        )

        val report = NetworkContinuityAnalyzer.analyzeSessionContinuity(1L, samples)
        assertEquals(1, report.telemetryGapsCount)
        assertEquals(15L, report.totalGapDurationSeconds)
    }
}
