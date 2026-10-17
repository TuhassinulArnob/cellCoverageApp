package com.cellscope.drive

import com.cellscope.drive.model.LteMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LteMetricsTest {

    @Test
    fun testRsrpClassification() {
        val excellent = LteMetrics(rsrpDbm = -75)
        assertEquals("EXCELLENT", excellent.rsrpRating)

        val good = LteMetrics(rsrpDbm = -85)
        assertEquals("GOOD", good.rsrpRating)

        val fair = LteMetrics(rsrpDbm = -95)
        assertEquals("FAIR", fair.rsrpRating)

        val poor = LteMetrics(rsrpDbm = -110)
        assertEquals("POOR", poor.rsrpRating)

        val noSignal = LteMetrics(rsrpDbm = null)
        assertEquals("NO SIGNAL", noSignal.rsrpRating)
    }

    @Test
    fun testRsrqClassification() {
        val excellent = LteMetrics(rsrqDb = -8)
        assertEquals("EXCELLENT", excellent.rsrqRating)

        val good = LteMetrics(rsrqDb = -12)
        assertEquals("GOOD", good.rsrqRating)

        val fair = LteMetrics(rsrqDb = -15)
        assertEquals("FAIR", fair.rsrqRating)

        val poor = LteMetrics(rsrqDb = -19)
        assertEquals("POOR", poor.rsrqRating)

        val noSignal = LteMetrics(rsrqDb = null)
        assertEquals("NO SIGNAL", noSignal.rsrqRating)
    }

    @Test
    fun testValidSignalDetection() {
        val valid = LteMetrics(servingPci = 288, rsrpDbm = -88)
        assertTrue(valid.hasValidSignal)

        val invalid = LteMetrics(servingPci = null, rsrpDbm = null)
        assertFalse(invalid.hasValidSignal)
    }
}
