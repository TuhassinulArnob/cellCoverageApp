package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.LteMeasurementEntity
import kotlin.math.*

data class CoverageCell(
    val lat: Double,
    val lon: Double,
    val interpolatedRsrp: Double?,
    val interpolatedRsrq: Double?,
    val interpolatedRssi: Double?,
    val interpolatedSinr: Double?,
    val confidenceScore: Float, // 0.0 .. 1.0
    val dominantPci: Int?,
    val sampleCount: Int,
    val isDirectlyMeasured: Boolean
) {
    val rsrpRating: String
        get() = when {
            interpolatedRsrp == null -> "NO DATA"
            interpolatedRsrp >= -80.0 -> "EXCELLENT"
            interpolatedRsrp >= -90.0 -> "GOOD"
            interpolatedRsrp >= -100.0 -> "FAIR"
            else -> "POOR"
        }
}

data class CoverageBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
)

data class CoverageSurface(
    val cells: List<CoverageCell>,
    val bounds: CoverageBounds,
    val resolutionDegrees: Double,
    val avgRsrp: Double?,
    val weakCoveragePercent: Float,
    val totalMeasuredAreaKm2: Double
)

object CoverageGridEngine {

    /**
     * Compute a spatial coverage surface using Motion-Aware Inverse Distance Weighting (IDW)
     */
    fun computeCoverageSurface(
        measurements: List<LteMeasurementEntity>,
        gridResolutionMeters: Double = 35.0,
        maxInterpolationRadiusMeters: Double = 120.0
    ): CoverageSurface {
        val validGps = measurements.filter {
            it.latitude != null && it.longitude != null && it.isGpsValid
        }

        if (validGps.isEmpty()) {
            val emptyBounds = CoverageBounds(0.0, 0.0, 0.0, 0.0)
            return CoverageSurface(
                cells = emptyList(),
                bounds = emptyBounds,
                resolutionDegrees = 0.0,
                avgRsrp = null,
                weakCoveragePercent = 0f,
                totalMeasuredAreaKm2 = 0.0
            )
        }

        var minLat = validGps.minOf { it.latitude!! }
        var maxLat = validGps.maxOf { it.latitude!! }
        var minLon = validGps.minOf { it.longitude!! }
        var maxLon = validGps.maxOf { it.longitude!! }

        // Pad bounds slightly to give border context
        val padDegrees = (maxInterpolationRadiusMeters / 111320.0)
        minLat -= padDegrees
        maxLat += padDegrees
        val lonPad = padDegrees / cos(Math.toRadians((minLat + maxLat) / 2.0)).coerceAtLeast(0.1)
        minLon -= lonPad
        maxLon += lonPad

        // Grid resolution in degrees (1 degree lat ~= 111,320m)
        val latStep = gridResolutionMeters / 111320.0
        val centerLatRad = Math.toRadians((minLat + maxLat) / 2.0)
        val lonStep = gridResolutionMeters / (111320.0 * cos(centerLatRad).coerceAtLeast(0.1))

        // Precompute Cartesian projected points for fast distance calculation
        val points = validGps.map { m ->
            val lat = m.latitude!!
            val lon = m.longitude!!
            val rsrp = m.rsrpDbm?.toDouble()
            val rsrq = m.rsrqDb?.toDouble()
            val rssi = m.rssiDbm?.toDouble()
            val sinr = m.sinrDb
            val pci = m.servingPci
            val bearing = m.bearing ?: 0f
            val speed = m.speedKmh ?: 0f
            Triple(lat, lon, m)
        }

        val gridCells = mutableListOf<CoverageCell>()
        var totalRsrpSum = 0.0
        var totalRsrpCount = 0
        var weakCount = 0

        var curLat = minLat
        while (curLat <= maxLat) {
            var curLon = minLon
            while (curLon <= maxLon) {
                // Find observations within search radius
                var weightedRsrp = 0.0
                var weightedRsrq = 0.0
                var weightedRssi = 0.0
                var weightedSinr = 0.0
                var weightSumRsrp = 0.0
                var weightSumRsrq = 0.0
                var weightSumRssi = 0.0
                var weightSumSinr = 0.0

                var minDistanceM = Double.MAX_VALUE
                var localSampleCount = 0
                val pciCounts = mutableMapOf<Int, Int>()

                for ((pLat, pLon, m) in points) {
                    val distM = calculateDistanceMeters(curLat, curLon, pLat, pLon)
                    if (distM < minDistanceM) {
                        minDistanceM = distM
                    }

                    if (distM <= maxInterpolationRadiusMeters) {
                        localSampleCount++
                        if (m.servingPci != null) {
                            pciCounts[m.servingPci] = (pciCounts[m.servingPci] ?: 0) + 1
                        }

                        // IDW: Weight = 1 / (distance^2 + eps)
                        // Motion-aware modifier: if moving fast along a direction, favor corridor
                        var w = 1.0 / (distM.coerceAtLeast(5.0) * distM.coerceAtLeast(5.0))

                        // Directional weighting
                        if (m.bearing != null && (m.speedKmh ?: 0f) > 10.0f) {
                            val targetBearing = calculateBearing(pLat, pLon, curLat, curLon)
                            val diff = abs(m.bearing - targetBearing)
                            val alignment = cos(Math.toRadians(diff.toDouble()))
                            if (alignment > 0) {
                                w *= (1.0 + 0.3 * alignment) // Slightly extend along corridor
                            }
                        }

                        if (m.rsrpDbm != null) {
                            weightedRsrp += m.rsrpDbm * w
                            weightSumRsrp += w
                        }
                        if (m.rsrqDb != null) {
                            weightedRsrq += m.rsrqDb * w
                            weightSumRsrq += w
                        }
                        if (m.rssiDbm != null) {
                            weightedRssi += m.rssiDbm * w
                            weightSumRssi += w
                        }
                        if (m.sinrDb != null) {
                            weightedSinr += m.sinrDb * w
                            weightSumSinr += w
                        }
                    }
                }

                // Only include cell if within max interpolation radius
                if (minDistanceM <= maxInterpolationRadiusMeters && weightSumRsrp > 0) {
                    val finalRsrp = weightedRsrp / weightSumRsrp
                    val finalRsrq = if (weightSumRsrq > 0) weightedRsrq / weightSumRsrq else null
                    val finalRssi = if (weightSumRssi > 0) weightedRssi / weightSumRssi else null
                    val finalSinr = if (weightSumSinr > 0) weightedSinr / weightSumSinr else null

                    // Confidence score based on proximity and sample density
                    val proximityFactor = exp(-minDistanceM / (maxInterpolationRadiusMeters * 0.5)).toFloat()
                    val densityFactor = min(1.0f, localSampleCount / 4.0f)
                    val confidence = (proximityFactor * 0.7f + densityFactor * 0.3f).coerceIn(0.0f, 1.0f)

                    val dominantPci = pciCounts.maxByOrNull { it.value }?.key
                    val isDirect = minDistanceM <= (gridResolutionMeters * 0.7)

                    val cell = CoverageCell(
                        lat = curLat,
                        lon = curLon,
                        interpolatedRsrp = finalRsrp,
                        interpolatedRsrq = finalRsrq,
                        interpolatedRssi = finalRssi,
                        interpolatedSinr = finalSinr,
                        confidenceScore = confidence,
                        dominantPci = dominantPci,
                        sampleCount = localSampleCount,
                        isDirectlyMeasured = isDirect
                    )
                    gridCells.add(cell)

                    totalRsrpSum += finalRsrp
                    totalRsrpCount++
                    if (finalRsrp < -105.0) {
                        weakCount++
                    }
                }

                curLon += lonStep
            }
            curLat += latStep
        }

        val avgRsrp = if (totalRsrpCount > 0) totalRsrpSum / totalRsrpCount else null
        val weakRatio = if (totalRsrpCount > 0) (weakCount.toFloat() / totalRsrpCount) * 100f else 0f
        val cellAreaKm2 = (gridResolutionMeters * gridResolutionMeters) / 1_000_000.0
        val totalAreaKm2 = gridCells.size * cellAreaKm2

        return CoverageSurface(
            cells = gridCells,
            bounds = CoverageBounds(minLat, maxLat, minLon, maxLon),
            resolutionDegrees = latStep,
            avgRsrp = avgRsrp,
            weakCoveragePercent = weakRatio,
            totalMeasuredAreaKm2 = totalAreaKm2
        )
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        val brng = Math.toDegrees(atan2(y, x))
        return ((brng + 360) % 360).toFloat()
    }
}
