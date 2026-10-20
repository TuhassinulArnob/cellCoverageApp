package com.cellscope.drive

import com.cellscope.drive.analytics.TowerEstimator
import com.cellscope.drive.data.CsvImporter
import com.cellscope.drive.data.entity.LteMeasurementEntity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class CsvImporterTest {

    private fun findDocsFile(filename: String): File {
        // Try multiple relative paths from current working dir / test runner root
        val candidates = listOf(
            File("../../docs", filename),
            File("../docs", filename),
            File("docs", filename),
            File(System.getProperty("user.dir") ?: ".", "docs/$filename"),
            File(System.getProperty("user.dir") ?: ".", "../../docs/$filename")
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not find fixture $filename in candidate locations: ${candidates.map { it.absolutePath }}")
    }

    @Test
    fun testValidateIuToKushtiaFixture() {
        val file = findDocsFile("IUtoKushtia.csv")
        assertTrue("Fixture must exist", file.exists())

        val result = FileInputStream(file).use { stream ->
            CsvImporter.validateInputStream(stream, file.name)
        }

        assertTrue("Dataset must be valid", result.isValid)
        assertEquals("IUtoKushtia.csv", result.filename)
        assertEquals(2131, result.totalRows)
        assertEquals(2131, result.validRows)
        assertEquals(2105, result.validGpsRows)
        assertEquals(26, result.missingGpsRows)
        assertEquals(305, result.placeholderRsrpCount)
        assertEquals("Robi", result.detectedOperator)
        assertTrue(result.detectedRats.contains("LTE"))

        // Monotonic duration: from 10:15:17 to 10:52:40 is ~2243 seconds (~37 minutes), NOT 2 seconds!
        assertEquals("2026-10-18T10:15:17.740+06:00", result.startTimestampIso)
        assertEquals("2026-10-18T10:52:40.785+06:00", result.endTimestampIso)
        assertEquals(2243L, result.durationSeconds)

        // Route distance ~23-25 km
        assertTrue("Distance should be ~24 km", result.totalDistanceKm in 22.0..26.0)

        // Warnings should accurately highlight missing GPS and placeholder RSRP
        assertTrue(result.warnings.any { it.contains("missing valid GPS") })
        assertTrue(result.warnings.any { it.contains("placeholder RSRP") })
    }

    @Test
    fun testValidateKushtiaToVeramaraFixture() {
        val file = findDocsFile("kushtia to veramara.csv")
        assertTrue("Fixture must exist", file.exists())

        val result = FileInputStream(file).use { stream ->
            CsvImporter.validateInputStream(stream, file.name)
        }

        assertTrue("Dataset must be valid", result.isValid)
        assertEquals("kushtia to veramara.csv", result.filename)
        assertEquals(2023, result.totalRows)
        assertEquals(2023, result.validRows)
        assertEquals(2023, result.validGpsRows)
        assertEquals(0, result.missingGpsRows)
        assertEquals(146, result.placeholderRsrpCount)
        assertEquals("Robi", result.detectedOperator)

        // Monotonic duration: from 13:19:11 to 13:54:24 is ~2112 seconds (~35 minutes), NOT 2 seconds!
        assertEquals("2026-10-18T13:19:11.868+06:00", result.startTimestampIso)
        assertEquals("2026-10-18T13:54:24.241+06:00", result.endTimestampIso)
        assertEquals(2112L, result.durationSeconds)

        // Route distance ~21-23 km
        assertTrue("Distance should be ~21-23 km", result.totalDistanceKm in 20.0..24.0)
    }

    @Test
    fun testPciReuseAndSpatialClustering() {
        // Create 12 samples of PCI 18: 6 samples in Kushtia (km 0), and 6 samples in Bheramara (km 15)
        val samples = mutableListOf<LteMeasurementEntity>()
        // Cluster 1 (Kushtia)
        for (i in 0 until 6) {
            samples.add(
                LteMeasurementEntity(
                    id = (i + 1).toLong(),
                    sessionId = 1L,
                    timestampIso = "2026-10-18T13:19:0$i.000+06:00",
                    timestampMs = 1700000000000L + i * 1000L,
                    latitude = 23.9080 + (i * 0.0001),
                    longitude = 89.1210 + (i * 0.0001),
                    altitudeM = 15.0,
                    accuracyM = 3.0f,
                    speedKmh = 20.0f,
                    networkType = "LTE",
                    operatorAlpha = "Robi",
                    operatorNumeric = "47002",
                    servingPci = 18,
                    tac = 50030,
                    earfcn = 1399,
                    bandwidthKhz = 20000,
                    rsrpDbm = -75 - i,
                    rsrqDb = -8,
                    rssiDbm = -70,
                    cqi = 10,
                    timingAdvance = 1,
                    isServing = true,
                    isGpsValid = true
                )
            )
        }
        // Cluster 2 (Bheramara: ~15 km away, lat 24.02)
        for (i in 0 until 6) {
            samples.add(
                LteMeasurementEntity(
                    id = (i + 7).toLong(),
                    sessionId = 1L,
                    timestampIso = "2026-10-18T13:40:0$i.000+06:00",
                    timestampMs = 1700001200000L + i * 1000L,
                    latitude = 24.0200 + (i * 0.0001),
                    longitude = 89.0200 + (i * 0.0001),
                    altitudeM = 15.0,
                    accuracyM = 3.0f,
                    speedKmh = 20.0f,
                    networkType = "LTE",
                    operatorAlpha = "Robi",
                    operatorNumeric = "47002",
                    servingPci = 18, // Identical reused PCI!
                    tac = 50030,
                    earfcn = 1399,
                    bandwidthKhz = 20000,
                    rsrpDbm = -70 - i,
                    rsrqDb = -7,
                    rssiDbm = -65,
                    cqi = 12,
                    timingAdvance = 1,
                    isServing = true,
                    isGpsValid = true
                )
            )
        }

        // Tower estimation must identify TWO separate candidate cell sites for PCI 18, NOT merge them into one!
        val towers = TowerEstimator.estimateTowers(samples, minSamplesRequired = 5)
        assertEquals("PCI reuse across 15 km must create 2 separate candidate sites", 2, towers.size)
        assertEquals(18, towers[0].pci)
        assertEquals(18, towers[1].pci)

        // Cluster 1 should be near lat 23.908, Cluster 2 near lat 24.020
        val cluster1 = towers.first { it.clusterId == 1 }
        val cluster2 = towers.first { it.clusterId == 2 }
        assertEquals(23.908, cluster1.estimatedLat, 0.005)
        assertEquals(24.020, cluster2.estimatedLat, 0.005)
    }

    @Test
    fun testEnodebIdAndLocalCellDerivation() {
        // ECI is 28 bits: eNodeB ID is top 20 bits (eci / 256), Local Cell ID is bottom 8 bits (eci % 256)
        // E.g., ECI = 5003001
        val sample = LteMeasurementEntity(
            id = 1L,
            sessionId = 1L,
            timestampIso = "2026-10-18T10:15:17.000+06:00",
            timestampMs = 1700000000000L,
            latitude = 23.722,
            longitude = 89.154,
            altitudeM = 10.0,
            accuracyM = 3.0f,
            speedKmh = 0f,
            networkType = "LTE",
            operatorAlpha = "Robi",
            operatorNumeric = "47002",
            servingPci = 4,
            tac = 50030,
            earfcn = 1399,
            bandwidthKhz = 20000,
            rsrpDbm = -75,
            rsrqDb = -8,
            rssiDbm = -70,
            cqi = 10,
            timingAdvance = 1,
            isServing = true,
            eci = 5003001,
            isGpsValid = true
        )

        val samples = (1..6).map { sample.copy(id = it.toLong(), timestampMs = 1700000000000L + it * 1000L) }
        val towers = TowerEstimator.estimateTowers(samples, minSamplesRequired = 5)
        assertEquals(1, towers.size)
        val tower = towers.first()

        assertEquals(5003001 / 256, tower.enodebId)
        assertEquals(5003001 % 256, tower.localCellId)
        assertEquals("47002-5003001", tower.ecgi)
        assertEquals(1399, tower.earfcn)
        assertEquals("Robi", tower.operator)
    }
}
