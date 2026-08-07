package com.amr3d.preview.pro

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class DXF2DView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var model: DxfModel? = null
    val currentModel: DxfModel? get() = model
    private var snapPoints: List<FloatArray> = emptyList()

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private var snapGrid: Map<Long, List<FloatArray>> = emptyMap()
    private var snapGridCellSize = 1f
    private var snapGridMinX = 0f
    private var snapGridMinY = 0f
    private val snapRadiusDp = 12f
    private var snapRadiusPx = snapRadiusDp * density

    private val hiddenLayers = mutableSetOf<String>()
    private var lineColorGroups: Map<Int, FloatArray> = emptyMap()

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    // ══ وضع القياس المنفصل ══
    var measureModeOn = false
        set(value) {
            field = value
            if (!value) {
                tempP1 = null
                isDragPlacing = false
                dragLiveWorld = null
                draggingPoint = null
            }
            invalidate()
        }
    var onDistanceMeasured: ((Float) -> Unit)? = null

    private var tempP1: FloatArray? = null

    private data class MeasureSegment(var p1: FloatArray, var p2: FloatArray, var distMm: Float) // خليتها var عشان التعديل
    private val measureSegments = mutableListOf<MeasureSegment>()

    // جديد: لتعديل النقط
    private var draggingPoint: Pair<Int, Int>? = null // Pair<indexSegment, 0=p1 or 1=p2>
    private val pointTouchRadiusPx = 24f * density

    private val deleteButtonRadiusPx = 11f * density
    private val deleteButtonTouchRadiusPx = 22f * density
    private var deleteButtonScreenPositions: List<FloatArray> = emptyList()

    private var isDragPlacing = false
    private var dragLiveWorld: FloatArray? = null
    private var dragScreenX = 0f
    private var dragScreenY = 0f

    private val basePointRadiusDp = 5f
    private var pointRadiusPx = basePointRadiusDp * density
    private var pointRadiusAnimator: ValueAnimator? = null
    private fun animatePointRadius(grow: Boolean) {
        pointRadiusAnimator?.cancel()
        val targetSmall = basePointRadiusDp * density
        val targetLarge = (basePointRadiusDp + 6f) * density
        pointRadiusAnimator = ValueAnimator.ofFloat(pointRadiusPx, if (grow) targetLarge else targetSmall).apply {
            duration = if (grow) 90 else 180
            addUpdateListener { pointRadiusPx = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private val magnifierBorderPaint = Paint().apply {
        color = Color.parseColor("#FF8A1E")
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        isAntiAlias = true
    }
    private val magnifierCrosshairPaint = Paint().apply {
        color = Color.parseColor("#FF2F3A")
        strokeWidth = 3f * density
        isAntiAlias = true
    }
    private val magnifierLinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        isAntiAlias = true
    }

    private val defaultPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 2.2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val bgPaint = Paint().apply { color = Color.parseColor("#0D0F12") }

    fun setDxfBackgroundColor(color: Int) {
        bgPaint.color = color
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        val isLightBg = luminance > 0.55
        gridPaint.color = if (isLightBg) Color.parseColor("#D2D6DC") else Color.parseColor("#1A1F26")
        axisPaint.color = if (isLightBg) Color.parseColor("#8A9099") else Color.parseColor("#3A4048")
        invalidate()
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#1A1F26")
        strokeWidth = 1.5f * density
        isAntiAlias = false
    }

    private val axisPaint = Paint().apply {
        color = Color.parseColor("#3A4048")
        strokeWidth = 2.5f * density
        isAntiAlias = true
    }

    private val measurePointPaint = Paint().apply {
        color = Color.parseColor("#FF8A1E")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val measurePointDraggingPaint = Paint().apply { // لون النقطة وهي بتتسحب
        color = Color.parseColor("#00FF88")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val measureLinePaint = Paint().apply {
        color = Color.parseColor("#FF8A1E")
        strokeWidth = 3f * density
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f * density, 8f * density), 0f)
    }

    var currentUnit: MeasurementUnit = MeasurementUnit.MM

    private val measureTextSizeSp = 14f
    private val measureTextPaint = Paint().apply {
        color = Color.parseColor("#FF8A1E")
        textSize = measureTextSizeSp * scaledDensity
        isAntiAlias = true
        isFakeBoldText = true
    }

    private val measureLabelBgPaint = Paint().apply {
        color = Color.parseColor("#CC101216")
        isAntiAlias = true
    }

    private val deleteButtonPaint = Paint().apply {
        color = Color.parseColor("#D8342A")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val deleteButtonXPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    var showGapHighlight = false
        set(value) { field = value; invalidate() }
    var gapHighlightSegments: FloatArray? = null
        set(value) { field = value; invalidate() }

    private val gapHighlightPaint = Paint().apply {
        color = Color.parseColor("#FF2626")
        strokeWidth = 3f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val gapHighlightRadiusPx = 9f * resources.displayMetrics.density

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val focusX = detector.focusX
                val focusY = detector.focusY
                val worldX = (focusX - offsetX) / scale
                val worldY = (focusY - offsetY) / scale
                scale = (scale * detector.scaleFactor).coerceIn(0.001f, 5000f)
                offsetX = focusX - worldX * scale
                offsetY = focusY - worldY * scale
                invalidate()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (draggingPoint == null) { // ماتحركش الشاشة لو بنسحب نقطة
                    offsetX -= dx
                    offsetY -= dy
                    invalidate()
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetView()
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (measureModeOn) {
                    val segIndex = findDeleteButtonAt(e.x, e.y)
                    if (segIndex!= null) {
                        measureSegments.removeAt(segIndex)
                        invalidate()
                        return true
                    }
                    animatePointRadius(true)
                    commitMeasurePoint(resolveWorldPoint(e.x, e.y))
                    animatePointRadius(false)
                    return true
                }
                return false
            }
            override fun onLongPress(e: MotionEvent) {
                if (measureModeOn && findDeleteButtonAt(e.x, e.y) == null && draggingPoint == null) {
                    isDragPlacing = true
                    animatePointRadius(true)
                    updateDragPreview(e.x, e.y)
                }
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) {
            if (isDragPlacing) { isDragPlacing = false; dragLiveWorld = null; animatePointRadius(false) }
            if (draggingPoint!= null) { draggingPoint = null }
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            invalidate()
            return true
        }

        if (measureModeOn) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hitPoint = findPointAt(event.x, event.y)
                    if (hitPoint!= null) {
                        draggingPoint = hitPoint // ابدأ سحب النقطة
                        animatePointRadius(true)
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    draggingPoint?.let { (segIdx, pointIdx) ->
                        val newWorld = resolveWorldPoint(event.x, event.y)
                        val seg = measureSegments[segIdx]
                        if (pointIdx == 0) seg.p1 = newWorld else seg.p2 = newWorld
                        seg.distMm = hypot((seg.p2[0] - seg.p1[0]).toDouble(), (seg.p2[1] - seg.p1[1]).toDouble()).toFloat()
                        invalidate()
                        return true
                    }
                    if (isDragPlacing) updateDragPreview(event.x, event.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (draggingPoint!= null) {
                        draggingPoint = null
                        animatePointRadius(false)
                        invalidate()
                        return true
                    }
                    if (isDragPlacing) {
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            updateDragPreview(event.x, event.y)
                            dragLiveWorld?.let { commitMeasurePoint(it) }
                        }
                        isDragPlacing = false
                        dragLiveWorld = null
                        animatePointRadius(false)
                        invalidate()
                    }
                }
            }
        }

        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun findPointAt(screenX: Float, screenY: Float): Pair<Int, Int>? {
        for ((segIndex, seg) in measureSegments.withIndex()) {
            val sx1 = toScreenX(seg.p1[0]); val sy1 = toScreenY(seg.p1[1])
            val sx2 = toScreenX(seg.p2[0]); val sy2 = toScreenY(seg.p2[1])
            if (hypot((sx1 - screenX).toDouble(), (sy1 - screenY).toDouble()) <= pointTouchRadiusPx) return Pair(segIndex, 0)
            if (hypot((sx2 - screenX).toDouble(), (sy2 - screenY).toDouble()) <= pointTouchRadiusPx) return Pair(segIndex, 1)
        }
        return null
    }

    private fun findDeleteButtonAt(screenX: Float, screenY: Float): Int? {
        for ((index, pos) in deleteButtonScreenPositions.withIndex()) {
            val d = hypot((pos[0] - screenX).toDouble(), (pos[1] - screenY).toDouble()).toFloat()
            if (d <= deleteButtonTouchRadiusPx) return index
        }
        return null
    }

    private fun updateDragPreview(screenX: Float, screenY: Float) {
        dragScreenX = screenX; dragScreenY = screenY
        dragLiveWorld = resolveWorldPoint(screenX, screenY)
        invalidate()
    }

    private fun resolveWorldPoint(screenX: Float, screenY: Float): FloatArray {
        val snapped = findSnapPoint(screenX, screenY)
        if (snapped!= null) return snapped
        return floatArrayOf(screenToWorldX(screenX), screenToWorldY(screenY))
    }

    private fun commitMeasurePoint(world: FloatArray) {
        if (tempP1 == null) {
            tempP1 = world
        } else {
            val p1 = tempP1!!
            val p2 = world
            val distMm = hypot((p2[0] - p1[0]).toDouble(), (p2[1] - p1[1]).toDouble()).toFloat()
            measureSegments.add(MeasureSegment(p1, p2, distMm))
            onDistanceMeasured?.invoke(distMm * currentUnit.factorFromMm)
            tempP1 = null
        }
        invalidate()
    }

    fun clearMeasurement() {
        tempP1 = null
        measureSegments.clear()
        invalidate()
    }

    fun setModel(m: DxfModel) {
        model = m
        tempP1 = null
        measureSegments.clear()
        hiddenLayers.clear()
        showGapHighlight = false
        gapHighlightSegments = null
        refreshSnapPoints(m)
        buildRenderCache()
        post { resetView() }
    }

    private fun buildRenderCache() {
        val m = model
        if (m == null) { lineColorGroups = emptyMap(); return }
        val buckets = HashMap<Int, ArrayList<Float>>()
        fun addSegment(color: Int, x1: Float, y1: Float, x2: Float, y2: Float) {
            buckets.getOrPut(color) { ArrayList() }.apply {
                add(x1); add(y1); add(x2); add(y2)
            }
        }
        for (line in m.lines) {
            if (!isLayerVisible(line.layer)) continue
            addSegment(line.color, line.x1, line.y1, line.x2, line.y2)
        }
        for (arc in m.arcs) {
            if (!isLayerVisible(arc.layer)) continue
            val segments = 48
            var end = arc.endDeg
            if (end <= arc.startDeg) end += 360f
            val totalAngle = end - arc.startDeg
            var prevX = 0f; var prevY = 0f
            for (s in 0..segments) {
                val angle = Math.toRadians((arc.startDeg + s * totalAngle / segments).toDouble())
                val x = arc.cx + arc.r * cos(angle).toFloat()
                val y = arc.cy + arc.r * sin(angle).toFloat()
                if (s > 0) addSegment(arc.color, prevX, prevY, x, y)
                prevX = x; prevY = y
            }
        }
        lineColorGroups = buckets.mapValues { it.value.toFloatArray() }
    }

    fun getLayers(): List<String> = model?.layers?: emptyList()
    fun isColorGroup(groupKey: String): Boolean = groupKey.startsWith(DXFParser.COLOR_GROUP_PREFIX)
    fun colorGroupIndex(groupKey: String): Int = groupKey.removePrefix(DXFParser.COLOR_GROUP_PREFIX).toIntOrNull()?: 0
    fun colorForGroup(groupKey: String): Int? {
        val m = model?: return null
        if (isColorGroup(groupKey)) return m.colorGroupPalette.getOrNull(colorGroupIndex(groupKey))
        m.lines.firstOrNull { it.layer == groupKey }?.let { return it.color }
        m.arcs.firstOrNull { it.layer == groupKey }?.let { return it.color }
        m.circles.firstOrNull { it.layer == groupKey }?.let { return it.color }
        return null
    }
    fun isLayerVisible(layer: String): Boolean = layer!in hiddenLayers
    fun setLayerVisible(layer: String, visible: Boolean) {
        if (visible) hiddenLayers.remove(layer) else hiddenLayers.add(layer)
        model?.let { refreshSnapPoints(it) }
        buildRenderCache()
        invalidate()
    }

    fun totalCutLength(): Float {
        val m = model?: return 0f
        var total = 0f
        for (line in m.lines) {
            if (!isLayerVisible(line.layer)) continue
            val dx = line.x2 - line.x1; val dy = line.y2 - line.y1
            total += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        for (arc in m.arcs) {
            if (!isLayerVisible(arc.layer)) continue
            var span = arc.endDeg - arc.startDeg
            if (span < 0) span += 360f
            total += arc.r * Math.toRadians(span.toDouble()).toFloat()
        }
        for (circle in m.circles) {
            if (!isLayerVisible(circle.layer)) continue
            total += 2f * Math.PI.toFloat() * circle.r
        }
        return total
    }

    fun visibleCuttableEntityCount(): Int {
        val m = model?: return 0
        return m.lines.count { isLayerVisible(it.layer) } +
            m.arcs.count { isLayerVisible(it.layer) } +
            m.circles.count { isLayerVisible(it.layer) }
    }

    private fun buildSnapPoints(m: DxfModel): List<FloatArray> {
        val pts = mutableListOf<FloatArray>()
        for (line in m.lines) {
            if (!isLayerVisible(line.layer)) continue
            pts.add(floatArrayOf(line.x1, line.y1))
            pts.add(floatArrayOf(line.x2, line.y2))
        }
        for (circle in m.circles) {
            if (!isLayerVisible(circle.layer)) continue
            pts.add(floatArrayOf(circle.cx, circle.cy))
        }
        for (arc in m.arcs) {
            if (!isLayerVisible(arc.layer)) continue
            pts.add(floatArrayOf(arc.cx, arc.cy))
            val startRad = Math.toRadians(arc.startDeg.toDouble())
            val endRad = Math.toRadians(arc.endDeg.toDouble())
            pts.add(floatArrayOf(arc.cx + arc.r * cos(startRad).toFloat(), arc.cy + arc.r * sin(startRad).toFloat()))
            pts.add(floatArrayOf(arc.cx + arc.r * cos(endRad).toFloat(), arc.cy + arc.r * sin(endRad).toFloat()))
        }
        return pts
    }

    private fun refreshSnapPoints(m: DxfModel) {
        snapPoints = buildSnapPoints(m)
        buildSnapGrid()
    }

    private var snapGridGeneration = 0
    private fun buildSnapGrid() {
        val pts = snapPoints
        val myGeneration = ++snapGridGeneration
        if (pts.isEmpty()) { snapGrid = emptyMap(); return }
        val ptsCopy = ArrayList<FloatArray>(pts)
        Thread {
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (p in ptsCopy) {
                if (p[0] < minX) minX = p[0]; if (p[1] < minY) minY = p[1]
                if (p[0] > maxX) maxX = p[0]; if (p[1] > maxY) maxY = p[1]
            }
            val diag = hypot((maxX - minX).toDouble(), (maxY - minY).toDouble()).toFloat().coerceAtLeast(1e-3f)
            val cellsPerAxis = maxOf(4, kotlin.math.ceil(kotlin.math.sqrt(ptsCopy.size.toDouble())).toInt())
            val cellSize = (diag / cellsPerAxis).coerceAtLeast(1e-4f)
            val buckets = HashMap<Long, MutableList<FloatArray>>()
            for (p in ptsCopy) {
                val cx = ((p[0] - minX) / cellSize).toInt()
                val cy = ((p[1] - minY) / cellSize).toInt()
                val key = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)
                buckets.getOrPut(key) { ArrayList() }.add(p)
            }
            val finalMap: Map<Long, List<FloatArray>> = buckets.mapValues { it.value.toList() }
            post {
                if (myGeneration!= snapGridGeneration) return@post
                snapGridCellSize = cellSize
                snapGridMinX = minX
                snapGridMinY = minY
                snapGrid = finalMap
                invalidate()
            }
        }.start()
    }

    private fun screenToWorldX(sx: Float) = (sx - offsetX) / scale
    private fun screenToWorldY(sy: Float) = (offsetY - sy) / scale

    private fun findSnapPoint(screenX: Float, screenY: Float): FloatArray? {
        if (snapGrid.isEmpty()) return null
        val worldX = screenToWorldX(screenX)
        val worldY = screenToWorldY(screenY)
        val radiusWorld = snapRadiusPx / scale.coerceAtLeast(1e-6f)
        val cellSpan = kotlin.math.ceil(radiusWorld / snapGridCellSize).toInt().coerceAtLeast(1)
        val centerCx = ((worldX - snapGridMinX) / snapGridCellSize).toInt()
        val centerCy = ((worldY - snapGridMinY) / snapGridCellSize).toInt()
        var closest: FloatArray? = null
        var closestDist = snapRadiusPx
        for (dcx in -cellSpan..cellSpan) {
            for (dcy in -cellSpan..cellSpan) {
                val key = ((centerCx + dcx).toLong() shl 32) or ((centerCy + dcy).toLong() and 0xffffffffL)
                val bucket = snapGrid[key]?: continue
                for (p in bucket) {
                    val sx = toScreenX(p[0])
                    val sy = toScreenY(p[1])
                    val d = hypot((sx - screenX).toDouble(), (sy - screenY).toDouble()).toFloat()
                    if (d < closestDist) { closestDist = d; closest = p }
                }
            }
        }
        return closest
    }

    fun clear() {
        model = null
        tempP1 = null
        measureSegments.clear()
        snapPoints = emptyList()
        snapGrid = emptyMap()
        hiddenLayers.clear()
        showGapHighlight = false
        gapHighlightSegments = null
        lineColorGroups = emptyMap()
        invalidate()
    }

    fun resetView() {
        val m = model?: return
        if (width == 0 || height == 0) return
        val w = (m.maxX - m.minX).let { if (it <= 0f) 1f else it }
        val h = (m.maxY - m.minY).let { if (it <= 0f) 1f else it }
        val padding = 0.9f
        val scaleX = (width * padding) / w
        val scaleY = (height * padding) / h
        scale = minOf(scaleX, scaleY)
        val centerX = (m.minX + m.maxX) / 2f
        val centerY = (m.minY + m.maxY) / 2f
        offsetX = width / 2f - centerX * scale
        offsetY = height / 2f + centerY * scale
        invalidate()
    }

    private fun toScreenX(x: Float) = offsetX + x * scale
    private fun toScreenY(y: Float) = offsetY - y * scale

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        drawGrid(canvas)
        val m = model?: return
        for ((color, modelCoords) in lineColorGroups) {
            val screenCoords = FloatArray(modelCoords.size)
            var i = 0
            while (i < modelCoords.size) {
                screenCoords[i] = toScreenX(modelCoords[i])
                screenCoords[i + 1] = toScreenY(modelCoords[i + 1])
                i += 2
            }
            defaultPaint.color = color
            canvas.drawLines(screenCoords, defaultPaint)
        }
        for (circle in m.circles) {
            if (!isLayerVisible(circle.layer)) continue
            defaultPaint.color = circle.color
            canvas.drawCircle(toScreenX(circle.cx), toScreenY(circle.cy), circle.r * scale, defaultPaint)
        }
        drawMeasurement(canvas)
        drawGapHighlight(canvas)
    }

    private fun drawGapHighlight(canvas: Canvas) {
        if (!showGapHighlight) return
        val segs = gapHighlightSegments?: return
        var i = 0
        while (i + 3 < segs.size) {
            canvas.drawCircle(toScreenX(segs[i]), toScreenY(segs[i + 1]), gapHighlightRadiusPx, gapHighlightPaint)
            canvas.drawCircle(toScreenX(segs[i + 2]), toScreenY(segs[i + 3]), gapHighlightRadiusPx, gapHighlightPaint)
            i += 4
        }
    }

    private fun drawMeasurement(canvas: Canvas) {
        val newHitPositions = ArrayList<FloatArray>(measureSegments.size)
        for ((segIndex, seg) in measureSegments.withIndex()) {
            val sx1 = toScreenX(seg.p1[0]); val sy1 = toScreenY(seg.p1[1])
            val sx2 = toScreenX(seg.p2[0]); val sy2 = toScreenY(seg.p2[1])

            val p1Paint = if (draggingPoint == Pair(segIndex, 0)) measurePointDraggingPaint else measurePointPaint
            val p2Paint = if (draggingPoint == Pair(segIndex, 1)) measurePointDraggingPaint else measurePointPaint

            canvas.drawCircle(sx1, sy1, pointRadiusPx, p1Paint)
            canvas.drawCircle(sx2, sy2, pointRadiusPx, p2Paint)
            val deleteBtnPos = drawMeasureLine(canvas, sx1, sy1, sx2, sy2, seg.distMm, drawDeleteButton = true)
            newHitPositions.add(deleteBtnPos)
        }
        deleteButtonScreenPositions = newHitPositions

        tempP1?.let { p1 ->
            val sx1 = toScreenX(p1[0]); val sy1 = toScreenY(p1[1])
            canvas.drawCircle(sx1, sy1, pointRadiusPx, measurePointPaint)
            if (isDragPlacing) {
                dragLiveWorld?.let { live ->
                    val sxLive = toScreenX(live[0]); val syLive = toScreenY(live[1])
                    val distMm = hypot((live[0] - p1[0]).toDouble(), (live[1] - p1[1]).toDouble()).toFloat()
                    drawMeasureLine(canvas, sx1, sy1, sxLive, syLive, distMm, drawDeleteButton = false)
                }
            }
        }

        if (isDragPlacing) {
            dragLiveWorld?.let { live -> drawMagnifier(canvas, live[0], live[1]) }
        }
    }

    private fun drawMeasureLine(canvas: Canvas, sx1: Float, sy1: Float, sx2: Float, sy2: Float, distMm: Float, drawDeleteButton: Boolean): FloatArray {
        canvas.drawLine(sx1, sy1, sx2, sy2, measureLinePaint)
        val displayDist = distMm * currentUnit.factorFromMm
        val midX = (sx1 + sx2) / 2f
        val midY = (sy1 + sy2) / 2f
        val label = "%.2f %s".format(displayDist, resources.getString(currentUnit.labelRes))
        val lineDx = sx2 - sx1; val lineDy = sy2 - sy1
        val lineLen = hypot(lineDx.toDouble(), lineDy.toDouble()).toFloat().let { if (it < 1f) 1f else it }
        var perpX = -lineDy / lineLen
        var perpY = lineDx / lineLen
        if (perpY > 0f) { perpX = -perpX; perpY = -perpY }
        val labelOffset = 34f * density
        val labelX = midX + perpX * labelOffset
        val labelY = midY + perpY * labelOffset
        val textWidth = measureTextPaint.measureText(label)
        val fm = measureTextPaint.fontMetrics
        val padH = 10f * density; val padV = 6f * density
        val bgLeft = labelX - textWidth / 2f - padH
        val bgTop = labelY + fm.ascent - padV
        val bgRight = labelX + textWidth / 2f + padH
        val bgBottom = labelY + fm.descent + padV
        canvas.drawRoundRect(bgLeft, bgTop, bgRight, bgBottom, 8f * density, 8f * density, measureLabelBgPaint)
        canvas.drawText(label, labelX - textWidth / 2f, labelY, measureTextPaint)
        val btnX = bgRight + deleteButtonRadiusPx + 6f * density
        val btnY = (bgTop + bgBottom) / 2f
        if (drawDeleteButton) {
            canvas.drawCircle(btnX, btnY, deleteButtonRadiusPx, deleteButtonPaint)
            val xSize = deleteButtonRadiusPx * 0.45f
            canvas.drawLine(btnX - xSize, btnY - xSize, btnX + xSize, btnY + xSize, deleteButtonXPaint)
            canvas.drawLine(btnX - xSize, btnY + xSize, btnX + xSize, btnY - xSize, deleteButtonXPaint)
        }
        return floatArrayOf(btnX, btnY)
    }

    // ══ العدسة فوق شمال واعلى ══
    private fun drawMagnifier(canvas: Canvas, worldX: Float, worldY: Float) {
        val radius = 62f * density
        val margin = 16f * density
        val extraTopOffset = 220f * density // رفعناها اكتر عشان تبعد عن الكارت والصباع

        val magCenterX = margin + radius
        val magCenterY = margin + radius + extraTopOffset

        val zoom = 3.5f
        val magScale = scale * zoom
        canvas.save()
        val clipPath = Path().apply {
            addCircle(magCenterX, magCenterY, radius, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)
        canvas.drawCircle(magCenterX, magCenterY, radius, bgPaint)
        for ((color, modelCoords) in lineColorGroups) {
            val magCoords = FloatArray(modelCoords.size)
            var i = 0
            while (i < modelCoords.size) {
                magCoords[i] = magCenterX + (modelCoords[i] - worldX) * magScale
                magCoords[i + 1] = magCenterY - (modelCoords[i + 1] - worldY) * magScale
                i += 2
            }
            magnifierLinePaint.color = color
            canvas.drawLines(magCoords, magnifierLinePaint)
        }
        model?.circles?.forEach { c ->
            if (!isLayerVisible(c.layer)) return@forEach
            magnifierLinePaint.color = c.color
            canvas.drawCircle(magCenterX + (c.cx - worldX) * magScale, magCenterY - (c.cy - worldY) * magScale, c.r * magScale, magnifierLinePaint)
        }
        canvas.restore()
        canvas.drawCircle(magCenterX, magCenterY, radius, magnifierBorderPaint)
        canvas.drawLine(magCenterX - 16f * density, magCenterY, magCenterX + 16f * density, magCenterY, magnifierCrosshairPaint)
        canvas.drawLine(magCenterX, magCenterY - 16f * density, magCenterX, magCenterY + 16f * density, magnifierCrosshairPaint)
    }

    private fun drawGrid(canvas: Canvas) {
        if (scale <= 0f) return
        var step = 10f
        val minPixelStep = 40f
        while (step * scale < minPixelStep) step *= 10f
        while (step * scale > minPixelStep * 10f) step /= 10f
        val worldLeft = (0 - offsetX) / scale
        val worldRight = (width - offsetX) / scale
        val worldTop = (offsetY - 0) / scale
        val worldBottom = (offsetY - height) / scale
        var gx = (Math.floor((worldLeft / step).toDouble()) * step).toFloat()
        while (gx <= worldRight) {
            canvas.drawLine(toScreenX(gx), 0f, toScreenX(gx), height.toFloat(), gridPaint)
            gx += step
        }
        var gy = (Math.floor((worldBottom / step).toDouble()) * step).toFloat()
        while (gy <= worldTop) {
            canvas.drawLine(0f, toScreenY(gy), width.toFloat(), toScreenY(gy), gridPaint)
            gy += step
        }
        canvas.drawLine(toScreenX(0f), 0f, toScreenX(0f), height.toFloat(), axisPaint)
        canvas.drawLine(0f, toScreenY(0f), width.toFloat(), toScreenY(0f), axisPaint)
    }
}