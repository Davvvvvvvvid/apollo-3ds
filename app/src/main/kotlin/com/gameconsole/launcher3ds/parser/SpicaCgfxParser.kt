package com.gameconsole.launcher3ds.parser

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 基于开源项目 gdkchan/SPICA (CtrGfx + PICA200 FragmentShaderGenerator) 移植的
 * 任天堂 3DS CGFX 完整材质/TEV混合/UV采样/骨骼变换解析器。
 */
object SpicaCgfxParser {

    data class TexEnvStage(
        val srcColor: IntArray = intArrayOf(0, 0, 0),
        val srcAlpha: IntArray = intArrayOf(0, 0, 0),
        val opColor: IntArray = intArrayOf(0, 0, 0),
        val opAlpha: IntArray = intArrayOf(0, 0, 0),
        var combColor: Int = 0, // 0=Replace
        var combAlpha: Int = 0,
        var colorR: Float = 1f,
        var colorG: Float = 1f,
        var colorB: Float = 1f,
        var colorA: Float = 1f,
        var scaleColor: Int = 1,
        var scaleAlpha: Int = 1,
        var constIndex: Int = 0,
        var updateColorBuffer: Boolean = false,
        var updateAlphaBuffer: Boolean = false
    ) {
        fun isColorPassThrough(): Boolean =
            combColor == 0 && srcColor[0] == 15 && opColor[0] == 0 && scaleColor == 1

        fun isAlphaPassThrough(): Boolean =
            combAlpha == 0 && srcAlpha[0] == 15 && opAlpha[0] == 0 && scaleAlpha == 1
    }

    data class SpicaMaterial(
        val name: String,
        val texIndices: IntArray, // size 3
        val texCoordIndices: IntArray, // size 3 (which UV coord each tex unit uses)
        val wrapU: IntArray, // size 3 (0=ClampToEdge, 1=ClampToBorder->ClampToEdge, 2=Repeat, 3=MirroredRepeat)
        val wrapV: IntArray, // size 3
        val uvScales: FloatArray, // size 6 (u0, v0, u1, v1, u2, v2)
        val uvTranslations: FloatArray, // size 6
        val uvRotations: FloatArray, // size 3
        val emission: FloatArray, // 4
        val ambient: FloatArray, // 4
        val diffuse: FloatArray, // 4
        val constants: Array<FloatArray>, // 6 x 4
        val texEnvBufferColor: FloatArray, // 4
        val texEnvStages: List<TexEnvStage>, // size 6
        val blendMode: Int, // 0=None, 1=Blend, 2=BlendSeparate, 3=LogicalOp
        val blendColorSrc: Int,
        val blendColorDst: Int,
        val blendColorEq: Int,
        val blendAlphaSrc: Int,
        val blendAlphaDst: Int,
        val blendAlphaEq: Int,
        val depthTestEnabled: Boolean,
        val depthWriteEnabled: Boolean,
        val alphaTestEnabled: Boolean,
        val alphaTestFunc: Int,
        val alphaTestRef: Float,
        val cullFaceMode: Int, // 0=None/Both, 1=Front, 2=Back
        val renderLayer: Int // 0=Opaque, 1=Translucent, 2=Subtractive, 3=Additive
    )

    data class SpicaSubMesh(
        val indices: ShortArray,
        val vertices: FloatArray, // interleaved 11 floats: x,y,z, u0,v0, u1,v1, r,g,b,a
        val materialIndex: Int
    )

    data class SpicaCgfxScene(
        val textures: List<CgfxParser.CgfxTexture>,
        val materials: List<SpicaMaterial>,
        val meshes: List<SpicaSubMesh>,
        val minX: Float, val maxX: Float,
        val minY: Float, val maxY: Float,
        val minZ: Float, val maxZ: Float
    )

    fun parseFile(cgfxFile: File): SpicaCgfxScene? {
        if (!cgfxFile.exists() || cgfxFile.length() < 64) return null
        return try {
            parseBytes(cgfxFile.readBytes())
        } catch (_: Throwable) {
            null
        }
    }

