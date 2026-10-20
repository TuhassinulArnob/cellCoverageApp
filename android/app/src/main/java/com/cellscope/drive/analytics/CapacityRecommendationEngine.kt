package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.data.entity.LteMeasurementEntity
import java.util.Locale

enum class RecommendationCategory(val displayName: String) {
    CELL_SPLITTING_SMALL_CELL("Small-Cell / Micro-Sector Evaluation"),
    ADDITIONAL_SPECTRUM_BANDWIDTH("Additional Bandwidth / Carrier Expansion"),
    NEW_BASE_STATION("New Base Station / Coverage Extension"),
    ANTENNA_SECTOR_OPTIMIZATION("Antenna & Sector RF Optimization"),
    INTERFERENCE_INVESTIGATION("Interference Investigation & Frequency Re-tuning")
}

data class EngineeringRecommendation(
    val category: RecommendationCategory,
    val title: String,
    val priorityRank: Int, // 1 (Highest) .. 5 (Lowest)
    val confidenceScore: Float, // 0.0 .. 1.0
    val targetCellOrCorridor: String,
    val rationale: String,
    val evidenceUsed: List<String>,
    val missingEvidenceDisclosed: List<String>,
    val alternativeExplanations: List<String>,
    val recommendedFieldActions: List<String>
)

data class SiteSuitabilityFactor(
    val factorName: String,
    val weight: Float,
    val score: Float, // 0.0 .. 100.0
    val evidence: String
)

data class SiteSuitabilityAssessment(
    val overallSuitabilityScore: Float, // 0.0 .. 100.0
    val confidenceGrade: String, // PRELIMINARY_HANDSET_DERIVED, MEDIUM, HIGH_OPERATOR_VERIFIED
    val factors: List<SiteSuitabilityFactor>,
    val missingEnvironmentalInputs: List<String>,
    val suitabilityDisclaimer: String
)

data class CapacityPlanningReport(
    val recommendations: List<EngineeringRecommendation>,
    val siteSuitability: SiteSuitabilityAssessment,
    val summaryNote: String
)

object CapacityRecommendationEngine {

