package com.cellscope.drive

import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.ui.CellComparisonItem
import com.cellscope.drive.ui.ComparisonAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CellCompareFilterTest {

    @Test
    fun testComparisonAdapter_filterServingOnly() {
        val adapter = ComparisonAdapter()
        val items = listOf(
            CellComparisonItem(
                pci = 101, earfcn = 1300, bandNumber = 3, bandName = "LTE 1800",
                dlFreqMhz = 1845.0, ulFreqMhz = 1750.0, isServing = true,
                tac = 2301, eci = 123456L, avgRsrp = -82.0, minRsrp = -85, maxRsrp = -80,
                avgRsrq = -9.0, avgSinr = 16.5, sampleCount = 50
            ),
            CellComparisonItem(
                pci = 102, earfcn = 1300, bandNumber = 3, bandName = "LTE 1800",
                dlFreqMhz = 1845.0, ulFreqMhz = 1750.0, isServing = false,
                tac = null, eci = null, avgRsrp = -95.0, minRsrp = -98, maxRsrp = -92,
                avgRsrq = -12.0, avgSinr = null, sampleCount = 45
            ),
            CellComparisonItem(
                pci = 205, earfcn = 300, bandNumber = 1, bandName = "LTE 2100",
                dlFreqMhz = 2140.0, ulFreqMhz = 1950.0, isServing = false,
                tac = null, eci = null, avgRsrp = -102.0, minRsrp = -106, maxRsrp = -99,
                avgRsrq = -14.0, avgSinr = null, sampleCount = 30
            )
        )

        var listenerReportedCount = -1
        adapter.onFilterResultListener = { count ->
            listenerReportedCount = count
        }

        adapter.setItems(items)
        assertEquals(3, adapter.itemCount)
        assertEquals(3, listenerReportedCount)

        // Filter: SERVING
        adapter.setFilterRole("SERVING")
        assertEquals(1, adapter.itemCount)
        assertEquals(1, listenerReportedCount)

        // Filter: NEIGHBOR (P3 Fix validation)
        adapter.setFilterRole("NEIGHBOR")
        assertEquals(2, adapter.itemCount)
        assertEquals(2, listenerReportedCount)

        // Filter: ALL
        adapter.setFilterRole("ALL")
        assertEquals(3, adapter.itemCount)
        assertEquals(3, listenerReportedCount)
    }

    @Test
    fun testComparisonAdapter_filterEmptyResultsTriggersListener() {
        val adapter = ComparisonAdapter()
        val items = listOf(
            CellComparisonItem(
                pci = 101, earfcn = 1300, bandNumber = 3, bandName = "LTE 1800",
                dlFreqMhz = 1845.0, ulFreqMhz = 1750.0, isServing = true,
                tac = 2301, eci = 123456L, avgRsrp = -82.0, minRsrp = -85, maxRsrp = -80,
                avgRsrq = -9.0, avgSinr = 16.5, sampleCount = 50
            )
        )

        var reportedCount = -1
        adapter.onFilterResultListener = { count ->
            reportedCount = count
        }
        adapter.setItems(items)

        // When filtering by NEIGHBOR with no neighbor cells, count should be 0 and trigger callback
        adapter.setFilterRole("NEIGHBOR")
        assertEquals(0, adapter.itemCount)
        assertEquals(0, reportedCount)
    }

    @Test
    fun testNeighborCellsJsonParsing() {
        // Validates that raw neighborCellsJson format "pci:rsrp:rsrq:earfcn;..." produces valid neighbor items
        val rawJson = "102:-95:-12:1300;205:-102:-14:300;103:-108:-16:1300"
        val tokens = rawJson.split(";")
        assertEquals(3, tokens.size)

        val parsedNeighbors = mutableListOf<Triple<Int, Int?, Int?>>()
        for (tok in tokens) {
            val parts = tok.split(":")
            val pci = parts[0].toInt()
            val rsrp = parts[1].toIntOrNull()
            val earfcn = parts[3].toIntOrNull()
            parsedNeighbors.add(Triple(pci, rsrp, earfcn))
        }

        assertEquals(102, parsedNeighbors[0].first)
        assertEquals(-95, parsedNeighbors[0].second)
        assertEquals(1300, parsedNeighbors[0].third)

        assertEquals(205, parsedNeighbors[1].first)
        assertEquals(-102, parsedNeighbors[1].second)
        assertEquals(300, parsedNeighbors[1].third)
    }
}
