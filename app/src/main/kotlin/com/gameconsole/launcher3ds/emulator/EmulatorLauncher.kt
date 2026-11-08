package com.gameconsole.launcher3ds.emulator

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.StrictMode
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.gameconsole.launcher3ds.model.GridSlotItem
import com.gameconsole.launcher3ds.model.PlatformType
import java.io.File

data class EmulatorCandidate(
    val name: String,
    val packageName: String,
    val platform: PlatformType = PlatformType.N3DS,
    val knownActivity: String = "",
    var isInstalled: Boolean = false
)

object EmulatorLauncher {

    val KNOWN_3DS_EMULATORS = listOf(
        EmulatorCandidate("Citra MMJ (推荐掌机版)", "org.citra.emu", PlatformType.N3DS, "org.citra.emu.ui.EmulationActivity"),
        EmulatorCandidate("Citra 官方正式版", "org.citra.citra_emu", PlatformType.N3DS, "org.citra.citra_emu.activities.EmulationActivity"),
        EmulatorCandidate("Citra Canary (实验版)", "org.citra.citra_emu.canary", PlatformType.N3DS, "org.citra.citra_emu.activities.EmulationActivity"),
        EmulatorCandidate("Citra MMJ 安兔兔极速版", "org.citra.emu.antutu", PlatformType.N3DS, "org.citra.emu.ui.EmulationActivity"),
        EmulatorCandidate("Citra 官方安兔兔版", "org.citra.citra_emu.antutu", PlatformType.N3DS, "org.citra.citra_emu.activities.EmulationActivity"),
        EmulatorCandidate("Lime3DS (分支版)", "io.github.lime3ds.android", PlatformType.N3DS, "org.citra.citra_emu.activities.EmulationActivity"),
        EmulatorCandidate("Azahar 3DS", "org.azahar_emu.azahar", PlatformType.N3DS, "org.citra.citra_emu.activities.EmulationActivity"),
        EmulatorCandidate("Mandarine 3DS", "io.github.mandarine3ds.mandarine", PlatformType.N3DS, "org.citra.citra_emu.activities.EmulationActivity"),
        EmulatorCandidate("Citra 社区通用版", "com.citra.emu", PlatformType.N3DS, "org.citra.emu.ui.EmulationActivity"),
        EmulatorCandidate("RetroArch (64位)", "com.retroarch.aarch64", PlatformType.N3DS, "com.retroarch.browser.retroactivity.RetroActivityFuture"),
        EmulatorCandidate("RetroArch", "com.retroarch", PlatformType.N3DS, "com.retroarch.browser.retroactivity.RetroActivityFuture")
    )

    val KNOWN_NDS_EMULATORS = listOf(
        EmulatorCandidate("DraStic (激烈NDS)", "com.dsemu.drastic", PlatformType.NDS, "com.dsemu.drastic.DraSticActivity"),
        EmulatorCandidate("DraStic 官方版", "com.drastic.emulator", PlatformType.NDS, "com.dsemu.drastic.DraSticActivity"),
        EmulatorCandidate("DraStic VR/修改版", "com.dsemu.drasticvr", PlatformType.NDS, "com.dsemu.drastic.DraSticActivity"),
        EmulatorCandidate("melonDS", "me.magnum.melonds", PlatformType.NDS, "me.magnum.melonds.ui.emulator.EmulatorActivity"),
        EmulatorCandidate("melonDS Nightly", "me.magnum.melonds.nightly", PlatformType.NDS, "me.magnum.melonds.ui.emulator.EmulatorActivity"),
        EmulatorCandidate("Lemuroid", "com.swordfish.lemuroid", PlatformType.NDS, ""),
        EmulatorCandidate("RetroArch (64位)", "com.retroarch.aarch64", PlatformType.NDS, "com.retroarch.browser.retroactivity.RetroActivityFuture"),
        EmulatorCandidate("RetroArch", "com.retroarch", PlatformType.NDS, "com.retroarch.browser.retroactivity.RetroActivityFuture")
    )

