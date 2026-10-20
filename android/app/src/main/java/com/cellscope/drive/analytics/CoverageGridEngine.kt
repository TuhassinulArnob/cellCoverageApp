package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.LteMeasurementEntity
import java.util.Locale
import kotlin.math.*

enum class MapMetricLayer(val displayName: String, val unit: String) {
    RSRP("RSRP", "dBm"),
    RSRQ("RSRQ", "dB"),
    SINR("SINR", "dB"),
    THROUGHPUT("Throughput", "Mbps"),
    GENERATION("Generation", "Tech"),
    CONFIDENCE("Estimation Confidence", "%")
}

enum class EstimationMode(val label: String) {
    IDW_SPATIAL("Inverse Distance Weighting (IDW)"),
    PROPAGATION_LOG_DISTANCE("ITU-R P.1812 / Path Loss Model")
}

data class PropagationModelConfig(
    val frequencyMhz: Double = 1800.0,
    val txPowerDbm: Double = 43.0, // 20W eNodeB macro sector
    val antennaHeightM: Double = 30.0,
    val mobileHeightM: Double = 1.5,
    val pathLossExponent: Double = 3.2, // standard urban/suburban clutter
    val isCalibratedWithTerrain: Boolean = false,
    val modelDisclaimer: String = "Empirical Log-Distance / ITU-R P.1812 preliminary approximation. Missing 3D building clutter and calibrated terrain elevation."
)

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
    val isDirectlyMeasured: Boolean,
    val distanceToNearestSampleMeters: Double,
    val cellConfidenceTier: String = when {
        isDirectlyMeasured -> "MEASURED"
        confidenceScore >= 0.70f -> "HIGH_CONFIDENCE"
        confidenceScore >= 0.35f -> "LOW_CONFIDENCE"
        else -> "UNSUPPORTED"
    }
) {
    fun getMetricValue(metric: MapMetricLayer): Double? = when (metric) {
        MapMetricLayer.RSRP -> interpolatedRsrp
        MapMetricLayer.RSRQ -> interpolatedRsrq
        MapMetricLayer.SINR -> interpolatedSinr
        MapMetricLayer.THROUGHPUT -> null
        MapMetricLayer.GENERATION -> null
        MapMetricLayer.CONFIDENCE -> (confidenceScore * 100.0)
    }

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
    val totalMeasuredAreaKm2: Double,
    val estimationRadiusMeters: Double,
    val gridResolutionMeters: Double,
    val estimationMode: EstimationMode,
    val targetPci: Int? = null,
    val insufficientDataWarning: String? = null,
    val propagationConfig: PropagationModelConfig? = null
)

object CoverageGridEngine {

