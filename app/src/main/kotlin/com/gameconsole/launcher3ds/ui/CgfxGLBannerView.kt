package com.gameconsole.launcher3ds.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import com.gameconsole.launcher3ds.parser.CgfxParser
import com.gameconsole.launcher3ds.parser.SpicaCgfxParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 3DS 原版 CGFX 上屏 3D 横幅实时 OpenGL ES 2.0 渲染视图（基于 TextureView）
 * 与 videoTextureView 同层序参与 View 树绘制，彻底解决 SurfaceView 被父视图背景遮挡的黑框问题
 */
class CgfxGLBannerView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    @Volatile
    private var pendingScene: CgfxParser.CgfxScene? = null

    @Volatile
    private var pendingSpicaScene: SpicaCgfxParser.SpicaCgfxScene? = null

    @Volatile
    private var useSpicaPipeline: Boolean = false

    @Volatile
    private var sceneDirty: Boolean = false

    @Volatile
    var parallaxX: Float = 0f

    @Volatile
    var parallaxY: Float = 0f

    @Volatile
    private var animStartTimeMs: Long = System.currentTimeMillis()

    @Volatile
    private var surfaceWidth: Int = 1

    @Volatile
    private var surfaceHeight: Int = 1

    @Volatile
    private var sizeChanged: Boolean = false

    @Volatile
    private var homeMenuCameraMode: Boolean = false

    private var renderThread: GlRenderThread? = null

    var cornerRadiusPx: Float = 14f
        set(value) {
            field = value
            invalidateOutline()
        }

    init {
        isOpaque = false
        surfaceTextureListener = this
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
    }

    fun setScene(scene: CgfxParser.CgfxScene?) {
        pendingScene = scene
        pendingSpicaScene = null
        useSpicaPipeline = false
        sceneDirty = true
        animStartTimeMs = System.currentTimeMillis()
    }

    fun setSpicaScene(scene: SpicaCgfxParser.SpicaCgfxScene?, homeMenuMode: Boolean = false) {
        pendingSpicaScene = scene
        pendingScene = null
        useSpicaPipeline = true
        homeMenuCameraMode = homeMenuMode
        sizeChanged = true
        sceneDirty = true
        animStartTimeMs = System.currentTimeMillis()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = max(1, width)
        surfaceHeight = max(1, height)
        sizeChanged = true
        renderThread?.stopRender()
        renderThread = GlRenderThread(surface).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = max(1, width)
        surfaceHeight = max(1, height)
        sizeChanged = true
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderThread?.stopRender()
        renderThread = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private data class GlMesh(
        val vbo: FloatBuffer,
        val ibo: ShortBuffer,
        val indexCount: Int,
        val texIndex: Int
    )

    private data class SpicaMaterialProgram(
        val mat: SpicaCgfxParser.SpicaMaterial,
        val progId: Int,
        val aPosLoc: Int,
        val aUv0Loc: Int,
        val aUv1Loc: Int,
        val aColLoc: Int,
        val uMvpLoc: Int,
        val uUvScale0Loc: Int,
        val uUvTrans0Loc: Int,
        val uUvScale1Loc: Int,
        val uUvTrans1Loc: Int,
        val uTexLocs: IntArray,
        val uHasTexLocs: IntArray,
        val uEmissionLoc: Int,
        val uAmbientLoc: Int,
        val uDiffuseLoc: Int,
        val uConstLocs: IntArray
    )

    private data class SpicaGlMesh(
        val vbo: FloatBuffer,
        val ibo: ShortBuffer,
        val indexCount: Int,
        val matIndex: Int,
        val renderLayer: Int
    )

    private inner class GlRenderThread(private val surfaceTexture: SurfaceTexture) : Thread("CgfxGLThread") {
        @Volatile
        private var running = true

        private var egl: EGL10? = null
        private var eglDisplay: EGLDisplay = EGL10.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL10.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL10.EGL_NO_SURFACE

        private var programId = 0
        private var aPosLoc = 0
        private var aUvLoc = 0
        private var aColLoc = 0
        private var uMvpLoc = 0
        private var uTexLoc = 0
        private var uHasTexLoc = 0

        private var activeScene: CgfxParser.CgfxScene? = null
        private var activeSpicaScene: SpicaCgfxParser.SpicaCgfxScene? = null
        private val glTextures = ArrayList<Int>()
        private val glMeshes = ArrayList<GlMesh>()
        private val spicaMatPrograms = ArrayList<SpicaMaterialProgram>()
        private val spicaGlMeshes = ArrayList<SpicaGlMesh>()

        private val projMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val modelMatrix = FloatArray(16)
        private val vpMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)

        fun stopRender() {
            running = false
        }

        override fun run() {
            if (!initEgl()) return
            initGl()
            sceneDirty = true
            sizeChanged = true

            while (running) {
                if (sizeChanged) {
                    sizeChanged = false
                    val w = surfaceWidth.coerceAtLeast(1)
                    val h = surfaceHeight.coerceAtLeast(1)
                    GLES20.glViewport(0, 0, w, h)
                    val fovDeg = if (useSpicaPipeline && homeMenuCameraMode) 30f else 38f
                    Matrix.perspectiveM(projMatrix, 0, fovDeg, w.toFloat() / h.toFloat(), 0.1f, 200f)
                }

                if (sceneDirty) {
                    sceneDirty = false
                    if (useSpicaPipeline) {
                        uploadSpicaScene(pendingSpicaScene)
                    } else {
                        uploadScene(pendingScene)
                    }
                }

                if (useSpicaPipeline) {
                    drawSpicaFrame()
                } else {
                    drawFrame()
                }
                egl?.eglSwapBuffers(eglDisplay, eglSurface)

                try {
                    sleep(16L)
                } catch (_: InterruptedException) {
                    break
                }
            }

            releaseGlAndEgl()
        }

        private fun initEgl(): Boolean {
            val e = EGLContext.getEGL() as? EGL10 ?: return false
            egl = e
            eglDisplay = e.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            if (eglDisplay === EGL10.EGL_NO_DISPLAY) return false
            val version = IntArray(2)
            if (!e.eglInitialize(eglDisplay, version)) return false

            val configSpec = intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)
            if (!e.eglChooseConfig(eglDisplay, configSpec, configs, 1, numConfig) || numConfig[0] <= 0) {
                return false
            }
            val config = configs[0] ?: return false
            val attribList = intArrayOf(0x3098, 2, EGL10.EGL_NONE) // EGL_CONTEXT_CLIENT_VERSION = 2
            eglContext = e.eglCreateContext(eglDisplay, config, EGL10.EGL_NO_CONTEXT, attribList)
            if (eglContext === EGL10.EGL_NO_CONTEXT) return false

            eglSurface = e.eglCreateWindowSurface(eglDisplay, config, surfaceTexture, null)
            if (eglSurface === EGL10.EGL_NO_SURFACE) return false

            return e.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }

        private fun initGl() {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            val vs = """
                uniform mat4 uMvp;
                attribute vec3 aPos;
                attribute vec2 aUv;
                attribute vec4 aCol;
                varying vec2 vUv;
                varying vec4 vCol;
                void main() {
                    vUv = aUv;
                    vCol = aCol;
                    gl_Position = uMvp * vec4(aPos, 1.0);
                }
            """.trimIndent()

            val fs = """
                precision mediump float;
                uniform sampler2D uTex;
                uniform float uHasTex;
                varying vec2 vUv;
                varying vec4 vCol;
                void main() {
                    vec4 texCol = uHasTex > 0.5 ? texture2D(uTex, vUv) : vec4(1.0);
                    vec4 outCol = texCol * vCol;
                    if (outCol.a < 0.02) discard;
                    gl_FragColor = vec4(outCol.rgb * outCol.a, outCol.a);
                }
            """.trimIndent()

            programId = createProgram(vs, fs)
            aPosLoc = GLES20.glGetAttribLocation(programId, "aPos")
            aUvLoc = GLES20.glGetAttribLocation(programId, "aUv")
            aColLoc = GLES20.glGetAttribLocation(programId, "aCol")
            uMvpLoc = GLES20.glGetUniformLocation(programId, "uMvp")
            uTexLoc = GLES20.glGetUniformLocation(programId, "uTex")
            uHasTexLoc = GLES20.glGetUniformLocation(programId, "uHasTex")
        }

        private fun computeMvp(minX: Float, maxX: Float, minY: Float, maxY: Float, minZ: Float, maxZ: Float) {
            val elapsedSec = (System.currentTimeMillis() - animStartTimeMs) / 1000f
            val popT = (elapsedSec / 0.35f).coerceIn(0f, 1f)
            val popScale = 0.72f + 0.28f * (1f - (1f - popT) * (1f - popT))

            val cx = (minX + maxX) * 0.5f
            val cy = (minY + maxY) * 0.5f
            val cz = (minZ + maxZ) * 0.5f
            val spanX = (maxX - minX).coerceAtLeast(0.01f)
            val spanY = (maxY - minY).coerceAtLeast(0.01f)
            val spanZ = (maxZ - minZ).coerceAtLeast(0.01f)
            val maxSpan = max(spanX, max(spanY, spanZ))
            val normScale = (1.0f / maxSpan) * popScale

            val halfW = (spanX * 0.5f) * normScale
            val halfH = (spanY * 0.5f) * normScale
            val halfD = (spanZ * 0.5f) * normScale

            val tanHalfFov = if (homeMenuCameraMode) 0.267949f else 0.344328f
            val aspect = (surfaceWidth.coerceAtLeast(1).toFloat() / surfaceHeight.coerceAtLeast(1).toFloat()).coerceAtLeast(0.5f)
            val distForY = (halfH / (0.86f * tanHalfFov)) + halfD * 0.65f
            val distForX = (halfW / (0.88f * tanHalfFov * aspect)) + halfD * 0.65f
            val camDist = max(distForY, distForX).coerceIn(0.65f, 4.5f)

            val swayYaw = if (homeMenuCameraMode) {
                sin(elapsedSec * 0.6283185f) * 11.46f + parallaxX * 8f
            } else {
                sin(elapsedSec * 1.15f) * 14f + parallaxX * 10f
            }
            val swayPitch = if (homeMenuCameraMode) {
                parallaxY * 4f
            } else {
                cos(elapsedSec * 0.85f) * 4.5f + parallaxY * 6f
            }
            val floatY = if (homeMenuCameraMode) {
                sin(elapsedSec * 1.2f) * 0.014f
            } else {
                sin(elapsedSec * 1.7f) * 0.022f
            }
            val camY = if (homeMenuCameraMode) camDist * 0.14f else camDist * 0.05f

            Matrix.setLookAtM(viewMatrix, 0, 0f, camY, camDist, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, 0f, floatY, 0f)
            Matrix.rotateM(modelMatrix, 0, swayPitch, 1f, 0f, 0f)
            Matrix.rotateM(modelMatrix, 0, swayYaw, 0f, 1f, 0f)
            Matrix.scaleM(modelMatrix, 0, normScale, normScale, normScale)
            Matrix.translateM(modelMatrix, 0, -cx, -cy, -cz)

            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        }

        private fun spicaWrapToGl(w: Int): Int = when (w) {
            0, 1 -> GLES20.GL_CLAMP_TO_EDGE
            3 -> GLES20.GL_MIRRORED_REPEAT
            else -> GLES20.GL_REPEAT
        }

        private fun drawSpicaFrame() {
            GLES20.glDepthMask(true)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val scene = activeSpicaScene ?: return
            if (spicaGlMeshes.isEmpty()) return

            computeMvp(scene.minX, scene.maxX, scene.minY, scene.maxY, scene.minZ, scene.maxZ)

            for (mesh in spicaGlMeshes) {
                val mp = spicaMatPrograms.getOrNull(mesh.matIndex) ?: continue
                val mat = mp.mat
                GLES20.glUseProgram(mp.progId)
                if (mp.uMvpLoc >= 0) GLES20.glUniformMatrix4fv(mp.uMvpLoc, 1, false, mvpMatrix, 0)

                // Depth & Blend state per SPICA GfxFragOp
                if (mat.depthTestEnabled) GLES20.glEnable(GLES20.GL_DEPTH_TEST) else GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                GLES20.glDepthMask(mat.depthWriteEnabled)
                if (mat.blendMode == 0) {
                    GLES20.glDisable(GLES20.GL_BLEND)
                } else {
                    GLES20.glEnable(GLES20.GL_BLEND)
                    val srcRgb = if (mat.blendColorSrc == GLES20.GL_SRC_ALPHA) GLES20.GL_ONE else mat.blendColorSrc
                    GLES20.glBlendEquationSeparate(mat.blendColorEq, mat.blendAlphaEq)
                    GLES20.glBlendFuncSeparate(srcRgb, mat.blendColorDst, GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                }

                // UV transform uniforms
                if (mp.uUvScale0Loc >= 0) GLES20.glUniform2f(mp.uUvScale0Loc, mat.uvScales[0], mat.uvScales[1])
                if (mp.uUvTrans0Loc >= 0) GLES20.glUniform2f(mp.uUvTrans0Loc, mat.uvTranslations[0], mat.uvTranslations[1])
                if (mp.uUvScale1Loc >= 0) GLES20.glUniform2f(mp.uUvScale1Loc, mat.uvScales[2], mat.uvScales[3])
                if (mp.uUvTrans1Loc >= 0) GLES20.glUniform2f(mp.uUvTrans1Loc, mat.uvTranslations[2], mat.uvTranslations[3])

                // Material & Constant colors
                if (mp.uEmissionLoc >= 0) GLES20.glUniform4fv(mp.uEmissionLoc, 1, mat.emission, 0)
                if (mp.uAmbientLoc >= 0) GLES20.glUniform4fv(mp.uAmbientLoc, 1, mat.ambient, 0)
                if (mp.uDiffuseLoc >= 0) GLES20.glUniform4fv(mp.uDiffuseLoc, 1, mat.diffuse, 0)
                for (c in 0..5) {
                    if (mp.uConstLocs[c] >= 0) GLES20.glUniform4fv(mp.uConstLocs[c], 1, mat.constants[c], 0)
                }

                // Bind up to 3 texture units with SPICA WrapU / WrapV
                for (u in 0..2) {
                    val texId = glTextures.getOrNull(mat.texIndices[u]) ?: 0
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + u)
                    if (texId != 0) {
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, spicaWrapToGl(mat.wrapU[u]))
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, spicaWrapToGl(mat.wrapV[u]))
                        if (mp.uTexLocs[u] >= 0) GLES20.glUniform1i(mp.uTexLocs[u], u)
                        if (mp.uHasTexLocs[u] >= 0) GLES20.glUniform1f(mp.uHasTexLocs[u], 1f)
                    } else {
                        if (mp.uHasTexLocs[u] >= 0) GLES20.glUniform1f(mp.uHasTexLocs[u], 0f)
                    }
                }

                val stride = 11 * 4
                if (mp.aPosLoc >= 0) {
                    mesh.vbo.position(0)
                    GLES20.glEnableVertexAttribArray(mp.aPosLoc)
                    GLES20.glVertexAttribPointer(mp.aPosLoc, 3, GLES20.GL_FLOAT, false, stride, mesh.vbo)
                }
                if (mp.aUv0Loc >= 0) {
                    mesh.vbo.position(3)
                    GLES20.glEnableVertexAttribArray(mp.aUv0Loc)
                    GLES20.glVertexAttribPointer(mp.aUv0Loc, 2, GLES20.GL_FLOAT, false, stride, mesh.vbo)
                }
                if (mp.aUv1Loc >= 0) {
                    mesh.vbo.position(5)
                    GLES20.glEnableVertexAttribArray(mp.aUv1Loc)
                    GLES20.glVertexAttribPointer(mp.aUv1Loc, 2, GLES20.GL_FLOAT, false, stride, mesh.vbo)
                }
                if (mp.aColLoc >= 0) {
                    mesh.vbo.position(7)
                    GLES20.glEnableVertexAttribArray(mp.aColLoc)
                    GLES20.glVertexAttribPointer(mp.aColLoc, 4, GLES20.GL_FLOAT, false, stride, mesh.vbo)
                }

                mesh.ibo.position(0)
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, mesh.ibo)
            }
            GLES20.glDepthMask(true)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }

        private fun clearSpicaResources() {
            for (mp in spicaMatPrograms) {
                if (mp.progId != 0 && mp.progId != programId) {
                    GLES20.glDeleteProgram(mp.progId)
                }
            }
            spicaMatPrograms.clear()
            spicaGlMeshes.clear()
            activeSpicaScene = null
        }

        private fun uploadSpicaScene(scene: SpicaCgfxParser.SpicaCgfxScene?) {
            if (glTextures.isNotEmpty()) {
                GLES20.glDeleteTextures(glTextures.size, glTextures.toIntArray(), 0)
                glTextures.clear()
            }
            clearSpicaResources()
            glMeshes.clear()
            activeScene = null
            activeSpicaScene = scene
            if (scene == null) return

            for (tex in scene.textures) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                val texId = ids[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                val rgbaBytes = ByteBuffer.allocateDirect(tex.width * tex.height * 4)
                for (argb in tex.rgbaPixels) {
                    rgbaBytes.put(((argb ushr 16) and 0xFF).toByte())
                    rgbaBytes.put(((argb ushr 8) and 0xFF).toByte())
                    rgbaBytes.put((argb and 0xFF).toByte())
                    rgbaBytes.put(((argb ushr 24) and 0xFF).toByte())
                }
                rgbaBytes.flip()
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    tex.width, tex.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgbaBytes
                )
                glTextures.add(texId)
            }

            val spicaVs = """
                uniform mat4 uMvp;
                uniform vec2 uUvScale0;
                uniform vec2 uUvTrans0;
                uniform vec2 uUvScale1;
                uniform vec2 uUvTrans1;
                attribute vec3 aPos;
                attribute vec2 aUv0;
                attribute vec2 aUv1;
                attribute vec4 aCol;
                varying vec2 vUv0;
                varying vec2 vUv1;
                varying vec4 vCol;
                void main() {
                    vUv0 = aUv0 * uUvScale0 + uUvTrans0;
                    vUv1 = aUv1 * uUvScale1 + uUvTrans1;
                    vCol = aCol;
                    gl_Position = uMvp * vec4(aPos, 1.0);
                }
            """.trimIndent()

            for (mat in scene.materials) {
                val fsCode = SpicaCgfxParser.generateFragmentShader(mat)
                val prog = createProgramChecked(spicaVs, fsCode).let { if (it != 0) it else programId }
                spicaMatPrograms.add(
                    SpicaMaterialProgram(
                        mat = mat,
                        progId = prog,
                        aPosLoc = GLES20.glGetAttribLocation(prog, "aPos"),
                        aUv0Loc = GLES20.glGetAttribLocation(prog, "aUv0"),
                        aUv1Loc = GLES20.glGetAttribLocation(prog, "aUv1"),
                        aColLoc = GLES20.glGetAttribLocation(prog, "aCol"),
                        uMvpLoc = GLES20.glGetUniformLocation(prog, "uMvp"),
                        uUvScale0Loc = GLES20.glGetUniformLocation(prog, "uUvScale0"),
                        uUvTrans0Loc = GLES20.glGetUniformLocation(prog, "uUvTrans0"),
                        uUvScale1Loc = GLES20.glGetUniformLocation(prog, "uUvScale1"),
                        uUvTrans1Loc = GLES20.glGetUniformLocation(prog, "uUvTrans1"),
                        uTexLocs = intArrayOf(
                            GLES20.glGetUniformLocation(prog, "uTex0"),
                            GLES20.glGetUniformLocation(prog, "uTex1"),
                            GLES20.glGetUniformLocation(prog, "uTex2")
                        ),
                        uHasTexLocs = intArrayOf(
                            GLES20.glGetUniformLocation(prog, "uHasTex0"),
                            GLES20.glGetUniformLocation(prog, "uHasTex1"),
                            GLES20.glGetUniformLocation(prog, "uHasTex2")
                        ),
                        uEmissionLoc = GLES20.glGetUniformLocation(prog, "uEmission"),
                        uAmbientLoc = GLES20.glGetUniformLocation(prog, "uAmbient"),
                        uDiffuseLoc = GLES20.glGetUniformLocation(prog, "uDiffuse"),
                        uConstLocs = IntArray(6) { c -> GLES20.glGetUniformLocation(prog, "uConstant$c") }
                    )
                )
            }

            val sortedMeshes = scene.meshes.sortedBy {
                scene.materials.getOrNull(it.materialIndex)?.renderLayer ?: 0
            }
            for (m in sortedMeshes) {
                val vb = ByteBuffer.allocateDirect(m.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                vb.put(m.vertices).position(0)
                val ib = ByteBuffer.allocateDirect(m.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
                ib.put(m.indices).position(0)
                val layer = scene.materials.getOrNull(m.materialIndex)?.renderLayer ?: 0
                spicaGlMeshes.add(SpicaGlMesh(vb, ib, m.indices.size, m.materialIndex, layer))
            }
        }

        private fun createProgramChecked(vsCode: String, fsCode: String): Int {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsCode)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsCode)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            if (linked[0] == 0) {
                GLES20.glDeleteProgram(prog)
                return 0
            }
            return prog
        }

        private fun drawFrame() {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val scene = activeScene ?: return
            if (glMeshes.isEmpty() || programId == 0) return

            val elapsedSec = (System.currentTimeMillis() - animStartTimeMs) / 1000f
            val popT = (elapsedSec / 0.35f).coerceIn(0f, 1f)
            val popScale = 0.72f + 0.28f * (1f - (1f - popT) * (1f - popT))

            val cx = (scene.minX + scene.maxX) * 0.5f
            val cy = (scene.minY + scene.maxY) * 0.5f
            val cz = (scene.minZ + scene.maxZ) * 0.5f
            val spanX = (scene.maxX - scene.minX).coerceAtLeast(0.01f)
            val spanY = (scene.maxY - scene.minY).coerceAtLeast(0.01f)
            val spanZ = (scene.maxZ - scene.minZ).coerceAtLeast(0.01f)
            val maxSpan = max(spanX, max(spanY, spanZ))
            val normScale = (1.0f / maxSpan) * popScale

            val halfW = (spanX * 0.5f) * normScale
            val halfH = (spanY * 0.5f) * normScale
            val halfD = (spanZ * 0.5f) * normScale

            // 依据 38° 垂直视角与当前宽高比，自适应计算相机距离：
            // halfH / visibleHalfH = 0.86f -> 主体占据舞台约 86% 高度、88% 宽度
            val tanHalfFov = 0.344328f // tan(19 deg)
            val aspect = (surfaceWidth.coerceAtLeast(1).toFloat() / surfaceHeight.coerceAtLeast(1).toFloat()).coerceAtLeast(0.5f)
            val distForY = (halfH / (0.86f * tanHalfFov)) + halfD * 0.65f
            val distForX = (halfW / (0.88f * tanHalfFov * aspect)) + halfD * 0.65f
            val camDist = max(distForY, distForX).coerceIn(0.65f, 4.5f)

            val swayYaw = sin(elapsedSec * 1.15f) * 14f + parallaxX * 10f
            val swayPitch = cos(elapsedSec * 0.85f) * 4.5f + parallaxY * 6f
            val floatY = sin(elapsedSec * 1.7f) * 0.022f

            Matrix.setLookAtM(viewMatrix, 0, 0f, camDist * 0.05f, camDist, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, 0f, floatY, 0f)
            Matrix.rotateM(modelMatrix, 0, swayPitch, 1f, 0f, 0f)
            Matrix.rotateM(modelMatrix, 0, swayYaw, 0f, 1f, 0f)
            Matrix.scaleM(modelMatrix, 0, normScale, normScale, normScale)
            Matrix.translateM(modelMatrix, 0, -cx, -cy, -cz)

            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

            GLES20.glUseProgram(programId)
            GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)

            for (mesh in glMeshes) {
                val texId = glTextures.getOrNull(mesh.texIndex) ?: 0
                if (texId != 0) {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                    GLES20.glUniform1i(uTexLoc, 0)
                    GLES20.glUniform1f(uHasTexLoc, 1f)
                } else {
                    GLES20.glUniform1f(uHasTexLoc, 0f)
                }

                val stride = 9 * 4
                mesh.vbo.position(0)
                GLES20.glEnableVertexAttribArray(aPosLoc)
                GLES20.glVertexAttribPointer(aPosLoc, 3, GLES20.GL_FLOAT, false, stride, mesh.vbo)

                mesh.vbo.position(3)
                GLES20.glEnableVertexAttribArray(aUvLoc)
                GLES20.glVertexAttribPointer(aUvLoc, 2, GLES20.GL_FLOAT, false, stride, mesh.vbo)

                mesh.vbo.position(5)
                GLES20.glEnableVertexAttribArray(aColLoc)
                GLES20.glVertexAttribPointer(aColLoc, 4, GLES20.GL_FLOAT, false, stride, mesh.vbo)

                mesh.ibo.position(0)
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, mesh.ibo)
            }
        }

        private fun uploadScene(scene: CgfxParser.CgfxScene?) {
            if (glTextures.isNotEmpty()) {
                GLES20.glDeleteTextures(glTextures.size, glTextures.toIntArray(), 0)
                glTextures.clear()
            }
            clearSpicaResources()
            glMeshes.clear()
            activeScene = scene
            if (scene == null) return

            for (tex in scene.textures) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                val texId = ids[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)

                val rgbaBytes = ByteBuffer.allocateDirect(tex.width * tex.height * 4)
                for (argb in tex.rgbaPixels) {
                    rgbaBytes.put(((argb ushr 16) and 0xFF).toByte())
                    rgbaBytes.put(((argb ushr 8) and 0xFF).toByte())
                    rgbaBytes.put((argb and 0xFF).toByte())
                    rgbaBytes.put(((argb ushr 24) and 0xFF).toByte())
                }
                rgbaBytes.flip()
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    tex.width, tex.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgbaBytes
                )
                glTextures.add(texId)
            }

            for (m in scene.meshes) {
                val vb = ByteBuffer.allocateDirect(m.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                vb.put(m.vertices).position(0)
                val ib = ByteBuffer.allocateDirect(m.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
                ib.put(m.indices).position(0)
                glMeshes.add(GlMesh(vb, ib, m.indices.size, m.textureIndex))
            }
        }

        private fun createProgram(vsCode: String, fsCode: String): Int {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsCode)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsCode)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            return prog
        }

        private fun compileShader(type: Int, code: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
            return shader
        }

        private fun releaseGlAndEgl() {
            clearSpicaResources()
            if (glTextures.isNotEmpty()) {
                GLES20.glDeleteTextures(glTextures.size, glTextures.toIntArray(), 0)
                glTextures.clear()
            }
            if (programId != 0) {
                GLES20.glDeleteProgram(programId)
                programId = 0
            }
            egl?.let { e ->
                e.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
                if (eglSurface !== EGL10.EGL_NO_SURFACE) e.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext !== EGL10.EGL_NO_CONTEXT) e.eglDestroyContext(eglDisplay, eglContext)
                e.eglTerminate(eglDisplay)
            }
        }
    }
}
