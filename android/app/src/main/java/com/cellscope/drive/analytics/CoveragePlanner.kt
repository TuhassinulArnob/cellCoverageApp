package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import java.util.Locale
import kotlin.math.*

data class WeakZone(
    val id: Int,
    val centerLat: Double,
    val centerLon: Double,
    val lengthMeters: Double,
    val sampleCount: Int,
    val avgRsrp: Double,
    val minRsrp: Int,
    val avgRsrq: Double?,
    val servingPcis: List<Int>,
    val nearbyTransitionsCount: Int,
    val diagnosis: String,
    val remediationOptions: List<RemediationHypothesis>
)

data class RemediationHypothesis(
    val category: String, // ANTENNA_OPTIMIZATION, SMALL_CELL, REPEATER, PASSIVE_REFLECTOR
    val title: String,
    val feasibilityScore: Float, // 0.0 .. 1.0
    val rationale: String,
    val requiredVerification: String
)

object CoveragePlanner {

    fun identifyWeakZones(
        measurements: List<LteMeasurementEntity>,
        transitions: List<CellTransitionEntity> = emptyList(),
        rsrpThresholdDbm: Int = -105,
        rsrqThresholdDb: Int = -16
    ): List<WeakZone> {
        val validSamples = measurements.filter {
            it.latitude != null && it.longitude != null && it.isGpsValid && it.rsrpDbm != null
        }

        if (validSamples.isEmpty()) return emptyList()

        val weakClusters = mutableListOf<MutableList<LteMeasurementEntity>>()
        var currentCluster = mutableListOf<LteMeasurementEntity>()

        for (m in validSamples) {
            val isWeak = m.rsrpDbm!! <= rsrpThresholdDbm || (m.rsrqDb != null && m.rsrqDb <= rsrqThresholdDb)
            if (isWeak) {
                currentCluster.add(m)
            } else {
                if (currentCluster.size >= 3) {
                    weakClusters.add(currentCluster)
                }
                currentCluster = mutableListOf()
            }
        }
        if (currentCluster.size >= 3) {
            weakClusters.add(currentCluster)
        }

        val weakZones = mutableListOf<WeakZone>()
        var zoneIndex = 1

        for (cluster in weakClusters) {
            val centerLat = cluster.map { it.latitude!! }.average()
            val centerLon = cluster.map { it.longitude!! }.average()

            // Calculate length of weak corridor
            val first = cluster.first()
            val last = cluster.last()
            val corridorLength = calculateDistanceMeters(
                first.latitude!!, first.longitude!!,
                last.latitude!!, last.longitude!!
            )

            val avgRsrp = cluster.map { it.rsrpDbm!! }.average()
            val minRsrp = cluster.minOf { it.rsrpDbm!! }
            val rsrqs = cluster.mapNotNull { it.rsrqDb }
            val avgRsrq = if (rsrqs.isNotEmpty()) rsrqs.average() else null
            val pcis = cluster.mapNotNull { it.servingPci }.distinct()

            // Check nearby transitions within 200m
            val nearbyTransitions = transitions.count { t ->
                if (t.latitude != null && t.longitude != null) {
                    calculateDistanceMeters(centerLat, centerLon, t.latitude, t.longitude) <= 250.0
                } else false
            }

            // Diagnose root cause
            val diagnosis: String
            val remediations = mutableListOf<RemediationHypothesis>()

            if (avgRsrq != null && avgRsrq < -16.0 && avgRsrp >= -100.0) {
                diagnosis = "Pilot Pollution & Inter-Cell Interference: Acceptable power (${String.format(Locale.US, "%.1f", avgRsrp)} dBm) but degraded RSRQ (${String.format(Locale.US, "%.1f", avgRsrq)} dB), indicating conflicting neighbor cells."
                remediations.add(
                    RemediationHypothesis(
                        category = "ANTENNA_OPTIMIZATION",
                        title = "Electrical Downtilt & Azimuth Re-tuning",
                        feasibilityScore = 0.85f,
                        rationale = "Increasing downtilt by 2°-3° on interfering dominant cells will confine RF energy to intended cell footprint and improve CINR.",
                        requiredVerification = "Verify sector antenna RET (Remote Electrical Tilt) parameters and neighbor cell interference matrices."
                    )
                )
            } else if (avgRsrp < -115.0) {
                diagnosis = "Severe Deep Coverage Hole: Heavy signal degradation (< -115 dBm) likely caused by distance, building penetration loss, or terrain blockage."
                remediations.add(
                    RemediationHypothesis(
                        category = "SMALL_CELL",
                        title = "Micro Small-Cell Deployment",
                        feasibilityScore = 0.80f,
                        rationale = "A low-power micro outdoor node or pole-mounted small cell will eliminate coverage gap in this ${String.format(Locale.US, "%.0f", corridorLength)}m corridor.",
                        requiredVerification = "Confirm fiber/microwave backhaul and street-pole power availability at candidate location."
                    )
                )
                remediations.add(
                    RemediationHypothesis(
                        category = "REPEATER",
                        title = "RF Optical/Off-Air Repeater Feasibility",
                        feasibilityScore = 0.65f,
                        rationale = "If donor site has line-of-sight on nearby building rooftop, a directional donor antenna can feed a coverage antenna below.",
                        requiredVerification = "Conduct donor signal survey to ensure donor RSRP > -85 dBm and isolation > 15 dB above repeater gain."
                    )
                )
            } else {
                diagnosis = "Moderate Shadowing & Cell Edge: Signal fading near serving boundary (${pcis.joinToString { "PCI $it" }})."
                remediations.add(
                    RemediationHypothesis(
                        category = "ANTENNA_OPTIMIZATION",
                        title = "Sector Power Boost / Tilt Refinement",
                        feasibilityScore = 0.75f,
                        rationale = "Adjusting RS (Reference Signal) power allocation or mechanical tilt may extend effective coverage radius into this edge zone.",
                        requiredVerification = "Examine cell traffic load and adjacent sector overshoot risks."
                    )
                )
                remediations.add(
                    RemediationHypothesis(
                        category = "PASSIVE_REFLECTOR",
                        title = "Passive Billboard/Panel RF Reflector Check",
                        feasibilityScore = 0.40f,
                        rationale = "For sharp building corner blockage, a metallic reflector panel can bend line-of-sight RF energy into shadow zone.",
                        requiredVerification = "Perform Fresnel zone clearance calculation and verify bi-static reflection loss at operating EARFCN."
                    )
                )
            }

            weakZones.add(
                WeakZone(
                    id = zoneIndex++,
                    centerLat = centerLat,
                    centerLon = centerLon,
                    lengthMeters = corridorLength.coerceAtLeast(25.0),
                    sampleCount = cluster.size,
                    avgRsrp = avgRsrp,
                    minRsrp = minRsrp,
                    avgRsrq = avgRsrq,
                    servingPcis = pcis,
                    nearbyTransitionsCount = nearbyTransitions,
                    diagnosis = diagnosis,
                    remediationOptions = remediations
                )
            )
        }

        return weakZones
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
