package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import java.util.Locale
import kotlin.math.*

enum class CoverageHoleSeverity(val label: String, val colorHex: String) {
    CRITICAL_DROPOUT("Critical Dropout (Outage Risk)", "#D50000"),
    SEVERE_DEFICIT("Severe Coverage Deficit", "#FF6D00"),
    MODERATE_SHADOWING("Moderate Shadowing / RF Edge", "#FFD600")
}

enum class HoleDataProvenance(val label: String) {
    MEASURED("Measured Route Observations"),
    INTERPOLATED("Spatially Interpolated Surface"),
    MODEL_PREDICTED("Empirical Model Prediction")
}

data class CoverageHoleRecord(
    val id: Int,
    val centerLat: Double,
    val centerLon: Double,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val corridorLengthMeters: Double,
    val estimatedAreaKm2: Double,
    val minRsrpDbm: Int,
    val avgRsrpDbm: Double,
    val avgRsrqDb: Double?,
    val sampleCount: Int,
    val supportingPcis: List<Int>,
    val severity: CoverageHoleSeverity,
    val confidenceScore: Float, // 0.0 .. 1.0
    val provenance: HoleDataProvenance,
    val isConfirmedOutage: Boolean,
    val engineeringEvidence: String,
    val stableZoneId: String = "ZONE-H$id",
    val localityHint: String = "Route Corridor (${String.format(Locale.US, "%.4f, %.4f", centerLat, centerLon)})"
) {
    val meanRsrpDbm: Double get() = avgRsrpDbm
}

object CoverageHoleEstimator {

