package com.gameconsole.launcher3ds.model

import android.graphics.Color

enum class PlatformType {
    N3DS,           // Nintendo 3DS (.3ds / .cci / .cia)
    NDS,            // Nintendo DS (.nds)
    ANDROID_APP,    // 已安装的安卓应用 (模拟器、工具、系统设置等)
    FOLDER,         // 3DS 文件夹
    BADGE,          // 3DS 徽章装饰 (Badge Arcade)
    EMPTY           // 空白卡槽
}

enum class SystemAppletType(
    val title: String,
    val subtitle: String,
    val accentColor: Int
) {
    NONE("", "", Color.parseColor("#00A8E8")),
    HOME_SETTINGS("HOME 菜单设置", "更改主题、扫描游戏、上下屏互换与模拟器设置", Color.parseColor("#5C6B73")),
    GAME_NOTES("游戏笔记", "边玩游戏边记录攻略与双屏彩色手写涂鸦", Color.parseColor("#F58220")),
    GAME_DESCRIPTION("游戏介绍", "在副屏查阅当前选中游戏的官方背景剧情、玩法说明与图文攻略", Color.parseColor("#FF6B00")),
    FRIEND_LIST("好友列表", "查看您的 Mii 名片、Friend Code 与正在游玩状态", Color.parseColor("#FF6B00")),
    NOTIFICATIONS("通知列表", "查看不知不觉通信 (SpotPass) 与擦肩通信通知", Color.parseColor("#18B858")),
    BROWSER("互联网浏览器", "在双屏上随时查阅游戏百科与金手指资料", Color.parseColor("#1D84E6")),
    MIIVERSE("桌面徽章屋", "管理 3DS 桌面装饰徽章与手绘心情贴纸", Color.parseColor("#56C428")),
    VIDEO_FILTER_SETTINGS("滤镜与设置", "实时切换上屏预览视频滤镜、3DS 主题配色与系统设置", Color.parseColor("#6366F1")),
    GAME_LIBRARY("游戏库与平台", "配置各个模拟器平台关联、默认模拟器与 ROM 路径", Color.parseColor("#0284C7"))
}

/**
 * ROM 与媒体资源扫描介质范围选项
 */
enum class ScanStorageScope(
    val id: String,
    val title: String,
    val shortLabel: String,
    val desc: String
) {
    BOTH("both", "两者同时", "两者同时", "同时扫描内部闪存与外置TF卡中的 ROM 与天马G媒体包"),
    INTERNAL_ONLY("internal_only", "仅内部存储", "仅内部存储", "仅扫描掌机内置存储闪存 (/storage/emulated/0)"),
    SDCARD_ONLY("sdcard_only", "仅外置TF卡", "仅外置TF卡", "仅扫描外置 TF / MicroSD 扩展卡")
}

/**
 * 上屏预览视频与画面的实时滤镜预设
 */
enum class VideoFilterType(
    val id: String,
    val displayName: String,
    val shortDesc: String,
    val accentColor: Int,
    val previewColorTop: Int,
    val previewColorBottom: Int
) {
    NONE(
        id = "none",
        displayName = "原画直出",
        shortDesc = "高清原色 · 无滤镜",
        accentColor = Color.parseColor("#00A8E8"),
        previewColorTop = Color.parseColor("#38BDF8"),
        previewColorBottom = Color.parseColor("#0284C7")
    ),
    N3DS_LCD(
        id = "n3ds_lcd",
        displayName = "3DS 液晶点阵",
        shortDesc = "原生掌机像素网格质感",
        accentColor = Color.parseColor("#10B981"),
        previewColorTop = Color.parseColor("#34D399"),
        previewColorBottom = Color.parseColor("#059669")
    ),
    CRT_SCANLINE(
        id = "crt_scanline",
        displayName = "CRT 扫描线",
        shortDesc = "街机显像管扫描线与暗角",
        accentColor = Color.parseColor("#8B5CF6"),
        previewColorTop = Color.parseColor("#A78BFA"),
        previewColorBottom = Color.parseColor("#5B21B6")
    ),
    VIVID_HDR(
        id = "vivid_hdr",
        displayName = "鲜艳绚丽",
        shortDesc = "高饱和高对比 · 通透讨喜",
        accentColor = Color.parseColor("#F43F5E"),
        previewColorTop = Color.parseColor("#FB7185"),
        previewColorBottom = Color.parseColor("#E11D48")
    ),
    WARM_RETRO(
        id = "warm_retro",
        displayName = "怀旧暖调",
        shortDesc = "琥珀胶片暖光 · 柔和护眼",
        accentColor = Color.parseColor("#F59E0B"),
        previewColorTop = Color.parseColor("#FBBF24"),
        previewColorBottom = Color.parseColor("#B45309")
    ),
    COOL_CYBER(
        id = "cool_cyber",
        displayName = "清冷赛博",
        shortDesc = "冰蓝冷调暗部与流光",
        accentColor = Color.parseColor("#06B6D4"),
        previewColorTop = Color.parseColor("#22D3EE"),
        previewColorBottom = Color.parseColor("#0E7490")
    ),
    GAMEBOY_DMG(
        id = "gameboy_dmg",
        displayName = "GB 复古绿屏",
        shortDesc = "初代 Game Boy 橄榄绿点阵",
        accentColor = Color.parseColor("#65A30D"),
        previewColorTop = Color.parseColor("#9BBC0F"),
        previewColorBottom = Color.parseColor("#306230")
    ),
    NOIR_MONO(
        id = "noir_mono",
        displayName = "经典黑白",
        shortDesc = "高反差银盐黑白胶片",
        accentColor = Color.parseColor("#64748B"),
        previewColorTop = Color.parseColor("#94A3B8"),
        previewColorBottom = Color.parseColor("#1E293B")
    );

    companion object {
        fun fromId(id: String): VideoFilterType {
            return entries.firstOrNull { it.id == id } ?: NONE
        }
    }
}

