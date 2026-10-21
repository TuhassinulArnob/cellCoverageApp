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
        // ECI 2849102 -> 2849102 / 256 = 11129 (eNodeB ID), 2849102 % 256 = 78 (Sector ID)
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

    @Test
    fun testOperatorSwitchingRobiToBanglalink() {
        // Initial state: Robi Axiata
        val robiIdentity = OperatorIdentity(
            simOperatorName = "Robi",
            simOperatorNumeric = "47002",
            networkOperatorName = "Robi",
            networkOperatorNumeric = "47002",
            servingOperatorAlpha = "Robi Axiata (Robi)",
            servingOperatorNumeric = "47002",
            dataNetworkType = "LTE",
            servingPci = 177,
            earfcn = 1399
        )
        assertEquals("Robi Axiata (Robi)", robiIdentity.getEffectiveDisplayOperator())
        assertEquals("Robi Axiata (Robi)", robiIdentity.servingResolvedName)

        // Switched to Banglalink: SIM and serving cell immediately reflect Banglalink
        val banglalinkIdentity = robiIdentity.copy(
            simOperatorName = "Banglalink",
            simOperatorNumeric = "47003",
            networkOperatorName = "Banglalink",
            networkOperatorNumeric = "47003",
            servingOperatorAlpha = "Banglalink (BL)",
            servingOperatorNumeric = "47003",
            servingPci = 312,
            earfcn = 1750
        )
        assertEquals("Banglalink (BL)", banglalinkIdentity.getEffectiveDisplayOperator())
        assertEquals("Banglalink (BL)", banglalinkIdentity.servingResolvedName)
        assertNotEquals(robiIdentity.getEffectiveDisplayOperator(), banglalinkIdentity.getEffectiveDisplayOperator())
    }

    @Test
    fun testOperatorSwitchingBanglalinkToRobi() {
        val blIdentity = OperatorIdentity(
            simOperatorName = "Banglalink",
            simOperatorNumeric = "47003",
            servingOperatorAlpha = "Banglalink (BL)",
            servingOperatorNumeric = "47003"
        )
        assertEquals("Banglalink (BL)", blIdentity.getEffectiveDisplayOperator())

        val robiIdentity = blIdentity.copy(
            simOperatorName = "Robi",
            simOperatorNumeric = "47002",
            servingOperatorAlpha = "Robi Axiata (Robi)",
            servingOperatorNumeric = "47002"
        )
        assertEquals("Robi Axiata (Robi)", robiIdentity.getEffectiveDisplayOperator())
    }

    @Test
    fun testLteToUmtsTransition() {
        val lteIdentity = OperatorIdentity(
            simOperatorNumeric = "47002",
            networkOperatorNumeric = "47002",
            dataNetworkType = "LTE",
            activeRat = "LTE",
            servingPci = 177,
            earfcn = 1399
        )
        assertEquals("LTE", lteIdentity.activeRat)

        val umtsIdentity = lteIdentity.copy(
            dataNetworkType = "HSPA+",
            activeRat = "3G",
            servingPci = 412, // PSC in 3G
            earfcn = null
        )
        assertEquals("3G", umtsIdentity.activeRat)
        assertEquals("HSPA+", umtsIdentity.dataNetworkType)
        assertNull(umtsIdentity.earfcn)
    }

    @Test
    fun testLteToGsmTransition() {
        val gsmIdentity = OperatorIdentity(
            simOperatorNumeric = "47002",
            networkOperatorNumeric = "47002",
            dataNetworkType = "EDGE",
            activeRat = "2G",
            servingPci = 32, // BSIC in 2G
            channelNumber = 1021,
            channelType = "ARFCN",
            tac = 18452, // LAC in 2G
            eci = 4567,  // CID in 2G
            earfcn = null
        )
        assertEquals("2G", gsmIdentity.activeRat)
        assertEquals("EDGE", gsmIdentity.dataNetworkType)
        assertEquals(32, gsmIdentity.servingPci)
        assertNull("eNodeB ID must be null on 2G GSM", gsmIdentity.derivedEnodebId)
        assertNull("Sector ID must be null on 2G GSM", gsmIdentity.derivedSectorId)
        assertNull("ECGI must be null on 2G GSM", gsmIdentity.ecgi)
        assertEquals("470-02-18452-4567", gsmIdentity.cgi)
        assertTrue("GSM carrier must resolve correctly", gsmIdentity.derivedBandText.contains("GSM 900") && gsmIdentity.derivedBandText.contains("ARFCN 1021"))
    }

    @Test
    fun testServiceUnavailableStates() {
        val outOfService = OperatorIdentity(
            simOperatorNumeric = "47002",
            serviceState = "OUT_OF_SERVICE"
        )
        assertEquals("No Service", outOfService.getEffectiveDisplayOperator())

        val emergencyOnly = OperatorIdentity(
            simOperatorNumeric = "47002",
            serviceState = "EMERGENCY_ONLY"
        )
        assertEquals("Emergency Calls Only", emergencyOnly.getEffectiveDisplayOperator())

        val powerOff = OperatorIdentity(
            simOperatorNumeric = "47002",
            serviceState = "POWER_OFF"
        )
        assertEquals("No Service", powerOff.getEffectiveDisplayOperator())
    }

    @Test
    fun testStaleCellInformation() {
        val staleIdentity = OperatorIdentity(
            networkOperatorNumeric = "47002",
            isStale = true,
            timestampMs = System.currentTimeMillis() - 20000L
        )
        assertEquals("Robi Axiata (Robi) (Stale)", staleIdentity.getEffectiveDisplayOperator())
        assertTrue(staleIdentity.getFreshnessText().contains("Stale"))
    }

    @Test
    fun testDualSimSubscriptionChanges() {
        // Device with SIM 1 (Robi) and SIM 2 (Banglalink)
        // Subscription 1 active for cellular data
        val sub1Identity = OperatorIdentity(
            simOperatorName = "Robi",
            simOperatorNumeric = "47002",
            networkOperatorNumeric = "47002",
            dataSubscriptionId = 1,
            servingOperatorAlpha = "Robi Axiata (Robi)",
            servingOperatorNumeric = "47002"
        )
        assertEquals(1, sub1Identity.dataSubscriptionId)
        assertEquals("Robi Axiata (Robi)", sub1Identity.getEffectiveDisplayOperator())

        // User switches data SIM to Subscription 2 (Banglalink)
        val sub2Identity = OperatorIdentity(
            simOperatorName = "Banglalink",
            simOperatorNumeric = "47003",
            networkOperatorNumeric = "47003",
            dataSubscriptionId = 2,
            servingOperatorAlpha = "Banglalink (BL)",
            servingOperatorNumeric = "47003"
        )
        assertEquals(2, sub2Identity.dataSubscriptionId)
        assertEquals("Banglalink (BL)", sub2Identity.getEffectiveDisplayOperator())
    }
}
