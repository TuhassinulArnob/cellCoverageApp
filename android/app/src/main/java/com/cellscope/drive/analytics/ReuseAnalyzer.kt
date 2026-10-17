package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.model.ObservedCell
import com.cellscope.drive.telephony.LteBandTable
import java.util.Locale
import kotlin.math.*

object ReuseAnalyzer {

    enum class ReuseRiskLevel {
        LOW_RISK_PLANNED_REUSE,     // Proper spatial separation (> 2.0 km)
        MODERATE_RISK_PROXIMITY,    // Medium separation (1.0 km - 2.0 km)
        HIGH_RISK_COLLISION,        // Implausibly close (< 1.0 km) or simultaneous detection
        CRITICAL_CONFUSION_HAZARD   // Identical PCI on multiple neighboring candidate cells
    }

    data class ObservedCellSignature(
        val pci: Int,
        val earfcn: Int?,
        val eci: Long?,
        val tac: Int?,
        val operatorAlpha: String?,
        val maxRsrpDbm: Int,
        val avgRsrpDbm: Double,
        val observationCount: Int,
        val bestLatitude: Double?,
        val bestLongitude: Double?,
        val isServing: Boolean
    )

    data class PciReuseEvent(
        val pci: Int,
        val earfcn: Int?,
        val cellA: ObservedCellSignature,
        val cellB: ObservedCellSignature,
        val separationDistanceM: Double,
        val riskLevel: ReuseRiskLevel,
        val diagnosticDescription: String
    )

    data class CrsCollisionWarning(
        val earfcn: Int,
        val pciA: Int,
        val pciB: Int,
        val mod3Value: Int,
        val severity: String,
        val technicalReason: String
    )

    data class FrequencyReuseSummary(
        val totalUniquePcis: Int,
        val totalUniqueEarfcn: Int,
        val activeBands: List<Int>,
        val sameFrequencyCellsCount: Map<Int, Int>, // EARFCN -> distinct cells count
        val reuseEvents: List<PciReuseEvent>,
        val crsCollisions: List<CrsCollisionWarning>,
        val hasSevereCollisions: Boolean
    )