data class GridSlotItem(
    val id: String,
    var slotIndex: Int,
    var type: PlatformType = PlatformType.EMPTY,
    var platformId: String = "3DS",     // 平台标识：3DS, NDS, GBA, GBC, GB, FC, SFC, MD, PS1, PSP, DC, SS, ARCADE, DOS 等
    var title: String = "",
    var subtitle: String = "",
    var publisher: String = "Nintendo",
    var developer: String = "",         // 游戏开发商 (来自 metadata.pegasus.txt)
    var description: String = "",       // 游戏官方剧情背景与详情介绍 (来自 metadata.pegasus.txt)
    var productCode: String = "",       // 例如 CTR-P-EKJJ / NTR-IPKJ-JPN
    var romPath: String = "",           // ROM 文件绝对路径 (.3ds / .cci / .cia / .nds 等)
    var boxArtPath: String = "",        // 外部 media/<游戏名>/boxFront.png 路径
    var logoPath: String = "",          // 外部 media/<游戏名>/logo.png 路径
    var videoPath: String = "",         // 外部 media/<游戏名>/video.mp4 路径
    var bannerAnimationPath: String = "", // 3DS 上屏 3D 横幅动效视频/动画路径 (.mp4)
    var cgfxPath: String = "",          // 3DS 原始 CGFX 3D 模型/横幅文件路径 (.cgfx)
    var categoryId: String = "",        // 所属游戏分类 ID (由 GameCategoryClassifier 自动归类)
    var iconCachePath: String = "",     // 解析出的 SMDH / NDS 真实图标缓存路径
    var previewCachePath: String = "",  // 上屏 3D 预览图缓存路径
    var assetIconName: String = "",     // 内置 3ds_art/ 真实封面图标文件名
    var assetPreviewName: String = "",  // 内置 3ds_art/ 上屏真实游戏标题预览图文件名
    var packageName: String = "",       // 安卓 App 包名或指定模拟器包名
    var activityName: String = "",      // 安卓 App Activity 名
    var parentFolderId: String = "",    // 若在文件夹内，记录所属文件夹 ID
    var folderLetter: String = "",      // 文件夹标签显示的单个字符
    var badgeId: String = "",           // 徽章模板 ID
    var playCount: Int = 0,             // 游玩次数
    var playTimeMinutes: Int = 0,       // 累计游玩时长(分钟)
    var lastPlayedTimestamp: Long = 0L, // 最近启动时间戳
    var preferredEmulatorPkg: String = "", // 单独为该游戏指定的模拟器包名
    var primaryColor: Int = Color.parseColor("#E60012"),
    var secondaryColor: Int = Color.parseColor("#FFFFFF"),
    var isDemo: Boolean = false,        // 是否为首次启动内置演示卡带
    var customMainRetroFilter: String? = "AUTO" // 主屏复古滤镜单独配置：AUTO(智能跟随/检测), ON(强制开), OFF(强制关)
) {
    fun hasBannerAnimation(): Boolean = bannerAnimationPath.isNotBlank()
    fun hasPreviewVideo(): Boolean = videoPath.isNotBlank()

    @Transient
    @Volatile
    private var cachedEffectivePlatform: String? = null

    fun getEffectivePlatform(): String {
        cachedEffectivePlatform?.let { return it }
        val p = (platformId as String?).orEmpty().trim()
        val direct = PlatformCatalog.ALL_PLATFORMS.firstOrNull { it.id.equals(p, ignoreCase = true) }
        val result = if (direct != null && direct.id != "3DS") {
            direct.id
        } else {
            val pathDetected = PlatformCatalog.detectPlatformFromItem(this)
            if (pathDetected.isNotBlank()) {
                val norm = PlatformCatalog.normalizeId(pathDetected)
                platformId = norm
                norm
            } else if (direct != null) {
                direct.id
            } else {
                val normP = PlatformCatalog.normalizeId(p)
                if (normP.isNotBlank() && normP != "3DS") {
                    platformId = normP
                    normP
                } else {
                    if (type == PlatformType.NDS) "NDS" else "3DS"
                }
            }
        }
        cachedEffectivePlatform = result
        return result
    }

    fun clearPlatformCache() {
        cachedEffectivePlatform = null
    }

    /**
     * 判断当前游戏启动时是否应当开启主屏 LCD 复古滤镜：
     * 1. 若玩家手动强制设置为 ON，100% 开启；
     * 2. 若玩家手动强制设置为 OFF，100% 关闭；
     * 3. 默认 AUTO 状态：若全局开关开启，或开启了 2D 智能识别且当前游戏属于 2D 贴图类游戏（如《冒险岛》/GBA等），自动开启！
     */
    fun shouldEnableMainRetroFilter(globalEnabled: Boolean, auto2d: Boolean): Boolean {
        val mode = (customMainRetroFilter as String?).orEmpty().trim().uppercase().ifBlank { "AUTO" }
        return when (mode) {
            "ON" -> true
            "OFF" -> false
            else -> {
                if (globalEnabled) return true
                if (auto2d && isLikely2DGame()) return true
                false
            }
        }
    }

    /**
     * 智能识别是否为 2D 贴图/像素类游戏（高分辨率拉大会发糊，需要 LCD 点阵填补模糊空隙）
     */
    fun isLikely2DGame(): Boolean {
        val plat = getEffectivePlatform()
        // 1. 原生 2D 复古机种
        if (plat in listOf("GBA", "GBC", "GB", "FC", "SFC", "MD", "ARCADE", "WSC", "DOS", "PCE")) {
            return true
        }

        // 2. 3DS / NDS 上的知名纯 2D / 2.5D 贴图与画质特殊游戏
        val t = (title + " " + subtitle + " " + romPath).lowercase()
        return t.contains("冒险岛") || t.contains("maplestory") ||
                t.contains("逆转裁判") || t.contains("ace attorney") ||
                t.contains("世界树") || t.contains("etrian") ||
                t.contains("勇气默示录") || t.contains("bravely") ||
                t.contains("铲子骑士") || t.contains("shovel knight") ||
                t.contains("雷顿") || t.contains("layton") ||
                t.contains("苍蓝雷霆") || t.contains("gunvolt") ||
                t.contains("血污") || t.contains("curse of the moon") ||
                t.contains("恶魔城") || t.contains("castlevania")
    }
}

data class MediaLibraryItem(
    val id: String,                    // normalized path
    val name: String,                  // human-readable friendly title e.g. "天马G 3DS 专属游戏库"
    val path: String,                  // absolute path to the media container folder
    val platformName: String = "",     // e.g. "3DS", "NDS", "综合机种"
    val gameCount: Int = 0,            // detected game count in this media container
    val isEnabled: Boolean = true      // whether this library is enabled
)

data class PlatformDefinition(
    val id: String,
    val name: String,
    val shortName: String,
    val colorHex: String,
    val defaultEmulatorPkg: String,
    val isDefaultEnabled: Boolean = false
)

object PlatformCatalog {
    val ALL_PLATFORMS = listOf(
        PlatformDefinition("3DS", "Nintendo 3DS (3DS/CCI/CIA)", "3DS", "#E60012", "org.citra.emu", isDefaultEnabled = true),
        PlatformDefinition("NDS", "Nintendo DS (NDS)", "NDS", "#2B343D", "com.dsemu.drastic", isDefaultEnabled = false),
        PlatformDefinition("GBA", "Game Boy Advance (GBA)", "GBA", "#8B5CF6", "com.retroarch.aarch64", isDefaultEnabled = false),
        PlatformDefinition("GBC", "Game Boy Color (GBC)", "GBC", "#06B6D4", "com.retroarch.aarch64", isDefaultEnabled = false),
        PlatformDefinition("GB", "Game Boy (GB)", "GB", "#65A30D", "com.retroarch.aarch64", isDefaultEnabled = false),
        PlatformDefinition("N64", "Nintendo 64 (N64)", "N64", "#059669", "org.mupen64plusae.v3.fzurita", isDefaultEnabled = false),
        PlatformDefinition("FC", "FC / 红白机 (NES)", "FC", "#DC2626", "com.retroarch.aarch64", isDefaultEnabled = false),
        PlatformDefinition("SFC", "SFC / 超任 (SNES)", "SFC", "#2563EB", "com.retroarch.aarch64", isDefaultEnabled = false),
        PlatformDefinition("NGC", "任天堂 NGC (GameCube)", "NGC", "#6366F1", "org.dolphinemu.dolphinemu", isDefaultEnabled = false),
        PlatformDefinition("WII", "任天堂 Wii", "WII", "#0EA5E9", "org.dolphinemu.dolphinemu", isDefaultEnabled = false),
        PlatformDefinition("SWITCH", "Nintendo Switch (NS)", "Switch", "#E11D48", "dev.eden.eden_emulator", isDefaultEnabled = false),
        PlatformDefinition("PSP", "索尼 PSP", "PSP", "#0284C7", "org.ppsspp.ppsspp", isDefaultEnabled = false),
        PlatformDefinition("PS1", "索尼 PS1 (PlayStation)", "PS1", "#475569", "com.github.stenzek.duckstation", isDefaultEnabled = false),
        PlatformDefinition("PS2", "索尼 PS2 (PlayStation 2)", "PS2", "#1D4ED8", "xyz.aethersx2.android", isDefaultEnabled = false),
        PlatformDefinition("VITA", "索尼 PSV (PS Vita)", "PSV", "#0284C7", "org.vita3k.emulator", isDefaultEnabled = false),
        PlatformDefinition("MD", "世嘉 MD (Genesis)", "MD", "#334155", "com.retroarch.aarch64", isDefaultEnabled = false),
        PlatformDefinition("DC", "世嘉 DC (Dreamcast)", "DC", "#F97316", "com.flyinghead.flycast", isDefaultEnabled = false),
        PlatformDefinition("SS", "世嘉土星 (Saturn)", "SS", "#64748B", "org.uoyevoct.yabasanshiro", isDefaultEnabled = false),
        PlatformDefinition("GG", "世嘉 Game Gear (GG)", "GG", "#3B82F6", "com.retroarch.aarch64", isDefaultEnabled = false),
        PlatformDefinition("SMS", "世嘉 Master System (SMS)", "SMS", "#475569", "com.retroarch.aarch64", isDefaultEnabled = false),
        PlatformDefinition("ARCADE", "街机 (FBNEO / MAME)", "街机", "#D97706", "com.retroarch.aarch64", isDefaultEnabled = false),
        PlatformDefinition("NEOGEO", "SNK Neo Geo (NEOGEO)", "NeoGeo", "#F59E0B", "com.retroarch.aarch64", isDefaultEnabled = false),
        PlatformDefinition("PCE", "PC Engine (PCE)", "PCE", "#E11D48", "com.retroarch.aarch64", isDefaultEnabled = false),
        PlatformDefinition("WSC", "WonderSwan (WS/WSC)", "WSC", "#0D9488", "com.retroarch.aarch64", isDefaultEnabled = false),
        PlatformDefinition("ATARI", "雅达利 (Atari 2600/7800/Lynx)", "Atari", "#B45309", "com.retroarch.aarch64", isDefaultEnabled = false),
        PlatformDefinition("DOS", "DOS 经典电脑游戏", "DOS", "#4B5563", "com.retroarch.aarch64", isDefaultEnabled = false),
        PlatformDefinition("POKEMINI", "宝可梦迷你 (Pokemon Mini)", "PokemonMini", "#EC4899", "com.retroarch.aarch64", isDefaultEnabled = false),
        PlatformDefinition("MSX", "MSX / MSX2 电脑", "MSX", "#0F766E", "com.retroarch.aarch64", isDefaultEnabled = false),
        PlatformDefinition("ANDROID", "安卓原生应用 / 移植游戏", "安卓", "#10B981", "", isDefaultEnabled = false)
    )

