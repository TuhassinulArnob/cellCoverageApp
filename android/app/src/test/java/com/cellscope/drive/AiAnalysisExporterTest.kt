package com.cellscope.drive

import android.content.Context
import android.content.ContextWrapper
import com.cellscope.drive.analytics.anomaly.AnomalyReport
import com.cellscope.drive.analytics.anomaly.AnomalyResult
import com.cellscope.drive.analytics.anomaly.AnomalySeverity
import com.cellscope.drive.analytics.anomaly.AnomalyType
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.export.AiAnalysisExporter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AiAnalysisExporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TestContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getExternalFilesDir(type: String?): File {
            val dir = File(baseDir, type ?: "files")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        override fun getPackageName(): String = "com.cellscope.drive"
    }

    private fun createSampleSession(): DriveSession {
        return DriveSession(
            id = 42L,
            sessionName = "Survey_Dhaka_Corridor_B3",
            startTimeMs = 1700000000000L,
            endTimeMs = 1700003600000L,
            totalDistanceM = 12540.0,
            sampleCount = 3600,
            avgRsrpDbm = -87.4
        )
    }

    private fun createSampleReport(): AnomalyReport {
        val finding = AnomalyResult(
            id = "ANO-42-001",
            sessionId = 42L,
            timestampIso = "2026-10-17T14:30:15.000Z",
            timestampMs = 1700000000000L,
            latitude = 23.8103,
            longitude = 90.4125,
            anomalyType = AnomalyType.ABRUPT_SIGNAL_DROP,
            severity = AnomalySeverity.CRITICAL,
            title = "Abrupt RSRP Plunge (Δ -28 dB)",
            detectionReason = "Serving cell RSRP dropped from -78 dBm to -106 dBm within 2 seconds without cell transition.",
            candidateHypothesis = "High-density concrete clutter shadowing or abrupt antenna null sector boundary.",
            confidenceScore = 0.92f,
            primaryContributingFeature = "rsrp_delta_db",
            mlAnomalyScore = -0.684,
            supportingMetrics = mapOf("rsrp_drop_db" to "-28", "time_window_sec" to "2.0")
        )

        return AnomalyReport(
            sessionId = 42L,
            totalAnomalies = 1,
            criticalCount = 1,
            warningCount = 0,
            infoCount = 0,
            mlAgreementRatioPct = 100.0,
            executiveSummary = "Identified 1 critical RF anomaly requiring physical site azimuth inspection.",
            anomalies = listOf(finding)
        )
    }

    @Test
    fun testExportAnalysisToMarkdown() {
        val testContext = TestContext(tempFolder.newFolder("test_md"))
        val session = createSampleSession()
        val report = createSampleReport()

        val mdFile = AiAnalysisExporter.exportAnalysisToMarkdown(testContext, session, report)

        assertNotNull(mdFile)
        assertTrue(mdFile.exists())
        val content = mdFile.readText()

        // Verify Author attribution per master prompt
        assertTrue(content.contains("Sarkar Tuhassinul Arnob"))
        assertTrue(content.contains("arnob.ict.iu@gmail.com"))

        // Verify Session Details
        assertTrue(content.contains("Survey_Dhaka_Corridor_B3"))
        assertTrue(content.contains("#42"))
        assertTrue(content.contains("12.54 km"))
        assertTrue(content.contains("3600"))

        // Verify Anomaly Details
        assertTrue(content.contains("Finding #1: Abrupt RSRP Plunge (Δ -28 dB) [CRITICAL]"))
        assertTrue(content.contains("`ANO-42-001`"))
        assertTrue(content.contains("Detection Reason"))
        assertTrue(content.contains("CandidateHypothesis") || content.contains("Root-Cause Hypothesis"))
        assertTrue(content.contains("Recommended Engineering Action"))
        assertTrue(content.contains("antenna down-tilt, azimuth alignment"))
    }

    @Test
    fun testExportAnalysisToJson() {
        val testContext = TestContext(tempFolder.newFolder("test_json"))
        val session = createSampleSession()
        val report = createSampleReport()

        val jsonFile = AiAnalysisExporter.exportAnalysisToJson(testContext, session, report)

        assertNotNull(jsonFile)
        assertTrue(jsonFile.exists())
        val content = jsonFile.readText()

        assertTrue(content.contains("\"project\": \"CellScope\""))
        assertTrue(content.contains("\"lead\": \"Sarkar Tuhassinul Arnob\""))
        assertTrue(content.contains("\"contact\": \"arnob.ict.iu@gmail.com\""))
        assertTrue(content.contains("\"sessionId\": 42"))
        assertTrue(content.contains("\"totalAnomalies\": 1"))
        assertTrue(content.contains("\"criticalCount\": 1"))
        assertTrue(content.contains("\"id\": \"ANO-42-001\""))
        assertTrue(content.contains("\"severity\": \"CRITICAL\""))
        assertTrue(content.contains("\"type\": \"ABRUPT_SIGNAL_DROP\""))
        assertTrue(content.contains("-0.684"))
    }
}
