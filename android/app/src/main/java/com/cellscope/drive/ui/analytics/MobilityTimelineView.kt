package com.cellscope.drive.ui.analytics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.util.TelecomTimeUtils
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Graph 5: Mobility & Handover Multi-Lane Timeline (P3).
 *
 * Engineering Features:
 * - Multi-lane visualization categorizing:
 *     1. Confirmed Handovers (Diamond, Green)
 *     2. Intra-Frequency Transitions (Circle, Cyan)
 *     3. Inter-Frequency Transitions (Triangle, Blue)
 *     4. RAT Mobility / Downgrades (Square, Orange)
 *     5. Ping-Pong Candidates (Star/Hexagon, Magenta)
 *     6. Observed / Other Transitions (Inverted Triangle, Slate)
 * - Elapsed time on X-axis (+00:00, +05:00, etc.) with origin time.
 * - Anti-collision: labels only render when spacing >= 70dp; crisp markers always visible.
 * - Horizontal zoom (1x to 10x) and pan scrolling for dense datasets (thousands of events).
 * - Interactive event selection with callback to bottom sheet/detail card.
 */
class MobilityTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class TransitionLane(val label: String, val shortCode: String, val colorHex: String) {
        CONFIRMED_HO("Confirmed Handover", "HO", "#10B981"),
        INTRA_FREQ("Intra-Freq", "Intra", "#06B6D4"),
        INTER_FREQ("Inter-Freq", "Inter", "#3B82F6"),
        RAT_MOBILITY("RAT Mobility", "RAT", "#F97316"),
        PING_PONG("Ping-Pong Candidate", "P-Pong", "#EC4899"),
        OBSERVED_OTHER("Observed / Reselect", "Obs", "#94A3B8")
    }

    private var transitions: List<CellTransitionEntity> = emptyList()
    private var selectedEvent: CellTransitionEntity? = null
    var onEventSelectedListener: ((CellTransitionEntity) -> Unit)? = null

    // Zoom and pan state
    private var scaleFactor = 1.0f
    private var scrollOffset = 0f

    // Cached rendered event hit-rects for touch selection
    private data class RenderedMarker(
        val event: CellTransitionEntity,
        val cx: Float,
        val cy: Float,
        val radius: Float
    )
    private val renderedMarkers = mutableListOf<RenderedMarker>()

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        isFakeBoldText = true
    }

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 18f
    }

    private val laneLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val laneLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        isFakeBoldText = true
        textAlign = Paint.Align.RIGHT
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.WHITE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        textSize = 19f
        textAlign = Paint.Align.CENTER
    }

    private val timeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 18f
        textAlign = Paint.Align.CENTER
    }

    private val selectionRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#FBBF24") // Amber selection glow
    }

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(1.0f, 10.0f)
            clampScroll()
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            scrollOffset += distanceX
            clampScroll()
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val touchX = e.x
            val touchY = e.y
            val hit = renderedMarkers.minByOrNull { m ->
                val dx = m.cx - touchX
                val dy = m.cy - touchY
                dx * dx + dy * dy
            }
            if (hit != null) {
                val dist = kotlin.math.sqrt((hit.cx - touchX) * (hit.cx - touchX) + (hit.cy - touchY) * (hit.cy - touchY))
                if (dist <= 40f) { // 40px touch slop
                    selectedEvent = hit.event
                    onEventSelectedListener?.invoke(hit.event)
                    invalidate()
                    return true
                }
            }
            return false
        }
    })

    fun setData(newTransitions: List<CellTransitionEntity>) {
        this.transitions = newTransitions.sortedBy { it.timestampMs }
        this.selectedEvent = null
        this.scaleFactor = 1.0f
        this.scrollOffset = 0f
        invalidate()
    }

    fun setSelectedEvent(event: CellTransitionEntity?) {
        this.selectedEvent = event
        invalidate()
    }

    fun zoomIn() {
        scaleFactor = (scaleFactor * 1.4f).coerceAtMost(10.0f)
        clampScroll()
        invalidate()
    }

    fun zoomOut() {
        scaleFactor = (scaleFactor / 1.4f).coerceAtLeast(1.0f)
        clampScroll()
        invalidate()
    }

    fun resetZoom() {
        scaleFactor = 1.0f
        scrollOffset = 0f
        invalidate()
    }

    private fun clampScroll() {
        val maxScroll = max(0f, (width * (scaleFactor - 1.0f)))
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var ret = scaleGestureDetector.onTouchEvent(event)
        ret = gestureDetector.onTouchEvent(event) || ret
        return ret || super.onTouchEvent(event)
    }

    private fun classifyLane(t: CellTransitionEntity): TransitionLane {
        val type = t.eventType.uppercase(Locale.US)
        val details = t.details.lowercase(Locale.US)
        return when {
            details.contains("ping-pong") || type.contains("PING_PONG") -> TransitionLane.PING_PONG
            type.contains("CONFIRMED") -> TransitionLane.CONFIRMED_HO
            type.contains("DOWNGRADE") || type.contains("RAT") || details.contains("downgrade") -> TransitionLane.RAT_MOBILITY
            details.contains("intra-freq") -> TransitionLane.INTRA_FREQ
            details.contains("inter-freq") -> TransitionLane.INTER_FREQ
            else -> TransitionLane.OBSERVED_OTHER
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        renderedMarkers.clear()

        if (transitions.isEmpty()) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#64748B")
                textSize = 28f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Zero serving-cell transitions detected (Dominant single-cell dwell)", w / 2f, h / 2f, emptyPaint)
            return
        }

        val paddingLeft = 110f
        val paddingRight = 40f
        val paddingTop = 65f
        val paddingBottom = 45f

        val plotW = w - paddingLeft - paddingRight
        val plotH = h - paddingTop - paddingBottom

        // Header Title & Subtitle with zoom indicator
        val zoomText = if (scaleFactor > 1.05f) " • Zoom: ${String.format(Locale.US, "%.1fx", scaleFactor)} (Swipe to Pan)" else ""
        canvas.drawText("Mobility & Handover Multi-Lane Timeline (${transitions.size} events)$zoomText", 20f, 32f, titlePaint)
        canvas.drawText("Grouped by mobility mechanism • Tap marker to inspect full RF evidence", 20f, 52f, subtitlePaint)

        val lanes = TransitionLane.entries.toTypedArray()
        val laneCount = lanes.size
        val laneHeight = plotH / laneCount

        // Draw Swimlane Tracks and Left-side Category Labels
        for (idx in lanes.indices) {
            val lane = lanes[idx]
            val laneY = paddingTop + idx * laneHeight + laneHeight / 2f

            laneLabelPaint.color = Color.parseColor(lane.colorHex)
            canvas.drawText(lane.shortCode, paddingLeft - 14f, laneY + 7f, laneLabelPaint)

            // Horizontal Track Line
            canvas.drawLine(paddingLeft, laneY, w - paddingRight, laneY, laneLinePaint)
        }

        val firstTime = transitions.first().timestampMs
        val lastTime = transitions.last().timestampMs
        val totalSpanMs = max(1L, lastTime - firstTime).toDouble()

        // Scaled plotting width
        val effectivePlotW = plotW * scaleFactor

        canvas.save()
        // Clip plot region to avoid drawing markers over left labels or beyond margins
        canvas.clipRect(paddingLeft, paddingTop - 10f, w - paddingRight, h)

        // Track last drawn label X per lane for collision avoidance
        val lastLabelXPerLane = FloatArray(laneCount) { -9999f }
        val minLabelSpacingPx = 70f * resources.displayMetrics.density

        for (trans in transitions) {
            val normTime = if (transitions.size == 1) 0.5 else ((trans.timestampMs - firstTime).toDouble() / totalSpanMs).coerceIn(0.0, 1.0)
            val eventX = paddingLeft + (normTime * effectivePlotW).toFloat() - scrollOffset

            // Skip offscreen events
            if (eventX < paddingLeft - 20f || eventX > w - paddingRight + 20f) {
                continue
            }

            val lane = classifyLane(trans)
            val laneIdx = lanes.indexOf(lane)
            val laneY = paddingTop + laneIdx * laneHeight + laneHeight / 2f

            markerPaint.color = Color.parseColor(lane.colorHex)

            // Render Distinct Marker Shape per Lane
            val markerRadius = 9f
            drawLaneShape(canvas, lane, eventX, laneY, markerRadius)

            // Cache rendered marker for touch hit-testing
            renderedMarkers.add(RenderedMarker(trans, eventX, laneY, markerRadius))

            // Highlight selected event
            if (selectedEvent?.id == trans.id || (selectedEvent?.timestampMs == trans.timestampMs && selectedEvent?.toPci == trans.toPci)) {
                canvas.drawCircle(eventX, laneY, markerRadius + 6f, selectionRingPaint)
            }

            // Draw label if space allows or if event is selected
            val isSelected = selectedEvent?.id == trans.id
            val canFitLabel = (eventX - lastLabelXPerLane[laneIdx] >= minLabelSpacingPx) || isSelected
            if (canFitLabel) {
                val fromPci = trans.fromPci ?: "—"
                val toPci = trans.toPci ?: "—"
                val label = "$fromPci→$toPci"
                canvas.drawText(label, eventX, laneY - 14f, textPaint)
                lastLabelXPerLane[laneIdx] = eventX
            }
        }

        canvas.restore()

        // X-Axis Baseline & Elapsed Time Markers
        val axisY = h - paddingBottom + 8f
        canvas.drawLine(paddingLeft, axisY, w - paddingRight, axisY, axisPaint)

        val totalSpanSec = (totalSpanMs / 1000.0).toInt()
        val timeSteps = 4
        for (i in 0..timeSteps) {
            val ratio = i.toFloat() / timeSteps
            val x = paddingLeft + ratio * plotW
            val elapsedSec = (ratio * totalSpanSec).toInt()
            val elapsedMin = elapsedSec / 60
            val elapsedSecRem = elapsedSec % 60
            val timeLabel = String.format(Locale.US, "+%02d:%02d", elapsedMin, elapsedSecRem)
            canvas.drawText(timeLabel, x, h - 14f, timeTextPaint)
            canvas.drawLine(x, axisY, x, axisY + 6f, axisPaint)
        }

        // Draw Start and End origin BDT clock times at the ends
        val startBdt = TelecomTimeUtils.isoToBdtTimeOnly(transitions.first().timestampIso)
        timeTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(startBdt, paddingLeft, h - 2f, timeTextPaint)

        val endBdt = TelecomTimeUtils.isoToBdtTimeOnly(transitions.last().timestampIso)
        timeTextPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(endBdt, w - paddingRight, h - 2f, timeTextPaint)
    }

    private fun drawLaneShape(canvas: Canvas, lane: TransitionLane, cx: Float, cy: Float, r: Float) {
        when (lane) {
            TransitionLane.CONFIRMED_HO -> {
                // Diamond
                val path = Path().apply {
                    moveTo(cx, cy - r * 1.2f)
                    lineTo(cx + r * 1.2f, cy)
                    lineTo(cx, cy + r * 1.2f)
                    lineTo(cx - r * 1.2f, cy)
                    close()
                }
                canvas.drawPath(path, markerPaint)
                canvas.drawPath(path, markerStrokePaint)
            }
            TransitionLane.INTRA_FREQ -> {
                // Solid Circle
                canvas.drawCircle(cx, cy, r, markerPaint)
                canvas.drawCircle(cx, cy, r, markerStrokePaint)
            }
            TransitionLane.INTER_FREQ -> {
                // Upward Triangle
                val path = Path().apply {
                    moveTo(cx, cy - r * 1.2f)
                    lineTo(cx + r * 1.1f, cy + r * 0.9f)
                    lineTo(cx - r * 1.1f, cy + r * 0.9f)
                    close()
                }
                canvas.drawPath(path, markerPaint)
                canvas.drawPath(path, markerStrokePaint)
            }
            TransitionLane.RAT_MOBILITY -> {
                // Solid Square
                val rect = RectF(cx - r, cy - r, cx + r, cy + r)
                canvas.drawRect(rect, markerPaint)
                canvas.drawRect(rect, markerStrokePaint)
            }
            TransitionLane.PING_PONG -> {
                // Hexagon
                val path = Path().apply {
                    val s = r * 1.15f
                    for (i in 0 until 6) {
                        val angle = Math.toRadians((60.0 * i - 30.0))
                        val px = cx + (s * kotlin.math.cos(angle)).toFloat()
                        val py = cy + (s * kotlin.math.sin(angle)).toFloat()
                        if (i == 0) moveTo(px, py) else lineTo(px, py)
                    }
                    close()
                }
                canvas.drawPath(path, markerPaint)
                canvas.drawPath(path, markerStrokePaint)
            }
            TransitionLane.OBSERVED_OTHER -> {
                // Inverted Triangle
                val path = Path().apply {
                    moveTo(cx, cy + r * 1.2f)
                    lineTo(cx + r * 1.1f, cy - r * 0.9f)
                    lineTo(cx - r * 1.1f, cy - r * 0.9f)
                    close()
                }
                canvas.drawPath(path, markerPaint)
                canvas.drawPath(path, markerStrokePaint)
            }
        }
    }
}
