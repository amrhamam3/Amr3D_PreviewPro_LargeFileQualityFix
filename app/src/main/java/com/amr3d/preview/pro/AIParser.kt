package com.amr3d.preview.pro

import android.content.Context
import android.net.Uri
import java.util.zip.Inflater

class AIParseException(message: String) : Exception(message)

/**
 * قارئ ملفات Adobe Illustrator (.ai) — يدعم النوعين الموجودين فعليًا في السوق:
 * 1) AI "كلاسيكي" (PostScript خام، بيبدأ بـ %!PS-Adobe) — قديم نسبيًا، لسه بيطلع
 *    من بعض الأدوات (زي GNU libplot أو تصدير قديم).
 * 2) AI "حديث" (Illustrator 9+ بالإعدادات الافتراضية) — ملف PDF حقيقي بالكامل
 *    (بيبدأ بـ %PDF-)، والمحتوى غالبًا مضغوط (FlateDecode/zlib).
 *
 * الاتنين بيستخدموا في النهاية نفس عائلة أوامر رسم PostScript/PDF القياسية
 * (moveto/lineto/curveto/closepath/fill) — فبعد استخراج نص الأوامر الخام (مباشرة
 * للنوع الأول، بعد فك ضغط وتجميع الـ Content Streams للنوع التاني)، بنستخدم نفس
 * المُحلّل (Tokenizer) والمنطق لبناء المسارات للاتنين.
 *
 * ⚠️ قرارات نطاق متعمّدة (زي OBJ/GLB بالظبط):
 * - بنتجاهل النصوص (Text/Fonts) تمامًا — مالهاش معنى لملف قصّ ليزر.
 * - بنحوّل أي منحنى Bézier لخطوط مستقيمة قصيرة (Flattening، 16 قطعة) — نفس فكرة
 *   تحويل أقواس DXF لقطع، مفيش داعي لتمثيل منحنى حقيقي في عارض ثنائي الأبعاد.
 * - استخراج الـ Content Stream من ملفات PDF بيعتمد على طريقة عملية (نلاقي كل
 *   الـ streams في الملف، نفك ضغطها، ونفلتر بس اللي شكلها فعليًا أوامر رسم) —
 *   مش تحليل كامل لبنية PDF (جدول xref/شجرة الصفحات...). كافي لملفات Illustrator
 *   العادية (لوحة رسم واحدة)، مش لأي PDF عام معقّد بصفحات متعددة/نماذج/طبقات OCG متداخلة.
 * - أي عنصر بيتحوّل لـ [DxfLine] بس (نفس شكل بيانات DXF بالظبط) — فبيتعرض على
 *   نفس عارض DXF2DView الموجود من غير أي تعديل فيه خالص.
 */
object AIParser {

    private const val MAX_FILE_SIZE = 300_000_000L // ملفات AI عادةً صغيرة جدًا مقارنة بـ STL/OBJ

    fun parse(context: Context, uri: Uri, onProgress: (Int) -> Unit = {}): DxfModel {
        val resolver = context.contentResolver
        val fileSize: Long = resolver.query(
            uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else -1L
            } else -1L
        } ?: -1L
        if (fileSize > MAX_FILE_SIZE) throw AIParseException(context.getString(R.string.error_ai_too_large))

        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw AIParseException(context.getString(R.string.error_ai_read_failed))
        if (bytes.isEmpty()) throw AIParseException(context.getString(R.string.error_ai_read_failed))
        onProgress(20) // قراءة البايتات الخام خلصت — دايمًا سريعة نسبيًا (I/O بس، من غير تحليل)

        val isPdfFlavor = bytes.size >= 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-"
        val contentText = if (isPdfFlavor) {
            extractPdfContentStreams(bytes) { p -> onProgress(20 + (p * 30) / 100) } // 20-50%
        } else {
            String(bytes, Charsets.ISO_8859_1)
        }
        onProgress(50)
        if (contentText.isBlank()) throw AIParseException(context.getString(R.string.error_ai_no_geometry))

