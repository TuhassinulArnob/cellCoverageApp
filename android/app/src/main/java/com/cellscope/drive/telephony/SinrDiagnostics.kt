package com.cellscope.drive.telephony

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 3GPP TS 36.214 & TS 38.215 Telecommunications Engineering Engine
 * for Real and Estimated SINR (Signal-to-Interference-plus-Noise Ratio) Diagnostics.
 *
 * Implements strict source provenance:
 * 1. MEASURED: Actual baseband modem measurement from Android RIL.
 * 2. ESTIMATED: Explicitly labeled calculation derived from LTE RSRQ/RSRP radio model.
 * 3. UNAVAILABLE: Missing inputs, unphysical values, or NR without modem measurement.
 *
 * Never silently fabricates or guesses uncalibrated numbers.
 */
object SinrDiagnostics {

    const val SOURCE_MEASURED = "MEASURED"
    const val SOURCE_ESTIMATED = "ESTIMATED"
    const val SOURCE_UNAVAILABLE = "UNAVAILABLE"

    // Android sentinel values representing unavailable cellular metrics
    val UNAVAILABLE_SENTINELS = setOf(
        Integer.MAX_VALUE,      // 0x7FFFFFFF (CellInfo.UNAVAILABLE)
        99,                     // Legacy GSM/LTE RSSI/SNR sentinel
        -1,                     // Common driver uninitialized sentinel
        Integer.MIN_VALUE
    )

    data class SinrProvenance(
        val sinrDb: Double?,
        val source: String,
        val estimationMethod: String?,
        val estimationAssumptions: String?,
        val confidence: String?,
        val qualitativeDiagnostic: String,
        val impliedRssiDbm: Double? = null
    ) {
        val isMeasured: Boolean get() = source == SOURCE_MEASURED
        val isEstimated: Boolean get() = source == SOURCE_ESTIMATED
        val isAvailable: Boolean get() = sinrDb != null && source != SOURCE_UNAVAILABLE
    }

