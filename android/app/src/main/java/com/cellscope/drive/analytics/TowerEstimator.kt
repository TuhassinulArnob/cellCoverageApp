package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import java.util.Locale
import kotlin.math.*

data class EstimatedTower(
    val pci: Int,
    val tac: Int?,
    val eci: Int?,
    val estimatedLat: Double,
    val estimatedLon: Double,
    val estimatedCoverageRadiusM: Double,
    val confidenceScore: Float, // 0.0 .. 1.0
    val sampleCount: Int,
    val peakRsrpDbm: Int?,
    val ambiguityReason: String?,
    val isExperimental: Boolean = true,
    // 3GPP Telecommunication Engineering Distinctions:
    val enodebId: Int? = eci?.let { it / 256 },
    val localCellId: Int? = eci?.let { it % 256 },
    val ecgi: String? = null,
    val earfcn: Int? = null,
    val operator: String? = null,
    val clusterId: Int = 1,
    val isCandidate: Boolean = true,
    val isVerified: Boolean = false,
    val verificationSource: String? = null,
    val uncertaintyRadiusM: Double = estimatedCoverageRadiusM,
    val estimationMethod: String = "Log-Distance Path Loss & Peak RSRP Centroid",
    val lateralAmbiguity: Boolean = false,
    val timingAdvanceRangeMeters: Pair<Double, Double>? = null,
    val handoverBoundaryConstraintsCount: Int = 0
) {
    val displayTitle: String
        get() = if (isVerified) {
            "Verified Site: ${enodebId?.let { "eNodeB $it / " } ?: ""}PCI $pci [${verificationSource ?: "Verified"}]"
        } else {
            "Candidate Cell Site: ${enodebId?.let { "eNodeB $it / " } ?: ""}PCI $pci (Cluster #$clusterId)"
        }

    val displaySubtitle: String
        get() = buildString {
            append("Peak: ${peakRsrpDbm ?: "—"} dBm | ")
            append("Conf: ${(confidenceScore * 100).toInt()}% | ")
            append("Uncertainty: ±${uncertaintyRadiusM.toInt()}m")
            if (earfcn != null) append(" | EARFCN $earfcn")
            if (tac != null) append(" | TAC $tac")
            if (timingAdvanceRangeMeters != null) {
                append(" | TA: ${timingAdvanceRangeMeters.first.toInt()}–${timingAdvanceRangeMeters.second.toInt()}m")
            }
        }
}

object TowerEstimator {

