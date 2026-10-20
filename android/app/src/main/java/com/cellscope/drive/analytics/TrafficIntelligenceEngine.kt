package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.util.TelecomTimeUtils
import java.util.Locale
import kotlin.math.*

object TrafficIntelligenceEngine {

    enum class RoutePatternClassification(val label: String, val description: String) {
        STATIONARY_OR_LOCALIZED(
            "Stationary / Micro-Cluster",
            "Survey conducted at a fixed or highly localized point (< 200m extent). Valid for local event planning and time-of-day quality observation."
        ),
        LOCALIZED_REPEATED_ROUTE(
            "Localized Repeated Circuit",
            "Survey covers multiple laps or passes within a compact area (< 2.5 km extent). Time-of-day variations reflect local conditions."
        ),
        BROAD_DRIVING_ROUTE(
            "Broad Driving Transit Route",
            "Survey traverses a broad driving corridor (> 2.5 km extent) crossing multiple base stations. Telemetry represents spatial progression along the road, NOT stationary diurnal load variation."
        ),
        MIXED_OR_INSUFFICIENT(
            "Mixed / Insufficient Data",
            "Telemetry sample density or geographic spread is insufficient to classify route pattern."
        )
    }

    data class TimeBinRecord(
        val binLabel: String,
        val startMs: Long,
        val endMs: Long,
        val sampleCount: Int,
        val medianRsrp: Double?,
        val p25Rsrp: Double?,
        val p75Rsrp: Double?,
        val medianRsrq: Double?,
        val medianSinr: Double?,
        val meanDownloadSpeedMbps: Double?,
        val meanLatencyMs: Double?,
        val isThroughputMeasured: Boolean,
        val metricCategoryLabel: String
    )

    data class SpatialCluster(
        val clusterId: Int,
        val centerLat: Double,
        val centerLon: Double,
        val sampleCount: Int,
        val dominantPci: Int?,
        val dominantRat: String,
        val timeBins: List<TimeBinRecord>
    )

    data class TrafficFeasibilityAssessment(
        val totalSamples: Int,
        val validGpsSamples: Int,
        val timeSpanMinutes: Double,
        val routeExtentKm: Double,
        val hasActiveTests: Boolean,
        val activeTestCount: Int,
        val isFeasibleForTrafficAnalysis: Boolean,
        val feasibilityLevel: String,
        val feasibilitySummary: String,
        val missingPrerequisites: List<String>
    )

    data class TrafficIntelligenceSummary(
        val routeClassification: RoutePatternClassification,
        val routeExtentMeters: Double,
        val totalPathDistanceMeters: Double,
        val tortuosityRatio: Double,
        val spatialClusters: List<SpatialCluster>,
        val overallTimeBins: List<TimeBinRecord>,
        val trafficInterpretationNotice: String,
        val feasibilityAssessment: TrafficFeasibilityAssessment
    )

