package com.amr3d.preview.pro

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Represents the parsed geometry of an STL file.
 *
 * @param vertices Flat array of x,y,z per vertex (3 vertices × 3 coords per triangle).
 * @param normals Flat array of nx,ny,nz per vertex (one normal per triangle, repeated for 3 vertices).
 * @param triangleCount Number of triangles actually stored in memory (after sampling, if any).
 * @param minBounds [minX, minY, minZ] — computed from **all** triangles in the file, even skipped ones.
 * @param maxBounds [maxX, maxY, maxZ] — computed from **all** triangles in the file.
 * @param estimatedOriginalTriangleCount The original triangle count declared in the file (or counted for ASCII).
 * @param isApproximate True if sampling was applied (kept fewer triangles than the original file has).
 * @param isWatertightHint Basic heuristic (even triangle count), not a full manifold check.
 */
data class STLModel(
    val vertices: FloatArray,
    val normals: FloatArray,
    val triangleCount: Int,
    val minBounds: FloatArray,
    val maxBounds: FloatArray,
    val estimatedOriginalTriangleCount: Int,
    val isApproximate: Boolean,
    val isWatertightHint: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as STLModel
        return triangleCount == other.triangleCount &&
                estimatedOriginalTriangleCount == other.estimatedOriginalTriangleCount &&
                isApproximate == other.isApproximate &&
                isWatertightHint == other.isWatertightHint &&
                vertices.contentEquals(other.vertices) &&
                normals.contentEquals(other.normals) &&
                minBounds.contentEquals(other.minBounds) &&
                maxBounds.contentEquals(other.maxBounds)
    }

    override fun hashCode(): Int {
        var result = vertices.contentHashCode()
        result = 31 * result + normals.contentHashCode()
        result = 31 * result + triangleCount
        result = 31 * result + minBounds.contentHashCode()
        result = 31 * result + maxBounds.contentHashCode()
        result = 31 * result + estimatedOriginalTriangleCount
        result = 31 * result + isApproximate.hashCode()
        result = 31 * result + isWatertightHint.hashCode()
        return result
    }
}

class STLParseException(message: String) : Exception(message)

/**
 * ═══════════════════════════════════════════════════════════════════════
 * ملحوظة معمارية مهمة (بعد بلاغ Amr على ملفات ~70 ميجا بتفتح "مشوّهة"):
 * ═══════════════════════════════════════════════════════════════════════
 * لما حجم الملف بيتجاوز حد أمان الذاكرة (safeTriangleCap)، مينفعش نختار
 * المثلثات المحتفظ بيها حسب **ترتيبها في الملف** (Stride: كل مثلث N).
 * ملفات STL (خصوصًا من سكانر) غالبًا متخزنة بترتيب متقارب مكانيًا، فلو
 * حافظنا على مثلث ورمينا اللي بعده مباشرة في الملف، فعليًا بنرمي **جاره
 * في الفراغ كمان** — فالمثلث المحتفظ بيه بيتعزل تمامًا (زي شظية طايرة)،
 * وده بيدي شكل "كونفيتي متناثر" مش موديل مبسّط بشكل معقول.
 *
 * البديل هنا: Spatial Grid Sampling — بنحتفظ بأول مثلث بس يقع مركزه في كل
 * خلية من شبكة مكانية خشنة (حجمها محسوب من حدود الموديل الحقيقية + العدد
 * المستهدف)، فالمثلثات الباقية بتتوزع على مساحة السطح كله بانتظام، مش حسب
 * ترتيب التخزين. بيحتاج قراءتين للملف (حدود أول حاجة، بعدين الاختيار
 * الفعلي) بس في الحالة النادرة دي بس (ملف أكبر من الحد الآمن) — التكلفة
 * مقبولة جدًا مقابل تجنب موديل متكسّر.
 */
object STLParser {

    private const val MAX_FILE_SIZE = 2_000_000_000L // 2 GB
    private const val BINARY_HEADER_SIZE = 84
    private const val BYTES_PER_TRIANGLE = 50
    private const val FLOATS_PER_TRIANGLE = 9 // 3 vertices × 3 coords
    private const val BYTES_PER_FLOAT = 4

    /**
     * ميزانية ذاكرة ديناميكية: 30% من أقصى heap متاح (largeHeap مفعّل في
     * المانيفست)، بحد أدنى/أقصى 250 ألف–8 مليون مثلث. رُفعت من 18%/4M
     * القديمة عشان تقلل احتمالية الدخول في مسار العينة أصلاً لملفات بحجم
     * ~70-100 ميجا على أغلب الأجهزة — أداء العرض نفسه اتحكم فيه بشكل منفصل
     * عن طريق MeshDecimator/EdgeCollapseDecimator (حسب إعداد الجودة)، مش
     * عن طريق تخريب البيانات وقت القراءة.
     */
    private fun safeTriangleCap(): Int {
        val maxHeapBytes = Runtime.getRuntime().maxMemory()
        val budgetBytes = (maxHeapBytes * 0.30).toLong()
        val bytesPerTriangle = (FLOATS_PER_TRIANGLE * 2 * BYTES_PER_FLOAT).toLong() // vertices + normals
        val cap = budgetBytes / bytesPerTriangle
        return cap.coerceIn(250_000L, 8_000_000L).toInt()
    }

