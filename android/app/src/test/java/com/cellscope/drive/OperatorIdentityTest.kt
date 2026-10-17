package com.cellscope.drive

import com.cellscope.drive.telephony.BangladeshMccMnc
import com.cellscope.drive.telephony.OperatorIdentity
import org.junit.Assert.*
import org.junit.Test

class OperatorIdentityTest {

    @Test
    fun testBangladeshOperatorResolution() {
        assertEquals("Grameenphone (GP)", BangladeshMccMnc.resolveOperatorName("47001", null))
        assertEquals("Robi Axiata (Robi)", BangladeshMccMnc.resolveOperatorName("47002", null))
        assertEquals("Banglalink (BL)", BangladeshMccMnc.resolveOperatorName("47003", null))
        assertEquals("Teletalk Bangladesh (Teletalk)", BangladeshMccMnc.resolveOperatorName("47004", null))
        assertEquals("Citycell (Historical CDMA)", BangladeshMccMnc.resolveOperatorName("47005", null))

        // Custom or unmapped MNC in Bangladesh
        assertEquals("PLMN 470-09 (BD 470-09)", BangladeshMccMnc.resolveOperatorName("47009", null))

        // International fallback
        assertEquals("Jio", BangladeshMccMnc.resolveOperatorName("405854", "Jio"))
    }

    @Test
    fun testEnodebAndSectorDerivation() {
        // ECI 2849102 -> 2849102 / 256 = 11129 (eNodeB ID), 2849102 % 256 = 86 (Sector ID)
        val identity = OperatorIdentity(
            simOperatorName = "Grameenphone",
            simOperatorNumeric = "47001",
            simCountryIso = "bd",
            networkOperatorName = "Grameenphone",
            networkOperatorNumeric = "47001",
            networkCountryIso = "bd",
            dataNetworkType = "LTE",
            isRoaming = false,
            servingPci = 43,
            tac = 18452,
            eci = 2849102,
            earfcn = 1399,
            timestampMs = System.currentTimeMillis()
        )

        assertEquals(11129, identity.derivedEnodebId)
        assertEquals(78, identity.derivedSectorId)
        assertEquals("470-01-2849102", identity.ecgi)
        assertEquals("Grameenphone (GP)", identity.simResolvedName)
        assertEquals("Grameenphone (GP)", identity.networkResolvedName)
        assertEquals("Home Network", identity.roamingStatusText)
        assertTrue(identity.derivedBandText.contains("Band 3"))
    }

    @Test
    fun testRoamingStatusDetection() {
        val roamingIdentity = OperatorIdentity(
            simOperatorName = "Teletalk",
            simOperatorNumeric = "47004",
            simCountryIso = "bd",
            networkOperatorName = "Grameenphone",
            networkOperatorNumeric = "47001",
            networkCountryIso = "bd",
            dataNetworkType = "LTE",
            isRoaming = true,
            servingPci = 43,
            tac = 18452,
            eci = 2849102,
            earfcn = 1399,
            timestampMs = System.currentTimeMillis()
        )

        assertTrue(roamingIdentity.roamingStatusText.contains("Roaming"))
    }
}
