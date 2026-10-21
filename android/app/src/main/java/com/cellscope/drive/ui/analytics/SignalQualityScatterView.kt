package com.cellscope.drive.ui.analytics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.cellscope.drive.data.entity.LteMeasurementEntity
import java.util.Locale
import kotlin.math.sqrt

/**
 * Graph 4: Signal Quality Relationship & Pearson Correlation (P4).
 *
 * Engineering Features:
 * - Scatter plot of LTE RSRP (X-axis, -130 to -60 dBm) vs RSRQ (Y-axis, -20 to -3 dB).
 * - Mathematical Pearson Correlation Coefficient (r) calculation.
 * - Missing sample tracking and baseband ceiling (-44 dBm clamping) bias detection.
 * - Shaded RF regions: High Interference / Cell Load vs Weak Coverage.
 * - Explicit note: Correlation does not establish causation.
 */
class SignalQualityScatterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class ScatterPoint(val rsrp: Int, val rsrq: Int, val rat: String, val isSuspectClamp: Boolean = false)
    private var points: List<ScatterPoint> = emptyList()
    private var missingCount: Int = 0
    private var pearsonR: Double? = null
    private var clampCount: Int = 0

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 20f
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 56, 189, 248) // Sky Blue
        style = Paint.Style.FILL
    }

    private val clampedPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 239, 68, 68) // Red for -44 dBm clamped ceiling
        style = Paint.Style.FILL
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 25f
        isFakeBoldText = true
    }

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 17f
    }

    private val warningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        textSize = 17f
        isFakeBoldText = true
    }

    private val shadedRegionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setData(measurements: List<LteMeasurementEntity>) {
        val valid = measurements
            .filter { it.rsrpDbm != null && it.rsrqDb != null }
            .map {
                val isSuspect = com.cellscope.drive.analytics.RadioQualityEngine.isSuspectBasebandClamp(
                    it.rsrpDbm, it.rsrqDb, it.rssiDbm, it.networkType, it.servingRat
                )
                ScatterPoint(it.rsrpDbm!!, it.rsrqDb!!, it.servingRat ?: it.networkGen ?: "4G", isSuspect)
            }

        points = valid
        missingCount = measurements.size - valid.size
        clampCount = valid.count { it.isSuspectClamp }

        // Compute Pearson's correlation coefficient r
        if (valid.size >= 5) {
            val meanX = valid.map { it.rsrp.toDouble() }.average()
            val meanY = valid.map { it.rsrq.toDouble() }.average()

            var num = 0.0
            var denX = 0.0
            var denY = 0.0
            for (p in valid) {
                val dx = p.rsrp - meanX
                val dy = p.rsrq - meanY
                num += dx * dy
                denX += dx * dx
                denY += dy * dy
            }
            val denom = sqrt(denX * denY)
            pearsonR = if (denom > 1e-6) (num / denom) else null
        } else {
            pearsonR = null
        }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (points.size < 5) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#64748B")
                textSize = 28f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Not enough paired RSRP/RSRQ samples for correlation scatter", w / 2f, h / 2f, emptyPaint)
            return
        }

        val paddingLeft = 85f
        val paddingRight = 40f
        val paddingTop = 60f
        val paddingBottom = 55f

        val plotW = w - paddingLeft - paddingRight
        val plotH = h - paddingTop - paddingBottom

        val rStr = pearsonR?.let { String.format(Locale.US, "r = %.2f (DERIVED)", it) } ?: "r = N/A"
        canvas.drawText("RSRP vs. RSRQ Scatter Correlation (n=${points.size}, $rStr)", paddingLeft, 32f, titlePaint)

        val clampPct = (clampCount * 100.0 / points.size)
        if (clampPct > 35.0) {
            canvas.drawText("⚠️ ${clampPct.toInt()}% points clamped at -44 dBm modem ceiling • Correlation biased", paddingLeft, 50f, warningPaint)
        } else {
            canvas.drawText("Correlation does not establish causation • Reflects link load & interference", paddingLeft, 50f, subtitlePaint)
        }

        val minRsrp = -130.0
        val maxRsrp = -60.0
        val minRsrq = -20.0
        val maxRsrq = -3.0

        // Shaded Region 1: Weak Coverage Region (RSRP < -105 dBm)
        val weakRsrpX = paddingLeft + (((-105.0 - minRsrp) / (maxRsrp - minRsrp)) * plotW).toFloat()
        shadedRegionPaint.color = Color.argb(20, 245, 158, 11) // Amber tint
        canvas.drawRect(RectF(paddingLeft, paddingTop, weakRsrpX, paddingTop + plotH), shadedRegionPaint)

        // Shaded Region 2: High Interference / Load (RSRP >= -95 dBm && RSRQ <= -14 dB)
        val interRsrpX = paddingLeft + (((-95.0 - minRsrp) / (maxRsrp - minRsrp)) * plotW).toFloat()
        val interRsrqY = paddingTop + (plotH * (1.0 - ((-14.0 - minRsrq) / (maxRsrq - minRsrq)))).toFloat()
        shadedRegionPaint.color = Color.argb(22, 239, 68, 68) // Red tint
        canvas.drawRect(RectF(interRsrpX, interRsrqY, w - paddingRight, paddingTop + plotH), shadedRegionPaint)

        // Y-Axis Gridlines & Labels (RSRQ)
        val ySteps = 4
        for (i in 0..ySteps) {
            val ratio = i.toFloat() / ySteps
            val y = paddingTop + plotH * (1f - ratio)
            canvas.drawLine(paddingLeft, y, w - paddingRight, y, gridPaint)

            val rsrqVal = minRsrq + ratio * (maxRsrq - minRsrq)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("${rsrqVal.toInt()} dB", paddingLeft - 8f, y + 6f, textPaint)
        }

        // X-Axis Gridlines & Labels (RSRP)
        val xSteps = 4
        for (i in 0..xSteps) {
            val ratio = i.toFloat() / xSteps
            val x = paddingLeft + plotW * ratio
            canvas.drawLine(x, paddingTop, x, paddingTop + plotH, gridPaint)

            val rsrpVal = minRsrp + ratio * (maxRsrp - minRsrp)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("${rsrpVal.toInt()}", x, h - 20f, textPaint)
        }
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Serving RSRP (dBm)", paddingLeft + plotW / 2f, h - 2f, textPaint)

        // Axes Box
        canvas.drawLine(paddingLeft, paddingTop, paddingLeft, paddingTop + plotH, axisPaint)
        canvas.drawLine(paddingLeft, paddingTop + plotH, w - paddingRight, paddingTop + plotH, axisPaint)

        // Scatter Points
        for (pt in points) {
            val xNorm = ((pt.rsrp - minRsrp) / (maxRsrp - minRsrp)).coerceIn(0.0, 1.0)
            val yNorm = ((pt.rsrq - minRsrq) / (maxRsrq - minRsrq)).coerceIn(0.0, 1.0)

            val x = paddingLeft + (xNorm * plotW).toFloat()
            val y = paddingTop + (plotH * (1.0 - yNorm)).toFloat()

            if (pt.isSuspectClamp) {
                canvas.drawCircle(x, y, 5f, clampedPointPaint)
            } else {
                canvas.drawCircle(x, y, 4.5f, pointPaint)
            }
        }
    }
}
