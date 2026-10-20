package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import java.util.Locale
import kotlin.math.roundToInt

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
    val transitionsPerMinute: Float = 0f,
    val pingPongRatePct: Float = 0f,
    val avgDeltaRsrpDb: Double? = null,
    val dwellStats: List<CellDwellStat>,
    val transitionEvents: List<CellTransitionEntity>
)

object HandoverAnalyzer {

    const val API_LIMITATION_DISCLAIMER =
        "Public Android Telephony APIs observe serving cell changes at application layer. In absence of RRC Connection Reconfiguration signalling, transitions are classified as Observed Transitions, Reselections, or Inferred Handovers based on empirical motion and RF evidence."

    const val PING_PONG_WINDOW_MS = 15000L // 15 seconds threshold for rapid return transition

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
                intraFrequencyTransitions = 0,
                interFrequencyTransitions = 0,
                transitionsPerKm = 0f,
                transitionsPerMinute = 0f,
                pingPongRatePct = 0f,
                avgDeltaRsrpDb = null,
                dwellStats = emptyList(),
                transitionEvents = emptyList()
            )
        }

        // Sort chronologically to protect against jitter or out-of-order samples
        val sortedMeasurements = measurements.sortedBy { it.timestampMs }

        val events = mutableListOf<CellTransitionEntity>()
        var lastPci: Int? = null
        var lastEarfcn: Int? = null
        var lastRsrp: Int? = null
        var lastTac: Int? = null
        var lastRat: String? = null
        var lastCellId: Int? = null
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
        val deltaRsrpList = mutableListOf<Double>()

        // Historical transition history: list of Pair(PCI, departureTimestampMs)
        val transitionHistory = mutableListOf<Pair<Int, Long>>()

        for (i in sortedMeasurements.indices) {
            val m = sortedMeasurements[i]
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

                // Detect transition between physical cells
                if (lastPci != null && lastPci != currentPci) {
                    val isStationary = (m.speedKmh ?: 0f) < 4.0f
                    val isSignalDrop = (lastRsrp ?: -90) < -115
                    val deltaRsrp = if (lastRsrp != null && m.rsrpDbm != null) {
                        (m.rsrpDbm - lastRsrp).toDouble().also { deltaRsrpList.add(it) }
                    } else null

                    // Intra vs Inter frequency classification
                    val isIntraFreq = (lastEarfcn != null && m.earfcn != null && lastEarfcn == m.earfcn)
                    if (isIntraFreq) {
                        intraFreqCount++
                    } else if (lastEarfcn != null && m.earfcn != null) {
                        interFreqCount++
                    }

                    // Ping-pong detection: rapid return to a recently departed cell within PING_PONG_WINDOW_MS
                    val recentDeparture = transitionHistory.findLast { it.first == currentPci }
                    val isPingPong = recentDeparture != null && (m.timestampMs - recentDeparture.second) <= PING_PONG_WINDOW_MS

                    if (isPingPong) {
                        pingPongCount++
                    }

                    val eventType: String
                    val confidence: Float
                    val detail: String
                    val deltaStr = deltaRsrp?.let { " (ΔRSRP: ${if (it >= 0) "+$it" else "$it"} dB)" } ?: ""

                    when {
                        isSignalDrop -> {
                            eventType = "SIGNAL_LOSS"
                            confidence = 0.85f
                            detail = "Signal collapsed to ${lastRsrp ?: -120} dBm before transition to PCI $currentPci$deltaStr"
                            signalLossCount++
                        }
                        isStationary -> {
                            eventType = "CELL_RESELECTION"
                            confidence = 0.80f
                            detail = "Stationary cell reselection while speed was ${String.format(Locale.US, "%.1f", m.speedKmh ?: 0f)} km/h$deltaStr"
                            reselectionCount++
                        }
                        isPingPong -> {
                            eventType = "POSSIBLE_HANDOVER"
                            confidence = 0.65f
                            val returnSeconds = (m.timestampMs - recentDeparture!!.second) / 1000L
                            detail = "Rapid ping-pong transition back to PCI $currentPci within ${returnSeconds}s$deltaStr"
                        }
                        lastTac != null && m.tac != null && lastTac != m.tac -> {
                            eventType = "CONFIRMED_HANDOVER"
                            confidence = 0.95f
                            detail = "Inter-TAC handover from TAC $lastTac to TAC ${m.tac} with PCI $lastPci -> $currentPci$deltaStr"
                            confirmedCount++
                        }
                        else -> {
                            eventType = "OBSERVED_TRANSITION"
                            confidence = 0.75f
                            val freqType = if (isIntraFreq) "Intra-Freq" else "Inter-Freq"
                            detail = "$freqType switch from PCI $lastPci to PCI $currentPci$deltaStr"
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
                            details = detail,
                            fromRat = lastRat,
                            toRat = m.servingRat ?: m.networkType,
                            fromChannel = lastEarfcn,
                            toChannel = m.channelNumber ?: m.earfcn,
                            fromCellId = lastCellId,
                            toCellId = m.eci
                        )
                    )

                    transitionHistory.add(Pair(lastPci, m.timestampMs))
                    if (transitionHistory.size > 20) {
                        transitionHistory.removeAt(0)
                    }
                }

                lastPci = currentPci
                lastEarfcn = m.channelNumber ?: m.earfcn
                lastRsrp = m.rsrpDbm
                lastTac = m.tac
                lastRat = m.servingRat ?: m.networkType
                lastCellId = m.eci
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

        val totalDurationMs = if (sortedMeasurements.size >= 2) {
            sortedMeasurements.last().timestampMs - sortedMeasurements.first().timestampMs
        } else 0L
        val durationMinutes = (totalDurationMs / 60000.0).coerceAtLeast(0.1)
        val transitionsPerMinute = (events.size / durationMinutes).toFloat()

        val pingPongRatePct = if (events.isNotEmpty()) {
            ((pingPongCount.toDouble() / events.size.toDouble()) * 100.0).toFloat()
        } else 0f

        val avgDeltaRsrp = if (deltaRsrpList.isNotEmpty()) {
            ((deltaRsrpList.average() * 10.0).roundToInt() / 10.0)
        } else null

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
            transitionsPerMinute = transitionsPerMinute,
            pingPongRatePct = pingPongRatePct,
            avgDeltaRsrpDb = avgDeltaRsrp,
            dwellStats = dwellStats,
            transitionEvents = events
        )
    }
}
