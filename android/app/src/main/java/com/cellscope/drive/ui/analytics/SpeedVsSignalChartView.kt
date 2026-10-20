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
import java.util.Locale
import kotlin.math.max

/**
 * Graph 6: Vehicle Speed versus RSRP [MEASURED] (P4).
 *
 * Engineering Clarification:
 * Plots observed scalar GPS speed (km/h) against serving-cell RSRP (dBm).
 * Explicitly states that handset speed is NOT a direct Doppler shift measurement.
 * Theoretical Doppler requires carrier frequency fc and relative radial velocity vr.
 */
class SpeedVsSignalChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class SpeedPoint(val speedKmh: Double, val rsrpDbm: Int)
    private var points: List<SpeedPoint> = emptyList()
    private var maxSpeedKmh: Double = 0.0

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
        color = Color.argb(160, 245, 158, 11) // Amber / Orange
        style = Paint.Style.FILL
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 25f
        isFakeBoldText = true
    }

    private val disclaimerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textSize = 17f
    }

    private val trendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8") // Sky Blue trend
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    fun setData(measurements: List<LteMeasurementEntity>) {
        val valid = measurements
            .filter { it.speedKmh != null && it.rsrpDbm != null && it.speedKmh!! >= 0f }
            .map { SpeedPoint(it.speedKmh!!.toDouble(), it.rsrpDbm!!) }

        points = valid
        maxSpeedKmh = if (valid.isNotEmpty()) max(10.0, valid.maxOf { it.speedKmh }) else 0.0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (points.size < 5 || maxSpeedKmh < 1.0) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#64748B")
                textSize = 28f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Speed telemetry stationary or unavailable (< 1 km/h)", w / 2f, h / 2f, emptyPaint)
            return
        }

        val paddingLeft = 85f
        val paddingRight = 40f
        val paddingTop = 60f
        val paddingBottom = 55f

        val plotW = w - paddingLeft - paddingRight
        val plotH = h - paddingTop - paddingBottom

        canvas.drawText("Vehicle Speed vs. RSRP [MEASURED] (n=${points.size}, Max: ${String.format(Locale.US, "%.1f km/h", maxSpeedKmh)})", paddingLeft, 32f, titlePaint)
        canvas.drawText("Scalar GPS speed • Not Doppler (Doppler requires radial vr & carrier frequency)", paddingLeft, 50f, disclaimerPaint)

        val minRsrp = -130.0
        val maxRsrp = -60.0

        // Y-Axis Gridlines & Labels (RSRP)
        val ySteps = 4
        for (i in 0..ySteps) {
            val ratio = i.toFloat() / ySteps
            val y = paddingTop + plotH * (1f - ratio)
            canvas.drawLine(paddingLeft, y, w - paddingRight, y, gridPaint)

            val rsrpVal = minRsrp + ratio * (maxRsrp - minRsrp)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("${rsrpVal.toInt()}", paddingLeft - 8f, y + 6f, textPaint)
        }

        // X-Axis Gridlines & Labels (Speed)
        val xSteps = 4
        for (i in 0..xSteps) {
            val ratio = i.toFloat() / xSteps
            val x = paddingLeft + plotW * ratio
            canvas.drawLine(x, paddingTop, x, paddingTop + plotH, gridPaint)

            val spdVal = ratio * maxSpeedKmh
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(String.format(Locale.US, "%.0f", spdVal), x, h - 20f, textPaint)
        }
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Vehicle Speed (km/h)", paddingLeft + plotW / 2f, h - 2f, textPaint)

        // Axes Box
        canvas.drawLine(paddingLeft, paddingTop, paddingLeft, paddingTop + plotH, axisPaint)
        canvas.drawLine(paddingLeft, paddingTop + plotH, w - paddingRight, paddingTop + plotH, axisPaint)

        // Scatter Points
        for (pt in points) {
            val xNorm = (pt.speedKmh / maxSpeedKmh).coerceIn(0.0, 1.0)
            val yNorm = ((pt.rsrpDbm - minRsrp) / (maxRsrp - minRsrp)).coerceIn(0.0, 1.0)

            val x = paddingLeft + (xNorm * plotW).toFloat()
            val y = paddingTop + (plotH * (1.0 - yNorm)).toFloat()

            canvas.drawCircle(x, y, 4.5f, pointPaint)
        }

        // Binned Speed Trend (if n >= 10)
        if (points.size >= 10) {
            val binCount = 4
            val binSize = maxSpeedKmh / binCount
            val trendPath = Path()
            var trendStarted = false

            for (b in 0 until binCount) {
                val binMin = b * binSize
                val binMax = (b + 1) * binSize
                val binPoints = points.filter { it.speedKmh >= binMin && (if (b == binCount - 1) it.speedKmh <= binMax else it.speedKmh < binMax) }
                if (binPoints.isNotEmpty()) {
                    val medianRsrp = binPoints.map { it.rsrpDbm }.sorted().let { s ->
                        if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
                    }
                    val binMidSpeed = (binMin + binMax) / 2.0
                    val xNorm = (binMidSpeed / maxSpeedKmh).coerceIn(0.0, 1.0)
                    val yNorm = ((medianRsrp - minRsrp) / (maxRsrp - minRsrp)).coerceIn(0.0, 1.0)
                    val tx = paddingLeft + (xNorm * plotW).toFloat()
                    val ty = paddingTop + (plotH * (1.0 - yNorm)).toFloat()

                    if (!trendStarted) {
                        trendPath.moveTo(tx, ty)
                        trendStarted = true
                    } else {
                        trendPath.lineTo(tx, ty)
                    }
                }
            }
            if (trendStarted) {
                canvas.drawPath(trendPath, trendPaint)
            }
        }
    }
}