    /**
     * Identifies poor-coverage corridors and spatial deficit areas.
     * Enforces strict distinction between weak coverage (< -105 dBm) and complete service outage (< -115 dBm or no service).
     */
    fun estimateCoverageHoles(
        measurements: List<LteMeasurementEntity>,
        transitions: List<CellTransitionEntity> = emptyList(),
        rsrpThresholdDbm: Int = -105,
        rsrqThresholdDb: Int = -16,
        minSamplesRequired: Int = 3
    ): List<CoverageHoleRecord> {
        val validSamples = measurements.filter {
            it.latitude != null && it.longitude != null && it.isGpsValid && it.rsrpDbm != null
        }.sortedBy { it.timestampMs }

        if (validSamples.isEmpty()) return emptyList()

        val clusters = mutableListOf<MutableList<LteMeasurementEntity>>()
        var currentCluster = mutableListOf<LteMeasurementEntity>()

        for (m in validSamples) {
            val rsrp = m.rsrpDbm!!
            val rsrq = m.rsrqDb
            val isWeak = rsrp <= rsrpThresholdDbm || (rsrq != null && rsrq <= rsrqThresholdDb)

            if (isWeak) {
                currentCluster.add(m)
            } else {
                if (currentCluster.size >= minSamplesRequired) {
                    clusters.add(currentCluster)
                }
                currentCluster = mutableListOf()
            }
        }
        if (currentCluster.size >= minSamplesRequired) {
            clusters.add(currentCluster)
        }

        val holeRecords = mutableListOf<CoverageHoleRecord>()
        var holeId = 1

        for (cluster in clusters) {
            val lats = cluster.mapNotNull { it.latitude }
            val lons = cluster.mapNotNull { it.longitude }
            val rsrps = cluster.mapNotNull { it.rsrpDbm }
            val rsrqs = cluster.mapNotNull { it.rsrqDb }

            val minLat = lats.minOrNull() ?: continue
            val maxLat = lats.maxOrNull() ?: continue
            val minLon = lons.minOrNull() ?: continue
            val maxLon = lons.maxOrNull() ?: continue
            val centerLat = lats.average()
            val centerLon = lons.average()

            // Calculate corridor length along the track
            var corridorLength = 0.0
            for (i in 1 until cluster.size) {
                corridorLength += calculateHaversineMeters(
                    cluster[i - 1].latitude!!, cluster[i - 1].longitude!!,
                    cluster[i].latitude!!, cluster[i].longitude!!
                )
            }

            // Estimate area: buffer corridor by ~30m on each side
            val corridorWidthM = 60.0
            val estimatedAreaKm2 = (corridorLength * corridorWidthM) / 1_000_000.0

            val minRsrp = rsrps.minOrNull() ?: -120
            val avgRsrp = rsrps.average()
            val avgRsrq = if (rsrqs.isNotEmpty()) rsrqs.average() else null
            val pcis = cluster.mapNotNull { it.servingPci }.distinct()

            // Determine if confirmed complete service loss vs weak signal
            val isCompleteOutage = minRsrp <= -115 || cluster.any { it.serviceState == "OUT_OF_SERVICE" }

            val severity = when {
                isCompleteOutage || avgRsrp <= -112.0 -> CoverageHoleSeverity.CRITICAL_DROPOUT
                corridorLength >= 200.0 || avgRsrp <= -107.0 -> CoverageHoleSeverity.SEVERE_DEFICIT
                else -> CoverageHoleSeverity.MODERATE_SHADOWING
            }

            // Confidence based on sample count and GPS accuracy
            val avgGpsAccuracy = cluster.mapNotNull { it.accuracyM }.average()
            val gpsQualityFactor = if (avgGpsAccuracy > 0) (1.0 - (avgGpsAccuracy / 50.0)).coerceIn(0.5, 1.0) else 0.8
            val densityFactor = (cluster.size / 10.0).coerceIn(0.5, 1.0)
            val confidence = (gpsQualityFactor * 0.6 + densityFactor * 0.4).toFloat().coerceIn(0.3f, 0.98f)

            // Check nearby transitions within 200m
            val nearbyTransitions = transitions.count { t ->
                if (t.latitude != null && t.longitude != null) {
                    calculateHaversineMeters(centerLat, centerLon, t.latitude, t.longitude) <= 250.0
                } else false
            }

            val evidence = buildString {
                append("Samples: ${cluster.size} • Min RSRP: $minRsrp dBm (Mean: ${String.format(Locale.US, "%.1f", avgRsrp)} dBm) • ")
                append("Corridor: ${corridorLength.toInt()}m (~${String.format(Locale.US, "%.3f", estimatedAreaKm2)} km²) • ")
                append("PCIs: ${pcis.joinToString(", ")}")
                if (nearbyTransitions > 0) append(" • $nearbyTransitions transition(s) observed")
                if (isCompleteOutage) append(" • COMPLETE RADIO LOSS OBSERVED")
            }

            holeRecords.add(
                CoverageHoleRecord(
                    id = holeId++,
                    centerLat = centerLat,
                    centerLon = centerLon,
                    minLat = minLat,
                    maxLat = maxLat,
                    minLon = minLon,
                    maxLon = maxLon,
                    corridorLengthMeters = corridorLength,
                    estimatedAreaKm2 = estimatedAreaKm2,
                    minRsrpDbm = minRsrp,
                    avgRsrpDbm = avgRsrp,
                    avgRsrqDb = avgRsrq,
                    sampleCount = cluster.size,
                    supportingPcis = pcis,
                    severity = severity,
                    confidenceScore = confidence,
                    provenance = HoleDataProvenance.MEASURED,
                    isConfirmedOutage = isCompleteOutage,
                    engineeringEvidence = evidence,
                    localityHint = resolveLocalityHint(centerLat, centerLon)
                )
            )
        }

        return holeRecords
    }

    fun resolveLocalityHint(lat: Double, lon: Double): String {
        return when {
            lat in 23.65..23.95 && lon in 90.30..90.55 -> "Dhaka Urban Corridor"
            lat in 22.25..22.45 && lon in 91.75..91.90 -> "Chittagong Coastal Corridor"
            lat in 24.30..24.45 && lon in 88.55..88.70 -> "Rajshahi Corridor"
            lat in 24.85..25.00 && lon in 91.80..91.95 -> "Sylhet Corridor"
            lat in 22.75..22.90 && lon in 89.50..89.65 -> "Khulna Corridor"
            lat in 20.5..26.7 && lon in 88.0..92.7 -> "Bangladesh Survey Route"
            else -> "Route Corridor (${String.format(Locale.US, "%.4f, %.4f", lat, lon)})"
        }
    }

    private fun calculateHaversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