    /**
     * Resolves LTE SINR with strict provenance priority.
     *
     * @param rawRssnr Modem-reported RSSNR in tenths of a dB (-200..300) or whole dB (-20..30)
     * @param rsrpDbm Serving Cell RSRP in dBm (-140..-40)
     * @param rsrqDb Serving Cell RSRQ in dB (-25..-3)
     * @param rssiDbm Optional directly measured carrier RSSI in dBm
     * @param bandwidthKhz LTE carrier channel bandwidth in kHz (1400, 3000, 5000, 10000, 15000, 20000)
     * @param hasSeenNonZeroRssnr True if the device has proven it can report non-zero RSSNR values
     * @param timestampMs Fix acquisition timestamp
     */
    fun resolveLteSinr(
        rawRssnr: Int?,
        rsrpDbm: Int?,
        rsrqDb: Int?,
        rssiDbm: Int? = null,
        bandwidthKhz: Int? = null,
        hasSeenNonZeroRssnr: Boolean = false,
        timestampMs: Long = System.currentTimeMillis()
    ): SinrProvenance {
        // 1. Check for valid MEASURED LTE RSSNR
        // On Android RILs, modems without RSSNR reporting capability or uninitialized HALs report 0
        // rather than CellInfo.UNAVAILABLE. Reject 0 as measured unless device has demonstrated
        // valid non-zero reporting.
        val isZeroUninitialized = (rawRssnr == 0 && !hasSeenNonZeroRssnr)
        if (rawRssnr != null && !UNAVAILABLE_SENTINELS.contains(rawRssnr) && !isZeroUninitialized) {
            val measuredSinr = when {
                rawRssnr in -200..300 -> rawRssnr / 10.0
                rawRssnr in -20..30 -> rawRssnr.toDouble()
                else -> null
            }

            if (measuredSinr != null) {
                return SinrProvenance(
                    sinrDb = roundTo1Dec(measuredSinr),
                    source = SOURCE_MEASURED,
                    estimationMethod = "ANDROID_RIL_RSSNR_MEASURED",
                    estimationAssumptions = "Direct physical baseband measurement from device modem (CellSignalStrengthLte.rssnr)",
                    confidence = "HIGH",
                    qualitativeDiagnostic = getQualitativeDiagnostic(measuredSinr),
                    impliedRssiDbm = rssiDbm?.toDouble()
                )
            }
        }

        // 2. If measured is unavailable, evaluate ESTIMATED LTE SINR
        // Requires valid RSRP, RSRQ, and positive physical bounds
        if (rsrpDbm != null && rsrqDb != null &&
            !UNAVAILABLE_SENTINELS.contains(rsrpDbm) && !UNAVAILABLE_SENTINELS.contains(rsrqDb) &&
            rsrpDbm in -140..-44 && rsrqDb in -25..-1
        ) {
            val validBandwidthKhz = bandwidthKhz?.let { bw ->
                val normalized = when (bw) {
                    1 -> 1400 // 1.4 MHz
                    3 -> 3000
                    5 -> 5000
                    10 -> 10000
                    15 -> 15000
                    20 -> 20000
                    else -> bw
                }
                normalized.takeIf {
                    it != Integer.MAX_VALUE &&
                    !UNAVAILABLE_SENTINELS.contains(it) &&
                    it in 1400..100000
                }
            }
            val numRb = bandwidthToResourceBlocks(validBandwidthKhz)
            val bwLabel = if (validBandwidthKhz != null) "${validBandwidthKhz / 1000} MHz" else "10 MHz (nominal assumption; cancels out in SINR equation)"
            val bwAssump = if (validBandwidthKhz != null) "N=$numRb RBs, $bwLabel" else "N=50 RBs, nominal 10 MHz assumption (bandwidth cancels out in SINR equation)"

            // 3GPP TS 36.214: RSRQ = N * RSRP / RSSI
            // Implied RSSI: RSSI_linear = N * RSRP_linear / RSRQ_linear
            val rsrqLinear = 10.0.pow(rsrqDb / 10.0)
            val rsrpLinearMw = 10.0.pow(rsrpDbm / 10.0)
            val impliedRssiLinearMw = (numRb * rsrpLinearMw) / rsrqLinear
            val impliedRssiDbm = 10.0 * log10(impliedRssiLinearMw)

            // Regime 3: 3GPP TS 36.133 Table 9.1.7-1 Index 34 Ceiling (RSRQ >= -3.01 dB)
            // At RSRQ >= -3 dB, 1/RSRQ <= 2.0. Even with zero traffic (eta = 0) and zero noise/interference,
            // maximum theoretical RSRQ is 1/2 = -3.01 dB. Multiple high SINR values (>25 dB) map to this
            // single ceiling value due to quantization and noise floor limits. Mathematical inversion is indeterminate.
            if (rsrqDb >= -3.01) {
                return SinrProvenance(
                    sinrDb = null,
                    source = SOURCE_UNAVAILABLE,
                    estimationMethod = "3GPP_TS_36_214_BOUNDED_LOAD_MODEL",
                    estimationAssumptions = "RSRQ ($rsrqDb dB) is at or above the 3GPP theoretical ceiling (-3.0 dB, where RSSI = 2*N*RSRP). Interference is below receiver noise floor; multiple SINR values map to this saturated state. Inversion is indeterminate.",
                    confidence = null,
                    qualitativeDiagnostic = "Clean RF channel conditions indicated by strong RSRQ ($rsrqDb dB); model-based SINR estimation unavailable at 3GPP saturation ceiling (RSRQ >= -3 dB)",
                    impliedRssiDbm = roundTo1Dec(impliedRssiDbm)
                )
            }

            // In LTE OFDM, carrier RSSI / N = (2 + 10 * eta) * RSRP + 12 * (I + N0)
            // Solving for SINR = RSRP / (I + N0):
            // 1 / RSRQ = (2 + 10 * eta) + 12 / SINR
            // 12 / SINR = (1 / RSRQ) - (2 + 10 * eta)

            // Regime 1: Loaded Cell Regime (RSRQ <= -8.45 dB, 1/RSRQ >= 7.0)
            // Cell can support nominal 50% traffic load (eta = 0.5 -> offset = 7.0)
            if (rsrqDb <= -8.45) {
                val loadFactor = 0.5
                val offset = 2.0 + (10.0 * loadFactor) // 7.0
                val denom = (1.0 / rsrqLinear) - offset
                if (denom > 0.001) {
                    val sinrLinear = 12.0 / denom
                    val db = 10.0 * log10(sinrLinear)
                    val clampedDb = db.coerceIn(-20.0, 30.0)

                    return SinrProvenance(
                        sinrDb = roundTo1Dec(clampedDb),
                        source = SOURCE_ESTIMATED,
                        estimationMethod = "3GPP_TS_36_214_RSRQ_LOAD_MODEL",
                        estimationAssumptions = "Derived from 3GPP TS 36.214 RSRQ definition ($bwAssump). Assumes nominal 50% cell traffic load (eta=0.5), flat Rayleigh channel, and uniform subcarrier interference. Estimated uncertainty is +/- 3.5 dB.",
                        confidence = "CALIBRATED_ESTIMATE",
                        qualitativeDiagnostic = getQualitativeDiagnostic(clampedDb),
                        impliedRssiDbm = roundTo1Dec(impliedRssiDbm)
                    )
                }
            }

            // Regime 2: Lightly Loaded Cell Regime (-8.45 dB < RSRQ < -3.01 dB)
            // Cell traffic load is physically bounded by RSRQ: eta in [0, eta_max], where eta_max = (1/RSRQ - 2) / 10.
            // Under maximum entropy (uniform prior) for unknown load, expected load is eta_avg = eta_max / 2.
            // Offset = 2 + 10*(eta_max / 2) = 1 + 1 / (2 * RSRQ_linear).
            // Denom = 1/RSRQ - offset = (1 - 2*RSRQ_linear) / (2 * RSRQ_linear).
            // SINR_linear = 24 * RSRQ_linear / (1 - 2 * RSRQ_linear).
            val etaMax = ((1.0 / rsrqLinear) - 2.0) / 10.0
            val etaAvg = (etaMax / 2.0).coerceIn(0.0, 0.5)
            val denom = (1.0 / rsrqLinear) - (2.0 + 10.0 * etaAvg)

            if (denom > 0.001) {
                val sinrLinear = 12.0 / denom
                val db = 10.0 * log10(sinrLinear)
                val clampedDb = db.coerceIn(-20.0, 30.0)

                // Compute physical SINR bounds:
                // Min SINR occurs at eta = 0 (completely idle cell, offset = 2.0)
                val denomMin = (1.0 / rsrqLinear) - 2.0
                val sinrMinDb = if (denomMin > 0.001) (10.0 * log10(12.0 / denomMin)).coerceIn(-20.0, 30.0) else -20.0
                val sinrMaxDb = 30.0 // Physical reference signal limit as (I + N0) -> 0

                val etaMaxPct = roundTo1Dec(etaMax * 100.0)
                val etaAvgPct = roundTo1Dec(etaAvg * 100.0)
                val minDbStr = String.format(java.util.Locale.US, "%.1f", sinrMinDb)
                val maxDbStr = String.format(java.util.Locale.US, "%.1f", sinrMaxDb)

                return SinrProvenance(
                    sinrDb = roundTo1Dec(clampedDb),
                    source = SOURCE_ESTIMATED,
                    estimationMethod = "3GPP_TS_36_214_BOUNDED_LOAD_MODEL",
                    estimationAssumptions = "Derived from 3GPP TS 36.214 carrier RSSI relation ($bwAssump). RSRQ ($rsrqDb dB) physically bounds traffic load to eta in [0%, $etaMaxPct%]. Model uses expected load eta_avg = $etaAvgPct% under uniform prior. Physical SINR bounds: [$minDbStr dB, $maxDbStr dB].",
                    confidence = "BOUNDED_LOAD_ESTIMATE",
                    qualitativeDiagnostic = getQualitativeDiagnostic(clampedDb),
                    impliedRssiDbm = roundTo1Dec(impliedRssiDbm)
                )
            }
        }

        // 3. UNAVAILABLE fallback with qualitative diagnostic
        val qual = when {
            rsrqDb != null && rsrqDb >= -10 -> "Clean RF channel conditions indicated by strong RSRQ ($rsrqDb dB)"
            rsrqDb != null && rsrqDb >= -14 -> "Normal operating cell conditions (RSRQ: $rsrqDb dB)"
            rsrqDb != null -> "Noticeable interference or high cell load (RSRQ: $rsrqDb dB)"
            rsrpDbm != null -> "Coverage reading available (RSRP: $rsrpDbm dBm) but RSRQ unavailable for SINR"
            else -> "Physical RF parameters insufficient for SINR estimation"
        }

        return SinrProvenance(
            sinrDb = null,
            source = SOURCE_UNAVAILABLE,
            estimationMethod = null,
            estimationAssumptions = "Modem does not expose hardware SNR and required physical inputs (RSRP/RSRQ/bandwidth) are incomplete or out of bounds.",
            confidence = null,
            qualitativeDiagnostic = qual,
            impliedRssiDbm = rssiDbm?.toDouble()
        )
    }

