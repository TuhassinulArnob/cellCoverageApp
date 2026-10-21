package com.cellscope.drive

import com.cellscope.drive.telephony.SinrDiagnostics
import org.junit.Assert.*
import org.junit.Test

class SinrDiagnosticsTest {

    @Test
    fun testMeasuredLteSinr_tenthsOfDb() {
        // Android RIL CellSignalStrengthLte.rssnr in tenths of a dB: 150 = 15.0 dB
        val res = SinrDiagnostics.resolveLteSinr(
            rawRssnr = 150,
            rsrpDbm = -85,
            rsrqDb = -8,
            rssiDbm = -65,
            bandwidthKhz = 20000
        )

        assertEquals(SinrDiagnostics.SOURCE_MEASURED, res.source)
        assertEquals(15.0, res.sinrDb!!, 0.01)
        assertEquals("ANDROID_RIL_RSSNR_MEASURED", res.estimationMethod)
        assertEquals("HIGH", res.confidence)
        assertTrue(res.isMeasured)
        assertFalse(res.isEstimated)
        assertTrue(res.isAvailable)
        assertEquals(-65.0, res.impliedRssiDbm!!, 0.01)
        assertTrue(res.qualitativeDiagnostic.contains("Good SINR"))
    }

    @Test
    fun testMeasuredLteSinr_negativeTenthsOfDb() {
        val res = SinrDiagnostics.resolveLteSinr(
            rawRssnr = -75,
            rsrpDbm = -112,
            rsrqDb = -16,
            rssiDbm = -80,
            bandwidthKhz = 10000
        )

        assertEquals(SinrDiagnostics.SOURCE_MEASURED, res.source)
        assertEquals(-7.5, res.sinrDb!!, 0.01)
        assertTrue(res.qualitativeDiagnostic.contains("Poor SINR"))
    }

    @Test
    fun testMeasuredLteSinr_highSnr() {
        val res = SinrDiagnostics.resolveLteSinr(
            rawRssnr = 220, // 220 tenths of a dB = 22.0 dB
            rsrpDbm = -75,
            rsrqDb = -7
        )

        assertEquals(SinrDiagnostics.SOURCE_MEASURED, res.source)
        assertEquals(22.0, res.sinrDb!!, 0.01)
        assertTrue(res.qualitativeDiagnostic.contains("Excellent SINR"))
    }

    @Test
    fun testSentinelRssnr_fallsBackToEstimateWhenInputsValid() {
        // Sentinel value 0x7FFFFFFF (CellInfo.UNAVAILABLE)
        val res = SinrDiagnostics.resolveLteSinr(
            rawRssnr = Integer.MAX_VALUE,
            rsrpDbm = -95,
            rsrqDb = -11,
            bandwidthKhz = 10000 // 50 RBs
        )

        assertEquals(SinrDiagnostics.SOURCE_ESTIMATED, res.source)
        assertNotNull(res.sinrDb)
        assertTrue(res.sinrDb!! in -20.0..30.0)
        assertEquals("3GPP_TS_36_214_RSRQ_LOAD_MODEL", res.estimationMethod)
        assertEquals("CALIBRATED_ESTIMATE", res.confidence)
        assertTrue(res.isEstimated)
        assertFalse(res.isMeasured)
        assertNotNull(res.impliedRssiDbm)
        assertTrue(res.estimationAssumptions!!.contains("nominal 50% cell traffic load"))
    }

    @Test
    fun testRssnrZero_whenNoNonZeroSeen_fallsBackToEstimateWhenInputsValid() {
        // Modem RIL uninitialized 0 sentinel when device has never reported non-zero RSSNR
        val res = SinrDiagnostics.resolveLteSinr(
            rawRssnr = 0,
            rsrpDbm = -92,
            rsrqDb = -10,
            bandwidthKhz = 10000,
            hasSeenNonZeroRssnr = false
        )

        assertEquals(SinrDiagnostics.SOURCE_ESTIMATED, res.source)
        assertNotNull(res.sinrDb)
        assertTrue(res.isEstimated)
        assertFalse(res.isMeasured)
        assertEquals("3GPP_TS_36_214_RSRQ_LOAD_MODEL", res.estimationMethod)
    }

