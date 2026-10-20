package com.cellscope.drive.telephony

import com.cellscope.drive.model.ObservedCell
import java.util.Locale

data class CellCarrierInfo(
    val rat: String,                      // "LTE", "5G_NR", "2G_GSM", or "3G_WCDMA"
    val bandNumber: Int,
    val bandName: String,
    val channelNumber: Int,               // EARFCN, NR-ARFCN, ARFCN, or UARFCN
    val dlCenterFreqMhz: Double,
    val ulCenterFreqMhz: Double?,
    val channelBandwidthMhz: Double,      // Effective bandwidth for plotting envelope
    val isNominalBandwidth: Boolean,
    val lowFreqMhz: Double,
    val highFreqMhz: Double,
    val duplexMode: String,
    val duplexSeparationMhz: Double? = null,
    val duplexDescription: String = "",
    val bandwidthDisplay: String = "",
    val classificationMethod: String = "3GPP TS Standard Allocation Rules"
)

object CellularBandResolver {

    /**
     * Resolves carrier frequency and channel geometry for an ObservedCell.
     * Strictly separates GSM ARFCN, UMTS UARFCN, LTE EARFCN, and 5G NR-ARFCN according to 3GPP standards.
     */
    fun resolve(cell: ObservedCell): CellCarrierInfo? {
        return when {
            cell.is5gNr || cell.nrArfcn != null || cell.channelType == "NR_ARFCN" -> resolveNr(cell)
            cell.isGsm || cell.channelType == "ARFCN" || cell.networkType == "2G_GSM" -> resolveGsm(cell)
            cell.isWcdma || cell.channelType == "UARFCN" || cell.networkType == "3G_WCDMA" -> resolveWcdma(cell)
            cell.isLte || cell.channelType == "EARFCN" || cell.networkType == "LTE" -> resolveLte(cell)
            cell.earfcn != null && !cell.isGsm && !cell.isWcdma -> resolveLte(cell)
            else -> null
        }
    }

    /**
     * Resolves carrier frequency and channel geometry for an LteMeasurementEntity.
     */
    fun resolveMeasurement(m: com.cellscope.drive.data.entity.LteMeasurementEntity): CellCarrierInfo? {
        val rat = m.servingRat ?: m.networkGen ?: m.networkType
        val chan = m.channelNumber ?: m.earfcn
        val chanType = m.channelType

        return when {
            rat.contains("5G", ignoreCase = true) || rat.contains("NR", ignoreCase = true) || chanType == "NR_ARFCN" -> {
                if (chan == null) null else resolveNr(ObservedCell(pci = m.servingPci ?: 0, nrArfcn = chan, servingRat = "5G", networkType = "5G_NR", channelType = "NR_ARFCN"))
            }
            rat.contains("2G", ignoreCase = true) || rat.contains("GSM", ignoreCase = true) || chanType == "ARFCN" -> {
                if (chan == null) null else resolveGsm(ObservedCell(pci = m.servingPci ?: 0, earfcn = chan, channelNumber = chan, servingRat = "2G", networkType = "2G_GSM", channelType = "ARFCN"))
            }
            rat.contains("3G", ignoreCase = true) || rat.contains("WCDMA", ignoreCase = true) || rat.contains("UMTS", ignoreCase = true) || chanType == "UARFCN" -> {
                if (chan == null) null else resolveWcdma(ObservedCell(pci = m.servingPci ?: 0, earfcn = chan, channelNumber = chan, servingRat = "3G", networkType = "3G_WCDMA", channelType = "UARFCN"))
            }
            else -> {
                if (chan == null) null else resolveLte(ObservedCell(pci = m.servingPci ?: 0, earfcn = chan, channelNumber = chan, servingRat = "4G", networkType = "LTE", channelType = "EARFCN", bandwidthKhz = m.bandwidthKhz))
            }
        }
    }

    private data class GsmBandConfig(
        val bandNum: Int,
        val bandName: String,
        val dlFreq: Double,
        val ulFreq: Double,
        val method: String
    )

