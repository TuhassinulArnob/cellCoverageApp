package com.cellscope.drive.telephony

import com.cellscope.drive.model.ObservedCell
import java.util.Locale

data class CellCarrierInfo(
    val rat: String,                      // "LTE" or "5G_NR"
    val bandNumber: Int,
    val bandName: String,
    val channelNumber: Int,               // EARFCN or NR-ARFCN
    val dlCenterFreqMhz: Double,
    val ulCenterFreqMhz: Double?,
    val channelBandwidthMhz: Double,
    val isNominalBandwidth: Boolean,
    val lowFreqMhz: Double,
    val highFreqMhz: Double,
    val duplexMode: String
)

object CellularBandResolver {

    /**
     * Resolves carrier frequency and channel geometry for an ObservedCell.
     * Strictly separates LTE EARFCN from 5G NR-ARFCN formulas according to 3GPP standards.
     */
    fun resolve(cell: ObservedCell): CellCarrierInfo? {
        return if (cell.networkType == "5G_NR" || cell.nrArfcn != null) {
            resolveNr(cell)
        } else {
            resolveLte(cell)
        }
    }

    /**
     * Resolves LTE carrier using 3GPP TS 36.101 EARFCN formulas.
     */
    fun resolveLte(cell: ObservedCell): CellCarrierInfo? {
        val earfcn = cell.earfcn ?: return null
        val freqResult = LteBandTable.resolveEarfcn(earfcn) ?: return null

        val bwKhz = cell.bandwidthKhz
        val (bwMhz, isNominal) = if (bwKhz != null && bwKhz > 0) {
            Pair(bwKhz / 1000.0, false)
        } else {
            // Nominal bandwidth default of 10.0 MHz when not exposed by modem RIL
            Pair(10.0, true)
        }

        val fCenter = freqResult.dlFrequencyMhz
        val fLow = (fCenter - (bwMhz / 2.0) * 10.0).toInt() / 10.0
        val fHigh = (fCenter + (bwMhz / 2.0) * 10.0).toInt() / 10.0

        return CellCarrierInfo(
            rat = "LTE",
            bandNumber = freqResult.bandNumber,
            bandName = "Band ${freqResult.bandNumber} (${freqResult.designation})",
            channelNumber = earfcn,
            dlCenterFreqMhz = fCenter,
            ulCenterFreqMhz = freqResult.ulFrequencyMhz,
            channelBandwidthMhz = bwMhz,
            isNominalBandwidth = isNominal,
            lowFreqMhz = fLow,
            highFreqMhz = fHigh,
            duplexMode = freqResult.duplexMode.name
        )
    }

    /**
     * Resolves 5G NR carrier using 3GPP TS 38.101-1 / TS 38.101-2 Global Frequency Raster.
     */
    fun resolveNr(cell: ObservedCell): CellCarrierInfo? {
        val nrArfcn = cell.nrArfcn ?: return null

        // 3GPP TS 38.101 Global Frequency Raster:
        // 0 .. 3000 MHz (ΔF_Global = 5 kHz): F_REF = 0 + 0.005 * N_REF
        // 3000 .. 24250 MHz (ΔF_Global = 15 kHz): F_REF = 3000 + 0.015 * (N_REF - 600000)
        // 24250 .. 100000 MHz (ΔF_Global = 60 kHz): F_REF = 24250.08 + 0.060 * (N_REF - 2016667)
        val fCenter = when {
            nrArfcn in 0..599999 -> nrArfcn * 0.005
            nrArfcn in 600000..2016666 -> 3000.0 + 0.015 * (nrArfcn - 600000)
            nrArfcn in 2016667..3279165 -> 24250.08 + 0.060 * (nrArfcn - 2016667)
            else -> return null
        }

        val roundedFreq = Math.round(fCenter * 100.0) / 100.0

        // Identify 3GPP NR operating band by frequency range
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

        // Nominal NR bandwidth (default 20.0 MHz for FR1 mid-band, or 100.0 MHz for n78)
        val bwMhz = when (bandNum) {
            77, 78 -> 100.0
            41 -> 40.0
            257, 258 -> 100.0
            else -> 20.0
        }

        val fLow = (roundedFreq - (bwMhz / 2.0) * 10.0).toInt() / 10.0
        val fHigh = (roundedFreq + (bwMhz / 2.0) * 10.0).toInt() / 10.0

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
            duplexMode = duplex
        )
    }
}
