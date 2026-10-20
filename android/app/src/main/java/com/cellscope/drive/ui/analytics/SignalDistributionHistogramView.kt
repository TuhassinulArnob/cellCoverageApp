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
 * Graph 3: Signal Distribution Histogram.
 * Renders sample count and percentage distributions across standardized 3GPP RSRP bins.
 */
class SignalDistributionHistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class BinData(val label: String, val rangeLabel: String, val count: Int, val color: Int)
    private var bins: List<BinData> = emptyList()
    private var totalSamples: Int = 0

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        isFakeBoldText = true
    }

    fun setData(measurements: List<LteMeasurementEntity>) {
        val rsrpList = measurements.mapNotNull { it.rsrpDbm }
        totalSamples = rsrpList.size

        if (totalSamples == 0) {
            bins = emptyList()
            invalidate()
            return
        }

        val bExc = rsrpList.count { it >= -80 }
        val bGood = rsrpList.count { it in -90..-81 }
        val bFair = rsrpList.count { it in -100..-91 }
        val bPoor = rsrpList.count { it in -110..-101 }
        val bOutage = rsrpList.count { it < -110 }

        bins = listOf(
            BinData("Outage", "< -110", bOutage, Color.parseColor("#B71C1C")),
            BinData("Poor", "-110..-101", bPoor, Color.parseColor("#D50000")),
            BinData("Fair", "-100..-91", bFair, Color.parseColor("#FFD600")),
            BinData("Good", "-90..-81", bGood, Color.parseColor("#76FF03")),
            BinData("Excellent", "≥ -80", bExc, Color.parseColor("#00E676"))
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (totalSamples == 0 || bins.isEmpty()) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#64748B")
                textSize = 30f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("No valid RSRP measurements for distribution histogram", w / 2f, h / 2f, emptyPaint)
            return
        }

        canvas.drawText("RSRP Signal Distribution ($totalSamples valid samples)", 20f, 36f, titlePaint)

        val paddingLeft = 30f
        val paddingRight = 30f
        val paddingTop = 60f
        val paddingBottom = 70f

        val plotW = w - paddingLeft - paddingRight
        val plotH = h - paddingTop - paddingBottom

        val maxCount = max(1, bins.maxOf { it.count })
        val binWidth = (plotW / bins.size) * 0.75f
        val binSpacing = (plotW / bins.size) * 0.25f

        for (i in bins.indices) {
            val bin = bins[i]
            val pct = (bin.count * 100.0) / totalSamples
            val barH = (bin.count.toFloat() / maxCount) * plotH

            val left = paddingLeft + i * (binWidth + binSpacing) + (binSpacing / 2f)
            val top = paddingTop + (plotH - barH)
            val right = left + binWidth
            val bottom = paddingTop + plotH

            barPaint.color = bin.color
            canvas.drawRoundRect(RectF(left, top, right, bottom), 8f, 8f, barPaint)

            // Value & Percentage on top of bar
            textPaint.color = Color.WHITE
            textPaint.textSize = 20f
            val countStr = "${bin.count}"
            val pctStr = String.format(Locale.US, "%.1f%%", pct)
            canvas.drawText(countStr, left + binWidth / 2f, max(top - 20f, 20f), textPaint)
            canvas.drawText(pctStr, left + binWidth / 2f, max(top - 4f, 36f), textPaint)

            // Labels below bar
            textPaint.color = Color.parseColor("#E2E8F0")
            textPaint.textSize = 21f
            canvas.drawText(bin.label, left + binWidth / 2f, bottom + 24f, textPaint)

            textPaint.color = Color.parseColor("#94A3B8")
            textPaint.textSize = 18f
            canvas.drawText(bin.rangeLabel, left + binWidth / 2f, bottom + 46f, textPaint)
        }
    }
}