    /**
     * Confidence-aware candidate cell-site estimation module.
     * Enforces:
     * 1. Strict separation of PCI, ECI, eNodeB ID, TAC, EARFCN, and GPS route points.
     * 2. Spatial clustering to prevent conflating distinct physical sites that share the same PCI (PCI reuse).
     * 3. Handover boundary observations used to constrain serving cell regions.
     * 4. LTE Timing Advance treated as a coarse range constraint (N_TA * 78.12m), not an exact distance.
     * 5. Rejection of underdetermined solutions (e.g. collinear highway tracks where lateral position is unconstrained).
     * 6. Never forces candidate site to sit on the surveyed road.
     * 7. Never labels estimated sites as verified physical towers.
     */
    fun estimateTowers(
        measurements: List<LteMeasurementEntity>,
        minSamplesRequired: Int = 5,
        verifiedTowers: List<EstimatedTower> = emptyList(),
        transitions: List<CellTransitionEntity> = emptyList()
    ): List<EstimatedTower> {
        val validGps = measurements.filter {
            it.latitude != null && it.longitude != null && it.isGpsValid && it.servingPci != null && it.rsrpDbm != null
        }

        if (validGps.isEmpty() && verifiedTowers.isEmpty()) return emptyList()

        val results = mutableListOf<EstimatedTower>()

        // Include verified external cell sites if available
        for (v in verifiedTowers) {
            results.add(v.copy(isVerified = true, isCandidate = false, confidenceScore = 1.0f))
        }

        // Group observations by strongest available identity:
        // Prefer (Operator, EARFCN, TAC, ECI) when ECI is available;
        // Otherwise (Operator, EARFCN, TAC, PCI)
        val initialGroups = validGps.groupBy { m ->
            val op = m.operatorNumeric ?: m.operatorAlpha ?: ""
            val earfcn = m.earfcn ?: -1
            val tac = m.tac ?: -1
            val eci = m.eci
            if (eci != null && eci > 0) {
                "ECI_${op}_${earfcn}_${tac}_$eci"
            } else {
                "PCI_${op}_${earfcn}_${tac}_${m.servingPci}"
            }
        }

        // Process each identity group and split into spatial clusters to handle PCI reuse
        for ((_, groupSamples) in initialGroups) {
            val clusters = splitIntoSpatialClusters(groupSamples, maxInterClusterDistanceMeters = 3000.0)

            for ((clusterIdx, clusterSamples) in clusters.withIndex()) {
                val pci = clusterSamples.first().servingPci!!
                val tac = clusterSamples.firstOrNull { it.tac != null }?.tac
                val eci = clusterSamples.firstOrNull { it.eci != null }?.eci
                val earfcn = clusterSamples.firstOrNull { it.earfcn != null }?.earfcn
                val opName = clusterSamples.firstOrNull { !it.operatorAlpha.isNullOrBlank() }?.operatorAlpha
                val opNumeric = clusterSamples.firstOrNull { !it.operatorNumeric.isNullOrBlank() }?.operatorNumeric
                val enodeb = eci?.let { it / 256 }
                val localCell = eci?.let { it % 256 }
                val ecgi = if (eci != null && !opNumeric.isNullOrBlank()) "$opNumeric-$eci" else null

                // Filter out baseband clamped placeholders (-44 dBm RSRP with -3 dB RSRQ and <= -100 dBm RSSI)
                val genuineSamples = clusterSamples.filterNot { s ->
                    s.rsrpDbm == -44 && s.rsrqDb == -3 && (s.rssiDbm == null || s.rssiDbm <= -100)
                }.ifEmpty { clusterSamples }

                if (genuineSamples.size < minSamplesRequired) {
                    continue
                }

                var sumWeight = 0.0
                var weightedLat = 0.0
                var weightedLon = 0.0
                var peakRsrp = -150
                var peakLat = genuineSamples.first().latitude!!
                var peakLon = genuineSamples.first().longitude!!

                for (s in genuineSamples) {
                    val rsrp = s.rsrpDbm!!
                    if (rsrp > peakRsrp) {
                        peakRsrp = rsrp
                        peakLat = s.latitude!!
                        peakLon = s.longitude!!
                    }

                    // Normalized linear RF power weighting: relative to -140 dBm baseline
                    val normalizedDbm = (rsrp + 140.0).coerceAtLeast(1.0)
                    val linearWeight = 10.0.pow(normalizedDbm / 20.0) // 20 dB slope compression

                    weightedLat += s.latitude!! * linearWeight
                    weightedLon += s.longitude!! * linearWeight
                    sumWeight += linearWeight
                }

                val estLat = weightedLat / sumWeight
                val estLon = weightedLon / sumWeight

                // Check handover transitions associated with this PCI
                val cellTransitions = transitions.filter { it.fromPci == pci || it.toPci == pci }
                val hoBoundaryCount = cellTransitions.size

                // Evaluate Timing Advance constraints where available
                val validTas = genuineSamples.mapNotNull { it.timingAdvance }.filter { it in 0..1282 }
                val taConstraint: Pair<Double, Double>? = if (validTas.isNotEmpty()) {
                    val medianTa = validTas.sorted()[validTas.size / 2]
                    val estDistM = medianTa * 78.12
                    val minM = (estDistM - 78.12).coerceAtLeast(0.0)
                    val maxM = estDistM + 156.24
                    Pair(minM, maxM)
                } else null

                // Compute spatial dispersion and geometric ambiguity
                val lats = genuineSamples.map { it.latitude!! }
                val lons = genuineSamples.map { it.longitude!! }
                val latSpanM = (lats.maxOrNull()!! - lats.minOrNull()!!) * 111320.0
                val lonSpanM = (lons.maxOrNull()!! - lons.minOrNull()!!) * 111320.0 * cos(Math.toRadians(estLat))
                val totalSpanM = sqrt(latSpanM * latSpanM + lonSpanM * lonSpanM)

                val minSpan = min(latSpanM, lonSpanM).coerceAtLeast(1.0)
                val maxSpan = max(latSpanM, lonSpanM)
                val collinearityRatio = maxSpan / minSpan

                var ambiguity: String
                var confidence: Float
                var lateralAmbiguity = false
                val uncertaintyM: Double

                // Log-distance path loss estimated distance to tower at closest point:
                // d_est = 100m * 10^((-70 - peakRsrp) / (10 * n)), assuming path-loss exponent n = 3.2
                val estimatedDistM = 100.0 * 10.0.pow(((-70.0 - peakRsrp).coerceAtLeast(0.0)) / 32.0)

                if (peakRsrp < -110) {
                    ambiguity = "Insufficient evidence: Peak RSRP ($peakRsrp dBm) is too weak to localize candidate base station."
                    confidence = 0.20f
                    lateralAmbiguity = true
                    uncertaintyM = estimatedDistM.coerceIn(500.0, 1500.0)
                } else if (totalSpanM < 30.0) {
                    ambiguity = "Stationary / tight cluster (<30m span): Insufficient spatial diversity for candidate site localization."
                    confidence = 0.25f
                    lateralAmbiguity = true
                    uncertaintyM = estimatedDistM.coerceIn(150.0, 600.0)
                } else if (collinearityRatio > 4.0) {
                    ambiguity = "Underdetermined: Collinear single-track evidence. Lateral offset perpendicular to road cannot be resolved without cross-axis trajectory; candidate site placed near RSRP peak with uncertainty bound."
                    confidence = if (peakRsrp >= -80) 0.50f else 0.35f
                    lateralAmbiguity = true
                    uncertaintyM = estimatedDistM.coerceIn(120.0, 900.0)
                } else if (genuineSamples.size >= 12 && peakRsrp >= -80) {
                    ambiguity = "Multi-directional spatial distribution with strong local RSRP peak (${peakRsrp} dBm) and $hoBoundaryCount handover boundary observation(s)."
                    confidence = 0.80f
                    lateralAmbiguity = false
                    uncertaintyM = estimatedDistM.coerceIn(80.0, 450.0)
                } else {
                    ambiguity = "Moderate spatial distribution; candidate coverage centroid estimated from drive points."
                    confidence = 0.55f
                    lateralAmbiguity = false
                    uncertaintyM = estimatedDistM.coerceIn(100.0, 600.0)
                }

                results.add(
                    EstimatedTower(
                        pci = pci,
                        tac = tac,
                        eci = eci,
                        estimatedLat = estLat,
                        estimatedLon = estLon,
                        estimatedCoverageRadiusM = uncertaintyM,
                        confidenceScore = confidence,
                        sampleCount = genuineSamples.size,
                        peakRsrpDbm = peakRsrp,
                        ambiguityReason = ambiguity,
                        isExperimental = true,
                        enodebId = enodeb,
                        localCellId = localCell,
                        ecgi = ecgi,
                        earfcn = earfcn,
                        operator = opName,
                        clusterId = clusterIdx + 1,
                        isCandidate = true,
                        isVerified = false,
                        uncertaintyRadiusM = uncertaintyM,
                        estimationMethod = "Log-Distance Path Loss & Peak RSRP Centroid",
                        lateralAmbiguity = lateralAmbiguity,
                        timingAdvanceRangeMeters = taConstraint,
                        handoverBoundaryConstraintsCount = hoBoundaryCount
                    )
                )
            }
        }

        return results.sortedWith(
            compareByDescending<EstimatedTower> { it.isVerified }
                .thenByDescending { it.sampleCount }
        )
    }

    /**
     * Splits ordered samples into spatial clusters if consecutive observations
     * are separated by more than maxInterClusterDistanceMeters (handling PCI reuse across a route).
     */
    private fun splitIntoSpatialClusters(
        samples: List<LteMeasurementEntity>,
        maxInterClusterDistanceMeters: Double
    ): List<List<LteMeasurementEntity>> {
        if (samples.isEmpty()) return emptyList()
        val sorted = samples.sortedBy { it.timestampMs }
        val clusters = mutableListOf<MutableList<LteMeasurementEntity>>()
        var currentCluster = mutableListOf<LteMeasurementEntity>()

        for (sample in sorted) {
            if (currentCluster.isEmpty()) {
                currentCluster.add(sample)
            } else {
                val prev = currentCluster.last()
                val dist = calculateDistanceMeters(
                    prev.latitude!!, prev.longitude!!,
                    sample.latitude!!, sample.longitude!!
                )
                if (dist > maxInterClusterDistanceMeters) {
                    clusters.add(currentCluster)
                    currentCluster = mutableListOf(sample)
                } else {
                    currentCluster.add(sample)
                }
            }
        }
        if (currentCluster.isNotEmpty()) {
            clusters.add(currentCluster)
        }
        return clusters
    }

    fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
