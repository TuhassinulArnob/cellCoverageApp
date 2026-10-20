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
import kotlin.math.max

/**
 * Graph 7: RAT and Channel Distribution.
 * Shows sample distribution across verified radio access technologies (5G, 4G, 3G, 2G).
 */
class RatChannelDistributionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class RatData(val ratName: String, val count: Int, val color: Int)
    private var ratList: List<RatData> = emptyList()
    private var totalCount: Int = 0

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        isFakeBoldText = true
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        textSize = 22f
    }

    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 20f
        textAlign = Paint.Align.RIGHT
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setData(measurements: List<LteMeasurementEntity>) {
        totalCount = measurements.size
        if (totalCount == 0) {
            ratList = emptyList()
            invalidate()
            return
        }

        var c5g = 0
        var c4g = 0
        var c3g = 0
        var c2g = 0

        for (m in measurements) {
            val rat = (m.servingRat ?: m.networkGen ?: m.networkType).uppercase(Locale.US)
            when {
                rat.contains("5G") || rat.contains("NR") -> c5g++
                rat.contains("4G") || rat.contains("LTE") -> c4g++
                rat.contains("3G") || rat.contains("UMTS") || rat.contains("WCDMA") -> c3g++
                rat.contains("2G") || rat.contains("GSM") || rat.contains("EDGE") -> c2g++
                else -> c4g++ // Default to LTE if ambiguous
            }
        }

        ratList = listOf(
            RatData("5G NR", c5g, Color.parseColor("#7C4DFF")),
            RatData("4G LTE", c4g, Color.parseColor("#0284C7")),
            RatData("3G UMTS", c3g, Color.parseColor("#F59E0B")),
            RatData("2G GSM", c2g, Color.parseColor("#795548"))
        ).filter { it.count > 0 }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (totalCount == 0 || ratList.isEmpty()) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#64748B")
                textSize = 30f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("No RAT telemetry observations recorded", w / 2f, h / 2f, emptyPaint)
            return
        }

        canvas.drawText("Observed Radio Access Technology (RAT) Breakdown", 20f, 36f, titlePaint)

        val paddingLeft = 120f
        val paddingRight = 100f
        val startY = 70f
        val rowHeight = (h - startY - 20f) / ratList.size.coerceAtLeast(1)
        val plotW = w - paddingLeft - paddingRight

        for (i in ratList.indices) {
            val item = ratList[i]
            val pct = (item.count * 100.0) / totalCount
            val y = startY + i * rowHeight

            // Label
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(item.ratName, paddingLeft - 15f, y + 24f, textPaint)

            // Bar Background
            barPaint.color = Color.parseColor("#1E293B")
            val barRectBg = RectF(paddingLeft, y + 4f, paddingLeft + plotW, y + 32f)
            canvas.drawRoundRect(barRectBg, 6f, 6f, barPaint)

            // Bar Progress
            val barW = (plotW * (item.count.toFloat() / totalCount)).coerceAtLeast(8f)
            barPaint.color = item.color
            val barRect = RectF(paddingLeft, y + 4f, paddingLeft + barW, y + 32f)
            canvas.drawRoundRect(barRect, 6f, 6f, barPaint)

            // Count & Percentage
            countPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(String.format(Locale.US, "%.1f%% (%d)", pct, item.count), paddingLeft + plotW + 12f, y + 24f, countPaint)
        }
    }
}
