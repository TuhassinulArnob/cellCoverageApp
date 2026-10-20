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

    enum class ReuseClassification {
        SAME_EARFCN,                          // Co-channel deployment on same carrier frequency
        DIFFERENT_PCI_SAME_EARFCN,            // Normal multi-cell deployment on same carrier
        SAME_PCI_DIFFERENT_CHANNELS,          // Inter-frequency multi-layer reuse (harmless)
        SAME_PCI_DIFFERENT_LOCATIONS,         // Planned spatial reuse (> 2.0 km separation)
        POTENTIAL_PCI_CONFUSION_COLLISION,    // Same EARFCN + Same PCI in close proximity (< 2.0 km)
        INSUFFICIENT_EVIDENCE                 // Missing coordinates or ambiguous cell identification
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
        val isServing: Boolean,
        val minSinrDb: Double? = null,
        val avgRsrqDb: Double? = null,
        val rat: String = "LTE",
        val channelNumber: Int? = earfcn
    )

    data class PciReuseEvent(
        val pci: Int,
        val earfcn: Int?,
        val cellA: ObservedCellSignature,
        val cellB: ObservedCellSignature,
        val separationDistanceM: Double,
        val riskLevel: ReuseRiskLevel,
        val classification: ReuseClassification = ReuseClassification.SAME_PCI_DIFFERENT_LOCATIONS,
        val diagnosticDescription: String
    )

    data class CrsCollisionWarning(
        val earfcn: Int,
        val pciA: Int,
        val pciB: Int,
        val mod3Value: Int,
        val severity: String,
        val technicalReason: String,
        val isCorroboratedInterference: Boolean = false
    )

    data class CellRadiusEstimate(
        val cellKey: String,
        val pci: Int,
        val rat: String,
        val channel: Int?,
        val sampleCount: Int,
        val estimatedRadiusM: Double?, // null if insufficient evidence
        val status: String,            // "VALID_ESTIMATE" or "INSUFFICIENT_EVIDENCE"
        val evidenceDescription: String
    )

    data class TheoreticalReuseGeometry(
        val rat: String,
        val channel: Int,
        val clusterSizeN: Int,
        val averageCellRadiusM: Double,
        val theoreticalReuseDistanceM: Double,
        val formulaDisplay: String = "D = R * sqrt(3N)",
        val modelDisclosure: String = "Theoretical idealized hexagonal lattice geometry; assumes uniform omnidirectional propagation and regular cell layout. Not tower-to-tower physical spacing."
    )

    data class FrequencyReuseSummary(
        val totalUniquePcis: Int,
        val totalUniqueEarfcn: Int,
        val activeBands: List<Int>,
        val sameFrequencyCellsCount: Map<Int, Int>, // EARFCN -> distinct cells count
        val reuseEvents: List<PciReuseEvent>,
        val crsCollisions: List<CrsCollisionWarning>,
        val hasSevereCollisions: Boolean,
        val classificationCounts: Map<ReuseClassification, Int> = emptyMap(),
        val reuseByRatAndChannel: Map<String, List<PciReuseEvent>> = emptyMap(),
        val cellRadiusEstimates: List<CellRadiusEstimate> = emptyList(),
        val theoreticalGeometries: List<TheoreticalReuseGeometry> = emptyList()
    )

    /**
     * Analyzes observed measurements for PCI reuse, spatial separation between observation points,
     * collision/confusion risk, LTE CRS modulo-3 subcarrier interference, empirical cell radius,
     * and theoretical hexagonal reuse distance.
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

        // Group observations by (PCI, ECI, RAT) or geographic proximity to identify distinct physical cells
        val groupedCells = mutableListOf<MutableList<LteMeasurementEntity>>()
        for (m in valid) {
            val pci = m.servingPci ?: continue
            val eci = m.eci?.toLong() ?: -1L
            val rat = m.servingRat ?: m.networkGen ?: m.networkType
            val lat = m.latitude ?: continue
            val lon = m.longitude ?: continue

            val match = groupedCells.firstOrNull { cluster ->
                val first = cluster.first()
                val cPci = first.servingPci
                val cEci = first.eci?.toLong() ?: -1L
                val cRat = first.servingRat ?: first.networkGen ?: first.networkType
                if (cPci != pci || !cRat.equals(rat, ignoreCase = true)) return@firstOrNull false

                if (eci > 0 && cEci > 0) {
                    cEci == eci
                } else {
                    val dist = calculateHaversineM(first.latitude!!, first.longitude!!, lat, lon)
                    dist < 1500.0
                }
            }

            if (match != null) {
                match.add(m)
            } else {
                groupedCells.add(mutableListOf(m))
            }
        }

        // Compute signatures for each physical cell
        val cellSignatures = mutableListOf<ObservedCellSignature>()
        val radiusEstimates = mutableListOf<CellRadiusEstimate>()

        for ((idx, samples) in groupedCells.withIndex()) {
            val key = "CELL_$idx"
            val pci = samples.first().servingPci!!
            val earfcn = samples.mapNotNull { it.earfcn }.firstOrNull()
            val chanNum = samples.mapNotNull { it.channelNumber }.firstOrNull() ?: earfcn
            val eci = samples.mapNotNull { it.eci }.firstOrNull()?.toLong()
            val tac = samples.mapNotNull { it.tac }.firstOrNull()
            val operator = samples.mapNotNull { it.operatorAlpha ?: it.networkType }.firstOrNull()
            val rat = samples.mapNotNull { it.servingRat ?: it.networkGen }.firstOrNull() ?: if (samples.first().networkType.contains("GSM", true)) "2G" else "4G"

            val bestSample = samples.maxByOrNull { it.primarySignalDbm ?: it.rsrpDbm ?: it.rssiDbm ?: -140 } ?: samples.first()
            val avgRsrp = samples.mapNotNull { it.primarySignalDbm ?: it.rsrpDbm ?: it.rssiDbm }.average().takeIf { !it.isNaN() } ?: -110.0
            val minSinr = samples.mapNotNull { it.sinrDb }.minOrNull()
            val avgRsrq = samples.mapNotNull { it.rsrqDb }.average().takeIf { !it.isNaN() }

            cellSignatures.add(
                ObservedCellSignature(
                    pci = pci,
                    earfcn = earfcn,
                    eci = eci,
                    tac = tac,
                    operatorAlpha = operator,
                    maxRsrpDbm = bestSample.primarySignalDbm ?: bestSample.rsrpDbm ?: bestSample.rssiDbm ?: -140,
                    avgRsrpDbm = avgRsrp,
                    observationCount = samples.size,
                    bestLatitude = bestSample.latitude,
                    bestLongitude = bestSample.longitude,
                    isServing = samples.any { it.isServing },
                    minSinrDb = minSinr,
                    avgRsrqDb = avgRsrq,
                    rat = rat,
                    channelNumber = chanNum
                )
            )

            // Empirical Cell Radius Estimation (Output D)
            val servingSamples = samples.filter { it.isServing && it.latitude != null && it.longitude != null }
            if (servingSamples.size < 5) {
                radiusEstimates.add(
                    CellRadiusEstimate(
                        cellKey = key,
                        pci = pci,
                        rat = rat,
                        channel = chanNum,
                        sampleCount = servingSamples.size,
                        estimatedRadiusM = null,
                        status = "INSUFFICIENT_EVIDENCE",
                        evidenceDescription = "Only ${servingSamples.size} serving sample(s) recorded (minimum 5 required for radius estimation)."
                    )
                )
            } else {
                val focalLat = bestSample.latitude!!
                val focalLon = bestSample.longitude!!
                var maxDistM = 0.0
                for (s in servingSamples) {
                    val d = calculateHaversineM(focalLat, focalLon, s.latitude!!, s.longitude!!)
                    if (d > maxDistM) maxDistM = d
                }

                if (maxDistM < 50.0) {
                    radiusEstimates.add(
                        CellRadiusEstimate(
                            cellKey = key,
                            pci = pci,
                            rat = rat,
                            channel = chanNum,
                            sampleCount = servingSamples.size,
                            estimatedRadiusM = null,
                            status = "INSUFFICIENT_EVIDENCE",
                            evidenceDescription = String.format(Locale.US, "Spatial spread is only %.0fm (stationary or cluster recording; insufficient spatial diversity).", maxDistM)
                        )
                    )
                } else {
                    radiusEstimates.add(
                        CellRadiusEstimate(
                            cellKey = key,
                            pci = pci,
                            rat = rat,
                            channel = chanNum,
                            sampleCount = servingSamples.size,
                            estimatedRadiusM = maxDistM,
                            status = "VALID_ESTIMATE",
                            evidenceDescription = String.format(Locale.US, "Empirical mobile coverage radius: %.0fm based on %d serving observation points.", maxDistM, servingSamples.size)
                        )
                    )
                }
            }
        }

        val classificationMap = mutableMapOf<ReuseClassification, Int>()
        fun incrementClassification(cat: ReuseClassification) {
            classificationMap[cat] = (classificationMap[cat] ?: 0) + 1
        }

        // 1. Detect PCI Reuse between distinct cells (Output A & C)
        val reuseEvents = mutableListOf<PciReuseEvent>()
        for (i in 0 until cellSignatures.size) {
            for (j in i + 1 until cellSignatures.size) {
                val a = cellSignatures[i]
                val b = cellSignatures[j]

                val isSamePci = a.pci == b.pci
                val isSameChan = a.channelNumber != null && b.channelNumber != null && a.channelNumber == b.channelNumber
                val isSameRat = a.rat.equals(b.rat, ignoreCase = true)

                if (isSameChan && isSameRat) {
                    incrementClassification(ReuseClassification.SAME_EARFCN)
                    if (!isSamePci) {
                        incrementClassification(ReuseClassification.DIFFERENT_PCI_SAME_EARFCN)
                    }
                } else if (isSamePci && isSameRat && a.channelNumber != null && b.channelNumber != null) {
                    incrementClassification(ReuseClassification.SAME_PCI_DIFFERENT_CHANNELS)
                }

                if (isSamePci && isSameRat) {
                    val distM = if (a.bestLatitude != null && a.bestLongitude != null && b.bestLatitude != null && b.bestLongitude != null) {
                        calculateHaversineM(a.bestLatitude, a.bestLongitude, b.bestLatitude, b.bestLongitude)
                    } else {
                        99999.0
                    }

                    // Ignore if it's the exact same cell (same ECI or distance < 200m)
                    if (a.eci != null && b.eci != null && a.eci == b.eci) continue
                    if (distM < 200.0) continue

                    val classification: ReuseClassification
                    val risk: ReuseRiskLevel
                    val desc: String

                    if (a.bestLatitude == null || b.bestLatitude == null) {
                        classification = ReuseClassification.INSUFFICIENT_EVIDENCE
                        risk = ReuseRiskLevel.LOW_RISK_PLANNED_REUSE
                        desc = "Insufficient geographic evidence to verify spatial separation for PCI ${a.pci}."
                    } else if (distM < 2000.0 && isSameChan) {
                        classification = ReuseClassification.POTENTIAL_PCI_CONFUSION_COLLISION
                        risk = if (distM < 1000.0) ReuseRiskLevel.HIGH_RISK_COLLISION else ReuseRiskLevel.MODERATE_RISK_PROXIMITY
                        desc = String.format(
                            Locale.US,
                            "Severe PCI collision/confusion hazard: Cells share PCI %d on channel %s separated by only %.0fm between observation points (Note: measures drive-test observation separation, not tower mast spacing). Handover failure expected.",
                            a.pci, a.channelNumber?.toString() ?: "Unknown", distM
                        )
                    } else {
                        classification = ReuseClassification.SAME_PCI_DIFFERENT_LOCATIONS
                        risk = ReuseRiskLevel.LOW_RISK_PLANNED_REUSE
                        desc = String.format(
                            Locale.US,
                            "Standard cellular spatial reuse: Distinct sectors share PCI %d with %.1f km separation between observation points (Note: measures drive-test observation separation, not tower mast spacing).",
                            a.pci, distM / 1000.0
                        )
                    }

                    incrementClassification(classification)

                    reuseEvents.add(
                        PciReuseEvent(
                            pci = a.pci,
                            earfcn = a.earfcn ?: b.earfcn ?: a.channelNumber,
                            cellA = a,
                            cellB = b,
                            separationDistanceM = distM,
                            riskLevel = risk,
                            classification = classification,
                            diagnosticDescription = desc
                        )
                    )
                }
            }
        }

        // Group PCI reuse by RAT and Channel (Output A)
        val reuseByRatAndChannel = reuseEvents.groupBy { "${it.cellA.rat}_${it.cellA.channelNumber ?: it.earfcn ?: 0}" }

        // 2. Detect CRS Modulo-3 Collisions (Output B: STRICTLY LTE ONLY)
        // In LTE, Cell-specific Reference Signal (CRS) subcarrier shift is v_shift = PCI mod 3 (or mod 6).
        // Applied ONLY to LTE (never GSM or 5G NR).
        val crsCollisions = mutableListOf<CrsCollisionWarning>()
        val lteSignatures = cellSignatures.filter { (it.rat.contains("4G", ignoreCase = true) || it.rat.contains("LTE", ignoreCase = true)) && it.earfcn != null }
        val byEarfcn = lteSignatures.groupBy { it.earfcn!! }

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

                        if (distM < 3000.0) {
                            val deltaRsrp = kotlin.math.abs(c1.avgRsrpDbm - c2.avgRsrpDbm)
                            val hasDegradedQuality = (c1.minSinrDb != null && c1.minSinrDb < 3.0) ||
                                    (c2.minSinrDb != null && c2.minSinrDb < 3.0) ||
                                    (c1.avgRsrqDb != null && c1.avgRsrqDb < -14.0)
                            val isCorroborated = deltaRsrp <= 6.0 && hasDegradedQuality

                            val severity = when {
                                isCorroborated -> "HIGH INTERFERENCE CONCERN"
                                distM < 1500.0 -> "MODERATE POTENTIAL"
                                else -> "CRS ALIGNMENT (LOW RISK)"
                            }

                            val reason = if (isCorroborated) {
                                String.format(
                                    Locale.US,
                                    "PCI %d and PCI %d on EARFCN %d share v_shift = PCI mod 3 = %d (%.0fm observation separation, Delta %.1f dB). CRS subcarriers collide with corroborated SINR/RSRQ degradation.",
                                    c1.pci, c2.pci, earfcn, mod3, distM, deltaRsrp
                                )
                            } else {
                                String.format(
                                    Locale.US,
                                    "PCI %d and PCI %d on EARFCN %d share v_shift = PCI mod 3 = %d (%.0fm observation separation). CRS reference symbols align in frequency domain; adequate serving dominance or unmeasured channel degradation.",
                                    c1.pci, c2.pci, earfcn, mod3, distM
                                )
                            }

                            crsCollisions.add(
                                CrsCollisionWarning(
                                    earfcn = earfcn,
                                    pciA = c1.pci,
                                    pciB = c2.pci,
                                    mod3Value = mod3,
                                    severity = severity,
                                    technicalReason = reason,
                                    isCorroboratedInterference = isCorroborated
                                )
                            )
                        }
                    }
                }
            }
        }

        // 3. Compute Theoretical Hexagonal Reuse Geometries (Output E: D = R * sqrt(3N))
        val theoreticalGeometries = mutableListOf<TheoreticalReuseGeometry>()
        val validRadiusByChannel = radiusEstimates.filter { it.status == "VALID_ESTIMATE" && it.estimatedRadiusM != null && it.channel != null }
            .groupBy { "${it.rat}_${it.channel}" }

        for ((_, cellEstimates) in validRadiusByChannel) {
            val avgR = cellEstimates.mapNotNull { it.estimatedRadiusM }.average()
            val rat = cellEstimates.first().rat
            val chan = cellEstimates.first().channel!!

            for (n in listOf(3, 7)) {
                val d = avgR * sqrt(3.0 * n)
                theoreticalGeometries.add(
                    TheoreticalReuseGeometry(
                        rat = rat,
                        channel = chan,
                        clusterSizeN = n,
                        averageCellRadiusM = avgR,
                        theoreticalReuseDistanceM = d
                    )
                )
            }
        }

        val uniquePcis = valid.mapNotNull { it.servingPci }.distinct().size
        val uniqueEarfcns = valid.mapNotNull { it.earfcn ?: it.channelNumber }.distinct()
        val activeBands = uniqueEarfcns.mapNotNull { LteBandTable.resolveEarfcn(it)?.bandNumber }.distinct()
        val sameFreqCount = byEarfcn.mapValues { it.value.size }

        return FrequencyReuseSummary(
            totalUniquePcis = uniquePcis,
            totalUniqueEarfcn = uniqueEarfcns.size,
            activeBands = activeBands,
            sameFrequencyCellsCount = sameFreqCount,
            reuseEvents = reuseEvents,
            crsCollisions = crsCollisions,
            hasSevereCollisions = reuseEvents.any { it.riskLevel == ReuseRiskLevel.HIGH_RISK_COLLISION } || crsCollisions.any { it.isCorroboratedInterference },
            classificationCounts = classificationMap,
            reuseByRatAndChannel = reuseByRatAndChannel,
            cellRadiusEstimates = radiusEstimates,
            theoreticalGeometries = theoreticalGeometries
        )
    }

    fun isAdjacentChannel(earfcn1: Int, earfcn2: Int): Boolean {
        if (earfcn1 == earfcn2) return false
        val b1 = LteBandTable.resolveEarfcn(earfcn1) ?: return false
        val b2 = LteBandTable.resolveEarfcn(earfcn2) ?: return false
        if (b1.bandNumber != b2.bandNumber) return false
        val diffMhz = kotlin.math.abs(b1.dlFrequencyMhz - b2.dlFrequencyMhz)
        return diffMhz in 1.4..20.0
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