    /**
     * Resolves 2G GSM carrier using 3GPP TS 05.05 / TS 45.005 ARFCN formulas.
     * Prevents GSM ARFCN from ever being mislabeled as LTE Band 40 or 39050.
     * Accurately distinguishes P-GSM 900, E-GSM 900, GSM 850, and DCS 1800.
     */
    fun resolveGsm(cell: ObservedCell): CellCarrierInfo? {
        val arfcn = cell.channelNumber ?: cell.earfcn?.takeIf { it in 0..1023 } ?: return null
        if (arfcn !in 0..1023) return null

        val config = when (arfcn) {
            in 1..124 -> {
                val dl = 935.0 + 0.2 * arfcn
                val ul = 890.0 + 0.2 * arfcn
                GsmBandConfig(900, "GSM 900 (P-GSM)", dl, ul, "3GPP TS 45.005 P-GSM 900 Channel Allocation")
            }
            0 -> {
                val dl = 935.0
                val ul = 890.0
                GsmBandConfig(900, "GSM 900 (E-GSM)", dl, ul, "3GPP TS 45.005 E-GSM 900 Channel Allocation (ARFCN 0)")
            }
            in 975..1023 -> {
                val dl = 925.0 + 0.2 * (arfcn - 974)
                val ul = 880.0 + 0.2 * (arfcn - 974)
                GsmBandConfig(900, "GSM 900 (E-GSM)", dl, ul, "3GPP TS 45.005 E-GSM 900 Channel Allocation")
            }
            in 128..251 -> {
                val dl = 869.2 + 0.2 * (arfcn - 128)
                val ul = 824.2 + 0.2 * (arfcn - 128)
                GsmBandConfig(850, "GSM 850", dl, ul, "3GPP TS 45.005 GSM 850 Channel Allocation")
            }
            in 512..885 -> {
                val dl = 1805.2 + 0.2 * (arfcn - 512)
                val ul = 1710.2 + 0.2 * (arfcn - 512)
                GsmBandConfig(1800, "DCS 1800 (GSM 1800)", dl, ul, "3GPP TS 45.005 DCS 1800 Channel Allocation")
            }
            else -> {
                GsmBandConfig(0, "GSM Carrier (ARFCN $arfcn)", 900.0, 890.0, "3GPP TS 45.005 Generic Allocation")
            }
        }

        val roundedDl = Math.round(config.dlFreq * 10.0) / 10.0
        val roundedUl = Math.round(config.ulFreq * 10.0) / 10.0
        val bwMhz = 0.2 // Standard GSM channel raster: 200 kHz
        val duplexSep = Math.round(kotlin.math.abs(roundedDl - roundedUl) * 10.0) / 10.0

        return CellCarrierInfo(
            rat = "2G_GSM",
            bandNumber = config.bandNum,
            bandName = config.bandName,
            channelNumber = arfcn,
            dlCenterFreqMhz = roundedDl,
            ulCenterFreqMhz = roundedUl,
            channelBandwidthMhz = bwMhz,
            isNominalBandwidth = true,
            lowFreqMhz = roundedDl - 0.1,
            highFreqMhz = roundedDl + 0.1,
            duplexMode = "FDD",
            duplexSeparationMhz = duplexSep,
            duplexDescription = "FDD — ${String.format(Locale.US, "%.1f", duplexSep)} MHz duplex separation",
            bandwidthDisplay = "200 kHz (GSM Standard Raster)",
            classificationMethod = config.method
        )
    }

