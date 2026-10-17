package com.cellscope.drive.telephony

import java.util.Locale

/**
 * Standards-based 3GPP TS 36.101 E-UTRA Frequency and EARFCN calculation tables.
 *
 * Mathematical formulas:
 *   Downlink carrier frequency:
 *     F_DL = F_DL_low + 0.1 * (N_DL - N_Offs_DL)
 *
 *   Uplink carrier frequency (FDD):
 *     F_UL = F_UL_low + 0.1 * (N_UL - N_Offs_UL)
 *
 *   For TDD bands, the carrier frequency is shared for both transmission directions:
 *     F_DL = F_UL = F_DL_low + 0.1 * (N_DL - N_Offs_DL)
 */
object LteBandTable {

    enum class DuplexMode {
        FDD,
        TDD
    }

    data class BandDefinition(
        val bandNumber: Int,
        val designation: String,
        val duplexMode: DuplexMode,
        val dlFreqLowMhz: Double,
        val dlEarfcnOffset: Int,
        val dlEarfcnRange: IntRange,
        val ulFreqLowMhz: Double? = null,
        val ulEarfcnOffset: Int? = null,
        val ulEarfcnRange: IntRange? = null,
        val channelBandwidthsMhz: List<Double> = listOf(1.4, 3.0, 5.0, 10.0, 15.0, 20.0),
        val bangladeshOperators: List<String> = emptyList(),
        val notes: String = ""
    )

    data class FrequencyResult(
        val bandNumber: Int,
        val designation: String,
        val duplexMode: DuplexMode,
        val earfcn: Int,
        val dlFrequencyMhz: Double,
        val ulFrequencyMhz: Double?,
        val pairedUlEarfcn: Int?,
        val bangladeshOperators: List<String>,
        val channelBandwidthsMhz: List<Double>,
        val summaryText: String
    )

