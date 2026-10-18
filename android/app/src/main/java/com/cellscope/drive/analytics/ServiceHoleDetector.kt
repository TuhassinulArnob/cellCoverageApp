package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.data.entity.NetworkContinuityEventEntity
import com.cellscope.drive.data.entity.ServiceHoleEntity
import java.util.Locale

data class ServiceHoleSummary(
    val totalHoles: Int,
    val radioOutagesCount: Int,
    val dataDisconnectionsCount: Int,
    val testFailuresCount: Int,
    val poorPerformanceCount: Int,
    val weakSignalHolesCount: Int,
    val telemetryGapsCount: Int,
    val downgradesCount: Int,
    val holes: List<ServiceHoleEntity>
)

object ServiceHoleDetector {

    /**
     * Detects and classifies the 7 distinct service-hole categories:
     * 1. RADIO_OUT_OF_SERVICE
     * 2. CELLULAR_DATA_DISCONNECTED
     * 3. INTERNET_TEST_FAILURE
     * 4. POOR_INTERNET_PERFORMANCE
     * 5. WEAK_RADIO_SIGNAL
     * 6. TELEMETRY_GAP
     * 7. GENERATION_DOWNGRADE
     */
    fun detectHoles(
        sessionId: Long,
        measurements: List<LteMeasurementEntity>,
        activeTests: List<ActiveQoeTestEntity> = emptyList(),
        continuityEvents: List<NetworkContinuityEventEntity> = emptyList()
    ): ServiceHoleSummary {
        val holes = mutableListOf<ServiceHoleEntity>()

        var radioOutages = 0
        var dataDisconnections = 0
        var testFailures = 0
        var poorPerf = 0
        var weakSignal = 0
        var telemetryGaps = 0
        var downgrades = 0

        val sorted = measurements.sortedBy { it.timestampMs }

        // 1. Radio Out of Service & Weak Signal & Telemetry Gaps
        var currentOosStart: LteMeasurementEntity? = null
        var currentWeakStart: LteMeasurementEntity? = null
        var currentDataDisconnectStart: LteMeasurementEntity? = null

        for (i in sorted.indices) {
            val curr = sorted[i]

            // Check Telemetry Gap between consecutive measurements
            if (i > 0) {
                val prev = sorted[i - 1]
                val dtMs = curr.timestampMs - prev.timestampMs
                if (dtMs > 10000L) { // > 10 seconds gap
                    telemetryGaps++
                    holes.add(
                        ServiceHoleEntity(
                            sessionId = sessionId,
                            timestampIso = prev.timestampIso,
                            timestampMs = prev.timestampMs,
                            latitude = prev.latitude,
                            longitude = prev.longitude,
                            accuracyM = prev.accuracyM,
                            holeType = "TELEMETRY_GAP",
                            severity = "MEDIUM",
                            durationMs = dtMs,
                            recoveryTimestampMs = curr.timestampMs,
                            rsrpDbm = prev.rsrpDbm,
                            networkType = prev.networkType,
                            networkGen = prev.networkGen,
                            engineeringEvidence = String.format(Locale.US, "Telemetry gap of %.1f seconds between samples. Device may have been asleep or background restricted.", dtMs / 1000.0),
                            resolutionStatus = "RECOVERED"
                        )
                    )
                }
            }

            // Radio Out of Service
            val isOos = curr.serviceState == "OUT_OF_SERVICE" || curr.serviceState == "POWER_OFF" || curr.networkGen == "OOS"
            if (isOos) {
                if (currentOosStart == null) {
                    currentOosStart = curr
                }
            } else {
                if (currentOosStart != null) {
                    radioOutages++
                    val durMs = curr.timestampMs - currentOosStart.timestampMs
                    holes.add(
                        ServiceHoleEntity(
                            sessionId = sessionId,
                            timestampIso = currentOosStart.timestampIso,
                            timestampMs = currentOosStart.timestampMs,
                            latitude = currentOosStart.latitude,
                            longitude = currentOosStart.longitude,
                            accuracyM = currentOosStart.accuracyM,
                            holeType = "RADIO_OUT_OF_SERVICE",
                            severity = "CRITICAL",
                            durationMs = durMs,
                            recoveryTimestampMs = curr.timestampMs,
                            rsrpDbm = null,
                            networkType = "NONE",
                            networkGen = "OOS",
                            engineeringEvidence = String.format(Locale.US, "Confirmed modem radio outage for %.1f seconds. Service state reported OUT_OF_SERVICE.", durMs / 1000.0),
                            resolutionStatus = "RECOVERED"
                        )
                    )
                    currentOosStart = null
                }
            }

            // Cellular Data Disconnected while Radio is In Service
            val isDataDown = !isOos && (curr.dataActivityState == "DISCONNECTED" || curr.dataActivityState == "SUSPENDED")
            if (isDataDown) {
                if (currentDataDisconnectStart == null) {
                    currentDataDisconnectStart = curr
                }
            } else {
                if (currentDataDisconnectStart != null) {
                    dataDisconnections++
                    val durMs = curr.timestampMs - currentDataDisconnectStart.timestampMs
                    holes.add(
                        ServiceHoleEntity(
                            sessionId = sessionId,
                            timestampIso = currentDataDisconnectStart.timestampIso,
                            timestampMs = currentDataDisconnectStart.timestampMs,
                            latitude = currentDataDisconnectStart.latitude,
                            longitude = currentDataDisconnectStart.longitude,
                            accuracyM = currentDataDisconnectStart.accuracyM,
                            holeType = "CELLULAR_DATA_DISCONNECTED",
                            severity = "HIGH",
                            durationMs = durMs.coerceAtLeast(1000L),
                            recoveryTimestampMs = curr.timestampMs,
                            rsrpDbm = currentDataDisconnectStart.rsrpDbm,
                            networkType = currentDataDisconnectStart.networkType,
                            networkGen = currentDataDisconnectStart.networkGen,
                            engineeringEvidence = String.format(Locale.US, "Radio in service but cellular data packet bearer reported disconnected for %.1f seconds.", durMs / 1000.0),
                            resolutionStatus = "RECOVERED"
                        )
                    )
                    currentDataDisconnectStart = null
                }
            }

            // Weak Radio Signal (< -115 dBm)
            val isWeak = curr.rsrpDbm != null && curr.rsrpDbm < -115
            if (isWeak) {
                if (currentWeakStart == null) {
                    currentWeakStart = curr
                }
            } else {
                if (currentWeakStart != null) {
                    weakSignal++
                    val durMs = curr.timestampMs - currentWeakStart.timestampMs
                    val minRsrp = currentWeakStart.rsrpDbm ?: -115
                    holes.add(
                        ServiceHoleEntity(
                            sessionId = sessionId,
                            timestampIso = currentWeakStart.timestampIso,
                            timestampMs = currentWeakStart.timestampMs,
                            latitude = currentWeakStart.latitude,
                            longitude = currentWeakStart.longitude,
                            accuracyM = currentWeakStart.accuracyM,
                            holeType = "WEAK_RADIO_SIGNAL",
                            severity = if (minRsrp <= -120) "HIGH" else "MEDIUM",
                            durationMs = durMs.coerceAtLeast(1000L),
                            recoveryTimestampMs = curr.timestampMs,
                            rsrpDbm = currentWeakStart.rsrpDbm,
                            networkType = currentWeakStart.networkType,
                            networkGen = currentWeakStart.networkGen,
                            engineeringEvidence = String.format(Locale.US, "Weak RF signal zone below -115 dBm (Min RSRP: %d dBm) for %.1f seconds.", minRsrp, durMs / 1000.0),
                            resolutionStatus = "RECOVERED"
                        )
                    )
                    currentWeakStart = null
                }
            }
        }

        // Close ongoing holes at end of session
        if (currentOosStart != null) {
            radioOutages++
            holes.add(
                ServiceHoleEntity(
                    sessionId = sessionId,
                    timestampIso = currentOosStart.timestampIso,
                    timestampMs = currentOosStart.timestampMs,
                    latitude = currentOosStart.latitude,
                    longitude = currentOosStart.longitude,
                    accuracyM = currentOosStart.accuracyM,
                    holeType = "RADIO_OUT_OF_SERVICE",
                    severity = "CRITICAL",
                    durationMs = (sorted.last().timestampMs - currentOosStart.timestampMs).coerceAtLeast(1000L),
                    recoveryTimestampMs = null,
                    rsrpDbm = null,
                    networkType = "NONE",
                    networkGen = "OOS",
                    engineeringEvidence = "Out of service ongoing through end of survey session.",
                    resolutionStatus = "ONGOING"
                )
            )
        }

        if (currentDataDisconnectStart != null) {
            dataDisconnections++
            holes.add(
                ServiceHoleEntity(
                    sessionId = sessionId,
                    timestampIso = currentDataDisconnectStart.timestampIso,
                    timestampMs = currentDataDisconnectStart.timestampMs,
                    latitude = currentDataDisconnectStart.latitude,
                    longitude = currentDataDisconnectStart.longitude,
                    accuracyM = currentDataDisconnectStart.accuracyM,
                    holeType = "CELLULAR_DATA_DISCONNECTED",
                    severity = "HIGH",
                    durationMs = (sorted.last().timestampMs - currentDataDisconnectStart.timestampMs).coerceAtLeast(1000L),
                    recoveryTimestampMs = null,
                    rsrpDbm = currentDataDisconnectStart.rsrpDbm,
                    networkType = currentDataDisconnectStart.networkType,
                    networkGen = currentDataDisconnectStart.networkGen,
                    engineeringEvidence = "Cellular data disconnection ongoing through end of survey session.",
                    resolutionStatus = "ONGOING"
                )
            )
        }

        // 2. Active Internet Test Failures and Poor Performance
        for (test in activeTests) {
            if (!test.success) {
                testFailures++
                holes.add(
                    ServiceHoleEntity(
                        sessionId = sessionId,
                        timestampIso = test.timestampIso,
                        timestampMs = test.timestampMs,
                        latitude = test.latitude,
                        longitude = test.longitude,
                        accuracyM = null,
                        holeType = "INTERNET_TEST_FAILURE",
                        severity = "CRITICAL",
                        durationMs = test.durationMs.toLong(),
                        recoveryTimestampMs = null,
                        rsrpDbm = null,
                        networkType = null,
                        networkGen = null,
                        engineeringEvidence = "Active ${test.testType} test failed: ${test.errorMessage ?: "Endpoint unreachable or timed out."}",
                        resolutionStatus = "OBSERVED"
                    )
                )
            } else {
                val isPoorLatency = test.latencyMs != null && test.latencyMs > 400
                val isPoorDl = test.downloadSpeedMbps != null && test.downloadSpeedMbps < 1.0
                if (isPoorLatency || isPoorDl) {
                    poorPerf++
                    val reason = if (isPoorLatency) "High latency (${test.latencyMs} ms > 400 ms)" else "Low throughput (${String.format(Locale.US, "%.2f", test.downloadSpeedMbps)} Mbps < 1.0 Mbps)"
                    holes.add(
                        ServiceHoleEntity(
                            sessionId = sessionId,
                            timestampIso = test.timestampIso,
                            timestampMs = test.timestampMs,
                            latitude = test.latitude,
                            longitude = test.longitude,
                            accuracyM = null,
                            holeType = "POOR_INTERNET_PERFORMANCE",
                            severity = "MEDIUM",
                            durationMs = test.durationMs.toLong(),
                            recoveryTimestampMs = null,
                            rsrpDbm = null,
                            networkType = null,
                            networkGen = null,
                            engineeringEvidence = "Active test completed but revealed degraded QoE: $reason.",
                            resolutionStatus = "OBSERVED"
                        )
                    )
                }
            }
        }

        // 3. Continuity Downgrades
        for (ev in continuityEvents.filter { it.transitionCategory == "DOWNGRADE" }) {
            downgrades++
            holes.add(
                ServiceHoleEntity(
                    sessionId = sessionId,
                    timestampIso = ev.timestampIso,
                    timestampMs = ev.timestampMs,
                    latitude = ev.latitude,
                    longitude = ev.longitude,
                    accuracyM = ev.accuracyM,
                    holeType = "GENERATION_DOWNGRADE",
                    severity = "MEDIUM",
                    durationMs = ev.durationSeconds?.let { it * 1000L },
                    recoveryTimestampMs = null,
                    rsrpDbm = ev.toSignalDbm,
                    networkType = ev.toTech,
                    networkGen = ev.toGeneration,
                    engineeringEvidence = "Network generation downgrade: ${ev.fromGeneration} -> ${ev.toGeneration} (${ev.details})",
                    resolutionStatus = "OBSERVED"
                )
            )
        }

        return ServiceHoleSummary(
            totalHoles = holes.size,
            radioOutagesCount = radioOutages,
            dataDisconnectionsCount = dataDisconnections,
            testFailuresCount = testFailures,
            poorPerformanceCount = poorPerf,
            weakSignalHolesCount = weakSignal,
            telemetryGapsCount = telemetryGaps,
            downgradesCount = downgrades,
            holes = holes.sortedBy { it.timestampMs }
        )
    }
}
