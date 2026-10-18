package com.cellscope.drive

import com.cellscope.drive.analytics.anomaly.AnomalySeverity
import com.cellscope.drive.analytics.anomaly.AnomalyType
import com.cellscope.drive.analytics.anomaly.ForestAnomalyScorer
import com.cellscope.drive.analytics.anomaly.NetworkAnomalyDetector
import com.cellscope.drive.analytics.anomaly.RuleBasedAnomalyEngine
import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnomalyDetectorTest {

    private fun createSample(
        id: Long = 1,
        secOffset: Long = 0,
        rsrp: Int = -85,
        rsrq: Int = -10,
        sinr: Double = 15.0,
        speed: Float = 35.0f,
        pci: Int = 100,
        neighbors: String? = null
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-17T12:00:${secOffset.toString().padStart(2, '0')}Z",
            timestampMs = 1700000000000L + (secOffset * 1000L),
            latitude = 23.8103 + (secOffset * 0.0001),
            longitude = 90.4125 + (secOffset * 0.0001),
            altitudeM = 15.0,
            accuracyM = 4.0f,
            speedKmh = speed,
            networkType = "LTE",
            operatorAlpha = "Grameenphone",
            operatorNumeric = "47001",
            servingPci = pci,
            tac = 1000,
            earfcn = 1650,
            bandwidthKhz = 20000,
            rsrpDbm = rsrp,
            rsrqDb = rsrq,
            rssiDbm = -65,
            cqi = 12,
            timingAdvance = 2,
            isServing = true,
            sinrDb = sinr,
            neighborCellsJson = neighbors,
            isGpsValid = true
        )
    }

    @Test
    fun testNormalDriveProducesZeroAnomalies() {
        val normalSamples = (0 until 30).map { i ->
            createSample(id = i.toLong(), secOffset = i.toLong(), rsrp = -85 + (i % 3), sinr = 14.0)
        }

        val report = NetworkAnomalyDetector.analyzeSession(
            sessionId = 1L,
            measurements = normalSamples
        )

        assertEquals(0, report.criticalCount)
        assertEquals(0, report.warningCount)
        assertEquals(0, report.totalAnomalies)
        assertTrue(report.executiveSummary.contains("Clean Drive Route"))
    }

    @Test
    fun testAbruptSignalDropDetection() {
        val samples = mutableListOf<LteMeasurementEntity>()
        // Normal baseline
        for (i in 0..5) {
            samples.add(createSample(id = i.toLong(), secOffset = i.toLong(), rsrp = -82))
        }
        // Sudden drop at sample 6 (> 20 dB drop in window)
        samples.add(createSample(id = 6, secOffset = 6, rsrp = -106))
        samples.add(createSample(id = 7, secOffset = 7, rsrp = -108))

        val anomalies = RuleBasedAnomalyEngine.detectAnomalies(sessionId = 1L, measurements = samples)
        val dropAnomaly = anomalies.firstOrNull { it.anomalyType == AnomalyType.ABRUPT_SIGNAL_DROP }

        assertNotNull(dropAnomaly)
        assertEquals(AnomalyType.ABRUPT_SIGNAL_DROP, dropAnomaly?.anomalyType)
        assertTrue(dropAnomaly?.candidateHypothesis?.contains("Candidate") == true)
        assertTrue(dropAnomaly?.detectionReason?.contains("collapsed") == true)
    }

    @Test
    fun testSustainedWeakCorridorDetection() {
        val samples = mutableListOf<LteMeasurementEntity>()
        // 18 consecutive weak samples (< -115 dBm)
        for (i in 0..17) {
            samples.add(createSample(id = i.toLong(), secOffset = i.toLong(), rsrp = -118, speed = 40.0f))
        }

        val anomalies = RuleBasedAnomalyEngine.detectAnomalies(sessionId = 1L, measurements = samples)
        val corridor = anomalies.firstOrNull { it.anomalyType == AnomalyType.SUSTAINED_WEAK_CORRIDOR }

        assertNotNull(corridor)
        assertEquals(AnomalyType.SUSTAINED_WEAK_CORRIDOR, corridor?.anomalyType)
        assertTrue(corridor?.candidateHypothesis?.contains("coverage hole") == true)
    }

    @Test
    fun testTelemetryGapDetection() {
        val samples = listOf(
            createSample(id = 1, secOffset = 0),
            createSample(id = 2, secOffset = 1),
            createSample(id = 3, secOffset = 16) // 15 second gap
        )

        val anomalies = RuleBasedAnomalyEngine.detectAnomalies(sessionId = 1L, measurements = samples)
        val gap = anomalies.firstOrNull { it.anomalyType == AnomalyType.TELEMETRY_OR_GPS_GAP }

        assertNotNull(gap)
        assertEquals(AnomalyType.TELEMETRY_OR_GPS_GAP, gap?.anomalyType)
        assertNotNull(gap?.dataQualityWarning)
    }

    @Test
    fun testPilotPollutionDetection() {
        // Serving RSRP -90 dBm, but 3 neighbors within 5 dB and SINR -5.0 dB
        val neighbors = "101:-91:-14:1650;102:-92:-15:1650;103:-93:-15:1650"
        val sample = createSample(id = 1, secOffset = 0, rsrp = -90, sinr = -5.0, rsrq = -17, neighbors = neighbors)

        val anomalies = RuleBasedAnomalyEngine.detectAnomalies(sessionId = 1L, measurements = listOf(sample))
        val pollution = anomalies.firstOrNull { it.anomalyType == AnomalyType.PILOT_POLLUTION }

        assertNotNull(pollution)
        assertEquals(AnomalyType.PILOT_POLLUTION, pollution?.anomalyType)
        assertTrue(pollution?.detectionReason?.contains("neighbor cells observed within 5 dB") == true)
    }

    @Test
    fun testPingPongMobilityDetection() {
        val sampleA = createSample(id = 1, secOffset = 0, pci = 100)
        val sampleB = createSample(id = 2, secOffset = 5, pci = 101)
        val sampleC = createSample(id = 3, secOffset = 10, pci = 100)

        val transitions = listOf(
            CellTransitionEntity(
                sessionId = 1L,
                timestampIso = "2026-10-17T12:00:05Z",
                timestampMs = 1700000005000L,
                latitude = 23.8105,
                longitude = 90.4127,
                fromPci = 100,
                toPci = 101,
                fromRsrp = -85,
                toRsrp = -88,
                eventType = "OBSERVED_TRANSITION",
                confidenceScore = 0.8f,
                details = "Transition from 100 to 101"
            ),
            CellTransitionEntity(
                sessionId = 1L,
                timestampIso = "2026-10-17T12:00:10Z",
                timestampMs = 1700000010000L,
                latitude = 23.8110,
                longitude = 90.4130,
                fromPci = 101,
                toPci = 100, // Returns to 100 within 5 seconds
                fromRsrp = -88,
                toRsrp = -84,
                eventType = "OBSERVED_TRANSITION",
                confidenceScore = 0.8f,
                details = "Transition from 101 to 100"
            )
        )

        val anomalies = RuleBasedAnomalyEngine.detectAnomalies(
            sessionId = 1L,
            measurements = listOf(sampleA, sampleB, sampleC),
            transitions = transitions
        )
        val pingPong = anomalies.firstOrNull { it.anomalyType == AnomalyType.PING_PONG_MOBILITY }

        assertNotNull(pingPong)
        assertEquals(AnomalyType.PING_PONG_MOBILITY, pingPong?.anomalyType)
        assertTrue(pingPong?.title?.contains("Handover Ping-Pong") == true)
    }

    @Test
    fun testActiveThroughputCollapseDetection() {
        val rfSample = createSample(id = 1, secOffset = 5, rsrp = -80, sinr = 18.0) // Great RF
        val qoeTest = ActiveQoeTestEntity(
            sessionId = 1L,
            testType = "DOWNLOAD",
            success = true,
            timestampMs = rfSample.timestampMs,
            durationMs = 5000L,
            downloadSpeedMbps = 0.15, // Severely collapsed
            bytesTransferred = 95000L,
            timestampIso = "2026-10-17T12:00:05Z"
        )

        val anomalies = RuleBasedAnomalyEngine.detectAnomalies(
            sessionId = 1L,
            measurements = listOf(rfSample),
            qoeTests = listOf(qoeTest)
        )
        val collapse = anomalies.firstOrNull { it.anomalyType == AnomalyType.ACTIVE_THROUGHPUT_COLLAPSE }

        assertNotNull(collapse)
        assertEquals(AnomalySeverity.CRITICAL, collapse?.severity)
        assertTrue(collapse?.candidateHypothesis?.contains("backhaul transport bottleneck") == true)
    }

    @Test
    fun testForestAnomalyScorerBoundsAndSensitivity() {
        val scorer = ForestAnomalyScorer()

        // Normal sample
        val normalScore = scorer.score(
            rsrpDbm = -85.0,
            rsrqDb = -10.0,
            sinrDb = 15.0,
            speedKmh = 35.0,
            deltaRsrp = 0.0,
            sampleDt = 1.0
        )

        // Severe outlier sample
        val anomalyScore = scorer.score(
            rsrpDbm = -125.0,
            rsrqDb = -18.0,
            sinrDb = -8.0,
            speedKmh = 80.0,
            deltaRsrp = -25.0,
            sampleDt = 15.0
        )

        assertTrue(normalScore.anomalyScore in 0.0..1.0)
        assertTrue(anomalyScore.anomalyScore in 0.0..1.0)
        assertTrue(
            "Anomaly score (${anomalyScore.anomalyScore}) must exceed normal score (${normalScore.anomalyScore})",
            anomalyScore.anomalyScore > normalScore.anomalyScore
        )
        assertNotNull(anomalyScore.primaryContributingFeature)
    }
}
