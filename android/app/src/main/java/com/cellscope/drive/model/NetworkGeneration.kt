package com.cellscope.drive.model

import android.telephony.TelephonyManager

/**
 * Authoritative normalized cellular network generations for CellScope.
 * Strictly separates 2G, 3G, 4G, 5G, Out of Service, and Unknown states.
 */
enum class NetworkGeneration(
    val code: String,
    val label: String,
    val colorHex: String
) {
    GEN_5G("5G", "5G NR", "#7C4DFF"),       // Deep Purple / Violet
    GEN_4G("4G", "4G LTE", "#1976D2"),      // Telecom Blue
    GEN_3G("3G", "3G UMTS", "#FF9800"),     // Amber / Orange
    GEN_2G("2G", "2G GSM", "#795548"),      // Brown
    OUT_OF_SERVICE("OOS", "No Service", "#D50000"), // Crimson Red
    UNKNOWN("UNKNOWN", "Unknown", "#9E9E9E");       // Slate Grey

    val isCellularService: Boolean
        get() = this == GEN_5G || this == GEN_4G || this == GEN_3G || this == GEN_2G

    val generationRank: Int
        get() = when (this) {
            GEN_5G -> 5
            GEN_4G -> 4
            GEN_3G -> 3
            GEN_2G -> 2
            OUT_OF_SERVICE -> 0
            UNKNOWN -> -1
        }

    companion object {
        /**
         * Resolves normalized generation from Android TelephonyManager network type int.
         * Never classifies UNKNOWN as 2G or as Out of Service.
         */
        fun fromNetworkType(typeInt: Int?, nrState: String? = null): NetworkGeneration {
            if (nrState == "SA" || nrState == "NSA") return GEN_5G
            if (typeInt == null) return UNKNOWN

            return when (typeInt) {
                TelephonyManager.NETWORK_TYPE_NR -> GEN_5G
                TelephonyManager.NETWORK_TYPE_LTE,
                19 /* TelephonyManager.NETWORK_TYPE_LTE_CA */ -> GEN_4G
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_EVDO_B,
                14 /* TelephonyManager.NETWORK_TYPE_EHRPD */,
                17 /* TelephonyManager.NETWORK_TYPE_TD_SCDMA */ -> GEN_3G
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                16 /* TelephonyManager.NETWORK_TYPE_GSM */,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_IDEN -> GEN_2G
                TelephonyManager.NETWORK_TYPE_UNKNOWN -> UNKNOWN
                else -> UNKNOWN
            }
        }

        fun fromString(str: String?): NetworkGeneration {
            val upper = str?.uppercase()?.trim() ?: return UNKNOWN
            return when {
                upper == "5G" || upper == "5G_NR" || upper.contains("5G") || upper.contains("NR") -> GEN_5G
                upper == "4G" || upper == "LTE" || upper.contains("4G") || upper.contains("LTE") -> GEN_4G
                upper == "3G" || upper.contains("3G") || upper.contains("WCDMA") || upper.contains("UMTS") || upper.contains("HSPA") -> GEN_3G
                upper == "2G" || upper.contains("2G") || upper.contains("GSM") || upper.contains("EDGE") || upper.contains("GPRS") -> GEN_2G
                upper == "OOS" || upper.contains("OUT_OF_SERVICE") || upper.contains("NO_SERVICE") -> OUT_OF_SERVICE
                else -> UNKNOWN
            }
        }

        fun getDetailedTechName(typeInt: Int?, nrState: String? = null): String {
            if (nrState == "SA") return "5G NR (SA)"
            if (nrState == "NSA") return "5G NR (NSA EN-DC)"
            if (typeInt == null) return "UNKNOWN"

            return when (typeInt) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
                TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                19 -> "4G LTE-A (CA)"
                TelephonyManager.NETWORK_TYPE_HSPAP -> "3G HSPA+"
                TelephonyManager.NETWORK_TYPE_HSPA -> "3G HSPA"
                TelephonyManager.NETWORK_TYPE_HSDPA -> "3G HSDPA"
                TelephonyManager.NETWORK_TYPE_HSUPA -> "3G HSUPA"
                TelephonyManager.NETWORK_TYPE_UMTS -> "3G WCDMA/UMTS"
                17 -> "3G TD-SCDMA"
                TelephonyManager.NETWORK_TYPE_EVDO_0 -> "3G EV-DO Rev 0"
                TelephonyManager.NETWORK_TYPE_EVDO_A -> "3G EV-DO Rev A"
                TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G EV-DO Rev B"
                14 -> "3G eHRPD"
                TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE (EGPRS)"
                TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"
                16 -> "2G GSM"
                TelephonyManager.NETWORK_TYPE_CDMA -> "2G CDMA (IS-95)"
                TelephonyManager.NETWORK_TYPE_1xRTT -> "2G 1xRTT"
                TelephonyManager.NETWORK_TYPE_IDEN -> "2G iDEN"
                TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
                else -> "UNKNOWN ($typeInt)"
            }
        }

        fun getServiceStateName(stateInt: Int?): String {
            return when (stateInt) {
                0 -> "IN_SERVICE"
                1 -> "OUT_OF_SERVICE"
                2 -> "EMERGENCY_ONLY"
                3 -> "POWER_OFF"
                else -> "UNKNOWN"
            }
        }

        fun getDataStateName(stateInt: Int?): String {
            return when (stateInt) {
                TelephonyManager.DATA_CONNECTED -> "CONNECTED"
                TelephonyManager.DATA_CONNECTING -> "CONNECTING"
                TelephonyManager.DATA_DISCONNECTED -> "DISCONNECTED"
                TelephonyManager.DATA_SUSPENDED -> "SUSPENDED"
                TelephonyManager.DATA_UNKNOWN -> "UNKNOWN"
                else -> "UNKNOWN"
            }
        }

        fun getCallStateName(stateInt: Int?): String {
            return when (stateInt) {
                TelephonyManager.CALL_STATE_IDLE -> "IDLE"
                TelephonyManager.CALL_STATE_RINGING -> "RINGING"
                TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
                else -> "IDLE"
            }
        }
    }
}
