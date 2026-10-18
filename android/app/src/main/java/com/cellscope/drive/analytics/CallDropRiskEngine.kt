package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.CallReliabilityEventEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.data.entity.NetworkContinuityEventEntity
import com.cellscope.drive.data.entity.ServiceHoleEntity
import java.util.Locale

data class CallDropRiskScore(
    val category: String, // "LOW", "MODERATE", "HIGH", "SEVERE", "INSUFFICIENT_EVIDENCE"
    val scoreValue: Double, // 0.0 - 100.0
    val totalCallEvents: Int,
    val suspectedDropsCount: Int,
    val contributingFactors: List<String>,
    val analyticalExplanation: String
)

object CallDropRiskEngine {

    /**
     * Evaluates a call state change event and classifies whether it was a normal termination,
     * a call drop associated with service interruption, or an unclassified event.
     */
    fun classifyCallEvent(
        sessionId: Long,
        timestampIso: String,
        timestampMs: Long,
        previousCallState: String,
        newCallState: String,
        currentMeasurement: LteMeasurementEntity?,
        nearbyServiceHoles: List<ServiceHoleEntity> = emptyList()
    ): CallReliabilityEventEntity {
        val lat = currentMeasurement?.latitude
        val lon = currentMeasurement?.longitude
        val acc = currentMeasurement?.accuracyM
        val rsrp = currentMeasurement?.rsrpDbm
        val serviceState = currentMeasurement?.serviceState ?: "IN_SERVICE"
        val netGen = currentMeasurement?.networkGen ?: "UNKNOWN"
        val tech = currentMeasurement?.dataNetworkType ?: currentMeasurement?.networkType

        // 1. Call initiated / ringing
        if (newCallState == "OFFHOOK" && previousCallState != "OFFHOOK") {
            return CallReliabilityEventEntity(
                sessionId = sessionId,
                timestampIso = timestampIso,
                timestampMs = timestampMs,
                latitude = lat,
                longitude = lon,
                accuracyM = acc,
                callState = newCallState,
                classification = "NORMAL_CALL_START",
                previousState = previousCallState,
                networkGeneration = netGen,
                detailedTech = tech,
                rsrpDbm = rsrp,
                serviceState = serviceState,
                callDurationSec = 0L,
                riskScoreCategory = "LOW",
                evidenceFactors = "User-initiated active call established on $netGen ($tech)."
            )
        }

        // 2. Active call terminated: OFFHOOK -> IDLE
        if (previousCallState == "OFFHOOK" && newCallState == "IDLE") {
            // Check if there was an active service hole or outage within 5 seconds
            val recentHole = nearbyServiceHoles.find { hole ->
                Math.abs(hole.timestampMs - timestampMs) <= 5000L
            }

            val isOos = serviceState == "OUT_OF_SERVICE" || recentHole?.holeType == "RADIO_OUT_OF_SERVICE"
            val isSevereSignalDrop = rsrp != null && rsrp < -118

            val classification: String
            val riskCat: String
            val factors = mutableListOf<String>()

            when {
                isOos -> {
                    classification = "TERMINATION_WITH_SERVICE_INTERRUPTION"
                    riskCat = "SEVERE"
                    factors.add("Radio entered OUT_OF_SERVICE at or immediately preceding call termination.")
                    recentHole?.let { factors.add("Correlated with service hole: ${it.holeType}.") }
                }
                isSevereSignalDrop -> {
                    classification = "SUSPECTED_CALL_DROP"
                    riskCat = "HIGH"
                    factors.add("Call terminated during severe radio degradation (RSRP: $rsrp dBm < -118 dBm).")
                }
                rsrp != null && rsrp < -108 -> {
                    classification = "UNCLASSIFIED_TERMINATION"
                    riskCat = "MODERATE"
                    factors.add("Call ended in marginal RF coverage ($rsrp dBm). Cause unconfirmed by platform modem.")
                }
                else -> {
                    classification = "NORMAL_CALL_END"
                    riskCat = "LOW"
                    factors.add("Normal call termination in healthy $netGen coverage (${rsrp ?: -90} dBm).")
                }
            }

            return CallReliabilityEventEntity(
                sessionId = sessionId,
                timestampIso = timestampIso,
                timestampMs = timestampMs,
                latitude = lat,
                longitude = lon,
                accuracyM = acc,
                callState = newCallState,
                classification = classification,
                previousState = previousCallState,
                networkGeneration = netGen,
                detailedTech = tech,
                rsrpDbm = rsrp,
                serviceState = serviceState,
                callDurationSec = null,
                riskScoreCategory = riskCat,
                evidenceFactors = factors.joinToString(" • ")
            )
        }

        // Generic state transition
        return CallReliabilityEventEntity(
            sessionId = sessionId,
            timestampIso = timestampIso,
            timestampMs = timestampMs,
            latitude = lat,
            longitude = lon,
            accuracyM = acc,
            callState = newCallState,
            classification = "STATE_CHANGE",
            previousState = previousCallState,
            networkGeneration = netGen,
            detailedTech = tech,
            rsrpDbm = rsrp,
            serviceState = serviceState,
            callDurationSec = null,
            riskScoreCategory = "LOW",
            evidenceFactors = "Call state transitioned from $previousCallState to $newCallState."
        )
    }

