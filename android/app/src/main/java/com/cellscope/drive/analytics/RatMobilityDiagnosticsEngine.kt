package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.model.NetworkGeneration
import java.util.Locale

object RatMobilityDiagnosticsEngine {

    enum class DowngradeRootCause(val label: String) {
        WEAK_COVERAGE_BOUNDARY("Weak Coverage Boundary"),
        DETERIORATING_RADIO_QUALITY("Deteriorating Radio Quality / High Interference"),
        PILOT_POLLUTION_CONTENTION("Intra-Frequency Pilot Pollution / Contention"),
        HIGH_SPEED_MOBILITY_DOPPLER("Rapid Mobility / Handover Stress"),
        MISSING_NEIGHBOR_RELATION("Missing Neighbor Relation / ANR Failure"),
        CIRCUIT_SWITCHED_FALLBACK("Circuit-Switched Fallback (CSFB)"),
        FAST_PING_PONG_BOUNCE("Fast Ping-Pong Reselection"),
        INCONCLUSIVE_TELEMETRY_WINDOW("Inconclusive Telemetry Window")
    }

    data class RatTransitionEvent(
        val timestampMs: Long,
        val timestampIso: String,
        val latitude: Double?,
        val longitude: Double?,
        val fromRat: String,
        val toRat: String,
        val fromGeneration: NetworkGeneration,
        val toGeneration: NetworkGeneration,
        val fromPci: Int?,
        val toPci: Int?,
        val fromSignalDbm: Int?,
        val toSignalDbm: Int?,
        val preTransitionMeanRsrp: Double?,
        val preTransitionMeanRsrq: Double?,
        val preTransitionMeanSinr: Double?,
        val preTransitionNeighborCount: Int,
        val speedKmh: Float?,
        val isPingPong: Boolean,
        val pingPongReturnDurationMs: Long?,
        val rootCause: DowngradeRootCause,
        val confidenceScore: Double,
        val diagnosticEvidence: String,
        val engineeringRemediation: String
    )

    data class RatMobilitySummary(
        val totalTransitions: Int,
        val lteTo3gDowngrades: Int,
        val lteTo2gDowngrades: Int,
        val nrToLteDowngrades: Int,
        val totalUpgrades: Int,
        val fastPingPongCount: Int,
        val rootCauseBreakdown: Map<DowngradeRootCause, Int>,
        val events: List<RatTransitionEvent>
    )

    const val WINDOW_MS = 15000L // 15-second pre/post transition window

