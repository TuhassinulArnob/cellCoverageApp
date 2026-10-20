package com.cellscope.drive.ui.map

/**
 * Central Map Mode Controller definitions for CellScope 5G.
 * Enforces strict overlay isolation: selecting one mode displays only its designated
 * markers and geometries, while [ALL_LAYERS] provides a structured multi-layer engineering view.
 */
enum class MapDisplayMode(
    val label: String,
    val shortTitle: String,
    val description: String
) {
    ROUTE_SAMPLES(
        label = "Route & Samples",
        shortTitle = "Route",
        description = "Measured GPS drive track, sample telemetry points, and real-time receiver location."
    ),
    HANDOVERS(
        label = "Handover Locations",
        shortTitle = "Handovers",
        description = "Cell boundary transitions, confirmed 3GPP handovers, and inter-frequency reselections."
    ),
    ESTIMATED_SITES(
        label = "Estimated Cell Sites",
        shortTitle = "Sites",
        description = "Candidate physical base-station centroids with explicit spatial uncertainty margins."
    ),
    COVERAGE_HEATMAP(
        label = "Coverage Heatmap",
        shortTitle = "Heatmap",
        description = "Continuous spatial interpolation surface with technology (RAT) isolation."
    ),
    WEAK_ZONES(
        label = "Weak Coverage / Holes",
        shortTitle = "Holes",
        description = "Detected RF attenuation corridors, severe deficits, and confirmed service interruption zones."
    ),
    CAPACITY_HOTSPOTS(
        label = "Capacity Hotspots",
        shortTitle = "Hotspots",
        description = "High-load bottleneck points, spectral deficits, and heavy resource-block utilization."
    ),
    EVENT_PLANNING(
        label = "Universal Event Planner",
        shortTitle = "Event Planner",
        description = "User-defined event boundary, estimated active demand, candidate capacity hotspots, and intervention options."
    ),
    ACTIVE_QOE(
        label = "Active QoE & Congestion",
        shortTitle = "Active QoE",
        description = "Empirical HTTP download/upload throughput, round-trip latency, jitter, loss, and radio congestion classification."
    ),
    ALL_LAYERS(
        label = "All Layers",
        shortTitle = "All Layers",
        description = "Deliberate multi-layer engineering view with isolated styling and comprehensive legend."
    )
}
