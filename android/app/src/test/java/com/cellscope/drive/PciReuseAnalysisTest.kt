package com.cellscope.drive

import com.cellscope.drive.analytics.ReuseAnalyzer
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class PciReuseAnalysisTest {

    private fun makeSample(
        id: Long,
        pci: Int,
        channel: Int,
        rat: String,
        lat: Double,
        lon: Double,
        rsrp: Int = -85,
        isServing: Boolean = true
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-20T05:00:00.000Z",
            timestampMs = 1792450000000L + id * 1000L,
            latitude = lat,
            longitude = lon,
            altitudeM = 12.0,
            accuracyM = 3.5f,
            speedKmh = 20.0f,
            networkType = if (rat == "2G") "2G_GSM" else "LTE",
            operatorAlpha = "Robi",
            operatorNumeric = "47002",
            servingPci = pci,
            tac = 50030,
            earfcn = if (rat == "4G") channel else null,
            bandwidthKhz = 20000,
            rsrpDbm = if (rat == "4G") rsrp else null,
            rsrqDb = -9,
            rssiDbm = rsrp,
            cqi = 10,
            timingAdvance = 1,
            isServing = isServing,
            handoverTrigger = 0,
            servingRat = rat,
            channelNumber = channel,
            channelType = if (rat == "2G") "ARFCN" else "EARFCN",
            primarySignalDbm = rsrp,
            signalMetricType = if (rat == "2G") "GSM_RSSI" else "LTE_RSRP"
        )
    }

    @Test
    fun testLteCrsModulo3ExcludesGsmAnd5g() {
        // Two 2G GSM cells sharing BSIC mod 3 (12 % 3 == 0, 15 % 3 == 0) on ARFCN 62 within 1 km
        val gsm1 = makeSample(1, pci = 12, channel = 62, rat = "2G", lat = 23.8100, lon = 90.4100)
        val gsm2 = makeSample(2, pci = 15, channel = 62, rat = "2G", lat = 23.8150, lon = 90.4130)

        val summary = ReuseAnalyzer.analyzeReuse(listOf(gsm1, gsm2))

        // CRS Modulo-3 is an LTE-only phenomenon and must NOT be triggered on 2G GSM
        assertTrue("CRS collisions must be empty for 2G GSM", summary.crsCollisions.isEmpty())
    }

    @Test
    fun testPciReuseGroupedByRatAndChannel() {
        // LTE reuse: PCI 4 on EARFCN 1399 separated by ~5 km
        val lte1 = makeSample(1, pci = 4, channel = 1399, rat = "4G", lat = 23.8100, lon = 90.4100)
        val lte2 = makeSample(2, pci = 4, channel = 1399, rat = "4G", lat = 23.8550, lon = 90.4100)

        // 2G reuse: BSIC 12 on ARFCN 62 separated by ~5 km
        val gsm1 = makeSample(3, pci = 12, channel = 62, rat = "2G", lat = 23.8100, lon = 90.4100)
        val gsm2 = makeSample(4, pci = 12, channel = 62, rat = "2G", lat = 23.8550, lon = 90.4100)

        val summary = ReuseAnalyzer.analyzeReuse(listOf(lte1, lte2, gsm1, gsm2))

        assertEquals(2, summary.reuseEvents.size)
        assertTrue(summary.reuseByRatAndChannel.containsKey("4G_1399"))
        assertTrue(summary.reuseByRatAndChannel.containsKey("2G_62"))

        // Verification of observation point disclosure
        for (event in summary.reuseEvents) {
            assertTrue(
                "Diagnostic must disclose observation separation rather than tower coordinates",
                event.diagnosticDescription.contains("observation") || event.diagnosticDescription.contains("separation")
            )
        }
    }

    @Test
    fun testCellRadiusEstimation() {
        // Case 1: Sparse samples (< 5) -> INSUFFICIENT_EVIDENCE
        val sparse = (1..3).map { i ->
            makeSample(i.toLong(), pci = 50, channel = 1399, rat = "4G", lat = 23.8100 + i * 0.0001, lon = 90.4100)
        }
        val summary1 = ReuseAnalyzer.analyzeReuse(sparse)
        val est1 = summary1.cellRadiusEstimates.firstOrNull { it.pci == 50 }
        assertNotNull(est1)
        assertEquals("INSUFFICIENT_EVIDENCE", est1!!.status)
        assertNull(est1.estimatedRadiusM)

        // Case 2: Adequate samples (8 samples) with spatial spread ~400m -> VALID_ESTIMATE
        val adequate = (1..8).map { i ->
            makeSample(i.toLong(), pci = 51, channel = 1399, rat = "4G", lat = 23.8100 + (i * 0.0005), lon = 90.4100 + (i * 0.0005))
        }
        val summary2 = ReuseAnalyzer.analyzeReuse(adequate)
        val est2 = summary2.cellRadiusEstimates.firstOrNull { it.pci == 51 }
        assertNotNull(est2)
        assertEquals("VALID_ESTIMATE", est2!!.status)
        assertNotNull(est2.estimatedRadiusM)
        assertTrue("Radius should be positive", est2.estimatedRadiusM!! > 50.0)
    }

    @Test
    fun testTheoreticalHexagonalReuseGeometryFormula() {
        // Adequate samples with known spatial extent
        val samples = (1..10).map { i ->
            makeSample(i.toLong(), pci = 100, channel = 1800, rat = "4G", lat = 23.8100 + (i * 0.0006), lon = 90.4100)
        }
        val summary = ReuseAnalyzer.analyzeReuse(samples)

        val geometries = summary.theoreticalGeometries.filter { it.channel == 1800 }
        assertEquals("Must generate N=3 and N=7 geometries", 2, geometries.size)

        val n3 = geometries.first { it.clusterSizeN == 3 }
        val n7 = geometries.first { it.clusterSizeN == 7 }

        val r = n3.averageCellRadiusM
        // For N=3: D = R * sqrt(9) = 3 * R
        assertEquals(3.0 * r, n3.theoreticalReuseDistanceM, 1.0)
        // For N=7: D = R * sqrt(21)
        assertEquals(r * sqrt(21.0), n7.theoreticalReuseDistanceM, 1.0)

        // Disclosure verification
        assertTrue("Model disclosure must be present", n3.modelDisclosure.contains("hexagonal"))
    }
}
