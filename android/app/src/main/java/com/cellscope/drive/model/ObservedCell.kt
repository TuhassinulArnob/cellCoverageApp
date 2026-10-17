package com.cellscope.drive.model

data class ObservedCell(
    val pci: Int?,
    val tac: Int? = null,
    val eci: Int? = null,
    val earfcn: Int? = null,
    val bandwidthKhz: Int? = null,
    val rsrpDbm: Int? = null,
    val rsrqDb: Int? = null,
    val rssiDbm: Int? = null,
    val sinrDb: Double? = null,
    val cqi: Int? = null,
    val timingAdvance: Int? = null,
    val isServing: Boolean = false,
    val networkType: String = "LTE",
    val operatorAlpha: String? = null,
    val operatorNumeric: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
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
