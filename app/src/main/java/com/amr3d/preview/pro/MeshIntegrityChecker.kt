package com.amr3d.preview.pro

import kotlin.math.sqrt

/**
 * فحص "قابلية الطباعة/القطع" الأساسي: حواف مفتوحة (Open Edges) + مثلثات بـ
 * Normals معكوسة (Flipped Normals) — بناءً على إن الجمهور المستهدف (خشب/CNC/
 * ليزر) محتاج يتأكد إن الموديل سليم قبل ما يبعته لسلايسر أو ماكينة.
 *
 * ═══ ليه الحواف المفتوحة والـ Normals المعكوسة تحديدًا (مش كل فحوصات الجودة)؟ ═══
 * دول أشهر سببين بيخلوا سلايسر/برنامج CAM يرفض الملف أو ينتج مسار قطع غلط:
 * - حافة مفتوحة = فتحة في السطح (الموديل مش مغلق 100%) → السلايسر مش هيقدر
 *   يحدد "جوه" الموديل من "بره" بثقة، فبتظهر فجوات أو تسريب في الطباعة/الحفر.
 * - Normal معكوس = مثلث اتجاه وشه (المفروض يبقى للخارج) مقلوب عكس جيرانه →
 *   بيربك أي عملية Boolean وبيخلي بعض السلايسرات تتعامل معاه كجزء داخلي غلط،
 *   فبتظهر فجوة أو سطح مقلوب في الناتج النهائي.
 *
 * ═══ الخوارزمية ═══
 * 1) توحيد الرؤوس المتكررة (Weld) — بيانات STL مش مفهرسة أصلاً (كل مثلث بنقطه
 *    الخاصة)، فمينفعش نقارن الأضلاع من غير ما نوحّد الرؤوس المتطابقة الأول
 *    (نفس تقنية الويلد المستخدمة في EdgeCollapseDecimator بالظبط).
 * 2) لكل ضلع (زوج رؤوس)، بنعد كام مثلث بيستخدمه:
 *    - مرة واحدة بس → حافة مفتوحة (Open Edge)
 *    - مرتين بنفس اتجاه الدوران (بدل ما يكونوا عكس بعض زي سطح سليم) → عدم
 *      اتساق بين المثلثين (أحدهما محتمل يكون معكوس)
 *    - أكتر من مرتين → ضلع غير منتظم (Non-manifold)، مؤشر خطأ إضافي في البيانات
 * 3) لتحديد "مين المعكوس فعلاً" (مش بس "الاتنين مش متفقين")، بنستخدم Union-Find
 *    بحساب توازي (Parity Union-Find): كل مجموعة مثلثات متصلة ببعض بتتجمع مع
 *    بعض، وأي مثلث لقى نفسه في الأقلية (عكس الاتجاه الغالب في مجموعته المتصلة)
 *    بيتحسب "معكوس". نفس المبدأ المستخدم في أدوات إصلاح الموديلات الاحترافية
 *    (Unify Normals) لكن بتنفيذ خفيف يناسب الموبايل.
 *
 * ⚠️ للموديلات الضخمة جدًا (فوق [LARGE_MESH_TRIANGLE_THRESHOLD])، الفحص بيشتغل
 * على نسخة مبسّطة (Decimated، عن طريق EdgeCollapseDecimator الموجود بالفعل)
 * مش الموديل الأصلي كامل — عشان الـ HashMap المستخدمة في تتبع الأضلاع تقيلة
 * على الذاكرة نسبيًا (نفس فلسفة الحماية في EdgeCollapseDecimator وSTLParser).
 * النتيجة في الحالة دي بس تقريبية/إرشادية (isApproximate = true).
 */
object MeshIntegrityChecker {

    data class IntegrityReport(
        val openEdgeCount: Int,
        val flippedTriangleCount: Int,
        val nonManifoldEdgeCount: Int,
        val checkedTriangleCount: Int,
        val isApproximate: Boolean
    ) {
        val isWatertight: Boolean get() = openEdgeCount == 0 && nonManifoldEdgeCount == 0
        val isPrintable: Boolean get() = isWatertight && flippedTriangleCount == 0
    }

