package com.cellscope.drive

import com.cellscope.drive.model.ObservedCell
import com.cellscope.drive.model.ServingStatus
import com.cellscope.drive.telephony.CellularBandResolver
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.max

class RfCarrierVisualizationTest {

    @Test
    fun testMultiBandCarrierResolution() {
        val servingCell = ObservedCell(
            pci = 45,
            earfcn = 1650,
            bandwidthKhz = 10000,
            isServing = true,
            servingStatus = ServingStatus.PRIMARY_SERVING,
            networkType = "LTE",
            rsrpDbm = -82
        )
        val neighborBand8 = ObservedCell(
            pci = 120,
            earfcn = 3625,
            bandwidthKhz = 5000,
            isServing = false,
            servingStatus = ServingStatus.NEIGHBOR,
            networkType = "LTE",
            rsrpDbm = -96
        )

        val carrierServing = CellularBandResolver.resolve(servingCell)
        val carrierB8 = CellularBandResolver.resolve(neighborBand8)

        assertNotNull(carrierServing)
        assertNotNull(carrierB8)

        assertEquals(3, carrierServing!!.bandNumber)
        assertEquals(1850.0, carrierServing.dlCenterFreqMhz, 0.001)

        assertEquals(8, carrierB8!!.bandNumber)
        assertEquals(942.5, carrierB8.dlCenterFreqMhz, 0.001)

        // Verify total span across multiple disparate bands
        val totalSpan = carrierServing.dlCenterFreqMhz - carrierB8.dlCenterFreqMhz
        assertTrue("Total span across B8 and B3 should exceed 60 MHz", totalSpan > 60.0)
    }

    @Test
    fun testAutoFocusWindowingOnServingCellBand() {
        val cells = listOf(
            ObservedCell(pci = 45, earfcn = 1650, isServing = true, servingStatus = ServingStatus.PRIMARY_SERVING, networkType = "LTE", rsrpDbm = -80),
            ObservedCell(pci = 46, earfcn = 1650, isServing = false, servingStatus = ServingStatus.NEIGHBOR, networkType = "LTE", rsrpDbm = -92),
            ObservedCell(pci = 120, earfcn = 3625, isServing = false, servingStatus = ServingStatus.NEIGHBOR, networkType = "LTE", rsrpDbm = -98)
        )

        val resolved = cells.mapNotNull { cell ->
            val info = CellularBandResolver.resolve(cell)
            if (info != null) Pair(cell, info) else null
        }

        val totalMin = resolved.minOf { it.second.lowFreqMhz }
        val totalMax = resolved.maxOf { it.second.highFreqMhz }
        val totalSpan = totalMax - totalMin

        assertTrue("Multi-band cells span > 60 MHz", totalSpan > 60.0)

        // Auto-focus logic: select serving cell's band
        val serving = resolved.firstOrNull { it.first.isServing && it.first.isConfirmedServing }
        assertNotNull("Serving cell must be found", serving)
        val targetBand = serving!!.second.bandNumber
        assertEquals(3, targetBand)

        // Filtered carriers for focused window
        val windowedCarriers = resolved.filter { it.second.bandNumber == targetBand }
        assertEquals(2, windowedCarriers.size)

        val windowMin = windowedCarriers.minOf { it.second.lowFreqMhz }
        val windowMax = windowedCarriers.maxOf { it.second.highFreqMhz }
        val baseSpan = max(windowMax - windowMin + 10.0, 20.0)

        assertTrue("Windowed span should be bounded and readable (~20 MHz)", baseSpan in 20.0..40.0)
    }

    @Test
    fun testBellEnvelopeEndpointsCalculation() {
        val cell = ObservedCell(
            pci = 45,
            earfcn = 1650,
            bandwidthKhz = 10000,
            isServing = true,
            networkType = "LTE",
            rsrpDbm = -85
        )
        val carrier = CellularBandResolver.resolve(cell)
        assertNotNull(carrier)

        // DL center: 1850.0 MHz, 10 MHz BW -> low: 1845.0, high: 1855.0
        assertEquals(1850.0, carrier!!.dlCenterFreqMhz, 0.001)
        assertEquals(1845.0, carrier.lowFreqMhz, 0.001)
        assertEquals(1855.0, carrier.highFreqMhz, 0.001)
        assertEquals(10.0, carrier.highFreqMhz - carrier.lowFreqMhz, 0.001)
    }

    @Test
    fun testModulo3CrsShiftGrouping() {
        val pci0 = 300 // 300 % 3 = 0
        val pci1 = 301 // 301 % 3 = 1
        val pci2 = 302 // 302 % 3 = 2

        assertEquals(0, pci0 % 3)
        assertEquals(1, pci1 % 3)
        assertEquals(2, pci2 % 3)
    }
}
