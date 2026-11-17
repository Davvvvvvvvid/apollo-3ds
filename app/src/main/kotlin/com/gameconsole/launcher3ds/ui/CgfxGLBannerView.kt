package com.gameconsole.launcher3ds.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.View
import android.view.ViewOutlineProvider
import com.gameconsole.launcher3ds.parser.CgfxParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 3DS 原版 CGFX 上屏 3D 横幅实时 OpenGL ES 2.0 渲染视图
 */
class CgfxGLBannerView(context: Context) : GLSurfaceView(context) {

    @Volatile
    private var pendingScene: CgfxParser.CgfxScene? = null

    @Volatile
    private var sceneDirty: Boolean = false

    @Volatile
    var parallaxX: Float = 0f

    @Volatile
    var parallaxY: Float = 0f

    @Volatile
    private var animStartTimeMs: Long = System.currentTimeMillis()

    var cornerRadiusPx: Float = 14f
        set(value) {
            field = value
            invalidateOutline()
        }

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        setRenderer(BannerRenderer())
        renderMode = RENDERMODE_CONTINUOUSLY

        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
    }

    fun setScene(scene: CgfxParser.CgfxScene?) {
        pendingScene = scene
        sceneDirty = true
        animStartTimeMs = System.currentTimeMillis()
    }

    private data class GlMesh(
        val vbo: FloatBuffer,
        val ibo: ShortBuffer,
        val indexCount: Int,
        val texIndex: Int
    )

    private inner class BannerRenderer : Renderer {
        private var programId = 0
        private var aPosLoc = 0
        private var aUvLoc = 0
        private var aColLoc = 0
        private var uMvpLoc = 0
        private var uTexLoc = 0
        private var uHasTexLoc = 0

        private var activeScene: CgfxParser.CgfxScene? = null
        private val glTextures = ArrayList<Int>()
        private val glMeshes = ArrayList<GlMesh>()

        private val projMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val modelMatrix = FloatArray(16)
        private val vpMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.05f, 0.08f, 0.14f, 1.0f)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

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
                    gl_FragColor = outCol;
                }
            """.trimIndent()

            programId = createProgram(vs, fs)
            aPosLoc = GLES20.glGetAttribLocation(programId, "aPos")
            aUvLoc = GLES20.glGetAttribLocation(programId, "aUv")
            aColLoc = GLES20.glGetAttribLocation(programId, "aCol")
            uMvpLoc = GLES20.glGetUniformLocation(programId, "uMvp")
            uTexLoc = GLES20.glGetUniformLocation(programId, "uTex")
            uHasTexLoc = GLES20.glGetUniformLocation(programId, "uHasTex")

            sceneDirty = true
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val aspect = width.toFloat() / max(1, height).toFloat()
            Matrix.perspectiveM(projMatrix, 0, 38f, aspect, 0.1f, 200f)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (sceneDirty) {
                sceneDirty = false
                uploadScene(pendingScene)
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val scene = activeScene ?: return
            if (glMeshes.isEmpty() || programId == 0) return

            val elapsedSec = (System.currentTimeMillis() - animStartTimeMs) / 1000f
            // 原机级入场弹性缩放 + 柔和悬浮与 3D 视差摆动
            val popT = (elapsedSec / 0.35f).coerceIn(0f, 1f)
            val popScale = 0.72f + 0.28f * (1f - (1f - popT) * (1f - popT))

            val cx = (scene.minX + scene.maxX) * 0.5f
            val cy = (scene.minY + scene.maxY) * 0.5f
            val cz = (scene.minZ + scene.maxZ) * 0.5f
            val spanX = (scene.maxX - scene.minX).coerceAtLeast(0.01f)
            val spanY = (scene.maxY - scene.minY).coerceAtLeast(0.01f)
            val spanZ = (scene.maxZ - scene.minZ).coerceAtLeast(0.01f)
            val maxSpan = max(spanX, max(spanY, spanZ))
            val normScale = (2.15f / maxSpan) * popScale

            val swayYaw = sin(elapsedSec * 1.15f) * 14f + parallaxX * 10f
            val swayPitch = cos(elapsedSec * 0.85f) * 4.5f + parallaxY * 6f
            val floatY = sin(elapsedSec * 1.7f) * 0.04f

            Matrix.setLookAtM(viewMatrix, 0, 0f, 0.15f, 4.2f, 0f, 0f, 0f, 0f, 1f, 0f)
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
    }
}
