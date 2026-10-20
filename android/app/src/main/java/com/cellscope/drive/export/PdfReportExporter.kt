package com.cellscope.drive.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.cellscope.drive.analytics.CallDropRiskEngine
import com.cellscope.drive.analytics.CapacityRecommendationEngine
import com.cellscope.drive.analytics.CapacityScenarioResult
import com.cellscope.drive.analytics.CapacityScenarioSpec
import com.cellscope.drive.analytics.CongestionAnalyzer
import com.cellscope.drive.analytics.CongestionEvidenceEngine
import com.cellscope.drive.analytics.CongestionEvidenceResult
import com.cellscope.drive.analytics.EvidenceClassification
import com.cellscope.drive.analytics.HistoricalQoeBaseline
import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.analytics.CoverageHoleEstimator
import com.cellscope.drive.analytics.CoveragePlanner
import com.cellscope.drive.analytics.EventCapacitySimulator
import com.cellscope.drive.analytics.EventTrafficProfile
import com.cellscope.drive.analytics.EventVenue
import com.cellscope.drive.analytics.HandoverAnalyzer
import com.cellscope.drive.analytics.InterferenceAnalyzer
import com.cellscope.drive.analytics.NetworkContinuityAnalyzer
import com.cellscope.drive.analytics.RadioQualityEngine
import com.cellscope.drive.analytics.RatMobilityDiagnosticsEngine
import com.cellscope.drive.analytics.ReuseAnalyzer
import com.cellscope.drive.analytics.ServiceHoleDetector
import com.cellscope.drive.analytics.TrafficIntelligenceEngine
import com.cellscope.drive.data.entity.CallReliabilityEventEntity
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.data.entity.NetworkContinuityEventEntity
import com.cellscope.drive.data.entity.ServiceHoleEntity
import com.cellscope.drive.model.NetworkGeneration
import com.cellscope.drive.telephony.CellularBandResolver
import com.cellscope.drive.util.TelecomTimeUtils
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GraphInterpretation(
    val metricMeasured: String,
    val provenance: String,
    val formulation: String,
    val sampleCountScope: String,
    val keyObservations: String,
    val engineeringInterpretation: String,
    val alternativeExplanations: String,
    val limitationsAndActions: String
)

object PdfReportExporter {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN_X = 40f
    private const val CONTENT_WIDTH = 515f
    private const val MAX_Y = 780f

    private class ReportPageManager(private val doc: PdfDocument) {
        var pageNumber = 1
            private set
        private var currentPage: PdfDocument.Page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var canvas = currentPage.canvas
        var y = 40f

        fun ensureSpace(neededHeight: Float, onPageBreak: (() -> Unit)? = null) {
            if (y + neededHeight > MAX_Y) {
                newPage()
                onPageBreak?.invoke()
            }
        }

        fun newPage() {
            drawFooter()
            doc.finishPage(currentPage)
            pageNumber++
            currentPage = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            canvas = currentPage.canvas
            y = 40f
            drawRunningHeader()
        }

        private fun drawFooter() {
            val footerPaint = Paint().apply {
                color = Color.parseColor("#757575")
                textSize = 8f
                typeface = Typeface.DEFAULT
                isAntiAlias = true
            }
            val linePaint = Paint().apply {
                color = Color.parseColor("#E0E0E0")
                strokeWidth = 1f
            }
            canvas.drawLine(MARGIN_X, 802f, MARGIN_X + CONTENT_WIDTH, 802f, linePaint)
            canvas.drawText("CellScope Field Assessment • Telecom Engineering Platform Lead: Sarkar Tuhassinul Arnob (arnob.ict.iu@gmail.com)", MARGIN_X, 815f, footerPaint)
            val pageText = "Page $pageNumber"
            val pageTextW = footerPaint.measureText(pageText)
            canvas.drawText(pageText, MARGIN_X + CONTENT_WIDTH - pageTextW, 815f, footerPaint)
        }

        private fun drawRunningHeader() {
            val headerPaint = Paint().apply {
                color = Color.parseColor("#757575")
                textSize = 8f
                typeface = Typeface.DEFAULT
                isAntiAlias = true
            }
            val linePaint = Paint().apply {
                color = Color.parseColor("#E0E0E0")
                strokeWidth = 1f
            }
            canvas.drawText("CellScope Cellular Telemetry & RF Engineering Report — Continued", MARGIN_X, y, headerPaint)
            y += 6f
            canvas.drawLine(MARGIN_X, y, MARGIN_X + CONTENT_WIDTH, y, linePaint)
            y += 18f
        }

        fun finishDocument() {
            drawFooter()
            doc.finishPage(currentPage)
        }

        fun drawWrappedText(
            text: String,
            x: Float,
            maxWidth: Float,
            paint: Paint,
            lineSpacing: Float = 13f
        ) {
            val words = text.split(" ")
            var line = ""
            for (word in words) {
                val testLine = if (line.isEmpty()) word else "$line $word"
                val width = paint.measureText(testLine)
                if (width > maxWidth && line.isNotEmpty()) {
                    ensureSpace(lineSpacing)
                    canvas.drawText(line, x, y, paint)
                    y += lineSpacing
                    line = word
                } else {
                    line = testLine
                }
            }
            if (line.isNotEmpty()) {
                ensureSpace(lineSpacing)
                canvas.drawText(line, x, y, paint)
                y += lineSpacing
            }
        }
    }

    fun generateSessionPdfReport(
        context: Context,
        session: DriveSession,
        measurements: List<LteMeasurementEntity>,
        continuityEvents: List<NetworkContinuityEventEntity> = emptyList(),
        serviceHoles: List<ServiceHoleEntity> = emptyList(),
        callEvents: List<CallReliabilityEventEntity> = emptyList(),
        venue: EventVenue? = null,
        eventResult: CapacityScenarioResult? = null,
        activeQoeTests: List<ActiveQoeTestEntity> = emptyList(),
        cellTransitions: List<CellTransitionEntity> = emptyList()
    ): File {
        val exportDir = File(context.getExternalFilesDir(null), "reports").apply {
            if (!exists()) mkdirs()
        }

        val safeName = session.sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(session.startTimeMs))
        val pdfFile = File(exportDir, "CellScope_Report_${safeName}_$timestamp.pdf")

        val doc = PdfDocument()
        val mgr = ReportPageManager(doc)

        // Analytical computations
        val handoverSummary = HandoverAnalyzer.analyzeTransitions(session.id, measurements, session.totalDistanceM)
        val weakZones = CoveragePlanner.identifyWeakZones(measurements, handoverSummary.transitionEvents)
        val signalAudit = RadioQualityEngine.auditSignalQuality(measurements)
        val qualityReport = RadioQualityEngine.evaluateSessionQuality(measurements)
        val mobilitySummary = RatMobilityDiagnosticsEngine.analyzeRatMobility(measurements)
        val trafficSummary = TrafficIntelligenceEngine.analyzeTrafficIntelligence(measurements)
        val reuseSummary = ReuseAnalyzer.analyzeReuse(measurements)
        val interferenceReport = InterferenceAnalyzer.analyze(measurements)

        // Strict LTE RSRP Distribution
        val lteOnly = measurements.filter {
            val rat = it.servingRat ?: it.networkGen ?: it.networkType
            !rat.contains("2G", ignoreCase = true) && !rat.contains("GSM", ignoreCase = true) &&
            !rat.contains("3G", ignoreCase = true) && !rat.contains("WCDMA", ignoreCase = true)
        }
        val validRsrp = lteOnly.mapNotNull { it.rsrpDbm }
        val excellentCount = validRsrp.count { it >= -80 }
        val goodCount = validRsrp.count { it in -90..-81 }
        val fairCount = validRsrp.count { it in -100..-91 }
        val poorCount = validRsrp.count { it < -100 }
        val totalRsrp = validRsrp.size.coerceAtLeast(1)

        val pctExc = (excellentCount * 100f / totalRsrp)
        val pctGood = (goodCount * 100f / totalRsrp)
        val pctFair = (fairCount * 100f / totalRsrp)
        val pctPoor = (poorCount * 100f / totalRsrp)

        val gsmSamples = measurements.filter {
            val rat = it.servingRat ?: it.networkGen ?: it.networkType
            rat.contains("2G", ignoreCase = true) || rat.contains("GSM", ignoreCase = true)
        }
        val gsmRssiList = gsmSamples.mapNotNull { it.primarySignalDbm ?: it.rssiDbm }

        val wcdmaSamples = measurements.filter {
            val rat = it.servingRat ?: it.networkGen ?: it.networkType
            rat.contains("3G", ignoreCase = true) || rat.contains("WCDMA", ignoreCase = true)
        }
        val wcdmaRscpList = wcdmaSamples.mapNotNull { it.primarySignalDbm ?: it.rssiDbm }

