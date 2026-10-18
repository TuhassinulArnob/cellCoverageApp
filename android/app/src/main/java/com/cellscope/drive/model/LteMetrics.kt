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
    val timestampMs: Long = System.currentTimeMillis(),
    val nci: Long? = null,                // 36-bit NR Cell Identity (5G NR)
    val nrArfcn: Int? = null,            // NR-ARFCN (5G NR)
    val csiRsrpDbm: Int? = null,         // CSI-RSRP (5G NR)
    val csiRsrqDb: Int? = null,          // CSI-RSRQ (5G NR)
    val csiSinrDb: Double? = null,       // CSI-SINR (5G NR)
    val nrState: String = "UNKNOWN",     // "SA", "NSA", "LTE_ONLY", "NOT_CONNECTED", "UNSUPPORTED"
    val nrAvailabilityState: String = "AVAILABLE", // "AVAILABLE", "TEMPORARILY_UNAVAILABLE", "UNSUPPORTED", "PERMISSION_DENIED", "STALE"
    val dataNetworkType: String = networkType,
    val voiceNetworkType: String = "UNKNOWN",
    val networkGen: NetworkGeneration = NetworkGeneration.fromString(networkType),
    val serviceState: String = "IN_SERVICE",       // "IN_SERVICE", "OUT_OF_SERVICE", "EMERGENCY_ONLY", "POWER_OFF", "UNKNOWN"
    val dataState: String = "CONNECTED",           // "CONNECTED", "CONNECTING", "DISCONNECTED", "SUSPENDED", "UNKNOWN"
    val callState: String = "IDLE"                 // "IDLE", "RINGING", "OFFHOOK"
) {
    val is5gActive: Boolean
        get() = networkType == "5G_NR" || nrState == "SA" || nrState == "NSA" || networkGen == NetworkGeneration.GEN_5G

    val hasValidSignal: Boolean
        get() = (rsrpDbm != null || servingPci != null)

    /**
     * Coverage rating per 3GPP TS 36.133 / TS 38.133 thresholds
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
