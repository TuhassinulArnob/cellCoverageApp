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

/**
 * Graph 8: Coverage Quality Breakdown.
 * Horizontal stacked bar showing Excellent, Good, Fair, Poor percentages for the selected dataset.
 */
class CoverageQualityBreakdownView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var cExc = 0
    private var cGood = 0
    private var cFair = 0
    private var cPoor = 0
    private var totalValid = 0

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        isFakeBoldText = true
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        textSize = 20f
    }

    fun setData(measurements: List<LteMeasurementEntity>) {
        val rsrpList = measurements.mapNotNull { it.rsrpDbm }
        totalValid = rsrpList.size

        if (totalValid == 0) {
            cExc = 0; cGood = 0; cFair = 0; cPoor = 0
            invalidate()
            return
        }

        cExc = rsrpList.count { it >= -80 }
        cGood = rsrpList.count { it in -90..-81 }
        cFair = rsrpList.count { it in -100..-91 }
        cPoor = rsrpList.count { it < -100 }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (totalValid == 0) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#64748B")
                textSize = 30f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("No valid RSRP measurements for quality breakdown", w / 2f, h / 2f, emptyPaint)
            return
        }

        canvas.drawText("3GPP TS 36.133 Coverage Quality Breakdown", 20f, 36f, titlePaint)

        val paddingLeft = 20f
        val paddingRight = 20f
        val barTop = 56f
        val barHeight = 36f
        val plotW = w - paddingLeft - paddingRight

        val pExc = (cExc.toFloat() / totalValid)
        val pGood = (cGood.toFloat() / totalValid)
        val pFair = (cFair.toFloat() / totalValid)
        val pPoor = (cPoor.toFloat() / totalValid)

        var curX = paddingLeft

        // Draw segmented stacked bar
        val wExc = plotW * pExc
        val wGood = plotW * pGood
        val wFair = plotW * pFair
        val wPoor = plotW * pPoor

        barPaint.color = Color.parseColor("#00E676")
        canvas.drawRect(curX, barTop, curX + wExc, barTop + barHeight, barPaint)
        curX += wExc

        barPaint.color = Color.parseColor("#76FF03")
        canvas.drawRect(curX, barTop, curX + wGood, barTop + barHeight, barPaint)
        curX += wGood

        barPaint.color = Color.parseColor("#FFD600")
        canvas.drawRect(curX, barTop, curX + wFair, barTop + barHeight, barPaint)
        curX += wFair

        barPaint.color = Color.parseColor("#D50000")
        canvas.drawRect(curX, barTop, curX + wPoor, barTop + barHeight, barPaint)

        // Draw Legend below bar
        val legendY = barTop + barHeight + 36f
        val colW = plotW / 4f

        val items = listOf(
            Triple("Exc (≥-80)", String.format(Locale.US, "%.1f%% (%d)", pExc * 100f, cExc), Color.parseColor("#00E676")),
            Triple("Good (-90..-81)", String.format(Locale.US, "%.1f%% (%d)", pGood * 100f, cGood), Color.parseColor("#76FF03")),
            Triple("Fair (-100..-91)", String.format(Locale.US, "%.1f%% (%d)", pFair * 100f, cFair), Color.parseColor("#FFD600")),
            Triple("Poor (<-100)", String.format(Locale.US, "%.1f%% (%d)", pPoor * 100f, cPoor), Color.parseColor("#D50000"))
        )

        for (i in items.indices) {
            val item = items[i]
            val x = paddingLeft + i * colW
            barPaint.color = item.third
            canvas.drawRoundRect(RectF(x, legendY - 14f, x + 16f, legendY + 2f), 4f, 4f, barPaint)

            textPaint.color = Color.parseColor("#94A3B8")
            textPaint.textSize = 19f
            canvas.drawText(item.first, x + 24f, legendY - 2f, textPaint)

            textPaint.color = Color.WHITE
            textPaint.textSize = 21f
            canvas.drawText(item.second, x + 24f, legendY + 22f, textPaint)
        }
    }
}