    fun analyzeRatMobility(measurements: List<LteMeasurementEntity>): RatMobilitySummary {
        if (measurements.size < 2) {
            return RatMobilitySummary(
                totalTransitions = 0,
                lteTo3gDowngrades = 0,
                lteTo2gDowngrades = 0,
                nrToLteDowngrades = 0,
                totalUpgrades = 0,
                fastPingPongCount = 0,
                rootCauseBreakdown = emptyMap(),
                events = emptyList()
            )
        }

        val sorted = measurements.sortedBy { it.timestampMs }
        val events = mutableListOf<RatTransitionEvent>()

        var lteTo3g = 0
        var lteTo2g = 0
        var nrToLte = 0
        var upgrades = 0
        var pingPongs = 0
        val rootCauseCounts = mutableMapOf<DowngradeRootCause, Int>()

        fun resolveGen(m: LteMeasurementEntity): NetworkGeneration {
            if (m.serviceState == "OUT_OF_SERVICE") return NetworkGeneration.OUT_OF_SERVICE
            val genStr = m.networkGen ?: m.servingRat ?: m.networkType
            return NetworkGeneration.fromString(genStr)
        }

        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val curr = sorted[i]

            val prevGen = resolveGen(prev)
            val currGen = resolveGen(curr)

            if (prevGen != currGen && prevGen != NetworkGeneration.OUT_OF_SERVICE && currGen != NetworkGeneration.OUT_OF_SERVICE) {
                val isDowngrade = prevGen.generationRank > currGen.generationRank
                val isUpgrade = prevGen.generationRank < currGen.generationRank

                if (isUpgrade) upgrades++

                // Identify specific downgrade types
                val prevIs5g = prevGen == NetworkGeneration.GEN_5G
                val prevIs4g = prevGen == NetworkGeneration.GEN_4G
                val currIs4g = currGen == NetworkGeneration.GEN_4G
                val currIs3g = currGen == NetworkGeneration.GEN_3G
                val currIs2g = currGen == NetworkGeneration.GEN_2G

                if (prevIs5g && currIs4g) nrToLte++
                if (prevIs4g && currIs3g) lteTo3g++
                if (prevIs4g && currIs2g) lteTo2g++

                // Check post-transition window for fast ping-pong return
                var isPingPong = false
                var pingPongDurationMs: Long? = null
                val postWindow = sorted.filter { it.timestampMs in curr.timestampMs..(curr.timestampMs + WINDOW_MS) }
                val returnIndex = postWindow.indexOfFirst { resolveGen(it) == prevGen }
                if (returnIndex > 0) {
                    isPingPong = true
                    pingPongDurationMs = postWindow[returnIndex].timestampMs - curr.timestampMs
                    pingPongs++
                }

                // Analyze pre-transition window (up to 15 seconds prior)
                val preWindow = sorted.filter { it.timestampMs in (curr.timestampMs - WINDOW_MS) until curr.timestampMs }

                val preRsrps = preWindow.mapNotNull { it.rsrpDbm?.toDouble() }
                val preRsrqs = preWindow.mapNotNull { it.rsrqDb?.toDouble() }
                val preSinrs = preWindow.mapNotNull { it.sinrDb }
                val preNeighbors = preWindow.mapNotNull { parseNeighborsCount(it.neighborCellsJson) }

                val meanPreRsrp = if (preRsrps.isNotEmpty()) preRsrps.average() else prev.rsrpDbm?.toDouble()
                val meanPreRsrq = if (preRsrqs.isNotEmpty()) preRsrqs.average() else prev.rsrqDb?.toDouble()
                val meanPreSinr = if (preSinrs.isNotEmpty()) preSinrs.average() else prev.sinrDb
                val avgNeighbors = if (preNeighbors.isNotEmpty()) preNeighbors.average().toInt() else 0
                val speed = curr.speedKmh ?: prev.speedKmh

                // Rule-based diagnostic evaluation (8 Rules)
                val (rootCause, confidence, evidence, remediation) = evaluateRootCause(
                    isDowngrade = isDowngrade,
                    isPingPong = isPingPong,
                    pingPongMs = pingPongDurationMs,
                    prevGen = prevGen,
                    currGen = currGen,
                    meanRsrp = meanPreRsrp,
                    meanRsrq = meanPreRsrq,
                    meanSinr = meanPreSinr,
                    neighborCount = avgNeighbors,
                    speedKmh = speed,
                    timeDeltaMs = curr.timestampMs - prev.timestampMs,
                    serviceState = curr.serviceState,
                    callState = curr.dataActivityState
                )

                if (isDowngrade) {
                    rootCauseCounts[rootCause] = (rootCauseCounts[rootCause] ?: 0) + 1
                }

                events.add(
                    RatTransitionEvent(
                        timestampMs = curr.timestampMs,
                        timestampIso = curr.timestampIso,
                        latitude = curr.latitude,
                        longitude = curr.longitude,
                        fromRat = prev.servingRat ?: prev.networkType,
                        toRat = curr.servingRat ?: curr.networkType,
                        fromGeneration = prevGen,
                        toGeneration = currGen,
                        fromPci = prev.servingPci,
                        toPci = curr.servingPci,
                        fromSignalDbm = prev.primarySignalDbm ?: prev.rsrpDbm,
                        toSignalDbm = curr.primarySignalDbm ?: curr.rsrpDbm,
                        preTransitionMeanRsrp = meanPreRsrp?.let { Math.round(it * 10.0) / 10.0 },
                        preTransitionMeanRsrq = meanPreRsrq?.let { Math.round(it * 10.0) / 10.0 },
                        preTransitionMeanSinr = meanPreSinr?.let { Math.round(it * 10.0) / 10.0 },
                        preTransitionNeighborCount = avgNeighbors,
                        speedKmh = speed,
                        isPingPong = isPingPong,
                        pingPongReturnDurationMs = pingPongDurationMs,
                        rootCause = rootCause,
                        confidenceScore = confidence,
                        diagnosticEvidence = evidence,
                        engineeringRemediation = remediation
                    )
                )
            }
        }

