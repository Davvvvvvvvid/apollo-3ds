package com.gameconsole.launcher3ds.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class ParsedRomMetadata(
    val title: String,
    val subtitle: String,
    val publisher: String,
    val productCode: String,
    val iconCachePath: String,
    val primaryColor: Int,
    val secondaryColor: Int
)

object RomHeaderParser {

    /**
     * 解析 .nds / .3ds / .cci / .cia 游戏文件头，提取真实图标、标题、厂商与编号
     */
    fun parseRomFile(context: Context, file: File): ParsedRomMetadata {
        val ext = file.extension.lowercase()
        val iconDir = File(context.filesDir, "rom_icons").apply { mkdirs() }
        val cacheKey = md5("${file.absolutePath}_${file.length()}_${file.lastModified()}")
        val cachedIconFile = File(iconDir, "$cacheKey.png")

        // 1. 优先检查同目录下是否有同名封面图 (.png / .jpg / .webp)
        val sidecarBitmap = findSidecarCover(file)
        if (sidecarBitmap != null && !cachedIconFile.exists()) {
            saveBitmap(sidecarBitmap, cachedIconFile)
        }

        return when (ext) {
            "nds" -> parseNdsRom(file, cachedIconFile)
            "3ds", "cci", "cia", "cxi", "app" -> parse3dsRom(file, cachedIconFile)
            else -> fallbackMetadata(file, cachedIconFile, is3ds = true)
        }
    }

    /**
     * 实机级解析 NDS (.nds) 卡带 Header + 0x068 偏移处的 4bpp 32x32 调色板图标与多语言标题
     */
    private fun parseNdsRom(file: File, cachedIconFile: File): ParsedRomMetadata {
        try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 0x200) {
                    return fallbackMetadata(file, cachedIconFile, is3ds = false)
                }
                val header = ByteArray(0x200)
                raf.readFully(header)

                val asciiTitle = String(header, 0x00, 12, StandardCharsets.US_ASCII)
                    .trim { it <= ' ' || it == '\u0000' }
                val gameCode = String(header, 0x0C, 4, StandardCharsets.US_ASCII)
                    .trim { it <= ' ' || it == '\u0000' }
                val makerCode = String(header, 0x10, 2, StandardCharsets.US_ASCII)
                    .trim { it <= ' ' || it == '\u0000' }