    /** نفس نسبة الويلد المستخدمة في EdgeCollapseDecimator — دقيقة جدًا عمدًا،
     * غرضها توحيد نسخ الرأس المكرر بس، مش عمل تبسيط حقيقي في الخطوة دي. */
    private const val WELD_RELATIVE_EPS = 1e-5

    /** فوق الحد ده، الفحص بيشتغل على نسخة مبسّطة بدل الموديل الأصلي — حماية
     * ذاكرة (شوف الشرح فوق). نفس القيمة المستخدمة في EdgeCollapseDecimator. */
    private const val LARGE_MESH_TRIANGLE_THRESHOLD = 600_000

    /** ⚠️ دالة بطيئة نسبيًا (O(n) مع overhead HashMap) — ماتِتنادَاش من الـ Main/UI
     * Thread ولا من الـ GL Thread، استخدمها من Coroutine على Dispatchers.Default. */
    fun check(model: STLModel): IntegrityReport {
        val original = model.triangleCount
        if (original == 0) {
            return IntegrityReport(0, 0, 0, 0, isApproximate = false)
        }

        val isApprox = original > LARGE_MESH_TRIANGLE_THRESHOLD
        val target = if (isApprox) {
            val keepRatio = LARGE_MESH_TRIANGLE_THRESHOLD.toFloat() / original.toFloat()
            EdgeCollapseDecimator.run(model, keepRatio) ?: model
        } else {
            model
        }

        return checkInternal(target, isApprox)
    }

