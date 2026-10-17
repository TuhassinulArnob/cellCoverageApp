package com.cellscope.drive

import com.cellscope.drive.telephony.LteBandTable
import org.junit.Assert.*
import org.junit.Test

class LteBandTableTest {

    @Test
    fun testBand3DcsCalculation() {
        // Band 3: FDD, DL low = 1805.0, offset = 1200, UL low = 1710.0, UL offset = 19200
        // EARFCN 1600:
        // DL = 1805.0 + 0.1 * (1600 - 1200) = 1805.0 + 40.0 = 1845.0 MHz
        // UL = 1710.0 + 0.1 * (1600 - 1200) = 1710.0 + 40.0 = 1750.0 MHz
        // Paired UL EARFCN = 19200 + 400 = 19600
        val result = LteBandTable.resolveEarfcn(1600)
        assertNotNull(result)
        assertEquals(3, result!!.bandNumber)
        assertEquals("DCS 1800", result.designation)
        assertEquals(LteBandTable.DuplexMode.FDD, result.duplexMode)
        assertEquals(1845.0, result.dlFrequencyMhz, 0.01)
        assertEquals(1750.0, result.ulFrequencyMhz!!, 0.01)
        assertEquals(19600, result.pairedUlEarfcn)
        assertTrue(result.bangladeshOperators.contains("Grameenphone"))
    }

    @Test
    fun testBand1ImtCalculation() {
        // Band 1: FDD, DL low = 2110.0, offset = 0, UL low = 1920.0
        // EARFCN 100:
        // DL = 2110.0 + 10.0 = 2120.0 MHz
        // UL = 1920.0 + 10.0 = 1930.0 MHz
        val result = LteBandTable.resolveEarfcn(100)
        assertNotNull(result)
        assertEquals(1, result!!.bandNumber)
        assertEquals(2120.0, result.dlFrequencyMhz, 0.01)
        assertEquals(1930.0, result.ulFrequencyMhz!!, 0.01)
    }

    @Test
    fun testBand8EGsmCalculation() {
        // Band 8: FDD, DL low = 925.0, offset = 3450, UL low = 880.0
        // EARFCN 3500:
        // DL = 925.0 + 0.1 * (50) = 930.0 MHz
        // UL = 880.0 + 0.1 * (50) = 885.0 MHz
        val result = LteBandTable.resolveEarfcn(3500)
        assertNotNull(result)
        assertEquals(8, result!!.bandNumber)
        assertEquals(930.0, result.dlFrequencyMhz, 0.01)
        assertEquals(885.0, result.ulFrequencyMhz!!, 0.01)
        assertTrue(result.bangladeshOperators.contains("Robi"))
    }

    @Test
    fun testBand40TddSharedFrequency() {
        // Band 40: TDD, DL low = 2300.0, offset = 38650
        // EARFCN 38750:
        // F = 2300.0 + 0.1 * (100) = 2310.0 MHz (Shared for DL and UL)
        val result = LteBandTable.resolveEarfcn(38750)
        assertNotNull(result)
        assertEquals(40, result!!.bandNumber)
        assertEquals(LteBandTable.DuplexMode.TDD, result.duplexMode)
        assertEquals(2310.0, result.dlFrequencyMhz, 0.01)
        assertEquals(2310.0, result.ulFrequencyMhz!!, 0.01)
        assertEquals(38750, result.pairedUlEarfcn)
        assertTrue(result.bangladeshOperators.contains("Banglalink"))
    }

    @Test
    fun testInvalidOrNullEarfcn() {
        assertNull(LteBandTable.resolveEarfcn(null))
        assertNull(LteBandTable.resolveEarfcn(-5))
        assertNull(LteBandTable.resolveEarfcn(999999))
    }
}
