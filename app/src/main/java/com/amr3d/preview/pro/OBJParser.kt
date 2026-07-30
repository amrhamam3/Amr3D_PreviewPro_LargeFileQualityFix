package com.amr3d.preview.pro

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import kotlin.math.sqrt

class OBJParseException(message: String) : Exception(message)

/**
 * قارئ ملفات OBJ — بيحوّل بيانات OBJ (رؤوس مفهرسة + أوجه بترجع لها بأرقام) لنفس
 * شكل [STLModel] المسطّح (Flat) اللي كل باقي التطبيق (الرندرر، أدوات القياس،
 * تقرير الفحص، تبسيط الملفات الكبيرة...) شغال عليه أصلاً — يعني مفيش أي تعديل
 * مطلوب في أي مكان تاني في التطبيق عشان OBJ يشتغل، بس القارئ ده.
 *
 * الفرق الجوهري عن STL: في OBJ الرؤوس متعرّفة مرة واحدة (سطور `v`)، والأوجه
 * (سطور `f`) بترجع لها بأرقام (Indexed) — وممكن يكون للوجه أكتر من 3 أضلاع
 * (Quad/N-gon)، فلازم "تثليث" (Fan Triangulation) بسيط: أي وجه بأكتر من 3 نقط
 * بيتقسّم لمثلثات بربط أول نقطة بكل ضلعين متتاليين بعدها.
 *
 * ⚠️ قرار متعمّد (المرحلة الأولى): بنتجاهل ملفات المواد (.mtl) والألوان تمامًا
 * — كل موديل OBJ بياخد لون واحد افتراضي (زي ما STL شغالة بالظبط). ممكن تتضاف
 * قراءة الألوان لاحقًا لو احتجناها فعلاً.
 */
object OBJParser {

    private const val MAX_FILE_SIZE = 2_000_000_000L // 2 GB — نفس حد STLParser

    /** نفس فكرة STLParser.safeTriangleCap بالظبط (نفس الصيغة) — سقف أمان لعدد
     * المثلثات المخزّنة في الذاكرة حسب رام الجهاز الفعلي، بغض النظر عن حجم
     * الملف الأصلي أو دقة تقدير عدد المثلثات المبدئي. */
    private fun safeTriangleCap(): Int {
        val maxHeapBytes = Runtime.getRuntime().maxMemory()
        val budgetBytes = (maxHeapBytes * 0.18).toLong()
        val bytesPerTriangle = 72L
        val cap = (budgetBytes / bytesPerTriangle)
        return cap.coerceIn(250_000L, 4_000_000L).toInt()
    }

    fun parse(context: Context, uri: Uri, onProgress: (Int) -> Unit = {}): STLModel {
        val resolver = context.contentResolver

        val fileSize: Long = resolver.query(
            uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
            } else -1L
        } ?: -1L

        if (fileSize > MAX_FILE_SIZE) {
            throw OBJParseException(context.getString(R.string.error_obj_too_large))
        }

        // ── سطور الرؤوس (v/vn) بتتخزن كاملة زي ما هي (مش هي مصدر الخطر الحقيقي
        // على الذاكرة — عادةً أقل بكتير من عدد المثلثات النهائي)، أما مخرجات
        // المثلثات نفسها (بعد التثليث) فبتتطبق عليها نفس فلسفة الأمان اللي في
        // STLParser.parseAsciiStreaming بالظبط: تقدير مبدئي لعدد المثلثات من حجم
        // الملف، حساب Stride (تصفية بانتظام) عليه، + سقف أقصى صارم (maxTriangles)
        // كخط دفاع أخير حتى لو التقدير المبدئي غلط تمامًا. ──
        val maxTriangles = safeTriangleCap()
        val estimatedTriangleCount = maxOf(1L, fileSize / 40L) // ~40 بايت تقريبي لكل سطر "f" في OBJ عادي
        val stride = if (estimatedTriangleCount > maxTriangles)
            Math.ceil(estimatedTriangleCount.toDouble() / maxTriangles).toInt()
        else 1

        val positions = ArrayList<Float>(300_000)
        val normalsIn = ArrayList<Float>(300_000)

        val outVerts = ArrayList<Float>(minOf(3_000_000, maxTriangles * 9))
        val outNorms = ArrayList<Float>(minOf(3_000_000, maxTriangles * 9))

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        var triangleCounter = 0L
        var keptCount = 0
        var sawAnyFace = false

        fun vertexAt(idx1Based: Int): FloatArray? {
            val i = if (idx1Based < 0) (positions.size / 3) + idx1Based else idx1Based - 1
            val base = i * 3
            if (i < 0 || base + 2 >= positions.size) return null
            return floatArrayOf(positions[base], positions[base + 1], positions[base + 2])
        }
        fun normalAt(idx1Based: Int): FloatArray? {
            if (idx1Based == 0) return null
            val i = if (idx1Based < 0) (normalsIn.size / 3) + idx1Based else idx1Based - 1
            val base = i * 3
            if (i < 0 || base + 2 >= normalsIn.size) return null
            return floatArrayOf(normalsIn[base], normalsIn[base + 1], normalsIn[base + 2])
        }

