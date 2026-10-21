package com.cellscope.drive.telephony

import java.util.Locale

/**
 * Unified, timestamped network-state model (P2).
 * Strictly distinguishes SIM operator, registered network, and serving cell operator.
 * Prevents stale operator information from persisting after SIM or RAT switching.
 */
data class OperatorIdentity(
    val simOperatorName: String? = null,
    val simOperatorNumeric: String? = null, // MCC+MNC e.g. "47001"
    val simCountryIso: String? = null,
    val simSlotIndex: Int? = null,
    val networkOperatorName: String? = null,
    val networkOperatorNumeric: String? = null, // MCC+MNC e.g. "47001"
    val networkCountryIso: String? = null,
    val servingOperatorAlpha: String? = null,
    val servingOperatorNumeric: String? = null,
    val dataNetworkType: String = "UNKNOWN", // "LTE", "5G_NR", "UMTS", "GSM", etc.
    val isRoaming: Boolean = false,
    val servingPci: Int? = null,
    val tac: Int? = null,
    val eci: Int? = null,
    val earfcn: Int? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val serviceState: String = "IN_SERVICE", // IN_SERVICE, OUT_OF_SERVICE, EMERGENCY_ONLY, POWER_OFF
    val dataConnectionState: String = "CONNECTED",
    val dataSubscriptionId: Int? = null,
    val isStale: Boolean = false,
    val activeRat: String = "LTE"
) {
    val simResolvedName: String
        get() = BangladeshMccMnc.resolveOperatorName(simOperatorNumeric, simOperatorName)

    val networkResolvedName: String
        get() = BangladeshMccMnc.resolveOperatorName(networkOperatorNumeric, networkOperatorName)

    val servingResolvedName: String
        get() = if (!servingOperatorNumeric.isNullOrBlank() || !servingOperatorAlpha.isNullOrBlank()) {
            BangladeshMccMnc.resolveOperatorName(servingOperatorNumeric, servingOperatorAlpha)
        } else {
            networkResolvedName
        }

    fun getEffectiveDisplayOperator(): String {
        return when (serviceState.uppercase(Locale.US)) {
            "OUT_OF_SERVICE", "POWER_OFF" -> "No Service"
            "EMERGENCY_ONLY" -> "Emergency Calls Only"
            else -> {
                val base = if (servingResolvedName.isNotBlank() && !servingResolvedName.startsWith("Unknown")) {
                    servingResolvedName
                } else if (networkResolvedName.isNotBlank() && !networkResolvedName.startsWith("Unknown")) {
                    networkResolvedName
                } else {
                    "Updating..."
                }
                if (isStale && base != "Updating...") "$base (Stale)" else base
            }
        }
    }

    val roamingStatusText: String
        get() = when {
            isRoaming -> "Roaming: $networkResolvedName"
            simOperatorNumeric != null && networkOperatorNumeric != null && simOperatorNumeric != networkOperatorNumeric ->
                "National Roaming (SIM: $simResolvedName, Net: $networkResolvedName)"
            else -> "Home Network"
        }

    val derivedEnodebId: Int?
        get() = eci?.let { it / 256 }

    val derivedSectorId: Int?
        get() = eci?.let { it % 256 }

    val ecgi: String?
        get() {
            val plmn = servingOperatorNumeric ?: networkOperatorNumeric
            return if (plmn != null && plmn.length >= 5 && eci != null) {
                val clean = plmn.replace("-", "")
                val mcc = clean.take(3)
                val mnc = clean.substring(3)
                "$mcc-$mnc-$eci"
            } else null
        }

    val derivedBand: LteBandTable.FrequencyResult?
        get() = LteBandTable.resolveEarfcn(earfcn)

    val derivedBandText: String
        get() = derivedBand?.let {
            "Band ${it.bandNumber} (${it.designation}) • DL ${it.dlFrequencyMhz} MHz"
        } ?: (earfcn?.let { "EARFCN $it" } ?: "Carrier: Unmapped")

    fun getFreshnessText(currentTimeMs: Long = System.currentTimeMillis()): String {
        if (isStale) return "Stale (> 15s since scan)"
        val ageMs = (currentTimeMs - timestampMs).coerceAtLeast(0L)
        val ageSec = ageMs / 1000.0
        return when {
            ageSec < 1.0 -> "Fresh (just now)"
            ageSec < 15.0 -> String.format(Locale.US, "Fresh (%.1fs ago)", ageSec)
            ageSec < 60.0 -> String.format(Locale.US, "Lagging (%.1fs ago)", ageSec)
            else -> String.format(Locale.US, "Stale (%dm %ds ago)", ageMs / 60000, (ageMs % 60000) / 1000)
        }
    }
}

object BangladeshMccMnc {
    // Validated 3GPP Mobile Country Code for Bangladesh = 470
    const val BANGLADESH_MCC = "470"

    private val OPERATOR_MAP = mapOf(
        "47001" to "Grameenphone (GP)",
        "47002" to "Robi Axiata (Robi)",
        "47003" to "Banglalink (BL)",
        "47004" to "Teletalk Bangladesh (Teletalk)",
        "47005" to "Citycell (Historical CDMA)",
        "47007" to "Airtel Bangladesh (Merged with Robi)"
    )

    fun resolveOperatorName(mccMnc: String?, fallbackAlpha: String?): String {
        if (mccMnc.isNullOrBlank()) {
            return fallbackAlpha?.takeIf { it.isNotBlank() } ?: "Unknown / Unverified (Unbroadcast PLMN)"
        }
        val clean = mccMnc.trim().replace("-", "")
        val mapped = OPERATOR_MAP[clean]
        if (mapped != null) {
            val cleanAlpha = fallbackAlpha?.trim()
            // Treat RYZE, MyBL, and similar digital packages/sub-brands as commercial packages rather than mutating operator identity
            val isCommercialBrandLabel = cleanAlpha != null && (
                cleanAlpha.equals("RYZE", ignoreCase = true) ||
                cleanAlpha.equals("MyBL", ignoreCase = true) ||
                cleanAlpha.equals("BL", ignoreCase = true) ||
                cleanAlpha.equals("Banglalink", ignoreCase = true) ||
                cleanAlpha.equals("Robi", ignoreCase = true) ||
                cleanAlpha.equals("GP", ignoreCase = true) ||
                cleanAlpha.equals("Grameenphone", ignoreCase = true)
            )
            return if (!cleanAlpha.isNullOrBlank() && !mapped.contains(cleanAlpha, ignoreCase = true) && !isCommercialBrandLabel) {
                "$mapped ($cleanAlpha)"
            } else {
                mapped
            }
        }
        // Handle generic Bangladesh 470 PLMNs
        if (clean.startsWith(BANGLADESH_MCC) && clean.length >= 5) {
            val mnc = clean.substring(3)
            val name = fallbackAlpha?.takeIf { it.isNotBlank() } ?: "PLMN 470-$mnc"
            return "$name (BD 470-$mnc)"
        }
        return fallbackAlpha?.takeIf { it.isNotBlank() } ?: "Unknown / Unverified ($clean)"
    }

    fun isBangladeshMcc(mccMnc: String?): Boolean {
        val clean = mccMnc?.trim()?.replace("-", "")
        return clean?.startsWith(BANGLADESH_MCC) == true
    }
}
