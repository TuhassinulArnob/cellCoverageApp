package com.cellscope.drive.ui.rfgraph

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.cellscope.drive.R
import com.cellscope.drive.model.ObservedCell
import java.util.Locale

class CrsShiftDiagramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
    }

    private val boldTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private var cells: List<ObservedCell> = emptyList()

    fun setCells(cells: List<ObservedCell>) {
        this.cells = cells.filter { it.pci != null }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val density = resources.displayMetrics.density

        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val textColor = if (isDark) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")
        val secondaryTextColor = if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")
        val cardBgColor = if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#F1F5F9")
        val cardStrokeColor = if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1")

        textPaint.color = secondaryTextColor
        textPaint.textSize = 10f * density

        boldTextPaint.color = textColor
        boldTextPaint.textSize = 10.5f * density

        headerPaint.textSize = 11.5f * density

        if (cells.isEmpty()) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = secondaryTextColor
                textSize = 11f * density
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("No LTE cells observed with valid PCI.", w / 2f, h / 2f - 6f, emptyPaint)
            canvas.drawText("Start survey or scan active network to view CRS subcarrier shift groups.", w / 2f, h / 2f + 14f, emptyPaint)
            return
        }

        // Divide into 3 columns for mod 0, mod 1, mod 2
        val colMargin = 6f * density
        val colWidth = (w - (colMargin * 4f)) / 3f
        val colTop = 10f * density
        val colBottom = h - 10f * density

        val groupColors = listOf(
            Color.parseColor("#0284C7"), // Group 0 - Sky Blue
            Color.parseColor("#059669"), // Group 1 - Emerald
            Color.parseColor("#7C3AED")  // Group 2 - Purple
        )

        for (groupIndex in 0..2) {
            val left = colMargin + groupIndex * (colWidth + colMargin)
            val right = left + colWidth
            val rect = RectF(left, colTop, right, colBottom)

            // Draw Column Card Background
            bgPaint.color = cardBgColor
            canvas.drawRoundRect(rect, 8f * density, 8f * density, bgPaint)

            strokePaint.color = cardStrokeColor
            canvas.drawRoundRect(rect, 8f * density, 8f * density, strokePaint)

            // Header Banner
            val headerRect = RectF(left, colTop, right, colTop + 26f * density)
            bgPaint.color = groupColors[groupIndex]
            canvas.drawRoundRect(headerRect, 8f * density, 8f * density, bgPaint)
            // Square bottom corners of header
            canvas.drawRect(left, colTop + 14f * density, right, colTop + 26f * density, bgPaint)

            headerPaint.color = Color.WHITE
            canvas.drawText("Shift Group $groupIndex (v_shift=$groupIndex)", (left + right) / 2f, colTop + 17f * density, headerPaint)

            // Find cells in this shift group
            val groupCells = cells.filter { (it.pci ?: -1) % 3 == groupIndex }

            var curY = colTop + 36f * density

            // Check if multiple cells in this group share EARFCN -> Subcarrier Collision Risk!
            val earfcnCounts = groupCells.groupBy { it.earfcn ?: -1 }
            val hasCollisionRisk = earfcnCounts.any { it.key > 0 && it.value.size > 1 }

            if (hasCollisionRisk) {
                // Hazard Tag
                val tagRect = RectF(left + 4f * density, curY, right - 4f * density, curY + 18f * density)
                bgPaint.color = Color.parseColor("#FEE2E2") // Light red
                canvas.drawRoundRect(tagRect, 4f * density, 4f * density, bgPaint)

                val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#DC2626") // Red text
                    textSize = 8.5f * density
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("⚠️ Subcarrier Collision Hazard", (left + right) / 2f, curY + 13f * density, tagPaint)
                curY += 24f * density
            } else if (groupCells.size > 1) {
                // Safe tag
                val tagRect = RectF(left + 4f * density, curY, right - 4f * density, curY + 16f * density)
                bgPaint.color = Color.parseColor("#DCFCE7") // Light green
                canvas.drawRoundRect(tagRect, 4f * density, 4f * density, bgPaint)

                val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#16A34A")
                    textSize = 8.5f * density
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("✓ Multi-Carrier (Clean)", (left + right) / 2f, curY + 12f * density, tagPaint)
                curY += 22f * density
            }

            if (groupCells.isEmpty()) {
                val nonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = secondaryTextColor
                    textSize = 9.5f * density
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("No cells in group", (left + right) / 2f, curY + 20f * density, nonePaint)
            } else {
                for (cell in groupCells.take(4)) {
                    if (curY + 34f * density > colBottom) break

                    val chipRect = RectF(left + 5f * density, curY, right - 5f * density, curY + 32f * density)
                    bgPaint.color = if (cell.isServing) {
                        if (isDark) Color.parseColor("#1E3A8A") else Color.parseColor("#DBEAFE")
                    } else {
                        if (isDark) Color.parseColor("#0F172A") else Color.parseColor("#FFFFFF")
                    }
                    canvas.drawRoundRect(chipRect, 4f * density, 4f * density, bgPaint)

                    strokePaint.color = if (cell.isServing) Color.parseColor("#2563EB") else cardStrokeColor
                    canvas.drawRoundRect(chipRect, 4f * density, 4f * density, strokePaint)

                    // Role & PCI
                    val roleLabel = if (cell.isServing) "SERVING" else "NEIGHBOR"
                    val pciText = "PCI ${cell.pci} ($roleLabel)"
                    boldTextPaint.textSize = 9f * density
                    boldTextPaint.color = if (cell.isServing) Color.parseColor("#2563EB") else textColor
                    canvas.drawText(pciText, left + 8f * density, curY + 12f * density, boldTextPaint)

                    // EARFCN & RSRP
                    val chStr = cell.earfcn?.let { "Ch $it" } ?: "Ch —"
                    val rsrpStr = cell.rsrpDbm?.let { "$it dBm" } ?: "— dBm"
                    val detailsText = "$chStr • $rsrpStr"
                    textPaint.textSize = 8.5f * density
                    canvas.drawText(detailsText, left + 8f * density, curY + 24f * density, textPaint)

                    curY += 36f * density
                }
            }
        }
    }
}
