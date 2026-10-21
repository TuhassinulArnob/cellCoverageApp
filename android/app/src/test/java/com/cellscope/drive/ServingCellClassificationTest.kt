package com.cellscope.drive

import com.cellscope.drive.model.ServingStatus
import com.cellscope.drive.telephony.RawCellCandidate
import com.cellscope.drive.telephony.ServingCellClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServingCellClassificationTest {

    @Test
    fun testSinglePrimaryRegisteredCell_AuthoritativeClassification() {
        val candidates = listOf(
            RawCellCandidate(
                pci = 101,
                tac = 18452,
                eci = 2849102,
                earfcn = 1600,
                rsrpDbm = -85,
                rsrqDb = -9,
                isRegistered = true
            ),
            RawCellCandidate(
                pci = 102,
                earfcn = 1600,
                rsrpDbm = -95,
                rsrqDb = -12,
                isRegistered = false
            ),
            RawCellCandidate(
                pci = 103,
                earfcn = 1600,
                rsrpDbm = -102,
                rsrqDb = -15,
                isRegistered = false
            )
        )

        val classified = ServingCellClassifier.classify(candidates)

        assertEquals(3, classified.size)
        val pcell = classified.find { it.pci == 101 }
        val n1 = classified.find { it.pci == 102 }
        val n2 = classified.find { it.pci == 103 }

        assertNotNull(pcell)
        assertEquals(ServingStatus.PRIMARY_SERVING, pcell?.servingStatus)
        assertTrue(pcell?.isServing == true)

        assertNotNull(n1)
        assertEquals(ServingStatus.NEIGHBOR, n1?.servingStatus)
        assertFalse(n1?.isServing == true)

        assertNotNull(n2)
        assertEquals(ServingStatus.NEIGHBOR, n2?.servingStatus)
        assertFalse(n2?.isServing == true)
    }

    @Test
    fun testCarrierAggregation_ExplicitPCellAndSCell() {
        // API 28+ explicit cellConnectionStatus reporting:
        // PCI 101 is PRIMARY_SERVING (PCell)
        // PCI 202 is SECONDARY_SERVING (SCell for CA)
        // PCI 303 is CONNECTION_NONE (Neighbor)
        val candidates = listOf(
            RawCellCandidate(
                pci = 101,
                tac = 18452,
                eci = 2849102,
                earfcn = 1600,
                rsrpDbm = -82,
                isRegistered = true,
                connectionStatus = RawCellCandidate.CONNECTION_PRIMARY_SERVING
            ),
            RawCellCandidate(
                pci = 202,
                earfcn = 3690,
                rsrpDbm = -86,
                isRegistered = true,
                connectionStatus = RawCellCandidate.CONNECTION_SECONDARY_SERVING
            ),
            RawCellCandidate(
                pci = 303,
                earfcn = 1600,
                rsrpDbm = -105,
                isRegistered = false,
                connectionStatus = RawCellCandidate.CONNECTION_NONE
            )
        )

        val classified = ServingCellClassifier.classify(candidates)

        val pcell = classified.find { it.pci == 101 }
        val scell = classified.find { it.pci == 202 }
        val neighbor = classified.find { it.pci == 303 }

        assertEquals(ServingStatus.PRIMARY_SERVING, pcell?.servingStatus)
        assertEquals(ServingStatus.SECONDARY_SERVING, scell?.servingStatus)
        assertEquals(ServingStatus.NEIGHBOR, neighbor?.servingStatus)

        // Exactly one primary serving cell
        assertEquals(1, classified.count { it.servingStatus == ServingStatus.PRIMARY_SERVING })
        assertEquals(1, classified.count { it.servingStatus == ServingStatus.SECONDARY_SERVING })
        assertEquals(1, classified.count { it.servingStatus == ServingStatus.NEIGHBOR })
    }

    @Test
    fun testAmbiguousMultipleRegisteredCells_DisambiguationBugReproduction() {
        // Reproduces the exact field issue observed by user:
        // RIL reported both PCI 43 and PCI 67 with isRegistered = true.
        // PCI 43 has TAC 18452, ECI 2849102, EARFCN 1399, RSRP -106 dBm.
        // PCI 67 has missing TAC/ECI, EARFCN 1399, RSRP -109 dBm.
        val candidates = listOf(
            RawCellCandidate(
                pci = 43,
                tac = 18452,
                eci = 2849102,
                earfcn = 1399,
                rsrpDbm = -106,
                isRegistered = true
            ),
            RawCellCandidate(
                pci = 67,
                tac = null,
                eci = null,
                earfcn = 1399,
                rsrpDbm = -109,
                isRegistered = true
            ),
            RawCellCandidate(
                pci = 88,
                tac = null,
                eci = null,
                earfcn = 1399,
                rsrpDbm = -112,
                isRegistered = false
            )
        )

        val classified = ServingCellClassifier.classify(candidates)

        val cell43 = classified.find { it.pci == 43 }
        val cell67 = classified.find { it.pci == 67 }
        val cell88 = classified.find { it.pci == 88 }

        // PCI 43 must be selected as the single authoritative PCell
        assertEquals(ServingStatus.PRIMARY_SERVING, cell43?.servingStatus)
        assertTrue(cell43?.isServing == true)

        // PCI 67 must NOT be duplicate PRIMARY_SERVING; it must be SERVING_UNCONFIRMED
        assertEquals(ServingStatus.SERVING_UNCONFIRMED, cell67?.servingStatus)
        assertTrue("Serving evidence must clarify unconfirmed RIL status", cell67?.servingEvidence?.contains("unconfirmed") == true)

        // PCI 88 is neighbor
        assertEquals(ServingStatus.NEIGHBOR, cell88?.servingStatus)
        assertFalse(cell88?.isServing == true)

        // Only ONE primary serving cell allowed
        assertEquals(1, classified.count { it.servingStatus == ServingStatus.PRIMARY_SERVING })
    }

    @Test
    fun testZeroRegisteredCells_NeverPromotesNeighbor() {
        // Simulates radio searching / out of service / neighbor scan only.
        // No cells are registered, but strong neighbor at -75 dBm exists.
        val candidates = listOf(
            RawCellCandidate(pci = 55, rsrpDbm = -75, isRegistered = false),
            RawCellCandidate(pci = 56, rsrpDbm = -85, isRegistered = false),
            RawCellCandidate(pci = 57, rsrpDbm = -95, isRegistered = false)
        )

        val classified = ServingCellClassifier.classify(candidates)

        // Must NOT promote PCI 55 to serving
        assertEquals(0, classified.count { it.servingStatus == ServingStatus.PRIMARY_SERVING })
        assertEquals(0, classified.count { it.servingStatus == ServingStatus.SECONDARY_SERVING })
        assertEquals(0, classified.count { it.servingStatus == ServingStatus.SERVING_UNCONFIRMED })
        assertEquals(3, classified.count { it.servingStatus == ServingStatus.NEIGHBOR })
        assertTrue("All cells must have isServing = false", classified.none { it.isServing })
    }

    @Test
    fun testMathematicalConsistencyOfCellCounts() {
        val candidates = listOf(
            RawCellCandidate(pci = 1, tac = 10, eci = 100, rsrpDbm = -80, isRegistered = true),
            RawCellCandidate(pci = 2, isRegistered = true, connectionStatus = RawCellCandidate.CONNECTION_SECONDARY_SERVING),
            RawCellCandidate(pci = 3, isRegistered = true), // will become unconfirmed
            RawCellCandidate(pci = 4, isRegistered = false),
            RawCellCandidate(pci = 5, isRegistered = false)
        )

        val classified = ServingCellClassifier.classify(candidates)

        val total = classified.size
        val servingCount = classified.count { it.servingStatus == ServingStatus.PRIMARY_SERVING }
        val secondaryCount = classified.count { it.servingStatus == ServingStatus.SECONDARY_SERVING }
        val unconfirmedCount = classified.count { it.servingStatus == ServingStatus.SERVING_UNCONFIRMED }
        val neighborCount = classified.count { it.servingStatus == ServingStatus.NEIGHBOR }

        assertEquals(5, total)
        assertEquals(1, servingCount)
        assertEquals(1, secondaryCount)
        assertEquals(1, unconfirmedCount)
        assertEquals(2, neighborCount)
        assertEquals(total, servingCount + secondaryCount + unconfirmedCount + neighborCount)
    }

    @Test
    fun testDualSim_ActiveDataSubscriptionPlmnPrioritizedOverStandbySim() {
        // Dual-SIM: Standby Banglalink has -85 dBm (stronger), Active Data Robi has -102 dBm
        val candidates = listOf(
            RawCellCandidate(
                pci = 444,
                tac = 18452,
                eci = 2849102,
                earfcn = 1600,
                rsrpDbm = -85,
                isRegistered = true,
                operatorNumeric = "47003"
            ),
            RawCellCandidate(
                pci = 177,
                tac = 18450,
                eci = 2849100,
                earfcn = 1399,
                rsrpDbm = -102,
                isRegistered = true,
                operatorNumeric = "47002"
            ),
            RawCellCandidate(
                pci = 178,
                earfcn = 1399,
                rsrpDbm = -108,
                isRegistered = false,
                operatorNumeric = "47002"
            )
        )

        // Classify with Robi as preferred data PLMN
        val classified = ServingCellClassifier.classify(candidates, preferredPlmn = "47002")

        val robiCell = classified.find { it.pci == 177 }
        val blCell = classified.find { it.pci == 444 }
        val neighbor = classified.find { it.pci == 178 }

        assertNotNull(robiCell)
        assertNotNull(blCell)
        assertNotNull(neighbor)

        // Robi MUST be PRIMARY_SERVING because it matches preferredPlmn
        assertEquals(ServingStatus.PRIMARY_SERVING, robiCell?.servingStatus)
        assertTrue(robiCell?.isServing == true)

        // Banglalink MUST NOT be PRIMARY_SERVING; it becomes SERVING_UNCONFIRMED
        assertEquals(ServingStatus.SERVING_UNCONFIRMED, blCell?.servingStatus)

        // Neighbor remains NEIGHBOR
        assertEquals(ServingStatus.NEIGHBOR, neighbor?.servingStatus)
    }
}
