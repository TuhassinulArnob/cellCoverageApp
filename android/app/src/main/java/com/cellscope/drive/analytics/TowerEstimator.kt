package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.LteMeasurementEntity
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
    val isExperimental: Boolean = true
)

object TowerEstimator {

    /**
     * Experimental tower estimation based on RF Power-Weighted Centroid and Spatial Gradient Analysis
     */
    fun estimateTowers(
        measurements: List<LteMeasurementEntity>,
        minSamplesRequired: Int = 5
    ): List<EstimatedTower> {
        val validGps = measurements.filter {
            it.latitude != null && it.longitude != null && it.isGpsValid && it.servingPci != null && it.rsrpDbm != null
        }

        if (validGps.isEmpty()) return emptyList()

        // Group observations by PCI
        val groupedByPci = validGps.groupBy { it.servingPci!! }
        val estimatedTowers = mutableListOf<EstimatedTower>()

        for ((pci, samples) in groupedByPci) {
            if (samples.size < minSamplesRequired) {
                // Insufficient observations for meaningful spatial inference
                continue
            }

            // Power-weighted centroid (Linear mW conversion: 10^(RSRP/10))
            var sumWeight = 0.0
            var weightedLat = 0.0
            var weightedLon = 0.0
            var peakRsrp = -150
            var peakLat = samples.first().latitude!!
            var peakLon = samples.first().longitude!!

            for (s in samples) {
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

            // Compute spatial dispersion and geometric ambiguity
            val distances = samples.map { s ->
                calculateDistanceMeters(estLat, estLon, s.latitude!!, s.longitude!!)
            }
            val avgDistance = distances.average()
            val maxDistance = distances.maxOrNull() ?: 100.0

            // Check collinearity: compute bounding box aspect ratio
            val lats = samples.map { it.latitude!! }
            val lons = samples.map { it.longitude!! }
            val latSpan = (lats.maxOrNull()!! - lats.minOrNull()!!) * 111320.0
            val lonSpan = (lons.maxOrNull()!! - lons.minOrNull()!!) * 111320.0 * cos(Math.toRadians(estLat))

            var ambiguity: String? = null
            var confidence = 0.5f

            if (latSpan < 20.0 && lonSpan < 20.0) {
                ambiguity = "Stationary / tight cluster: insufficient spatial diversity for trilateration"
                confidence = 0.25f
            } else if (latSpan / (lonSpan + 1.0) > 8.0 || lonSpan / (latSpan + 1.0) > 8.0) {
                ambiguity = "High collinearity: drive route was linear, causing perpendicular position ambiguity"
                confidence = 0.35f
            } else if (samples.size >= 15 && peakRsrp >= -85) {
                ambiguity = "Good multi-angle coverage with strong local RSRP peak (${peakRsrp} dBm)"
                confidence = 0.75f
            } else {
                ambiguity = "Moderate spatial distribution; candidate coverage centroid estimated"
                confidence = 0.55f
            }

            val tac = samples.firstOrNull { it.tac != null }?.tac
            val eci = samples.firstOrNull { it.eci != null }?.eci

            estimatedTowers.add(
                EstimatedTower(
                    pci = pci,
                    tac = tac,
                    eci = eci,
                    estimatedLat = estLat,
                    estimatedLon = estLon,
                    estimatedCoverageRadiusM = maxDistance.coerceIn(150.0, 3000.0),
                    confidenceScore = confidence,
                    sampleCount = samples.size,
                    peakRsrpDbm = peakRsrp,
                    ambiguityReason = ambiguity,
                    isExperimental = true
                )
            )
        }

        return estimatedTowers.sortedByDescending { it.sampleCount }
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
