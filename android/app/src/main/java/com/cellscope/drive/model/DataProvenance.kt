package com.cellscope.drive.model

/**
 * Standardized Data Provenance Classification System (P9).
 * Every metric, chart, and export in CellScope 5G must explicitly state its provenance.
 * Never invent results to make the interface look complete.
 */
enum class DataProvenance(val tag: String, val description: String) {
    /** Directly collected by handset hardware sensors or Android Telephony APIs */
    MEASURED("MEASURED", "Directly collected by device telephony/sensors"),

    /** Calculated from identified measurements using a documented mathematical formula */
    DERIVED("DERIVED", "Calculated from valid measurements using documented formula"),

    /** Model-based estimate with documented assumptions and spatial/RF uncertainty */
    ESTIMATED("ESTIMATED", "Model-based estimate with documented assumptions and uncertainty"),

    /** User-configured scenario input or nominal parameter */
    ASSUMED("ASSUMED", "User-configured scenario input or nominal standard parameter"),

    /** Not exposed by the current device, telephony API, or dataset */
    UNAVAILABLE("UNAVAILABLE", "Not exposed by device hardware, telephony API, or dataset"),

    /** Requires future implementation, active testing, additional RF hardware, or operator-side OSS/ENM counters */
    FURTHER_WORK_REQUIRED("FURTHER WORK REQUIRED", "Requires operator OSS/ENM counters, RF hardware, or active testing"),

    /** Available spatial or temporal survey data does not support a reliable conclusion */
    INSUFFICIENT_EVIDENCE("INSUFFICIENT EVIDENCE", "Available survey data does not support a reliable conclusion");

    fun badge(): String = "[$tag]"

    companion object {
        fun prbUtilizationStatus(hasOperatorCounters: Boolean = false): String {
            return if (hasOperatorCounters) {
                "[MEASURED] eNodeB MAC Scheduler Counter"
            } else {
                "[FURTHER WORK REQUIRED] Operator OSS/ENM counters required"
            }
        }

        fun sinrStatus(sinr: Double?): String {
            return if (sinr != null) {
                "[MEASURED] Android CellSignalStrengthLte"
            } else {
                "[UNAVAILABLE] Current device/telephony API did not expose SINR"
            }
        }

        fun dopplerStatus(): String {
            return "[FURTHER WORK REQUIRED] Requires radial velocity vector vr and carrier frequency fc"
        }

        fun operatorCapacityStatus(hasOperatorData: Boolean = false): String {
            return if (hasOperatorData) {
                "[MEASURED] Operator Core Network telemetry"
            } else {
                "[FURTHER WORK REQUIRED] Operator capacity counters required"
            }
        }
    }
}
