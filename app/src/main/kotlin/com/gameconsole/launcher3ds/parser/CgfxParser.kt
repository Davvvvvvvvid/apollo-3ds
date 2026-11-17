package com.gameconsole.launcher3ds.parser

import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * 任天堂 3DS CGFX 3D 场景与纹理贴图解析器
 * 支持解析 CMDL (3D 网格/顶点/索引/材质) 与 TXOB (ETC1/ETC1A4 Morton Z-order 贴图解码)
 */
object CgfxParser {

    data class CgfxTexture(
        val name: String,
        val width: Int,
        val height: Int,
        val rgbaPixels: IntArray
    )

    data class CgfxSubMesh(
        val indices: ShortArray,
        val vertices: FloatArray, // interleaved: x, y, z, u, v, r, g, b, a (9 floats per vertex)
        val textureIndex: Int
    )

    data class CgfxScene(
        val textures: List<CgfxTexture>,
        val meshes: List<CgfxSubMesh>,
        val minX: Float, val maxX: Float,
        val minY: Float, val maxY: Float,
        val minZ: Float, val maxZ: Float
    )

    fun parseFile(cgfxFile: File): CgfxScene? {
        if (!cgfxFile.exists() || cgfxFile.length() < 64) return null
        return try {
            parseBytes(cgfxFile.readBytes())
        } catch (_: Throwable) {
            null
        }
    }

    fun parseBytes(data: ByteArray): CgfxScene? {
        if (data.size < 0x60) return null
        if (data[0] != 'C'.code.toByte() || data[1] != 'G'.code.toByte() ||
            data[2] != 'F'.code.toByte() || data[3] != 'X'.code.toByte()
        ) return null

        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Dict 0: Models (CMDL), Dict 1: Textures (TXOB)
        val texDictCnt = bb.getInt(0x1C + 1 * 8)
        val texDictRel = bb.getInt(0x1C + 1 * 8 + 4)
        val textures = ArrayList<CgfxTexture>()
        val texOffsetToIndex = HashMap<Int, Int>()

        if (texDictCnt > 0 && texDictRel != 0) {
            val texDictAbs = (0x1C + 1 * 8 + 4) + texDictRel
            val entries = readDict(data, bb, texDictAbs)
            for ((name, objOff) in entries) {
                val tex = parseTxob(data, bb, objOff, name)
                if (tex != null) {
                    texOffsetToIndex[objOff] = textures.size
                    textures.add(tex)
                }
            }
        }

        val modelDictCnt = bb.getInt(0x1C)
        val modelDictRel = bb.getInt(0x1C + 4)
        val meshes = ArrayList<CgfxSubMesh>()

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        if (modelDictCnt > 0 && modelDictRel != 0) {
            val modelDictAbs = (0x1C + 4) + modelDictRel
            val modelEntries = readDict(data, bb, modelDictAbs)
            for ((_, modelOff) in modelEntries) {
                parseCmdl(data, bb, modelOff, textures, texOffsetToIndex, meshes)
            }
        }

        for (m in meshes) {
            var i = 0
            while (i + 8 < m.vertices.size) {
                val x = m.vertices[i]
                val y = m.vertices[i + 1]
                val z = m.vertices[i + 2]
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                i += 9
            }
        }

        if (meshes.isEmpty()) return null
        if (minX > maxX) {
            minX = -1f; maxX = 1f; minY = -1f; maxY = 1f; minZ = -1f; maxZ = 1f
        }
        return CgfxScene(textures, meshes, minX, maxX, minY, maxY, minZ, maxZ)
    }

    private fun readDict(data: ByteArray, bb: ByteBuffer, dictOff: Int): List<Pair<String, Int>> {
        if (dictOff < 0 || dictOff + 12 > data.size) return emptyList()
        if (data[dictOff] != 'D'.code.toByte() || data[dictOff + 1] != 'I'.code.toByte() ||
            data[dictOff + 2] != 'C'.code.toByte() || data[dictOff + 3] != 'T'.code.toByte()
        ) return emptyList()

        val count = bb.getInt(dictOff + 8)
        if (count <= 0 || count > 512) return emptyList()

        val out = ArrayList<Pair<String, Int>>(count)
        for (i in 0 until count) {
            val nOff = dictOff + 12 + 16 + i * 16
            if (nOff + 16 > data.size) break
            val nameRel = bb.getInt(nOff + 8)
            val name = if (nameRel != 0) readCString(data, nOff + 8 + nameRel) else "item_$i"
            val objRel = bb.getInt(nOff + 12)
            val objOff = if (objRel != 0) (nOff + 12) + objRel else 0
            if (objOff in 1 until data.size) {
                out.add(name to objOff)
            }
        }
        return out
    }

