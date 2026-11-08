package com.gameconsole.launcher3ds.companion.model

import com.gameconsole.launcher3ds.model.GridSlotItem
import com.gameconsole.launcher3ds.model.PlatformType
import java.io.File

/**
 * 🎮 3DS 战术伴侣六大面板枚举
 */
enum class CompanionTab(val id: String, val title: String, val icon: String) {
    TRANSLATE("translate", "剧情翻译", "🌐"),
    GALLERY("gallery", "游戏画廊", "🖼️"),
    GUIDE("guide", "图文攻略", "📖"),
    SF4_BUTTONS("sf4_buttons", "街霸按键", "🕹️"),
    TOUCHPAD("touchpad", "高精触控板", "🖱️"),
    AI_CHAT("ai_chat", "DeepSeek", "🤖")
}

/**
 * 🕹️ 街霸4 风格虚拟触控按键排布
 */
enum class Sf4LayoutType(val id: String, val displayName: String, val rows: Int, val cols: Int) {
    FOUR_PADS_2X2("2x2", "4 大方格 (3DS 经典)", 2, 2),
    SIX_PADS_3X2("3x2", "6 键街机 (卡普空格斗)", 2, 3),
    EIGHT_PADS_4X2("4x2", "8 键全功能", 2, 4)
}

/**
 * 🕹️ 单个触控按键配置
 */
data class Sf4ButtonItem(
    val id: String,
    var title: String,
    var subTitle: String = "",
    var colorTheme: String = "#2563EB", // 卡普空蓝, 烈焰红, 黄金等
    var actionType: String = ACTION_KEY_CODE, // KEY_CODE, SAVE_STATE, LOAD_STATE, FAST_FORWARD, TURBO
    var keyCode: Int = 0,
    var macroKeys: List<Int> = emptyList(),
    var isTurbo: Boolean = false
) {
    companion object {
        const val ACTION_KEY_CODE = "KEY_CODE"
        const val ACTION_SAVE_STATE = "SAVE_STATE"
        const val ACTION_LOAD_STATE = "LOAD_STATE"
        const val ACTION_FAST_FORWARD = "FAST_FORWARD"
        const val ACTION_REWIND = "REWIND"
        const val ACTION_SCREENSHOT = "SCREENSHOT"
        const val ACTION_TURBO = "TURBO"
    }
}

/**
 * 🖱️ 触控板配置
 */
data class TouchpadConfig(
    var sensitivity: Float = 1.2f, // 0.8f, 1.2f, 1.8f, 2.5f
    var vibrationEnabled: Boolean = true,
    var tapToClick: Boolean = true,
    var twoFingerRightClick: Boolean = true,
    var scrollWheelEnabled: Boolean = true
)

/**
 * 📖 图文攻略配置
 */
data class GuideConfig(
    val customImagePaths: MutableList<String> = mutableListOf(),
    var selectedImageIndex: Int = 0,
    var guideText: String = "",
    var guideMarkdownPath: String = ""
)

/**
 * 🖼️ 游戏画廊配置
 */
data class GalleryConfig(
    val customImagePaths: MutableList<String> = mutableListOf(),
    var ambientKenBurnsEnabled: Boolean = true,
    var displayMode: String = "FIT" // FIT, CROP
)

/**
 * 🤖 DeepSeek AI 对话配置
 */
data class AiChatConfig(
    var systemPrompt: String = "",
    var apiKey: String = "",
    var baseUrl: String = "https://api.deepseek.com/v1",
    var modelName: String = "deepseek-chat", // 兼容 DeepSeek v3 / v4.1 / Flash
    var temperature: Float = 0.6f
)

/**
 * 🌐 翻译面板配置
 */
data class TranslateConfig(
    var autoTranslateEnabled: Boolean = false,
    var targetLanguage: String = "zh",
    var sourceLanguage: String = "auto"
)

/**
 * 🏷️ 战术伴侣综合配置档案 (支持单个游戏专属 profile 与通用可复用 template)
 */