    /**
     * Computes an explainable session-level Call-Drop Risk Indicator.
     * Evaluates evidence from weak coverage, service outages, rapid transitions, and call events.
     */
    fun evaluateSessionRisk(
        measurements: List<LteMeasurementEntity>,
        continuityEvents: List<NetworkContinuityEventEntity>,
        serviceHoles: List<ServiceHoleEntity>,
        callEvents: List<CallReliabilityEventEntity>
    ): CallDropRiskScore {
        if (measurements.size < 5) {
            return CallDropRiskScore(
                category = "INSUFFICIENT_EVIDENCE",
                scoreValue = 0.0,
                totalCallEvents = callEvents.size,
                suspectedDropsCount = 0,
                contributingFactors = listOf("Insufficient survey duration (< 5 measurements) to evaluate call-drop risk."),
                analyticalExplanation = "Insufficient evidence to estimate risk. Additional drive-test observations required."
            )
        }

        var riskPoints = 0.0
        val factors = mutableListOf<String>()

        // 1. Weak Signal Proportion (< -115 dBm)
        val validRsrp = measurements.mapNotNull { it.rsrpDbm }
        if (validRsrp.isNotEmpty()) {
            val weakCount = validRsrp.count { it < -115 }
            val weakPct = (weakCount.toDouble() / validRsrp.size) * 100.0
            if (weakPct > 20.0) {
                riskPoints += 30.0
                factors.add(String.format(Locale.US, "Severe coverage deficit: %.1f%% of samples recorded RSRP < -115 dBm.", weakPct))
            } else if (weakPct > 8.0) {
                riskPoints += 15.0
                factors.add(String.format(Locale.US, "Moderate coverage deficit: %.1f%% of samples recorded RSRP < -115 dBm.", weakPct))
            }
        }

        // 2. Confirmed Out-of-Service Holes
        val oosHoles = serviceHoles.filter { it.holeType == "RADIO_OUT_OF_SERVICE" }
        if (oosHoles.isNotEmpty()) {
            val totalOosMs = oosHoles.sumOf { it.durationMs ?: 1000L }
            riskPoints += (oosHoles.size * 15.0).coerceAtMost(35.0)
            factors.add("Observed ${oosHoles.size} radio out-of-service outages totaling ${totalOosMs / 1000} seconds.")
        }

        // 3. Rapid Generation Downgrades
        val downgrades = continuityEvents.filter { it.transitionCategory == "DOWNGRADE" }
        if (downgrades.size >= 3) {
            riskPoints += 15.0
            factors.add("High mobility instability: ${downgrades.size} network generation downgrades detected.")
        }

        // 4. Observed Suspected Drops & Disruptive Call Ends
        val suspectedDrops = callEvents.filter {
            it.classification == "SUSPECTED_CALL_DROP" || it.classification == "TERMINATION_WITH_SERVICE_INTERRUPTION"
        }
        if (suspectedDrops.isNotEmpty()) {
            riskPoints += (suspectedDrops.size * 25.0).coerceAtMost(50.0)
            factors.add("Detected ${suspectedDrops.size} suspected unexpected call drop events.")
        }

        val clampedScore = riskPoints.coerceIn(0.0, 100.0)
        val category = when {
            clampedScore >= 65.0 -> "SEVERE"
            clampedScore >= 40.0 -> "HIGH"
            clampedScore >= 20.0 -> "MODERATE"
            else -> "LOW"
        }

        val explanation = when (category) {
            "SEVERE" -> "High probability of dropped voice calls due to persistent out-of-service zones and severe coverage attenuation."
            "HIGH" -> "Elevated risk of call drops and voice muting; frequent generation downgrades and weak signal segments observed."
            "MODERATE" -> "Localized call reliability risks present in edge-of-cell coverage pockets."
            else -> "Adequate cellular coverage stability. Call-drop risk indicators remain low along the tested route."
        }

        return CallDropRiskScore(
            category = category,
            scoreValue = clampedScore,
            totalCallEvents = callEvents.size,
            suspectedDropsCount = suspectedDrops.size,
            contributingFactors = if (factors.isEmpty()) listOf("No adverse telemetry anomalies detected along route.") else factors,
            analyticalExplanation = explanation
        )
    }
}