    private fun checkInternal(model: STLModel, isApproximate: Boolean): IntegrityReport {
        val triangleCount = model.triangleCount
        val verts = model.vertices

        val minX = model.minBounds[0].toDouble(); val minY = model.minBounds[1].toDouble(); val minZ = model.minBounds[2].toDouble()
        val maxX = model.maxBounds[0].toDouble(); val maxY = model.maxBounds[1].toDouble(); val maxZ = model.maxBounds[2].toDouble()
        val dx = maxX - minX; val dy = maxY - minY; val dz = maxZ - minZ
        val diag = sqrt(dx * dx + dy * dy + dz * dz).let { if (it > 1e-9) it else 1.0 }
        val weldEps = (diag * WELD_RELATIVE_EPS).coerceAtLeast(1e-7)

        // ── 1) توحيد الرؤوس المتطابقة/شبه المتطابقة (Weld) ──
        val weldCells = 1 shl 20
        fun axisIndex(v: Double, minV: Double): Int = ((v - minV) / weldEps).toInt().coerceIn(0, weldCells - 1)

        val cornerCount = triangleCount * 3
        val weldKeyToId = HashMap<Long, Int>(cornerCount)
        val cornerVertId = IntArray(cornerCount)
        var nextId = 0
        var ci = 0; var vi = 0
        while (ci < cornerCount) {
            val x = verts[vi].toDouble(); val y = verts[vi + 1].toDouble(); val z = verts[vi + 2].toDouble()
            val key = (axisIndex(x, minX).toLong() shl 42) or (axisIndex(y, minY).toLong() shl 21) or axisIndex(z, minZ).toLong()
            val id = weldKeyToId.getOrPut(key) { nextId++ }
            cornerVertId[ci] = id
            ci++; vi += 3
        }

        val triA = IntArray(triangleCount); val triB = IntArray(triangleCount); val triC = IntArray(triangleCount)
        val triAlive = BooleanArray(triangleCount)
        for (t in 0 until triangleCount) {
            val a = cornerVertId[t * 3]; val b = cornerVertId[t * 3 + 1]; val c = cornerVertId[t * 3 + 2]
            triA[t] = a; triB[t] = b; triC[t] = c
            triAlive[t] = a != b && b != c && a != c // مثلث منعدم المساحة (تكرار حقيقي في المصدر) — نتجاهله
        }

        // ── 2) عدّ استخدامات كل ضلع ──
        fun edgeKey(a: Int, b: Int): Long {
            val lo = minOf(a, b); val hi = maxOf(a, b)
            return (lo.toLong() shl 32) or hi.toLong()
        }

        val edgeCount = HashMap<Long, Int>(cornerCount)
        for (t in 0 until triangleCount) {
            if (!triAlive[t]) continue
            val a = triA[t]; val b = triB[t]; val c = triC[t]
            edgeCount.merge(edgeKey(a, b), 1, Int::plus)
            edgeCount.merge(edgeKey(b, c), 1, Int::plus)
            edgeCount.merge(edgeKey(c, a), 1, Int::plus)
        }

        // ── 3) Union-Find بحساب توازي (Parity) لتجميع المثلثات المتصلة وتحديد
        // مين "الأقلية" (المعكوسة) في كل مجموعة — من غير Path Compression عمدًا
        // (تبسيط الصحة على حساب سرعة بسيطة، Union by Rank لوحدها كافية هنا) ──
        val parent = IntArray(triangleCount) { it }
        val parityToParent = BooleanArray(triangleCount)
        val rank = IntArray(triangleCount)

        fun find(start: Int): Pair<Int, Boolean> {
            var node = start
            var parity = false
            while (parent[node] != node) {
                parity = parity xor parityToParent[node]
                node = parent[node]
            }
            return node to parity
        }

        fun union(a: Int, b: Int, shouldDiffer: Boolean) {
            val (ra, pa) = find(a)
            val (rb, pb) = find(b)
            if (ra == rb) return // متصلين بالفعل — تبسيط مقصود (من غير فحص تعارض إضافي)
            val newParityForB = pa xor pb xor shouldDiffer
            if (rank[ra] < rank[rb]) {
                parent[ra] = rb
                parityToParent[ra] = newParityForB
            } else {
                parent[rb] = ra
                parityToParent[rb] = newParityForB
                if (rank[ra] == rank[rb]) rank[ra]++
            }
        }

        var openEdgeCount = 0
        val nonManifoldKeys = HashSet<Long>()
        val edgeFirstTri = HashMap<Long, Int>()
        val edgeFirstForward = HashMap<Long, Boolean>()

        fun processEdge(a: Int, b: Int, t: Int) {
            val key = edgeKey(a, b)
            when (edgeCount[key] ?: 0) {
                1 -> openEdgeCount++
                2 -> {
                    val firstTri = edgeFirstTri[key]
                    if (firstTri == null) {
                        edgeFirstTri[key] = t
                        edgeFirstForward[key] = a < b
                    } else {
                        val firstForward = edgeFirstForward.getValue(key)
                        val forward = a < b
                        // في سطح سليم متصل، نفس الضلع لازم يتقرأ باتجاهين متعاكسين من
                        // المثلثين المجاورين (زي ترس ساعة وعكس الساعة) — لو اتقرأ بنفس
                        // الاتجاه في الاتنين، ده عدم اتساق (Normal معكوس محتمل)
                        val consistent = forward != firstForward
                        union(t, firstTri, !consistent)
                        edgeFirstTri.remove(key)
                        edgeFirstForward.remove(key)
                    }
                }
                else -> if ((edgeCount[key] ?: 0) > 2) nonManifoldKeys.add(key)
            }
        }

        for (t in 0 until triangleCount) {
            if (!triAlive[t]) continue
            val a = triA[t]; val b = triB[t]; val c = triC[t]
            processEdge(a, b, t)
            processEdge(b, c, t)
            processEdge(c, a, t)
        }

        // ── 4) "الأقلية" في كل مجموعة متصلة = المثلثات المعكوسة ──
        val rootOf = IntArray(triangleCount) { -1 }
        val parityOf = BooleanArray(triangleCount)
        val countTrueByRoot = HashMap<Int, Int>()
        val countFalseByRoot = HashMap<Int, Int>()
        for (t in 0 until triangleCount) {
            if (!triAlive[t]) continue
            val (root, parity) = find(t)
            rootOf[t] = root
            parityOf[t] = parity
            if (parity) countTrueByRoot.merge(root, 1, Int::plus) else countFalseByRoot.merge(root, 1, Int::plus)
        }

        var flippedTriangleCount = 0
        for (t in 0 until triangleCount) {
            if (!triAlive[t]) continue
            val root = rootOf[t]
            val majorityIsTrue = (countTrueByRoot[root] ?: 0) > (countFalseByRoot[root] ?: 0)
            if (parityOf[t] != majorityIsTrue) flippedTriangleCount++
        }

        return IntegrityReport(
            openEdgeCount = openEdgeCount,
            flippedTriangleCount = flippedTriangleCount,
            nonManifoldEdgeCount = nonManifoldKeys.size,
            checkedTriangleCount = triangleCount,
            isApproximate = isApproximate
        )
    }
}
