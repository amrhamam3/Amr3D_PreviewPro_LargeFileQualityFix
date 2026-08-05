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
 * ⚠️ بيتعامل كمان مع ملفات PDF عادية (مش AI) — [isPdfFile]=true. الهدف هنا محدود
 * عمدًا: التطبيق موجّه لعملاء ورش القص (CNC/ليزر) لعرض وقياس ملفاتهم، مش لمصممين
 * محترفين محتاجين تعامل كامل مع كل مواصفة PDF. يعني:
 * - بس **الصفحة الأولى** — أي صفحة تانية في ملف PDF متعدد الصفحات بتتجاهل بصمت.
 * - بس الرسم الشعاعي (Vector) — أي صورة مضمّنة (Image/XObject) بتتجاهل.
 * - لو معرفناش نلاقي بنية "صفحة" واضحة (ملف PDF غريب الشكل)، بنرجع تلقائيًا
 *   لنفس أسلوب البحث الشامل المستخدم لملفات AI (أفضل من ما نرجّع خطأ فورًا).
 *
 * الاتنين (AI وPDF) بيستخدموا في النهاية نفس عائلة أوامر رسم PostScript/PDF
 * القياسية (moveto/lineto/curveto/closepath/fill) — فبعد استخراج نص الأوامر الخام
 * (مباشرة للـ AI الكلاسيكي، بعد فك ضغط وتجميع الـ Content Streams للباقي)، بنستخدم
 * نفس المُحلّل (Tokenizer) والمنطق لبناء المسارات للكل.
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

    private const val MAX_FILE_SIZE = 300_000_000L // ملفات AI/PDF عادةً صغيرة جدًا مقارنة بـ STL/OBJ

    /** [isPdfFile] بتتحكم في حاجتين مع بعض: (1) رسائل الخطأ بتتكلم عن "PDF" مش
     * "AI" لو الملف اللي المستخدم فتحه فعليًا .pdf، (2) بيستخدم استخراج "الصفحة
     * الأولى بس" بدل المسح الشامل لكل الملف (المناسب أكتر لملفات AI اللي غالبًا
     * لوحة رسم واحدة أصلًا). */
    fun parse(context: Context, uri: Uri, onProgress: (Int) -> Unit = {}, isPdfFile: Boolean = false): DxfModel {
        val resolver = context.contentResolver
        val fileSize: Long = resolver.query(
            uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else -1L
            } else -1L
        } ?: -1L
        if (fileSize > MAX_FILE_SIZE) {
            throw AIParseException(context.getString(if (isPdfFile) R.string.error_pdf_too_large else R.string.error_ai_too_large))
        }

        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw AIParseException(context.getString(if (isPdfFile) R.string.error_pdf_read_failed else R.string.error_ai_read_failed))
        if (bytes.isEmpty()) {
            throw AIParseException(context.getString(if (isPdfFile) R.string.error_pdf_read_failed else R.string.error_ai_read_failed))
        }
        onProgress(20) // قراءة البايتات الخام خلصت — دايمًا سريعة نسبيًا (I/O بس، من غير تحليل)

        val isPdfFlavor = bytes.size >= 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-"
        val contentText = if (isPdfFlavor) {
            if (isPdfFile) {
                extractFirstPageContentStreams(bytes) { p -> onProgress(20 + (p * 30) / 100) } // 20-50%
            } else {
                extractPdfContentStreams(bytes) { p -> onProgress(20 + (p * 30) / 100) } // 20-50%
            }
        } else {
            String(bytes, Charsets.ISO_8859_1)
        }
        onProgress(50)
        if (contentText.isBlank()) {
            throw AIParseException(context.getString(if (isPdfFile) R.string.error_pdf_no_geometry else R.string.error_ai_no_geometry))
        }

        val model = parseContentStream(contentText) { p -> onProgress(50 + (p * 40) / 100) } // 50-90%
        if (model.lines.isEmpty()) {
            throw AIParseException(context.getString(if (isPdfFile) R.string.error_pdf_no_geometry else R.string.error_ai_no_geometry))
        }
        onProgress(90)
        return model
    }

    /** المحلّل المشترك: نفس منطق بناء المسارات بغض النظر عن مصدر النص (PostScript
     * خام أو Content Stream مستخرج من PDF) — الاتنين بيستخدموا نفس أوامر الرسم.
     *
     * ⚠️ تحسين أداء (اقتراح Amr، بناءً على خبرته في 3ds Max): عدد قطع تفليح
     * منحنيات Bézier (Flattening) مش رقم ثابت (16) بغض النظر عن الملف — بقى
     * تكيّفي حسب عدد المنحنيات الفعلي في الملف (شوف [estimateBezierSegments]).
     * الملفات اللي فيها منحنيات قليلة بتاخد أعلى جودة (16 قطعة/منحنى)، والملفات
     * اللي فيها آلاف المنحنيات بتاخد جودة أقل (نزولًا لـ 4) — التطبيق ده للعرض
     * بس حاليًا مش للتصنيع الدقيق، فالفرق البصري ضئيل جدًا مقابل تقليل حقيقي في
     * عدد الخطوط الناتجة (وبالتالي وقت التحميل والرسم). */
    /** ⚠️ إصلاح (بلاغ Amr: "التكسير كبير جدًا" بعد ما بقى الملف يفتح): كان تقدير
     * عدد قطع تفتيت كل منحنى (Bézier) معتمد على **طول النص الكلي** — لكن بعض
     * الملفات (زي EX3.ai) فيها معاينة/تعليقات ضخمة جدًا (لقينا حالة كانت 92% من
     * الـ Stream معاينة، 8% بس رسم فعلي) بتضخّم الطول من غير أي علاقة بعدد
     * المنحنيات الحقيقي، فالملف كان بياخد أقل جودة ممكنة (4 قطع) رغم إن فيه
     * منحنيات قليلة جدًا فعليًا (٢٧٧٣ في ملف الاختبار، رقم بسيط جدًا).
     *
     * دلوقتي بنعدّ أوامر المنحنى الفعلية (c/C/v/V/y/Y كـ tokens مستقلة، محاطة
     * بمسافات) في مرور سريع واحد على النص، **متجاهلين التعليقات** (زي ما
     * الـ Tokenizer الرئيسي بيعمل بالظبط) — عشان المعاينات المدمجة متأثرش على
     * التقدير خالص. تكلفة المرور الإضافي ده ضئيلة جدًا (مرور واحد بسيط، بدون أي
     * تخصيص ذاكرة كبير) مقارنة بالفرق الحقيقي في الجودة. */
    private fun estimateBezierSegments(contentText: String): Int {
        var curveCount = 0
        var i = 0
        val n = contentText.length
        while (i < n) {
            val c = contentText[i]
            if (c == '%') { while (i < n && contentText[i] != '\n') i++; continue }
            if (c == 'c' || c == 'C' || c == 'v' || c == 'V' || c == 'y' || c == 'Y') {
                val prevOk = i == 0 || contentText[i - 1].isWhitespace()
                val nextOk = i + 1 >= n || contentText[i + 1].isWhitespace()
                if (prevOk && nextOk) curveCount++
            }
            i++
        }
        return when {
            curveCount < 5_000 -> 16
            curveCount < 20_000 -> 8
            else -> 4
        }
    }

    private fun parseContentStream(contentText: String, onProgress: (Int) -> Unit = {}): DxfModel {
        val lines = ArrayList<DxfLine>()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE

        val bezierSegments = estimateBezierSegments(contentText)

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

    /** بيدوّر على كل الـ Streams في ملف الـ PDF، يفك تشفيرها (لو محتاجة)، وبيفلتر
     * بس اللي شكلها فعليًا أوامر رسم (مش صور/خطوط مضمّنة) — طريقة عملية بدل تحليل
     * بنية PDF الكاملة. كافي لملفات Illustrator عادية بلوحة رسم واحدة. مستخدمة
     * لملفات .ai — لملفات .pdf شوف [extractFirstPageContentStreams] (بتاخد صفحة
     * واحدة بس، وده الفرق الجوهري بين الاتنين). */
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
            val filters = extractFilterNames(dictText)

            var dataStart = streamIdx + 6
            if (dataStart < latin.length && latin[dataStart] == '\r') dataStart++
            if (dataStart < latin.length && latin[dataStart] == '\n') dataStart++

            val endIdx = latin.indexOf("endstream", dataStart)
            if (endIdx < 0) break
            val rawStreamBytes = bytes.copyOfRange(dataStart, endIdx.coerceAtMost(bytes.size))

            val decoded: ByteArray? = if (filters.isEmpty()) rawStreamBytes else applyFilters(rawStreamBytes, filters)

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

    /** نسخة "الصفحة الأولى بس" — مخصوصة لملفات .pdf عادية (زي ما طلب Amr: العميل
     * محتاج يشوف ويقيس بس، مش تعامل احترافي مع كل صفحات ملف PDF). بتدوّر على أول
     * كائن `/Type /Page` حقيقي (مش `/Type /Pages` — ده كائن شجرة الصفحات نفسه مش
     * صفحة)، وتاخد الـ Content Stream(s) المرتبطة بيه بس عن طريق تتبّع مرجع
     * `/Contents` (سواء كان مرجع واحد أو Array مراجع لأكتر من Stream للصفحة نفسها).
     *
     * لو الملف معقّد بشكل غير متوقع (بنية PDF غريبة، xref stream، إلخ) ومعرفناش
     * نلاقي صفحة بالطريقة الدقيقة دي، بنرجع تلقائيًا لنفس أسلوب المسح الشامل
     * المستخدم لملفات AI — أفضل بكتير من رسالة خطأ فورية لملف المستخدم غالبًا
     * محتاج يفتحه، حتى لو النتيجة مش مضمونة تكون صفحة واحدة بالظبط في الحالة دي. */
    private fun extractFirstPageContentStreams(bytes: ByteArray, onProgress: (Int) -> Unit = {}): String {
        val latin = String(bytes, Charsets.ISO_8859_1)

        val pageMatch = Regex("/Type\\s*/Page(?!s)\\b").find(latin)
            ?: return extractPdfContentStreams(bytes, onProgress) // مفيش /Type /Page واضح — رجوع للطريقة الشاملة

        val dictStart = latin.lastIndexOf("obj", pageMatch.range.first).let { if (it < 0) 0 else it }
        val dictEnd = latin.indexOf("endobj", pageMatch.range.first).let { if (it < 0) latin.length else it }
        if (dictEnd <= dictStart) return extractPdfContentStreams(bytes, onProgress)
        val pageDictText = latin.substring(dictStart, dictEnd)

        // /Contents ممكن يكون مرجع واحد "5 0 R" أو Array مراجع "[5 0 R 6 0 R]"
        val contentsMatch = Regex("/Contents\\s*(\\[([^\\]]*)\\]|(\\d+)\\s+\\d+\\s+R)").find(pageDictText)
            ?: return extractPdfContentStreams(bytes, onProgress)
        val arrPart = contentsMatch.groupValues[2]
        val singlePart = contentsMatch.groupValues[3]
        val objectNumbers = mutableListOf<Int>()
        if (arrPart.isNotBlank()) {
            Regex("(\\d+)\\s+\\d+\\s+R").findAll(arrPart).forEach { objectNumbers.add(it.groupValues[1].toInt()) }
        } else if (singlePart.isNotBlank()) {
            objectNumbers.add(singlePart.toInt())
        }
        if (objectNumbers.isEmpty()) return extractPdfContentStreams(bytes, onProgress)

        val text = StringBuilder()
        for ((idx, objNum) in objectNumbers.withIndex()) {
            onProgress(((idx + 1) * 100) / objectNumbers.size)
            val objMatch = Regex("(?m)^\\s*$objNum\\s+\\d+\\s+obj\\b").find(latin) ?: continue
            val streamIdx = latin.indexOf("stream", objMatch.range.last)
            if (streamIdx < 0) continue
            val objDictText = latin.substring(objMatch.range.last, streamIdx) // ديكشنري الكائن ده بس، مش أي 400 حرف عشوائي
            val filters = extractFilterNames(objDictText)

            var dataStart = streamIdx + 6
            if (dataStart < latin.length && latin[dataStart] == '\r') dataStart++
            if (dataStart < latin.length && latin[dataStart] == '\n') dataStart++
            val endIdx = latin.indexOf("endstream", dataStart)
            if (endIdx < 0) continue
            val rawStreamBytes = bytes.copyOfRange(dataStart, endIdx.coerceAtMost(bytes.size))

            val decoded = if (filters.isEmpty()) rawStreamBytes else applyFilters(rawStreamBytes, filters)
            if (decoded != null) text.append(String(decoded, Charsets.ISO_8859_1)).append('\n')
        }
        // لو مفيش أي Stream اتقرا فعليًا (مثلاً كل الـ objects كانت مش لاقيينها)، رجوع للطريقة الشاملة كملاذ أخير
        return if (text.isBlank()) extractPdfContentStreams(bytes, onProgress) else text.toString()
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

    /** ⚠️ إصلاح (بلاغ Amr، ملف EX3.ai): بعض الأدوات (لقينا الحالة دي مع CorelDRAW
     * وهو بيصدّر بصيغة AI-compatible) بتشفّر الـ Content Stream الحقيقي بـ **خطوتين**
     * مش واحدة — مثلًا `/Filter [/ASCIIHexDecode /FlateDecode]`: الأول لازم تفك
     * ترميز الـ Hex، وبعدين تفك ضغط Flate على الناتج. الكود القديم كان بيفحص وجود
     * "FlateDecode" كنص وبس، ويحاول يفك ضغط Flate مباشرة على البايتات الخام — وده
     * كان بيفشل فورًا لأي Stream فيه خطوة تانية قبل الـ Flate (البايتات الخام في
     * الحالة دي نص Hex مش بيانات مضغوطة أصلًا)، فالـ Stream بالكامل كان بيتجاهل
     * بصمت. دلوقتي بنستخرج **كل** خطوات الـ Filter بترتيبها ونطبّقهم واحدة ورا
     * التانية بالظبط زي ما الملف بيحددهم. */
    private fun extractFilterNames(dictText: String): List<String> {
        val m = Regex("/Filter\\s*(\\[([^\\]]*)\\]|/(\\w+))").find(dictText) ?: return emptyList()
        val arrPart = m.groupValues[2]
        val singlePart = m.groupValues[3]
        return when {
            arrPart.isNotBlank() -> Regex("/(\\w+)").findAll(arrPart).map { it.groupValues[1] }.toList()
            singlePart.isNotBlank() -> listOf(singlePart)
            else -> emptyList()
        }
    }

    /** بتطبّق كل خطوات فك التشفير بترتيبها (زي ما ظاهرين في /Filter). فيلتر مش
     * معروف (نادر جدًا لملفات القص/الرسم) بنسيبه زي ما هو ونكمل الباقي بدل ما
     * نفشل الـ Stream بالكامل — أفضل نتيجة جزئية من ولا حاجة خالص. */
    private fun applyFilters(raw: ByteArray, filters: List<String>): ByteArray? {
        var data: ByteArray? = raw
        for (name in filters) {
            val current = data ?: return null
            data = when (name) {
                "FlateDecode", "Fl" -> inflate(current)
                "ASCIIHexDecode", "AHx" -> decodeAsciiHex(current)
                "ASCII85Decode", "A85" -> decodeAscii85(current)
                else -> current
            }
        }
        return data
    }

    /** فك ترميز ASCIIHexDecode القياسي في PDF: كل بايتين حرف Hex = بايت واحد،
     * المسافات/أسطر جديدة تتجاهل، والترميز بينتهي عند '>' (أو نهاية البيانات). */
    private fun decodeAsciiHex(data: ByteArray): ByteArray? {
        return try {
            val sb = StringBuilder(data.size)
            for (b in data) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c == '>') break
                if (!c.isWhitespace()) sb.append(c)
            }
            val hex = sb.toString()
            val out = java.io.ByteArrayOutputStream(hex.length / 2 + 1)
            var i = 0
            while (i < hex.length) {
                val hi = Character.digit(hex[i], 16)
                if (hi < 0) { i++; continue } // حرف مش Hex صالح — تجاهل دفاعي، نادر الحدوث
                val lo = if (i + 1 < hex.length) Character.digit(hex[i + 1], 16) else 0
                out.write((hi shl 4) or (if (lo < 0) 0 else lo))
                i += 2
            }
            out.toByteArray()
        } catch (_: Exception) { null }
    }

    /** فك ترميز ASCII85Decode القياسي في PDF/PostScript: مجموعات من 5 حروف
     * (المدى '!' لـ 'u') بتتحول لـ 4 بايت، والحرف 'z' اختصار لـ 4 بايت أصفار،
     * والترميز بينتهي عند "~". */
    private fun decodeAscii85(data: ByteArray): ByteArray? {
        return try {
            val out = java.io.ByteArrayOutputStream(data.size)
            val tuple = IntArray(5)
            var count = 0
            var idx = 0
            while (idx < data.size) {
                val c = (data[idx].toInt() and 0xFF).toChar()
                idx++
                if (c == '~') break
                if (c.isWhitespace()) continue
                if (c == 'z' && count == 0) {
                    out.write(0); out.write(0); out.write(0); out.write(0)
                    continue
                }
                if (c < '!' || c > 'u') continue // حرف مش صالح — تجاهل دفاعي
                tuple[count] = c.code - '!'.code
                count++
                if (count == 5) {
                    var value = 0L
                    for (t in tuple) value = value * 85 + t
                    out.write(((value shr 24) and 0xFF).toInt())
                    out.write(((value shr 16) and 0xFF).toInt())
                    out.write(((value shr 8) and 0xFF).toInt())
                    out.write((value and 0xFF).toInt())
                    count = 0
                }
            }
            if (count > 0) {
                // مجموعة أخيرة ناقصة — Padding بـ 'u' (القيمة 84) زي ما بيحدد المواصفة،
                // ونكتب بس عدد البايتات الناقص (count-1) مش الـ 4 كاملين
                for (k in count until 5) tuple[k] = 84
                var value = 0L
                for (t in tuple) value = value * 85 + t
                val allBytes = byteArrayOf(
                    ((value shr 24) and 0xFF).toByte(), ((value shr 16) and 0xFF).toByte(),
                    ((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte()
                )
                out.write(allBytes, 0, count - 1)
            }
            out.toByteArray()
        } catch (_: Exception) { null }
    }

    /** فحص سريع: الـ Content Stream الحقيقي لازم يحتوي على واحد من أوامر الرسم
     * الأساسية كأوبريتور مستقل (محاط بمسافات)، عكس الصور/الخطوط المضمّنة اللي
     * مش هتحتوي على النمط ده أبدًا.
     *
     * ⚠️ إصلاح (بلاغ Amr، ملف EX3.ai): كان الفحص بيقتصر على أول 20 ألف حرف بس
     * (تحسين أداء ظاهريًا). لكن بعض الملفات (لقيناها مع تصدير CorelDRAW) بتحط
     * صورة معاينة كاملة مشفّرة Hex جوه تعليقات PostScript **قبل** أوامر الرسم
     * الفعلية — في الملف اللي بلّغ عنه Amr، أوامر الرسم الحقيقية بتبدأ بعد أول
     * 2.35 مليون حرف (92% من الـ Stream معاينة، 8% بس رسم حقيقي في الآخر)! يعني
     * أي عيّنة من البداية بس، مهما كان حجمها المعقول، كانت هترفض الـ Stream ده
     * غلط رغم إنه فيه رسم حقيقي فعلًا. الفحص بقى على النص كامل — التكلفة الإضافية
     * (Regex واحد على نص كام ميجا، مرة واحدة لكل Stream، عددهم قليل جدًا في أي
     * ملف) ضئيلة جدًا مقارنة بمخاطرة رفض رسمة حقيقية غلط. */
    private fun looksLikeContentStream(s: String): Boolean {
        return Regex("(^|\\s)(m|l|c|re|f|S)(\\s|$)").containsMatchIn(s)
    }
}
