package com.cellscope.drive

import com.cellscope.drive.analytics.RatComparisonEngine
import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.model.NetworkGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RatComparisonEngineTest {

    private fun createMeasurement(
        id: Long,
        networkGen: String,
        networkType: String,
        rsrp: Int? = -85,
        sinr: Double? = 15.0,
        pci: Int? = 101,
        earfcn: Int? = 1600
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:00.000Z",
            timestampMs = 1729245600000L + id * 1000L,
            latitude = 23.8103,
            longitude = 90.4125,
            altitudeM = 15.0,
            accuracyM = 4.0f,
            speedKmh = 10.0f,
            networkType = networkType,
            networkGen = networkGen,
            operatorAlpha = "Grameenphone",
            operatorNumeric = "47001",
            servingPci = pci,
            tac = 24100,
            earfcn = earfcn,
            bandwidthKhz = 20000,
            rsrpDbm = rsrp,
            rsrqDb = -10,
            rssiDbm = -60,
            sinrDb = sinr,
            cqi = 12,
            timingAdvance = 2,
            isServing = true,
            isGpsValid = true
        )
    }

    private fun createActiveTest(
        id: Long,
        rat: String,
        dlMbps: Double = 25.0,
        ulMbps: Double = 10.0,
        latencyMs: Long = 30L
    ): ActiveQoeTestEntity {
        return ActiveQoeTestEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-18T10:00:00.000Z",
            timestampMs = 1729245600000L + id * 1000L,
            latitude = 23.8103,
            longitude = 90.4125,
            testType = "FULL_CYCLE",
            latencyMs = latencyMs,
            downloadSpeedMbps = dlMbps,
            uploadSpeedMbps = ulMbps,
            bytesTransferred = 1_000_000L,
            durationMs = 1000L,
            success = true,
            errorMessage = null,
            servingRat = rat,
            servingPci = 101,
            servingEarfcn = 1600,
            rsrpDbm = -85,
            sinrDb = 15.0,
            jitterMs = 4.0,
            packetLossPct = 0.0,
            congestionClassification = null,
            operator = "Grameenphone"
        )
    }

    @Test
    fun testEmptyReport() {
        val report = RatComparisonEngine.generateComparisonReport(emptyList(), emptyList())
        assertEquals(0, report.totalSamples)
        assertEquals(0, report.totalQoeTests)
        assertNull(report.dominantGeneration)
        assertEquals(4, report.profiles.size)
        assertTrue(report.comparativeSummary.contains("No survey samples available"))
    }

    @Test
    fun testMetricNamingPerGeneration() {
        val report = RatComparisonEngine.generateComparisonReport(emptyList(), emptyList())

        val p5g = report.profiles.first { it.generation == NetworkGeneration.GEN_5G }
        assertEquals("SS-RSRP", p5g.primaryMetricName)
        assertEquals("dBm", p5g.primaryMetricUnit)
        assertEquals("SS-SINR", p5g.secondaryMetricName)

        val p4g = report.profiles.first { it.generation == NetworkGeneration.GEN_4G }
        assertEquals("RSRP", p4g.primaryMetricName)
        assertEquals("dBm", p4g.primaryMetricUnit)
        assertEquals("SINR", p4g.secondaryMetricName)

        val p3g = report.profiles.first { it.generation == NetworkGeneration.GEN_3G }
        assertEquals("RSCP", p3g.primaryMetricName)
        assertEquals("dBm", p3g.primaryMetricUnit)
        assertEquals("Ec/No", p3g.secondaryMetricName)

        val p2g = report.profiles.first { it.generation == NetworkGeneration.GEN_2G }
        assertEquals("RxLev", p2g.primaryMetricName)
        assertEquals("dBm", p2g.primaryMetricUnit)
        assertEquals("RxQual / BER", p2g.secondaryMetricName)
    }

    @Test
    fun testUnobservedGenerationHasDisclosure() {
        val lteSamples = (1..5).map { createMeasurement(it.toLong(), "4G", "LTE", rsrp = -80) }
        val report = RatComparisonEngine.generateComparisonReport(lteSamples)

        val p5g = report.profiles.first { it.generation == NetworkGeneration.GEN_5G }
        assertFalse(p5g.isObservedInSession)
        assertEquals(0, p5g.sampleCount)
        assertNotNull(p5g.comparabilityWarning)
        assertTrue(p5g.comparabilityWarning!!.contains("Not observed in survey session"))

        val p4g = report.profiles.first { it.generation == NetworkGeneration.GEN_4G }
        assertTrue(p4g.isObservedInSession)
        assertEquals(5, p4g.sampleCount)
        assertEquals(-80.0, p4g.medianPrimarySignalDbm!!, 0.1)
    }

    @Test
    fun testMultiRatCoexistenceAndDominantGeneration() {
        val measurements = mutableListOf<LteMeasurementEntity>()
        // 12 LTE samples
        for (i in 1..12) {
            measurements.add(createMeasurement(i.toLong(), "4G", "LTE", rsrp = -85, sinr = 14.0, pci = 100 + i % 2))
        }
        // 4 3G samples
        for (i in 13..16) {
            measurements.add(createMeasurement(i.toLong(), "3G", "UMTS", rsrp = -92, sinr = null, pci = null, earfcn = 10562))
        }

        val activeTests = listOf(
            createActiveTest(1, "4G", dlMbps = 35.0, ulMbps = 15.0),
            createActiveTest(2, "3G", dlMbps = 4.5, ulMbps = 1.2)
        )

        val report = RatComparisonEngine.generateComparisonReport(measurements, activeTests)

        assertEquals(16, report.totalSamples)
        assertEquals(2, report.totalQoeTests)
        assertEquals(NetworkGeneration.GEN_4G, report.dominantGeneration)

        val p4g = report.profiles.first { it.generation == NetworkGeneration.GEN_4G }
        assertEquals(12, p4g.sampleCount)
        assertEquals(75.0, p4g.percentageOfTotal, 0.1) // 12/16 = 75%
        assertEquals(35.0, p4g.medianDownloadMbps!!, 0.1)
        assertEquals(2, p4g.distinctCellCount) // 2 distinct PCIs

        val p3g = report.profiles.first { it.generation == NetworkGeneration.GEN_3G }
        assertEquals(4, p3g.sampleCount)
        assertEquals(25.0, p3g.percentageOfTotal, 0.1) // 4/16 = 25%
        assertEquals(4.5, p3g.medianDownloadMbps!!, 0.1)

        assertTrue(report.comparativeSummary.contains("Multi-RAT coexistence observed"))
    }
}