    val KNOWN_GBA_EMULATORS = listOf(
        EmulatorCandidate("RetroArch (64位)", "com.retroarch.aarch64", PlatformType.N3DS, "com.retroarch.browser.retroactivity.RetroActivityFuture"),
        EmulatorCandidate("RetroArch (32位)", "com.retroarch", PlatformType.N3DS, "com.retroarch.browser.retroactivity.RetroActivityFuture"),
        EmulatorCandidate("RetroArch (RA32)", "com.retroarch.ra32", PlatformType.N3DS, "com.retroarch.browser.retroactivity.RetroActivityFuture"),
        EmulatorCandidate("My Boy! (GBA)", "com.fastemulator.gba", PlatformType.N3DS, "com.fastemulator.gba.EmulatorActivity"),
        EmulatorCandidate("My Boy! 免费版", "com.fastemulator.gbafree", PlatformType.N3DS, "com.fastemulator.gba.EmulatorActivity"),
        EmulatorCandidate("Pizza Boy GBA", "it.dbassacc.pizzaemugba", PlatformType.N3DS, "it.dbassacc.pizzaemugba.MainActivity"),
        EmulatorCandidate("Pizza Boy GBA Pro", "it.dbassacc.pizzaemugbapro", PlatformType.N3DS, "it.dbassacc.pizzaemugbapro.MainActivity"),
        EmulatorCandidate("Lemuroid", "com.swordfish.lemuroid", PlatformType.N3DS, ""),
        EmulatorCandidate("Nostalgia.GBA", "com.nostalgiaemulators.nostalgiagba", PlatformType.N3DS, "")
    )

    val KNOWN_PSP_EMULATORS = listOf(
        EmulatorCandidate("PPSSPP (PSP)", "org.ppsspp.ppsspp", PlatformType.N3DS, "org.ppsspp.ppsspp.PpssppActivity"),
        EmulatorCandidate("PPSSPP 黄金版 (Gold)", "org.ppsspp.ppssppgold", PlatformType.N3DS, "org.ppsspp.ppsspp.PpssppActivity"),
        EmulatorCandidate("RetroArch (64位)", "com.retroarch.aarch64", PlatformType.N3DS, "com.retroarch.browser.retroactivity.RetroActivityFuture"),
        EmulatorCandidate("RetroArch", "com.retroarch", PlatformType.N3DS, "com.retroarch.browser.retroactivity.RetroActivityFuture")
    )

    val KNOWN_PS2_EMULATORS = listOf(
        EmulatorCandidate("AetherSX2 (PS2)", "xyz.aethersx2.android", PlatformType.N3DS, "xyz.aethersx2.android.EmulationActivity"),
        EmulatorCandidate("NetherSX2 (优化版)", "xyz.nethersx2.android", PlatformType.N3DS, "xyz.nethersx2.android.EmulationActivity"),
        EmulatorCandidate("AetherSX2 Alpha", "xyz.aethersx2.android.alpha", PlatformType.N3DS, "xyz.aethersx2.android.alpha.EmulationActivity")
    )

    val KNOWN_PS1_EMULATORS = listOf(
        EmulatorCandidate("DuckStation (PS1)", "com.github.stenzek.duckstation", PlatformType.N3DS, "com.github.stenzek.duckstation.EmulationActivity"),
        EmulatorCandidate("ePSXe", "com.epsxe.ePSXe", PlatformType.N3DS, "com.epsxe.ePSXe.ePSXe"),
        EmulatorCandidate("RetroArch (64位)", "com.retroarch.aarch64", PlatformType.N3DS, "com.retroarch.browser.retroactivity.RetroActivityFuture"),
        EmulatorCandidate("RetroArch", "com.retroarch", PlatformType.N3DS, "com.retroarch.browser.retroactivity.RetroActivityFuture")
    )

    val KNOWN_N64_EMULATORS = listOf(
        EmulatorCandidate("Mupen64Plus FZ", "org.mupen64plusae.v3.fzurita", PlatformType.N3DS, "paulscode.android.mupen64plusae.SplashActivity"),
        EmulatorCandidate("Mupen64Plus FZ Pro", "org.mupen64plusae.v3.fzurita.pro", PlatformType.N3DS, "paulscode.android.mupen64plusae.SplashActivity"),
        EmulatorCandidate("RetroArch (64位)", "com.retroarch.aarch64", PlatformType.N3DS, "com.retroarch.browser.retroactivity.RetroActivityFuture")
    )

    val KNOWN_DOLPHIN_EMULATORS = listOf(
        EmulatorCandidate("Dolphin (NGC/Wii)", "org.dolphinemu.dolphinemu", PlatformType.N3DS, "org.dolphinemu.dolphinemu.ui.main.MainActivity"),
        EmulatorCandidate("Dolphin MMJR", "org.dolphinemu.mmjr", PlatformType.N3DS, "org.dolphinemu.mmjr.ui.main.MainActivity"),
        EmulatorCandidate("Dolphin Handheld", "org.dolphinemu.handheld", PlatformType.N3DS, "org.dolphinemu.handheld.ui.main.MainActivity")
    )