                val bannerOffset = ByteBuffer.wrap(header, 0x68, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int
                    .toLong() and 0xFFFFFFFFL

                var fullTitle = ""
                var publisher = mapMakerCode(makerCode)
                var primaryColor = Color.parseColor("#2B343D")

                if (bannerOffset >= 0x200 && bannerOffset + 0x840 <= raf.length()) {
                    raf.seek(bannerOffset)
                    val versionBytes = ByteArray(2)
                    raf.readFully(versionBytes)
                    val version = (versionBytes[0].toInt() and 0xFF) or ((versionBytes[1].toInt() and 0xFF) shl 8)

                    // 读取 32x32 4bpp 图标 (0x20..0x21F, 512 bytes) 与 16 色 BGR555 调色板 (0x220..0x23F, 32 bytes)
                    if (!cachedIconFile.exists()) {
                        raf.seek(bannerOffset + 0x20)
                        val tileBytes = ByteArray(0x200)
                        val paletteBytes = ByteArray(0x20)
                        raf.readFully(tileBytes)
                        raf.readFully(paletteBytes)

                        val bmp = decodeNdsIcon(tileBytes, paletteBytes)
                        if (bmp != null) {
                            primaryColor = extractDominantColor(bmp, Color.parseColor("#2B3A4A"))
                            saveBitmap(bmp, cachedIconFile)
                        }
                    }

                    // 读取多语言标题（每个语言槽 0x100 字节 UTF-16LE）
                    // 0x240: 日文, 0x340: 英文, 0x840: 中文 (当 version >= 2)
                    val candidateOffsets = mutableListOf<Long>()
                    if (version >= 2 && bannerOffset + 0x940 <= raf.length()) {
                        candidateOffsets.add(bannerOffset + 0x840L) // 中文
                    }
                    candidateOffsets.add(bannerOffset + 0x340L) // 英文
                    candidateOffsets.add(bannerOffset + 0x240L) // 日文

                    for (off in candidateOffsets) {
                        raf.seek(off)
                        val strBytes = ByteArray(0x100)
                        raf.readFully(strBytes)
                        val decoded = decodeUtf16LeNullTerminated(strBytes)
                        if (decoded.isNotBlank()) {
                            fullTitle = decoded
                            break
                        }
                    }
                }

                val lines = fullTitle.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val cleanFileName = cleanRomFileName(file.nameWithoutExtension)
                val displayTitle = when {
                    // 如果文件名包含中文而卡带内只有英文/日文，优先保留中文文件名方便玩家识别
                    containsChinese(cleanFileName) && !containsChinese(fullTitle) -> cleanFileName
                    lines.size >= 2 -> "${lines[0]} ${lines[1]}"
                    lines.size == 1 -> lines[0]
                    cleanFileName.isNotEmpty() -> cleanFileName
                    asciiTitle.isNotEmpty() -> asciiTitle
                    else -> file.nameWithoutExtension
                }
                if (lines.size >= 3) {
                    publisher = lines.last()
                }
                val subtitle = if (lines.size >= 2 && displayTitle != lines[0]) lines[0] else "NINTENDO DS 卡带"
                val code = if (gameCode.isNotEmpty()) "NTR-P-$gameCode" else "NTR-NDS"

                return ParsedRomMetadata(
                    title = displayTitle,
                    subtitle = subtitle,
                    publisher = publisher,
                    productCode = code,
                    iconCachePath = if (cachedIconFile.exists()) cachedIconFile.absolutePath else "",
                    primaryColor = primaryColor,
                    secondaryColor = Color.parseColor("#DCE2E8")
                )
            }
        } catch (_: Exception) {
            return fallbackMetadata(file, cachedIconFile, is3ds = false)
        }
    }

    /**
     * 解码 NDS 32x32 4bpp (8x8 分块) BGR555 调色板图标
     */
    private fun decodeNdsIcon(tileData: ByteArray, paletteData: ByteArray): Bitmap? {
        return try {
            val palette = IntArray(16)
            palette[0] = Color.TRANSPARENT // 索引 0 为透明色
            for (i in 1 until 16) {
                val lo = paletteData[i * 2].toInt() and 0xFF
                val hi = paletteData[i * 2 + 1].toInt() and 0xFF
                val bgr555 = lo or (hi shl 8)
                val r5 = bgr555 and 0x1F
                val g5 = (bgr555 shr 5) and 0x1F
                val b5 = (bgr555 shr 10) and 0x1F
                val r8 = (r5 shl 3) or (r5 shr 2)
                val g8 = (g5 shl 3) or (g5 shr 2)
                val b8 = (b5 shl 3) or (b5 shr 2)
                palette[i] = Color.argb(255, r8, g8, b8)
            }

            val pixels = IntArray(32 * 32)
            var byteIndex = 0
            for (tileY in 0 until 4) {
                for (tileX in 0 until 4) {
                    for (py in 0 until 8) {
                        for (pxPair in 0 until 4) {
                            val b = tileData[byteIndex++].toInt() and 0xFF
                            val p0 = b and 0x0F
                            val p1 = (b shr 4) and 0x0F
                            val x0 = tileX * 8 + pxPair * 2
                            val y0 = tileY * 8 + py
                            pixels[y0 * 32 + x0] = if (p0 == 0) Color.WHITE else palette[p0]
                            pixels[y0 * 32 + x0 + 1] = if (p1 == 0) Color.WHITE else palette[p1]
                        }
                    }
                }
            }
            val rawBmp = Bitmap.createBitmap(pixels, 32, 32, Bitmap.Config.ARGB_8888)
            // 使用最近邻插值放大到 96x96，保留锐利像素颗粒感
            Bitmap.createScaledBitmap(rawBmp, 96, 96, false)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 实机级解析 3DS (.3ds / .cci / .cia) 中的 SMDH 图标与多语言标题结构
     */
    private fun parse3dsRom(file: File, cachedIconFile: File): ParsedRomMetadata {
        try {
            RandomAccessFile(file, "r").use { raf ->
                val smdhOffset = findSmdhOffset(raf)
                var titleFromSmdh = ""
                var longTitleFromSmdh = ""
                var publisherFromSmdh = "Nintendo 3DS"
                var primaryColor = Color.parseColor("#E60012")

                if (smdhOffset >= 0 && smdhOffset + 0x36C0 <= raf.length()) {
                    // 优先顺序: 6=简体中文, 11=繁体中文, 1=英文, 0=日文
                    val langPriority = intArrayOf(6, 11, 1, 0, 2, 3, 4, 5)
                    for (langIdx in langPriority) {
                        val entryOffset = smdhOffset + 0x08L + langIdx * 0x200L
                        raf.seek(entryOffset)
                        val shortBytes = ByteArray(0x80)
                        val longBytes = ByteArray(0x100)
                        val pubBytes = ByteArray(0x80)
                        raf.readFully(shortBytes)
                        raf.readFully(longBytes)
                        raf.readFully(pubBytes)

                        val shortStr = decodeUtf16LeNullTerminated(shortBytes).trim()
                        val longStr = decodeUtf16LeNullTerminated(longBytes).replace('\n', ' ').trim()
                        val pubStr = decodeUtf16LeNullTerminated(pubBytes).trim()

                        if (shortStr.isNotEmpty()) {
                            titleFromSmdh = shortStr
                            longTitleFromSmdh = if (longStr.isNotEmpty()) longStr else shortStr
                            if (pubStr.isNotEmpty()) publisherFromSmdh = pubStr
                            break
                        }
                    }

                    // 解码 SMDH 0x24C0 处的 48x48 RGB565 Morton Z-Order 图标 (4608 字节)
                    if (!cachedIconFile.exists()) {
                        raf.seek(smdhOffset + 0x24C0L)
                        val iconBytes = ByteArray(0x1200) // 48 * 48 * 2 = 4608
                        raf.readFully(iconBytes)
                        val bmp = decode3dsSmdhIcon48(iconBytes)
                        if (bmp != null) {
                            primaryColor = extractDominantColor(bmp, Color.parseColor("#00A8E8"))
                            saveBitmap(bmp, cachedIconFile)
                        }
                    }
                }

                // 尝试从 NCSD/NCCH 提取 Product Code (例如 CTR-P-xxxx)
                val productCode = extract3dsProductCode(raf)
                val cleanFileName = cleanRomFileName(file.nameWithoutExtension)
                val finalTitle = when {
                    containsChinese(cleanFileName) && !containsChinese(titleFromSmdh) -> cleanFileName
                    titleFromSmdh.isNotEmpty() -> titleFromSmdh
                    cleanFileName.isNotEmpty() -> cleanFileName
                    else -> file.nameWithoutExtension
                }
                val finalSubtitle = when {
                    longTitleFromSmdh.isNotEmpty() && longTitleFromSmdh != finalTitle -> longTitleFromSmdh
                    titleFromSmdh.isNotEmpty() && titleFromSmdh != finalTitle -> titleFromSmdh
                    else -> "NINTENDO 3DS 软件"
                }

                return ParsedRomMetadata(
                    title = finalTitle,
                    subtitle = finalSubtitle,
                    publisher = publisherFromSmdh,
                    productCode = productCode,
                    iconCachePath = if (cachedIconFile.exists()) cachedIconFile.absolutePath else "",
                    primaryColor = primaryColor,
                    secondaryColor = Color.parseColor("#FFFFFF")
                )
            }
        } catch (_: Exception) {
            return fallbackMetadata(file, cachedIconFile, is3ds = true)
        }
    }

    /**
     * 在 .3ds / .cci / .cia 文件中快速定位 SMDH ("SMDH" = 0x53 0x4D 0x44 0x48)
     */
    private fun findSmdhOffset(raf: RandomAccessFile): Long {
        val len = raf.length()
        if (len < 0x36C0) return -1L

        // 1. 如果是 .cia，Meta 区通常位于文件末尾 (末尾 64KB 内包含 SMDH)
        if (len > 0x4000) {
            val tailSize = minOf(len, 65536L).toInt()
            val tailStart = len - tailSize
            val buf = ByteArray(tailSize)
            raf.seek(tailStart)
            raf.readFully(buf)
            val idx = indexOfSmdhMagic(buf)
            if (idx >= 0) return tailStart + idx
        }

        // 2. 检查文件头部前 2MB (适用于已解密 .3ds / .cci / .cxi)
        val headSize = minOf(len, 2 * 1024 * 1024L).toInt()
        val headBuf = ByteArray(headSize)
        raf.seek(0)
        raf.readFully(headBuf)
        val headIdx = indexOfSmdhMagic(headBuf)
        if (headIdx >= 0) return headIdx.toLong()

        return -1L
    }

    private fun indexOfSmdhMagic(buf: ByteArray): Int {
        val limit = buf.size - 0x36C0
        if (limit < 0) return -1
        var i = 0
        while (i <= limit) {
            if (buf[i] == 0x53.toByte() &&
                buf[i + 1] == 0x4D.toByte() &&
                buf[i + 2] == 0x44.toByte() &&
                buf[i + 3] == 0x48.toByte()
            ) {
                return i
            }
            i += 4
        }
        return -1
    }

    /**
     * 解码 3DS SMDH 48x48 RGB565 Morton Z-Order 曲线排列的图标
     */
    private fun decode3dsSmdhIcon48(iconBytes: ByteArray): Bitmap? {
        return try {
            val pixels = IntArray(48 * 48)
            var byteIdx = 0
            for (tileY in 0 until 6) {
                for (tileX in 0 until 6) {
                    for (morton in 0 until 64) {
                        val localX = compact1By1(morton)
                        val localY = compact1By1(morton shr 1)
                        val lo = iconBytes[byteIdx].toInt() and 0xFF
                        val hi = iconBytes[byteIdx + 1].toInt() and 0xFF
                        byteIdx += 2
                        val rgb565 = lo or (hi shl 8)
                        val r5 = (rgb565 shr 11) and 0x1F
                        val g6 = (rgb565 shr 5) and 0x3F
                        val b5 = rgb565 and 0x1F
                        val r8 = (r5 shl 3) or (r5 shr 2)
                        val g8 = (g6 shl 2) or (g6 shr 4)
                        val b8 = (b5 shl 3) or (b5 shr 2)

                        val x = tileX * 8 + localX
                        val y = tileY * 8 + localY
                        pixels[y * 48 + x] = Color.argb(255, r8, g8, b8)
                    }
                }
            }
            val rawBmp = Bitmap.createBitmap(pixels, 48, 48, Bitmap.Config.ARGB_8888)
            Bitmap.createScaledBitmap(rawBmp, 96, 96, true)
        } catch (_: Exception) {
            null
        }
    }

    private fun compact1By1(xInput: Int): Int {
        var x = xInput and 0x55555555
        x = (x or (x shr 1)) and 0x33333333
        x = (x or (x shr 2)) and 0x0F0F0F0F
        x = (x or (x shr 4)) and 0x00FF00FF
        x = (x or (x shr 8)) and 0x0000FFFF
        return x
    }

    private fun extract3dsProductCode(raf: RandomAccessFile): String {
        return try {
            if (raf.length() >= 0x4200) {
                raf.seek(0x4150)
                val codeBytes = ByteArray(16)
                raf.readFully(codeBytes)
                val code = String(codeBytes, StandardCharsets.US_ASCII)
                    .trim { it <= ' ' || it == '\u0000' }
                if (code.startsWith("CTR-") || code.startsWith("KTR-") || code.startsWith("NTR-")) {
                    code
                } else {
                    "CTR-P-3DS0"
                }
            } else {
                "CTR-P-3DS0"
            }
        } catch (_: Exception) {
            "CTR-P-3DS0"
        }
    }

    private fun fallbackMetadata(file: File, cachedIconFile: File, is3ds: Boolean): ParsedRomMetadata {
        val cleanTitle = cleanRomFileName(file.nameWithoutExtension)
        val palette = arrayOf(
            Color.parseColor("#E60012"),
            Color.parseColor("#00A8E8"),
            Color.parseColor("#2EC4B6"),
            Color.parseColor("#FF9F1C"),
            Color.parseColor("#7B2CBF"),
            Color.parseColor("#18B858")
        )
        val color = palette[Math.abs(cleanTitle.hashCode()) % palette.size]
        return ParsedRomMetadata(
            title = cleanTitle.ifEmpty { file.nameWithoutExtension },
            subtitle = if (is3ds) "NINTENDO 3DS 卡带" else "NINTENDO DS 卡带",
            publisher = "Nintendo",
            productCode = if (is3ds) "CTR-P-GAME" else "NTR-P-GAME",
            iconCachePath = if (cachedIconFile.exists()) cachedIconFile.absolutePath else "",
            primaryColor = color,
            secondaryColor = Color.WHITE
        )
    }

    private fun findSidecarCover(romFile: File): Bitmap? {
        val parent = romFile.parentFile ?: return null
        val base = romFile.nameWithoutExtension
        val exts = listOf("png", "jpg", "jpeg", "webp")
        val searchDirs = listOf(parent, File(parent, "covers"), File(parent, "icons"))
        for (dir in searchDirs) {
            if (!dir.exists()) continue
            for (e in exts) {
                val candidate = File(dir, "$base.$e")
                if (candidate.exists() && candidate.length() > 64) {
                    val bmp = BitmapFactory.decodeFile(candidate.absolutePath)
                    if (bmp != null) return Bitmap.createScaledBitmap(bmp, 96, 96, true)
                }
            }
        }
        return null
    }

    private fun saveBitmap(bmp: Bitmap, dest: File) {
        try {
            FileOutputStream(dest).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (_: Exception) {
        }
    }

    private fun extractDominantColor(bmp: Bitmap, fallback: Int): Int {
        return try {
            var rSum = 0L
            var gSum = 0L
            var bSum = 0L
            var count = 0L
            val step = maxOf(1, bmp.width / 8)
            for (y in 0 until bmp.height step step) {
                for (x in 0 until bmp.width step step) {
                    val c = bmp.getPixel(x, y)
                    if (Color.alpha(c) > 200) {
                        val r = Color.red(c)
                        val g = Color.green(c)
                        val b = Color.blue(c)
                        if ((r + g + b) in 60..700) {
                            rSum += r
                            gSum += g
                            bSum += b
                            count++
                        }
                    }
                }
            }
            if (count > 0) {
                Color.rgb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
            } else {
                fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    private fun decodeUtf16LeNullTerminated(bytes: ByteArray): String {
        var end = 0
        while (end + 1 < bytes.size) {
            if (bytes[end].toInt() == 0 && bytes[end + 1].toInt() == 0) {
                break
            }
            end += 2
        }
        if (end == 0) return ""
        return String(bytes, 0, end, StandardCharsets.UTF_16LE)
    }

    private fun cleanRomFileName(raw: String): String {
        return raw
            .replace(Regex("\\[[^\\]]*\\]"), "")
            .replace(Regex("\\([^\\)]*\\)"), "")
            .replace(Regex("^[0-9]{1,4}\\s*[-_.]\\s*"), "")
            .replace("_", " ")
            .trim()
    }

    private fun containsChinese(text: String): Boolean {
        return text.any { it.code in 0x4E00..0x9FFF }
    }

    private fun mapMakerCode(code: String): String {
        return when (code.uppercase()) {
            "01" -> "Nintendo"
            "08" -> "Capcom"
            "AF" -> "Bandai Namco"
            "8P" -> "SEGA"
            "GD" -> "Square Enix"
            "A4" -> "Konami"
            "EB" -> "Atlus"
            "E9" -> "Level-5"
            else -> "Nintendo"
        }
    }

    private fun md5(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            input.hashCode().toString()
        }
    }
}