    fun getAllPlatforms(extraPlatforms: Collection<String> = emptyList()): List<PlatformDefinition> {
        val baseList = ALL_PLATFORMS.toMutableList()
        val existingIds = baseList.map { it.id.uppercase() }.toMutableSet()
        for (raw in extraPlatforms) {
            val norm = normalizeId(raw)
            if (norm.isNotBlank() && existingIds.add(norm.uppercase())) {
                baseList.add(
                    PlatformDefinition(
                        id = norm,
                        name = "$norm 自定义平台",
                        shortName = norm,
                        colorHex = "#64748B",
                        defaultEmulatorPkg = "com.retroarch.aarch64",
                        isDefaultEnabled = false
                    )
                )
            }
        }
        return baseList
    }

    fun getById(id: String): PlatformDefinition? = getAllPlatforms().firstOrNull { it.id.equals(id, ignoreCase = true) }

    private val GENERIC_FOLDERS = setOf(
        "roms", "rom", "media", "games", "game", "download", "downloads",
        "retroarch", "storage", "emulated", "sdcard", "pegasus", "metadata",
        "天马g", "天马", "整合包"
    )

    private val CAPACITY_REGEX = Regex("""(?i)\b\d+(\.\d+)?\s*(gb|g|mb|m|tb|t)\b""")
    private val DSI_REGEX = Regex("""(?i).*\bdsi\b.*""")
    private val PS_REGEX = Regex("""(?i).*(^|[^a-z0-9])ps([^a-z0-9]|$).*""")
    private val WS_REGEX = Regex("""(?i).*(^|[^a-z0-9])ws([^a-z0-9]|$).*""")
    private val GB_REGEX = Regex("""(?i).*(^|[^a-z0-9])gb([^a-z0-9]|$).*""")

    fun resolvePlatformFromKeyword(raw: String): String? {
        val clean = raw.trim()
        val sUpper = clean.uppercase()
        if (sUpper.isBlank()) return null

        // 优先检查完全匹配已知平台 ID
        val direct = ALL_PLATFORMS.firstOrNull { it.id.equals(sUpper, ignoreCase = true) }
        if (direct != null) return direct.id

if (sUpper.contains("3DS") || sUpper.contains("CITRA")) return "3DS"
        if (sUpper.contains("NDS") || sUpper.contains("DRASTIC") || DSI_REGEX.matches(clean)) return "NDS"
        if (sUpper.contains("GBA")) return "GBA"
        if (sUpper.contains("GBC")) return "GBC"
        if (sUpper.contains("SFC") || sUpper.contains("SNES") || clean.contains("超任") || sUpper.contains("SUPERFAMICOM")) return "SFC"
        if ((sUpper.contains("FC") || sUpper.contains("NES") || clean.contains("红白机") || sUpper.contains("FAMICOM")) && !sUpper.contains("SFC")) return "FC"
        if (sUpper.contains("N64") || sUpper.contains("NINTENDO64") || clean.contains("任天堂64")) return "N64"
        if (sUpper.contains("GENESIS") || sUpper.contains("MEGADRIVE") || clean.contains("世嘉MD") || (sUpper.contains("MD") && !sUpper.contains("CMD") && !sUpper.contains("AMD"))) return "MD"
        if (sUpper.contains("PSP") || sUpper.contains("PPSSPP")) return "PSP"
        if (sUpper.contains("PS2") || sUpper.contains("PLAYSTATION2") || sUpper.contains("AETHERSX2")) return "PS2"
        if (sUpper.contains("PS1") || sUpper.contains("PSX") || sUpper.contains("PLAYSTATION") || PS_REGEX.matches(clean)) return "PS1"
        if (sUpper.contains("VITA") || sUpper.contains("PSV") || sUpper.contains("VITA3K")) return "VITA"
        if (sUpper.contains("SWITCH") || sUpper.contains("YUZU") || (sUpper == "NS")) return "SWITCH"
        if (sUpper.contains("NGC") || sUpper.contains("GAMECUBE") || sUpper.contains("DOLPHIN") || (sUpper == "GC")) return "NGC"
        if (sUpper.contains("WII") && !sUpper.contains("WIIU")) return "WII"
        if (sUpper.contains("DC") || sUpper.contains("DREAMCAST") || sUpper.contains("FLYCAST")) return "DC"
        if (sUpper.contains("SS") || sUpper.contains("SATURN") || clean.contains("土星")) return "SS"
        if (sUpper.contains("NEOGEO") || sUpper.contains("NEO-GEO") || sUpper.contains("NGP") || sUpper.contains("NGPC")) return "NEOGEO"
        if (listOf("ARCADE", "MAME", "FBNEO", "FBA", "CPS").any { sUpper.contains(it) } || clean.contains("街机")) return "ARCADE"
        if (sUpper.contains("PCE") || sUpper.contains("PCENGINE") || sUpper.contains("TG16")) return "PCE"
        if (sUpper.contains("WSC") || sUpper.contains("WONDERSWAN") || WS_REGEX.matches(clean)) return "WSC"
        if (sUpper.contains("DOS") || sUpper.contains("DOSBOX")) return "DOS"
        if (sUpper.contains("GAMEGEAR") || (sUpper == "GG")) return "GG"
        if (sUpper.contains("MASTERSYSTEM") || (sUpper == "SMS")) return "SMS"
        if (sUpper.contains("ATARI") || sUpper.contains("LYNX")) return "ATARI"
        if (sUpper.contains("POKEMINI") || clean.contains("宝可梦迷你")) return "POKEMINI"
        if (sUpper.contains("MSX")) return "MSX"
        if (sUpper.contains("ANDROID") || clean.contains("安卓游戏") || clean.contains("原生游戏")) return "ANDROID"
        if (sUpper.contains("GAMEBOY") || GB_REGEX.matches(clean)) return "GB"

        return null
    }