    /**
     * Android entry point. Detects format and parses with streaming.
     * ⚠️ [onProgress] is called from the IO thread — update UI on Main Thread.
     * بيفتح الملف أكتر من مرة عند الحاجة (ملفات فوق الحد الآمن) — متاح لأننا
     * شغالين بـ [Uri] مش [InputStream] واحد، فكل قراءة بتاخد Stream جديد.
     */
    fun parse(context: Context, uri: Uri, onProgress: (Int) -> Unit = {}): STLModel {
        val resolver = context.contentResolver
        val fileSize = getFileSize(resolver, uri)

        if (fileSize == 0L) {
            throw STLParseException(context.getString(R.string.error_stl_empty))
        }
        if (fileSize > MAX_FILE_SIZE) {
            throw STLParseException(context.getString(R.string.error_stl_too_large))
        }

        val headerBytes = ByteArray(minOf(BINARY_HEADER_SIZE, fileSize.toInt()))
        resolver.openInputStream(uri)?.use { it.read(headerBytes) }
            ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        return if (isAsciiSTL(headerBytes, fileSize)) {
            parseAsciiFromUri(context, resolver, uri, fileSize, onProgress)
        } else {
            parseBinaryFromUri(context, resolver, uri, fileSize, onProgress)
        }
    }

    /**
     * Platform-agnostic entry point. Accepts a single [InputStream] and file size — مناسب
     * للاختبارات أو الاستخدام برة سياق Android. ⚠️ بما إن الـ Stream مينفعش يتقرأ مرتين،
     * المسار ده بيستخدم Stride Sampling بترتيب الملف لو الحجم تجاوز الحد الآمن (مش
     * Spatial Grid) — نادرًا ما يحصل عمليًا لأن الاستخدام الأساسي في التطبيق دايمًا
     * عن طريق [parse] بالـ [Uri] فوق، اللي بيدعم القراءتين وبيتجنب المشكلة دي بالكامل.
     */
    fun parse(input: InputStream, fileSize: Long, onProgress: (Int) -> Unit = {}): STLModel {
        if (fileSize == 0L) throw STLParseException("STL file is empty")
        if (fileSize > MAX_FILE_SIZE) throw STLParseException("STL file too large")

        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 8192)
        buffered.mark(1024)

        val headerBytes = ByteArray(minOf(BINARY_HEADER_SIZE, fileSize.toInt()))
        val read = buffered.read(headerBytes)
        if (read < 0) throw STLParseException("Failed to read STL header")

        buffered.reset()

        return if (isAsciiSTL(headerBytes, fileSize)) {
            parseAsciiSingleStream(buffered, fileSize, onProgress)
        } else {
            parseBinarySingleStream(buffered, fileSize, onProgress)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun getFileSize(resolver: android.content.ContentResolver, uri: Uri): Long {
        var size = -1L
        resolver.query(
            uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) size = cursor.getLong(idx)
            }
        }
        if (size > 0) return size

