package com.cellscope.drive.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.cellscope.drive.analytics.CoveragePlanner
import com.cellscope.drive.analytics.HandoverAnalyzer
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.data.entity.LteMeasurementEntity
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
            canvas.drawText("CellScope LTE Field Assessment • Confidential", MARGIN_X, 815f, footerPaint)
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
        measurements: List<LteMeasurementEntity>
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
        mgr.canvas.drawText("Automated Native Android Cellular Field Assessment", MARGIN_X, mgr.y, bodyPaint)
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
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(session.startTimeMs))
        val avgRsrpStr = session.avgRsrpDbm?.let { String.format(Locale.US, "%.1f dBm", it) } ?: "— dBm"
        val maxSpeedStr = String.format(Locale.US, "%.1f km/h", session.maxSpeedKmh)

        val summaryItems = listOf(
            Pair("Session Name:", session.sessionName),
            Pair("Start Timestamp:", dateStr),
            Pair("Duration:", durationStr),
            Pair("Total Distance:", distKmStr),
            Pair("Sample Count:", "${measurements.size} measurements"),
            Pair("Mean RSRP:", avgRsrpStr),
            Pair("Max Speed:", maxSpeedStr),
            Pair("Serving Cells Observed:", "${handoverSummary.dwellStats.size} distinct PCIs")
        )

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
        // 6. Methodology & Hardware Disclaimer
        // ==========================================
        mgr.ensureSpace(40f)
        val disclaimerPaint = Paint().apply {
            color = Color.parseColor("#757575")
            textSize = 7.5f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        mgr.canvas.drawText("Methodology & Device Limitations Note:", MARGIN_X, mgr.y, disclaimerPaint)
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
