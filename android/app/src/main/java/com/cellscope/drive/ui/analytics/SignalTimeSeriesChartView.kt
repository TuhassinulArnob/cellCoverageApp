package com.cellscope.drive.ui.analytics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.util.TelecomTimeUtils
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Graph 1: Signal vs Time Chart.
 * Plots LTE RSRP (-140 to -60 dBm) on primary left axis and RSRQ (-20 to -3 dB) on secondary right axis.
 * Units and scales are strictly separated to prevent misleading common-scale plotting.
 */
class SignalTimeSeriesChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var measurements: List<LteMeasurementEntity> = emptyList()

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
        textSize = 24f
    }

    private val rsrpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676") // Green
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
    }

    private val rsrqPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8") // Sky Blue
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        isFakeBoldText = true
    }

    fun setData(newMeasurements: List<LteMeasurementEntity>) {
        this.measurements = newMeasurements.sortedBy { it.timestampMs }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (measurements.size < 2) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#64748B")
                textSize = 30f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Not enough valid measurements for Signal vs Time plot", w / 2f, h / 2f, emptyPaint)
            return
        }

        val paddingLeft = 90f
        val paddingRight = 80f
        val paddingTop = 60f
        val paddingBottom = 60f

        val plotW = w - paddingLeft - paddingRight
        val plotH = h - paddingTop - paddingBottom

        // Draw Legend at top
        legendPaint.color = Color.parseColor("#00E676")
        canvas.drawText("― RSRP (dBm, left)", paddingLeft, 36f, legendPaint)
        legendPaint.color = Color.parseColor("#38BDF8")
        canvas.drawText("- - RSRQ (dB, right)", paddingLeft + 300f, 36f, legendPaint)

        // Axes bounds
        val minRsrp = -130.0
        val maxRsrp = -60.0
        val minRsrq = -20.0
        val maxRsrq = -3.0

        val firstTime = measurements.first().timestampMs
        val lastTime = measurements.last().timestampMs
        val timeSpan = max(1L, lastTime - firstTime).toDouble()

        // Horizontal Gridlines & Left/Right Axis Labels
        val gridSteps = 4
        for (i in 0..gridSteps) {
            val ratio = i.toFloat() / gridSteps
            val y = paddingTop + plotH * (1f - ratio)
            canvas.drawLine(paddingLeft, y, w - paddingRight, y, gridPaint)

            // RSRP label (Left)
            val rsrpVal = minRsrp + ratio * (maxRsrp - minRsrp)
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.color = Color.parseColor("#00E676")
            canvas.drawText("${rsrpVal.toInt()}", paddingLeft - 10f, y + 8f, textPaint)

            // RSRQ label (Right)
            val rsrqVal = minRsrq + ratio * (maxRsrq - minRsrq)
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = Color.parseColor("#38BDF8")
            canvas.drawText("${rsrqVal.toInt()}", w - paddingRight + 10f, y + 8f, textPaint)
        }

        // Draw Axes Box
        canvas.drawLine(paddingLeft, paddingTop, paddingLeft, paddingTop + plotH, axisPaint)
        canvas.drawLine(w - paddingRight, paddingTop, w - paddingRight, paddingTop + plotH, axisPaint)
        canvas.drawLine(paddingLeft, paddingTop + plotH, w - paddingRight, paddingTop + plotH, axisPaint)

        // Draw Time labels at bottom
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.parseColor("#94A3B8")
        val startTimeStr = TelecomTimeUtils.isoToBdtTimeOnly(measurements.first().timestampIso)
        val endTimeStr = TelecomTimeUtils.isoToBdtTimeOnly(measurements.last().timestampIso)
        canvas.drawText(startTimeStr, paddingLeft, h - 16f, textPaint)
        canvas.drawText(endTimeStr, w - paddingRight, h - 16f, textPaint)

        // Build Paths for RSRP and RSRQ
        val rsrpPath = Path()
        val rsrqPath = Path()
        var hasRsrpStart = false
        var hasRsrqStart = false

        for (m in measurements) {
            val tNorm = ((m.timestampMs - firstTime).toDouble() / timeSpan).coerceIn(0.0, 1.0)
            val x = paddingLeft + (tNorm * plotW).toFloat()

            m.rsrpDbm?.let { rsrp ->
                val rsrpNorm = ((rsrp - minRsrp) / (maxRsrp - minRsrp)).coerceIn(0.0, 1.0)
                val y = paddingTop + (plotH * (1.0 - rsrpNorm)).toFloat()
                if (!hasRsrpStart) {
                    rsrpPath.moveTo(x, y)
                    hasRsrpStart = true
                } else {
                    rsrpPath.lineTo(x, y)
                }
            }

            m.rsrqDb?.let { rsrq ->
                val rsrqNorm = ((rsrq - minRsrq) / (maxRsrq - minRsrq)).coerceIn(0.0, 1.0)
                val y = paddingTop + (plotH * (1.0 - rsrqNorm)).toFloat()
                if (!hasRsrqStart) {
                    rsrqPath.moveTo(x, y)
                    hasRsrqStart = true
                } else {
                    rsrqPath.lineTo(x, y)
                }
            }
        }

        if (hasRsrpStart) canvas.drawPath(rsrpPath, rsrpPaint)
        if (hasRsrqStart) canvas.drawPath(rsrqPath, rsrqPaint)
    }
}
