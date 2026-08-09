package com.amr3d.preview.pro

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

class GLViewerView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {

    val stlRenderer = STLRenderer()

    // ===== مؤشرات اللمس =====
    private var previousX = 0f
    private var previousY = 0f
    private var previousSpan = 0f
    private var previousAngle = 0f
    private var lastTouchCount = 0
    private var moved = false

    // ===== Callbacks =====
    var onSingleTap: ((Float, Float) -> Unit)? = null
    var onMeasureDrag: ((Float, Float) -> Unit)? = null
    var onAutoRotateStopped: (() -> Unit)? = null
    var onLongPressPivot: ((Float, Float) -> Unit)? = null
    var onDistanceMeasured: ((Float) -> Unit)? = null

    var measurementModeActive = false
        set(value) {
            field = value
            if (!value) clearMeasurement()
        }

    var lightModeActive = false

    // ===== نظام القياس =====
    private var tempP1: FloatArray? = null
    private data class MeasureSegment3D(var p1: FloatArray, var p2: FloatArray, var distMm: Float)
    private val measureSegments = mutableListOf<MeasureSegment3D>()
    private var draggingPoint: Pair<Int, Int>? = null
    private var isDragPlacing = false
    private var dragLiveWorld: FloatArray? = null

    // ===== ✅ مصفوفات قابلة لإعادة الاستخدام (تقليل GC) =====
    private val reusableWorldPoint = FloatArray(3)
    private val reusableP1Screen = FloatArray(2)
    private val reusableP2Screen = FloatArray(2)
    private val avgOut = FloatArray(2)

    // ===== ✅ حالة الرندر الذكية =====
    private var needsRender = true
    private val touchSlopSq = 1f  // مربع الحد الأدنى للحركة