    private fun readCString(data: ByteArray, off: Int): String {
        if (off < 0 || off >= data.size) return ""
        var end = off
        while (end < data.size && data[end] != 0.toByte()) end++
        return String(data, off, end - off, Charsets.UTF_8)
    }

    private fun parseCmdl(
        data: ByteArray,
        bb: ByteBuffer,
        mOff: Int,
        textures: List<CgfxTexture>,
        texOffsetToIndex: HashMap<Int, Int>,
        outMeshes: MutableList<CgfxSubMesh>
    ) {
        if (mOff + 0xD0 > data.size) return
        val meshCount = bb.getInt(mOff + 0xB4)
        val meshListRel = bb.getInt(mOff + 0xB8)
        val matDictCount = bb.getInt(mOff + 0xBC)
        val matDictRel = bb.getInt(mOff + 0xC0)
        val shapeCount = bb.getInt(mOff + 0xC4)
        val shapeListRel = bb.getInt(mOff + 0xC8)

        if (meshCount <= 0 || meshCount > 256 || shapeCount <= 0 || shapeCount > 256) return

        val meshListAbs = (mOff + 0xB8) + meshListRel
        val shapeListAbs = (mOff + 0xC8) + shapeListRel

        // Parse Materials -> diffuse texture index
        val matTexIndices = ArrayList<Int>()
        if (matDictCount > 0 && matDictRel != 0) {
            val matDictAbs = (mOff + 0xC0) + matDictRel
            val matEntries = readDict(data, bb, matDictAbs)
            for ((_, matOff) in matEntries) {
                matTexIndices.add(resolveMaterialTextureIndex(data, bb, matOff, textures, texOffsetToIndex))
            }
        }

        // Parse Shapes
        val shapeOffsets = IntArray(shapeCount)
        for (i in 0 until shapeCount) {
            val p = shapeListAbs + i * 4
            if (p + 4 <= data.size) {
                val rel = bb.getInt(p)
                shapeOffsets[i] = if (rel != 0) p + rel else 0
            }
        }

        // Parse Meshes
        for (i in 0 until meshCount) {
            val p = meshListAbs + i * 4
            if (p + 4 > data.size) continue
            val mRel = bb.getInt(p)
            if (mRel == 0) continue
            val meshOff = p + mRel
            if (meshOff + 0x44 > data.size) continue

            val shapeIdx = bb.getInt(meshOff + 0x38)
            val matIdx = bb.getInt(meshOff + 0x3C)
            val texIdx = matTexIndices.getOrNull(matIdx) ?: -1

            if (shapeIdx in 0 until shapeCount) {
                val shapeOff = shapeOffsets[shapeIdx]
                if (shapeOff > 0) {
                    parseShape(data, bb, shapeOff, texIdx, outMeshes)
                }
            }
        }
    }

    private fun resolveMaterialTextureIndex(
        data: ByteArray,
        bb: ByteBuffer,
        matOff: Int,
        textures: List<CgfxTexture>,
        texOffsetToIndex: HashMap<Int, Int>
    ): Int {
        // MTOB texture mapper 0 pointer is located around matOff + 0xF8..0x118
        // Scan pointer table safely inside MTOB for a valid TXOB reference or texture name match
        if (matOff + 0x130 > data.size) return -1
        for (o in 0xD0..0x120 step 4) {
            val ptrPos = matOff + o
            if (ptrPos + 4 > data.size) break
            val rel = bb.getInt(ptrPos)
            if (rel in 0x10..0x4000) {
                val mapperOff = ptrPos + rel
                if (mapperOff + 0x14 <= data.size) {
                    // Check if TextureRef inside mapper points to a TXOB
                    for (tOff in 0x04..0x18 step 4) {
                        val tPos = mapperOff + tOff
                        if (tPos + 4 > data.size) break
                        val tRel = bb.getInt(tPos)
                        val target = tPos + tRel
                        texOffsetToIndex[target]?.let { return it }
                    }
                }
            }
        }
        return if (textures.isNotEmpty()) 0 else -1
    }