    @Test
    fun testRssnrZero_whenNoNonZeroSeen_andInputsMissing_returnsUnavailable() {
        // When rawRssnr is 0 and RSRP/RSRQ are absent or sentinel, never fabricate 0.0 dB
        val res = SinrDiagnostics.resolveLteSinr(
            rawRssnr = 0,
            rsrpDbm = null,
            rsrqDb = null,
            hasSeenNonZeroRssnr = false
        )

        assertEquals(SinrDiagnostics.SOURCE_UNAVAILABLE, res.source)
        assertNull(res.sinrDb)
        assertFalse(res.isAvailable)
    }

    @Test
    fun testRssnrZero_whenNonZeroSeen_treatedAsMeasuredZeroDb() {
        // If device has proven capable of non-zero reporting, 0 is genuinely 0.0 dB
        val res = SinrDiagnostics.resolveLteSinr(
            rawRssnr = 0,
            rsrpDbm = -90,
            rsrqDb = -12,
            hasSeenNonZeroRssnr = true
        )

        assertEquals(SinrDiagnostics.SOURCE_MEASURED, res.source)
        assertEquals(0.0, res.sinrDb!!, 0.01)
        assertTrue(res.isMeasured)
    }

    @Test
    fun testSentinel99_fallsBackToEstimate() {
        // Legacy modem sentinel 99
        val res = SinrDiagnostics.resolveLteSinr(
            rawRssnr = 99,
            rsrpDbm = -90,
            rsrqDb = -10,
            bandwidthKhz = 20000 // 100 RBs
        )

        assertEquals(SinrDiagnostics.SOURCE_ESTIMATED, res.source)
        assertNotNull(res.sinrDb)
        assertTrue(res.sinrDb!! in -20.0..30.0)
    }

    @Test
    fun testEstimatedLteSinr_impliedRssiCalculation() {
        // RSRP = -90 dBm, RSRQ = -10 dB, BW = 10 MHz (N = 50 RBs)
        // RSSI_dBm = RSRP_dBm + 10*log10(N) - RSRQ_dB
        // -90 + 10*log10(50) - (-10) = -90 + 16.9897 + 10 = -63.01 dBm
        val res = SinrDiagnostics.resolveLteSinr(
            rawRssnr = null,
            rsrpDbm = -90,
            rsrqDb = -10,
            bandwidthKhz = 10000
        )

        assertEquals(SinrDiagnostics.SOURCE_ESTIMATED, res.source)
        assertNotNull(res.impliedRssiDbm)
        assertEquals(-63.0, res.impliedRssiDbm!!, 0.2)
    }

    @Test
    fun testUnavailableSinr_whenInputsMissing() {
        val res = SinrDiagnostics.resolveLteSinr(
            rawRssnr = null,
            rsrpDbm = null,
            rsrqDb = null
        )

        assertEquals(SinrDiagnostics.SOURCE_UNAVAILABLE, res.source)
        assertNull(res.sinrDb)
        assertFalse(res.isAvailable)
        assertFalse(res.isMeasured)
        assertFalse(res.isEstimated)
        assertTrue(res.qualitativeDiagnostic.contains("Physical RF parameters insufficient"))
    }

    @Test
    fun testUnavailableSinr_whenRsrqSentinel() {
        val res = SinrDiagnostics.resolveLteSinr(
            rawRssnr = null,
            rsrpDbm = -90,
            rsrqDb = Integer.MAX_VALUE
        )

        assertEquals(SinrDiagnostics.SOURCE_UNAVAILABLE, res.source)
        assertNull(res.sinrDb)
    }