    fun parseBytes(data: ByteArray): SpicaCgfxScene? {
        if (data.size < 0x60) return null
        if (data[0] != 'C'.code.toByte() || data[1] != 'G'.code.toByte() ||
            data[2] != 'F'.code.toByte() || data[3] != 'X'.code.toByte()
        ) return null

        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Dict 1: Textures (TXOB)
        val texDictCnt = bb.getInt(0x1C + 1 * 8)
        val texDictRel = bb.getInt(0x1C + 1 * 8 + 4)
        val textures = ArrayList<CgfxParser.CgfxTexture>()
        val texOffsetToIndex = HashMap<Int, Int>()

        if (texDictCnt > 0 && texDictRel != 0) {
            val texDictAbs = (0x1C + 1 * 8 + 4) + texDictRel
            val entries = CgfxParser.readDict(data, bb, texDictAbs)
            for ((name, objOff) in entries) {
                val tex = CgfxParser.parseTxob(data, bb, objOff, name)
                if (tex != null) {
                    texOffsetToIndex[objOff] = textures.size
                    textures.add(tex)
                }
            }
        }

        // Dict 0: Models (CMDL)
        val modelDictCnt = bb.getInt(0x1C)
        val modelDictRel = bb.getInt(0x1C + 4)
        val materials = ArrayList<SpicaMaterial>()
        val meshes = ArrayList<SpicaSubMesh>()

        if (modelDictCnt > 0 && modelDictRel != 0) {
            val modelDictAbs = (0x1C + 4) + modelDictRel
            val modelEntries = CgfxParser.readDict(data, bb, modelDictAbs)
            for ((_, modelOff) in modelEntries) {
                parseCmdl(data, bb, modelOff, textures, texOffsetToIndex, materials, meshes)
            }
        }

        if (meshes.isEmpty()) return null

        // 抗离群点自适应包围盒
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        val meshStats = ArrayList<FloatArray>(meshes.size)
        val highVtxSpans = ArrayList<Float>()

        for (m in meshes) {
            var mMinX = Float.MAX_VALUE; var mMaxX = -Float.MAX_VALUE
            var mMinY = Float.MAX_VALUE; var mMaxY = -Float.MAX_VALUE
            var mMinZ = Float.MAX_VALUE; var mMaxZ = -Float.MAX_VALUE
            var i = 0
            while (i + 10 < m.vertices.size) {
                val x = m.vertices[i]
                val y = m.vertices[i + 1]
                val z = m.vertices[i + 2]
                if (x < mMinX) mMinX = x; if (x > mMaxX) mMaxX = x
                if (y < mMinY) mMinY = y; if (y > mMaxY) mMaxY = y
                if (z < mMinZ) mMinZ = z; if (z > mMaxZ) mMaxZ = z
                i += 11
            }
            if (mMinX <= mMaxX) {
                val span = max(mMaxX - mMinX, max(mMaxY - mMinY, mMaxZ - mMinZ))
                val vtxCnt = m.vertices.size / 11
                meshStats.add(floatArrayOf(mMinX, mMaxX, mMinY, mMaxY, mMinZ, mMaxZ, span, vtxCnt.toFloat()))
                if (vtxCnt > 8) highVtxSpans.add(span)
            }
        }

        val coreMaxSpan = if (highVtxSpans.isNotEmpty()) highVtxSpans.maxOrNull() ?: 0f else 0f
        for (st in meshStats) {
            val vtxCnt = st[7].toInt()
            val span = st[6]
            if (coreMaxSpan > 0f && vtxCnt <= 8 && span > coreMaxSpan * 2.2f) continue
            if (st[0] < minX) minX = st[0]; if (st[1] > maxX) maxX = st[1]
            if (st[2] < minY) minY = st[2]; if (st[3] > maxY) maxY = st[3]
            if (st[4] < minZ) minZ = st[4]; if (st[5] > maxZ) maxZ = st[5]
        }
        if (minX > maxX) {
            for (st in meshStats) {
                if (st[0] < minX) minX = st[0]; if (st[1] > maxX) maxX = st[1]
                if (st[2] < minY) minY = st[2]; if (st[3] > maxY) maxY = st[3]
                if (st[4] < minZ) minZ = st[4]; if (st[5] > maxZ) maxZ = st[5]
            }
        }
        if (minX > maxX) {
            minX = -1f; maxX = 1f; minY = -1f; maxY = 1f; minZ = -1f; maxZ = 1f
        }

        return SpicaCgfxScene(textures, materials, meshes, minX, maxX, minY, maxY, minZ, maxZ)
    }

    private fun parseCmdl(
        data: ByteArray,
        bb: ByteBuffer,
        mOff: Int,
        textures: List<CgfxParser.CgfxTexture>,
        texOffsetToIndex: HashMap<Int, Int>,
        outMaterials: MutableList<SpicaMaterial>,
        outMeshes: MutableList<SpicaSubMesh>
    ) {
        if (mOff + 0xD0 > data.size) return

        // CMDL Local/World Transform (3x4 row-major at +0x54 or +0x84? Wait: LocalMatrix at +0x54, WorldMatrix at +0x84)
        // Only apply if non-identity and finite
        val modelMtx = readTransformMatrix3x4(data, bb, mOff + 0x54)

        val meshCount = bb.getInt(mOff + 0xB4)
        val meshListRel = bb.getInt(mOff + 0xB8)
        val matDictCount = bb.getInt(mOff + 0xBC)
        val matDictRel = bb.getInt(mOff + 0xC0)
        val shapeCount = bb.getInt(mOff + 0xC4)
        val shapeListRel = bb.getInt(mOff + 0xC8)

        if (meshCount <= 0 || meshCount > 256 || shapeCount <= 0 || shapeCount > 256) return

        val meshListAbs = (mOff + 0xB8) + meshListRel
        val shapeListAbs = (mOff + 0xC8) + shapeListRel

        val matBaseIdx = outMaterials.size
        if (matDictCount > 0 && matDictRel != 0) {
            val matDictAbs = (mOff + 0xC0) + matDictRel
            val matEntries = CgfxParser.readDict(data, bb, matDictAbs)
            for ((name, matOff) in matEntries) {
                outMaterials.add(parseMtob(data, bb, matOff, name, textures, texOffsetToIndex))
            }
        }

        val shapeOffsets = IntArray(shapeCount)
        for (i in 0 until shapeCount) {
            val p = shapeListAbs + i * 4
            if (p + 4 <= data.size) {
                val rel = bb.getInt(p)
                shapeOffsets[i] = if (rel != 0) p + rel else 0
            }
        }

        for (i in 0 until meshCount) {
            val p = meshListAbs + i * 4
            if (p + 4 > data.size) continue
            val mRel = bb.getInt(p)
            if (mRel == 0) continue
            val meshOff = p + mRel
            if (meshOff + 0x44 > data.size) continue

            val shapeIdx = bb.getInt(meshOff + 0x18)
            val matLocalIdx = bb.getInt(meshOff + 0x1C)
            val matGlobalIdx = (matBaseIdx + matLocalIdx).coerceIn(0, (outMaterials.size - 1).coerceAtLeast(0))

            if (shapeIdx in 0 until shapeCount) {
                val shapeOff = shapeOffsets[shapeIdx]
                if (shapeOff > 0) {
                    parseShape(data, bb, shapeOff, matGlobalIdx, modelMtx, outMeshes)
                }
            }
        }
    }