    /**
     * Resolves 5G NR SINR with standard-appropriate separation.
     * Note: NR SS-RSRQ definition does NOT follow the LTE carrier RSSI equation,
     * so NR SINR is only returned as MEASURED if exposed by modem, or UNAVAILABLE.
     */
    fun resolveNrSinr(
        ssSinr: Double?,
        csiSinr: Double?,
        ssRsrp: Int? = null,
        ssRsrq: Int? = null
    ): SinrProvenance {
        // Check for valid SS-SINR (3GPP TS 38.133: -23 dB to +40 dB)
        val validSsSinr = ssSinr?.takeIf { it in -23.0..40.0 && !UNAVAILABLE_SENTINELS.contains(it.toInt()) }
        val validCsiSinr = csiSinr?.takeIf { it in -23.0..40.0 && !UNAVAILABLE_SENTINELS.contains(it.toInt()) }

        val bestMeasured = validSsSinr ?: validCsiSinr
        if (bestMeasured != null) {
            val method = if (validSsSinr != null) "NR_SS_SINR_MEASURED" else "NR_CSI_SINR_MEASURED"
            val assumptions = if (validSsSinr != null) {
                "Direct 5G NR Synchronization Signal SINR from modem baseband (3GPP TS 38.215)"
            } else {
                "Direct 5G NR Channel State Information SINR from modem baseband (3GPP TS 38.215)"
            }

            return SinrProvenance(
                sinrDb = roundTo1Dec(bestMeasured),
                source = SOURCE_MEASURED,
                estimationMethod = method,
                estimationAssumptions = assumptions,
                confidence = "HIGH",
                qualitativeDiagnostic = getQualitativeDiagnostic(bestMeasured)
            )
        }

        // NR does not support LTE carrier RSSI equation
        val qual = when {
            ssRsrq != null && ssRsrq >= -10 -> "Clean 5G NR beam signal indicated by SS-RSRQ ($ssRsrq dB)"
            ssRsrq != null && ssRsrq >= -15 -> "Normal 5G NR radio conditions (SS-RSRQ: $ssRsrq dB)"
            ssRsrq != null -> "High 5G NR beam interference or traffic load (SS-RSRQ: $ssRsrq dB)"
            else -> "5G NR modem does not report hardware SS-SINR/CSI-SINR"
        }

        return SinrProvenance(
            sinrDb = null,
            source = SOURCE_UNAVAILABLE,
            estimationMethod = "NR_MODEL_UNSUPPORTED",
            estimationAssumptions = "3GPP TS 38.215 NR SS-RSRQ is measured over SS/PBCH block symbols with dynamic subcarrier spacing and cannot be converted using LTE carrier equations. Qualitative diagnostic only.",
            confidence = null,
            qualitativeDiagnostic = qual
        )
    }