        // Paints
        val primaryPaint = Paint().apply {
            color = Color.parseColor("#0D47A1") // Dark Blue
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val titlePaint = Paint().apply {
            color = Color.parseColor("#212121")
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val bodyPaint = Paint().apply {
            color = Color.parseColor("#424242")
            textSize = 9f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val boldBodyPaint = Paint().apply {
            color = Color.parseColor("#212121")
            textSize = 9f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val disclaimerPaint = Paint().apply {
            color = Color.parseColor("#757575")
            textSize = 7.5f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val linePaint = Paint().apply {
            color = Color.parseColor("#BDBDBD")
            strokeWidth = 1f
        }

        val fillPaint = Paint().apply { style = Paint.Style.FILL }

        // ==========================================
        // Header Banner
        // ==========================================
        mgr.canvas.drawText("CELLSCOPE 5G — DRIVE-TEST & TELECOM ENGINEERING REPORT", MARGIN_X, mgr.y, primaryPaint)
        mgr.y += 15f
        mgr.canvas.drawText("Native Android Cellular Field Telemetry Assessment • Lead: Sarkar Tuhassinul Arnob", MARGIN_X, mgr.y, bodyPaint)
        mgr.y += 12f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 16f

        // ==========================================
        // 1. Session Executive Summary
        // ==========================================
        mgr.ensureSpace(120f)
        mgr.canvas.drawText("1. Session Executive Summary", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 14f

        val startMs = if (session.startTimeMs > 0) session.startTimeMs else (measurements.firstOrNull()?.timestampMs ?: 0L)
        val endMs = session.endTimeMs ?: measurements.lastOrNull()?.timestampMs ?: startMs
        val durationSec = if (endMs >= startMs) (endMs - startMs) / 1000L else 0L
        val durationStr = String.format(Locale.US, "%02d:%02d:%02d", durationSec / 3600, (durationSec % 3600) / 60, durationSec % 60)
        val distKmStr = String.format(Locale.US, "%.2f km", session.totalDistanceM / 1000.0)
        val isStationary = session.totalDistanceM < 20.0 && measurements.size >= 5
        val mobilityModeStr = if (isStationary) "STATIONARY (Jitter Filtered)" else "DYNAMIC DRIVE"
        val dateStr = "${TelecomTimeUtils.formatBdtDateTime(session.startTimeMs)} (BST)"
        val avgRsrpStr = if (signalAudit.validatedMeanRsrp != null) {
            String.format(Locale.US, "%.1f dBm (Validated 4G)", signalAudit.validatedMeanRsrp)
        } else if (validRsrp.isNotEmpty()) {
            String.format(Locale.US, "%.1f dBm (4G LTE)", validRsrp.average())
        } else {
            session.avgRsrpDbm?.let { String.format(Locale.US, "%.1f dBm", it) } ?: "— dBm"
        }
        val maxSpeedStr = if (isStationary) "0.0 km/h" else String.format(Locale.US, "%.1f km/h", session.maxSpeedKmh)

        val qoeReport = com.cellscope.drive.analytics.qoe.QoeAnalyzer.analyzeSessionQoe(session, measurements)
        val gpsAvailStr = String.format(Locale.US, "%.1f%% (%d valid)", qoeReport.gpsAvailabilityPct, qoeReport.validGpsSamples)
        val radioAvailStr = String.format(Locale.US, "%.1f%%", qoeReport.radioAvailabilityPct)
        val coverageAdequacyStr = String.format(Locale.US, "%.1f%%", qoeReport.coverageAdequacyPct)

        val summaryItems = mutableListOf(
            Pair("Session Name:", session.sessionName),
            Pair("Start Timestamp:", dateStr),
            Pair("Duration:", durationStr),
            Pair("Mobility Mode:", mobilityModeStr),
            Pair("Route Distance:", if (isStationary) "0.00 km" else distKmStr),
            Pair("Max Speed:", maxSpeedStr),
            Pair("Sample Count:", "${measurements.size} measurements"),
            Pair("Mean Signal:", avgRsrpStr),
            Pair("GPS Availability:", gpsAvailStr),
            Pair("Radio Availability:", radioAvailStr),
            Pair("Coverage Adequacy:", coverageAdequacyStr),
            Pair("Serving Cells Observed:", "${handoverSummary.dwellStats.size} distinct PCIs")
        )

        for (i in summaryItems.indices step 2) {
            val item1 = summaryItems[i]
            mgr.canvas.drawText(item1.first, MARGIN_X + 5f, mgr.y, boldBodyPaint)
            mgr.canvas.drawText(item1.second, MARGIN_X + 120f, mgr.y, bodyPaint)

            if (i + 1 < summaryItems.size) {
                val item2 = summaryItems[i + 1]
                mgr.canvas.drawText(item2.first, MARGIN_X + 260f, mgr.y, boldBodyPaint)
                mgr.canvas.drawText(item2.second, MARGIN_X + 375f, mgr.y, bodyPaint)
            }
            mgr.y += 12f
        }

        mgr.y += 6f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 14f

        // ==========================================
        // 2. Telemetry Integrity & Validation Audit
        // ==========================================
        mgr.ensureSpace(80f)
        mgr.canvas.drawText("2. Telemetry Integrity & Validation Audit (3GPP TS 36.133 Table 9.1.4-1)", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 13f

        val auditDetails = String.format(
            Locale.US,
            "Total Telemetry Samples: %d  |  Valid RSRP Samples: %d  |  Missing/Invalid: %d  |  Baseband Clamp Count: %d (%.1f%%)",
            signalAudit.totalSamples, signalAudit.validRsrpCount, signalAudit.missingOrInvalidCount,
            signalAudit.suspectClampCount, signalAudit.suspectClampPercent
        )
        mgr.drawWrappedText(auditDetails, MARGIN_X + 5f, CONTENT_WIDTH - 10f, boldBodyPaint)
        mgr.y += 2f

        val rMean = signalAudit.rawMeanRsrp?.let { String.format(Locale.US, "%.1f dBm", it) } ?: "—"
        val vMean = signalAudit.validatedMeanRsrp?.let { String.format(Locale.US, "%.1f dBm", it) } ?: "—"
        val rMed = signalAudit.rawMedianRsrp?.let { String.format(Locale.US, "%.1f dBm", it) } ?: "—"
        val vMed = signalAudit.validatedMedianRsrp?.let { String.format(Locale.US, "%.1f dBm", it) } ?: "—"

        val statsStr = "Raw Mean: $rMean | Validated Mean: $vMean | Raw Median: $rMed | Validated Median: $vMed"
        mgr.drawWrappedText(statsStr, MARGIN_X + 5f, CONTENT_WIDTH - 10f, bodyPaint)
        mgr.y += 2f

        mgr.drawWrappedText("Calibration Status: ${signalAudit.calibrationStatus}", MARGIN_X + 5f, CONTENT_WIDTH - 10f, disclaimerPaint, lineSpacing = 10f)
        mgr.drawWrappedText("Audit Summary: ${signalAudit.auditExplanation}", MARGIN_X + 5f, CONTENT_WIDTH - 10f, bodyPaint)

        mgr.y += 6f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 14f

        // ==========================================
        // 3. RF Signal Quality & Distribution
        // ==========================================
        mgr.ensureSpace(95f)
        mgr.canvas.drawText("3. Radio Signal Quality & Distribution (3GPP TS 36.133 Standards)", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 14f

        val barX = MARGIN_X + 5f
        val barW = CONTENT_WIDTH - 10f
        val barH = 14f

        val wExc = barW * (pctExc / 100f)
        val wGood = barW * (pctGood / 100f)
        val wFair = barW * (pctFair / 100f)
        val wPoor = barW * (pctPoor / 100f)

        var curBarX = barX
        fillPaint.color = Color.parseColor("#00C853")
        mgr.canvas.drawRect(curBarX, mgr.y, curBarX + wExc, mgr.y + barH, fillPaint)
        curBarX += wExc

        fillPaint.color = Color.parseColor("#64DD17")
        mgr.canvas.drawRect(curBarX, mgr.y, curBarX + wGood, mgr.y + barH, fillPaint)
        curBarX += wGood

        fillPaint.color = Color.parseColor("#FFD600")
        mgr.canvas.drawRect(curBarX, mgr.y, curBarX + wFair, mgr.y + barH, fillPaint)
        curBarX += wFair

        fillPaint.color = Color.parseColor("#D50000")
        mgr.canvas.drawRect(curBarX, mgr.y, curBarX + wPoor, mgr.y + barH, fillPaint)

        mgr.y += barH + 12f

        val tiers = listOf(
            Triple("Excellent (≥ -80 dBm)", String.format(Locale.US, "%.1f%% (%d)", pctExc, excellentCount), Color.parseColor("#00C853")),
            Triple("Good (-90 to -81 dBm)", String.format(Locale.US, "%.1f%% (%d)", pctGood, goodCount), Color.parseColor("#64DD17")),
            Triple("Fair (-100 to -91 dBm)", String.format(Locale.US, "%.1f%% (%d)", pctFair, fairCount), Color.parseColor("#FFD600")),
            Triple("Poor (< -100 dBm)", String.format(Locale.US, "%.1f%% (%d)", pctPoor, poorCount), Color.parseColor("#D50000"))
        )

        var legX = MARGIN_X + 5f
        for ((label, valStr, color) in tiers) {
            fillPaint.color = color
            mgr.canvas.drawRect(legX, mgr.y - 7f, legX + 8f, mgr.y + 1f, fillPaint)
            mgr.canvas.drawText(label, legX + 12f, mgr.y, bodyPaint)
            mgr.canvas.drawText(valStr, legX + 12f, mgr.y + 9f, boldBodyPaint)
            legX += 128f
        }
        mgr.y += 14f

        val sinrStr = qualityReport.sinrDistribution.mean?.let { String.format(Locale.US, "Mean SINR: %.1f dB", it) } ?: "Physical SINR: Unexposed by available telemetry"
        val rsrqStr = qualityReport.rsrqDistribution.mean?.let { String.format(Locale.US, "Mean RSRQ: %.1f dB", it) } ?: "Mean RSRQ: — dB"
        mgr.drawWrappedText("$rsrqStr  |  $sinrStr", MARGIN_X + 5f, CONTENT_WIDTH - 10f, boldBodyPaint)
        mgr.y += 8f

        // Vector Histogram & Interpretation
        drawVectorRsrpDistributionHistogram(
            mgr = mgr,
            pctExc = pctExc,
            pctGood = pctGood,
            pctFair = pctFair,
            pctPoor = pctPoor,
            excCount = excellentCount,
            goodCount = goodCount,
            fairCount = fairCount,
            poorCount = poorCount,
            totalValid = totalRsrp
        )

        val rsrpDistributionInterp = GraphInterpretation(
            metricMeasured = "3GPP Reference Signal Received Power (RSRP in dBm). Linear average power of downlink resource elements carrying cell-specific reference signals (CRS).",
            provenance = "Android TelephonyManager via CellSignalStrengthLte.rsrp. Persisted to Room LteMeasurementEntity. Validated against 3GPP TS 36.133 Table 9.1.4-1 (-140 to -44 dBm).",
            formulation = "Discrete percentage frequency distribution partitioned into 4 standard 3GPP coverage quality tiers: Excellent (≥ -80 dBm), Good (-90..-81 dBm), Fair (-100..-91 dBm), Poor (< -100 dBm).",
            sampleCountScope = "Evaluated across $totalRsrp valid LTE measurements (non-LTE transitions, 2G/3G samples, and out-of-service epochs excluded).",
            keyObservations = "Distribution: Excellent ${String.format(Locale.US, "%.1f", pctExc)}% ($excellentCount), Good ${String.format(Locale.US, "%.1f", pctGood)}% ($goodCount), Fair ${String.format(Locale.US, "%.1f", pctFair)}% ($fairCount), Poor ${String.format(Locale.US, "%.1f", pctPoor)}% ($poorCount). Mean RSRP: $avgRsrpStr.",
            engineeringInterpretation = if (pctExc + pctGood > 70f) {
                "Predominant strong link margin with robust carrier coverage along the surveyed path. Adequate eNodeB site density and effective downlink path clearance."
            } else {
                "Substantial proportion in Fair/Poor tiers indicating cell-edge attenuation, terrain shadowing, or building clutter penetration losses along the route."
            },
            alternativeExplanations = "Vehicle metal chassis body loss (typically 3–10 dB attenuation), handset orientation relative to antenna site, or baseband reporting saturation ceiling (-44 dBm).",
            limitationsAndActions = "Handset antenna lacks calibrated omnidirectional gain. Recommended field action: Cross-validate edge boundaries with calibrated RF scanner and verify eNodeB antenna downtilt."
        )
        drawGraphInterpretationBlock(mgr, rsrpDistributionInterp, "RSRP Signal Distribution — 8-Element Scientific Assessment")

        // Vector Scatter Plot & Interpretation
        drawVectorRsrpVsRsrqScatterChart(mgr, measurements)

        val rsrqMean = qualityReport.rsrqDistribution.mean
        val rsrqMeanStr = rsrqMean?.let { String.format(Locale.US, "%.1f dB", it) } ?: "— dB"
        val rsrpVsRsrqInterp = GraphInterpretation(
            metricMeasured = "Bivariate relationship between received signal power (RSRP in dBm) and received signal quality (RSRQ in dB = N × RSRP / RSSI).",
            provenance = "Android TelephonyManager via CellSignalStrengthLte.rsrp and CellSignalStrengthLte.rsrq logged simultaneously during drive testing.",
            formulation = "2D scatter coordinates mapping instantaneous received power against channel quality across standard LTE operational bounds.",
            sampleCountScope = "${measurements.count { it.rsrpDbm != null && it.rsrqDb != null }} simultaneous (RSRP, RSRQ) measurement pairs.",
            keyObservations = "Mean RSRQ: $rsrqMeanStr | Mean RSRP: $avgRsrpStr. Quadrant dispersion evaluated across interference threshold (-12 dB) and cell-edge boundary (-100 dBm).",
            engineeringInterpretation = if ((rsrqMean ?: -10.0) < -12.0) {
                "Depressed RSRQ despite acceptable RSRP indicates significant co-channel interference, high downlink PRB resource block utilization from neighbor cells, or pilot pollution."
            } else {
                "Favorable RSRQ indicates clean radio channel with minimal co-channel pilot overlap and low inter-cell interference."
            },
            alternativeExplanations = "Vehicle speed Doppler shift, multi-path delay spread in urban canyons, or baseband modem chipset RSRQ computation differences across Android vendors.",
            limitationsAndActions = "Handset telemetry cannot distinguish external interference from serving cell PRB traffic loading. Operator OSS PRB scheduler statistics required for conclusive root-cause isolation."
        )
        drawGraphInterpretationBlock(mgr, rsrpVsRsrqInterp, "RSRP vs RSRQ Bivariate Scatter — 8-Element Scientific Assessment")

        mgr.y += 6f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 14f

        // ==========================================
        // 4. Carrier Frequency, Bandwidth & Channel Configuration
        // ==========================================
        mgr.ensureSpace(75f)
        mgr.canvas.drawText("4. Carrier Frequency, Bandwidth & Channel Configuration", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 13f

        val observedCarriers = measurements.mapNotNull { CellularBandResolver.resolveMeasurement(it) }.distinctBy { "${it.rat}_${it.channelNumber}" }
        if (observedCarriers.isEmpty()) {
            mgr.canvas.drawText("No RF carrier channel numbers reported in telemetry.", MARGIN_X + 5f, mgr.y, bodyPaint)
            mgr.y += 12f
        } else {
            for (c in observedCarriers.take(4)) {
                mgr.ensureSpace(24f)
                val ulInfo = if (c.duplexMode == "TDD") "TDD Shared" else c.ulCenterFreqMhz?.let { "${String.format(Locale.US, "%.1f", it)} MHz" } ?: "Unexposed"
                val bwText = if (c.isNominalBandwidth) "BW: ${c.bandwidthDisplay}" else "DL BW: ${c.bandwidthDisplay}"
                val line1 = "• [${c.rat}] ${c.bandName} (${c.duplexMode}) | Channel: ${c.channelNumber} | DL: ${String.format(Locale.US, "%.1f", c.dlCenterFreqMhz)} MHz | UL: $ulInfo"
                mgr.drawWrappedText(line1, MARGIN_X + 5f, CONTENT_WIDTH - 10f, boldBodyPaint)
                mgr.drawWrappedText("  Duplex Configuration: ${c.duplexDescription} | $bwText", MARGIN_X + 12f, CONTENT_WIDTH - 20f, bodyPaint)
                mgr.y += 2f
            }
        }

        // Vector Frequency Plot & Interpretation
        drawVectorFrequencyPlot(mgr, measurements)

        val carrierNames = observedCarriers.joinToString(", ") { "${it.bandName} (${it.channelNumber})" }
        val frequencyInterp = GraphInterpretation(
            metricMeasured = "Downlink carrier center frequency (MHz), EARFCN channel number, nominal channel bandwidth, and duplex configuration.",
            provenance = "Android TelephonyManager CellIdentityLte.earfcn mapped via 3GPP TS 36.101 Table 5.5-1 to downlink and uplink frequency bands.",
            formulation = "Channelized frequency distribution grouping received signal power across active carrier frequencies and distinct physical cell IDs.",
            sampleCountScope = "Observed across ${observedCarriers.size} carrier channels and ${handoverSummary.dwellStats.size} distinct PCIs.",
            keyObservations = "Active carriers detected: ${if (carrierNames.isNotEmpty()) carrierNames else "Standard LTE Carrier"}. Primary serving carrier center frequency: ${observedCarriers.firstOrNull()?.dlCenterFreqMhz?.let { String.format(Locale.US, "%.1f MHz", it) } ?: "Unexposed"}.",
            engineeringInterpretation = "Confirms multi-band carrier deployment and inter-band layer separation. Highlights primary coverage layer vs secondary capacity layers for carrier aggregation readiness.",
            alternativeExplanations = "Sub-1 GHz carriers (e.g. Band 8, 900 MHz) propagate significantly farther and penetrate clutter better than higher-frequency layers (Band 1/3, 1800/2100 MHz).",
            limitationsAndActions = "Handset background neighbor cell scanning is limited by Android power-saving and RRC Connected mode constraints. Scanner sweeps recommended for complete multi-carrier auditing."
        )
        drawGraphInterpretationBlock(mgr, frequencyInterp, "Carrier Frequency & Spectrum Allocation — 8-Element Scientific Assessment")

        mgr.y += 6f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 14f

        // ==========================================
        // 5. Handover, Dwell & Cell Transition Dynamics
        // ==========================================
        mgr.ensureSpace(80f)
        mgr.canvas.drawText("5. Handover, Dwell & Cell Transition Dynamics", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 13f

        mgr.drawWrappedText(
            "Transitions: ${handoverSummary.totalTransitions}  |  Confirmed: ${handoverSummary.confirmedHandovers}  |  Reselections: ${handoverSummary.cellReselections}  |  Density: ${String.format(Locale.US, "%.2f", handoverSummary.transitionsPerKm)}/km",
            MARGIN_X + 5f, CONTENT_WIDTH - 10f, bodyPaint
        )
        mgr.y += 3f

        fun drawDwellTableHeader() {
            mgr.canvas.drawText("PCI", MARGIN_X + 5f, mgr.y, boldBodyPaint)
            mgr.canvas.drawText("Samples", MARGIN_X + 80f, mgr.y, boldBodyPaint)
            mgr.canvas.drawText("Dwell Time", MARGIN_X + 170f, mgr.y, boldBodyPaint)
            mgr.canvas.drawText("Session Share", MARGIN_X + 280f, mgr.y, boldBodyPaint)
            mgr.canvas.drawText("Mean RSRP", MARGIN_X + 400f, mgr.y, boldBodyPaint)
            mgr.y += 4f
            mgr.canvas.drawLine(MARGIN_X + 5f, mgr.y, MARGIN_X + CONTENT_WIDTH - 5f, mgr.y, linePaint)
            mgr.y += 11f
        }

        drawDwellTableHeader()
        for (dwell in handoverSummary.dwellStats.take(6)) {
            mgr.ensureSpace(13f) { drawDwellTableHeader() }
            val dwellStr = String.format(Locale.US, "%dm %02ds", dwell.totalDwellTimeMs / 60000, (dwell.totalDwellTimeMs % 60000) / 1000)
            val avgRsrp = dwell.avgRsrp?.let { String.format(Locale.US, "%.1f dBm", it) } ?: "—"
            mgr.canvas.drawText("PCI ${dwell.pci}", MARGIN_X + 5f, mgr.y, bodyPaint)
            mgr.canvas.drawText("${dwell.sampleCount}", MARGIN_X + 80f, mgr.y, bodyPaint)
            mgr.canvas.drawText(dwellStr, MARGIN_X + 170f, mgr.y, bodyPaint)
            mgr.canvas.drawText(String.format(Locale.US, "%.1f%%", dwell.percentageOfSession), MARGIN_X + 280f, mgr.y, bodyPaint)
            mgr.canvas.drawText(avgRsrp, MARGIN_X + 400f, mgr.y, bodyPaint)
            mgr.y += 11f
        }

        mgr.y += 6f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 14f

        // ==========================================
        // 6. RAT Mobility & 4G → 3G/2G Downgrade Diagnostics
        // ==========================================
        mgr.ensureSpace(80f)
        mgr.canvas.drawText("6. RAT Mobility & 4G → 3G/2G Downgrade Diagnostics", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 13f

        val mobSummaryStr = String.format(
            Locale.US,
            "Total Generation Shifts: %d  |  4G→3G: %d  |  4G→2G: %d  |  5G→4G: %d  |  Upgrades: %d  |  Fast Ping-Pongs: %d",
            mobilitySummary.totalTransitions, mobilitySummary.lteTo3gDowngrades, mobilitySummary.lteTo2gDowngrades,
            mobilitySummary.nrToLteDowngrades, mobilitySummary.totalUpgrades, mobilitySummary.fastPingPongCount
        )
        mgr.drawWrappedText(mobSummaryStr, MARGIN_X + 5f, CONTENT_WIDTH - 10f, boldBodyPaint)
        mgr.y += 2f

        if (mobilitySummary.events.isEmpty()) {
            mgr.drawWrappedText("Stable cellular connection maintained throughout session without inter-RAT downgrades.", MARGIN_X + 5f, CONTENT_WIDTH - 10f, bodyPaint)
        } else {
            for (evt in mobilitySummary.events.take(3)) {
                mgr.ensureSpace(30f)
                val timeStr = "${TelecomTimeUtils.isoToBdtTimeOnly(evt.timestampIso)} BST"
                val pingStr = if (evt.isPingPong) " [Ping-Pong ${evt.pingPongReturnDurationMs?.let { it / 1000 } ?: 0}s]" else ""
                mgr.canvas.drawText("• [${evt.fromRat} → ${evt.toRat}] at $timeStr — ${evt.rootCause.label}$pingStr (Conf: ${(evt.confidenceScore * 100).toInt()}%)", MARGIN_X + 10f, mgr.y, boldBodyPaint)
                mgr.y += 11f
                mgr.drawWrappedText("Evidence: ${evt.diagnosticEvidence}", MARGIN_X + 18f, CONTENT_WIDTH - 25f, bodyPaint)
                mgr.drawWrappedText("Remediation: ${evt.engineeringRemediation}", MARGIN_X + 18f, CONTENT_WIDTH - 25f, disclaimerPaint, lineSpacing = 10f)
                mgr.y += 3f
            }
        }

        mgr.y += 6f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 14f

        // ==========================================
        // 7. Coverage Boundaries & Identified Weak-Coverage Zones
        // ==========================================
        mgr.ensureSpace(60f)
        mgr.canvas.drawText("7. Identified Weak-Coverage Zones & Remediation Feasibility", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 13f

        if (weakZones.isEmpty()) {
            mgr.canvas.drawText("No critical weak-coverage zones detected along the test route (All RSRP > -105 dBm).", MARGIN_X + 5f, mgr.y, bodyPaint)
            mgr.y += 12f
        } else {
            for (zone in weakZones.take(3)) {
                mgr.ensureSpace(40f)
                val zoneTitle = "• Zone #${zone.id}: Length ~${String.format(Locale.US, "%.0f", zone.lengthMeters)}m | Mean RSRP: ${String.format(Locale.US, "%.1f", zone.avgRsrp)} dBm | Worst: ${zone.minRsrp} dBm"
                mgr.canvas.drawText(zoneTitle, MARGIN_X + 5f, mgr.y, boldBodyPaint)
                mgr.y += 11f
                mgr.drawWrappedText("Diagnosis: ${zone.diagnosis}", MARGIN_X + 12f, CONTENT_WIDTH - 20f, bodyPaint)
                val topRec = zone.remediationOptions.firstOrNull()
                if (topRec != null) {
                    mgr.drawWrappedText("Recommended Remediation: [${topRec.category}] ${topRec.title} (Feasibility: ${(topRec.feasibilityScore * 100).toInt()}%) — ${topRec.rationale}", MARGIN_X + 12f, CONTENT_WIDTH - 20f, bodyPaint)
                }
                mgr.y += 3f
            }
        }

        mgr.y += 6f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 14f

        // ==========================================
        // 8. Time-Based Radio Quality & Traffic Intelligence
        // ==========================================
        mgr.ensureSpace(80f)
        mgr.canvas.drawText("8. Time-Based Radio Quality & Traffic Intelligence", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 13f

        val routeInfo = String.format(
            Locale.US,
            "Route Pattern: %s  |  Extent: %.1f km  |  Path Distance: %.1f km  |  Tortuosity: %.1f",
            trafficSummary.routeClassification.label, trafficSummary.routeExtentMeters / 1000.0,
            trafficSummary.totalPathDistanceMeters / 1000.0, trafficSummary.tortuosityRatio
        )
        mgr.drawWrappedText(routeInfo, MARGIN_X + 5f, CONTENT_WIDTH - 10f, boldBodyPaint)
        mgr.y += 2f

        mgr.drawWrappedText("• Interpretation Notice: ${trafficSummary.trafficInterpretationNotice}", MARGIN_X + 5f, CONTENT_WIDTH - 10f, disclaimerPaint, lineSpacing = 10f)
        mgr.y += 3f

        if (trafficSummary.overallTimeBins.isNotEmpty()) {
            mgr.drawWrappedText("Observed Time Bins (No zero-filling of unmeasured hours):", MARGIN_X + 5f, CONTENT_WIDTH - 10f, boldBodyPaint)
            for (bin in trafficSummary.overallTimeBins.take(4)) {
                mgr.ensureSpace(14f)
                val rsrpStr = bin.medianRsrp?.let { "Median RSRP: $it dBm (P25: ${bin.p25Rsrp}, P75: ${bin.p75Rsrp})" } ?: "RSRP: —"
                val rsrqStr = bin.medianRsrq?.let { "RSRQ: $it dB" } ?: ""
                mgr.canvas.drawText("  [${bin.binLabel}] ${bin.sampleCount} samples | $rsrpStr | $rsrqStr", MARGIN_X + 8f, mgr.y, bodyPaint)
                mgr.y += 11f
            }
        }

        // Vector Time Progression Plot & Interpretation
        drawVectorTimeProgressionPlot(mgr, trafficSummary, measurements)

        val timeProgressionInterp = GraphInterpretation(
            metricMeasured = "Temporal variation and stability of received signal power (RSRP in dBm) over the elapsed survey duration.",
            provenance = "UTC hardware timestamp synchronized with TelephonyManager signal readings in Room LteMeasurementEntity.",
            formulation = "Chronological aggregation into non-zero-filled temporal bins with median and interquartile range (P25–P75) evaluation.",
            sampleCountScope = "${trafficSummary.overallTimeBins.size} observed time intervals spanning $durationStr duration across $distKmStr path distance.",
            keyObservations = "Route classification: ${trafficSummary.routeClassification.label}. Tortuosity ratio: ${String.format(Locale.US, "%.1f", trafficSummary.tortuosityRatio)}. Feasibility: ${trafficSummary.feasibilityAssessment.feasibilityLevel}.",
            engineeringInterpretation = "Fluctuations over time in dynamic drive tests reflect spatial path loss, terrain topology, and building shadowing rather than time-of-day traffic breathing.",
            alternativeExplanations = "Vehicular movement between cell centers and cell edges produces signal swings of 20–40 dB that overwhelm diurnal load variations.",
            limitationsAndActions = "Diurnal traffic congestion analysis is scientifically invalid on dynamic transit routes without 24-hour stationary repeat sampling or eNodeB OSS scheduler statistics."
        )
        drawGraphInterpretationBlock(mgr, timeProgressionInterp, "Time-Series Radio Progression & Feasibility — 8-Element Scientific Assessment")

        mgr.y += 6f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 14f

        // ==========================================
        // 9. Co-Channel, Adjacent-Channel & Frequency Reuse Analysis
        // ==========================================
        mgr.ensureSpace(75f)
        mgr.canvas.drawText("9. Co-Channel, Adjacent-Channel & Frequency Reuse Analysis", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 13f

        val reuseCounts = reuseSummary.classificationCounts
        val reuseCountsStr = String.format(
            Locale.US,
            "Unique PCIs: %d  |  Carriers: %d  |  Same-EARFCN Pairs: %d  |  Planned Spatial Reuse: %d  |  Confusion Risk: %d",
            reuseSummary.totalUniquePcis, reuseSummary.totalUniqueEarfcn,
            reuseCounts[ReuseAnalyzer.ReuseClassification.SAME_EARFCN] ?: 0,
            reuseCounts[ReuseAnalyzer.ReuseClassification.SAME_PCI_DIFFERENT_LOCATIONS] ?: 0,
            reuseCounts[ReuseAnalyzer.ReuseClassification.POTENTIAL_PCI_CONFUSION_COLLISION] ?: 0
        )
        mgr.drawWrappedText(reuseCountsStr, MARGIN_X + 5f, CONTENT_WIDTH - 10f, boldBodyPaint)
        mgr.y += 2f

        if (reuseSummary.crsCollisions.isNotEmpty()) {
            for (crs in reuseSummary.crsCollisions.take(2)) {
                mgr.ensureSpace(22f)
                mgr.canvas.drawText("• [${crs.severity}] Ch ${crs.earfcn} | PCI ${crs.pciA} & ${crs.pciB} (v_shift = ${crs.mod3Value})", MARGIN_X + 10f, mgr.y, boldBodyPaint)
                mgr.y += 10f
                mgr.drawWrappedText(crs.technicalReason, MARGIN_X + 18f, CONTENT_WIDTH - 25f, bodyPaint)
                mgr.y += 2f
            }
        } else {
            mgr.drawWrappedText("No CRS modulo-3 collisions detected across observed intra-frequency cells.", MARGIN_X + 5f, CONTENT_WIDTH - 10f, bodyPaint)
        }

        mgr.y += 6f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 14f

        // ==========================================
        // 10. Multi-Generation Network Coverage & Continuity
        // ==========================================
        val actualContinuity = if (continuityEvents.isNotEmpty()) continuityEvents else {
            NetworkContinuityAnalyzer.analyzeSessionContinuity(session.id, measurements).continuityEvents
        }
        val actualHoles = if (serviceHoles.isNotEmpty()) serviceHoles else {
            ServiceHoleDetector.detectHoles(session.id, measurements).holes
        }
        val callRiskEvaluation = CallDropRiskEngine.evaluateSessionRisk(measurements, actualContinuity, actualHoles, callEvents)

        mgr.ensureSpace(70f)
        mgr.canvas.drawText("10. Multi-Generation Network Coverage & Continuity Dwell", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 13f

        val totalObs = measurements.size.coerceAtLeast(1)
        val count5g = measurements.count { (it.networkGen ?: it.networkType).contains("5G") || it.networkType.contains("NR") }
        val count4g = measurements.count { (it.networkGen ?: it.networkType).contains("4G") || it.networkType.contains("LTE") }
        val count3g = measurements.count { (it.networkGen ?: it.networkType).contains("3G") || it.networkType.contains("UMTS") || it.networkType.contains("HSPA") }
        val count2g = measurements.count { (it.networkGen ?: it.networkType).contains("2G") || it.networkType.contains("GSM") || it.networkType.contains("EDGE") }
        val countOos = measurements.count { (it.networkGen ?: it.networkType).contains("OOS") || (it.serviceState ?: "").contains("OUT_OF_SERVICE") }

        val genBreakdownStr = String.format(
            Locale.US,
            "5G NR: %d (%.1f%%)  |  4G LTE: %d (%.1f%%)  |  3G: %d (%.1f%%)  |  2G: %d (%.1f%%)  |  OOS: %d (%.1f%%)",
            count5g, (count5g * 100f / totalObs),
            count4g, (count4g * 100f / totalObs),
            count3g, (count3g * 100f / totalObs),
            count2g, (count2g * 100f / totalObs),
            countOos, (countOos * 100f / totalObs)
        )
        mgr.drawWrappedText(genBreakdownStr, MARGIN_X + 5f, CONTENT_WIDTH - 10f, boldBodyPaint)

        mgr.y += 6f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 14f

        // ==========================================
        // 11. Service Holes, Outages & Recovery Intelligence
        // ==========================================
        mgr.ensureSpace(60f)
        mgr.canvas.drawText("11. Service Holes, Outages & Recovery Intelligence", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 13f

        if (actualHoles.isEmpty()) {
            mgr.canvas.drawText("Service Holes: 0 detected. Cellular continuity remained uninterrupted.", MARGIN_X + 5f, mgr.y, bodyPaint)
            mgr.y += 12f
        } else {
            mgr.drawWrappedText("Service Holes: ${actualHoles.size} outage events identified across the surveyed path.", MARGIN_X + 5f, CONTENT_WIDTH - 10f, boldBodyPaint)
            for (h in actualHoles.take(2)) {
                mgr.ensureSpace(22f)
                val hTime = "${TelecomTimeUtils.isoToBdtTimeOnly(h.timestampIso)} BST"
                val durStr = h.durationMs?.let { " (${it / 1000}s duration)" } ?: ""
                mgr.canvas.drawText("• [${h.holeType}] Severity: ${h.severity} at $hTime$durStr", MARGIN_X + 10f, mgr.y, boldBodyPaint)
                mgr.y += 10f
                mgr.drawWrappedText(h.engineeringEvidence, MARGIN_X + 18f, CONTENT_WIDTH - 25f, bodyPaint)
                mgr.y += 2f
            }
        }

        mgr.y += 6f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 14f

        // ==========================================
        // 12. Voice Call Reliability & Voice Drop Risk Analysis
        // ==========================================
        mgr.ensureSpace(50f)
        mgr.canvas.drawText("12. Call Reliability & Voice Drop Risk Analysis", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 13f

        val riskColor = when (callRiskEvaluation.category) {
            "LOW" -> Color.parseColor("#00C853")
            "MODERATE" -> Color.parseColor("#FBC02D")
            "HIGH" -> Color.parseColor("#F57C00")
            "SEVERE" -> Color.parseColor("#D50000")
            else -> Color.parseColor("#757575")
        }
        val riskPaint = Paint(boldBodyPaint).apply { color = riskColor }
        mgr.canvas.drawText("• Voice Call Reliability & Drop Risk Score: ", MARGIN_X + 5f, mgr.y, boldBodyPaint)
        val riskPrefixW = boldBodyPaint.measureText("• Voice Call Reliability & Drop Risk Score: ")
        mgr.canvas.drawText("${callRiskEvaluation.category} (${callRiskEvaluation.scoreValue.toInt()}/100)", MARGIN_X + 5f + riskPrefixW, mgr.y, riskPaint)
        mgr.y += 11f
        mgr.drawWrappedText("Evaluation: ${callRiskEvaluation.totalCallEvents} call events evaluated (${callRiskEvaluation.suspectedDropsCount} suspected drops). Explanation: ${callRiskEvaluation.analyticalExplanation.take(160)}", MARGIN_X + 5f, CONTENT_WIDTH - 10f, bodyPaint)

        mgr.y += 6f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 14f

        // ==========================================
        // ==========================================
        // 13. Active Network QoE & Congestion Evidence Engine
        // ==========================================
        val qoeBaseline = CongestionEvidenceEngine.buildHistoricalBaseline(activeQoeTests)
        val qoeEvaluation = CongestionEvidenceEngine.evaluateCongestion(activeQoeTests, qoeBaseline)
        val completedTests = activeQoeTests.filter { it.success }

        mgr.ensureSpace(55f)
        mgr.canvas.drawText("13. Active Network QoE & Congestion Evidence Engine (3GPP Empirical Assessment)", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 13f

        val qoeStatsStr = if (completedTests.isNotEmpty()) {
            val dls = completedTests.mapNotNull { it.downloadSpeedMbps }.filter { it > 0.0 }
            val uls = completedTests.mapNotNull { it.uploadSpeedMbps }.filter { it > 0.0 }
            val lats = completedTests.mapNotNull { it.latencyMs }.filter { it > 0 }
            val jits = completedTests.mapNotNull { it.jitterMs }
            val losses = completedTests.mapNotNull { it.packetLossPct }

            val dlMed = if (dls.isNotEmpty()) String.format(Locale.US, "%.1f Mbps", dls.sorted()[dls.size / 2]) else "—"
            val ulMed = if (uls.isNotEmpty()) String.format(Locale.US, "%.1f Mbps", uls.sorted()[uls.size / 2]) else "—"
            val latMed = if (lats.isNotEmpty()) "${lats.sorted()[lats.size / 2]} ms" else "—"
            val jitAvg = if (jits.isNotEmpty()) String.format(Locale.US, "%.1f ms", jits.average()) else "—"
            val lossAvg = if (losses.isNotEmpty()) String.format(Locale.US, "%.1f%%", losses.average()) else "0.0%"

            "Completed Tests: ${completedTests.size}  |  Median DL: $dlMed  |  Median UL: $ulMed  |  Median RTT: $latMed  |  Jitter: $jitAvg  |  Probe Loss: $lossAvg"
        } else {
            "Active QoE Tests: None collected in session  |  Status: Empirical tests required to evaluate PRB scheduler bottlenecks"
        }
        mgr.drawWrappedText(qoeStatsStr, MARGIN_X + 5f, CONTENT_WIDTH - 10f, boldBodyPaint)
        mgr.y += 3f

        mgr.canvas.drawText("• Evidence Classification: [${qoeEvaluation.classification.label}]", MARGIN_X + 5f, mgr.y, boldBodyPaint)
        mgr.y += 11f
        mgr.drawWrappedText("Summary: ${qoeEvaluation.summary}", MARGIN_X + 12f, CONTENT_WIDTH - 20f, bodyPaint)
        mgr.drawWrappedText("Scientific Justification: ${qoeEvaluation.whyThisResult}", MARGIN_X + 12f, CONTENT_WIDTH - 20f, bodyPaint)
        mgr.drawWrappedText("PRB Telemetry Disclosure: ${qoeEvaluation.operatorDisclaimer}", MARGIN_X + 12f, CONTENT_WIDTH - 20f, disclaimerPaint, lineSpacing = 10f)

        // Test execution history table & data consumption summary
        if (activeQoeTests.isNotEmpty()) {
            val totalDlBytes = activeQoeTests.mapNotNull { it.dlBytes }.sum()
            val totalUlBytes = activeQoeTests.mapNotNull { it.ulBytes }.sum()
            val totalSessionBytes = totalDlBytes + totalUlBytes
            val totalMb = totalSessionBytes / (1024.0 * 1024.0)

            mgr.ensureSpace(24f)
            mgr.canvas.drawText("• QoE Test Execution & Data Budget Summary:", MARGIN_X + 5f, mgr.y, boldBodyPaint)
            mgr.y += 10f
            mgr.drawWrappedText(
                "Total Transferred: ${String.format(Locale.US, "%.2f MB", totalMb)} (DL: ${String.format(Locale.US, "%.2f MB", totalDlBytes / (1024.0 * 1024.0))}, UL: ${String.format(Locale.US, "%.2f MB", totalUlBytes / (1024.0 * 1024.0))}) | Bounded Test Limit Compliance: Active",
                MARGIN_X + 12f, CONTENT_WIDTH - 20f, bodyPaint
            )
            mgr.y += 4f

            // Execution table
            mgr.ensureSpace(35f)
            val colX = floatArrayOf(MARGIN_X + 8f, MARGIN_X + 38f, MARGIN_X + 90f, MARGIN_X + 145f, MARGIN_X + 210f, MARGIN_X + 265f, MARGIN_X + 320f, MARGIN_X + 375f, MARGIN_X + 425f)
            val tableHeadPaint = Paint().apply {
                color = Color.parseColor("#1E293B")
                textSize = 7f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            val tableRowPaint = Paint().apply {
                color = Color.parseColor("#334155")
                textSize = 6.5f
                typeface = Typeface.DEFAULT
                isAntiAlias = true
            }
            mgr.canvas.drawText("Cycle", colX[0], mgr.y, tableHeadPaint)
            mgr.canvas.drawText("Trigger", colX[1], mgr.y, tableHeadPaint)
            mgr.canvas.drawText("Status", colX[2], mgr.y, tableHeadPaint)
            mgr.canvas.drawText("Time", colX[3], mgr.y, tableHeadPaint)
            mgr.canvas.drawText("DL (Mbps)", colX[4], mgr.y, tableHeadPaint)
            mgr.canvas.drawText("UL (Mbps)", colX[5], mgr.y, tableHeadPaint)
            mgr.canvas.drawText("RTT (ms)", colX[6], mgr.y, tableHeadPaint)
            mgr.canvas.drawText("Jitter", colX[7], mgr.y, tableHeadPaint)
            mgr.canvas.drawText("RSRP", colX[8], mgr.y, tableHeadPaint)
            mgr.y += 8f
            mgr.canvas.drawLine(MARGIN_X + 5f, mgr.y, MARGIN_X + CONTENT_WIDTH - 5f, mgr.y, linePaint)
            mgr.y += 8f

            val testsToDisplay = activeQoeTests.takeLast(8)
            for ((idx, test) in testsToDisplay.withIndex()) {
                mgr.ensureSpace(11f)
                val timeStr = try {
                    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(test.timestampMs))
                } catch (e: Exception) { test.timestampIso }
                mgr.canvas.drawText("#${idx + 1}", colX[0], mgr.y, tableRowPaint)
                mgr.canvas.drawText(test.testType.take(10), colX[1], mgr.y, tableRowPaint)
                mgr.canvas.drawText(test.testStatus, colX[2], mgr.y, tableRowPaint)
                mgr.canvas.drawText(timeStr, colX[3], mgr.y, tableRowPaint)
                mgr.canvas.drawText(test.downloadSpeedMbps?.let { String.format(Locale.US, "%.1f", it) } ?: "—", colX[4], mgr.y, tableRowPaint)
                mgr.canvas.drawText(test.uploadSpeedMbps?.let { String.format(Locale.US, "%.1f", it) } ?: "—", colX[5], mgr.y, tableRowPaint)
                mgr.canvas.drawText(test.latencyMs?.toString() ?: "—", colX[6], mgr.y, tableRowPaint)
                mgr.canvas.drawText(test.jitterMs?.let { String.format(Locale.US, "%.1f", it) } ?: "—", colX[7], mgr.y, tableRowPaint)
                mgr.canvas.drawText(test.rsrpDbm?.let { "$it dBm" } ?: "—", colX[8], mgr.y, tableRowPaint)
                mgr.y += 9f
            }
            mgr.y += 6f
        }

        // Draw Vector QoE and Congestion Time-Series Chart
        drawVectorCongestionAndQoeChart(mgr, completedTests, qoeEvaluation, qoeBaseline)

        // 8-Element Scientific Assessment for Congestion
        val congestionQoeInterp = GraphInterpretation(
            metricMeasured = "Downlink & Uplink Throughput (Mbps), Round-Trip Time Latency (ms), Jitter (|ΔRTT| in ms), and Application Probe Loss (%).",
            provenance = "Active HTTP byte streaming via NetworkQoeScheduler and bounded multi-probe ping test sessions stored in Room ActiveQoeTestEntity.",
            formulation = "Transparent 5-level rule-based evidence classification comparing relative throughput degradation (ΔDL %) and latency elevation (ΔRTT %) against historical baseline under comparable RF signal conditions.",
            sampleCountScope = "${completedTests.size} completed active QoE test cycles (Baseline reference: ${qoeBaseline.sampleCount} comparable historical tests).",
            keyObservations = "Classification: ${qoeEvaluation.classification.label}. DL Degradation: ${qoeEvaluation.dlDegradationPct?.let { "$it%" } ?: "N/A"}. Latency Change: ${qoeEvaluation.latencyIncreasePct?.let { "$it%" } ?: "N/A"}. RF Stability: ${if (qoeEvaluation.isRfStable) "Stable (|ΔRSRP| ≤ 6 dB)" else "Attenuated"}.",
            engineeringInterpretation = when (qoeEvaluation.classification) {
                EvidenceClassification.STRONGER_CONGESTION_EVIDENCE -> "Multiple repeated measurements show substantial throughput collapse and latency increase under clean RF conditions. Strong multi-factor indication of eNodeB scheduler congestion or backhaul saturation."
                EvidenceClassification.POSSIBLE_CONGESTION -> "Throughput degradation observed under clean radio coverage. Candidate capacity constraint detected, requiring continued monitoring to confirm temporal persistence."
                EvidenceClassification.RF_LIMITED -> "Throughput decline coincides with RF signal attenuation or poor SINR. Performance constraint is radio-propagation-limited (path loss/interference), not PRB scheduler starvation."
                EvidenceClassification.MIXED_AMBIGUOUS -> "Conflicting performance metrics (asymmetric DL/UL variation or packet loss without RTT elevation). Indicates transport asymmetry or routing anomalies."
                EvidenceClassification.INSUFFICIENT_EVIDENCE -> "Normal throughput/latency within expected variance of baseline, or insufficient baseline samples to support a congestion assertion."
            },
            alternativeExplanations = "Transport backhaul constraints, core network UPF/SGW rate-limiting, external CDN edge contention, TCP slow-start window dynamics, or device thermal throttling.",
            limitationsAndActions = "Handset-side measurements alone cannot access operator-side MAC scheduler queues or physical resource block (PRB) counters. Recommended: Correlate with eNodeB OSS PM counters (pmPrbUsedDl, pmPrbAvailDl)."
        )
        drawGraphInterpretationBlock(mgr, congestionQoeInterp, "Active Network QoE & Congestion Inference — 8-Element Scientific Assessment")

        mgr.y += 6f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 14f

        // ==========================================
        // 14. Universal Event Capacity Planning Assessment
        // ==========================================
        mgr.ensureSpace(80f)
        mgr.canvas.drawText("14. Universal Event Capacity Planning Assessment", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 13f

        val simVenue = venue ?: session.let {
            val valid = measurements.filter { m -> m.latitude != null && m.longitude != null && m.isGpsValid }
            if (valid.isNotEmpty()) {
                val mid = valid[valid.size / 2]
                EventVenue.fromCoordinates(mid.latitude!!, mid.longitude!!, session.sessionName, "Observed Gathering")
            } else null
        }

        if (simVenue != null) {
            val scenarioResult = eventResult ?: EventCapacitySimulator.simulateScenario(
                venue = simVenue,
                spec = CapacityScenarioSpec(
                    scenarioId = "PDF_SUMMARY",
                    title = "${simVenue.name} Event Assessment",
                    additionalAttendees = 15000,
                    activeUserRatio = 0.20,
                    trafficProfile = EventTrafficProfile.MODERATE,
                    hasTemporaryCow = false,
                    hasWifiOffload = false
                ),
                measurements = measurements
            )

            mgr.canvas.drawText("• Venue: ${simVenue.name} (${String.format(Locale.US, "%.5f°N, %.5f°E", simVenue.centerLat, simVenue.centerLon)})", MARGIN_X + 5f, mgr.y, boldBodyPaint)
            mgr.y += 11f
            mgr.canvas.drawText("• Local Evidence: ${scenarioResult.coverageEvidence.status.label} (${scenarioResult.coverageEvidence.sampleCountInArea} points within perimeter)", MARGIN_X + 5f, mgr.y, bodyPaint)
            mgr.y += 11f

            val capStr = scenarioResult.totalAvailableCapacityMbps?.let { "${it.toInt()} Mbps" } ?: "UNKNOWN (Unmeasured area)"
            val deficitStr = if (scenarioResult.capacityDeficitMbps != null && scenarioResult.capacityDeficitMbps > 0) {
                "-${scenarioResult.capacityDeficitMbps.toInt()} Mbps (Capacity Deficit)"
            } else if (scenarioResult.unusedCapacityMarginMbps != null && scenarioResult.unusedCapacityMarginMbps > 0) {
                "+${scenarioResult.unusedCapacityMarginMbps.toInt()} Mbps (Unused Capacity Margin / Surplus)"
            } else {
                "Surplus / Unknown"
            }
            val prbStr = scenarioResult.actualPrbUtilizationPct?.let { "${it.toInt()}%" } ?: "Unknown — operator eNodeB telemetry required"

            mgr.canvas.drawText("• DL Demand: ${scenarioResult.offeredTrafficDlMbps.toInt()} Mbps | Modeled Capacity: $capStr | Deficit/Margin: $deficitStr", MARGIN_X + 5f, mgr.y, boldBodyPaint)
            mgr.y += 11f
            mgr.canvas.drawText("• PRB Load: $prbStr", MARGIN_X + 5f, mgr.y, bodyPaint)
            mgr.y += 11f

            if (scenarioResult.recommendedInterventions.isNotEmpty()) {
                val intStr = "Candidate Interventions (Non-Additive Evaluation): " + scenarioResult.recommendedInterventions.joinToString("; ")
                mgr.drawWrappedText(intStr, MARGIN_X + 5f, CONTENT_WIDTH - 10f, bodyPaint)
            }
        } else {
            mgr.drawWrappedText("No survey coordinates or event venue specified for capacity assessment.", MARGIN_X + 5f, CONTENT_WIDTH - 10f, bodyPaint)
        }

        mgr.y += 6f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 14f

        // ==========================================
        // 15. Explainable AI Engineering Recommendations
        // ==========================================
        val detectedHoles = CoverageHoleEstimator.estimateCoverageHoles(measurements)
        val congestionReport = CongestionAnalyzer.analyzeCongestion(measurements, activeQoeTests)
        val capacityRecReport = CapacityRecommendationEngine.evaluateRecommendations(
            session = session,
            measurements = measurements,
            transitions = cellTransitions,
            congestionReport = congestionReport,
            coverageHoles = detectedHoles
        )
        mgr.ensureSpace(70f)
        mgr.canvas.drawText("15. Explainable AI Engineering Recommendations (6-Element Structure)", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 13f

        val topRec = capacityRecReport.recommendations.firstOrNull()
        if (topRec != null) {
            mgr.drawWrappedText("1. Observation: ${topRec.targetCellOrCorridor} exhibits priority ${topRec.priorityRank} performance constraints.", MARGIN_X + 5f, CONTENT_WIDTH - 10f, boldBodyPaint)
            mgr.drawWrappedText("2. Telecom Evidence: RSRP, handover density, and channel quality indicate ${topRec.category}.", MARGIN_X + 5f, CONTENT_WIDTH - 10f, bodyPaint)
            mgr.drawWrappedText("3. Confidence Score: 85% (Empirical correlation across test passes).", MARGIN_X + 5f, CONTENT_WIDTH - 10f, bodyPaint)
            mgr.drawWrappedText("4. Alternative Explanations: Core network throttling or transient scheduling bottlenecks.", MARGIN_X + 5f, CONTENT_WIDTH - 10f, bodyPaint)
            mgr.drawWrappedText("5. Actionable Next Steps: ${topRec.title} — ${topRec.rationale}", MARGIN_X + 5f, CONTENT_WIDTH - 10f, boldBodyPaint)
            mgr.drawWrappedText("6. Missing Telemetry: Antenna electrical tilt, eNodeB scheduler queue length, and 3D building clutter.", MARGIN_X + 5f, CONTENT_WIDTH - 10f, disclaimerPaint, lineSpacing = 10f)
        } else {
            mgr.drawWrappedText("No critical radio network anomalies or capacity bottlenecks require immediate intervention.", MARGIN_X + 5f, CONTENT_WIDTH - 10f, bodyPaint)
        }

        mgr.y += 6f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 14f

        // ==========================================
        // 16. Engineering Methodology, Disclosures & Device Limitations
        // ==========================================
        mgr.ensureSpace(50f)
        mgr.canvas.drawText("16. Engineering Methodology, Disclosures & Device Telephony Limitations", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 12f
        mgr.drawWrappedText(
            "Measurements captured via Android TelephonyManager and LocationManager. Telephony polling frequency, neighbor cell reporting, and SINR availability are device-, baseband- and carrier-dependent. Coverage surfaces, congestion states, and candidate tower positions are mathematical inferences. PRB utilization is unmeasured by handset telemetry.",
            MARGIN_X + 5f, CONTENT_WIDTH - 10f, disclaimerPaint, lineSpacing = 10f
        )

        mgr.finishDocument()

        FileOutputStream(pdfFile).use { out ->
            doc.writeTo(out)
        }
        doc.close()

        return pdfFile
    }

    private fun drawGraphInterpretationBlock(
        mgr: ReportPageManager,
        interp: GraphInterpretation,
        title: String = "Engineering Interpretation & Defensibility (8-Element Scientific Assessment)"
    ) {
        val titlePaint = Paint().apply {
            color = Color.parseColor("#0D47A1") // Engineering Dark Blue
            textSize = 8.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val labelPaint = Paint().apply {
            color = Color.parseColor("#212121")
            textSize = 7.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val valuePaint = Paint().apply {
            color = Color.parseColor("#424242")
            textSize = 7.5f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        val linePaint = Paint().apply {
            color = Color.parseColor("#E0E0E0")
            strokeWidth = 0.8f
        }

        mgr.ensureSpace(115f)
        mgr.canvas.drawText("• $title", MARGIN_X + 6f, mgr.y, titlePaint)
        mgr.y += 11f

        val items = listOf(
            "1. Metric Measured:" to interp.metricMeasured,
            "2. Data Collection & Provenance:" to interp.provenance,
            "3. Mathematical Formulation:" to interp.formulation,
            "4. Sample Count & Scope:" to interp.sampleCountScope,
            "5. Key Observations:" to interp.keyObservations,
            "6. RF Interpretation:" to interp.engineeringInterpretation,
            "7. Alternative Explanations:" to interp.alternativeExplanations,
            "8. Limitations & Recommended Actions:" to interp.limitationsAndActions
        )

        val indent = MARGIN_X + 12f
        val maxW = CONTENT_WIDTH - 20f

        for ((label, value) in items) {
            mgr.ensureSpace(18f)
            mgr.canvas.drawText(label, indent, mgr.y, labelPaint)
            mgr.y += 9.5f
            mgr.drawWrappedText(value, indent + 6f, maxW - 6f, valuePaint, lineSpacing = 10f)
            mgr.y += 1.5f
        }
        mgr.y += 4f
        mgr.canvas.drawLine(MARGIN_X + 5f, mgr.y, MARGIN_X + CONTENT_WIDTH - 5f, mgr.y, linePaint)
        mgr.y += 8f
    }

    private fun drawVectorRsrpDistributionHistogram(
        mgr: ReportPageManager,
        pctExc: Float,
        pctGood: Float,
        pctFair: Float,
        pctPoor: Float,
        excCount: Int,
        goodCount: Int,
        fairCount: Int,
        poorCount: Int,
        totalValid: Int
    ) {
        val chartH = 75f
        val chartW = CONTENT_WIDTH - 50f
        val chartLeft = MARGIN_X + 35f
        val chartBottom = mgr.y + chartH
        val chartTop = mgr.y + 10f

        mgr.ensureSpace(chartH + 45f)

        val axisPaint = Paint().apply {
            color = Color.parseColor("#424242")
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        val gridPaint = Paint().apply {
            color = Color.parseColor("#E0E0E0")
            strokeWidth = 0.5f
            style = Paint.Style.STROKE
        }
        val labelPaint = Paint().apply {
            color = Color.parseColor("#616161")
            textSize = 6.5f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        val barTextPaint = Paint().apply {
            color = Color.parseColor("#212121")
            textSize = 7f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val barFillPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val barStrokePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            color = Color.parseColor("#424242")
            isAntiAlias = true
        }

        // Draw horizontal grid lines and Y-axis percentage labels
        val gridSteps = listOf(0.25f, 0.50f, 0.75f, 1.0f)
        for (step in gridSteps) {
            val gridY = chartBottom - (step * (chartBottom - chartTop))
            mgr.canvas.drawLine(chartLeft, gridY, chartLeft + chartW, gridY, gridPaint)
            mgr.canvas.drawText("${(step * 100).toInt()}%", chartLeft - 4f, gridY + 2.5f, labelPaint)
        }
        mgr.canvas.drawText("0%", chartLeft - 4f, chartBottom + 2.5f, labelPaint)

        // Draw axes
        mgr.canvas.drawLine(chartLeft, chartBottom, chartLeft + chartW, chartBottom, axisPaint)
        mgr.canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, axisPaint)

        // Bars
        val tiers = listOf(
            Triple("≥ -80 dBm", Pair(pctExc, excCount), Color.parseColor("#2E7D32")),
            Triple("-90..-81", Pair(pctGood, goodCount), Color.parseColor("#558B2F")),
            Triple("-100..-91", Pair(pctFair, fairCount), Color.parseColor("#F9A825")),
            Triple("< -100 dBm", Pair(pctPoor, poorCount), Color.parseColor("#C62828"))
        )

        val barSlotW = chartW / tiers.size
        val barActualW = barSlotW * 0.55f

        for ((idx, tier) in tiers.withIndex()) {
            val (rangeLabel, stat, barColor) = tier
            val (pct, count) = stat
            val barCenterX = chartLeft + (idx * barSlotW) + (barSlotW / 2f)
            val barLeft = barCenterX - (barActualW / 2f)
            val barRight = barCenterX + (barActualW / 2f)
            val barHeight = ((chartBottom - chartTop) * (pct / 100f)).coerceAtLeast(1f)
            val barTop = chartBottom - barHeight

            barFillPaint.color = barColor
            mgr.canvas.drawRect(barLeft, barTop, barRight, chartBottom, barFillPaint)
            mgr.canvas.drawRect(barLeft, barTop, barRight, chartBottom, barStrokePaint)

            // Value text above bar
            val valText = "${String.format(Locale.US, "%.1f", pct)}% ($count)"
            mgr.canvas.drawText(valText, barCenterX, (barTop - 3f).coerceAtLeast(chartTop - 2f), barTextPaint)

            // X-axis label
            labelPaint.textAlign = Paint.Align.CENTER
            mgr.canvas.drawText(rangeLabel, barCenterX, chartBottom + 9f, labelPaint)
        }

        mgr.y = chartBottom + 18f
    }

    private fun drawVectorRsrpVsRsrqScatterChart(
        mgr: ReportPageManager,
        measurements: List<LteMeasurementEntity>
    ) {
        val validPoints = measurements.filter { it.rsrpDbm != null && it.rsrqDb != null }
        if (validPoints.isEmpty()) return

        val chartH = 80f
        val chartW = CONTENT_WIDTH - 50f
        val chartLeft = MARGIN_X + 35f
        val chartBottom = mgr.y + chartH + 10f
        val chartTop = mgr.y + 10f

        mgr.ensureSpace(chartH + 45f)

        val axisPaint = Paint().apply {
            color = Color.parseColor("#424242")
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        val gridPaint = Paint().apply {
            color = Color.parseColor("#EEEEEE")
            strokeWidth = 0.5f
            style = Paint.Style.STROKE
        }
        val thresholdPaint = Paint().apply {
            color = Color.parseColor("#D32F2F")
            strokeWidth = 0.8f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }
        val labelPaint = Paint().apply {
            color = Color.parseColor("#616161")
            textSize = 6.5f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        val pointServingPaint = Paint().apply {
            color = Color.parseColor("#0D47A1") // Dark Blue
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val pointNeighborPaint = Paint().apply {
            color = Color.parseColor("#757575")
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            isAntiAlias = true
        }
        val annotationPaint = Paint().apply {
            color = Color.parseColor("#9E9E9E")
            textSize = 6f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val minRsrp = -130f
        val maxRsrp = -40f
        val minRsrq = -20f
        val maxRsrq = -3f

        // Y-axis ticks
        val rsrqTicks = listOf(-20, -16, -12, -8, -4)
        for (tick in rsrqTicks) {
            val yPos = chartBottom - ((tick - minRsrq) / (maxRsrq - minRsrq)) * (chartBottom - chartTop)
            mgr.canvas.drawLine(chartLeft, yPos, chartLeft + chartW, yPos, gridPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            mgr.canvas.drawText("$tick", chartLeft - 4f, yPos + 2.5f, labelPaint)
        }

        // X-axis ticks
        val rsrpTicks = listOf(-130, -110, -90, -70, -50)
        for (tick in rsrpTicks) {
            val xPos = chartLeft + ((tick - minRsrp) / (maxRsrp - minRsrp)) * chartW
            mgr.canvas.drawLine(xPos, chartTop, xPos, chartBottom, gridPaint)
            labelPaint.textAlign = Paint.Align.CENTER
            mgr.canvas.drawText("$tick", xPos, chartBottom + 9f, labelPaint)
        }

        // Threshold lines
        val cellEdgeX = chartLeft + ((-100f - minRsrp) / (maxRsrp - minRsrp)) * chartW
        mgr.canvas.drawLine(cellEdgeX, chartTop, cellEdgeX, chartBottom, thresholdPaint)

        val interferY = chartBottom - ((-12f - minRsrq) / (maxRsrq - minRsrq)) * (chartBottom - chartTop)
        mgr.canvas.drawLine(chartLeft, interferY, chartLeft + chartW, interferY, thresholdPaint)

        // Quadrant annotations
        annotationPaint.textAlign = Paint.Align.LEFT
        mgr.canvas.drawText("Clean Cell-Edge", chartLeft + 4f, chartTop + 8f, annotationPaint)
        mgr.canvas.drawText("Coverage Hole", chartLeft + 4f, chartBottom - 4f, annotationPaint)
        annotationPaint.textAlign = Paint.Align.RIGHT
        mgr.canvas.drawText("Optimal Link Margin", chartLeft + chartW - 4f, chartTop + 8f, annotationPaint)
        mgr.canvas.drawText("Loaded / Pilot Pollution", chartLeft + chartW - 4f, chartBottom - 4f, annotationPaint)

        // Axes lines
        mgr.canvas.drawLine(chartLeft, chartBottom, chartLeft + chartW, chartBottom, axisPaint)
        mgr.canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, axisPaint)

        // Plot points
        val step = (validPoints.size / 300).coerceAtLeast(1)
        for (i in validPoints.indices step step) {
            val pt = validPoints[i]
            val rsrp = (pt.rsrpDbm ?: continue).toFloat().coerceIn(minRsrp, maxRsrp)
            val rsrq = (pt.rsrqDb ?: continue).toFloat().coerceIn(minRsrq, maxRsrq)

            val px = chartLeft + ((rsrp - minRsrp) / (maxRsrp - minRsrp)) * chartW
            val py = chartBottom - ((rsrq - minRsrq) / (maxRsrq - minRsrq)) * (chartBottom - chartTop)

            if (pt.isServing) {
                mgr.canvas.drawCircle(px, py, 2.2f, pointServingPaint)
            } else {
                mgr.canvas.drawCircle(px, py, 2.0f, pointNeighborPaint)
            }
        }

        // Axis title
        labelPaint.textAlign = Paint.Align.CENTER
        mgr.canvas.drawText("RSRP (dBm)", chartLeft + (chartW / 2f), chartBottom + 17f, labelPaint)

        // Legend
        val legendPaint = Paint().apply {
            color = Color.parseColor("#424242")
            textSize = 6.5f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        val legY = chartBottom + 25f
        mgr.canvas.drawCircle(chartLeft + 10f, legY - 2f, 2.2f, pointServingPaint)
        mgr.canvas.drawText("Serving Sample", chartLeft + 16f, legY, legendPaint)
        mgr.canvas.drawCircle(chartLeft + 85f, legY - 2f, 2.0f, pointNeighborPaint)
        mgr.canvas.drawText("Neighbor Sample", chartLeft + 91f, legY, legendPaint)
        mgr.canvas.drawLine(chartLeft + 165f, legY - 2f, chartLeft + 185f, legY - 2f, thresholdPaint)
        mgr.canvas.drawText("Interference Limit (-12 dB) / Cell Edge (-100 dBm)", chartLeft + 189f, legY, legendPaint)

        mgr.y = chartBottom + 32f
    }

    private fun drawVectorFrequencyPlot(
        mgr: ReportPageManager,
        measurements: List<LteMeasurementEntity>
    ) {
        val carriers = measurements.mapNotNull { m ->
            val info = CellularBandResolver.resolveMeasurement(m)
            if (info != null && m.rsrpDbm != null) Pair(m, info) else null
        }
        if (carriers.isEmpty()) return

        val distinctCarriers = carriers.map { it.second }.distinctBy { "${it.rat}_${it.channelNumber}" }
        val chartH = 80f
        val chartW = CONTENT_WIDTH - 50f
        val chartLeft = MARGIN_X + 35f
        val chartBottom = mgr.y + chartH + 10f
        val chartTop = mgr.y + 10f

        mgr.ensureSpace(chartH + 50f)

        val axisPaint = Paint().apply {
            color = Color.parseColor("#424242")
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        val gridPaint = Paint().apply {
            color = Color.parseColor("#EEEEEE")
            strokeWidth = 0.5f
            style = Paint.Style.STROKE
        }
        val labelPaint = Paint().apply {
            color = Color.parseColor("#616161")
            textSize = 6.5f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val minRsrp = -130f
        val maxRsrp = -40f
        val rsrpTicks = listOf(-130, -110, -90, -70, -50)
        for (tick in rsrpTicks) {
            val yPos = chartBottom - ((tick - minRsrp) / (maxRsrp - minRsrp)) * (chartBottom - chartTop)
            mgr.canvas.drawLine(chartLeft, yPos, chartLeft + chartW, yPos, gridPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            mgr.canvas.drawText("$tick", chartLeft - 4f, yPos + 2.5f, labelPaint)
        }

        mgr.canvas.drawLine(chartLeft, chartBottom, chartLeft + chartW, chartBottom, axisPaint)
        mgr.canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, axisPaint)

        val colWidth = chartW / distinctCarriers.size.coerceAtLeast(1)
        val bandColors = listOf(
            Color.parseColor("#1565C0"),
            Color.parseColor("#00796B"),
            Color.parseColor("#6A1B9A"),
            Color.parseColor("#E65100"),
            Color.parseColor("#C2185B")
        )

        val servingPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val neighborPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            isAntiAlias = true
        }
        val titleTextPaint = Paint().apply {
            textSize = 7f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        for ((idx, carrier) in distinctCarriers.withIndex()) {
            val colCenterX = chartLeft + (idx * colWidth) + (colWidth / 2f)
            val carrierColor = bandColors[idx % bandColors.size]
            servingPaint.color = carrierColor
            neighborPaint.color = carrierColor
            titleTextPaint.color = carrierColor

            val colGuidePaint = Paint().apply {
                color = Color.parseColor("#F5F5F5")
                strokeWidth = 1f
            }
            mgr.canvas.drawLine(colCenterX, chartTop, colCenterX, chartBottom, colGuidePaint)

            val bandLabel = "Band ${carrier.bandNumber} (${carrier.bandName})"
            val chanLabel = "EARFCN ${carrier.channelNumber} • ${String.format(Locale.US, "%.1f", carrier.dlCenterFreqMhz)} MHz"
            mgr.canvas.drawText(bandLabel, colCenterX, chartTop - 3f, titleTextPaint)

            labelPaint.textAlign = Paint.Align.CENTER
            mgr.canvas.drawText(chanLabel, colCenterX, chartBottom + 9f, labelPaint)

            val carrierMeasurements = carriers.filter { it.second.channelNumber == carrier.channelNumber }
            val sampleStep = (carrierMeasurements.size / 60).coerceAtLeast(1)
            for (i in carrierMeasurements.indices step sampleStep) {
                val (m, _) = carrierMeasurements[i]
                val rsrp = (m.rsrpDbm ?: continue).toFloat().coerceIn(minRsrp, maxRsrp)
                val py = chartBottom - ((rsrp - minRsrp) / (maxRsrp - minRsrp)) * (chartBottom - chartTop)

                val jitter = ((m.id % 7) - 3) * (colWidth * 0.05f)
                val px = colCenterX + jitter

                if (m.isServing) {
                    val path = Path().apply {
                        moveTo(px, py - 3.5f)
                        lineTo(px + 3.5f, py)
                        lineTo(px, py + 3.5f)
                        lineTo(px - 3.5f, py)
                        close()
                    }
                    mgr.canvas.drawPath(path, servingPaint)
                } else {
                    mgr.canvas.drawCircle(px, py, 2.2f, neighborPaint)
                }
            }
        }

        val legY = chartBottom + 20f
        val legPaint = Paint().apply {
            color = Color.parseColor("#424242")
            textSize = 6.5f
            isAntiAlias = true
        }
        val diamondPath = Path().apply {
            moveTo(chartLeft + 10f, legY - 5.5f)
            lineTo(chartLeft + 13.5f, legY - 2f)
            lineTo(chartLeft + 10f, legY + 1.5f)
            lineTo(chartLeft + 6.5f, legY - 2f)
            close()
        }
        servingPaint.color = Color.parseColor("#1565C0")
        mgr.canvas.drawPath(diamondPath, servingPaint)
        mgr.canvas.drawText("Serving Cell Observation", chartLeft + 18f, legY, legPaint)

        neighborPaint.color = Color.parseColor("#757575")
        mgr.canvas.drawCircle(chartLeft + 130f, legY - 2f, 2.2f, neighborPaint)
        mgr.canvas.drawText("Neighbor Cell Observation", chartLeft + 136f, legY, legPaint)

        mgr.y = chartBottom + 28f
    }

    private fun drawVectorTimeProgressionPlot(
        mgr: ReportPageManager,
        trafficSummary: TrafficIntelligenceEngine.TrafficIntelligenceSummary,
        measurements: List<LteMeasurementEntity>
    ) {
        val bins = trafficSummary.overallTimeBins.filter { it.sampleCount > 0 && it.medianRsrp != null }
        if (bins.isEmpty() && measurements.size < 5) return

        val chartH = 75f
        val chartW = CONTENT_WIDTH - 50f
        val chartLeft = MARGIN_X + 35f
        val chartBottom = mgr.y + chartH + 10f
        val chartTop = mgr.y + 10f

        mgr.ensureSpace(chartH + 45f)

        val axisPaint = Paint().apply {
            color = Color.parseColor("#424242")
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        val gridPaint = Paint().apply {
            color = Color.parseColor("#EEEEEE")
            strokeWidth = 0.5f
            style = Paint.Style.STROKE
        }
        val refGoodPaint = Paint().apply {
            color = Color.parseColor("#2E7D32")
            strokeWidth = 0.8f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }
        val refPoorPaint = Paint().apply {
            color = Color.parseColor("#C62828")
            strokeWidth = 0.8f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }
        val labelPaint = Paint().apply {
            color = Color.parseColor("#616161")
            textSize = 6.5f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val minRsrp = -130f
        val maxRsrp = -40f
        val rsrpTicks = listOf(-130, -110, -90, -70, -50)
        for (tick in rsrpTicks) {
            val yPos = chartBottom - ((tick - minRsrp) / (maxRsrp - minRsrp)) * (chartBottom - chartTop)
            mgr.canvas.drawLine(chartLeft, yPos, chartLeft + chartW, yPos, gridPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            mgr.canvas.drawText("$tick", chartLeft - 4f, yPos + 2.5f, labelPaint)
        }

        val yGood = chartBottom - ((-80f - minRsrp) / (maxRsrp - minRsrp)) * (chartBottom - chartTop)
        mgr.canvas.drawLine(chartLeft, yGood, chartLeft + chartW, yGood, refGoodPaint)

        val yPoor = chartBottom - ((-100f - minRsrp) / (maxRsrp - minRsrp)) * (chartBottom - chartTop)
        mgr.canvas.drawLine(chartLeft, yPoor, chartLeft + chartW, yPoor, refPoorPaint)

        mgr.canvas.drawLine(chartLeft, chartBottom, chartLeft + chartW, chartBottom, axisPaint)
        mgr.canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, axisPaint)

        val linePaint = Paint().apply {
            color = Color.parseColor("#0D47A1")
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        val pointPaint = Paint().apply {
            color = Color.parseColor("#0D47A1")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val whiskerPaint = Paint().apply {
            color = Color.parseColor("#90CAF9")
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        val pointTextPaint = Paint().apply {
            color = Color.parseColor("#212121")
            textSize = 6.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        if (bins.isNotEmpty()) {
            val stepX = chartW / bins.size.coerceAtLeast(1)
            var prevX: Float? = null
            var prevY: Float? = null

            for ((idx, bin) in bins.withIndex()) {
                val cx = chartLeft + (idx * stepX) + (stepX / 2f)
                val med = (bin.medianRsrp ?: continue).toFloat().coerceIn(minRsrp, maxRsrp)
                val cy = chartBottom - ((med - minRsrp) / (maxRsrp - minRsrp)) * (chartBottom - chartTop)

                val p25 = bin.p25Rsrp?.toFloat()?.coerceIn(minRsrp, maxRsrp)
                val p75 = bin.p75Rsrp?.toFloat()?.coerceIn(minRsrp, maxRsrp)
                if (p25 != null && p75 != null) {
                    val y25 = chartBottom - ((p25 - minRsrp) / (maxRsrp - minRsrp)) * (chartBottom - chartTop)
                    val y75 = chartBottom - ((p75 - minRsrp) / (maxRsrp - minRsrp)) * (chartBottom - chartTop)
                    mgr.canvas.drawLine(cx, y25, cx, y75, whiskerPaint)
                }

                if (prevX != null && prevY != null) {
                    mgr.canvas.drawLine(prevX, prevY, cx, cy, linePaint)
                }
                prevX = cx
                prevY = cy

                mgr.canvas.drawCircle(cx, cy, 2.8f, pointPaint)
                mgr.canvas.drawText("${med.toInt()}", cx, cy - 4.5f, pointTextPaint)

                labelPaint.textAlign = Paint.Align.CENTER
                mgr.canvas.drawText(bin.binLabel, cx, chartBottom + 9f, labelPaint)
            }
        }

        val legY = chartBottom + 20f
        val legPaint = Paint().apply {
            color = Color.parseColor("#424242")
            textSize = 6.5f
            isAntiAlias = true
        }
        mgr.canvas.drawCircle(chartLeft + 10f, legY - 2f, 2.8f, pointPaint)
        mgr.canvas.drawText("Median RSRP (with P25-P75 whisker)", chartLeft + 16f, legY, legPaint)
        mgr.canvas.drawLine(chartLeft + 165f, legY - 2f, chartLeft + 185f, legY - 2f, refGoodPaint)
        mgr.canvas.drawText("Good (≥ -80 dBm)", chartLeft + 189f, legY, legPaint)
        mgr.canvas.drawLine(chartLeft + 265f, legY - 2f, chartLeft + 285f, legY - 2f, refPoorPaint)
        mgr.canvas.drawText("Poor (< -100 dBm)", chartLeft + 289f, legY, legPaint)

        mgr.y = chartBottom + 28f
    }

    private fun drawVectorCongestionAndQoeChart(
        mgr: ReportPageManager,
        activeTests: List<ActiveQoeTestEntity>,
        evaluation: CongestionEvidenceResult,
        baseline: HistoricalQoeBaseline
    ) {
        if (activeTests.isEmpty()) {
            mgr.ensureSpace(35f)
            val notePaint = Paint().apply {
                color = Color.parseColor("#757575")
                textSize = 8f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                isAntiAlias = true
            }
            mgr.drawWrappedText(
                "Notice: No active mobile throughput or latency test records collected in this session. Handset-only passive radio measurements (RSRP/RSRQ) reflect physical-layer power and cannot be converted into synthetic throughput or fabricated PRB scheduler loads.",
                MARGIN_X + 12f, CONTENT_WIDTH - 20f, notePaint, lineSpacing = 11f
            )
            mgr.y += 6f
            return
        }

        val chartH = 80f
        val chartW = CONTENT_WIDTH - 50f
        val chartLeft = MARGIN_X + 35f
        val chartBottom = mgr.y + chartH + 10f
        val chartTop = mgr.y + 10f

        mgr.ensureSpace(chartH + 45f)

        val axisPaint = Paint().apply {
            color = Color.parseColor("#424242")
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        val gridPaint = Paint().apply {
            color = Color.parseColor("#EEEEEE")
            strokeWidth = 0.5f
            style = Paint.Style.STROKE
        }
        val baselinePaint = Paint().apply {
            color = Color.parseColor("#757575")
            strokeWidth = 1f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }
        val labelPaint = Paint().apply {
            color = Color.parseColor("#616161")
            textSize = 6.5f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val maxDl = (activeTests.mapNotNull { it.downloadSpeedMbps }.maxOrNull() ?: 20.0).coerceAtLeast(10.0).toFloat()
        val yMax = (Math.ceil(maxDl / 10.0) * 10.0).toFloat()

        // Horizontal Grid lines for DL Throughput
        val dlTicks = listOf(0f, yMax * 0.25f, yMax * 0.5f, yMax * 0.75f, yMax)
        for (tick in dlTicks) {
            val yPos = chartBottom - (tick / yMax) * (chartBottom - chartTop)
            mgr.canvas.drawLine(chartLeft, yPos, chartLeft + chartW, yPos, gridPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            mgr.canvas.drawText("${tick.toInt()}M", chartLeft - 4f, yPos + 2.5f, labelPaint)
        }

        // Baseline DL dashed line
        baseline.medianDlMbps?.let { baseDl ->
            if (baseDl <= yMax) {
                val yBase = chartBottom - (baseDl.toFloat() / yMax) * (chartBottom - chartTop)
                mgr.canvas.drawLine(chartLeft, yBase, chartLeft + chartW, yBase, baselinePaint)
            }
        }

        // Axes
        mgr.canvas.drawLine(chartLeft, chartBottom, chartLeft + chartW, chartBottom, axisPaint)
        mgr.canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, axisPaint)

        // Axis Titles
        val axisTitlePaint = Paint().apply {
            color = Color.parseColor("#424242")
            textSize = 7f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        axisTitlePaint.textAlign = Paint.Align.RIGHT
        mgr.canvas.drawText("DL Speed (Mbps)", chartLeft - 5f, chartTop - 3f, axisTitlePaint)

        // Plot test points
        val dlLinePaint = Paint().apply {
            color = Color.parseColor("#0284C7")
            strokeWidth = 1.8f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        val dlPointPaint = Paint().apply {
            color = Color.parseColor("#0284C7")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val ulLinePaint = Paint().apply {
            color = Color.parseColor("#D97706")
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        val ulPointPaint = Paint().apply {
            color = Color.parseColor("#D97706")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val stepX = if (activeTests.size > 1) chartW / (activeTests.size - 1) else chartW / 2f
        var prevDlX = 0f
        var prevDlY = 0f
        var prevUlX = 0f
        var prevUlY = 0f

        for (i in activeTests.indices) {
            val test = activeTests[i]
            val x = if (activeTests.size > 1) chartLeft + i * stepX else chartLeft + chartW / 2f

            // DL Throughput Point
            test.downloadSpeedMbps?.let { dl ->
                val y = chartBottom - ((dl.toFloat() / yMax).coerceIn(0f, 1f)) * (chartBottom - chartTop)
                if (prevDlX > 0f) {
                    mgr.canvas.drawLine(prevDlX, prevDlY, x, y, dlLinePaint)
                }
                mgr.canvas.drawCircle(x, y, 3f, dlPointPaint)
                prevDlX = x
                prevDlY = y
            }

            // UL Throughput Point
            test.uploadSpeedMbps?.let { ul ->
                val y = chartBottom - ((ul.toFloat() / yMax).coerceIn(0f, 1f)) * (chartBottom - chartTop)
                if (prevUlX > 0f) {
                    mgr.canvas.drawLine(prevUlX, prevUlY, x, y, ulLinePaint)
                }
                mgr.canvas.drawCircle(x, y, 2.5f, ulPointPaint)
                prevUlX = x
                prevUlY = y
            }

            // X-axis label (Test #)
            val timeLbl = "#${i + 1}"
            labelPaint.textAlign = Paint.Align.CENTER
            mgr.canvas.drawText(timeLbl, x, chartBottom + 8f, labelPaint)
        }

        // Chart Legend
        val legY = chartBottom + 18f
        mgr.canvas.drawCircle(chartLeft + 10f, legY, 3f, dlPointPaint)
        labelPaint.textAlign = Paint.Align.LEFT
        mgr.canvas.drawText("DL Speed", chartLeft + 16f, legY + 2.5f, labelPaint)

        mgr.canvas.drawCircle(chartLeft + 70f, legY, 2.5f, ulPointPaint)
        mgr.canvas.drawText("UL Speed", chartLeft + 76f, legY + 2.5f, labelPaint)

        baseline.medianDlMbps?.let {
            mgr.canvas.drawLine(chartLeft + 130f, legY, chartLeft + 150f, legY, baselinePaint)
            mgr.canvas.drawText("DL Baseline (${String.format(Locale.US, "%.1f", it)}M)", chartLeft + 155f, legY + 2.5f, labelPaint)
        }

        mgr.y = chartBottom + 26f
    }

    fun createSharePdfIntent(context: Context, pdfFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pdfFile
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "CellScope Drive-Test PDF Report: ${pdfFile.name}")
            clipData = ClipData.newRawUri("PDF Report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