        val model = parseContentStream(contentText) { p -> onProgress(50 + (p * 40) / 100) } // 50-90%
        if (model.lines.isEmpty()) throw AIParseException(context.getString(R.string.error_ai_no_geometry))
        onProgress(90)
        return model
    }

    /** المحلّل المشترك: نفس منطق بناء المسارات بغض النظر عن مصدر النص (PostScript
     * خام أو Content Stream مستخرج من PDF) — الاتنين بيستخدموا نفس أوامر الرسم.
     *
     * ⚠️ تحسين أداء (اقتراح Amr، بناءً على خبرته في 3ds Max): عدد قطع تفليح
     * منحنيات Bézier (Flattening) مش رقم ثابت (16) بغض النظر عن حجم الملف —
     * بقى تكيّفي حسب حجم النص. الملفات الصغيرة بتاخد أعلى جودة (16 قطعة/منحنى)،
     * والملفات الكبيرة (فيها آلاف المنحنيات غالبًا) بتاخد جودة أقل (نزولًا لـ 4)
     * — التطبيق ده للعرض بس حاليًا مش للتصنيع الدقيق، فالفرق البصري ضئيل جدًا
     * مقابل تقليل حقيقي في عدد الخطوط الناتجة (وبالتالي وقت التحميل والرسم). */
    private fun parseContentStream(contentText: String, onProgress: (Int) -> Unit = {}): DxfModel {
        val lines = ArrayList<DxfLine>()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE

        val bezierSegments = when {
            contentText.length < 500_000 -> 16
            contentText.length < 2_000_000 -> 8
            else -> 4
        }

        fun noteBounds(x: Float, y: Float) {
            if (x < minX) minX = x; if (y < minY) minY = y
            if (x > maxX) maxX = x; if (y > maxY) maxY = y
        }

        var curX = 0f; var curY = 0f
        var startX = 0f; var startY = 0f // بداية الـ Subpath الحالي (لإغلاقه لو Closepath)
        var currentColor = 0xFFFFFFFF.toInt()
        val operandStack = ArrayList<Double>(8)

        fun popN(count: Int): DoubleArray {
            val result = DoubleArray(count)
            for (idx in count - 1 downTo 0) {
                result[idx] = if (operandStack.isNotEmpty()) operandStack.removeAt(operandStack.size - 1) else 0.0
            }
            return result
        }

        fun addLine(x1: Float, y1: Float, x2: Float, y2: Float) {
            if (x1 == x2 && y1 == y2) return
            lines.add(DxfLine(x1, y1, x2, y2, currentColor, "AI"))
            noteBounds(x1, y1); noteBounds(x2, y2)
        }

        fun flattenBezier(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            val segments = bezierSegments
            var px = x0; var py = y0
            for (s in 1..segments) {
                val t = s.toFloat() / segments
                val mt = 1f - t
                val x = mt*mt*mt*x0 + 3*mt*mt*t*x1 + 3*mt*t*t*x2 + t*t*t*x3
                val y = mt*mt*mt*y0 + 3*mt*mt*t*y1 + 3*mt*t*t*y2 + t*t*t*y3
                addLine(px, py, x, y)
                px = x; py = y
            }
        }

        fun processOperator(op: String) {
            when (op) {
                "m" -> {
                    val a = popN(2); curX = a[0].toFloat(); curY = a[1].toFloat()
                    startX = curX; startY = curY
                }
                "l", "L" -> {
                    val a = popN(2)
                    val nx = a[0].toFloat(); val ny = a[1].toFloat()
                    addLine(curX, curY, nx, ny)
                    curX = nx; curY = ny
                }
                "c", "C" -> {
                    val a = popN(6)
                    val x1 = a[0].toFloat(); val y1 = a[1].toFloat()
                    val x2 = a[2].toFloat(); val y2 = a[3].toFloat()
                    val x3 = a[4].toFloat(); val y3 = a[5].toFloat()
                    flattenBezier(curX, curY, x1, y1, x2, y2, x3, y3)
                    curX = x3; curY = y3
                }
                "v", "V" -> { // نقطة تحكم أولى = نقطة البداية نفسها
                    val a = popN(4)
                    val x2 = a[0].toFloat(); val y2 = a[1].toFloat()
                    val x3 = a[2].toFloat(); val y3 = a[3].toFloat()
                    flattenBezier(curX, curY, curX, curY, x2, y2, x3, y3)
                    curX = x3; curY = y3
                }
                "y", "Y" -> { // نقطة تحكم تانية = نقطة النهاية نفسها
                    val a = popN(4)
                    val x1 = a[0].toFloat(); val y1 = a[1].toFloat()
                    val x3 = a[2].toFloat(); val y3 = a[3].toFloat()
                    flattenBezier(curX, curY, x1, y1, x3, y3, x3, y3)
                    curX = x3; curY = y3
                }
                "h", "H" -> {
                    addLine(curX, curY, startX, startY)
                    curX = startX; curY = startY
                }
                "re" -> { // مستطيل PDF: x y w h re
                    val a = popN(4)
                    val x = a[0].toFloat(); val y = a[1].toFloat()
                    val w = a[2].toFloat(); val h = a[3].toFloat()
                    addLine(x, y, x + w, y); addLine(x + w, y, x + w, y + h)
                    addLine(x + w, y + h, x, y + h); addLine(x, y + h, x, y)
                    curX = x; curY = y; startX = x; startY = y
                }
                "rg", "RG" -> {
                    val a = popN(3)
                    currentColor = packRgb(a[0], a[1], a[2])
                }
                "g", "G" -> {
                    val a = popN(1)
                    currentColor = packRgb(a[0], a[0], a[0])
                }
                "k", "K" -> {
                    val a = popN(4)
                    val r = (1 - a[0]) * (1 - a[3]); val gc = (1 - a[1]) * (1 - a[3]); val b = (1 - a[2]) * (1 - a[3])
                    currentColor = packRgb(r, gc, b)
                }
                else -> { /* أي أوبريتور تاني (تلوين/تخطيط مسار، سمك خط، Xa، Lb، إلخ) — نتجاهله وننضّف العمليات */ }
            }
            operandStack.clear()
        }

        var i = 0
        val n = contentText.length.coerceAtLeast(1)
        val tokenBuilder = StringBuilder()
        val progressStep = kotlin.math.max(n / 100, 2000)
        var lastReportedPercent = -1
        while (i < n) {
            if (i % progressStep == 0) {
                val percent = ((i.toLong() * 100L) / n).toInt().coerceIn(0, 100)
                if (percent != lastReportedPercent) { lastReportedPercent = percent; onProgress(percent) }
            }
            val ch = contentText[i]
            when {
                ch == '%' -> { while (i < n && contentText[i] != '\n') i++ }
                ch == '(' -> { // نص حرفي — تجاهل لحد القوس المقفول (بمراعاة \) مهرّبة)
                    i++
                    var depth = 1
                    while (i < n && depth > 0) {
                        if (contentText[i] == '\\' && i + 1 < n) { i += 2; continue }
                        if (contentText[i] == '(') depth++
                        if (contentText[i] == ')') depth--
                        i++
                    }
                }
                ch == '<' -> { i++; while (i < n && contentText[i] != '>') i++; i++ }
                ch == '[' || ch == ']' -> { i++ }
                ch.isWhitespace() -> { i++ }
                else -> {
                    tokenBuilder.setLength(0)
                    while (i < n && !contentText[i].isWhitespace() && contentText[i] !in "()<>[]%") {
                        tokenBuilder.append(contentText[i]); i++
                    }
                    val token = tokenBuilder.toString()
                    val num = token.toDoubleOrNull()
                    if (num != null) operandStack.add(num) else processOperator(token)
                }
            }
        }

        return DxfModel(
            lines = lines, arcs = emptyList(), circles = emptyList(),
            minX = if (lines.isEmpty()) 0f else minX, minY = if (lines.isEmpty()) 0f else minY,
            maxX = if (lines.isEmpty()) 0f else maxX, maxY = if (lines.isEmpty()) 0f else maxY,
            entityCount = lines.size, layers = listOf("AI"), colorGroupPalette = emptyList()
        )
    }

    private fun packRgb(r: Double, g: Double, b: Double): Int {
        val ri = (r.coerceIn(0.0, 1.0) * 255).toInt()
        val gi = (g.coerceIn(0.0, 1.0) * 255).toInt()
        val bi = (b.coerceIn(0.0, 1.0) * 255).toInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    /** بيدوّر على كل الـ Streams في ملف الـ PDF، يفك ضغطها (لو FlateDecode)، وبيفلتر
     * بس اللي شكلها فعليًا أوامر رسم (مش صور/خطوط مضمّنة) — طريقة عملية بدل تحليل
     * بنية PDF الكاملة. كافي لملفات AI عادية بلوحة رسم واحدة. */
    private fun extractPdfContentStreams(bytes: ByteArray, onProgress: (Int) -> Unit = {}): String {
        val text = StringBuilder()
        val latin = String(bytes, Charsets.ISO_8859_1) // تحويل حرف-لبايت 1:1 بلا فقدان، مش ترميز نصي حقيقي
        val totalLen = latin.length.coerceAtLeast(1)
        var searchFrom = 0
        var lastReportedPercent = -1
        while (true) {
            val percent = ((searchFrom.toLong() * 100L) / totalLen).toInt().coerceIn(0, 100)
            if (percent != lastReportedPercent) { lastReportedPercent = percent; onProgress(percent) }
            val streamIdx = latin.indexOf("stream", searchFrom)
            if (streamIdx < 0) break
            val dictStart = (streamIdx - 400).coerceAtLeast(0)
            val dictText = latin.substring(dictStart, streamIdx)
            val isFlate = dictText.contains("FlateDecode")

            var dataStart = streamIdx + 6
            if (dataStart < latin.length && latin[dataStart] == '\r') dataStart++
            if (dataStart < latin.length && latin[dataStart] == '\n') dataStart++

            val endIdx = latin.indexOf("endstream", dataStart)
            if (endIdx < 0) break
            val rawStreamBytes = bytes.copyOfRange(dataStart, endIdx.coerceAtMost(bytes.size))

            val decoded: ByteArray? = if (isFlate) inflate(rawStreamBytes) else rawStreamBytes

            if (decoded != null) {
                val decodedText = String(decoded, Charsets.ISO_8859_1)
                if (looksLikeContentStream(decodedText)) {
                    text.append(decodedText).append('\n')
                }
            }
            searchFrom = endIdx + 9
        }
        return text.toString()
    }

    private fun inflate(data: ByteArray): ByteArray? {
        return try {
            val inflater = Inflater()
            inflater.setInput(data)
            val out = java.io.ByteArrayOutputStream(data.size * 3)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, count)
            }
            inflater.end()
            out.toByteArray()
        } catch (_: Exception) { null }
    }

    /** فحص سريع: الـ Content Stream الحقيقي لازم يحتوي على واحد من أوامر الرسم
     * الأساسية كأوبريتور مستقل (محاط بمسافات)، عكس الصور/الخطوط المضمّنة اللي
     * مش هتحتوي على النمط ده أبدًا. */
    private fun looksLikeContentStream(s: String): Boolean {
        val sample = if (s.length > 20000) s.substring(0, 20000) else s
        return Regex("(^|\\s)(m|l|c|re|f|S)(\\s|$)").containsMatchIn(sample)
    }
}
