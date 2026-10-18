package com.cellscope.drive

import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.model.LteMetrics
import com.cellscope.drive.model.ObservedCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NrTelephonyTest {

    @Test
    fun testObservedCell5gNrModel() {
        val nrCell = ObservedCell(
            pci = 624, // 5G NR PCI in 0..1007 range
            tac = 84521, // 24-bit TAC
            nci = 17179869184L, // 36-bit NCI
            nrArfcn = 643334, // n78 band NR-ARFCN
            rsrpDbm = -85, // SS-RSRP
            rsrqDb = -11, // SS-RSRQ
            sinrDb = 18.5, // SS-SINR
            csiRsrpDbm = -88, // CSI-RSRP
            csiRsrqDb = -12, // CSI-RSRQ
            csiSinrDb = 16.0, // CSI-SINR
            isServing = true,
            networkType = "5G_NR",
            operatorAlpha = "Grameenphone 5G",
            operatorNumeric = "47001",
            timestampMs = 1700000000000L
        )

        assertEquals(624, nrCell.pci)
        assertEquals(84521, nrCell.tac)
        assertEquals(17179869184L, nrCell.nci)
        assertEquals(643334, nrCell.nrArfcn)
        assertEquals(-85, nrCell.rsrpDbm)
        assertEquals(-88, nrCell.csiRsrpDbm)
        assertEquals(18.5, nrCell.sinrDb!!, 0.01)
        assertEquals(16.0, nrCell.csiSinrDb!!, 0.01)
        assertTrue(nrCell.isServing)
        assertEquals("5G_NR", nrCell.networkType)
    }

    @Test
    fun testNrArchitectureClassificationSa() {
        val servingNr = ObservedCell(
            pci = 350,
            nci = 549755813888L,
            nrArfcn = 632000,
            rsrpDbm = -78,
            isServing = true,
            networkType = "5G_NR"
        )

        val observed = listOf(servingNr)
        val primaryIsNr = servingNr.networkType == "5G_NR"
        val hasNr = observed.any { it.networkType == "5G_NR" }

        val nrState = when {
            primaryIsNr -> "SA"
            hasNr -> "NSA"
            else -> "LTE_ONLY"
        }

        assertEquals("SA", nrState)
    }

    @Test
    fun testNrArchitectureClassificationNsa() {
        val servingLte = ObservedCell(
            pci = 120,
            tac = 4050,
            eci = 12345678,
            earfcn = 1650,
            rsrpDbm = -82,
            isServing = true,
            networkType = "LTE"
        )
        val secondaryNr = ObservedCell(
            pci = 450,
            nrArfcn = 640000,
            rsrpDbm = -88,
            isServing = false, // Secondary carrier in NSA EN-DC
            networkType = "5G_NR"
        )

        val observed = listOf(servingLte, secondaryNr)
        val primaryIsNr = servingLte.networkType == "5G_NR"
        val hasNr = observed.any { it.networkType == "5G_NR" }

        val nrState = when {
            primaryIsNr -> "SA"
            hasNr -> "NSA"
            else -> "LTE_ONLY"
        }

        assertEquals("NSA", nrState)
    }

    @Test
    fun testNrArchitectureClassificationLteOnly() {
        val servingLte = ObservedCell(
            pci = 120,
            isServing = true,
            networkType = "LTE"
        )
        val neighborLte = ObservedCell(
            pci = 121,
            isServing = false,
            networkType = "LTE"
        )

        val observed = listOf(servingLte, neighborLte)
        val primaryIsNr = servingLte.networkType == "5G_NR"
        val hasNr = observed.any { it.networkType == "5G_NR" }

        val nrState = when {
            primaryIsNr -> "SA"
            hasNr -> "NSA"
            else -> "LTE_ONLY"
        }

        assertEquals("LTE_ONLY", nrState)
    }

    @Test
    fun testMeasurementEntityNrMapping() {
        val entity = LteMeasurementEntity(
            sessionId = 1L,
            timestampIso = "2026-10-17T12:00:00Z",
            timestampMs = 1700000000000L,
            latitude = 23.8103,
            longitude = 90.4125,
            altitudeM = 15.0,
            accuracyM = 3.5f,
            speedKmh = 25.0f,
            networkType = "5G_NR",
            operatorAlpha = "Grameenphone 5G",
            operatorNumeric = "47001",
            servingPci = 702,
            tac = 15200,
            earfcn = null,
            bandwidthKhz = 100000,
            rsrpDbm = -80,
            rsrqDb = -10,
            rssiDbm = -70,
            cqi = 14,
            timingAdvance = 5,
            isServing = true,
            sinrDb = 22.0,
            nci = 68719476730L,
            nrArfcn = 643334,
            csiRsrp = -84,
            csiSinr = 20.5
        )

        assertEquals("5G_NR", entity.networkType)
        assertEquals(702, entity.servingPci)
        assertEquals(68719476730L, entity.nci)
        assertEquals(643334, entity.nrArfcn)
        assertEquals(-84, entity.csiRsrp)
        assertEquals(20.5, entity.csiSinr!!, 0.01)
    }
}
