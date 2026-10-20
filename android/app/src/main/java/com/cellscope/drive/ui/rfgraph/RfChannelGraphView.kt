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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Selection Mode for RF Graph and Cell Comparison.
 */
enum class RfSelectionMode {
    ALL,
    SINGLE,
    MULTIPLE
}

/**
 * Distinct Analytical Views for RF Frequency Engineering.
 */
enum class RfGraphViewMode(val title: String) {
    FREQUENCY_SCATTER("Frequency vs. RSRP Scatter"),
    BAND_INVENTORY("Frequency & Band Inventory"),
    SERVING_VS_NEIGHBOR("Serving vs. Neighbor Comparison"),
    REUSE_PCI_ALIGNMENT("Frequency Reuse & PCI Alignment")
}

/**
 * Advanced Cell Explorer RF Channel Frequency Graph & Analytical Engine.
 *
 * Engineering Standards:
 * 1. Restored smooth bell-shaped RF channel envelopes for serving and neighbor cells.
 * 2. Serving cell highlighted with primary blue color; neighbor cells rendered in distinct slate.
 * 3. Standard telecom Y-axis (-140 dBm to -40 dBm) with stable 20 dB gridlines.
 * 4. Multi-band smart auto-focus: when "All Bands" is active and observed cells span multiple
 *    disparate bands (> 60 MHz), auto-windows onto the serving cell's active band to prevent
 *    extreme multi-band compression, while allowing explicit band selection via filter chips.
 * 5. Minimum visual width (16dp half-width) ensuring narrow channels (GSM 200 kHz, LTE 1.4/3 MHz)
 *    render distinct, legible bell profiles.
 * 6. Interactive touch selection: tapping an envelope highlights the cell and updates the
 *    Cell Carrier Inspection panel below the graph.
 * 7. Pinch-to-zoom and horizontal pan gestures with double-tap reset.
 * 8. Explicit 3GPP nominal channel allocation disclaimer (not measured baseband spectral power).
 * 9. Full light and dark theme adaptive color styling.
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

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var observedCells: List<ObservedCell> = emptyList()
    private var resolvedCarriers: List<Pair<ObservedCell, CellCarrierInfo>> = emptyList()

    // Selection management
    var selectionMode: RfSelectionMode = RfSelectionMode.ALL
        set(value) {
            field = value
            if (value == RfSelectionMode.ALL) {
                selectedCellKeys.clear()
            } else if (value == RfSelectionMode.SINGLE && selectedCellKeys.size > 1) {
                val first = selectedCellKeys.firstOrNull()
                selectedCellKeys.clear()
                if (first != null) selectedCellKeys.add(first)
            }
            invalidate()
            notifySelectionListeners()
        }

    val selectedCellKeys = mutableSetOf<String>()

    var selectedCell: ObservedCell? = null
        private set

    var viewMode: RfGraphViewMode = RfGraphViewMode.FREQUENCY_SCATTER
        set(value) {
            field = value
            invalidate()
        }

    var onCellSelectedListener: ((ObservedCell, CellCarrierInfo) -> Unit)? = null
    var onSelectionChangedListener: ((List<ObservedCell>, RfSelectionMode) -> Unit)? = null

    var filterBand: Int? = null
        set(value) {
            field = value
            // Reset zoom/pan when changing band filter
            zoomScale = 1.0f
            panOffsetMhz = 0.0
            recomputeCarriers()
            invalidate()
        }

    // Legend filter state
    var showServingOnly: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var showNeighborsOnly: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // Zoom and pan
    private var zoomScale = 1.0f
    private var panOffsetMhz = 0.0
    private var lastTouchX = 0f
    private var isDragging = false

    // Hit testing areas for interactive tapping
    private val hitAreas = mutableListOf<Pair<RectF, Pair<ObservedCell, CellCarrierInfo>>>()
    private var servingLegendHitRect = RectF()
    private var neighborLegendHitRect = RectF()

    // Standard fixed telecom signal range (-140 dBm to -40 dBm)
    private val minDb = -140f
    private val maxDb = -40f

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (viewMode == RfGraphViewMode.FREQUENCY_SCATTER) {
                zoomScale = (zoomScale * detector.scaleFactor).coerceIn(1.0f, 6.0f)
                invalidate()
                return true
            }
            return false
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

            // 1. Check interactive legend taps
            if (servingLegendHitRect.contains(tx, ty)) {
                if (showServingOnly) {
                    showServingOnly = false
                } else {
                    showServingOnly = true
                    showNeighborsOnly = false
                }
                invalidate()
                return true
            }
            if (neighborLegendHitRect.contains(tx, ty)) {
                if (showNeighborsOnly) {
                    showNeighborsOnly = false
                } else {
                    showNeighborsOnly = true
                    showServingOnly = false
                }
                invalidate()
                return true
            }

            // 2. Check cell marker / envelope hit areas
            val clicked = hitAreas.firstOrNull { it.first.contains(tx, ty) }
            if (clicked != null) {
                val (cell, carrier) = clicked.second
                handleCellClicked(cell, carrier)
                return true
            }
            return false
        }
    })

    fun setCells(cells: List<ObservedCell>) {
        this.observedCells = cells
        recomputeCarriers()

        // Default to authoritative serving cell if no selection exists or selected cell departed
        if (selectedCell == null || observedCells.none { isSameCell(it, selectedCell!!) }) {
            selectedCell = observedCells.firstOrNull { isCellAuthoritativeServing(it) } ?: observedCells.firstOrNull()
        }

        selectedCell?.let { sel ->
            resolvedCarriers.firstOrNull { isSameCell(it.first, sel) }?.let { pair ->
                onCellSelectedListener?.invoke(pair.first, pair.second)
            }
        }
        invalidate()
    }

    fun selectAll() {
        selectionMode = RfSelectionMode.ALL
        selectedCellKeys.clear()
        invalidate()
        notifySelectionListeners()
    }

    fun clearSelection() {
        selectionMode = RfSelectionMode.MULTIPLE
        selectedCellKeys.clear()
        selectedCell = null
        invalidate()
        notifySelectionListeners()
    }

    fun selectSingleCell(cell: ObservedCell) {
        selectionMode = RfSelectionMode.SINGLE
        selectedCell = cell
        selectedCellKeys.clear()
        selectedCellKeys.add(getCellKey(cell))
        resolvedCarriers.firstOrNull { isSameCell(it.first, cell) }?.let { pair ->
            onCellSelectedListener?.invoke(pair.first, pair.second)
        }
        invalidate()
        notifySelectionListeners()
    }

    fun toggleCellSelection(cell: ObservedCell) {
        val key = getCellKey(cell)
        if (selectedCellKeys.contains(key)) {
            selectedCellKeys.remove(key)
            if (selectedCell != null && isSameCell(selectedCell!!, cell)) {
                selectedCell = getSelectedCells().firstOrNull()
            }
        } else {
            if (selectionMode == RfSelectionMode.SINGLE) {
                selectedCellKeys.clear()
            }
            selectedCellKeys.add(key)
            selectedCell = cell
        }
        selectedCell?.let { sel ->
            resolvedCarriers.firstOrNull { isSameCell(it.first, sel) }?.let { pair ->
                onCellSelectedListener?.invoke(pair.first, pair.second)
            }
        }
        invalidate()
        notifySelectionListeners()
    }

    fun getSelectedCells(): List<ObservedCell> {
        return when (selectionMode) {
            RfSelectionMode.ALL -> observedCells
            RfSelectionMode.SINGLE -> selectedCell?.let { listOf(it) } ?: emptyList()
            RfSelectionMode.MULTIPLE -> observedCells.filter { selectedCellKeys.contains(getCellKey(it)) }
        }
    }

    fun getSelectedCount(): Int {
        return when (selectionMode) {
            RfSelectionMode.ALL -> observedCells.size
            RfSelectionMode.SINGLE -> if (selectedCell != null) 1 else 0
            RfSelectionMode.MULTIPLE -> selectedCellKeys.size
        }
    }

    private fun handleCellClicked(cell: ObservedCell, carrier: CellCarrierInfo) {
        selectedCell = cell
        val key = getCellKey(cell)
        if (selectionMode == RfSelectionMode.MULTIPLE) {
            if (selectedCellKeys.contains(key)) selectedCellKeys.remove(key) else selectedCellKeys.add(key)
        } else {
            selectedCellKeys.clear()
            selectedCellKeys.add(key)
        }
        onCellSelectedListener?.invoke(cell, carrier)
        notifySelectionListeners()
        invalidate()
    }

    private fun notifySelectionListeners() {
        onSelectionChangedListener?.invoke(getSelectedCells(), selectionMode)
    }

    fun getCellKey(cell: ObservedCell): String {
        val rat = cell.servingRat ?: cell.networkType
        val channel = cell.earfcn ?: cell.nrArfcn ?: cell.channelNumber ?: 0
        val pci = cell.pci ?: -1
        return "${rat}_${channel}_${pci}"
    }

    private fun isSameCell(a: ObservedCell, b: ObservedCell): Boolean {
        return getCellKey(a) == getCellKey(b)
    }

    private fun isCellAuthoritativeServing(cell: ObservedCell): Boolean {
        return cell.isServing && cell.isConfirmedServing
    }

    private fun isCellSelected(cell: ObservedCell): Boolean {
        return when (selectionMode) {
            RfSelectionMode.ALL -> true
            RfSelectionMode.SINGLE -> selectedCell != null && isSameCell(cell, selectedCell!!)
            RfSelectionMode.MULTIPLE -> selectedCellKeys.contains(getCellKey(cell))
        }
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

        val paddingLeft = 46f * density
        val paddingRight = 16f * density
        val paddingTop = 38f * density
        val paddingBottom = 34f * density

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

        // Top Scientific Disclaimer
        val subtitle = when (viewMode) {
            RfGraphViewMode.FREQUENCY_SCATTER -> "Visualized Channel Signal Distribution (Derived from 3GPP channel allocation; not calibrated spectral power)"
            RfGraphViewMode.BAND_INVENTORY -> "Observed Carrier & Band Inventory • 3GPP Bandwidth Allocations"
            RfGraphViewMode.SERVING_VS_NEIGHBOR -> "Serving vs. Neighbor Relative RF Signal Level Comparison"
            RfGraphViewMode.REUSE_PCI_ALIGNMENT -> "Observed PCIs per Carrier • Modulo-3 CRS Subcarrier Shift Grouping"
        }
        canvas.drawText(subtitle, w / 2f, 14f * density, titlePaint)

        // Interactive Legend: [Serving Cell] and [Neighbors]
        val servingColor = ContextCompat.getColor(context, R.color.primary)
        val neighborColor = Color.parseColor("#64748B") // Subtle Slate

        val legendDotRadius = 4.5f * density
        val legendY = 27f * density

        // Legend 1: Serving Cell
        var curLegendX = plotLeft + 4f * density
        val servingDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (showNeighborsOnly) Color.parseColor("#475569") else servingColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(curLegendX, legendY - 3f * density, legendDotRadius, servingDotPaint)
        curLegendX += legendDotRadius + 6f * density
        val servingLegendText = if (showServingOnly) "Serving Cell (FILTERED)" else "Serving Cell"
        canvas.drawText(servingLegendText, curLegendX, legendY, legendTextPaint)
        val servingTextW = legendTextPaint.measureText(servingLegendText)
        servingLegendHitRect = RectF(plotLeft, legendY - 12f * density, curLegendX + servingTextW + 6f * density, legendY + 6f * density)
        curLegendX += servingTextW + 18f * density

        // Legend 2: Neighbors
        val neighborDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (showServingOnly) Color.parseColor("#475569") else neighborColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(curLegendX, legendY - 3f * density, legendDotRadius, neighborDotPaint)
        curLegendX += legendDotRadius + 6f * density
        val neighborLegendText = if (showNeighborsOnly) "Neighbors (FILTERED)" else "Neighbors"
        canvas.drawText(neighborLegendText, curLegendX, legendY, legendTextPaint)
        val neighborTextW = legendTextPaint.measureText(neighborLegendText)
        neighborLegendHitRect = RectF(curLegendX - legendDotRadius - 6f * density, legendY - 12f * density, curLegendX + neighborTextW + 6f * density, legendY + 6f * density)

        // Empty state check
        if (resolvedCarriers.isEmpty()) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                textSize = 11f * density
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("No active cellular carrier frequencies detected.", w / 2f, h / 2f - 8f, emptyPaint)
            canvas.drawText("Awaiting modem telemetry or device is out of service.", w / 2f, h / 2f + 14f, emptyPaint)
            return
        }

        // Apply legend filter
        val displayedCarriers = resolvedCarriers.filter { pair ->
            val isServing = isCellAuthoritativeServing(pair.first)
            if (showServingOnly) isServing
            else if (showNeighborsOnly) !isServing
            else true
        }

        // Render selected analytical view mode
        when (viewMode) {
            RfGraphViewMode.FREQUENCY_SCATTER -> drawFrequencyScatterView(canvas, displayedCarriers, plotLeft, plotRight, plotTop, plotBottom, density, servingColor, neighborColor, textColor)
            RfGraphViewMode.BAND_INVENTORY -> drawBandInventoryView(canvas, displayedCarriers, plotLeft, plotRight, plotTop, plotBottom, density, servingColor, neighborColor, textColor)
            RfGraphViewMode.SERVING_VS_NEIGHBOR -> drawServingVsNeighborView(canvas, displayedCarriers, plotLeft, plotRight, plotTop, plotBottom, density, servingColor, neighborColor, textColor)
            RfGraphViewMode.REUSE_PCI_ALIGNMENT -> drawReusePciView(canvas, displayedCarriers, plotLeft, plotRight, plotTop, plotBottom, density, servingColor, neighborColor, textColor)
        }
    }

    // =========================================================================
    // VIEW 1: BELL-SHAPED RF FREQUENCY SCATTER & CHANNEL ENVELOPES (RESTORED)
    // =========================================================================
    private fun drawFrequencyScatterView(
        canvas: Canvas,
        carriers: List<Pair<ObservedCell, CellCarrierInfo>>,
        plotLeft: Float,
        plotRight: Float,
        plotTop: Float,
        plotBottom: Float,
        density: Float,
        servingColor: Int,
        neighborColor: Int,
        textColor: Int
    ) {
        if (carriers.isEmpty()) return

        // Multi-Band Smart Windowing:
        // When "All Bands" is active (filterBand == null) and the observed carriers span
        // multiple disparate bands (> 60 MHz span), auto-focus the active window around the
        // serving cell's band to prevent extreme 1000+ MHz distortion.
        val totalMinFreq = carriers.minOfOrNull { it.second.lowFreqMhz } ?: 1800.0
        val totalMaxFreq = carriers.maxOfOrNull { it.second.highFreqMhz } ?: 1850.0
        val totalSpan = totalMaxFreq - totalMinFreq

        val (carriersToPlot, isAutoWindowed, windowBandName) = if (filterBand == null && totalSpan > 60.0) {
            // Find target band: serving cell's band, or the band with largest cell count
            val servingCellPair = carriers.firstOrNull { isCellAuthoritativeServing(it.first) }
            val targetBand = servingCellPair?.second?.bandNumber ?: carriers.groupBy { it.second.bandNumber }.maxByOrNull { it.value.size }?.key ?: 3
            val filtered = carriers.filter { it.second.bandNumber == targetBand }
            val bName = filtered.firstOrNull()?.second?.bandName ?: "Band $targetBand"
            Triple(filtered.ifEmpty { carriers }, true, bName)
        } else {
            Triple(carriers, false, "")
        }

        if (isAutoWindowed) {
            // Draw subtle notice about auto-focusing on the active band
            val noticePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.primary)
                textSize = 8.5f * density
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText("Auto-focused: $windowBandName • Filter bands above", plotRight - 2f, plotTop - 4f, noticePaint)
        }

        // Determine X-axis frequency bounds with generous padding so envelopes aren't clipped
        val rawMinFreq = (carriersToPlot.minOfOrNull { it.second.lowFreqMhz } ?: 1800.0) - 5.0
        val rawMaxFreq = (carriersToPlot.maxOfOrNull { it.second.highFreqMhz } ?: 1850.0) + 5.0
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

        // Draw Standard Y-Axis Grid Lines & dBm Labels (-140 to -40 dBm, 20 dB steps)
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

        // Draw X-Axis Frequency Ticks & MHz Labels
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

        // Render Cell Channel Envelopes (Serving cell on top)
        val sortedToDraw = carriersToPlot.sortedBy { isCellAuthoritativeServing(it.first) }
        var selectedDrawData: Triple<ObservedCell, CellCarrierInfo, Float>? = null

        for (pair in sortedToDraw) {
            val (cell, carrier) = pair
            val isServing = isCellAuthoritativeServing(cell)
            val rsrp = (cell.primarySignalDbm ?: cell.rsrpDbm ?: cell.rssiDbm ?: -115).toFloat()

            val xCenter = toScreenX(carrier.dlCenterFreqMhz)
            val nominalHalfWidthPx = abs(toScreenX(carrier.highFreqMhz) - toScreenX(carrier.lowFreqMhz)) / 2f
            val minHalfWidthPx = 16f * density // Guarantees a minimum width of 32dp visual bell curve
            val halfWidthPx = max(nominalHalfWidthPx, minHalfWidthPx)
            val xStart = xCenter - halfWidthPx
            val xEnd = xCenter + halfWidthPx
            val yPeak = toScreenY(rsrp)
            val yBase = toScreenY(minDb)

            val channelColor = if (isServing) servingColor else neighborColor

            // Draw envelope only if within or near plot bounds
            if (xEnd >= plotLeft && xStart <= plotRight) {
                val path = Path().apply {
                    moveTo(xStart, yBase)
                    cubicTo(
                        xStart + halfWidthPx * 0.35f, yBase,
                        xCenter - halfWidthPx * 0.35f, yPeak,
                        xCenter, yPeak
                    )
                    cubicTo(
                        xCenter + halfWidthPx * 0.35f, yPeak,
                        xEnd - halfWidthPx * 0.35f, yBase,
                        xEnd, yBase
                    )
                    close()
                }

                // Fill with subtle transparency
                val fillAlpha = if (isServing) 55 else 20
                val fillP = if (isServing) servingFillPaint else neighborFillPaint
                fillP.color = Color.argb(fillAlpha, Color.red(channelColor), Color.green(channelColor), Color.blue(channelColor))
                canvas.drawPath(path, fillP)

                // Outline
                val strokeP = if (isServing) servingStrokePaint else neighborStrokePaint
                strokeP.color = channelColor
                canvas.drawPath(path, strokeP)

                // Peak dot (clean, telecom standard)
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
                val isSelected = isCellSelected(cell)
                if (isSelected) {
                    selectedDrawData = Triple(cell, carrier, xCenter)
                }
            }
        }

        // Highlight & Single Callout Tag ONLY for the Selected Cell (Clutter-Free!)
        selectedDrawData?.let { (cell, _, xCenter) ->
            val rsrp = (cell.primarySignalDbm ?: cell.rsrpDbm ?: cell.rssiDbm ?: -115).toFloat()
            val yPeak = toScreenY(rsrp)
            val isServing = isCellAuthoritativeServing(cell)

            // Outer Glowing Ring on peak
            canvas.drawCircle(xCenter, yPeak, 8f * density, selectedRingPaint)
            canvas.drawCircle(xCenter, yPeak, 3.5f * density, selectedDotPaint)

            // Clean callout tag above peak
            val pciLabel = when {
                cell.isGsm -> cell.pci?.let { "BSIC $it" } ?: "BSIC —"
                cell.isWcdma -> cell.pci?.let { "PSC $it" } ?: "PSC —"
                cell.is5gNr -> cell.pci?.let { "NR-PCI $it" } ?: "PCI —"
                else -> cell.pci?.let { "PCI $it" } ?: "PCI —"
            }
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

    // ==========================================
    // VIEW 2: BAND & FREQUENCY INVENTORY
    // ==========================================
    private fun drawBandInventoryView(
        canvas: Canvas,
        carriers: List<Pair<ObservedCell, CellCarrierInfo>>,
        plotLeft: Float,
        plotRight: Float,
        plotTop: Float,
        plotBottom: Float,
        density: Float,
        servingColor: Int,
        neighborColor: Int,
        textColor: Int
    ) {
        val carrierGroups = carriers.groupBy { it.second.dlCenterFreqMhz }.entries.toList()
        if (carrierGroups.isEmpty()) return

        val rowHeight = min(42f * density, (plotBottom - plotTop) / max(carrierGroups.size, 1))
        var curY = plotTop + 10f * density

        for ((centerFreq, cellsInGroup) in carrierGroups) {
            val carrier = cellsInGroup.first().second
            val hasServing = cellsInGroup.any { isCellAuthoritativeServing(it.first) }
            val count = cellsInGroup.size

            val barRect = RectF(plotLeft, curY, plotRight, curY + rowHeight - 6f * density)
            barPaint.color = if (hasServing) Color.argb(40, Color.red(servingColor), Color.green(servingColor), Color.blue(servingColor)) else Color.argb(25, 100, 116, 139)
            canvas.drawRoundRect(barRect, 6f * density, 6f * density, barPaint)

            val accentRect = RectF(plotLeft, curY, plotLeft + 4f * density, curY + rowHeight - 6f * density)
            barPaint.color = if (hasServing) servingColor else neighborColor
            canvas.drawRoundRect(accentRect, 2f * density, 2f * density, barPaint)

            textPaint.textAlign = Paint.Align.LEFT
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val bandLabel = "Band ${carrier.bandNumber} (${carrier.bandName})"
            val channelLabel = "${carrier.rat} Ch ${carrier.channelNumber} • ${String.format(Locale.US, "%.1f", centerFreq)} MHz"
            canvas.drawText(bandLabel, plotLeft + 12f * density, curY + 14f * density, textPaint)

            textPaint.typeface = Typeface.DEFAULT
            textPaint.textSize = 8.5f * density
            canvas.drawText(channelLabel, plotLeft + 12f * density, curY + 26f * density, textPaint)

            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val roleText = if (hasServing) "★ Serving + ${count - 1} Neighbors" else "$count Neighbors"
            canvas.drawText(roleText, plotRight - 10f * density, curY + 14f * density, textPaint)

            textPaint.typeface = Typeface.DEFAULT
            val bwText = "BW: ${carrier.bandwidthDisplay} • ${carrier.duplexDescription.take(16)}"
            canvas.drawText(bwText, plotRight - 10f * density, curY + 26f * density, textPaint)

            hitAreas.add(Pair(barRect, cellsInGroup.first()))
            curY += rowHeight
        }
    }

    // ==========================================
    // VIEW 3: SERVING VS. NEIGHBOR COMPARISON
    // ==========================================
    private fun drawServingVsNeighborView(
        canvas: Canvas,
        carriers: List<Pair<ObservedCell, CellCarrierInfo>>,
        plotLeft: Float,
        plotRight: Float,
        plotTop: Float,
        plotBottom: Float,
        density: Float,
        servingColor: Int,
        neighborColor: Int,
        textColor: Int
    ) {
        val serving = carriers.firstOrNull { isCellAuthoritativeServing(it.first) }
        val neighbors = carriers.filter { !isCellAuthoritativeServing(it.first) }.sortedByDescending { it.first.primarySignalDbm ?: it.first.rsrpDbm ?: -140 }

        if (serving == null && neighbors.isEmpty()) return

        val allToCompare = listOfNotNull(serving) + neighbors.take(5)
        val rowHeight = min(36f * density, (plotBottom - plotTop) / max(allToCompare.size, 1))

        var curY = plotTop + 8f * density
        val minDbm = -130f
        val maxDbm = -50f

        for (pair in allToCompare) {
            val (cell, _) = pair
            val isServing = isCellAuthoritativeServing(cell)
            val rsrp = (cell.primarySignalDbm ?: cell.rsrpDbm ?: cell.rssiDbm ?: -125).toFloat()
            val pci = cell.pci?.let { "PCI $it" } ?: "Cell"

            textPaint.textAlign = Paint.Align.LEFT
            textPaint.typeface = if (isServing) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            val prefix = if (isServing) "★ SERVING" else "Neighbor"
            canvas.drawText("$prefix $pci", plotLeft, curY + 14f * density, textPaint)

            val barLeft = plotLeft + 90f * density
            val barRight = plotRight - 55f * density
            val barW = barRight - barLeft
            val norm = ((rsrp - minDbm) / (maxDbm - minDbm)).coerceIn(0.05f, 1.0f)
            val fillW = barW * norm

            val bgRect = RectF(barLeft, curY + 4f * density, barRight, curY + 18f * density)
            barPaint.color = Color.parseColor("#334155")
            canvas.drawRoundRect(bgRect, 4f * density, 4f * density, barPaint)

            val fillRect = RectF(barLeft, curY + 4f * density, barLeft + fillW, curY + 18f * density)
            barPaint.color = if (isServing) servingColor else neighborColor
            canvas.drawRoundRect(fillRect, 4f * density, 4f * density, barPaint)

            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("${rsrp.toInt()} dBm", plotRight, curY + 14f * density, textPaint)

            hitAreas.add(Pair(RectF(plotLeft, curY, plotRight, curY + rowHeight), pair))
            curY += rowHeight
        }
    }

    // ==========================================
    // VIEW 4: REUSE & PCI ALIGNMENT
    // ==========================================
    private fun drawReusePciView(
        canvas: Canvas,
        carriers: List<Pair<ObservedCell, CellCarrierInfo>>,
        plotLeft: Float,
        plotRight: Float,
        plotTop: Float,
        plotBottom: Float,
        density: Float,
        servingColor: Int,
        neighborColor: Int,
        textColor: Int
    ) {
        val groupedByChan = carriers.groupBy { it.second.channelNumber }
        var curY = plotTop + 10f * density

        for ((chanNum, cellsOnFreq) in groupedByChan) {
            val carrier = cellsOnFreq.first().second
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("${carrier.rat} Ch $chanNum • ${carrier.bandName} (${String.format(Locale.US, "%.1f", carrier.dlCenterFreqMhz)} MHz)", plotLeft, curY + 10f * density, textPaint)
            curY += 16f * density

            val mod0 = cellsOnFreq.filter { (it.first.pci ?: -1) % 3 == 0 }
            val mod1 = cellsOnFreq.filter { (it.first.pci ?: -1) % 3 == 1 }
            val mod2 = cellsOnFreq.filter { (it.first.pci ?: -1) % 3 == 2 }

            val colW = (plotRight - plotLeft) / 3f
            drawPciModBucket(canvas, "v_shift = 0", mod0, plotLeft, curY, colW - 6f * density, density, servingColor)
            drawPciModBucket(canvas, "v_shift = 1", mod1, plotLeft + colW, curY, colW - 6f * density, density, servingColor)
            drawPciModBucket(canvas, "v_shift = 2", mod2, plotLeft + colW * 2f, curY, colW - 6f * density, density, servingColor)

            curY += 48f * density
        }
    }

    private fun drawPciModBucket(
        canvas: Canvas,
        label: String,
        cells: List<Pair<ObservedCell, CellCarrierInfo>>,
        x: Float,
        y: Float,
        w: Float,
        density: Float,
        servingColor: Int
    ) {
        val rect = RectF(x, y, x + w, y + 40f * density)
        barPaint.color = Color.parseColor("#1E293B")
        canvas.drawRoundRect(rect, 4f * density, 4f * density, barPaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 8f * density
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(label, x + 6f * density, y + 12f * density, textPaint)

        textPaint.typeface = Typeface.DEFAULT
        val pcis = cells.mapNotNull { it.first.pci }.joinToString(", ") { "PCI $it" }
        val display = if (pcis.isBlank()) "None" else pcis
        canvas.drawText(display.take(24), x + 6f * density, y + 26f * density, textPaint)

        cells.firstOrNull()?.let { hitAreas.add(Pair(rect, it)) }
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
                if (viewMode == RfGraphViewMode.FREQUENCY_SCATTER && event.pointerCount == 1 && zoomScale > 1.05f) {
                    val dx = event.x - lastTouchX
                    if (abs(dx) > 4f) {
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
