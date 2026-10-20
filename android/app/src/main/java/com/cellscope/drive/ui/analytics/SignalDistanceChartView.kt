package com.cellscope.drive.ui.analytics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.location.GpsJitterFilter
import java.util.Locale
import kotlin.math.max

/**
 * Graph 2: Signal versus Route Distance.
 * Plots LTE RSRP against cumulative route distance in kilometres from valid ordered GPS samples.
 */
class SignalDistanceChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class DistancePoint(val distKm: Double, val rsrpDbm: Int)
    private var distancePoints: List<DistancePoint> = emptyList()
    private var totalDistanceKm: Double = 0.0

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

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Cyan
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
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

    fun setData(measurements: List<LteMeasurementEntity>) {
        val valid = measurements
            .filter { it.latitude != null && it.longitude != null && it.isGpsValid && it.rsrpDbm != null }
            .sortedBy { it.timestampMs }

        if (valid.size < 2) {
            distancePoints = emptyList()
            totalDistanceKm = 0.0
            invalidate()
            return
        }

        val points = mutableListOf<DistancePoint>()
        var cumDistM = 0.0
        points.add(DistancePoint(0.0, valid.first().rsrpDbm!!))

        for (i in 1 until valid.size) {
            val prev = valid[i - 1]
            val curr = valid[i]
            val dM = GpsJitterFilter.computeHaversineMeters(
                prev.latitude!!, prev.longitude!!,
                curr.latitude!!, curr.longitude!!
            )
            // Filter jitter jumps (> 500m per sec or sub-1m noise)
            if (dM in 1.0..500.0) {
                cumDistM += dM
            }
            points.add(DistancePoint(cumDistM / 1000.0, curr.rsrpDbm!!))
        }

        this.totalDistanceKm = cumDistM / 1000.0
        this.distancePoints = points
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (distancePoints.size < 2 || totalDistanceKm < 0.05) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#64748B")
                textSize = 30f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("No mobile drive distance recorded (Stationary or non-GPS log)", w / 2f, h / 2f, emptyPaint)
            return
        }

        val paddingLeft = 90f
        val paddingRight = 40f
        val paddingTop = 60f
        val paddingBottom = 60f

        val plotW = w - paddingLeft - paddingRight
        val plotH = h - paddingTop - paddingBottom

        // Title and disclaimer
        canvas.drawText("Signal vs. Route Distance [DERIVED] (${String.format(Locale.US, "%.2f km", totalDistanceKm)})", paddingLeft, 32f, titlePaint)
        canvas.drawText("Cumulative drive route distance • Not physical distance to tower", paddingLeft, 50f, subtitlePaint)

        val minRsrp = -130.0
        val maxRsrp = -60.0

        // Gridlines & RSRP Y-Axis
        val gridSteps = 4
        for (i in 0..gridSteps) {
            val ratio = i.toFloat() / gridSteps
            val y = paddingTop + plotH * (1f - ratio)
            canvas.drawLine(paddingLeft, y, w - paddingRight, y, gridPaint)

            val rsrpVal = minRsrp + ratio * (maxRsrp - minRsrp)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("${rsrpVal.toInt()}", paddingLeft - 10f, y + 8f, textPaint)
        }

        // Axes Box
        canvas.drawLine(paddingLeft, paddingTop, paddingLeft, paddingTop + plotH, axisPaint)
        canvas.drawLine(paddingLeft, paddingTop + plotH, w - paddingRight, paddingTop + plotH, axisPaint)

        // X-Axis distance labels
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("0.0 km", paddingLeft, h - 16f, textPaint)
        canvas.drawText(String.format(Locale.US, "%.1f km", totalDistanceKm / 2.0), paddingLeft + plotW / 2f, h - 16f, textPaint)
        canvas.drawText(String.format(Locale.US, "%.1f km", totalDistanceKm), w - paddingRight, h - 16f, textPaint)

        // Plot Path
        val path = Path()
        var started = false

        for (pt in distancePoints) {
            val xNorm = (pt.distKm / totalDistanceKm).coerceIn(0.0, 1.0)
            val yNorm = ((pt.rsrpDbm - minRsrp) / (maxRsrp - minRsrp)).coerceIn(0.0, 1.0)

            val x = paddingLeft + (xNorm * plotW).toFloat()
            val y = paddingTop + (plotH * (1.0 - yNorm)).toFloat()

            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }

        canvas.drawPath(path, linePaint)
    }
}
