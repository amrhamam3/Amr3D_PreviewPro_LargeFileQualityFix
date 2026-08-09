package com.amr3d.preview.pro

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CopyOnWriteArrayList
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class STLRenderer : GLSurfaceView.Renderer {

    // --- Shaders مع دعم اتجاه الإضاءة ---
    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uNormalMatrix;
        uniform mat4 uModelMatrix;
        attribute vec4 vPosition;
        attribute vec3 vNormal;
        varying vec3 fNormal;
        varying highp vec3 fPosition;
        varying highp vec3 fWorldPos;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            fNormal    = normalize((uNormalMatrix * vec4(vNormal, 0.0)).xyz);
            fPosition  = vPosition.xyz;
            fWorldPos  = (uModelMatrix * vPosition).xyz;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec3 fNormal;
        varying highp vec3 fPosition;
        varying highp vec3 fWorldPos;
        uniform vec4 uColor;
        uniform vec3 uLightDir;
        uniform int  uMaterial;
        uniform float uLightAngleDeg;
        // بيتحكم في شفافية الرسمة كلها — 1.0 عادي، وبتتقلل لحد \~0.15 وقت رسم
        // انعكاس الموديل (reflection) تحته، بدل ما نكرر كود الشيدر من الأول
        uniform float uOpacityMultiplier;
        // بيتحسب من نصف قطر الموديل الفعلي (1/modelRadius) بدل رقم ثابت (كان 0.015)
        // عشان حبيبات الخشب وعروق الرخام تبان بنفس النسبة والوضوح بغض النظر عن حجم
        // الموديل الحقيقي (مم صغيرة أو أمتار) — قبل كده كانت بتتلخبط (تتكدّس أو تختفي)
        // لأي موديل مش قريب من الحجم اللي الرقم الثابت كان متظبّط عليه.
        uniform float uPatternScale;

        // ═══ Hash & Noise (لسه محتاجينها لشبكة الأرضية وتأثيرات تانية) ═══
        float hash(highp vec3 p) {
            p = fract(p * vec3(443.897, 397.297, 491.187));
            p += dot(p.zxy, p.yxz + 19.19);
            return fract(p.x * p.y * p.z);
        }

        void main() {
            vec3 N = normalize(fNormal);
            vec3 L = normalize(uLightDir);
            vec3 V = normalize(vec3(0.0, 0.0, 1.0) - fPosition * 0.008);

            // ═══ إضاءة "شبه واقعية" (البند 2.1) — قاعدة مطفية زي Clay Shading
            // القديمة (إضاءة ثلاثية: رئيسية + تعبئة + حافة) بس بإضافة لمعة ناعمة
            // (Soft Specular، Blinn-Phong بمدى واسع مش حاد) فوقها — مش PBR كامل،
            // بس بتقرّب الإحساس من مرجع "غلاية بإضاءة ناعمة واقعية": توازن نور/ظل
            // أوضح وحواف بتلمع بهدوء بدل ما تبقى مطفية 100%. ═══
            vec3 col = uColor.rgb;

            float NdotL  = max(dot(N, L), 0.0);
            // ضوء تعبئة من الاتجاه المعاكس تقريبًا — بيوضّح التفاصيل في المناطق
            // البعيدة عن الضوء الرئيسي من غير ما تبقى سودة تمامًا
            float NdotL2 = max(dot(N, normalize(vec3(-0.35, -0.55, 0.4))), 0.0);
            // ضوء حافة خفيف من فوق — بيدي تمايز بسيط للحواف العلوية
            float NdotL3 = max(dot(N, normalize(vec3(0.0, 1.0, 0.15))), 0.0);

            // إحساس بسيط بالـ Ambient Occlusion: المناطق اللي مش واخدة ضوء كفاية من
            // أي مصدر من التلاتة (يعني تجاويف/تفاصيل غايرة) بتتظلل شوية أكتر —
            // من غير أي حسابات ضوضاء إضافية تقيلة على الأداء
            float lightSum = NdotL + NdotL2 * 0.5 + NdotL3 * 0.3;
            float occlusion = clamp(0.55 + lightSum * 0.5, 0.55, 1.0);

            vec3 ambient  = col * 0.42 * occlusion;
            vec3 diffuse  = col * NdotL  * 0.62;
            vec3 fill     = col * NdotL2 * 0.16;
            vec3 rimLight = col * NdotL3 * 0.10;

            // حافة خفيفة جدًا (fresnel) بس عشان الحواف الخارجية تبان، من غير أي لمعان حقيقي
            float NdotV   = max(dot(N, V), 0.0);
            float fresnel = pow(1.0 - NdotV, 5.0) * 0.06;
            vec3 rim      = col * fresnel;

            // لمعة ناعمة (Soft Specular) من الضوء الرئيسي بس — Blinn-Phong بأس منخفض
            // (10) يدي هالة واسعة ناعمة (زي دهان/سيراميك) مش نقطة حادة لامعة زي
            // المعدن. شدتها معتدلة (0.16) عشان تفضل موزونة مع الـ diffuse ومتطغاش عليه.
            vec3 Hn = normalize(L + V);
            float NdotH = max(dot(N, Hn), 0.0);
            float specular = pow(NdotH, 10.0) * 0.16 * NdotL;
            vec3 specCol = vec3(1.0, 0.98, 0.94) * specular; // أبيض دافئ خفيف، مش لون الموديل نفسه

            vec3 result = ambient + diffuse + fill + rimLight + rim + specCol;
            result = result / (result + vec3(0.55));  // tone mapping بسيط
            result = pow(result, vec3(0.9));           // gamma تقريبي
            gl_FragColor = vec4(result, uColor.a * uOpacityMultiplier);
        }
    """.trimIndent()

    private val lineVertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        attribute float vPointSize;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            gl_PointSize = vPointSize;
        }
    """.trimIndent()

    private val lineFragmentShaderCode = """
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            gl_FragColor = uColor;
        }
    """.trimIndent()

    // ═══ ظل أرضي بسيط تحت الموديل (contact shadow) — quad مسطّح بتدرّج دائري
    // شفاف بيدّي إحساس إن الموديل "واقف على حاجة" مش طاير في الفضاء ═══
    private val shadowVertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec3 vPosition;
        attribute vec2 vUV;
        varying vec2 fUV;
        void main() {
            gl_Position = uMVPMatrix * vec4(vPosition, 1.0);
            fUV = vUV;
        }
    """.trimIndent()

    private val shadowFragmentShaderCode = """
        precision mediump float;
        varying vec2 fUV;
        uniform vec3 uGlowColor;
        uniform float uGlowAlpha;
        void main() {
            float d = length(fUV);
            float alpha = smoothstep(1.0, 0.1, d) * uGlowAlpha;
            gl_FragColor = vec4(uGlowColor, alpha);
        }
    """.trimIndent()

    private var meshProgram = 0
    private var lineProgram = 0
    private var shadowProgram = 0

    // CPU-side buffers (nulled after upload to GPU)
    private var vertexBuffer: FloatBuffer? = null
    private var normalBuffer: FloatBuffer? = null
    private var wireframeBuffer: FloatBuffer? = null
    private var wireframeVertexCount = 0
    private var vertexCountToDraw = 0

    // VBO handles — data lives on GPU after upload
    private val vboIds = IntArray(3) // [0]=vertex [1]=normal [2]=wireframe
    private var vboReady = false
    private var pendingModel: STLModel? = null

    // جودة العرض من الإعدادات: 0=منخفضة 1=متوسطة 2=عالية
    @Volatile var qualityLevel: Int = 2

    @Volatile var wireframeMode = false

    /** بيتفعّل بس أثناء تدوير الموديل بإصبع واحد — بيوريه للمستخدم مركز الدوران (pivot)
     * اللي الموديل بيلف حواليه، عشان يعرف يتحكم في الاتجاه بشكل مقصود بدل ما يحس إنه
     * بيلف "من غير مرجعية". بيختفي تاني لما الإصبع يترفع. */
    @Volatile var showPivotIndicator = false
    /** true أثناء أي تفاعل لمس فعلي (تدوير/تحريك/تكبير) — بيوقف حركة "التنفس" الخفيفة
     * للموديل عشان مايتعارضش مع سحب المستخدم اليدوي */
    @Volatile var isUserInteracting = false
    /** true وقت ما وضع القياس مفعّل — بيوقف حركة "التنفس" عشان متعارضش مع دقة اختيار نقطتين القياس */
    @Volatile var suppressIdleFloat = false
    private var floatPhase = 0f

    private val mvpMatrix = FloatArray(16)
    /** بتتقلل مؤقتًا وقت رسم انعكاس الموديل (reflection) تحته، وترجع 1.0 بعد كده */
    private var currentOpacityMultiplier = 1f
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val normalMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    @Volatile var rotationX = 25f
    @Volatile var rotationY = 35f
    @Volatile var scaleFactor = 1f
    @Volatile var panX = 0f
    @Volatile var panY = 0f
    /** لما تبقى true، الموديل بيلف لوحده حوالين محور Y كل فريم — بيتوقف أوتوماتيك
     * أول ما المستخدم يلمس الشاشة عشان يدوّر يدويًا. */
    @Volatile var autoRotate = false

    /**
     * بيطبّق تغيير زووم (Pinch) حوالين نقطة معيّنة على الشاشة (نقطة تلاقي
     * الأصابع)، بدل ما الزووم يتم دايمًا حوالين مركز الموديل الثابت (البند 3.1).
     */
    fun applyPinchZoom(focalScreenX: Float, focalScreenY: Float, spanRatio: Float) {
        if (surfaceWidth == 0 || surfaceHeight == 0) return
        val oldScale = scaleFactor
        val newScale = (oldScale * spanRatio).coerceIn(0.1f, 12f)
        if (newScale == oldScale) return
        val actualRatio = newScale / oldScale

        val ratio = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        val ndcX = (focalScreenX / surfaceWidth) * 2f - 1f
        val ndcY = 1f - (focalScreenY / surfaceHeight) * 2f

        panX = panX * actualRatio + ndcX * ratio * (1f - actualRatio)
        panY = panY * actualRatio + ndcY * (1f - actualRatio)
        scaleFactor = newScale
    }

    // اتجاه الإضاءة - قابل للتغيير من أداة الإضاءة (LightDialOverlayView، حلقة 360°)
    @Volatile
    var lightAngle = 45f
        set(value) {
            field = ((value % 360f) + 360f) % 360f
        }

    private var modelCenter = floatArrayOf(0f, 0f, 0f)
    private var modelRadius = 1f
    /** لو مش null، بيستخدم بدل modelCenter كمركز دوران — بيتحدد من نقطة اللمس الأولى
     * على سطح الموديل (raycast) */
    @Volatile var pivotOverride: FloatArray? = null

    // CopyOnWriteArrayList بدل ArrayList - thread-safe
    private val measurementPoints = CopyOnWriteArrayList<FloatArray>()
    private val measurementPointTimes = CopyOnWriteArrayList<Long>()
    @Volatile private var previewPoint: FloatArray? = null

    fun setPreviewMeasurementPoint(point: FloatArray?) {
        previewPoint = point
    }

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    var modelColor = floatArrayOf(0.45f, 0.75f, 0.95f, 1.0f)

    fun setModelColor(r: Float, g: Float, b: Float) { modelColor = floatArrayOf(r, g, b, 1.0f) }

    enum class Material(val id: Int, val nameAr: String, val defaultColor: FloatArray) {
        CLAY_GRAY  (0, "كلاي رمادي",   floatArrayOf(0.62f, 0.62f, 0.64f)),
        CLAY_WHITE (1, "كلاي أبيض",    floatArrayOf(0.88f, 0.87f, 0.84f)),
        CLAY_BLUE  (2, "كلاي أزرق",    floatArrayOf(0.30f, 0.48f, 0.72f)),
        CLAY_BROWN (3, "كلاي بني",     floatArrayOf(0.50f, 0.34f, 0.22f)),
        CLAY_ORANGE(4, "كلاي برتقالي", floatArrayOf(0.85f, 0.48f, 0.18f)),
        CLAY_BLACK (5, "كلاي أسود",    floatArrayOf(0.16f, 0.16f, 0.18f)),
        CLAY_YELLOW(6, "كلاي أصفر",    floatArrayOf(0.90f, 0.76f, 0.20f)),
        CLAY_RED   (7, "كلاي أحمر",    floatArrayOf(0.75f, 0.22f, 0.20f))
    }

    @Volatile var currentMaterial = Material.CLAY_GRAY
    @Volatile var zUpMode = true
    @Volatile var modelBottomY = 0f
    private var modelMinBounds = floatArrayOf(0f, 0f, 0f)
    private var modelMaxBounds = floatArrayOf(0f, 0f, 0f)
    @Volatile var showBoundingBox = false
    @Volatile var showOpenEdgesHighlight = false
    @Volatile var openEdgeHighlightVertices: FloatArray? = null
    @Volatile var showReflection = false

    private var introActive = false
    private var introStartMs = 0L
    private val introDurationMs = 1100L

    private val POINT_SIZE_LARGE = 34f
    private val POINT_SIZE_RESTING = 15f
    private val POINT_SHRINK_DURATION_MS = 320L

    fun setMaterial(material: Material) {
        currentMaterial = material
        setModelColor(material.defaultColor[0], material.defaultColor[1], material.defaultColor[2])
    }
    fun getCurrentModelMatrix(): FloatArray = modelMatrix.copyOf()
    fun getCurrentViewMatrix(): FloatArray = viewMatrix.copyOf()
    fun getCurrentProjectionMatrix(): FloatArray = projectionMatrix.copyOf()
    fun getSurfaceWidth(): Int = surfaceWidth
    fun getSurfaceHeight(): Int = surfaceHeight

    private var currentModel: STLModel? = null
    fun getModel(): STLModel? = currentModel

    fun applyAxisConvention(model: STLModel, sourceIsAlwaysYUp: Boolean = false): STLModel =
        if (zUpMode && !sourceIsAlwaysYUp) swapYZ(model) else model

    fun clearModel() {
        currentModel = null
        pendingModel = null
        vertexBuffer = null; normalBuffer = null; wireframeBuffer = null
        vertexCountToDraw = 0
        wireframeVertexCount = 0
        vboReady = false
        measurementPoints.clear()
        measurementPointTimes.clear()
        previewPoint = null
        if (vboIds[0] != 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 0, null, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[1])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 0, null, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[2])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 0, null, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }
    }

    fun setModel(model: STLModel) {
        currentModel = model
        pendingModel = model

        modelCenter = floatArrayOf(
            (model.minBounds[0] + model.maxBounds[0]) / 2f,
            (model.minBounds[1] + model.maxBounds[1]) / 2f,
            (model.minBounds[2] + model.maxBounds[2]) / 2f
        )
        val dx = model.maxBounds[0] - model.minBounds[0]
        val dy = model.maxBounds[1] - model.minBounds[1]
        val dz = model.maxBounds[2] - model.minBounds[2]
        modelRadius = (maxOf(dx, dy, dz) / 2f).let { if (it <= 0f) 1f else it }
        modelBottomY = model.minBounds[1]
        modelMinBounds = model.minBounds.copyOf()
        modelMaxBounds = model.maxBounds.copyOf()

        rotationX = 25f; rotationY = 35f; scaleFactor = 0.85f; panX = 0f; panY = 0f
        pivotOverride = null
        measurementPoints.clear()
        introActive = true
        introStartMs = android.os.SystemClock.uptimeMillis()
        updateProjection()
    }

    private fun swapYZ(model: STLModel): STLModel {
        val v = model.vertices
        var i = 0
        while (i < v.size) {
            val oldY = v[i + 1]
            val oldZ = v[i + 2]
            v[i + 1] = oldZ
            v[i + 2] = -oldY
            i += 3
        }
        val n = model.normals
        i = 0
        while (i < n.size) {
            val oldY = n[i + 1]
            val oldZ = n[i + 2]
            n[i + 1] = oldZ
            n[i + 2] = -oldY
            i += 3
        }
        val minB = floatArrayOf(model.minBounds[0], model.minBounds[2], -model.maxBounds[1])
        val maxB = floatArrayOf(model.maxBounds[0], model.maxBounds[2], -model.minBounds[1])
        return model.copy(minBounds = minB, maxBounds = maxB)
    }

    private fun uploadModelToGPU(model: STLModel) {
        val verts = model.vertices
        val norms = model.normals
        vertexCountToDraw = verts.size / 3

        try {
            val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            vb.asFloatBuffer().put(verts); vb.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, vb, GLES20.GL_STATIC_DRAW)

            val nb = ByteBuffer.allocateDirect(norms.size * 4).order(ByteOrder.nativeOrder())
            nb.asFloatBuffer().put(norms); nb.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[1])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, norms.size * 4, nb, GLES20.GL_STATIC_DRAW)

            val triCount = vertexCountToDraw / 3
            val qualityMultiplier = when (qualityLevel) {
                0 -> 4
                1 -> 2
                else -> 1
            }
            val wireStep = when {
                triCount > 1_000_000 -> 20
                triCount > 500_000   -> 10
                triCount > 200_000   -> 5
                triCount > 50_000    -> 2
                else                 -> 1
            } * qualityMultiplier
            val maxWireTris = minOf((triCount + wireStep - 1) / wireStep, 50_000)
            val wireBytes = maxWireTris * 6 * 3 * 4
            val wb = ByteBuffer.allocateDirect(wireBytes).order(ByteOrder.nativeOrder())
            val wf = wb.asFloatBuffer()
            var wCount = 0; var vSrc = 0; var t = 0
            while (t < triCount && wCount < maxWireTris) {
                val base = vSrc
                if (base + 8 < verts.size) {
                    val ax = verts[base];   val ay = verts[base+1]; val az = verts[base+2]
                    val bx = verts[base+3]; val by = verts[base+4]; val bz = verts[base+5]
                    val cx = verts[base+6]; val cy = verts[base+7]; val cz = verts[base+8]
                    wf.put(ax); wf.put(ay); wf.put(az)
                    wf.put(bx); wf.put(by); wf.put(bz)
                    wf.put(bx); wf.put(by); wf.put(bz)
                    wf.put(cx); wf.put(cy); wf.put(cz)
                    wf.put(cx); wf.put(cy); wf.put(cz)
                    wf.put(ax); wf.put(ay); wf.put(az)
                    wCount++
                }
                t += wireStep; vSrc += wireStep * 9
            }
            wb.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[2])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, wCount * 18 * 4, wb, GLES20.GL_STATIC_DRAW)
            wireframeVertexCount = wCount * 6

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            vertexBuffer = null; normalBuffer = null; wireframeBuffer = null
            vboReady = true

        } catch (e: OutOfMemoryError) {
            android.util.Log.e("STLRenderer", "OOM in uploadModelToGPU, using CPU fallback")
            val maxVerts = minOf(verts.size, 3_000_000)
            vertexBuffer = ByteBuffer.allocateDirect(maxVerts * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(verts, 0, maxVerts); position(0) }
            normalBuffer = ByteBuffer.allocateDirect(maxVerts * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(norms, 0, maxVerts); position(0) }
            vertexCountToDraw = maxVerts / 3
            wireframeVertexCount = 0
            vboReady = false
        }
    }

    fun addMeasurementPoint(point: FloatArray) {
        measurementPoints.add(point)
        measurementPointTimes.add(android.os.SystemClock.uptimeMillis())
        if (measurementPoints.size > 2) { measurementPoints.removeAt(0); measurementPointTimes.removeAt(0) }
        previewPoint = null
    }

    fun clearMeasurementPoints() {
        measurementPoints.clear(); measurementPointTimes.clear(); previewPoint = null
    }
    fun getMeasurementPoints(): List<FloatArray> = measurementPoints.toList()

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        updateClearColor()
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        meshProgram = createProgram(vertexShaderCode, fragmentShaderCode)
        lineProgram = createProgram(lineVertexShaderCode, lineFragmentShaderCode)
        shadowProgram = createProgram(shadowVertexShaderCode, shadowFragmentShaderCode)
        GLES20.glGenBuffers(3, vboIds, 0)
        pendingModel?.let { uploadModelToGPU(it); pendingModel = null }
    }

    var bgColor = floatArrayOf(0f, 0f, 0f)
    fun setBackgroundColor(r: Float, g: Float, b: Float) { bgColor = floatArrayOf(r, g, b); updateClearColor() }
    private fun updateClearColor() { GLES20.glClearColor(bgColor[0], bgColor[1], bgColor[2], 1f) }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        surfaceWidth = width; surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
        updateProjection()
    }

    fun updateProjection() {
        if (surfaceWidth == 0 || surfaceHeight == 0) return
        val ratio = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        val safeRadius = if (modelRadius > 0f) modelRadius else 1f
        val orthoHalf = safeRadius * 1.4f / scaleFactor
        val near = -safeRadius * 10f
        val far = safeRadius * 10f
        Matrix.orthoM(projectionMatrix, 0,
            -orthoHalf * ratio, orthoHalf * ratio,
            -orthoHalf, orthoHalf, near, far)
    }

    private fun updatePerspectiveProjection(safeRadius: Float) {
        if (surfaceWidth == 0 || surfaceHeight == 0) return
        val ratio = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        val near = safeRadius * 0.05f
        val far = safeRadius * 300f
        Matrix.perspectiveM(projectionMatrix, 0, 45f, ratio, near, far)
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        updateClearColor()

        pendingModel?.let { uploadModelToGPU(it); pendingModel = null }

        if ((!vboReady && vertexBuffer == null) || vertexCountToDraw == 0) return

        if (autoRotate) rotationY = (rotationY + 0.6f) % 360f

        val floatOffset = if (!isUserInteracting && !suppressIdleFloat) {
            floatPhase += 0.025f
            kotlin.math.sin(floatPhase) * (if (modelRadius > 0f) modelRadius else 1f) * 0.012f
        } else 0f

        if (introActive && isUserInteracting) introActive = false

        val safeRadius = if (modelRadius > 0f) modelRadius else 1f
        var camDistance = safeRadius * 5f
        var introFadeAlpha = 1f

        if (introActive) {
            val elapsed = android.os.SystemClock.uptimeMillis() - introStartMs
            val raw = (elapsed.toFloat() / introDurationMs).coerceIn(0f, 1f)
            val t = 1f - (1f - raw) * (1f - raw) * (1f - raw)
            updatePerspectiveProjection(safeRadius)
            camDistance = safeRadius * (5f + (1f - t) * 55f)
            introFadeAlpha = (raw * 1.4f).coerceIn(0f, 1f)
            if (raw >= 1f) introActive = false
        } else {
            updateProjection()
        }

        val panScale = safeRadius * 1.4f / scaleFactor

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, rotationX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, rotationY, 0f, 1f, 0f)
        val pivot = pivotOverride ?: modelCenter
        Matrix.translateM(modelMatrix, 0, -pivot[0], -pivot[1] + floatOffset, -pivot[2])

        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.translateM(viewMatrix, 0, panX * panScale, panY * panScale, -camDistance)

        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        Matrix.invertM(normalMatrix, 0, modelMatrix, 0)
        Matrix.transposeM(normalMatrix, 0, normalMatrix, 0)

        drawGroundShadow()
        if (showReflection) drawReflection() else drawFloorGrid()
        currentOpacityMultiplier = introFadeAlpha
        drawMesh()
        currentOpacityMultiplier = 1f

        val pts = measurementPoints.toList()
        val hasPreview = pts.size == 1 && previewPoint != null
        if (pts.isNotEmpty() || hasPreview) drawMeasurementOverlay(pts, hasPreview)
        if (showPivotIndicator) drawPivotIndicator()
        if (showBoundingBox) drawBoundingBox()
        if (showOpenEdgesHighlight) drawOpenEdgesHighlight()
    }

    private fun drawMesh() {
        if (wireframeMode) drawWireframe() else drawSolidMesh()
    }

    fun captureFrame(width: Int, height: Int): android.graphics.Bitmap {
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        buffer.rewind(); bitmap.copyPixelsFromBuffer(buffer)
        val matrix = android.graphics.Matrix().apply { postScale(1f, -1f) }
        return android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
    }

    private fun drawWireframe() {
        if (wireframeVertexCount == 0) return
        GLES20.glUseProgram(lineProgram)
        val positionHandle = GLES20.glGetAttribLocation(lineProgram, "vPosition")
        val mvpHandle = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorHandle = GLES20.glGetUniformLocation(lineProgram, "uColor")
        GLES20.glEnableVertexAttribArray(positionHandle)
        if (vboReady) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[2])
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, 0)
        } else {
            val buf = wireframeBuffer ?: return
            buf.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, buf)
        }
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(colorHandle, modelColor[0], modelColor[1], modelColor[2], 1f)
        GLES20.glLineWidth(1.5f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, wireframeVertexCount)
        GLES20.glDisableVertexAttribArray(positionHandle)
        if (vboReady) GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawReflection() {
        if (wireframeMode) return
        val refY = (pivotOverride ?: modelCenter)[1]
        val floorY = modelBottomY - refY

        val reflectMat = FloatArray(16)
        Matrix.setIdentityM(reflectMat, 0)
        Matrix.translateM(reflectMat, 0, 0f, floorY, 0f)
        Matrix.scaleM(reflectMat, 0, 1f, -1f, 1f)
        Matrix.translateM(reflectMat, 0, 0f, -floorY, 0f)

        val reflectedModel = FloatArray(16)
        Matrix.multiplyMM(reflectedModel, 0, reflectMat, 0, modelMatrix, 0)

        val reflectedTemp = FloatArray(16)
        Matrix.multiplyMM(reflectedTemp, 0, viewMatrix, 0, reflectedModel, 0)
        val reflectedMvp = FloatArray(16)
        Matrix.multiplyMM(reflectedMvp, 0, projectionMatrix, 0, reflectedTemp, 0)

        val reflectedNormal = FloatArray(16)
        Matrix.invertM(reflectedNormal, 0, reflectedModel, 0)
        Matrix.transposeM(reflectedNormal, 0, reflectedNormal, 0)

        val savedMvp = mvpMatrix.copyOf()
        val savedModel = modelMatrix.copyOf()
        val savedNormal = normalMatrix.copyOf()

        System.arraycopy(reflectedMvp, 0, mvpMatrix, 0, 16)
        System.arraycopy(reflectedModel, 0, modelMatrix, 0, 16)
        System.arraycopy(reflectedNormal, 0, normalMatrix, 0, 16)

        GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        currentOpacityMultiplier = 0.14f
        drawSolidMesh()
        currentOpacityMultiplier = 1f
        GLES20.glDepthMask(true)

        System.arraycopy(savedMvp, 0, mvpMatrix, 0, 16)
        System.arraycopy(savedModel, 0, modelMatrix, 0, 16)
        System.arraycopy(savedNormal, 0, normalMatrix, 0, 16)
    }

    private fun drawFloorGrid() {
        val r = if (modelRadius > 0f) modelRadius else 1f
        val pivot = pivotOverride ?: modelCenter
        val centerX = pivot[0]
        val centerZ = pivot[2]
        val floorY = modelBottomY
        val ext = r * 1.7f
        val divisions = 12
        val step = (ext * 2f) / divisions

        val lines = ArrayList<Float>((divisions + 1) * 12)
        for (i in 0..divisions) {
            val pos = -ext + i * step
            lines.add(centerX - ext); lines.add(floorY); lines.add(centerZ + pos)
            lines.add(centerX + ext); lines.add(floorY); lines.add(centerZ + pos)
            lines.add(centerX + pos); lines.add(floorY); lines.add(centerZ - ext)
            lines.add(centerX + pos); lines.add(floorY); lines.add(centerZ + ext)
        }
        val verts = lines.toFloatArray()

        GLES20.glUseProgram(lineProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val posHandle = GLES20.glGetAttribLocation(lineProgram, "vPosition")
        val mvpHandle = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorHandle = GLES20.glGetUniformLocation(lineProgram, "uColor")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(colorHandle, 0.62f, 0.64f, 0.68f, 0.5f)
        GLES20.glLineWidth(1.2f)

        val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(verts); position(0) }

        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, vb)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, verts.size / 3)
        GLES20.glDisableVertexAttribArray(posHandle)
    }

    private fun drawSolidMesh() {
        GLES20.glUseProgram(meshProgram)
        val positionHandle = GLES20.glGetAttribLocation(meshProgram, "vPosition")
        val normalHandle   = GLES20.glGetAttribLocation(meshProgram, "vNormal")
        val mvpHandle      = GLES20.glGetUniformLocation(meshProgram, "uMVPMatrix")
        val modelMatHandle = GLES20.glGetUniformLocation(meshProgram, "uModelMatrix")
        GLES20.glUniformMatrix4fv(modelMatHandle, 1, false, modelMatrix, 0)
        val normalMatrixHandle = GLES20.glGetUniformLocation(meshProgram, "uNormalMatrix")
        val colorHandle = GLES20.glGetUniformLocation(meshProgram, "uColor")
        val lightDirHandle = GLES20.glGetUniformLocation(meshProgram, "uLightDir")
        val materialHandle = GLES20.glGetUniformLocation(meshProgram, "uMaterial")
        val patternScaleHandle = GLES20.glGetUniformLocation(meshProgram, "uPatternScale")
        val opacityHandle = GLES20.glGetUniformLocation(meshProgram, "uOpacityMultiplier")

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(normalHandle)
        if (vboReady) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[1])
            GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        } else {
            vertexBuffer?.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer ?: return)
            normalBuffer?.position(0)
            GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 0, normalBuffer ?: return)
        }

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(normalMatrixHandle, 1, false, normalMatrix, 0)
        GLES20.glUniform4fv(colorHandle, 1, modelColor, 0)
        GLES20.glUniform1i(materialHandle, currentMaterial.id)
        GLES20.glUniform1f(patternScaleHandle, 1f / (if (modelRadius > 0f) modelRadius else 1f))
        GLES20.glUniform1f(opacityHandle, currentOpacityMultiplier)

        val angleRad = Math.toRadians(lightAngle.toDouble()).toFloat()
        val lx = kotlin.math.cos(angleRad) * 0.7f
        val ly = 0.7f
        val lz = kotlin.math.sin(angleRad) * 0.7f
        GLES20.glUniform3f(lightDirHandle, lx, ly, lz)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCountToDraw)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
        if (vboReady) GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawMeasurementOverlay(committedPts: List<FloatArray>, hasPreview: Boolean) {
        val overlayPts = if (hasPreview) committedPts + previewPoint!! else committedPts
        if (overlayPts.isEmpty()) return

        GLES20.glUseProgram(lineProgram)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        val positionHandle = GLES20.glGetAttribLocation(lineProgram, "vPosition")
        val sizeHandle = GLES20.glGetAttribLocation(lineProgram, "vPointSize")
        val mvpHandle = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorHandle = GLES20.glGetUniformLocation(lineProgram, "uColor")

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(sizeHandle)

        val flat = FloatArray(overlayPts.size * 3)
        val sizes = FloatArray(overlayPts.size)
        val now = android.os.SystemClock.uptimeMillis()
        overlayPts.forEachIndexed { i, p ->
            flat[i * 3] = p[0]; flat[i * 3 + 1] = p[1]; flat[i * 3 + 2] = p[2]
            sizes[i] = if (i < measurementPointTimes.size) {
                val elapsed = now - measurementPointTimes[i]
                val t = (elapsed.toFloat() / POINT_SHRINK_DURATION_MS).coerceIn(0f, 1f)
                POINT_SIZE_RESTING + (POINT_SIZE_LARGE - POINT_SIZE_RESTING) * (1f - t)
            } else {
                POINT_SIZE_LARGE
            }
        }
        val fb = ByteBuffer.allocateDirect(flat.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(flat); position(0) }
        val sb = ByteBuffer.allocateDirect(sizes.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(sizes); position(0) }

        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, fb)
        GLES20.glVertexAttribPointer(sizeHandle, 1, GLES20.GL_FLOAT, false, 0, sb)
        GLES20.glUniform4f(colorHandle, 1f, 0.75f, 0.1f, 1f)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, overlayPts.size)

        if (overlayPts.size == 2) {
            GLES20.glUniform4f(colorHandle, 1f, 0.85f, 0.2f, 1f)
            GLES20.glLineWidth(4f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        }

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(sizeHandle)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawGroundShadow() {
        val r = if (modelRadius > 0f) modelRadius else 1f
        val pivot = pivotOverride ?: modelCenter
        val centerX = pivot[0]
        val centerZ = pivot[2]
        val floorY = modelBottomY
        val ext = r * 1.7f
        val verts = floatArrayOf(
            centerX - ext, floorY, centerZ - ext,
            centerX + ext, floorY, centerZ - ext,
            centerX - ext, floorY, centerZ + ext,
            centerX + ext, floorY, centerZ - ext,
            centerX + ext, floorY, centerZ + ext,
            centerX - ext, floorY, centerZ + ext
        )
        val uvs = floatArrayOf(
            -1f, -1f,  1f, -1f,  -1f, 1f,
             1f, -1f,  1f,  1f,  -1f, 1f
        )

        GLES20.glUseProgram(shadowProgram)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val posHandle = GLES20.glGetAttribLocation(shadowProgram, "vPosition")
        val uvHandle = GLES20.glGetAttribLocation(shadowProgram, "vUV")
        val mvpHandle = GLES20.glGetUniformLocation(shadowProgram, "uMVPMatrix")
        val colorHandle = GLES20.glGetUniformLocation(shadowProgram, "uGlowColor")
        val alphaHandle = GLES20.glGetUniformLocation(shadowProgram, "uGlowAlpha")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform3f(colorHandle, 0f, 0f, 0f)
        GLES20.glUniform1f(alphaHandle, 0.16f)

        val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(verts); position(0) }
        val ub = ByteBuffer.allocateDirect(uvs.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(uvs); position(0) }

        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, vb)
        GLES20.glEnableVertexAttribArray(uvHandle)
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 0, ub)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(uvHandle)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawBoundingBox() {
        val minX = modelMinBounds[0]; val maxX = modelMaxBounds[0]
        val minY = modelMinBounds[1]; val maxY = modelMaxBounds[1]
        val minZ = modelMinBounds[2]; val maxZ = modelMaxBounds[2]

        val c = arrayOf(
            floatArrayOf(minX, minY, minZ), floatArrayOf(maxX, minY, minZ),
            floatArrayOf(maxX, maxY, minZ), floatArrayOf(minX, maxY, minZ),
            floatArrayOf(minX, minY, maxZ), floatArrayOf(maxX, minY, maxZ),
            floatArrayOf(maxX, maxY, maxZ), floatArrayOf(minX, maxY, maxZ)
        )
        val edges = intArrayOf(
            0,1, 1,2, 2,3, 3,0,
            4,5, 5,6, 6,7, 7,4,
            0,4, 1,5, 2,6, 3,7
        )
        val verts = FloatArray(edges.size * 3)
        for (i in edges.indices) {
            val p = c[edges[i]]
            verts[i * 3] = p[0]; verts[i * 3 + 1] = p[1]; verts[i * 3 + 2] = p[2]
        }

        GLES20.glUseProgram(lineProgram)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        val positionHandle = GLES20.glGetAttribLocation(lineProgram, "vPosition")
        val mvpHandle = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorHandle = GLES20.glGetUniformLocation(lineProgram, "uColor")

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(colorHandle, 1f, 0.75f, 0.1f, 0.75f)
        GLES20.glLineWidth(2f)

        val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(verts); position(0) }

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vb)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, edges.size)
        GLES20.glDisableVertexAttribArray(positionHandle)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawOpenEdgesHighlight() {
        val verts = openEdgeHighlightVertices
        if (verts == null || verts.isEmpty()) return

        GLES20.glUseProgram(lineProgram)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        val positionHandle = GLES20.glGetAttribLocation(lineProgram, "vPosition")
        val mvpHandle = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorHandle = GLES20.glGetUniformLocation(lineProgram, "uColor")

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(colorHandle, 1f, 0.15f, 0.15f, 0.95f)
        GLES20.glLineWidth(4f)

        val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(verts); position(0) }

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vb)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, verts.size / 3)
        GLES20.glDisableVertexAttribArray(positionHandle)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawPivotIndicator() {
        GLES20.glUseProgram(lineProgram)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        val positionHandle = GLES20.glGetAttribLocation(lineProgram, "vPosition")
        val mvpHandle = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorHandle = GLES20.glGetUniformLocation(lineProgram, "uColor")

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)

        val pivot = pivotOverride ?: modelCenter
        val len = (if (modelRadius > 0f) modelRadius else 1f) * 0.12f
        val lines = floatArrayOf(
            pivot[0] - len, pivot[1], pivot[2],  pivot[0] + len, pivot[1], pivot[2],
            pivot[0], pivot[1] - len, pivot[2],  pivot[0], pivot[1] + len, pivot[2],
            pivot[0], pivot[1], pivot[2] - len,  pivot[0], pivot[1], pivot[2] + len
        )
        val fb = ByteBuffer.allocateDirect(lines.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(lines); position(0) }

        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, fb)
        GLES20.glLineWidth(3f)
        GLES20.glUniform4f(colorHandle, 1f, 1f, 1f, 0.9f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 6)

        val dot = floatArrayOf(pivot[0], pivot[1], pivot[2])
        val dotBuffer = ByteBuffer.allocateDirect(dot.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(dot); position(0) }
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, dotBuffer)
        GLES20.glUniform4f(colorHandle, 1f, 0.75f, 0.1f, 1f)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun createProgram(vertexCode: String, fragmentCode: String): Int {
        val v = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
        val f = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, v)
            GLES20.glAttachShader(it, f)
            GLES20.glLinkProgram(it)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(it)
                GLES20.glDeleteProgram(it)
                throw RuntimeException("Program link failed: $log")
            }
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, shaderCode)
            GLES20.glCompileShader(it)
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(it)
                GLES20.glDeleteShader(it)
                throw RuntimeException("Shader compile failed: $log")
            }
        }
    }

    // ============================================================
    // ===== دوال الإسقاط والعكس (مطلوبة لنظام القياس في GLViewerView)
    // ============================================================

    /**
     * يحول نقطة من إحداثيات الشاشة إلى نقطة ثلاثية الأبعاد على سطح الموديل.
     * بيستخدم RayPicker + Möller–Trumbore.
     * بيرجع null لو اللمس وقع في فراغ (مفيش تقاطع مع أي مثلث).
     */
    fun unprojectRay(screenX: Float, screenY: Float): FloatArray? {
        if (surfaceWidth == 0 || surfaceHeight == 0) return null
        val model = currentModel ?: return null

        val ray = RayPicker.screenPointToRay(
            screenX, screenY,
            surfaceWidth, surfaceHeight,
            modelMatrix, viewMatrix, projectionMatrix
        )
        return RayPicker.findClosestIntersection(ray, model)
    }

    /**
     * يسقط نقطة ثلاثية الأبعاد (World) على الشاشة ويكتب النتيجة في out[0]=x, out[1]=y
     * بيستخدم mvpMatrix الحالية.
     */
    fun projectToScreenInto(worldPoint: FloatArray, out: FloatArray) {
        if (surfaceWidth == 0 || surfaceHeight == 0) {
            out[0] = 0f
            out[1] = 0f
            return
        }

        val temp = FloatArray(4)
        Matrix.multiplyMV(temp, 0, mvpMatrix, 0,
            floatArrayOf(worldPoint[0], worldPoint[1], worldPoint[2], 1f), 0)

        if (temp[3] == 0f) {
            out[0] = 0f
            out[1] = 0f
            return
        }

        val ndcX = temp[0] / temp[3]
        val ndcY = temp[1] / temp[3]

        out[0] = (ndcX + 1f) * 0.5f * surfaceWidth
        out[1] = (1f - ndcY) * 0.5f * surfaceHeight
    }
}