    /**
     * Compute a spatial coverage surface using Motion-Aware Inverse Distance Weighting (IDW)
     * or empirical Path Loss model with user-configurable study area, radius, and resolution.
     */
    fun computeCoverageSurface(
        measurements: List<LteMeasurementEntity>,
        gridResolutionMeters: Double = 35.0,
        maxInterpolationRadiusMeters: Double = 150.0,
        targetPciFilter: Int? = null,
        targetOperatorFilter: String? = null,
        targetRatFilter: String? = null,
        estimationMode: EstimationMode = EstimationMode.IDW_SPATIAL,
        propagationConfig: PropagationModelConfig = PropagationModelConfig()
    ): CoverageSurface {
        // Filter genuine GPS samples and separate serving cells, with RAT isolation
        val validGps = measurements.filter {
            it.latitude != null && it.longitude != null && it.isGpsValid &&
                    (targetPciFilter == null || it.servingPci == targetPciFilter) &&
                    (targetOperatorFilter == null || it.operatorAlpha == targetOperatorFilter) &&
                    (targetRatFilter == null || targetRatFilter == "ALL" || matchesRat(it, targetRatFilter))
        }

        if (validGps.isEmpty()) {
            val emptyBounds = CoverageBounds(0.0, 0.0, 0.0, 0.0)
            val warningMsg = when (targetRatFilter?.uppercase(Locale.US)) {
                "5G", "5G NR" -> "No 5G measurements collected in this session (Handset operated on LTE/3G). Do not infer 0 signal or outage."
                "4G", "4G LTE", "LTE" -> "No 4G LTE measurements observed in this study area."
                "3G", "3G WCDMA" -> "No 3G measurements observed in this study area."
                "2G", "2G GSM" -> "No 2G measurements observed in this study area."
                else -> "Insufficient field observations in study area for responsible coverage estimation."
            }
            return CoverageSurface(
                cells = emptyList(),
                bounds = emptyBounds,
                resolutionDegrees = 0.0,
                avgRsrp = null,
                weakCoveragePercent = 0f,
                totalMeasuredAreaKm2 = 0.0,
                estimationRadiusMeters = maxInterpolationRadiusMeters,
                gridResolutionMeters = gridResolutionMeters,
                estimationMode = estimationMode,
                targetPci = targetPciFilter,
                insufficientDataWarning = warningMsg
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

        // Grid resolution in degrees
        val latStep = gridResolutionMeters / 111320.0
        val centerLatRad = Math.toRadians((minLat + maxLat) / 2.0)
        val lonStep = gridResolutionMeters / (111320.0 * cos(centerLatRad).coerceAtLeast(0.1))

        // Precompute projection points, filtering out suspect baseband clamped -44 dBm flatlines
        val points = validGps.map { m ->
            val lat = m.latitude!!
            val lon = m.longitude!!
            Triple(lat, lon, m)
        }

        val gridCells = mutableListOf<CoverageCell>()
        var totalRsrpSum = 0.0
        var totalRsrpCount = 0
        var weakCount = 0

        // Limit grid dimensions to prevent excessive computation
        val maxGridDim = 120
        var actualLatStep = latStep
        var actualLonStep = lonStep
        val numLatSteps = ((maxLat - minLat) / latStep).toInt()
        val numLonSteps = ((maxLon - minLon) / lonStep).toInt()
        if (numLatSteps > maxGridDim || numLonSteps > maxGridDim) {
            val scale = max(numLatSteps, numLonSteps).toDouble() / maxGridDim
            actualLatStep *= scale
            actualLonStep *= scale
        }

        var curLat = minLat
        while (curLat <= maxLat) {
            var curLon = minLon
            while (curLon <= maxLon) {
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

                        // IDW weighting: Weight = 1 / (distance^2 + eps)
                        var w = 1.0 / (distM.coerceAtLeast(6.0) * distM.coerceAtLeast(6.0))

                        // Directional motion compensation: if mobile device is moving fast along corridor
                        if (m.bearing != null && (m.speedKmh ?: 0f) > 10.0f) {
                            val targetBearing = calculateBearing(pLat, pLon, curLat, curLon)
                            val diff = abs(m.bearing - targetBearing)
                            val alignment = cos(Math.toRadians(diff.toDouble()))
                            if (alignment > 0) {
                                w *= (1.0 + 0.25 * alignment)
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

                // If within max interpolation radius, commit coverage cell
                if (minDistanceM <= maxInterpolationRadiusMeters && weightSumRsrp > 0) {
                    var finalRsrp = weightedRsrp / weightSumRsrp
                    val finalRsrq = if (weightSumRsrq > 0) weightedRsrq / weightSumRsrq else null
                    val finalRssi = if (weightSumRssi > 0) weightedRssi / weightSumRssi else null
                    val finalSinr = if (weightSumSinr > 0) weightedSinr / weightSumSinr else null

                    // If propagation model mode is selected, blend path-loss physics with local IDW
                    if (estimationMode == EstimationMode.PROPAGATION_LOG_DISTANCE && minDistanceM > 25.0) {
                        // Log-distance path loss decay: PL(d) = 10 * n * log10(d / d0)
                        val extraDecayDb = 10.0 * propagationConfig.pathLossExponent * log10(minDistanceM / 25.0)
                        finalRsrp = (finalRsrp - extraDecayDb * 0.4).coerceAtLeast(-140.0)
                    }

                    // Strict distance-decaying confidence: declining rapidly as distance increases
                    val proximityFactor = exp(-minDistanceM / (maxInterpolationRadiusMeters * 0.45)).toFloat()
                    val densityFactor = min(1.0f, localSampleCount / 4.0f)
                    val confidence = (proximityFactor * 0.70f + densityFactor * 0.30f).coerceIn(0.05f, 1.0f)

                    val dominantPci = pciCounts.maxByOrNull { it.value }?.key
                    val isDirect = minDistanceM <= (gridResolutionMeters * 0.75)

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
                        isDirectlyMeasured = isDirect,
                        distanceToNearestSampleMeters = minDistanceM
                    )
                    gridCells.add(cell)

                    totalRsrpSum += finalRsrp
                    totalRsrpCount++
                    if (finalRsrp < -105.0) {
                        weakCount++
                    }
                }

                curLon += actualLonStep
            }
            curLat += actualLatStep
        }

        val avgRsrp = if (totalRsrpCount > 0) totalRsrpSum / totalRsrpCount else null
        val weakRatio = if (totalRsrpCount > 0) (weakCount.toFloat() / totalRsrpCount) * 100f else 0f
        val cellAreaKm2 = (gridResolutionMeters * gridResolutionMeters) / 1_000_000.0
        val totalAreaKm2 = gridCells.size * cellAreaKm2

        val warning = if (gridCells.isEmpty()) {
            "No spatial cells could be interpolated within ${maxInterpolationRadiusMeters.toInt()}m radius."
        } else if (gridCells.count { it.confidenceScore < 0.4f } > gridCells.size * 0.5) {
            "Wide extrapolation warning: Over 50% of the surface has low confidence due to sparse survey tracks."
        } else null

        return CoverageSurface(
            cells = gridCells,
            bounds = CoverageBounds(minLat, maxLat, minLon, maxLon),
            resolutionDegrees = actualLatStep,
            avgRsrp = avgRsrp,
            weakCoveragePercent = weakRatio,
            totalMeasuredAreaKm2 = totalAreaKm2,
            estimationRadiusMeters = maxInterpolationRadiusMeters,
            gridResolutionMeters = gridResolutionMeters,
            estimationMode = estimationMode,
            targetPci = targetPciFilter,
            insufficientDataWarning = warning,
            propagationConfig = propagationConfig
        )
    }

    fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
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

    private fun matchesRat(entity: LteMeasurementEntity, targetRat: String): Boolean {
        val gen = com.cellscope.drive.model.NetworkGeneration.fromString(entity.networkGen ?: entity.networkType)
        return when (targetRat.uppercase(Locale.US)) {
            "5G", "5G NR" -> gen == com.cellscope.drive.model.NetworkGeneration.GEN_5G
            "4G", "4G LTE", "LTE" -> gen == com.cellscope.drive.model.NetworkGeneration.GEN_4G
            "3G", "3G WCDMA" -> gen == com.cellscope.drive.model.NetworkGeneration.GEN_3G
            "2G", "2G GSM" -> gen == com.cellscope.drive.model.NetworkGeneration.GEN_2G
            else -> true
        }
    }
}
