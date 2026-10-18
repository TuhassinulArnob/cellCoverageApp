package com.cellscope.drive

import android.telephony.TelephonyManager
import com.cellscope.drive.model.NetworkGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkGenerationTest {

    @Test
    fun testFromNetworkType5gNr() {
        assertEquals(NetworkGeneration.GEN_5G, NetworkGeneration.fromNetworkType(TelephonyManager.NETWORK_TYPE_NR))
        assertEquals(NetworkGeneration.GEN_5G, NetworkGeneration.fromNetworkType(TelephonyManager.NETWORK_TYPE_LTE, nrState = "NSA"))
        assertEquals(NetworkGeneration.GEN_5G, NetworkGeneration.fromNetworkType(TelephonyManager.NETWORK_TYPE_UNKNOWN, nrState = "SA"))
    }

    @Test
    fun testFromNetworkType4gLte() {
        assertEquals(NetworkGeneration.GEN_4G, NetworkGeneration.fromNetworkType(TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals(NetworkGeneration.GEN_4G, NetworkGeneration.fromNetworkType(19 /* NETWORK_TYPE_LTE_CA */))
    }

    @Test
    fun testFromNetworkType3gUmtsHspa() {
        assertEquals(NetworkGeneration.GEN_3G, NetworkGeneration.fromNetworkType(TelephonyManager.NETWORK_TYPE_UMTS))
        assertEquals(NetworkGeneration.GEN_3G, NetworkGeneration.fromNetworkType(TelephonyManager.NETWORK_TYPE_HSPA))
        assertEquals(NetworkGeneration.GEN_3G, NetworkGeneration.fromNetworkType(TelephonyManager.NETWORK_TYPE_HSPAP))
        assertEquals(NetworkGeneration.GEN_3G, NetworkGeneration.fromNetworkType(TelephonyManager.NETWORK_TYPE_HSDPA))
        assertEquals(NetworkGeneration.GEN_3G, NetworkGeneration.fromNetworkType(TelephonyManager.NETWORK_TYPE_HSUPA))
        assertEquals(NetworkGeneration.GEN_3G, NetworkGeneration.fromNetworkType(TelephonyManager.NETWORK_TYPE_EVDO_0))
    }

    @Test
    fun testFromNetworkType2gGsmEdge() {
        assertEquals(NetworkGeneration.GEN_2G, NetworkGeneration.fromNetworkType(TelephonyManager.NETWORK_TYPE_EDGE))
        assertEquals(NetworkGeneration.GEN_2G, NetworkGeneration.fromNetworkType(TelephonyManager.NETWORK_TYPE_GPRS))
        assertEquals(NetworkGeneration.GEN_2G, NetworkGeneration.fromNetworkType(16 /* NETWORK_TYPE_GSM */))
        assertEquals(NetworkGeneration.GEN_2G, NetworkGeneration.fromNetworkType(TelephonyManager.NETWORK_TYPE_CDMA))
        assertEquals(NetworkGeneration.GEN_2G, NetworkGeneration.fromNetworkType(TelephonyManager.NETWORK_TYPE_1xRTT))
    }

    @Test
    fun testUnknownNeverClassifiedAs2gOrOos() {
        assertEquals(NetworkGeneration.UNKNOWN, NetworkGeneration.fromNetworkType(TelephonyManager.NETWORK_TYPE_UNKNOWN))
        assertEquals(NetworkGeneration.UNKNOWN, NetworkGeneration.fromNetworkType(null))
        assertEquals(NetworkGeneration.UNKNOWN, NetworkGeneration.fromNetworkType(9999))
        assertFalse(NetworkGeneration.UNKNOWN.isCellularService)
    }

    @Test
    fun testFromStringParsing() {
        assertEquals(NetworkGeneration.GEN_5G, NetworkGeneration.fromString("5G"))
        assertEquals(NetworkGeneration.GEN_5G, NetworkGeneration.fromString("5G NR"))
        assertEquals(NetworkGeneration.GEN_5G, NetworkGeneration.fromString("NR"))
        assertEquals(NetworkGeneration.GEN_4G, NetworkGeneration.fromString("4G"))
        assertEquals(NetworkGeneration.GEN_4G, NetworkGeneration.fromString("LTE"))
        assertEquals(NetworkGeneration.GEN_4G, NetworkGeneration.fromString("4G LTE"))
        assertEquals(NetworkGeneration.GEN_3G, NetworkGeneration.fromString("3G"))
        assertEquals(NetworkGeneration.GEN_3G, NetworkGeneration.fromString("WCDMA"))
        assertEquals(NetworkGeneration.GEN_3G, NetworkGeneration.fromString("HSPA+"))
        assertEquals(NetworkGeneration.GEN_2G, NetworkGeneration.fromString("2G"))
        assertEquals(NetworkGeneration.GEN_2G, NetworkGeneration.fromString("GSM"))
        assertEquals(NetworkGeneration.GEN_2G, NetworkGeneration.fromString("EDGE"))
        assertEquals(NetworkGeneration.OUT_OF_SERVICE, NetworkGeneration.fromString("OOS"))
        assertEquals(NetworkGeneration.OUT_OF_SERVICE, NetworkGeneration.fromString("OUT_OF_SERVICE"))
        assertEquals(NetworkGeneration.UNKNOWN, NetworkGeneration.fromString("WiFi"))
        assertEquals(NetworkGeneration.UNKNOWN, NetworkGeneration.fromString(null))
    }

    @Test
    fun testDetailedTechName() {
        assertEquals("5G NR", NetworkGeneration.getDetailedTechName(TelephonyManager.NETWORK_TYPE_NR))
        assertEquals("5G NR (NSA EN-DC)", NetworkGeneration.getDetailedTechName(TelephonyManager.NETWORK_TYPE_LTE, nrState = "NSA"))
        assertEquals("4G LTE", NetworkGeneration.getDetailedTechName(TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals("3G WCDMA/UMTS", NetworkGeneration.getDetailedTechName(TelephonyManager.NETWORK_TYPE_UMTS))
        assertEquals("2G EDGE (EGPRS)", NetworkGeneration.getDetailedTechName(TelephonyManager.NETWORK_TYPE_EDGE))
        assertEquals("2G GSM", NetworkGeneration.getDetailedTechName(16))
    }

    @Test
    fun testServiceStateAndDataStateNames() {
        assertEquals("IN_SERVICE", NetworkGeneration.getServiceStateName(0))
        assertEquals("OUT_OF_SERVICE", NetworkGeneration.getServiceStateName(1))
        assertEquals("EMERGENCY_ONLY", NetworkGeneration.getServiceStateName(2))

        assertEquals("CONNECTED", NetworkGeneration.getDataStateName(TelephonyManager.DATA_CONNECTED))
        assertEquals("DISCONNECTED", NetworkGeneration.getDataStateName(TelephonyManager.DATA_DISCONNECTED))
        assertEquals("CONNECTING", NetworkGeneration.getDataStateName(TelephonyManager.DATA_CONNECTING))

        assertEquals("IDLE", NetworkGeneration.getCallStateName(TelephonyManager.CALL_STATE_IDLE))
        assertEquals("RINGING", NetworkGeneration.getCallStateName(TelephonyManager.CALL_STATE_RINGING))
        assertEquals("OFFHOOK", NetworkGeneration.getCallStateName(TelephonyManager.CALL_STATE_OFFHOOK))
    }
}