    @Test
    fun testNrMeasuredSinr_ssSinr() {
        val res = SinrDiagnostics.resolveNrSinr(
            ssSinr = 18.5,
            csiSinr = 16.0,
            ssRsrp = -88,
            ssRsrq = -10
        )

        assertEquals(SinrDiagnostics.SOURCE_MEASURED, res.source)
        assertEquals(18.5, res.sinrDb!!, 0.01)
        assertEquals("NR_SS_SINR_MEASURED", res.estimationMethod)
        assertTrue(res.isMeasured)
    }

    @Test
    fun testNrMeasuredSinr_csiSinrFallback() {
        val res = SinrDiagnostics.resolveNrSinr(
            ssSinr = null,
            csiSinr = 14.0,
            ssRsrp = -92,
            ssRsrq = -11
        )

        assertEquals(SinrDiagnostics.SOURCE_MEASURED, res.source)
        assertEquals(14.0, res.sinrDb!!, 0.01)
        assertEquals("NR_CSI_SINR_MEASURED", res.estimationMethod)
    }

    @Test
    fun testNrEstimationRejection_neverEstimatesUsingLteEquation() {
        // Enforce 3GPP separation: NR SS-RSRQ definition does not support LTE carrier RSSI equation
        val res = SinrDiagnostics.resolveNrSinr(
            ssSinr = null,
            csiSinr = null,
            ssRsrp = -95,
            ssRsrq = -12
        )

        assertEquals(SinrDiagnostics.SOURCE_UNAVAILABLE, res.source)
        assertNull(res.sinrDb)
        assertEquals("NR_MODEL_UNSUPPORTED", res.estimationMethod)
        assertTrue(res.qualitativeDiagnostic.contains("SS-RSRQ"))
        assertTrue(res.estimationAssumptions!!.contains("cannot be converted using LTE carrier equations"))
    }

    @Test
    fun testBandwidthToResourceBlocks() {
        assertEquals(6, SinrDiagnostics.bandwidthToResourceBlocks(1400))
        assertEquals(15, SinrDiagnostics.bandwidthToResourceBlocks(3000))
        assertEquals(25, SinrDiagnostics.bandwidthToResourceBlocks(5000))
        assertEquals(50, SinrDiagnostics.bandwidthToResourceBlocks(10000))
        assertEquals(75, SinrDiagnostics.bandwidthToResourceBlocks(15000))
        assertEquals(100, SinrDiagnostics.bandwidthToResourceBlocks(20000))
        assertEquals(50, SinrDiagnostics.bandwidthToResourceBlocks(null))

        // Vendor HAL MHz values normalization
        assertEquals(50, SinrDiagnostics.bandwidthToResourceBlocks(10))
        assertEquals(100, SinrDiagnostics.bandwidthToResourceBlocks(20))
        assertEquals(25, SinrDiagnostics.bandwidthToResourceBlocks(5))
        assertEquals(15, SinrDiagnostics.bandwidthToResourceBlocks(3))
        assertEquals(6, SinrDiagnostics.bandwidthToResourceBlocks(1))
    }

