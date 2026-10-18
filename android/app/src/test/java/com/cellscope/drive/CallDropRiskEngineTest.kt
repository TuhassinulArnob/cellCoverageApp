package com.cellscope.drive

import com.cellscope.drive.analytics.CallDropRiskEngine
import com.cellscope.drive.data.entity.CallReliabilityEventEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.data.entity.NetworkContinuityEventEntity
import com.cellscope.drive.data.entity.ServiceHoleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallDropRiskEngineTest {

    private fun createSample(
        id: Long,
        rsrp: Int?,
        serviceState: String = "IN_SERVICE"
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:0$id.000Z",
            timestampMs = 1000L * id,
            latitude = 23.81,
            longitude = 90.41,
            altitudeM = 10.0,
            accuracyM = 3.0f,
            speedKmh = 10.0f,
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
            networkGen = "4G",
            serviceState = serviceState,
            dataActivityState = "CONNECTED",
            isGpsValid = true
        )
    }

    @Test
    fun testNormalCallStart() {
        val sample = createSample(1, -85)
        val event = CallDropRiskEngine.classifyCallEvent(
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:01.000Z",
            timestampMs = 1000L,
            previousCallState = "IDLE",
            newCallState = "OFFHOOK",
            currentMeasurement = sample
        )

        assertEquals("NORMAL_CALL_START", event.classification)
        assertEquals("LOW", event.riskScoreCategory)
        assertEquals("OFFHOOK", event.callState)
    }

    @Test
    fun testNormalCallEndHealthySignal() {
        val sample = createSample(1, -82)
        val event = CallDropRiskEngine.classifyCallEvent(
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:01.000Z",
            timestampMs = 1000L,
            previousCallState = "OFFHOOK",
            newCallState = "IDLE",
            currentMeasurement = sample
        )

        assertEquals("NORMAL_CALL_END", event.classification)
        assertEquals("LOW", event.riskScoreCategory)
    }

    @Test
    fun testSuspectedCallDropSevereSignalDegradation() {
        val sample = createSample(1, -122) // < -118 dBm
        val event = CallDropRiskEngine.classifyCallEvent(
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:01.000Z",
            timestampMs = 1000L,
            previousCallState = "OFFHOOK",
            newCallState = "IDLE",
            currentMeasurement = sample
        )

        assertEquals("SUSPECTED_CALL_DROP", event.classification)
        assertEquals("HIGH", event.riskScoreCategory)
        assertTrue(event.evidenceFactors.contains("severe radio degradation"))
    }

    @Test
    fun testTerminationWithServiceInterruption() {
        val sample = createSample(1, null, serviceState = "OUT_OF_SERVICE")
        val hole = ServiceHoleEntity(
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:01.000Z",
            timestampMs = 1000L,
            latitude = 23.81,
            longitude = 90.41,
            accuracyM = 3.0f,
            holeType = "RADIO_OUT_OF_SERVICE",
            severity = "CRITICAL",
            engineeringEvidence = "Out of service"
        )

        val event = CallDropRiskEngine.classifyCallEvent(
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:01.000Z",
            timestampMs = 1000L,
            previousCallState = "OFFHOOK",
            newCallState = "IDLE",
            currentMeasurement = sample,
            nearbyServiceHoles = listOf(hole)
        )

        assertEquals("TERMINATION_WITH_SERVICE_INTERRUPTION", event.classification)
        assertEquals("SEVERE", event.riskScoreCategory)
    }

    @Test
    fun testEvaluateSessionRiskInsufficientEvidence() {
        val samples = listOf(createSample(1, -85), createSample(2, -86))
        val score = CallDropRiskEngine.evaluateSessionRisk(samples, emptyList(), emptyList(), emptyList())
        assertEquals("INSUFFICIENT_EVIDENCE", score.category)
    }

    @Test
    fun testEvaluateSessionRiskLow() {
        val samples = (1..10).map { createSample(it.toLong(), -80) }
        val score = CallDropRiskEngine.evaluateSessionRisk(samples, emptyList(), emptyList(), emptyList())
        assertEquals("LOW", score.category)
        assertEquals(0.0, score.scoreValue, 0.1)
    }

    @Test
    fun testEvaluateSessionRiskSevereWithDropsAndOutages() {
        val samples = (1..10).map { createSample(it.toLong(), -120) } // all < -115
        val oosHole = ServiceHoleEntity(
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:01.000Z",
            timestampMs = 1000L,
            latitude = 23.81,
            longitude = 90.41,
            accuracyM = 3f,
            holeType = "RADIO_OUT_OF_SERVICE",
            severity = "CRITICAL",
            durationMs = 5000L,
            engineeringEvidence = "Outage"
        )
        val dropEvent = CallReliabilityEventEntity(
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:01.000Z",
            timestampMs = 1000L,
            latitude = 23.81,
            longitude = 90.41,
            accuracyM = 3f,
            callState = "IDLE",
            classification = "SUSPECTED_CALL_DROP",
            previousState = "OFFHOOK",
            networkGeneration = "4G",
            detailedTech = "4G LTE",
            rsrpDbm = -120,
            serviceState = "IN_SERVICE",
            riskScoreCategory = "HIGH",
            evidenceFactors = "Severe degradation"
        )

        val score = CallDropRiskEngine.evaluateSessionRisk(
            measurements = samples,
            continuityEvents = emptyList(),
            serviceHoles = listOf(oosHole),
            callEvents = listOf(dropEvent)
        )

        assertEquals("SEVERE", score.category)
        assertTrue(score.scoreValue >= 65.0)
    }
}
