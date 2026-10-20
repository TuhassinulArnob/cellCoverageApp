package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.LteMeasurementEntity
import java.util.Locale
import kotlin.math.*

/**
 * Metric provenance classification distinguishing empirical measurements from assumptions.
 */
enum class MetricProvenance(val label: String) {
    MEASURED("Measured (Empirical Drive-Test)"),
    IMPORTED("Imported (Historical Log / External File)"),
    USER_SPECIFIED_ASSUMPTION("User-Specified Assumption"),
    ENGINEERING_ESTIMATE("Engineering Estimate (Parametric Model)"),
    UNKNOWN("Unknown — Operator / Network Counters Required")
}

/**
 * Standardized network congestion classification conforming strictly to RAN evidence standards.
 */
enum class CongestionEvidenceLevel(val label: String, val description: String) {
    INSUFFICIENT_EVIDENCE(
        "Insufficient Evidence",
        "Handset-only survey without active throughput/latency tests cannot confirm network congestion."
    ),
    POTENTIAL_CONGESTION(
        "Potential Congestion",
        "Elevated demand relative to modeled nominal capacity or isolated throughput drops observed."
    ),
    STRONGER_CONGESTION_EVIDENCE(
        "Stronger Congestion Evidence",
        "Recurring throughput choke and elevated latency under favorable RF signal conditions."
    ),
    VERIFIED_NETWORK_CONGESTION(
        "Verified Network Congestion",
        "Confirmed through genuine operator eNodeB PRB utilization and scheduler queue telemetry."
    )
}

/**
 * Event Traffic Profile per active subscriber.
 */
enum class EventTrafficProfile(
    val label: String,
    val dlDataRateKbps: Double,
    val ulDataRateKbps: Double,
    val description: String
) {
    LIGHT(
        label = "Light (Messaging & VoLTE)",
        dlDataRateKbps = 50.0,
        ulDataRateKbps = 15.0,
        description = "Instant messaging (WhatsApp), background push notifications, and voice calls."
    ),
    MODERATE(
        label = "Moderate (Social & Photos)",
        dlDataRateKbps = 250.0,
        ulDataRateKbps = 60.0,
        description = "Social media feeds, compressed photo sharing, web browsing, and messaging."
    ),
    HEAVY(
        label = "Heavy (Video & Live Streaming)",
        dlDataRateKbps = 1500.0,
        ulDataRateKbps = 400.0,
        description = "1080p video streaming, Facebook/YouTube live broadcasts, and continuous video uploads."
    ),
    CUSTOM(
        label = "Custom Profile",
        dlDataRateKbps = 500.0,
        ulDataRateKbps = 100.0,
        description = "User-configured uplink and downlink throughput assumptions per active subscriber."
    )
}

/**
 * Event Preset categories for planning labels.
 */
enum class EventPreset(
    val displayName: String,
    val defaultRadiusM: Double,
    val defaultActiveRatio: Double,
    val defaultProfile: EventTrafficProfile
) {
    CONCERT("Concert / Musical Event", 350.0, 0.40, EventTrafficProfile.HEAVY),
    SPORTS_EVENT("Sports Event / Stadium", 450.0, 0.35, EventTrafficProfile.MODERATE),
    FESTIVAL("Festival / Cultural Fair", 600.0, 0.30, EventTrafficProfile.MODERATE),
    CONFERENCE("Conference / Exhibition", 300.0, 0.45, EventTrafficProfile.MODERATE),
    PUBLIC_GATHERING("Public Gathering", 500.0, 0.25, EventTrafficProfile.LIGHT),
    POLITICAL_RALLY("Political Rally", 500.0, 0.30, EventTrafficProfile.MODERATE),
    EMERGENCY_RESPONSE("Emergency Response / Incident", 400.0, 0.50, EventTrafficProfile.LIGHT),
    CUSTOM("Custom Event", 500.0, 0.25, EventTrafficProfile.MODERATE)
}