    @Test
    fun testEstimatedLteSinr_rsrqSamplesMatrix() {
        // Sample A: RSRP -101 dBm, RSRQ -11 dB -> 3.3 dB (Nominal 50% load model)
        val resA = SinrDiagnostics.resolveLteSinr(
            rawRssnr = null,
            rsrpDbm = -101,
            rsrqDb = -11,
            bandwidthKhz = 10000
        )
        assertEquals(SinrDiagnostics.SOURCE_ESTIMATED, resA.source)
        assertEquals(3.3, resA.sinrDb!!, 0.1)
        assertEquals("CALIBRATED_ESTIMATE", resA.confidence)
        assertEquals("3GPP_TS_36_214_RSRQ_LOAD_MODEL", resA.estimationMethod)

        // Sample B: RSRP -103 dBm, RSRQ -10 dB -> 6.0 dB (Nominal 50% load model)
        val resB = SinrDiagnostics.resolveLteSinr(
            rawRssnr = null,
            rsrpDbm = -103,
            rsrqDb = -10,
            bandwidthKhz = 10000
        )
        assertEquals(SinrDiagnostics.SOURCE_ESTIMATED, resB.source)
        assertEquals(6.0, resB.sinrDb!!, 0.1)
        assertEquals("CALIBRATED_ESTIMATE", resB.confidence)
        assertEquals("3GPP_TS_36_214_RSRQ_LOAD_MODEL", resB.estimationMethod)

        // User screenshot scenario: RSRP = -97 dBm, RSRQ = -8 dB, Modem RSSI = -89 dBm, Unknown BW
        // Evaluates under 3GPP Bounded-Load Model: eta_max = 43.1%, expected eta = 21.5% -> 7.5 dB
        val res8 = SinrDiagnostics.resolveLteSinr(
            rawRssnr = null,
            rsrpDbm = -97,
            rsrqDb = -8,
            rssiDbm = -89,
            bandwidthKhz = null
        )
        assertEquals(SinrDiagnostics.SOURCE_ESTIMATED, res8.source)
        assertEquals(7.5, res8.sinrDb!!, 0.1)
        assertEquals("BOUNDED_LOAD_ESTIMATE", res8.confidence)
        assertEquals("3GPP_TS_36_214_BOUNDED_LOAD_MODEL", res8.estimationMethod)
        assertTrue(res8.estimationAssumptions!!.contains("bounds traffic load"))
        assertTrue(res8.estimationAssumptions!!.contains("Physical SINR bounds"))
        assertNotNull(res8.impliedRssiDbm)

        // RSRQ = -7 dB: Lightly loaded cell -> 9.0 dB under Bounded-Load Model
        val res7 = SinrDiagnostics.resolveLteSinr(
            rawRssnr = null,
            rsrpDbm = -90,
            rsrqDb = -7,
            bandwidthKhz = 10000
        )
        assertEquals(SinrDiagnostics.SOURCE_ESTIMATED, res7.source)
        assertEquals(9.0, res7.sinrDb!!, 0.1)
        assertEquals("BOUNDED_LOAD_ESTIMATE", res7.confidence)
        assertEquals("3GPP_TS_36_214_BOUNDED_LOAD_MODEL", res7.estimationMethod)

        // RSRQ = -3 dB: 3GPP TS 36.133 Table 9.1.7-1 Index 34 theoretical saturation ceiling
        // Interference is below receiver noise floor; mathematical inversion is indeterminate.
        // Returns UNAVAILABLE with explicit ceiling saturation diagnostic (never fabricated 30 dB).
        val res3 = SinrDiagnostics.resolveLteSinr(
            rawRssnr = null,
            rsrpDbm = -80,
            rsrqDb = -3,
            bandwidthKhz = 10000
        )
        assertEquals(SinrDiagnostics.SOURCE_UNAVAILABLE, res3.source)
        assertNull(res3.sinrDb)
        assertTrue(res3.qualitativeDiagnostic.contains("Clean RF channel"))
        assertTrue(res3.qualitativeDiagnostic.contains("saturation ceiling"))
        assertTrue(res3.estimationAssumptions!!.contains("theoretical ceiling"))
    }

