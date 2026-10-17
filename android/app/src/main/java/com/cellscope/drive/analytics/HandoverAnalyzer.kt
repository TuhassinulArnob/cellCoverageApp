package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import java.util.Locale

data class CellDwellStat(
    val pci: Int,
    val totalDwellTimeMs: Long,
    val sampleCount: Int,
    val avgRsrp: Double?,
    val percentageOfSession: Float
)

data class HandoverSummary(
    val totalTransitions: Int,
    val confirmedHandovers: Int,
    val observedTransitions: Int,
    val cellReselections: Int,
    val signalLossTransitions: Int,
    val pingPongEvents: Int,
    val intraFrequencyTransitions: Int = 0,
    val interFrequencyTransitions: Int = 0,
    val transitionsPerKm: Float,
    val dwellStats: List<CellDwellStat>,
    val transitionEvents: List<CellTransitionEntity>
)

object HandoverAnalyzer {

    fun analyzeTransitions(
        sessionId: Long,
        measurements: List<LteMeasurementEntity>,
        totalDistanceMeters: Double = 0.0
    ): HandoverSummary {
        if (measurements.isEmpty()) {
            return HandoverSummary(
                totalTransitions = 0,
                confirmedHandovers = 0,
                observedTransitions = 0,
                cellReselections = 0,
                signalLossTransitions = 0,
                pingPongEvents = 0,
                transitionsPerKm = 0f,
                dwellStats = emptyList(),
                transitionEvents = emptyList()
            )
        }

        val events = mutableListOf<CellTransitionEntity>()
        var lastPci: Int? = null
        var lastEarfcn: Int? = null
        var lastRsrp: Int? = null
        var lastTac: Int? = null
        var lastTimestampMs: Long = 0L

        // Track dwell times
        val dwellTimeMap = mutableMapOf<Int, Long>()
        val dwellSampleMap = mutableMapOf<Int, Int>()
        val rsrpAccumulatorMap = mutableMapOf<Int, Long>()
        val rsrpCountMap = mutableMapOf<Int, Int>()

        var confirmedCount = 0
        var observedCount = 0
        var reselectionCount = 0
        var signalLossCount = 0
        var pingPongCount = 0
        var intraFreqCount = 0
        var interFreqCount = 0

        val recentPciHistory = ArrayDeque<Int>()

        for (i in measurements.indices) {
            val m = measurements[i]
            val currentPci = m.servingPci

            if (currentPci != null) {
                // Accumulate dwell stats
                val dt = if (lastTimestampMs > 0 && lastPci == currentPci) {
                    (m.timestampMs - lastTimestampMs).coerceIn(0L, 5000L)
                } else {
                    1000L
                }

                dwellTimeMap[currentPci] = (dwellTimeMap[currentPci] ?: 0L) + dt
                dwellSampleMap[currentPci] = (dwellSampleMap[currentPci] ?: 0) + 1

                if (m.rsrpDbm != null) {
                    rsrpAccumulatorMap[currentPci] = (rsrpAccumulatorMap[currentPci] ?: 0L) + m.rsrpDbm
                    rsrpCountMap[currentPci] = (rsrpCountMap[currentPci] ?: 0) + 1
                }

                // Detect transition
                if (lastPci != null && lastPci != currentPci) {
                    val isStationary = (m.speedKmh ?: 0f) < 4.0f
                    val isSignalDrop = (lastRsrp ?: -90) < -115

                    // Intra vs Inter frequency classification
                    val isIntraFreq = (lastEarfcn != null && m.earfcn != null && lastEarfcn == m.earfcn)
                    if (isIntraFreq) {
                        intraFreqCount++
                    } else if (lastEarfcn != null && m.earfcn != null) {
                        interFreqCount++
                    }

                    // Check ping pong: switched back to a recently left PCI within last 4 events
                    val isPingPong = recentPciHistory.contains(currentPci)
                    if (isPingPong) {
                        pingPongCount++
                    }

                    val eventType: String
                    val confidence: Float
                    val detail: String

                    when {
                        isSignalDrop -> {
                            eventType = "SIGNAL_LOSS"
                            confidence = 0.85f
                            detail = "Signal collapsed to ${lastRsrp ?: -120} dBm before transition to PCI $currentPci"
                            signalLossCount++
                        }
                        isStationary -> {
                            eventType = "CELL_RESELECTION"
                            confidence = 0.80f
                            detail = "Stationary cell reselection while speed was ${String.format(Locale.US, "%.1f", m.speedKmh ?: 0f)} km/h"
                            reselectionCount++
                        }
                        isPingPong -> {
                            eventType = "POSSIBLE_HANDOVER"
                            confidence = 0.65f
                            detail = "Rapid ping-pong transition back to PCI $currentPci"
                        }
                        lastTac != null && m.tac != null && lastTac != m.tac -> {
                            eventType = "CONFIRMED_HANDOVER"
                            confidence = 0.95f
                            detail = "Inter-TAC handover from TAC $lastTac to TAC ${m.tac} with PCI $lastPci -> $currentPci"
                            confirmedCount++
                        }
                        else -> {
                            eventType = "OBSERVED_TRANSITION"
                            confidence = 0.75f
                            val freqType = if (isIntraFreq) "Intra-Freq" else "Inter-Freq"
                            detail = "$freqType switch from PCI $lastPci to PCI $currentPci"
                            observedCount++
                        }
                    }

                    events.add(
                        CellTransitionEntity(
                            sessionId = sessionId,
                            timestampIso = m.timestampIso,
                            timestampMs = m.timestampMs,
                            latitude = m.latitude,
                            longitude = m.longitude,
                            fromPci = lastPci,
                            toPci = currentPci,
                            fromRsrp = lastRsrp,
                            toRsrp = m.rsrpDbm,
                            fromTac = lastTac,
                            toTac = m.tac,
                            eventType = eventType,
                            confidenceScore = confidence,
                            details = detail
                        )
                    )

                    recentPciHistory.addLast(lastPci)
                    if (recentPciHistory.size > 5) {
                        recentPciHistory.removeFirst()
                    }
                }

                lastPci = currentPci
                lastEarfcn = m.earfcn
                lastRsrp = m.rsrpDbm
                lastTac = m.tac
                lastTimestampMs = m.timestampMs
            }
        }

        val totalSessionDwellTime = dwellTimeMap.values.sum().coerceAtLeast(1L)
        val dwellStats = dwellTimeMap.map { (pci, timeMs) ->
            val count = rsrpCountMap[pci] ?: 0
            val avg = if (count > 0) (rsrpAccumulatorMap[pci] ?: 0L).toDouble() / count else null
            CellDwellStat(
                pci = pci,
                totalDwellTimeMs = timeMs,
                sampleCount = dwellSampleMap[pci] ?: 0,
                avgRsrp = avg,
                percentageOfSession = (timeMs.toFloat() / totalSessionDwellTime) * 100f
            )
        }.sortedByDescending { it.totalDwellTimeMs }

        val distanceKm = (totalDistanceMeters / 1000.0).coerceAtLeast(0.1)
        val transitionsPerKm = (events.size / distanceKm).toFloat()

        return HandoverSummary(
            totalTransitions = events.size,
            confirmedHandovers = confirmedCount,
            observedTransitions = observedCount,
            cellReselections = reselectionCount,
            signalLossTransitions = signalLossCount,
            pingPongEvents = pingPongCount,
            intraFrequencyTransitions = intraFreqCount,
            interFrequencyTransitions = interFreqCount,
            transitionsPerKm = transitionsPerKm,
            dwellStats = dwellStats,
            transitionEvents = events
        )
    }
}