/**
 * Coverage Evidence Status derived strictly from surveyed measurements.
 */
enum class CoverageEvidenceStatus(val label: String) {
    MEASURED_LOCAL_EVIDENCE("Local Survey Evidence Available"),
    PARTIAL_EVIDENCE("Partial / Peripheral Evidence Nearby"),
    NO_LOCAL_EVIDENCE("No Local Survey Measurements (Unmeasured Area)")
}

/**
 * Evaluated survey evidence within an event boundary.
 */
data class CoverageEvidence(
    val status: CoverageEvidenceStatus,
    val sampleCountInArea: Int,
    val meanRsrpDbm: Double?,
    val observedPcis: List<Int>,
    val observedRats: List<String>,
    val distanceToNearestSampleM: Double,
    val evidenceSummary: String,
    val provenance: MetricProvenance
)

/**
 * Universal Event Venue and Study Area specification.
 * Supports ANY arbitrary geographic location on the map.
 */
data class EventVenue(
    val name: String,
    val eventName: String,
    val centerLat: Double,
    val centerLon: Double,
    val radiusMeters: Double = 500.0,
    val minLat: Double = centerLat - (radiusMeters / 111320.0),
    val maxLat: Double = centerLat + (radiusMeters / 111320.0),
    val minLon: Double = centerLon - (radiusMeters / (111320.0 * cos(Math.toRadians(centerLat)).coerceAtLeast(0.1))),
    val maxLon: Double = centerLon + (radiusMeters / (111320.0 * cos(Math.toRadians(centerLat)).coerceAtLeast(0.1))),
    val areaKm2: Double = (Math.PI * (radiusMeters / 1000.0) * (radiusMeters / 1000.0)),
    val baselineActiveUsers: Int = 1200,
    val baselineThroughputMbps: Double = 32.0,
    val primaryOperator: String = "Cellular Network",
    val primaryBand: String = "Band 3 (1800 MHz FDD)",
    val eventType: EventPreset = EventPreset.CUSTOM,
    val isPreset: Boolean = false
) {
    fun contains(lat: Double, lon: Double): Boolean {
        val dLatM = (lat - centerLat) * 111320.0
        val dLonM = (lon - centerLon) * 111320.0 * cos(Math.toRadians(centerLat))
        val distM = sqrt(dLatM * dLatM + dLonM * dLonM)
        return distM <= radiusMeters
    }

    companion object {
        fun defaultVenue(
            centerLat: Double = 23.8103,
            centerLon: Double = 90.4125,
            name: String = "Selected Map Location",
            eventName: String = "Special Event Gathering",
            radiusMeters: Double = 500.0,
            eventType: EventPreset = EventPreset.CUSTOM
        ): EventVenue {
            return fromCoordinates(centerLat, centerLon, name, eventName, radiusMeters, eventType)
        }

        fun fromCoordinates(
            lat: Double,
            lon: Double,
            name: String = "Selected Map Location",
            eventName: String = "Custom Gathering",
            radiusMeters: Double = 500.0,
            eventType: EventPreset = EventPreset.CUSTOM
        ): EventVenue {
            return EventVenue(
                name = name,
                eventName = eventName,
                centerLat = lat,
                centerLon = lon,
                radiusMeters = radiusMeters,
                eventType = eventType,
                isPreset = false
            )
        }
    }
}

typealias CampusVenue = EventVenue

/**
 * Concrete capacity scenario specification with explicit assumptions.
 */
data class CapacityScenarioSpec(
    val scenarioId: String,
    val title: String,
    val additionalAttendees: Int,
    val activeUserRatio: Double, // e.g. 0.25 for 25%
    val trafficProfile: EventTrafficProfile,
    val hasTemporaryCow: Boolean = false,
    val cowCapacityMbps: Double = 350.0, // editable scenario assumption
    val hasWifiOffload: Boolean = false,
    val wifiOffloadRatio: Double = 0.40, // editable scenario assumption
    val hasAntennaDowntiltOptimization: Boolean = false,
    val durationHours: Double = 3.0
)

