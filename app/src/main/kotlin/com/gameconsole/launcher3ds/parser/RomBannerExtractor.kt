package com.gameconsole.launcher3ds.parser

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * 3DS 原机上屏 3D 横幅 (CBMD -> CGFX) 与原机提示音 (CWAV -> WAV) 实时提取器
 * 直接从已解密的 .3ds / .cci / .cia ROM ExeFS 中按偏移秒级读取几百 KB 横幅分区并缓存
 */
object RomBannerExtractor {

    data class ExtractedBanner(
        val cgfxPath: String,
        val wavPath: String
    )

    fun extractFromRom(context: Context, romFile: File): ExtractedBanner? {
        if (!romFile.exists() || !romFile.isFile) return null
        val ext = romFile.extension.lowercase()
        if (ext != "3ds" && ext != "cci" && ext != "cia" && ext != "cxi") return null

        val bannerDir = File(context.filesDir, "rom_banners").apply { mkdirs() }
        val key = md5("${romFile.absolutePath}_${romFile.length()}_${romFile.lastModified()}")
        val cgfxOut = File(bannerDir, "$key.cgfx")
        val wavOut = File(bannerDir, "$key.wav")

        if (cgfxOut.exists() && cgfxOut.length() > 64) {
            return ExtractedBanner(
                cgfxPath = cgfxOut.absolutePath,
                wavPath = if (wavOut.exists() && wavOut.length() > 44) wavOut.absolutePath else ""
            )
        }

        return try {
            val bannerBytes = RandomAccessFile(romFile, "r").use { raf ->
                when (ext) {
                    "cia" -> readBannerFromCia(raf)
                    "cxi" -> readBannerFromNcch(raf, 0L)
                    else -> readBannerFromNcsd(raf)
                }
            } ?: return null

            if (bannerBytes.size < 0x90) return null
            if (bannerBytes[0] != 'C'.code.toByte() || bannerBytes[1] != 'B'.code.toByte() ||
                bannerBytes[2] != 'M'.code.toByte() || bannerBytes[3] != 'D'.code.toByte()
            ) {
                return null
            }

            val bb = ByteBuffer.wrap(bannerBytes).order(ByteOrder.LITTLE_ENDIAN)
            // CBMD: 0x08..0x3B 是 13 个 CGFX 偏移（0 为通用 CGFX），0x84 为 CWAV 偏移
            var cgfxOffset = bb.getInt(0x08)
            if (cgfxOffset <= 0 || cgfxOffset >= bannerBytes.size) {
                for (i in 1 until 13) {
                    val off = bb.getInt(0x08 + i * 4)
                    if (off > 0 && off < bannerBytes.size) {
                        cgfxOffset = off
                        break
                    }
                }
            }
            val cwavOffset = bb.getInt(0x84)

            var extractedCgfx = false
            if (cgfxOffset in 0x88 until bannerBytes.size) {
                val cgfxEnd = if (cwavOffset > cgfxOffset && cwavOffset <= bannerBytes.size) cwavOffset else bannerBytes.size
                val compressed = bannerBytes.copyOfRange(cgfxOffset, cgfxEnd)
                val rawCgfx = if (compressed.isNotEmpty() && compressed[0] == 0x11.toByte()) {
                    decompressLz11(compressed)
                } else {
                    compressed
                }
                if (rawCgfx.size >= 20 &&
                    rawCgfx[0] == 'C'.code.toByte() && rawCgfx[1] == 'G'.code.toByte() &&
                    rawCgfx[2] == 'F'.code.toByte() && rawCgfx[3] == 'X'.code.toByte()
                ) {
                    cgfxOut.writeBytes(rawCgfx)
                    extractedCgfx = true
                }
            }

            var extractedWav = false
            if (cwavOffset in 0x88 until bannerBytes.size - 16) {
                val cwavBytes = bannerBytes.copyOfRange(cwavOffset, bannerBytes.size)
                val pcmWav = decodeCwavToWav(cwavBytes)
                if (pcmWav != null && pcmWav.size > 44) {
                    wavOut.writeBytes(pcmWav)
                    extractedWav = true
                }
            }

            if (extractedCgfx) {
                ExtractedBanner(
                    cgfxPath = cgfxOut.absolutePath,
                    wavPath = if (extractedWav) wavOut.absolutePath else ""
                )
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readBannerFromNcsd(raf: RandomAccessFile): ByteArray? {
        if (raf.length() < 0x200) return null
        val magic = ByteArray(4)
        raf.seek(0x100)
        raf.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != "NCSD") return null

        val p0Bytes = ByteArray(8)
        raf.seek(0x120)
        raf.readFully(p0Bytes)
        val bb = ByteBuffer.wrap(p0Bytes).order(ByteOrder.LITTLE_ENDIAN)
        val part0Off = bb.getInt(0).toLong() and 0xFFFFFFFFL
        return readBannerFromNcch(raf, part0Off * 0x200L)
    }

    private fun readBannerFromCia(raf: RandomAccessFile): ByteArray? {
        if (raf.length() < 0x40) return null
        val hdr = ByteArray(0x20)
        raf.seek(0)
        raf.readFully(hdr)
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = align64(bb.getInt(0x00).toLong() and 0xFFFFFFFFL)
        val certSize = align64(bb.getInt(0x08).toLong() and 0xFFFFFFFFL)
        val ticketSize = align64(bb.getInt(0x0C).toLong() and 0xFFFFFFFFL)
        val tmdSize = align64(bb.getInt(0x10).toLong() and 0xFFFFFFFFL)
        val contentOffset = headerSize + certSize + ticketSize + tmdSize
        return readBannerFromNcch(raf, contentOffset)
    }

    private fun align64(v: Long): Long = (v + 63L) and 63L.inv()

    private fun readBannerFromNcch(raf: RandomAccessFile, ncchOffset: Long): ByteArray? {
        if (ncchOffset < 0 || ncchOffset + 0x200 > raf.length()) return null
        val magic = ByteArray(4)
        raf.seek(ncchOffset + 0x100)
        raf.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != "NCCH") return null

        raf.seek(ncchOffset + 0x18F)
        val flags7 = raf.readByte().toInt() and 0xFF
        val noCrypto = (flags7 and 0x04) != 0
        if (!noCrypto) return null

        val exefsInfo = ByteArray(8)
        raf.seek(ncchOffset + 0x1A0)
        raf.readFully(exefsInfo)
        val bb = ByteBuffer.wrap(exefsInfo).order(ByteOrder.LITTLE_ENDIAN)
        val exefsOffUnits = bb.getInt(0).toLong() and 0xFFFFFFFFL
        val exefsSizeUnits = bb.getInt(4).toLong() and 0xFFFFFFFFL
        if (exefsOffUnits == 0L || exefsSizeUnits == 0L) return null

        val exefsAbs = ncchOffset + exefsOffUnits * 0x200L
        if (exefsAbs + 0x200L > raf.length()) return null

        val exefsHdr = ByteArray(0x200)
        raf.seek(exefsAbs)
        raf.readFully(exefsHdr)
        val hdrBb = ByteBuffer.wrap(exefsHdr).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until 10) {
            val base = i * 16
            val name = String(exefsHdr, base, 8, Charsets.US_ASCII).trimEnd('\u0000')
            if (name == "banner") {
                val off = hdrBb.getInt(base + 8).toLong() and 0xFFFFFFFFL
                val size = hdrBb.getInt(base + 12)
                if (size <= 0 || size > 4 * 1024 * 1024) return null
                val absPos = exefsAbs + 0x200L + off
                if (absPos + size > raf.length()) return null
                val out = ByteArray(size)
                raf.seek(absPos)
                raf.readFully(out)
                return out
            }
        }
        return null
    }

