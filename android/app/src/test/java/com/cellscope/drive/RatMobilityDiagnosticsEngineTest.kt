package com.cellscope.drive

import com.cellscope.drive.analytics.RatMobilityDiagnosticsEngine
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.model.NetworkGeneration
import org.junit.Assert.*
import org.junit.Test

class RatMobilityDiagnosticsEngineTest {

    private fun createSample(
        id: Long,
        timestampMs: Long,
        networkType: String,
        servingRat: String,
        rsrp: Int?,
        rsrq: Int? = null,
        sinr: Double? = null,
        speed: Float = 20f,
        neighborsRaw: String? = "101:-95:-12:1600",
        dataActivity: String? = null
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:00.000Z",
            timestampMs = timestampMs,
            latitude = 23.81,
            longitude = 90.41,
            altitudeM = 10.0,
            accuracyM = 3.0f,
            speedKmh = speed,
            networkType = networkType,
            operatorAlpha = "Grameenphone",
            operatorNumeric = "47001",
            servingPci = 288,
            tac = 18452,
            earfcn = 1600,
            bandwidthKhz = 20000,
            rsrpDbm = rsrp,
            rsrqDb = rsrq,
            rssiDbm = -60,
            sinrDb = sinr,
            cqi = 12,
            timingAdvance = 1,
            isServing = true,
            neighborCellsJson = neighborsRaw,
            handoverTrigger = 0,
            servingRat = servingRat,
            dataActivityState = dataActivity
        )
    }

    @Test
    fun testWeakCoverageBoundaryDowngrade() {
        val s1 = createSample(1, 1000L, "LTE", "4G", rsrp = -118, rsrq = -15)
        val s2 = createSample(2, 2000L, "3G_WCDMA", "3G", rsrp = null) // Downgraded to 3G

        val summary = RatMobilityDiagnosticsEngine.analyzeRatMobility(listOf(s1, s2))
        assertEquals(1, summary.totalTransitions)
        assertEquals(1, summary.lteTo3gDowngrades)

        val event = summary.events[0]
        assertEquals(RatMobilityDiagnosticsEngine.DowngradeRootCause.WEAK_COVERAGE_BOUNDARY, event.rootCause)
        assertTrue(event.confidenceScore >= 0.9)
        assertTrue(event.diagnosticEvidence.contains("-118.0 dBm"))
    }

    @Test
    fun testDeterioratingRadioQualityDowngrade() {
        // Adequate RSRP (-98 dBm) but collapsed SINR (-3.0 dB)
        val s1 = createSample(1, 1000L, "LTE", "4G", rsrp = -98, rsrq = -17, sinr = -3.0)
        val s2 = createSample(2, 2000L, "3G_WCDMA", "3G", rsrp = null)

        val summary = RatMobilityDiagnosticsEngine.analyzeRatMobility(listOf(s1, s2))
        val event = summary.events[0]
        assertEquals(RatMobilityDiagnosticsEngine.DowngradeRootCause.DETERIORATING_RADIO_QUALITY, event.rootCause)
        assertTrue(event.diagnosticEvidence.contains("SINR -3.0 dB"))
    }

    @Test
    fun testFastPingPongBounce() {
        // 4G -> 3G for 4 seconds, then back to 4G
        val s1 = createSample(1, 1000L, "LTE", "4G", rsrp = -95, rsrq = -10)
        val s2 = createSample(2, 2000L, "3G_WCDMA", "3G", rsrp = null)
        val s3 = createSample(3, 6000L, "LTE", "4G", rsrp = -95, rsrq = -10)

        val summary = RatMobilityDiagnosticsEngine.analyzeRatMobility(listOf(s1, s2, s3))
        assertEquals(2, summary.totalTransitions)
        assertEquals(1, summary.fastPingPongCount)

        val firstDowngrade = summary.events[0]
        assertTrue(firstDowngrade.isPingPong)
        assertEquals(RatMobilityDiagnosticsEngine.DowngradeRootCause.FAST_PING_PONG_BOUNCE, firstDowngrade.rootCause)
        assertEquals(4000L, firstDowngrade.pingPongReturnDurationMs)
    }

    @Test
    fun testCsfbIndicationDowngrade() {
        val s1 = createSample(1, 1000L, "LTE", "4G", rsrp = -90, rsrq = -9, dataActivity = "DATA_ACTIVITY_INOUT")
        val s2 = createSample(2, 2000L, "2G_GSM", "2G", rsrp = null, dataActivity = "CALL_ACTIVE")

        val summary = RatMobilityDiagnosticsEngine.analyzeRatMobility(listOf(s1, s2))
        val event = summary.events[0]
        assertEquals(RatMobilityDiagnosticsEngine.DowngradeRootCause.CIRCUIT_SWITCHED_FALLBACK, event.rootCause)
    }

    @Test
    fun testTelemetryGapProducesInconclusiveDiagnosis() {
        // Gap of 25 seconds between samples
        val s1 = createSample(1, 1000L, "LTE", "4G", rsrp = -90, rsrq = -9)
        val s2 = createSample(2, 26000L, "3G_WCDMA", "3G", rsrp = null)

        val summary = RatMobilityDiagnosticsEngine.analyzeRatMobility(listOf(s1, s2))
        val event = summary.events[0]
        assertEquals(RatMobilityDiagnosticsEngine.DowngradeRootCause.INCONCLUSIVE_TELEMETRY_WINDOW, event.rootCause)
        assertTrue(event.diagnosticEvidence.contains("Telemetry gap of 25s"))
    }
}