    /**
     * Analyzes session telemetry to categorize route spatial distribution and generate
     * honest time-based radio quality variation or traffic trends without zero-filling missing hours.
     */
    fun analyzeTrafficIntelligence(
        measurements: List<LteMeasurementEntity>,
        binMinutes: Int = 60,
        activeTests: List<ActiveQoeTestEntity> = emptyList()
    ): TrafficIntelligenceSummary {
        val valid = measurements.filter { it.isGpsValid && it.latitude != null && it.longitude != null }
        if (valid.size < 5) {
            val emptyFeasibility = TrafficFeasibilityAssessment(
                totalSamples = measurements.size,
                validGpsSamples = valid.size,
                timeSpanMinutes = 0.0,
                routeExtentKm = 0.0,
                hasActiveTests = activeTests.isNotEmpty(),
                activeTestCount = activeTests.size,
                isFeasibleForTrafficAnalysis = false,
                feasibilityLevel = "INSUFFICIENT_DATA",
                feasibilitySummary = "Insufficient GPS-validated telemetry samples to analyze traffic variation.",
                missingPrerequisites = listOf("Minimum 5 GPS-validated telemetry samples", "Continuous timestamp sequence")
            )
            return TrafficIntelligenceSummary(
                routeClassification = RoutePatternClassification.MIXED_OR_INSUFFICIENT,
                routeExtentMeters = 0.0,
                totalPathDistanceMeters = 0.0,
                tortuosityRatio = 1.0,
                spatialClusters = emptyList(),
                overallTimeBins = emptyList(),
                trafficInterpretationNotice = "Insufficient GPS-validated telemetry samples to analyze traffic variation.",
                feasibilityAssessment = emptyFeasibility
            )
        }

        // 1. Calculate Route Spatial Extent & Tortuosity
        val lats = valid.mapNotNull { it.latitude }
        val lons = valid.mapNotNull { it.longitude }
        val minLat = lats.minOrNull() ?: 0.0
        val maxLat = lats.maxOrNull() ?: 0.0
        val minLon = lons.minOrNull() ?: 0.0
        val maxLon = lons.maxOrNull() ?: 0.0

        val extentMeters = calculateHaversineM(minLat, minLon, maxLat, maxLon)

        var totalDistMeters = 0.0
        for (i in 1 until valid.size) {
            val p1 = valid[i - 1]
            val p2 = valid[i]
            totalDistMeters += calculateHaversineM(p1.latitude!!, p1.longitude!!, p2.latitude!!, p2.longitude!!)
        }

        val tortuosity = if (extentMeters > 50.0) totalDistMeters / extentMeters else 1.0

        val routeClass = when {
            extentMeters < 300.0 || totalDistMeters < 600.0 -> RoutePatternClassification.STATIONARY_OR_LOCALIZED
            extentMeters < 2500.0 && tortuosity >= 2.0 -> RoutePatternClassification.LOCALIZED_REPEATED_ROUTE
            extentMeters >= 2500.0 && totalDistMeters >= 3000.0 -> RoutePatternClassification.BROAD_DRIVING_ROUTE
            else -> RoutePatternClassification.MIXED_OR_INSUFFICIENT
        }

        val notice = when (routeClass) {
            RoutePatternClassification.BROAD_DRIVING_ROUTE ->
                "CAUTION: Broad Driving Route detected (${String.format(Locale.US, "%.1f km", extentMeters / 1000.0)} extent). Changes over time primarily reflect different geographic locations and cell towers visited along the road, NOT stationary load variation over time. Do NOT conflate route progression into a single-location diurnal traffic curve."
            RoutePatternClassification.LOCALIZED_REPEATED_ROUTE ->
                "Localized Repeated Route (${String.format(Locale.US, "%.1f km", extentMeters / 1000.0)} extent). Point-to-point comparisons within this localized cluster illustrate radio condition consistency across drive passes."
            RoutePatternClassification.STATIONARY_OR_LOCALIZED ->
                "Stationary / Micro-Cluster (< 300m extent). Variations across time bins reflect actual temporal RF fluctuations and cell load changes at this specific venue."
            RoutePatternClassification.MIXED_OR_INSUFFICIENT ->
                "Mixed trajectory spread. Evaluate time bins in conjunction with spatial cluster centroids."
        }

        // 2. Spatial Clustering (~1 km radius)
        val clusters = buildSpatialClusters(valid, binMinutes, activeTests)

        // 3. Overall Time-Binning (Never zero-fill missing hours!)
        val overallBins = computeTimeBins(valid, binMinutes, activeTests)

        // 4. Feasibility Assessment (Step 5 of P6)
        val minTime = valid.minOf { it.timestampMs }
        val maxTime = valid.maxOf { it.timestampMs }
        val timeSpanMin = (maxTime - minTime) / 60000.0
        val extentKm = extentMeters / 1000.0

        val feasibilityAssessment = when {
            routeClass == RoutePatternClassification.BROAD_DRIVING_ROUTE -> {
                TrafficFeasibilityAssessment(
                    totalSamples = measurements.size,
                    validGpsSamples = valid.size,
                    timeSpanMinutes = timeSpanMin,
                    routeExtentKm = extentKm,
                    hasActiveTests = activeTests.isNotEmpty(),
                    activeTestCount = activeTests.size,
                    isFeasibleForTrafficAnalysis = false,
                    feasibilityLevel = "BROAD_TRANSIT_UNSUITABLE",
                    feasibilitySummary = "Survey traversed a broad transit route (${String.format(Locale.US, "%.1f km", extentKm)}). Time variations represent geographic transit across different base stations along the road, not stationary diurnal traffic load.",
                    missingPrerequisites = listOf(
                        "Stationary or localized repeated route survey (< 2.5 km)",
                        "Active throughput / latency measurements",
                        "Operator eNodeB PRB scheduler utilization counters"
                    )
                )
            }
            activeTests.isEmpty() -> {
                TrafficFeasibilityAssessment(
                    totalSamples = measurements.size,
                    validGpsSamples = valid.size,
                    timeSpanMinutes = timeSpanMin,
                    routeExtentKm = extentKm,
                    hasActiveTests = false,
                    activeTestCount = 0,
                    isFeasibleForTrafficAnalysis = false,
                    feasibilityLevel = "RF_VARIATION_ONLY",
                    feasibilitySummary = "Stationary/localized dataset permits temporal RF quality tracking (RSRP/RSRQ variability). Conclusive network traffic congestion analysis requires active throughput/latency telemetry or operator PRB counters.",
                    missingPrerequisites = listOf(
                        "Active download/upload throughput tests",
                        "Round-trip latency / probe-loss telemetry",
                        "Operator eNodeB PRB scheduler counters"
                    )
                )
            }
            else -> {
                TrafficFeasibilityAssessment(
                    totalSamples = measurements.size,
                    validGpsSamples = valid.size,
                    timeSpanMinutes = timeSpanMin,
                    routeExtentKm = extentKm,
                    hasActiveTests = true,
                    activeTestCount = activeTests.size,
                    isFeasibleForTrafficAnalysis = true,
                    feasibilityLevel = "FULL_TRAFFIC_ANALYSIS",
                    feasibilitySummary = "Comprehensive local performance and traffic trend analysis enabled with ${activeTests.size} active throughput/latency measurements.",
                    missingPrerequisites = emptyList()
                )
            }
        }

        return TrafficIntelligenceSummary(
            routeClassification = routeClass,
            routeExtentMeters = extentMeters,
            totalPathDistanceMeters = totalDistMeters,
            tortuosityRatio = Math.round(tortuosity * 10.0) / 10.0,
            spatialClusters = clusters,
            overallTimeBins = overallBins,
            trafficInterpretationNotice = notice,
            feasibilityAssessment = feasibilityAssessment
        )
    }