    /**
     * 任天堂 LZ11 (0x11) 标准解压
     */
    fun decompressLz11(data: ByteArray): ByteArray {
        if (data.size < 4 || data[0] != 0x11.toByte()) return data
        var decompSize = (data[1].toInt() and 0xFF) or
            ((data[2].toInt() and 0xFF) shl 8) or
            ((data[3].toInt() and 0xFF) shl 16)
        var idx = 4
        if (decompSize == 0) {
            if (data.size < 8) return data
            decompSize = (data[4].toInt() and 0xFF) or
                ((data[5].toInt() and 0xFF) shl 8) or
                ((data[6].toInt() and 0xFF) shl 16) or
                ((data[7].toInt() and 0xFF) shl 24)
            idx = 8
        }
        if (decompSize <= 0 || decompSize > 16 * 1024 * 1024) return data

        val out = ByteArray(decompSize)
        var outPos = 0

        while (outPos < decompSize && idx < data.size) {
            val flags = data[idx++].toInt() and 0xFF
            for (bit in 0 until 8) {
                if (outPos >= decompSize || idx >= data.size) break
                if ((flags and (0x80 shr bit)) != 0) {
                    val b1 = data[idx++].toInt() and 0xFF
                    val top = b1 ushr 4
                    val count: Int
                    val disp: Int
                    when (top) {
                        0 -> {
                            if (idx + 1 >= data.size) break
                            val b2 = data[idx++].toInt() and 0xFF
                            val b3 = data[idx++].toInt() and 0xFF
                            count = (((b1 and 0x0F) shl 4) or (b2 ushr 4)) + 0x11
                            disp = (((b2 and 0x0F) shl 8) or b3) + 1
                        }
                        1 -> {
                            if (idx + 2 >= data.size) break
                            val b2 = data[idx++].toInt() and 0xFF
                            val b3 = data[idx++].toInt() and 0xFF
                            val b4 = data[idx++].toInt() and 0xFF
                            count = (((b1 and 0x0F) shl 12) or (b2 shl 4) or (b3 ushr 4)) + 0x111
                            disp = (((b3 and 0x0F) shl 8) or b4) + 1
                        }
                        else -> {
                            if (idx >= data.size) break
                            val b2 = data[idx++].toInt() and 0xFF
                            count = top + 1
                            disp = (((b1 and 0x0F) shl 8) or b2) + 1
                        }
                    }
                    for (k in 0 until count) {
                        if (outPos >= decompSize) break
                        val srcIdx = outPos - disp
                        out[outPos++] = if (srcIdx in 0 until outPos) out[srcIdx] else 0
                    }
                } else {
                    out[outPos++] = data[idx++]
                }
            }
        }
        return if (outPos == decompSize) out else out.copyOf(outPos)
    }

