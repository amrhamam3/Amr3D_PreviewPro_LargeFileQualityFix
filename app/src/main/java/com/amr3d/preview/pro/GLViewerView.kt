package com.amr3d.preview.pro

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class GLViewerView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {

    val stlRenderer = STLRenderer()

    private var previousX = 0f
    private var previousY = 0f
    private var previousSpan = 0f
    private var previousAngle = 0f
    private var lastTouchCount = 0
    private var moved = false

    var onSingleTap: ((Float, Float) -> Unit)? = null

    var measurementModeActive = false
        set(value) {
            field = value
            if (!value) {
                clearMeasurement() // امسح القياسات اول ما نطلع
            }
        }

    var lightModeActive = false
    var onMeasureDrag: ((Float, Float) -> Unit)? = null
    var onAutoRotateStopped: (() -> Unit)? = null
    var onLongPressPivot: ((Float, Float) -> Unit)? = null
    var onDistanceMeasured: ((Float) -> Unit)? = null

    // نظام القياس
    private var tempP1: FloatArray? = null
    private data class MeasureSegment3D(var p1: FloatArray, var p2: FloatArray, var distMm: Float)
    private val measureSegments = mutableListOf<MeasureSegment3D>()
    private var draggingPoint: Pair<Int, Int>? = null
    private var isDragPlacing = false
    private var dragLiveWorld: FloatArray? = null

    private val longPressRunnable = Runnable {
        longPressTriggered = true
        onLongPressPivot?.invoke(pendingPivotX, pendingPivotY)
    }
    private var pendingPivotX = 0f
    private var pendingPivotY = 0f
    private var longPressTriggered = false

    companion object {
        private const val LONG_PRESS_TIMEOUT_MS = 500L
        private const val LONG_PRESS_CANCEL_SLOP = 20f
    }

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(stlRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private fun isAwaitingSecondMeasurePoint() = measurementModeActive && tempP1!= null
    private fun rotationSensitivityFactor(): Float = (1f / stlRenderer.scaleFactor).coerceIn(0.15f, 1f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (lightModeActive) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousX = event.x; previousY = event.y; moved = false; lastTouchCount = 1
                stlRenderer.showPivotIndicator = false; stlRenderer.isUserInteracting = true
                if (stlRenderer.autoRotate) { stlRenderer.autoRotate = false; onAutoRotateStopped?.invoke() }

                if (measurementModeActive) {
                    val hit = findPointAt(event.x, event.y)
                    if (hit!= null) {
                        draggingPoint = hit; isDragPlacing = true
                        val seg = measureSegments[hit.first]
                        dragLiveWorld = if (hit.second == 0) seg.p1 else seg.p2
                        return true
                    }
                } else {
                    pendingPivotX = event.x; pendingPivotY = event.y; longPressTriggered = false
                    removeCallbacks(longPressRunnable); postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPressRunnable)
                lastTouchCount = event.pointerCount
                previousX = averageX(event); previousY = averageY(event)
                previousSpan = currentSpan(event); previousAngle = currentAngle(event)
            }

            MotionEvent.ACTION_MOVE -> {
                val curX = averageX(event); val curY = averageY(event)
                val dx = curX - previousX; val dy = curY - previousY
                if (abs(dx) > 1f || abs(dy) > 1f) moved = true

                if (event.pointerCount == 1) {
                    val distFromDown = hypot(event.x - pendingPivotX, event.y - pendingPivotY)
                    if (distFromDown > LONG_PRESS_CANCEL_SLOP) removeCallbacks(longPressRunnable)
                }

                // سحب الدبوس
                draggingPoint?.let {
                    dragLiveWorld = resolveWorldPoint(event.x, event.y)
                    val seg = measureSegments[it.first]
                    if (it.second == 0) seg.p1 = dragLiveWorld!! else seg.p2 = dragLiveWorld!!
                    seg.distMm = distance3D(seg.p1, seg.p2)
                    requestRender(); return true
                }

                if (event.pointerCount == 1 && isAwaitingSecondMeasurePoint()) {
                    onMeasureDrag?.invoke(event.x, event.y); previousX = curX; previousY = curY; return true
                }

                if (event.pointerCount >= 2) {
                    stlRenderer.showPivotIndicator = false
                    val curSpan = currentSpan(event)
                    if (previousSpan > 10f && curSpan > 10f) {
                        stlRenderer.applyPinchZoom(curX, curY, curSpan / previousSpan)
                    }
                    previousSpan = curSpan

                    if (!measurementModeActive) {
                        val curAngle = currentAngle(event)
                        val angleDelta = curAngle - previousAngle
                        val normAngle = when { angleDelta > 180f -> angleDelta - 360f; angleDelta < -180f -> angleDelta + 360f; else -> angleDelta }
                        if (abs(normAngle) > 0.3f) stlRenderer.rotationY += normAngle * 1.5f * rotationSensitivityFactor()
                        previousAngle = curAngle
                    }
                    stlRenderer.panX += dx * 0.003f; stlRenderer.panY -= dy * 0.003f

                } else if (measurementModeActive) {
                    stlRenderer.panX += dx * 0.003f; stlRenderer.panY -= dy * 0.003f
                } else {
                    stlRenderer.showPivotIndicator = true
                    val rotFactor = rotationSensitivityFactor()
                    stlRenderer.rotationY += dx * 0.5f * rotFactor
                    stlRenderer.rotationX += dy * 0.5f * rotFactor
                    stlRenderer.rotationX = stlRenderer.rotationX.coerceIn(-90f, 90f)
                }
                previousX = curX; previousY = curY
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val liftedIndex = event.actionIndex; var sumX = 0f; var sumY = 0f; var remaining = 0
                for (i in 0 until event.pointerCount) { if (i == liftedIndex) continue; sumX += event.getX(i); sumY += event.getY(i); remaining++ }
                lastTouchCount = remaining.coerceAtLeast(1)
                if (remaining > 0) { previousX = sumX / remaining; previousY = sumY / remaining }
                stlRenderer.showPivotIndicator = false
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                stlRenderer.showPivotIndicator = false
                stlRenderer.isUserInteracting = false

                if (draggingPoint!= null) { draggingPoint = null; dragLiveWorld = null; isDragPlacing = false; requestRender(); return true }

                if (measurementModeActive && lastTouchCount == 1) {
                    commitMeasurePoint(resolveWorldPoint(event.x, event.y))
                } else if (lastTouchCount == 1 &&!moved) {
                    onSingleTap?.invoke(event.x, event.y)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                draggingPoint = null; dragLiveWorld = null; isDragPlacing = false
                stlRenderer.showPivotIndicator = false; stlRenderer.isUserInteracting = false
            }
        }
        return true
    }

    private fun findPointAt(screenX: Float, screenY: Float): Pair<Int, Int>? {
        val touchRadius = 50f
        for ((segIndex, seg) in measureSegments.withIndex()) {
            val p1Screen = stlRenderer.projectToScreen(seg.p1)
            val p2Screen = stlRenderer.projectToScreen(seg.p2)
            if (hypot((p1Screen[0] - screenX).toDouble(), (p1Screen[1] - screenY).toDouble()) <= touchRadius) return Pair(segIndex, 0)
            if (hypot((p2Screen[0] - screenX).toDouble(), (p2Screen[1] - screenY).toDouble()) <= touchRadius) return Pair(segIndex, 1)
        }
        return null
    }

    private fun resolveWorldPoint(screenX: Float, screenY: Float): FloatArray {
        return stlRenderer.unprojectRay(screenX, screenY)?: floatArrayOf(0f, 0f, 0f)
    }

    private fun commitMeasurePoint(world: FloatArray) {
        if (tempP1 == null) { tempP1 = world } else {
            val p1 = tempP1!!; val p2 = world
            val distMm = distance3D(p1, p2)
            measureSegments.add(MeasureSegment3D(p1, p2, distMm))
            onDistanceMeasured?.invoke(distMm); tempP1 = null
        }
        requestRender()
    }

    fun clearMeasurement() { tempP1 = null; measureSegments.clear(); requestRender() }
    fun getMeasureSegments() = measureSegments
    private fun distance3D(a: FloatArray, b: FloatArray) = hypot((b[0]-a[0]).toDouble(), (b[1]-a[1]).toDouble(), (b[2]-a[2]).toDouble()).toFloat()
    private fun currentSpan(event: MotionEvent): Float { if (event.pointerCount < 2) return 0f; val dx = event.getX(0) - event.getX(1); val dy = event.getY(0) - event.getY(1); return hypot(dx, dy) }
    private fun currentAngle(event: MotionEvent): Float { if (event.pointerCount < 2) return 0f; val dx = event.getX(1) - event.getX(0); val dy = event.getY(1) - event.getY(0); return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() }
    private fun averageX(event: MotionEvent): Float { var total = 0f; for (i in 0 until event.pointerCount) total += event.getX(i); return total / event.pointerCount }
    private fun averageY(event: MotionEvent): Float { var total = 0f; for (i in 0 until event.pointerCount) total += event.getY(i); return total / event.pointerCount }
}