    @Test
    fun testBandwidthValidation_sentinelsAndMissing() {
        // Missing bandwidth defaults to nominal 50 RBs (10 MHz)
        assertEquals(50, SinrDiagnostics.bandwidthToResourceBlocks(null))
        assertEquals(50, SinrDiagnostics.bandwidthToResourceBlocks(0))
        assertEquals(50, SinrDiagnostics.bandwidthToResourceBlocks(-5000))

        // Integer.MAX_VALUE / CellInfo.UNAVAILABLE sentinel (2147483647)
        assertEquals(50, SinrDiagnostics.bandwidthToResourceBlocks(Integer.MAX_VALUE))
        assertEquals(50, SinrDiagnostics.bandwidthToResourceBlocks(2147483647))

        // Implausible bandwidth > 100 MHz
        assertEquals(50, SinrDiagnostics.bandwidthToResourceBlocks(500000))

        // Valid LTE bandwidths in kHz
        assertEquals(6, SinrDiagnostics.bandwidthToResourceBlocks(1400))
        assertEquals(15, SinrDiagnostics.bandwidthToResourceBlocks(3000))
        assertEquals(25, SinrDiagnostics.bandwidthToResourceBlocks(5000))
        assertEquals(50, SinrDiagnostics.bandwidthToResourceBlocks(10000))
        assertEquals(75, SinrDiagnostics.bandwidthToResourceBlocks(15000))
        assertEquals(100, SinrDiagnostics.bandwidthToResourceBlocks(20000))

        // When bandwidth is Integer.MAX_VALUE in resolveLteSinr, it is treated as nominal and does not fail
        val res = SinrDiagnostics.resolveLteSinr(
            rawRssnr = null,
            rsrpDbm = -95,
            rsrqDb = -11,
            bandwidthKhz = Integer.MAX_VALUE
        )
        assertEquals(SinrDiagnostics.SOURCE_ESTIMATED, res.source)
        assertEquals(3.3, res.sinrDb!!, 0.1)
        assertTrue(res.estimationAssumptions!!.contains("nominal 10 MHz assumption"))

        // Bandwidth independence demonstration:
        // Estimated SINR at RSRQ = -8 dB is identical (7.5 dB) regardless of whether bandwidth is 10 MHz, 20 MHz, or unknown
        val resBw10 = SinrDiagnostics.resolveLteSinr(rawRssnr = null, rsrpDbm = -97, rsrqDb = -8, bandwidthKhz = 10000)
        val resBw20 = SinrDiagnostics.resolveLteSinr(rawRssnr = null, rsrpDbm = -97, rsrqDb = -8, bandwidthKhz = 20000)
        val resBwUnknown = SinrDiagnostics.resolveLteSinr(rawRssnr = null, rsrpDbm = -97, rsrqDb = -8, bandwidthKhz = null)
        assertEquals(7.5, resBw10.sinrDb!!, 0.05)
        assertEquals(7.5, resBw20.sinrDb!!, 0.05)
        assertEquals(7.5, resBwUnknown.sinrDb!!, 0.05)
    }

    @Test
    fun testSuspectBasebandClamp_validVsSuspiciousMinus44() {
        // Suspicious baseband clamp: RSRP = -44 dBm, RSRQ = -3 dB, RSSI <= -100 (or null)
        assertTrue(
            com.cellscope.drive.analytics.RadioQualityEngine.isSuspectBasebandClamp(
                rsrp = -44,
                rsrq = -3,
                rssi = null,
                networkType = "LTE"
            )
        )
        assertTrue(
            com.cellscope.drive.analytics.RadioQualityEngine.isSuspectBasebandClamp(
                rsrp = -44,
                rsrq = -3,
                rssi = -105,
                networkType = "LTE"
            )
        )

        // Valid strong -44 dBm near-tower reading with normal RSSI and RSRQ
        assertFalse(
            com.cellscope.drive.analytics.RadioQualityEngine.isSuspectBasebandClamp(
                rsrp = -44,
                rsrq = -8,
                rssi = -25,
                networkType = "LTE"
            )
        )
        assertFalse(
            com.cellscope.drive.analytics.RadioQualityEngine.isSuspectBasebandClamp(
                rsrp = -44,
                rsrq = -10,
                rssi = -30,
                networkType = "LTE"
            )
        )

        // Valid 2G reading at -44 dBm
        assertFalse(
            com.cellscope.drive.analytics.RadioQualityEngine.isSuspectBasebandClamp(
                rsrp = -44,
                rsrq = -3,
                rssi = -105,
                networkType = "GSM"
            )
        )
    }
}
