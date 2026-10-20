package com.cellscope.drive

import com.cellscope.drive.model.ObservedCell
import com.cellscope.drive.telephony.CellularBandResolver
import org.junit.Assert.*
import org.junit.Test

class GsmArfcnClassificationTest {

    private fun makeGsmCell(arfcn: Int): ObservedCell {
        return ObservedCell(
            pci = 10,
            channelNumber = arfcn,
            servingRat = "2G",
            networkType = "2G_GSM",
            channelType = "ARFCN",
            primarySignalDbm = -75,
            rssiDbm = -75
        )
    }

    @Test
    fun testPsm900Classification() {
        // ARFCN 1: F_DL = 935.2 MHz, F_UL = 890.2 MHz
        val c1 = CellularBandResolver.resolve(makeGsmCell(1))
        assertNotNull(c1)
        assertEquals("2G_GSM", c1!!.rat)
        assertEquals(900, c1.bandNumber)
        assertTrue(c1.bandName.contains("P-GSM"))
        assertEquals(935.2, c1.dlCenterFreqMhz, 0.05)
        assertEquals(890.2, c1.ulCenterFreqMhz!!, 0.05)
        assertEquals(45.0, c1.duplexSeparationMhz!!, 0.05)
        assertTrue(c1.classificationMethod.contains("3GPP TS 45.005"))

        // ARFCN 62: F_DL = 947.4 MHz, F_UL = 902.4 MHz
        val c62 = CellularBandResolver.resolve(makeGsmCell(62))
        assertNotNull(c62)
        assertEquals(900, c62!!.bandNumber)
        assertTrue(c62.bandName.contains("P-GSM"))
        assertEquals(947.4, c62.dlCenterFreqMhz, 0.05)
        assertEquals(902.4, c62.ulCenterFreqMhz!!, 0.05)

        // ARFCN 124: F_DL = 959.8 MHz, F_UL = 914.8 MHz
        val c124 = CellularBandResolver.resolve(makeGsmCell(124))
        assertNotNull(c124)
        assertEquals(900, c124!!.bandNumber)
        assertTrue(c124.bandName.contains("P-GSM"))
        assertEquals(959.8, c124.dlCenterFreqMhz, 0.05)
        assertEquals(914.8, c124.ulCenterFreqMhz!!, 0.05)
    }

    @Test
    fun testEsm900Classification() {
        // ARFCN 0: F_DL = 935.0 MHz, F_UL = 890.0 MHz
        val c0 = CellularBandResolver.resolve(makeGsmCell(0))
        assertNotNull(c0)
        assertEquals(900, c0!!.bandNumber)
        assertTrue(c0.bandName.contains("E-GSM"))
        assertEquals(935.0, c0.dlCenterFreqMhz, 0.05)
        assertEquals(890.0, c0.ulCenterFreqMhz!!, 0.05)
        assertEquals(45.0, c0.duplexSeparationMhz!!, 0.05)

        // ARFCN 975: F_DL = 925.2 MHz, F_UL = 880.2 MHz
        val c975 = CellularBandResolver.resolve(makeGsmCell(975))
        assertNotNull(c975)
        assertEquals(900, c975!!.bandNumber)
        assertTrue(c975.bandName.contains("E-GSM"))
        assertEquals(925.2, c975.dlCenterFreqMhz, 0.05)
        assertEquals(880.2, c975.ulCenterFreqMhz!!, 0.05)

        // ARFCN 1023: F_DL = 934.8 MHz, F_UL = 889.8 MHz
        val c1023 = CellularBandResolver.resolve(makeGsmCell(1023))
        assertNotNull(c1023)
        assertEquals(900, c1023!!.bandNumber)
        assertTrue(c1023.bandName.contains("E-GSM"))
        assertEquals(934.8, c1023.dlCenterFreqMhz, 0.05)
        assertEquals(889.8, c1023.ulCenterFreqMhz!!, 0.05)
    }

    @Test
    fun testGsm850Classification() {
        // ARFCN 128: F_DL = 869.2 MHz, F_UL = 824.2 MHz
        val c128 = CellularBandResolver.resolve(makeGsmCell(128))
        assertNotNull(c128)
        assertEquals(850, c128!!.bandNumber)
        assertTrue(c128.bandName.contains("GSM 850"))
        assertEquals(869.2, c128.dlCenterFreqMhz, 0.05)
        assertEquals(824.2, c128.ulCenterFreqMhz!!, 0.05)
        assertEquals(45.0, c128.duplexSeparationMhz!!, 0.05)

        // ARFCN 251: F_DL = 893.8 MHz, F_UL = 848.8 MHz
        val c251 = CellularBandResolver.resolve(makeGsmCell(251))
        assertNotNull(c251)
        assertEquals(850, c251!!.bandNumber)
        assertEquals(893.8, c251.dlCenterFreqMhz, 0.05)
        assertEquals(848.8, c251.ulCenterFreqMhz!!, 0.05)
    }

    @Test
    fun testDcs1800Classification() {
        // ARFCN 512: F_DL = 1805.2 MHz, F_UL = 1710.2 MHz
        val c512 = CellularBandResolver.resolve(makeGsmCell(512))
        assertNotNull(c512)
        assertEquals(1800, c512!!.bandNumber)
        assertTrue(c512.bandName.contains("DCS 1800"))
        assertEquals(1805.2, c512.dlCenterFreqMhz, 0.05)
        assertEquals(1710.2, c512.ulCenterFreqMhz!!, 0.05)
        assertEquals(95.0, c512.duplexSeparationMhz!!, 0.05)

        // ARFCN 600: F_DL = 1822.8 MHz, F_UL = 1727.8 MHz
        val c600 = CellularBandResolver.resolve(makeGsmCell(600))
        assertNotNull(c600)
        assertEquals(1800, c600!!.bandNumber)
        assertEquals(1822.8, c600.dlCenterFreqMhz, 0.05)
        assertEquals(1727.8, c600.ulCenterFreqMhz!!, 0.05)
    }

    @Test
    fun testGsmNeverMistakenForLteBand() {
        // ARFCN 60 is within LTE Band 40 EARFCN (38650..39650), but as GSM it MUST NOT be resolved as LTE
        val gsmCell = makeGsmCell(60)
        val carrier = CellularBandResolver.resolve(gsmCell)
        assertNotNull(carrier)
        assertNotEquals("LTE", carrier!!.rat)
        assertNotEquals(40, carrier.bandNumber)
        assertEquals("2G_GSM", carrier.rat)
        assertEquals(900, carrier.bandNumber)
    }
}
