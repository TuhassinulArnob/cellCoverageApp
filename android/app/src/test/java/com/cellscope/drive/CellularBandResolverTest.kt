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

    @Test
    fun testGsmArfcnResolution_Gsm900() {
        val cell = ObservedCell(
            pci = 0,
            channelNumber = 60,
            servingRat = "2G",
            networkType = "2G_GSM",
            channelType = "ARFCN"
        )
        val carrier = CellularBandResolver.resolve(cell)
        assertNotNull(carrier)
        assertEquals("2G_GSM", carrier!!.rat)
        assertEquals(900, carrier.bandNumber)
        assertEquals(60, carrier.channelNumber)
        assertEquals(947.0, carrier.dlCenterFreqMhz, 0.1)
    }

    @Test
    fun testGsmArfcnResolution_Dcs1800() {
        val cell = ObservedCell(
            pci = 0,
            channelNumber = 600,
            servingRat = "2G",
            networkType = "2G_GSM",
            channelType = "ARFCN"
        )
        val carrier = CellularBandResolver.resolve(cell)
        assertNotNull(carrier)
        assertEquals("2G_GSM", carrier!!.rat)
        assertEquals(1800, carrier.bandNumber)
        assertEquals(600, carrier.channelNumber)
        assertEquals(1822.8, carrier.dlCenterFreqMhz, 0.1)
    }

    @Test
    fun testWcdmaUarfcnResolution_Band1() {
        val cell = ObservedCell(
            pci = 0,
            channelNumber = 10600,
            servingRat = "3G",
            networkType = "3G_WCDMA",
            channelType = "UARFCN"
        )
        val carrier = CellularBandResolver.resolve(cell)
        assertNotNull(carrier)
        assertEquals("3G_WCDMA", carrier!!.rat)
        assertEquals(1, carrier.bandNumber)
        assertEquals(2120.0, carrier.dlCenterFreqMhz, 0.1)
    }

    @Test
    fun testResolveMeasurement_GsmNeverTreatedAsLteBand40() {
        val gsmEntity = com.cellscope.drive.data.entity.LteMeasurementEntity(
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:00.000Z",
            timestampMs = 1000L,
            latitude = 23.81,
            longitude = 90.41,
            altitudeM = 10.0,
            accuracyM = 3.0f,
            speedKmh = 10.0f,
            networkType = "2G_GSM",
            operatorAlpha = "Robi",
            operatorNumeric = "47002",
            servingPci = null,
            tac = 5001,
            earfcn = 60,
            bandwidthKhz = null,
            rsrpDbm = null, // Strict null for 2G
            rsrqDb = null,
            rssiDbm = -44,
            sinrDb = null,
            cqi = null,
            timingAdvance = null,
            isServing = true,
            handoverTrigger = 0,
            servingRat = "2G",
            channelNumber = 60,
            channelType = "ARFCN",
            primarySignalDbm = -44,
            signalMetricType = "GSM_RSSI"
        )

        val carrier = CellularBandResolver.resolveMeasurement(gsmEntity)
        assertNotNull(carrier)
        assertEquals("2G_GSM", carrier!!.rat)
        assertEquals(900, carrier.bandNumber)
        assertNotEquals("Must NEVER be resolved as LTE Band 40", 40, carrier.bandNumber)
    }

    @Test
    fun testBandwidthSentinelInvalidation() {
        // Android RIL sentinel: Integer.MAX_VALUE
        val cellSentinelMax = ObservedCell(
            pci = 10,
            earfcn = 1600,
            bandwidthKhz = Int.MAX_VALUE,
            isServing = true,
            networkType = "LTE"
        )
        val carrier1 = CellularBandResolver.resolve(cellSentinelMax)
        assertNotNull(carrier1)
        assertTrue("Sentinel Int.MAX_VALUE must be treated as nominal/unexposed", carrier1!!.isNominalBandwidth)
        assertEquals("Unreported / Android Radio API limitation", carrier1.bandwidthDisplay)

        // Invalid negative bandwidth
        val cellSentinelNeg = ObservedCell(
            pci = 10,
            earfcn = 1600,
            bandwidthKhz = -1,
            isServing = true,
            networkType = "LTE"
        )
        val carrier2 = CellularBandResolver.resolve(cellSentinelNeg)
        assertNotNull(carrier2)
        assertTrue(carrier2!!.isNominalBandwidth)
        assertEquals("Unreported / Android Radio API limitation", carrier2.bandwidthDisplay)

        // Valid 20 MHz LTE bandwidth
        val cellValid = ObservedCell(
            pci = 10,
            earfcn = 1600,
            bandwidthKhz = 20000,
            isServing = true,
            networkType = "LTE"
        )
        val carrier3 = CellularBandResolver.resolve(cellValid)
        assertNotNull(carrier3)
        assertFalse(carrier3!!.isNominalBandwidth)
        assertEquals("20.0 MHz", carrier3.bandwidthDisplay)
    }

    @Test
    fun testDuplexSeparationCalculations() {
        // FDD Band 1 (DL 2140.0, UL 1950.0 -> 190.0 MHz duplex separation)
        val cellBand1 = ObservedCell(pci = 10, earfcn = 300, isServing = true, networkType = "LTE")
        val carrier1 = CellularBandResolver.resolve(cellBand1)
        assertNotNull(carrier1)
        assertEquals("FDD", carrier1!!.duplexMode)
        assertEquals(190.0, carrier1.duplexSeparationMhz!!, 0.1)
        assertTrue(carrier1.duplexDescription.contains("190.0 MHz duplex separation"))

        // TDD Band 40 (DL = UL -> 0.0 MHz separation)
        val cellBand40 = ObservedCell(pci = 301, earfcn = 39000, isServing = true, networkType = "LTE")
        val carrier2 = CellularBandResolver.resolve(cellBand40)
        assertNotNull(carrier2)
        assertEquals("TDD", carrier2!!.duplexMode)
        assertEquals(0.0, carrier2.duplexSeparationMhz!!, 0.001)
        assertTrue(carrier2.duplexDescription.contains("carrier shares time resources"))
    }
}
