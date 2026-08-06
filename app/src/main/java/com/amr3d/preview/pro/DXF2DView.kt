package com.amr3d.preview.pro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * شاشة عرض DXF ثنائية الأبعاد حقيقية — بترسم بكانفاس 2D مباشرة بألوان الطبقات الحقيقية
 * (مش بتحوّل الخطوط لمثلثات وتعرضها في محرك 3D زي ما كان بيحصل قبل كده).
 * بتدعم: تكبير بإصبعين (pinch zoom)، تحريك بإصبع واحد (pan)، ضبط تلقائي للعرض (fit to view)،
 * وأداة قياس مسافة حقيقية بين نقطتين (وضع القياس).
 */
class DXF2DView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var model: DxfModel? = null
    val currentModel: DxfModel? get() = model
    private var snapPoints: List<FloatArray> = emptyList() // كل نقاط النهايات/المراكز القابلة للالتقاط [x, y]

    // شاشة القياسات والكثافة
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    /** ⚠️ نفس فلسفة إصلاح الخطوط بالظبط (شوف lineColorGroups فوق) — بلاغ Amr:
     * "بيهنج لما بضغط على القياس". السبب: drawMeasurement() كان بيعمل loop على
     * **كل** نقاط الالتقاط (ممكن توصل لمئات الآلاف في ملف تقيل) في كل فريم
     * رسم واحد طول ما وضع القياس مفعّل — حتى لو أغلبها خارج حدود الشاشة
     * المرئية فعليًا. الحل: فهرسة النقاط في شبكة مكانية (Grid) مرة واحدة بس
     * (buildSnapGrid)، وفي وقت الرسم بنستعلم بس عن الخلايا اللي فعليًا متقاطعة
     * مع حدود الشاشة الحالية — مش كل نقطة في الملف كله. نفس الفهرسة دي بتُستخدم
     * كمان في findSnapPoint (لمس المستخدم) بدل مسح القائمة كاملة. */
    private var snapGrid: Map<Long, List<FloatArray>> = emptyMap()
    private var snapGridCellSize = 1f
    private var snapGridMinX = 0f
    private var snapGridMinY = 0f
    private val snapRadiusDp = 12f
    private var snapRadiusPx = snapRadiusDp * density // نصف قطر الالتقاط بالبكسل — محسوب على density

    // ══ إخفاء/إظهار الطبقات (Layers) ══
    // بيحتوي على أسماء الطبقات المخفية فقط — أي طبقة مش موجودة هنا معناها ظاهرة (الحالة الافتراضية)
    private val hiddenLayers = mutableSetOf<String>()

    /** ⚠️ إصلاح جذري لمشكلة الـ hang مع ملفات DXF/AI الكبيرة (بلاغ Amr — تجارب
     * تانية بتفتح نفس الملفات من غير مشاكل): الكود القديم كان بينادي
     * canvas.drawLine() **مرة واحدة لكل خط لوحده** جوه onDraw() — يعني لملف فيه
     * 50 ألف خط (شائع جدًا في DXF/AI حقيقية، خصوصًا بعد تفليح منحنيات AI)، كان
     * بيعمل 50 ألف نداء منفصل لـ Canvas **في كل فريم واحد** (60 مرة في الثانية
     * وقت أي سحب/تكبير) — تكلفة نداء Canvas مش صفر حتى مع تسريع الهاردوير، فمضاعَفة بالعدد ده كانت كافية تعلّق التطبيق تمامًا.
     *
     * الحل: تجميع كل الخطوط (والأقواس بعد تفليحها لخطوط قصيرة) حسب اللون في
     * مصفوفة واحدة مسطّحة لكل لون (Cache)، تتبني مرة واحدة بس لما الموديل
     * يتغيّر أو رؤية طبقة تتغيّر — مش في كل فريم. وقت الرسم الفعلي، بنستخدم
     * canvas.drawLines() (الجمع، مش المفرد) اللي بيرسم آلاف الخطوط بنداء
     * Canvas **واحد بس لكل لون** — فرق جوهري في الأداء، مش تحسين طفيف. */
    private var lineColorGroups: Map<Int, FloatArray> = emptyMap()

    // مصفوفة تحويل من إحداثيات DXF (وحدات الرسم) لإحداثيات الشاشة (بكسل)
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    // ══ وضع القياس ══
    var measureModeOn = false
        set(value) {
            field = value
            if (!value) {
                chainStartPoint = null
                measureSegments.clear()
                isDragPlacing = false
                dragLiveWorld = null
            }
            invalidate()
        }
    var onDistanceMeasured: ((Float) -> Unit)? = null

    /** ⚠️ تعديل جوهري (طلب Amr): كان القياس زوج نقط بس (measureP1/measureP2) —
     * أي نقطة تالتة كانت بتمسح القياس القديم بالكامل وتبدأ واحد جديد من الصفر
     * ("إحساس القفزة" اللي اشتكى منه). دلوقتي القياس بقى **سلسلة (Chain)**:
     * كل نقطة جديدة بتكمّل ضلع جديد من آخر نقطة اتحطت، والأضلاع القديمة بتفضل
     * موجودة وقابلة للحذف الفردي (زرار × صغير جنب كل واحد) بدل ما تتمسح تلقائيًا.
     * [chainStartPoint] هي نقطة بداية الضلع المعلّق حاليًا (null لو لسه مفيش
     * ولا نقطة اتحطت أو آخر ضلع كمّل وبستنى نقطة جديدة تبدأ منها الضلع اللي بعده). */
    private var chainStartPoint: FloatArray? = null

    private data class MeasureSegment(val p1: FloatArray, val p2: FloatArray, val distMm: Float)
    private val measureSegments = mutableListOf<MeasureSegment>()

    /** نصف قطر زرار الحذف باللمس — أكبر شوية من نصف قطر الرسم البصري نفسه
     * عشان يبقى سهل يتلمس بإصبع حتى لو مش دقيق 100% */
    private val deleteButtonRadiusPx = 11f * density
    private val deleteButtonTouchRadiusPx = 22f * density
    /** مواقع أزرار الحذف على الشاشة (بتتحدث كل رسمة لأنها بتتغيّر مع الزوم/البان) —
     * index بتاعها = index القياس المقابل في measureSegments */
    private var deleteButtonScreenPositions: List<FloatArray> = emptyList()

    // ── وضع "اللمس المستمر" (Long-press) لوضع نقطة بدقة — الخط بيتحرك Live مع
    // الإصبع + شاشة تكبير ثابتة (مش تتبع الإصبع) فوق الشاشة، وقفل تلقائي على
    // الأفقي/الرأسي (Ortho) لو زاوية الخط قريبة من 0/90/180/270 درجة. ──
    private var isDragPlacing = false
    private var dragLiveWorld: FloatArray? = null
    private var dragScreenX = 0f
    private var dragScreenY = 0f

    /** نصف قطر نقطة القياس المرسومة — بيكبر أثناء الضغط ويرجع لحجمه الطبيعي فور
     * الرفع (تحسين وضوح، اقتراح Amr)، بحركة سلسة بدل قفزة فجائية.
     * ⚠️ تعديل (طلب Amr: "عاوز اكبر نقطة القياس قليلا اثناء ادراجها"): الحجم
     * الأساسي والنمو أثناء الضغط زادوا شوية (كانوا basePointRadiusDp=4 ونمو +4 بس). */
    private val basePointRadiusDp = 5f
    private var pointRadiusPx = basePointRadiusDp * density
    private var pointRadiusAnimator: android.animation.ValueAnimator? = null
    private fun animatePointRadius(grow: Boolean) {
        pointRadiusAnimator?.cancel()
        val targetSmall = basePointRadiusDp * density
        val targetLarge = (basePointRadiusDp + 6f) * density // نمو أوضح عند الضغط
        pointRadiusAnimator = android.animation.ValueAnimator.ofFloat(pointRadiusPx, if (grow) targetLarge else targetSmall).apply {
            duration = if (grow) 90 else 180
            addUpdateListener { pointRadiusPx = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** أقصى انحراف بالدرجات عن الأفقي/الرأسي عشان يتفعّل القفل التلقائي (Ortho) —
     * لو الزاوية بين النقطة الأولى والنقطة المرشّحة تحت الحد ده، بنعدّل النقطة
     * تلقائيًا تبقى بالظبط أفقية أو رأسية بدل الزاوية شبه المستقيمة. */
    private val orthoSnapDegrees = 2.0

    private fun applyOrthoSnap(candidate: FloatArray): FloatArray {
        val p1 = chainStartPoint ?: return candidate
        val dx = (candidate[0] - p1[0]).toDouble()
        val dy = (candidate[1] - p1[1]).toDouble()
        if (dx == 0.0 && dy == 0.0) return candidate
        val angleDeg = Math.toDegrees(Math.atan2(dy, dx)).let { if (it < 0) it + 360.0 else it }
        val nearest90 = (Math.round(angleDeg / 90.0) * 90.0) % 360.0
        val diff = Math.abs(angleDeg - nearest90).let { if (it > 180.0) 360.0 - it else it }
        if (diff >= orthoSnapDegrees) return candidate
        return if (nearest90 == 0.0 || nearest90 == 180.0) {
            floatArrayOf(candidate[0], p1[1]) // أفقي: نفس ارتفاع النقطة الأولى
        } else {
            floatArrayOf(p1[0], candidate[1]) // رأسي: نفس أفقية النقطة الأولى
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

    // ⚠️ إصلاح (بلاغ Amr: "جودة الخط ضعيفة ورفيع نسبيًا" ثم "بقى عريض جدًا"):
    // كانت strokeWidth رقم بكسل خام ثابت من غير أي علاقة بكثافة الشاشة، بعدين
    // اتعدّلت لـ 3.5dp وطلعت تقيلة أوي — استقرت على 2.2dp كنقطة وسط: واضحة
    // على أي جهاز (بفضل التحويل لـ density) من غير ما تكون تقيلة بصريًا.
    private val defaultPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 2.2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val bgPaint = Paint().apply { color = Color.parseColor("#0D0F12") }

    /** بيغيّر لون خلفية عارض الـ DXF — بديل عن الأبيض الافتراضي في أي ثيم فاتح مستقبلي،
     * ومهم برضو عشان ألوان بعض العناصر (زي الأبيض أو الأصفر من AciColors) بتفضل واضحة
     * بس على خلفيات غامقة اللون؛ فبنسيب الاختيار للمستخدم بدل ما نفرض خلفية بيضا ممكن تخفي رسمته.
     * وعشان الشبكة (Grid) والمحاور تفضل واضحة أيًا كان اللون المختار، بنلوّنهم تلقائيًا
     * حسب سطوع الخلفية (فاتحة → خطوط غامقة، غامقة → خطوط فاتحة) بدل ما نسيبهم لون ثابت.
     * ملحوظة: الاسم `setDxfBackgroundColor` مش `setBackgroundColor` عشان الاسم التاني
     * أصلًا method موجودة في View نفسها (بتلوّن خلفية الـ View كعنصر UI عادي)، فاستخدامه
     * كان بيعمل "hides member of supertype" ومنع الـ build. */
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

    private val measureLinePaint = Paint().apply {
        color = Color.parseColor("#FF8A1E")
        strokeWidth = 3f * density
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f * density, 8f * density), 0f)
    }

    /** وحدة القياس الحالية (مم/سم/بوصة) — بتتحدث من ViewerFragment كل ما المستخدم يغيّرها
     * من الإعدادات أو زرار الوحدة، عشان قياس الـ DXF يطلع بنفس المنطق بالظبط زي العارض
     * ثلاثي الأبعاد بدل ما يعرض رقم خام من غير وحدة واضحة. */
    var currentUnit: MeasurementUnit = MeasurementUnit.MM

    private val measureTextSizeSp = 14f
    private val measureTextPaint = Paint().apply {
        color = Color.parseColor("#FF8A1E")
        textSize = measureTextSizeSp * scaledDensity // بدل 46f خام
        isAntiAlias = true
        isFakeBoldText = true
    }
    /** خلفية خفيفة شبه شفافة وراء نص القياس — عشان يفضل مقروء حتى لو وقع فوق
     * خط أو تفصيلة في الرسمة (مش بس الاعتماد على إبعاده عن الخط) */
    private val measureLabelBgPaint = Paint().apply {
        color = Color.parseColor("#CC101216")
        isAntiAlias = true
    }

    /** زرار الحذف الصغير (×) جنب كل قياس — أحمر مميّز عن لون القياس نفسه
     * (البرتقالي) عشان يبان بوضوح إنه إجراء مختلف (حذف) مش جزء من القياس */
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

    /** هايلايت بسيط للفجوات الكبيرة نسبيًا (نتيجة DxfGapChecker) — إشارة بصرية
     * بحتة "في فجوة هنا"، مش تقرير دقيق (نفس فلسفة حواف الـ STL المفتوحة). */
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
                // الـ Pan لازم يفضل شغال بالظبط زي العرض العادي حتى ووضع القياس مفعّل،
                // عشان المستخدم يقدر يتنقل بحرية ويوصل لأي نقطة يحتاج يقيسها. اختيار نقاط
                // القياس نفسه بيتم بالـ tap (onSingleTapConfirmed) مش بالسحب، فمفيش تعارض.
                offsetX -= dx
                offsetY -= dy
                invalidate()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetView()
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (measureModeOn) {
                    // ⚠️ لازم نفحص زرار الحذف الأول قبل أي تفسير تاني للمسة —
                    // لو المستخدم بيحاول يحذف قياس قديم، متعاملوش كنقطة قياس جديدة
                    val segIndex = findDeleteButtonAt(e.x, e.y)
                    if (segIndex != null) {
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
                // بداية "اللمس المستمر" — بيفعّل خط بيتحرك Live + شاشة تكبير ثابتة،
                // لحد ما المستخدم يرفع إصبعه (شوف onTouchEvent تحت). مبيتفعّلش لو
                // اللمسة كانت أصلاً على زرار حذف (كان هيعمل تكبير على الفاضي)
                if (measureModeOn && findDeleteButtonAt(e.x, e.y) == null) {
                    isDragPlacing = true
                    animatePointRadius(true)
                    updateDragPreview(e.x, e.y)
                }
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // ⚠️ أي لمسة بإصبعين (تكبير/تصغير أو سحب بإصبعين) لازم تشتغل دايمًا، حتى
        // لو كان في لمس مستمر شغال لوضع نقطة قياس — بنلغي وضع التثبيت فورًا
        // ونسيب الزوم/البان العاديين يتولوا الإيماءة.
        if (event.pointerCount > 1) {
            if (isDragPlacing) { isDragPlacing = false; dragLiveWorld = null; animatePointRadius(false) }
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            invalidate()
            return true
        }

        if (measureModeOn && isDragPlacing) {
            // بعد ما اللمس المستمر يتفعّل، بنتولى تتبّع الحركة يدويًا (مش عن طريق
            // GestureDetector) عشان نمنع الـ Pan من "يخطف" اللمسة أثناء وضع النقطة
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> updateDragPreview(event.x, event.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
            return true
        }

        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    /** بيدوّر على زرار حذف قياس واقع تحت نقطة اللمس دي — null لو مفيش. نصف قطر
     * اللمس (deleteButtonTouchRadiusPx) أكبر من الدايرة المرسومة فعليًا عشان
     * يبقى سهل يتلمس بإصبع حتى لو مش دقيق 100%. */
    private fun findDeleteButtonAt(screenX: Float, screenY: Float): Int? {
        for ((index, pos) in deleteButtonScreenPositions.withIndex()) {
            val d = hypot((pos[0] - screenX).toDouble(), (pos[1] - screenY).toDouble()).toFloat()
            if (d <= deleteButtonTouchRadiusPx) return index
        }
        return null
    }

    /** بيحدّث نقطة المعاينة الحية أثناء اللمس المستمر — بيلتقط أقرب نقطة حقيقية
     * زي اللمسة السريعة العادية بالظبط (+ قفل Ortho لو الزاوية قريبة من مستقيم) */
    private fun updateDragPreview(screenX: Float, screenY: Float) {
        dragScreenX = screenX; dragScreenY = screenY
        dragLiveWorld = resolveWorldPoint(screenX, screenY)
        invalidate()
    }

    /** بيرجّع أقرب نقطة التقاط حقيقية (نهاية خط/مركز دايرة أو قوس) لو موجودة قريب
     * كفاية من مكان اللمس، وإلا الموضع الخام المحوّل لفراغ الموديل. قفل الـ Ortho
     * بيتطبق بس لو النقطة **مش** ملتقطة على Vertex حقيقي — لو هي نقطة حقيقية
     * فعلًا، بنسيبها زي ما هي بالظبط، عشان القياس يفضل دقيق 100% على نقط الرسمة
     * الحقيقية بدل ما يتزحزح لأقرب زاوية 90° صدفة. */
    private fun resolveWorldPoint(screenX: Float, screenY: Float): FloatArray {
        val snapped = findSnapPoint(screenX, screenY)
        if (snapped != null) return snapped
        val raw = floatArrayOf(screenToWorldX(screenX), screenToWorldY(screenY))
        return applyOrthoSnap(raw)
    }

    /** بيسجّل نقطة قياس جديدة — أول نقطة بتفتح السلسلة، وأي نقطة بعدها بتكمّل
     * ضلع جديد من آخر نقطة اتحطت (مش بتبدأ قياس جديد من الصفر). */
    private fun commitMeasurePoint(world: FloatArray) {
        val start = chainStartPoint
        if (start == null) {
            chainStartPoint = world
        } else {
            val distMm = hypot((world[0] - start[0]).toDouble(), (world[1] - start[1]).toDouble()).toFloat()
            measureSegments.add(MeasureSegment(start, world, distMm))
            chainStartPoint = world
            onDistanceMeasured?.invoke(distMm * currentUnit.factorFromMm)
        }
        invalidate()
    }

    fun clearMeasurement() {
        chainStartPoint = null
        measureSegments.clear()
        invalidate()
    }

    /** تحميل موديل DXF جديد — بيعمل ضبط تلقائي (fit to view) أول ما يتحمّل */
    fun setModel(m: DxfModel) {
        model = m
        chainStartPoint = null
        measureSegments.clear()
        hiddenLayers.clear() // كل الطبقات ظاهرة افتراضيًا مع أي ملف جديد
        showGapHighlight = false
        gapHighlightSegments = null
        refreshSnapPoints(m)
        buildRenderCache()
        post { resetView() }
    }

    /** بيبني lineColorGroups من جديد — بيتنادى مرة واحدة بس لما الموديل يتغيّر أو
     * رؤية طبقة تتغيّر، مش في كل فريم رسم (شوف الشرح فوق تعريف lineColorGroups). */
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

        // تفليح الأقواس لخطوط قصيرة **هنا بس** (وقت بناء الكاش، مرة واحدة) —
        // نفس منطق درجات الزاوية اللي كان في drawArc القديمة بالظبط
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

    /** بيرجّع أسماء كل الطبقات الموجودة في الملف الحالي، بترتيب ظهورها. فاضية لو مفيش موديل محمّل. */
    fun getLayers(): List<String> = model?.layers ?: emptyList()

    /** true لو المفتاح ده مجموعة ألوان (مش اسم طبقة CAD حقيقي) — شوف DXFParser.COLOR_GROUP_PREFIX */
    fun isColorGroup(groupKey: String): Boolean = groupKey.startsWith(DXFParser.COLOR_GROUP_PREFIX)

    /** رقم الترتيب لو المفتاح مجموعة ألوان (مستخدم في تسمية "لون 1"، "لون 2" ...) */
    fun colorGroupIndex(groupKey: String): Int =
        groupKey.removePrefix(DXFParser.COLOR_GROUP_PREFIX).toIntOrNull() ?: 0

    /** بيرجّع لون تمثيلي للمفتاح (طبقة أو مجموعة ألوان) عشان يتعرض كسواتش جنب اسمه
     * في قائمة الطبقات — null لو مفيش موديل أو المفتاح مش موجود */
    fun colorForGroup(groupKey: String): Int? {
        val m = model ?: return null
        if (isColorGroup(groupKey)) return m.colorGroupPalette.getOrNull(colorGroupIndex(groupKey))
        m.lines.firstOrNull { it.layer == groupKey }?.let { return it.color }
        m.arcs.firstOrNull { it.layer == groupKey }?.let { return it.color }
        m.circles.firstOrNull { it.layer == groupKey }?.let { return it.color }
        return null
    }

    /** true لو الطبقة ظاهرة حاليًا (أو مش معروفة أصلًا — بنعتبرها ظاهرة افتراضيًا) */
    fun isLayerVisible(layer: String): Boolean = layer !in hiddenLayers

    /** بيتحكم في إظهار/إخفاء طبقة معيّنة — بيعيد بناء نقاط الالتقاط (Snap) عشان أداة
     * القياس متلتقطش على نقط من طبقة مخفية، وبيعيد رسم الشاشة فورًا. */
    fun setLayerVisible(layer: String, visible: Boolean) {
        if (visible) hiddenLayers.remove(layer) else hiddenLayers.add(layer)
        model?.let { refreshSnapPoints(it) }
        buildRenderCache()
        invalidate()
    }

    /** بيحسب إجمالي طول القطع (الخطوط + الأقواس + محيط الدوائر) من العناصر
     * **الظاهرة حاليًا بس** (بيستثني أي طبقة مخفية — منطقي لأن الطبقة المخفية
     * عادة بتبقى ملاحظات/أبعاد مش جزء من القطع الفعلي). القيمة بوحدة الملف
     * الأصلية نفسها (زي باقي أدوات القياس في العارض، من غير تحويل وحدات). */
    fun totalCutLength(): Float {
        val m = model ?: return 0f
        var total = 0f
        for (line in m.lines) {
            if (!isLayerVisible(line.layer)) continue
            val dx = line.x2 - line.x1; val dy = line.y2 - line.y1
            total += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        for (arc in m.arcs) {
            if (!isLayerVisible(arc.layer)) continue
            var span = arc.endDeg - arc.startDeg
            if (span < 0) span += 360f // الأقواس اللي بتلف حوالين نقطة الصفر (0°)
            total += arc.r * Math.toRadians(span.toDouble()).toFloat()
        }
        for (circle in m.circles) {
            if (!isLayerVisible(circle.layer)) continue
            total += 2f * Math.PI.toFloat() * circle.r
        }
        return total
    }

    /** عدد المسارات القابلة للقطع الظاهرة حاليًا (كل خط/قوس/دائرة = مسار منفصل
     * تقريبًا) — مؤشر تقريبي لعدد مرات "الدخول" اللي ماكينة الليزر هتحتاجها،
     * مفيد للتسعير التقريبي حتى لو مافيش سرعة قطع معروفة لحساب وقت فعلي. */
    fun visibleCuttableEntityCount(): Int {
        val m = model ?: return 0
        return m.lines.count { isLayerVisible(it.layer) } +
            m.arcs.count { isLayerVisible(it.layer) } +
            m.circles.count { isLayerVisible(it.layer) }
    }

    /** بيجمّع كل نقاط النهايات والمراكز من عناصر الرسمة عشان أداة القياس تقدر تلتقط عليها —
     * بيتجاهل عناصر أي طبقة مخفية حاليًا عشان القياس ميلتقطش على حاجة المستخدم مخفيها. */
    private fun buildSnapPoints(m: DxfModel): List<FloatArray> {
        val pts = mutableListOf<FloatArray>()
        for (line in m.lines) {
            if (!isLayerVisible(line.layer)) continue
            pts.add(floatArrayOf(line.x1, line.y1))
            pts.add(floatArrayOf(line.x2, line.y2))
        }
        for (circle in m.circles) {
            if (!isLayerVisible(circle.layer)) continue
            pts.add(floatArrayOf(circle.cx, circle.cy)) // مركز الدايرة
        }
        for (arc in m.arcs) {
            if (!isLayerVisible(arc.layer)) continue
            pts.add(floatArrayOf(arc.cx, arc.cy)) // مركز القوس
            val startRad = Math.toRadians(arc.startDeg.toDouble())
            val endRad = Math.toRadians(arc.endDeg.toDouble())
            pts.add(floatArrayOf(arc.cx + arc.r * cos(startRad).toFloat(), arc.cy + arc.r * sin(startRad).toFloat()))
            pts.add(floatArrayOf(arc.cx + arc.r * cos(endRad).toFloat(), arc.cy + arc.r * sin(endRad).toFloat()))
        }
        return pts
    }

    /** بتحدّث snapPoints + الفهرس المكاني (snapGrid) مع بعض دايمًا — نقطة دخول
     * واحدة بدل ما ننسى نبني الفهرس بعد أي تحديث لـ snapPoints. */
    private fun refreshSnapPoints(m: DxfModel) {
        snapPoints = buildSnapPoints(m)
        buildSnapGrid()
    }

    /** ⚠️ إصلاح (Race Condition حقيقي): الحساب بيحصل في Thread منفصل (شوف الشرح
     * تحت)، ولو المستخدم غيّر الموديل/رؤية طبقة بسرعة (فيفتح Thread جديد قبل
     * ما القديم يخلص)، مفيش أي ضمان إن القديم مش هيخلص **بعد** الجديد ويكتب
     * فوقه بيانات قديمة بصمت (باگ نادر بس خطير لو حصل — قياس بيلتقط نقط من
     * حالة سابقة). الحل: رقم تسلسلي (snapGridGeneration) بيزيد قبل كل Thread،
     * وبنتأكد إن الـ Thread اللي بيكتب النتيجة لسه "الأحدث" وقت ما يخلص فعلًا. */
    private var snapGridGeneration = 0

    /** بيبني snapGrid من snapPoints الحالية — حجم الخلية محسوب عشان يدّي تقريبًا
     * عدد خلايا يساوي عدد النقاط (شبكة متوازنة، نفس فلسفة sqrt(n) المستخدمة في
     * أماكن تانية بالمشروع زي MeshIntegrityChecker). الحوسبة الثقيلة بتحصل في Thread */
    private fun buildSnapGrid() {
        val pts = snapPoints
        val myGeneration = ++snapGridGeneration
        if (pts.isEmpty()) { snapGrid = emptyMap(); return }

        // نعمل نسخة للبيانات عشان نستخدمها في الـ Thread بأمان
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

            // قم بتجهيز الخريطة النهائية (قابلة للقراءة من الـ UI)
            val finalMap: Map<Long, List<FloatArray>> = buckets.mapValues { it.value.toList() }

            // ارجع النتائج للـ UI thread وآمن متغيرات الحالة — بس لو لسه إحنا
            // أحدث طلب بناء (مفيش طلب تاني اتعمل بعدنا وإحنا لسه شغالين)
            post {
                if (myGeneration != snapGridGeneration) return@post // اتلغينا، فيه طلب أحدث
                snapGridCellSize = cellSize
                snapGridMinX = minX
                snapGridMinY = minY
                snapGrid = finalMap
                invalidate()
            }
        }.start()
    }

    private fun cellKeyOf(worldX: Float, worldY: Float): Long {
        val cx = ((worldX - snapGridMinX) / snapGridCellSize).toInt()
        val cy = ((worldY - snapGridMinY) / snapGridCellSize).toInt()
        return (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)
    }

    private fun screenToWorldX(sx: Float) = (sx - offsetX) / scale
    private fun screenToWorldY(sy: Float) = (offsetY - sy) / scale

    /** بيدوّر على أقرب نقطة التقاط لمكان اللمس (بمسافة بالبكسل على الشاشة، مش بوحدات الرسمة) */
    private fun findSnapPoint(screenX: Float, screenY: Float): FloatArray? {
        if (snapGrid.isEmpty()) return null
        val worldX = screenToWorldX(screenX)
        val worldY = screenToWorldY(screenY)
        // نصف قطر الالتقاط بالبكسل محوّل لوحدات الموديل، عشان نعرف كام خلية حوالين
        // نقطة اللمس محتاجين نفحص (باستخدام 1 كحد أدنى لتفادي قسمة على صفر لو scale=0)
        val radiusWorld = snapRadiusPx / scale.coerceAtLeast(1e-6f)
        val cellSpan = kotlin.math.ceil(radiusWorld / snapGridCellSize).toInt().coerceAtLeast(1)
        val centerCx = ((worldX - snapGridMinX) / snapGridCellSize).toInt()
        val centerCy = ((worldY - snapGridMinY) / snapGridCellSize).toInt()

        var closest: FloatArray? = null
        var closestDist = snapRadiusPx
        for (dcx in -cellSpan..cellSpan) {
            for (dcy in -cellSpan..cellSpan) {
                val key = ((centerCx + dcx).toLong() shl 32) or ((centerCy + dcy).toLong() and 0xffffffffL)
                val bucket = snapGrid[key] ?: continue
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
        chainStartPoint = null
        measureSegments.clear()
        snapPoints = emptyList()
        snapGrid = emptyMap()
        hiddenLayers.clear()
        showGapHighlight = false
        gapHighlightSegments = null
        lineColorGroups = emptyMap()
        invalidate()
    }

    /** إعادة ضبط العرض عشان الرسمة كلها تظهر بالكامل في نص الشاشة */
    fun resetView() {
        val m = model ?: return
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

        val m = model ?: return

        // ── رسم كل الخطوط + الأقواس (بعد تفليحها) بنداء Canvas واحد لكل لون —
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
            canvas.drawCircle(
                toScreenX(circle.cx), toScreenY(circle.cy),
                circle.r * scale, defaultPaint
            )
        }

        drawMeasurement(canvas)
        drawGapHighlight(canvas)
    }

    // ⚠️ تعديل (طلب Amr): كان بيرسم خط أحمر بيوصل بين طرفي الفجوة — بقى يرسم
    // دايرة حمراء عند كل طرف من الطرفين بدل الخط. كل عنصر في [gapHighlightSegments]
    // لسه زوج نقط (x1,y1,x2,y2) — هما نفسهم طرفي الفجوة، بس بدل ما نوصلهم بخط
    // بنعلّم كل طرف بدايرة منفصلة (أوضح لتحديد مكان كل طرف بالظبط، مش بس "فيه
    // فجوة في المنطقة دي" زي ما كان بيوحي الخط).
    private fun drawGapHighlight(canvas: Canvas) {
        if (!showGapHighlight) return
        val segs = gapHighlightSegments ?: return
        var i = 0
        while (i + 3 < segs.size) {
            canvas.drawCircle(toScreenX(segs[i]), toScreenY(segs[i + 1]), gapHighlightRadiusPx, gapHighlightPaint)
            canvas.drawCircle(toScreenX(segs[i + 2]), toScreenY(segs[i + 3]), gapHighlightRadiusPx, gapHighlightPaint)
            i += 4
        }
    }

    private fun drawMeasurement(canvas: Canvas) {
        // ⚠️ تعديل جوهري (طلب Amr): بدل زوج نقط واحد، بنرسم كل الأضلاع المكمّلة
        // في measureSegments (كل واحد بلابل الطول + زرار حذف)، بالإضافة للضلع
        // المعلّق حاليًا (من chainStartPoint لحد آخر نقطة معاينة أثناء السحب).
        val newHitPositions = ArrayList<FloatArray>(measureSegments.size)
        for (seg in measureSegments) {
            val sx1 = toScreenX(seg.p1[0]); val sy1 = toScreenY(seg.p1[1])
            val sx2 = toScreenX(seg.p2[0]); val sy2 = toScreenY(seg.p2[1])
            val deleteBtnPos = drawMeasureLine(canvas, sx1, sy1, sx2, sy2, seg.distMm, drawDeleteButton = true)
            newHitPositions.add(deleteBtnPos)
        }
        deleteButtonScreenPositions = newHitPositions

        val start = chainStartPoint
        if (start != null) {
            val sx1 = toScreenX(start[0]); val sy1 = toScreenY(start[1])
            canvas.drawCircle(sx1, sy1, pointRadiusPx, measurePointPaint)

            if (isDragPlacing) {
                // خط القياس بيتحرك Live مع الإصبع أثناء اللمس المستمر، قبل ما تتثبّت
                // النقطة التانية فعليًا (يشمل قفل Ortho لو الزاوية قريبة من مستقيم)
                dragLiveWorld?.let { live ->
                    val sxLive = toScreenX(live[0]); val syLive = toScreenY(live[1])
                    val distMm = hypot((live[0] - start[0]).toDouble(), (live[1] - start[1]).toDouble()).toFloat()
                    drawMeasureLine(canvas, sx1, sy1, sxLive, syLive, distMm, drawDeleteButton = false)
                }
            }
        }

        // شاشة تكبير ثابتة أعلى الشاشة أثناء اللمس المستمر — بتوضح مكان النقطة
        // اللي هتتحدد بدقة، لأن الإصبع نفسه بيحجب المكان ده مباشرة
        if (isDragPlacing) {
            dragLiveWorld?.let { live -> drawMagnifier(canvas, live[0], live[1]) }
        }
    }

    /** رسم خط قياس (نهائي أو معاينة حية أثناء السحب) + تسمية الطول — دالة مشتركة
     * عشان منكررش نفس منطق إبعاد النص عن الخط مرتين. بترجع موقع زرار الحذف على
     * الشاشة (حتى لو [drawDeleteButton]=false، عشان الاستدعاء يفضل موحّد) —
     * الزرار نفسه ما بيترسمش غير لو drawDeleteButton=true (الأضلاع المكمّلة بس،
     * مش معاينة السحب الحية اللي لسه مش قياس فعلي). */
    private fun drawMeasureLine(
        canvas: Canvas, sx1: Float, sy1: Float, sx2: Float, sy2: Float,
        distMm: Float, drawDeleteButton: Boolean
    ): FloatArray {
        canvas.drawLine(sx1, sy1, sx2, sy2, measureLinePaint)

        val displayDist = distMm * currentUnit.factorFromMm
        val midX = (sx1 + sx2) / 2f
        val midY = (sy1 + sy2) / 2f
        val label = "%.2f %s".format(displayDist, resources.getString(currentUnit.labelRes))

        // ── إبعاد النص عن الخط نفسه عمودي على اتجاهه (مش إزاحة قطرية ثابتة) عشان
        // يفضل واضح مهما كانت زاوية الخط، بمسافة أكبر من قبل، بالإضافة لخلفية
        // خفيفة وراءه تضمن وضوحه حتى لو وقع فوق تفصيلة تانية في الرسمة ──
        val lineDx = sx2 - sx1; val lineDy = sy2 - sy1
        val lineLen = hypot(lineDx.toDouble(), lineDy.toDouble()).toFloat().let { if (it < 1f) 1f else it }
        var perpX = -lineDy / lineLen
        var perpY = lineDx / lineLen
        if (perpY > 0f) { perpX = -perpX; perpY = -perpY } // دايمًا لفوق في الشاشة، مش عشوائي حسب اتجاه الخط
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

        // ── زرار الحذف: دايرة صغيرة حمراء عند الطرف اليمين لخلفية اللابل ──
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

    /** شاشة تكبير ثابتة — بترسم نسخة مكبّرة من نفس محتوى الرسمة حوالين نقطة
     * الالتقاط الحالية، بخلفية بنفس لون خلفية العارض الفعلي (مش لون ثابت) عشان
     * خطوط غامقة اللون تفضل واضحة لو المستخدم مغيّر الخلفية لفاتحة، مع علامة +
     * في النص توضح بالظبط فين هتتحدد النقطة.
     *
     * ⚠️ إصلاح (بلاغ Amr، مرفق صورة): كانت مكانها ثابت أعلى يمين الشاشة، وده
     * كان بيتصادم مع كارت "إظهار الفجوات في الرسمة" العائم فوق العارض في نفس
     * المنطقة تقريبًا — جزء من العدسة كان بيختفي وراه. نقلناها لأسفل يمين
     * الشاشة، بعيد تمامًا عن أي كارت عائم بيظهر فوق العارض (كلهم فوق). */
    private fun drawMagnifier(canvas: Canvas, worldX: Float, worldY: Float) {
        val radius = 62f * density
        val margin = 16f * density
        val magCenterX = width - margin - radius
        val magCenterY = height - margin - radius
        val zoom = 3.5f
        val magScale = scale * zoom

        canvas.save()
        val clipPath = android.graphics.Path().apply {
            addCircle(magCenterX, magCenterY, radius, android.graphics.Path.Direction.CW)
        }
        canvas.clipPath(clipPath)
        canvas.drawCircle(magCenterX, magCenterY, radius, bgPaint) // نفس لون خلفية العارض الحالي بالظبط

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
            canvas.drawCircle(
                magCenterX + (c.cx - worldX) * magScale,
                magCenterY - (c.cy - worldY) * magScale,
                c.r * magScale, magnifierLinePaint
            )
        }

        canvas.restore()

        canvas.drawCircle(magCenterX, magCenterY, radius, magnifierBorderPaint)
        canvas.drawLine(magCenterX - 16f * density, magCenterY, magCenterX + 16f * density, magCenterY, magnifierCrosshairPaint)
        canvas.drawLine(magCenterX, magCenterY - 16f * density, magCenterX, magCenterY + 16f * density, magnifierCrosshairPaint)
    }

    /** شبكة خفيفة + محاور X/Y زي شاشة الرسم بالأوتوكاد */
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