/**
 * Detailed simulation results strictly separating demand from capacity and enforcing
 * non-fabricated PRB metrics.
 */
data class CapacityScenarioResult(
    val spec: CapacityScenarioSpec,
    val venue: EventVenue,
    val coverageEvidence: CoverageEvidence,

    // 1. Demand Metrics
    val totalAttendees: Int,
    val activeEventUsers: Int,
    val existingBaselineUsers: Int,
    val totalActiveUsers: Int,
    val offeredTrafficDlMbps: Double,
    val offeredTrafficUlMbps: Double,
    val demandProvenance: MetricProvenance = MetricProvenance.USER_SPECIFIED_ASSUMPTION,

    // 2. Capacity Metrics
    val existingMacroCapacityMbps: Double?, // null if unmeasured / unknown
    val addedCandidateCapacityMbps: Double,
    val totalAvailableCapacityMbps: Double?, // null if unmeasured / unknown
    val capacityProvenance: MetricProvenance,

    // 3. Balance & Deficit Metrics
    val capacityDeficitMbps: Double?, // null if unmeasured; positive if deficit, 0.0 if balanced/surplus
    val unusedCapacityMarginMbps: Double?, // positive if surplus, 0.0 if deficit
    val demandToCapacityRatioPct: Double?, // Demand-to-Capacity ratio (can be > 100%)

    // 4. PRB Utilization Metric (strictly 0–100% or null)
    val actualPrbUtilizationPct: Double?, // null if genuine operator counters absent; strictly <= 100%
    val prbCounterAvailabilityNote: String = "Unknown — Operator eNodeB PRB scheduler counters not exposed by device RIL",

    // 5. Congestion Classification & Bottlenecks
    val congestionEvidenceLevel: CongestionEvidenceLevel,
    val bottleneckSeverity: String, // "NORMAL", "MODERATE_STRESS", "SEVERE_BOTTLENECK", "CRITICAL_BLACKOUT", "UNKNOWN_UNMEASURED"
    val bottleneckCells: List<String>,

    // 6. Candidate Interventions & Disclosures
    val recommendedInterventions: List<String>,
    val interventionOverlapNote: String,
    val sensitivitySummary: String,
    val isOperatorCountersAvailable: Boolean = false,
    val calculationDisclosures: List<String>
) {
    // Backward-compatibility accessors
    val concurrentActiveUsers: Int
        get() = totalActiveUsers
    val predictedPrbUtilizationPct: Double?
        get() = demandToCapacityRatioPct
}

/**
 * Multi-scenario comparison bundle.
 */
data class ScenarioComparisonReport(
    val venue: EventVenue,
    val baseline: CapacityScenarioResult,
    val scenarioA: CapacityScenarioResult,
    val scenarioB: CapacityScenarioResult,
    val scenarioC: CapacityScenarioResult,
    val executiveSummary: String,
    val recommendedDecision: String
)

object EventCapacitySimulator {