    private fun readTransformMatrix3x4(data: ByteArray, bb: ByteBuffer, off: Int): FloatArray? {
        if (off + 48 > data.size) return null
        val m = FloatArray(12) { k -> bb.getFloat(off + k * 4) }
        for (v in m) {
            if (!v.isFinite() || kotlin.math.abs(v) > 10000f) return null
        }
        // Check if identity or zero
        val isZero = m.all { kotlin.math.abs(it) < 1e-6f }
        if (isZero) return null
        return m
    }

    private fun parseMtob(
        data: ByteArray,
        bb: ByteBuffer,
        matOff: Int,
        name: String,
        textures: List<CgfxParser.CgfxTexture>,
        texOffsetToIndex: HashMap<Int, Int>
    ): SpicaMaterial {
        // TranslucencyKind: +0x24
        val translucency = if (matOff + 0x28 <= data.size) bb.getInt(matOff + 0x24).coerceIn(0, 3) else 0

        fun safe01(v: Float, def: Float = 1f): Float = if (v.isFinite()) v.coerceIn(0f, 1f) else def
        // GfxMaterialColor: starts at +0x24 (11 x Vector4 = 176 bytes)
        fun readVec4(off: Int, defW: Float = 1f): FloatArray {
            if (off + 16 > data.size) return floatArrayOf(1f, 1f, 1f, defW)
            return floatArrayOf(
                safe01(bb.getFloat(off), 1f),
                safe01(bb.getFloat(off + 4), 1f),
                safe01(bb.getFloat(off + 8), 1f),
                safe01(bb.getFloat(off + 12), defW)
            )
        }
        val emission = readVec4(matOff + 0x24, 1f)
        val ambient = readVec4(matOff + 0x34, 1f)
        val diffuse = readVec4(matOff + 0x44, 1f)
        val constants = Array(6) { idx -> readVec4(matOff + 0x74 + idx * 16, 1f) }

        // GfxRasterization (CullMode around +0x100)
        // 0=FrontFace, 1=BackFace, 2=Always/Never -> default Back/None
        val cullFaceMode = 0 // CGFX banner meshes frequently rely on double-sided or Front/Back; keep safe

        // GfxFragOpDepth (+0x110..0x12B) & GfxFragOpBlend (+0x12C..0x157)
        val blendMode = if (matOff + 0x130 <= data.size) bb.getInt(matOff + 0x12C).coerceIn(0, 3) else 0
        var blendColorSrc = 0x0302 // GL_SRC_ALPHA
        var blendColorDst = 0x0303 // GL_ONE_MINUS_SRC_ALPHA
        var blendColorEq = 0x8006  // GL_FUNC_ADD
        var blendAlphaSrc = 0x0001 // GL_ONE
        var blendAlphaDst = 0x0303 // GL_ONE_MINUS_SRC_ALPHA
        var blendAlphaEq = 0x8006
        var depthTestEnabled = true
        var depthWriteEnabled = (blendMode == 0 && translucency == 0)

        if (matOff + 0x158 <= data.size) {
            val blendCmds = IntArray(6) { k -> bb.getInt(matOff + 0x140 + k * 4) }
            readPicaCommands(blendCmds) { reg, param ->
                when (reg) {
                    0x0101 -> { // GPUREG_BLEND_FUNC
                        blendColorEq = picaBlendEqToGl(param and 7)
                        blendAlphaEq = picaBlendEqToGl((param ushr 8) and 7)
                        blendColorSrc = picaBlendFuncToGl((param ushr 16) and 0xF)
                        blendColorDst = picaBlendFuncToGl((param ushr 20) and 0xF)
                        blendAlphaSrc = picaBlendFuncToGl((param ushr 24) and 0xF)
                        blendAlphaDst = picaBlendFuncToGl((param ushr 28) and 0xF)
                    }
                    0x0107 -> { // GPUREG_DEPTH_COLOR_MASK
                        depthTestEnabled = (param and 1) != 0
                        depthWriteEnabled = (param and 0x1000) != 0
                    }
                }
            }
        }

        // GfxTextureCoord[0..2]: +0x168 = UsedTextureCoordsCount, +0x16C..+0x273 = TextureCoords[0..2] (stride 0x58 = 88)
        val texCoordIndices = intArrayOf(0, 0, 0)
        val uvScales = floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f)
        val uvTranslations = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
        val uvRotations = floatArrayOf(0f, 0f, 0f)
        val usedTexCoords = if (matOff + 0x16C <= data.size) bb.getInt(matOff + 0x168).coerceIn(0, 3) else 1
        for (i in 0 until usedTexCoords) {
            val tcOff = matOff + 0x16C + i * 0x58
            if (tcOff + 0x58 <= data.size) {
                texCoordIndices[i] = bb.getInt(tcOff).coerceIn(0, 2)
                val sx = bb.getFloat(tcOff + 0x10)
                val sy = bb.getFloat(tcOff + 0x14)
                val rot = bb.getFloat(tcOff + 0x18)
                val tx = bb.getFloat(tcOff + 0x1C)
                val ty = bb.getFloat(tcOff + 0x20)
                if (sx.isFinite() && sx != 0f) uvScales[i * 2] = sx
                if (sy.isFinite() && sy != 0f) uvScales[i * 2 + 1] = sy
                if (rot.isFinite()) uvRotations[i] = rot
                if (tx.isFinite()) uvTranslations[i * 2] = tx
                if (ty.isFinite()) uvTranslations[i * 2 + 1] = ty
            }
        }