    /**
     * Resolves 3G UMTS/WCDMA carrier using 3GPP TS 25.101 UARFCN formulas.
     */
    fun resolveWcdma(cell: ObservedCell): CellCarrierInfo? {
        val uarfcn = cell.channelNumber ?: cell.earfcn?.takeIf { it in 0..16383 } ?: return null
        if (uarfcn !in 0..16383) return null

        val (bandNum, bandName, dlFreq) = when (uarfcn) {
            in 10562..10838 -> Triple(1, "Band 1 (2100 MHz WCDMA)", uarfcn / 5.0)
            in 2937..3088 -> Triple(8, "Band 8 (900 MHz WCDMA)", uarfcn / 5.0)
            else -> Triple(0, "WCDMA Carrier (UARFCN $uarfcn)", uarfcn / 5.0)
        }

        val roundedDl = Math.round(dlFreq * 10.0) / 10.0
        val bwMhz = 5.0 // 3GPP WCDMA standard 5 MHz channel
        val duplexSep = when (bandNum) {
            1 -> 190.0
            8 -> 45.0
            else -> null
        }
        val duplexDesc = if (duplexSep != null) {
            "FDD — ${String.format(Locale.US, "%.1f", duplexSep)} MHz duplex separation"
        } else {
            "FDD — UL carrier unexposed"
        }

        return CellCarrierInfo(
            rat = "3G_WCDMA",
            bandNumber = bandNum,
            bandName = bandName,
            channelNumber = uarfcn,
            dlCenterFreqMhz = roundedDl,
            ulCenterFreqMhz = duplexSep?.let { roundedDl - it },
            channelBandwidthMhz = bwMhz,
            isNominalBandwidth = true,
            lowFreqMhz = roundedDl - 2.5,
            highFreqMhz = roundedDl + 2.5,
            duplexMode = "FDD",
            duplexSeparationMhz = duplexSep,
            duplexDescription = duplexDesc,
            bandwidthDisplay = "5.0 MHz (3GPP WCDMA Channel)",
            classificationMethod = "3GPP TS 25.101 UARFCN Allocation Rules"
        )
    }

    /**
     * Resolves LTE carrier using 3GPP TS 36.101 EARFCN formulas.
     */
    fun resolveLte(cell: ObservedCell): CellCarrierInfo? {
        if (cell.isGsm || cell.isWcdma) return null
        val earfcn = cell.channelNumber ?: cell.earfcn ?: return null
        val freqResult = LteBandTable.resolveEarfcn(earfcn) ?: return null

        val bwKhz = cell.bandwidthKhz
        // Invalidate bandwidth sentinels: <= 0, > 100000, or Integer.MAX_VALUE
        val (bwMhz, isNominal) = if (bwKhz != null && bwKhz in 1400..100000 && bwKhz != Int.MAX_VALUE) {
            Pair(bwKhz / 1000.0, false)
        } else {
            // Nominal bandwidth default of 10.0 MHz for envelope display when unexposed
            Pair(10.0, true)
        }

        val fCenter = freqResult.dlFrequencyMhz
        val fLow = Math.round((fCenter - (bwMhz / 2.0)) * 10.0) / 10.0
        val fHigh = Math.round((fCenter + (bwMhz / 2.0)) * 10.0) / 10.0

        val duplexMode = freqResult.duplexMode.name
        val duplexSep = if (duplexMode == "TDD") {
            0.0
        } else if (freqResult.ulFrequencyMhz != null) {
            Math.round(kotlin.math.abs(fCenter - freqResult.ulFrequencyMhz) * 10.0) / 10.0
        } else null

        val duplexDesc = when {
            duplexMode == "TDD" -> "TDD — carrier shares time resources (UL/DL on same frequency)"
            duplexSep != null -> "FDD — ${String.format(Locale.US, "%.1f", duplexSep)} MHz duplex separation"
            else -> "FDD — UL carrier unexposed"
        }

        val bwDisplay = if (isNominal) {
            "Unreported / Android Radio API limitation"
        } else {
            "${String.format(Locale.US, "%.1f", bwMhz)} MHz"
        }

        return CellCarrierInfo(
            rat = "LTE",
            bandNumber = freqResult.bandNumber,
            bandName = "Band ${freqResult.bandNumber} (${freqResult.designation})",
            channelNumber = earfcn,
            dlCenterFreqMhz = fCenter,
            ulCenterFreqMhz = if (duplexMode == "TDD") fCenter else freqResult.ulFrequencyMhz,
            channelBandwidthMhz = bwMhz,
            isNominalBandwidth = isNominal,
            lowFreqMhz = fLow,
            highFreqMhz = fHigh,
            duplexMode = duplexMode,
            duplexSeparationMhz = duplexSep,
            duplexDescription = duplexDesc,
            bandwidthDisplay = bwDisplay,
            classificationMethod = "3GPP TS 36.101 EARFCN Table Allocation Rules"
        )
    }

