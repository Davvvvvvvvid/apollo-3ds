package com.gameconsole.launcher3ds.storage

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Environment
import com.gameconsole.launcher3ds.emulator.EmulatorLauncher
import com.gameconsole.launcher3ds.model.FriendProfile
import com.gameconsole.launcher3ds.model.CustomCategoryItem
import com.gameconsole.launcher3ds.model.GameCategory
import com.gameconsole.launcher3ds.model.GameCategoryClassifier
import com.gameconsole.launcher3ds.model.GameNoteData
import com.gameconsole.launcher3ds.model.GridSlotItem
import com.gameconsole.launcher3ds.model.NotificationItem
import com.gameconsole.launcher3ds.model.PlatformType
import com.gameconsole.launcher3ds.model.ScanStorageScope
import com.gameconsole.launcher3ds.model.UpperDisplayMode
import com.gameconsole.launcher3ds.parser.RomHeaderParser
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class LauncherRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("launcher_3ds_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ==================== Pegasus metadata.pegasus.txt 元数据解析与游戏介绍引擎 ====================

    data class PegasusEntry(
        val game: String = "",
        val file: String = "",
        val developer: String = "",
        val description: String = "",
        val sortBy: String = ""
    )

    data class PegasusFileInspection(
        val platform: String,
        val filePath: String,
        val exists: Boolean,
        val fileSizeFormatted: String,
        val encodingName: String,
        val totalEntries: Int,
        val sampleEntries: List<PegasusEntry>,
        val rawTextSnippet: String,
        val allDetectedFilesForPlatform: List<String>
    )

    private val pegasusMetadataCache = java.util.concurrent.ConcurrentHashMap<String, PegasusEntry>()
    private val scannedPegasusFiles = java.util.Collections.synchronizedSet(HashSet<String>())

    fun registerPegasusFile(file: File?) {
        if (file == null || !file.exists() || !file.isFile || file.length() == 0L) return
        val path = file.absolutePath
        if (scannedPegasusFiles.contains(path)) return
        scannedPegasusFiles.add(path)
        val list = parsePegasusMetadataFile(file)
        for (e in list) {
            registerPegasusEntry(e)
        }
    }

    private fun registerPegasusEntry(e: PegasusEntry) {
        if (e.file.isNotBlank()) {
            val fLow = e.file.lowercase().trim()
            pegasusMetadataCache[fLow] = e
            pegasusMetadataCache[fLow.replace(" ", "")] = e
            val fNoExt = fLow.substringBeforeLast(".")
            pegasusMetadataCache[fNoExt] = e
            pegasusMetadataCache[fNoExt.replace(" ", "")] = e
            val fName = File(e.file).name.lowercase()
            pegasusMetadataCache[fName] = e
            pegasusMetadataCache[fName.replace(" ", "")] = e
            val fNameNoExt = File(e.file).nameWithoutExtension.lowercase()
            pegasusMetadataCache[fNameNoExt] = e
            pegasusMetadataCache[fNameNoExt.replace(" ", "")] = e
        }
        if (e.game.isNotBlank()) {
            val gLow = e.game.lowercase().trim()
            pegasusMetadataCache[gLow] = e
            pegasusMetadataCache[gLow.replace(" ", "")] = e
            val (cleanGame, _) = GameCategoryClassifier.splitGameFolderName(e.game)
            val cLow = cleanGame.lowercase().trim()
            pegasusMetadataCache[cLow] = e
            pegasusMetadataCache[cLow.replace(" ", "")] = e
        }
    }

    fun parsePegasusMetadataFile(file: File): List<PegasusEntry> {
        if (!file.exists() || !file.isFile) return emptyList()
        val list = ArrayList<PegasusEntry>()
        try {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return emptyList()
            // 智能编码识别：支持 UTF-8, 带 BOM 的 UTF-8, 以及 Windows 天马G常见的 GBK/GB18030 中文编码
            val (cleanBytes, charset) = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                bytes.copyOfRange(3, bytes.size) to Charsets.UTF_8
            } else {
                try {
                    val decoder = Charsets.UTF_8.newDecoder()
                    decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    decoder.decode(java.nio.ByteBuffer.wrap(bytes))
                    bytes to Charsets.UTF_8
                } catch (_: Exception) {
                    bytes to java.nio.charset.Charset.forName("GB18030")
                }
            }
            val lines = String(cleanBytes, charset).lines()
            var curGame = ""
            var curFile = ""
            var curDev = ""
            var curSortBy = ""
            val descLines = ArrayList<String>()
            var inDesc = false

            fun commit() {
                if (curGame.isNotBlank() || curFile.isNotBlank()) {
                    val fullDesc = descLines.joinToString("\n").trim()
                    list.add(
                        PegasusEntry(
                            game = curGame.trim(),
                            file = curFile.trim(),
                            developer = curDev.trim(),
                            description = fullDesc,
                            sortBy = curSortBy.trim()
                        )
                    )
                }
                curGame = ""
                curFile = ""
                curDev = ""
                curSortBy = ""
                descLines.clear()
                inDesc = false
            }

            for (rawLine in lines) {
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty()) {
                    if (inDesc) {
                        descLines.add("")
                    } else {
                        commit()
                    }
                    continue
                }
                if (rawLine.startsWith(" ") || rawLine.startsWith("\t")) {
                    if (inDesc) {
                        descLines.add(trimmed)
                    }
                    continue
                }
                val colonIdx = rawLine.indexOf(':')
                if (colonIdx > 0) {
                    val k = rawLine.substring(0, colonIdx).trim().lowercase()
                    val v = rawLine.substring(colonIdx + 1).trim()
                    when (k) {
                        "game" -> {
                            if (curGame.isNotBlank() || curFile.isNotBlank()) {
                                commit()
                            }
                            curGame = v
                            inDesc = false
                        }
                        "file" -> {
                            curFile = v
                            inDesc = false
                        }
                        "developer" -> {
                            curDev = v
                            inDesc = false
                        }
                        "sort-by" -> {
                            curSortBy = v
                            inDesc = false
                        }
                        "description" -> {
                            inDesc = true
                            if (v.isNotBlank()) {
                                descLines.add(v)
                            }
                        }
                        else -> {
                            inDesc = false
                        }
                    }
                } else if (inDesc) {
                    descLines.add(trimmed)
                }
            }
            commit()
        } catch (e: Exception) {
            android.util.Log.e("LauncherRepository", "Error parsing Pegasus metadata: ${file.absolutePath}", e)
        }
        return list
    }

    fun findPegasusMetadata(vararg rawKeys: String): PegasusEntry? {
        val lookupKeys = ArrayList<String>()
        for (k in rawKeys) {
            val trimmed = k.trim()
            if (trimmed.isNotBlank()) {
                val low = trimmed.lowercase()
                lookupKeys.add(low)
                lookupKeys.add(low.replace(" ", ""))
                val noExt = low.substringBeforeLast(".")
                lookupKeys.add(noExt)
                lookupKeys.add(noExt.replace(" ", ""))
                val (cleanK, _) = GameCategoryClassifier.splitGameFolderName(trimmed)
                val cLow = cleanK.lowercase()
                lookupKeys.add(cLow)
                lookupKeys.add(cLow.replace(" ", ""))
            }
        }

        // 1. 优先在内存高速缓存中精确查找
        for (k in lookupKeys) {
            val found = pegasusMetadataCache[k]
            if (found != null && (found.description.isNotBlank() || found.developer.isNotBlank())) {
                return found
            }
        }

        // 2. 缓存未命中时，即时扫描已知存储根目录与挂载目录下的 metadata.pegasus.txt
        val candidateDirs = LinkedHashSet<File>()
        for (root in getActiveStorageRoots()) {
            candidateDirs.add(root)
        }
        for (path in getCustomRomDirs(includeStorageRoots = true)) {
            candidateDirs.add(File(path))
        }
        candidateDirs.add(File("I:\\天马G整合包\\【08】3DS--142G\\3DS media\\3DS"))

        for (dir in candidateDirs) {
            if (!dir.exists()) continue
            val txtFile = if (dir.isDirectory) File(dir, "metadata.pegasus.txt") else dir
            if (txtFile.exists() && txtFile.isFile) {
                registerPegasusFile(txtFile)
            }
        }

        for (k in lookupKeys) {
            val found = pegasusMetadataCache[k]
            if (found != null && (found.description.isNotBlank() || found.developer.isNotBlank())) {
                return found
            }
        }

        // 3. 模糊互包含容错匹配（针对包含汉化版、序号、中英文标点差异的情况）
        val cleanQueries = rawKeys.map { 
            val (c, _) = GameCategoryClassifier.splitGameFolderName(it)
            c.lowercase().replace(" ", "").replace("（", "(").replace("）", ")").replace("：", ":")
        }.filter { it.length >= 2 }

        for ((cachedKey, entry) in pegasusMetadataCache) {
            if (entry.description.isBlank()) continue
            val normCached = cachedKey.replace(" ", "").replace("（", "(").replace("）", ")").replace("：", ":")
            for (q in cleanQueries) {
                if (normCached == q || (q.length >= 3 && normCached.contains(q)) || (normCached.length >= 3 && q.contains(normCached))) {
                    return entry
                }
            }
        }
        return null
    }

    fun resolveGameDescription(item: GridSlotItem?): String {
        if (item == null || item.type == PlatformType.EMPTY) return ""
        if (item.description.isNotBlank()) return item.description

        val folderName = if (item.boxArtPath.isNotBlank()) File(item.boxArtPath).parentFile?.name.orEmpty() else ""
        val romFileName = if (item.romPath.isNotBlank()) File(item.romPath).name else ""
        val (cleanTitle, _) = GameCategoryClassifier.splitGameFolderName(item.title.ifBlank { folderName })

        // 动态向上遍历最多 5 层检测各级父目录下的 metadata.pegasus.txt（精准覆盖 3DS/media 及上层机种包目录）
        if (item.boxArtPath.isNotBlank()) {
            val bFile = File(item.boxArtPath)
            var curr: File? = bFile.parentFile
            var count = 0
            while (curr != null && count < 5) {
                val pf = File(curr, "metadata.pegasus.txt")
                if (pf.exists() && pf.isFile) registerPegasusFile(pf)
                curr = curr.parentFile
                count++
            }
        }
        if (item.romPath.isNotBlank()) {
            val rFile = File(item.romPath)
            var curr: File? = rFile.parentFile
            var count = 0
            while (curr != null && count < 5) {
                val pf = File(curr, "metadata.pegasus.txt")
                if (pf.exists() && pf.isFile) registerPegasusFile(pf)
                curr = curr.parentFile
                count++
            }
        }

        val entry = findPegasusMetadata(folderName, cleanTitle, item.title, romFileName, item.romPath)
        if (entry != null) {
            if (entry.description.isNotBlank()) {
                item.description = entry.description
            }
            if (entry.developer.isNotBlank() && item.developer.isBlank()) {
                item.developer = entry.developer
            }
            return entry.description
        }
        return ""
    }

    /**
     * 针对指定平台 (如 3DS / NDS) 进行 metadata.pegasus.txt 探测、状态检查与内容预览
     */
    fun hasPegasusMetadata(platformId: String): Boolean {
        val norm = com.gameconsole.launcher3ds.model.PlatformCatalog.normalizeId(platformId).uppercase()
        synchronized(scannedPegasusFiles) {
            if (scannedPegasusFiles.any { it.uppercase().contains(norm) }) return true
        }
        for (root in getActiveStorageRoots()) {
            val r = root.absolutePath
            val candidates = when (norm) {
                "3DS" -> listOf(
                    "$r/roms/3ds/metadata.pegasus.txt",
                    "$r/roms/3DS/metadata.pegasus.txt",
                    "$r/3DS/metadata.pegasus.txt",
                    "$r/3DS media/3DS/metadata.pegasus.txt",
                    "$r/天马G整合包/【08】3DS--142G/3DS media/3DS/metadata.pegasus.txt",
                    "$r/天马G/3DS/media/metadata.pegasus.txt"
                )
                "NDS" -> listOf(
                    "$r/roms/nds/metadata.pegasus.txt",
                    "$r/roms/NDS/metadata.pegasus.txt",
                    "$r/NDS/metadata.pegasus.txt",
                    "$r/NDS media/NDS/metadata.pegasus.txt",
                    "$r/天马G整合包/【05】NDS--35G/NDS media/NDS/metadata.pegasus.txt",
                    "$r/天马G/NDS/media/metadata.pegasus.txt"
                )
                else -> listOf(
                    "$r/roms/$norm/metadata.pegasus.txt",
                    "$r/$norm/metadata.pegasus.txt"
                )
            }
            if (candidates.any { File(it).exists() }) return true
        }
        return false
    }

    private val pegasusInspectionCache = java.util.concurrent.ConcurrentHashMap<String, PegasusFileInspection>()

    fun inspectPegasusMetadataForPlatform(platformId: String, forceRefresh: Boolean = false): PegasusFileInspection {
        val normPlat = com.gameconsole.launcher3ds.model.PlatformCatalog.normalizeId(platformId).uppercase()
        if (!forceRefresh) {
            pegasusInspectionCache[normPlat]?.let { return it }
        }
        val allPegasus = LinkedHashSet<File>()

        // 1. 从已扫描注册的 Pegasus 文件列表中筛选
        synchronized(scannedPegasusFiles) {
            for (p in scannedPegasusFiles) {
                val f = File(p)
                if (f.exists() && f.isFile) allPegasus.add(f)
            }
        }

        // 2. 毫秒级检查常见候选路径（绝不遍历整张存储卡）
        for (root in getActiveStorageRoots()) {
            val r = root.absolutePath
            val candidates = when (normPlat) {
                "3DS" -> listOf(
                    "$r/天马G整合包/【08】3DS--142G/3DS media/3DS/metadata.pegasus.txt",
                    "$r/3DS media/3DS/metadata.pegasus.txt",
                    "$r/roms/3ds/metadata.pegasus.txt",
                    "$r/roms/3DS/metadata.pegasus.txt",
                    "$r/3DS/metadata.pegasus.txt",
                    "$r/天马G/3DS/metadata.pegasus.txt"
                )
                "NDS" -> listOf(
                    "$r/天马G整合包/【05】NDS--35G/NDS media/NDS/metadata.pegasus.txt",
                    "$r/NDS media/NDS/metadata.pegasus.txt",
                    "$r/roms/nds/metadata.pegasus.txt",
                    "$r/roms/NDS/metadata.pegasus.txt",
                    "$r/NDS/metadata.pegasus.txt",
                    "$r/天马G/NDS/metadata.pegasus.txt"
                )
                else -> listOf(
                    "$r/roms/$normPlat/metadata.pegasus.txt",
                    "$r/$normPlat/metadata.pegasus.txt"
                )
            }
            for (c in candidates) {
                val f = File(c)
                if (f.exists() && f.isFile) {
                    allPegasus.add(f)
                    registerPegasusFile(f)
                }
            }
        }

        // 3. 匹配对应平台的 metadata.pegasus.txt
        val matchedFiles = allPegasus.filter { f ->
            val pNorm = f.absolutePath.replace("\\", "/").uppercase()
            when (normPlat) {
                "3DS" -> pNorm.contains("3DS")
                "NDS" -> pNorm.contains("NDS") || pNorm.contains("DRASTIC")
                "GBA" -> pNorm.contains("GBA")
                "PSP" -> pNorm.contains("PSP")
                "PS1" -> pNorm.contains("PS1") || pNorm.contains("PSX")
                else -> pNorm.contains(normPlat)
            }
        }.sortedByDescending { it.length() }

        val primaryFile = matchedFiles.firstOrNull() ?: allPegasus.firstOrNull()

        if (primaryFile == null || !primaryFile.exists() || !primaryFile.isFile) {
            val tf = getDetectedTfCardPath()
            val expectedPath = if (tf.isNotBlank()) {
                "$tf/天马G整合包/【08】3DS--142G/3DS media/3DS/metadata.pegasus.txt"
            } else {
                "/storage/emulated/0/roms/3ds/metadata.pegasus.txt"
            }
            return PegasusFileInspection(
                platform = normPlat,
                filePath = expectedPath,
                exists = false,
                fileSizeFormatted = "0 KB",
                encodingName = "未找到文件",
                totalEntries = 0,
                sampleEntries = emptyList(),
                rawTextSnippet = "未在对应目录检测到 metadata.pegasus.txt。\n建议在天马G分包目录 (例如 3DS media/3DS/) 或 ROM 目录下放置该文件。",
                allDetectedFilesForPlatform = emptyList()
            )
        }

        val bytes = try { primaryFile.readBytes() } catch (_: Exception) { ByteArray(0) }
        val sizeKb = (bytes.size / 1024.0)
        val sizeStr = if (sizeKb >= 1024) String.format(java.util.Locale.US, "%.2f MB", sizeKb / 1024.0) else String.format(java.util.Locale.US, "%.1f KB", sizeKb)
        val (cleanBytes, encodingStr) = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size) to "UTF-8 (含BOM)"
        } else {
            try {
                val decoder = Charsets.UTF_8.newDecoder()
                decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                decoder.decode(java.nio.ByteBuffer.wrap(bytes))
                bytes to "UTF-8"
            } catch (_: Exception) {
                bytes to "GB18030 / GBK (Windows天马G)"
            }
        }

        val entries = parsePegasusMetadataFile(primaryFile)
        val rawSnippet = try {
            val text = String(cleanBytes, if (encodingStr.startsWith("UTF-8")) Charsets.UTF_8 else java.nio.charset.Charset.forName("GB18030"))
            text.lines().take(60).joinToString("\n")
        } catch (_: Exception) {
            "无法读取文件文本片段"
        }

        val inspection = PegasusFileInspection(
            platform = normPlat,
            filePath = primaryFile.absolutePath,
            exists = true,
            fileSizeFormatted = sizeStr,
            encodingName = encodingStr,
            totalEntries = entries.size,
            sampleEntries = entries,
            rawTextSnippet = rawSnippet,
            allDetectedFilesForPlatform = matchedFiles.map { it.absolutePath }
        )
        pegasusInspectionCache[normPlat] = inspection
        return inspection
    }

    private fun scanPegasusCandidatesInDir(dir: File, out: MutableSet<File>, maxDepth: Int) {
        if (maxDepth < 0 || !dir.exists() || !dir.isDirectory) return
        val list = dir.listFiles() ?: return
        for (f in list) {
            if (f.name.startsWith(".")) continue
            if (f.isFile && f.name.equals("metadata.pegasus.txt", ignoreCase = true)) {
                out.add(f)
                registerPegasusFile(f)
            } else if (f.isDirectory && !f.name.equals("Android", ignoreCase = true)) {
                val subPegasus = File(f, "metadata.pegasus.txt")
                if (subPegasus.exists() && subPegasus.isFile) {
                    out.add(subPegasus)
                    registerPegasusFile(subPegasus)
                }
                if (!f.name.equals("media", ignoreCase = true)) {
                    scanPegasusCandidatesInDir(f, out, maxDepth - 1)
                }
            }
        }
    }


    companion object {
        const val TOTAL_SLOTS = 180
        private const val KEY_SLOTS = "grid_slots_v2"
        private const val KEY_FOLDER_ITEMS = "folder_items_v2"
        private const val KEY_THEME_ID = "theme_id"
        private const val KEY_GRID_ROWS = "grid_rows"
        private const val KEY_SWAP_SCREENS = "swap_screens_v2"
        private const val KEY_SPLIT_PREVIEW = "split_preview_single_screen_v2"
        private const val KEY_SFX_ENABLED = "sfx_enabled"
        private const val KEY_BGM_ENABLED = "bgm_enabled"
        private const val KEY_CUSTOM_BGM_PATH = "custom_bgm_path"
        private const val KEY_DEFAULT_3DS_EMU = "default_3ds_emu"
        private const val KEY_DEFAULT_NDS_EMU = "default_nds_emu"
        private const val KEY_PLAY_COINS = "play_coins"
        private const val KEY_STEPS = "pedometer_steps"
        private const val KEY_FRIEND_PROFILE = "friend_profile"
        private const val KEY_GAME_NOTES = "game_notes_map"
        private const val KEY_NOTIFICATIONS = "notifications_list"
        private const val KEY_ROM_DIRS = "custom_rom_dirs"
        private const val KEY_CURRENT_CATEGORY = "current_category_id"
        private const val KEY_LAST_MOUNTED_MEDIA_DIR = "last_mounted_media_dir"
        private const val KEY_VIDEO_FILTER_ID = "upper_video_filter_id"
        private const val KEY_AUTO_RANDOM_INTERVAL_SEC = "auto_random_interval_sec"
        private const val KEY_UPPER_DISPLAY_MODE = "upper_display_mode_v3"
        private const val KEY_SHOW_UPPER_BOXART = "show_upper_boxart_v1"
        private const val KEY_SHOW_UPPER_LOGO = "show_upper_logo_v2"
        private const val KEY_ENABLED_PLATFORMS = "enabled_platforms_v1"
        private const val KEY_PLATFORM_EMULATORS = "platform_emulators_map_v1"
        private const val KEY_SFX_VOLUME = "sfx_volume_percent_v2"
        private const val KEY_DISABLED_MEDIA_LIBS = "disabled_media_libraries_v1"
        private const val KEY_CACHED_MEDIA_LIBRARIES = "cached_media_libraries_v3"
        private const val KEY_MANUAL_MOUNTED_DIRS = "manual_mounted_game_dirs_v2"
        private const val KEY_HIDE_GAMES_WITHOUT_COVER = "hide_games_without_cover_v1"
        private const val KEY_HIDE_GAMES_WITHOUT_ROM = "hide_games_without_rom_v1"
        private const val KEY_SUBSCREEN_COMPANION_ENABLED = "key_subscreen_companion_enabled"
        private const val KEY_SUBSCREEN_RETRO_FILTER_ENABLED = "subscreen_retro_filter_enabled"
        private const val KEY_SUBSCREEN_RETRO_FILTER_TYPE = "subscreen_retro_filter_type"
        private const val KEY_SUBSCREEN_RETRO_FILTER_OPACITY = "subscreen_retro_filter_opacity"
        private const val KEY_MAIN_SCREEN_RETRO_FILTER_ENABLED = "main_screen_retro_filter_enabled"
        private const val KEY_MAIN_SCREEN_RETRO_FILTER_TYPE = "main_screen_retro_filter_type"
        private const val KEY_MAIN_SCREEN_RETRO_FILTER_OPACITY = "main_screen_retro_filter_opacity"
        private const val KEY_MAIN_SCREEN_RETRO_FILTER_AUTO_2D = "main_screen_retro_filter_auto_2d"
        private const val KEY_SCAN_STORAGE_SCOPE = "scan_storage_scope_v1"
        private const val KEY_CUSTOM_CATEGORIES_LIST = "custom_categories_list_v2"
        private const val KEY_CATEGORY_INCLUSIONS = "category_inclusions_map_v2"
        private const val KEY_CATEGORY_EXCLUSIONS = "category_exclusions_map_v2"
    }

    var scanStorageScope: ScanStorageScope
        get() {
            val raw = prefs.getString(KEY_SCAN_STORAGE_SCOPE, ScanStorageScope.BOTH.id) ?: ScanStorageScope.BOTH.id
            return ScanStorageScope.values().firstOrNull { it.id == raw } ?: ScanStorageScope.BOTH
        }
        set(value) = prefs.edit().putString(KEY_SCAN_STORAGE_SCOPE, value.id).apply()

    fun getDisabledMediaLibs(): Set<String> {
        val raw = prefs.getString(KEY_DISABLED_MEDIA_LIBS, null)
        if (!raw.isNullOrBlank()) {
            try {
                val type = object : TypeToken<Set<String>>() {}.type
                val saved: Set<String>? = gson.fromJson(raw, type)
                if (saved != null) return saved
            } catch (_: Exception) {}
        }
        return emptySet()
    }

    fun setMediaLibraryEnabled(path: String, enabled: Boolean) {
        val norm = path.replace('\\', '/').trimEnd('/').lowercase()
        val current = getDisabledMediaLibs().map { it.replace('\\', '/').trimEnd('/').lowercase() }.toMutableSet()
        if (enabled) {
            current.remove(norm)
            // 清理可能误存的媒体路径或历史子目录
            current.removeIf { it.equals(norm, ignoreCase = true) || it.contains("media") }
        } else {
            current.add(norm)
        }
        prefs.edit().putString(KEY_DISABLED_MEDIA_LIBS, gson.toJson(current)).apply()

        // 立即同步更新 cachedMediaLibraries 内存状态（精确目录匹配）
        cachedMediaLibraries = cachedMediaLibraries?.map { item ->
            val itemNorm = item.path.replace('\\', '/').trimEnd('/').lowercase()
            val isDis = current.contains(itemNorm)
            item.copy(isEnabled = !isDis)
        }
    }

    fun setAllMediaLibrariesEnabled(enabled: Boolean) {
        if (enabled) {
            prefs.edit().remove(KEY_DISABLED_MEDIA_LIBS).apply()
            cachedMediaLibraries = cachedMediaLibraries?.map { it.copy(isEnabled = true) }
        } else {
            val allLibs = getMountedGameDirectories().map { it.replace('\\', '/').trimEnd('/').lowercase() }.toSet()
            prefs.edit().putString(KEY_DISABLED_MEDIA_LIBS, gson.toJson(allLibs)).apply()
            cachedMediaLibraries = cachedMediaLibraries?.map { it.copy(isEnabled = false) }
        }
    }

    var hideGamesWithoutCover: Boolean
        get() = prefs.getBoolean(KEY_HIDE_GAMES_WITHOUT_COVER, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HIDE_GAMES_WITHOUT_COVER, value).apply()
        }

    var hideGamesWithoutRom: Boolean
        get() = prefs.getBoolean(KEY_HIDE_GAMES_WITHOUT_ROM, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HIDE_GAMES_WITHOUT_ROM, value).apply()
        }

    private val validRomCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val validCoverCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun clearValidityCaches() {
        validRomCache.clear()
        validCoverCache.clear()
    }

    fun hasValidRom(slot: GridSlotItem): Boolean {
        if (slot.isDemo) {
            val rom = slot.romPath
            return rom.isNotBlank() && validRomCache.getOrPut(rom) {
                val f = File(rom)
                f.exists() && f.length() > 0
            }
        }
        if (slot.type == PlatformType.EMPTY) return false
        if (slot.type == PlatformType.ANDROID_APP || slot.type == PlatformType.FOLDER || slot.type == PlatformType.BADGE) return true
        val rom = slot.romPath
        if (rom.isNotBlank()) {
            return validRomCache.getOrPut(rom) {
                val f = File(rom)
                f.exists() && f.length() > 0
            }
        }
        return false
    }

    fun hasValidCover(slot: GridSlotItem): Boolean {
        if (slot.isDemo) return true
        if (slot.type == PlatformType.EMPTY) return false
        if (slot.type == PlatformType.ANDROID_APP || slot.type == PlatformType.FOLDER || slot.type == PlatformType.BADGE) return true
        val box = slot.boxArtPath
        if (box.isNotBlank()) {
            val ok = validCoverCache.getOrPut(box) {
                val f = File(box)
                f.exists() && f.length() > 0
            }
            if (ok) return true
        }
        val icon = slot.iconCachePath
        if (icon.isNotBlank()) {
            return validCoverCache.getOrPut(icon) {
                val f = File(icon)
                f.exists() && f.length() > 0
            }
        }
        return false
    }

    fun getMountedGameDirectories(): MutableList<String> {
        val raw = prefs.getString(KEY_MANUAL_MOUNTED_DIRS, null)
        if (raw != null) {
            try {
                val type = object : TypeToken<MutableList<String>>() {}.type
                val saved: MutableList<String>? = gson.fromJson(raw, type)
                if (saved != null) return saved
            } catch (_: Exception) {}
        }
        val initial = LinkedHashSet<String>()
        val slots = loadRootSlots()
        for (s in slots) {
            if (!s.isDemo && s.type != PlatformType.EMPTY && s.romPath.isNotBlank()) {
                val dir = extractEmulatorRootDir(s.romPath)
                if (dir.isNotBlank() && !dir.lowercase().contains("media")) {
                    val f = File(dir)
                    if (f.exists()) {
                        initial.add(dir)
                    }
                }
            }
        }
        val list = initial.toMutableList()
        saveMountedGameDirectories(list)
        return list
    }

    fun saveMountedGameDirectories(dirs: List<String>) {
        prefs.edit().putString(KEY_MANUAL_MOUNTED_DIRS, gson.toJson(dirs)).apply()
    }

    fun addMountedGameDirectory(path: String): Int {
        val norm = path.replace('\\', '/').trimEnd('/')
        val dirs = getMountedGameDirectories()
        if (!dirs.any { it.equals(norm, ignoreCase = true) }) {
            dirs.add(0, norm)
            saveMountedGameDirectories(dirs)
        }
        val disabled = getDisabledMediaLibs().toMutableSet()
        disabled.removeIf { it.equals(norm, ignoreCase = true) || it.contains("media") }
        prefs.edit().putString(KEY_DISABLED_MEDIA_LIBS, gson.toJson(disabled)).apply()

        addCustomRomDir(norm)
        lastMountedMediaDir = norm

        val detectedPlat = com.gameconsole.launcher3ds.model.PlatformCatalog.detectPlatform(File(norm))
        val enabled = getEnabledPlatforms().toMutableSet()
        if (detectedPlat.isNotBlank()) {
            enabled.add(detectedPlat)
            saveEnabledPlatforms(enabled)
        }

        val addedCount = scanSingleDirectoryAndMerge(File(norm))
        // 挂载后自动激活所有检测出的 ROM 机种，确保用户挂载后在桌面 100% 可见！
        val freshSlots = loadRootSlots()
        val allDiscoveredPlatforms = freshSlots.filter { !it.isDemo && it.type != PlatformType.EMPTY && matchesLibraryPath(it, norm) }
            .map { it.getEffectivePlatform() }.filter { it.isNotBlank() }.toSet()
        if (allDiscoveredPlatforms.isNotEmpty()) {
            val updated = getEnabledPlatforms().toMutableSet()
            updated.addAll(allDiscoveredPlatforms)
            saveEnabledPlatforms(updated)
        }
        getDiscoveredMediaLibraries(forceRefresh = true)
        return addedCount
    }

    fun removeMountedGameDirectory(path: String) {
        val norm = path.replace('\\', '/').trimEnd('/')
        val dirs = getMountedGameDirectories()
        dirs.removeIf { it.equals(norm, ignoreCase = true) }
        saveMountedGameDirectories(dirs)

        val disabled = getDisabledMediaLibs().toMutableSet()
        disabled.removeIf { it.equals(norm, ignoreCase = true) }
        prefs.edit().putString(KEY_DISABLED_MEDIA_LIBS, gson.toJson(disabled)).apply()

        val slots = loadRootSlots()
        var modified = false
        for (i in slots.indices) {
            val s = slots[i]
            if (!s.isDemo && s.type != PlatformType.EMPTY && matchesLibraryPath(s, norm)) {
                slots[i] = GridSlotItem(id = "empty_$i", slotIndex = i, type = PlatformType.EMPTY)
                modified = true
            }
        }
        if (modified) {
            saveRootSlots(slots)
        }
        getDiscoveredMediaLibraries(forceRefresh = true)
    }

    fun removeAllMountedGameDirectories() {
        saveMountedGameDirectories(emptyList())
        prefs.edit().remove(KEY_DISABLED_MEDIA_LIBS).apply()
        prefs.edit().remove(KEY_ROM_DIRS).apply()
        prefs.edit().remove(KEY_CACHED_MEDIA_LIBRARIES).apply()
        cachedMediaLibraries = emptyList()

        val slots = loadRootSlots()
        for (i in slots.indices) {
            val s = slots[i]
            if (!s.isDemo && s.type != PlatformType.EMPTY) {
                slots[i] = GridSlotItem(id = "empty_$i", slotIndex = i, type = PlatformType.EMPTY)
            }
        }
        saveRootSlots(slots)
    }

    data class ResolvedMediaAssets(
        val boxArtPath: String = "",
        val logoPath: String = "",
        val videoPath: String = "",
        val bannerAnimationPath: String = "",
        val cgfxPath: String = ""
    )

    fun normalizeForFuzzy(s: String): String {
        var str = s.lowercase().trim()
        // 移除 16 位十六进制 Title ID 前缀 (例如 0004000000076400 )
        str = str.replace(Regex("^[0-9a-f]{16}\\s*[-_]?\\s*"), "")
        // 移除纯数字编号前缀 (例如 1842 - )
        str = str.replace(Regex("^\\d{3,5}\\s*[-_.]?\\s*"), "")
        // 移除括号及其内容 (例如 (简), [CTR-P-AMKE], (汉化版), (USA), (v1.0))
        str = str.replace(Regex("\\([^)]*\\)"), "")
        str = str.replace(Regex("\\[[^\\]]*\\]"), "")
        str = str.replace(Regex("（[^）]*）"), "")
        str = str.replace(Regex("【[^】]*】"), "")
        // 常见译名统一规范化
        str = str.replace("马力欧", "马里奥")
        str = str.replace("宝可梦", "口袋妖怪").replace("精灵宝可梦", "口袋妖怪").replace("宠物小精灵", "口袋妖怪")
        str = str.replace("塞尔达传说", "塞尔达")
        // 去除所有标点符号与空白字符
        str = str.replace(Regex("[\\s\\p{Punct}·:：_\\-\\+]+"), "")
        return str
    }

    fun findCandidateMediaContainers(targetDir: File): List<File> {
        val containers = LinkedHashSet<File>()
        if (isSingleGameMediaDir(targetDir)) {
            targetDir.parentFile?.let { containers.add(it) }
        } else if (targetDir.name.equals("media", ignoreCase = true)) {
            containers.add(targetDir)
        }
        val subMedia = File(targetDir, "media")
        if (subMedia.exists() && subMedia.isDirectory) containers.add(subMedia)

        targetDir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                if (f.name.lowercase().contains("media") || f.name.equals("media", ignoreCase = true)) {
                    containers.add(f)
                    val sub = File(f, "media")
                    if (sub.exists() && sub.isDirectory) containers.add(sub)
                } else {
                    val subMedia = File(f, "media")
                    if (subMedia.exists() && subMedia.isDirectory) {
                        containers.add(subMedia)
                    }
                    val tianmaSub = File(f, "${f.name} media/${f.name}/media")
                    if (tianmaSub.exists() && tianmaSub.isDirectory) containers.add(tianmaSub)
                }
            }
        }

        val parent = targetDir.parentFile
        if (parent != null) {
            val pMedia = File(parent, "media")
            if (pMedia.exists() && pMedia.isDirectory) containers.add(pMedia)
            val tianmaMedia = File(parent, "3DS media/3DS/media")
            if (tianmaMedia.exists() && tianmaMedia.isDirectory) containers.add(tianmaMedia)
            val tianmaMedia2 = File(parent, "3DS media/media")
            if (tianmaMedia2.exists() && tianmaMedia2.isDirectory) containers.add(tianmaMedia2)
            val tianmaMedia3 = File(parent, "${targetDir.name} media/${targetDir.name}/media")
            if (tianmaMedia3.exists() && tianmaMedia3.isDirectory) containers.add(tianmaMedia3)
            val tianmaMedia4 = File(parent, "${targetDir.name} media/media")
            if (tianmaMedia4.exists() && tianmaMedia4.isDirectory) containers.add(tianmaMedia4)

            parent.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name.lowercase().contains("media")) {
                    containers.add(f)
                    val sub = File(f, "media")
                    if (sub.exists() && sub.isDirectory) containers.add(sub)
                    f.listFiles()?.forEach { subF ->
                        if (subF.isDirectory && (subF.name.equals(targetDir.name, ignoreCase = true) || subF.name.equals("media", ignoreCase = true))) {
                            containers.add(subF)
                            val subSub = File(subF, "media")
                            if (subSub.exists() && subSub.isDirectory) containers.add(subSub)
                        }
                    }
                }
            }
        }
        cachedMediaContainers?.let { containers.addAll(it) }
        return containers.filter { it.exists() && it.isDirectory }
    }

    /**
     * 为指定的 ROM 文件寻找最佳匹配的 media 媒体资源（封面、Logo、视频、横幅）
     * 采用多级模糊互包含容错算法，彻底解决版本标签、语言编号、TitleID 差异导致的封面缺失问题
     */
    fun resolveMediaAssetsForRom(
        romFile: File,
        metaTitle: String = "",
        productCode: String = ""
    ): ResolvedMediaAssets {
        val baseName = romFile.nameWithoutExtension
        val (cleanTitle, _) = GameCategoryClassifier.splitGameFolderName(metaTitle.ifBlank { baseName })
        val parent = romFile.parentFile

        // 1. 优先检查 ROM 文件同目录下是否有同名独立封面
        if (parent != null && parent.exists()) {
            val directBox = listOf(
                "$baseName.png", "$baseName.jpg", "$baseName.webp",
                "$cleanTitle.png", "$cleanTitle.jpg",
                "cover.png", "cover.jpg", "boxFront.png"
            ).map { File(parent, it) }.firstOrNull { it.exists() && it.length() > 0 }
            if (directBox != null) {
                val directLogo = File(parent, "logo.png").takeIf { it.exists() && it.length() > 0 }?.absolutePath ?: ""
                val directVideo = File(parent, "video.mp4").takeIf { it.exists() && it.length() > 0 }?.absolutePath ?: ""
                return ResolvedMediaAssets(
                    boxArtPath = directBox.absolutePath,
                    logoPath = directLogo,
                    videoPath = directVideo
                )
            }
        }

        // 2. 收集所有相关的候选 media 根目录 (优先就近和缓存，避免重复深度全盘扫描)
        val candidateMediaRoots = LinkedHashSet<File>(findCandidateMediaContainers(romFile))

        // 3. 构建待匹配的多级特征 Key
        val rawCandidates = listOf(baseName, metaTitle, cleanTitle).filter { it.isNotBlank() }
        val fuzzyQueries = rawCandidates.map { normalizeForFuzzy(it) }.filter { it.length >= 2 }.distinct()
        val cleanProductCode = productCode.replace("-", "").lowercase().trim()

        var bestMatchDir: File? = null
        var bestMatchScore = 0

        for (container in candidateMediaRoots) {
            val gameFolders = container.listFiles() ?: continue
            for (folder in gameFolders) {
                if (!folder.isDirectory) continue
                val fName = folder.name
                val folderFuzzy = normalizeForFuzzy(fName)

                var score = 0
                // 1) 检查 ProductCode
                if (cleanProductCode.length >= 4 && (fName.lowercase().contains(cleanProductCode) || folderFuzzy.contains(cleanProductCode))) {
                    score = maxOf(score, 120)
                }
                // 2) 检查模糊归一化完全一致
                for (q in fuzzyQueries) {
                    if (q == folderFuzzy) {
                        score = maxOf(score, 100)
                        break
                    }
                    if (q.length >= 3 && folderFuzzy.length >= 3) {
                        if (q.contains(folderFuzzy)) {
                            score = maxOf(score, 80 + folderFuzzy.length)
                        } else if (folderFuzzy.contains(q)) {
                            score = maxOf(score, 80 + q.length)
                        }
                    }
                }
                // 3) 检查原始名称互包含
                for (raw in rawCandidates) {
                    if (fName.equals(raw, ignoreCase = true)) {
                        score = maxOf(score, 110)
                        break
                    }
                }

                if (score > bestMatchScore) {
                    bestMatchScore = score
                    bestMatchDir = folder
                    if (score >= 110) break
                }
            }
            if (bestMatchScore >= 110) break
        }

        if (bestMatchDir != null && bestMatchDir.exists()) {
            val boxFile = listOf("boxFront.png", "boxfront.png", "boxFront.jpg", "cover.png", "cover.jpg", "front.png", "box.png")
                .map { File(bestMatchDir, it) }.firstOrNull { it.exists() && it.length() > 0 }
            val logoFile = listOf("logo.png", "wheel.png", "marquee.png", "title.png")
                .map { File(bestMatchDir, it) }.firstOrNull { it.exists() && it.length() > 0 }
            val videoFile = listOf("video.mp4", "preview.mp4", "snap.mp4", "video.webm")
                .map { File(bestMatchDir, it) }.firstOrNull { it.exists() && it.length() > 0 }
            val bannerFile = listOf("banner_anim.mp4", "banner.mp4", "bannerAnimation.mp4", "横幅渲染.mp4")
                .map { File(bestMatchDir, it) }.firstOrNull { it.exists() && it.length() > 0 }
            val cgfxFile = listOf("banner.cgfx", "banner_upper.cgfx")
                .map { File(bestMatchDir, it) }.firstOrNull { it.exists() && it.length() > 0 }

            if (boxFile != null || logoFile != null || videoFile != null) {
                return ResolvedMediaAssets(
                    boxArtPath = boxFile?.absolutePath ?: "",
                    logoPath = logoFile?.absolutePath ?: "",
                    videoPath = videoFile?.absolutePath ?: "",
                    bannerAnimationPath = bannerFile?.absolutePath ?: "",
                    cgfxPath = cgfxFile?.absolutePath ?: ""
                )
            }
        }

        return ResolvedMediaAssets()
    }

    fun rematchAllMissingCovers(): Int {
        val slots = loadRootSlots()
        var updated = 0
        for (item in slots) {
            if (item.isDemo || item.type == PlatformType.EMPTY || item.romPath.isBlank()) continue
            val romFile = File(item.romPath)
            if (!romFile.exists()) continue

            val boxMissing = item.boxArtPath.isBlank() || !File(item.boxArtPath).exists()
            val logoMissing = item.logoPath.isBlank() || !File(item.logoPath).exists()
            val videoMissing = item.videoPath.isBlank() || !File(item.videoPath).exists()
            val iconMissing = item.iconCachePath.isBlank() || !File(item.iconCachePath).exists()

            if (boxMissing || logoMissing || videoMissing || iconMissing) {
                val meta = RomHeaderParser.parseRomFile(context, romFile)
                val mediaAssets = resolveMediaAssetsForRom(romFile, meta.title, meta.productCode)

                var changed = false
                if (boxMissing) {
                    var newBox = mediaAssets.boxArtPath
                    if (newBox.isBlank() && meta.iconCachePath.isNotBlank() && File(meta.iconCachePath).exists()) {
                        newBox = meta.iconCachePath
                    }
                    if (newBox.isNotBlank()) {
                        item.boxArtPath = newBox
                        changed = true
                    }
                }
                if (iconMissing && meta.iconCachePath.isNotBlank() && File(meta.iconCachePath).exists()) {
                    item.iconCachePath = meta.iconCachePath
                    changed = true
                }
                if (logoMissing && mediaAssets.logoPath.isNotBlank()) {
                    item.logoPath = mediaAssets.logoPath
                    changed = true
                }
                if (videoMissing && mediaAssets.videoPath.isNotBlank()) {
                    item.videoPath = mediaAssets.videoPath
                    changed = true
                }
                if (mediaAssets.bannerAnimationPath.isNotBlank() && item.bannerAnimationPath.isBlank()) {
                    item.bannerAnimationPath = mediaAssets.bannerAnimationPath
                    changed = true
                }
                if (mediaAssets.cgfxPath.isNotBlank() && item.cgfxPath.isBlank()) {
                    item.cgfxPath = mediaAssets.cgfxPath
                    changed = true
                }
                if (changed) {
                    updated++
                }
            }
        }
        if (updated > 0) {
            saveRootSlots(slots)
        }
        return updated
    }

    fun scanSingleDirectoryAndMerge(dir: File): Int {
        if (!dir.exists() || !dir.isDirectory) return 0

        // 1. 搜寻 dir 及其关联路径下的所有 media 容器（天马G/Pegasus媒体包）
        val mediaContainers = findCandidateMediaContainers(dir)

        // 2. 搜寻 dir 及其关联路径下的所有 ROM 文件
        val validExtensions = setOf(
            "3ds", "cci", "cia", "cxi", "app",
            "nds", "dsi",
            "gba", "gbc", "gb",
            "nes", "unf", "fds",
            "sfc", "smc",
            "z64", "n64", "v64",
            "md", "gen", "bin",
            "iso", "cso", "chd", "cue", "pbp",
            "cdi", "gdi",
            "rvz", "wbfs", "gcm", "ciso",
            "nsp", "xci", "nsz",
            "vpk",
            "a26", "a78", "lnx",
            "min",
            "pce", "ws", "wsc",
            "zip", "7z"
        )
        val foundRomFiles = LinkedHashSet<File>()
        collectRomFiles(dir, validExtensions, foundRomFiles, maxDepth = 4)

        // 如果 dir 处于天马G分包目录，也到同级/上级 roms 目录搜索 ROM
        val parent = dir.parentFile
        if (parent != null) {
            val siblingRoms = listOf(File(parent, "roms"), File(parent, "Roms"), File(parent, "ROMs"))
            for (r in siblingRoms) {
                if (r.exists() && r.isDirectory) {
                    collectRomFiles(r, validExtensions, foundRomFiles, maxDepth = 4)
                }
            }
        }

        // 建立 ROM 索引 (精确文件名、清理后标题、模糊归一化键)
        val romByExact = HashMap<String, File>()
        val romByClean = HashMap<String, File>()
        val romByFuzzy = HashMap<String, File>()
        val romList = foundRomFiles.toList()

        for (rom in romList) {
            val baseName = rom.nameWithoutExtension.trim()
            romByExact[baseName.lowercase()] = rom
            val (cleanMain, _) = GameCategoryClassifier.splitGameFolderName(baseName)
            romByClean[cleanMain.lowercase().replace(" ", "")] = rom
            val fuzzy = normalizeForFuzzy(baseName)
            if (fuzzy.isNotBlank()) {
                romByFuzzy[fuzzy] = rom
            }
        }
        val romFuzzyPairs = romList.map { it to (romByFuzzy.entries.firstOrNull { e -> e.value == it }?.key ?: normalizeForFuzzy(it.nameWithoutExtension)) }

        // 3. 【核心：以媒体资源为准】从所有发现的 media 容器中提取游戏媒体文件夹
        data class MediaGameItem(
            val folderName: String,
            val mediaDir: File,
            val platformId: String
        )
        val mediaGameDirs = LinkedHashMap<String, MediaGameItem>()

        for (container in mediaContainers) {
            var curr: File? = container
            var count = 0
            while (curr != null && count < 5) {
                val pf = File(curr, "metadata.pegasus.txt")
                if (pf.exists() && pf.isFile) registerPegasusFile(pf)
                curr = curr.parentFile
                count++
            }
            val detectedPlatform = com.gameconsole.launcher3ds.model.PlatformCatalog.detectPlatform(container)
                .ifBlank { com.gameconsole.launcher3ds.model.PlatformCatalog.normalizeId(container.name) }
                .ifBlank { "3DS" }

            val children = container.listFiles() ?: continue
            for (child in children) {
                if (isSingleGameMediaDir(child)) {
                    val childPlat = com.gameconsole.launcher3ds.model.PlatformCatalog.detectPlatformFromPath(child.absolutePath)
                        .ifBlank { detectedPlatform }
                    val key = child.name.trim().lowercase()
                    mediaGameDirs.putIfAbsent(key, MediaGameItem(child.name.trim(), child, childPlat))
                }
            }
        }

        val slots = loadRootSlots()
        val matchedRomPaths = HashSet<String>()
        var addedOrUpdatedCount = 0

        // 4. 第一优先级【以媒体资源为准】：遍历所有媒体资源文件夹，保证 100% 拥有完整封面、Logo 与视频
        for ((_, mediaItem) in mediaGameDirs) {
            val folderName = mediaItem.folderName
            val mediaDir = mediaItem.mediaDir
            val itemPlatform = mediaItem.platformId
            val platformType = if (itemPlatform == "NDS") PlatformType.NDS else PlatformType.N3DS

            val boxFile = listOf("boxFront.png", "boxfront.png", "boxFront.jpg", "cover.png", "cover.jpg", "front.png", "box.png")
                .map { File(mediaDir, it) }.firstOrNull { it.exists() && it.length() > 0 }
            val logoFile = listOf("logo.png", "wheel.png", "marquee.png", "title.png")
                .map { File(mediaDir, it) }.firstOrNull { it.exists() && it.length() > 0 }
            val videoFile = listOf("video.mp4", "preview.mp4", "snap.mp4", "video.webm")
                .map { File(mediaDir, it) }.firstOrNull { it.exists() && it.length() > 0 }
            val bannerFile = listOf("banner_anim.mp4", "banner.mp4", "bannerAnimation.mp4", "横幅渲染.mp4")
                .map { File(mediaDir, it) }.firstOrNull { it.exists() && it.length() > 0 }
            val cgfxFile = listOf("banner.cgfx", "banner_upper.cgfx")
                .map { File(mediaDir, it) }.firstOrNull { it.exists() && it.length() > 0 }

            val (cleanTitle, versionTag) = GameCategoryClassifier.splitGameFolderName(folderName)
            val cleanKey = cleanTitle.lowercase().replace(" ", "")
            val folderKey = folderName.lowercase().replace(" ", "")
            val folderFuzzy = normalizeForFuzzy(folderName)

            // 辅以 ROM 资源：精确 -> 清理标题 -> 模糊互包含查找对应的 ROM 文件
            var matchedRom: File? = romByExact[folderName.lowercase()]
                ?: romByClean[cleanKey]
                ?: romByFuzzy[folderFuzzy]

            if (matchedRom == null) {
                val pegEntry = pegasusMetadataCache[folderName.lowercase()]
                    ?: pegasusMetadataCache[cleanKey]
                    ?: pegasusMetadataCache[folderKey]
                if (pegEntry != null && pegEntry.file.isNotBlank()) {
                    val targetRomName = File(pegEntry.file).nameWithoutExtension.lowercase()
                    matchedRom = romByExact[targetRomName]
                        ?: romByClean[targetRomName.replace(" ", "")]
                        ?: romList.firstOrNull { it.name.equals(File(pegEntry.file).name, ignoreCase = true) }
                }
            }

            if (matchedRom == null) {
                val folderFuzzyLen = folderFuzzy.length
                matchedRom = romFuzzyPairs.firstOrNull { (_, rFuzzy) ->
                    (rFuzzy.length >= 3 && folderFuzzyLen >= 3) &&
                    (rFuzzy.contains(folderFuzzy) || folderFuzzy.contains(rFuzzy))
                }?.first
            }

            val romAbsPath = matchedRom?.absolutePath ?: ""
            if (romAbsPath.isNotBlank()) {
                matchedRomPaths.add(romAbsPath.replace('\\', '/').trimEnd('/').lowercase())
            }

            val pegasusMeta = findPegasusMetadata(folderName, cleanTitle, folderKey, cleanKey, romAbsPath, matchedRom?.name ?: "")
            val itemDesc = pegasusMeta?.description.orEmpty()
            val itemDev = pegasusMeta?.developer.orEmpty()
            val (inferredPub, inferredColor) = GameCategoryClassifier.inferPublisherAndColor(cleanTitle)

            val existingSlot = slots.firstOrNull { s ->
                s.type != PlatformType.EMPTY && !s.isDemo &&
                (s.title.equals(cleanTitle, ignoreCase = true) ||
                 (romAbsPath.isNotBlank() && s.romPath.equals(romAbsPath, ignoreCase = true)) ||
                 (boxFile != null && s.boxArtPath.equals(boxFile.absolutePath, ignoreCase = true)))
            }

            if (existingSlot != null) {
                if (boxFile != null) existingSlot.boxArtPath = boxFile.absolutePath
                if (logoFile != null) existingSlot.logoPath = logoFile.absolutePath
                if (videoFile != null) existingSlot.videoPath = videoFile.absolutePath
                if (bannerFile != null) existingSlot.bannerAnimationPath = bannerFile.absolutePath
                if (cgfxFile != null) existingSlot.cgfxPath = cgfxFile.absolutePath
                if (romAbsPath.isNotBlank()) existingSlot.romPath = romAbsPath
                if (itemDesc.isNotBlank()) existingSlot.description = itemDesc
                if (itemDev.isNotBlank()) existingSlot.developer = itemDev
                addedOrUpdatedCount++
            } else {
                var emptySlotIdx = slots.indexOfFirst { it.type == PlatformType.EMPTY }
                if (emptySlotIdx < 0) {
                    emptySlotIdx = slots.size
                    slots.add(GridSlotItem(id = "empty_$emptySlotIdx", slotIndex = emptySlotIdx, type = PlatformType.EMPTY))
                }

                val newItem = GridSlotItem(
                    id = "media_${UUID.nameUUIDFromBytes(folderName.toByteArray())}",
                    slotIndex = emptySlotIdx,
                    type = platformType,
                    platformId = itemPlatform,
                    title = cleanTitle,
                    subtitle = "$versionTag  ·  ${com.gameconsole.launcher3ds.model.PlatformCatalog.getById(itemPlatform)?.name ?: inferredPub}",
                    publisher = if (itemDev.isNotBlank()) itemDev else inferredPub,
                    developer = itemDev,
                    description = itemDesc,
                    productCode = if (romAbsPath.isNotBlank()) "${itemPlatform}-ROM-READY" else "${itemPlatform}-MEDIA-PACK",
                    romPath = romAbsPath,
                    boxArtPath = boxFile?.absolutePath ?: "",
                    logoPath = logoFile?.absolutePath ?: "",
                    videoPath = videoFile?.absolutePath ?: "",
                    bannerAnimationPath = bannerFile?.absolutePath ?: "",
                    cgfxPath = cgfxFile?.absolutePath ?: "",
                    primaryColor = inferredColor,
                    secondaryColor = Color.WHITE,
                    isDemo = false
                )
                newItem.categoryId = GameCategoryClassifier.classifyCategory(newItem).id
                slots[emptySlotIdx] = newItem
                addedOrUpdatedCount++
            }
        }

        // 5. 第二优先级【ROM资源为辅】：遍历未在媒体库中出现的独立 ROM，以模糊匹配或内嵌图标补充
        val remainingRoms = foundRomFiles.filter {
            val norm = it.absolutePath.replace('\\', '/').trimEnd('/').lowercase()
            !matchedRomPaths.contains(norm)
        }

        for (romFile in remainingRoms) {
            val normRom = romFile.absolutePath.replace('\\', '/').trimEnd('/').lowercase()
            val existingSlot = slots.firstOrNull { s ->
                s.type != PlatformType.EMPTY && !s.isDemo &&
                s.romPath.replace('\\', '/').trimEnd('/').lowercase() == normRom
            }
            var emptySlotIdx = slots.indexOfFirst { it.type == PlatformType.EMPTY }
            if (emptySlotIdx < 0) {
                emptySlotIdx = slots.size
                slots.add(GridSlotItem(id = "empty_$emptySlotIdx", slotIndex = emptySlotIdx, type = PlatformType.EMPTY))
            }

            val itemPlatform = com.gameconsole.launcher3ds.model.PlatformCatalog.detectPlatform(romFile)
                .ifBlank { com.gameconsole.launcher3ds.model.PlatformCatalog.detectPlatformFromPath(romFile.parentFile?.absolutePath ?: "") }
                .ifBlank { "3DS" }
            val platformType = if (itemPlatform == "NDS") PlatformType.NDS else PlatformType.N3DS
            val meta = RomHeaderParser.parseRomFile(context, romFile)
            val (cleanTitle, versionTag) = GameCategoryClassifier.splitGameFolderName(meta.title.ifBlank { romFile.nameWithoutExtension })
            val (inferredPub, inferredColor) = GameCategoryClassifier.inferPublisherAndColor(cleanTitle)

            val mediaAssets = resolveMediaAssetsForRom(romFile, meta.title, meta.productCode)
            var finalBoxPath = mediaAssets.boxArtPath
            if (finalBoxPath.isBlank() && meta.iconCachePath.isNotBlank() && File(meta.iconCachePath).exists()) {
                finalBoxPath = meta.iconCachePath
            }
            val (matchedAssetIcon, matchedAssetPreview) = matchBuiltInArtwork(cleanTitle + " " + romFile.nameWithoutExtension, platformType)

            val romMeta = findPegasusMetadata(romFile.nameWithoutExtension, cleanTitle, romFile.absolutePath, romFile.name)
            val romDesc = romMeta?.description.orEmpty()
            val romDev = romMeta?.developer.orEmpty()

            if (existingSlot != null) {
                if (finalBoxPath.isNotBlank()) existingSlot.boxArtPath = finalBoxPath
                if (meta.iconCachePath.isNotBlank()) existingSlot.iconCachePath = meta.iconCachePath
                if (mediaAssets.logoPath.isNotBlank()) existingSlot.logoPath = mediaAssets.logoPath
                if (mediaAssets.videoPath.isNotBlank()) existingSlot.videoPath = mediaAssets.videoPath
                if (mediaAssets.bannerAnimationPath.isNotBlank()) existingSlot.bannerAnimationPath = mediaAssets.bannerAnimationPath
                if (mediaAssets.cgfxPath.isNotBlank()) existingSlot.cgfxPath = mediaAssets.cgfxPath
                if (romDesc.isNotBlank()) existingSlot.description = romDesc
                if (romDev.isNotBlank()) existingSlot.developer = romDev
                addedOrUpdatedCount++
                continue
            }

            val newItem = GridSlotItem(
                id = "rom_${UUID.randomUUID()}",
                slotIndex = emptySlotIdx,
                type = platformType,
                platformId = itemPlatform,
                title = cleanTitle,
                subtitle = meta.subtitle.ifBlank { "$versionTag  ·  ${com.gameconsole.launcher3ds.model.PlatformCatalog.getById(itemPlatform)?.name ?: inferredPub}" },
                publisher = if (romDev.isNotBlank()) romDev else if (meta.publisher == "Nintendo" && inferredPub != "Nintendo 3DS") inferredPub else meta.publisher,
                developer = romDev,
                description = romDesc,
                productCode = meta.productCode.ifBlank { "${itemPlatform}-ROM" },
                romPath = romFile.absolutePath,
                boxArtPath = finalBoxPath,
                iconCachePath = meta.iconCachePath,
                assetIconName = matchedAssetIcon,
                assetPreviewName = matchedAssetPreview,
                logoPath = mediaAssets.logoPath,
                videoPath = mediaAssets.videoPath,
                bannerAnimationPath = mediaAssets.bannerAnimationPath,
                cgfxPath = mediaAssets.cgfxPath,
                primaryColor = if (meta.primaryColor != 0) meta.primaryColor else inferredColor,
                secondaryColor = Color.WHITE,
                isDemo = false
            )
            newItem.categoryId = GameCategoryClassifier.classifyCategory(newItem).id
            slots[emptySlotIdx] = newItem
            addedOrUpdatedCount++
        }

        if (addedOrUpdatedCount > 0) {
            saveRootSlots(slots)
        }
        return addedOrUpdatedCount
    }

    /**
     * 获取用户在游戏库中勾选启用的游戏平台列表
     * 默认规则：只选中 3DS！若从未设置或为空，默认仅展示 3DS 平台游戏
     */
    /**
     * 获取用户在游戏库中勾选启用的游戏平台列表
     * 默认规则：默认仅选中 3DS，其他平台默认不选中；玩家可手动勾选开启其它平台模拟器
     */
    @Volatile
    private var cachedEnabledPlatforms: Set<String>? = null

    fun getEnabledPlatforms(): Set<String> {
        cachedEnabledPlatforms?.let { return it }
        val raw = prefs.getString(KEY_ENABLED_PLATFORMS, null)
        val result = if (!raw.isNullOrBlank()) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<Set<String>>() {}.type
                val parsed: Set<String>? = gson.fromJson(raw, type)
                if (parsed != null && parsed.isNotEmpty()) {
                    parsed.map { com.gameconsole.launcher3ds.model.PlatformCatalog.normalizeId(it) }.toSet()
                } else setOf("3DS")
            } catch (_: Exception) {
                setOf("3DS")
            }
        } else {
            setOf("3DS")
        }
        cachedEnabledPlatforms = result
        return result
    }

    fun saveEnabledPlatforms(platforms: Set<String>) {
        val normalized = platforms.map { com.gameconsole.launcher3ds.model.PlatformCatalog.normalizeId(it) }.toSet()
        cachedEnabledPlatforms = normalized
        prefs.edit().putString(KEY_ENABLED_PLATFORMS, gson.toJson(normalized)).apply()
    }

    fun clearPegasusInspectionCache() {
        pegasusInspectionCache.clear()
    }

    fun getPlatformEmulator(platformId: String): String {
        val p = com.gameconsole.launcher3ds.model.PlatformCatalog.normalizeId(platformId)
        val customMap = getPlatformEmulatorMap()
        if (customMap.containsKey(p)) {
            val pkg = customMap[p] ?: ""
            if (pkg.isNotBlank() && EmulatorLauncher.isPackageInstalled(context.packageManager, pkg)) return pkg
        }
        if (p == "3DS" && default3dsEmulatorPkg.isNotBlank() && EmulatorLauncher.isPackageInstalled(context.packageManager, default3dsEmulatorPkg)) return default3dsEmulatorPkg
        if (p == "NDS" && defaultNdsEmulatorPkg.isNotBlank() && EmulatorLauncher.isPackageInstalled(context.packageManager, defaultNdsEmulatorPkg)) return defaultNdsEmulatorPkg
        val autoDetected = EmulatorLauncher.findInstalledEmulatorForPlatform(context, p)
        if (autoDetected.isNotBlank()) {
            if (p == "3DS") default3dsEmulatorPkg = autoDetected
            if (p == "NDS") defaultNdsEmulatorPkg = autoDetected
            if (!customMap.containsKey(p) || !EmulatorLauncher.isPackageInstalled(context.packageManager, customMap[p].orEmpty())) {
                setPlatformEmulator(p, autoDetected)
            }
            return autoDetected
        }
        return com.gameconsole.launcher3ds.model.PlatformCatalog.getById(p)?.defaultEmulatorPkg ?: ""
    }

    fun setPlatformEmulator(platformId: String, pkg: String) {
        val p = com.gameconsole.launcher3ds.model.PlatformCatalog.normalizeId(platformId)
        val map = getPlatformEmulatorMap().toMutableMap()
        map[p] = pkg
        prefs.edit().putString(KEY_PLATFORM_EMULATORS, gson.toJson(map)).apply()
        if (p == "3DS") default3dsEmulatorPkg = pkg
        if (p == "NDS") defaultNdsEmulatorPkg = pkg
    }

    fun getPlatformEmulatorMap(): Map<String, String> {
        val raw = prefs.getString(KEY_PLATFORM_EMULATORS, null) ?: return emptyMap()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
            gson.fromJson(raw, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    var showUpperBoxart: Boolean
        get() = prefs.getBoolean(KEY_SHOW_UPPER_BOXART, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_UPPER_BOXART, value).apply()

    var showUpperLogo: Boolean
        get() = prefs.getBoolean(KEY_SHOW_UPPER_LOGO, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_UPPER_LOGO, value).apply()

    var upperDisplayMode: UpperDisplayMode
        get() {
            val id = prefs.getInt(KEY_UPPER_DISPLAY_MODE, -1)
            if (id in 0 until UpperDisplayMode.entries.size) {
                return UpperDisplayMode.fromId(id)
            }
            return UpperDisplayMode.BOXART_3D
        }
        set(mode) {
            val showBox = (mode == UpperDisplayMode.BOXART_3D || mode == UpperDisplayMode.DUAL_HYBRID)
            val showLogo = (mode != UpperDisplayMode.BOXART_3D && mode != UpperDisplayMode.CINEMA_PURE)
            prefs.edit()
                .putInt(KEY_UPPER_DISPLAY_MODE, mode.modeId)
                .putBoolean(KEY_SHOW_UPPER_BOXART, showBox)
                .putBoolean(KEY_SHOW_UPPER_LOGO, showLogo)
                .apply()
        }

    fun cycleNextUpperDisplayMode(): UpperDisplayMode {
        val cur = upperDisplayMode
        val allModes = UpperDisplayMode.entries
        val next = allModes[(cur.ordinal + 1) % allModes.size]
        upperDisplayMode = next
        return next
    }

    var subscreenCompanionEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUBSCREEN_COMPANION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SUBSCREEN_COMPANION_ENABLED, value).apply()

    var subscreenRetroFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUBSCREEN_RETRO_FILTER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SUBSCREEN_RETRO_FILTER_ENABLED, value).apply()

    var subscreenRetroFilterType: String
        get() = prefs.getString(KEY_SUBSCREEN_RETRO_FILTER_TYPE, "LCD_3X") ?: "LCD_3X"
        set(value) = prefs.edit().putString(KEY_SUBSCREEN_RETRO_FILTER_TYPE, value).apply()

    var subscreenRetroFilterOpacity: Int
        get() = prefs.getInt(KEY_SUBSCREEN_RETRO_FILTER_OPACITY, 20).coerceIn(10, 80)
        set(value) = prefs.edit().putInt(KEY_SUBSCREEN_RETRO_FILTER_OPACITY, value.coerceIn(10, 80)).apply()

    var mainScreenRetroFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MAIN_SCREEN_RETRO_FILTER_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_MAIN_SCREEN_RETRO_FILTER_ENABLED, value).apply()

    var mainScreenRetroFilterType: String
        get() = prefs.getString(KEY_MAIN_SCREEN_RETRO_FILTER_TYPE, "LCD_3X") ?: "LCD_3X"
        set(value) = prefs.edit().putString(KEY_MAIN_SCREEN_RETRO_FILTER_TYPE, value).apply()

    var mainScreenRetroFilterOpacity: Int
        get() = prefs.getInt(KEY_MAIN_SCREEN_RETRO_FILTER_OPACITY, 20).coerceIn(10, 80)
        set(value) = prefs.edit().putInt(KEY_MAIN_SCREEN_RETRO_FILTER_OPACITY, value.coerceIn(10, 80)).apply()

    var mainScreenRetroFilterAuto2D: Boolean
        get() = prefs.getBoolean(KEY_MAIN_SCREEN_RETRO_FILTER_AUTO_2D, true)
        set(value) = prefs.edit().putBoolean(KEY_MAIN_SCREEN_RETRO_FILTER_AUTO_2D, value).apply()

    var autoRandomIntervalSec: Int
        get() = prefs.getInt(KEY_AUTO_RANDOM_INTERVAL_SEC, 10).coerceIn(3, 120)
        set(value) = prefs.edit().putInt(KEY_AUTO_RANDOM_INTERVAL_SEC, value.coerceIn(3, 120)).apply()

    var videoFilterId: String
        get() = prefs.getString(KEY_VIDEO_FILTER_ID, "none") ?: "none"
        set(value) = prefs.edit().putString(KEY_VIDEO_FILTER_ID, value).apply()

    var currentCategoryId: String
        get() = prefs.getString(KEY_CURRENT_CATEGORY, GameCategory.ALL.id) ?: GameCategory.ALL.id
        set(value) = prefs.edit().putString(KEY_CURRENT_CATEGORY, value).apply()

    @Volatile
    private var cachedCustomCategories: MutableList<CustomCategoryItem>? = null
    @Volatile
    private var cachedCategoryInclusions: MutableMap<String, MutableSet<String>>? = null
    @Volatile
    private var cachedCategoryExclusions: MutableMap<String, MutableSet<String>>? = null

    fun getCustomCategories(): MutableList<CustomCategoryItem> {
        cachedCustomCategories?.let { return it }
        val raw = prefs.getString(KEY_CUSTOM_CATEGORIES_LIST, null)
        var list: MutableList<CustomCategoryItem>
        if (!raw.isNullOrBlank()) {
            try {
                val type = object : TypeToken<MutableList<CustomCategoryItem>>() {}.type
                val parsed: MutableList<CustomCategoryItem>? = gson.fromJson(raw, type)
                list = if (!parsed.isNullOrEmpty()) parsed else CustomCategoryItem.defaultCategories().toMutableList()
            } catch (_: Exception) {
                list = CustomCategoryItem.defaultCategories().toMutableList()
            }
        } else {
            list = CustomCategoryItem.defaultCategories().toMutableList()
        }
        val allItem = list.firstOrNull { it.id == "all" } ?: CustomCategoryItem.fromGameCategory(GameCategory.ALL, 0)
        list.removeAll { it.id == "all" }
        list.add(0, allItem)
        list.forEachIndexed { idx, it -> it.sortOrder = idx }
        cachedCustomCategories = list
        return list
    }

    fun saveCustomCategories(categories: List<CustomCategoryItem>) {
        val list = categories.toMutableList()
        val allItem = list.firstOrNull { it.id == "all" } ?: CustomCategoryItem.fromGameCategory(GameCategory.ALL, 0)
        list.removeAll { it.id == "all" }
        list.add(0, allItem)
        list.forEachIndexed { idx, it -> it.sortOrder = idx }
        cachedCustomCategories = list
        prefs.edit().putString(KEY_CUSTOM_CATEGORIES_LIST, gson.toJson(list)).apply()
    }

    fun addCustomCategory(
        displayName: String,
        isSeries: Boolean,
        symbol: String = "🏷️",
        color: Int = Color.parseColor("#0284C7")
    ): CustomCategoryItem {
        val categories = getCustomCategories()
        val id = "cat_" + UUID.randomUUID().toString().take(8)
        val shortName = if (displayName.length > 5) displayName.take(4) else displayName
        val engTag = if (isSeries) "SERIES" else "GENRE"
        val item = CustomCategoryItem(
            id = id,
            displayName = displayName,
            shortName = shortName,
            englishTag = engTag,
            accentColor = color,
            badgeSymbol = symbol,
            isBuiltIn = false,
            isSeries = isSeries,
            sortOrder = categories.size
        )
        categories.add(item)
        saveCustomCategories(categories)
        return item
    }

    fun updateCustomCategory(id: String, displayName: String, isSeries: Boolean, symbol: String, color: Int) {
        val categories = getCustomCategories()
        val target = categories.firstOrNull { it.id == id } ?: return
        target.displayName = displayName
        target.shortName = if (displayName.length > 5) displayName.take(4) else displayName
        target.isSeries = isSeries
        target.badgeSymbol = symbol
        target.accentColor = color
        saveCustomCategories(categories)
    }

    fun deleteCustomCategory(id: String): Boolean {
        val categories = getCustomCategories()
        val removed = categories.removeAll { it.id == id && !it.isBuiltIn && it.id != "all" }
        if (removed) {
            saveCustomCategories(categories)
        }
        return removed
    }

    fun moveCategoryUp(index: Int): Boolean {
        val categories = getCustomCategories()
        if (index <= 1 || index >= categories.size) return false
        val item = categories.removeAt(index)
        categories.add(index - 1, item)
        saveCustomCategories(categories)
        return true
    }

    fun moveCategoryDown(index: Int): Boolean {
        val categories = getCustomCategories()
        if (index <= 0 || index >= categories.size - 1) return false
        val item = categories.removeAt(index)
        categories.add(index + 1, item)
        saveCustomCategories(categories)
        return true
    }

    fun resetCategoriesToDefault() {
        val defaultList = CustomCategoryItem.defaultCategories().toMutableList()
        saveCustomCategories(defaultList)
    }

    fun getCategoryInclusionsMap(): MutableMap<String, MutableSet<String>> {
        cachedCategoryInclusions?.let { return it }
        val raw = prefs.getString(KEY_CATEGORY_INCLUSIONS, null)
        val map = mutableMapOf<String, MutableSet<String>>()
        if (!raw.isNullOrBlank()) {
            try {
                val type = object : TypeToken<Map<String, Set<String>>>() {}.type
                val parsed: Map<String, Set<String>>? = gson.fromJson(raw, type)
                parsed?.forEach { (k, v) -> map[k] = v.toMutableSet() }
            } catch (_: Exception) {}
        }
        cachedCategoryInclusions = map
        return map
    }

    fun getCategoryExclusionsMap(): MutableMap<String, MutableSet<String>> {
        cachedCategoryExclusions?.let { return it }
        val raw = prefs.getString(KEY_CATEGORY_EXCLUSIONS, null)
        val map = mutableMapOf<String, MutableSet<String>>()
        if (!raw.isNullOrBlank()) {
            try {
                val type = object : TypeToken<Map<String, Set<String>>>() {}.type
                val parsed: Map<String, Set<String>>? = gson.fromJson(raw, type)
                parsed?.forEach { (k, v) -> map[k] = v.toMutableSet() }
            } catch (_: Exception) {}
        }
        cachedCategoryExclusions = map
        return map
    }

    private fun saveCategoryInclusionsMap(map: Map<String, Set<String>>) {
        cachedCategoryInclusions = map.mapValues { it.value.toMutableSet() }.toMutableMap()
        prefs.edit().putString(KEY_CATEGORY_INCLUSIONS, gson.toJson(map)).apply()
    }

    private fun saveCategoryExclusionsMap(map: Map<String, Set<String>>) {
        cachedCategoryExclusions = map.mapValues { it.value.toMutableSet() }.toMutableMap()
        prefs.edit().putString(KEY_CATEGORY_EXCLUSIONS, gson.toJson(map)).apply()
    }

    fun getGameIdentifier(slot: GridSlotItem): String {
        return if (slot.romPath.isNotBlank()) slot.romPath.replace('\\', '/').trimEnd('/').lowercase() else slot.id
    }

    private val builtInCategoriesMap by lazy { GameCategory.entries.associateBy { it.id } }

    fun isGameInCategory(catId: String, slot: GridSlotItem): Boolean {
        if (catId == "all" || catId.isBlank()) return true

        val incMap = getCategoryInclusionsMap()
        val inclusions = incMap[catId]
        val excMap = getCategoryExclusionsMap()
        val exclusions = excMap[catId]

        if (!inclusions.isNullOrEmpty() || !exclusions.isNullOrEmpty()) {
            val key = getGameIdentifier(slot)
            if (inclusions != null) {
                if (inclusions.contains(key) || inclusions.contains(slot.id)) return true
                if (slot.title.isNotBlank() && inclusions.contains(slot.title.lowercase())) return true
            }
            if (exclusions != null) {
                if (exclusions.contains(key) || exclusions.contains(slot.id)) return false
                if (slot.title.isNotBlank() && exclusions.contains(slot.title.lowercase())) return false
            }
        }

        if (slot.categoryId.equals(catId, ignoreCase = true)) {
            return true
        }

        val builtIn = builtInCategoriesMap[catId]
        if (builtIn != null && builtIn != GameCategory.ALL) {
            return GameCategoryClassifier.resolveCategoryId(slot) == catId
        }
        return false
    }

    fun addGameToCategory(catId: String, slot: GridSlotItem) {
        if (catId == "all" || catId.isBlank()) return
        val key = getGameIdentifier(slot)
        val incMap = getCategoryInclusionsMap()
        val incSet = incMap.getOrPut(catId) { mutableSetOf() }
        incSet.add(key)
        incSet.add(slot.id)
        if (slot.title.isNotBlank()) incSet.add(slot.title.lowercase())
        saveCategoryInclusionsMap(incMap)

        val excMap = getCategoryExclusionsMap()
        excMap[catId]?.let {
            it.remove(key)
            it.remove(slot.id)
            if (slot.title.isNotBlank()) it.remove(slot.title.lowercase())
            saveCategoryExclusionsMap(excMap)
        }
    }

    fun removeGameFromCategory(catId: String, slot: GridSlotItem) {
        if (catId == "all" || catId.isBlank()) return
        val key = getGameIdentifier(slot)
        val incMap = getCategoryInclusionsMap()
        incMap[catId]?.let {
            it.remove(key)
            it.remove(slot.id)
            if (slot.title.isNotBlank()) it.remove(slot.title.lowercase())
            saveCategoryInclusionsMap(incMap)
        }

        val excMap = getCategoryExclusionsMap()
        val excSet = excMap.getOrPut(catId) { mutableSetOf() }
        excSet.add(key)
        excSet.add(slot.id)
        if (slot.title.isNotBlank()) excSet.add(slot.title.lowercase())
        saveCategoryExclusionsMap(excMap)

        if (slot.categoryId.equals(catId, ignoreCase = true)) {
            slot.categoryId = ""
            val slots = loadRootSlots()
            val match = slots.firstOrNull { it.id == slot.id }
            if (match != null) {
                match.categoryId = ""
                saveRootSlots(slots)
            }
        }
    }

    var lastMountedMediaDir: String
        get() = prefs.getString(KEY_LAST_MOUNTED_MEDIA_DIR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_MOUNTED_MEDIA_DIR, value).apply()

    var themeId: String
        get() = prefs.getString(KEY_THEME_ID, "basic_white") ?: "basic_white"
        set(value) = prefs.edit().putString(KEY_THEME_ID, value).apply()

    var gridRows: Int
        get() = prefs.getInt(KEY_GRID_ROWS, 2).coerceIn(1, 6)
        set(value) = prefs.edit().putInt(KEY_GRID_ROWS, value.coerceIn(1, 6)).apply()

    var swapScreens: Boolean
        get() = prefs.getBoolean(KEY_SWAP_SCREENS, false)
        set(value) = prefs.edit().putBoolean(KEY_SWAP_SCREENS, value).apply()

    var splitPreviewOnSingleScreen: Boolean
        get() = prefs.getBoolean(KEY_SPLIT_PREVIEW, false)
        set(value) = prefs.edit().putBoolean(KEY_SPLIT_PREVIEW, value).apply()

    var sfxVolume: Int
        get() = prefs.getInt(KEY_SFX_VOLUME, 100).coerceIn(0, 100)
        set(value) = prefs.edit().putInt(KEY_SFX_VOLUME, value.coerceIn(0, 100)).apply()

    var sfxEnabled: Boolean
        get() = prefs.getBoolean(KEY_SFX_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SFX_ENABLED, value).apply()

    var bgmEnabled: Boolean
        get() = prefs.getBoolean(KEY_BGM_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BGM_ENABLED, value).apply()

    var customBgmPath: String
        get() = prefs.getString(KEY_CUSTOM_BGM_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_BGM_PATH, value).apply()

    var default3dsEmulatorPkg: String
        get() = prefs.getString(KEY_DEFAULT_3DS_EMU, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEFAULT_3DS_EMU, value).apply()

    var defaultNdsEmulatorPkg: String
        get() = prefs.getString(KEY_DEFAULT_NDS_EMU, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEFAULT_NDS_EMU, value).apply()

    var playCoins: Int
        get() = prefs.getInt(KEY_PLAY_COINS, 128).coerceIn(0, 300)
        set(value) = prefs.edit().putInt(KEY_PLAY_COINS, value.coerceIn(0, 300)).apply()

    var stepsCount: Int
        get() = prefs.getInt(KEY_STEPS, 3650)
        set(value) = prefs.edit().putInt(KEY_STEPS, value.coerceAtLeast(0)).apply()

    fun loadRootSlots(): MutableList<GridSlotItem> {
        val raw = prefs.getString(KEY_SLOTS, null)
        if (!raw.isNullOrBlank()) {
            try {
                val type = object : TypeToken<MutableList<GridSlotItem>>() {}.type
                val parsed: MutableList<GridSlotItem> = gson.fromJson(raw, type)
                if (parsed.isNotEmpty()) {
                    val validItems = parsed.filterNotNull()
                    for (item in validItems) {
                        if ((item.customMainRetroFilter as String?).isNullOrBlank()) {
                            item.customMainRetroFilter = "AUTO"
                        }
                    }
                    // 彻底清除历史残留的假测试马里奥数据与无效的 I: 盘占位项，恢复纯净卡槽
                    val cleaned = validItems.mapIndexed { idx, item ->
                        if (item.id == "showcase_3ds_mario_3d_land" ||
                            item.romPath.startsWith("I:", ignoreCase = true) ||
                            (item.title.contains("马里奥 3D大陆", ignoreCase = true) && !File(item.romPath).exists())
                        ) {
                            GridSlotItem(id = "empty_$idx", slotIndex = idx, type = PlatformType.EMPTY)
                        } else {
                            item
                        }
                    }
                    val hasLegacyDemo = cleaned.any { it.isDemo || it.id == "folder_emulators" || it.id == "badge_slot_qblock" }
                    val finalItems: MutableList<GridSlotItem>
                    if (!hasLegacyDemo) {
                        finalItems = cleaned.toMutableList()
                    } else {
                        val gamesOnly = cleaned.filter { item ->
                            !item.isDemo &&
                                item.type != PlatformType.EMPTY &&
                                item.type != PlatformType.ANDROID_APP &&
                                item.id != "folder_emulators" &&
                                item.id != "badge_slot_qblock"
                        }
                        val targetSize = maxOf(TOTAL_SLOTS, gamesOnly.size + 12)
                        val result = MutableList(targetSize) { idx ->
                            GridSlotItem(id = "empty_$idx", slotIndex = idx, type = PlatformType.EMPTY)
                        }
                        for ((newIdx, game) in gamesOnly.withIndex()) {
                            val copy = game.copy(slotIndex = newIdx)
                            val eff = copy.getEffectivePlatform()
                            copy.platformId = eff
                            if ((copy.customMainRetroFilter as String?).isNullOrBlank()) {
                                copy.customMainRetroFilter = "AUTO"
                            }
                            copy.categoryId = GameCategoryClassifier.resolveCategoryId(copy)
                            result[newIdx] = copy
                        }
                        finalItems = result
                    }
                    // 确保所有槽位的 platformId 与 categoryId 均为最新推断值
                    for (item in finalItems) {
                        if (item.type != PlatformType.EMPTY && item.type != PlatformType.FOLDER && item.type != PlatformType.BADGE) {
                            val eff = item.getEffectivePlatform()
                            item.platformId = eff
                            item.categoryId = GameCategoryClassifier.resolveCategoryId(item)
                        }
                    }
                    saveRootSlots(finalItems)
                    return finalItems
                }
            } catch (e: Exception) {
                android.util.Log.e("LauncherRepository", "Error deserializing root slots, fallback to initial slots", e)
            }
        }
        val initial = createInitialSlots()
        saveRootSlots(initial)
        return initial
    }

    fun saveRootSlots(slots: List<GridSlotItem>) {
        val normalized = normalizeSlots(slots)
        prefs.edit().putString(KEY_SLOTS, gson.toJson(normalized)).apply()
    }

    fun loadFolderItems(): MutableList<GridSlotItem> {
        val raw = prefs.getString(KEY_FOLDER_ITEMS, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<GridSlotItem>>() {}.type
            val list: MutableList<GridSlotItem> = gson.fromJson(raw, type) ?: mutableListOf()
            list.forEach {
                if ((it.customMainRetroFilter as String?).isNullOrBlank()) {
                    it.customMainRetroFilter = "AUTO"
                }
            }
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveFolderItems(items: List<GridSlotItem>) {
        prefs.edit().putString(KEY_FOLDER_ITEMS, gson.toJson(items)).apply()
    }

    fun loadFriendProfile(): FriendProfile {
        val raw = prefs.getString(KEY_FRIEND_PROFILE, null) ?: return FriendProfile()
        return try {
            gson.fromJson(raw, FriendProfile::class.java) ?: FriendProfile()
        } catch (_: Exception) {
            FriendProfile()
        }
    }

    fun saveFriendProfile(profile: FriendProfile) {
        prefs.edit().putString(KEY_FRIEND_PROFILE, gson.toJson(profile)).apply()
    }

    fun loadGameNote(gameId: String, gameTitle: String): GameNoteData {
        val all = loadAllGameNotes()
        return all[gameId] ?: GameNoteData(gameId = gameId, gameTitle = gameTitle)
    }

    fun saveGameNote(note: GameNoteData) {
        val all = loadAllGameNotes()
        note.updatedAt = System.currentTimeMillis()
        all[note.gameId] = note
        prefs.edit().putString(KEY_GAME_NOTES, gson.toJson(all)).apply()
    }

    private fun loadAllGameNotes(): MutableMap<String, GameNoteData> {
        val raw = prefs.getString(KEY_GAME_NOTES, null) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, GameNoteData>>() {}.type
            gson.fromJson(raw, type) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    fun loadNotifications(): MutableList<NotificationItem> {
        val raw = prefs.getString(KEY_NOTIFICATIONS, null)
        if (!raw.isNullOrBlank()) {
            try {
                val type = object : TypeToken<MutableList<NotificationItem>>() {}.type
                val list: MutableList<NotificationItem> = gson.fromJson(raw, type)
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {
            }
        }
        val today = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
        val defaults = mutableListOf(
            NotificationItem(
                id = "notif_welcome",
                title = "欢迎使用 Nintendo 3DS 双屏桌面！",
                sender = "Nintendo 3DS 系统中心",
                dateStr = today,
                body = "• 上屏为无界沉浸状态栏与 3D 实机游戏预览舞台，下屏为触控卡带网格与五大 3DS 系统图标。\n• 点击下屏左上角「HOME菜单设置」可切换 6 款 3DS 主题、扫描本地 .3ds/.cia/.nds 游戏 ROM、一键互换上下屏映射！",
                isSpotPass = true
            ),
            NotificationItem(
                id = "notif_streetpass",
                title = "【擦肩通信】恭喜遇见双屏掌机玩家！",
                sender = "擦肩 Mii 广场",
                dateStr = today,
                body = "绿灯亮起！您已获得 128 枚 3DS 游戏币，可在下屏顶栏「Miiverse 与徽章」中将马力欧、塞尔达、精灵球等徽章装饰到桌面空白卡槽上。",
                isSpotPass = false
            )
        )
        saveNotifications(defaults)
        return defaults
    }

    fun saveNotifications(list: List<NotificationItem>) {
        prefs.edit().putString(KEY_NOTIFICATIONS, gson.toJson(list)).apply()
    }

    fun addNotification(title: String, sender: String, body: String, isSpotPass: Boolean = true) {
        val list = loadNotifications()
        val now = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
        list.add(
            0,
            NotificationItem(
                id = UUID.randomUUID().toString(),
                title = title,
                sender = sender,
                dateStr = now,
                body = body,
                isSpotPass = isSpotPass,
                isRead = false
            )
        )
        saveNotifications(list.take(30))
    }

    /**
     * 自动探测掌机上的所有存储根目录（内置存储 /storage/emulated/0 + 外置 TF/SD 卡 /storage/XXXX-XXXX）
     * 采用 StorageManager、/proc/mounts、环境变量、文件系统多重互补策略，确保 100% 识别 TF 扩展卡
     */
    fun discoverStorageRoots(): List<File> {
        val roots = LinkedHashSet<File>()

        // 1. 掌机内部存储 (/storage/emulated/0)
        try {
            val primary = Environment.getExternalStorageDirectory()
            if (primary != null) {
                roots.add(primary)
            }
        } catch (_: Exception) {}

        // 2. Android 官方 StorageManager.storageVolumes 机制 (Android 7.0+ 官方 API)
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager
            if (sm != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                for (vol in sm.storageVolumes) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        vol.directory?.let { dir ->
                            if (dir.exists()) roots.add(dir)
                        }
                    } else {
                        try {
                            val getPath = vol.javaClass.getMethod("getPath")
                            (getPath.invoke(vol) as? String)?.let { p ->
                                val f = File(p)
                                if (f.exists()) roots.add(f)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Android 掌机常见环境变量 SECONDARY_STORAGE / EXTERNAL_STORAGE
        try {
            System.getenv("SECONDARY_STORAGE")?.split(":")?.forEach { p ->
                val f = File(p.trim())
                if (f.exists()) roots.add(f)
            }
            System.getenv("EXTERNAL_STORAGE")?.let { p ->
                val f = File(p.trim())
                if (f.exists()) roots.add(f)
            }
        } catch (_: Exception) {}

        // 4. getExternalFilesDirs 挂载点截取 (去除 /Android/data/...)
        try {
            val extDirs = context.getExternalFilesDirs(null)
            for (d in extDirs) {
                if (d == null) continue
                val path = d.absolutePath
                val androidIdx = path.indexOf("/Android/")
                if (androidIdx > 0) {
                    val root = File(path.substring(0, androidIdx))
                    if (root.exists()) {
                        roots.add(root)
                    }
                }
            }
        } catch (_: Exception) {}

        // 5. 读取 Linux 标准内核挂载表 /proc/mounts (任何已挂载 TF卡/OTG盘皆在此清单中)
        try {
            val procMounts = File("/proc/mounts")
            if (procMounts.exists() && procMounts.canRead()) {
                procMounts.forEachLine { line ->
                    val tokens = line.split("\\s+".toRegex())
                    if (tokens.size >= 2) {
                        val mnt = tokens[1]
                        if (mnt.startsWith("/storage/") && !mnt.contains("emulated") && !mnt.contains("self") && !mnt.contains("knox")) {
                            val f = File(mnt)
                            if (f.exists()) roots.add(f)
                        } else if (mnt.startsWith("/mnt/media_rw/")) {
                            val f = File(mnt)
                            if (f.exists()) roots.add(f)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 6. /storage 目录遍历 (绝不可检查 child.canRead()，Android 11+ SELinux 下 access 会返回 false 但子目录可读)
        try {
            val storageParent = File("/storage")
            if (storageParent.exists()) {
                storageParent.listFiles()?.forEach { child ->
                    if (child.isDirectory &&
                        !child.name.equals("self", ignoreCase = true) &&
                        !child.name.equals("emulated", ignoreCase = true) &&
                        !child.name.equals("knox", ignoreCase = true)
                    ) {
                        roots.add(child)
                    }
                }
            }
        } catch (_: Exception) {}

        // 7. /mnt/media_rw 与 /mnt 目录遍历
        try {
            val mntParent = File("/mnt")
            if (mntParent.exists()) {
                File(mntParent, "media_rw").listFiles()?.forEach { if (it.isDirectory) roots.add(it) }
                mntParent.listFiles()?.forEach { child ->
                    val cName = child.name.lowercase()
                    if (child.isDirectory && (cName.contains("sd") || cName.contains("ext") || cName.contains("tf") || cName.contains("card"))) {
                        roots.add(child)
                    }
                }
            }
        } catch (_: Exception) {}

        // 8. PC / 调试环境盘符回退
        for (letter in listOf("I", "D", "E", "F", "G", "H")) {
            val winDrive = File("$letter:\\")
            if (winDrive.exists()) roots.add(winDrive)
        }

        val validRoots = roots.filter { it.exists() && it.isDirectory }
        android.util.Log.i("LauncherRepository", "Discovered storage roots count: ${validRoots.size}, paths: ${validRoots.map { it.absolutePath }}")
        return validRoots
    }

    fun isInternalStorage(file: File): Boolean {
        val path = file.absolutePath.replace("\\", "/")
        val primary = Environment.getExternalStorageDirectory()?.absolutePath?.replace("\\", "/") ?: "/storage/emulated/0"
        return path == primary || path.startsWith("/storage/emulated") || path.startsWith("/sdcard") || path.startsWith("/data")
    }

    fun isTfCard(file: File): Boolean {
        return !isInternalStorage(file)
    }

    fun getDetectedTfCardPath(): String {
        return discoverStorageRoots().firstOrNull { isTfCard(it) }?.absolutePath ?: ""
    }

    fun getActiveStorageRoots(): List<File> {
        val all = discoverStorageRoots()
        return when (scanStorageScope) {
            ScanStorageScope.INTERNAL_ONLY -> all.filter { isInternalStorage(it) }.ifEmpty { all }
            ScanStorageScope.SDCARD_ONLY -> all.filter { isTfCard(it) }.ifEmpty { all }
            ScanStorageScope.BOTH -> all
        }
    }

    fun getCustomRomDirs(includeStorageRoots: Boolean = false): MutableList<String> {
        val dirs = LinkedHashSet<String>()
        if (lastMountedMediaDir.isNotBlank()) {
            dirs.add(lastMountedMediaDir)
        }
        val raw = prefs.getString(KEY_ROM_DIRS, null)
        if (!raw.isNullOrBlank()) {
            try {
                val type = object : TypeToken<MutableList<String>>() {}.type
                val saved: MutableList<String>? = gson.fromJson(raw, type)
                if (!saved.isNullOrEmpty()) {
                    dirs.addAll(saved)
                }
            } catch (_: Exception) {
            }
        }
        if (!includeStorageRoots) {
            return dirs.toMutableList()
        }
        for (root in getActiveStorageRoots()) {
            if (!root.exists() || !root.isDirectory) continue
            val r = root.absolutePath
            dirs.add(r)
            // 自动遍历加入 TF 卡 / 存储根目录下的所有真实子文件夹
            root.listFiles()?.forEach { topDir ->
                if (topDir.isDirectory && !topDir.name.startsWith(".") && !topDir.name.equals("Android", ignoreCase = true)) {
                    dirs.add(topDir.absolutePath)
                    // 如果包含天马整合包或机种包，将其中的子包和媒体目录加入
                    val name = topDir.name
                    if (name.contains("天马") || name.contains("Pegasus") || name.contains("整合") || name.startsWith("【")) {
                        topDir.listFiles()?.forEach { sub ->
                            if (sub.isDirectory) {
                                dirs.add(sub.absolutePath)
                                dirs.add("${sub.absolutePath}/3DS media/3DS/media")
                                dirs.add("${sub.absolutePath}/3DS media/3DS")
                                dirs.add("${sub.absolutePath}/3DS media")
                                dirs.add("${sub.absolutePath}/media")
                                dirs.add("${sub.absolutePath}/roms")
                                dirs.add("${sub.absolutePath}/Roms")
                                dirs.add("${sub.absolutePath}/ROMs")
                                dirs.add("${sub.absolutePath}/3DS")
                                dirs.add("${sub.absolutePath}/NDS")
                            }
                        }
                    }
                }
            }
            dirs.add("$r/roms")
            dirs.add("$r/roms/3ds")
            dirs.add("$r/roms/3DS")
            dirs.add("$r/roms/3ds/media")
            dirs.add("$r/roms/3DS/media")
            dirs.add("$r/roms/media")
            dirs.add("$r/roms/nds")
            dirs.add("$r/roms/NDS")
            dirs.add("$r/roms/gba")
            dirs.add("$r/roms/GBA")
            dirs.add("$r/roms/psp")
            dirs.add("$r/roms/PSP")
            dirs.add("$r/roms/ps1")
            dirs.add("$r/roms/PS1")
            dirs.add("$r/roms/fc")
            dirs.add("$r/roms/sfc")
            dirs.add("$r/roms/md")
            dirs.add("$r/roms/arcade")
            dirs.add("$r/Roms")
            dirs.add("$r/Roms/3ds")
            dirs.add("$r/Roms/3DS")
            dirs.add("$r/Roms/3ds/media")
            dirs.add("$r/Roms/3DS/media")
            dirs.add("$r/Roms/NDS")
            dirs.add("$r/Roms/GBA")
            dirs.add("$r/Roms/PSP")
            dirs.add("$r/Roms/PS1")
            dirs.add("$r/Roms/FC")
            dirs.add("$r/Roms/SFC")
            dirs.add("$r/Roms/MD")
            dirs.add("$r/Roms/ARCADE")
            dirs.add("$r/ROMs")
            dirs.add("$r/ROMs/3ds")
            dirs.add("$r/ROMs/3DS")
            dirs.add("$r/ROMs/3DS/media")
            dirs.add("$r/ROMs/NDS")
            dirs.add("$r/3DS/media")
            dirs.add("$r/3ds/media")
            dirs.add("$r/3DS media/3DS/media")
            dirs.add("$r/3DS media/media")
            dirs.add("$r/media")
            dirs.add("$r/天马G/3DS/media")
            dirs.add("$r/天马G")
            dirs.add("$r/天马G整合包")
            dirs.add("$r/Pegasus/3DS/media")
            dirs.add("$r/Pegasus")
            dirs.add("$r/3DS")
            dirs.add("$r/3ds")
            dirs.add("$r/NDS")
            dirs.add("$r/nds")
            dirs.add("$r/GBA")
            dirs.add("$r/gba")
            dirs.add("$r/PSP")
            dirs.add("$r/psp")
            dirs.add("$r/PS1")
            dirs.add("$r/ps1")
            dirs.add("$r/Games")
            dirs.add("$r/Citra/roms")
            dirs.add("$r/DraStic/roms")
            dirs.add("$r/Download")

            // 智能检索存储根目录下的各整合包或游戏大文件夹 (深探至第 3 层)
            root.listFiles()?.forEach { child ->
                if (child.isDirectory && !child.name.startsWith(".") && !child.name.equals("Android", ignoreCase = true)) {
                    dirs.add(child.absolutePath)
                    val cName = child.name.lowercase()
                    if (cName.contains("天马") || cName.contains("pegasus") || cName.contains("rom") ||
                        cName.contains("game") || cName.contains("retro") || cName.contains("emu") ||
                        cName.contains("3ds") || cName.contains("nds") || cName.contains("gba")
                    ) {
                        child.listFiles()?.forEach { sub ->
                            if (sub.isDirectory && !sub.name.startsWith(".")) {
                                dirs.add(sub.absolutePath)
                                sub.listFiles()?.forEach { sub2 ->
                                    if (sub2.isDirectory && !sub2.name.startsWith(".")) {
                                        dirs.add(sub2.absolutePath)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return dirs.toMutableList()
    }

    fun addCustomRomDir(path: String) {
        val dirs = getCustomRomDirs()
        if (path.isNotBlank()) {
            dirs.remove(path)
            dirs.add(0, path)
            prefs.edit().putString(KEY_ROM_DIRS, gson.toJson(dirs)).apply()
        }
    }

    /**
     * 判断一个目录是否是包含 boxFront.png / logo.png / video.mp4 的单个游戏媒体目录
     */
    private fun isSingleGameMediaDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return File(dir, "boxFront.png").exists() ||
            File(dir, "boxfront.png").exists() ||
            File(dir, "boxFront.jpg").exists() ||
            File(dir, "cover.png").exists() ||
            File(dir, "logo.png").exists() ||
            File(dir, "wheel.png").exists() ||
            File(dir, "video.mp4").exists() ||
            File(dir, "preview.mp4").exists()
    }

    /**
     * 自动搜寻掌机中存放游戏媒体子目录的 `media` 根目录
     */
    @Volatile
    private var cachedMediaContainers: List<File>? = null

    fun findMediaContainerDirs(
        searchRoots: List<String> = getCustomRomDirs(includeStorageRoots = true),
        forceRefresh: Boolean = false
    ): List<File> {
        if (!forceRefresh && cachedMediaContainers != null) {
            return cachedMediaContainers!!
        }
        val mediaContainers = LinkedHashSet<File>()

        fun inspectDir(dir: File, depthLeft: Int) {
            if (depthLeft < 0 || !dir.exists() || !dir.isDirectory) return
            val name = dir.name
            if (name.startsWith(".") || name.equals("Android", ignoreCase = true)) return

            // 如果当前目录本身就是单个游戏的媒体目录，则其父目录就是 media 容器
            if (isSingleGameMediaDir(dir)) {
                dir.parentFile?.let { mediaContainers.add(it) }
                return
            }

            // 如果当前目录下有 "media" 子文件夹
            val subMedia = File(dir, "media")
            if (subMedia.exists() && subMedia.isDirectory) {
                val sampleSub = subMedia.listFiles()?.firstOrNull { isSingleGameMediaDir(it) }
                if (sampleSub != null) {
                    mediaContainers.add(subMedia)
                }
            }

            // 检查当前目录的子文件夹是否直接就是游戏媒体文件夹
            val children = dir.listFiles() ?: return
            var gameMediaChildCount = 0
            for (child in children) {
                if (child.isDirectory && isSingleGameMediaDir(child)) {
                    gameMediaChildCount++
                    if (gameMediaChildCount >= 1) {
                        mediaContainers.add(dir)
                        break
                    }
                }
            }

            if (depthLeft > 0) {
                for (child in children) {
                    if (child.isDirectory && !child.name.startsWith(".") && !child.name.equals("Android", ignoreCase = true)) {
                        inspectDir(child, depthLeft - 1)
                    }
                }
            }
        }

        for (path in searchRoots) {
            inspectDir(File(path), depthLeft = 7)
        }
        // 同时在各生效存储根目录做递归扫描（深度扩展至 7 层），确保放在深层天马G整合包媒体目录也能 100% 自动识别
        for (root in getActiveStorageRoots()) {
            inspectDir(root, depthLeft = 7)
        }
        val res = mediaContainers.toList()
        cachedMediaContainers = res
        return res
    }

    fun matchesLibraryPath(slot: GridSlotItem, libPath: String): Boolean {
        val normLib = libPath.replace('\\', '/').trimEnd('/').lowercase()
        val rom = slot.romPath.replace('\\', '/').trimEnd('/').lowercase()
        if (rom.isNotBlank() && (rom.startsWith("$normLib/") || rom == normLib)) {
            return true
        }
        val box = slot.boxArtPath.replace('\\', '/').trimEnd('/').lowercase()
        if (box.isNotBlank() && (box.startsWith("$normLib/") || box == normLib)) {
            return true
        }
        val idPath = slot.id.replace('\\', '/').trimEnd('/').lowercase()
        if (idPath.contains('/')) {
            if (idPath.startsWith("$normLib/") || idPath == normLib) return true
        }
        val libName = normLib.substringAfterLast('/')
        val libParent = normLib.substringBeforeLast('/')
        if (box.isNotBlank() && libName.isNotBlank() && libParent.isNotBlank()) {
            if (box.startsWith("$libParent/$libName media/") ||
                box.startsWith("$libParent/${libName}_media/") ||
                box.startsWith("$libParent/media/")
            ) {
                return true
            }
        }
        val detectedPlat = com.gameconsole.launcher3ds.model.PlatformCatalog.detectPlatform(File(normLib))
        if (detectedPlat.isNotBlank() && slot.getEffectivePlatform().equals(detectedPlat, ignoreCase = true)) {
            val isTfLib = normLib.contains("storage/") && !normLib.contains("emulated/0")
            val isTfSlot = (rom.isNotBlank() && rom.contains("storage/") && !rom.contains("emulated/0")) ||
                           (box.isNotBlank() && box.contains("storage/") && !box.contains("emulated/0"))
            if (isTfLib == isTfSlot) {
                return true
            }
        }
        val libFile = File(normLib)
        val parent = libFile.parentFile?.absolutePath?.replace('\\', '/')?.trimEnd('/')?.lowercase()
        if (parent != null && parent.isNotBlank()) {
            if (rom.startsWith("$parent/") || box.startsWith("$parent/")) {
                val libPlat = com.gameconsole.launcher3ds.model.PlatformCatalog.detectPlatform(libFile)
                if (libPlat.isBlank() || slot.getEffectivePlatform().equals(libPlat, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    fun generateLibraryDisplayName(file: File, platform: String): String {
        val folderName = file.name.ifBlank { file.parentFile?.name ?: "ROMs" }
        // 天马G 专用文件夹格式提取（如 【08】3DS--142G -> 3DS (142GB)）
        if (folderName.startsWith("【") && folderName.contains("】")) {
            val rest = folderName.substringAfter("】").trim()
            val plat = if (rest.contains("--")) rest.substringBefore("--").trim() else rest
            val capacity = if (rest.contains("--")) " (" + rest.substringAfter("--").trim() + ")" else ""
            if (plat.isNotBlank()) {
                val pNorm = com.gameconsole.launcher3ds.model.PlatformCatalog.normalizeId(plat)
                return "$pNorm 模拟器$capacity"
            }
        }
        val p = com.gameconsole.launcher3ds.model.PlatformCatalog.normalizeId(platform.ifBlank { folderName })
        val platDef = com.gameconsole.launcher3ds.model.PlatformCatalog.getById(p)
        return when (p.uppercase()) {
            "3DS" -> "Nintendo 3DS 模拟器"
            "NDS" -> "Nintendo DS 模拟器"
            "GBA" -> "Game Boy Advance 模拟器"
            "GBC" -> "Game Boy Color 模拟器"
            "GB" -> "Game Boy 模拟器"
            "N64" -> "Nintendo 64 模拟器"
            "FC" -> "FC / 红白机模拟器"
            "SFC" -> "SFC / 超任模拟器"
            "NGC" -> "GameCube (NGC) 模拟器"
            "WII" -> "Wii 模拟器"
            "SWITCH" -> "Nintendo Switch 模拟器"
            "PSP" -> "索尼 PSP 模拟器"
            "PS1" -> "索尼 PS1 模拟器"
            "PS2" -> "索尼 PS2 模拟器"
            "VITA" -> "索尼 PSV 模拟器"
            "MD" -> "世嘉 MD 模拟器"
            "DC" -> "世嘉 Dreamcast 模拟器"
            "SS" -> "世嘉土星 (Saturn) 模拟器"
            "GG" -> "世嘉 Game Gear 模拟器"
            "SMS" -> "世嘉 Master System 模拟器"
            "ARCADE" -> "经典街机模拟器"
            "NEOGEO" -> "SNK Neo Geo 模拟器"
            "PCE" -> "PC Engine 模拟器"
            "WSC" -> "神奇天鹅 (WonderSwan) 模拟器"
            "ATARI" -> "雅达利 (Atari) 模拟器"
            "DOS" -> "DOS 电脑模拟器"
            "POKEMINI" -> "宝可梦迷你模拟器"
            "MSX" -> "MSX 电脑模拟器"
            "ANDROID" -> "安卓原生游戏"
            else -> if (platDef != null) "${platDef.shortName} 模拟器" else "$folderName 模拟器"
        }
    }

    fun extractEmulatorRootDir(path: String): String {
        val norm = path.replace('\\', '/').trimEnd('/')
        val file = File(norm)
        val dir = if (file.isFile || norm.substringAfterLast('/').contains('.')) {
            file.parentFile ?: file
        } else {
            file
        }
        val dirNorm = dir.absolutePath.replace('\\', '/').trimEnd('/')
        val segs = dirNorm.split('/').filter { it.isNotBlank() }

        // 绝不将整卡或内置根目录作为模拟器目录
        // /storage/emulated/0 (3 segs) -> root
        // /storage/XXXX-XXXX (2 segs) -> root
        if ((dirNorm.startsWith("/storage/emulated") && segs.size <= 3) ||
            (dirNorm.startsWith("/storage/") && !dirNorm.startsWith("/storage/emulated") && segs.size <= 2) ||
            norm == "/sdcard" ||
            dirNorm == "/storage" ||
            dirNorm == "/storage/emulated"
        ) {
            return ""
        }

        // 1. 如果路径中包含天马G顶级格式，例如 "【08】3DS--142G"
        for (i in segs.indices) {
            val s = segs[i]
            if (s.startsWith("【") && s.contains("--")) {
                if (s.lowercase().contains("media")) return ""
                val prefix = if (dirNorm.startsWith("/")) "/" else ""
                return prefix + segs.subList(0, i + 1).joinToString("/")
            }
        }

        // 2. 如果包含 "roms/xxx"
        for (i in segs.indices) {
            val s = segs[i].lowercase()
            if (s == "roms" && i + 1 < segs.size) {
                val platSeg = segs[i + 1]
                if (!platSeg.lowercase().contains("media")) {
                    val prefix = if (dirNorm.startsWith("/")) "/" else ""
                    return prefix + segs.subList(0, i + 2).joinToString("/")
                }
            }
        }

        // 3. 直接返回包含该游戏的模拟器目录（彻底过滤包含 media 或 roms 单独一级的非叶子目录）
        val dirName = dir.name.lowercase()
        if (dirName == "media" || dirName.contains("media") || dirName == "roms" || dirName == "games") {
            return ""
        }
        return dirNorm
    }

    fun buildAndCacheMediaLibraries(slots: List<GridSlotItem>, mediaContainers: Collection<File> = emptyList()): List<com.gameconsole.launcher3ds.model.MediaLibraryItem> {
        return getDiscoveredMediaLibraries(forceRefresh = true)
    }

    @Volatile
    private var cachedMediaLibraries: List<com.gameconsole.launcher3ds.model.MediaLibraryItem>? = null

    /**
     * 获取所有用户手动挂载的游戏目录，包含模拟器专属显示名、游戏款数及启闭状态。
     */
    fun getDiscoveredMediaLibraries(forceRefresh: Boolean = false): List<com.gameconsole.launcher3ds.model.MediaLibraryItem> {
        if (!forceRefresh) {
            cachedMediaLibraries?.let { return it }
        }
        val mounted = getMountedGameDirectories()
        val disabledSet = getDisabledMediaLibs().map { it.replace('\\', '/').trimEnd('/').lowercase() }.toSet()
        val slots = loadRootSlots()

        val result = mutableListOf<com.gameconsole.launcher3ds.model.MediaLibraryItem>()
        for (path in mounted) {
            val file = File(path)
            val abs = file.absolutePath
            val norm = abs.replace('\\', '/').trimEnd('/')
            val normLower = norm.lowercase()

            val count = slots.count { slot ->
                !slot.isDemo && slot.type != PlatformType.EMPTY && matchesLibraryPath(slot, norm)
            }

            val platform = com.gameconsole.launcher3ds.model.PlatformCatalog.detectPlatform(file)
            val name = generateLibraryDisplayName(file, platform)
            val isEnabled = !disabledSet.contains(normLower)

            result.add(
                com.gameconsole.launcher3ds.model.MediaLibraryItem(
                    id = abs,
                    name = name,
                    path = abs,
                    platformName = platform,
                    gameCount = count,
                    isEnabled = isEnabled
                )
            )
        }
        cachedMediaLibraries = result
        try {
            prefs.edit().putString(KEY_CACHED_MEDIA_LIBRARIES, gson.toJson(result)).apply()
        } catch (_: Exception) {}
        return result
    }

    fun scanRomDirectories(
        customDir: String? = null,
        removeDemoCards: Boolean = false,
        onProgress: ((progressPercent: Int, statusText: String, detailText: String) -> Unit)? = null
    ): Int {
        clearPegasusInspectionCache()
        clearValidityCaches()
        cachedMediaLibraries = null
        onProgress?.invoke(5, "正在检测存储介质与 ROM 目录...", "扫描内置存储与 SD 卡挂载目录")
        if (!customDir.isNullOrBlank()) {
            addCustomRomDir(customDir)
            lastMountedMediaDir = customDir
        }
        val dirsToScan = if (!customDir.isNullOrBlank()) {
            (listOf(customDir) + getCustomRomDirs(includeStorageRoots = true)).distinct()
        } else {
            getCustomRomDirs(includeStorageRoots = true)
        }

        // 1. 收集所有支持的游戏 ROM 文件扩展名（涵盖 3DS、NDS、GBA、FC、SFC、MD、PSP、PS1、街机等）
        val validExtensions = setOf(
            "3ds", "cci", "cia", "cxi", "app",
            "nds", "dsi",
            "gba", "gbc", "gb",
            "nes", "unf", "fds",
            "sfc", "smc",
            "z64", "n64", "v64",
            "md", "gen", "bin",
            "iso", "cso", "chd", "cue", "pbp",
            "cdi", "gdi",
            "rvz", "wbfs", "gcm", "ciso",
            "nsp", "xci", "nsz",
            "vpk",
            "a26", "a78", "lnx",
            "min",
            "pce", "ws", "wsc",
            "zip", "7z"
        )
        val foundRomFiles = LinkedHashSet<File>()
        for (dirPath in dirsToScan) {
            val dir = File(dirPath)
            if (dir.exists() && dir.isDirectory) {
                collectRomFiles(dir, validExtensions, foundRomFiles, maxDepth = 4)
                dir.parentFile?.let { parent ->
                    if (parent.exists() && parent.isDirectory) {
                        collectRomFiles(parent, validExtensions, foundRomFiles, maxDepth = 2)
                    }
                }
            }
        }
        for (root in getActiveStorageRoots()) {
            collectRomFiles(root, validExtensions, foundRomFiles, maxDepth = 4)
            root.listFiles()?.forEach { topDir ->
                if (topDir.isDirectory && !topDir.name.startsWith(".") && !topDir.name.equals("Android", ignoreCase = true)) {
                    collectRomFiles(topDir, validExtensions, foundRomFiles, maxDepth = 4)
                }
            }
        }

        onProgress?.invoke(15, "正在检索支持的游戏 ROM 文件...", "已在存储介质中找到 ${foundRomFiles.size} 个 ROM 文件")

        // 建立 ROM 名称索引，方便与 media/<游戏名称> 自动配对
        val romByExactName = HashMap<String, File>()
        val romByCleanTitle = HashMap<String, File>()
        for (rom in foundRomFiles) {
            val rawName = rom.nameWithoutExtension.trim()
            romByExactName[rawName.lowercase()] = rom
            val (cleanMain, _) = GameCategoryClassifier.splitGameFolderName(rawName)
            romByCleanTitle[cleanMain.lowercase().replace(" ", "")] = rom
        }

        // 2. 搜寻所有包含 <游戏名>/{boxFront.png, logo.png, video.mp4} 的 media 目录 (自动排除用户已禁用的媒体库)
        val disabledLibs = getDisabledMediaLibs().map { it.replace("\\", "/").trimEnd('/').lowercase() }
        val mediaContainers = findMediaContainerDirs(dirsToScan).filter { container ->
            val norm = container.absolutePath.replace("\\", "/").trimEnd('/').lowercase()
            !disabledLibs.any { disabled -> norm.startsWith(disabled) }
        }
        if (mediaContainers.isNotEmpty()) {
            lastMountedMediaDir = mediaContainers.first().absolutePath
        }

        onProgress?.invoke(25, "正在识别游戏媒体资源包...", "已发现 ${mediaContainers.size} 个媒体资源库，准备解析封面与视频")

        data class ScannedMediaItem(val folderName: String, val mediaDir: File, val platformId: String)
        val gameMediaDirs = LinkedHashMap<String, ScannedMediaItem>()
        for (container in mediaContainers) {
            var curr: File? = container
            var count = 0
            while (curr != null && count < 5) {
                val pf = File(curr, "metadata.pegasus.txt")
                if (pf.exists() && pf.isFile) registerPegasusFile(pf)
                curr = curr.parentFile
                count++
            }
            val detectedPlatform = com.gameconsole.launcher3ds.model.PlatformCatalog.detectPlatform(container)
                .ifBlank { com.gameconsole.launcher3ds.model.PlatformCatalog.normalizeId(container.name) }
            val children = container.listFiles() ?: continue
            children.sortedBy { it.name }.forEach { child ->
                if (isSingleGameMediaDir(child)) {
                    val childPlat = com.gameconsole.launcher3ds.model.PlatformCatalog.detectPlatformFromPath(child.absolutePath)
                        .ifBlank { detectedPlatform }
                    val key = child.name.trim().lowercase()
                    gameMediaDirs.putIfAbsent(key, ScannedMediaItem(child.name.trim(), child, childPlat))
                }
            }
        }

        val slots = loadRootSlots()
        // 自动移除预置的内置演示卡带与测试占位项，保留真实扫描出的游戏
        for (i in slots.indices) {
            val s = slots[i]
            if (s.isDemo || s.type == PlatformType.ANDROID_APP || s.id == "folder_emulators" || s.id == "badge_slot_qblock" ||
                s.id == "showcase_3ds_mario_3d_land"
            ) {
                slots[i] = GridSlotItem(id = "empty_$i", slotIndex = i, type = PlatformType.EMPTY)
            }
        }

        // 建立已有卡槽索引（按媒体目录名或 ROM 路径匹配，支持增量更新而不重复创建）
        val slotIndexByTitleKey = HashMap<String, Int>()
        val existingRomPaths = HashSet<String>()
        for (i in slots.indices) {
            val s = slots[i]
            if (s.type != PlatformType.EMPTY) {
                slotIndexByTitleKey[s.title.lowercase().replace(" ", "")] = i
                if (s.romPath.isNotBlank()) existingRomPaths.add(s.romPath)
                if (s.boxArtPath.isNotBlank()) {
                    File(s.boxArtPath).parentFile?.name?.let { folderName ->
                        slotIndexByTitleKey[folderName.lowercase().replace(" ", "")] = i
                    }
                }
            }
        }

        var addedOrUpdatedCount = 0

        // 3. 挂载所有 media/<游戏名称> 游戏目录
        val totalMedia = gameMediaDirs.size
        var processedMedia = 0
        for ((_, itemInfo) in gameMediaDirs) {
            processedMedia++
            if (processedMedia % 2 == 0 || processedMedia == totalMedia) {
                val pct = 25 + ((processedMedia.toFloat() / totalMedia.coerceAtLeast(1)) * 50).toInt()
                onProgress?.invoke(pct, "正在解析游戏封面与视频 (${processedMedia}/$totalMedia)...", itemInfo.folderName)
            }
            val folderName = itemInfo.folderName
            val mediaDir = itemInfo.mediaDir
            var itemPlatform = itemInfo.platformId

            val boxFile = listOf("boxFront.png", "boxfront.png", "boxFront.jpg", "cover.png")
                .map { File(mediaDir, it) }
                .firstOrNull { it.exists() && it.length() > 0 }
            val logoFile = listOf("logo.png", "wheel.png", "marquee.png")
                .map { File(mediaDir, it) }
                .firstOrNull { it.exists() && it.length() > 0 }
            val videoFile = listOf("video.mp4", "preview.mp4", "video.webm")
                .map { File(mediaDir, it) }
                .firstOrNull { it.exists() && it.length() > 0 }
            val bannerFile = listOf("banner_anim.mp4", "banner.mp4", "bannerAnimation.mp4", "横幅渲染.mp4")
                .map { File(mediaDir, it) }
                .firstOrNull { it.exists() && it.length() > 0 }
            val cgfxFile = listOf("banner.cgfx", "banner_upper.cgfx")
                .map { File(mediaDir, it) }
                .firstOrNull { it.exists() && it.length() > 0 }

            val (cleanTitle, versionTag) = GameCategoryClassifier.splitGameFolderName(folderName)
            val cleanKey = cleanTitle.lowercase().replace(" ", "")
            val folderKey = folderName.lowercase().replace(" ", "")

            val matchedRom = romByExactName[folderName.lowercase()]
                ?: romByCleanTitle[cleanKey]
                ?: File(mediaDir.parentFile?.parentFile, "$folderName.zip").takeIf { it.exists() }
                ?: File(mediaDir.parentFile?.parentFile, "$folderName.7z").takeIf { it.exists() }
                ?: File(mediaDir.parentFile?.parentFile, "$folderName.cci").takeIf { it.exists() }
                ?: File(mediaDir.parentFile?.parentFile, "$folderName.3ds").takeIf { it.exists() }
                ?: File(mediaDir.parentFile?.parentFile, "$folderName.nds").takeIf { it.exists() }
                ?: File(mediaDir.parentFile?.parentFile, "$folderName.gba").takeIf { it.exists() }
                ?: File(mediaDir.parentFile?.parentFile, "$folderName.nes").takeIf { it.exists() }
                ?: File(mediaDir.parentFile?.parentFile, "$folderName.sfc").takeIf { it.exists() }
            val romAbsPath = matchedRom?.absolutePath ?: ""
            if (matchedRom != null) {
                val romPlat = com.gameconsole.launcher3ds.model.PlatformCatalog.detectPlatform(matchedRom)
                if (romPlat.isNotBlank()) {
                    itemPlatform = romPlat
                }
            }
            if (romAbsPath.isNotBlank()) {
                existingRomPaths.add(romAbsPath)
            }

            var bannerAnimPath = bannerFile?.absolutePath ?: ""
            var cgfxPath = cgfxFile?.absolutePath ?: ""
            if (matchedRom != null) {
                val romDir = matchedRom.parentFile
                if (romDir != null && romDir.exists()) {
                    val romCgfx = romDir.listFiles()?.firstOrNull { it.extension.equals("cgfx", true) && it.name.contains(cleanTitle, ignoreCase = true) }
                    val romBanner = romDir.listFiles()?.firstOrNull { it.extension.equals("mp4", true) && (it.name.contains("横幅", ignoreCase = true) || it.name.contains("渲染", ignoreCase = true) || it.name.contains("banner", ignoreCase = true)) }
                    if (romCgfx != null && cgfxPath.isBlank()) cgfxPath = romCgfx.absolutePath
                    if (romBanner != null && bannerAnimPath.isBlank()) bannerAnimPath = romBanner.absolutePath
                }
            }

            val (publisher, primaryColor) = GameCategoryClassifier.inferPublisherAndColor(folderName)
            val existingIdx = slotIndexByTitleKey[folderKey] ?: slotIndexByTitleKey[cleanKey]

            if (existingIdx != null && existingIdx in slots.indices) {
                val existing = slots[existingIdx]
                val changed = existing.boxArtPath != (boxFile?.absolutePath ?: "") ||
                    existing.logoPath != (logoFile?.absolutePath ?: "") ||
                    existing.videoPath != (videoFile?.absolutePath ?: "") ||
                    (bannerAnimPath.isNotBlank() && existing.bannerAnimationPath != bannerAnimPath) ||
                    (romAbsPath.isNotBlank() && existing.romPath != romAbsPath)
                existing.boxArtPath = boxFile?.absolutePath ?: existing.boxArtPath
                existing.logoPath = logoFile?.absolutePath ?: existing.logoPath
                existing.videoPath = videoFile?.absolutePath ?: existing.videoPath
                if (bannerAnimPath.isNotBlank()) existing.bannerAnimationPath = bannerAnimPath
                if (cgfxPath.isNotBlank()) existing.cgfxPath = cgfxPath
                if (romAbsPath.isNotBlank()) existing.romPath = romAbsPath
                val pegasusMeta = findPegasusMetadata(folderName, cleanTitle, folderKey, cleanKey, romAbsPath, matchedRom?.name ?: "")
                if (pegasusMeta != null) {
                    if (pegasusMeta.description.isNotBlank()) existing.description = pegasusMeta.description
                    if (pegasusMeta.developer.isNotBlank()) existing.developer = pegasusMeta.developer
                }
                existing.publisher = if (existing.developer.isNotBlank()) existing.developer else publisher
                existing.primaryColor = primaryColor
                existing.platformId = itemPlatform
                existing.categoryId = GameCategoryClassifier.classifyCategory(existing).id
                if (changed) addedOrUpdatedCount++
            } else {
                var emptySlotIdx = slots.indexOfFirst { it.type == PlatformType.EMPTY }
                if (emptySlotIdx < 0) {
                    emptySlotIdx = slots.size
                    slots.add(GridSlotItem(id = "empty_$emptySlotIdx", slotIndex = emptySlotIdx, type = PlatformType.EMPTY))
                }
                val pegasusMeta = findPegasusMetadata(folderName, cleanTitle, folderKey, cleanKey, romAbsPath, matchedRom?.name ?: "")
                val itemDesc = pegasusMeta?.description.orEmpty()
                val itemDev = pegasusMeta?.developer.orEmpty()
                val newItem = GridSlotItem(
                    id = "media_${UUID.nameUUIDFromBytes(folderName.toByteArray())}",
                    slotIndex = emptySlotIdx,
                    type = if (itemPlatform == "NDS") PlatformType.NDS else PlatformType.N3DS,
                    platformId = itemPlatform,
                    title = cleanTitle,
                    subtitle = "$versionTag  ·  ${com.gameconsole.launcher3ds.model.PlatformCatalog.getById(itemPlatform)?.name ?: publisher}",
                    publisher = if (itemDev.isNotBlank()) itemDev else publisher,
                    developer = itemDev,
                    description = itemDesc,
                    productCode = if (romAbsPath.isNotBlank()) "${itemPlatform}-ROM-READY" else "${itemPlatform}-MEDIA-PACK",
                    romPath = romAbsPath,
                    boxArtPath = boxFile?.absolutePath ?: "",
                    logoPath = logoFile?.absolutePath ?: "",
                    videoPath = videoFile?.absolutePath ?: "",
                    bannerAnimationPath = bannerAnimPath,
                    cgfxPath = cgfxPath,
                    primaryColor = primaryColor,
                    secondaryColor = Color.WHITE,
                    isDemo = false
                )
                newItem.categoryId = GameCategoryClassifier.classifyCategory(newItem).id
                slots[emptySlotIdx] = newItem
                slotIndexByTitleKey[folderKey] = emptySlotIdx
                slotIndexByTitleKey[cleanKey] = emptySlotIdx
                addedOrUpdatedCount++
            }
        }

        // 4. 挂载其余未在 media 目录中出现过的独立 ROM 文件
        val remainingRoms = foundRomFiles.filter { !existingRomPaths.contains(it.absolutePath) }
        val totalRoms = remainingRoms.size
        var processedRoms = 0
        for (romFile in remainingRoms) {
            processedRoms++
            if (processedRoms % 3 == 0 || processedRoms == totalRoms) {
                val pct = 75 + ((processedRoms.toFloat() / totalRoms.coerceAtLeast(1)) * 18).toInt()
                onProgress?.invoke(pct, "正在解析独立 ROM 文件 (${processedRoms}/$totalRoms)...", romFile.name)
            }
            val absPath = romFile.absolutePath

            var emptySlotIdx = slots.indexOfFirst { it.type == PlatformType.EMPTY }
            if (emptySlotIdx < 0) {
                emptySlotIdx = slots.size
                slots.add(GridSlotItem(id = "empty_$emptySlotIdx", slotIndex = emptySlotIdx, type = PlatformType.EMPTY))
            }

            val ext = romFile.extension.lowercase()
            val itemPlatform = com.gameconsole.launcher3ds.model.PlatformCatalog.detectPlatform(romFile)
                .ifBlank { com.gameconsole.launcher3ds.model.PlatformCatalog.detectPlatformFromPath(romFile.parentFile?.absolutePath ?: "") }
                .ifBlank { "3DS" }
            val platformType = if (itemPlatform == "NDS") PlatformType.NDS else PlatformType.N3DS
            val meta = RomHeaderParser.parseRomFile(context, romFile)
            val (cleanTitle, versionTag) = GameCategoryClassifier.splitGameFolderName(meta.title.ifBlank { romFile.nameWithoutExtension })
            val (inferredPub, inferredColor) = GameCategoryClassifier.inferPublisherAndColor(cleanTitle)
            val (matchedAssetIcon, matchedAssetPreview) = matchBuiltInArtwork(cleanTitle + " " + romFile.nameWithoutExtension, platformType)

            // 检查同级是否有 media/<romName> 以及 3DS CGFX / 横幅渲染动效
            val siblingMediaDir = File(romFile.parentFile, "media/${romFile.nameWithoutExtension}")
            var boxPath = File(siblingMediaDir, "boxFront.png").takeIf { it.exists() }?.absolutePath
                ?: File(siblingMediaDir, "boxfront.png").takeIf { it.exists() }?.absolutePath ?: ""
            var logoPath = File(siblingMediaDir, "logo.png").takeIf { it.exists() }?.absolutePath ?: ""
            var videoPath = File(siblingMediaDir, "video.mp4").takeIf { it.exists() }?.absolutePath ?: ""

            // 增强1：同目录下的同名封面文件 (如 <ROM名>.png / <ROM名>.jpg)
            val romParent = romFile.parentFile
            if (boxPath.isBlank() && romParent != null) {
                for (extImg in listOf("png", "jpg", "jpeg", "webp")) {
                    val sidecar = File(romParent, "${romFile.nameWithoutExtension}.$extImg")
                    if (sidecar.exists() && sidecar.length() > 0) {
                        boxPath = sidecar.absolutePath
                        break
                    }
                }
            }

            // 增强2：检查同级 covers / boxart / named_boxarts 常用封面文件夹
            if (boxPath.isBlank() && romParent != null) {
                for (subName in listOf("covers", "boxart", "boxarts", "named_boxarts")) {
                    val subDir = File(romParent, subName)
                    if (subDir.exists() && subDir.isDirectory) {
                        for (extImg in listOf("png", "jpg", "jpeg", "webp")) {
                            val imgFile = File(subDir, "${romFile.nameWithoutExtension}.$extImg")
                            if (imgFile.exists() && imgFile.length() > 0) {
                                boxPath = imgFile.absolutePath
                                break
                            }
                        }
                        if (boxPath.isNotBlank()) break
                    }
                }
            }

            // 增强3：智能模糊匹配天马/Pegasus媒体包目录 (针对用户下载的新汉化版ROM文件名轻微不同的情况)
            if (boxPath.isBlank() && gameMediaDirs.isNotEmpty()) {
                val cleanKey = cleanTitle.lowercase().replace(" ", "").replace("_", "").replace("-", "")
                val matchedMedia = gameMediaDirs.values.firstOrNull { m ->
                    val mClean = normalizeForFuzzy(m.folderName)
                    mClean.contains(cleanKey) || cleanKey.contains(mClean)
                }
                if (matchedMedia != null) {
                    val mDir = matchedMedia.mediaDir
                    val mBox = listOf("boxFront.png", "boxfront.png", "boxFront.jpg", "cover.png", "box.png")
                        .map { File(mDir, it) }
                        .firstOrNull { it.exists() && it.length() > 0 }
                    if (mBox != null) {
                        boxPath = mBox.absolutePath
                    }
                    val mLogo = listOf("logo.png", "wheel.png", "title.png")
                        .map { File(mDir, it) }
                        .firstOrNull { it.exists() && it.length() > 0 }
                    if (mLogo != null && logoPath.isBlank()) {
                        logoPath = mLogo.absolutePath
                    }
                    val mVideo = listOf("video.mp4", "snap.mp4")
                        .map { File(mDir, it) }
                        .firstOrNull { it.exists() && it.length() > 0 }
                    if (mVideo != null && videoPath.isBlank()) {
                        videoPath = mVideo.absolutePath
                    }
                }
            }

            val romDir = romFile.parentFile
            val romCgfx = romDir?.listFiles()?.firstOrNull { it.extension.equals("cgfx", true) && (it.name.contains(cleanTitle, ignoreCase = true) || it.name.contains("横幅", ignoreCase = true)) }
            val romBanner = romDir?.listFiles()?.firstOrNull { it.extension.equals("mp4", true) && (it.name.contains("横幅", ignoreCase = true) || it.name.contains("渲染", ignoreCase = true) || it.name.contains("banner", ignoreCase = true)) }
            val bannerAnimPath = romBanner?.absolutePath ?: ""
            val cgfxPath = romCgfx?.absolutePath ?: ""

            val romMeta = findPegasusMetadata(romFile.nameWithoutExtension, cleanTitle, absPath, romFile.name)
            val romDesc = romMeta?.description.orEmpty()
            val romDev = romMeta?.developer.orEmpty()
            val newItem = GridSlotItem(
                id = "rom_${UUID.randomUUID()}",
                slotIndex = emptySlotIdx,
                type = platformType,
                platformId = itemPlatform,
                title = cleanTitle,
                subtitle = meta.subtitle.ifBlank { "$versionTag  ·  ${com.gameconsole.launcher3ds.model.PlatformCatalog.getById(itemPlatform)?.name ?: inferredPub}" },
                publisher = if (romDev.isNotBlank()) romDev else if (meta.publisher == "Nintendo" && inferredPub != "Nintendo 3DS") inferredPub else meta.publisher,
                developer = romDev,
                description = romDesc,
                productCode = meta.productCode.ifBlank { "${itemPlatform}-ROM" },
                romPath = absPath,
                boxArtPath = boxPath,
                logoPath = logoPath,
                videoPath = videoPath,
                bannerAnimationPath = bannerAnimPath,
                cgfxPath = cgfxPath,
                iconCachePath = meta.iconCachePath,
                assetIconName = if (boxPath.isBlank()) matchedAssetIcon else "",
                assetPreviewName = if (videoPath.isBlank() && boxPath.isBlank()) matchedAssetPreview else "",
                primaryColor = inferredColor,
                secondaryColor = Color.WHITE,
                isDemo = false
            )
            newItem.categoryId = GameCategoryClassifier.classifyCategory(newItem).id
            slots[emptySlotIdx] = newItem
            existingRomPaths.add(absPath)
            addedOrUpdatedCount++
        }

        // 确保所有非空卡槽都有准确的分类 ID
        for (s in slots) {
            if (s.type != PlatformType.EMPTY && s.categoryId.isBlank()) {
                s.categoryId = GameCategoryClassifier.classifyCategory(s).id
            }
        }

        onProgress?.invoke(96, "正在整理卡槽并智能分类...", "已建立 ${addedOrUpdatedCount} 款游戏索引")
        saveRootSlots(slots)
        val currentMounted = getMountedGameDirectories()
        val newMounted = LinkedHashSet(currentMounted)
        for (s in slots) {
            if (!s.isDemo && s.type != PlatformType.EMPTY && s.romPath.isNotBlank()) {
                val dir = extractEmulatorRootDir(s.romPath)
                if (dir.isNotBlank() && !dir.lowercase().contains("media") && File(dir).exists()) {
                    newMounted.add(dir)
                }
            }
        }
        saveMountedGameDirectories(newMounted.toList())
        buildAndCacheMediaLibraries(slots, mediaContainers)
        onProgress?.invoke(100, "游戏库加载就绪", "共发现并挂载 $addedOrUpdatedCount 款游戏与媒体资源")
        if (addedOrUpdatedCount > 0) {
            addNotification(
                title = "成功挂载 3DS 媒体库与游戏 (+$addedOrUpdatedCount)",
                sender = "3DS 媒体资源挂载器",
                body = "已自动关联封面 (boxFront.png)、透明标题徽标 (logo.png) 与实机预览视频 (video.mp4)，并按系列完成分类！按 L1 / R1 即可切换底部分类。",
                isSpotPass = true
            )
        }
        return addedOrUpdatedCount
    }

    private fun matchBuiltInArtwork(query: String, platform: PlatformType): Pair<String, String> {
        val q = query.lowercase()
        return when {
            q.contains("塞尔达") || q.contains("时之笛") || q.contains("zelda") || q.contains("ocarina") ->
                "3ds_art/zelda_oot3d_icon.png" to "3ds_art/zelda_oot3d_preview.png"
            q.contains("3d大陆") || q.contains("3d land") ->
                "3ds_art/mario_3d_land_icon.png" to "3ds_art/mario_3d_land_preview.png"
            q.contains("赛车") || q.contains("kart") ->
                "3ds_art/mariokart7_icon.png" to "3ds_art/mariokart7_preview.png"
            q.contains("心金") || q.contains("魂银") || q.contains("heartgold") || q.contains("soulsilver") ->
                "3ds_art/nds_hgss_icon.png" to "3ds_art/nds_hgss_preview.png"
            q.contains("宝可梦") || q.contains("口袋妖怪") || q.contains("pokemon") || q.contains("ultra") ->
                "3ds_art/pokemon_usum_icon.png" to "3ds_art/pokemon_usum_preview.png"
            q.contains("怪物猎人") || q.contains("monster hunter") || q.contains("mhxx") || q.contains("mh4") ->
                "3ds_art/mhxx_3ds_icon.png" to "3ds_art/mhxx_3ds_preview.png"
            q.contains("火焰") || q.contains("纹章") || q.contains("fire emblem") || q.contains("awakening") ->
                "3ds_art/fe_awakening_icon.png" to "3ds_art/fe_awakening_preview.png"
            q.contains("动物森友会") || q.contains("动森") || q.contains("animal crossing") ->
                "3ds_art/animal_crossing_icon.png" to "3ds_art/animal_crossing_preview.png"
            q.contains("路易吉") || q.contains("鬼屋") || q.contains("luigi") ->
                "3ds_art/luigis_mansion_icon.png" to "3ds_art/luigis_mansion_preview.png"
            q.contains("逆转裁判") || q.contains("ace attorney") || q.contains("gyakuten") ->
                "3ds_art/nds_ace_attorney_icon.png" to "3ds_art/nds_ace_attorney_preview.png"
            q.contains("时空之轮") || q.contains("chrono") ->
                "3ds_art/nds_chrono_icon.png" to "3ds_art/nds_chrono_preview.png"
            q.contains("新超级马力欧") || q.contains("new super mario") ->
                "3ds_art/nds_nsmb_icon.png" to "3ds_art/nds_nsmb_preview.png"
            platform == PlatformType.NDS ->
                "3ds_art/nds_nsmb_icon.png" to "3ds_art/nds_nsmb_preview.png"
            else ->
                "3ds_art/mario_3d_land_icon.png" to "3ds_art/mario_3d_land_preview.png"
        }
    }

    private fun collectRomFiles(dir: File, exts: Set<String>, out: MutableCollection<File>, maxDepth: Int) {
        if (maxDepth < 0 || out.size >= 5000) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isFile) {
                val ext = child.extension.lowercase()
                if (ext in exts && child.length() > 1024) {
                    out.add(child)
                }
            } else if (child.isDirectory &&
                !child.name.startsWith(".") &&
                !child.name.equals("Android", ignoreCase = true) &&
                !child.name.equals("media", ignoreCase = true) &&
                !child.name.equals("DCIM", ignoreCase = true) &&
                !child.name.equals("Pictures", ignoreCase = true) &&
                !child.name.equals("Music", ignoreCase = true) &&
                !child.name.equals("Movies", ignoreCase = true) &&
                !child.name.equals("Alarms", ignoreCase = true) &&
                !child.name.equals("Ringtones", ignoreCase = true) &&
                !child.name.equals("Notifications", ignoreCase = true) &&
                !child.name.equals("Podcasts", ignoreCase = true) &&
                !child.name.equals("Tencent", ignoreCase = true)
            ) {
                collectRomFiles(child, exts, out, maxDepth - 1)
            }
        }
    }

    private fun normalizeSlots(input: List<GridSlotItem>): MutableList<GridSlotItem> {
        val targetSize = maxOf(TOTAL_SLOTS, (input.maxOfOrNull { it.slotIndex } ?: 0) + 12)
        val result = MutableList(targetSize) { idx ->
            GridSlotItem(id = "empty_$idx", slotIndex = idx, type = PlatformType.EMPTY)
        }
        for (item in input) {
            val idx = item.slotIndex.coerceIn(0, targetSize - 1)
            val copy = item.copy(slotIndex = idx)
            if ((copy.customMainRetroFilter as String?).isNullOrBlank()) {
                copy.customMainRetroFilter = "AUTO"
            }
            if (copy.type != PlatformType.EMPTY) {
                copy.categoryId = GameCategoryClassifier.resolveCategoryId(copy)
            }
            result[idx] = copy
        }
        return result
    }

    /**
     * 初始卡槽：包含演示游戏《超级马里奥 3D大陆》(带 3DS CGFX 离线渲染动效与双模切换)
     */
    fun createInitialSlots(): MutableList<GridSlotItem> {
        return MutableList(TOTAL_SLOTS) { idx ->
            GridSlotItem(id = "empty_$idx", slotIndex = idx, type = PlatformType.EMPTY)
        }
    }

    /**
     * 构建《超级马里奥 3D大陆》演示游戏槽位对象：
     * - ROM: 测试/超级马里奥 3D大陆 汉化版.cci
     * - 3DS 原版 CGFX 3D 模型文件: 测试/马里奥3D大陆_上屏横幅.cgfx
     * - 3DS 上屏横幅 60FPS 离线动效: 测试/马里奥3D大陆_横幅渲染.mp4
     * - 3DS 原版横幅封面模型: 测试/马里奥3D大陆_横幅封面.png
     * - 3DS 128x128 卡带图标: 测试/马里奥3D大陆_贴图_128x128.png
     * - 实机演示视频: 3DS media/3DS/media/超级马里奥 3D大陆 汉化版/video.mp4
     * - 官方透明 Logo: 3DS media/3DS/media/超级马里奥 3D大陆 汉化版/logo.png
     */
    fun createMario3DLandShowcaseSlot(slotIndex: Int = 0): GridSlotItem {
        val testDir = File("I:\\天马G整合包\\【08】3DS--142G\\测试")
        val mediaDir = File("I:\\天马G整合包\\【08】3DS--142G\\3DS media\\3DS\\media\\超级马里奥 3D大陆 汉化版")

        // 尝试从设备各存储挂载点动态查找真实 ROM 与资源
        var resolvedRomPath = ""
        var resolvedBannerPath = "asset://3ds_art/mario_3d_land_banner.mp4"
        var resolvedVideoPath = "asset://3ds_art/mario_3d_land_video.mp4"
        var resolvedCgfxPath = "asset://3ds_art/mario_3d_land_banner.cgfx"
        var resolvedBoxPath = ""
        var resolvedLogoPath = "asset://3ds_art/mario_3d_land_logo.png"
        var resolvedIconPath = ""

        // 1. 如果在 Windows 环境或已挂载 I: 盘
        val romCandidatesWin = listOf(
            File(testDir, "超级马里奥 3D大陆 汉化版.cci"),
            File(testDir, "马里奥 3D大陆 中文.cci"),
            File(testDir.parentFile, "超级马里奥 3D大陆 汉化版.cci")
        )
        romCandidatesWin.firstOrNull { it.exists() }?.let { resolvedRomPath = it.absolutePath }
        File(testDir, "马里奥3D大陆_横幅渲染.mp4").takeIf { it.exists() }?.let { resolvedBannerPath = it.absolutePath }
        File(testDir, "马里奥3D大陆_上屏横幅.cgfx").takeIf { it.exists() }?.let { resolvedCgfxPath = it.absolutePath }
        File(mediaDir, "video.mp4").takeIf { it.exists() }?.let { resolvedVideoPath = it.absolutePath }
        File(testDir, "马里奥3D大陆_横幅封面.png").takeIf { it.exists() }?.let { resolvedBoxPath = it.absolutePath }
        File(mediaDir, "logo.png").takeIf { it.exists() }?.let { resolvedLogoPath = it.absolutePath }
        File(testDir, "马里奥3D大陆_贴图_128x128.png").takeIf { it.exists() }?.let { resolvedIconPath = it.absolutePath }

        // 2. 如果在安卓掌机内部存储或 TF 卡中，智能检索存储根目录
        if (resolvedRomPath.isBlank()) {
            for (root in getActiveStorageRoots()) {
                val candidateRoms = listOf(
                    File(root, "roms/3ds/超级马里奥 3D大陆 汉化版.cci"),
                    File(root, "roms/3DS/超级马里奥 3D大陆 汉化版.cci"),
                    File(root, "Roms/3DS/超级马里奥 3D大陆 汉化版.cci"),
                    File(root, "测试/超级马里奥 3D大陆 汉化版.cci"),
                    File(root, "3DS/超级马里奥 3D大陆 汉化版.cci")
                )
                val found = candidateRoms.firstOrNull { it.exists() }
                if (found != null) {
                    resolvedRomPath = found.absolutePath
                    val dir = found.parentFile
                    val b = File(dir, "马里奥3D大陆_横幅渲染.mp4")
                    if (b.exists()) resolvedBannerPath = b.absolutePath
                    break
                }
            }
        }

        return GridSlotItem(
            id = "showcase_3ds_mario_3d_land",
            slotIndex = slotIndex,
            type = PlatformType.N3DS,
            platformId = "3DS",
            title = "超级马里奥 3D大陆",
            subtitle = "Super Mario 3D Land (3DS 原版 3D 横幅演示)",
            publisher = "Nintendo",
            productCode = "CTR-P-AREJ",
            romPath = resolvedRomPath,
            boxArtPath = resolvedBoxPath,
            logoPath = resolvedLogoPath,
            videoPath = resolvedVideoPath,
            bannerAnimationPath = resolvedBannerPath,
            cgfxPath = resolvedCgfxPath,
            iconCachePath = resolvedIconPath,
            assetIconName = "3ds_art/mario_3d_land_icon.png",
            assetPreviewName = "3ds_art/mario_3d_land_preview.png",
            primaryColor = Color.parseColor("#E60012"),
            secondaryColor = Color.parseColor("#FFD166"),
            playCount = 64,
            playTimeMinutes = 1280,
            isDemo = false,
            customMainRetroFilter = "AUTO"
        ).apply {
            categoryId = GameCategoryClassifier.resolveCategoryId(this)
        }
    }

    /**
     * 保证卡槽列表中始终存在《超级马里奥 3D大陆》演示游戏，并刷新其 3D 横幅动效与视频路径
     */
    fun ensureMario3DLandShowcasePresent(slots: MutableList<GridSlotItem>) {
        // 已彻底废弃强制注入伪测试数据，避免污染真实游戏库
    }

    @Suppress("UNUSED_VARIABLE", "DEPRECATION")
    private fun legacyShowcaseSlots(): MutableList<GridSlotItem> {
        val slots = MutableList(TOTAL_SLOTS) { idx ->
            GridSlotItem(id = "empty_$idx", slotIndex = idx, type = PlatformType.EMPTY)
        }

        val showcaseItems = listOf(
            GridSlotItem(
                id = "demo_zelda_oot3d",
                slotIndex = 0,
                type = PlatformType.N3DS,
                title = "塞尔达传说：时之笛 3D",
                subtitle = "The Legend of Zelda: Ocarina of Time 3D",
                publisher = "Nintendo",
                productCode = "CTR-P-AQEJ",
                assetIconName = "3ds_art/zelda_oot3d_icon.png",
                assetPreviewName = "3ds_art/zelda_oot3d_preview.png",
                primaryColor = Color.parseColor("#D4AF37"),
                secondaryColor = Color.parseColor("#1D3557"),
                playCount = 18,
                playTimeMinutes = 1420,
                isDemo = true
            ),
            GridSlotItem(
                id = "demo_mario_3d_land",
                slotIndex = 1,
                type = PlatformType.N3DS,
                title = "超级马力欧 3D大陆",
                subtitle = "SUPER MARIO 3D LAND",
                publisher = "Nintendo",
                productCode = "CTR-P-AREJ",
                assetIconName = "3ds_art/mario_3d_land_icon.png",
                assetPreviewName = "3ds_art/mario_3d_land_preview.png",
                primaryColor = Color.parseColor("#E60012"),
                secondaryColor = Color.parseColor("#FFD166"),
                playCount = 24,
                playTimeMinutes = 960,
                isDemo = true
            ),
            GridSlotItem(
                id = "demo_pokemon_usum",
                slotIndex = 2,
                type = PlatformType.N3DS,
                title = "宝可梦 究极之日 / 究极之月",
                subtitle = "Pokémon Ultra Sun & Ultra Moon",
                publisher = "The Pokémon Company",
                productCode = "CTR-P-A2AA",
                assetIconName = "3ds_art/pokemon_usum_icon.png",
                assetPreviewName = "3ds_art/pokemon_usum_preview.png",
                primaryColor = Color.parseColor("#FF8800"),
                secondaryColor = Color.parseColor("#1E2A4A"),
                playCount = 42,
                playTimeMinutes = 3150,
                isDemo = true
            ),
            GridSlotItem(
                id = "demo_mhxx_3ds",
                slotIndex = 3,
                type = PlatformType.N3DS,
                title = "怪物猎人 XX (Double Cross)",
                subtitle = "MONSTER HUNTER XX",
                publisher = "CAPCOM",
                productCode = "CTR-P-AGQJ",
                assetIconName = "3ds_art/mhxx_3ds_icon.png",
                assetPreviewName = "3ds_art/mhxx_3ds_preview.png",
                primaryColor = Color.parseColor("#B81D13"),
                secondaryColor = Color.parseColor("#F4D35E"),
                playCount = 65,
                playTimeMinutes = 5280,
                isDemo = true
            ),
            GridSlotItem(
                id = "demo_fe_awakening",
                slotIndex = 4,
                type = PlatformType.N3DS,
                title = "火焰之纹章：觉醒",
                subtitle = "Fire Emblem: Awakening",
                publisher = "Nintendo / Intelligent Systems",
                productCode = "CTR-P-AFEJ",
                assetIconName = "3ds_art/fe_awakening_icon.png",
                assetPreviewName = "3ds_art/fe_awakening_preview.png",
                primaryColor = Color.parseColor("#1D4E89"),
                secondaryColor = Color.parseColor("#90E0EF"),
                playCount = 15,
                playTimeMinutes = 1180,
                isDemo = true
            ),
            GridSlotItem(
                id = "demo_mariokart7",
                slotIndex = 5,
                type = PlatformType.N3DS,
                title = "马力欧卡丁车 7",
                subtitle = "MARIO KART 7",
                publisher = "Nintendo",
                productCode = "CTR-P-AMKJ",
                assetIconName = "3ds_art/mariokart7_icon.png",
                assetPreviewName = "3ds_art/mariokart7_preview.png",
                primaryColor = Color.parseColor("#E60012"),
                secondaryColor = Color.parseColor("#FFFFFF"),
                playCount = 38,
                playTimeMinutes = 1890,
                isDemo = true
            ),
            GridSlotItem(
                id = "demo_animal_crossing",
                slotIndex = 6,
                type = PlatformType.N3DS,
                title = "来吧！动物森友会",
                subtitle = "Animal Crossing: New Leaf",
                publisher = "Nintendo",
                productCode = "CTR-P-EGDJ",
                assetIconName = "3ds_art/animal_crossing_icon.png",
                assetPreviewName = "3ds_art/animal_crossing_preview.png",
                primaryColor = Color.parseColor("#56C428"),
                secondaryColor = Color.parseColor("#FFF8D6"),
                playCount = 52,
                playTimeMinutes = 4120,
                isDemo = true
            ),
            GridSlotItem(
                id = "demo_luigis_mansion",
                slotIndex = 7,
                type = PlatformType.N3DS,
                title = "路易吉洋馆 2 (暗月)",
                subtitle = "Luigi's Mansion: Dark Moon",
                publisher = "Nintendo",
                productCode = "CTR-P-AGGJ",
                assetIconName = "3ds_art/luigis_mansion_icon.png",
                assetPreviewName = "3ds_art/luigis_mansion_preview.png",
                primaryColor = Color.parseColor("#2EC4B6"),
                secondaryColor = Color.parseColor("#1B263B"),
                playCount = 14,
                playTimeMinutes = 820,
                isDemo = true
            ),
            GridSlotItem(
                id = "demo_nds_hgss",
                slotIndex = 8,
                type = PlatformType.NDS,
                title = "宝可梦 心金 / 魂银",
                subtitle = "Pokémon HeartGold & SoulSilver",
                publisher = "Nintendo / GAME FREAK",
                productCode = "NTR-IPKJ-JPN",
                assetIconName = "3ds_art/nds_hgss_icon.png",
                assetPreviewName = "3ds_art/nds_hgss_preview.png",
                primaryColor = Color.parseColor("#E09F3E"),
                secondaryColor = Color.parseColor("#335C67"),
                playCount = 31,
                playTimeMinutes = 2640,
                isDemo = true
            ),
            GridSlotItem(
                id = "demo_nds_ace_attorney",
                slotIndex = 9,
                type = PlatformType.NDS,
                title = "逆转裁判：复苏的逆转",
                subtitle = "Phoenix Wright: Ace Attorney DS",
                publisher = "CAPCOM",
                productCode = "NTR-AGYJ-CHN",
                assetIconName = "3ds_art/nds_ace_attorney_icon.png",
                assetPreviewName = "3ds_art/nds_ace_attorney_preview.png",
                primaryColor = Color.parseColor("#1E6091"),
                secondaryColor = Color.parseColor("#D9ED92"),
                playCount = 12,
                playTimeMinutes = 890,
                isDemo = true
            ),
            GridSlotItem(
                id = "demo_nds_chrono",
                slotIndex = 10,
                type = PlatformType.NDS,
                title = "时空之轮 DS (Chrono Trigger)",
                subtitle = "CHRONO TRIGGER DS",
                publisher = "SQUARE ENIX",
                productCode = "NTR-YQUJ-JPN",
                assetIconName = "3ds_art/nds_chrono_icon.png",
                assetPreviewName = "3ds_art/nds_chrono_preview.png",
                primaryColor = Color.parseColor("#2EC4B6"),
                secondaryColor = Color.parseColor("#CBF3F0"),
                playCount = 9,
                playTimeMinutes = 750,
                isDemo = true
            ),
            GridSlotItem(
                id = "demo_nds_nsmb",
                slotIndex = 11,
                type = PlatformType.NDS,
                title = "新超级马力欧兄弟 DS",
                subtitle = "New Super Mario Bros. DS",
                publisher = "Nintendo",
                productCode = "NTR-A2DJ-JPN",
                assetIconName = "3ds_art/nds_nsmb_icon.png",
                assetPreviewName = "3ds_art/nds_nsmb_preview.png",
                primaryColor = Color.parseColor("#F5A623"),
                secondaryColor = Color.parseColor("#E60012"),
                playCount = 27,
                playTimeMinutes = 1350,
                isDemo = true
            )
        )

        var nextSlot = 0
        for (item in showcaseItems) {
            slots[nextSlot] = item.copy(slotIndex = nextSlot)
            nextSlot++
        }

        // 预置一个示例 3DS 文件夹 + 装饰徽章
        slots[nextSlot] = GridSlotItem(
            id = "folder_emulators",
            slotIndex = nextSlot,
            type = PlatformType.FOLDER,
            title = "模拟器与掌机工具箱",
            subtitle = "已安装的安卓模拟器与系统工具文件夹",
            publisher = "Nintendo 3DS System",
            folderLetter = "模",
            primaryColor = Color.parseColor("#4EA8DE")
        )
        nextSlot++

        slots[nextSlot] = GridSlotItem(
            id = "badge_slot_qblock",
            slotIndex = nextSlot,
            type = PlatformType.BADGE,
            title = "问号砖块徽章",
            subtitle = "Nintendo Badge Arcade 桌面装饰",
            publisher = "Badge Arcade",
            badgeId = "badge_qblock",
            primaryColor = Color.parseColor("#F5A623")
        )
        nextSlot++

        val pm = context.packageManager
        val priorityPackages = listOf(
            "com.gameconsole.apollo",
            "org.azahar_emu.azahar",
            "io.github.lime3ds.android",
            "org.citra.citra_emu",
            "org.citra.citra_emu.canary",
            "org.citra.emu",
            "io.github.mandarine3ds.mandarine",
            "com.dsemu.drastic",
            "me.magnum.melonds",
            "me.magnum.melonds.nightly",
            "com.retroarch.aarch64",
            "com.retroarch",
            "com.android.settings"
        )

        for (pkg in priorityPackages) {
            if (nextSlot >= TOTAL_SLOTS) break
            if (EmulatorLauncher.isPackageInstalled(pm, pkg)) {
                val appLabel = try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    pkg
                }
                slots[nextSlot] = GridSlotItem(
                    id = "app_$pkg",
                    slotIndex = nextSlot,
                    type = PlatformType.ANDROID_APP,
                    title = appLabel,
                    subtitle = "掌机已安装应用 ($pkg)",
                    publisher = "Android Handheld",
                    packageName = pkg,
                    primaryColor = Color.parseColor("#00A8E8")
                )
                nextSlot++
            }
        }

        return slots
    }

    fun queryAllInstalledApps(): List<GridSlotItem> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = try {
            pm.queryIntentActivities(mainIntent, 0)
        } catch (_: Exception) {
            emptyList()
        }
        val selfPkg = context.packageName
        return resolveList.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == selfPkg) return@mapNotNull null
            val actName = ri.activityInfo?.name ?: ""
            val label = try {
                ri.loadLabel(pm).toString()
            } catch (_: Exception) {
                pkg
            }
            GridSlotItem(
                id = "app_${pkg}_$actName",
                slotIndex = 0,
                type = PlatformType.ANDROID_APP,
                title = label,
                subtitle = pkg,
                publisher = "Android App",
                packageName = pkg,
                activityName = actName,
                primaryColor = Color.parseColor("#00A8E8")
            )
        }.distinctBy { it.packageName }.sortedBy { it.title.lowercase() }
    }
}