    /**
     * Generates tentative, evidence-based RF capacity and coverage recommendations.
     * Never claims optimal base station placement without complete RF environmental data.
     */
    fun evaluateRecommendations(
        session: DriveSession,
        measurements: List<LteMeasurementEntity>,
        transitions: List<CellTransitionEntity>,
        congestionReport: CongestionAnalysisReport? = null,
        coverageHoles: List<CoverageHoleRecord> = emptyList()
    ): CapacityPlanningReport {
        val recommendations = mutableListOf<EngineeringRecommendation>()

        val validMeasurements = measurements.filter { it.rsrpDbm != null }
        val rsrpList = validMeasurements.mapNotNull { it.rsrpDbm }
        val sinrList = validMeasurements.mapNotNull { it.sinrDb }
        val rsrqList = validMeasurements.mapNotNull { it.rsrqDb }

        val avgRsrp = if (rsrpList.isNotEmpty()) rsrpList.average() else -100.0
        val avgSinr = if (sinrList.isNotEmpty()) sinrList.average() else 0.0
        val avgRsrq = if (rsrqList.isNotEmpty()) rsrqList.average() else -15.0

        val weakCount = rsrpList.count { it < -105 }
        val weakPct = if (rsrpList.isNotEmpty()) weakCount * 100.0 / rsrpList.size else 0.0
        val dropoutCount = rsrpList.count { it < -115 }

        val pciDwell = validMeasurements.mapNotNull { it.servingPci }.groupBy { it }
        val dominantPci = pciDwell.maxByOrNull { it.value.size }?.key

        val isCongested = congestionReport?.confidenceLevel == CongestionConfidenceLevel.STRONG_CONGESTION_EVIDENCE ||
                congestionReport?.confidenceLevel == CongestionConfidenceLevel.CONGESTION_SUSPECTED
        val hasSevereHoles = coverageHoles.any { it.severity == CoverageHoleSeverity.CRITICAL_DROPOUT || it.corridorLengthMeters >= 300.0 }
        val hasPilotPollution = validMeasurements.count { (it.rsrpDbm ?: -150) >= -95 && ((it.sinrDb ?: 5.0) < 0.0 || (it.rsrqDb ?: 0) < -15) } >= 5

        // Recommendation 1: New Base Station or Coverage Extension
        if (weakPct >= 20.0 || hasSevereHoles || dropoutCount >= 10) {
            val holeLengthM = coverageHoles.sumOf { it.corridorLengthMeters }
            recommendations.add(
                EngineeringRecommendation(
                    category = RecommendationCategory.NEW_BASE_STATION,
                    title = "Evaluate Macro Base Station / Outdoor Repeater Extension",
                    priorityRank = 1,
                    confidenceScore = 0.72f,
                    targetCellOrCorridor = "Corridor with ${String.format(Locale.US, "%.1f%%", weakPct)} weak coverage (${holeLengthM.toInt()}m affected)",
                    rationale = "Drive data demonstrates persistent coverage deficit (< -105 dBm) exceeding nominal link margin along this route.",
                    evidenceUsed = listOf(
                        "Weak signal observations: $weakCount samples (${String.format(Locale.US, "%.1f%%", weakPct)})",
                        "Deep signal drops (< -115 dBm): $dropoutCount samples",
                        "Identified coverage holes: ${coverageHoles.size} discrete corridors"
                    ),
                    missingEvidenceDisclosed = listOf(
                        "High-resolution Digital Terrain Elevation Data (DTED/DEM)",
                        "3D building clutter and morphology datasets",
                        "Existing operator tower heights, azimuths, and legal leasehold access",
                        "Local backhaul transmission feasibility (LOS microwave / fiber duct)"
                    ),
                    alternativeExplanations = listOf(
                        "Terrain shadowing from local topography rather than tower absence",
                        "Heavy indoor or vehicular penetration loss"
                    ),
                    recommendedFieldActions = listOf(
                        "Perform CW (Continuous Wave) propagation test from candidate tall structure",
                        "Evaluate line-of-sight backhaul to nearest core transmission node"
                    )
                )
            )
        }

        // Recommendation 2: Small-Cell / Micro-Sector Candidate (Engineering evaluation only; no automated cell splitting)
        if (isCongested && avgRsrp >= -92.0) {
            recommendations.add(
                EngineeringRecommendation(
                    category = RecommendationCategory.CELL_SPLITTING_SMALL_CELL,
                    title = "Evaluate Small-Cell / Micro-Sector at Traffic Hotspot",
                    priorityRank = 2,
                    confidenceScore = 0.68f,
                    targetCellOrCorridor = "Serving PCI $dominantPci (Dense traffic corridor)",
                    rationale = "Repeated capacity throttling observed under strong RF signal (Mean RSRP: ${String.format(Locale.US, "%.1f dBm", avgRsrp)}), indicating localized user concentration. Candidate option for engineering evaluation; no automated cell splitting performed or declared mandatory.",
                    evidenceUsed = listOf(
                        "Strong received RF power: Mean RSRP ${String.format(Locale.US, "%.1f dBm", avgRsrp)}",
                        "Throughput bottleneck: Avg DL ${congestionReport?.avgDownloadMbps?.let { String.format(Locale.US, "%.1f Mbps", it) } ?: "Low"}",
                        "High round-trip latency: Avg ${congestionReport?.avgLatencyMs?.let { String.format(Locale.US, "%.0f ms", it) } ?: "High"}"
                    ),
                    missingEvidenceDisclosed = listOf(
                        "Operator OSS/BSS busy-hour cell traffic volume (GB/hour)",
                        "Physical Resource Block (PRB) utilization statistics (DL PRB > 85%)",
                        "Pedestrian footfall and population density census data"
                    ),
                    alternativeExplanations = listOf(
                        "Transient download traffic spike during drive test",
                        "Backhaul transport link congestion rather than air interface saturation"
                    ),
                    recommendedFieldActions = listOf(
                        "Inspect operator eNodeB PRB utilization counters for 7 consecutive days",
                        "Identify lamppost or utility pole mounting options for outdoor micro-cell evaluation"
                    )
                )
            )
        }

        // Recommendation 3: Additional Bandwidth / Carrier Expansion
        if (isCongested) {
            recommendations.add(
                EngineeringRecommendation(
                    category = RecommendationCategory.ADDITIONAL_SPECTRUM_BANDWIDTH,
                    title = "Activate Secondary LTE/5G Carrier or Carrier Aggregation (CA)",
                    priorityRank = 3,
                    confidenceScore = 0.65f,
                    targetCellOrCorridor = "Sector PCI $dominantPci (Carrier upgrade)",
                    rationale = "Expanding channel bandwidth (e.g. adding 10 MHz or 20 MHz secondary E-UTRA carrier) will alleviate air interface resource contention without constructing new physical towers.",
                    evidenceUsed = listOf(
                        "Bottleneck classification: ${congestionReport?.primaryLimitingFactor ?: "Capacity chokepoint"}",
                        "Current channel: Single carrier observation on EARFCN ${validMeasurements.firstOrNull { it.earfcn != null }?.earfcn ?: "—"}"
                    ),
                    missingEvidenceDisclosed = listOf(
                        "Operator spectrum license availability in target frequency bands (e.g. Band 1/3/8/28/40)",
                        "Baseband hardware processing capacity and RRU (Remote Radio Unit) dual-band readiness"
                    ),
                    alternativeExplanations = listOf(
                        "Backhaul constraint would not be relieved by adding air interface carriers"
                    ),
                    recommendedFieldActions = listOf(
                        "Audit radio unit RRU hardware capabilities for 2xCA or 3xCA activation",
                        "Verify spectrum allocation with national telecommunications regulatory authority"
                    )
                )
            )
        }

        // Recommendation 4: Antenna / Sector Optimization
        if (transitions.size >= 8 || hasPilotPollution) {
            recommendations.add(
                EngineeringRecommendation(
                    category = RecommendationCategory.ANTENNA_SECTOR_OPTIMIZATION,
                    title = "Down-Tilt & Azimuth Re-Orientation (RF Boundary Control)",
                    priorityRank = 4,
                    confidenceScore = 0.75f,
                    targetCellOrCorridor = "Cluster around PCIs ${pciDwell.keys.take(3).joinToString(", ")}",
                    rationale = "Excessive boundary overlap and rapid handovers (${transitions.size} transitions) suggest antenna overshoot into neighboring cell dominance areas.",
                    evidenceUsed = listOf(
                        "Transition density: ${transitions.size} cell changes across survey",
                        "Pilot pollution indicator count: $hasPilotPollution",
                        "Mean SINR: ${String.format(Locale.US, "%.1f dB", avgSinr)}"
                    ),
                    missingEvidenceDisclosed = listOf(
                        "Mechanical and electrical down-tilt (RET) values of active antennas",
                        "Antenna mast heights and 3D radiation patterns (horizontal/vertical beamwidths)",
                        "Surrounding rooftop clutter and obstacle heights"
                    ),
                    alternativeExplanations = listOf(
                        "Cluttered reflective urban surfaces causing multipath scatter"
                    ),
                    recommendedFieldActions = listOf(
                        "Perform physical site inspection of antenna azimuth and mechanical tilt",
                        "Apply remote electrical tilt (RET) increase of 2° to contain overshoot"
                    )
                )
            )
        }

        // Recommendation 5: Interference Investigation
        if (avgSinr < 3.0 && avgRsrp >= -95.0) {
            recommendations.add(
                EngineeringRecommendation(
                    category = RecommendationCategory.INTERFERENCE_INVESTIGATION,
                    title = "Co-Channel & Adjacent-Channel Interference Audit",
                    priorityRank = 5,
                    confidenceScore = 0.70f,
                    targetCellOrCorridor = "Area of PCI $dominantPci",
                    rationale = "Uncharacteristically degraded SINR (${String.format(Locale.US, "%.1f dB", avgSinr)}) in the presence of strong signal power (${String.format(Locale.US, "%.1f dBm", avgRsrp)}) indicates co-channel collision, PCI Modulo-3 clash, or external RF emitter.",
                    evidenceUsed = listOf(
                        "Mean SINR: ${String.format(Locale.US, "%.1f dB", avgSinr)} (< 3.0 dB)",
                        "Mean RSRP: ${String.format(Locale.US, "%.1f dBm", avgRsrp)} (≥ -95 dBm)",
                        "Mean RSRQ: ${String.format(Locale.US, "%.1f dB", avgRsrq)}"
                    ),
                    missingEvidenceDisclosed = listOf(
                        "Continuous spectrum analyzer sweep for non-cellular external emissions",
                        "Full neighbor list configuration in eNodeB RRC parameters"
                    ),
                    alternativeExplanations = listOf(
                        "External repeater misconfiguration or illegal booster oscillation",
                        "Passive intermodulation (PIM) at nearby antenna feedline"
                    ),
                    recommendedFieldActions = listOf(
                        "Audit PCI modulo-3 planning on adjacent sectors to eliminate Cell-Specific Reference Signal (CRS) collisions",
                        "Deploy calibrated spectrum analyzer for passive emitter hunt"
                    )
                )
            )
        }

        // Default nominal recommendation if route was clean
        if (recommendations.isEmpty()) {
            recommendations.add(
                EngineeringRecommendation(
                    category = RecommendationCategory.ANTENNA_SECTOR_OPTIMIZATION,
                    title = "Routine Preventive RF Monitoring & Antenna Maintenance",
                    priorityRank = 5,
                    confidenceScore = 0.85f,
                    targetCellOrCorridor = "Current Surveyed Route",
                    rationale = "Route exhibits balanced coverage and quality within standard 3GPP operational thresholds.",
                    evidenceUsed = listOf(
                        "Coverage adequacy: ${String.format(Locale.US, "%.1f%%", 100.0 - weakPct)} of samples above -105 dBm",
                        "Clean radio quality: Mean SINR ${String.format(Locale.US, "%.1f dB", avgSinr)}"
                    ),
                    missingEvidenceDisclosed = listOf(
                        "Peak busy-hour load trends over seasonal variations"
                    ),
                    alternativeExplanations = emptyList(),
                    recommendedFieldActions = listOf(
                        "Schedule quarterly regression drive-test verification"
                    )
                )
            )
        }

        // Site Suitability Assessment
        val coverageDeficitScore = (weakPct.toFloat() * 1.5f).coerceIn(0f, 100f)
        val congestionScore = if (isCongested) 80f else 25f
        val transitionDensityScore = (transitions.size * 5f).coerceIn(10f, 95f)
        val siteFeasibilityScore = 50f // Baseline neutral without civil survey

        val factors = listOf(
            SiteSuitabilityFactor(
                factorName = "Coverage Deficit & Hole Severity",
                weight = 0.35f,
                score = coverageDeficitScore,
                evidence = "Weak coverage ratio: ${String.format(Locale.US, "%.1f%%", weakPct)} (${coverageHoles.size} holes)"
            ),
            SiteSuitabilityFactor(
                factorName = "Traffic Demand & Capacity Choke",
                weight = 0.30f,
                score = congestionScore,
                evidence = congestionReport?.primaryLimitingFactor ?: "Handset throughput telemetry"
            ),
            SiteSuitabilityFactor(
                factorName = "Mobility Transitions & Handover Stress",
                weight = 0.20f,
                score = transitionDensityScore,
                evidence = "${transitions.size} handovers / transitions observed along corridor"
            ),
            SiteSuitabilityFactor(
                factorName = "Physical Infrastructure Feasibility",
                weight = 0.15f,
                score = siteFeasibilityScore,
                evidence = "Nominal road corridor access; civil site acquisition survey required"
            )
        )

        val compositeScore = factors.sumOf { (it.weight * it.score).toDouble() }.toFloat()

        val suitability = SiteSuitabilityAssessment(
            overallSuitabilityScore = compositeScore,
            confidenceGrade = "PRELIMINARY_HANDSET_DERIVED",
            factors = factors,
            missingEnvironmentalInputs = listOf(
                "3D building morphology and clutter height profiles",
                "Digital Elevation Model (DEM) terrain data",
                "Fiber/microwave transmission backhaul availability",
                "Legal site leasing rights and municipal zoning permissions",
                "Commercial power grid availability and battery backup requirements"
            ),
            suitabilityDisclaimer = "This Site Suitability Assessment is derived from mobile drive-test observations. It serves as an exploratory decision-support indicator and cannot replace professional RF propagation planning with calibrated 3D clutter databases."
        )

        return CapacityPlanningReport(
            recommendations = recommendations.sortedBy { it.priorityRank },
            siteSuitability = suitability,
            summaryNote = "${recommendations.size} engineering recommendations generated based on field telemetry."
        )
    }
}
