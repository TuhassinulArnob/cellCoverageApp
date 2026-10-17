package com.cellscope.drive.telephony

import java.util.Locale

data class OperatorIdentity(
    val simOperatorName: String?,
    val simOperatorNumeric: String?, // MCC+MNC e.g. "47001"
    val simCountryIso: String?,
    val networkOperatorName: String?,
    val networkOperatorNumeric: String?, // MCC+MNC e.g. "47001"
    val networkCountryIso: String?,
    val dataNetworkType: String, // "LTE", "5G_NR", etc.
    val isRoaming: Boolean,
    val servingPci: Int?,
    val tac: Int?,
    val eci: Int?,
    val earfcn: Int?,
    val timestampMs: Long
) {
    val simResolvedName: String
        get() = BangladeshMccMnc.resolveOperatorName(simOperatorNumeric, simOperatorName)

    val networkResolvedName: String
        get() = BangladeshMccMnc.resolveOperatorName(networkOperatorNumeric, networkOperatorName)

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
        get() = if (networkOperatorNumeric != null && eci != null) {
            val mcc = networkOperatorNumeric.take(3)
            val mnc = networkOperatorNumeric.substring(3)
            "$mcc-$mnc-$eci"
        } else null

    val derivedBand: LteBandTable.FrequencyResult?
        get() = LteBandTable.resolveEarfcn(earfcn)

    val derivedBandText: String
        get() = derivedBand?.let {
            "Band ${it.bandNumber} (${it.designation}) • DL ${it.dlFrequencyMhz} MHz"
        } ?: (earfcn?.let { "EARFCN $it" } ?: "Carrier: Unmapped")

    fun getFreshnessText(currentTimeMs: Long = System.currentTimeMillis()): String {
        val ageMs = (currentTimeMs - timestampMs).coerceAtLeast(0L)
        val ageSec = ageMs / 1000.0
        return when {
            ageSec < 1.0 -> "Fresh (just now)"
            ageSec < 60.0 -> String.format(Locale.US, "%.1fs ago", ageSec)
            else -> String.format(Locale.US, "%dm %ds ago", ageMs / 60000, (ageMs % 60000) / 1000)
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
        "47005" to "Citycell (Historical CDMA)"
    )

    fun resolveOperatorName(mccMnc: String?, fallbackAlpha: String?): String {
        if (mccMnc.isNullOrBlank()) {
            return fallbackAlpha?.takeIf { it.isNotBlank() } ?: "Unknown Operator"
        }
        val clean = mccMnc.trim()
        val mapped = OPERATOR_MAP[clean]
        if (mapped != null) {
            return if (!fallbackAlpha.isNullOrBlank() && !mapped.contains(fallbackAlpha, ignoreCase = true)) {
                "$mapped ($fallbackAlpha)"
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
        return fallbackAlpha?.takeIf { it.isNotBlank() } ?: "PLMN $clean"
    }

    fun isBangladeshMcc(mccMnc: String?): Boolean {
        return mccMnc?.startsWith(BANGLADESH_MCC) == true
    }
}