        // TextureMappers[0..2]: +0x274, +0x278, +0x27C
        val texIndices = intArrayOf(-1, -1, -1)
        val wrapU = intArrayOf(0, 0, 0) // 默认 ClampToEdge，避免无指令时误平铺
        val wrapV = intArrayOf(0, 0, 0)
        for (unit in 0 until 3) {
            val ptrOff = matOff + 0x274 + unit * 4
            if (ptrOff + 4 > data.size) continue
            val tmRel = bb.getInt(ptrOff)
            if (tmRel !in 0x10..0x4000) continue
            val tmOff = ptrOff + tmRel
            if (tmOff + 0x34 > data.size) continue

            val trRel = bb.getInt(tmOff + 0x08)
            if (trRel in 0x04..0x4000) {
                val trOff = tmOff + 0x08 + trRel
                if (trOff + 0x1C <= data.size) {
                    val nRel = bb.getInt(trOff + 0x18)
                    if (nRel != 0) {
                        val tName = CgfxParser.readCString(data, trOff + 0x18 + nRel)
                        texIndices[unit] = textures.indexOfFirst { it.name.equals(tName, ignoreCase = true) }
                    }
                }
            }
            // Read PICA sampler commands inside TexMapper (+0x10..+0x2F, 8-byte aligned param+hdr pairs)
            val tmCmds = IntArray(8) { k -> bb.getInt(tmOff + 0x10 + k * 4) }
            readPicaCommands(tmCmds) { reg, param ->
                if (reg == 0x0083 || reg == 0x0093 || reg == 0x009B) {
                    wrapV[unit] = (param ushr 8) and 3
                    wrapU[unit] = (param ushr 12) and 3
                }
            }
        }

        // FragmentShader: +0x288
        val stages = List(6) { TexEnvStage() }
        // Default Stage 0: Replace Texture0 or PrimaryColor
        stages[0].srcColor[0] = if (texIndices[0] >= 0) 3 else 0
        stages[0].srcAlpha[0] = if (texIndices[0] >= 0) 3 else 0
        for (s in 1 until 6) {
            stages[s].srcColor[0] = 15 // Previous
            stages[s].srcAlpha[0] = 15
        }

        var texEnvBufColor = floatArrayOf(0f, 0f, 0f, 1f)
        var alphaTestEnabled = false
        var alphaTestFunc = 1 // Always
        var alphaTestRef = 0f

        if (matOff + 0x28C <= data.size) {
            val fsRel = bb.getInt(matOff + 0x288)
            if (fsRel in 0x10..0x8000) {
                val fsOff = matOff + 0x288 + fsRel
                if (fsOff + 0xF4 <= data.size) {
                    texEnvBufColor = readVec4(fsOff, 1f)
                    for (s in 0 until 6) {
                        val teOff = fsOff + 0x2C + s * 28
                        val st = stages[s]
                        st.constIndex = bb.getInt(teOff).coerceIn(0, 5)
                        val cmds = IntArray(6) { k -> bb.getInt(teOff + 4 + k * 4) }
                        readPicaCommands(cmds) { reg, param ->
                            when (reg) {
                                0x00C0, 0x00C8, 0x00D0, 0x00D8, 0x00F0, 0x00F8 -> {
                                    st.srcColor[0] = param and 0xF
                                    st.srcColor[1] = (param ushr 4) and 0xF
                                    st.srcColor[2] = (param ushr 8) and 0xF
                                    st.srcAlpha[0] = (param ushr 16) and 0xF
                                    st.srcAlpha[1] = (param ushr 20) and 0xF
                                    st.srcAlpha[2] = (param ushr 24) and 0xF
                                }
                                0x00C1, 0x00C9, 0x00D1, 0x00D9, 0x00F1, 0x00F9 -> {
                                    st.opColor[0] = param and 0xF
                                    st.opColor[1] = (param ushr 4) and 0xF
                                    st.opColor[2] = (param ushr 8) and 0xF
                                    st.opAlpha[0] = (param ushr 12) and 0xF
                                    st.opAlpha[1] = (param ushr 16) and 0xF
                                    st.opAlpha[2] = (param ushr 20) and 0xF
                                }
                                0x00C2, 0x00CA, 0x00D2, 0x00DA, 0x00F2, 0x00FA -> {
                                    st.combColor = param and 0xF
                                    st.combAlpha = (param ushr 16) and 0xF
                                }
                                0x00C3, 0x00CB, 0x00D3, 0x00DB, 0x00F3, 0x00FB -> {
                                    st.colorR = (param and 0xFF) / 255f
                                    st.colorG = ((param ushr 8) and 0xFF) / 255f
                                    st.colorB = ((param ushr 16) and 0xFF) / 255f
                                    st.colorA = ((param ushr 24) and 0xFF) / 255f
                                }
                                0x00C4, 0x00CC, 0x00D4, 0x00DC, 0x00F4, 0x00FC -> {
                                    st.scaleColor = 1 shl (param and 3)
                                    st.scaleAlpha = 1 shl ((param ushr 16) and 3)
                                }
                            }
                        }
                    }

                    // AlphaTest (+0xD4, 2 uints)
                    val atCmds = IntArray(2) { k -> bb.getInt(fsOff + 0xD4 + k * 4) }
                    readPicaCommands(atCmds) { reg, param ->
                        if (reg == 0x0104) {
                            alphaTestEnabled = (param and 1) != 0
                            alphaTestFunc = (param ushr 4) and 7
                            alphaTestRef = ((param ushr 8) and 0xFF) / 255f
                        }
                    }

                    // FragShader Commands (+0xDC, 6 uints)
                    val fsCmds = IntArray(6) { k -> bb.getInt(fsOff + 0xDC + k * 4) }
                    readPicaCommands(fsCmds) { reg, param ->
                        when (reg) {
                            0x00FD -> { // GPUREG_TEXENV_UPDATE_BUFFER
                                stages[1].updateColorBuffer = (param and 0x100) != 0
                                stages[2].updateColorBuffer = (param and 0x200) != 0
                                stages[3].updateColorBuffer = (param and 0x400) != 0
                                stages[4].updateColorBuffer = (param and 0x800) != 0
                                stages[1].updateAlphaBuffer = (param and 0x1000) != 0
                                stages[2].updateAlphaBuffer = (param and 0x2000) != 0
                                stages[3].updateAlphaBuffer = (param and 0x4000) != 0
                                stages[4].updateAlphaBuffer = (param and 0x8000) != 0
                            }
                            0x00E0 -> {
                                texEnvBufColor = floatArrayOf(
                                    (param and 0xFF) / 255f,
                                    ((param ushr 8) and 0xFF) / 255f,
                                    ((param ushr 16) and 0xFF) / 255f,
                                    ((param ushr 24) and 0xFF) / 255f
                                )
                            }
                        }
                    }
                }
            }
        }