        resolver.openInputStream(uri)?.use { rawStream ->
            var bytesRead = 0L
            var lastReportedPercent = -1
            val countingStream = object : java.io.InputStream() {
                override fun read(): Int {
                    val r = rawStream.read()
                    if (r >= 0) bytesRead++
                    return r
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = rawStream.read(b, off, len)
                    if (n > 0) {
                        bytesRead += n
                        if (fileSize > 0) {
                            val percent = ((bytesRead * 90L) / fileSize).toInt().coerceIn(0, 90)
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    return n
                }
            }
            val bufferedStream = BufferedInputStream(countingStream, 8192)

            bufferedStream.bufferedReader().useLines { lines ->
                for (rawLine in lines) {
                    val line = rawLine.trim()
                    if (line.isEmpty() || line[0] == '#') continue

                    when {
                        line.startsWith("v ") || line.startsWith("v\t") -> {
                            val parts = line.split(Regex("\\s+"))
                            if (parts.size >= 4) {
                                try {
                                    val x = parts[1].toFloat(); val y = parts[2].toFloat(); val z = parts[3].toFloat()
                                    positions.add(x); positions.add(y); positions.add(z)
                                    if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                                    if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
                                } catch (_: NumberFormatException) { /* سطر تالف — نتجاهله */ }
                            }
                        }
                        line.startsWith("vn ") || line.startsWith("vn\t") -> {
                            val parts = line.split(Regex("\\s+"))
                            if (parts.size >= 4) {
                                try {
                                    normalsIn.add(parts[1].toFloat()); normalsIn.add(parts[2].toFloat()); normalsIn.add(parts[3].toFloat())
                                } catch (_: NumberFormatException) { }
                            }
                        }
                        line.startsWith("f ") || line.startsWith("f\t") -> {
                            sawAnyFace = true
                            val tokens = line.split(Regex("\\s+")).drop(1).filter { it.isNotEmpty() }
                            if (tokens.size < 3) continue

                            // كل عنصر ممكن يكون بصيغة: v أو v/vt أو v/vt/vn أو v//vn
                            val vIdx = IntArray(tokens.size)
                            val nIdx = IntArray(tokens.size)
                            var validFace = true
                            for (i in tokens.indices) {
                                val comps = tokens[i].split("/")
                                try {
                                    vIdx[i] = comps[0].toInt()
                                    nIdx[i] = if (comps.size >= 3 && comps[2].isNotEmpty()) comps[2].toInt() else 0
                                } catch (_: NumberFormatException) { validFace = false }
                            }
                            if (!validFace) continue

                            // Fan Triangulation: (0,1,2), (0,2,3), (0,3,4)...
                            for (k in 1 until vIdx.size - 1) {
                                val keepThis = (triangleCounter % stride == 0L) && keptCount < maxTriangles
                                if (keepThis) {
                                    val pa = vertexAt(vIdx[0]); val pb = vertexAt(vIdx[k]); val pc = vertexAt(vIdx[k + 1])
                                    if (pa != null && pb != null && pc != null) {
                                        var na = normalAt(nIdx[0]); var nb = normalAt(nIdx[k]); var nc = normalAt(nIdx[k + 1])
                                        if (na == null || nb == null || nc == null) {
                                            val computed = computeFaceNormal(pa, pb, pc)
                                            na = computed; nb = computed; nc = computed
                                        }
                                        outVerts.add(pa[0]); outVerts.add(pa[1]); outVerts.add(pa[2])
                                        outVerts.add(pb[0]); outVerts.add(pb[1]); outVerts.add(pb[2])
                                        outVerts.add(pc[0]); outVerts.add(pc[1]); outVerts.add(pc[2])
                                        outNorms.add(na[0]); outNorms.add(na[1]); outNorms.add(na[2])
                                        outNorms.add(nb[0]); outNorms.add(nb[1]); outNorms.add(nb[2])
                                        outNorms.add(nc[0]); outNorms.add(nc[1]); outNorms.add(nc[2])
                                        keptCount++
                                    }
                                }
                                triangleCounter++
                            }
                        }
                    }
                }
            }
        } ?: throw OBJParseException(context.getString(R.string.error_obj_read_failed))

        if (positions.isEmpty()) {
            throw OBJParseException(context.getString(R.string.error_obj_no_vertices))
        }
        if (!sawAnyFace || keptCount == 0) {
            throw OBJParseException(context.getString(R.string.error_obj_no_faces))
        }

        onProgress(95)

        return STLModel(
            vertices = outVerts.toFloatArray(),
            normals = outNorms.toFloatArray(),
            triangleCount = keptCount,
            minBounds = floatArrayOf(minX, minY, minZ),
            maxBounds = floatArrayOf(maxX, maxY, maxZ),
            // ⚠️ إضافة (كانت ناقصة في النسخة الأصلية فمكانتش هتتوافق مع STLModel
            // الحالي أصلاً — شوف نفس الحقول بالظبط في STLParser.parseBinaryFromUri
            // لتفاصيل ليه هما موجودين): estimatedOriginalTriangleCount هو
            // "triangleCounter" (كل المثلثات بعد التثليث قبل أي تصفية بالـ Stride)،
            // وisApproximate بتبقى true لو فعلاً حصلت تصفية (stride > 1).
            estimatedOriginalTriangleCount = triangleCounter.toInt(),
            isApproximate = stride > 1,
            isWatertightHint = (keptCount % 2 == 0)
        )
    }

    private fun computeFaceNormal(a: FloatArray, b: FloatArray, c: FloatArray): FloatArray {
        val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
        val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len > 1e-12f) { nx /= len; ny /= len; nz /= len }
        return floatArrayOf(nx, ny, nz)
    }
}