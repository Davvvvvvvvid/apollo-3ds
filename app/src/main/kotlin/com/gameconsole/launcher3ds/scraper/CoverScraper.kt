package com.gameconsole.launcher3ds.scraper

import android.content.Context
import com.gameconsole.launcher3ds.model.GridSlotItem
import com.gameconsole.launcher3ds.model.PlatformType
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

object CoverScraper {

    private val executor = Executors.newSingleThreadExecutor()

    fun getScrapedCoversDir(context: Context): File {
        return File(context.filesDir, "scraped_covers").apply { mkdirs() }
    }

    /**
     * 针对单款游戏进行在线封面检索并自动下载
     */
    fun scrapeCoverForSlot(
        context: Context,
        slot: GridSlotItem,
        onComplete: (Boolean, String, String) -> Unit
    ) {
        if (slot.isDemo || slot.type == PlatformType.EMPTY || slot.type == PlatformType.ANDROID_APP || slot.type == PlatformType.FOLDER) {
            onComplete(false, "", "该项目类型无需封面")
            return
        }

        executor.execute {
            val urls = getCandidateCoverUrls(slot)
            var successFile: File? = null

            for (urlStr in urls) {
                val f = downloadImage(context, urlStr, slot.id)
                if (f != null && f.exists() && f.length() > 512L) {
                    successFile = f
                    break
                }
            }

            val resultFile = successFile
            if (resultFile != null) {
                onComplete(true, resultFile.absolutePath, "成功获取封面！")
            } else {
                onComplete(false, "", "未检索到匹配的在线封面")
            }
        }
    }

    private fun getCandidateCoverUrls(slot: GridSlotItem): List<String> {
        val urls = mutableListOf<String>()
        val plat = slot.getEffectivePlatform().uppercase()
        val pCode = slot.productCode.trim()

        // 1. 针对 3DS 游戏：利用官方 CTR-P-xxxx Product Code 查询 GameTDB
        if (plat == "3DS" || slot.type == PlatformType.N3DS) {
            val rawCode = pCode.removePrefix("CTR-P-").removePrefix("CTR-").trim()
            if (rawCode.isNotBlank() && rawCode != "GAME" && rawCode != "3DS-ROM") {
                val regions = listOf("JA", "US", "EN", "ZH", "KO", "FR", "DE", "ES", "IT")
                for (reg in regions) {
                    urls.add("https://art.gametdb.com/3ds/coverHQ/$reg/$rawCode.jpg")
                    urls.add("https://art.gametdb.com/3ds/cover/$reg/$rawCode.jpg")
                    urls.add("https://art.gametdb.com/3ds/coverM/$reg/$rawCode.jpg")
                }
            }
        }

        // 2. 针对 NDS 游戏：利用 4 位 Game Code 查询 GameTDB
        if (plat == "NDS" || slot.type == PlatformType.NDS) {
            val rawCode = pCode.removePrefix("NTR-P-").removePrefix("NTR-").trim()
            if (rawCode.length in 4..6 && rawCode != "GAME" && rawCode != "NDS") {
                val regions = listOf("JA", "US", "EN", "ZH", "KO", "FR", "DE", "ES", "IT")
                for (reg in regions) {
                    urls.add("https://art.gametdb.com/ds/coverHQ/$reg/$rawCode.jpg")
                    urls.add("https://art.gametdb.com/ds/cover/$reg/$rawCode.jpg")
                    urls.add("https://art.gametdb.com/ds/coverM/$reg/$rawCode.jpg")
                }
            }
        }

        // 3. Libretro 开放缩略图数据库（覆盖 GBA, SFC, NES, PSP, PS1, MD 等）
        val libretroSystem = when (plat) {
            "GBA" -> "Nintendo - Game Boy Advance"
            "GBC" -> "Nintendo - Game Boy Color"
            "GB" -> "Nintendo - Game Boy"
            "SFC", "SNES" -> "Nintendo - Super Nintendo Entertainment System"
            "FC", "NES" -> "Nintendo - Nintendo Entertainment System"
            "N64" -> "Nintendo - Nintendo 64"
            "PSP" -> "Sony - PlayStation Portable"
            "PS1", "PSX" -> "Sony - PlayStation"
            "MD", "GENESIS" -> "Sega - Mega Drive - Genesis"
            "GG" -> "Sega - Game Gear"
            "SMS" -> "Sega - Master System - Mark III"
            "PCE" -> "NEC - PC Engine - TurboGrafx 16"
            "NDS" -> "Nintendo - Nintendo DS"
            "3DS" -> "Nintendo - Nintendo 3DS"
            else -> ""
        }

        if (libretroSystem.isNotBlank()) {
            val cleanTitle = cleanGameTitleForSearch(slot.title)
            val romBaseName = if (slot.romPath.isNotBlank()) File(slot.romPath).nameWithoutExtension else ""
            val cleanRom = cleanGameTitleForSearch(romBaseName)

            val titlesToTry = linkedSetOf<String>()
            if (cleanTitle.isNotBlank()) titlesToTry.add(cleanTitle)
            if (cleanRom.isNotBlank()) titlesToTry.add(cleanRom)
            if (slot.title.isNotBlank()) titlesToTry.add(slot.title)
            if (romBaseName.isNotBlank()) titlesToTry.add(romBaseName)

            for (t in titlesToTry) {
                val encodedSys = URLEncoder.encode(libretroSystem, "UTF-8").replace("+", "%20")
                val encodedName = URLEncoder.encode(t, "UTF-8").replace("+", "%20")
                urls.add("https://thumbnails.libretro.com/$encodedSys/Named_Boxarts/$encodedName.png")
            }
        }

        return urls
    }

    private fun cleanGameTitleForSearch(title: String): String {
        return title
            .replace(Regex("\\[[^\\]]*\\]"), "")
            .replace(Regex("\\([^\\)]*\\)"), "")
            .replace(Regex("【[^】]*】"), "")
            .replace("汉化版", "")
            .replace("中文版", "")
            .replace("最终版", "")
            .replace("重置版", "")
            .replace("重制版", "")
            .replace("修正版", "")
            .replace("完全版", "")
            .replace("完美版", "")
            .trim()
    }

    private fun downloadImage(context: Context, urlStr: String, slotId: String): File? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            conn.connect()

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val dir = getScrapedCoversDir(context)
                val safeId = slotId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val ext = if (urlStr.endsWith(".png", ignoreCase = true)) "png" else "jpg"
                val outFile = File(dir, "cover_${safeId}_${System.currentTimeMillis()}.$ext")
                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (outFile.exists() && outFile.length() > 512L) {
                    outFile
                } else {
                    outFile.delete()
                    null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
