package com.amr3d.preview.pro

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * فحص "الفجوات الكبيرة نسبيًا" في رسومات DXF (خطوط + أقواس — الدوائر مستثناة
 * لأنها دايمًا مقفولة بطبيعتها). بناءً على توضيح Amr: معظم الشغل بوليلاين
 * (بتتحول لخطوط منفصلة وقت التحليل)، وبتروح إما لطباعة 1:1 أو لليزر للقطع —
 * في الحالتين، أي شكل مفروض يبقى مقفول لازم كل نهاية ضلع فيه تلاقي نهاية ضلع
 * تاني قريبة جدًا منها؛ الفجوة الحقيقية (مش تفاوت تصدير عادي) هي المشكلة.
 *
 * ═══ ليه نطاقين مش نطاق واحد؟ ═══
 * أي ملف DXF حقيقي فيه فجوات دقيقة جدًا (أجزاء من المليمتر) بسبب دقة التصدير
 * من الأوتوكاد نفسه — لو حسبناها كلها هيطلع تحذير في كل ملف تقريبًا حتى
 * السليم. فبنستخدم نطاقين نسبة لقطر الرسمة كله:
 * - أصغر من [WELD_RELATIVE] → نفس النقطة عمليًا (تفاوت تصدير طبيعي، يتجاهل)
 * - بين [WELD_RELATIVE] و [MAX_GAP_RELATIVE] → فجوة حقيقية "كبيرة نسبيًا"
 *   تستاهل تنبيه (نهايتين قريبتين من بعض بس مش متصلتين فعليًا)
 * - أكبر من [MAX_GAP_RELATIVE] → مش فجوة في نفس الشكل أصلاً، غالبًا عنصر
 *   منفصل تمامًا (خط أبعاد، ملاحظة، شكل تاني بعيد) — يتجاهل عشان مايطلعش
 *   تحذيرات غلط لكل خط منفرد في الرسمة
 *
 * الخوارزمية: لكل نهاية ضلع، أقرب نهاية ضلع "تانية" (من عنصر مختلف) — البحث
 * عن طريق شبكة مكانية (Grid Hash) بدل O(n²) عشان يفضل سريع حتى مع رسومات
 * فيها آلاف العناصر (تصفيف/Nesting لعدة قطع على شيت واحد مثلاً).
 */
object DxfGapChecker {

    data class GapReport(
        val hasSignificantGaps: Boolean,
        /** كل فجوة كخط قصير (x1,y1)->(x2,y2) بيوصل بين النهايتين — للرسم كـ
         * Highlight بس، مش تقرير دقيق (زي فلسفة حواف الـ STL المفتوحة بالظبط). */
        val gapSegments: FloatArray
    )

    private const val WELD_RELATIVE = 0.0008   // 0.08% من قطر الرسمة
    private const val MAX_GAP_RELATIVE = 0.02  // 2% من قطر الرسمة
    private const val MAX_HIGHLIGHT_GAPS = 5000

    /** [isLayerVisible] بيسمح نستثني الطبقات المخفية حاليًا من الفحص — نفس
     * منطق totalCutLength() في DXF2DView (الطبقة المخفية عادة ملاحظات/أبعاد). */
    fun check(model: DxfModel, isLayerVisible: (String) -> Boolean): GapReport {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val entityIds = ArrayList<Int>()

        var eid = 0
        for (line in model.lines) {
            if (isLayerVisible(line.layer)) {
                xs.add(line.x1); ys.add(line.y1); entityIds.add(eid)
                xs.add(line.x2); ys.add(line.y2); entityIds.add(eid)
            }
            eid++
        }
        for (arc in model.arcs) {
            if (isLayerVisible(arc.layer)) {
                val s = Math.toRadians(arc.startDeg.toDouble())
                val e = Math.toRadians(arc.endDeg.toDouble())
                xs.add(arc.cx + arc.r * cos(s).toFloat()); ys.add(arc.cy + arc.r * sin(s).toFloat()); entityIds.add(eid)
                xs.add(arc.cx + arc.r * cos(e).toFloat()); ys.add(arc.cy + arc.r * sin(e).toFloat()); entityIds.add(eid)
            }
            eid++
        }

        val pointCount = xs.size
        if (pointCount < 4) return GapReport(false, FloatArray(0)) // أقل من ضلعين، مفيش معنى لفجوة

        val dx = (model.maxX - model.minX).toDouble(); val dy = (model.maxY - model.minY).toDouble()
        val diag = sqrt(dx * dx + dy * dy).let { if (it > 1e-6) it else 1.0 }
        val weldEps = diag * WELD_RELATIVE
        val maxGap = diag * MAX_GAP_RELATIVE

        // ── شبكة مكانية: حجم الخلية = أقصى فجوة مسموحة، عشان أي نقطتين في مدى
        // الفجوة القصوى يقعوا في نفس الخلية أو خلية مجاورة مباشرة ──
        val cellSize = maxGap.coerceAtLeast(1e-6)
        fun cellX(x: Float) = floor((x - model.minX) / cellSize).toInt()
        fun cellY(y: Float) = floor((y - model.minY) / cellSize).toInt()
        fun cellKey(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

        val grid = HashMap<Long, MutableList<Int>>()
        for (i in 0 until pointCount) {
            grid.getOrPut(cellKey(cellX(xs[i]), cellY(ys[i]))) { ArrayList() }.add(i)
        }

        val gapSegs = ArrayList<Float>()
        val flaggedPairs = HashSet<Long>()
        var gapCount = 0

        for (i in 0 until pointCount) {
            if (gapCount >= MAX_HIGHLIGHT_GAPS) break
            val cx = cellX(xs[i]); val cy = cellY(ys[i])
            var bestDist = Double.MAX_VALUE
            var bestJ = -1
            for (ncx in cx - 1..cx + 1) {
                for (ncy in cy - 1..cy + 1) {
                    val bucket = grid[cellKey(ncx, ncy)] ?: continue
                    for (j in bucket) {
                        if (j == i || entityIds[j] == entityIds[i]) continue // نفس النقطة أو نفس العنصر — تجاهل
                        val ddx = (xs[j] - xs[i]).toDouble(); val ddy = (ys[j] - ys[i]).toDouble()
                        val d = sqrt(ddx * ddx + ddy * ddy)
                        if (d < bestDist) { bestDist = d; bestJ = j }
                    }
                }
            }
            if (bestJ < 0) continue
            if (bestDist > weldEps && bestDist <= maxGap) {
                val pairKey = if (i < bestJ) (i.toLong() shl 32) or bestJ.toLong() else (bestJ.toLong() shl 32) or i.toLong()
                if (flaggedPairs.add(pairKey)) {
                    gapSegs.add(xs[i]); gapSegs.add(ys[i]); gapSegs.add(xs[bestJ]); gapSegs.add(ys[bestJ])
                    gapCount++
                }
            }
        }

        return GapReport(hasSignificantGaps = gapCount > 0, gapSegments = gapSegs.toFloatArray())
    }
}