    private fun buildSpatialClusters(
        samples: List<LteMeasurementEntity>,
        binMinutes: Int,
        activeTests: List<ActiveQoeTestEntity> = emptyList()
    ): List<SpatialCluster> {
        val clusters = mutableListOf<SpatialCluster>()
        val unassigned = samples.toMutableList()
        var clusterIdCounter = 1

        while (unassigned.isNotEmpty() && clusters.size < 10) {
            val seed = unassigned.removeAt(0)
            val seedLat = seed.latitude ?: continue
            val seedLon = seed.longitude ?: continue

            val clusterMembers = mutableListOf(seed)
            val it = unassigned.iterator()
            while (it.hasNext()) {
                val candidate = it.next()
                if (candidate.latitude != null && candidate.longitude != null) {
                    val dist = calculateHaversineM(seedLat, seedLon, candidate.latitude!!, candidate.longitude!!)
                    if (dist <= 1000.0) {
                        clusterMembers.add(candidate)
                        it.remove()
                    }
                }
            }

            if (clusterMembers.size >= 3) {
                val avgLat = clusterMembers.mapNotNull { it.latitude }.average()
                val avgLon = clusterMembers.mapNotNull { it.longitude }.average()
                val dominantPci = clusterMembers.groupBy { it.servingPci }.maxByOrNull { it.value.size }?.key
                val dominantRat = clusterMembers.groupBy { it.servingRat ?: it.networkGen ?: it.networkType }
                    .maxByOrNull { it.value.size }?.key ?: "LTE"

                val clusterMinTime = clusterMembers.minOf { it.timestampMs }
                val clusterMaxTime = clusterMembers.maxOf { it.timestampMs }
                val relevantActiveTests = activeTests.filter { it.timestampMs in clusterMinTime..clusterMaxTime }

                val bins = computeTimeBins(clusterMembers, binMinutes, relevantActiveTests)

                clusters.add(
                    SpatialCluster(
                        clusterId = clusterIdCounter++,
                        centerLat = Math.round(avgLat * 100000.0) / 100000.0,
                        centerLon = Math.round(avgLon * 100000.0) / 100000.0,
                        sampleCount = clusterMembers.size,
                        dominantPci = dominantPci,
                        dominantRat = dominantRat,
                        timeBins = bins
                    )
                )
            }
        }

        return clusters
    }