    fun detectPlatformFromPath(path: String, fileExt: String = ""): String {
        val p = path.trim()
        if (p.isBlank()) return ""

        // 1. 优先根据特异文件扩展名判定
        val ext = (if (fileExt.isNotBlank()) fileExt else if (p.contains('.')) p.substringAfterLast('.') else "").trim().lowercase()
        when (ext) {
            "3ds", "cci", "cia", "cxi" -> return "3DS"
            "nds", "dsi" -> return "NDS"
            "gba" -> return "GBA"
            "gbc" -> return "GBC"
            "gb" -> return "GB"
            "z64", "n64", "v64" -> return "N64"
            "nes", "unf", "fds" -> return "FC"
            "sfc", "smc" -> return "SFC"
            "gcm", "ciso" -> return "NGC"
            "wbfs" -> return "WII"
            "nsp", "xci", "nsz" -> return "SWITCH"
            "vpk" -> return "VITA"
            "md", "gen" -> return "MD"
            "cso", "pbp" -> return "PSP"
            "cdi", "gdi" -> return "DC"
            "gg" -> return "GG"
            "sms" -> return "SMS"
            "pce" -> return "PCE"
            "ws", "wsc" -> return "WSC"
            "a26", "a78", "lnx" -> return "ATARI"
            "min" -> return "POKEMINI"
        }

        // 2. 逆向检查各级路径段（从最近层目录向上最多追溯 6 层）
        val norm = p.replace('\\', '/')
        val segments = norm.split('/').filter { it.isNotBlank() }
        for (seg in segments.takeLast(6).reversed()) {
            val sClean = CAPACITY_REGEX.replace(seg, "").trim()
            if (sClean.isBlank()) continue
            if (GENERIC_FOLDERS.contains(sClean.lowercase())) continue

            val matched = resolvePlatformFromKeyword(sClean)
            if (matched != null) return matched
        }

        return ""
    }

    fun detectPlatform(file: java.io.File): String {
        val fromExt = detectPlatformFromPath(file.name, file.extension)
        if (fromExt.isNotBlank()) return fromExt

        val fromPath = detectPlatformFromPath(file.absolutePath, file.extension)
        if (fromPath.isNotBlank()) return fromPath

        return ""
    }

    fun detectPlatformFromItem(slot: GridSlotItem): String {
        if (slot.romPath.isNotBlank()) {
            val p = detectPlatformFromPath(slot.romPath)
            if (p.isNotBlank()) return p
        }
        if (slot.boxArtPath.isNotBlank()) {
            val p = detectPlatformFromPath(slot.boxArtPath)
            if (p.isNotBlank()) return p
        }
        if (slot.videoPath.isNotBlank()) {
            val p = detectPlatformFromPath(slot.videoPath)
            if (p.isNotBlank()) return p
        }
        if (slot.cgfxPath.isNotBlank()) {
            val p = detectPlatformFromPath(slot.cgfxPath)
            if (p.isNotBlank()) return p
        }
        if (slot.id.isNotBlank() && (slot.id.contains('/') || slot.id.contains('\\'))) {
            val p = detectPlatformFromPath(slot.id)
            if (p.isNotBlank()) return p
        }
        return ""
    }

    fun normalizeId(raw: String, fileExt: String = ""): String {
        val clean = raw.trim()
        if (clean.isBlank()) return "3DS"

        // 若传入的是文件路径或含有斜杠
        if (clean.contains('/') || clean.contains('\\')) {
            val detected = detectPlatformFromPath(clean, fileExt)
            if (detected.isNotBlank()) return detected
        }

        // 扩展名判定
        val ext = fileExt.trim().lowercase().removePrefix(".")
        if (ext.isNotBlank()) {
            val fromExt = detectPlatformFromPath("", ext)
            if (fromExt.isNotBlank()) return fromExt
        }

        // 关键词或平台名判定
        val keywordMatch = resolvePlatformFromKeyword(clean)
        if (keywordMatch != null) return keywordMatch

// 尝试作为路径或文件名再分析一次
        val pathMatch = detectPlatformFromPath(clean, fileExt)
        if (pathMatch.isNotBlank()) return pathMatch

        val sUpper = clean.uppercase()
        if (sUpper.isNotBlank() && sUpper.length in 2..12 && sUpper.all { it.isLetterOrDigit() || it == '_' } && !GENERIC_FOLDERS.contains(sUpper.lowercase())) {
            return sUpper
        }

        return "3DS"
    }
}

enum class GameCategory(
    val id: String,
    val displayName: String,
    val shortName: String,
    val englishTag: String,
    val accentColor: Int,
    val badgeSymbol: String
) {
    ALL("all", "全部游戏", "全部", "ALL GAMES", Color.parseColor("#00A8E8"), "✦"),

    // 5 大任天堂经典顶级 IP 系列
    MARIO("mario", "马里奥系列", "马里奥", "SUPER MARIO", Color.parseColor("#E60012"), "🍄"),
    ZELDA("zelda", "塞尔达系列", "塞尔达", "ZELDA SERIES", Color.parseColor("#10B981"), "🗡️"),
    POKEMON("pokemon", "宝可梦系列", "宝可梦", "POKÉMON", Color.parseColor("#F59E0B"), "⚡"),
    KIRBY("kirby", "星之卡比系列", "星之卡比", "KIRBY SERIES", Color.parseColor("#EC4899"), "🌟"),
    FIRE_EMBLEM("fire_emblem", "火焰纹章系列", "火焰纹章", "FIRE EMBLEM", Color.parseColor("#2563EB"), "🛡️"),

    // 通用游戏类型（兼顾掌机、家用机、街机等全机种模拟器）
    ACTION_ADV("action_adv", "动作与冒险", "动作/冒险", "ACTION & ADV", Color.parseColor("#D97706"), "⚔️"),
    RPG("rpg", "角色扮演 (RPG)", "角色扮演", "ROLE-PLAYING", Color.parseColor("#8B5CF6"), "🧙"),
    STRATEGY_SLG("strategy_slg", "策略与战棋", "策略/战棋", "STRATEGY & SLG", Color.parseColor("#0284C7"), "♟️"),
    FIGHTING("fighting", "格斗与对战", "格斗/对战", "FIGHTING", Color.parseColor("#EF4444"), "🥊"),
    RACING_SPORTS("racing_sports", "竞速与体育", "竞速/体育", "RACING & SPORTS", Color.parseColor("#06B6D4"), "🏁"),
    SHOOTING_STG("shooting_stg", "飞行与射击", "飞行/射击", "SHOOTING & STG", Color.parseColor("#F97316"), "✈️"),
    CASUAL_SIM("casual_sim", "休闲·益智·模拟", "休闲/模拟", "CASUAL & SIM", Color.parseColor("#14B8A6"), "☕"),
    TOOLS_APPS("tools_apps", "模拟器与工具", "模拟器/工具", "SYSTEM & TOOLS", Color.parseColor("#64748B"), "⚙️");

    companion object {
        fun fromId(id: String): GameCategory {
            return entries.firstOrNull { it.id == id } ?: ALL
        }
    }
}

