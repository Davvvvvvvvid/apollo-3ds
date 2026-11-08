package com.gameconsole.launcher3ds.companion.repository

import android.content.Context
import android.os.Environment
import android.util.Log
import com.gameconsole.launcher3ds.companion.model.CompanionProfile
import com.gameconsole.launcher3ds.model.GridSlotItem
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * 💾 战术伴侣配置与模板仓库 (CompanionProfileRepository)
 * 职责：
 * 1. 负责单个游戏专属 Profile 的自动读取与持久化保存；
 * 2. 负责通用模板库 (Templates) 的增删改查；
 * 3. 负责平台默认模板 (Platform Defaults) 的兜底管理；
 * 4. 自动扫描 ROM 所在目录下的攻略图档、全景地图与说明书，无缝装配到该游戏画廊与攻略页！
 */
class CompanionProfileRepository(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val baseDir: File by lazy {
        // 优先存放在外部存储的标准目录下，以便用户插电脑备份与分享
        val extDir = File(Environment.getExternalStorageDirectory(), "Launcher3DS/companion")
        if (!extDir.exists()) {
            extDir.mkdirs()
        }
        if (extDir.canWrite()) extDir else File(context.filesDir, "companion").apply { if (!exists()) mkdirs() }
    }

    private val gameProfilesDir: File by lazy {
        File(baseDir, "game_profiles").apply { if (!exists()) mkdirs() }
    }

    private val templatesDir: File by lazy {
        File(baseDir, "templates").apply { if (!exists()) mkdirs() }
    }

    private val platformDefaultsDir: File by lazy {
        File(baseDir, "platform_defaults").apply { if (!exists()) mkdirs() }
    }

    init {
        ensureDefaultTemplates()
    }

    /**
     * 1. 获取某个游戏的专属伴侣配置 (1对1精准加载)
     */
    fun getProfileForGame(slot: GridSlotItem): CompanionProfile {
        val gameKey = CompanionProfile.generateGameKey(slot.title, slot.romPath)
        val profileFile = File(gameProfilesDir, "${gameKey}.json")

        if (profileFile.exists()) {
            try {
                val json = profileFile.readText()
                val profile = gson.fromJson(json, CompanionProfile::class.java)
                if (profile != null) {
                    profile.romPath = slot.romPath
                    profile.gameTitle = slot.title
                    profile.platform = slot.getEffectivePlatform()
                    scanLocalRomAssets(slot, profile)
                    return profile
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse game profile for ${slot.title}", e)
            }
        }

        // 若尚未为该游戏单独配置过：检查平台默认模板
        val platDefaultFile = File(platformDefaultsDir, "${slot.getEffectivePlatform().lowercase()}.json")
        if (platDefaultFile.exists()) {
            try {
                val json = platDefaultFile.readText()
                val platProfile = gson.fromJson(json, CompanionProfile::class.java)
                if (platProfile != null) {
                    platProfile.isTemplate = false
                    platProfile.gameTitle = slot.title
                    platProfile.platform = slot.getEffectivePlatform()
                    platProfile.romPath = slot.romPath
                    scanLocalRomAssets(slot, platProfile)
                    return platProfile
                }
            } catch (_: Exception) {}
        }

        // 创建全新单游戏专属配置并自动扫描本地攻略与大图
        val newProfile = CompanionProfile.createDefault(
            gameTitle = slot.title,
            platform = slot.getEffectivePlatform(),
            romPath = slot.romPath,
            isTemplate = false
        )
        scanLocalRomAssets(slot, newProfile)
        saveGameProfile(newProfile)
        return newProfile
    }

    /**
     * 保存单个游戏的专属配置
     */
    fun saveGameProfile(profile: CompanionProfile) {
        val gameKey = CompanionProfile.generateGameKey(profile.gameTitle, profile.romPath)
        profile.updatedAt = System.currentTimeMillis()
        val file = File(gameProfilesDir, "${gameKey}.json")
        try {
            file.writeText(gson.toJson(profile))
            Log.d(TAG, "Saved game profile to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving game profile", e)
        }
    }

    /**
     * 将当前配置【另存为模板】收录进公共模板库
     */
    fun saveAsTemplate(profile: CompanionProfile, templateName: String): CompanionProfile {
        val cleanName = templateName.trim().ifBlank { "我的自建模板_${System.currentTimeMillis()}" }
        val templateKey = cleanName.lowercase().replace(Regex("[^a-zA-Z0-9_\\u4e00-\\u9fa5]"), "_")
        val templateProfile = profile.copy(
            id = "template_${templateKey}",
            name = cleanName,
            isTemplate = true,
            updatedAt = System.currentTimeMillis()
        )
        val file = File(templatesDir, "${templateKey}.json")
        try {
            file.writeText(gson.toJson(templateProfile))
            Log.d(TAG, "Saved template to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving template", e)
        }
        return templateProfile
    }

    /**
     * 列出模板库中所有可用模板
     */
    fun listAllTemplates(): List<CompanionProfile> {
        val files = templatesDir.listFiles { f -> f.extension.equals("json", ignoreCase = true) } ?: return emptyList()
        val list = mutableListOf<CompanionProfile>()
        for (f in files) {
            try {
                val p = gson.fromJson(f.readText(), CompanionProfile::class.java)
                if (p != null) list.add(p)
            } catch (_: Exception) {}
        }
        return list
    }

    /**
     * 删除某个自建模板
     */
    fun deleteTemplate(templateId: String): Boolean {
        val cleanKey = templateId.removePrefix("template_")
        val file = File(templatesDir, "${cleanKey}.json")
        return if (file.exists()) file.delete() else false
    }

    /**
     * 将模板库中的某套模板应用到当前单个游戏上
     */
    fun applyTemplateToGame(templateId: String, slot: GridSlotItem): CompanionProfile {
        val templates = listAllTemplates()
        val targetTemplate = templates.firstOrNull { it.id == templateId } ?: CompanionProfile.createDefault(
            gameTitle = slot.title,
            platform = slot.getEffectivePlatform(),
            romPath = slot.romPath
        )

        val appliedProfile = targetTemplate.copy(
            id = "profile_${CompanionProfile.generateGameKey(slot.title, slot.romPath)}",
            name = "${slot.title} 专属面板",
            isTemplate = false,
            gameTitle = slot.title,
            platform = slot.getEffectivePlatform(),
            romPath = slot.romPath,
            updatedAt = System.currentTimeMillis()
        )
        scanLocalRomAssets(slot, appliedProfile)
        saveGameProfile(appliedProfile)
        return appliedProfile
    }

    /**
     * 智能扫描：自动将 ROM 所在目录及上级目录中的同名攻略图、地图、说明书关联进该游戏
     */
    private fun scanLocalRomAssets(slot: GridSlotItem, profile: CompanionProfile) {
        if (slot.boxArtPath.isNotBlank() && !profile.gallery.customImagePaths.contains(slot.boxArtPath)) {
            profile.gallery.customImagePaths.add(0, slot.boxArtPath)
        }

        if (slot.romPath.isBlank()) return
        val romFile = File(slot.romPath)
        val parentDir = romFile.parentFile ?: return
        val baseName = romFile.nameWithoutExtension

        // 扫描同目录下的攻略图片与地图 (如 游戏名_map.png, 游戏名_guide.jpg)
        val imageExtensions = listOf("png", "jpg", "jpeg", "webp")
        val textExtensions = listOf("txt", "md")

        val siblingFiles = parentDir.listFiles() ?: return
        for (file in siblingFiles) {
            val ext = file.extension.lowercase()
            val fileName = file.nameWithoutExtension
            if (ext in imageExtensions && (fileName.contains(baseName, ignoreCase = true) || fileName.contains("map", ignoreCase = true) || fileName.contains("guide", ignoreCase = true))) {
                if (!profile.guide.customImagePaths.contains(file.absolutePath)) {
                    profile.guide.customImagePaths.add(file.absolutePath)
                }
            } else if (ext in textExtensions && fileName.contains(baseName, ignoreCase = true)) {
                if (profile.guide.guideText.isBlank()) {
                    try {
                        profile.guide.guideText = file.readText().take(50000)
                        profile.guide.guideMarkdownPath = file.absolutePath
                    } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * 初始化内置的精品官方模板 (卡普空 6 键格斗、JRPG 快速刷级、RTS 触控板鼠标)
     */
    private fun ensureDefaultTemplates() {
        val tplFighter = File(templatesDir, "capcom_fighter.json")
        if (!tplFighter.exists()) {
            val p = CompanionProfile.createDefault("街头霸王", "ARCADE", "", isTemplate = true, templateName = "卡普空 6 键格斗神器").apply {
                defaultTab = com.gameconsole.launcher3ds.companion.model.CompanionTab.SF4_BUTTONS
                enabledTabs = mutableListOf(
                    com.gameconsole.launcher3ds.companion.model.CompanionTab.SF4_BUTTONS,
                    com.gameconsole.launcher3ds.companion.model.CompanionTab.GUIDE,
                    com.gameconsole.launcher3ds.companion.model.CompanionTab.AI_CHAT
                )
                sf4Layout = com.gameconsole.launcher3ds.companion.model.Sf4LayoutType.SIX_PADS_3X2
                sf4Buttons.clear()
                sf4Buttons.add(com.gameconsole.launcher3ds.companion.model.Sf4ButtonItem("b1", "轻拳 LP", "按键 X", "#2563EB", keyCode = 99))
                sf4Buttons.add(com.gameconsole.launcher3ds.companion.model.Sf4ButtonItem("b2", "中拳 MP", "按键 Y", "#2563EB", keyCode = 100))
                sf4Buttons.add(com.gameconsole.launcher3ds.companion.model.Sf4ButtonItem("b3", "重拳 HP", "按键 R1", "#2563EB", keyCode = 102))
                sf4Buttons.add(com.gameconsole.launcher3ds.companion.model.Sf4ButtonItem("b4", "轻脚 LK", "按键 A", "#DC2626", keyCode = 96))
                sf4Buttons.add(com.gameconsole.launcher3ds.companion.model.Sf4ButtonItem("b5", "中脚 MK", "按键 B", "#DC2626", keyCode = 97))
                sf4Buttons.add(com.gameconsole.launcher3ds.companion.model.Sf4ButtonItem("b6", "超必杀 🌟", "灭·波动拳", "#7C3AED", keyCode = 103))
            }
            try { tplFighter.writeText(gson.toJson(p)) } catch (_: Exception) {}
        }

        val tplJrpg = File(templatesDir, "jrpg_grind.json")
        if (!tplJrpg.exists()) {
            val p = CompanionProfile.createDefault("勇者斗恶龙", "GBA", "", isTemplate = true, templateName = "JRPG 高速练级与存读档").apply {
                defaultTab = com.gameconsole.launcher3ds.companion.model.CompanionTab.SF4_BUTTONS
                enabledTabs = mutableListOf(
                    com.gameconsole.launcher3ds.companion.model.CompanionTab.TRANSLATE,
                    com.gameconsole.launcher3ds.companion.model.CompanionTab.GUIDE,
                    com.gameconsole.launcher3ds.companion.model.CompanionTab.SF4_BUTTONS,
                    com.gameconsole.launcher3ds.companion.model.CompanionTab.AI_CHAT
                )
                sf4Layout = com.gameconsole.launcher3ds.companion.model.Sf4LayoutType.FOUR_PADS_2X2
                sf4Buttons.clear()
                sf4Buttons.add(com.gameconsole.launcher3ds.companion.model.Sf4ButtonItem("b1", "即时存档 💾", "Slot 1 (F2)", "#059669", actionType = com.gameconsole.launcher3ds.companion.model.Sf4ButtonItem.ACTION_SAVE_STATE, keyCode = 132))
                sf4Buttons.add(com.gameconsole.launcher3ds.companion.model.Sf4ButtonItem("b2", "即时读档 📂", "Slot 1 (F4)", "#2563EB", actionType = com.gameconsole.launcher3ds.companion.model.Sf4ButtonItem.ACTION_LOAD_STATE, keyCode = 134))
                sf4Buttons.add(com.gameconsole.launcher3ds.companion.model.Sf4ButtonItem("b3", "3x 快进 ⚡", "战斗加速", "#F59E0B", actionType = com.gameconsole.launcher3ds.companion.model.Sf4ButtonItem.ACTION_FAST_FORWARD, keyCode = 62))
                sf4Buttons.add(com.gameconsole.launcher3ds.companion.model.Sf4ButtonItem("b4", "A 键连发 ⚔️", "自动砍怪", "#DC2626", actionType = com.gameconsole.launcher3ds.companion.model.Sf4ButtonItem.ACTION_TURBO, keyCode = 96, isTurbo = true))
            }
            try { tplJrpg.writeText(gson.toJson(p)) } catch (_: Exception) {}
        }

        val tplRts = File(templatesDir, "rts_trackpad.json")
        if (!tplRts.exists()) {
            val p = CompanionProfile.createDefault("红色警戒", "PC", "", isTemplate = true, templateName = "RTS / PC 高精触控板").apply {
                defaultTab = com.gameconsole.launcher3ds.companion.model.CompanionTab.TOUCHPAD
                enabledTabs = mutableListOf(
                    com.gameconsole.launcher3ds.companion.model.CompanionTab.TOUCHPAD,
                    com.gameconsole.launcher3ds.companion.model.CompanionTab.GUIDE,
                    com.gameconsole.launcher3ds.companion.model.CompanionTab.AI_CHAT
                )
            }
            try { tplRts.writeText(gson.toJson(p)) } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "CompanionRepo"
    }
}
