package com.cellscope.drive

import com.cellscope.drive.analytics.TowerEstimator
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TowerEstimatorTest {

    private fun createSample(
        id: Long,
        lat: Double,
        lon: Double,
        pci: Int,
        rsrp: Int
    ): LteMeasurementEntity {
        return LteMeasurementEntity(
            id = id,
            sessionId = 1L,
            timestampIso = "2026-10-17T12:00:0$id.000Z",
            timestampMs = 1000L * id,
            latitude = lat,
            longitude = lon,
            altitudeM = 10.0,
            accuracyM = 3.0f,
            speedKmh = 25.0f,
            networkType = "LTE",
            operatorAlpha = "Grameenphone",
            operatorNumeric = "47001",
            servingPci = pci,
            tac = 18452,
            earfcn = 1600,
            bandwidthKhz = 20000,
            rsrpDbm = rsrp,
            rsrqDb = -11,
            rssiDbm = -60,
            cqi = 12,
            timingAdvance = 2,
            isServing = true,
            handoverTrigger = 0,
            isGpsValid = true
        )
    }

    @Test
    fun testTowerCentroidEstimation() {
        // Samples arranged around tower at (23.8120, 90.4150)
        // Highest RSRP (-72 dBm) is near (23.8120, 90.4150)
        val samples = listOf(
            createSample(1, 23.8110, 90.4140, 288, -98),
            createSample(2, 23.8115, 90.4145, 288, -85),
            createSample(3, 23.8120, 90.4150, 288, -72), // Peak
            createSample(4, 23.8125, 90.4155, 288, -88),
            createSample(5, 23.8130, 90.4160, 288, -102),
            createSample(6, 23.8118, 90.4152, 288, -75)  // Near peak
        )

        val towers = TowerEstimator.estimateTowers(samples, minSamplesRequired = 5)

        assertEquals(1, towers.size)
        val tower = towers.first()
        assertEquals(288, tower.pci)
        assertTrue(tower.isExperimental)
        assertEquals(-72, tower.peakRsrpDbm)

        // Estimated location should be heavily pulled towards the peak (23.8120, 90.4150)
        assertEquals(23.8120, tower.estimatedLat, 0.0005)
        assertEquals(90.4150, tower.estimatedLon, 0.0005)
        assertTrue(tower.confidenceScore in 0.0f..1.0f)
    }

    @Test
    fun testInsufficientSamplesRejection() {
        val samples = listOf(
            createSample(1, 23.8110, 90.4140, 288, -98),
            createSample(2, 23.8115, 90.4145, 288, -85)
        )

        // Default requires at least 5 samples
        val towers = TowerEstimator.estimateTowers(samples, minSamplesRequired = 5)
        assertTrue(towers.isEmpty())
    }
}