data class CustomCategoryItem(
    val id: String,
    var displayName: String,
    var shortName: String,
    var englishTag: String = "",
    var accentColor: Int = Color.parseColor("#0284C7"),
    var badgeSymbol: String = "🏷️",
    var isBuiltIn: Boolean = false,
    var isSeries: Boolean = false,
    var sortOrder: Int = 0
) {
    fun toGameCategory(): GameCategory {
        return GameCategory.entries.firstOrNull { it.id == id } ?: GameCategory.ALL
    }

    companion object {
        fun fromGameCategory(cat: GameCategory, sortOrder: Int): CustomCategoryItem {
            val isSeries = cat in listOf(
                GameCategory.MARIO,
                GameCategory.ZELDA,
                GameCategory.POKEMON,
                GameCategory.KIRBY,
                GameCategory.FIRE_EMBLEM
            )
            return CustomCategoryItem(
                id = cat.id,
                displayName = cat.displayName,
                shortName = cat.shortName,
                englishTag = cat.englishTag,
                accentColor = cat.accentColor,
                badgeSymbol = cat.badgeSymbol,
                isBuiltIn = true,
                isSeries = isSeries,
                sortOrder = sortOrder
            )
        }

        fun defaultCategories(): List<CustomCategoryItem> {
            return GameCategory.entries.mapIndexed { idx, cat ->
                fromGameCategory(cat, idx)
            }
        }
    }
}

object GameCategoryClassifier {

    fun resolveCategoryId(slot: GridSlotItem): String {
        val cid = slot.categoryId
        if (cid.isNotBlank() && cid !in listOf("mario_kirby", "zelda_action", "rpg_strategy", "fight_race", "avg_sim")) {
            val cat = GameCategory.fromId(cid)
            if (cat != GameCategory.ALL) return cat.id
        }
        return classifyCategory(slot).id
    }

    /**
     * 智能识别游戏所属系列或通用类型：
     * 1. 优先提取 5 大任天堂经典顶级 IP 系列：马里奥、塞尔达、宝可梦、星之卡比、火焰纹章；
     * 2. 其他所有游戏按通用游戏类型智能归类（动作冒险、角色扮演、策略战棋、格斗对战、竞速体育、飞行射击、休闲模拟等），
     *    全面兼顾 3DS、NDS、GBA、GBC、FC、SFC、MD、PSP、PS1、街机等全平台模拟器机种。
     */
    fun classifyCategory(slot: GridSlotItem): GameCategory {
        if (slot.type == PlatformType.ANDROID_APP || slot.type == PlatformType.FOLDER || slot.type == PlatformType.BADGE) {
            return GameCategory.TOOLS_APPS
        }
        val t = (slot.title + " " + slot.subtitle + " " + slot.romPath).lowercase()

        // 1. 马里奥系列 (Super Mario Series)
        if (t.contains("马里奥") || t.contains("马力欧") || t.contains("mario") ||
            t.contains("路易吉") || t.contains("luigi") || t.contains("瓦里奥") || t.contains("wario") ||
            t.contains("耀西") || t.contains("yoshi") || t.contains("蘑菇队长") ||
            t.contains("大金刚") || t.contains("donkey kong") || t.contains("马车")
        ) {
            return GameCategory.MARIO
        }

        // 2. 塞尔达系列 (The Legend of Zelda Series)
        if (t.contains("塞尔达") || t.contains("zelda") || t.contains("林克") || t.contains("link") ||
            t.contains("海拉鲁") || t.contains("hyrule") || t.contains("时之笛") || t.contains("姆吉拉") ||
            t.contains("风之杖") || t.contains("三角力量") || t.contains("众神的三角") ||
            t.contains("缩小帽") || t.contains("梦见岛") || t.contains("黄昏公主") || t.contains("天空之剑")
        ) {
            return GameCategory.ZELDA
        }

        // 3. 宝可梦系列 (Pokémon Series)
        if (t.contains("宝可梦") || t.contains("pokemon") || t.contains("口袋妖怪") ||
            t.contains("口袋怪兽") || t.contains("宠物小精灵") || t.contains("皮卡丘") || t.contains("pikachu")
        ) {
            return GameCategory.POKEMON
        }

        // 4. 星之卡比系列 (Kirby Series - 用户亦称星之卡片)
        if (t.contains("卡比") || t.contains("kirby") || t.contains("星之卡片") ||
            t.contains("镜之迷宫") || t.contains("梦之泉") || t.contains("三重豪华") ||
            t.contains("机械行星") || t.contains("探索发现") || t.contains("毛线卡比")
        ) {
            return GameCategory.KIRBY
        }

        // 5. 火焰纹章系列 (Fire Emblem Series)
        if (t.contains("火焰纹章") || t.contains("火焰之纹章") || t.contains("圣火徽章") ||
            t.contains("fire emblem") || t.contains("纹章之谜") || t.contains("圣战之系谱") ||
            t.contains("多拉基亚") || t.contains("封印之剑") || t.contains("烈火之剑") ||
            t.contains("圣魔之光石") || t.contains("苍炎之轨迹") || t.contains("晓之女神") ||
            t.contains("觉醒 汉化") || t.contains("if 白夜") || t.contains("if 暗夜") ||
            t.contains("if 透魔") || t.contains("回声 汉化") || t.contains("风花雪月") ||
            t.contains("结合 engage")
        ) {
            return GameCategory.FIRE_EMBLEM
        }

        // 6. 格斗与对战 (Fighting)
        if (t.contains("拳皇") || t.contains("kof") || t.contains("街头霸王") || t.contains("街霸") ||
            t.contains("street fighter") || t.contains("铁拳") || t.contains("tekken") ||
            t.contains("侍魂") || t.contains("samurai") || t.contains("死或生") || t.contains("dead or alive") ||
            t.contains("月华剑士") || t.contains("罪恶装备") || t.contains("guilty gear") ||
            t.contains("苍翼默示录") || t.contains("blazblue") || t.contains("大乱斗") || t.contains("smash") ||
            t.contains("饿狼传说") || t.contains("致命狂怒") || t.contains("龙珠") || t.contains("dragon ball") ||
            t.contains("火影") || t.contains("naruto") || t.contains("死神") || t.contains("bleach") ||
            t.contains("刀魂") || t.contains("灵魂能力") || t.contains("soulcalibur") ||
            t.contains("豪血寺") || t.contains("世界英雄") || t.contains("傲剑狂刀") ||
            t.contains("vr战士") || t.contains("virtua fighter") || t.contains("格斗") || t.contains("fighting")
        ) {
            return GameCategory.FIGHTING
        }

        // 7. 竞速与体育 (Racing & Sports)
        if (t.contains("赛车") || t.contains("竞速") || t.contains("极品飞车") || t.contains("need for speed") ||
            t.contains("山脊赛车") || t.contains("ridge racer") || t.contains("gt赛车") || t.contains("gran turismo") ||
            t.contains("头文字d") || t.contains("initial d") || t.contains("狂野飙车") || t.contains("asphalt") ||
            t.contains("跑跑卡丁车") || t.contains("世嘉拉力") || t.contains("f1") || t.contains("卡丁车") ||
            t.contains("racing") || t.contains("实况足球") || t.contains("pes") || t.contains("fifa") ||
            t.contains("足球") || t.contains("soccer") || t.contains("football") || t.contains("篮球") ||
            t.contains("nba") || t.contains("basketball") || t.contains("网球") || t.contains("tennis") ||
            t.contains("高尔夫") || t.contains("golf") || t.contains("棒球") || t.contains("baseball") ||
            t.contains("滑板") || t.contains("skate") || t.contains("滑雪") || t.contains("保龄球") ||
            t.contains("台球") || t.contains("奥运会") || t.contains("体育") || t.contains("sports")
        ) {
            return GameCategory.RACING_SPORTS
        }

        // 8. 飞行与射击 (Shooting & STG)
        if (t.contains("射击") || t.contains("雷电") || t.contains("raiden") || t.contains("彩京") ||
            t.contains("stg") || t.contains("沙罗曼蛇") || t.contains("salamander") ||
            t.contains("宇宙巡航机") || t.contains("gradius") || t.contains("斑鸠") || t.contains("ikaruga") ||
            t.contains("怒首领蜂") || t.contains("donpachi") || t.contains("东方") || t.contains("touhou") ||
            t.contains("皇牌空战") || t.contains("ace combat") || t.contains("星际火狐") || t.contains("star fox") ||
            t.contains("魂斗罗") || t.contains("contra") || t.contains("装甲核心") || t.contains("armored core") ||
            t.contains("毁灭战士") || t.contains("doom") || t.contains("荣誉勋章") || t.contains("使命召唤") ||
            t.contains("call of duty") || t.contains("四国战机") || t.contains("音速战机") ||
            t.contains("武装飞鸟") || t.contains("兵蜂") || t.contains("打飞机") || t.contains("空战") ||
            t.contains("shmup")
        ) {
            return GameCategory.SHOOTING_STG
        }

        // 9. 策略与战棋 (Strategy & SLG)
        if (t.contains("机战") || t.contains("机器人大战") || t.contains("super robot") ||
            t.contains("皇家骑士团") || t.contains("tactics ogre") || t.contains("最终幻想战略版") ||
            t.contains("fft") || t.contains("梦幻模拟战") || t.contains("langrisser") ||
            t.contains("三国志") || t.contains("信长之野望") || t.contains("高级战争") ||
            t.contains("advance wars") || t.contains("大战略") || t.contains("文明") ||
            t.contains("civilization") || t.contains("魔界战记") || t.contains("disgaea") ||
            t.contains("战场女武神") || t.contains("valkyria") || t.contains("塔防") ||
            t.contains("植物大战僵尸") || t.contains("圣剑同盟") || t.contains("弧光之源") ||
            t.contains("光辉物语") || t.contains("战棋") || t.contains("策略") || t.contains("战略") || t.contains("slg")
        ) {
            return GameCategory.STRATEGY_SLG
        }

        // 10. 休闲·益智·模拟 (Casual, Simulation & Music)
        if (t.contains("逆转裁判") || t.contains("大逆转裁判") || t.contains("ace attorney") ||
            t.contains("雷顿") || t.contains("layton") || t.contains("极限脱出") || t.contains("zero escape") ||
            t.contains("弹丸论破") || t.contains("danganronpa") || t.contains("流行之神") ||
            t.contains("牧场物语") || t.contains("harvest moon") || t.contains("story of seasons") ||
            t.contains("符文工房") || t.contains("rune factory") || t.contains("动物之森") ||
            t.contains("动物森友会") || t.contains("animal crossing") || t.contains("模拟人生") ||
            t.contains("sims") || t.contains("俄罗斯方块") || t.contains("tetris") ||
            t.contains("泡泡龙") || t.contains("炸弹人") || t.contains("bomberman") ||
            t.contains("太鼓达人") || t.contains("节奏天国") || t.contains("初音未来") ||
            t.contains("project diva") || t.contains("脑锻炼") || t.contains("爱相随") ||
            t.contains("love plus") || t.contains("烹饪妈妈") || t.contains("心跳回忆") ||
            t.contains("大富翁") || t.contains("麻将") || t.contains("花牌") || t.contains("象棋") ||
            t.contains("益智") || t.contains("模拟") || t.contains("音乐") || t.contains("节奏") || t.contains("休闲")
        ) {
            return GameCategory.CASUAL_SIM
        }

        // 11. 角色扮演 (RPG)
        if (t.contains("最终幻想") || t.contains("final fantasy") || t.contains("ff") ||
            t.contains("勇者斗恶龙") || t.contains("dragon quest") || t.contains("dq") ||
            t.contains("异度之刃") || t.contains("异度神剑") || t.contains("xenoblade") ||
            t.contains("传说") || t.contains("tales of") || t.contains("王国之心") ||
            t.contains("kingdom hearts") || t.contains("时空之轮") || t.contains("chrono") ||
            t.contains("英雄传说") || t.contains("轨迹") || t.contains("伊苏") || t.contains("ys") ||
            t.contains("女神异闻录") || t.contains("persona") || t.contains("真女神转生") ||
            t.contains("shin megami") || t.contains("重装机兵") || t.contains("metal max") ||
            t.contains("天地创造") || t.contains("北欧女神") || t.contains("黄金太阳") ||
            t.contains("golden sun") || t.contains("mother") || t.contains("龙战士") ||
            t.contains("世界树") || t.contains("etrian") || t.contains("勇气默示录") ||
            t.contains("bravely") || t.contains("仙剑") || t.contains("轩辕剑") ||
            t.contains("暗黑") || t.contains("diablo") || t.contains("天外魔境") ||
            t.contains("幻想水浒传") || t.contains("suikoden") || t.contains("圣剑传说") ||
            t.contains("荒野兵器") || t.contains("数码宝贝") || t.contains("digimon") ||
            t.contains("妖怪手表") || t.contains("youkai") || t.contains("yokai") ||
            t.contains("rpg") || t.contains("角色扮演")
        ) {
            return GameCategory.RPG
        }

        // 12. 动作与冒险 (Action & Adventure) 作为最终通用分类
        return GameCategory.ACTION_ADV
    }

