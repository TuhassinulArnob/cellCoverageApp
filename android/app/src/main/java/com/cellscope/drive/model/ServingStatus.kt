package com.cellscope.drive.model

/**
 * Authoritative serving-cell classification for cellular field assessment.
 * Replaces ambiguous boolean flags with strict 3GPP network role definitions.
 */
enum class ServingStatus(
    val label: String,
    val badgeText: String,
    val description: String
) {
    /**
     * Confirmed Primary Serving Cell (PCell).
     * Carries the primary RRC signaling, SIB broadcast, and NAS connection.
     */
    PRIMARY_SERVING(
        label = "Primary Serving Cell (PCell)",
        badgeText = "PCELL",
        description = "Confirmed primary serving cell on active subscription."
    ),

    /**
     * Confirmed Secondary Serving Cell (SCell).
     * Added in LTE Carrier Aggregation (CA) or 5G NSA Dual Connectivity (EN-DC)
     * for additional bandwidth / throughput boost.
     */
    SECONDARY_SERVING(
        label = "Secondary Serving Cell (SCell)",
        badgeText = "SCELL (CA)",
        description = "Confirmed secondary component carrier in Carrier Aggregation."
    ),

    /**
     * Cell reported as registered by modem HAL, but without confirmed SCell connection status.
     * Prevents false Carrier Aggregation claims and contradictory multi-serving labels.
     */
    SERVING_UNCONFIRMED(
        label = "Observed cell — serving status unconfirmed",
        badgeText = "UNCONFIRMED",
        description = "Reported as registered by modem HAL; Carrier Aggregation SCell status unconfirmed by telephony connection status."
    ),

    /**
     * Detected non-serving neighbor cell.
     */
    NEIGHBOR(
        label = "Neighbor Cell",
        badgeText = "NEIGHBOR",
        description = "Monitored neighbor cell for handover candidate evaluation."
    );

    val isServingConnection: Boolean
        get() = this == PRIMARY_SERVING || this == SECONDARY_SERVING

    val isPrimary: Boolean
        get() = this == PRIMARY_SERVING
}