data class CompanionProfile(
    val id: String, // "profile_${gameKey}" 或 "template_${templateId}"
    var name: String,
    var isTemplate: Boolean = false,
    var gameTitle: String = "",
    var platform: String = "",
    var romPath: String = "",
    var defaultTab: CompanionTab = CompanionTab.SF4_BUTTONS,
    var enabledTabs: MutableList<CompanionTab>? = CompanionTab.values().toMutableList(),
    var sf4Layout: Sf4LayoutType = Sf4LayoutType.FOUR_PADS_2X2,
    val sf4Buttons: MutableList<Sf4ButtonItem> = mutableListOf(),
    val touchpad: TouchpadConfig = TouchpadConfig(),
    val guide: GuideConfig = GuideConfig(),
    val gallery: GalleryConfig = GalleryConfig(),
    val aiChat: AiChatConfig = AiChatConfig(),
    val translate: TranslateConfig = TranslateConfig(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 获取当前生效的可见标签列表（若为空则兜底展示全部六大标签）
     */
    fun getEffectiveTabs(): List<CompanionTab> {
        val list = (enabledTabs as List<CompanionTab>?)
        return if (list.isNullOrEmpty()) CompanionTab.values().toList() else list
    }
    companion object {
        fun createDefault(gameTitle: String, platform: String, romPath: String, isTemplate: Boolean = false, templateName: String = ""): CompanionProfile {
            val key = if (isTemplate) templateName else generateGameKey(gameTitle, romPath)
            val profileId = if (isTemplate) "template_${key}" else "profile_${key}"
            val pName = if (isTemplate) templateName else "$gameTitle 专属面板"

            // 智能预设：如果是格斗类游戏，默认推荐街霸 4 大方格
            val isFighter = gameTitle.contains("街霸", ignoreCase = true) ||
                    gameTitle.contains("拳皇", ignoreCase = true) ||
                    gameTitle.contains("KOF", ignoreCase = true) ||
                    gameTitle.contains("Street Fighter", ignoreCase = true) ||
                    gameTitle.contains("Tekken", ignoreCase = true) ||
                    gameTitle.contains("Alpha", ignoreCase = true)

            val isRtsOrPc = gameTitle.contains("红警", ignoreCase = true) ||
                    gameTitle.contains("辐射", ignoreCase = true) ||
                    gameTitle.contains("星际", ignoreCase = true) ||
                    gameTitle.contains("魔兽", ignoreCase = true) ||
                    gameTitle.contains("Winlator", ignoreCase = true)

            val initialTab = when {
                isRtsOrPc -> CompanionTab.TOUCHPAD
                isFighter -> CompanionTab.SF4_BUTTONS
                else -> CompanionTab.SF4_BUTTONS
            }

            val defaultButtons = if (isFighter) {
                mutableListOf(
                    Sf4ButtonItem(id = "b1", title = "波动拳 ⚡", subTitle = "↓↘→ + P", colorTheme = "#2563EB", actionType = Sf4ButtonItem.ACTION_KEY_CODE, keyCode = 99),
                    Sf4ButtonItem(id = "b2", title = "升龙拳 💥", subTitle = "→↓↘ + P", colorTheme = "#DC2626", actionType = Sf4ButtonItem.ACTION_KEY_CODE, keyCode = 100),
                    Sf4ButtonItem(id = "b3", title = "3P 同按 🥊", subTitle = "EX 技能", colorTheme = "#F59E0B", actionType = Sf4ButtonItem.ACTION_KEY_CODE, keyCode = 102),
                    Sf4ButtonItem(id = "b4", title = "超必杀 🌟", subTitle = "真空·波动拳", colorTheme = "#7C3AED", actionType = Sf4ButtonItem.ACTION_KEY_CODE, keyCode = 103)
                )
            } else {
                mutableListOf(
                    Sf4ButtonItem(id = "b1", title = "快速存档 💾", subTitle = "Slot 1 (F2)", colorTheme = "#059669", actionType = Sf4ButtonItem.ACTION_SAVE_STATE, keyCode = 132),
                    Sf4ButtonItem(id = "b2", title = "快速读档 📂", subTitle = "Slot 1 (F4)", colorTheme = "#2563EB", actionType = Sf4ButtonItem.ACTION_LOAD_STATE, keyCode = 134),
                    Sf4ButtonItem(id = "b3", title = "3x 快进 ⚡", subTitle = "切换加速", colorTheme = "#F59E0B", actionType = Sf4ButtonItem.ACTION_FAST_FORWARD, keyCode = 62),
                    Sf4ButtonItem(id = "b4", title = "连发 Turbo ⚔️", subTitle = "A 键高频连击", colorTheme = "#DC2626", actionType = Sf4ButtonItem.ACTION_TURBO, keyCode = 96, isTurbo = true)
                )
            }

            return CompanionProfile(
                id = profileId,
                name = pName,
                isTemplate = isTemplate,
                gameTitle = gameTitle,
                platform = platform,
                romPath = romPath,
                defaultTab = initialTab,
                sf4Layout = Sf4LayoutType.FOUR_PADS_2X2,
                sf4Buttons = defaultButtons,
                touchpad = TouchpadConfig(),
                guide = GuideConfig(),
                gallery = GalleryConfig(),
                aiChat = AiChatConfig(
                    systemPrompt = "你是专业的掌机游戏战略助手。当前玩家正在掌机主屏上游玩《$gameTitle》（所属机种平台：$platform）。请以资深游戏玩家的口吻，简明扼要、直切要点地提供关键剧情解谜、Boss 弱点打法、地图宝箱、机体/角色加点或出招策略建议。"
                )
            )
        }

        fun generateGameKey(gameTitle: String, romPath: String): String {
            if (romPath.isNotBlank()) {
                val name = File(romPath).nameWithoutExtension
                if (name.isNotBlank()) {
                    return name.trim().lowercase().replace(Regex("[^a-zA-Z0-9_\\u4e00-\\u9fa5]"), "_")
                }
            }
            return gameTitle.trim().lowercase().replace(Regex("[^a-zA-Z0-9_\\u4e00-\\u9fa5]"), "_")
        }
    }
}
