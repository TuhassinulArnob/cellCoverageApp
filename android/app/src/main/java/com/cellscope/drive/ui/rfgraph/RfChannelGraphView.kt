package com.cellscope.drive.ui.rfgraph

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.cellscope.drive.R
import com.cellscope.drive.model.ObservedCell
import com.cellscope.drive.model.ServingStatus
import com.cellscope.drive.telephony.CellCarrierInfo
import com.cellscope.drive.telephony.CellularBandResolver
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Simplified Cell Explorer RF Channel Frequency Graph.
 *
 * Telecom Requirements:
 * 1. At most two legend entries: "Serving Cell" and "Unconfirmed Neighbors".
 * 2. Serving cell derived from authoritative status, not raw PCI alone.
 * 3. All non-verified neighbor observations classified as "Unconfirmed Neighbors".
 * 4. No persistent pile of overlapping PCI labels — clean peak dots with selection callout.
 * 5. Selected cell inspection panel for detailed metrics.
 * 6. Explicit 3GPP nominal channel allocation disclaimer (not measured baseband spectral power).
 * 7. Graceful empty states when no neighbors or no cells are available.
 */
class RfChannelGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        textAlign = Paint.Align.CENTER
    }

    private val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val servingFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val servingStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.8f * resources.displayMetrics.density
    }

    private val neighborFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val neighborStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    private val selectedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        color = Color.parseColor("#F59E0B") // Amber
    }

    private val selectedDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F59E0B")
    }

    private val leaderLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * resources.displayMetrics.density
        color = Color.parseColor("#F59E0B")
    }

    private val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var observedCells: List<ObservedCell> = emptyList()
    private var resolvedCarriers: List<Pair<ObservedCell, CellCarrierInfo>> = emptyList()
    var selectedCell: ObservedCell? = null
        private set

    var onCellSelectedListener: ((ObservedCell, CellCarrierInfo) -> Unit)? = null
    var filterBand: Int? = null
        set(value) {
            field = value
            recomputeCarriers()
            invalidate()
        }

    // Zoom and pan
    private var zoomScale = 1.0f
    private var panOffsetMhz = 0.0
    private var lastTouchX = 0f
    private var isDragging = false

    // Hit testing areas for interactive tapping
    private val hitAreas = mutableListOf<Pair<RectF, Pair<ObservedCell, CellCarrierInfo>>>()

    private val minDb = -140f
    private val maxDb = -40f

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomScale = (zoomScale * detector.scaleFactor).coerceIn(1.0f, 6.0f)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomScale = 1.0f
            panOffsetMhz = 0.0
            invalidate()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val tx = e.x
            val ty = e.y
            val clicked = hitAreas.firstOrNull { it.first.contains(tx, ty) }
            if (clicked != null) {
                val (cell, carrier) = clicked.second
                selectedCell = cell
                invalidate()
                onCellSelectedListener?.invoke(cell, carrier)
                return true
            }
            return false
        }
    })

    fun setCells(cells: List<ObservedCell>) {
        this.observedCells = cells
        recomputeCarriers()
        // If no selected cell or selected cell no longer present, default to serving cell
        if (selectedCell == null || observedCells.none { isSameCell(it, selectedCell!!) }) {
            selectedCell = observedCells.firstOrNull { isCellAuthoritativeServing(it) }
        }
        selectedCell?.let { sel ->
            resolvedCarriers.firstOrNull { isSameCell(it.first, sel) }?.let { pair ->
                onCellSelectedListener?.invoke(pair.first, pair.second)
            }
        }
        invalidate()
    }

    private fun isSameCell(a: ObservedCell, b: ObservedCell): Boolean {
        if (a.pci != b.pci) return false
        val aChannel = a.earfcn ?: a.nrArfcn
        val bChannel = b.earfcn ?: b.nrArfcn
        return aChannel == null || bChannel == null || aChannel == bChannel
    }

    /**
     * Authoritative serving cell determination.
     * Evaluates isServing and servingStatus enum; does not rely on PCI alone.
     */
    private fun isCellAuthoritativeServing(cell: ObservedCell): Boolean {
        return cell.isServing && cell.isConfirmedServing
    }

    private fun recomputeCarriers() {
        val resolved = observedCells.mapNotNull { cell ->
            val info = CellularBandResolver.resolve(cell)
            if (info != null) Pair(cell, info) else null
        }

        resolvedCarriers = if (filterBand != null) {
            resolved.filter { it.second.bandNumber == filterBand }
        } else {
            resolved
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitAreas.clear()

        val w = width.toFloat()
        val h = height.toFloat()
        val density = resources.displayMetrics.density

        textPaint.textSize = 9f * density
        titlePaint.textSize = 9f * density
        legendTextPaint.textSize = 8.5f * density

        val paddingLeft = 44f * density
        val paddingRight = 16f * density
        val paddingTop = 36f * density
        val paddingBottom = 32f * density

        val plotLeft = paddingLeft
        val plotRight = w - paddingRight
        val plotTop = paddingTop
        val plotBottom = h - paddingBottom

        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val textColor = if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")
        val gridColor = if (isDark) Color.parseColor("#2E3D56") else Color.parseColor("#E2E8F0")
        val axisColor = if (isDark) Color.parseColor("#475569") else Color.parseColor("#CBD5E1")

        textPaint.color = textColor
        gridPaint.color = gridColor
        axisPaint.color = axisColor
        titlePaint.color = textColor
        legendTextPaint.color = textColor

        // 1. Draw Top Scientific Disclaimer
        canvas.drawText(
            "3GPP Nominal Channel Allocation (RSRP Peak) • Not measured spectral power",
            w / 2f,
            14f * density,
            titlePaint
        )

        // 2. Exactly TWO Legend Entries: [Serving Cell] and [Unconfirmed Neighbors]
        val servingColor = ContextCompat.getColor(context, R.color.primary)
        val neighborColor = Color.parseColor("#64748B") // Subtle Slate

        val legendDotRadius = 4f * density
        val legendY = 27f * density

        // Legend Entry 1: Serving Cell
        var curLegendX = plotLeft + 6f * density
        val servingDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = servingColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(curLegendX, legendY - 3f * density, legendDotRadius, servingDotPaint)
        curLegendX += legendDotRadius + 5f * density
        canvas.drawText("Serving Cell", curLegendX, legendY, legendTextPaint)
        curLegendX += legendTextPaint.measureText("Serving Cell") + 18f * density

        // Legend Entry 2: Unconfirmed Neighbors
        val neighborDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = neighborColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(curLegendX, legendY - 3f * density, legendDotRadius, neighborDotPaint)
        curLegendX += legendDotRadius + 5f * density
        canvas.drawText("Unconfirmed Neighbors", curLegendX, legendY, legendTextPaint)

        // Empty state check
        if (resolvedCarriers.isEmpty()) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                textSize = 11f * density
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(
                "No active cellular carrier frequencies detected.",
                w / 2f,
                h / 2f - 8f,
                emptyPaint
            )
            canvas.drawText(
                "Awaiting modem telemetry or device is out of service.",
                w / 2f,
                h / 2f + 14f,
                emptyPaint
            )
            return
        }

        // Determine X-axis frequency bounds with generous padding so envelopes aren't clipped
        val rawMinFreq = (resolvedCarriers.minOfOrNull { it.second.lowFreqMhz } ?: 1800.0) - 5.0
        val rawMaxFreq = (resolvedCarriers.maxOfOrNull { it.second.highFreqMhz } ?: 1850.0) + 5.0
        val baseSpan = max(rawMaxFreq - rawMinFreq, 20.0)

        val visibleSpan = baseSpan / zoomScale
        val centerFreq = (rawMinFreq + rawMaxFreq) / 2.0 + panOffsetMhz
        val minFreq = centerFreq - visibleSpan / 2.0
        val maxFreq = centerFreq + visibleSpan / 2.0

        fun toScreenX(fMhz: Double): Float {
            return plotLeft + ((fMhz - minFreq) / visibleSpan * (plotRight - plotLeft)).toFloat()
        }

        fun toScreenY(rsrp: Float): Float {
            val clamped = rsrp.coerceIn(minDb, maxDb)
            return plotBottom - ((clamped - minDb) / (maxDb - minDb) * (plotBottom - plotTop))
        }

        // 3. Draw Y-Axis Grid Lines & dBm Labels
        val dbSteps = listOf(-140f, -120f, -100f, -80f, -60f, -40f)
        for (db in dbSteps) {
            val y = toScreenY(db)
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("${db.toInt()} dBm", plotLeft - 5f, y + 4f, textPaint)
        }

        // Draw Axis Lines
        canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, axisPaint)
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, axisPaint)

        // 4. Draw X-Axis Frequency Ticks & MHz Labels
        val numTicks = 5
        val freqStep = visibleSpan / numTicks
        textPaint.textAlign = Paint.Align.CENTER
        for (i in 0..numTicks) {
            val f = minFreq + (i * freqStep)
            val x = toScreenX(f)
            if (x in plotLeft..plotRight) {
                canvas.drawLine(x, plotBottom, x, plotBottom + 4f, axisPaint)
                canvas.drawText(String.format(Locale.US, "%.1f", f), x, plotBottom + 14f, textPaint)
            }
        }
        canvas.drawText("Center Frequency (MHz)", (plotLeft + plotRight) / 2f, plotBottom + 26f, textPaint)

        // Check if there are neighbors
        val neighborsCount = resolvedCarriers.count { !isCellAuthoritativeServing(it.first) }
        if (neighborsCount == 0) {
            val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                textSize = 9.5f * density
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText("No neighbor cell observations on carrier", plotRight - 4f, plotTop + 14f, hintPaint)
        }

        // 5. Render Cell Channel Envelopes (Serving cell on top)
        val sortedToDraw = resolvedCarriers.sortedBy { isCellAuthoritativeServing(it.first) }

        var selectedDrawData: Triple<ObservedCell, CellCarrierInfo, Float>? = null

        for (pair in sortedToDraw) {
            val (cell, carrier) = pair
            val isServing = isCellAuthoritativeServing(cell)
            val rsrp = (cell.rsrpDbm ?: -115).toFloat()

            val xStart = toScreenX(carrier.lowFreqMhz)
            val xCenter = toScreenX(carrier.dlCenterFreqMhz)
            val xEnd = toScreenX(carrier.highFreqMhz)
            val yPeak = toScreenY(rsrp)
            val yBase = toScreenY(minDb)

            val channelColor = if (isServing) servingColor else neighborColor

            // Draw envelope only if within or near plot bounds
            if (xEnd >= plotLeft && xStart <= plotRight) {
                val path = Path().apply {
                    moveTo(xStart, yBase)
                    cubicTo(
                        xStart + (xCenter - xStart) * 0.35f, yBase,
                        xCenter - (xCenter - xStart) * 0.35f, yPeak,
                        xCenter, yPeak
                    )
                    cubicTo(
                        xCenter + (xEnd - xCenter) * 0.35f, yPeak,
                        xEnd - (xEnd - xCenter) * 0.35f, yBase,
                        xEnd, yBase
                    )
                    close()
                }

                // Fill with subtle transparency
                val fillAlpha = if (isServing) 60 else 25
                val fillP = if (isServing) servingFillPaint else neighborFillPaint
                fillP.color = Color.argb(fillAlpha, Color.red(channelColor), Color.green(channelColor), Color.blue(channelColor))
                canvas.drawPath(path, fillP)

                // Outline
                val strokeP = if (isServing) servingStrokePaint else neighborStrokePaint
                strokeP.color = channelColor
                canvas.drawPath(path, strokeP)

                // Peak dot (subtle, clean)
                val dotRadius = if (isServing) 4.5f * density else 3f * density
                val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = channelColor
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(xCenter, yPeak, dotRadius, dotP)

                // Hit test area around the peak and upper half of envelope
                val hitBox = RectF(
                    max(plotLeft, xCenter - 20f * density),
                    max(plotTop, yPeak - 20f * density),
                    min(plotRight, xCenter + 20f * density),
                    min(plotBottom, yPeak + 40f * density)
                )
                hitAreas.add(Pair(hitBox, pair))

                // Check if this is the selected cell
                val isSelected = selectedCell != null && isSameCell(cell, selectedCell!!)
                if (isSelected) {
                    selectedDrawData = Triple(cell, carrier, xCenter)
                }
            }
        }

        // 6. Draw Highlight & Single Callout Tag ONLY for the Selected Cell (Clutter-Free!)
        selectedDrawData?.let { (cell, _, xCenter) ->
            val rsrp = (cell.rsrpDbm ?: -115).toFloat()
            val yPeak = toScreenY(rsrp)
            val isServing = isCellAuthoritativeServing(cell)

            // Outer Glowing Ring on peak
            canvas.drawCircle(xCenter, yPeak, 8f * density, selectedRingPaint)
            canvas.drawCircle(xCenter, yPeak, 3.5f * density, selectedDotPaint)

            // Clean callout tag above peak
            val pciLabel = cell.pci?.let { "PCI $it" } ?: "Cell"
            val tagText = if (isServing) "★ SERVING ($pciLabel)" else "$pciLabel (Neighbor)"

            val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 9f * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
            }

            val tagW = tagPaint.measureText(tagText) + 12f * density
            val tagH = 17f * density
            val tagY = max(plotTop + tagH, yPeak - 10f * density)

            // Subtle vertical leader line from tag to peak
            canvas.drawLine(xCenter, tagY, xCenter, yPeak - 8f * density, leaderLinePaint)

            // Tag Pill
            tagBgPaint.color = if (isServing) servingColor else Color.parseColor("#475569")
            val tagRect = RectF(
                (xCenter - tagW / 2f).coerceIn(plotLeft, plotRight - tagW),
                tagY - tagH,
                (xCenter + tagW / 2f).coerceIn(plotLeft + tagW, plotRight),
                tagY
            )
            canvas.drawRoundRect(tagRect, 5f * density, 5f * density, tagBgPaint)
            canvas.drawText(tagText, tagRect.centerX(), tagRect.centerY() + 3f * density, tagPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && zoomScale > 1.05f) {
                    val dx = event.x - lastTouchX
                    if (kotlin.math.abs(dx) > 4f) {
                        isDragging = true
                        val span = 20.0 / zoomScale
                        val mhzPerPixel = span / (width - 60f)
                        panOffsetMhz -= (dx * mhzPerPixel)
                        lastTouchX = event.x
                        invalidate()
                    }
                }
            }
        }
        return true
    }
}