        return resolver.openInputStream(uri)?.use { stream ->
            var count = 0L
            val buf = ByteArray(8192)
            var n: Int
            while (stream.read(buf).also { n = it } >= 0) count += n
            count
        } ?: throw STLParseException("Failed to determine file size")
    }

    /** Heuristic: starts with "solid" AND contains "facet"; falls back to binary-size validation. */
    private fun isAsciiSTL(headerBytes: ByteArray, fileSize: Long): Boolean {
        val header = String(headerBytes, Charsets.US_ASCII).trim()
        if (!header.lowercase().startsWith("solid")) return false

        if (fileSize >= BINARY_HEADER_SIZE) {
            try {
                val triCount = ByteBuffer.wrap(headerBytes, 80, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int
                val expectedSize = BINARY_HEADER_SIZE + (triCount.toLong() * BYTES_PER_TRIANGLE)
                if (expectedSize == fileSize) return false
            } catch (_: Exception) { /* assume ASCII if header parse fails */ }
        }
        return header.contains("facet", ignoreCase = true)
    }

    /** حجم خلية شبكة مكانية بتستهدف تقريبًا [targetCount] خلية على مساحة سطح الموديل
     * (sqrt مش cbrt — السطح غشاء ثنائي الأبعاد جوه صندوق ثلاثي الأبعاد، مش حجم مصمت،
     * نفس المنطق المستخدم في EdgeCollapseDecimator.findSafeWeldEpsilon). */
    private fun computeCellSize(minB: FloatArray, maxB: FloatArray, targetCount: Int): Double {
        val dx = (maxB[0] - minB[0]).toDouble()
        val dy = (maxB[1] - minB[1]).toDouble()
        val dz = (maxB[2] - minB[2]).toDouble()
        val avgAxisSize = ((dx + dy + dz) / 3.0).coerceAtLeast(1e-6)
        val cellsPerAxis = maxOf(4, Math.ceil(Math.sqrt(targetCount.toDouble())).toInt())
        return (avgAxisSize / cellsPerAxis).coerceAtLeast(1e-7)
    }

    private const val GRID_DIM = 1L shl 20
    private fun cellIndex(v: Float, minV: Float, cellSize: Double): Long =
        ((v - minV).toDouble() / cellSize).toLong().coerceIn(0, GRID_DIM - 1)

    private fun cellKey(cx: Float, cy: Float, cz: Float, minB: FloatArray, cellSize: Double): Long {
        val ix = cellIndex(cx, minB[0], cellSize)
        val iy = cellIndex(cy, minB[1], cellSize)
        val iz = cellIndex(cz, minB[2], cellSize)
        return (ix shl 42) or (iy shl 21) or iz
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Binary Parser — Uri-based (supports two-pass spatial sampling)
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseBinaryFromUri(
        context: Context, resolver: android.content.ContentResolver, uri: Uri,
        fileSize: Long, onProgress: (Int) -> Unit
    ): STLModel {
        if (fileSize < BINARY_HEADER_SIZE) {
            throw STLParseException(context.getString(R.string.error_stl_binary_corrupt))
        }

        val header = ByteArray(BINARY_HEADER_SIZE)
        resolver.openInputStream(uri)?.use { it.read(header) }
            ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        val triangleCount = ByteBuffer.wrap(header, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val expectedSize = BINARY_HEADER_SIZE + (triangleCount.toLong() * BYTES_PER_TRIANGLE)
        if (expectedSize > fileSize) {
            throw STLParseException(context.getString(R.string.error_stl_triangle_mismatch, triangleCount))
        }
        if (triangleCount <= 0) {
            throw STLParseException(context.getString(R.string.error_stl_no_valid_triangles))
        }

        val maxTriangles = safeTriangleCap()

        if (triangleCount <= maxTriangles) {
            // ── المسار السريع: الملف يدخل في حدود الذاكرة الآمنة، قراءة واحدة كاملة ──
            return resolver.openInputStream(uri)?.use { stream ->
                readAllBinaryTriangles(context, stream, triangleCount, onProgress)
            } ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))
        }

        // ── مسار العينة: قراءة تحضيرية للحدود (0-40%) + قراءة فعلية بـ Spatial Grid (40-90%) ──
        val (minB, maxB) = resolver.openInputStream(uri)?.use { stream ->
            computeBinaryBounds(context, stream, triangleCount) { p -> onProgress((p * 40) / 100) }
        } ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        val cellSize = computeCellSize(minB, maxB, maxTriangles)

        return resolver.openInputStream(uri)?.use { stream ->
            readBinaryTrianglesSpatialSampled(
                context, stream, triangleCount, maxTriangles, minB, maxB, cellSize
            ) { p -> onProgress(40 + (p * 50) / 100) }
        } ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))
    }

    /** قراءة كل المثلثات بدون أي عينة — بيعيد استخدام [ByteBuffer] واحد بدل تخصيص واحد
     * جديد لكل مثلث (تقليل ضغط GC على الملفات الكبيرة، أسرع بشكل ملموس). */
    private fun readAllBinaryTriangles(
        context: Context, stream: InputStream, triangleCount: Int, onProgress: (Int) -> Unit
    ): STLModel {
        stream.skip(BINARY_HEADER_SIZE.toLong())

        val vertices = FloatArray(triangleCount * FLOATS_PER_TRIANGLE)
        val normals = FloatArray(triangleCount * FLOATS_PER_TRIANGLE)

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        val triangleBytes = ByteArray(BYTES_PER_TRIANGLE)
        val buffer = ByteBuffer.wrap(triangleBytes).order(ByteOrder.LITTLE_ENDIAN)
        val progressStep = maxOf(triangleCount / 100, 500)
        var lastReportedPercent = -1
        var vIdx = 0

        for (t in 0 until triangleCount) {
            if (stream.read(triangleBytes) != BYTES_PER_TRIANGLE) {
                throw STLParseException(context.getString(R.string.error_stl_corrupt_triangle, t))
            }
            buffer.clear()
            val nx = buffer.float; val ny = buffer.float; val nz = buffer.float

            repeat(3) {
                val x = buffer.float; val y = buffer.float; val z = buffer.float
                vertices[vIdx] = x; vertices[vIdx + 1] = y; vertices[vIdx + 2] = z
                normals[vIdx] = nx; normals[vIdx + 1] = ny; normals[vIdx + 2] = nz
                vIdx += 3
                if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
            }
            buffer.short // skip attribute byte count

            if (t % progressStep == 0 || t == triangleCount - 1) {
                val percent = (((t + 1).toLong() * 90L) / triangleCount).toInt()
                if (percent != lastReportedPercent) { lastReportedPercent = percent; onProgress(percent) }
            }
        }

        return STLModel(
            vertices = vertices, normals = normals, triangleCount = triangleCount,
            minBounds = floatArrayOf(minX, minY, minZ), maxBounds = floatArrayOf(maxX, maxY, maxZ),
            estimatedOriginalTriangleCount = triangleCount, isApproximate = false,
            isWatertightHint = (triangleCount % 2 == 0)
        )
    }

    /** قراءة تحضيرية سريعة: الحدود الخارجية بس، من غير أي تخزين للمثلثات. */
    private fun computeBinaryBounds(
        context: Context, stream: InputStream, triangleCount: Int, onProgress: (Int) -> Unit
    ): Pair<FloatArray, FloatArray> {
        stream.skip(BINARY_HEADER_SIZE.toLong())
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        val triangleBytes = ByteArray(BYTES_PER_TRIANGLE)
        val buffer = ByteBuffer.wrap(triangleBytes).order(ByteOrder.LITTLE_ENDIAN)
        val progressStep = maxOf(triangleCount / 100, 500)
        var lastReportedPercent = -1

        for (t in 0 until triangleCount) {
            if (stream.read(triangleBytes) != BYTES_PER_TRIANGLE) {
                throw STLParseException(context.getString(R.string.error_stl_corrupt_triangle, t))
            }
            buffer.clear()
            buffer.float; buffer.float; buffer.float // skip normal
            repeat(3) {
                val x = buffer.float; val y = buffer.float; val z = buffer.float
                if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
            }
            buffer.short
            if (t % progressStep == 0 || t == triangleCount - 1) {
                val percent = (((t + 1).toLong() * 100L) / triangleCount).toInt()
                if (percent != lastReportedPercent) { lastReportedPercent = percent; onProgress(percent) }
            }
        }
        return floatArrayOf(minX, minY, minZ) to floatArrayOf(maxX, maxY, maxZ)
    }

    /** القراءة الفعلية: بتحتفظ بأول مثلث يقع مركزه في كل خلية من الشبكة المكانية
     * (بدل Stride بترتيب الملف) — شوف الشرح المعماري أعلى الملف. */
    private fun readBinaryTrianglesSpatialSampled(
        context: Context, stream: InputStream, triangleCount: Int, maxTriangles: Int,
        minB: FloatArray, maxB: FloatArray, cellSize: Double, onProgress: (Int) -> Unit
    ): STLModel {
        stream.skip(BINARY_HEADER_SIZE.toLong())

        val vertices = FloatArray(maxTriangles * FLOATS_PER_TRIANGLE)
        val normals = FloatArray(maxTriangles * FLOATS_PER_TRIANGLE)
        val occupiedCells = HashSet<Long>(maxTriangles)

        val triangleBytes = ByteArray(BYTES_PER_TRIANGLE)
        val buffer = ByteBuffer.wrap(triangleBytes).order(ByteOrder.LITTLE_ENDIAN)
        val progressStep = maxOf(triangleCount / 100, 500)
        var lastReportedPercent = -1
        var vIdx = 0
        var keptTriangles = 0

        for (t in 0 until triangleCount) {
            if (stream.read(triangleBytes) != BYTES_PER_TRIANGLE) {
                throw STLParseException(context.getString(R.string.error_stl_corrupt_triangle, t))
            }
            buffer.clear()
            val nx = buffer.float; val ny = buffer.float; val nz = buffer.float
            val x0 = buffer.float; val y0 = buffer.float; val z0 = buffer.float
            val x1 = buffer.float; val y1 = buffer.float; val z1 = buffer.float
            val x2 = buffer.float; val y2 = buffer.float; val z2 = buffer.float
            buffer.short

            if (keptTriangles < maxTriangles) {
                val ccx = (x0 + x1 + x2) / 3f; val ccy = (y0 + y1 + y2) / 3f; val ccz = (z0 + z1 + z2) / 3f
                val key = cellKey(ccx, ccy, ccz, minB, cellSize)
                if (occupiedCells.add(key)) {
                    vertices[vIdx] = x0; vertices[vIdx + 1] = y0; vertices[vIdx + 2] = z0
                    vertices[vIdx + 3] = x1; vertices[vIdx + 4] = y1; vertices[vIdx + 5] = z1
                    vertices[vIdx + 6] = x2; vertices[vIdx + 7] = y2; vertices[vIdx + 8] = z2
                    normals[vIdx] = nx; normals[vIdx + 1] = ny; normals[vIdx + 2] = nz
                    normals[vIdx + 3] = nx; normals[vIdx + 4] = ny; normals[vIdx + 5] = nz
                    normals[vIdx + 6] = nx; normals[vIdx + 7] = ny; normals[vIdx + 8] = nz
                    vIdx += 9
                    keptTriangles++
                }
            }

            if (t % progressStep == 0 || t == triangleCount - 1) {
                val percent = (((t + 1).toLong() * 100L) / triangleCount).toInt()
                if (percent != lastReportedPercent) { lastReportedPercent = percent; onProgress(percent) }
            }
        }

        return STLModel(
            vertices = if (keptTriangles == maxTriangles) vertices else vertices.copyOf(keptTriangles * FLOATS_PER_TRIANGLE),
            normals = if (keptTriangles == maxTriangles) normals else normals.copyOf(keptTriangles * FLOATS_PER_TRIANGLE),
            triangleCount = keptTriangles,
            minBounds = minB, maxBounds = maxB,
            estimatedOriginalTriangleCount = triangleCount,
            isApproximate = true,
            isWatertightHint = (keptTriangles % 2 == 0)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ASCII Parser — Uri-based (supports two-pass spatial sampling)
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseAsciiFromUri(
        context: Context, resolver: android.content.ContentResolver, uri: Uri,
        fileSize: Long, onProgress: (Int) -> Unit
    ): STLModel {
        val maxTriangles = safeTriangleCap()
        // تقدير سريع من حجم الملف (~220 بايت/مثلث في صياغة ASCII القياسية) عشان نقرر
        // من الأول: مسار سريع (قراءة واحدة) ولا مسار العينة (قراءتين) — بدون التقدير
        // ده كنا هنحتاج نعمل قراءة تحضيرية حتى للملفات الصغيرة، وده هدر غير لازم.
        val estimatedTriangleCount = maxOf(1L, fileSize / 220L)

        if (estimatedTriangleCount <= maxTriangles) {
            val result = resolver.openInputStream(uri)?.use { stream ->
                readAllAsciiTriangles(context, stream, fileSize, maxTriangles, onProgress)
            } ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))
            // لو التقدير كان غلط والملف فعليًا أكبر بكتير من المتوقع، readAllAsciiTriangles
            // بترجع isApproximate=true تلقائيًا (بتوقف عند maxTriangles) — تعامل آمن كحد أدنى
            return result
        }

        // ── مسار العينة: قراءة تحضيرية (عدد حقيقي + حدود، 0-40%) + قراءة فعلية Spatial Grid (40-90%) ──
        val (exactCount, minB, maxB) = resolver.openInputStream(uri)?.use { stream ->
            countAsciiTrianglesAndBounds(stream, fileSize) { p -> onProgress((p * 40) / 100) }
        } ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        if (exactCount == 0) {
            throw STLParseException(context.getString(R.string.error_stl_ascii_no_triangles))
        }
        if (exactCount <= maxTriangles) {
            // العدد الحقيقي طلع أقل من المتوقع (تقدير 220 بايت/مثلث كان متشائم) — نقدر
            // نخزن الكل عادي في قراءة تانية بسيطة، من غير الحاجة لـ Spatial Grid
            val result = resolver.openInputStream(uri)?.use { stream ->
                readAllAsciiTriangles(context, stream, fileSize, maxTriangles) { p -> onProgress(40 + (p * 50) / 100) }
            } ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))
            return result
        }

        val cellSize = computeCellSize(minB, maxB, maxTriangles)
        return resolver.openInputStream(uri)?.use { stream ->
            readAsciiTrianglesSpatialSampled(stream, fileSize, maxTriangles, exactCount, minB, maxB, cellSize) { p ->
                onProgress(40 + (p * 50) / 100)
            }
        } ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))
    }

    /** Manual token parsing (no Regex) لسطر "facet normal nx ny nz" أو "vertex x y z". */
    private fun parseFloatTriplet(line: String, skipChars: Int): Triple<Float, Float, Float>? {
        val after = line.substring(skipChars).trimStart()
        val parts = after.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        return try {
            Triple(parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat())
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** قراءة كل المثلثات بدون عينة (أو مع توقف آمن عند maxTriangles كحد أقصى أخير). */
    private fun readAllAsciiTriangles(
        context: Context, input: InputStream, fileSize: Long, maxTriangles: Int, onProgress: (Int) -> Unit
    ): STLModel {
        var capacity = 4096 * FLOATS_PER_TRIANGLE
        var vertices = FloatArray(capacity)
        var normals = FloatArray(capacity)

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        var curNx = 0f; var curNy = 0f; var curNz = 0f
        var totalTriangleCount = 0
        var keptTriangleCount = 0
        var storeCurrentFacet = true
        var vertsInCurrentFacet = 0
        var vIdx = 0
        var approxBytesRead = 0L
        var lastReportedPercent = -1

        val reader = input.bufferedReader()
        reader.use { br ->
            var rawLine: String?
            while (br.readLine().also { rawLine = it } != null) {
                val raw = rawLine!!
                val line = raw.trim()
                approxBytesRead += raw.length + 1

                when {
                    line.startsWith("facet normal", ignoreCase = true) -> {
                        val n = parseFloatTriplet(line, 12)
                        if (n != null) { curNx = n.first; curNy = n.second; curNz = n.third }
                        else { curNx = 0f; curNy = 0f; curNz = 0f }
                        vertsInCurrentFacet = 0
                        storeCurrentFacet = keptTriangleCount < maxTriangles
                    }
                    line.startsWith("vertex", ignoreCase = true) -> {
                        val v = parseFloatTriplet(line, 6)
                            ?: throw STLParseException(context.getString(R.string.error_stl_invalid_value, raw))
                        val (x, y, z) = v

                        if (storeCurrentFacet) {
                            if (vIdx + 3 > vertices.size) {
                                val newCap = (vertices.size * 2).coerceAtMost(maxTriangles * FLOATS_PER_TRIANGLE)
                                if (newCap <= vertices.size) {
                                    storeCurrentFacet = false
                                } else {
                                    vertices = vertices.copyOf(newCap)
                                    normals = normals.copyOf(newCap)
                                }
                            }
                            if (storeCurrentFacet) {
                                vertices[vIdx] = x; vertices[vIdx + 1] = y; vertices[vIdx + 2] = z
                                normals[vIdx] = curNx; normals[vIdx + 1] = curNy; normals[vIdx + 2] = curNz
                                vIdx += 3
                            }
                        }

                        if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                        if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
                        vertsInCurrentFacet++
                    }
                    line.startsWith("endfacet", ignoreCase = true) -> {
                        if (vertsInCurrentFacet == 3) {
                            totalTriangleCount++
                            if (storeCurrentFacet) keptTriangleCount++
                        }
                    }
                }

                if (fileSize > 0) {
                    val percent = ((approxBytesRead * 90L) / fileSize).toInt().coerceIn(0, 90)
                    if (percent != lastReportedPercent) { lastReportedPercent = percent; onProgress(percent) }
                }
            }
        }

        if (totalTriangleCount == 0) {
            throw STLParseException(context.getString(R.string.error_stl_ascii_no_triangles))
        }

        val finalSize = keptTriangleCount * FLOATS_PER_TRIANGLE
        return STLModel(
            vertices = if (finalSize == vertices.size) vertices else vertices.copyOf(finalSize),
            normals = if (finalSize == normals.size) normals else normals.copyOf(finalSize),
            triangleCount = keptTriangleCount,
            minBounds = floatArrayOf(minX, minY, minZ),
            maxBounds = floatArrayOf(maxX, maxY, maxZ),
            estimatedOriginalTriangleCount = totalTriangleCount,
            isApproximate = keptTriangleCount < totalTriangleCount,
            isWatertightHint = (keptTriangleCount % 2 == 0)
        )
    }

    /** قراءة تحضيرية: بترجع العدد الحقيقي للمثلثات + الحدود الخارجية، من غير أي تخزين. */
    private fun countAsciiTrianglesAndBounds(
        input: InputStream, fileSize: Long, onProgress: (Int) -> Unit
    ): Triple<Int, FloatArray, FloatArray> {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var triangleCount = 0
        var vertsInCurrentFacet = 0
        var approxBytesRead = 0L
        var lastReportedPercent = -1

        val reader = input.bufferedReader()
        reader.use { br ->
            var rawLine: String?
            while (br.readLine().also { rawLine = it } != null) {
                val raw = rawLine!!
                val line = raw.trim()
                approxBytesRead += raw.length + 1

                when {
                    line.startsWith("facet normal", ignoreCase = true) -> vertsInCurrentFacet = 0
                    line.startsWith("vertex", ignoreCase = true) -> {
                        val v = parseFloatTriplet(line, 6) ?: return@use
                        val (x, y, z) = v
                        if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                        if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
                        vertsInCurrentFacet++
                    }
                    line.startsWith("endfacet", ignoreCase = true) -> {
                        if (vertsInCurrentFacet == 3) triangleCount++
                    }
                }
                if (fileSize > 0) {
                    val percent = ((approxBytesRead * 100L) / fileSize).toInt().coerceIn(0, 100)
                    if (percent != lastReportedPercent) { lastReportedPercent = percent; onProgress(percent) }
                }
            }
        }
        return Triple(triangleCount, floatArrayOf(minX, minY, minZ), floatArrayOf(maxX, maxY, maxZ))
    }

    /** القراءة الفعلية بـ Spatial Grid: بتحتفظ بأول مثلث يقع مركزه في كل خلية. */
    private fun readAsciiTrianglesSpatialSampled(
        input: InputStream, fileSize: Long, maxTriangles: Int, exactCount: Int,
        minB: FloatArray, maxB: FloatArray, cellSize: Double, onProgress: (Int) -> Unit
    ): STLModel {
        val vertices = FloatArray(maxTriangles * FLOATS_PER_TRIANGLE)
        val normals = FloatArray(maxTriangles * FLOATS_PER_TRIANGLE)
        val occupiedCells = HashSet<Long>(maxTriangles)

        var curNx = 0f; var curNy = 0f; var curNz = 0f
        var vx0 = 0f; var vy0 = 0f; var vz0 = 0f
        var vx1 = 0f; var vy1 = 0f; var vz1 = 0f
        var vx2 = 0f; var vy2 = 0f; var vz2 = 0f
        var vertsInCurrentFacet = 0
        var vIdx = 0
        var keptTriangles = 0
        var approxBytesRead = 0L
        var lastReportedPercent = -1

        val reader = input.bufferedReader()
        reader.use { br ->
            var rawLine: String?
            while (br.readLine().also { rawLine = it } != null) {
                val raw = rawLine!!
                val line = raw.trim()
                approxBytesRead += raw.length + 1

                when {
                    line.startsWith("facet normal", ignoreCase = true) -> {
                        val n = parseFloatTriplet(line, 12)
                        if (n != null) { curNx = n.first; curNy = n.second; curNz = n.third }
                        else { curNx = 0f; curNy = 0f; curNz = 0f }
                        vertsInCurrentFacet = 0
                    }
                    line.startsWith("vertex", ignoreCase = true) -> {
                        val v = parseFloatTriplet(line, 6) ?: return@use
                        val (x, y, z) = v
                        when (vertsInCurrentFacet) {
                            0 -> { vx0 = x; vy0 = y; vz0 = z }
                            1 -> { vx1 = x; vy1 = y; vz1 = z }
                            2 -> { vx2 = x; vy2 = y; vz2 = z }
                        }
                        vertsInCurrentFacet++
                    }
                    line.startsWith("endfacet", ignoreCase = true) -> {
                        if (vertsInCurrentFacet == 3 && keptTriangles < maxTriangles) {
                            val ccx = (vx0 + vx1 + vx2) / 3f; val ccy = (vy0 + vy1 + vy2) / 3f; val ccz = (vz0 + vz1 + vz2) / 3f
                            val key = cellKey(ccx, ccy, ccz, minB, cellSize)
                            if (occupiedCells.add(key)) {
                                vertices[vIdx] = vx0; vertices[vIdx + 1] = vy0; vertices[vIdx + 2] = vz0
                                vertices[vIdx + 3] = vx1; vertices[vIdx + 4] = vy1; vertices[vIdx + 5] = vz1
                                vertices[vIdx + 6] = vx2; vertices[vIdx + 7] = vy2; vertices[vIdx + 8] = vz2
                                normals[vIdx] = curNx; normals[vIdx + 1] = curNy; normals[vIdx + 2] = curNz
                                normals[vIdx + 3] = curNx; normals[vIdx + 4] = curNy; normals[vIdx + 5] = curNz
                                normals[vIdx + 6] = curNx; normals[vIdx + 7] = curNy; normals[vIdx + 8] = curNz
                                vIdx += 9
                                keptTriangles++
                            }
                        }
                        vertsInCurrentFacet = 0
                    }
                }
                if (fileSize > 0) {
                    val percent = ((approxBytesRead * 100L) / fileSize).toInt().coerceIn(0, 100)
                    if (percent != lastReportedPercent) { lastReportedPercent = percent; onProgress(percent) }
                }
            }
        }

        return STLModel(
            vertices = if (keptTriangles == maxTriangles) vertices else vertices.copyOf(keptTriangles * FLOATS_PER_TRIANGLE),
            normals = if (keptTriangles == maxTriangles) normals else normals.copyOf(keptTriangles * FLOATS_PER_TRIANGLE),
            triangleCount = keptTriangles,
            minBounds = minB, maxBounds = maxB,
            estimatedOriginalTriangleCount = exactCount,
            isApproximate = true,
            isWatertightHint = (keptTriangles % 2 == 0)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Single-stream fallback (generic InputStream overload — see kdoc on parse() above)
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseBinarySingleStream(input: InputStream, fileSize: Long, onProgress: (Int) -> Unit): STLModel {
        if (fileSize < BINARY_HEADER_SIZE) throw STLParseException("Binary STL too small (< 84 bytes)")

        val header = ByteArray(BINARY_HEADER_SIZE)
        if (input.read(header) < BINARY_HEADER_SIZE) throw STLParseException("Incomplete binary STL header")

        val triangleCount = ByteBuffer.wrap(header, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val expectedSize = BINARY_HEADER_SIZE + (triangleCount.toLong() * BYTES_PER_TRIANGLE)
        if (expectedSize > fileSize) {
            throw STLParseException("Triangle count mismatch: declared $triangleCount, expected ${expectedSize}B but file is ${fileSize}B")
        }
        if (triangleCount <= 0) throw STLParseException("No valid triangles in binary STL")

        val maxTriangles = safeTriangleCap()
        val stride = if (triangleCount > maxTriangles) {
            kotlin.math.ceil(triangleCount.toDouble() / maxTriangles).toInt()
        } else 1
        val keptCapacity = (triangleCount + stride - 1) / stride

        val vertices = FloatArray(keptCapacity * FLOATS_PER_TRIANGLE)
        val normals = FloatArray(keptCapacity * FLOATS_PER_TRIANGLE)

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        val triangleBytes = ByteArray(BYTES_PER_TRIANGLE)
        val buffer = ByteBuffer.wrap(triangleBytes).order(ByteOrder.LITTLE_ENDIAN)

        var vIdx = 0
        var keptTriangles = 0
        val progressStep = kotlin.math.max(triangleCount / 100, 500)
        var lastReportedPercent = -1

        for (t in 0 until triangleCount) {
            val read = input.read(triangleBytes)
            if (read != BYTES_PER_TRIANGLE) {
                throw STLParseException("Corrupt triangle at index $t (read $read bytes, expected $BYTES_PER_TRIANGLE)")
            }
            buffer.clear()
            val nx = buffer.float; val ny = buffer.float; val nz = buffer.float
            val keepThis = (t % stride == 0) && keptTriangles < keptCapacity

            repeat(3) {
                val x = buffer.float; val y = buffer.float; val z = buffer.float
                if (keepThis) {
                    vertices[vIdx] = x; vertices[vIdx + 1] = y; vertices[vIdx + 2] = z
                    normals[vIdx] = nx; normals[vIdx + 1] = ny; normals[vIdx + 2] = nz
                    vIdx += 3
                }
                if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
            }
            buffer.short
            if (keepThis) keptTriangles++

            if (t % progressStep == 0 || t == triangleCount - 1) {
                val percent = (((t + 1).toLong() * 90L) / triangleCount).toInt()
                if (percent != lastReportedPercent) { lastReportedPercent = percent; onProgress(percent) }
            }
        }

        return STLModel(
            vertices = if (keptTriangles == keptCapacity) vertices else vertices.copyOf(keptTriangles * FLOATS_PER_TRIANGLE),
            normals = if (keptTriangles == keptCapacity) normals else normals.copyOf(keptTriangles * FLOATS_PER_TRIANGLE),
            triangleCount = keptTriangles,
            minBounds = floatArrayOf(minX, minY, minZ),
            maxBounds = floatArrayOf(maxX, maxY, maxZ),
            estimatedOriginalTriangleCount = triangleCount,
            isApproximate = stride > 1,
            isWatertightHint = (keptTriangles % 2 == 0)
        )
    }

    private fun parseAsciiSingleStream(input: InputStream, fileSize: Long, onProgress: (Int) -> Unit): STLModel {
        val maxTriangles = safeTriangleCap()
        val estimatedTriangleCount = kotlin.math.max(1L, fileSize / 220L)
        val stride = if (estimatedTriangleCount > maxTriangles) {
            kotlin.math.ceil(estimatedTriangleCount.toDouble() / maxTriangles).toInt()
        } else 1

        var capacity = kotlin.math.min(estimatedTriangleCount.toInt(), maxTriangles).coerceAtLeast(1) * FLOATS_PER_TRIANGLE
        var vertices = FloatArray(capacity)
        var normals = FloatArray(capacity)

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        var curNx = 0f; var curNy = 0f; var curNz = 0f
        var totalTriangleCount = 0
        var keptTriangleCount = 0
        var facetIndex = -1
        var storeCurrentFacet = true
        var vertsInCurrentFacet = 0
        var vIdx = 0
        var approxBytesRead = 0L
        var lastReportedPercent = -1

        val reader = input.bufferedReader()
        reader.use { br ->
            var rawLine: String?
            while (br.readLine().also { rawLine = it } != null) {
                val raw = rawLine!!
                val line = raw.trim()
                approxBytesRead += raw.length + 1

                when {
                    line.startsWith("facet normal", ignoreCase = true) -> {
                        val n = parseFloatTriplet(line, 12)
                        if (n != null) { curNx = n.first; curNy = n.second; curNz = n.third }
                        else { curNx = 0f; curNy = 0f; curNz = 0f }
                        vertsInCurrentFacet = 0
                        facetIndex++
                        storeCurrentFacet = (facetIndex % stride == 0) && keptTriangleCount < maxTriangles
                    }
                    line.startsWith("vertex", ignoreCase = true) -> {
                        val v = parseFloatTriplet(line, 6) ?: throw STLParseException("Invalid vertex value in line: $raw")
                        val (x, y, z) = v
                        if (storeCurrentFacet) {
                            if (vIdx + 3 > vertices.size) {
                                val newCap = (vertices.size * 2).coerceIn(FLOATS_PER_TRIANGLE, maxTriangles * FLOATS_PER_TRIANGLE)
                                if (newCap <= vertices.size) storeCurrentFacet = false
                                else { vertices = vertices.copyOf(newCap); normals = normals.copyOf(newCap) }
                            }
                            if (storeCurrentFacet) {
                                vertices[vIdx] = x; vertices[vIdx + 1] = y; vertices[vIdx + 2] = z
                                normals[vIdx] = curNx; normals[vIdx + 1] = curNy; normals[vIdx + 2] = curNz
                                vIdx += 3
                            }
                        }
                        if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                        if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
                        vertsInCurrentFacet++
                    }
                    line.startsWith("endfacet", ignoreCase = true) -> {
                        if (vertsInCurrentFacet == 3) {
                            totalTriangleCount++
                            if (storeCurrentFacet) keptTriangleCount++
                        }
                    }
                }
                if (fileSize > 0) {
                    val percent = ((approxBytesRead * 90L) / fileSize).toInt().coerceIn(0, 90)
                    if (percent != lastReportedPercent) { lastReportedPercent = percent; onProgress(percent) }
                }
            }
        }

        if (totalTriangleCount == 0) throw STLParseException("No valid triangles found in ASCII STL")

        val finalSize = keptTriangleCount * FLOATS_PER_TRIANGLE
        return STLModel(
            vertices = if (finalSize == vertices.size) vertices else vertices.copyOf(finalSize),
            normals = if (finalSize == normals.size) normals else normals.copyOf(finalSize),
            triangleCount = keptTriangleCount,
            minBounds = floatArrayOf(minX, minY, minZ),
            maxBounds = floatArrayOf(maxX, maxY, maxZ),
            estimatedOriginalTriangleCount = totalTriangleCount,
            isApproximate = stride > 1 || keptTriangleCount < totalTriangleCount,
            isWatertightHint = (keptTriangleCount % 2 == 0)
        )
    }
}