    fun inferPublisherAndColor(rawName: String): Pair<String, Int> {
        val t = rawName.lowercase()
        return when {
            t.contains("口袋妖怪") || t.contains("宝可梦") -> "Nintendo / The Pokémon Company" to Color.parseColor("#F59E0B")
            t.contains("塞尔达") -> "Nintendo" to Color.parseColor("#D4AF37")
            t.contains("马里奥") || t.contains("马力欧") || t.contains("蘑菇队长") || t.contains("瓦里奥") -> "Nintendo" to Color.parseColor("#E60012")
            t.contains("路易吉") -> "Nintendo" to Color.parseColor("#10B981")
            t.contains("卡比") -> "Nintendo / HAL Laboratory" to Color.parseColor("#EC4899")
            t.contains("动物之森") || t.contains("动物森友会") || t.contains("皮克敏") || t.contains("节奏天国") || t.contains("脑锻炼") || t.contains("大乱斗") || t.contains("星际火狐") || t.contains("新光之神话") || t.contains("银河战士") -> "Nintendo" to Color.parseColor("#00A8E8")
            t.contains("火焰纹章") || t.contains("火焰之纹章") -> "Nintendo / Intelligent Systems" to Color.parseColor("#2563EB")
            t.contains("异度之刃") -> "Nintendo / Monolith Soft" to Color.parseColor("#DC2626")
            t.contains("怪物猎人") || t.contains("逆转裁判") || t.contains("生化危机") || t.contains("街头霸王") -> "CAPCOM" to Color.parseColor("#D97706")
            t.contains("勇者斗恶龙") || t.contains("勇气默示录") || t.contains("最终幻想") || t.contains("王国之心") || t.contains("时空之轮") -> "SQUARE ENIX" to Color.parseColor("#3B82F6")
            t.contains("真女神转生") || t.contains("女神异闻录") || t.contains("世界树") -> "ATLUS" to Color.parseColor("#6366F1")
            t.contains("妖怪手表") || t.contains("雷顿") || t.contains("幻想生活") || t.contains("纸箱战机") || t.contains("解放少女") -> "LEVEL-5" to Color.parseColor("#0EA5E9")
            t.contains("牧场物语") || t.contains("符文工房") || t.contains("禁忌的玛古那") -> "Marvelous" to Color.parseColor("#22C55E")
            t.contains("机器人大战") || t.contains("太鼓达人") || t.contains("山脊赛车") || t.contains("皇牌空战") || t.contains("龙珠") || t.contains("交叉领域") -> "BANDAI NAMCO" to Color.parseColor("#EF4444")
            t.contains("索尼克") || t.contains("第七龙神") -> "SEGA" to Color.parseColor("#1D4ED8")
            t.contains("恶魔城") || t.contains("爱相随") -> "KONAMI" to Color.parseColor("#9333EA")
            t.contains("死或生") || t.contains("无双") -> "KOEI TECMO" to Color.parseColor("#E11D48")
            t.contains("极限脱出") || t.contains("为我而生") -> "Spike Chunsoft" to Color.parseColor("#0284C7")
            t.contains("重装机兵") -> "Kadokawa Games" to Color.parseColor("#B45309")
            else -> "Nintendo 3DS" to Color.parseColor("#00A8E8")
        }
    }