    // ===== الضغط المطوّل =====
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        // ✅ Haptic Feedback عند الضغط المطوّل
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPressPivot?.invoke(pendingPivotX, pendingPivotY)
    }
    private var pendingPivotX = 0f
    private var pendingPivotY = 0f
    private var longPressTriggered = false

    companion object {
        private const val LONG_PRESS_TIMEOUT_MS = 500L
        private const val LONG_PRESS_CANCEL_SLOP = 20f
        private const val LONG_PRESS_CANCEL_SLOP_SQ = LONG_PRESS_CANCEL_SLOP * LONG_PRESS_CANCEL_SLOP
        private const val TOUCH_RADIUS = 50f
        private const val TOUCH_RADIUS_SQ = TOUCH_RADIUS * TOUCH_RADIUS
        private const val BASE_PAN_FACTOR = 0.003f      // ✅ القيمة الأساسية (هتتعدل حسب الـ scale)
        private const val ROT_FACTOR = 0.5f
        private const val ROTATION_DAMPING = 0.85f     // ✅ damping بسيط لجعل الحركة أنعم
    }

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(stlRenderer)
        // ✅ التحسين الأهم: ارسم فقط عند الحاجة
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    // ===== ✅ دوال مساعدة للرندر الذكي =====
    private fun markDirty() {
        needsRender = true
        requestRender()
    }

    /** يبدأ الرندرة المستمرة أثناء تفاعل المستخدم */
    private fun startContinuousRender() {
        if (renderMode != RENDERMODE_CONTINUOUSLY) {
            renderMode = RENDERMODE_CONTINUOUSLY
        }
    }

    /** يوقف الرندرة المستمرة بعد انتهاء التفاعل */
    private fun stopContinuousRender() {
        if (renderMode != RENDERMODE_WHEN_DIRTY) {
            renderMode = RENDERMODE_WHEN_DIRTY
        }
        requestRender() // رندر أخير للاستقرار
    }

    private fun isAwaitingSecondMeasurePoint() = measurementModeActive && tempP1 != null

    private fun rotationSensitivityFactor(): Float =
        (1f / stlRenderer.scaleFactor).coerceIn(0.15f, 1f)

    /** ✅ حساسية الـ Pan تعتمد على الـ scale (أبطأ لما تكون مكبر) */
    private fun panFactor(): Float =
        BASE_PAN_FACTOR / stlRenderer.scaleFactor.coerceAtLeast(0.15f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (lightModeActive) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleActionDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> handleActionPointerDown(event)
            MotionEvent.ACTION_MOVE -> handleActionMove(event)
            MotionEvent.ACTION_POINTER_UP -> handleActionPointerUp(event)
            MotionEvent.ACTION_UP -> handleActionUp(event)
            MotionEvent.ACTION_CANCEL -> handleActionCancel()
        }
        return true
    }

    private fun handleActionDown(event: MotionEvent) {
        previousX = event.x
        previousY = event.y
        moved = false
        lastTouchCount = 1
        stlRenderer.showPivotIndicator = false
        stlRenderer.isUserInteracting = true

        // ✅ ابدأ الرندر المستمر أثناء التفاعل
        startContinuousRender()

        if (stlRenderer.autoRotate) {
            stlRenderer.autoRotate = false
            onAutoRotateStopped?.invoke()
        }

        if (measurementModeActive) {
            val hit = findPointAt(event.x, event.y)
            if (hit != null) {
                draggingPoint = hit
                isDragPlacing = true
                val seg = measureSegments[hit.first]
                dragLiveWorld = if (hit.second == 0) seg.p1 else seg.p2
                return
            }
        } else {
            pendingPivotX = event.x
            pendingPivotY = event.y
            longPressTriggered = false
            removeCallbacks(longPressRunnable)
            postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
        }
    }

    private fun handleActionPointerDown(event: MotionEvent) {
        removeCallbacks(longPressRunnable)
        lastTouchCount = event.pointerCount
        // ✅ حساب مرة واحدة بس بدل 4 مرات
        computeAverageInto(event, avgOut)
        previousX = avgOut[0]
        previousY = avgOut[1]
        previousSpan = currentSpan(event)
        previousAngle = currentAngle(event)
    }

    private fun handleActionMove(event: MotionEvent) {
        // ✅ حساب الأفريدج مرة واحدة
        computeAverageInto(event, avgOut)
        val curX = avgOut[0]
        val curY = avgOut[1]
        val dx = curX - previousX
        val dy = curY - previousY

        // ✅ استخدام مربع المسافة (أسرع من abs مرتين + مقارنة)
        if (dx * dx + dy * dy > touchSlopSq) moved = true

        if (event.pointerCount == 1) {
            val ddx = event.x - pendingPivotX
            val ddy = event.y - pendingPivotY
            if (ddx * ddx + ddy * ddy > LONG_PRESS_CANCEL_SLOP_SQ) {
                removeCallbacks(longPressRunnable)
            }
        }

        // سحب الدبوس
        val dp = draggingPoint
        if (dp != null) {
            // ✅ استخدم المصفوفة القابلة لإعادة الاستخدام + حماية من null
            val world = resolveWorldPointInto(event.x, event.y, reusableWorldPoint)
            if (world != null) {
                val seg = measureSegments[dp.first]
                // نسخ للـ segment (لسه محتاجين نسخة ثابتة)
                val target = if (dp.second == 0) seg.p1 else seg.p2
                target[0] = world[0]
                target[1] = world[1]
                target[2] = world[2]
                seg.distMm = distance3D(seg.p1, seg.p2)
                dragLiveWorld = target
                markDirty()
            }
            return
        }

        if (event.pointerCount == 1 && isAwaitingSecondMeasurePoint()) {
            onMeasureDrag?.invoke(event.x, event.y)
            previousX = curX
            previousY = curY
            return
        }

        var changed = false
        val currentPanFactor = panFactor()   // ✅ حساسية ديناميكية

        if (event.pointerCount >= 2) {
            stlRenderer.showPivotIndicator = false

            val curSpan = currentSpan(event)
            if (previousSpan > 10f && curSpan > 10f) {
                stlRenderer.applyPinchZoom(curX, curY, curSpan / previousSpan)
                changed = true
            }
            previousSpan = curSpan

            // ✅ التدوير مقفول أثناء وضع القياس (طلب المستخدم)
            if (!measurementModeActive) {
                val curAngle = currentAngle(event)
                var angleDelta = curAngle - previousAngle
                if (angleDelta > 180f) angleDelta -= 360f
                else if (angleDelta < -180f) angleDelta += 360f

                if (abs(angleDelta) > 0.3f) {
                    // ✅ damping بسيط لجعل الحركة أنعم
                    val dampedDelta = angleDelta * ROTATION_DAMPING
                    stlRenderer.rotationY += dampedDelta * 1.5f * rotationSensitivityFactor()
                    changed = true
                }
                previousAngle = curAngle
            }

            if (dx != 0f || dy != 0f) {
                stlRenderer.panX += dx * currentPanFactor
                stlRenderer.panY -= dy * currentPanFactor
                changed = true
            }
        } else if (measurementModeActive) {
            // في وضع القياس: نسمح بالـ Pan فقط
            if (dx != 0f || dy != 0f) {
                stlRenderer.panX += dx * currentPanFactor
                stlRenderer.panY -= dy * currentPanFactor
                changed = true
            }
        } else {
            stlRenderer.showPivotIndicator = true
            val rotFactor = rotationSensitivityFactor()

            if (dx != 0f) {
                stlRenderer.rotationY += dx * ROT_FACTOR * rotFactor * ROTATION_DAMPING
                changed = true
            }
            if (dy != 0f) {
                stlRenderer.rotationX = (stlRenderer.rotationX + dy * ROT_FACTOR * rotFactor * ROTATION_DAMPING)
                    .coerceIn(-90f, 90f)
                changed = true
            }
        }

        previousX = curX
        previousY = curY

        // ✅ اطلب رندر بس لو في تغيير فعلي
        if (changed) markDirty()
    }

    private fun handleActionPointerUp(event: MotionEvent) {
        val liftedIndex = event.actionIndex
        var sumX = 0f
        var sumY = 0f
        var remaining = 0
        val count = event.pointerCount

        for (i in 0 until count) {
            if (i == liftedIndex) continue
            sumX += event.getX(i)
            sumY += event.getY(i)
            remaining++
        }

        lastTouchCount = if (remaining < 1) 1 else remaining

        if (remaining > 0) {
            previousX = sumX / remaining
            previousY = sumY / remaining
        }

        stlRenderer.showPivotIndicator = false
        markDirty()
    }

    private fun handleActionUp(event: MotionEvent) {
        removeCallbacks(longPressRunnable)
        stlRenderer.showPivotIndicator = false
        stlRenderer.isUserInteracting = false

        if (draggingPoint != null) {
            draggingPoint = null
            dragLiveWorld = null
            isDragPlacing = false
            markDirty()
            stopContinuousRender()
            return
        }

        if (measurementModeActive && lastTouchCount == 1) {
            val world = resolveWorldPointCopy(event.x, event.y)
            // ✅ حماية من null: مش هنحط نقطة لو فشل الـ unproject
            if (world != null) {
                commitMeasurePoint(world)
            }
        } else if (lastTouchCount == 1 && !moved) {
            onSingleTap?.invoke(event.x, event.y)
        }

        // ✅ أوقف الرندر المستمر بعد انتهاء التفاعل
        stopContinuousRender()
    }

    private fun handleActionCancel() {
        removeCallbacks(longPressRunnable)
        draggingPoint = null
        dragLiveWorld = null
        isDragPlacing = false
        stlRenderer.showPivotIndicator = false
        stlRenderer.isUserInteracting = false
        stopContinuousRender()
    }

    /**
     * ✅ بحث مُحسّن عن الدبوس - يستخدم مربع المسافة (بدون sqrt)
     * ومصفوفات قابلة لإعادة الاستخدام
     */
    private fun findPointAt(screenX: Float, screenY: Float): Pair<Int, Int>? {
        val segments = measureSegments
        val size = segments.size

        for (i in 0 until size) {
            val seg = segments[i]

            stlRenderer.projectToScreenInto(seg.p1, reusableP1Screen)
            val dx1 = reusableP1Screen[0] - screenX
            val dy1 = reusableP1Screen[1] - screenY
            if (dx1 * dx1 + dy1 * dy1 <= TOUCH_RADIUS_SQ) return Pair(i, 0)

            stlRenderer.projectToScreenInto(seg.p2, reusableP2Screen)
            val dx2 = reusableP2Screen[0] - screenX
            val dy2 = reusableP2Screen[1] - screenY
            if (dx2 * dx2 + dy2 * dy2 <= TOUCH_RADIUS_SQ) return Pair(i, 1)
        }
        return null
    }

    /**
     * ✅ يكتب النقطة في مصفوفة موجودة (لا تخصيص)
     * يرجع null لو فشل الـ unproject
     */
    private fun resolveWorldPointInto(screenX: Float, screenY: Float, out: FloatArray): FloatArray? {
        val result = stlRenderer.unprojectRay(screenX, screenY) ?: return null
        out[0] = result[0]
        out[1] = result[1]
        out[2] = result[2]
        return out
    }

    /**
     * ✅ ينشئ نسخة جديدة (للاستخدام في التخزين الدائم)
     * يرجع null لو فشل الـ unproject
     */
    private fun resolveWorldPointCopy(screenX: Float, screenY: Float): FloatArray? {
        val result = stlRenderer.unprojectRay(screenX, screenY) ?: return null
        return floatArrayOf(result[0], result[1], result[2])
    }

    private fun commitMeasurePoint(world: FloatArray) {
        if (tempP1 == null) {
            tempP1 = world
            // ✅ Haptic عند وضع النقطة الأولى
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } else {
            val p1 = tempP1!!
            val distMm = distance3D(p1, world)
            measureSegments.add(MeasureSegment3D(p1, world, distMm))
            onDistanceMeasured?.invoke(distMm)
            tempP1 = null
            // ✅ Haptic عند تأكيد المسافة
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
        markDirty()
    }

    fun clearMeasurement() {
        tempP1 = null
        measureSegments.clear()
        markDirty()
    }

    fun getMeasureSegments(): List<MeasureSegment3D> = measureSegments.toList()

    // ✅ حساب صحيح للمسافة ثلاثية الأبعاد (hypot بمعاملين + sqrt)
    private fun distance3D(a: FloatArray, b: FloatArray): Float {
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val dz = b[2] - a[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun currentSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return hypot(dx, dy)
    }

    private fun currentAngle(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    /** ✅ يحسب المتوسط ويكتبه في out (بدل استدعاء دالتين) */
    private fun computeAverageInto(event: MotionEvent, out: FloatArray) {
        var totalX = 0f
        var totalY = 0f
        val count = event.pointerCount
        for (i in 0 until count) {
            totalX += event.getX(i)
            totalY += event.getY(i)
        }
        out[0] = totalX / count
        out[1] = totalY / count
    }
}