    /**
     * Evaluates local survey measurements within the event radius.
     */
    fun evaluateCoverageEvidence(
        venue: EventVenue,
        measurements: List<LteMeasurementEntity>
    ): CoverageEvidence {
        val valid = measurements.filter { it.latitude != null && it.longitude != null && it.isGpsValid }
        if (valid.isEmpty()) {
            return CoverageEvidence(
                status = CoverageEvidenceStatus.NO_LOCAL_EVIDENCE,
                sampleCountInArea = 0,
                meanRsrpDbm = null,
                observedPcis = emptyList(),
                observedRats = emptyList(),
                distanceToNearestSampleM = Double.MAX_VALUE,
                evidenceSummary = "No drive-test measurements exist in this dataset. Local radio conditions and capacity are unmeasured.",
                provenance = MetricProvenance.UNKNOWN
            )
        }

        val inArea = valid.filter { venue.contains(it.latitude!!, it.longitude!!) }
        val nearestSample = valid.minByOrNull {
            val dLat = (it.latitude!! - venue.centerLat) * 111320.0
            val dLon = (it.longitude!! - venue.centerLon) * 111320.0 * cos(Math.toRadians(venue.centerLat))
            sqrt(dLat * dLat + dLon * dLon)
        }
        val nearestDistM = nearestSample?.let {
            val dLat = (it.latitude!! - venue.centerLat) * 111320.0
            val dLon = (it.longitude!! - venue.centerLon) * 111320.0 * cos(Math.toRadians(venue.centerLat))
            sqrt(dLat * dLat + dLon * dLon)
        } ?: Double.MAX_VALUE

        return if (inArea.isNotEmpty()) {
            val meanRsrp = inArea.mapNotNull { it.rsrpDbm }.average()
            val pcis = inArea.mapNotNull { it.servingPci }.distinct()
            val rats = inArea.mapNotNull { it.networkGen ?: it.networkType }.distinct()
            CoverageEvidence(
                status = CoverageEvidenceStatus.MEASURED_LOCAL_EVIDENCE,
                sampleCountInArea = inArea.size,
                meanRsrpDbm = if (meanRsrp.isNaN()) null else meanRsrp,
                observedPcis = pcis,
                observedRats = rats,
                distanceToNearestSampleM = 0.0,
                evidenceSummary = "${inArea.size} surveyed samples inside event radius (${rats.joinToString("/")}, Mean RSRP: ${String.format(Locale.US, "%.1f dBm", meanRsrp)}).",
                provenance = MetricProvenance.MEASURED
            )
        } else if (nearestDistM <= 1500.0) {
            val rats = nearestSample?.let { listOf(it.networkGen ?: it.networkType) } ?: emptyList()
            CoverageEvidence(
                status = CoverageEvidenceStatus.PARTIAL_EVIDENCE,
                sampleCountInArea = 0,
                meanRsrpDbm = nearestSample?.rsrpDbm?.toDouble(),
                observedPcis = nearestSample?.servingPci?.let { listOf(it) } ?: emptyList(),
                observedRats = rats,
                distanceToNearestSampleM = nearestDistM,
                evidenceSummary = "Peripheral survey track ${nearestDistM.toInt()}m from event center. Local coverage conditions within event perimeter remain unverified.",
                provenance = MetricProvenance.ENGINEERING_ESTIMATE
            )
        } else {
            CoverageEvidence(
                status = CoverageEvidenceStatus.NO_LOCAL_EVIDENCE,
                sampleCountInArea = 0,
                meanRsrpDbm = null,
                observedPcis = emptyList(),
                observedRats = emptyList(),
                distanceToNearestSampleM = nearestDistM,
                evidenceSummary = "Nearest surveyed route is ${(nearestDistM / 1000.0).toInt()} km away. Local RF conditions are unknown.",
                provenance = MetricProvenance.UNKNOWN
            )
        }
    }

