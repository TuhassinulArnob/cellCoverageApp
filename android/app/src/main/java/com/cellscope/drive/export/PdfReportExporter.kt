package com.cellscope.drive.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.cellscope.drive.analytics.CallDropRiskEngine
import com.cellscope.drive.analytics.CoveragePlanner
import com.cellscope.drive.analytics.HandoverAnalyzer
import com.cellscope.drive.analytics.NetworkContinuityAnalyzer
import com.cellscope.drive.analytics.ServiceHoleDetector
import com.cellscope.drive.data.entity.CallReliabilityEventEntity
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.data.entity.NetworkContinuityEventEntity
import com.cellscope.drive.data.entity.ServiceHoleEntity
import com.cellscope.drive.model.NetworkGeneration
import com.cellscope.drive.util.TelecomTimeUtils
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            canvas.drawText("CellScope Field Assessment • Platform Lead: Sarkar Tuhassinul Arnob (arnob.ict.iu@gmail.com)", MARGIN_X, 815f, footerPaint)
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
            canvas.drawText("CellScope LTE Drive-Test Report — Continued", MARGIN_X, y, headerPaint)
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
        callEvents: List<CallReliabilityEventEntity> = emptyList()
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

        // RSRP Distribution
        val validRsrp = measurements.mapNotNull { it.rsrpDbm }
        val excellentCount = validRsrp.count { it >= -80 }
        val goodCount = validRsrp.count { it in -90..-81 }
        val fairCount = validRsrp.count { it in -100..-91 }
        val poorCount = validRsrp.count { it < -100 }
        val totalRsrp = validRsrp.size.coerceAtLeast(1)

        val pctExc = (excellentCount * 100f / totalRsrp)
        val pctGood = (goodCount * 100f / totalRsrp)
        val pctFair = (fairCount * 100f / totalRsrp)
        val pctPoor = (poorCount * 100f / totalRsrp)

        // Paints
        val primaryPaint = Paint().apply {
            color = Color.parseColor("#0D47A1") // Dark Blue
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val titlePaint = Paint().apply {
            color = Color.parseColor("#212121")
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val bodyPaint = Paint().apply {
            color = Color.parseColor("#424242")
            textSize = 9.5f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val boldBodyPaint = Paint().apply {
            color = Color.parseColor("#212121")
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val linePaint = Paint().apply {
            color = Color.parseColor("#BDBDBD")
            strokeWidth = 1f
        }

        val fillPaint = Paint().apply { style = Paint.Style.FILL }

        // ==========================================
        // 1. Header Banner
        // ==========================================
        mgr.canvas.drawText("CELLSCOPE DRIVE-TEST & RF COVERAGE REPORT", MARGIN_X, mgr.y, primaryPaint)
        mgr.y += 16f
        mgr.canvas.drawText("Automated Native Android Cellular Field Assessment • Lead: Sarkar Tuhassinul Arnob", MARGIN_X, mgr.y, bodyPaint)
        mgr.y += 14f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 18f

        // ==========================================
        // 2. Executive Summary
        // ==========================================
        mgr.ensureSpace(120f)
        mgr.canvas.drawText("1. Session Executive Summary", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 15f

        val durationSec = if (session.endTimeMs != null) {
            (session.endTimeMs!! - session.startTimeMs) / 1000
        } else {
            (measurements.lastOrNull()?.timestampMs ?: session.startTimeMs) - session.startTimeMs
        } / 1000
        val durationStr = String.format(Locale.US, "%02d:%02d:%02d", durationSec / 3600, (durationSec % 3600) / 60, durationSec % 60)
        val distKmStr = String.format(Locale.US, "%.2f km", session.totalDistanceM / 1000.0)
        val isStationary = session.totalDistanceM < 20.0 && measurements.size >= 5
        val mobilityModeStr = if (isStationary) "STATIONARY (Jitter Filtered)" else "DYNAMIC DRIVE"
        val dateStr = "${TelecomTimeUtils.formatBdtDateTime(session.startTimeMs)} (BST)"
        val avgRsrpStr = session.avgRsrpDbm?.let { String.format(Locale.US, "%.1f dBm", it) } ?: "— dBm"
        val maxSpeedStr = if (isStationary) "0.0 km/h (Filtered)" else String.format(Locale.US, "%.1f km/h", session.maxSpeedKmh)
        val distDisplayStr = if (isStationary) "0.00 km (Filtered)" else distKmStr

        val qoeReport = com.cellscope.drive.analytics.qoe.QoeAnalyzer.analyzeSessionQoe(session, measurements)
        val gpsAvailStr = String.format(Locale.US, "%.1f%% (%d valid)", qoeReport.gpsAvailabilityPct, qoeReport.validGpsSamples)
        val radioAvailStr = String.format(Locale.US, "%.1f%%", qoeReport.radioAvailabilityPct)
        val coverageAdequacyStr = String.format(Locale.US, "%.1f%%", qoeReport.coverageAdequacyPct)

        val summaryItems = mutableListOf(
            Pair("Session Name:", session.sessionName),
            Pair("Start Timestamp:", dateStr),
            Pair("Duration:", durationStr),
            Pair("Mobility Mode:", mobilityModeStr),
            Pair("Route Distance:", distDisplayStr),
            Pair("Max Speed:", maxSpeedStr),
            Pair("Sample Count:", "${measurements.size} measurements"),
            Pair("Mean RSRP:", avgRsrpStr),
            Pair("GPS Availability:", gpsAvailStr),
            Pair("Radio Availability:", radioAvailStr),
            Pair("Coverage Adequacy:", coverageAdequacyStr),
            Pair("Serving Cells Observed:", "${handoverSummary.dwellStats.size} distinct PCIs")
        )

        if (session.avgLatencyMs != null || session.downloadThroughputMbps != null) {
            val latStr = session.avgLatencyMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "—"
            val dlStr = session.downloadThroughputMbps?.let { String.format(Locale.US, "%.2f Mbps", it) } ?: "—"
            summaryItems.add(Pair("Mean HTTP Latency:", latStr))
            summaryItems.add(Pair("Mean DL Throughput:", dlStr))
        }

        for (i in summaryItems.indices step 2) {
            val item1 = summaryItems[i]
            mgr.canvas.drawText(item1.first, MARGIN_X + 5f, mgr.y, boldBodyPaint)
            mgr.canvas.drawText(item1.second, MARGIN_X + 120f, mgr.y, bodyPaint)

            if (i + 1 < summaryItems.size) {
                val item2 = summaryItems[i + 1]
                mgr.canvas.drawText(item2.first, MARGIN_X + 260f, mgr.y, boldBodyPaint)
                mgr.canvas.drawText(item2.second, MARGIN_X + 390f, mgr.y, bodyPaint)
            }
            mgr.y += 13f
        }

        if (isStationary) {
            mgr.ensureSpace(14f)
            mgr.canvas.drawText("• Mobility Note: Stationary session detected. GPS multipath jitter filter applied to eliminate indoor speed/distance drift.", MARGIN_X + 5f, mgr.y, boldBodyPaint)
            mgr.y += 13f
        }

        mgr.y += 10f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 18f

        // ==========================================
        // 3. RF Signal Quality Distribution (3GPP TS 36.133)
        // ==========================================
        mgr.ensureSpace(90f)
        mgr.canvas.drawText("2. RF Signal Quality Distribution (3GPP TS 36.133)", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 16f

        // Horizontal Stacked Bar
        val barX = MARGIN_X + 5f
        val barW = CONTENT_WIDTH - 10f
        val barH = 16f

        val wExc = barW * (pctExc / 100f)
        val wGood = barW * (pctGood / 100f)
        val wFair = barW * (pctFair / 100f)
        val wPoor = barW * (pctPoor / 100f)

        var curBarX = barX
        fillPaint.color = Color.parseColor("#00C853") // Green
        mgr.canvas.drawRect(curBarX, mgr.y, curBarX + wExc, mgr.y + barH, fillPaint)
        curBarX += wExc

        fillPaint.color = Color.parseColor("#64DD17") // Light Green
        mgr.canvas.drawRect(curBarX, mgr.y, curBarX + wGood, mgr.y + barH, fillPaint)
        curBarX += wGood

        fillPaint.color = Color.parseColor("#FFD600") // Yellow
        mgr.canvas.drawRect(curBarX, mgr.y, curBarX + wFair, mgr.y + barH, fillPaint)
        curBarX += wFair

        fillPaint.color = Color.parseColor("#D50000") // Red
        mgr.canvas.drawRect(curBarX, mgr.y, curBarX + wPoor, mgr.y + barH, fillPaint)

        mgr.y += barH + 14f

        // Distribution Legend
        val tiers = listOf(
            Triple("Excellent (≥ -80 dBm)", String.format(Locale.US, "%.1f%% (%d)", pctExc, excellentCount), Color.parseColor("#00C853")),
            Triple("Good (-90 to -81 dBm)", String.format(Locale.US, "%.1f%% (%d)", pctGood, goodCount), Color.parseColor("#64DD17")),
            Triple("Fair (-100 to -91 dBm)", String.format(Locale.US, "%.1f%% (%d)", pctFair, fairCount), Color.parseColor("#FFD600")),
            Triple("Poor (< -100 dBm)", String.format(Locale.US, "%.1f%% (%d)", pctPoor, poorCount), Color.parseColor("#D50000"))
        )

        var legX = MARGIN_X + 5f
        for ((label, valStr, color) in tiers) {
            fillPaint.color = color
            mgr.canvas.drawRect(legX, mgr.y - 7f, legX + 9f, mgr.y + 2f, fillPaint)
            mgr.canvas.drawText(label, legX + 13f, mgr.y, bodyPaint)
            mgr.canvas.drawText(valStr, legX + 13f, mgr.y + 10f, boldBodyPaint)
            legX += 128f
        }

        mgr.y += 24f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 18f

        // ==========================================
        // 4. Handover & Serving-Cell Dwell Analytics
        // ==========================================
        mgr.ensureSpace(80f)
        mgr.canvas.drawText("3. Handover & Serving-Cell Dwell Analytics", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 15f

        mgr.canvas.drawText(
            "Total Cell Transitions: ${handoverSummary.totalTransitions}  |  Confirmed Handovers: ${handoverSummary.confirmedHandovers}  |  Reselections: ${handoverSummary.cellReselections}",
            MARGIN_X + 5f, mgr.y, bodyPaint
        )
        mgr.y += 13f
        mgr.canvas.drawText(
            "Transition Density: ${String.format(Locale.US, "%.2f", handoverSummary.transitionsPerKm)} transitions/km  |  Signal Loss Reselections: ${handoverSummary.signalLossTransitions}",
            MARGIN_X + 5f, mgr.y, bodyPaint
        )
        mgr.y += 16f

        fun drawDwellTableHeader() {
            mgr.canvas.drawText("PCI", MARGIN_X + 5f, mgr.y, boldBodyPaint)
            mgr.canvas.drawText("Samples", MARGIN_X + 80f, mgr.y, boldBodyPaint)
            mgr.canvas.drawText("Dwell Time", MARGIN_X + 170f, mgr.y, boldBodyPaint)
            mgr.canvas.drawText("Session Share", MARGIN_X + 280f, mgr.y, boldBodyPaint)
            mgr.canvas.drawText("Mean RSRP", MARGIN_X + 400f, mgr.y, boldBodyPaint)
            mgr.y += 5f
            mgr.canvas.drawLine(MARGIN_X + 5f, mgr.y, MARGIN_X + CONTENT_WIDTH - 5f, mgr.y, linePaint)
            mgr.y += 12f
        }

        drawDwellTableHeader()

        if (handoverSummary.dwellStats.isEmpty()) {
            mgr.canvas.drawText("No cell dwell observations recorded.", MARGIN_X + 5f, mgr.y, bodyPaint)
            mgr.y += 14f
        } else {
            for (dwell in handoverSummary.dwellStats) {
                mgr.ensureSpace(14f) {
                    drawDwellTableHeader()
                }

                val dwellMins = dwell.totalDwellTimeMs / 60000
                val dwellSecs = (dwell.totalDwellTimeMs % 60000) / 1000
                val dwellStr = String.format(Locale.US, "%dm %02ds", dwellMins, dwellSecs)
                val avgRsrp = dwell.avgRsrp?.let { String.format(Locale.US, "%.1f dBm", it) } ?: "—"

                mgr.canvas.drawText("PCI ${dwell.pci}", MARGIN_X + 5f, mgr.y, bodyPaint)
                mgr.canvas.drawText("${dwell.sampleCount}", MARGIN_X + 80f, mgr.y, bodyPaint)
                mgr.canvas.drawText(dwellStr, MARGIN_X + 170f, mgr.y, bodyPaint)
                mgr.canvas.drawText(String.format(Locale.US, "%.1f%%", dwell.percentageOfSession), MARGIN_X + 280f, mgr.y, bodyPaint)
                mgr.canvas.drawText(avgRsrp, MARGIN_X + 400f, mgr.y, bodyPaint)
                mgr.y += 12f
            }
        }

        mgr.y += 10f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 18f

        // ==========================================
        // 5. Weak Coverage Zones & Engineering Recommendations
        // ==========================================
        mgr.ensureSpace(60f)
        mgr.canvas.drawText("4. Identified Weak-Coverage Zones & Remediation Feasibility", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 15f

        if (weakZones.isEmpty()) {
            mgr.canvas.drawText("No critical weak-coverage zones detected along the test route (All RSRP > -105 dBm).", MARGIN_X + 5f, mgr.y, bodyPaint)
            mgr.y += 16f
        } else {
            for (zone in weakZones) {
                mgr.ensureSpace(50f)
                val zoneTitle = "Zone #${zone.id}: Length ~${String.format(Locale.US, "%.0f", zone.lengthMeters)}m | Mean RSRP: ${String.format(Locale.US, "%.1f", zone.avgRsrp)} dBm | Min: ${zone.minRsrp} dBm"
                mgr.canvas.drawText(zoneTitle, MARGIN_X + 5f, mgr.y, boldBodyPaint)
                mgr.y += 12f

                mgr.drawWrappedText("Diagnosis: ${zone.diagnosis}", MARGIN_X + 5f, CONTENT_WIDTH - 10f, bodyPaint)

                val topRec = zone.remediationOptions.firstOrNull()
                if (topRec != null) {
                    val recTitle = "Recommended Action: [${topRec.category}] ${topRec.title} (Feasibility: ${(topRec.feasibilityScore * 100).toInt()}%)"
                    mgr.drawWrappedText(recTitle, MARGIN_X + 5f, CONTENT_WIDTH - 10f, boldBodyPaint)
                    mgr.drawWrappedText("Rationale: ${topRec.rationale}", MARGIN_X + 5f, CONTENT_WIDTH - 10f, bodyPaint)
                }
                mgr.y += 6f
            }
        }

        mgr.y += 8f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 16f

        // ==========================================
        // 5. Explainable AI Network Anomaly Detection
        // ==========================================
        val anomalyReport = com.cellscope.drive.analytics.anomaly.NetworkAnomalyDetector.analyzeSession(
            sessionId = session.id,
            measurements = measurements,
            transitions = handoverSummary.transitionEvents
        )
        mgr.ensureSpace(60f)
        mgr.canvas.drawText("5. Explainable AI Network Anomaly Detection (Candidate Hypotheses)", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 14f

        if (anomalyReport.totalAnomalies == 0) {
            mgr.canvas.drawText("No candidate network anomalies or telemetry disruptions flagged across this route.", MARGIN_X + 5f, mgr.y, bodyPaint)
            mgr.y += 16f
        } else {
            val alertSummary = "Identified ${anomalyReport.totalAnomalies} candidate anomalies (${anomalyReport.criticalCount} Critical, ${anomalyReport.warningCount} Warning, ${anomalyReport.infoCount} Advisory). ML consensus: ${String.format(Locale.US, "%.1f%%", anomalyReport.mlAgreementRatioPct)}."
            mgr.drawWrappedText(alertSummary, MARGIN_X + 5f, CONTENT_WIDTH - 10f, boldBodyPaint)
            mgr.y += 4f

            for (a in anomalyReport.anomalies.take(4)) {
                mgr.ensureSpace(35f)
                val alertColor = when (a.severity) {
                    com.cellscope.drive.analytics.anomaly.AnomalySeverity.CRITICAL -> "#D32F2F"
                    com.cellscope.drive.analytics.anomaly.AnomalySeverity.WARNING -> "#F57C00"
                    com.cellscope.drive.analytics.anomaly.AnomalySeverity.INFO -> "#1976D2"
                }
                fillPaint.color = Color.parseColor(alertColor)
                mgr.canvas.drawCircle(MARGIN_X + 9f, mgr.y - 4f, 4f, fillPaint)

                mgr.canvas.drawText("[${a.severity}] ${a.title}", MARGIN_X + 18f, mgr.y, boldBodyPaint)
                mgr.y += 12f
                mgr.drawWrappedText("Hypothesis: ${a.candidateHypothesis}", MARGIN_X + 18f, CONTENT_WIDTH - 25f, bodyPaint)
                mgr.y += 4f
            }
        }

        mgr.y += 8f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 16f

        // ==========================================
        // 6. Co-Channel, Adjacent-Channel & PCI Reuse Analysis
        // ==========================================
        val interferenceReport = com.cellscope.drive.analytics.InterferenceAnalyzer.analyze(measurements)
        mgr.ensureSpace(60f)
        mgr.canvas.drawText("6. Co-Channel, Adjacent-Channel & PCI Reuse Analysis", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 14f

        val interfSummary = "Evaluated ${interferenceReport.totalCoChannelPairs} co-channel allocations, ${interferenceReport.totalAdjacentPairs} adjacent carrier allocations, and ${interferenceReport.crsCollisionsCount} CRS modulo-3 collisions. Correlation: ${interferenceReport.radioQualityCorrelationSummary}"
        mgr.drawWrappedText(interfSummary, MARGIN_X + 5f, CONTENT_WIDTH - 10f, boldBodyPaint)
        mgr.y += 4f

        if (interferenceReport.coChannelEvents.isNotEmpty()) {
            for (cc in interferenceReport.coChannelEvents.take(3)) {
                mgr.ensureSpace(32f)
                val distStr = cc.distanceM?.let { " (Sep: ${it.toInt()}m)" } ?: ""
                mgr.canvas.drawText("• [${cc.classification.label}] Ch ${cc.earfcnOrArfcn} | PCI ${cc.pciA} & ${cc.pciB}$distStr", MARGIN_X + 10f, mgr.y, boldBodyPaint)
                mgr.y += 11f
                mgr.drawWrappedText(cc.engineeringEvidence, MARGIN_X + 18f, CONTENT_WIDTH - 25f, bodyPaint)
                mgr.y += 4f
            }
        }

        if (interferenceReport.adjacentEvents.isNotEmpty()) {
            for (ac in interferenceReport.adjacentEvents.take(2)) {
                mgr.ensureSpace(32f)
                mgr.canvas.drawText("• [${ac.classification.label}] Channels ${ac.channelA} & ${ac.channelB} (Δf = ${String.format(Locale.US, "%.1f", ac.deltaFreqMhz)} MHz)", MARGIN_X + 10f, mgr.y, boldBodyPaint)
                mgr.y += 11f
                mgr.drawWrappedText(ac.engineeringEvidence, MARGIN_X + 18f, CONTENT_WIDTH - 25f, bodyPaint)
                mgr.y += 4f
            }
        }

        val disclaimerPaint = Paint().apply {
            color = Color.parseColor("#757575")
            textSize = 7.5f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        mgr.ensureSpace(28f)
        mgr.drawWrappedText("Interference Limitation: ${interferenceReport.analyticalDisclaimer}", MARGIN_X + 5f, CONTENT_WIDTH - 10f, disclaimerPaint, lineSpacing = 10f)

        mgr.y += 8f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 16f

        // ==========================================
        // 7. Multi-Generation Network Coverage, Continuity & Service Hole Intelligence
        // ==========================================
        val actualContinuity = if (continuityEvents.isNotEmpty()) continuityEvents else {
            NetworkContinuityAnalyzer.analyzeSessionContinuity(session.id, measurements).continuityEvents
        }
        val actualHoles = if (serviceHoles.isNotEmpty()) serviceHoles else {
            ServiceHoleDetector.detectHoles(session.id, measurements).holes
        }
        val callRiskEvaluation = CallDropRiskEngine.evaluateSessionRisk(measurements, actualContinuity, actualHoles, callEvents)

        mgr.ensureSpace(80f)
        mgr.canvas.drawText("7. Multi-Generation Network Coverage, Continuity & Service Hole Intelligence", MARGIN_X, mgr.y, titlePaint)
        mgr.y += 15f

        // Generation breakdown
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
        mgr.y += 4f

        // Continuity transitions & downgrades
        val downgrades = actualContinuity.filter { it.transitionCategory == "DOWNGRADE" }
        val oosEvents = actualContinuity.filter { it.transitionCategory == "OUT_OF_SERVICE" }
        val continuityText = "Continuity Events: ${actualContinuity.size} total shifts (${downgrades.size} downgrades, ${oosEvents.size} service losses). Generation transitions are recorded with engineering evidence without assuming unconfirmed causes."
        mgr.drawWrappedText(continuityText, MARGIN_X + 5f, CONTENT_WIDTH - 10f, bodyPaint)
        mgr.y += 4f

        if (downgrades.isNotEmpty()) {
            for (dg in downgrades.take(2)) {
                mgr.ensureSpace(24f)
                val dgTime = "${TelecomTimeUtils.isoToBdtTimeOnly(dg.timestampIso)} (BST)"
                mgr.canvas.drawText("• [${dg.transitionCategory}] ${dg.fromGeneration} → ${dg.toGeneration} at $dgTime", MARGIN_X + 10f, mgr.y, boldBodyPaint)
                mgr.y += 11f
                mgr.drawWrappedText("${dg.evidenceClassification}: ${dg.details}", MARGIN_X + 18f, CONTENT_WIDTH - 25f, bodyPaint)
                mgr.y += 3f
            }
        }

        // Service Holes summary
        mgr.ensureSpace(50f)
        val holeTypesCount = actualHoles.groupBy { it.holeType }.map { "${it.key}: ${it.value.size}" }.joinToString(", ")
        val holesSummaryStr = if (actualHoles.isEmpty()) {
            "Service Holes: 0 detected. Cellular continuity and connectivity remained uninterrupted."
        } else {
            "Service Holes: ${actualHoles.size} events identified across 7 distinct outage categories ($holeTypesCount)."
        }
        mgr.drawWrappedText(holesSummaryStr, MARGIN_X + 5f, CONTENT_WIDTH - 10f, boldBodyPaint)
        mgr.y += 4f

        if (actualHoles.isNotEmpty()) {
            for (h in actualHoles.take(2)) {
                mgr.ensureSpace(24f)
                val hTime = "${TelecomTimeUtils.isoToBdtTimeOnly(h.timestampIso)} (BST)"
                val durStr = h.durationMs?.let { " (${it / 1000}s duration)" } ?: ""
                mgr.canvas.drawText("• [${h.holeType}] Severity: ${h.severity} at $hTime$durStr", MARGIN_X + 10f, mgr.y, boldBodyPaint)
                mgr.y += 11f
                mgr.drawWrappedText(h.engineeringEvidence, MARGIN_X + 18f, CONTENT_WIDTH - 25f, bodyPaint)
                mgr.y += 3f
            }
        }

        // Call Drop Risk
        mgr.ensureSpace(40f)
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
        mgr.canvas.drawText("${callRiskEvaluation.category} (Risk Score: ${callRiskEvaluation.scoreValue.toInt()}/100)", MARGIN_X + 5f + riskPrefixW, mgr.y, riskPaint)
        mgr.y += 12f
        val callEvidenceStr = "Evaluation: ${callRiskEvaluation.totalCallEvents} call events evaluated (${callRiskEvaluation.suspectedDropsCount} suspected drops). Explanation: ${callRiskEvaluation.analyticalExplanation.take(150)}"
        mgr.drawWrappedText(callEvidenceStr, MARGIN_X + 10f, CONTENT_WIDTH - 15f, bodyPaint)
        mgr.y += 4f

        // Disclaimer for Section 7
        mgr.ensureSpace(20f)
        mgr.drawWrappedText("Multi-Gen Measurement Note: Voice and data bearers are independently evaluated. Telemetry gaps and test timeouts are strictly distinguished from true radio outages.", MARGIN_X + 5f, CONTENT_WIDTH - 10f, disclaimerPaint, lineSpacing = 10f)

        mgr.y += 8f
        mgr.canvas.drawLine(MARGIN_X, mgr.y, MARGIN_X + CONTENT_WIDTH, mgr.y, linePaint)
        mgr.y += 16f

        // ==========================================
        // 8. Methodology & Hardware Disclaimer
        // ==========================================
        mgr.ensureSpace(40f)
        mgr.canvas.drawText("8. Methodology & Device Limitations Note:", MARGIN_X, mgr.y, disclaimerPaint)
        mgr.y += 9f
        mgr.drawWrappedText(
            "Measurements captured via Android TelephonyManager and LocationManager. Telephony polling frequency, neighbor cell reporting, and SINR availability are device-, baseband- and carrier-dependent. Coverage surfaces and tower positions are mathematical inferences.",
            MARGIN_X, CONTENT_WIDTH, disclaimerPaint, lineSpacing = 10f
        )

        mgr.finishDocument()

        FileOutputStream(pdfFile).use { out ->
            doc.writeTo(out)
        }
        doc.close()

        return pdfFile
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