    /**
     * 拆分天马G文件夹名中的游戏主标题与版本标签（例如 "塞尔达传说 时之笛3D 汉化版" -> "塞尔达传说 时之笛3D" 与 "汉化版"）
     */
    fun splitGameFolderName(folderName: String): Pair<String, String> {
        val suffixes = listOf(" 英字日音版", " 汉化版", " 日文版", " 黄金版 汉化版", " 专家版 汉化版", " 特别版 汉化版")
        val trimmed = folderName.trim().replace(Regex("\\s+"), " ")
        for (sfx in suffixes) {
            if (trimmed.endsWith(sfx)) {
                val main = trimmed.removeSuffix(sfx).trim()
                val tag = sfx.trim()
                if (main.isNotEmpty()) return main to tag
            }
        }
        return trimmed to "Nintendo 3DS"
    }
}

data class StrokePoint(
    val x: Float,
    val y: Float
)

data class NoteStroke(
    val colorHex: String = "#1A1A1A",
    val strokeWidth: Float = 5f,
    val points: MutableList<StrokePoint> = mutableListOf()
)

data class GameNoteData(
    val gameId: String,
    var gameTitle: String,
    var textMemo: String = "",
    var strokes: MutableList<NoteStroke> = mutableListOf(),
    var updatedAt: Long = System.currentTimeMillis()
)

data class FriendProfile(
    var miiName: String = "Nova 玩家",
    var friendCode: String = "2026 - 0330 - 8899",
    var statusMessage: String = "正在双屏掌机重温 3DS 经典！",
    var favoriteGame: String = "塞尔达传说：时之笛 3D",
    var miiShirtColor: Int = Color.parseColor("#E60012"),
    var isOnline: Boolean = true
)

data class NotificationItem(
    val id: String,
    val title: String,
    val sender: String,
    val dateStr: String,
    val body: String,
    val isSpotPass: Boolean = true, // true=蓝色SpotPass, false=绿色StreetPass
    var isRead: Boolean = false
)

data class BadgeTemplate(
    val id: String,
    val name: String,
    val category: String,
    val primaryColor: Int,
    val accentColor: Int,
    val symbol: String
)

data class Theme3DS(
    val id: String,
    val name: String,
    val description: String,
    val upperSkyTop: Int,
    val upperSkyBottom: Int,
    val upperGridColor: Int,
    val lowerBgTop: Int,
    val lowerBgBottom: Int,
    val slotFillColor: Int,
    val slotBorderColor: Int,
    val emptySlotRecessColor: Int,
    val cursorMainColor: Int,
    val cursorGlowColor: Int,
    val topBarBgColor: Int,
    val bottomBarBgColor: Int,
    val bottomBarButtonColor: Int,
    val textPrimaryColor: Int,
    val textSecondaryColor: Int,
    val bgmScaleNotes: IntArray // 对应主题的环境音乐和弦音高 (MIDI/Hz 索引)
)

object ThemeCatalog {
    val ALL_THEMES = listOf(
        Theme3DS(
            id = "basic_white",
            name = "基本（纯白）",
            description = "Nintendo 3DS 经典出厂纯白系统主题，搭配天空蓝立体光效与轻快木琴背景音",
            upperSkyTop = Color.parseColor("#C9D6E2"),
            upperSkyBottom = Color.parseColor("#F5F8FA"),
            upperGridColor = Color.parseColor("#B5C4D2"),
            lowerBgTop = Color.parseColor("#E2E7EC"),
            lowerBgBottom = Color.parseColor("#CFD7DF"),
            slotFillColor = Color.parseColor("#F9FBFD"),
            slotBorderColor = Color.parseColor("#B0BCC8"),
            emptySlotRecessColor = Color.parseColor("#D6DEE6"),
            cursorMainColor = Color.parseColor("#00B8F0"),
            cursorGlowColor = Color.parseColor("#6CE0FF"),
            topBarBgColor = Color.parseColor("#DCE3EA"),
            bottomBarBgColor = Color.parseColor("#CDD6E0"),
            bottomBarButtonColor = Color.parseColor("#F2F6FA"),
            textPrimaryColor = Color.parseColor("#2A343D"),
            textSecondaryColor = Color.parseColor("#5C6B78"),
            bgmScaleNotes = intArrayOf(523, 659, 784, 987, 1046) // C5, E5, G5, B5, C6
        ),
        Theme3DS(
            id = "basic_black",
            name = "基本（曜石黑）",
            description = "New 3DS 经典深邃金属黑主题，沉稳护眼，凸显霓虹青色四角的选中光标",
            upperSkyTop = Color.parseColor("#14181E"),
            upperSkyBottom = Color.parseColor("#2B323C"),
            upperGridColor = Color.parseColor("#38424F"),
            lowerBgTop = Color.parseColor("#22272E"),
            lowerBgBottom = Color.parseColor("#161A1F"),
            slotFillColor = Color.parseColor("#2E3540"),
            slotBorderColor = Color.parseColor("#475262"),
            emptySlotRecessColor = Color.parseColor("#1B2026"),
            cursorMainColor = Color.parseColor("#00E5FF"),
            cursorGlowColor = Color.parseColor("#80F2FF"),
            topBarBgColor = Color.parseColor("#1D2228"),
            bottomBarBgColor = Color.parseColor("#181C22"),
            bottomBarButtonColor = Color.parseColor("#1E242C"),
            textPrimaryColor = Color.parseColor("#ECF2F8"),
            textSecondaryColor = Color.parseColor("#9BA8B8"),
            bgmScaleNotes = intArrayOf(440, 523, 659, 784, 880) // A4, C5, E5, G5, A5
        ),
        Theme3DS(
            id = "mario_red",
            name = "超级马力欧（蘑菇王国红）",
            description = "热情活力的任天堂红金配色，点缀问号砖块与无敌星光效",
            upperSkyTop = Color.parseColor("#B8000E"),
            upperSkyBottom = Color.parseColor("#E60012"),
            upperGridColor = Color.parseColor("#FF5252"),
            lowerBgTop = Color.parseColor("#D41420"),
            lowerBgBottom = Color.parseColor("#9E0B14"),
            slotFillColor = Color.parseColor("#FFF8E7"),
            slotBorderColor = Color.parseColor("#F5B000"),
            emptySlotRecessColor = Color.parseColor("#880810"),
            cursorMainColor = Color.parseColor("#FFD000"),
            cursorGlowColor = Color.parseColor("#FFF078"),
            topBarBgColor = Color.parseColor("#B00C16"),
            bottomBarBgColor = Color.parseColor("#8E0710"),
            bottomBarButtonColor = Color.parseColor("#FFCC00"),
            textPrimaryColor = Color.parseColor("#3D1E00"),
            textSecondaryColor = Color.parseColor("#FFE4B5"),
            bgmScaleNotes = intArrayOf(659, 659, 523, 659, 784) // Mario-inspired bright notes
        ),
        Theme3DS(
            id = "zelda_gold",
            name = "塞尔达传说（海拉鲁黄金）",
            description = "皇家暗夜蓝与三角力量辉金交织，充满传奇冒险仪式感",
            upperSkyTop = Color.parseColor("#0F1A2C"),
            upperSkyBottom = Color.parseColor("#1E3250"),
            upperGridColor = Color.parseColor("#D4AF37"),
            lowerBgTop = Color.parseColor("#192841"),
            lowerBgBottom = Color.parseColor("#101A2B"),
            slotFillColor = Color.parseColor("#233656"),
            slotBorderColor = Color.parseColor("#D4AF37"),
            emptySlotRecessColor = Color.parseColor("#121E31"),
            cursorMainColor = Color.parseColor("#FFD700"),
            cursorGlowColor = Color.parseColor("#FFF29C"),
            topBarBgColor = Color.parseColor("#142136"),
            bottomBarBgColor = Color.parseColor("#0E1726"),
            bottomBarButtonColor = Color.parseColor("#142032"),
            textPrimaryColor = Color.parseColor("#F7E7B4"),
            textSecondaryColor = Color.parseColor("#B5A268"),
            bgmScaleNotes = intArrayOf(440, 587, 659, 880, 784) // Ocarina-style intervals
        ),
        Theme3DS(
            id = "pikachu_yellow",
            name = "宝可梦（皮卡丘电光黄）",
            description = "明快温暖的皮卡丘黄与精灵球红白点缀，适合宝可梦全世代玩家",
            upperSkyTop = Color.parseColor("#F5B700"),
            upperSkyBottom = Color.parseColor("#FFE047"),
            upperGridColor = Color.parseColor("#FF9900"),
            lowerBgTop = Color.parseColor("#FFE156"),
            lowerBgBottom = Color.parseColor("#F4C400"),
            slotFillColor = Color.parseColor("#FFFDF2"),
            slotBorderColor = Color.parseColor("#E09F00"),
            emptySlotRecessColor = Color.parseColor("#E2B200"),
            cursorMainColor = Color.parseColor("#E60012"),
            cursorGlowColor = Color.parseColor("#FF6B6B"),
            topBarBgColor = Color.parseColor("#F2BE00"),
            bottomBarBgColor = Color.parseColor("#E5AE00"),
            bottomBarButtonColor = Color.parseColor("#FFF9D6"),
            textPrimaryColor = Color.parseColor("#3B2800"),
            textSecondaryColor = Color.parseColor("#7A5400"),
            bgmScaleNotes = intArrayOf(587, 740, 880, 1174, 880) // D major bright
        ),
        Theme3DS(
            id = "streetpass_mint",
            name = "擦肩通信广场（薄荷清风）",
            description = "3DS 擦肩 Mii 广场标志性的清新薄荷绿与纯白质感",
            upperSkyTop = Color.parseColor("#88E0C0"),
            upperSkyBottom = Color.parseColor("#E6FAF2"),
            upperGridColor = Color.parseColor("#5CC8A0"),
            lowerBgTop = Color.parseColor("#D4F4E8"),
            lowerBgBottom = Color.parseColor("#B8E8D6"),
            slotFillColor = Color.parseColor("#FFFFFF"),
            slotBorderColor = Color.parseColor("#78CCB0"),
            emptySlotRecessColor = Color.parseColor("#A4DEC8"),
            cursorMainColor = Color.parseColor("#18B858"),
            cursorGlowColor = Color.parseColor("#72F2A4"),
            topBarBgColor = Color.parseColor("#C2EFE0"),
            bottomBarBgColor = Color.parseColor("#AEE4D0"),
            bottomBarButtonColor = Color.parseColor("#F2FCF8"),
            textPrimaryColor = Color.parseColor("#194234"),
            textSecondaryColor = Color.parseColor("#3E7A65"),
            bgmScaleNotes = intArrayOf(523, 587, 659, 784, 1046) // Pentatonic breeze
        )
    )

