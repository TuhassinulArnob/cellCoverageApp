package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.data.entity.NetworkContinuityEventEntity
import com.cellscope.drive.model.NetworkGeneration
import java.util.Locale

data class GenerationDwellStat(
    val generation: NetworkGeneration,
    val sampleCount: Int,
    val percentOfSamples: Double,
    val durationSeconds: Long,
    val estimatedDistanceM: Double
)

data class NetworkContinuitySummary(
    val sessionId: Long,
    val totalObservations: Int,
    val totalTransitions: Int,
    val totalDowngrades: Int,
    val totalUpgrades: Int,
    val totalOutages: Int,
    val generationDwellStats: Map<NetworkGeneration, GenerationDwellStat>,
    val continuityEvents: List<NetworkContinuityEventEntity>,
    val telemetryGapsCount: Int,
    val totalGapDurationSeconds: Long
)

object NetworkContinuityAnalyzer {

    /**
     * Analyzes session telemetry measurements to identify network generation transitions,
     * continuity breaks, service interruptions, and dwell distributions.
     */
    fun analyzeSessionContinuity(
        sessionId: Long,
        measurements: List<LteMeasurementEntity>
    ): NetworkContinuitySummary {
        if (measurements.isEmpty()) {
            return NetworkContinuitySummary(
                sessionId = sessionId,
                totalObservations = 0,
                totalTransitions = 0,
                totalDowngrades = 0,
                totalUpgrades = 0,
                totalOutages = 0,
                generationDwellStats = emptyMap(),
                continuityEvents = emptyList(),
                telemetryGapsCount = 0,
                totalGapDurationSeconds = 0L
            )
        }

        val sorted = measurements.sortedBy { it.timestampMs }
        val events = mutableListOf<NetworkContinuityEventEntity>()

        var prevGen = resolveGeneration(sorted.first())
        var prevTech = sorted.first().dataNetworkType ?: sorted.first().networkType
        var prevSignal = sorted.first().rsrpDbm
        var prevTimeMs = sorted.first().timestampMs
        var transitionStartTimeMs: Long? = null

        var downgradeCount = 0
        var upgradeCount = 0
        var outageCount = 0
        var telemetryGapsCount = 0
        var totalGapDurationSec = 0L

        // Dwell statistics tracking
        val sampleCounts = mutableMapOf<NetworkGeneration, Int>()
        val durations = mutableMapOf<NetworkGeneration, Long>()
        val distances = mutableMapOf<NetworkGeneration, Double>()

        for (i in sorted.indices) {
            val curr = sorted[i]
            val currGen = resolveGeneration(curr)
            val currTech = curr.dataNetworkType ?: curr.networkType
            val currSignal = curr.rsrpDbm
            val currTimeMs = curr.timestampMs

            sampleCounts[currGen] = (sampleCounts[currGen] ?: 0) + 1

            if (i > 0) {
                val prev = sorted[i - 1]
                val dtSec = ((currTimeMs - prev.timestampMs) / 1000L).coerceAtLeast(0L)
                val stepDistM = computeDisplacementM(prev.latitude, prev.longitude, curr.latitude, curr.longitude)

                // Detect telemetry gaps (time gap > 10s or distance gap > 150m)
                if (dtSec > 10L) {
                    telemetryGapsCount++
                    totalGapDurationSec += dtSec
                } else {
                    durations[currGen] = (durations[currGen] ?: 0L) + dtSec
                    distances[currGen] = (distances[currGen] ?: 0.0) + stepDistM
                }
            } else {
                durations[currGen] = (durations[currGen] ?: 0L) + 1L
            }

            // Detect generation or service-state transition
            if (currGen != prevGen) {
                val category = classifyTransitionCategory(prevGen, currGen)
                when (category) {
                    "DOWNGRADE" -> downgradeCount++
                    "UPGRADE" -> upgradeCount++
                    "OUT_OF_SERVICE" -> outageCount++
                }

                val durationSec = if (transitionStartTimeMs != null) {
                    ((currTimeMs - transitionStartTimeMs) / 1000L).coerceAtLeast(1L)
                } else null

                val evidence = classifyEvidence(prevGen, currGen, prevSignal, currSignal, curr.serviceState)

                val event = NetworkContinuityEventEntity(
                    sessionId = sessionId,
                    timestampIso = curr.timestampIso,
                    timestampMs = currTimeMs,
                    latitude = curr.latitude,
                    longitude = curr.longitude,
                    accuracyM = curr.accuracyM,
                    fromGeneration = prevGen.code,
                    toGeneration = currGen.code,
                    fromTech = prevTech,
                    toTech = currTech,
                    transitionCategory = category,
                    fromSignalDbm = prevSignal,
                    toSignalDbm = currSignal,
                    durationSeconds = durationSec,
                    dataConnectivityState = curr.dataActivityState,
                    evidenceClassification = evidence.first,
                    details = evidence.second
                )
                events.add(event)

                transitionStartTimeMs = currTimeMs
                prevGen = currGen
                prevTech = currTech
                prevSignal = currSignal
            }

            prevTimeMs = currTimeMs
        }

        // Build dwell stats
        val totalSamples = sorted.size.toDouble()
        val dwellMap = mutableMapOf<NetworkGeneration, GenerationDwellStat>()
        for ((gen, count) in sampleCounts) {
            val pct = (count / totalSamples) * 100.0
            val durSec = durations[gen] ?: (count.toLong())
            val distM = distances[gen] ?: 0.0
            dwellMap[gen] = GenerationDwellStat(
                generation = gen,
                sampleCount = count,
                percentOfSamples = Math.round(pct * 10.0) / 10.0,
                durationSeconds = durSec,
                estimatedDistanceM = Math.round(distM * 10.0) / 10.0
            )
        }

        return NetworkContinuitySummary(
            sessionId = sessionId,
            totalObservations = sorted.size,
            totalTransitions = events.size,
            totalDowngrades = downgradeCount,
            totalUpgrades = upgradeCount,
            totalOutages = outageCount,
            generationDwellStats = dwellMap,
            continuityEvents = events,
            telemetryGapsCount = telemetryGapsCount,
            totalGapDurationSeconds = totalGapDurationSec
        )
    }

