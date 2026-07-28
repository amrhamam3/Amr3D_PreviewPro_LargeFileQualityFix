package com.amr3d.preview.pro

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Represents the parsed geometry of an STL file.
 * vertices: flat array of x,y,z per vertex
 * normals: flat array of nx,ny,nz per vertex (one normal per triangle, repeated for each of its 3 vertices)
 * triangleCount: number of triangles
 */
data class STLModel(
    val vertices: FloatArray,
    val normals: FloatArray,
    val triangleCount: Int,
    val minBounds: FloatArray, // [minX, minY, minZ]
    val maxBounds: FloatArray, // [maxX, maxY, maxZ]
    val isWatertightHint: Boolean // basic heuristic, not a full manifold check
)

class STLParseException(message: String) : Exception(message)

object STLParser {
    
    private const val MAX_FILE_SIZE = 2_000_000_000L // 2 GB limit
    private const val CHUNK_SIZE = 4_000_000 // Read 4MB chunks
    // لا حد ثابت للـ ASCII — يعتمد على RAM الجهاز

    /** ⚠️ حد أمان على عدد المثلثات اللي بتترفع فعليًا في الذاكرة وقت القراءة نفسها
     * — بغض النظر عن إعداد الجودة أو موافقة المستخدم على التبسيط. المشكلة اللي
     * بيحلها: قبل كده كان التبسيط (MeshDecimator) بيتنفذ بعد ما القراءة الخام
     * تخلص بالكامل — يعني ملف ضخم جدًا كان ممكن يعمل OutOfMemoryError أثناء
     * التخصيص/القراءة الخام نفسها، قبل ما التبسيط ياخد فرصته أصلاً. دلوقتي القراءة
     * نفسها بتوقف عن تخزين كل مثلث لما العدد يتجاوز الحد ده (بتاخد عينة بانتظام
     * Stride Sampling بدل ما تخزن الكل) — فالذاكرة المحجوزة أصلاً بتفضل محدودة
     * دايمًا، مهما كان حجم الملف. الحدود الخارجية (Bounding Box) بتتحسب من كل
     * مثلث في الملف بالكامل (حتى المتجاهل من التخزين) عشان الأبعاد الحقيقية
     * للقطعة تفضل دقيقة 100% حتى مع العينة دي.
     *
     * ⚠️ الحد ده مش رقم ثابت — بيتحسب حسب أقصى ذاكرة فعليًا متاحة للتطبيق على
     * الجهاز نفسه (Runtime.getRuntime().maxMemory())، مش تخمين واحد يفترض نفس
     * القيمة لكل الأجهزة. بناخد جزء صغير بس منها (18%) كهامش أمان كبير، عشان
     * يفضل فاضي مساحة كافية للنسخ المؤقتة أثناء تصحيح المحور والتبسيط الإضافي،
     * وذاكرة الواجهة والـ GPU. الأولوية المطلقة: الملف لازم يفتح، حتى لو بتفاصيل
     * أقل من كده على الأجهزة الضعيفة في الرام. */
    private fun safeTriangleCap(): Int {
        val maxHeapBytes = Runtime.getRuntime().maxMemory()
        // ⚠️ رُفعت من 18% إلى 30% (بلاغ Amr: ملفات ~70 ميجا كانت بتطلع "مشوّهة/
        // مفتّتة" رغم إن نافذة تنبيه الملفات الكبيرة متظبطة على 100 ميجا — السبب
        // الحقيقي إن الحد ده (safeTriangleCap) مستقل تمامًا عن نافذة التنبيه دي،
        // وكان بيتفعّل بصمت جوه القراءة الخام نفسها حتى لو المستخدم مشافش أي رسالة
        // تحذير. رفع النسبة + سقف أعلى بيقلل احتمالية تفعيله أصلاً لملفات الحجم
        // ده على أغلب الأجهزة (largeHeap مفعّل بالفعل في المانيفست).
        val budgetBytes = (maxHeapBytes * 0.30).toLong()
        val bytesPerTriangle = 72L // 9 floats vertices + 9 floats normals × 4 بايت لكل float
        val cap = (budgetBytes / bytesPerTriangle)
        return cap.coerceIn(250_000L, 8_000_000L).toInt()
    }

