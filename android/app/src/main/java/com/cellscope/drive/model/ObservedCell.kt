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
    val servingStatus: ServingStatus = if (isServing) ServingStatus.PRIMARY_SERVING else ServingStatus.NEIGHBOR,
    val servingEvidence: String? = null,
    val networkType: String = "LTE",
    val operatorAlpha: String? = null,
    val operatorNumeric: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val nci: Long? = null,                // 36-bit NR Cell Identity (5G NR)
    val nrArfcn: Int? = null,            // NR-ARFCN (5G NR)
    val csiRsrpDbm: Int? = null,         // CSI-RSRP (5G NR)
    val csiRsrqDb: Int? = null,          // CSI-RSRQ (5G NR)
    val csiSinrDb: Double? = null,       // CSI-SINR (5G NR)
    val nrState: String? = null,         // "SA", "NSA", "NOT_CONNECTED", "UNSUPPORTED"
    val availabilityState: String = "AVAILABLE", // AVAILABLE, TEMPORARILY_UNAVAILABLE, UNSUPPORTED, PERMISSION_DENIED, STALE
    val servingRat: String? = null,
    val channelNumber: Int? = null,
    val channelType: String? = null,
    val primarySignalDbm: Int? = null,
    val signalMetricType: String? = null,
    val secondarySignalDb: Double? = null
) {
    val is5gNr: Boolean
        get() = servingRat == "5G" || networkType == "5G_NR" || networkType == "5G"

    val isGsm: Boolean
        get() = servingRat == "2G" || networkType.contains("GSM", ignoreCase = true) || networkType == "2G"

    val isWcdma: Boolean
        get() = servingRat == "3G" || networkType.contains("WCDMA", ignoreCase = true) || networkType == "3G" || networkType == "UMTS"

    val isLte: Boolean
        get() = servingRat == "4G" || networkType.equals("LTE", ignoreCase = true) || networkType == "4G"

    val isConfirmedServing: Boolean
        get() = servingStatus == ServingStatus.PRIMARY_SERVING || servingStatus == ServingStatus.SECONDARY_SERVING

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