    /**
     * Resolves 5G NR carrier using 3GPP TS 38.101-1 / TS 38.101-2 Global Frequency Raster.
     */
    fun resolveNr(cell: ObservedCell): CellCarrierInfo? {
        val nrArfcn = cell.nrArfcn ?: return null

        val fCenter = when {
            nrArfcn in 0..599999 -> nrArfcn * 0.005
            nrArfcn in 600000..2016666 -> 3000.0 + 0.015 * (nrArfcn - 600000)
            nrArfcn in 2016667..3279165 -> 24250.08 + 0.060 * (nrArfcn - 2016667)
            else -> return null
        }

        val roundedFreq = Math.round(fCenter * 100.0) / 100.0

        val (bandNum, bandName, duplex) = when {
            roundedFreq in 2110.0..2170.0 -> Triple(1, "n1 (2100 MHz)", "FDD")
            roundedFreq in 1930.0..1990.0 -> Triple(2, "n2 (1900 MHz)", "FDD")
            roundedFreq in 1805.0..1880.0 -> Triple(3, "n3 (1800 MHz)", "FDD")
            roundedFreq in 869.0..894.0 -> Triple(5, "n5 (850 MHz)", "FDD")
            roundedFreq in 925.0..960.0 -> Triple(8, "n8 (900 MHz)", "FDD")
            roundedFreq in 758.0..803.0 -> Triple(28, "n28 (700 MHz APT)", "FDD")
            roundedFreq in 2300.0..2400.0 -> Triple(40, "n40 (2300 MHz)", "TDD")
            roundedFreq in 2496.0..2690.0 -> Triple(41, "n41 (2500 MHz BRS)", "TDD")
            roundedFreq in 3300.0..3800.0 -> Triple(78, "n78 (3500 MHz Mid-Band)", "TDD")
            roundedFreq in 3300.0..4200.0 -> Triple(77, "n77 (3700 MHz C-Band)", "TDD")
            roundedFreq in 24250.0..27500.0 -> Triple(258, "n258 (26 GHz mmWave)", "TDD")
            roundedFreq in 26500.0..29500.0 -> Triple(257, "n257 (28 GHz mmWave)", "TDD")
            else -> Triple(0, "NR Custom (${String.format(Locale.US, "%.1f MHz", roundedFreq)})", "TDD")
        }

        val bwMhz = when (bandNum) {
            77, 78 -> 100.0
            41 -> 40.0
            257, 258 -> 100.0
            else -> 20.0
        }

        val fLow = Math.round((roundedFreq - (bwMhz / 2.0)) * 10.0) / 10.0
        val fHigh = Math.round((roundedFreq + (bwMhz / 2.0)) * 10.0) / 10.0

        val duplexSep = if (duplex == "TDD") 0.0 else null
        val duplexDesc = if (duplex == "TDD") {
            "TDD — carrier shares time resources (UL/DL on same frequency)"
        } else {
            "FDD — UL carrier unexposed"
        }

        return CellCarrierInfo(
            rat = "5G_NR",
            bandNumber = bandNum,
            bandName = bandName,
            channelNumber = nrArfcn,
            dlCenterFreqMhz = roundedFreq,
            ulCenterFreqMhz = if (duplex == "TDD") roundedFreq else null,
            channelBandwidthMhz = bwMhz,
            isNominalBandwidth = true,
            lowFreqMhz = fLow,
            highFreqMhz = fHigh,
            duplexMode = duplex,
            duplexSeparationMhz = duplexSep,
            duplexDescription = duplexDesc,
            bandwidthDisplay = "Unreported / Android Radio API limitation",
            classificationMethod = "3GPP TS 38.101 NR-ARFCN Global Frequency Raster"
        )
    }
}