    fun getById(id: String): Theme3DS {
        return ALL_THEMES.firstOrNull { it.id == id } ?: ALL_THEMES.first()
    }
}

object BadgeCatalog {
    val ALL_BADGES = listOf(
        BadgeTemplate("badge_qblock", "问号砖块", "超级马力欧", Color.parseColor("#F5A623"), Color.parseColor("#FFF099"), "？"),
        BadgeTemplate("badge_mushroom", "超级蘑菇", "超级马力欧", Color.parseColor("#E60012"), Color.parseColor("#FFFFFF"), "🍄"),
        BadgeTemplate("badge_star", "无敌星", "超级马力欧", Color.parseColor("#FFD700"), Color.parseColor("#FFF8B8"), "★"),
        BadgeTemplate("badge_triforce", "三角力量", "塞尔达传说", Color.parseColor("#D4AF37"), Color.parseColor("#1E3250"), "▲"),
        BadgeTemplate("badge_rupee", "海拉鲁绿卢比", "塞尔达传说", Color.parseColor("#2ECC71"), Color.parseColor("#A3F7BF"), "◆"),
        BadgeTemplate("badge_pokeball", "精灵球", "宝可梦", Color.parseColor("#E60012"), Color.parseColor("#F5F7FA"), "◐"),
        BadgeTemplate("badge_masterball", "大师球", "宝可梦", Color.parseColor("#7B2CBF"), Color.parseColor("#FF4D8D"), "M"),
        BadgeTemplate("badge_3ds_console", "New 3DS 掌机", "任天堂硬件", Color.parseColor("#00A8E8"), Color.parseColor("#E8F1F5"), "3DS"),
        BadgeTemplate("badge_nds_console", "NDS 掌机", "任天堂硬件", Color.parseColor("#5C6B73"), Color.parseColor("#DCE2E6"), "DS"),
        BadgeTemplate("badge_streetpass", "擦肩绿灯", "系统纪念", Color.parseColor("#18B858"), Color.parseColor("#B8F8D0"), "✦"),
        BadgeTemplate("badge_playcoin", "3DS 游戏币", "系统纪念", Color.parseColor("#F7B801"), Color.parseColor("#FFF3B0"), "¢"),
        BadgeTemplate("badge_kirby", "噗噗噗之星", "星之卡比", Color.parseColor("#FF75A0"), Color.parseColor("#FFD6E4"), "♥")
    )

    fun getById(id: String): BadgeTemplate? = ALL_BADGES.firstOrNull { it.id == id }
}

/**
 * 上屏游戏主舞台展示方案预设
 */
enum class UpperDisplayMode(
    val modeId: Int,
    val title: String,
    val shortLabel: String,
    val desc: String,
    val colorHex: String
) {
    LOGO_ONLY(0, "👑 仅显示Logo", "仅Logo", "超大高透Logo · 居中悬浮极简无标题", "#EC4899"),
    LOGO_TITLE(1, "🔤 Logo+纯字标题", "Logo+纯字", "高透Logo居中 · 浮空文字无背景框", "#06B6D4"),
    LOGO_HERO(2, "✨ 悬浮Logo模式", "悬浮Logo", "左侧Logo+右侧视频 · 经典3DS磨砂标题飘带", "#10B981"),
    BOXART_3D(3, "📦 3D卡盒模式", "3D卡盒", "经典立体卡带盒 · 360°自由把玩", "#0284C7"),
    DUAL_HYBRID(4, "💎 双重豪华模式", "双重豪华", "3D立体卡盒 + 悬浮Logo双重光影", "#8B5CF6"),
    CINEMA_PURE(5, "🎬 纯净大屏模式", "纯净大屏", "居中全景视界 · 无遮挡沉浸式视频", "#F59E0B");

    companion object {
        fun fromId(id: Int): UpperDisplayMode = entries.firstOrNull { it.modeId == id } ?: BOXART_3D

        fun fromBooleans(showBox: Boolean, showLogo: Boolean): UpperDisplayMode = when {
            !showBox && showLogo -> LOGO_ONLY
            showBox && !showLogo -> BOXART_3D
            showBox && showLogo -> DUAL_HYBRID
            else -> CINEMA_PURE
        }
    }
}