    /**
     * Analyzes observed measurements for PCI reuse, spatial separation, collision/confusion risk,
     * and CRS modulo-3 subcarrier interference.
     */
    fun analyzeReuse(measurements: List<LteMeasurementEntity>): FrequencyReuseSummary {
        val valid = measurements.filter { it.servingPci != null && it.isGpsValid && it.latitude != null && it.longitude != null }
        if (valid.isEmpty()) {
            return FrequencyReuseSummary(
                totalUniquePcis = 0,
                totalUniqueEarfcn = 0,
                activeBands = emptyList(),
                sameFrequencyCellsCount = emptyMap(),
                reuseEvents = emptyList(),
                crsCollisions = emptyList(),
                hasSevereCollisions = false
            )
        }

        // Group observations by (PCI, ECI) to identify distinct physical cells
        val groupedCells = mutableMapOf<String, MutableList<LteMeasurementEntity>>()
        for (m in valid) {
            val pci = m.servingPci ?: continue
            val eci = m.eci?.toLong() ?: -1L
            // Key based on PCI and ECI if known, else cluster by geographic proximity
            val key = if (eci > 0) "PCI_${pci}_ECI_$eci" else "PCI_${pci}"
            groupedCells.getOrPut(key) { mutableListOf() }.add(m)
        }

        // Compute signatures for each physical cell
        val cellSignatures = mutableListOf<ObservedCellSignature>()
        for ((_, samples) in groupedCells) {
            val pci = samples.first().servingPci!!
            val earfcn = samples.mapNotNull { it.earfcn }.firstOrNull()
            val eci = samples.mapNotNull { it.eci }.firstOrNull()?.toLong()
            val tac = samples.mapNotNull { it.tac }.firstOrNull()
            val operator = samples.mapNotNull { it.networkType }.firstOrNull()

            val bestSample = samples.maxByOrNull { it.rsrpDbm ?: -140 } ?: samples.first()
            val avgRsrp = samples.mapNotNull { it.rsrpDbm }.average().takeIf { !it.isNaN() } ?: -110.0

            cellSignatures.add(
                ObservedCellSignature(
                    pci = pci,
                    earfcn = earfcn,
                    eci = eci,
                    tac = tac,
                    operatorAlpha = operator,
                    maxRsrpDbm = bestSample.rsrpDbm ?: -140,
                    avgRsrpDbm = avgRsrp,
                    observationCount = samples.size,
                    bestLatitude = bestSample.latitude,
                    bestLongitude = bestSample.longitude,
                    isServing = samples.any { it.isServing }
                )
            )
        }

        // 1. Detect PCI Reuse between distinct cells
        val reuseEvents = mutableListOf<PciReuseEvent>()
        for (i in 0 until cellSignatures.size) {
            for (j in i + 1 until cellSignatures.size) {
                val a = cellSignatures[i]
                val b = cellSignatures[j]

                if (a.pci == b.pci) {
                    val distM = if (a.bestLatitude != null && a.bestLongitude != null && b.bestLatitude != null && b.bestLongitude != null) {
                        calculateHaversineM(a.bestLatitude, a.bestLongitude, b.bestLatitude, b.bestLongitude)
                    } else {
                        99999.0
                    }

                    // Ignore if it's the exact same cell (same ECI or distance < 200m)
                    if (a.eci != null && b.eci != null && a.eci == b.eci) continue
                    if (distM < 200.0) continue

                    val risk = when {
                        distM < 1000.0 -> ReuseRiskLevel.HIGH_RISK_COLLISION
                        distM < 2000.0 -> ReuseRiskLevel.MODERATE_RISK_PROXIMITY
                        else -> ReuseRiskLevel.LOW_RISK_PLANNED_REUSE
                    }

                    val desc = when (risk) {
                        ReuseRiskLevel.HIGH_RISK_COLLISION ->
                            String.format(Locale.US, "Severe PCI collision hazard: Cells share PCI %d only %.0fm apart. Handover failure and pilot collision expected.", a.pci, distM)
                        ReuseRiskLevel.MODERATE_RISK_PROXIMITY ->
                            String.format(Locale.US, "Close PCI reuse: Cells share PCI %d separated by %.1f km. Handover confusion possible.", a.pci, distM / 1000.0)
                        else ->
                            String.format(Locale.US, "Standard cellular reuse: Distinct sectors share PCI %d with %.1f km separation.", a.pci, distM / 1000.0)
                    }

                    reuseEvents.add(
                        PciReuseEvent(
                            pci = a.pci,
                            earfcn = a.earfcn ?: b.earfcn,
                            cellA = a,
                            cellB = b,
                            separationDistanceM = distM,
                            riskLevel = risk,
                            diagnosticDescription = desc
                        )
                    )
                }
            }
        }

        // 2. Detect CRS Modulo-3 Frequency Domain Collisions
        // In LTE, Cell-specific Reference Signal (CRS) subcarrier shift is v_shift = PCI mod 3 (or mod 6).
        // If two cells operate on the same EARFCN and have the same PCI mod 3, their CRS subcarriers overlap!
        val crsCollisions = mutableListOf<CrsCollisionWarning>()
        val byEarfcn = cellSignatures.filter { it.earfcn != null }.groupBy { it.earfcn!! }

        for ((earfcn, cells) in byEarfcn) {
            for (i in 0 until cells.size) {
                for (j in i + 1 until cells.size) {
                    val c1 = cells[i]
                    val c2 = cells[j]

                    if (c1.pci != c2.pci && (c1.pci % 3 == c2.pci % 3)) {
                        val mod3 = c1.pci % 3
                        val distM = if (c1.bestLatitude != null && c1.bestLongitude != null && c2.bestLatitude != null && c2.bestLongitude != null) {
                            calculateHaversineM(c1.bestLatitude, c1.bestLongitude, c2.bestLatitude, c2.bestLongitude)
                        } else 5000.0

                        if (distM < 3000.0) { // Relevant when cells are within 3 km of each other
                            crsCollisions.add(
                                CrsCollisionWarning(
                                    earfcn = earfcn,
                                    pciA = c1.pci,
                                    pciB = c2.pci,
                                    mod3Value = mod3,
                                    severity = if (distM < 1500.0) "HIGH INTERFERENCE" else "MODERATE",
                                    technicalReason = String.format(
                                        Locale.US,
                                        "PCI %d and PCI %d on EARFCN %d share PCI mod 3 = %d (%.0fm apart). CRS reference symbols overlap in frequency domain causing SINR degradation.",
                                        c1.pci, c2.pci, earfcn, mod3, distM
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }

        val uniquePcis = valid.mapNotNull { it.servingPci }.distinct().size
        val uniqueEarfcns = valid.mapNotNull { it.earfcn }.distinct()
        val activeBands = uniqueEarfcns.mapNotNull { LteBandTable.resolveEarfcn(it)?.bandNumber }.distinct()
        val sameFreqCount = byEarfcn.mapValues { it.value.size }

        return FrequencyReuseSummary(
            totalUniquePcis = uniquePcis,
            totalUniqueEarfcn = uniqueEarfcns.size,
            activeBands = activeBands,
            sameFrequencyCellsCount = sameFreqCount,
            reuseEvents = reuseEvents,
            crsCollisions = crsCollisions,
            hasSevereCollisions = reuseEvents.any { it.riskLevel == ReuseRiskLevel.HIGH_RISK_COLLISION } || crsCollisions.any { it.severity == "HIGH INTERFERENCE" }
        )
    }

    private fun calculateHaversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
