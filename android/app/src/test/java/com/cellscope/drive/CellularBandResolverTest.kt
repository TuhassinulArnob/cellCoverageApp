package com.cellscope.drive

import com.cellscope.drive.model.ObservedCell
import com.cellscope.drive.telephony.CellularBandResolver
import org.junit.Assert.*
import org.junit.Test

class CellularBandResolverTest {

    @Test
    fun testLteEarfcnResolution_Band1() {
        val cell = ObservedCell(
            pci = 10,
            earfcn = 300,
            bandwidthKhz = 20000,
            isServing = true,
            networkType = "LTE",
            rsrpDbm = -85
        )

        val carrier = CellularBandResolver.resolve(cell)
        assertNotNull(carrier)
        carrier!!
        assertEquals("LTE", carrier.rat)
        assertEquals(1, carrier.bandNumber)
        assertEquals("FDD", carrier.duplexMode)
        assertEquals(2140.0, carrier.dlCenterFreqMhz, 0.001)
        assertEquals(20.0, carrier.channelBandwidthMhz, 0.001)
        assertEquals(300, carrier.channelNumber)
    }

    @Test
    fun testLteEarfcnResolution_Band3() {
        val cell = ObservedCell(
            pci = 45,
            earfcn = 1650,
            bandwidthKhz = 10000,
            isServing = false,
            networkType = "LTE",
            rsrpDbm = -95
        )

        val carrier = CellularBandResolver.resolve(cell)
        assertNotNull(carrier)
        carrier!!
        assertEquals(3, carrier.bandNumber)
        assertEquals("FDD", carrier.duplexMode)
        assertEquals(1850.0, carrier.dlCenterFreqMhz, 0.001)
        assertEquals(10.0, carrier.channelBandwidthMhz, 0.001)
        assertFalse(carrier.isNominalBandwidth)
    }

    @Test
    fun testLteEarfcnResolution_Band8() {
        val cell = ObservedCell(
            pci = 120,
            earfcn = 3625,
            bandwidthKhz = 5000,
            isServing = true,
            networkType = "LTE",
            rsrpDbm = -78
        )

        val carrier = CellularBandResolver.resolve(cell)
        assertNotNull(carrier)
        carrier!!
        assertEquals(8, carrier.bandNumber)
        assertEquals("FDD", carrier.duplexMode)
        assertEquals(942.5, carrier.dlCenterFreqMhz, 0.001)
        assertEquals(5.0, carrier.channelBandwidthMhz, 0.001)
    }

    @Test
    fun testLteEarfcnResolution_Band40_Tdd() {
        val cell = ObservedCell(
            pci = 301,
            earfcn = 39000,
            bandwidthKhz = 20000,
            isServing = false,
            networkType = "LTE"
        )

        val carrier = CellularBandResolver.resolve(cell)
        assertNotNull(carrier)
        carrier!!
        assertEquals(40, carrier.bandNumber)
        assertEquals("TDD", carrier.duplexMode)
        assertEquals(2335.0, carrier.dlCenterFreqMhz, 0.001)
    }

    @Test
    fun testNrArfcnResolution_BandN78() {
        val cell = ObservedCell(
            pci = 600,
            nrArfcn = 630000,
            isServing = true,
            networkType = "5G_NR",
            rsrpDbm = -75
        )

        val carrier = CellularBandResolver.resolve(cell)
        assertNotNull(carrier)
        carrier!!
        assertEquals("5G_NR", carrier.rat)
        assertEquals(78, carrier.bandNumber)
        assertEquals("TDD", carrier.duplexMode)
        // 3000 + 0.015 * (630000 - 600000) = 3450.0 MHz
        assertEquals(3450.0, carrier.dlCenterFreqMhz, 0.001)
        assertEquals(100.0, carrier.channelBandwidthMhz, 0.001)
        assertEquals(630000, carrier.channelNumber)
    }

    @Test
    fun testNrArfcnResolution_BandN41() {
        val cell = ObservedCell(
            pci = 150,
            nrArfcn = 500000,
            isServing = false,
            networkType = "5G_NR",
            rsrpDbm = -90
        )

        val carrier = CellularBandResolver.resolve(cell)
        assertNotNull(carrier)
        carrier!!
        assertEquals(41, carrier.bandNumber)
        assertEquals("TDD", carrier.duplexMode)
        // 0.005 * 500000 = 2500.0 MHz
        assertEquals(2500.0, carrier.dlCenterFreqMhz, 0.001)
    }

    @Test
    fun testResolutionWithMissingChannel_ReturnsNull() {
        val cell = ObservedCell(
            pci = 100,
            earfcn = null,
            nrArfcn = null,
            bandwidthKhz = 20000,
            isServing = true,
            networkType = "LTE"
        )

        val carrier = CellularBandResolver.resolve(cell)
        assertNull(carrier)
    }

    @Test
    fun testResolutionDefaultBandwidthFallback() {
        val cell = ObservedCell(
            pci = 10,
            earfcn = 300,
            bandwidthKhz = null,
            isServing = true,
            networkType = "LTE"
        )

        val carrier = CellularBandResolver.resolve(cell)
        assertNotNull(carrier)
        // Defaults to 10.0 MHz nominal bandwidth when unexposed
        assertEquals(10.0, carrier!!.channelBandwidthMhz, 0.001)
        assertTrue(carrier.isNominalBandwidth)
    }
}