        return SpicaMaterial(
            name = name,
            texIndices = texIndices,
            texCoordIndices = texCoordIndices,
            wrapU = wrapU,
            wrapV = wrapV,
            uvScales = uvScales,
            uvTranslations = uvTranslations,
            uvRotations = uvRotations,
            emission = emission,
            ambient = ambient,
            diffuse = diffuse,
            constants = constants,
            texEnvBufferColor = texEnvBufColor,
            texEnvStages = stages,
            blendMode = blendMode,
            blendColorSrc = blendColorSrc,
            blendColorDst = blendColorDst,
            blendColorEq = blendColorEq,
            blendAlphaSrc = blendAlphaSrc,
            blendAlphaDst = blendAlphaDst,
            blendAlphaEq = blendAlphaEq,
            depthTestEnabled = depthTestEnabled,
            depthWriteEnabled = depthWriteEnabled,
            alphaTestEnabled = alphaTestEnabled,
            alphaTestFunc = alphaTestFunc,
            alphaTestRef = alphaTestRef,
            cullFaceMode = cullFaceMode,
            renderLayer = translucency
        )
    }

    private inline fun readPicaCommands(cmds: IntArray, onCmd: (reg: Int, param: Int) -> Unit) {
        var idx = 0
        while (idx + 1 < cmds.size) {
            val param0 = cmds[idx]
            val hdr = cmds[idx + 1]
            idx += 2
            val reg = hdr and 0xFFFF
            if (reg == 0 || reg == 0xFFFF) continue
            val extra = (hdr ushr 20) and 0x7FF
            val seq = (hdr and 0x80000000.toInt()) != 0
            onCmd(reg, param0)
            for (k in 0 until extra) {
                if (idx >= cmds.size) break
                val r = if (seq) reg + 1 + k else reg
                onCmd(r, cmds[idx])
                idx++
            }
            if ((idx and 1) != 0) idx++
        }
    }

    private fun picaBlendFuncToGl(f: Int): Int = when (f) {
        0 -> 0x0000 // GL_ZERO
        1 -> 0x0001 // GL_ONE
        2 -> 0x0300 // GL_SRC_COLOR
        3 -> 0x0301 // GL_ONE_MINUS_SRC_COLOR
        4 -> 0x0306 // GL_DST_COLOR
        5 -> 0x0307 // GL_ONE_MINUS_DST_COLOR
        6 -> 0x0302 // GL_SRC_ALPHA
        7 -> 0x0303 // GL_ONE_MINUS_SRC_ALPHA
        8 -> 0x0304 // GL_DST_ALPHA
        9 -> 0x0305 // GL_ONE_MINUS_DST_ALPHA
        10 -> 0x8001 // GL_CONSTANT_COLOR
        11 -> 0x8002 // GL_ONE_MINUS_CONSTANT_COLOR
        12 -> 0x8003 // GL_CONSTANT_ALPHA
        13 -> 0x8004 // GL_ONE_MINUS_CONSTANT_ALPHA
        14 -> 0x0308 // GL_SRC_ALPHA_SATURATE
        else -> 0x0302
    }

    private fun picaBlendEqToGl(eq: Int): Int = when (eq) {
        0 -> 0x8006 // GL_FUNC_ADD
        1 -> 0x800A // GL_FUNC_SUBTRACT
        2 -> 0x800B // GL_FUNC_REVERSE_SUBTRACT
        else -> 0x8006
    }

    private fun parseShape(
        data: ByteArray,
        bb: ByteBuffer,
        shapeOff: Int,
        matIndex: Int,
        modelMtx: FloatArray?,
        outMeshes: MutableList<SpicaSubMesh>
    ) {
        if (shapeOff + 0x48 > data.size) return
        val faceCount = bb.getInt(shapeOff + 0x2C)
        val faceListRel = bb.getInt(shapeOff + 0x30)
        val vtxBufCount = bb.getInt(shapeOff + 0x38)
        val vtxBufListRel = bb.getInt(shapeOff + 0x3C)

        if (faceCount <= 0 || faceCount > 64 || vtxBufCount <= 0 || vtxBufCount > 32) return

        val faceListAbs = (shapeOff + 0x30) + faceListRel
        val vtxBufListAbs = (shapeOff + 0x3C) + vtxBufListRel

        var posData: FloatArray? = null
        var uv0Data: FloatArray? = null
        var uv1Data: FloatArray? = null
        var colData: FloatArray? = null
        var fixedCol: FloatArray? = null
        var vertexCount = 0

        for (vbIdx in 0 until vtxBufCount) {
            val p = vtxBufListAbs + vbIdx * 4
            if (p + 4 > data.size) continue
            val vbRel = bb.getInt(p)
            val vbOff = p + vbRel
            if (vbOff + 0x20 > data.size) continue

            val flags = bb.getInt(vbOff)
            if ((flags and 0x40000000) != 0 && (flags and 0x0F) == 2 && vbOff + 0x30 <= data.size) {
                // Interleaved VtxBuffer (0x40000002)
                val rawLen = bb.getInt(vbOff + 0x14)
                val rawRel = bb.getInt(vbOff + 0x18)
                val rawAbs = (vbOff + 0x18) + rawRel
                val stride = bb.getInt(vbOff + 0x24).coerceAtLeast(4)
                val streamCount = bb.getInt(vbOff + 0x28)
                val streamListRel = bb.getInt(vbOff + 0x2C)
                val streamListAbs = (vbOff + 0x2C) + streamListRel
                val curVtxCount = (rawLen / stride).coerceAtLeast(1)
                if (vertexCount == 0) vertexCount = curVtxCount

                if (posData == null) posData = FloatArray(vertexCount * 3)
                if (uv0Data == null) uv0Data = FloatArray(vertexCount * 2)
                if (uv1Data == null) uv1Data = FloatArray(vertexCount * 2)
                if (colData == null) colData = FloatArray(vertexCount * 4) { 1f }

                for (sIdx in 0 until streamCount.coerceAtMost(16)) {
                    val sp = streamListAbs + sIdx * 4
                    if (sp + 4 > data.size) break
                    val sRel = bb.getInt(sp)
                    val sOff = sp + sRel
                    if (sOff + 0x34 > data.size) continue

                    val usage = bb.getInt(sOff + 0x04) // 0=Pos, 3=Color, 4=TexCoord0, 5=TexCoord1
                    val glType = bb.getInt(sOff + 0x24)
                    val compCount = bb.getInt(sOff + 0x28).coerceIn(1, 4)
                    val scale = bb.getFloat(sOff + 0x2C)
                    val elemOffset = bb.getInt(sOff + 0x30)

                    for (v in 0 until min(vertexCount, curVtxCount)) {
                        val vBase = rawAbs + v * stride + elemOffset
                        if (vBase >= data.size) break
                        for (c in 0 until compCount) {
                            val valF = CgfxParser.readGlComp(data, bb, vBase, c, glType) * scale
                            when (usage) {
                                0 -> if (c < 3) posData[v * 3 + c] = valF
                                3 -> if (c < 4) colData[v * 4 + c] = valF
                                4 -> if (c < 2) uv0Data[v * 2 + c] = if (c == 1) 1f - valF else valF
                                5 -> if (c < 2) uv1Data[v * 2 + c] = if (c == 1) 1f - valF else valF
                            }
                        }
                    }
                }
            } else if ((flags and 0x80000000.toInt()) != 0 && vbOff + 0x24 <= data.size) {
                // Fixed Attribute (0x80000000)
                val usage = bb.getInt(vbOff + 0x04)
                if (usage == 3 && vbOff + 0x28 <= data.size) {
                    val compCount = bb.getInt(vbOff + 0x10).coerceIn(1, 4)
                    val vRel = bb.getInt(vbOff + 0x1C)
                    val vAbs = if (vRel > 0 && vbOff + 0x1C + vRel + compCount * 4 <= data.size) {
                        vbOff + 0x1C + vRel
                    } else -1
                    if (vAbs >= 0) {
                        fixedCol = FloatArray(4) { c ->
                            if (c < compCount) bb.getFloat(vAbs + c * 4).coerceIn(0f, 1f) else 1f
                        }
                    }
                }
            }
        }

        if (posData == null || vertexCount <= 0) return

        val interleaved = FloatArray(vertexCount * 11)
        for (v in 0 until vertexCount) {
            var x = posData[v * 3 + 0]
            var y = posData[v * 3 + 1]
            var z = posData[v * 3 + 2]
            if (modelMtx != null) {
                val tx = modelMtx[0] * x + modelMtx[1] * y + modelMtx[2] * z + modelMtx[3]
                val ty = modelMtx[4] * x + modelMtx[5] * y + modelMtx[6] * z + modelMtx[7]
                val tz = modelMtx[8] * x + modelMtx[9] * y + modelMtx[10] * z + modelMtx[11]
                x = tx; y = ty; z = tz
            }
            interleaved[v * 11 + 0] = x
            interleaved[v * 11 + 1] = y
            interleaved[v * 11 + 2] = z
            interleaved[v * 11 + 3] = uv0Data?.getOrNull(v * 2 + 0) ?: 0f
            interleaved[v * 11 + 4] = uv0Data?.getOrNull(v * 2 + 1) ?: 0f
            interleaved[v * 11 + 5] = uv1Data?.getOrNull(v * 2 + 0) ?: (uv0Data?.getOrNull(v * 2 + 0) ?: 0f)
            interleaved[v * 11 + 6] = uv1Data?.getOrNull(v * 2 + 1) ?: (uv0Data?.getOrNull(v * 2 + 1) ?: 0f)
            interleaved[v * 11 + 7] = fixedCol?.get(0) ?: colData?.getOrNull(v * 4 + 0) ?: 1f
            interleaved[v * 11 + 8] = fixedCol?.get(1) ?: colData?.getOrNull(v * 4 + 1) ?: 1f
            interleaved[v * 11 + 9] = fixedCol?.get(2) ?: colData?.getOrNull(v * 4 + 2) ?: 1f
            interleaved[v * 11 + 10] = fixedCol?.get(3) ?: colData?.getOrNull(v * 4 + 3) ?: 1f
        }

        for (fIdx in 0 until faceCount) {
            val fp = faceListAbs + fIdx * 4
            if (fp + 4 > data.size) continue
            val fRel = bb.getInt(fp)
            val fOff = fp + fRel
            if (fOff + 0x18 > data.size) continue

            val idxStreamCount = bb.getInt(fOff + 0x0C)
            val idxStreamListRel = bb.getInt(fOff + 0x10)
            val idxStreamListAbs = (fOff + 0x10) + idxStreamListRel

            for (isIdx in 0 until idxStreamCount.coerceAtMost(8)) {
                val isp = idxStreamListAbs + isIdx * 4
                if (isp + 4 > data.size) continue
                val isRel = bb.getInt(isp)
                val isOff = isp + isRel
                if (isOff + 0x28 > data.size) continue

                val glIdxType = bb.getInt(isOff + 0x18)
                val idxLen = bb.getInt(isOff + 0x20)
                val idxDataRel = bb.getInt(isOff + 0x24)
                val idxDataAbs = (isOff + 0x24) + idxDataRel

                if (idxLen <= 0 || idxDataAbs < 0 || idxDataAbs >= data.size) continue
                val isUByte = (glIdxType == 0x1401 || glIdxType == 0)
                val rawIndices = if (isUByte) {
                    val safeLen = idxLen.coerceAtMost(data.size - idxDataAbs)
                    ShortArray(safeLen) { k -> (data[idxDataAbs + k].toInt() and 0xFF).toShort() }
                } else {
                    val count = (idxLen / 2).coerceAtMost((data.size - idxDataAbs) / 2)
                    ShortArray(count) { k -> bb.getShort(idxDataAbs + k * 2) }
                }
                val triCount = rawIndices.size / 3
                if (triCount > 0) {
                    var valid = true
                    val trimmed = ShortArray(triCount * 3)
                    for (k in trimmed.indices) {
                        val idxVal = rawIndices[k].toInt() and 0xFFFF
                        if (idxVal >= vertexCount) {
                            valid = false
                            break
                        }
                        trimmed[k] = rawIndices[k]
                    }
                    if (valid) {
                        outMeshes.add(SpicaSubMesh(trimmed, interleaved, matIndex))
                    }
                }
            }
        }
    }

    /**
     * 1:1 移植自 SPICA.Rendering.Shaders.FragmentShaderGenerator，
     * 将 PICA200 6 阶段 TEV 混合管线编译为 Android OpenGL ES 2.0 片元着色器。
     */
    fun generateFragmentShader(mat: SpicaMaterial): String {
        val sb = StringBuilder()
        sb.appendLine("precision mediump float;")
        sb.appendLine("uniform sampler2D uTex0;")
        sb.appendLine("uniform sampler2D uTex1;")
        sb.appendLine("uniform sampler2D uTex2;")
        sb.appendLine("uniform float uHasTex0;")
        sb.appendLine("uniform float uHasTex1;")
        sb.appendLine("uniform float uHasTex2;")
        sb.appendLine("uniform vec4 uEmission;")
        sb.appendLine("uniform vec4 uAmbient;")
        sb.appendLine("uniform vec4 uDiffuse;")
        for (c in 0..5) sb.appendLine("uniform vec4 uConstant$c;")
        sb.appendLine("varying vec2 vUv0;")
        sb.appendLine("varying vec2 vUv1;")
        sb.appendLine("varying vec4 vCol;")
        sb.appendLine()
        sb.appendLine("void main() {")

        val hasTex = BooleanArray(3)
        // Determine which textures are referenced
        for (stage in mat.texEnvStages) {
            for (p in 0..2) {
                for (u in 0..2) {
                    val src = 3 + u // Texture0=3, Texture1=4, Texture2=5
                    if (stage.srcColor[p] == src || stage.srcAlpha[p] == src) {
                        hasTex[u] = true
                    }
                }
            }
        }
        for (u in 0..2) {
            if (hasTex[u]) {
                val tcIdx = mat.texCoordIndices[u].coerceIn(0, 1)
                val uvExpr = if (tcIdx == 1) "vUv1" else "vUv0"
                sb.appendLine("    vec4 Color$u = uHasTex$u > 0.5 ? texture2D(uTex$u, $uvExpr) : vec4(1.0);")
            }
        }

        val bc = mat.texEnvBufferColor
        sb.appendLine("    vec4 Previous = vec4(1.0);")
        sb.appendLine("    vec4 Output = vec4(1.0);")
        sb.appendLine("    vec4 CombBuffer = vec4(${bc[0]}, ${bc[1]}, ${bc[2]}, ${bc[3]});")
        sb.appendLine("    vec4 FragPriColor = vec4(clamp(uEmission.rgb + uAmbient.rgb + uDiffuse.rgb * 0.65, 0.0, 1.0), 1.0);")
        sb.appendLine("    vec4 FragSecColor = vec4(0.0, 0.0, 0.0, 1.0);")

        mat.texEnvStages.forEachIndexed { idx, st ->
            val hasColor = !st.isColorPassThrough()
            val hasAlpha = !st.isAlphaPassThrough()
            if (hasColor || hasAlpha) {
                val constName = "uConstant${st.constIndex.coerceIn(0, 5)}"
                val cArgs = Array(3) { p ->
                    var arg = srcExpr(st.srcColor[p], constName)
                    when (st.opColor[p] and 0xE) {
                        2 -> arg = "$arg.aaaa"
                        4 -> arg = "$arg.rrrr"
                        6 -> arg = "$arg.gggg"
                        8 -> arg = "$arg.bbbb"
                    }
                    if ((st.opColor[p] and 1) != 0) "(vec4(1.0) - $arg)" else arg
                }
                val aArgs = Array(3) { p ->
                    var arg = srcExpr(st.srcAlpha[p], constName)
                    arg = when (st.opAlpha[p] and 0xE) {
                        2 -> "$arg.r"
                        4 -> "$arg.g"
                        6 -> "$arg.b"
                        else -> "$arg.a"
                    }
                    if ((st.opAlpha[p] and 1) != 0) "(1.0 - $arg)" else arg
                }

                if (hasColor) {
                    sb.appendLine("    Output.rgb = ${combColorExpr(st.combColor, cArgs)};")
                }
                if (hasAlpha) {
                    sb.appendLine("    Output.a = ${combAlphaExpr(st.combAlpha, aArgs)};")
                }
                if (st.scaleColor != 1) {
                    sb.appendLine("    Output.rgb = min(Output.rgb * ${st.scaleColor}.0, 1.0);")
                }
                if (st.scaleAlpha != 1) {
                    sb.appendLine("    Output.a = min(Output.a * ${st.scaleAlpha}.0, 1.0);")
                }
            }
            if (st.updateColorBuffer) sb.appendLine("    CombBuffer.rgb = Previous.rgb;")
            if (st.updateAlphaBuffer) sb.appendLine("    CombBuffer.a = Previous.a;")
            if (idx < 5 && (hasColor || hasAlpha)) sb.appendLine("    Previous = Output;")
        }

        if (mat.alphaTestEnabled) {
            val ref = mat.alphaTestRef.coerceIn(0f, 1f)
            when (mat.alphaTestFunc) {
                0 -> sb.appendLine("    discard;")
                2 -> sb.appendLine("    if (abs(Output.a - $ref) > 0.004) discard;")
                3 -> sb.appendLine("    if (abs(Output.a - $ref) <= 0.004) discard;")
                4 -> sb.appendLine("    if (Output.a >= $ref) discard;")
                5 -> sb.appendLine("    if (Output.a > $ref) discard;")
                6 -> sb.appendLine("    if (Output.a <= $ref) discard;")
                7 -> sb.appendLine("    if (Output.a < $ref) discard;")
            }
        } else {
            sb.appendLine("    if (Output.a < 0.015) discard;")
        }

        if (mat.blendMode == 0) {
            sb.appendLine("    gl_FragColor = vec4(Output.rgb, 1.0);")
        } else {
            sb.appendLine("    gl_FragColor = vec4(Output.rgb * Output.a, Output.a);")
        }
        sb.appendLine("}")
        return sb.toString()
    }

    private fun srcExpr(src: Int, constName: String): String = when (src) {
        0 -> "vCol"
        1 -> "FragPriColor"
        2 -> "FragSecColor"
        3 -> "Color0"
        4 -> "Color1"
        5 -> "Color2"
        13 -> "Previous"
        14 -> constName
        15 -> "Previous"
        else -> "CombBuffer"
    }

    private fun combColorExpr(mode: Int, a: Array<String>): String = when (mode) {
        0 -> "(${a[0]}).rgb"
        1 -> "(${a[0]}).rgb * (${a[1]}).rgb"
        2 -> "min((${a[0]}).rgb + (${a[1]}).rgb, 1.0)"
        3 -> "clamp((${a[0]}).rgb + (${a[1]}).rgb - 0.5, 0.0, 1.0)"
        4 -> "mix((${a[1]}).rgb, (${a[0]}).rgb, (${a[2]}).rgb)"
        5 -> "max((${a[0]}).rgb - (${a[1]}).rgb, 0.0)"
        6 -> "vec3(min(dot((${a[0]}).rgb, (${a[1]}).rgb), 1.0))"
        7 -> "vec3(min(dot(${a[0]}, ${a[1]}), 1.0))"
        8 -> "min((${a[0]}).rgb * (${a[1]}).rgb + (${a[2]}).rgb, 1.0)"
        9 -> "min((${a[0]}).rgb + (${a[1]}).rgb, 1.0) * (${a[2]}).rgb"
        else -> "(${a[0]}).rgb"
    }

    private fun combAlphaExpr(mode: Int, a: Array<String>): String = when (mode) {
        0 -> "(${a[0]})"
        1 -> "(${a[0]}) * (${a[1]})"
        2 -> "min((${a[0]}) + (${a[1]}), 1.0)"
        3 -> "clamp((${a[0]}) + (${a[1]}) - 0.5, 0.0, 1.0)"
        4 -> "mix((${a[1]}), (${a[0]}), (${a[2]}))"
        5 -> "max((${a[0]}) - (${a[1]}), 0.0)"
        6 -> "min(dot(vec3(${a[0]}), vec3(${a[1]})), 1.0)"
        7 -> "min(dot(vec4(${a[0]}), vec4(${a[1]})), 1.0)"
        8 -> "min((${a[0]}) * (${a[1]}) + (${a[2]}), 1.0)"
        9 -> "min((${a[0]}) + (${a[1]}), 1.0) * (${a[2]})"
        else -> "(${a[0]})"
    }
}