    /**
     * 解码 3DS CWAV (BCWAV) 音效文件为标准 16-bit PCM WAV
     * 支持 PCM8 / PCM16 / DSP-ADPCM (单声道 & 立体声)
     */
    fun decodeCwavToWav(cwav: ByteArray): ByteArray? {
        if (cwav.size < 0x40) return null
        if (cwav[0] != 'C'.code.toByte() || cwav[1] != 'W'.code.toByte() ||
            cwav[2] != 'A'.code.toByte() || cwav[3] != 'V'.code.toByte()
        ) return null

        val bb = ByteBuffer.wrap(cwav).order(ByteOrder.LITTLE_ENDIAN)
        val hdrLen = bb.getShort(0x06).toInt() and 0xFFFF
        val blockCount = bb.getShort(0x10).toInt() and 0xFFFF

        var infoOffset = -1
        var dataOffset = -1
        var pos = 0x14
        for (i in 0 until blockCount) {
            if (pos + 12 > cwav.size) break
            val refId = bb.getShort(pos).toInt() and 0xFFFF
            val blockOff = bb.getInt(pos + 4)
            if (refId == 0x7000) infoOffset = blockOff
            else if (refId == 0x7001) dataOffset = blockOff
            pos += 12
        }
        if (infoOffset < 0 || dataOffset < 0 || infoOffset + 0x20 > cwav.size) return null

        // INFO block: magic(4) + size(4)
        val encoding = cwav[infoOffset + 0x08].toInt() and 0xFF // 0=PCM8, 1=PCM16, 2=DSP-ADPCM
        val sampleRate = bb.getInt(infoOffset + 0x0C)
        val loopEndFrame = bb.getInt(infoOffset + 0x14)
        val channelCount = bb.getInt(infoOffset + 0x1C).coerceIn(1, 2)
        if (sampleRate <= 0 || loopEndFrame <= 0 || loopEndFrame > sampleRate * 15) return null

        val dataBase = dataOffset + 8
        val channelSamples = Array(channelCount) { ShortArray(loopEndFrame) }

        for (ch in 0 until channelCount) {
            val chRefOff = infoOffset + 0x20 + ch * 8
            if (chRefOff + 8 > cwav.size) return null
            val chInfoRel = bb.getInt(chRefOff + 4)
            val chInfoAbs = infoOffset + 0x1C + chInfoRel
            if (chInfoAbs + 16 > cwav.size) return null

            val sampleRel = bb.getInt(chInfoAbs + 0x04)
            val adpcmInfoRel = bb.getInt(chInfoAbs + 0x0C)
            val sampleAbs = dataBase + sampleRel

            when (encoding) {
                0 -> {
                    // PCM8
                    for (s in 0 until loopEndFrame) {
                        if (sampleAbs + s < cwav.size) {
                            channelSamples[ch][s] = ((cwav[sampleAbs + s].toInt()) shl 8).toShort()
                        }
                    }
                }
                1 -> {
                    // PCM16
                    for (s in 0 until loopEndFrame) {
                        val p = sampleAbs + s * 2
                        if (p + 1 < cwav.size) {
                            channelSamples[ch][s] = bb.getShort(p)
                        }
                    }
                }
                2 -> {
                    // DSP-ADPCM
                    val dspInfoAbs = chInfoAbs + adpcmInfoRel
                    if (dspInfoAbs + 0x20 > cwav.size) return null
                    val coefs = ShortArray(16)
                    for (k in 0 until 16) {
                        coefs[k] = bb.getShort(dspInfoAbs + k * 2)
                    }
                    var yn1 = bb.getShort(dspInfoAbs + 0x22).toInt()
                    var yn2 = bb.getShort(dspInfoAbs + 0x24).toInt()
                    decodeDspAdpcm(cwav, sampleAbs, loopEndFrame, coefs, yn1, yn2, channelSamples[ch])
                }
                else -> return null
            }
        }

        // Write standard WAV
        val pcmBytesLen = loopEndFrame * channelCount * 2
        val out = ByteArrayOutputStream(44 + pcmBytesLen)
        val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put("RIFF".toByteArray(Charsets.US_ASCII))
        hdr.putInt(36 + pcmBytesLen)
        hdr.put("WAVE".toByteArray(Charsets.US_ASCII))
        hdr.put("fmt ".toByteArray(Charsets.US_ASCII))
        hdr.putInt(16)
        hdr.putShort(1) // PCM
        hdr.putShort(channelCount.toShort())
        hdr.putInt(sampleRate)
        hdr.putInt(sampleRate * channelCount * 2)
        hdr.putShort((channelCount * 2).toShort())
        hdr.putShort(16)
        hdr.put("data".toByteArray(Charsets.US_ASCII))
        hdr.putInt(pcmBytesLen)
        out.write(hdr.array())

        val sampleBuf = ByteBuffer.allocate(pcmBytesLen).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until loopEndFrame) {
            for (ch in 0 until channelCount) {
                sampleBuf.putShort(channelSamples[ch][i])
            }
        }
        out.write(sampleBuf.array())
        return out.toByteArray()
    }

    private fun decodeDspAdpcm(
        src: ByteArray,
        startOffset: Int,
        sampleCount: Int,
        coefs: ShortArray,
        initYn1: Int,
        initYn2: Int,
        out: ShortArray
    ) {
        var yn1 = initYn1
        var yn2 = initYn2
        var inPos = startOffset
        var outPos = 0

        while (outPos < sampleCount && inPos < src.size) {
            val header = src[inPos++].toInt() and 0xFF
            val scale = 1 shl (header and 0x0F)
            val predIdx = ((header ushr 4) and 0x07).coerceIn(0, 7)
            val c1 = coefs[predIdx * 2].toInt()
            val c2 = coefs[predIdx * 2 + 1].toInt()

            for (i in 0 until 14) {
                if (outPos >= sampleCount) break
                val byteIdx = inPos + (i ushr 1)
                if (byteIdx >= src.size) break
                val b = src[byteIdx].toInt() and 0xFF
                var nibble = if ((i and 1) == 0) (b ushr 4) else (b and 0x0F)
                if (nibble >= 8) nibble -= 16

                val sample = (((nibble * scale) shl 11) + 1024 + (c1 * yn1 + c2 * yn2)) shr 11
                val clamped = sample.coerceIn(-32768, 32767)
                yn2 = yn1
                yn1 = clamped
                out[outPos++] = clamped.toShort()
            }
            inPos += 7
        }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