    /**
     * Run event capacity simulation with strictly separated demand and capacity,
     * traceable provenance, and honest PRB counter reporting.
     */
    fun simulateScenario(
        venue: EventVenue,
        spec: CapacityScenarioSpec,
        measurements: List<LteMeasurementEntity> = emptyList()
    ): CapacityScenarioResult {
        // 1. Evaluate Survey Evidence
        val evidence = evaluateCoverageEvidence(venue, measurements)

        // 2. Active User Demand Calculations (Distinct user tiers)
        val existingBaselineUsers = venue.baselineActiveUsers
        val activeUsersFromEvent = (spec.additionalAttendees * spec.activeUserRatio).toInt()
        val totalActiveUsers = existingBaselineUsers + activeUsersFromEvent
        val totalAttendees = venue.baselineActiveUsers + spec.additionalAttendees

        // 3. Offered Traffic Calculations (Demand)
        val rawDlMbps = (totalActiveUsers * spec.trafficProfile.dlDataRateKbps) / 1000.0
        val rawUlMbps = (totalActiveUsers * spec.trafficProfile.ulDataRateKbps) / 1000.0

        val wifiRatio = if (spec.hasWifiOffload) spec.wifiOffloadRatio else 0.0
        val offeredDlMbps = rawDlMbps * (1.0 - wifiRatio)
        val offeredUlMbps = rawUlMbps * (1.0 - wifiRatio)

        // 4. Macro Capacity Modeling & Evidence Grounding
        val hasSurveyEvidence = evidence.status == CoverageEvidenceStatus.MEASURED_LOCAL_EVIDENCE
        val detectedSectorsCount = if (evidence.observedPcis.isNotEmpty()) evidence.observedPcis.size else 0

        val nominalSectorCapacityDlMbps = 44.0 // 20 MHz FDD LTE at 2.2 bps/Hz practical urban SE
        val existingMacroCapacityDl: Double? = if (hasSurveyEvidence && detectedSectorsCount > 0) {
            detectedSectorsCount * nominalSectorCapacityDlMbps
        } else {
            null // Unknown — survey/operator data required
        }

        val capacityProvenance = if (existingMacroCapacityDl != null) {
            MetricProvenance.ENGINEERING_ESTIMATE
        } else {
            MetricProvenance.UNKNOWN
        }

        // 5. Candidate Interventions (Modeled Scenarios — Not Deployed Infrastructure)
        var addedCapacity = 0.0
        val interventions = mutableListOf<String>()

        if (spec.hasTemporaryCow) {
            addedCapacity += spec.cowCapacityMbps
            interventions.add("Candidate Scenario: Temporary Cell On Wheels (COW) (+${spec.cowCapacityMbps.toInt()} Mbps modeled DL capacity, 3 sectors). Modeled estimate only.")
        }
        if (spec.hasWifiOffload) {
            val offloadedDl = (rawDlMbps * spec.wifiOffloadRatio).toInt()
            interventions.add("Candidate Scenario: Wi-Fi 6 Offload Nodes (Assumes ${ (spec.wifiOffloadRatio * 100).toInt() }% offload of pedestrian traffic, ~$offloadedDl Mbps bulk offload).")
        }
        if (spec.hasAntennaDowntiltOptimization) {
            val seBoost = (existingMacroCapacityDl ?: 0.0) * 0.15
            if (seBoost > 0) {
                addedCapacity += seBoost
                interventions.add("Candidate Scenario: Macro antenna tilt optimization (2° to 6° down-tilt modeled to reduce inter-cell interference, +${seBoost.toInt()} Mbps).")
            } else {
                interventions.add("Candidate Scenario: Macro antenna tilt optimization (Down-tilt modeled to reduce inter-cell interference).")
            }
        }

        if (existingMacroCapacityDl != null && offeredDlMbps > existingMacroCapacityDl && !spec.hasTemporaryCow) {
            interventions.add("Engineering Recommendation: Temporary capacity augmentation (e.g. COW or small cells) recommended during peak hours.")
        }
        if (evidence.status == CoverageEvidenceStatus.NO_LOCAL_EVIDENCE) {
            interventions.add("Field Action Required: Perform calibrated drive/walk test at venue coordinates to establish empirical baseline.")
        }

        val overlapNote = "Intervention Warning: Intervention capacities are non-additive and may overlap. Wi-Fi offload reduces mobile demand, whereas COW adds raw RF capacity. Do not sum theoretical gains without radio propagation planning."

        // 6. Capacity Balance, Deficit & Demand-to-Capacity Ratio
        val totalAvailableCapacity: Double? = if (existingMacroCapacityDl != null) {
            existingMacroCapacityDl + addedCapacity
        } else if (addedCapacity > 0) {
            addedCapacity
        } else {
            null
        }

        val capacityDeficitMbps: Double?
        val unusedCapacityMarginMbps: Double?
        if (totalAvailableCapacity != null) {
            val diff = offeredDlMbps - totalAvailableCapacity
            if (diff > 0.0) {
                capacityDeficitMbps = diff
                unusedCapacityMarginMbps = 0.0
            } else {
                capacityDeficitMbps = 0.0
                unusedCapacityMarginMbps = abs(diff)
            }
        } else {
            capacityDeficitMbps = null
            unusedCapacityMarginMbps = null
        }

        val demandRatioPct: Double? = if (totalAvailableCapacity != null && totalAvailableCapacity > 0.0) {
            (offeredDlMbps / totalAvailableCapacity) * 100.0
        } else {
            null
        }

        // 7. PRB Utilization: Strictly null (Unknown) for handset surveys
        val actualPrbUtilizationPct: Double? = null // Handsets do not expose eNodeB PRB scheduler counters

        // 8. Congestion Classification
        val congestionLevel = when {
            evidence.status == CoverageEvidenceStatus.NO_LOCAL_EVIDENCE -> CongestionEvidenceLevel.INSUFFICIENT_EVIDENCE
            demandRatioPct == null -> CongestionEvidenceLevel.INSUFFICIENT_EVIDENCE
            demandRatioPct > 150.0 -> CongestionEvidenceLevel.POTENTIAL_CONGESTION
            demandRatioPct > 100.0 -> CongestionEvidenceLevel.POTENTIAL_CONGESTION
            else -> CongestionEvidenceLevel.INSUFFICIENT_EVIDENCE
        }

        val severity = when {
            demandRatioPct == null -> "UNKNOWN_UNMEASURED"
            demandRatioPct <= 75.0 -> "NORMAL"
            demandRatioPct <= 100.0 -> "MODERATE_STRESS"
            demandRatioPct <= 150.0 -> "SEVERE_BOTTLENECK"
            else -> "CRITICAL_BLACKOUT"
        }

        val bottleneckCells = if (evidence.observedPcis.isNotEmpty() && (demandRatioPct ?: 0.0) > 90.0) {
            evidence.observedPcis.map { "Observed Sector PCI $it" }
        } else {
            emptyList()
        }

        val sensitivity = buildString {
            append("Sensitivity: At 15% active user ratio, offered demand is ${String.format(Locale.US, "%.0f", rawDlMbps * 0.6)} Mbps; ")
            append("at 35% peak active ratio, offered demand reaches ${String.format(Locale.US, "%.0f", rawDlMbps * 1.4)} Mbps.")
        }

        val disclosures = mutableListOf(
            "Provenance: Attendee counts, active-user ratios, and traffic profiles are user-specified planning assumptions.",
            "Handset-Only Survey Limitation: Device RIL does not provide operator-side PRB utilization, scheduler queues, or subscriber counts.",
            "PRB utilization is not fabricated from demand/capacity ratio and is correctly marked Unknown without network counters.",
            "Spectral efficiency modeled at 2.2 bps/Hz (loaded urban macro conditions).",
            "Candidate interventions represent parametric models, not actual deployed infrastructure.",
            "Candidate interventions are preliminary recommendations only; no automated physical cell splitting is performed."
        )
        if (evidence.status == CoverageEvidenceStatus.NO_LOCAL_EVIDENCE) {
            disclosures.add("No local drive-test measurements exist within the 500m event boundary. Macro capacity and deficit require empirical calibration.")
        }

        return CapacityScenarioResult(
            spec = spec,
            venue = venue,
            coverageEvidence = evidence,
            totalAttendees = totalAttendees,
            activeEventUsers = activeUsersFromEvent,
            existingBaselineUsers = existingBaselineUsers,
            totalActiveUsers = totalActiveUsers,
            offeredTrafficDlMbps = offeredDlMbps,
            offeredTrafficUlMbps = offeredUlMbps,
            demandProvenance = MetricProvenance.USER_SPECIFIED_ASSUMPTION,
            existingMacroCapacityMbps = existingMacroCapacityDl,
            addedCandidateCapacityMbps = addedCapacity,
            totalAvailableCapacityMbps = totalAvailableCapacity,
            capacityProvenance = capacityProvenance,
            capacityDeficitMbps = capacityDeficitMbps,
            unusedCapacityMarginMbps = unusedCapacityMarginMbps,
            demandToCapacityRatioPct = demandRatioPct,
            actualPrbUtilizationPct = actualPrbUtilizationPct,
            prbCounterAvailabilityNote = "Unknown — Operator eNodeB PRB scheduler counters not exposed by device RIL",
            congestionEvidenceLevel = congestionLevel,
            bottleneckSeverity = severity,
            bottleneckCells = bottleneckCells,
            recommendedInterventions = interventions,
            interventionOverlapNote = overlapNote,
            sensitivitySummary = sensitivity,
            isOperatorCountersAvailable = false,
            calculationDisclosures = disclosures
        )
    }

