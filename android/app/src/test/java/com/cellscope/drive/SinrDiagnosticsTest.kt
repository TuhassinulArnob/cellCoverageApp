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
    }
}