    val BANDS: List<BandDefinition> = listOf(
        // Band 1: 2100 MHz (FDD) - Widely used in Bangladesh by Grameenphone, Robi, Banglalink, Teletalk
        BandDefinition(
            bandNumber = 1,
            designation = "IMT 2100",
            duplexMode = DuplexMode.FDD,
            dlFreqLowMhz = 2110.0,
            dlEarfcnOffset = 0,
            dlEarfcnRange = 0..599,
            ulFreqLowMhz = 1920.0,
            ulEarfcnOffset = 18000,
            ulEarfcnRange = 18000..18599,
            channelBandwidthsMhz = listOf(5.0, 10.0, 15.0, 20.0),
            bangladeshOperators = listOf("Grameenphone", "Robi", "Banglalink", "Teletalk"),
            notes = "Primary 3G/4G urban capacity band in Bangladesh"
        ),
        // Band 2: 1900 MHz (FDD)
        BandDefinition(
            bandNumber = 2,
            designation = "PCS 1900",
            duplexMode = DuplexMode.FDD,
            dlFreqLowMhz = 1930.0,
            dlEarfcnOffset = 600,
            dlEarfcnRange = 600..1199,
            ulFreqLowMhz = 1850.0,
            ulEarfcnOffset = 18600,
            ulEarfcnRange = 18600..19199
        ),
        // Band 3: 1800 MHz DCS (FDD) - Backbone LTE band in Bangladesh across all mobile operators
        BandDefinition(
            bandNumber = 3,
            designation = "DCS 1800",
            duplexMode = DuplexMode.FDD,
            dlFreqLowMhz = 1805.0,
            dlEarfcnOffset = 1200,
            dlEarfcnRange = 1200..1949,
            ulFreqLowMhz = 1710.0,
            ulEarfcnOffset = 19200,
            ulEarfcnRange = 19200..19949,
            channelBandwidthsMhz = listOf(1.4, 3.0, 5.0, 10.0, 15.0, 20.0),
            bangladeshOperators = listOf("Grameenphone", "Robi", "Banglalink", "Teletalk"),
            notes = "Main coverage & throughput carrier for LTE in Bangladesh"
        ),
        // Band 4: AWS-1 1700/2100 (FDD)
        BandDefinition(
            bandNumber = 4,
            designation = "AWS-1 1700/2100",
            duplexMode = DuplexMode.FDD,
            dlFreqLowMhz = 2110.0,
            dlEarfcnOffset = 1950,
            dlEarfcnRange = 1950..2399,
            ulFreqLowMhz = 1710.0,
            ulEarfcnOffset = 19950,
            ulEarfcnRange = 19950..20399
        ),
        // Band 5: 850 MHz (FDD)
        BandDefinition(
            bandNumber = 5,
            designation = "CLR 850",
            duplexMode = DuplexMode.FDD,
            dlFreqLowMhz = 869.0,
            dlEarfcnOffset = 2400,
            dlEarfcnRange = 2400..2649,
            ulFreqLowMhz = 824.0,
            ulEarfcnOffset = 20400,
            ulEarfcnRange = 20400..20649
        ),
        // Band 7: 2600 MHz (FDD)
        BandDefinition(
            bandNumber = 7,
            designation = "IMT-E 2600",
            duplexMode = DuplexMode.FDD,
            dlFreqLowMhz = 2620.0,
            dlEarfcnOffset = 2750,
            dlEarfcnRange = 2750..3449,
            ulFreqLowMhz = 2500.0,
            ulEarfcnOffset = 20750,
            ulEarfcnRange = 20750..21449,
            channelBandwidthsMhz = listOf(5.0, 10.0, 15.0, 20.0)
        ),
        // Band 8: 900 MHz E-GSM (FDD) - Key deep indoor & rural coverage band in Bangladesh
        BandDefinition(
            bandNumber = 8,
            designation = "E-GSM 900",
            duplexMode = DuplexMode.FDD,
            dlFreqLowMhz = 925.0,
            dlEarfcnOffset = 3450,
            dlEarfcnRange = 3450..3799,
            ulFreqLowMhz = 880.0,
            ulEarfcnOffset = 21450,
            ulEarfcnRange = 21450..21799,
            channelBandwidthsMhz = listOf(1.4, 3.0, 5.0, 10.0),
            bangladeshOperators = listOf("Grameenphone", "Robi", "Banglalink"),
            notes = "Sub-1GHz indoor penetration layer in Bangladesh"
        ),
        // Band 12: 700 MHz (FDD)
        BandDefinition(
            bandNumber = 12,
            designation = "Lower SMH 700",
            duplexMode = DuplexMode.FDD,
            dlFreqLowMhz = 729.0,
            dlEarfcnOffset = 5010,
            dlEarfcnRange = 5010..5179,
            ulFreqLowMhz = 699.0,
            ulEarfcnOffset = 23010,
            ulEarfcnRange = 23010..23179
        ),
        // Band 20: 800 MHz (FDD) - Digital Dividend
        BandDefinition(
            bandNumber = 20,
            designation = "E-GSM 800 (DD)",
            duplexMode = DuplexMode.FDD,
            dlFreqLowMhz = 791.0,
            dlEarfcnOffset = 6150,
            dlEarfcnRange = 6150..6449,
            ulFreqLowMhz = 832.0,
            ulEarfcnOffset = 24150,
            ulEarfcnRange = 24150..24449
        ),
        // Band 28: 700 MHz (FDD) - APT 700
        BandDefinition(
            bandNumber = 28,
            designation = "APT 700",
            duplexMode = DuplexMode.FDD,
            dlFreqLowMhz = 758.0,
            dlEarfcnOffset = 9210,
            dlEarfcnRange = 9210..9659,
            ulFreqLowMhz = 703.0,
            ulEarfcnOffset = 27210,
            ulEarfcnRange = 27210..27659,
            channelBandwidthsMhz = listOf(3.0, 5.0, 10.0, 15.0, 20.0),
            notes = "Allocated for 4G/5G nationwide expansion in Bangladesh"
        ),
        // Band 38: 2600 MHz (TDD)
        BandDefinition(
            bandNumber = 38,
            designation = "TD 2600",
            duplexMode = DuplexMode.TDD,
            dlFreqLowMhz = 2570.0,
            dlEarfcnOffset = 37750,
            dlEarfcnRange = 37750..38249
        ),
        // Band 40: 2300 MHz (TDD) - High throughput TDD band used by Banglalink & Robi
        BandDefinition(
            bandNumber = 40,
            designation = "TD 2300",
            duplexMode = DuplexMode.TDD,
            dlFreqLowMhz = 2300.0,
            dlEarfcnOffset = 38650,
            dlEarfcnRange = 38650..39649,
            channelBandwidthsMhz = listOf(5.0, 10.0, 15.0, 20.0),
            bangladeshOperators = listOf("Banglalink", "Robi"),
            notes = "TDD high-capacity deployment in Bangladesh metro hubs"
        ),
        // Band 41: 2500 MHz (TDD) - BRS/EBS
        BandDefinition(
            bandNumber = 41,
            designation = "TD 2500",
            duplexMode = DuplexMode.TDD,
            dlFreqLowMhz = 2496.0,
            dlEarfcnOffset = 39650,
            dlEarfcnRange = 39650..41589,
            channelBandwidthsMhz = listOf(5.0, 10.0, 15.0, 20.0)
        ),
        // Band 66: Extended AWS (FDD)
        BandDefinition(
            bandNumber = 66,
            designation = "Extended AWS 1700/2100",
            duplexMode = DuplexMode.FDD,
            dlFreqLowMhz = 2110.0,
            dlEarfcnOffset = 66436,
            dlEarfcnRange = 66436..67335,
            ulFreqLowMhz = 1710.0,
            ulEarfcnOffset = 131972,
            ulEarfcnRange = 131972..132671
        )
    )