    fun resolveGeneration(m: LteMeasurementEntity): NetworkGeneration {
        if (m.serviceState == "OUT_OF_SERVICE" || m.serviceState == "POWER_OFF") {
            return NetworkGeneration.OUT_OF_SERVICE
        }
        if (m.networkGen != null) {
            val parsed = NetworkGeneration.fromString(m.networkGen)
            if (parsed != NetworkGeneration.UNKNOWN) return parsed
        }
        return NetworkGeneration.fromString(m.networkType)
    }

    private fun classifyTransitionCategory(from: NetworkGeneration, to: NetworkGeneration): String {
        if (to == NetworkGeneration.OUT_OF_SERVICE) return "OUT_OF_SERVICE"
        if (from == NetworkGeneration.OUT_OF_SERVICE && to.isCellularService) return "RECOVERY"
        val rankFrom = from.generationRank
        val rankTo = to.generationRank
        return when {
            rankFrom > rankTo -> "DOWNGRADE"
            rankFrom < rankTo -> "UPGRADE"
            else -> "GENERATION_CHANGE"
        }
    }

    private fun classifyEvidence(
        from: NetworkGeneration,
        to: NetworkGeneration,
        fromSignal: Int?,
        toSignal: Int?,
        serviceState: String?
    ): Pair<String, String> {
        if (to == NetworkGeneration.OUT_OF_SERVICE || serviceState == "OUT_OF_SERVICE") {
            return Pair(
                "CONFIRMED_OUT_OF_SERVICE",
                "Modem reported OUT_OF_SERVICE state from $from to $to."
            )
        }
        if (from == NetworkGeneration.OUT_OF_SERVICE && to.isCellularService) {
            return Pair(
                "SERVICE_RECOVERY",
                "Radio regained ${to.label} coverage (${toSignal?.let { "$it dBm" } ?: "Signal available"})."
            )
        }
        if (from.generationRank > to.generationRank) {
            // Downgrade
            if (fromSignal != null && fromSignal < -115) {
                return Pair(
                    "COVERAGE_HOLE_DOWNGRADE",
                    "Transitioned from ${from.code} to ${to.code} due to severe signal degradation (${fromSignal} dBm < -115 dBm)."
                )
            } else if (fromSignal != null && fromSignal >= -95) {
                return Pair(
                    "Network generation change — cause unconfirmed",
                    "Transitioned from ${from.code} to ${to.code} while signal was acceptable (${fromSignal} dBm). Possible carrier policy, traffic steering or modem layer reselection."
                )
            } else {
                return Pair(
                    "CELLULAR_RESELECTION_DOWNGRADE",
                    "Transitioned from ${from.code} (${fromSignal ?: 0} dBm) to ${to.code} (${toSignal ?: 0} dBm)."
                )
            }
        }
        // Upgrade
        return Pair(
            "GENERATION_UPGRADE",
            "Radio restored connection to higher-tier ${to.label} layer (${toSignal?.let { "$it dBm" } ?: "Active"})."
        )
    }

    private fun computeDisplacementM(lat1: Double?, lon1: Double?, lat2: Double?, lon2: Double?): Double {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return 0.0
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }
}