    fun getAvailableEmulators(context: Context, platform: PlatformType): List<EmulatorCandidate> {
        val pm = context.packageManager
        val sourceList = if (platform == PlatformType.NDS) KNOWN_NDS_EMULATORS else KNOWN_3DS_EMULATORS
        val knownResult = sourceList.map { candidate ->
            val installed = isPackageInstalled(pm, candidate.packageName)
            candidate.copy(isInstalled = installed)
        }.toMutableList()

        if (platform == PlatformType.N3DS) {
            // 动态扫描：自动发现设备上已安装但包名特殊的任意 Citra / 3DS 模拟器变体（如各种汉化定制版、优化版）
            try {
                val installedApps = pm.getInstalledApplications(0)
                for (app in installedApps) {
                    val pkg = app.packageName.lowercase()
                    val label = try { pm.getApplicationLabel(app).toString() } catch (_: Exception) { "" }
                    val labelLower = label.lowercase()
                    if ((pkg.contains("citra") || pkg.contains("3ds") || labelLower.contains("citra") || labelLower.contains("3ds")) &&
                        knownResult.none { it.packageName.equals(app.packageName, ignoreCase = true) } &&
                        !pkg.contains("com.gameconsole.launcher3ds")
                    ) {
                        knownResult.add(
                            EmulatorCandidate(
                                name = if (label.isNotBlank()) "$label [自定义]" else app.packageName,
                                packageName = app.packageName,
                                platform = PlatformType.N3DS,
                                knownActivity = "",
                                isInstalled = true
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
        }
        return knownResult
    }

    fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun findInstalledEmulatorForPlatform(context: Context, platform: String): String {
        val pm = context.packageManager
        val candidates: List<String> = when (platform.uppercase()) {
            "3DS" -> KNOWN_3DS_EMULATORS.map { it.packageName }
            "NDS" -> KNOWN_NDS_EMULATORS.map { it.packageName }
            "GBA" -> KNOWN_GBA_EMULATORS.map { it.packageName }
            "GBC", "GB" -> listOf("com.retroarch.aarch64", "com.retroarch", "com.fastemulator.gbc", "com.fastemulator.gbcfree", "it.dbassacc.pizzaemugbc", "com.swordfish.lemuroid")
            "PSP" -> KNOWN_PSP_EMULATORS.map { it.packageName }
            "PS2" -> KNOWN_PS2_EMULATORS.map { it.packageName }
            "PS1" -> KNOWN_PS1_EMULATORS.map { it.packageName }
            "N64" -> KNOWN_N64_EMULATORS.map { it.packageName }
            "NGC", "WII" -> KNOWN_DOLPHIN_EMULATORS.map { it.packageName }
            "SWITCH" -> listOf("org.yuzu.yuzu_emu", "org.sudachi.sudachi_emu", "skyline.emu")
            "FC", "NES" -> listOf("com.retroarch.aarch64", "com.retroarch", "com.nostalgiaemulators.nesfull", "com.nostalgiaemulators.neslite", "com.swordfish.lemuroid")
            "SFC", "SNES" -> listOf("com.retroarch.aarch64", "com.retroarch", "com.explusalpha.Snes9xPlus", "com.swordfish.lemuroid")
            "MD", "GENESIS" -> listOf("com.retroarch.aarch64", "com.retroarch", "com.explusalpha.MdEmu", "com.swordfish.lemuroid")
            "DC" -> listOf("com.flyinghead.flycast", "io.recompiled.redream", "com.retroarch.aarch64", "com.retroarch")
            else -> listOf("com.retroarch.aarch64", "com.retroarch", "com.swordfish.lemuroid")
        }
        for (pkg in candidates) {
            if (isPackageInstalled(pm, pkg)) {
                return pkg
            }
        }
        val defaultPkg = com.gameconsole.launcher3ds.model.PlatformCatalog.getById(platform)?.defaultEmulatorPkg.orEmpty()
        if (defaultPkg.isNotBlank() && isPackageInstalled(pm, defaultPkg)) {
            return defaultPkg
        }

        // 动态扫描已安装应用：防止某些第三方修改版/汉化版 DraStic 或 melonDS 使用了不同包名
        if (platform.equals("NDS", ignoreCase = true)) {
            try {
                val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0)).map { it.packageName }
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledPackages(0).map { it.packageName }
                }
                val dynamicDrastic = installed.firstOrNull { it.contains("drastic", ignoreCase = true) }
                if (!dynamicDrastic.isNullOrBlank()) return dynamicDrastic
                val dynamicMelon = installed.firstOrNull { it.contains("melonds", ignoreCase = true) }
                if (!dynamicMelon.isNullOrBlank()) return dynamicMelon
            } catch (_: Exception) {}
        }
        return ""
    }

    /**
     * 禁用 StrictMode VmPolicy 限制，允许传递 file:// URI 供 DraStic / RetroArch / PPSSPP 等原生模拟器读取
     */
    fun disableDeathOnFileUriExposure() {
        try {
            val builder = StrictMode.VmPolicy.Builder()
            StrictMode.setVmPolicy(builder.build())
        } catch (_: Throwable) {}
        try {
            val m = StrictMode::class.java.getMethod("disableDeathOnFileUriExposure")
            m.invoke(null)
        } catch (_: Throwable) {}
    }

    /**
     * 如果 ROM 为 .zip 格式，自动解压出内部 .nds 文件至缓存区。
     * DraStic 官方及各修改版无法直接从外部 Intent 载入 .zip 格式的 NDS ROM，必须为解压后的 .nds。
     */
    private fun tryExtractNdsFromZip(context: Context, zipFile: File): File? {
        if (!zipFile.exists() || !zipFile.name.endsWith(".zip", ignoreCase = true)) return null

        val parentDir = zipFile.parentFile
        val targetNds = if (parentDir != null && parentDir.canWrite()) {
            File(parentDir, "${zipFile.nameWithoutExtension}.nds")
        } else {
            val extDir = context.getExternalFilesDir("nds_unzipped")
                ?: File(context.externalCacheDir ?: context.cacheDir, "nds_unzipped").apply { mkdirs() }
            File(extDir, "${zipFile.nameWithoutExtension}.nds")
        }

        if (targetNds.exists() && targetNds.length() > 0 && targetNds.lastModified() >= zipFile.lastModified()) {
            targetNds.setReadable(true, false)
            return targetNds
        }
        return try {
            java.util.zip.ZipFile(zipFile).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull {
                    !it.isDirectory && it.name.endsWith(".nds", ignoreCase = true)
                } ?: return null
                zip.getInputStream(entry).use { input ->
                    targetNds.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            if (targetNds.exists() && targetNds.length() > 0) {
                targetNds.setReadable(true, false)
                targetNds
            } else null
        } catch (e: Exception) {
            Log.w("EmulatorLauncher", "Failed to extract .nds from zip: ${zipFile.absolutePath}", e)
            null
        }
    }

    private fun resolveActivityForClass(context: Context, packageName: String, simpleClassName: String): String? {
        return try {
            val pm = context.packageManager
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            }
            pkgInfo.activities?.firstOrNull { it.name.endsWith(simpleClassName, ignoreCase = true) }?.name
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 启动卡槽中的游戏 ROM 或安卓 App
     */
    fun launchSlotItem(
        context: Context,
        item: GridSlotItem,
        default3dsEmulatorPkg: String,
        defaultNdsEmulatorPkg: String,
        platformEmulatorPkg: String = "",
        preferredDisplayId: Int? = null,
        subscreenCompanionEnabled: Boolean = true
    ): Boolean {
        disableDeathOnFileUriExposure()
        if (item.type == PlatformType.ANDROID_APP) {
            return launchAndroidApp(context, item.packageName, item.activityName, preferredDisplayId)
        }
        if (item.type == PlatformType.EMPTY || item.type == PlatformType.FOLDER || item.type == PlatformType.BADGE) {
            return false
        }

        val effectivePlatform = item.getEffectivePlatform()
        val targetEmulatorPkg = when {
            item.preferredEmulatorPkg.isNotBlank() && isPackageInstalled(context.packageManager, item.preferredEmulatorPkg) ->
                item.preferredEmulatorPkg
            platformEmulatorPkg.isNotBlank() && isPackageInstalled(context.packageManager, platformEmulatorPkg) ->
                platformEmulatorPkg
            effectivePlatform == "3DS" && default3dsEmulatorPkg.isNotBlank() && isPackageInstalled(context.packageManager, default3dsEmulatorPkg) ->
                default3dsEmulatorPkg
            effectivePlatform == "NDS" && defaultNdsEmulatorPkg.isNotBlank() && isPackageInstalled(context.packageManager, defaultNdsEmulatorPkg) ->
                defaultNdsEmulatorPkg
            else -> findInstalledEmulatorForPlatform(context, effectivePlatform)
        }
        val actualPkg = targetEmulatorPkg

        try {
            val broadcastIntent = Intent("com.gameconsole.launcher3ds.ACTION_GAME_LAUNCHED").apply {
                putExtra("extra_title", item.title)
                putExtra("extra_platform", effectivePlatform)
                putExtra("extra_rom_path", item.romPath)
                putExtra("extra_boxart_path", item.boxArtPath)
                putExtra("extra_package", actualPkg)
                putExtra("extra_launcher_handles_retro_filter", true)
                putExtra("extra_subscreen_companion_enabled", subscreenCompanionEnabled)
            }
            context.sendBroadcast(broadcastIntent)
        } catch (_: Exception) {}

        if (item.isDemo && (item.romPath.isBlank() || !File(item.romPath).exists())) {
            // 如果是内置展示卡带且尚未关联本地 ROM，若已安装对应模拟器则直接打开模拟器主界面
            if (targetEmulatorPkg.isNotBlank()) {
                Toast.makeText(context, "正在启动模拟器：${item.title}", Toast.LENGTH_SHORT).show()
                return launchAndroidApp(context, targetEmulatorPkg, "", preferredDisplayId)
            } else {
                Toast.makeText(
                    context,
                    "演示卡带「${item.title}」：请在设置中扫描您的本地 ROM 目录或安装对应模拟器",
                    Toast.LENGTH_LONG
                ).show()
                return false
            }
        }

        val romFile = File(item.romPath)
        if (!romFile.exists()) {
            Toast.makeText(context, "未找到 ROM 文件：${item.romPath}", Toast.LENGTH_LONG).show()
            return false
        }

        if (targetEmulatorPkg.isBlank()) {
            val platDef = com.gameconsole.launcher3ds.model.PlatformCatalog.getById(effectivePlatform)
            val platName = platDef?.name ?: effectivePlatform
            val suggested = when (effectivePlatform.uppercase()) {
                "GBA", "GBC", "GB", "FC", "SFC", "MD", "ARCADE" -> "RetroArch 或相关专用模拟器"
                "PSP" -> "PPSSPP"
                "PS2" -> "AetherSX2 或 NetherSX2"
                "PS1" -> "DuckStation 或 RetroArch"
                "NDS" -> "DraStic 或 melonDS"
                "3DS" -> "Citra、Lime3DS 或 Azahar"
                "N64" -> "Mupen64Plus FZ"
                "NGC", "WII" -> "Dolphin"
                "SWITCH" -> "Yuzu 或 Sudachi"
                else -> "RetroArch"
            }
            Toast.makeText(context, "未检测到已安装的 $platName 模拟器，请先安装 $suggested", Toast.LENGTH_LONG).show()
            return false
        }

        return launchRomWithEmulator(context, romFile, effectivePlatform, targetEmulatorPkg, preferredDisplayId)
    }

    fun findFirstInstalledEmulator(context: Context, platform: PlatformType): String {
        val list = getAvailableEmulators(context, platform)
        return list.firstOrNull { it.isInstalled }?.packageName ?: ""
    }

    private fun launchRomWithEmulator(
        context: Context,
        romFile: File,
        effectivePlatform: String,
        emulatorPkg: String,
        preferredDisplayId: Int?
    ): Boolean {
        disableDeathOnFileUriExposure()

        val uri: Uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                romFile
            )
        } catch (_: Exception) {
            Uri.fromFile(romFile)
        }

        val fileUri = Uri.fromFile(romFile)

        // 授予目标模拟器临时读取该 ROM URI 的权限
        try {
            context.grantUriPermission(emulatorPkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        try {
            context.grantUriPermission(emulatorPkg, fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}

        val bundle = createLaunchBundle(preferredDisplayId)

        // 1. RetroArch (GBA / GBC / GB / FC / SFC / MD / Arcade / N64 / PS1 等)
        if (emulatorPkg.startsWith("com.retroarch")) {
            val raIntent = Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(emulatorPkg, "com.retroarch.browser.retroactivity.RetroActivityFuture")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("ROM", romFile.absolutePath)
                putExtra("CONFIGFILE", "/data/data/$emulatorPkg/retroarch.cfg")
                val coreName = when (effectivePlatform.uppercase()) {
                    "GBA" -> "mgba_libretro_android.so"
                    "GBC", "GB" -> "gambatte_libretro_android.so"
                    "FC", "NES" -> "fceumm_libretro_android.so"
                    "SFC", "SNES" -> "snes9x_libretro_android.so"
                    "MD", "GENESIS" -> "genesis_plus_gx_libretro_android.so"
                    "N64" -> "mupen64plus_next_libretro_android.so"
                    "PS1", "PSX" -> "pcsx_rearmed_libretro_android.so"
                    "PCE" -> "mednafen_pce_fast_libretro_android.so"
                    "ARCADE", "NEOGEO" -> "fbneo_libretro_android.so"
                    "WSC" -> "mednafen_wswan_libretro_android.so"
                    "NDS" -> "melonds_libretro_android.so"
                    else -> ""
                }
                if (coreName.isNotBlank()) {
                    putExtra("LIBRETRO", "/data/data/$emulatorPkg/cores/$coreName")
                }
                putExtra("PATH", romFile.absolutePath)
                putExtra("GamePath", romFile.absolutePath)
                putExtra("SelectedGame", romFile.absolutePath)
            }
            try {
                context.startActivity(raIntent, bundle)
                return true
            } catch (e: Exception) {
                try {
                    val fallbackRa = context.packageManager.getLaunchIntentForPackage(emulatorPkg)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("ROM", romFile.absolutePath)
                        putExtra("PATH", romFile.absolutePath)
                    }
                    if (fallbackRa != null) {
                        context.startActivity(fallbackRa, bundle)
                        return true
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. DraStic (激烈 NDS)
        if (emulatorPkg.contains("drastic", ignoreCase = true)) {
            disableDeathOnFileUriExposure()

            val actualRomFile = if (romFile.extension.equals("zip", ignoreCase = true)) {
                tryExtractNdsFromZip(context, romFile) ?: romFile
            } else {
                romFile
            }
            actualRomFile.setReadable(true, false)

            val actualFileUri = Uri.fromFile(actualRomFile)
            val actualContentUri: Uri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", actualRomFile)
            } catch (_: Exception) {
                actualFileUri
            }

            try {
                context.grantUriPermission(emulatorPkg, actualContentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.grantUriPermission(emulatorPkg, actualFileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}

            val targetActivityName = resolveActivityForClass(context, emulatorPkg, "DraSticActivity")
                ?: if (emulatorPkg == "com.dsemu.drastic") "com.dsemu.drastic.DraSticActivity" else "$emulatorPkg.DraSticActivity"

            // 方式 1: 显式组件 + 纯净标准 ACTION_VIEW + file:// URI (Pegasus & Daijisho 黄金标准)
            // 核心注意：绝不可携带 "GAMEPATH"、"shortcut" 等额外 extra！
            // DraStic 识别到此类 extra 时会误将其当作桌面内部快捷方式去查询数据库，由于未查到记录会弹出“无法打开快捷方式”Toast。
            // 纯净标准的 ACTION_VIEW + data URI 即可直接启动 ROM 且杜绝任何报错弹窗！
            try {
                val drasticIntent = Intent(Intent.ACTION_VIEW).apply {
                    component = ComponentName(emulatorPkg, targetActivityName)
                    data = actualFileUri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(drasticIntent, bundle)
                return true
            } catch (e: Exception) {
                Log.w("EmulatorLauncher", "DraStic launch 1 (ACTION_VIEW + fileUri) failed", e)
            }

            // 方式 2: 显式组件 + 纯净 ACTION_VIEW + raw path URI (部分机型 am start 方式兼容)
            try {
                val pathUri = Uri.parse(actualRomFile.absolutePath)
                val drasticIntentPath = Intent(Intent.ACTION_VIEW).apply {
                    component = ComponentName(emulatorPkg, targetActivityName)
                    data = pathUri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(drasticIntentPath, bundle)
                return true
            } catch (e: Exception) {
                Log.w("EmulatorLauncher", "DraStic launch 2 (ACTION_VIEW + pathUri) failed", e)
            }

            // 方式 3: 隐式 ACTION_VIEW + file:// URI (系统 Intent Filter 匹配)
            try {
                val drasticIntentImplicit = Intent(Intent.ACTION_VIEW).apply {
                    setPackage(emulatorPkg)
                    data = actualFileUri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                if (drasticIntentImplicit.resolveActivity(context.packageManager) != null) {
                    context.startActivity(drasticIntentImplicit, bundle)
                    return true
                }
            } catch (e: Exception) {
                Log.w("EmulatorLauncher", "DraStic launch 3 (implicit VIEW) failed", e)
            }

            // 方式 4: 显式组件 + content:// URI (Android 11+ SAF 适配版 DraStic)
            try {
                val drasticIntentContent = Intent(Intent.ACTION_VIEW).apply {
                    component = ComponentName(emulatorPkg, targetActivityName)
                    data = actualContentUri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(drasticIntentContent, bundle)
                return true
            } catch (e: Exception) {
                Log.w("EmulatorLauncher", "DraStic launch 4 (contentUri) failed", e)
            }
        }

        // 3. PSP (PPSSPP / PPSSPP Gold)
        if (emulatorPkg.contains("ppsspp")) {
            try {
                val ppssppIntent = Intent(Intent.ACTION_VIEW).apply {
                    component = ComponentName(emulatorPkg, "org.ppsspp.ppsspp.PpssppActivity")
                    setDataAndType(fileUri, "application/octet-stream")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra("org.ppsspp.ppsspp.Shortcuts", romFile.absolutePath)
                    putExtra("shortcut", romFile.absolutePath)
                    putExtra("GamePath", romFile.absolutePath)
                    putExtra("game_path", romFile.absolutePath)
                    putExtra("PATH", romFile.absolutePath)
                }
                context.startActivity(ppssppIntent, bundle)
                return true
            } catch (_: Exception) {}

            try {
                val ppssppIntent2 = Intent(Intent.ACTION_VIEW, fileUri).apply {
                    setPackage(emulatorPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra("org.ppsspp.ppsspp.Shortcuts", romFile.absolutePath)
                }
                context.startActivity(ppssppIntent2, bundle)
                return true
            } catch (_: Exception) {}
        }

        // 4. PS2 (AetherSX2 / NetherSX2)
        if (emulatorPkg.contains("aethersx2") || emulatorPkg.contains("nethersx2")) {
            try {
                val ps2Intent = Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(emulatorPkg, "$emulatorPkg.EmulationActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("bootPath", romFile.absolutePath)
                    putExtra("GamePath", romFile.absolutePath)
                    putExtra("PATH", romFile.absolutePath)
                    data = fileUri
                }
                context.startActivity(ps2Intent, bundle)
                return true
            } catch (_: Exception) {}

            try {
                val ps2Intent2 = Intent(Intent.ACTION_VIEW).apply {
                    setPackage(emulatorPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("bootPath", romFile.absolutePath)
                    data = fileUri
                }
                context.startActivity(ps2Intent2, bundle)
                return true
            } catch (_: Exception) {}
        }

        // 5. PS1 (DuckStation)
        if (emulatorPkg.contains("duckstation")) {
            try {
                val ps1Intent = Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(emulatorPkg, "com.github.stenzek.duckstation.EmulationActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("bootPath", romFile.absolutePath)
                    data = fileUri
                }
                context.startActivity(ps1Intent, bundle)
                return true
            } catch (_: Exception) {}
        }

        // 6. NGC / Wii (Dolphin)
        if (emulatorPkg.contains("dolphin")) {
            try {
                val dolphinIntent = Intent(Intent.ACTION_VIEW).apply {
                    component = ComponentName(emulatorPkg, "org.dolphinemu.dolphinemu.ui.main.MainActivity")
                    setDataAndType(fileUri, "application/octet-stream")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("AutoStartFile", romFile.absolutePath)
                    putExtra("AutoStart", true)
                }
                context.startActivity(dolphinIntent, bundle)
                return true
            } catch (_: Exception) {}
        }

        // 7. N64 (Mupen64Plus FZ)
        if (emulatorPkg.contains("mupen64plus")) {
            try {
                val n64Intent = Intent(Intent.ACTION_VIEW).apply {
                    setPackage(emulatorPkg)
                    setDataAndType(fileUri, "application/octet-stream")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("RomPath", romFile.absolutePath)
                }
                context.startActivity(n64Intent, bundle)
                return true
            } catch (_: Exception) {}
        }

        // 8. melonDS (NDS)
        if (emulatorPkg.contains("melonds")) {
            val actualRomFile = if (romFile.extension.equals("zip", ignoreCase = true)) {
                tryExtractNdsFromZip(context, romFile) ?: romFile
            } else {
                romFile
            }
            val actualFileUri = Uri.fromFile(actualRomFile)
            val actualContentUri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", actualRomFile)
            } catch (_: Exception) {
                actualFileUri
            }
            val targetActivity = resolveActivityForClass(context, emulatorPkg, "EmulatorActivity")
                ?: "me.magnum.melonds.ui.emulator.EmulatorActivity"
            try {
                val melonIntent = Intent(Intent.ACTION_VIEW).apply {
                    component = ComponentName(emulatorPkg, targetActivity)
                    data = actualContentUri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra("uri", actualContentUri.toString())
                    putExtra("file_uri", actualFileUri.toString())
                    putExtra("PATH", actualRomFile.absolutePath)
                    putExtra("GamePath", actualRomFile.absolutePath)
                    putExtra("GAMEPATH", actualRomFile.absolutePath)
                }
                context.startActivity(melonIntent, bundle)
                return true
            } catch (_: Exception) {}

            try {
                val melonIntentFile = Intent(Intent.ACTION_VIEW).apply {
                    component = ComponentName(emulatorPkg, targetActivity)
                    data = actualFileUri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra("uri", actualFileUri.toString())
                    putExtra("PATH", actualRomFile.absolutePath)
                    putExtra("GAMEPATH", actualRomFile.absolutePath)
                }
                context.startActivity(melonIntentFile, bundle)
                return true
            } catch (_: Exception) {}
        }

        // 9. My Boy! (GBA)
        if (emulatorPkg.contains("fastemulator.gba")) {
            try {
                val myboyIntent = Intent(Intent.ACTION_VIEW).apply {
                    component = ComponentName(emulatorPkg, "com.fastemulator.gba.EmulatorActivity")
                    setDataAndType(fileUri, "application/octet-stream")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("game", romFile.absolutePath)
                }
                context.startActivity(myboyIntent, bundle)
                return true
            } catch (_: Exception) {}
        }

        // 10. Citra / Lime3DS / Azahar / Mandarine (3DS)
        if (emulatorPkg.contains("citra") || emulatorPkg.contains("lime") || emulatorPkg.contains("azahar") || emulatorPkg.contains("mandarine")) {
            val knownCitraActivities = listOf(
                "org.citra.emu.ui.EmulationActivity",
                "org.citra.citra_emu.activities.EmulationActivity",
                "org.citra.emu.ui.MainActivity"
            )
            for (act in knownCitraActivities) {
                try {
                    val citraIntent = Intent(Intent.ACTION_VIEW).apply {
                        component = ComponentName(emulatorPkg, act)
                        data = uri
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra("SelectedGame", romFile.absolutePath)
                        putExtra("GamePath", romFile.absolutePath)
                        putExtra("game_path", romFile.absolutePath)
                    }
                    context.startActivity(citraIntent, bundle)
                    return true
                } catch (_: Exception) {}
            }
        }

        // 11. 尝试标准 ACTION_VIEW + 补全各模拟器全套 Extra 参数
        try {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setPackage(emulatorPkg)
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("SelectedGame", romFile.absolutePath)
                putExtra("GamePath", romFile.absolutePath)
                putExtra("game_path", romFile.absolutePath)
                putExtra("GAME_PATH", romFile.absolutePath)
                putExtra("bootPath", romFile.absolutePath)
                putExtra("org.ppsspp.ppsspp.Shortcuts", romFile.absolutePath)
                putExtra("shortcut", romFile.absolutePath)
                putExtra("PATH", romFile.absolutePath)
                putExtra("uri", uri.toString())
                putExtra("ROM_PATH", romFile.absolutePath)
                putExtra("ROM", romFile.absolutePath)
            }
            if (viewIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(viewIntent, bundle)
                return true
            }
        } catch (_: Exception) {}

        // 12. 尝试不带 mimeType 的 ACTION_VIEW
        try {
            val viewNoMimeIntent = Intent(Intent.ACTION_VIEW, fileUri).apply {
                setPackage(emulatorPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("SelectedGame", romFile.absolutePath)
                putExtra("GamePath", romFile.absolutePath)
                putExtra("game_path", romFile.absolutePath)
                putExtra("GAME_PATH", romFile.absolutePath)
                putExtra("bootPath", romFile.absolutePath)
                putExtra("PATH", romFile.absolutePath)
                putExtra("ROM", romFile.absolutePath)
            }
            if (viewNoMimeIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(viewNoMimeIntent, bundle)
                return true
            }
        } catch (_: Exception) {}

        // 13. 兜底：直接打开对应模拟器主程序并携带路径参数
        return launchAndroidApp(context, emulatorPkg, "", preferredDisplayId, romFile.absolutePath, uri)
    }

    fun launchAndroidApp(
        context: Context,
        packageName: String,
        activityName: String = "",
        preferredDisplayId: Int? = null,
        extraRomPath: String? = null,
        extraUri: Uri? = null
    ): Boolean {
        if (packageName.isBlank()) return false
        return try {
            val pm = context.packageManager
            val intent = if (activityName.isNotBlank()) {
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(packageName, activityName)
                }
            } else {
                pm.getLaunchIntentForPackage(packageName)
            } ?: return false

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!extraRomPath.isNullOrBlank()) {
                intent.putExtra("GAMEPATH", extraRomPath)
                intent.putExtra("SelectedGame", extraRomPath)
                intent.putExtra("GamePath", extraRomPath)
                intent.putExtra("game_path", extraRomPath)
                intent.putExtra("GAME_PATH", extraRomPath)
                intent.putExtra("bootPath", extraRomPath)
                intent.putExtra("org.ppsspp.ppsspp.Shortcuts", extraRomPath)
                intent.putExtra("PATH", extraRomPath)
                intent.putExtra("ROM", extraRomPath)
            }
            if (extraUri != null) {
                intent.data = extraUri
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val bundle = createLaunchBundle(preferredDisplayId)
            context.startActivity(intent, bundle)
            true
        } catch (e: Exception) {
            Toast.makeText(context, "启动应用失败: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun createLaunchBundle(preferredDisplayId: Int?): android.os.Bundle? {
        if (preferredDisplayId == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val opts = ActivityOptions.makeBasic()
            opts.launchDisplayId = preferredDisplayId
            opts.toBundle()
        } catch (_: Exception) {
            null
        }
    }
}