    /**
     * Generate standard 4-scenario comparison for any venue.
     */
    fun runMultiScenarioComparison(
        venue: EventVenue,
        measurements: List<LteMeasurementEntity> = emptyList()
    ): ScenarioComparisonReport {

        val baselineSpec = CapacityScenarioSpec(
            scenarioId = "BASELINE",
            title = "Baseline (Normal Conditions)",
            additionalAttendees = 0,
            activeUserRatio = 0.20,
            trafficProfile = EventTrafficProfile.LIGHT
        )

        val scenarioASpec = CapacityScenarioSpec(
            scenarioId = "SCENARIO_A",
            title = "Scenario A (+10,000 Attendees)",
            additionalAttendees = 10000,
            activeUserRatio = 0.20,
            trafficProfile = EventTrafficProfile.MODERATE,
            hasTemporaryCow = false
        )

        val scenarioBSpec = CapacityScenarioSpec(
            scenarioId = "SCENARIO_B",
            title = "Scenario B (+20,000 Attendees - Unmitigated)",
            additionalAttendees = 20000,
            activeUserRatio = 0.20,
            trafficProfile = EventTrafficProfile.MODERATE,
            hasTemporaryCow = false
        )

        val scenarioCSpec = CapacityScenarioSpec(
            scenarioId = "SCENARIO_C",
            title = "Scenario C (+20,000 Attendees - With Candidate Interventions)",
            additionalAttendees = 20000,
            activeUserRatio = 0.25,
            trafficProfile = EventTrafficProfile.HEAVY,
            hasTemporaryCow = true,
            hasWifiOffload = true,
            hasAntennaDowntiltOptimization = true
        )

        val baseRes = simulateScenario(venue, baselineSpec, measurements)
        val resA = simulateScenario(venue, scenarioASpec, measurements)
        val resB = simulateScenario(venue, scenarioBSpec, measurements)
        val resC = simulateScenario(venue, scenarioCSpec, measurements)

        val summary = buildString {
            append("${venue.name} (${venue.eventName}): ")
            if (baseRes.demandToCapacityRatioPct != null) {
                append("Macro network accommodates baseline load (${baseRes.demandToCapacityRatioPct.toInt()}% demand/capacity ratio). ")
                append("An unmitigated gathering of 10,000 to 20,000 attendees increases demand to ")
                append("${resA.demandToCapacityRatioPct?.toInt() ?: 0}% to ${resB.demandToCapacityRatioPct?.toInt() ?: 0}% of nominal capacity. ")
                append("Candidate Scenario C (COW + Wi-Fi 6 offload + down-tilt) relieves the modeled deficit.")
            } else {
                append("No baseline survey data available in event radius. Capacity modeling requires baseline survey verification.")
            }
        }

        val decision = "RECOMMENDATION: Evaluate candidate Cell on Wheels (COW) and Wi-Fi 6 offload nodes; conduct baseline drive testing to verify coverage."

        return ScenarioComparisonReport(
            venue = venue,
            baseline = baseRes,
            scenarioA = resA,
            scenarioB = resB,
            scenarioC = resC,
            executiveSummary = summary,
            recommendedDecision = decision
        )
    }
}