        return RatMobilitySummary(
            totalTransitions = events.size,
            lteTo3gDowngrades = lteTo3g,
            lteTo2gDowngrades = lteTo2g,
            nrToLteDowngrades = nrToLte,
            totalUpgrades = upgrades,
            fastPingPongCount = pingPongs,
            rootCauseBreakdown = rootCauseCounts,
            events = events
        )
    }

    private data class Diagnosis(
        val rootCause: DowngradeRootCause,
        val confidence: Double,
        val evidence: String,
        val remediation: String
    )

    private fun evaluateRootCause(
        isDowngrade: Boolean,
        isPingPong: Boolean,
        pingPongMs: Long?,
        prevGen: NetworkGeneration,
        currGen: NetworkGeneration,
        meanRsrp: Double?,
        meanRsrq: Double?,
        meanSinr: Double?,
        neighborCount: Int,
        speedKmh: Float?,
        timeDeltaMs: Long,
        serviceState: String?,
        callState: String?
    ): Diagnosis {
        if (!isDowngrade) {
            return Diagnosis(
                rootCause = DowngradeRootCause.INCONCLUSIVE_TELEMETRY_WINDOW,
                confidence = 0.90,
                evidence = "Standard inter-RAT upgrade from ${prevGen.label} to ${currGen.label}.",
                remediation = "Normal network mobility operation."
            )
        }

        // Rule 7: Fast Ping-Pong Bounce
        if (isPingPong && pingPongMs != null && pingPongMs < 15000L) {
            return Diagnosis(
                rootCause = DowngradeRootCause.FAST_PING_PONG_BOUNCE,
                confidence = 0.92,
                evidence = "Device downgraded from ${prevGen.code} to ${currGen.code} but rebounded back within ${(pingPongMs / 1000.0).toInt()}s.",
                remediation = "Tune eNodeB inter-RAT cell reselection hysteresis (Qhyst) and TreselectionRAT timer to avoid ping-pong oscillations."
            )
        }

        // Rule 8: Inconclusive Telemetry Window / Gap
        if (timeDeltaMs > 10000L) {
            return Diagnosis(
                rootCause = DowngradeRootCause.INCONCLUSIVE_TELEMETRY_WINDOW,
                confidence = 0.70,
                evidence = "Telemetry gap of ${(timeDeltaMs / 1000.0).toInt()}s immediately preceding downgrade. Pre-transition metrics unobservable.",
                remediation = "Verify background GPS/telemetry collection service keep-alive configuration."
            )
        }

        // Rule 6: Circuit-Switched Fallback (CSFB)
        if (callState != null && (callState.contains("CALL", ignoreCase = true) || callState.contains("OFFHOOK", ignoreCase = true))) {
            return Diagnosis(
                rootCause = DowngradeRootCause.CIRCUIT_SWITCHED_FALLBACK,
                confidence = 0.88,
                evidence = "Call activity active during transition from LTE to legacy ${currGen.label}. Characteristic of CSFB due to lack of VoLTE/IMS bearer.",
                remediation = "Audit IMS registration and VoLTE APN configuration on handset and core network."
            )
        }

        // Rule 1: Weak Coverage Boundary
        if (meanRsrp != null && meanRsrp < -115.0) {
            return Diagnosis(
                rootCause = DowngradeRootCause.WEAK_COVERAGE_BOUNDARY,
                confidence = 0.95,
                evidence = String.format(Locale.US, "Pre-transition LTE RSRP deteriorated to %.1f dBm (< -115 dBm coverage threshold) triggering B2/B1 measurement report.", meanRsrp),
                remediation = "RF coverage hole identified. Adjust eNodeB electrical downtilt, transmit power, or deploy small cell fill-in."
            )
        }

        // Rule 2: Deteriorating Radio Quality (Interference while RSRP is moderate)
        if (meanRsrp != null && meanRsrp >= -110.0 && ((meanRsrq != null && meanRsrq < -16.0) || (meanSinr != null && meanSinr < 0.0))) {
            val metricStr = if (meanSinr != null) "SINR %.1f dB".format(Locale.US, meanSinr) else "RSRQ %.1f dB".format(Locale.US, meanRsrq)
            return Diagnosis(
                rootCause = DowngradeRootCause.DETERIORATING_RADIO_QUALITY,
                confidence = 0.86,
                evidence = String.format(Locale.US, "LTE signal level was adequate (%.1f dBm), but channel quality collapsed ($metricStr) forcing downgrade.", meanRsrp),
                remediation = "Mitigate co-channel interference. Optimize PCI planning, CRS modulo-3 alignment, or inter-cell interference coordination (ICIC)."
            )
        }

        // Rule 3: Intra-Frequency Pilot Pollution Contention
        if (neighborCount >= 3 && meanRsrq != null && meanRsrq < -14.0) {
            return Diagnosis(
                rootCause = DowngradeRootCause.PILOT_POLLUTION_CONTENTION,
                confidence = 0.84,
                evidence = ">= 3 competing intra-frequency neighbor cells detected with degraded RSRQ prior to downgrade.",
                remediation = "Eliminate pilot pollution: tilt down overshooting neighbor sectors to re-establish single dominant server."
            )
        }

        // Rule 4: High Speed Mobility Handover Stress
        if (speedKmh != null && speedKmh > 60.0f) {
            return Diagnosis(
                rootCause = DowngradeRootCause.HIGH_SPEED_MOBILITY_DOPPLER,
                confidence = 0.80,
                evidence = String.format(Locale.US, "Downgrade occurred at vehicular speed of %.1f km/h during rapid signal variation.", speedKmh),
                remediation = "Review eNodeB mobility parameters: configure Speed Dependent Scaling for Handover (Time-to-Trigger scaling)."
            )
        }

        // Rule 5: Missing Neighbor Relation / Sudden Drop
        if (neighborCount == 0 && meanRsrp != null && meanRsrp >= -110.0) {
            return Diagnosis(
                rootCause = DowngradeRootCause.MISSING_NEIGHBOR_RELATION,
                confidence = 0.78,
                evidence = "No neighbor cells detected in measurement window prior to abrupt LTE connection release.",
                remediation = "Verify Automatic Neighbor Relation (ANR) configurations and inter-frequency neighbor relation tables in OSS."
            )
        }

        // Fallback: Inconclusive
        return Diagnosis(
            rootCause = DowngradeRootCause.INCONCLUSIVE_TELEMETRY_WINDOW,
            confidence = 0.55,
            evidence = "Modem reselection from ${prevGen.code} to ${currGen.code} without definitive RF threshold violation.",
            remediation = "Perform dedicated drive-test capture with chipset-level diagnostic logging."
        )
    }

    private fun parseNeighborsCount(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val cells = raw.split(";").filter { it.isNotBlank() }
        return cells.size
    }
}
