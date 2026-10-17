package com.cellscope.drive.model

data class LteMetrics(
    val networkType: String = "UNKNOWN",
    val operatorAlpha: String? = null,
    val operatorNumeric: String? = null,
    val servingPci: Int? = null,
    val tac: Int? = null,
    val earfcn: Int? = null,
    val bandwidthKhz: Int? = null,
    val rsrpDbm: Int? = null,
    val rsrqDb: Int? = null,
    val rssiDbm: Int? = null,
    val sinrDb: Double? = null,
    val eci: Int? = null,
    val cqi: Int? = null,
    val timingAdvance: Int? = null,
    val isServing: Boolean = false,
    val neighborCells: List<ObservedCell> = emptyList(),
    val allCells: List<ObservedCell> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis()
) {
    val hasValidSignal: Boolean
        get() = (rsrpDbm != null || servingPci != null)

    /**
     * Coverage rating per 3GPP TS 36.133 thresholds
     */
    val rsrpRating: String
        get() = when {
            rsrpDbm == null -> "NO SIGNAL"
            rsrpDbm >= -80 -> "EXCELLENT"
            rsrpDbm >= -90 -> "GOOD"
            rsrpDbm >= -100 -> "FAIR"
            else -> "POOR"
        }

    val rsrqRating: String
        get() = when {
            rsrqDb == null -> "NO SIGNAL"
            rsrqDb >= -10 -> "EXCELLENT"
            rsrqDb >= -14 -> "GOOD"
            rsrqDb >= -17 -> "FAIR"
            else -> "POOR"
        }
}