    /**
     * Entry point: detects ASCII vs Binary STL and parses accordingly.
     * Uses streaming for large files to avoid OutOfMemoryError.
     * onProgress: 0..100 — نسبة تقدم القراءة الفعلية (بيتنادى من خيط IO، لازم تحدّث الـ UI على الـ main thread)
     */
    fun parse(context: Context, uri: Uri, onProgress: (Int) -> Unit = {}): STLModel {
        val resolver = context.contentResolver

        // ✅ إصلاح: استخدام ContentResolver.query للحصول على الحجم الحقيقي
        val fileSize: Long = resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
            } else -1L
        } ?: -1L

        val actualSize = if (fileSize > 0) fileSize else
            resolver.openInputStream(uri)?.use { stream ->
                var count = 0L; val buf = ByteArray(8192)
                var n = stream.read(buf)
                while (n >= 0) { count += n; n = stream.read(buf) }
                count
            } ?: throw STLParseException(context.getString(R.string.error_stl_open_failed))

        if (actualSize == 0L) {
            throw STLParseException(context.getString(R.string.error_stl_empty))
        }

        if (actualSize > MAX_FILE_SIZE) {
            throw STLParseException(context.getString(R.string.error_stl_too_large))
        }

        // Read header only (512 bytes) to detect format
        val headerBytes = ByteArray(minOf(512, actualSize.toInt()))
        resolver.openInputStream(uri)?.use { stream ->
            stream.read(headerBytes)
        } ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        return if (isAsciiSTL(headerBytes, actualSize)) {
            parseAsciiStreaming(context, uri, actualSize, onProgress)
        } else {
            parseBinaryOptimized(context, uri, actualSize, onProgress)
        }
    }

    /**
     * Heuristic: ASCII STL files start with "solid" (case-insensitive) AND contain "facet"
     * shortly after. Some binary files also start with "solid" in their header by mistake,
     * so we double check for the "facet normal" token, and also validate via expected
     * binary size as a fallback.
     */
    private fun isAsciiSTL(headerBytes: ByteArray, fileSize: Long): Boolean {
        val header = String(headerBytes, Charsets.US_ASCII).trim()

        if (!header.lowercase().startsWith("solid")) {
            return false
        }

        // Check if the binary-size formula matches; if it matches well, treat as binary
        if (fileSize >= 84) {
            try {
                val triCountFromHeader = ByteBuffer.wrap(headerBytes, 80, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int
                val expectedBinarySize = 84L + (triCountFromHeader.toLong() * 50L)
                if (expectedBinarySize == fileSize) {
                    return false
                }
            } catch (e: Exception) {
                // If parsing fails, assume ASCII
            }
        }

        // Look for "facet" within the header to confirm ASCII structure
        val sample = String(headerBytes, Charsets.US_ASCII)
        return sample.contains("facet", ignoreCase = true)
    }

    /**
     * Optimized binary parsing with memory-efficient chunk processing.
     */
    private fun parseBinaryOptimized(context: Context, uri: Uri, fileSize: Long, onProgress: (Int) -> Unit = {}): STLModel {
        if (fileSize < 84) {
            throw STLParseException(context.getString(R.string.error_stl_binary_corrupt))
        }

        val resolver = context.contentResolver
        val headerBuffer = ByteArray(84)

        resolver.openInputStream(uri)?.use { stream ->
            stream.read(headerBuffer)
        } ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        val triangleCount = ByteBuffer.wrap(headerBuffer, 80, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int

        val expectedSize = 84L + (triangleCount.toLong() * 50L)
        if (expectedSize > fileSize) {
            throw STLParseException(
                context.getString(R.string.error_stl_triangle_mismatch, triangleCount)
            )
        }
        if (triangleCount <= 0) {
            throw STLParseException(context.getString(R.string.error_stl_no_valid_triangles))
        }

        val maxTriangles = safeTriangleCap()

        // ⚠️⚠️ إصلاح جوهري (بلاغ Amr — ملفات ~70 ميجا بتفتح "مشوّهة جدًا"،
        // شكل مفتّت لشظايا مثلثات متناثرة مفصولة عن بعضها): الطريقة القديمة هنا
        // كانت بتاخد "عينة بانتظام" باختيار مثلث كل stride مثلث حسب ترتيبه في
        // الملف نفسه (t % stride == 0). المشكلة إن ملفات STL (خصوصًا من سكانر/
        // ماسح ضوئي) بتتخزّن غالبًا بترتيب متقارب مكانيًا (المثلثات المتجاورة في
        // الفراغ غالبًا متجاورة في الملف كمان) — يعني لما نحتفظ بمثلث ونرمي
        // اللي بعده واللي بعده (جيرانه الفعليين في المساحة)، المثلث المحتفظ بيه
        // بيبقى "جزيرة" معزولة تمامًا (كل أضلاعه الثلاثة من غير أي مثلث مجاور
        // باقي)، وده بالظبط بيدي شكل "الكونفيتي المتناثر" اللي ظهر في اللقطة.
        // هنا ده بالظبط نفس فشل الخوارزمية القديمة اللي MeshDecimator اتعمل
        // أصلاً عشان يتجنبه — بس بيحصل تاني في مرحلة القراءة الخام قبل ما
        // MeshDecimator ياخد فرصته، ومستقل تمامًا عن نافذة تنبيه الملفات الكبيرة
        // في الواجهة (اللي بتتفعّل بس فوق 100 ميجا).
        //
        // الحل: توزيع "الاختيار" على مساحة الموديل نفسها (Spatial Grid Sampling)
        // بدل ترتيب الملف — بنقرأ الملف مرة تحضيرية سريعة أول حاجة عشان نعرف
        // الحدود الخارجية الحقيقية (Bounds)، وبعدين في القراءة الفعلية بنحتفظ
        // بأول مثلث بس يقع في كل خلية من شبكة مكانية خشنة (حجمها محسوب عشان
        // يدّينا تقريبًا العدد المطلوب). النتيجة: المثلثات الباقية موزعة على كل
        // سطح الموديل بانتظام (زي نسخة أقل دقة من نفس الشكل) بدل ما تبقى شظايا
        // معزولة — بالظبط نفس فلسفة findSafeWeldEpsilon في EdgeCollapseDecimator.
        if (triangleCount > maxTriangles) {
            return parseBinaryWithSpatialSampling(context, resolver, uri, triangleCount, maxTriangles, onProgress)
        }

        // ── المسار العادي: الملف يدخل في حدود الذاكرة الآمنة، نخزّن كل مثلث زي ما هو ──
        val vertices = FloatArray(triangleCount * 3 * 3)
        val normals = FloatArray(triangleCount * 3 * 3)

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE

        var vIdx = 0
        val triangleBytes = ByteArray(50) // 50 bytes per triangle
        val progressStep = maxOf(triangleCount / 100, 500)
        var lastReportedPercent = -1

        resolver.openInputStream(uri)?.use { stream ->
            stream.skip(84) // Skip header

            for (t in 0 until triangleCount) {
                if (stream.read(triangleBytes) != 50) {
                    throw STLParseException(context.getString(R.string.error_stl_corrupt_triangle, t))
                }

                val buffer = ByteBuffer.wrap(triangleBytes).order(ByteOrder.LITTLE_ENDIAN)

                val nx = buffer.float
                val ny = buffer.float
                val nz = buffer.float

                for (v in 0 until 3) {
                    val x = buffer.float
                    val y = buffer.float
                    val z = buffer.float

                    vertices[vIdx] = x
                    vertices[vIdx + 1] = y
                    vertices[vIdx + 2] = z

                    normals[vIdx] = nx
                    normals[vIdx + 1] = ny
                    normals[vIdx + 2] = nz

                    vIdx += 3

                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (z < minZ) minZ = z
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                    if (z > maxZ) maxZ = z
                }

                // Skip attribute byte count (2 bytes)
                buffer.short

                if (t % progressStep == 0 || t == triangleCount - 1) {
                    val percent = (((t + 1).toLong() * 90L) / triangleCount).toInt()
                    if (percent != lastReportedPercent) {
                        lastReportedPercent = percent
                        onProgress(percent)
                    }
                }
            }
        } ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        return STLModel(
            vertices = vertices,
            normals = normals,
            triangleCount = triangleCount,
            minBounds = floatArrayOf(minX, minY, minZ),
            maxBounds = floatArrayOf(maxX, maxY, maxZ),
            isWatertightHint = (triangleCount % 2 == 0)
        )
    }

    /**
     * مسار الملفات اللي تجاوزت الحد الآمن للذاكرة — قراءتين بدل واحدة:
     * (1) قراءة تحضيرية سريعة (تحسب الحدود الخارجية بس، من غير أي تخزين) —
     * التكلفة الإضافية مقبولة لأنها بتحصل بس في الحالة النادرة دي (ملف أكبر من
     * الحد الآمن)، وبتاخد 0-40% من شريط التقدم.
     * (2) القراءة الفعلية (40-90%) وفيها بنحتفظ بأول مثلث يقع في كل خلية من
     * شبكة مكانية خشنة محسوبة من حجم الموديل الحقيقي (Spatial Grid Sampling) —
     * بدل الـ Stride القديم اللي كان بيفتّت الموديل (شوف الشرح فوق).
     */
    private fun parseBinaryWithSpatialSampling(
        context: Context, resolver: android.content.ContentResolver, uri: Uri,
        triangleCount: Int, maxTriangles: Int, onProgress: (Int) -> Unit
    ): STLModel {
        val triangleBytes = ByteArray(50)

        // ── القراءة الأولى: الحدود الخارجية بس ──
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE

        val boundsProgressStep = maxOf(triangleCount / 100, 500)
        var lastReportedPercent = -1
        resolver.openInputStream(uri)?.use { stream ->
            stream.skip(84)
            for (t in 0 until triangleCount) {
                if (stream.read(triangleBytes) != 50) {
                    throw STLParseException(context.getString(R.string.error_stl_corrupt_triangle, t))
                }
                val buffer = ByteBuffer.wrap(triangleBytes).order(ByteOrder.LITTLE_ENDIAN)
                buffer.float; buffer.float; buffer.float // skip normal
                for (v in 0 until 3) {
                    val x = buffer.float; val y = buffer.float; val z = buffer.float
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (z < minZ) minZ = z
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                    if (z > maxZ) maxZ = z
                }
                if (t % boundsProgressStep == 0 || t == triangleCount - 1) {
                    val percent = (((t + 1).toLong() * 40L) / triangleCount).toInt() // 0-40%
                    if (percent != lastReportedPercent) {
                        lastReportedPercent = percent
                        onProgress(percent)
                    }
                }
            }
        } ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        // حجم خلية الشبكة: بنستهدف عدد خلايا يدّي تقريبًا maxTriangles خلية على
        // مساحة سطح الموديل (sqrt مش cbrt لنفس سبب findSafeWeldEpsilon — السطح
        // غشاء ثنائي الأبعاد مش حجم مصمت)
        val dx = (maxX - minX).toDouble(); val dy = (maxY - minY).toDouble(); val dz = (maxZ - minZ).toDouble()
        val avgAxisSize = ((dx + dy + dz) / 3.0).coerceAtLeast(1e-6)
        val cellsPerAxis = maxOf(4, Math.ceil(Math.sqrt(maxTriangles.toDouble())).toInt())
        val cellSize = (avgAxisSize / cellsPerAxis).coerceAtLeast(1e-7)
        val gridDim = 1 shl 20
        fun cellIndex(v: Float, minV: Float): Long {
            return ((v - minV).toDouble() / cellSize).toLong().coerceIn(0, (gridDim - 1).toLong())
        }

        val keptCapacity = maxTriangles
        val vertices = FloatArray(keptCapacity * 3 * 3)
        val normals = FloatArray(keptCapacity * 3 * 3)
        var vIdx = 0
        var keptTriangles = 0
        val occupiedCells = HashSet<Long>(keptCapacity)

        resolver.openInputStream(uri)?.use { stream ->
            stream.skip(84)
            for (t in 0 until triangleCount) {
                if (stream.read(triangleBytes) != 50) {
                    throw STLParseException(context.getString(R.string.error_stl_corrupt_triangle, t))
                }
                val buffer = ByteBuffer.wrap(triangleBytes).order(ByteOrder.LITTLE_ENDIAN)
                val nx = buffer.float; val ny = buffer.float; val nz = buffer.float

                val x0 = buffer.float; val y0 = buffer.float; val z0 = buffer.float
                val x1 = buffer.float; val y1 = buffer.float; val z1 = buffer.float
                val x2 = buffer.float; val y2 = buffer.float; val z2 = buffer.float
                buffer.short // attribute byte count

                // مركز المثلث (centroid) هو مفتاح الخلية — كده كل مثلث بيتحدد
                // مكانه الحقيقي في الفراغ، مش ترتيبه في الملف
                val ccx = (x0 + x1 + x2) / 3f; val ccy = (y0 + y1 + y2) / 3f; val ccz = (z0 + z1 + z2) / 3f
                val key = (cellIndex(ccx, minX) shl 42) or (cellIndex(ccy, minY) shl 21) or cellIndex(ccz, minZ)

                val keepThis = keptTriangles < keptCapacity && occupiedCells.add(key)
                if (keepThis) {
                    vertices[vIdx] = x0; vertices[vIdx + 1] = y0; vertices[vIdx + 2] = z0
                    vertices[vIdx + 3] = x1; vertices[vIdx + 4] = y1; vertices[vIdx + 5] = z1
                    vertices[vIdx + 6] = x2; vertices[vIdx + 7] = y2; vertices[vIdx + 8] = z2
                    normals[vIdx] = nx; normals[vIdx + 1] = ny; normals[vIdx + 2] = nz
                    normals[vIdx + 3] = nx; normals[vIdx + 4] = ny; normals[vIdx + 5] = nz
                    normals[vIdx + 6] = nx; normals[vIdx + 7] = ny; normals[vIdx + 8] = nz
                    vIdx += 9
                    keptTriangles++
                }

                if (t % boundsProgressStep == 0 || t == triangleCount - 1) {
                    val percent = 40 + (((t + 1).toLong() * 50L) / triangleCount).toInt() // 40-90%
                    if (percent != lastReportedPercent) {
                        lastReportedPercent = percent
                        onProgress(percent)
                    }
                }
            }
        } ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        return STLModel(
            vertices = if (keptTriangles == keptCapacity) vertices else vertices.copyOf(keptTriangles * 9),
            normals = if (keptTriangles == keptCapacity) normals else normals.copyOf(keptTriangles * 9),
            triangleCount = keptTriangles,
            minBounds = floatArrayOf(minX, minY, minZ),
            maxBounds = floatArrayOf(maxX, maxY, maxZ),
            isWatertightHint = (keptTriangles % 2 == 0)
        )
    }

    /**
     * Streaming ASCII parser to handle large ASCII files without loading entire file into memory.
     */
    private fun parseAsciiStreaming(context: Context, uri: Uri, fileSize: Long, onProgress: (Int) -> Unit = {}): STLModel {
        val resolver = context.contentResolver
        // ── حد الأمان (نفس فكرة parseBinaryOptimized): بما إن عدد المثلثات في
        // ASCII مش معروف مقدمًا، بنقدّره تقريبيًا من حجم الملف (متوسط ~220 بايت
        // لكل مثلث في صياغة ASCII القياسية) عشان نحسب Stride مناسب من الأول ──
        val maxTriangles = safeTriangleCap()
        val estimatedTriangleCount = maxOf(1L, fileSize / 220L)
        val stride = if (estimatedTriangleCount > maxTriangles)
            Math.ceil(estimatedTriangleCount.toDouble() / maxTriangles).toInt()
        else 1

        val vertexList = ArrayList<Float>(minOf(1_000_000, (maxTriangles * 9)))
        val normalList = ArrayList<Float>(minOf(4_000_000, (maxTriangles * 9)))

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE

        var curNx = 0f
        var curNy = 0f
        var curNz = 0f
        var triangleCount = 0
        var keptTriangleCount = 0
        var facetIndex = -1
        var storeCurrentFacet = true
        var vertsInCurrentFacet = 0

        resolver.openInputStream(uri)?.use { rawStream ->
            // Wrapper بيعدّ البايتات المقروءة فعلياً عشان نحسب نسبة التقدم الحقيقية من حجم الملف
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
            val reader = bufferedStream.bufferedReader()

            reader.use { lineReader ->
                lineReader.forEachLine { rawLine ->
                    val line = rawLine.trim()
                    when {
                        line.startsWith("facet normal", ignoreCase = true) -> {
                            val parts = line.split(Regex("\\s+"))
                            if (parts.size >= 5) {
                                try {
                                    curNx = parts[2].toFloat()
                                    curNy = parts[3].toFloat()
                                    curNz = parts[4].toFloat()
                                } catch (e: NumberFormatException) {
                                    curNx = 0f
                                    curNy = 0f
                                    curNz = 0f
                                }
                            }
                            vertsInCurrentFacet = 0
                            facetIndex++
                            // ── حد الأمان: نقرر تخزين المثلث ده من عدمه بناءً على الـ
                            // Stride المحسوب سلفًا، وبرضو نوقف التخزين لو وصلنا لسقف
                            // الأمان الأقصى (maxTriangles، محسوب حسب رام الجهاز) حتى
                            // لو الملف فعليًا أكبر من التقدير المبدئي ──
                            storeCurrentFacet = (facetIndex % stride == 0) &&
                                keptTriangleCount < maxTriangles
                        }
                        line.startsWith("vertex", ignoreCase = true) -> {
                            val parts = line.split(Regex("\\s+"))
                            if (parts.size >= 4) {
                                try {
                                    val x = parts[1].toFloat()
                                    val y = parts[2].toFloat()
                                    val z = parts[3].toFloat()

                                    if (storeCurrentFacet) {
                                        vertexList.add(x)
                                        vertexList.add(y)
                                        vertexList.add(z)
                                        normalList.add(curNx)
                                        normalList.add(curNy)
                                        normalList.add(curNz)
                                    }

                                    // الحدود الخارجية بتتحسب من كل مثلث في الملف (حتى
                                    // المتجاهل من التخزين) عشان الأبعاد الحقيقية تفضل دقيقة
                                    if (x < minX) minX = x
                                    if (y < minY) minY = y
                                    if (z < minZ) minZ = z
                                    if (x > maxX) maxX = x
                                    if (y > maxY) maxY = y
                                    if (z > maxZ) maxZ = z

                                    vertsInCurrentFacet++

                                } catch (e: NumberFormatException) {
                                    throw STLParseException(context.getString(R.string.error_stl_invalid_value, line))
                                }
                            }
                        }
                        line.startsWith("endfacet", ignoreCase = true) -> {
                            if (vertsInCurrentFacet == 3) {
                                triangleCount++
                                if (storeCurrentFacet) keptTriangleCount++
                            }
                        }
                    }
                }
            }
        } ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        if (triangleCount == 0) {
            throw STLParseException(context.getString(R.string.error_stl_ascii_no_triangles))
        }

        return STLModel(
            vertices = vertexList.toFloatArray(),
            normals = normalList.toFloatArray(),
            triangleCount = keptTriangleCount,
            minBounds = floatArrayOf(minX, minY, minZ),
            maxBounds = floatArrayOf(maxX, maxY, maxZ),
            isWatertightHint = (keptTriangleCount % 2 == 0)
        )
    }
}