    private fun parseShape(
        data: ByteArray,
        bb: ByteBuffer,
        shapeOff: Int,
        texIdx: Int,
        outMeshes: MutableList<CgfxSubMesh>
    ) {
        if (shapeOff + 0x48 > data.size) return
        val faceCount = bb.getInt(shapeOff + 0x2C)
        val faceListRel = bb.getInt(shapeOff + 0x30)
        val vtxBufCount = bb.getInt(shapeOff + 0x38)
        val vtxBufListRel = bb.getInt(shapeOff + 0x3C)

        if (faceCount <= 0 || faceCount > 64 || vtxBufCount <= 0 || vtxBufCount > 32) return

        val faceListAbs = (shapeOff + 0x30) + faceListRel
        val vtxBufListAbs = (shapeOff + 0x3C) + vtxBufListRel

        // Extract Vertex Attributes (Position, TexCoord0, Color) across all VertexBuffers
        var posData: FloatArray? = null
        var uvData: FloatArray? = null
        var colData: FloatArray? = null
        var vertexCount = 0

        for (vbIdx in 0 until vtxBufCount) {
            val p = vtxBufListAbs + vbIdx * 4
            if (p + 4 > data.size) continue
            val vbRel = bb.getInt(p)
            val vbOff = p + vbRel
            if (vbOff + 0x30 > data.size) continue

            val flags = bb.getInt(vbOff)
            if ((flags and 0x40000000) != 0 && (flags and 0x0F) == 2) {
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
                if (uvData == null) uvData = FloatArray(vertexCount * 2)
                if (colData == null) colData = FloatArray(vertexCount * 4) { 1f }

                for (sIdx in 0 until streamCount.coerceAtMost(16)) {
                    val sp = streamListAbs + sIdx * 4
                    if (sp + 4 > data.size) break
                    val sRel = bb.getInt(sp)
                    val sOff = sp + sRel
                    if (sOff + 0x30 > data.size) continue

                    val usage = bb.getInt(sOff + 0x04) // 0=Pos, 1=Normal, 3=Color, 4=TexCoord0
                    val glType = bb.getInt(sOff + 0x20) // 0x1400=Byte, 0x1401=UByte, 0x1402=Short, 0x1406=Float
                    val compCount = bb.getInt(sOff + 0x24).coerceIn(1, 4)
                    val scale = bb.getFloat(sOff + 0x28)
                    val elemOffset = bb.getInt(sOff + 0x2C)

                    for (v in 0 until min(vertexCount, curVtxCount)) {
                        val vBase = rawAbs + v * stride + elemOffset
                        if (vBase >= data.size) break
                        for (c in 0 until compCount) {
                            val valF = readGlComp(data, bb, vBase, c, glType) * scale
                            when (usage) {
                                0 -> if (c < 3) posData[v * 3 + c] = valF
                                3 -> if (c < 4) colData[v * 4 + c] = valF
                                4 -> if (c < 2) uvData[v * 2 + c] = if (c == 1) 1f - valF else valF
                            }
                        }
                    }
                }
            }
        }

        if (posData == null || vertexCount <= 0) return

        val interleaved = FloatArray(vertexCount * 9)
        for (v in 0 until vertexCount) {
            interleaved[v * 9 + 0] = posData[v * 3 + 0]
            interleaved[v * 9 + 1] = posData[v * 3 + 1]
            interleaved[v * 9 + 2] = posData[v * 3 + 2]
            interleaved[v * 9 + 3] = uvData?.getOrNull(v * 2 + 0) ?: 0f
            interleaved[v * 9 + 4] = uvData?.getOrNull(v * 2 + 1) ?: 0f
            interleaved[v * 9 + 5] = colData?.getOrNull(v * 4 + 0) ?: 1f
            interleaved[v * 9 + 6] = colData?.getOrNull(v * 4 + 1) ?: 1f
            interleaved[v * 9 + 7] = colData?.getOrNull(v * 4 + 2) ?: 1f
            interleaved[v * 9 + 8] = colData?.getOrNull(v * 4 + 3) ?: 1f
        }

        // Read Index Streams from Faces
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
                if (isOff + 0x2C > data.size) continue

                // IndexStream inside Face: +0x20=flags, +0x24=format(0=UByte,1=UShort), +0x28=len, +0x2C=dataRel
                // Wait: in some Face structs, the IndexStream object is inline or pointed. Verify both:
                val inlineLen = bb.getInt(isOff + 0x28)
                val inlineRel = bb.getInt(isOff + 0x2C)
                val isInline = (inlineLen in 3..65536) && (isOff + 0x2C + inlineRel in 0 until data.size)
                val idxFormat: Int
                val idxLen: Int
                val idxDataAbs: Int
                if (isInline) {
                    idxFormat = data[isOff + 0x24].toInt() and 0xFF
                    idxLen = inlineLen
                    idxDataAbs = (isOff + 0x2C) + inlineRel
                } else {
                    idxFormat = data[isOff + 0x04].toInt() and 0xFF
                    idxLen = bb.getInt(isOff + 0x0C)
                    val idxDataRel = bb.getInt(isOff + 0x10)
                    idxDataAbs = (isOff + 0x10) + idxDataRel
                }

                if (idxLen <= 0 || idxDataAbs < 0 || idxDataAbs >= data.size) continue
                val indices = if (idxFormat == 0) {
                    val arr = ShortArray(idxLen)
                    for (k in 0 until idxLen) {
                        if (idxDataAbs + k < data.size) {
                            arr[k] = (data[idxDataAbs + k].toInt() and 0xFF).toShort()
                        }
                    }
                    arr
                } else {
                    val count = idxLen / 2
                    val arr = ShortArray(count)
                    for (k in 0 until count) {
                        if (idxDataAbs + k * 2 + 1 < data.size) {
                            arr[k] = bb.getShort(idxDataAbs + k * 2)
                        }
                    }
                    arr
                }
                if (indices.isNotEmpty()) {
                    outMeshes.add(CgfxSubMesh(indices, interleaved, texIdx))
                }
            }
        }
    }

    private fun readGlComp(data: ByteArray, bb: ByteBuffer, base: Int, comp: Int, glType: Int): Float {
        return when (glType) {
            0x1400 -> { // GL_BYTE
                val p = base + comp
                if (p < data.size) data[p].toFloat() else 0f
            }
            0x1401 -> { // GL_UNSIGNED_BYTE
                val p = base + comp
                if (p < data.size) (data[p].toInt() and 0xFF).toFloat() else 0f
            }
            0x1402 -> { // GL_SHORT
                val p = base + comp * 2
                if (p + 1 < data.size) bb.getShort(p).toFloat() else 0f
            }
            else -> { // 0x1406 GL_FLOAT
                val p = base + comp * 4
                if (p + 3 < data.size) bb.getFloat(p) else 0f
            }
        }
    }

    private fun parseTxob(data: ByteArray, bb: ByteBuffer, tOff: Int, name: String): CgfxTexture? {
        if (tOff + 0x50 > data.size) return null
        val w = bb.getInt(tOff + 0x18)
        val h = bb.getInt(tOff + 0x1C)
        val fmtId = bb.getInt(tOff + 0x34)
        val dataSize = bb.getInt(tOff + 0x44)
        val dataRel = bb.getInt(tOff + 0x48)
        val dataAbs = (tOff + 0x48) + dataRel

        if (w <= 0 || h <= 0 || w > 1024 || h > 1024 || dataAbs < 0 || dataAbs >= data.size) return null
        val pixels = decode3dsTexture(data, dataAbs, dataSize.coerceAtMost(data.size - dataAbs), w, h, fmtId)
        return CgfxTexture(name, w, h, pixels)
    }

    // Morton Z-order 8x8 tile coordinates
    private val TILE_ORDER = intArrayOf(
        0, 1, 8, 9, 2, 3, 10, 11,
        16, 17, 24, 25, 18, 19, 26, 27,
        4, 5, 12, 13, 6, 7, 14, 15,
        20, 21, 28, 29, 22, 23, 30, 31,
        32, 33, 40, 41, 34, 35, 42, 43,
        48, 49, 56, 57, 50, 51, 58, 59,
        36, 37, 44, 45, 38, 39, 46, 47,
        52, 53, 60, 61, 54, 55, 62, 63
    )

    private val ETC1_MODIFIERS = arrayOf(
        intArrayOf(2, 8, -2, -8),
        intArrayOf(5, 17, -5, -17),
        intArrayOf(9, 29, -9, -29),
        intArrayOf(13, 42, -13, -42),
        intArrayOf(18, 60, -18, -60),
        intArrayOf(24, 80, -24, -80),
        intArrayOf(33, 106, -33, -106),
        intArrayOf(47, 183, -47, -183)
    )

    private fun decode3dsTexture(src: ByteArray, off: Int, len: Int, w: Int, h: Int, fmt: Int): IntArray {
        val out = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        if (fmt == 0xC || fmt == 0xD) {
            // ETC1 (0xC) or ETC1A4 (0xD)
            val withAlpha = (fmt == 0xD)
            val blockBytes = if (withAlpha) 16 else 8
            var pos = off
            val tilesX = w / 8
            val tilesY = h / 8

            for (ty in 0 until tilesY) {
                for (tx in 0 until tilesX) {
                    for (sub in 0 until 4) {
                        val sx = (sub and 1) * 4
                        val sy = (sub ushr 1) * 4
                        if (pos + blockBytes > src.size) break

                        var alpha64 = -1L
                        var colorPos = pos
                        if (withAlpha) {
                            alpha64 = readLongLE(src, pos)
                            colorPos = pos + 8
                        }
                        val block64 = readLongLE(src, colorPos)
                        decodeEtc1Block(block64, alpha64, withAlpha, out, w, tx * 8 + sx, ty * 8 + sy)
                        pos += blockBytes
                    }
                }
            }
            return out
        }

        val bpp = when (fmt) {
            0x0 -> 4 // RGBA8
            0x1 -> 3 // RGB8
            0x2, 0x3, 0x4, 0x5 -> 2 // RGBA5551, RGB565, RGBA4, LA8
            0x7, 0x8 -> 1 // L8, A8
            else -> 2
        }
        val tilesX = max(1, w / 8)
        val tilesY = max(1, h / 8)
        var pos = off

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                for (k in 0 until 64) {
                    if (pos + bpp > src.size) break
                    val t = TILE_ORDER[k]
                    val px = tx * 8 + (t and 7)
                    val py = ty * 8 + (t ushr 3)
                    if (px < w && py < h) {
                        out[py * w + px] = readPixel(src, pos, fmt)
                    }
                    pos += bpp
                }
            }
        }
        return out
    }

    private fun readPixel(src: ByteArray, pos: Int, fmt: Int): Int {
        return when (fmt) {
            0x0 -> { // RGBA8 (ABGR in little-endian memory on PICA200)
                val a = src[pos].toInt() and 0xFF
                val b = src[pos + 1].toInt() and 0xFF
                val g = src[pos + 2].toInt() and 0xFF
                val r = src[pos + 3].toInt() and 0xFF
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            0x1 -> { // RGB8
                val b = src[pos].toInt() and 0xFF
                val g = src[pos + 1].toInt() and 0xFF
                val r = src[pos + 2].toInt() and 0xFF
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            0x2 -> { // RGBA5551
                val v = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
                val r = ((v ushr 11) and 0x1F) * 255 / 31
                val g = ((v ushr 6) and 0x1F) * 255 / 31
                val b = ((v ushr 1) and 0x1F) * 255 / 31
                val a = if ((v and 1) != 0) 255 else 0
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            0x3 -> { // RGB565
                val v = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
                val r = ((v ushr 11) and 0x1F) * 255 / 31
                val g = ((v ushr 5) and 0x3F) * 255 / 63
                val b = (v and 0x1F) * 255 / 31
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            0x4 -> { // RGBA4
                val v = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
                val r = ((v ushr 12) and 0xF) * 17
                val g = ((v ushr 8) and 0xF) * 17
                val b = ((v ushr 4) and 0xF) * 17
                val a = (v and 0xF) * 17
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            0x5 -> { // LA8
                val a = src[pos].toInt() and 0xFF
                val l = src[pos + 1].toInt() and 0xFF
                (a shl 24) or (l shl 16) or (l shl 8) or l
            }
            0x7 -> { // L8
                val l = src[pos].toInt() and 0xFF
                (0xFF shl 24) or (l shl 16) or (l shl 8) or l
            }
            0x8 -> { // A8
                val a = src[pos].toInt() and 0xFF
                (a shl 24) or 0x00FFFFFF
            }
            else -> 0xFFFFFFFF.toInt()
        }
    }

    private fun readLongLE(src: ByteArray, pos: Int): Long {
        var res = 0L
        for (i in 0 until 8) {
            res = res or ((src[pos + i].toLong() and 0xFFL) shl (i * 8))
        }
        return res
    }

    private fun decodeEtc1Block(
        block: Long,
        alphaBlock: Long,
        withAlpha: Boolean,
        out: IntArray,
        stride: Int,
        baseX: Int,
        baseY: Int
    ) {
        val diff = ((block ushr 33) and 1L) != 0L
        val flip = ((block ushr 32) and 1L) != 0L
        val table1 = ((block ushr 37) and 7L).toInt()
        val table2 = ((block ushr 34) and 7L).toInt()

        var r1: Int; var g1: Int; var b1: Int
        var r2: Int; var g2: Int; var b2: Int

        if (diff) {
            val r = ((block ushr 59) and 0x1FL).toInt()
            val g = ((block ushr 51) and 0x1FL).toInt()
            val b = ((block ushr 43) and 0x1FL).toInt()
            var dr = ((block ushr 56) and 0x7L).toInt(); if (dr >= 4) dr -= 8
            var dg = ((block ushr 48) and 0x7L).toInt(); if (dg >= 4) dg -= 8
            var db = ((block ushr 40) and 0x7L).toInt(); if (db >= 4) db -= 8
            r1 = (r shl 3) or (r ushr 2)
            g1 = (g shl 3) or (g ushr 2)
            b1 = (b shl 3) or (b ushr 2)
            r2 = ((r + dr) and 0x1F).let { (it shl 3) or (it ushr 2) }
            g2 = ((g + dg) and 0x1F).let { (it shl 3) or (it ushr 2) }
            b2 = ((b + db) and 0x1F).let { (it shl 3) or (it ushr 2) }
        } else {
            r1 = ((block ushr 60) and 0xFL).toInt() * 17
            g1 = ((block ushr 52) and 0xFL).toInt() * 17
            b1 = ((block ushr 44) and 0xFL).toInt() * 17
            r2 = ((block ushr 56) and 0xFL).toInt() * 17
            g2 = ((block ushr 48) and 0xFL).toInt() * 17
            b2 = ((block ushr 40) and 0xFL).toInt() * 17
        }

        for (x in 0 until 4) {
            for (y in 0 until 4) {
                val idx = x * 4 + y
                val lsb = ((block ushr idx) and 1L).toInt()
                val msb = ((block ushr (idx + 16)) and 1L).toInt() shl 1
                val modIdx = lsb or msb

                val sub2 = if (flip) (y >= 2) else (x >= 2)
                val mod = ETC1_MODIFIERS[if (sub2) table2 else table1][modIdx]
                val r = ((if (sub2) r2 else r1) + mod).coerceIn(0, 255)
                val g = ((if (sub2) g2 else g1) + mod).coerceIn(0, 255)
                val b = ((if (sub2) b2 else b1) + mod).coerceIn(0, 255)
                val a = if (withAlpha) {
                    (((alphaBlock ushr (idx * 4)) and 0xFL).toInt() * 17)
                } else 255

                val px = baseX + x
                val py = baseY + y
                if (px < stride && py * stride + px < out.size) {
                    out[py * stride + px] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }
}
