package com.cellscope.drive

import com.cellscope.drive.analytics.ServingNeighborIntelligence
import com.cellscope.drive.model.ObservedCell
import org.junit.Assert.*
import org.junit.Test

class ServingNeighborIntelligenceTest {

    @Test
    fun testInterFrequencyStrongerNeighborAnalysis() {
        // Field test case: Serving is on EARFCN 1399 (Band 3, 1800 MHz) at -106 dBm
        // Neighbor is on EARFCN 3690 (Band 8, 900 MHz) at -101 dBm (5 dB stronger)
        val serving = ObservedCell(
            pci = 43,
            tac = 18452,
            eci = 2849102,
            earfcn = 1399,
            rsrpDbm = -106,
            rsrqDb = -12,
            isServing = true
        )

        val neighbor = ObservedCell(
            pci = 351,
            earfcn = 3690,
            rsrpDbm = -101,
            rsrqDb = -10,
            isServing = false
        )

        val analysis = ServingNeighborIntelligence.analyze(serving, listOf(neighbor))

        assertTrue(analysis.hasStrongerNeighbor)
        assertTrue(analysis.isInterFrequency)
        assertEquals(-5.0, analysis.deltaRsrpDb!!, 0.01)
        assertEquals("Inter-Frequency Carrier Layer Management", analysis.engineeringVerdict)
        assertTrue(analysis.primaryReason.contains("different carrier layer"))
        assertTrue(analysis.standardsBasis.contains("TS 36.304") || analysis.standardsBasis.contains("TS 36.331"))
        assertTrue(analysis.confirmedFacts.any { it.contains("INTER-FREQUENCY") })
        assertTrue(analysis.networkHypotheses.any { it.contains("cellReselectionPriority") })
    }

    @Test
    fun testIntraFrequencyStrongerNeighborAnalysis() {
        // Serving is on EARFCN 1399 at -106 dBm, Neighbor is on SAME EARFCN 1399 at -102 dBm
        val serving = ObservedCell(
            pci = 43,
            tac = 18452,
            eci = 2849102,
            earfcn = 1399,
            rsrpDbm = -106,
            rsrqDb = -12,
            isServing = true
        )

        val neighbor = ObservedCell(
            pci = 88,
            earfcn = 1399,
            rsrpDbm = -102,
            rsrqDb = -10,
            isServing = false
        )

        val analysis = ServingNeighborIntelligence.analyze(serving, listOf(neighbor))

        assertTrue(analysis.hasStrongerNeighbor)
        assertFalse(analysis.isInterFrequency)
        assertEquals(-4.0, analysis.deltaRsrpDb!!, 0.01)
        assertTrue(analysis.engineeringVerdict.contains("Intra-Frequency"))
        assertTrue(analysis.standardsBasis.contains("Event A3"))
    }

    @Test
    fun testServingDominanceAnalysis() {
        // Serving is at -85 dBm, Best Neighbor is at -92 dBm
        val serving = ObservedCell(
            pci = 288,
            tac = 18452,
            eci = 2849102,
            earfcn = 1600,
            rsrpDbm = -85,
            rsrqDb = -8,
            isServing = true
        )

        val neighbor = ObservedCell(
            pci = 289,
            earfcn = 1600,
            rsrpDbm = -92,
            rsrqDb = -11,
            isServing = false
        )

        val analysis = ServingNeighborIntelligence.analyze(serving, listOf(neighbor))

        assertFalse(analysis.hasStrongerNeighbor)
        assertEquals(7.0, analysis.deltaRsrpDb!!, 0.01)
        assertTrue(analysis.engineeringVerdict.contains("Proper Server Dominance"))
    }
}