    /**
     * Resolves LTE frequency and duplex properties for an observed EARFCN according to 3GPP TS 36.101.
     */
    fun resolveEarfcn(earfcn: Int?): FrequencyResult? {
        if (earfcn == null || earfcn <= 0) return null

        val band = BANDS.firstOrNull { earfcn in it.dlEarfcnRange }
            ?: return null

        val delta = earfcn - band.dlEarfcnOffset
        val dlFreq = band.dlFreqLowMhz + (0.1 * delta)

        val (ulFreq, pairedUlEarfcn) = if (band.duplexMode == DuplexMode.TDD) {
            Pair(dlFreq, earfcn)
        } else {
            val ulOffset = band.ulEarfcnOffset ?: 0
            val ulLow = band.ulFreqLowMhz ?: 0.0
            val pairedEarfcn = ulOffset + delta
            val ulCalculated = ulLow + (0.1 * delta)
            Pair(ulCalculated, pairedEarfcn)
        }

        val summary = String.format(
            Locale.US,
            "Band %d (%s) • %s • DL: %.1f MHz • UL: %.1f MHz",
            band.bandNumber,
            band.designation,
            band.duplexMode.name,
            dlFreq,
            ulFreq
        )

        return FrequencyResult(
            bandNumber = band.bandNumber,
            designation = band.designation,
            duplexMode = band.duplexMode,
            earfcn = earfcn,
            dlFrequencyMhz = Math.round(dlFreq * 10.0) / 10.0,
            ulFrequencyMhz = Math.round(ulFreq * 10.0) / 10.0,
            pairedUlEarfcn = pairedUlEarfcn,
            bangladeshOperators = band.bangladeshOperators,
            channelBandwidthsMhz = band.channelBandwidthsMhz,
            summaryText = summary
        )
    }

    /**
     * Look up band definition by band number.
     */
    fun getBand(bandNumber: Int): BandDefinition? {
        return BANDS.firstOrNull { it.bandNumber == bandNumber }
    }
}