    fun computeTimeBins(
        samples: List<LteMeasurementEntity>,
        binMinutes: Int,
        activeTests: List<ActiveQoeTestEntity> = emptyList()
    ): List<TimeBinRecord> {
        if (samples.isEmpty()) return emptyList()

        val binMs = (binMinutes * 60 * 1000L).coerceAtLeast(60000L)
        val minTime = samples.minOf { it.timestampMs }

        // Group into actual non-empty bins (NO zero-filling missing hours)
        val groupedByBin = samples.groupBy { (it.timestampMs - minTime) / binMs }

        val result = mutableListOf<TimeBinRecord>()
        for ((binIdx, binSamples) in groupedByBin.toSortedMap()) {
            val startMs = minTime + binIdx * binMs
            val endMs = startMs + binMs
            val label = "${TelecomTimeUtils.formatBdtTimeOnly(startMs)} – ${TelecomTimeUtils.formatBdtTimeOnly(endMs)} BDT"

            val lteOnly = binSamples.filter {
                val rat = it.servingRat ?: it.networkGen ?: it.networkType
                !rat.contains("2G", ignoreCase = true) && !rat.contains("GSM", ignoreCase = true) &&
                !rat.contains("3G", ignoreCase = true) && !rat.contains("WCDMA", ignoreCase = true)
            }

            val rsrps = lteOnly.mapNotNull { it.rsrpDbm?.toDouble() }.sorted()
            val rsrqs = lteOnly.mapNotNull { it.rsrqDb?.toDouble() }.sorted()
            val sinrs = lteOnly.mapNotNull { it.sinrDb }.sorted()

            val medianRsrp = rsrps.getPercentile(0.50)
            val p25Rsrp = rsrps.getPercentile(0.25)
            val p75Rsrp = rsrps.getPercentile(0.75)
            val medianRsrq = rsrqs.getPercentile(0.50)
            val medianSinr = sinrs.getPercentile(0.50)

            // Active tests within this time bin
            val matchingTests = activeTests.filter { it.timestampMs in startMs until endMs }
            val downloadSpeeds = matchingTests.mapNotNull { it.downloadSpeedMbps }
            val latencies = matchingTests.mapNotNull { it.latencyMs?.toDouble() }

            val meanDl = if (downloadSpeeds.isNotEmpty()) downloadSpeeds.average() else null
            val meanLat = if (latencies.isNotEmpty()) latencies.average() else null
            val hasThroughput = meanDl != null

            val category = if (hasThroughput) "Measured Performance & Radio Quality Trend" else "Radio Quality Variation (Throughput Unmeasured)"

            result.add(
                TimeBinRecord(
                    binLabel = label,
                    startMs = startMs,
                    endMs = endMs,
                    sampleCount = binSamples.size,
                    medianRsrp = medianRsrp?.let { Math.round(it * 10.0) / 10.0 },
                    p25Rsrp = p25Rsrp?.let { Math.round(it * 10.0) / 10.0 },
                    p75Rsrp = p75Rsrp?.let { Math.round(it * 10.0) / 10.0 },
                    medianRsrq = medianRsrq?.let { Math.round(it * 10.0) / 10.0 },
                    medianSinr = medianSinr?.let { Math.round(it * 10.0) / 10.0 },
                    meanDownloadSpeedMbps = meanDl?.let { Math.round(it * 10.0) / 10.0 },
                    meanLatencyMs = meanLat?.let { Math.round(it * 10.0) / 10.0 },
                    isThroughputMeasured = hasThroughput,
                    metricCategoryLabel = category
                )
            )
        }

        return result
    }

    private fun List<Double>.getPercentile(p: Double): Double? {
        if (isEmpty()) return null
        val idx = ((size - 1) * p).toInt().coerceIn(0, size - 1)
        return this[idx]
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