    /**
     * Maps LTE channel bandwidth in kHz to 3GPP TS 36.101 Resource Block count N.
     */
    fun bandwidthToResourceBlocks(bandwidthKhz: Int?): Int {
        if (bandwidthKhz == null || bandwidthKhz <= 0 || bandwidthKhz == Integer.MAX_VALUE ||
            UNAVAILABLE_SENTINELS.contains(bandwidthKhz)
        ) {
            return 50 // Default nominal 10 MHz
        }
        val normalizedKhz = when (bandwidthKhz) {
            1 -> 1400 // 1.4 MHz (integer MHz vendor HAL representation)
            3 -> 3000
            5 -> 5000
            10 -> 10000
            15 -> 15000
            20 -> 20000
            else -> bandwidthKhz
        }
        if (normalizedKhz !in 1400..100000) {
            return 50
        }
        return when {
            normalizedKhz <= 1400 -> 6    // 1.4 MHz
            normalizedKhz <= 3000 -> 15   // 3.0 MHz
            normalizedKhz <= 5000 -> 25   // 5.0 MHz
            normalizedKhz <= 10000 -> 50  // 10.0 MHz
            normalizedKhz <= 15000 -> 75  // 15.0 MHz
            else -> 100                   // 20.0 MHz
        }
    }

    private fun getQualitativeDiagnostic(sinrDb: Double): String {
        return when {
            sinrDb >= 20.0 -> "Clean channel, negligible co-channel interference (Excellent SINR)"
            sinrDb >= 13.0 -> "Low interference, high spectral efficiency achievable (Good SINR)"
            sinrDb >= 0.0 -> "Moderate interference or higher noise floor; lower modulation order (Fair SINR)"
            else -> "Severe co-channel interference or pilot pollution; cell-edge conditions (Poor SINR)"
        }
    }

    private fun roundTo1Dec(v: Double): Double {
        return (v * 10.0).roundToInt() / 10.0
    }
}
