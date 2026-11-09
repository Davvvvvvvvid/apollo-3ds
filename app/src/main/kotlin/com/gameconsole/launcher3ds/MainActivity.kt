package com.gameconsole.launcher3ds

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.gameconsole.launcher3ds.audio.SoundEffectEngine
import com.gameconsole.launcher3ds.emulator.EmulatorLauncher
import com.gameconsole.launcher3ds.model.BadgeCatalog
import com.gameconsole.launcher3ds.model.BadgeTemplate
import com.gameconsole.launcher3ds.model.CustomCategoryItem
import com.gameconsole.launcher3ds.model.GameCategory
import com.gameconsole.launcher3ds.model.GameCategoryClassifier
import com.gameconsole.launcher3ds.model.GridSlotItem
import com.gameconsole.launcher3ds.model.PlatformType
import com.gameconsole.launcher3ds.model.SystemAppletType
import com.gameconsole.launcher3ds.model.GameNoteData
import com.gameconsole.launcher3ds.model.ThemeCatalog
import com.gameconsole.launcher3ds.model.UpperDisplayMode
import com.gameconsole.launcher3ds.model.ScanStorageScope
import com.gameconsole.launcher3ds.storage.LauncherRepository
import com.gameconsole.launcher3ds.telemetry.StartupProfiler
import com.gameconsole.launcher3ds.ui.GameNotesCanvasView
import com.gameconsole.launcher3ds.ui.LowerScreenView
import com.gameconsole.launcher3ds.ui.Secondary3dsPresentation
import com.gameconsole.launcher3ds.ui.UpperScreenView
import java.io.File
import java.net.URLEncoder
import java.util.UUID

class MainActivity : AppCompatActivity(), LowerScreenView.Listener {

    private lateinit var repo: LauncherRepository
    private lateinit var displayManager: DisplayManager

    private lateinit var mainRootContainer: FrameLayout
    private lateinit var upperScreenView: UpperScreenView
    private lateinit var lowerScreenView: LowerScreenView

    private var secondaryPresentation: Secondary3dsPresentation? = null
    private var rootSlots: MutableList<GridSlotItem> = mutableListOf()
    private var folderItems: MutableList<GridSlotItem> = mutableListOf()
    private var currentOpenedFolder: GridSlotItem? = null
    private var currentCategoryIndex: Int = 0

    // 记录模拟扳机键 L2 / R2 的按下状态，防止连续触发
    private var l2TriggerPressed: Boolean = false
    private var r2TriggerPressed: Boolean = false
    private var hasAutoScannedOnResume: Boolean = false
    // 视频原声播放状态：true=停止背景音乐并播放上屏视频原声；false=恢复背景音乐且视频静音
    private var isVideoAudioMode: Boolean = false
    private var lastSlotLaunchTimestamp: Long = 0L

    // 自动随机轮播任务句柄
    private var isAutoRandomRunning: Boolean = false
    private val autoRandomHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoRandomRunnable = object : Runnable {
        override fun run() {
            if (!isAutoRandomRunning) return
            performAutoRandomSelect()
            val nextDelay = (repo.autoRandomIntervalSec * 1000L).coerceIn(2000L, 3600000L)
            autoRandomHandler.postDelayed(this, nextDelay)
        }
    }

    private var activeAppletDialog: AlertDialog? = null

    private val noteAddReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val title = intent.getStringExtra("extra_title") ?: "战术便签"
            val textContent = intent.getStringExtra("extra_content") ?: return
            val noteId = UUID.randomUUID().toString()
            val note = GameNoteData(
                gameId = noteId,
                gameTitle = title,
                textMemo = textContent,
                updatedAt = System.currentTimeMillis()
            )
            repo.saveGameNote(note)
            Toast.makeText(context, "📝 已同步保存至 3DS 游戏便签！", Toast.LENGTH_SHORT).show()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            setupDualScreenLayout()
        }

        override fun onDisplayRemoved(displayId: Int) {
            setupDualScreenLayout()
        }

        override fun onDisplayChanged(displayId: Int) {
        }
    }

    private val backgroundExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun triggerBackgroundRomScan(
        customDir: String? = null,
        showToastOnCompletion: Boolean = false,
        removeDemoCards: Boolean = false
    ) {
        if (!hasStoragePermission()) {
            requestStoragePermission()
            return
        }

        if (showToastOnCompletion) {
            val tfPath = repo.getDetectedTfCardPath()
            val hint = if (tfPath.isNotBlank()) "正在全量扫描 TF 卡与游戏库，请稍候..." else "正在全量扫描游戏库，请稍候..."
            Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
        }

        backgroundExecutor.execute {
            val newlyAdded = repo.scanRomDirectories(
                customDir = customDir,
                removeDemoCards = removeDemoCards
            )
            repo.rematchAllMissingCovers()
            repo.getDiscoveredMediaLibraries(forceRefresh = true)
            val freshSlots = repo.loadRootSlots()
            val enabledPlatforms = repo.getEnabledPlatforms()
            val disabledLibs = repo.getDisabledMediaLibs()
            val gamesToPrewarm = freshSlots.filter {
                it.type != PlatformType.EMPTY && !it.isDemo &&
                it.type != PlatformType.ANDROID_APP && it.type != PlatformType.FOLDER && it.type != PlatformType.BADGE &&
                enabledPlatforms.contains(it.getEffectivePlatform()) &&
                !isSlotFromDisabledMediaLib(it, disabledLibs)
            }.take(16)
            for (g in gamesToPrewarm) {
                lowerScreenView.predecodeSlotIconSync(g)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                rootSlots = freshSlots
                invalidatePlatformGameCountsCache()
                if (showToastOnCompletion) {
                    val tfDesc = if (repo.getDetectedTfCardPath().isNotBlank()) " (含TF卡)" else ""
                    val msg = if (newlyAdded > 0) {
                        "扫描完成：新增 $newlyAdded 款游戏$tfDesc (${repo.scanStorageScope.shortLabel})"
                    } else {
                        "扫描完成：游戏库已是最新$tfDesc (${repo.scanStorageScope.shortLabel})"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
                syncViewsWithState()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupProfiler.markOnCreateStart()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveFullscreen()

        repo = LauncherRepository(this)
        StartupProfiler.markRepoInit()

        EmulatorLauncher.disableDeathOnFileUriExposure()

        // 自动发现并绑定已安装的 3DS / NDS 模拟器 (优先 Citra MMJ / 官方正式版 / Lime3DS / DraStic)
        if (repo.default3dsEmulatorPkg.isBlank() || !EmulatorLauncher.isPackageInstalled(packageManager, repo.default3dsEmulatorPkg)) {
            val autoDetected3ds = EmulatorLauncher.findInstalledEmulatorForPlatform(this, "3DS")
            if (autoDetected3ds.isNotBlank()) {
                repo.default3dsEmulatorPkg = autoDetected3ds
                repo.setPlatformEmulator("3DS", autoDetected3ds)
            }
        }
        if (repo.defaultNdsEmulatorPkg.isBlank() || !EmulatorLauncher.isPackageInstalled(packageManager, repo.defaultNdsEmulatorPkg)) {
            val autoDetectedNds = EmulatorLauncher.findInstalledEmulatorForPlatform(this, "NDS")
            if (autoDetectedNds.isNotBlank()) {
                repo.defaultNdsEmulatorPkg = autoDetectedNds
                repo.setPlatformEmulator("NDS", autoDetectedNds)
            }
        }
        StartupProfiler.detected3dsEmulatorPkg = repo.default3dsEmulatorPkg
        StartupProfiler.detected3dsEmulatorLabel = queryAppLabel(repo.default3dsEmulatorPkg).ifBlank { "Citra MMJ" }
        StartupProfiler.detected3dsEmulatorInstalled = EmulatorLauncher.isPackageInstalled(packageManager, repo.default3dsEmulatorPkg)

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        mainRootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        setContentView(mainRootContainer)

        upperScreenView = UpperScreenView(this).apply {
            onVideoAudioToggleRequested = {
                this@MainActivity.onVideoAudioToggleRequested()
            }
        }

        lowerScreenView = LowerScreenView(this).apply {
            listener = this@MainActivity
        }
        StartupProfiler.markViewsInit()

        val theme = ThemeCatalog.getById(repo.themeId)
        SoundEffectEngine.init(this)
        SoundEffectEngine.sfxEnabled = repo.sfxEnabled
        SoundEffectEngine.sfxVolume = if (repo.sfxEnabled) repo.sfxVolume / 100f else 0f
        SoundEffectEngine.bgmEnabled = repo.bgmEnabled
        SoundEffectEngine.updateTheme(theme, repo.customBgmPath)

        currentCategoryIndex = GameCategory.entries.indexOfFirst { it.id == repo.currentCategoryId }.coerceAtLeast(0)

        try {
            rootSlots = repo.loadRootSlots()
            folderItems = repo.loadFolderItems()
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Failed to load slots, falling back to initial", e)
            rootSlots = repo.createInitialSlots()
            folderItems = mutableListOf()
        }
        StartupProfiler.markSlotsLoad()

        try {
            syncViewsWithState()
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Failed to sync views with state, resetting slots to initial", e)
            rootSlots = repo.createInitialSlots()
            syncViewsWithState()
        }
        StartupProfiler.markSyncViews()

        setupDualScreenLayout()
        StartupProfiler.markLayoutSetup()

        // 极速秒开检测：判断是否已有真实扫描出的游戏或是否插有尚未加载的 TF 卡
        val hasRealGames = rootSlots.any {
            !it.isDemo &&
            it.id != "showcase_3ds_mario_3d_land" &&
            it.type != PlatformType.EMPTY &&
            it.type != PlatformType.ANDROID_APP &&
            it.type != PlatformType.FOLDER &&
            it.type != PlatformType.BADGE
        }
        val tfCardPath = repo.getDetectedTfCardPath()
        val hasTfGames = rootSlots.any {
            it.type != PlatformType.EMPTY && !it.isDemo && (
                (it.romPath.isNotBlank() && repo.isTfCard(File(it.romPath))) ||
                (it.boxArtPath.isNotBlank() && repo.isTfCard(File(it.boxArtPath)))
            )
        }
        val needsTfScan = tfCardPath.isNotBlank() && !hasTfGames

        if ((!hasRealGames || needsTfScan) && hasStoragePermission()) {
            hasAutoScannedOnResume = true
            triggerBackgroundRomScan(showToastOnCompletion = needsTfScan)
        } else if (hasRealGames && hasStoragePermission()) {
            backgroundExecutor.execute {
                val rematched = repo.rematchAllMissingCovers()
                if (rematched > 0) {
                    val freshSlots = repo.loadRootSlots()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            rootSlots = freshSlots
                            syncViewsWithState()
                        }
                    }
                }
            }
        } else if (!hasStoragePermission()) {
            requestStoragePermission()
        }

        try {
            val filter = IntentFilter("com.gameconsole.launcher3ds.ACTION_ADD_NOTE")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(noteAddReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(noteAddReceiver, filter)
            }
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveFullscreen()
        displayManager.registerDisplayListener(displayListener, null)
        val hasRealGames = rootSlots.any {
            !it.isDemo &&
            it.id != "showcase_3ds_mario_3d_land" &&
            it.type != PlatformType.EMPTY &&
            it.type != PlatformType.ANDROID_APP &&
            it.type != PlatformType.FOLDER &&
            it.type != PlatformType.BADGE
        }
        val tfCardPath = repo.getDetectedTfCardPath()
        val hasTfGames = rootSlots.any {
            it.type != PlatformType.EMPTY && !it.isDemo && (
                (it.romPath.isNotBlank() && repo.isTfCard(File(it.romPath))) ||
                (it.boxArtPath.isNotBlank() && repo.isTfCard(File(it.boxArtPath)))
            )
        }
        val needsTfScan = tfCardPath.isNotBlank() && !hasTfGames

        if (((!hasRealGames && !hasAutoScannedOnResume) || needsTfScan) && hasStoragePermission()) {
            hasAutoScannedOnResume = true
            triggerBackgroundRomScan(showToastOnCompletion = true)
        }
        setupDualScreenLayout()
        upperScreenView.isVideoAudioEnabled = isVideoAudioMode
        lowerScreenView.isVideoAudioEnabled = isVideoAudioMode
        upperScreenView.onHostResume()
        if (!isVideoAudioMode) {
            SoundEffectEngine.startBgm()
        } else {
            SoundEffectEngine.stopBgm()
        }
        StartupProfiler.markOnResumeEnd()
        try {
            com.gameconsole.launcher3ds.overlay.LauncherRetroFilterManager.hide()
            com.gameconsole.launcher3ds.companion.overlay.LauncherCompanionOverlayManager.hide()
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "Error hiding overlays on resume", e)
        }

        try {
            sendBroadcast(Intent("com.gameconsole.launcher3ds.ACTION_STATE_CHANGED").putExtra("is_foreground", true))
            syncRetroFilterToApollo()
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        stopAutoRandomPlayback()
        displayManager.unregisterDisplayListener(displayListener)
        upperScreenView.onHostPause()
        dismissSecondaryPresentation()
        SoundEffectEngine.stopBgm()
        try {
            sendBroadcast(Intent("com.gameconsole.launcher3ds.ACTION_STATE_CHANGED").putExtra("is_foreground", false))
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        dismissSecondaryPresentation()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoRandomPlayback()
        upperScreenView.onHostPause()
        dismissSecondaryPresentation()
        SoundEffectEngine.stopBgm()
        try {
            backgroundExecutor.shutdown()
        } catch (_: Exception) {}
        try {
            unregisterReceiver(noteAddReceiver)
        } catch (_: Exception) {}
    }

    /**
     * 核心双屏分配逻辑：
     * - 您的双屏掌机上：主屏 (displayId=0) 是上屏，副屏 (displayId!=0) 是下屏。
     * - 因此当检测到物理副屏 (secondaryDisplay != null) 且未强制开启单屏分屏预览时：
     *   - 默认 (!swapScreens)：主屏(上屏) 显示 3DS 上屏舞台 (upperScreenView)，副屏(下屏) 显示 3DS 下屏触控网格 (lowerScreenView)！
     *   - 互换 (swapScreens)：主屏显示 3DS 下屏，副屏显示 3DS 上屏。
     * - 仅当未检测到副屏（或用户手动开启了同屏双显预览）时，才在单屏内上下分屏同显。
     */
    private fun setupDualScreenLayout() {
        val secondaryDisplay = findSecondaryDisplay()

        // 先将两个视图从旧父容器安全解绑
        (upperScreenView.parent as? ViewGroup)?.removeView(upperScreenView)
        (lowerScreenView.parent as? ViewGroup)?.removeView(lowerScreenView)
        mainRootContainer.removeAllViews()

        if (secondaryDisplay != null && !repo.splitPreviewOnSingleScreen) {
            try {
                if (secondaryPresentation?.display?.displayId != secondaryDisplay.displayId ||
                    secondaryPresentation?.isShowing != true
                ) {
                    dismissSecondaryPresentation()
                    secondaryPresentation = Secondary3dsPresentation(
                        outerContext = this,
                        display = secondaryDisplay,
                        keyEventDispatcher = { ev -> handleLauncherKeyEvent(ev) },
                        motionEventDispatcher = { ev -> handleLauncherMotionEvent(ev) }
                    )
                }

                val pres = secondaryPresentation!!
                if (!pres.isShowing) {
                    pres.show()
                }

                if (!repo.swapScreens) {
                    // 默认：主屏 (displayId=0, 上屏) 挂载 3DS 上屏视图；副屏 (下屏) 挂载 3DS 下屏触控网格视图
                    mainRootContainer.addView(
                        upperScreenView,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    pres.attachScreenView(lowerScreenView)
                } else {
                    // 互换后：主屏挂载 3DS 下屏触控网格；副屏挂载 3DS 上屏视图
                    mainRootContainer.addView(
                        lowerScreenView,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    pres.attachScreenView(upperScreenView)
                }
                return
            } catch (e: Exception) {
                e.printStackTrace()
                dismissSecondaryPresentation()
                (upperScreenView.parent as? ViewGroup)?.removeView(upperScreenView)
                (lowerScreenView.parent as? ViewGroup)?.removeView(lowerScreenView)
                mainRootContainer.removeAllViews()
            }
        } else {
            dismissSecondaryPresentation()
        }

        // 兜底：单屏上下分屏同显模式（仅当无副屏或用户手动开启「单屏双显预览」时使用）
        val splitLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#181C20"))
        }

        val topParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.46f)
        val hingeView = create3dsHingeBarView()
        val hingeParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22))
        val bottomParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.54f)

        if (!repo.swapScreens) {
            splitLayout.addView(upperScreenView, topParams)
            splitLayout.addView(hingeView, hingeParams)
            splitLayout.addView(lowerScreenView, bottomParams)
        } else {
            splitLayout.addView(lowerScreenView, bottomParams)
            splitLayout.addView(hingeView, hingeParams)
            splitLayout.addView(upperScreenView, topParams)
        }

        mainRootContainer.addView(
            splitLayout,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    /**
     * 获取当前承载下屏触控网格的 Context（当副屏显示下屏时，让所有弹窗直接弹在副屏上！）
     */
    private fun dialogContext(): Context {
        // AlertDialog 必须绑定 Activity 的 WindowToken，否则在 Presentation.context 下会抛出 BadTokenException 闪退
        return this
    }

    private fun create3dsHingeBarView(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#2A3038"))
            setPadding(dp(12), 0, dp(12), 0)

            val label = TextView(context).apply {
                text = "NINTENDO 3DS DUAL-SCREEN"
                setTextColor(Color.parseColor("#9BA8B8"))
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val swapBtn = TextView(context).apply {
                text = "⇅ 互换上下屏"
                setTextColor(Color.parseColor("#00E5FF"))
                textSize = 10f
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener {
                    toggleSwapScreensQuickly()
                }
            }
            addView(swapBtn)

            val ledDot = TextView(context).apply {
                text = "● 3D / StreetPass"
                setTextColor(Color.parseColor("#34E84E"))
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(ledDot)
        }
    }

    private fun toggleSwapScreensQuickly() {
        repo.swapScreens = !repo.swapScreens
        SoundEffectEngine.playSelectApplet()
        setupDualScreenLayout()
        val modeDesc = if (repo.swapScreens) "已互换上下屏映射 (上屏=触控网格 / 下屏=3D舞台)" else "已恢复标准映射 (上屏=3D舞台 / 下屏=触控网格)"
        Toast.makeText(dialogContext(), modeDesc, Toast.LENGTH_SHORT).show()
    }

    private fun findSecondaryDisplay(): Display? {
        val allDisplays = displayManager.displays
        // 严格匹配真正的物理副屏（displayId 绝对不能是主屏 DEFAULT_DISPLAY = 0）
        allDisplays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }?.let { return it }
        val presDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        return presDisplays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    private fun dismissSecondaryPresentation() {
        try {
            val pres = secondaryPresentation
            if (pres != null) {
                (lowerScreenView.parent as? ViewGroup)?.removeView(lowerScreenView)
                (upperScreenView.parent as? ViewGroup)?.removeView(upperScreenView)
                pres.dismiss()
            }
        } catch (_: Exception) {
        }
        secondaryPresentation = null
    }

    private fun isPlatformAllowed(slot: GridSlotItem, enabledPlatforms: Set<String>): Boolean {
        val eff = slot.getEffectivePlatform()
        return enabledPlatforms.any { it.equals(eff, ignoreCase = true) }
    }

    private fun isSlotFromDisabledMediaLib(slot: GridSlotItem, disabledLibs: Set<String>): Boolean {
        if (disabledLibs.isEmpty()) return false
        for (d in disabledLibs) {
            if (repo.matchesLibraryPath(slot, d)) return true
        }
        return false
    }

    private var cachedPlatformGameCounts: Map<String, Int>? = null
    private val categorySlotsCache = mutableMapOf<String, MutableList<GridSlotItem>>()

    private fun invalidateCategorySlotsCache() {
        categorySlotsCache.clear()
    }

    private fun getPlatformGameCounts(): Map<String, Int> {
        cachedPlatformGameCounts?.let { return it }
        val disabledLibs = repo.getDisabledMediaLibs()
        val hideMissing = repo.hideGamesWithoutCover
        val counts = rootSlots.filter {
            !it.isDemo &&
                it.type != PlatformType.EMPTY &&
                it.type != PlatformType.FOLDER &&
                it.type != PlatformType.BADGE &&
                it.type != PlatformType.ANDROID_APP &&
                !isSlotFromDisabledMediaLib(it, disabledLibs) &&
                (!hideMissing || repo.hasValidCover(it))
        }.groupingBy { it.getEffectivePlatform() }.eachCount()
        cachedPlatformGameCounts = counts
        return counts
    }

    private fun invalidatePlatformGameCountsCache() {
        cachedPlatformGameCounts = null
        categorySlotsCache.clear()
    }

    private fun countGamesForCategory(catId: String): Int {
        val enabledPlatforms = repo.getEnabledPlatforms()
        val disabledLibs = repo.getDisabledMediaLibs()
        val hideMissing = repo.hideGamesWithoutCover
        val allNonEmpty = rootSlots.filter {
            it.type != PlatformType.EMPTY &&
                it.type != PlatformType.FOLDER &&
                it.type != PlatformType.BADGE &&
                it.type != PlatformType.ANDROID_APP &&
                !it.isDemo &&
                isPlatformAllowed(it, enabledPlatforms) &&
                !isSlotFromDisabledMediaLib(it, disabledLibs) &&
                (!hideMissing || repo.hasValidCover(it))
        }
        return if (catId == "all") {
            allNonEmpty.size
        } else {
            allNonEmpty.count { repo.isGameInCategory(catId, it) }
        }
    }

    private fun countGamesForCategory(cat: GameCategory): Int = countGamesForCategory(cat.id)

    private fun applyActiveCategoryState(animateZoom: Boolean = true) {
        val categories = repo.getCustomCategories()
        if (categories.isEmpty()) return
        currentCategoryIndex = currentCategoryIndex.coerceIn(0, (categories.size - 1).coerceAtLeast(0))
        val activeCategory = categories[currentCategoryIndex]
        repo.currentCategoryId = activeCategory.id

        val activeList = getCurrentDisplayedSlots()
        val gamesCount = activeList.count { it.type != PlatformType.EMPTY && !it.isDemo }

        lowerScreenView.updateCategoryState(categories, currentCategoryIndex, gamesCount, activeList)

        val selectedSlot = activeList.firstOrNull { it.type != PlatformType.EMPTY } ?: activeList.getOrNull(0)
        upperScreenView.updateSelectedSlot(
            newSlot = selectedSlot,
            keepPreviewPlaying = isVideoAudioMode,
            keepAudioEnabled = isVideoAudioMode
        )
        if (selectedSlot?.type == PlatformType.FOLDER) {
            upperScreenView.folderChildrenCount = folderItems.count { it.parentFolderId == selectedSlot.id }
        }
        if (animateZoom) {
            SoundEffectEngine.playZoomChange(true)
        }
    }

    private fun syncViewsWithState() {
        val theme = ThemeCatalog.getById(repo.themeId)
        val filter = com.gameconsole.launcher3ds.model.VideoFilterType.fromId(repo.videoFilterId)
        val disabledLibs = repo.getDisabledMediaLibs()
        upperScreenView.currentTheme = theme
        upperScreenView.videoFilter = filter
        upperScreenView.upperDisplayMode = repo.upperDisplayMode
        upperScreenView.showUpperBoxart = repo.showUpperBoxart
        upperScreenView.showUpperLogo = repo.showUpperLogo
        lowerScreenView.currentTheme = theme
        lowerScreenView.currentVideoFilter = filter
        lowerScreenView.gameDescriptionResolver = { slot -> repo.resolveGameDescription(slot) }
        lowerScreenView.pegasusInspectionProvider = { platformId -> repo.inspectPegasusMetadataForPlatform(platformId) }
        lowerScreenView.hasPegasusMetadataProvider = { platformId -> repo.hasPegasusMetadata(platformId) }
        lowerScreenView.upperDisplayMode = repo.upperDisplayMode
        lowerScreenView.showUpperBoxart = repo.showUpperBoxart
        lowerScreenView.showUpperLogo = repo.showUpperLogo
        lowerScreenView.subscreenCompanionEnabled = repo.subscreenCompanionEnabled
        lowerScreenView.subscreenRetroFilterEnabled = repo.subscreenRetroFilterEnabled
        lowerScreenView.subscreenRetroFilterType = repo.subscreenRetroFilterType
        lowerScreenView.subscreenRetroFilterOpacity = repo.subscreenRetroFilterOpacity
        lowerScreenView.mainScreenRetroFilterEnabled = repo.mainScreenRetroFilterEnabled
        lowerScreenView.mainScreenRetroFilterAuto2D = repo.mainScreenRetroFilterAuto2D
        lowerScreenView.mainScreenRetroFilterType = repo.mainScreenRetroFilterType
        lowerScreenView.mainScreenRetroFilterOpacity = repo.mainScreenRetroFilterOpacity
        lowerScreenView.isBgmEnabled = repo.bgmEnabled
        lowerScreenView.isSfxEnabled = (repo.sfxVolume > 0 && repo.sfxEnabled)
        lowerScreenView.sfxVolumePercent = repo.sfxVolume
        SoundEffectEngine.sfxVolume = if (repo.sfxEnabled) repo.sfxVolume / 100f else 0f
        lowerScreenView.isSwapScreensEnabled = repo.swapScreens
        lowerScreenView.isAutoRandomPlaying = isAutoRandomRunning
        lowerScreenView.autoRandomIntervalSec = repo.autoRandomIntervalSec
        lowerScreenView.gridRows = repo.gridRows
        lowerScreenView.enabledPlatforms = repo.getEnabledPlatforms()
        lowerScreenView.platformGameCounts = getPlatformGameCounts()
        lowerScreenView.lastMountedMediaDir = repo.lastMountedMediaDir
        lowerScreenView.hideGamesWithoutCover = repo.hideGamesWithoutCover
        lowerScreenView.default3dsEmulatorPkg = repo.default3dsEmulatorPkg
        lowerScreenView.defaultNdsEmulatorPkg = repo.defaultNdsEmulatorPkg
        lowerScreenView.default3dsEmulatorLabel = queryAppLabel(repo.default3dsEmulatorPkg).ifBlank { "Citra MMJ" }
        lowerScreenView.defaultNdsEmulatorLabel = queryAppLabel(repo.defaultNdsEmulatorPkg).ifBlank { "DraStic" }
        lowerScreenView.isSplitPreviewEnabled = repo.splitPreviewOnSingleScreen
        lowerScreenView.scanStorageScope = repo.scanStorageScope
        lowerScreenView.detectedTfCardPath = repo.getDetectedTfCardPath()
        lowerScreenView.candidateRomDirs = getCandidateRomDirs()
        lowerScreenView.mediaLibraries = repo.getDiscoveredMediaLibraries()

        val categories = repo.getCustomCategories()
        currentCategoryIndex = currentCategoryIndex.coerceIn(0, (categories.size - 1).coerceAtLeast(0))
        val activeCategory = categories.getOrElse(currentCategoryIndex) { categories.first() }

        val activeList = getCurrentDisplayedSlots()
        val gamesCount = activeList.count { it.type != PlatformType.EMPTY && !it.isDemo }

        lowerScreenView.categories = categories
        lowerScreenView.currentCategoryIndex = currentCategoryIndex
        lowerScreenView.currentCategoryGameCount = gamesCount
        lowerScreenView.slots = activeList
        lowerScreenView.activeFolderTitle = currentOpenedFolder?.title

        val selIdx = lowerScreenView.selectedIndex.coerceIn(0, (activeList.size - 1).coerceAtLeast(0))
        lowerScreenView.selectedIndex = selIdx
        val selectedSlot = activeList.getOrNull(selIdx)

        upperScreenView.updateSelectedSlot(
            newSlot = selectedSlot,
            keepPreviewPlaying = isVideoAudioMode,
            keepAudioEnabled = isVideoAudioMode
        )
        upperScreenView.friendProfile = repo.loadFriendProfile()
        upperScreenView.playCoins = repo.playCoins
        upperScreenView.stepsCount = repo.stepsCount
        if (selectedSlot?.type == PlatformType.FOLDER) {
            upperScreenView.folderChildrenCount = folderItems.count { it.parentFolderId == selectedSlot.id }
        }
        val notifs = repo.loadNotifications()
        lowerScreenView.unreadNotificationsCount = notifs.count { !it.isRead }
    }

    private fun getCurrentDisplayedSlots(): MutableList<GridSlotItem> {
        val enabledPlatforms = repo.getEnabledPlatforms()
        val disabledLibs = repo.getDisabledMediaLibs()

        val hideMissing = repo.hideGamesWithoutCover
        val folder = currentOpenedFolder
        if (folder != null) {
            val children = folderItems.filter {
                it.parentFolderId == folder.id &&
                    isPlatformAllowed(it, enabledPlatforms) &&
                    !isSlotFromDisabledMediaLib(it, disabledLibs) &&
                    (!hideMissing || repo.hasValidCover(it))
            }.toMutableList()
            val folderGrid = MutableList(LauncherRepository.TOTAL_SLOTS) { idx ->
                GridSlotItem(id = "folder_empty_${folder.id}_$idx", slotIndex = idx, type = PlatformType.EMPTY)
            }
            for ((i, item) in children.withIndex()) {
                val targetIdx = item.slotIndex.coerceIn(0, LauncherRepository.TOTAL_SLOTS - 1)
                if (folderGrid[targetIdx].type == PlatformType.EMPTY) {
                    folderGrid[targetIdx] = item.copy(slotIndex = targetIdx)
                } else {
                    val firstEmpty = folderGrid.indexOfFirst { it.type == PlatformType.EMPTY }.coerceAtLeast(i)
                    if (firstEmpty in 0 until LauncherRepository.TOTAL_SLOTS) {
                        folderGrid[firstEmpty] = item.copy(slotIndex = firstEmpty)
                    }
                }
            }
            return folderGrid
        }

        val categories = repo.getCustomCategories()
        val safeCatIdx = currentCategoryIndex.coerceIn(0, (categories.size - 1).coerceAtLeast(0))
        val activeCategory = categories.getOrElse(safeCatIdx) { categories.first() }

        val cachedGrid = categorySlotsCache[activeCategory.id]
        if (cachedGrid != null) {
            return cachedGrid
        }

        if (activeCategory.id == "all") {
            val gamesOnly = rootSlots.filter {
                it.type != PlatformType.EMPTY &&
                    it.type != PlatformType.FOLDER &&
                    it.type != PlatformType.BADGE &&
                    it.type != PlatformType.ANDROID_APP &&
                    !it.isDemo &&
                    isPlatformAllowed(it, enabledPlatforms) &&
                    !isSlotFromDisabledMediaLib(it, disabledLibs) &&
                    (!hideMissing || repo.hasValidCover(it))
            }
            val targetSize = maxOf(LauncherRepository.TOTAL_SLOTS, gamesOnly.size + 12)
            val allGrid = MutableList(targetSize) { idx ->
                GridSlotItem(id = "cat_empty_all_$idx", slotIndex = idx, type = PlatformType.EMPTY)
            }
            for ((idx, item) in gamesOnly.withIndex()) {
                allGrid[idx] = item.copy(slotIndex = idx)
            }
            categorySlotsCache[activeCategory.id] = allGrid
            return allGrid
        }

        // 按当前选中的游戏类型/系列过滤，支持手动收录与排除规则
        val matched = rootSlots.filter {
            it.type != PlatformType.EMPTY &&
                it.type != PlatformType.FOLDER &&
                it.type != PlatformType.BADGE &&
                it.type != PlatformType.ANDROID_APP &&
                !it.isDemo &&
                isPlatformAllowed(it, enabledPlatforms) &&
                repo.isGameInCategory(activeCategory.id, it) &&
                !isSlotFromDisabledMediaLib(it, disabledLibs) &&
                (!hideMissing || repo.hasValidCover(it))
        }
        val targetSize = maxOf(LauncherRepository.TOTAL_SLOTS, matched.size + 12)
        val categoryGrid = MutableList(targetSize) { idx ->
            GridSlotItem(id = "cat_empty_${activeCategory.id}_$idx", slotIndex = idx, type = PlatformType.EMPTY)
        }
        for ((idx, item) in matched.withIndex()) {
            categoryGrid[idx] = item.copy(slotIndex = idx)
        }
        categorySlotsCache[activeCategory.id] = categoryGrid
        return categoryGrid
    }

    // ==================== LowerScreenView.Listener 回调 ====================

    override fun onSlotSelected(slot: GridSlotItem, index: Int) {
        SoundEffectEngine.playCursorMove()
        if (upperScreenView.isVideoFullscreen) {
            upperScreenView.exitVideoFullscreen()
        }
        upperScreenView.activeApplet = SystemAppletType.NONE
        val isNewGame = upperScreenView.selectedSlot?.id != slot.id
        if (isNewGame) {
            upperScreenView.updateSelectedSlot(
                newSlot = slot,
                keepPreviewPlaying = isVideoAudioMode,
                keepAudioEnabled = isVideoAudioMode
            )
            lowerScreenView.isVideoAudioEnabled = isVideoAudioMode
            if (isVideoAudioMode) {
                SoundEffectEngine.stopBgm()
            }
        } else {
            upperScreenView.selectedSlot = slot
        }
        if (slot.type == PlatformType.FOLDER) {
            upperScreenView.folderChildrenCount = folderItems.count { it.parentFolderId == slot.id }
        }
    }

    override fun onSlotOpenRequested(slot: GridSlotItem, index: Int) {
        when (slot.type) {
            PlatformType.EMPTY -> {
                SoundEffectEngine.playSelectApplet()
                showScanOrAddDialog(targetSlotIndex = index)
            }
            PlatformType.FOLDER -> {
                SoundEffectEngine.playFolderOpen()
                currentOpenedFolder = slot
                lowerScreenView.selectedIndex = 0
                syncViewsWithState()
            }
            PlatformType.BADGE -> {
                SoundEffectEngine.playSelectApplet()
                showBadgeBoxDialog(targetSlotIndex = index)
            }
            PlatformType.N3DS, PlatformType.NDS, PlatformType.ANDROID_APP -> {
                val now = System.currentTimeMillis()
                if (now - lastSlotLaunchTimestamp < 1500L) {
                    return
                }
                lastSlotLaunchTimestamp = now

                SoundEffectEngine.playLaunchGame()
                slot.playCount += 1
                slot.playTimeMinutes += 15
                slot.lastPlayedTimestamp = System.currentTimeMillis()
                repo.playCoins = (repo.playCoins + 2).coerceAtMost(300)
                repo.stepsCount += 50
                persistSlotUpdate(slot)
                syncViewsWithState()

                upperScreenView.triggerLaunchSplash(slot.title) {
                    val platformEmu = repo.getPlatformEmulator(slot.getEffectivePlatform())
                    val effPlat = slot.getEffectivePlatform()
                    val isDualEmuApp = slot.type == PlatformType.ANDROID_APP && (
                        slot.packageName.contains("citra", ignoreCase = true) ||
                        slot.packageName.contains("lime", ignoreCase = true) ||
                        slot.packageName.contains("azahar", ignoreCase = true) ||
                        slot.packageName.contains("drastic", ignoreCase = true) ||
                        slot.packageName.contains("melonds", ignoreCase = true) ||
                        slot.packageName.contains("mandarine", ignoreCase = true)
                    )
                    val isDualGame = effPlat.equals("3DS", ignoreCase = true) ||
                        effPlat.equals("NDS", ignoreCase = true) ||
                        slot.type == PlatformType.N3DS ||
                        slot.type == PlatformType.NDS ||
                        isDualEmuApp

                    // 1. 主屏复古滤镜智能判断与挂载 (解决《冒险岛》、2D 贴图游戏等高倍拉大发糊问题)
                    val needMainFilter = slot.shouldEnableMainRetroFilter(
                        globalEnabled = repo.mainScreenRetroFilterEnabled,
                        auto2d = repo.mainScreenRetroFilterAuto2D
                    )
                    if (needMainFilter) {
                        com.gameconsole.launcher3ds.overlay.LauncherRetroFilterManager.showMain(
                            context = this,
                            typeStr = repo.mainScreenRetroFilterType,
                            opacity = repo.mainScreenRetroFilterOpacity
                        )
                    } else {
                        com.gameconsole.launcher3ds.overlay.LauncherRetroFilterManager.hideMain()
                    }

                    // 2. 副屏分流
                    if (isDualGame) {
                        if (repo.subscreenRetroFilterEnabled) {
                            com.gameconsole.launcher3ds.overlay.LauncherRetroFilterManager.showSecondary(
                                context = this,
                                typeStr = repo.subscreenRetroFilterType,
                                opacity = repo.subscreenRetroFilterOpacity
                            )
                        }
                    } else {
                        if (repo.subscreenCompanionEnabled) {
                            com.gameconsole.launcher3ds.companion.overlay.LauncherCompanionOverlayManager.show(
                                context = this,
                                slot = slot
                            )
                        } else {
                            com.gameconsole.launcher3ds.companion.overlay.LauncherCompanionOverlayManager.hide()
                        }
                    }

                    dismissSecondaryPresentation()

                    EmulatorLauncher.launchSlotItem(
                        context = this,
                        item = slot,
                        default3dsEmulatorPkg = repo.default3dsEmulatorPkg,
                        defaultNdsEmulatorPkg = repo.defaultNdsEmulatorPkg,
                        platformEmulatorPkg = platformEmu,
                        subscreenCompanionEnabled = repo.subscreenCompanionEnabled
                    )
                }
            }
        }
    }

    override fun onSlotManualOrConfigRequested(slot: GridSlotItem, index: Int) {
        SoundEffectEngine.playSelectApplet()
        when (slot.type) {
            PlatformType.EMPTY, PlatformType.BADGE -> showBadgeBoxDialog(targetSlotIndex = index)
            PlatformType.FOLDER -> showRenameFolderDialog(slot)
            else -> showGameManualAndConfigDialog(slot)
        }
    }

    override fun onRightActionRequested(slot: GridSlotItem, index: Int) {
        SoundEffectEngine.playSelectApplet()
        when {
            currentOpenedFolder != null && slot.type != PlatformType.EMPTY -> {
                moveItemOutOfFolder(slot)
            }
            slot.type == PlatformType.EMPTY -> {
                showCreateFolderDialog(index)
            }
            slot.type == PlatformType.FOLDER -> {
                confirmDeleteFolder(slot, index)
            }
            slot.type == PlatformType.BADGE -> {
                clearSlotAt(index)
                Toast.makeText(dialogContext(), "已取下桌面徽章", Toast.LENGTH_SHORT).show()
            }
            else -> {
                showMoveOrArchiveDialog(slot, index)
            }
        }
    }

    override fun onCategoryStepRequested(delta: Int) {
        if (currentOpenedFolder != null) {
            currentOpenedFolder = null
        }
        val categories = repo.getCustomCategories()
        if (categories.isEmpty()) return
        val count = categories.size
        currentCategoryIndex = (currentCategoryIndex + delta + count) % count
        applyActiveCategoryState(animateZoom = true)
    }

    override fun onCategoryPickerRequested() {
        SoundEffectEngine.playSelectApplet()
        lowerScreenView.openCategoryManager()
    }

    private fun showCategoryAndSeriesPickerDialog() {
        val ctx = dialogContext()
        val categories = repo.getCustomCategories()
        val items = mutableListOf<String>()

        items.add("🏷️ 【管理系列与游戏类别】 (自定义/排序/增删游戏)...")

        val allCat = categories.firstOrNull { it.id == "all" }
        if (allCat != null) {
            items.add("✦ 【全部游戏】 (${countGamesForCategory("all")}款)")
        }

        val seriesList = categories.filter { it.isSeries && it.id != "all" }
        val genreList = categories.filter { !it.isSeries && it.id != "all" }

        val actionIndexMap = mutableMapOf<Int, CustomCategoryItem>()
        var curIdx = 1
        if (allCat != null) {
            actionIndexMap[curIdx] = allCat
            curIdx++
        }

        if (seriesList.isNotEmpty()) {
            items.add("--- 🎮 IP 精选系列 ---")
            curIdx++
            for (cat in seriesList) {
                items.add("${cat.badgeSymbol} ${cat.displayName} (${countGamesForCategory(cat.id)}款)")
                actionIndexMap[curIdx] = cat
                curIdx++
            }
        }

        if (genreList.isNotEmpty()) {
            items.add("--- 🕹️ 通用游戏类型分类 ---")
            curIdx++
            for (cat in genreList) {
                items.add("${cat.badgeSymbol} ${cat.displayName} (${countGamesForCategory(cat.id)}款)")
                actionIndexMap[curIdx] = cat
                curIdx++
            }
        }

        items.add("--- ⚙️ 平台与显示设置 ---")
        val platSettingsIdx = curIdx + 1
        items.add("🎮 配置主屏显示的模拟器平台 (开启/关闭模拟器)...")

        AlertDialog.Builder(ctx)
            .setTitle("🏷️ 选择主屏显示系列与分类")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    lowerScreenView.openSettingsPanel(initialTab = 3)
                    lowerScreenView.openCategoryManager()
                    return@setItems
                }
                if (which == platSettingsIdx) {
                    lowerScreenView.openSettingsPanel(initialTab = 2)
                    return@setItems
                }
                val selectedCat = actionIndexMap[which] ?: return@setItems
                val idx = categories.indexOfFirst { it.id == selectedCat.id }
                if (idx >= 0) {
                    currentCategoryIndex = idx
                    repo.currentCategoryId = selectedCat.id
                    lowerScreenView.selectedTopIconIndex = -1
                    lowerScreenView.selectedIndex = 0
                    upperScreenView.activeApplet = SystemAppletType.NONE
                    SoundEffectEngine.playZoomChange(true)
                    val activeSlots = getCurrentDisplayedSlots().filter { it.type != PlatformType.EMPTY }.take(12)
                    for (g in activeSlots) {
                        lowerScreenView.predecodeSlotIconSync(g)
                    }
                    syncViewsWithState()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 模拟器平台与游戏库管理 (Game Library & Emulator Platforms) ====================
    private fun showGameLibraryManagerDialog() {
        try {
            activeAppletDialog?.dismiss()
            val ctx = this
        val enabledPlatforms = repo.getEnabledPlatforms().toMutableSet()

        // 统计各平台当前已扫描/挂载的游戏数量
        val countByPlatform = rootSlots.filter {
            !it.isDemo &&
                it.type != PlatformType.EMPTY &&
                it.type != PlatformType.FOLDER &&
                it.type != PlatformType.BADGE &&
                it.type != PlatformType.ANDROID_APP
        }.groupingBy { it.getEffectivePlatform() }.eachCount()

        val allPlatforms = com.gameconsole.launcher3ds.model.PlatformCatalog.getAllPlatforms(countByPlatform.keys + repo.getDiscoveredMediaLibraries().map { it.name })

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        // 顶部操作说明与简介
        val headerTv = TextView(ctx).apply {
            text = "💡 勾选要在 3DS 主屏桌面显示的游戏平台类型。默认只显示 3DS 游戏；若导入了天马G或多机种 ROM，勾选后即可在主屏一并畅玩。"
            setTextColor(Color.parseColor("#475569"))
            textSize = 12.5f
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(headerTv)

        // 快捷预设按钮栏
        val presetRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        val checkBoxes = HashMap<String, CheckBox>()

        val btnOnly3ds = createStyledButton(ctx, "🌟 仅3DS (默认)", Color.parseColor("#E60012"), Color.WHITE).apply {
            textSize = 12f
            setOnClickListener {
                enabledPlatforms.clear()
                enabledPlatforms.add("3DS")
                for ((id, cb) in checkBoxes) {
                    cb.isChecked = (id == "3DS")
                }
                SoundEffectEngine.playCursorMove()
            }
        }
        val btnSelectAll = createStyledButton(ctx, "🌐 全选平台", Color.parseColor("#0284C7"), Color.WHITE).apply {
            textSize = 12f
            setOnClickListener {
                allPlatforms.forEach { enabledPlatforms.add(it.id) }
                for ((_, cb) in checkBoxes) {
                    cb.isChecked = true
                }
                SoundEffectEngine.playCursorMove()
            }
        }
        val btnInvert = createStyledButton(ctx, "🔄 反选", Color.parseColor("#64748B"), Color.WHITE).apply {
            textSize = 12f
            setOnClickListener {
                allPlatforms.forEach {
                    if (enabledPlatforms.contains(it.id)) enabledPlatforms.remove(it.id) else enabledPlatforms.add(it.id)
                }
                for ((id, cb) in checkBoxes) {
                    cb.isChecked = enabledPlatforms.contains(id)
                }
                SoundEffectEngine.playCursorMove()
            }
        }

        val lp = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) }
        val lpLast = LinearLayout.LayoutParams(0, dp(38), 1f)
        presetRow.addView(btnOnly3ds, lp)
        presetRow.addView(btnSelectAll, lp)
        presetRow.addView(btnInvert, lpLast)
        root.addView(presetRow)

        addSectionHeader(ctx, root, "平台显示过滤与模拟器绑定 (勾选 = 在主屏展示)：")

        // 平台列表（带滚动视图）
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
        }
        val platformContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        for (plat in allPlatforms) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#F8FAFC"))
                    setStroke(dp(1), Color.parseColor("#CBD5E1"))
                    cornerRadius = dp(8).toFloat()
                }
                background = bg
            }

            val cb = CheckBox(ctx).apply {
                val gameCount = countByPlatform[plat.id] ?: 0
                text = "${plat.name}  [${gameCount}款]"
                setTextColor(Color.parseColor("#1E293B"))
                textSize = 13f
                isChecked = enabledPlatforms.contains(plat.id)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) enabledPlatforms.add(plat.id) else enabledPlatforms.remove(plat.id)
                }
            }
            checkBoxes[plat.id] = cb

            val emuBtn = Button(ctx).apply {
                val curPkg = repo.getPlatformEmulator(plat.id)
                val appLabel = queryAppLabel(curPkg).ifBlank {
                    if (plat.id == "3DS") "Citra/Azahar" else if (plat.id == "NDS") "DraStic/melonDS" else "RetroArch"
                }
                text = "🎮 $appLabel"
                textSize = 11.5f
                setTextColor(Color.parseColor("#0369A1"))
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    showPickEmulatorForPlatformDialog(plat) { newPkg ->
                        repo.setPlatformEmulator(plat.id, newPkg)
                        val updatedLabel = queryAppLabel(newPkg).ifBlank { newPkg }
                        text = "🎮 $updatedLabel"
                        Toast.makeText(ctx, "已为 ${plat.name} 绑定模拟器：$updatedLabel", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            card.addView(cb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(emuBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)))
            platformContainer.addView(
                card,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                }
            )
        }
        scroll.addView(platformContainer)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))

        // 底部导入与扫描 ROM 模块
        addSectionHeader(ctx, root, "导入天马G / 本地 ROM 目录：")
        val dirInput = EditText(ctx).apply {
            val defaultPath = repo.lastMountedMediaDir.ifBlank {
                val sd = Environment.getExternalStorageDirectory().absolutePath
                "$sd/roms"
            }
            hint = "输入天马G Roms 或本地 ROM 目录路径"
            setText(defaultPath)
            textSize = 12f
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(dirInput)

        val scanRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val scanNowBtn = createStyledButton(ctx, "🔄 立即扫描导入", Color.parseColor("#10B981"), Color.WHITE).apply {
            textSize = 12f
            setOnClickListener {
                val path = dirInput.text.toString().trim()
                val count = repo.scanRomDirectories(customDir = path.ifBlank { null }, removeDemoCards = false)
                rootSlots = repo.loadRootSlots()
                syncViewsWithState()
                SoundEffectEngine.playSelectApplet()
                Toast.makeText(ctx, "扫描导入完成！共发现/更新 $count 款游戏与媒体资源", Toast.LENGTH_LONG).show()
                // 刷新弹窗内各平台的游戏计数
                val newCounts = rootSlots.filter { !it.isDemo && it.type != PlatformType.EMPTY }.groupingBy { it.getEffectivePlatform() }.eachCount()
                for (p in allPlatforms) {
                    val c = newCounts[p.id] ?: 0
                    checkBoxes[p.id]?.text = "${p.name}  [${c}款]"
                }
            }
        }
        val browseBtn = createStyledButton(ctx, "📁 选择常用路径", Color.parseColor("#475569"), Color.WHITE).apply {
            textSize = 12f
            setOnClickListener {
                showPickDirectoryPresetsDialog { chosenDir ->
                    dirInput.setText(chosenDir)
                }
            }
        }
        scanRow.addView(scanNowBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
        scanRow.addView(browseBtn, LinearLayout.LayoutParams(0, dp(40), 1f))
        root.addView(scanRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })

        activeAppletDialog = AlertDialog.Builder(ctx)
            .setTitle("🎮 模拟器平台与游戏库管理")
            .setView(root)
            .setPositiveButton("💾 保存并应用到主屏") { d, _ ->
                if (enabledPlatforms.isEmpty()) {
                    // 若未勾选任何平台，自动保留 3DS（符合“默认只选中3DS”）
                    enabledPlatforms.add("3DS")
                }
                repo.saveEnabledPlatforms(enabledPlatforms)
                syncViewsWithState()
                SoundEffectEngine.playSelectApplet()
                val visibleCount = getCurrentDisplayedSlots().count { it.type != PlatformType.EMPTY }
                Toast.makeText(ctx, "已保存平台设置！当前主屏展示平台: [${enabledPlatforms.joinToString()}] 共 $visibleCount 款游戏", Toast.LENGTH_LONG).show()
                d.dismiss()
            }
            .setNegativeButton("取消") { d, _ ->
                SoundEffectEngine.playBackCancel()
                d.dismiss()
            }
            .create().apply {
                show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "打开游戏库管理失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPickEmulatorForPlatformDialog(plat: com.gameconsole.launcher3ds.model.PlatformDefinition, onPicked: (String) -> Unit) {
        val ctx = dialogContext()
        val allApps = repo.queryAllInstalledApps()
        val emuApps = allApps.filter { app ->
            val pkg = app.packageName.lowercase()
            val label = app.title.lowercase()
            pkg.contains("citra") || pkg.contains("azahar") || pkg.contains("lime") || pkg.contains("mandarine") ||
                pkg.contains("drastic") || pkg.contains("melonds") || pkg.contains("retroarch") ||
                pkg.contains("ppsspp") || pkg.contains("duckstation") || pkg.contains("flycast") ||
                pkg.contains("redream") || pkg.contains("yabasanshiro") || pkg.contains("snes") ||
                pkg.contains("emu") || pkg.contains("arcade") || label.contains("模拟器") ||
                label.contains("citra") || label.contains("drastic") || label.contains("retroarch") ||
                label.contains("ppsspp")
        }
        val candidates = if (emuApps.isNotEmpty()) emuApps else allApps
        val names = candidates.map { "${it.title} (${it.packageName})" }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("为 ${plat.name} 选择关联模拟器")
            .setItems(names) { _, which ->
                val chosen = candidates[which]
                onPicked(chosen.packageName)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPickDirectoryPresetsDialog(onChosen: (String) -> Unit) {
        val ctx = dialogContext()
        val dirs = repo.getCustomRomDirs()
        val sd = Environment.getExternalStorageDirectory().absolutePath
        val candidateDirs = (listOf(
            "I:\\天马G整合包\\【1】基础包_110GB\\基础包_110GB Roms\\Roms",
            "I:\\天马G整合包\\【08】3DS--142G\\3DS media\\3DS\\media",
            "I:\\天马G整合包\\【08】3DS--142G",
            "$sd/roms",
            "$sd/roms/3DS",
            "$sd/roms/3ds/media",
            "$sd/Roms",
            "$sd/天马G/Roms"
        ) + dirs).distinct().toTypedArray()

        AlertDialog.Builder(ctx)
            .setTitle("📁 选择扫描目录预设")
            .setItems(candidateDirs) { _, which ->
                onChosen(candidateDirs[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun queryAppLabel(pkg: String): String {
        if (pkg.isBlank()) return ""
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            ""
        }
    }

    override fun onTopIconFocused(topIconIndex: Int, previewApplet: SystemAppletType) {
        SoundEffectEngine.playCursorMove()
        upperScreenView.activeApplet = previewApplet
    }

    override fun onVideoAudioToggleRequested() {
        val currentSlot = upperScreenView.selectedSlot
        val hasBanner = currentSlot != null && currentSlot.bannerAnimationPath.isNotBlank()

        if (hasBanner) {
            val willShowPreview = !upperScreenView.isPreviewVideoPlaying
            upperScreenView.isPreviewVideoPlaying = willShowPreview
            upperScreenView.isVideoAudioEnabled = willShowPreview
            lowerScreenView.isVideoAudioEnabled = willShowPreview
            isVideoAudioMode = willShowPreview
            SoundEffectEngine.playSelectApplet()

            if (willShowPreview) {
                SoundEffectEngine.stopBgm()
                Toast.makeText(dialogContext(), "▶ 已切换为：实机演示视频播放 (原声已开启)", Toast.LENGTH_SHORT).show()
            } else {
                if (repo.bgmEnabled) {
                    SoundEffectEngine.startBgm()
                }
                Toast.makeText(dialogContext(), "✨ 已切换为：3DS 原版 3D 横幅动效", Toast.LENGTH_SHORT).show()
            }
        } else {
            isVideoAudioMode = !isVideoAudioMode
            upperScreenView.isVideoAudioEnabled = isVideoAudioMode
            lowerScreenView.isVideoAudioEnabled = isVideoAudioMode
            SoundEffectEngine.playSelectApplet()

            if (isVideoAudioMode) {
                SoundEffectEngine.stopBgm()
                Toast.makeText(dialogContext(), "🔊 已开启视频原声（背景音乐已暂停，再按一次切回）", Toast.LENGTH_SHORT).show()
            } else {
                if (repo.bgmEnabled) {
                    SoundEffectEngine.startBgm()
                }
                Toast.makeText(dialogContext(), "🎵 已恢复背景音乐（视频静音）", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getCandidateRomDirs(): List<String> {
        val result = LinkedHashSet<String>()
        val tf = repo.getDetectedTfCardPath()
        val sd = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"

        if (tf.isNotBlank()) {
            val tfFile = File(tf)
            // 自动搜索 TF 卡上的实际天马包或 roms 目录
            tfFile.listFiles()?.forEach { top ->
                if (top.isDirectory && !top.name.startsWith(".") && !top.name.equals("Android", ignoreCase = true)) {
                    val n = top.name
                    if (n.contains("天马") || n.contains("Pegasus") || n.contains("整合") || n.startsWith("【")) {
                        result.add(top.absolutePath)
                        top.listFiles()?.forEach { sub ->
                            if (sub.isDirectory) {
                                val sName = sub.name
                                if (sName.contains("3DS", ignoreCase = true) || sName.contains("NDS", ignoreCase = true) || sName.contains("Rom", ignoreCase = true) || sName.contains("media", ignoreCase = true)) {
                                    result.add(sub.absolutePath)
                                    val subMedia = File(sub, "3DS media/3DS/media")
                                    if (subMedia.exists()) result.add(subMedia.absolutePath)
                                }
                            }
                        }
                    } else if (n.equals("roms", ignoreCase = true) || n.equals("media", ignoreCase = true) || n.equals("3ds", ignoreCase = true)) {
                        result.add(top.absolutePath)
                    }
                }
            }
            result.add("$tf/roms/3ds")
            result.add("$tf/roms")
        }

        // 内置存储
        result.add("$sd/roms/3ds")
        result.add("$sd/roms")

        // 已经探测到的媒体库
        repo.getDiscoveredMediaLibraries().forEach {
            result.add(it.path)
        }

        // 用户自定义目录
        result.addAll(repo.getCustomRomDirs())

        return result.filter { it.isNotBlank() && (File(it).exists() || it.startsWith("/storage")) }.take(10)
    }

    override fun onAppletClicked(applet: SystemAppletType) {
        if (applet == SystemAppletType.NONE) {
            if (lowerScreenView.isSettingsPanelOpen) {
                lowerScreenView.closeSettingsPanel()
                return
            }
            if (currentOpenedFolder != null) {
                SoundEffectEngine.playBackCancel()
                currentOpenedFolder = null
                lowerScreenView.selectedIndex = 0
                syncViewsWithState()
            }
            return
        }

        SoundEffectEngine.playSelectApplet()
        upperScreenView.activeApplet = applet

        when (applet) {
            SystemAppletType.GAME_DESCRIPTION -> {
                lowerScreenView.toggleGameDescription()
            }
            SystemAppletType.HOME_SETTINGS -> {
                // 方案 A：第 1 个图标负责 HOME 菜单、主题与个性化设置
                lowerScreenView.openSettingsPanel(initialTab = 1)
            }
            SystemAppletType.VIDEO_FILTER_SETTINGS -> {
                // 直接在下屏原版 3DS 内嵌面板中展开「上屏视频滤镜」配置页！
                lowerScreenView.openSettingsPanel(initialTab = 0)
            }
            SystemAppletType.GAME_LIBRARY -> {
                // 方案 A：倒数第 3 个图标负责游戏库与模拟器高级系统设置
                lowerScreenView.openSettingsPanel(initialTab = 3)
                lowerScreenView.settingsSubPanel = LowerScreenView.SettingsSubPanel.ADVANCED_SETTINGS
            }
            SystemAppletType.GAME_NOTES -> showGameNotesDialog()
            SystemAppletType.FRIEND_LIST -> showFriendListDialog()
            SystemAppletType.NOTIFICATIONS -> showNotificationsDialog()
            SystemAppletType.BROWSER -> showBrowserDialog()
            SystemAppletType.MIIVERSE -> showBadgeBoxDialog(targetSlotIndex = lowerScreenView.selectedIndex)
            else -> {}
        }
    }

    override fun onVideoFilterSelected(filter: com.gameconsole.launcher3ds.model.VideoFilterType) {
        repo.videoFilterId = filter.id
        upperScreenView.videoFilter = filter
        SoundEffectEngine.playCursorMove()
        Toast.makeText(dialogContext(), "已应用滤镜：${filter.displayName}", Toast.LENGTH_SHORT).show()
    }

    override fun onThemeSelectedFromPanel(theme: com.gameconsole.launcher3ds.model.Theme3DS) {
        repo.themeId = theme.id
        SoundEffectEngine.updateTheme(theme, repo.customBgmPath)
        syncViewsWithState()
        Toast.makeText(dialogContext(), "已切换主题：${theme.name}", Toast.LENGTH_SHORT).show()
    }

    override fun onQuickToggleBgmRequested() {
        repo.bgmEnabled = !repo.bgmEnabled
        SoundEffectEngine.bgmEnabled = repo.bgmEnabled
        if (repo.bgmEnabled && !isVideoAudioMode) {
            SoundEffectEngine.startBgm()
            Toast.makeText(dialogContext(), "🎵 背景音乐已开启", Toast.LENGTH_SHORT).show()
        } else {
            SoundEffectEngine.stopBgm()
            Toast.makeText(dialogContext(), "🔇 背景音乐已关闭", Toast.LENGTH_SHORT).show()
        }
        syncViewsWithState()
    }

    override fun onQuickToggleSfxRequested() {
        val cur = repo.sfxVolume
        val next = when {
            cur >= 88 -> 75
            cur >= 63 -> 50
            cur >= 38 -> 25
            cur >= 12 -> 0
            else -> 100
        }
        repo.sfxVolume = next
        repo.sfxEnabled = (next > 0)
        SoundEffectEngine.sfxVolume = next / 100f
        lowerScreenView.sfxVolumePercent = next
        lowerScreenView.isSfxEnabled = (next > 0)
        if (next > 0) {
            SoundEffectEngine.playCursorMove()
        }
        val desc = if (next == 0) "🔇 音效已静音" else "🔊 音效音量已调节至 " + next + "%"
        Toast.makeText(dialogContext(), desc, Toast.LENGTH_SHORT).show()
        syncViewsWithState()
    }

    override fun onCycleUpperDisplayModeRequested() {
        val nextMode = repo.cycleNextUpperDisplayMode()
        upperScreenView.upperDisplayMode = repo.upperDisplayMode
        upperScreenView.showUpperBoxart = repo.showUpperBoxart
        upperScreenView.showUpperLogo = repo.showUpperLogo
        lowerScreenView.upperDisplayMode = repo.upperDisplayMode
        lowerScreenView.showUpperBoxart = repo.showUpperBoxart
        lowerScreenView.showUpperLogo = repo.showUpperLogo
        SoundEffectEngine.playSelectApplet()
        Toast.makeText(dialogContext(), "已切换上屏方案：${nextMode.title}\n(${nextMode.desc})", Toast.LENGTH_SHORT).show()
        syncViewsWithState()
    }

    override fun onToggleUpperBoxartRequested() {
        repo.showUpperBoxart = !repo.showUpperBoxart
        val newMode = if (repo.showUpperBoxart) {
            if (repo.showUpperLogo) UpperDisplayMode.DUAL_HYBRID else UpperDisplayMode.BOXART_3D
        } else {
            if (repo.showUpperLogo) UpperDisplayMode.LOGO_HERO else UpperDisplayMode.CINEMA_PURE
        }
        repo.upperDisplayMode = newMode
        SoundEffectEngine.playSelectApplet()
        val desc = if (repo.showUpperBoxart) "📦 3D卡盒展示：已开启" else "📦 3D卡盒展示：已隐藏"
        Toast.makeText(dialogContext(), "$desc (当前方案: ${newMode.shortLabel})", Toast.LENGTH_SHORT).show()
        syncViewsWithState()
    }

    override fun onToggleUpperLogoRequested() {
        repo.showUpperLogo = !repo.showUpperLogo
        val newMode = if (repo.showUpperLogo) {
            if (repo.showUpperBoxart) UpperDisplayMode.DUAL_HYBRID else UpperDisplayMode.LOGO_HERO
        } else {
            if (repo.showUpperBoxart) UpperDisplayMode.BOXART_3D else UpperDisplayMode.CINEMA_PURE
        }
        repo.upperDisplayMode = newMode
        SoundEffectEngine.playSelectApplet()
        val desc = if (repo.showUpperLogo) "🏷️ 游戏Logo展示：已开启" else "🏷️ 游戏Logo展示：已隐藏"
        Toast.makeText(dialogContext(), "$desc (当前方案: ${newMode.shortLabel})", Toast.LENGTH_SHORT).show()
        syncViewsWithState()
    }

    override fun onQuickSwapScreensRequested() {
        toggleSwapScreensQuickly()
        syncViewsWithState()
    }

    override fun onQuickRescanRequested() {
        if (!hasStoragePermission()) {
            requestStoragePermission()
            return
        }
        SoundEffectEngine.playSelectApplet()
        Toast.makeText(dialogContext(), "正在全量扫描游戏库 (${repo.scanStorageScope.shortLabel})...", Toast.LENGTH_SHORT).show()
        triggerBackgroundRomScan(showToastOnCompletion = true)
    }

    override fun onPick3dsEmulatorRequested() {
        showDefaultEmulatorPicker(PlatformType.N3DS)
    }

    override fun onPickNdsEmulatorRequested() {
        showDefaultEmulatorPicker(PlatformType.NDS)
    }

    override fun onOpenAdvancedSettingsDialogRequested() {
        lowerScreenView.openSettingsPanel(initialTab = 3)
        lowerScreenView.settingsSubPanel = LowerScreenView.SettingsSubPanel.ADVANCED_SETTINGS
    }

    override fun onSettingsPanelClosed() {
        upperScreenView.activeApplet = SystemAppletType.NONE
        SoundEffectEngine.playBackCancel()
        syncViewsWithState()
    }

    override fun onAutoRandomToggled() {
        if (isAutoRandomRunning) {
            stopAutoRandomPlayback()
            Toast.makeText(dialogContext(), "⏹ 已停止自动随机轮播", Toast.LENGTH_SHORT).show()
        } else {
            startAutoRandomPlayback()
            Toast.makeText(dialogContext(), "🎲 已开启自动随机轮播（每 ${repo.autoRandomIntervalSec} 秒轮换）", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAutoRandomIntervalChanged(newIntervalSec: Int) {
        val clamped = newIntervalSec.coerceIn(2, 3600)
        repo.autoRandomIntervalSec = clamped
        lowerScreenView.autoRandomIntervalSec = clamped
        SoundEffectEngine.playSelectApplet()
        if (isAutoRandomRunning) {
            autoRandomHandler.removeCallbacks(autoRandomRunnable)
            autoRandomHandler.postDelayed(autoRandomRunnable, (clamped * 1000L).coerceIn(2000L, 3600000L))
        }
        Toast.makeText(dialogContext(), "⏱ 轮播间隔已设置为每 $clamped 秒", Toast.LENGTH_SHORT).show()
    }

    override fun onAutoRandomIntervalDialogRequested() {
        lowerScreenView.openSettingsPanel(initialTab = 3)
        lowerScreenView.settingsSubPanel = LowerScreenView.SettingsSubPanel.AUTO_RANDOM_INTERVAL
    }

    override fun onRomDirectoryPicked(chosenDir: String) {
        repo.lastMountedMediaDir = chosenDir
        SoundEffectEngine.playSelectApplet()
        Toast.makeText(dialogContext(), "已选择目录：$chosenDir\n正在后台扫描导入...", Toast.LENGTH_SHORT).show()
        triggerBackgroundRomScan(customDir = chosenDir, showToastOnCompletion = true)
    }

    override fun onToggleSplitPreviewRequested() {
        repo.splitPreviewOnSingleScreen = !repo.splitPreviewOnSingleScreen
        SoundEffectEngine.playSelectApplet()
        setupDualScreenLayout()
        syncViewsWithState()
    }

    override fun onToggleSubscreenCompanionRequested() {
        repo.subscreenCompanionEnabled = !repo.subscreenCompanionEnabled
        syncViewsWithState()
        syncCompanionSettingsToApollo()
        if (!repo.subscreenCompanionEnabled) {
            com.gameconsole.launcher3ds.companion.overlay.LauncherCompanionOverlayManager.hide()
        }
        val state = if (repo.subscreenCompanionEnabled) "开启 (单屏游戏副屏启动伴侣面板)" else "关闭 (单屏游戏副屏保持原生黑屏)"
        android.widget.Toast.makeText(dialogContext(), "单屏游戏副屏面板: $state", android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onToggleBgmRequested() {
        repo.bgmEnabled = !repo.bgmEnabled
        SoundEffectEngine.bgmEnabled = repo.bgmEnabled
        if (repo.bgmEnabled) SoundEffectEngine.startBgm() else SoundEffectEngine.stopBgm()
        syncViewsWithState()
    }

    override fun onSetDefaultHomeRequested() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(dialogContext(), "请在系统设置 -> 默认应用中将「3DS HOME 双屏桌面」设为默认主屏幕", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSubscreenRetroFilterToggled() {
        repo.subscreenRetroFilterEnabled = !repo.subscreenRetroFilterEnabled
        SoundEffectEngine.playSelectApplet()
        syncViewsWithState()
        syncRetroFilterToApollo()
        com.gameconsole.launcher3ds.overlay.LauncherRetroFilterManager.update(
            context = this,
            enabled = repo.subscreenRetroFilterEnabled,
            typeStr = repo.subscreenRetroFilterType,
            opacity = repo.subscreenRetroFilterOpacity
        )
        if (repo.subscreenRetroFilterEnabled && !com.gameconsole.launcher3ds.overlay.LauncherRetroFilterManager.hasOverlayPermission(this)) {
            AlertDialog.Builder(dialogContext())
                .setTitle("提示：需要开启悬浮窗权限")
                .setMessage("副屏复古滤镜需要在 3DS / NDS 模拟器游戏运行时覆盖点阵微层。\n请在打开的系统页面中允许 Launcher3DS「在其他应用上层显示」。")
                .setPositiveButton("前往开启") { _, _ ->
                    com.gameconsole.launcher3ds.overlay.LauncherRetroFilterManager.requestOverlayPermission(this)
                }
                .setNegativeButton("稍后", null)
                .show()
        }
    }

    override fun onSubscreenRetroFilterTypeSelected(type: String) {
        repo.subscreenRetroFilterType = type
        SoundEffectEngine.playCursorMove()
        syncViewsWithState()
        syncRetroFilterToApollo()
        com.gameconsole.launcher3ds.overlay.LauncherRetroFilterManager.update(
            context = this,
            enabled = repo.subscreenRetroFilterEnabled,
            typeStr = repo.subscreenRetroFilterType,
            opacity = repo.subscreenRetroFilterOpacity
        )
    }

    override fun onSubscreenRetroFilterOpacitySelected(opacity: Int) {
        repo.subscreenRetroFilterOpacity = opacity
        SoundEffectEngine.playCursorMove()
        syncViewsWithState()
        syncRetroFilterToApollo()
        com.gameconsole.launcher3ds.overlay.LauncherRetroFilterManager.update(
            context = this,
            enabled = repo.subscreenRetroFilterEnabled,
            typeStr = repo.subscreenRetroFilterType,
            opacity = repo.subscreenRetroFilterOpacity
        )
    }

    override fun onMainScreenRetroFilterToggled() {
        repo.mainScreenRetroFilterEnabled = !repo.mainScreenRetroFilterEnabled
        SoundEffectEngine.playSelectApplet()
        syncViewsWithState()
        Toast.makeText(dialogContext(), if (repo.mainScreenRetroFilterEnabled) "主屏滤镜已全局强制开启" else "主屏滤镜全局强制已关闭", Toast.LENGTH_SHORT).show()
    }

    override fun onMainScreenRetroFilterAuto2DToggled() {
        repo.mainScreenRetroFilterAuto2D = !repo.mainScreenRetroFilterAuto2D
        SoundEffectEngine.playSelectApplet()
        syncViewsWithState()
        Toast.makeText(dialogContext(), if (repo.mainScreenRetroFilterAuto2D) "主屏 2D 智能识别已开启（《冒险岛》等 2D 贴图游戏自动启用）" else "主屏 2D 智能识别已关闭", Toast.LENGTH_SHORT).show()
    }

    override fun onMainScreenRetroFilterTypeSelected(type: String) {
        repo.mainScreenRetroFilterType = type
        SoundEffectEngine.playCursorMove()
        syncViewsWithState()
    }

    override fun onMainScreenRetroFilterOpacitySelected(opacity: Int) {
        repo.mainScreenRetroFilterOpacity = opacity
        SoundEffectEngine.playCursorMove()
        syncViewsWithState()
    }

    override fun onScanStorageScopeChanged(scope: ScanStorageScope) {
        repo.scanStorageScope = scope
        lowerScreenView.scanStorageScope = scope
        lowerScreenView.detectedTfCardPath = repo.getDetectedTfCardPath()
        lowerScreenView.mediaLibraries = repo.getDiscoveredMediaLibraries(forceRefresh = false)
        val desc = when (scope) {
            ScanStorageScope.BOTH -> "已切换为：两者同时扫描 (内部存储 + TF卡)"
            ScanStorageScope.INTERNAL_ONLY -> "已切换为：仅扫描内置存储"
            ScanStorageScope.SDCARD_ONLY -> {
                val tf = repo.getDetectedTfCardPath()
                if (tf.isNotBlank()) "已切换为：仅扫描外置TF卡 ($tf)" else "已切换为：仅扫描外置TF卡 (未检测到TF卡)"
            }
        }
        Toast.makeText(dialogContext(), desc, Toast.LENGTH_SHORT).show()
    }

    override fun onManualRomScanRequested() {
        onQuickRescanRequested()
    }

    override fun onMediaLibraryManagerOpened() {
        lowerScreenView.mediaLibraries = repo.getDiscoveredMediaLibraries(forceRefresh = false)
        lowerScreenView.invalidate()
    }

    private fun syncRetroFilterToApollo() {
        try {
            val intent = Intent("com.gameconsole.apollo.ACTION_SET_RETRO_FILTER").apply {
                putExtra("extra_enabled", repo.subscreenRetroFilterEnabled)
                putExtra("extra_type", repo.subscreenRetroFilterType)
                putExtra("extra_opacity", repo.subscreenRetroFilterOpacity)
            }
            sendBroadcast(intent)
        } catch (_: Exception) {}
        syncCompanionSettingsToApollo()
    }

    private fun syncCompanionSettingsToApollo() {
        try {
            val intent = Intent("com.gameconsole.apollo.ACTION_SET_COMPANION_ENABLED").apply {
                putExtra("extra_enabled", repo.subscreenCompanionEnabled)
            }
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    private fun showAutoRandomIntervalDialog() {
        try {
            activeAppletDialog?.dismiss()
            val ctx = dialogContext()
            val curVal = repo.autoRandomIntervalSec

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
            }

            val descTv = TextView(ctx).apply {
                text = "设置上屏实机游戏封面与演示视频的自动轮播间隔时间。支持直接手动输入秒数，或点击下方常用预设快捷填入："
                textSize = 13.5f
                setTextColor(Color.parseColor("#475569"))
            }
            root.addView(descTv)

            // 输入区域 (带单位标签)
            val inputRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(12))
            }

            val inputEt = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(curVal.toString())
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#F1F5F9"))
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setSelectAllOnFocus(true)
            }
            inputRow.addView(inputEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val unitTv = TextView(ctx).apply {
                text = " 秒"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#1E293B"))
                setPadding(dp(8), 0, 0, 0)
            }
            inputRow.addView(unitTv)
            root.addView(inputRow)

            // 快捷预设按钮组
            val presetLabel = TextView(ctx).apply {
                text = "⚡ 快捷预设时长："
                textSize = 12.5f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, dp(6), 0, dp(6))
            }
            root.addView(presetLabel)

            val presets = intArrayOf(5, 10, 15, 20, 30, 60, 120, 300)
            val chipsRow1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(2), 0, dp(4))
            }
            val chipsRow2 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(2), 0, dp(8))
            }

            for ((idx, sec) in presets.withIndex()) {
                val chip = Button(ctx).apply {
                    text = "${sec}秒"
                    textSize = 12f
                    setOnClickListener {
                        inputEt.setText(sec.toString())
                        inputEt.setSelection(inputEt.text.length)
                    }
                }
                val lp = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                if (idx < 4) {
                    chipsRow1.addView(chip, lp)
                } else {
                    chipsRow2.addView(chip, lp)
                }
            }
            root.addView(chipsRow1)
            root.addView(chipsRow2)

            activeAppletDialog = AlertDialog.Builder(ctx)
                .setTitle("🎲 自动随机轮播时长设置")
                .setView(root)
                .setPositiveButton("✔ 确认保存") { d, _ ->
                    val typedSec = inputEt.text.toString().trim().toIntOrNull() ?: curVal
                    val finalSec = typedSec.coerceIn(2, 3600)
                    onAutoRandomIntervalChanged(finalSec)
                    d.dismiss()
                }
                .setNegativeButton("取消") { d, _ ->
                    SoundEffectEngine.playBackCancel()
                    d.dismiss()
                }
                .create()
            activeAppletDialog?.show()
        } catch (_: Exception) {
        }
    }

    override fun onPlatformToggled(platformId: String) {
        val normId = com.gameconsole.launcher3ds.model.PlatformCatalog.normalizeId(platformId)
        val current = repo.getEnabledPlatforms().map { com.gameconsole.launcher3ds.model.PlatformCatalog.normalizeId(it) }.toMutableSet()
        if (current.contains(normId)) {
            if (current.size > 1) {
                current.remove(normId)
            } else {
                Toast.makeText(dialogContext(), "至少保留一个显示平台", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            current.add(normId)
        }
        repo.saveEnabledPlatforms(current)
        lowerScreenView.enabledPlatforms = current
        invalidatePlatformGameCountsCache()
        lowerScreenView.invalidate()
        SoundEffectEngine.playSelectApplet()
        syncViewsWithState()
    }

    override fun onPresetOnly3dsRequested() {
        repo.saveEnabledPlatforms(setOf("3DS"))
        SoundEffectEngine.playSelectApplet()
        syncViewsWithState()
        Toast.makeText(dialogContext(), "已重置为默认：仅显示 3DS 平台", Toast.LENGTH_SHORT).show()
    }

    override fun onPresetHandheldsRequested() {
        val handhelds = setOf("3DS", "NDS", "GBA", "GBC", "GB", "PSP")
        repo.saveEnabledPlatforms(handhelds)
        SoundEffectEngine.playSelectApplet()
        syncViewsWithState()
        Toast.makeText(dialogContext(), "已应用常用掌机预设（3DS / NDS / GBA / GB / PSP）", Toast.LENGTH_SHORT).show()
    }

    override fun onPresetSelectAllPlatformsRequested() {
        val all = com.gameconsole.launcher3ds.model.PlatformCatalog.getAllPlatforms(repo.getDiscoveredMediaLibraries().map { it.name }).map { it.id }.toSet()
        repo.saveEnabledPlatforms(all)
        SoundEffectEngine.playSelectApplet()
        syncViewsWithState()
        Toast.makeText(dialogContext(), "已全选平台（主屏展示所有平台游戏）", Toast.LENGTH_SHORT).show()
    }

    override fun onPresetInvertPlatformsRequested() {
        val all = com.gameconsole.launcher3ds.model.PlatformCatalog.ALL_PLATFORMS.map { it.id }
        val current = repo.getEnabledPlatforms()
        val inverted = all.filter { !current.contains(it) }.toMutableSet()
        if (inverted.isEmpty()) {
            inverted.add("3DS")
        }
        repo.saveEnabledPlatforms(inverted)
        SoundEffectEngine.playSelectApplet()
        syncViewsWithState()
    }

    override fun onPickRomDirectoryRequested() {
        SoundEffectEngine.playSelectApplet()
        lowerScreenView.openSettingsPanel(initialTab = 3)
        lowerScreenView.settingsSubPanel = LowerScreenView.SettingsSubPanel.MEDIA_LIBRARY_MANAGER
    }

    override fun onMediaLibraryToggled(path: String, enabled: Boolean) {
        repo.setMediaLibraryEnabled(path, enabled)
        invalidatePlatformGameCountsCache()
        lowerScreenView.mediaLibraries = repo.getDiscoveredMediaLibraries(forceRefresh = true)
        lowerScreenView.invalidate()
        SoundEffectEngine.playSelectApplet()
        syncViewsWithState()
        val status = if (enabled) "已开启" else "已关闭"
        Toast.makeText(dialogContext(), "游戏目录 $status", Toast.LENGTH_SHORT).show()
    }

    override fun onEnableAllMediaLibrariesRequested() {
        repo.setAllMediaLibrariesEnabled(true)
        invalidatePlatformGameCountsCache()
        lowerScreenView.mediaLibraries = repo.getDiscoveredMediaLibraries(forceRefresh = true)
        lowerScreenView.invalidate()
        SoundEffectEngine.playSelectApplet()
        syncViewsWithState()
        Toast.makeText(dialogContext(), "已全部开启所有挂载游戏目录", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableAllMediaLibrariesRequested() {
        repo.setAllMediaLibrariesEnabled(false)
        invalidatePlatformGameCountsCache()
        lowerScreenView.mediaLibraries = repo.getDiscoveredMediaLibraries(forceRefresh = true)
        lowerScreenView.invalidate()
        SoundEffectEngine.playSelectApplet()
        syncViewsWithState()
        Toast.makeText(dialogContext(), "已全部关闭所有挂载游戏目录", Toast.LENGTH_SHORT).show()
    }

    override fun onAddDirectoryRequested() {
        lowerScreenView.openRomDirectoryPicker()
    }

    override fun onGetStorageRoots(): List<File> {
        return repo.discoverStorageRoots()
    }

    override fun onCategoryManagerRequested() {
        lowerScreenView.openCategoryManager()
    }

    override fun onCreateCategoryRequested() {
        lowerScreenView.openCategoryEditor(null)
    }

    override fun onEditCategoryRequested(category: CustomCategoryItem) {
        lowerScreenView.openCategoryEditor(category)
    }

    override fun onDeleteCategoryRequested(category: CustomCategoryItem) {
        onDeleteCategory(category.id)
    }

    override fun onDeleteCategory(id: String) {
        repo.deleteCustomCategory(id)
        currentCategoryIndex = 0
        syncViewsWithState()
        SoundEffectEngine.playSelectApplet()
        Toast.makeText(this, "已删除分类", Toast.LENGTH_SHORT).show()
    }

    override fun onSelectCategory(category: CustomCategoryItem) {
        val categories = repo.getCustomCategories()
        val idx = categories.indexOfFirst { it.id == category.id }
        if (idx >= 0) {
            currentCategoryIndex = idx
            applyActiveCategoryState(animateZoom = true)
        }
    }

    override fun onSaveCategory(
        id: String?,
        name: String,
        isSeries: Boolean,
        symbol: String,
        color: Int
    ) {
        if (id != null) {
            repo.updateCustomCategory(id, name, isSeries, symbol, color)
        } else {
            repo.addCustomCategory(name, isSeries, symbol, color)
        }
        syncViewsWithState()
        SoundEffectEngine.playSelectApplet()
        Toast.makeText(this, "已保存分类「$name」", Toast.LENGTH_SHORT).show()
    }

    override fun onMoveCategoryUpRequested(index: Int) {
        val success = repo.moveCategoryUp(index)
        if (success) {
            syncViewsWithState()
        }
    }

    override fun onMoveCategoryDownRequested(index: Int) {
        val success = repo.moveCategoryDown(index)
        if (success) {
            syncViewsWithState()
        }
    }

    override fun onResetCategoriesRequested() {
        repo.resetCategoriesToDefault()
        currentCategoryIndex = 0
        syncViewsWithState()
        SoundEffectEngine.playSelectApplet()
        Toast.makeText(this, "已恢复默认排序", Toast.LENGTH_SHORT).show()
    }

    override fun onGetGamesForCategory(category: CustomCategoryItem): List<GridSlotItem> {
        val enabledPlatforms = repo.getEnabledPlatforms()
        val disabledLibs = repo.getDisabledMediaLibs()
        val hideMissing = repo.hideGamesWithoutCover
        return rootSlots.filter {
            it.type != PlatformType.EMPTY &&
                it.type != PlatformType.FOLDER &&
                it.type != PlatformType.BADGE &&
                it.type != PlatformType.ANDROID_APP &&
                !it.isDemo &&
                isPlatformAllowed(it, enabledPlatforms) &&
                !isSlotFromDisabledMediaLib(it, disabledLibs) &&
                (!hideMissing || repo.hasValidCover(it)) &&
                repo.isGameInCategory(category.id, it)
        }
    }

    override fun onAddGamesToCategoryRequested(category: CustomCategoryItem) {
        lowerScreenView.openCategoryGameManager(category)
    }

    override fun onRemoveGameFromCategoryRequested(category: CustomCategoryItem, slot: GridSlotItem) {
        repo.removeGameFromCategory(category.id, slot)
        invalidatePlatformGameCountsCache()
        syncViewsWithState()
        SoundEffectEngine.playSelectApplet()
        Toast.makeText(this, "已将「${slot.title}」移出「${category.displayName}」", Toast.LENGTH_SHORT).show()
    }

    override fun onGetCategoryGameCounts(): Map<String, Int> {
        val categories = repo.getCustomCategories()
        val counts = mutableMapOf<String, Int>()
        for (cat in categories) {
            counts[cat.id] = countGamesForCategory(cat.id)
        }
        return counts
    }

    override fun onGetAllCandidateGames(): List<GridSlotItem> {
        val enabledPlatforms = repo.getEnabledPlatforms()
        val disabledLibs = repo.getDisabledMediaLibs()
        val hideMissing = repo.hideGamesWithoutCover
        return rootSlots.filter {
            !it.isDemo &&
                it.type != PlatformType.EMPTY &&
                it.type != PlatformType.FOLDER &&
                it.type != PlatformType.BADGE &&
                it.type != PlatformType.ANDROID_APP &&
                isPlatformAllowed(it, enabledPlatforms) &&
                !isSlotFromDisabledMediaLib(it, disabledLibs) &&
                (!hideMissing || repo.hasValidCover(it))
        }.sortedWith(compareBy({ it.getEffectivePlatform() }, { it.title.lowercase() }))
    }

    override fun onIsGameInCategory(catId: String, slot: GridSlotItem): Boolean {
        return repo.isGameInCategory(catId, slot)
    }

    override fun onToggleGameCategory(catId: String, slot: GridSlotItem, add: Boolean) {
        if (add) {
            repo.addGameToCategory(catId, slot)
        } else {
            repo.removeGameFromCategory(catId, slot)
        }
        invalidatePlatformGameCountsCache()
        syncViewsWithState()
    }

    override fun onSearchCategoryGamesPrompt(
        category: CustomCategoryItem,
        currentQuery: String,
        onQuerySubmitted: (String) -> Unit
    ) {
        onQuerySubmitted(currentQuery)
    }

    override fun onDirectoryChosenForMount(path: String) {
        val ctx = this
        val f = File(path)
        val displayName = repo.generateLibraryDisplayName(f, com.gameconsole.launcher3ds.model.PlatformCatalog.detectPlatform(f))
        Toast.makeText(ctx, "正在挂载「$displayName」...", Toast.LENGTH_SHORT).show()
        // 使用独立线程立即执行挂载，绝不堵塞在 backgroundExecutor 的后台扫描队列后面
        Thread {
            try {
                val addedCount = repo.addMountedGameDirectory(path)
                val freshSlots = repo.loadRootSlots()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    rootSlots = freshSlots
                    invalidatePlatformGameCountsCache()
                    lowerScreenView.mediaLibraries = repo.getDiscoveredMediaLibraries(forceRefresh = true)
                    // 挂载后自动将分类切换到「全部游戏」(index 0)，确保挂载出的新游戏 100% 呈现在主屏上
                    currentCategoryIndex = 0
                    syncViewsWithState()
                    lowerScreenView.settingsSubPanel = LowerScreenView.SettingsSubPanel.MEDIA_LIBRARY_MANAGER
                    lowerScreenView.invalidate()
                    SoundEffectEngine.playSelectApplet()
                    val totalGames = rootSlots.count { !it.isDemo && it.type != PlatformType.EMPTY }
                    val hideMsg = if (repo.hideGamesWithoutCover) "（当前已开启隐藏无封面模式）" else ""
                    Toast.makeText(ctx, "已成功挂载「$displayName」！本次载入/更新 $addedCount 款游戏（卡槽当前共有 $totalGames 款）$hideMsg", Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                android.util.Log.e("Launcher3DS", "Failed to mount directory: $path", e)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    lowerScreenView.settingsSubPanel = LowerScreenView.SettingsSubPanel.MEDIA_LIBRARY_MANAGER
                    lowerScreenView.invalidate()
                    Toast.makeText(ctx, "挂载出错: ${e.localizedMessage ?: "未知错误"}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onRemoveMediaLibraryRequested(path: String) {
        val f = File(path)
        val displayName = repo.generateLibraryDisplayName(f, com.gameconsole.launcher3ds.model.PlatformCatalog.detectPlatform(f))
        repo.removeMountedGameDirectory(path)
        rootSlots = repo.loadRootSlots()
        invalidatePlatformGameCountsCache()
        lowerScreenView.mediaLibraries = repo.getDiscoveredMediaLibraries(forceRefresh = true)
        syncViewsWithState()
        Toast.makeText(this, "已移除「$displayName」及对应卡槽游戏", Toast.LENGTH_SHORT).show()
    }

    override fun onRemoveAllMediaLibrariesRequested() {
        repo.removeAllMountedGameDirectories()
        rootSlots = repo.loadRootSlots()
        invalidatePlatformGameCountsCache()
        lowerScreenView.mediaLibraries = repo.getDiscoveredMediaLibraries(forceRefresh = true)
        syncViewsWithState()
        Toast.makeText(this, "已全部移除所有挂载目录，桌面卡槽已重置", Toast.LENGTH_SHORT).show()
    }

    override fun onToggleHideGamesWithoutCoverRequested() {
        val newVal = !repo.hideGamesWithoutCover
        repo.hideGamesWithoutCover = newVal
        invalidatePlatformGameCountsCache()
        lowerScreenView.hideGamesWithoutCover = newVal
        SoundEffectEngine.playSelectApplet()
        syncViewsWithState()
        val msg = if (newVal) "已开启：仅显示拥有封面的游戏" else "已关闭：显示全部游戏（包含无封面ROM）"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showFolderPickerDialog() {
        val ctx = dialogContext()
        val roots = repo.discoverStorageRoots()
        val validRoots = if (roots.isNotEmpty()) roots else listOfNotNull(Environment.getExternalStorageDirectory())
        var currentDir: File = validRoots.firstOrNull { repo.isTfCard(it) } ?: validRoots.firstOrNull() ?: File("/storage/emulated/0")

        val validRomExtensions = setOf(
            "3ds", "cci", "cia", "cxi", "nds", "gba", "gbc", "gb",
            "nes", "unf", "fds", "sfc", "smc", "md", "gen", "bin",
            "iso", "cso", "chd", "cue", "pbp", "cdi", "gdi", "pce", "ws", "wsc", "zip", "7z"
        )

        var dialog: AlertDialog? = null

        val rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val storageRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        rootLayout.addView(storageRow)

        val pathCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val pathTv = TextView(ctx).apply {
            textSize = 13.5f
            setTextColor(Color.parseColor("#1E293B"))
            setTypeface(null, Typeface.BOLD)
        }
        val romInfoTv = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#10B981"))
            setPadding(0, dp(3), 0, 0)
        }
        pathCard.addView(pathTv)
        pathCard.addView(romInfoTv)
        rootLayout.addView(pathCard)

        val listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(280)
            )
            addView(listContainer)
        }
        rootLayout.addView(scrollView)

        fun updateView() {
            storageRow.removeAllViews()
            for (r in validRoots) {
                val isTf = repo.isTfCard(r)
                val label = if (isTf) "🎴 TF卡 (${r.name})" else "📱 内部存储"
                val isCur = currentDir.absolutePath.startsWith(r.absolutePath)
                val btn = Button(ctx).apply {
                    text = label
                    textSize = 11.5f
                    setTextColor(if (isCur) Color.WHITE else Color.parseColor("#334155"))
                    setBackgroundColor(if (isCur) Color.parseColor("#0284C7") else Color.parseColor("#E2E8F0"))
                    val lp = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                        setMargins(dp(2), 0, dp(2), 0)
                    }
                    layoutParams = lp
                    setOnClickListener {
                        currentDir = r
                        updateView()
                    }
                }
                storageRow.addView(btn)
            }

            pathTv.text = "📂 当前浏览: " + currentDir.absolutePath
            val localFiles = currentDir.listFiles() ?: emptyArray()
            val romCount = localFiles.count { it.isFile && validRomExtensions.contains(it.extension.lowercase()) }
            if (romCount > 0) {
                romInfoTv.visibility = View.VISIBLE
                romInfoTv.text = "✨ 当前文件夹包含 $romCount 款游戏 ROM 文件！可点击下方按钮直接挂载"
            } else {
                romInfoTv.visibility = View.GONE
            }

            listContainer.removeAllViews()

            val parent = currentDir.parentFile
            val isAtRoot = validRoots.any { it.absolutePath == currentDir.absolutePath }
            if (parent != null && !isAtRoot) {
                val upItem = TextView(ctx).apply {
                    text = "⬆️ [返回上一级] .. (${parent.name.ifBlank { "根目录" }})"
                    textSize = 13.5f
                    setTextColor(Color.parseColor("#2563EB"))
                    setTypeface(null, Typeface.BOLD)
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    setBackgroundColor(Color.parseColor("#F8FAFC"))
                    setOnClickListener {
                        currentDir = parent
                        updateView()
                    }
                }
                listContainer.addView(upItem)
            }

            val subDirs = localFiles.filter { it.isDirectory && !it.name.startsWith(".") && !it.name.equals("Android", true) }
                .sortedWith(compareBy { it.name.lowercase() })

            if (subDirs.isEmpty() && isAtRoot) {
                val emptyTv = TextView(ctx).apply {
                    text = "（此根目录下无可见子文件夹）"
                    textSize = 12.5f
                    setTextColor(Color.parseColor("#94A3B8"))
                    setPadding(dp(12), dp(16), dp(12), dp(16))
                }
                listContainer.addView(emptyTv)
            }

            for (sub in subDirs) {
                val subRoms = sub.listFiles()?.count { it.isFile && validRomExtensions.contains(it.extension.lowercase()) } ?: 0
                val subItem = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    setBackgroundColor(Color.TRANSPARENT)
                    val tv = TextView(ctx).apply {
                        val romTag = if (subRoms > 0) " (🎮 $subRoms 款游戏)" else ""
                        text = "📁 " + sub.name + romTag
                        textSize = 13.5f
                        setTextColor(if (subRoms > 0) Color.parseColor("#059669") else Color.parseColor("#1E293B"))
                        if (subRoms > 0) setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val arrow = TextView(ctx).apply {
                        text = "进入 ▶"
                        textSize = 12f
                        setTextColor(Color.parseColor("#64748B"))
                    }
                    addView(tv)
                    addView(arrow)
                    setOnClickListener {
                        currentDir = sub
                        updateView()
                    }
                }
                listContainer.addView(subItem)
            }
        }

        updateView()

        dialog = AlertDialog.Builder(ctx)
            .setTitle("📁 手动选择游戏文件夹挂载")
            .setView(rootLayout)
            .setPositiveButton("✔ 确认挂载此文件夹") { _, _ ->
                val chosenPath = currentDir.absolutePath
                backgroundExecutor.execute {
                    val addedCount = repo.addMountedGameDirectory(chosenPath)
                    repo.rematchAllMissingCovers()
                    val freshSlots = repo.loadRootSlots()
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        rootSlots = freshSlots
                        invalidatePlatformGameCountsCache()
                        lowerScreenView.mediaLibraries = repo.getDiscoveredMediaLibraries()
                        syncViewsWithState()
                        Toast.makeText(ctx, "已成功挂载目录！载入 $addedCount 款游戏（已自动模糊补齐封面与元数据）", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private fun showRemoveSingleDirectoryConfirmDialog(path: String) {
        val ctx = dialogContext()
        val f = File(path)
        val displayName = repo.generateLibraryDisplayName(f, com.gameconsole.launcher3ds.model.PlatformCatalog.detectPlatform(f))
        AlertDialog.Builder(ctx)
            .setTitle("🗑️ 移除挂载目录")
            .setMessage("确定要从桌面移除目录「$displayName」吗？\n\n路径：$path\n\n该目录下的所有游戏将从桌面卡槽中清除（不会删除存储卡中的物理 ROM 文件）。")
            .setPositiveButton("确认移除") { _, _ ->
                repo.removeMountedGameDirectory(path)
                invalidatePlatformGameCountsCache()
                lowerScreenView.mediaLibraries = repo.getDiscoveredMediaLibraries()
                syncViewsWithState()
                Toast.makeText(ctx, "已移除「$displayName」及对应卡槽游戏", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRemoveAllDirectoriesConfirmDialog() {
        val ctx = dialogContext()
        val count = repo.getMountedGameDirectories().size
        AlertDialog.Builder(ctx)
            .setTitle("⚠️ 全部移除所有挂载目录")
            .setMessage("确定要全部移除当前所有挂载的游戏目录（共 $count 个）吗？\n\n移除后，桌面所有已载入的游戏卡槽将被清空并恢复为纯净空卡槽。\n（不会删除 TF 卡或内置存储中的任何物理 ROM 文件）")
            .setPositiveButton("全部移除") { _, _ ->
                repo.removeAllMountedGameDirectories()
                invalidatePlatformGameCountsCache()
                lowerScreenView.mediaLibraries = repo.getDiscoveredMediaLibraries()
                syncViewsWithState()
                Toast.makeText(ctx, "已全部移除所有挂载目录，桌面卡槽已重置", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onSelect3dsEmulator(pkg: String) {
        repo.default3dsEmulatorPkg = pkg
        repo.setPlatformEmulator("3DS", pkg)
        StartupProfiler.detected3dsEmulatorPkg = pkg
        StartupProfiler.detected3dsEmulatorLabel = queryAppLabel(pkg).ifBlank { "Citra" }
        StartupProfiler.detected3dsEmulatorInstalled = true
        syncViewsWithState()
    }

    override fun onSelectNdsEmulator(pkg: String) {
        repo.defaultNdsEmulatorPkg = pkg
        repo.setPlatformEmulator("NDS", pkg)
        syncViewsWithState()
    }

    override fun onPickCustomInstalledAppRequested(platform: PlatformType) {
        showCustomInstalledAppPicker { chosenPkg, appLabel ->
            if (platform == PlatformType.N3DS) {
                onSelect3dsEmulator(chosenPkg)
            } else {
                onSelectNdsEmulator(chosenPkg)
            }
            android.widget.Toast.makeText(dialogContext(), "已成功绑定模拟器：$appLabel", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCycle3dsEmulatorRequested() {
        SoundEffectEngine.playSelectApplet()
        val list = EmulatorLauncher.getAvailableEmulators(this, PlatformType.N3DS)
        if (list.isEmpty()) {
            Toast.makeText(dialogContext(), "未检测到已安装的 3DS 模拟器", Toast.LENGTH_SHORT).show()
            return
        }
        val curPkg = repo.default3dsEmulatorPkg
        val curIdx = list.indexOfFirst { it.packageName == curPkg }
        val nextIdx = (curIdx + 1) % list.size
        val nextEmu = list[nextIdx]
        repo.default3dsEmulatorPkg = nextEmu.packageName
        syncViewsWithState()
        Toast.makeText(dialogContext(), "3DS 模拟器已切换为：${nextEmu.name}", Toast.LENGTH_SHORT).show()
    }

    override fun onCycleNdsEmulatorRequested() {
        SoundEffectEngine.playSelectApplet()
        val list = EmulatorLauncher.getAvailableEmulators(this, PlatformType.NDS)
        if (list.isEmpty()) {
            Toast.makeText(dialogContext(), "未检测到已安装的 NDS 模拟器", Toast.LENGTH_SHORT).show()
            return
        }
        val curPkg = repo.defaultNdsEmulatorPkg
        val curIdx = list.indexOfFirst { it.packageName == curPkg }
        val nextIdx = (curIdx + 1) % list.size
        val nextEmu = list[nextIdx]
        repo.defaultNdsEmulatorPkg = nextEmu.packageName
        syncViewsWithState()
        Toast.makeText(dialogContext(), "NDS 模拟器已切换为：${nextEmu.name}", Toast.LENGTH_SHORT).show()
    }

    private fun startAutoRandomPlayback() {
        isAutoRandomRunning = true
        lowerScreenView.isAutoRandomPlaying = true
        autoRandomHandler.removeCallbacks(autoRandomRunnable)
        autoRandomHandler.postDelayed(autoRandomRunnable, 1000L)
    }

    private fun stopAutoRandomPlayback() {
        isAutoRandomRunning = false
        lowerScreenView.isAutoRandomPlaying = false
        autoRandomHandler.removeCallbacks(autoRandomRunnable)
    }

    private fun performAutoRandomSelect() {
        val currentSlots = getCurrentDisplayedSlots()
        val validIndices = currentSlots.indices.filter { idx ->
            val slot = currentSlots[idx]
            slot.type != PlatformType.EMPTY &&
                slot.type != PlatformType.BADGE &&
                slot.type != PlatformType.FOLDER &&
                slot.type != PlatformType.ANDROID_APP &&
                !slot.isDemo
        }
        if (validIndices.isEmpty()) return

        // 随机选择一个与当前不同的卡槽
        val candidates = if (validIndices.size > 1) {
            validIndices.filter { it != lowerScreenView.selectedIndex }
        } else {
            validIndices
        }
        val chosenIndex = candidates.random()
        lowerScreenView.selectedIndex = chosenIndex
        val chosenSlot = currentSlots[chosenIndex]
        onSlotSelected(chosenSlot, chosenIndex)
    }

    override fun onZoomChanged(newRows: Int, zoomIn: Boolean) {
        repo.gridRows = newRows
        SoundEffectEngine.playZoomChange(zoomIn)
    }

    override fun onSlotsSwappedOrMoved(fromIndex: Int, toIndex: Int) {
        SoundEffectEngine.playFolderOpen()
        val activeCategory = GameCategory.entries.getOrElse(currentCategoryIndex) { GameCategory.ALL }
        if (currentOpenedFolder == null && activeCategory == GameCategory.ALL) {
            val fromItem = rootSlots.getOrNull(fromIndex) ?: return
            val toItem = rootSlots.getOrNull(toIndex) ?: return

            if (toItem.type == PlatformType.FOLDER && fromItem.type != PlatformType.FOLDER && fromItem.type != PlatformType.EMPTY) {
                val existingInFolder = folderItems.count { it.parentFolderId == toItem.id }
                val moved = fromItem.copy(parentFolderId = toItem.id, slotIndex = existingInFolder)
                folderItems.add(moved)
                repo.saveFolderItems(folderItems)
                rootSlots[fromIndex] = GridSlotItem(id = "empty_$fromIndex", slotIndex = fromIndex, type = PlatformType.EMPTY)
                repo.saveRootSlots(rootSlots)
                syncViewsWithState()
                Toast.makeText(dialogContext(), "已将「${fromItem.title}」放入文件夹「${toItem.title}」", Toast.LENGTH_SHORT).show()
                return
            }

            rootSlots[fromIndex] = toItem.copy(slotIndex = fromIndex)
            rootSlots[toIndex] = fromItem.copy(slotIndex = toIndex)
            repo.saveRootSlots(rootSlots)
            syncViewsWithState()
        } else if (currentOpenedFolder != null) {
            val displayed = getCurrentDisplayedSlots()
            val fromItem = displayed.getOrNull(fromIndex) ?: return
            val toItem = displayed.getOrNull(toIndex) ?: return
            displayed[fromIndex] = toItem.copy(slotIndex = fromIndex)
            displayed[toIndex] = fromItem.copy(slotIndex = toIndex)
            val folderId = currentOpenedFolder!!.id
            folderItems.removeAll { it.parentFolderId == folderId }
            folderItems.addAll(displayed.filter { it.type != PlatformType.EMPTY }.map { it.copy(parentFolderId = folderId) })
            repo.saveFolderItems(folderItems)
            syncViewsWithState()
        } else {
            // 在分类过滤视图下交换两个游戏的顺序：直接对调它们在 rootSlots 中的位置
            val displayed = getCurrentDisplayedSlots()
            val fromItem = displayed.getOrNull(fromIndex) ?: return
            val toItem = displayed.getOrNull(toIndex) ?: return
            val rootFromIdx = rootSlots.indexOfFirst { it.id == fromItem.id }
            val rootToIdx = rootSlots.indexOfFirst { it.id == toItem.id }
            if (rootFromIdx >= 0 && rootToIdx >= 0) {
                val a = rootSlots[rootFromIdx]
                val b = rootSlots[rootToIdx]
                rootSlots[rootFromIdx] = b.copy(slotIndex = rootFromIdx)
                rootSlots[rootToIdx] = a.copy(slotIndex = rootToIdx)
                repo.saveRootSlots(rootSlots)
                syncViewsWithState()
            }
        }
    }

    // ==================== 实体掌机手柄按键映射 (L1/R1 切分类, L2/R2 切顶部图标) ====================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleLauncherKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (handleLauncherMotionEvent(event)) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun handleLauncherMotionEvent(event: MotionEvent): Boolean {
        if (activeAppletDialog?.isShowing == true) return false
        if (event.action == MotionEvent.ACTION_MOVE) {
            val l2Axis = maxOf(
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE)
            )
            val r2Axis = maxOf(
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS)
            )

            if (l2Axis > 0.55f && !l2TriggerPressed) {
                l2TriggerPressed = true
                lowerScreenView.stepTopBarIcon(-1)
                return true
            } else if (l2Axis < 0.25f) {
                l2TriggerPressed = false
            }

            if (r2Axis > 0.55f && !r2TriggerPressed) {
                r2TriggerPressed = true
                lowerScreenView.stepTopBarIcon(1)
                return true
            } else if (r2Axis < 0.25f) {
                r2TriggerPressed = false
            }

            // 右摇杆 (Right Stick / C-Stick) 控制上屏 3D 卡带/包装盒 360° 自由旋转把玩
            val rx = event.getAxisValue(MotionEvent.AXIS_Z).let { if (kotlin.math.abs(it) > 0.15f) it else event.getAxisValue(MotionEvent.AXIS_RX) }
            val ry = event.getAxisValue(MotionEvent.AXIS_RZ).let { if (kotlin.math.abs(it) > 0.15f) it else event.getAxisValue(MotionEvent.AXIS_RY) }
            if (kotlin.math.abs(rx) > 0.16f || kotlin.math.abs(ry) > 0.16f) {
                upperScreenView.rotateBoxartByStick(rx * 4.2f, -ry * 2.8f)
            }
        }
        return false
    }

    private fun handleLauncherKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && activeAppletDialog?.isShowing != true) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    lowerScreenView.moveCursorByDpad(0, -1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (lowerScreenView.selectedTopIconIndex >= 0) {
                        lowerScreenView.selectedTopIconIndex = -1
                        upperScreenView.activeApplet = SystemAppletType.NONE
                        SoundEffectEngine.playCursorMove()
                        return true
                    }
                    lowerScreenView.moveCursorByDpad(0, 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (lowerScreenView.selectedTopIconIndex >= 0) {
                        lowerScreenView.stepTopBarIcon(-1)
                        return true
                    }
                    lowerScreenView.moveCursorByDpad(-1, 0)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (lowerScreenView.selectedTopIconIndex >= 0) {
                        lowerScreenView.stepTopBarIcon(1)
                        return true
                    }
                    lowerScreenView.moveCursorByDpad(1, 0)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (lowerScreenView.isSettingsPanelOpen) {
                        lowerScreenView.activateFocusedSettingsCard()
                        return true
                    }
                    if (lowerScreenView.isGameDescriptionOpen) {
                        val list = getCurrentDisplayedSlots()
                        val idx = lowerScreenView.selectedIndex
                        list.getOrNull(idx)?.let { onSlotOpenRequested(it, idx) }
                        return true
                    }
                    if (lowerScreenView.selectedTopIconIndex >= 0) {
                        lowerScreenView.activateFocusedTopIcon()
                        return true
                    }
                    val list = getCurrentDisplayedSlots()
                    val idx = lowerScreenView.selectedIndex
                    list.getOrNull(idx)?.let { onSlotOpenRequested(it, idx) }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                    if (upperScreenView.exitVideoFullscreen()) {
                        return true
                    }
                    if (lowerScreenView.handleBackPressed()) {
                        return true
                    }
                    if (lowerScreenView.selectedTopIconIndex >= 0) {
                        lowerScreenView.selectedTopIconIndex = -1
                        upperScreenView.activeApplet = SystemAppletType.NONE
                        SoundEffectEngine.playBackCancel()
                        return true
                    }
                    if (lowerScreenView.movingSourceIndex >= 0) {
                        lowerScreenView.movingSourceIndex = -1
                        SoundEffectEngine.playBackCancel()
                        return true
                    }
                    if (currentOpenedFolder != null) {
                        currentOpenedFolder = null
                        lowerScreenView.selectedIndex = 0
                        SoundEffectEngine.playBackCancel()
                        syncViewsWithState()
                        return true
                    }
                    if (upperScreenView.activeApplet != SystemAppletType.NONE) {
                        upperScreenView.activeApplet = SystemAppletType.NONE
                        SoundEffectEngine.playBackCancel()
                        return true
                    }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_X -> {
                    val list = getCurrentDisplayedSlots()
                    val idx = lowerScreenView.selectedIndex
                    list.getOrNull(idx)?.let { onSlotManualOrConfigRequested(it, idx) }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_Y -> {
                    val list = getCurrentDisplayedSlots()
                    val idx = lowerScreenView.selectedIndex
                    list.getOrNull(idx)?.let { onRightActionRequested(it, idx) }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_L1 -> {
                    if (lowerScreenView.isSettingsPanelOpen) {
                        lowerScreenView.handleSettingsActionLeft()
                        return true
                    }
                    onCategoryStepRequested(-1)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_R1 -> {
                    if (lowerScreenView.isSettingsPanelOpen) {
                        lowerScreenView.handleSettingsActionRight()
                        return true
                    }
                    onCategoryStepRequested(1)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_L2 -> {
                    if (event.repeatCount == 0) {
                        lowerScreenView.stepTopBarIcon(-1)
                    }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_R2 -> {
                    if (event.repeatCount == 0) {
                        lowerScreenView.stepTopBarIcon(1)
                    }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_SELECT -> {
                    onVideoAudioToggleRequested()
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> {
                    onAppletClicked(SystemAppletType.HOME_SETTINGS)
                    return true
                }
            }
        }
        return false
    }

    // ==================== 1. HOME 菜单设置抽屉 ====================

    private fun showHomeMenuSettingsDialog() {
        val ctx = dialogContext()
        val scroll = ScrollView(ctx)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }
        scroll.addView(container)

        addSectionHeader(ctx, container, "🎨 1. 更换 3DS 主题 (实时双屏生效)")
        val themeRow = HorizontalScrollView(ctx)
        val themeLinear = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        themeRow.addView(themeLinear)
        for (theme in ThemeCatalog.ALL_THEMES) {
            val isCurrent = (theme.id == repo.themeId)
            val btn = createStyledButton(
                ctx = ctx,
                text = if (isCurrent) "✓ ${theme.name}" else theme.name,
                bgColor = theme.upperSkyTop,
                textColor = theme.textPrimaryColor
            ).apply {
                setOnClickListener {
                    repo.themeId = theme.id
                    SoundEffectEngine.playSelectApplet()
                    SoundEffectEngine.updateTheme(theme, repo.customBgmPath)
                    syncViewsWithState()
                    activeAppletDialog?.dismiss()
                    showHomeMenuSettingsDialog()
                }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply {
                rightMargin = dp(8)
            }
            themeLinear.addView(btn, lp)
        }
        container.addView(themeRow)

        addSectionHeader(ctx, container, "📺 2. 双屏掌机显示与上下屏映射")
        val secondary = findSecondaryDisplay()
        val statusText = TextView(ctx).apply {
            val secDesc = if (secondary != null) "已连接物理副屏 (ID=${secondary.displayId})" else "未检测到副屏 (单屏同显模式)"
            text = "硬件检测：$secDesc  |  当前映射：${if (repo.swapScreens) "模式 B (已互换)" else "模式 A (上屏=3D舞台 / 下屏=触控网格)"}"
            setTextColor(Color.parseColor("#4A5568"))
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        }
        container.addView(statusText)

        val screenBtnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val swapBtn = createStyledButton(
            ctx = ctx,
            text = "⇅ 一键互换上/下屏映射",
            bgColor = Color.parseColor("#00A8E8"),
            textColor = Color.WHITE
        ).apply {
            setOnClickListener {
                activeAppletDialog?.dismiss()
                toggleSwapScreensQuickly()
            }
        }
        val splitToggleBtn = createStyledButton(
            ctx = ctx,
            text = if (repo.splitPreviewOnSingleScreen) "单屏合并预览：开启" else "真双屏独立显示：开启",
            bgColor = Color.parseColor("#2B343D"),
            textColor = Color.WHITE
        ).apply {
            setOnClickListener {
                repo.splitPreviewOnSingleScreen = !repo.splitPreviewOnSingleScreen
                SoundEffectEngine.playSelectApplet()
                activeAppletDialog?.dismiss()
                setupDualScreenLayout()
            }
        }
        screenBtnRow.addView(swapBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(6) })
        screenBtnRow.addView(splitToggleBtn, LinearLayout.LayoutParams(0, dp(42), 1f))
        container.addView(screenBtnRow)

        addSectionHeader(ctx, container, "🔍 3. 扫描 3DS / NDS 游戏 ROM 与添加安卓应用")
        val romBtnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val scanBtn = createStyledButton(
            ctx = ctx,
            text = "📂 扫描本地 ROM 目录",
            bgColor = Color.parseColor("#18B858"),
            textColor = Color.WHITE
        ).apply {
            setOnClickListener {
                activeAppletDialog?.dismiss()
                showScanOrAddDialog(targetSlotIndex = -1)
            }
        }
        val addAppBtn = createStyledButton(
            ctx = ctx,
            text = "📱 添加掌机已安装 App",
            bgColor = Color.parseColor("#F58220"),
            textColor = Color.WHITE
        ).apply {
            setOnClickListener {
                activeAppletDialog?.dismiss()
                showAddAndroidAppDialog(targetSlotIndex = -1)
            }
        }
        romBtnRow.addView(scanBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(6) })
        romBtnRow.addView(addAppBtn, LinearLayout.LayoutParams(0, dp(42), 1f))
        container.addView(romBtnRow)

        addSectionHeader(ctx, container, "🎮 4. 绑定默认 3DS / NDS 模拟器")
        val emuRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val bind3dsBtn = createStyledButton(
            ctx = ctx,
            text = "配置 3DS 模拟器 (Citra/Azahar)",
            bgColor = Color.parseColor("#E60012"),
            textColor = Color.WHITE
        ).apply {
            setOnClickListener {
                showDefaultEmulatorPicker(PlatformType.N3DS)
            }
        }
        val bindNdsBtn = createStyledButton(
            ctx = ctx,
            text = "配置 NDS 模拟器 (DraStic/melonDS)",
            bgColor = Color.parseColor("#3B4856"),
            textColor = Color.WHITE
        ).apply {
            setOnClickListener {
                showDefaultEmulatorPicker(PlatformType.NDS)
            }
        }
        emuRow.addView(bind3dsBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(6) })
        emuRow.addView(bindNdsBtn, LinearLayout.LayoutParams(0, dp(42), 1f))
        container.addView(emuRow)

        addSectionHeader(ctx, container, "🔊 5. 3DS 音效音量、背景音乐与系统设置")
        val sfxVolLabel = TextView(ctx).apply {
            text = "音效音量调节 (当前: " + repo.sfxVolume + "%)："
            setTextColor(Color.parseColor("#4A5568"))
            textSize = 13f
            setPadding(0, dp(2), 0, dp(4))
        }
        container.addView(sfxVolLabel)

        val sfxRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val sfxLevels = listOf(100 to "100%(默认)", 75 to "75%", 50 to "50%", 25 to "25%", 0 to "🔇静音")
        for ((vol, label) in sfxLevels) {
            val isCurrent = (repo.sfxVolume == vol)
            val btn = createStyledButton(
                ctx = ctx,
                text = if (isCurrent) "✓ " + label else label,
                bgColor = if (isCurrent) Color.parseColor("#F59E0B") else Color.parseColor("#CBD5E1"),
                textColor = if (isCurrent) Color.WHITE else Color.parseColor("#1E293B")
            ).apply {
                setOnClickListener {
                    repo.sfxVolume = vol
                    repo.sfxEnabled = (vol > 0)
                    SoundEffectEngine.sfxVolume = vol / 100f
                    lowerScreenView.sfxVolumePercent = vol
                    lowerScreenView.isSfxEnabled = (vol > 0)
                    if (vol > 0) SoundEffectEngine.playCursorMove()
                    activeAppletDialog?.dismiss()
                    showHomeMenuSettingsDialog()
                }
            }
            sfxRow.addView(btn, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(4) })
        }
        container.addView(sfxRow)

        val audioRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val bgmBtn = createStyledButton(
            ctx = ctx,
            text = if (repo.bgmEnabled) "🎵 背景音乐：开启" else "🔇 背景音乐：关闭",
            bgColor = Color.parseColor("#7B2CBF"),
            textColor = Color.WHITE
        ).apply {
            setOnClickListener {
                repo.bgmEnabled = !repo.bgmEnabled
                SoundEffectEngine.bgmEnabled = repo.bgmEnabled
                if (repo.bgmEnabled) SoundEffectEngine.startBgm() else SoundEffectEngine.stopBgm()
                activeAppletDialog?.dismiss()
                showHomeMenuSettingsDialog()
            }
        }
        val upperModeBtn = createStyledButton(
            ctx = ctx,
            text = repo.upperDisplayMode.title,
            bgColor = Color.parseColor(repo.upperDisplayMode.colorHex),
            textColor = Color.WHITE
        ).apply {
            setOnClickListener {
                repo.cycleNextUpperDisplayMode()
                upperScreenView.upperDisplayMode = repo.upperDisplayMode
                upperScreenView.showUpperBoxart = repo.showUpperBoxart
                upperScreenView.showUpperLogo = repo.showUpperLogo
                lowerScreenView.upperDisplayMode = repo.upperDisplayMode
                lowerScreenView.showUpperBoxart = repo.showUpperBoxart
                lowerScreenView.showUpperLogo = repo.showUpperLogo
                SoundEffectEngine.playSelectApplet()
                activeAppletDialog?.dismiss()
                showHomeMenuSettingsDialog()
            }
        }
        val homeDefaultBtn = createStyledButton(
            ctx = ctx,
            text = "🏠 设为默认桌面",
            bgColor = Color.parseColor("#5C6B73"),
            textColor = Color.WHITE
        ).apply {
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(ctx, "请在系统设置 -> 默认应用中将「3DS HOME 双屏桌面」设为默认主屏幕", Toast.LENGTH_LONG).show()
                }
            }
        }
        audioRow.addView(bgmBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(4) })
        audioRow.addView(upperModeBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(4) })
        audioRow.addView(homeDefaultBtn, LinearLayout.LayoutParams(0, dp(42), 1f))
        container.addView(audioRow)

        addSectionHeader(ctx, container, "✨ 6. 上屏展示方案 (6款预设与独立微调)")
        val curDisplayMode = repo.upperDisplayMode
        val modeDescText = TextView(ctx).apply {
            text = "当前方案：${curDisplayMode.title}  (${curDisplayMode.desc})"
            setTextColor(Color.parseColor(curDisplayMode.colorHex))
            textSize = 13f
            setPadding(0, dp(2), 0, dp(6))
        }
        container.addView(modeDescText)

        val presetScroll = HorizontalScrollView(ctx)
        val presetModeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        presetScroll.addView(presetModeRow)
        for (m in UpperDisplayMode.entries) {
            val isCurrent = (m == curDisplayMode)
            val btn = createStyledButton(
                ctx = ctx,
                text = if (isCurrent) "✓ ${m.title}" else m.shortLabel,
                bgColor = if (isCurrent) Color.parseColor(m.colorHex) else Color.parseColor("#CBD5E1"),
                textColor = if (isCurrent) Color.WHITE else Color.parseColor("#1E293B")
            ).apply {
                setOnClickListener {
                    repo.upperDisplayMode = m
                    upperScreenView.upperDisplayMode = repo.upperDisplayMode
                    upperScreenView.showUpperBoxart = repo.showUpperBoxart
                    upperScreenView.showUpperLogo = repo.showUpperLogo
                    lowerScreenView.upperDisplayMode = repo.upperDisplayMode
                    lowerScreenView.showUpperBoxart = repo.showUpperBoxart
                    lowerScreenView.showUpperLogo = repo.showUpperLogo
                    SoundEffectEngine.playSelectApplet()
                    activeAppletDialog?.dismiss()
                    showHomeMenuSettingsDialog()
                }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                rightMargin = dp(6)
            }
            presetModeRow.addView(btn, lp)
        }
        container.addView(presetScroll)

        val toggleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val boxartToggleBtn = createStyledButton(
            ctx = ctx,
            text = if (repo.showUpperBoxart) "📦 3D卡盒：开启" else "📦 3D卡盒：隐藏",
            bgColor = if (repo.showUpperBoxart) Color.parseColor("#0284C7") else Color.parseColor("#64748B"),
            textColor = Color.WHITE
        ).apply {
            setOnClickListener {
                repo.showUpperBoxart = !repo.showUpperBoxart
                upperScreenView.showUpperBoxart = repo.showUpperBoxart
                lowerScreenView.showUpperBoxart = repo.showUpperBoxart
                SoundEffectEngine.playSelectApplet()
                activeAppletDialog?.dismiss()
                showHomeMenuSettingsDialog()
            }
        }
        val logoToggleBtn = createStyledButton(
            ctx = ctx,
            text = if (repo.showUpperLogo) "🏷️ 游戏Logo：开启" else "🏷️ 游戏Logo：隐藏",
            bgColor = if (repo.showUpperLogo) Color.parseColor("#10B981") else Color.parseColor("#64748B"),
            textColor = Color.WHITE
        ).apply {
            setOnClickListener {
                repo.showUpperLogo = !repo.showUpperLogo
                upperScreenView.showUpperLogo = repo.showUpperLogo
                lowerScreenView.showUpperLogo = repo.showUpperLogo
                SoundEffectEngine.playSelectApplet()
                activeAppletDialog?.dismiss()
                showHomeMenuSettingsDialog()
            }
        }
        toggleRow.addView(boxartToggleBtn, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(6) })
        toggleRow.addView(logoToggleBtn, LinearLayout.LayoutParams(0, dp(38), 1f))
        container.addView(toggleRow)

        activeAppletDialog = AlertDialog.Builder(ctx)
            .setTitle("🏠 HOME 菜单设置 (Nintendo 3DS)")
            .setView(scroll)
            .setPositiveButton("返回桌面 (B)") { d, _ ->
                SoundEffectEngine.playBackCancel()
                upperScreenView.activeApplet = SystemAppletType.NONE
                d.dismiss()
            }
            .setOnCancelListener {
                upperScreenView.activeApplet = SystemAppletType.NONE
            }
            .create()
        activeAppletDialog?.show()
    }

    // ==================== 2. 游戏笔记 (Game Notes) ====================

    private fun showGameNotesDialog() {
        val ctx = dialogContext()
        val currentSlot = getCurrentDisplayedSlots().getOrNull(lowerScreenView.selectedIndex)
        val gameId = currentSlot?.id?.takeIf { currentSlot.type != PlatformType.EMPTY } ?: "global_3ds_note"
        val gameTitle = currentSlot?.title?.takeIf { it.isNotBlank() } ?: "掌机全局游戏笔记"
        val noteData = repo.loadGameNote(gameId, gameTitle)
        upperScreenView.activeGameNote = noteData
        upperScreenView.invalidate()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.parseColor("#F4EFE6"))
        }

        val memoInput = EditText(ctx).apply {
            hint = "在此输入攻略要点、迷宫走法或密码（上屏将同步展示）..."
            setText(noteData.textMemo)
            textSize = 14f
            maxLines = 2
        }
        root.addView(memoInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        lateinit var canvasView: GameNotesCanvasView
        canvasView = GameNotesCanvasView(ctx, noteData.strokes) {
            noteData.textMemo = memoInput.text.toString()
            upperScreenView.activeGameNote = noteData
            upperScreenView.invalidate()
        }

        val toolBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val blackBtn = createStyledButton(ctx, "✒️ 黑笔", Color.parseColor("#1A1A1A"), Color.WHITE).apply {
            setOnClickListener { canvasView.currentInkColorHex = "#1A1A1A" }
        }
        val redBtn = createStyledButton(ctx, "🖍️ 红笔", Color.parseColor("#E60012"), Color.WHITE).apply {
            setOnClickListener { canvasView.currentInkColorHex = "#E60012" }
        }
        val blueBtn = createStyledButton(ctx, "🖌️ 蓝笔", Color.parseColor("#0077CC"), Color.WHITE).apply {
            setOnClickListener { canvasView.currentInkColorHex = "#0077CC" }
        }
        val undoBtn = createStyledButton(ctx, "↩ 撤销", Color.parseColor("#6C757D"), Color.WHITE).apply {
            setOnClickListener {
                if (noteData.strokes.isNotEmpty()) {
                    noteData.strokes.removeLast()
                    canvasView.invalidate()
                    upperScreenView.invalidate()
                }
            }
        }
        val clearBtn = createStyledButton(ctx, "🗑 清空", Color.parseColor("#B02A37"), Color.WHITE).apply {
            setOnClickListener {
                noteData.strokes.clear()
                canvasView.invalidate()
                upperScreenView.invalidate()
            }
        }
        for (b in listOf(blackBtn, redBtn, blueBtn, undoBtn, clearBtn)) {
            toolBar.addView(b, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(4) })
        }
        root.addView(toolBar)
        root.addView(canvasView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))

        activeAppletDialog = AlertDialog.Builder(ctx)
            .setTitle("✏️ 游戏笔记 — $gameTitle (上屏实时同步镜像)")
            .setView(root)
            .setPositiveButton("保存并关闭") { d, _ ->
                noteData.textMemo = memoInput.text.toString()
                repo.saveGameNote(noteData)
                SoundEffectEngine.playSelectApplet()
                upperScreenView.activeApplet = SystemAppletType.NONE
                d.dismiss()
            }
            .setOnCancelListener {
                noteData.textMemo = memoInput.text.toString()
                repo.saveGameNote(noteData)
                upperScreenView.activeApplet = SystemAppletType.NONE
            }
            .create()
        activeAppletDialog?.show()
    }

    // ==================== 3. 好友列表 (Friend List & Mii 名片编辑) ====================

    private fun showFriendListDialog() {
        val ctx = dialogContext()
        val profile = repo.loadFriendProfile()
        upperScreenView.friendProfile = profile
        upperScreenView.invalidate()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }

        val nameEdit = EditText(ctx).apply {
            hint = "Mii 昵称"
            setText(profile.miiName)
        }
        val fcEdit = EditText(ctx).apply {
            hint = "Friend Code (如 2026 - 0330 - 8899)"
            setText(profile.friendCode)
        }
        val mottoEdit = EditText(ctx).apply {
            hint = "个人状态签名气泡 (显示在上屏名片上)"
            setText(profile.statusMessage)
        }
        val favGameEdit = EditText(ctx).apply {
            hint = "最喜爱的 3DS/NDS 游戏"
            setText(profile.favoriteGame)
        }

        addSectionHeader(ctx, root, "Mii 玩家昵称：")
        root.addView(nameEdit)
        addSectionHeader(ctx, root, "Friend Code 好友编号：")
        root.addView(fcEdit)
        addSectionHeader(ctx, root, "个性签名气泡（实时显示在上屏 Mii 名片）：")
        root.addView(mottoEdit)
        addSectionHeader(ctx, root, "最喜爱的游戏：")
        root.addView(favGameEdit)

        activeAppletDialog = AlertDialog.Builder(ctx)
            .setTitle("😊 3DS 好友列表与 Mii 名片")
            .setView(root)
            .setPositiveButton("保存名片") { d, _ ->
                profile.miiName = nameEdit.text.toString().ifBlank { "Nova 玩家" }
                profile.friendCode = fcEdit.text.toString().ifBlank { "2026 - 0330 - 8899" }
                profile.statusMessage = mottoEdit.text.toString().ifBlank { "正在双屏掌机重温 3DS 经典！" }
                profile.favoriteGame = favGameEdit.text.toString().ifBlank { "塞尔达传说：时之笛 3D" }
                repo.saveFriendProfile(profile)
                upperScreenView.friendProfile = profile
                upperScreenView.activeApplet = SystemAppletType.NONE
                upperScreenView.invalidate()
                SoundEffectEngine.playSelectApplet()
                d.dismiss()
            }
            .setNegativeButton("关闭") { d, _ ->
                upperScreenView.activeApplet = SystemAppletType.NONE
                d.dismiss()
            }
            .setOnCancelListener {
                upperScreenView.activeApplet = SystemAppletType.NONE
            }
            .create()
        activeAppletDialog?.show()
    }

    // ==================== 4. 通知中心 (SpotPass / StreetPass) ====================

    private fun showNotificationsDialog() {
        val ctx = dialogContext()
        val list = repo.loadNotifications()
        if (list.isNotEmpty()) {
            list[0].isRead = true
            repo.saveNotifications(list)
            upperScreenView.selectedNotification = list[0]
            upperScreenView.invalidate()
        }

        val titles = list.map { n ->
            val tag = if (n.isSpotPass) "🔵 [SpotPass]" else "🟢 [擦肩通信]"
            "$tag ${n.title} (${n.dateStr})"
        }.toTypedArray()

        activeAppletDialog = AlertDialog.Builder(ctx)
            .setTitle("🔔 3DS 通知列表 (点击信件在上屏阅读正文)")
            .setItems(titles) { _, which ->
                val selected = list[which]
                selected.isRead = true
                repo.saveNotifications(list)
                upperScreenView.selectedNotification = selected
                upperScreenView.invalidate()
                lowerScreenView.unreadNotificationsCount = list.count { !it.isRead }
                SoundEffectEngine.playCursorMove()
                showNotificationsDialog()
            }
            .setPositiveButton("全部标为已读并关闭") { d, _ ->
                list.forEach { it.isRead = true }
                repo.saveNotifications(list)
                lowerScreenView.unreadNotificationsCount = 0
                upperScreenView.activeApplet = SystemAppletType.NONE
                d.dismiss()
            }
            .setOnCancelListener {
                upperScreenView.activeApplet = SystemAppletType.NONE
            }
            .create()
        activeAppletDialog?.show()
    }

    // ==================== 5. 互联网浏览器 ====================

    private fun showBrowserDialog() {
        val ctx = dialogContext()
        val currentSlot = getCurrentDisplayedSlots().getOrNull(lowerScreenView.selectedIndex)
        val queryTitle = currentSlot?.title?.takeIf { currentSlot.type != PlatformType.EMPTY } ?: "Nintendo 3DS 经典游戏"
        val encoded = try {
            URLEncoder.encode("$queryTitle 攻略", "UTF-8")
        } catch (_: Exception) {
            "3DS"
        }

        val webView = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl("https://www.bing.com/search?q=$encoded")
        }

        val container = FrameLayout(ctx).apply {
            addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))
        }

        activeAppletDialog = AlertDialog.Builder(ctx)
            .setTitle("🌐 3DS 互联网浏览器 — 检索：$queryTitle")
            .setView(container)
            .setPositiveButton("返回桌面") { d, _ ->
                upperScreenView.activeApplet = SystemAppletType.NONE
                d.dismiss()
            }
            .setOnCancelListener {
                upperScreenView.activeApplet = SystemAppletType.NONE
            }
            .create()
        activeAppletDialog?.show()
    }

    // ==================== 6. Miiverse 与徽章盒子 (Badge Arcade) ====================

    private fun showBadgeBoxDialog(targetSlotIndex: Int) {
        val ctx = dialogContext()
        val badges = BadgeCatalog.ALL_BADGES
        val items = badges.map { "${it.symbol}  ${it.name}  —  【${it.category}】" }.toTypedArray()

        activeAppletDialog = AlertDialog.Builder(ctx)
            .setTitle("💬 3DS 徽章抓取机盒子 — 选择徽章装饰到桌面卡槽")
            .setItems(items) { _, which ->
                val badge = badges[which]
                placeBadgeOnGrid(badge, targetSlotIndex)
                upperScreenView.activeApplet = SystemAppletType.NONE
            }
            .setNegativeButton("关闭") { d, _ ->
                upperScreenView.activeApplet = SystemAppletType.NONE
                d.dismiss()
            }
            .setOnCancelListener {
                upperScreenView.activeApplet = SystemAppletType.NONE
            }
            .create()
        activeAppletDialog?.show()
    }

    private fun placeBadgeOnGrid(badge: BadgeTemplate, preferredSlotIndex: Int) {
        val list = if (currentOpenedFolder == null) rootSlots else getCurrentDisplayedSlots()
        val targetIdx = if (preferredSlotIndex in list.indices &&
            (list[preferredSlotIndex].type == PlatformType.EMPTY || list[preferredSlotIndex].type == PlatformType.BADGE)
        ) {
            preferredSlotIndex
        } else {
            list.indexOfFirst { it.type == PlatformType.EMPTY }.takeIf { it >= 0 } ?: preferredSlotIndex.coerceIn(0, list.size - 1)
        }

        val badgeSlot = GridSlotItem(
            id = "badge_${UUID.randomUUID()}",
            slotIndex = targetIdx,
            type = PlatformType.BADGE,
            title = "${badge.name}徽章",
            subtitle = "${badge.category} · Nintendo Badge Arcade",
            publisher = "Nintendo",
            badgeId = badge.id,
            primaryColor = badge.primaryColor
        )

        if (currentOpenedFolder == null) {
            rootSlots[targetIdx] = badgeSlot
            repo.saveRootSlots(rootSlots)
        } else {
            badgeSlot.parentFolderId = currentOpenedFolder!!.id
            folderItems.add(badgeSlot)
            repo.saveFolderItems(folderItems)
        }
        lowerScreenView.selectedIndex = targetIdx
        SoundEffectEngine.playSelectApplet()
        syncViewsWithState()
        Toast.makeText(dialogContext(), "已将「${badge.name}」贴到第 ${targetIdx + 1} 号卡槽！", Toast.LENGTH_SHORT).show()
    }

    // ==================== 扫描 ROM 目录 / 添加安卓 App / 新建文件夹 / 说明书配置 ====================

    private fun showScanOrAddDialog(targetSlotIndex: Int) {
        if (!hasStoragePermission()) {
            requestStoragePermission()
        }

        val ctx = dialogContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }

        val discoveredMedia = repo.findMediaContainerDirs()
        val mediaSummary = if (discoveredMedia.isNotEmpty()) {
            "✅ 已检测到 3DS 媒体包目录：\n" + discoveredMedia.joinToString("\n") { "  • ${it.absolutePath}" }
        } else {
            "💡 提示：支持自动扫描内部存储或 TF 卡的「roms」与「3DS」文件夹（如 /sdcard/roms/3DS/media、/sdcard/roms/3ds、/sdcard/3DS/media），自动挂载全部封面、透明 Logo、预览视频与 ROM！"
        }

        val mediaInfoView = TextView(ctx).apply {
            text = mediaSummary
            setTextColor(Color.parseColor("#334155"))
            textSize = 12.5f
            setPadding(0, dp(2), 0, dp(8))
        }
        root.addView(mediaInfoView)

        val pathInput = EditText(ctx).apply {
            val sd = Environment.getExternalStorageDirectory().absolutePath
            val defaultText = discoveredMedia.firstOrNull()?.absolutePath
                ?: repo.getCustomRomDirs().firstOrNull()
                ?: "$sd/roms"
            hint = "输入 roms 或 3DS/media 路径 (如 $sd/roms 或 $sd/3DS/media)"
            setText(defaultText)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        addSectionHeader(ctx, root, "自动扫描并挂载 3DS media 文件夹 (boxFront/logo/video) 与 .3ds/.cci/.nds ROM：")
        root.addView(pathInput)

        val scanAllBtn = createStyledButton(ctx, "🔍 立即扫描并挂载 3DS media 资源包与 ROM", Color.parseColor("#18B858"), Color.WHITE).apply {
            setOnClickListener {
                val customPath = pathInput.text.toString().trim()
                val count = repo.scanRomDirectories(customDir = customPath.ifBlank { null }, removeDemoCards = false)
                rootSlots = repo.loadRootSlots()
                syncViewsWithState()
                SoundEffectEngine.playSelectApplet()
                Toast.makeText(ctx, "扫描挂载完成！新增/更新 $count 款 3DS 游戏与媒体资源", Toast.LENGTH_LONG).show()
                activeAppletDialog?.dismiss()
            }
        }
        val cleanDemoScanBtn = createStyledButton(ctx, "🧹 清除内置演示卡带并重新挂载真实 3DS media 库", Color.parseColor("#E60012"), Color.WHITE).apply {
            setOnClickListener {
                val customPath = pathInput.text.toString().trim()
                val count = repo.scanRomDirectories(customDir = customPath.ifBlank { null }, removeDemoCards = true)
                rootSlots = repo.loadRootSlots()
                syncViewsWithState()
                SoundEffectEngine.playSelectApplet()
                Toast.makeText(ctx, "已清除演示卡带并挂载 $count 款真实 3DS 游戏！", Toast.LENGTH_LONG).show()
                activeAppletDialog?.dismiss()
            }
        }
        val addAndroidBtn = createStyledButton(ctx, "📱 从掌机已安装的安卓应用中选择添加", Color.parseColor("#00A8E8"), Color.WHITE).apply {
            setOnClickListener {
                activeAppletDialog?.dismiss()
                showAddAndroidAppDialog(targetSlotIndex)
            }
        }

        root.addView(scanAllBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(10) })
        root.addView(cleanDemoScanBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(8) })
        root.addView(addAndroidBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(8) })

        activeAppletDialog = AlertDialog.Builder(ctx)
            .setTitle("📂 挂载 3DS media 媒体文件夹 / 扫描 ROM")
            .setView(root)
            .setNegativeButton("取消", null)
            .create()
        activeAppletDialog?.show()
    }

    private fun showAddAndroidAppDialog(targetSlotIndex: Int) {
        val ctx = dialogContext()
        val apps = repo.queryAllInstalledApps()
        if (apps.isEmpty()) {
            Toast.makeText(ctx, "未查询到可启动的应用", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = apps.map { "${it.title} (${it.packageName})" }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("📱 选择要放入 3DS 桌面的安卓应用")
            .setItems(labels) { _, which ->
                val chosen = apps[which]
                val destIdx = if (targetSlotIndex in rootSlots.indices && rootSlots[targetSlotIndex].type == PlatformType.EMPTY) {
                    targetSlotIndex
                } else {
                    rootSlots.indexOfFirst { it.type == PlatformType.EMPTY }.takeIf { it >= 0 } ?: 0
                }
                rootSlots[destIdx] = chosen.copy(slotIndex = destIdx)
                repo.saveRootSlots(rootSlots)
                lowerScreenView.selectedIndex = destIdx
                SoundEffectEngine.playSelectApplet()
                syncViewsWithState()
                Toast.makeText(ctx, "已将「${chosen.title}」放入桌面卡槽！", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCreateFolderDialog(slotIndex: Int) {
        val ctx = dialogContext()
        val input = EditText(ctx).apply {
            hint = "输入文件夹名称（如：宝可梦全集 / 塞尔达系列）"
            setText("3DS 游戏集")
        }
        AlertDialog.Builder(ctx)
            .setTitle("📁 新建 3DS 文件夹")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val title = input.text.toString().trim().ifBlank { "新建文件夹" }
                val folderSlot = GridSlotItem(
                    id = "folder_${UUID.randomUUID()}",
                    slotIndex = slotIndex,
                    type = PlatformType.FOLDER,
                    title = title,
                    subtitle = "3DS 桌面文件夹（长按任意游戏拖放到此即可归档）",
                    publisher = "Nintendo 3DS System",
                    folderLetter = title.take(1),
                    primaryColor = Color.parseColor("#4EA8DE")
                )
                rootSlots[slotIndex] = folderSlot
                repo.saveRootSlots(rootSlots)
                SoundEffectEngine.playFolderOpen()
                syncViewsWithState()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRenameFolderDialog(folder: GridSlotItem) {
        val ctx = dialogContext()
        val input = EditText(ctx).apply {
            setText(folder.title)
        }
        AlertDialog.Builder(ctx)
            .setTitle("重命名文件夹")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newTitle = input.text.toString().trim().ifBlank { folder.title }
                folder.title = newTitle
                folder.folderLetter = newTitle.take(1)
                persistSlotUpdate(folder)
                syncViewsWithState()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteFolder(folder: GridSlotItem, slotIndex: Int) {
        val ctx = dialogContext()
        AlertDialog.Builder(ctx)
            .setTitle("删除文件夹「${folder.title}」？")
            .setMessage("文件夹内的游戏不会被删除，会自动移回主桌面空白卡槽。")
            .setPositiveButton("确认删除") { _, _ ->
                val children = folderItems.filter { it.parentFolderId == folder.id }
                folderItems.removeAll { it.parentFolderId == folder.id }
                repo.saveFolderItems(folderItems)

                rootSlots[slotIndex] = GridSlotItem(id = "empty_$slotIndex", slotIndex = slotIndex, type = PlatformType.EMPTY)
                for (child in children) {
                    val emptyIdx = rootSlots.indexOfFirst { it.type == PlatformType.EMPTY }
                    if (emptyIdx >= 0) {
                        rootSlots[emptyIdx] = child.copy(parentFolderId = "", slotIndex = emptyIdx)
                    }
                }
                repo.saveRootSlots(rootSlots)
                SoundEffectEngine.playBackCancel()
                syncViewsWithState()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMediaAssetPicker(slot: GridSlotItem, isVideo: Boolean) {
        val ctx = dialogContext()
        val titleType = if (isVideo) "视频资源 (.mp4/.webm)" else "封面图片 (.png/.jpg/.webp)"
        val validExts = if (isVideo) setOf("mp4", "mkv", "webm", "avi", "mov") else setOf("png", "jpg", "jpeg", "webp")

        val candidates = LinkedHashSet<String>()
        val romFile = if (slot.romPath.isNotBlank()) File(slot.romPath) else null

        if (romFile != null && romFile.exists()) {
            val parent = romFile.parentFile
            if (parent != null && parent.exists()) {
                parent.listFiles()?.forEach { f ->
                    if (f.isFile && validExts.contains(f.extension.lowercase())) {
                        candidates.add(f.absolutePath)
                    }
                }
            }
            val assets = repo.resolveMediaAssetsForRom(romFile, slot.title, slot.productCode)
            if (isVideo) {
                if (assets.videoPath.isNotBlank()) candidates.add(assets.videoPath)
                if (assets.bannerAnimationPath.isNotBlank()) candidates.add(assets.bannerAnimationPath)
            } else {
                if (assets.boxArtPath.isNotBlank()) candidates.add(assets.boxArtPath)
            }
        }

        val roots = repo.discoverStorageRoots()
        val validRoots = if (roots.isNotEmpty()) roots else listOfNotNull(Environment.getExternalStorageDirectory())
        var currentDir: File = romFile?.parentFile ?: validRoots.firstOrNull { repo.isTfCard(it) } ?: validRoots.firstOrNull() ?: File("/storage/emulated/0")
        if (!currentDir.exists() || !currentDir.isDirectory) {
            currentDir = validRoots.first()
        }

        var dialog: AlertDialog? = null

        val rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val storageRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        rootLayout.addView(storageRow)

        val pathCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val pathTv = TextView(ctx).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#1E293B"))
            setTypeface(null, Typeface.BOLD)
        }
        val tipTv = TextView(ctx).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, dp(2), 0, 0)
            text = "点击文件直接绑定为该游戏的 $titleType"
        }
        pathCard.addView(pathTv)
        pathCard.addView(tipTv)
        rootLayout.addView(pathCard)

        val listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(280)
            )
            addView(listContainer)
        }
        rootLayout.addView(scrollView)

        fun updateView() {
            storageRow.removeAllViews()
            for (r in validRoots) {
                val isTf = repo.isTfCard(r)
                val label = if (isTf) "🎴 TF卡 (${r.name})" else "📱 内部存储"
                val isCur = currentDir.absolutePath.startsWith(r.absolutePath)
                val btn = Button(ctx).apply {
                    text = label
                    textSize = 11.5f
                    setTextColor(if (isCur) Color.WHITE else Color.parseColor("#334155"))
                    setBackgroundColor(if (isCur) Color.parseColor("#0284C7") else Color.parseColor("#E2E8F0"))
                    val lp = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                        setMargins(dp(2), 0, dp(2), 0)
                    }
                    layoutParams = lp
                    setOnClickListener {
                        currentDir = r
                        updateView()
                    }
                }
                storageRow.addView(btn)
            }

            pathTv.text = "📂 当前目录: " + currentDir.absolutePath
            listContainer.removeAllViews()

            if (candidates.isNotEmpty()) {
                val candHeader = TextView(ctx).apply {
                    text = "✨ 智能探测到的推荐资源候选："
                    textSize = 12f
                    setTextColor(Color.parseColor("#D97706"))
                    setTypeface(null, Typeface.BOLD)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                }
                listContainer.addView(candHeader)

                for (candPath in candidates) {
                    val cFile = File(candPath)
                    if (!cFile.exists()) continue
                    val candItem = TextView(ctx).apply {
                        text = "⭐ ${cFile.name}  (${cFile.parentFile?.name ?: ""})"
                        textSize = 13f
                        setTextColor(Color.parseColor("#059669"))
                        setTypeface(null, Typeface.BOLD)
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        setBackgroundColor(Color.parseColor("#ECFDF5"))
                        setOnClickListener {
                            if (isVideo) {
                                slot.videoPath = cFile.absolutePath
                            } else {
                                slot.boxArtPath = cFile.absolutePath
                            }
                            persistSlotUpdate(slot)
                            lowerScreenView.predecodeSlotIconSync(slot)
                            syncViewsWithState()
                            dialog?.dismiss()
                            Toast.makeText(ctx, "已为「${slot.title}」绑定 $titleType: ${cFile.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    listContainer.addView(candItem)
                }

                val div = View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        setMargins(0, dp(8), 0, dp(8))
                    }
                    setBackgroundColor(Color.parseColor("#CBD5E1"))
                }
                listContainer.addView(div)
            }

            val parent = currentDir.parentFile
            val isAtRoot = validRoots.any { it.absolutePath == currentDir.absolutePath }
            if (parent != null && !isAtRoot) {
                val upItem = TextView(ctx).apply {
                    text = "⬆️ [返回上一级] .. (${parent.name.ifBlank { "根目录" }})"
                    textSize = 13.5f
                    setTextColor(Color.parseColor("#2563EB"))
                    setTypeface(null, Typeface.BOLD)
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    setBackgroundColor(Color.parseColor("#F8FAFC"))
                    setOnClickListener {
                        currentDir = parent
                        updateView()
                    }
                }
                listContainer.addView(upItem)
            }

            val localFiles = currentDir.listFiles() ?: emptyArray()

            val subDirs = localFiles.filter { it.isDirectory && !it.name.startsWith(".") && !it.name.equals("Android", true) }
                .sortedWith(compareBy { it.name.lowercase() })

            for (sub in subDirs) {
                val subItem = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    val tv = TextView(ctx).apply {
                        text = "📁 " + sub.name
                        textSize = 13.5f
                        setTextColor(Color.parseColor("#1E293B"))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val arrow = TextView(ctx).apply {
                        text = "进入 ▶"
                        textSize = 11.5f
                        setTextColor(Color.parseColor("#64748B"))
                    }
                    addView(tv)
                    addView(arrow)
                    setOnClickListener {
                        currentDir = sub
                        updateView()
                    }
                }
                listContainer.addView(subItem)
            }

            val mediaFiles = localFiles.filter { it.isFile && validExts.contains(it.extension.lowercase()) }
                .sortedWith(compareBy { it.name.lowercase() })

            if (mediaFiles.isNotEmpty()) {
                val filesHeader = TextView(ctx).apply {
                    text = "📄 媒体文件 (${mediaFiles.size} 个，点击选取)："
                    textSize = 12f
                    setTextColor(Color.parseColor("#475569"))
                    setTypeface(null, Typeface.BOLD)
                    setPadding(dp(8), dp(6), dp(8), dp(4))
                }
                listContainer.addView(filesHeader)

                for (mFile in mediaFiles) {
                    val fileItem = TextView(ctx).apply {
                        val icon = if (isVideo) "🎬" else "🖼️"
                        text = "$icon ${mFile.name}"
                        textSize = 13.5f
                        setTextColor(Color.parseColor("#0F172A"))
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        setBackgroundColor(Color.parseColor("#F1F5F9"))
                        setOnClickListener {
                            if (isVideo) {
                                slot.videoPath = mFile.absolutePath
                            } else {
                                slot.boxArtPath = mFile.absolutePath
                            }
                            persistSlotUpdate(slot)
                            lowerScreenView.predecodeSlotIconSync(slot)
                            syncViewsWithState()
                            dialog?.dismiss()
                            Toast.makeText(ctx, "已为「${slot.title}」绑定 $titleType: ${mFile.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    listContainer.addView(fileItem)
                }
            } else if (subDirs.isEmpty()) {
                val emptyTv = TextView(ctx).apply {
                    text = "（此目录下无匹配的 $titleType）"
                    textSize = 12f
                    setTextColor(Color.parseColor("#94A3B8"))
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }
                listContainer.addView(emptyTv)
            }
        }

        updateView()

        dialog = AlertDialog.Builder(ctx)
            .setTitle("手动选择 $titleType — ${slot.title}")
            .setView(rootLayout)
            .setNeutralButton("🗑️ 清空该设置") { _, _ ->
                if (isVideo) {
                    slot.videoPath = ""
                } else {
                    slot.boxArtPath = ""
                }
                persistSlotUpdate(slot)
                syncViewsWithState()
                Toast.makeText(ctx, "已清空「${slot.title}」的 $titleType", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private fun showGameManualAndConfigDialog(slot: GridSlotItem) {
        val ctx = dialogContext()
        val emulators = EmulatorLauncher.getAvailableEmulators(this, slot.type)
        val infoBuilder = StringBuilder().apply {
            append("• 标题：${slot.title}\n")
            append("• 平台：${slot.type.name} (${slot.productCode.ifBlank { "标准卡带" }})\n")
            append("• 厂商：${slot.publisher}\n")
            if (slot.romPath.isNotBlank()) {
                append("• ROM 路径：${slot.romPath}\n")
            }
            if (slot.boxArtPath.isNotBlank()) {
                append("• 封面路径：${slot.boxArtPath}\n")
            }
            if (slot.videoPath.isNotBlank()) {
                append("• 视频路径：${slot.videoPath}\n")
            }
            if (slot.packageName.isNotBlank()) {
                append("• 应用包名：${slot.packageName}\n")
            }
            append("• 游玩记录：共启动 ${slot.playCount} 次，累计 ${slot.playTimeMinutes / 60} 小时 ${slot.playTimeMinutes % 60} 分钟")
        }

        val mainFilterDesc = getMainFilterLabel(slot)
        val boxStatus = if (slot.boxArtPath.isNotBlank() && File(slot.boxArtPath).exists()) "已设置" else if (slot.iconCachePath.isNotBlank()) "官方内嵌图标" else "未设置"
        val videoStatus = if (slot.videoPath.isNotBlank() && File(slot.videoPath).exists()) "已设置" else "未设置"

        val options = mutableListOf(
            "🖼️ 手动选择游戏封面图片 (当前: $boxStatus)",
            "🎬 手动选择上屏预览视频 (当前: $videoStatus)",
            "✏️ 打开该游戏的双屏手写「游戏笔记」",
            "🎮 单独指定启动该游戏的模拟器 (${slot.preferredEmulatorPkg.ifBlank { "跟随全局默认" }})",
            "📺 主屏 LCD 3x 复古滤镜 ($mainFilterDesc)",
            "🛠️ 定制副屏战术面板 (街霸按键/攻略/触控板/AI)",
            "✏️ 修改卡带显示名称",
            "🗑 从桌面卡槽移除此图标"
        )

        AlertDialog.Builder(ctx)
            .setTitle("📘 软件配置与媒体资源 — ${slot.title}")
            .setMessage(infoBuilder.toString())
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showMediaAssetPicker(slot, isVideo = false)
                    1 -> showMediaAssetPicker(slot, isVideo = true)
                    2 -> onAppletClicked(SystemAppletType.GAME_NOTES)
                    3 -> {
                        if (emulators.isEmpty()) return@setItems
                        val labels = emulators.map {
                            val tag = if (it.isInstalled) " [已安装]" else " [未安装]"
                            "${it.name}$tag (${it.packageName})"
                        }.toTypedArray()
                        AlertDialog.Builder(ctx)
                            .setTitle("为「${slot.title}」指定专属模拟器")
                            .setItems(labels) { _, emuIdx ->
                                slot.preferredEmulatorPkg = emulators[emuIdx].packageName
                                persistSlotUpdate(slot)
                                Toast.makeText(ctx, "已绑定：${emulators[emuIdx].name}", Toast.LENGTH_SHORT).show()
                            }
                            .show()
                    }
                    4 -> {
                        showMainRetroFilterConfigDialog(slot)
                    }
                    5 -> {
                        showGameCompanionConfigDialog(slot)
                    }
                    6 -> {
                        val edit = EditText(ctx).apply { setText(slot.title) }
                        AlertDialog.Builder(ctx)
                            .setTitle("修改卡带标题")
                            .setView(edit)
                            .setPositiveButton("保存") { _, _ ->
                                slot.title = edit.text.toString().trim().ifBlank { slot.title }
                                persistSlotUpdate(slot)
                                syncViewsWithState()
                            }
                            .show()
                    }
                    7 -> {
                        clearSlotAt(lowerScreenView.selectedIndex)
                    }
                }
            }
            .setPositiveButton("确定", null)
            .show()
    }

    private fun getMainFilterLabel(slot: GridSlotItem): String {
        val mode = (slot.customMainRetroFilter as String?).orEmpty().trim().uppercase().ifBlank { "AUTO" }
        return when (mode) {
            "ON" -> "强制开启"
            "OFF" -> "强制关闭"
            else -> {
                val autoOn = slot.shouldEnableMainRetroFilter(repo.mainScreenRetroFilterEnabled, repo.mainScreenRetroFilterAuto2D)
                if (autoOn) "智能识别开启 (2D像素)" else "智能识别关闭 (3D高清)"
            }
        }
    }

    private fun showMainRetroFilterConfigDialog(slot: GridSlotItem) {
        val ctx = dialogContext()
        val choices = arrayOf(
            "✨ 智能自动识别 (推荐：冒险岛及 2D 游戏自动开，3D 游戏保持高清)",
            "🟢 强制为此游戏开启主屏滤镜 (彻底解决 2D 贴图拉大发糊发虚)",
            "⚪ 强制为此游戏关闭主屏滤镜 (纯 3D 游戏保持多倍渲染高清)",
            "🎨 调节主屏滤镜类型与浓度 (当前: ${repo.mainScreenRetroFilterType} · ${repo.mainScreenRetroFilterOpacity}%)"
        )

        AlertDialog.Builder(ctx)
            .setTitle("📺 主屏 LCD 3x 复古滤镜配置 — ${slot.title}")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> {
                        slot.customMainRetroFilter = "AUTO"
                        persistSlotUpdate(slot)
                        Toast.makeText(ctx, "已设为智能识别（根据 2D/3D 自动决策）", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        slot.customMainRetroFilter = "ON"
                        persistSlotUpdate(slot)
                        Toast.makeText(ctx, "已强制为「${slot.title}」开启主屏 LCD 滤镜", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        slot.customMainRetroFilter = "OFF"
                        persistSlotUpdate(slot)
                        Toast.makeText(ctx, "已强制为「${slot.title}」关闭主屏滤镜", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        showMainFilterTypeAndOpacityDialog()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showMainFilterTypeAndOpacityDialog() {
        val ctx = dialogContext()
        val types = com.gameconsole.launcher3ds.overlay.LauncherRetroFilterManager.FilterType.values()
        val typeNames = types.map { it.displayName }.toTypedArray()

        AlertDialog.Builder(ctx)
            .setTitle("选择主屏复古滤镜类型")
            .setItems(typeNames) { _, tIdx ->
                repo.mainScreenRetroFilterType = types[tIdx].id
                val opacities = arrayOf(10, 20, 30, 40)
                val opNames = arrayOf("10% (极轻微)", "20% (推荐 · 通透物理质感)", "30% (浓郁)", "40% (重度复古)")
                AlertDialog.Builder(ctx)
                    .setTitle("选择主屏滤镜浓度")
                    .setItems(opNames) { _, oIdx ->
                        repo.mainScreenRetroFilterOpacity = opacities[oIdx]
                        Toast.makeText(ctx, "已更新主屏滤镜参数：${types[tIdx].displayName} · ${opacities[oIdx]}%", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
            .show()
    }

    private fun showGameCompanionConfigDialog(slot: GridSlotItem) {
        val ctx = dialogContext()
        val companionRepo = com.gameconsole.launcher3ds.companion.repository.CompanionProfileRepository(ctx)
        val profile = companionRepo.getProfileForGame(slot)

        val switchText = if (repo.subscreenCompanionEnabled) {
            "🔘 副屏战术伴侣总开关：【已开启】(点击关闭)"
        } else {
            "🔘 副屏战术伴侣总开关：【已关闭】(点击开启)"
        }

        val items = arrayOf(
            switchText,
            "🏷️ 当前生效配置：${profile.name}",
            "📂 套用已有模板 (格斗/JRPG/触控板)...",
            "💾 将当前配置另存为新模板...",
            "🎯 设置启动后默认展开页面 (${profile.defaultTab.title})",
            "🤖 配置 DeepSeek API Key"
        )

        AlertDialog.Builder(ctx)
            .setTitle("🎮 副屏战术伴侣配置 — ${slot.title}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        repo.subscreenCompanionEnabled = !repo.subscreenCompanionEnabled
                        syncViewsWithState()
                        syncCompanionSettingsToApollo()
                        if (!repo.subscreenCompanionEnabled) {
                            com.gameconsole.launcher3ds.companion.overlay.LauncherCompanionOverlayManager.hide()
                        }
                        val state = if (repo.subscreenCompanionEnabled) "已开启 (单屏游戏副屏启动伴侣面板)" else "已关闭 (单屏游戏副屏保持原生黑屏)"
                        Toast.makeText(ctx, "副屏战术伴侣：$state", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        Toast.makeText(ctx, "配置已自动与「${slot.title}」专属绑定", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        val templates = companionRepo.listAllTemplates()
                        if (templates.isEmpty()) {
                            Toast.makeText(ctx, "模板库暂无模板", Toast.LENGTH_SHORT).show()
                            return@setItems
                        }
                        val tNames = templates.map { it.name }.toTypedArray()
                        AlertDialog.Builder(ctx)
                            .setTitle("选择要套用的模板")
                            .setItems(tNames) { _, tIdx ->
                                companionRepo.applyTemplateToGame(templates[tIdx].id, slot)
                                Toast.makeText(ctx, "已套用并绑定为该游戏专属面板：${templates[tIdx].name}", Toast.LENGTH_SHORT).show()
                            }
                            .show()
                    }
                    3 -> {
                        val input = EditText(ctx).apply { hint = "输入模板名称" }
                        AlertDialog.Builder(ctx)
                            .setTitle("另存为新模板")
                            .setView(input)
                            .setPositiveButton("保存") { _, _ ->
                                val name = input.text.toString().trim()
                                if (name.isNotBlank()) {
                                    val tpl = companionRepo.saveAsTemplate(profile, name)
                                    Toast.makeText(ctx, "已保存新模板：${tpl.name}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    4 -> {
                        val tabs = com.gameconsole.launcher3ds.companion.model.CompanionTab.values()
                        val tabNames = tabs.map { "${it.icon} ${it.title}" }.toTypedArray()
                        AlertDialog.Builder(ctx)
                            .setTitle("选择游戏启动后默认显示的副屏面板")
                            .setItems(tabNames) { _, tabIdx ->
                                profile.defaultTab = tabs[tabIdx]
                                companionRepo.saveGameProfile(profile)
                                Toast.makeText(ctx, "默认页面已设为：${tabs[tabIdx].title}", Toast.LENGTH_SHORT).show()
                            }
                            .show()
                    }
                    5 -> {
                        val input = EditText(ctx).apply {
                            setText(profile.aiChat.apiKey)
                            hint = "sk-..."
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        }
                        AlertDialog.Builder(ctx)
                            .setTitle("设置 DeepSeek API Key")
                            .setMessage("支持 DeepSeek 官方 API 或 硅基流动等兼容通道 Key。")
                            .setView(input)
                            .setPositiveButton("保存") { _, _ ->
                                profile.aiChat.apiKey = input.text.toString().trim()
                                companionRepo.saveGameProfile(profile)
                                Toast.makeText(ctx, "DeepSeek API Key 已保存", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showMoveOrArchiveDialog(slot: GridSlotItem, index: Int) {
        val ctx = dialogContext()
        val folders = rootSlots.filter { it.type == PlatformType.FOLDER }
        val actions = mutableListOf(
            "✥ 进入光标移动换位模式（点选目标卡槽即可交换）",
            "🏷️ 修改所属系列或分类（当前: ${GameCategory.fromId(GameCategoryClassifier.resolveCategoryId(slot)).displayName}）"
        )
        folders.forEach { f ->
            actions.add("📂 归档至文件夹：「${f.title}」")
        }

        AlertDialog.Builder(ctx)
            .setTitle("整理卡槽 — ${slot.title}")
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        lowerScreenView.movingSourceIndex = index
                        Toast.makeText(ctx, "请点击目标卡槽或移动光标后按「放到此处 (A)」", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        showEditGameCategoryDialog(slot, index)
                    }
                    else -> {
                        val targetFolder = folders[which - 2]
                        onSlotsSwappedOrMoved(index, targetFolder.slotIndex)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditGameCategoryDialog(slot: GridSlotItem, index: Int) {
        val ctx = dialogContext()
        val cats = GameCategory.entries.filter { it != GameCategory.ALL }
        val names = cats.map { "${it.badgeSymbol} ${it.displayName}" }.toMutableList()
        names.add("➕ 输入自定义分类/系列名称...")

        AlertDialog.Builder(ctx)
            .setTitle("修改游戏分类与系列 — ${slot.title}")
            .setItems(names.toTypedArray()) { _, which ->
                if (which < cats.size) {
                    val chosen = cats[which]
                    slot.categoryId = chosen.id
                    repo.saveRootSlots(rootSlots)
                    syncViewsWithState()
                    SoundEffectEngine.playSelectApplet()
                    Toast.makeText(ctx, "已归类至：${chosen.displayName}", Toast.LENGTH_SHORT).show()
                } else {
                    val input = android.widget.EditText(ctx).apply {
                        hint = "例如：最终幻想 / 怪物猎人 / 战术射击"
                    }
                    AlertDialog.Builder(ctx)
                        .setTitle("输入自定义分类")
                        .setView(input)
                        .setPositiveButton("确定") { _, _ ->
                            val customTag = input.text.toString().trim()
                            if (customTag.isNotBlank()) {
                                slot.categoryId = customTag
                                repo.saveRootSlots(rootSlots)
                                syncViewsWithState()
                                SoundEffectEngine.playSelectApplet()
                                Toast.makeText(ctx, "已设置自定义分类：$customTag", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDefaultEmulatorPicker(platform: PlatformType) {
        val ctx = dialogContext()
        val list = EmulatorLauncher.getAvailableEmulators(this, platform)
        val labels = list.map {
            val status = if (it.isInstalled) "✅ [已安装]" else "⚪ [未安装]"
            "$status ${it.name} (${it.packageName})"
        }.toMutableList()
        labels.add("📱 从本机已安装应用中自定义挑选...")

        val title = if (platform == PlatformType.N3DS) "选择默认 3DS 模拟器" else "选择默认 NDS 模拟器"
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < list.size) {
                    val chosen = list[which]
                    if (platform == PlatformType.N3DS) {
                        repo.default3dsEmulatorPkg = chosen.packageName
                    } else {
                        repo.defaultNdsEmulatorPkg = chosen.packageName
                    }
                    SoundEffectEngine.playSelectApplet()
                    Toast.makeText(ctx, "已设为默认模拟器：${chosen.name}", Toast.LENGTH_SHORT).show()
                } else {
                    showCustomInstalledAppPicker { chosenPkg, appLabel ->
                        if (platform == PlatformType.N3DS) {
                            repo.default3dsEmulatorPkg = chosenPkg
                        } else {
                            repo.defaultNdsEmulatorPkg = chosenPkg
                        }
                        SoundEffectEngine.playSelectApplet()
                        Toast.makeText(ctx, "已设为默认模拟器：$appLabel", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCustomInstalledAppPicker(onSelected: (packageName: String, label: String) -> Unit) {
        val ctx = dialogContext()
        val pm = packageManager
        val mainIntent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = pm.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        if (resolveList.isEmpty()) {
            Toast.makeText(ctx, "未找到其他已安装应用", Toast.LENGTH_SHORT).show()
            return
        }

        val names = resolveList.map { "${it.loadLabel(pm)} (${it.activityInfo.packageName})" }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("选择应用作为该平台模拟器")
            .setItems(names) { _, which ->
                val info = resolveList[which]
                onSelected(info.activityInfo.packageName, info.loadLabel(pm).toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun moveItemOutOfFolder(slot: GridSlotItem) {
        val folder = currentOpenedFolder ?: return
        folderItems.removeAll { it.id == slot.id && it.parentFolderId == folder.id }
        repo.saveFolderItems(folderItems)

        val emptyIdx = rootSlots.indexOfFirst { it.type == PlatformType.EMPTY }
        if (emptyIdx >= 0) {
            rootSlots[emptyIdx] = slot.copy(parentFolderId = "", slotIndex = emptyIdx)
            repo.saveRootSlots(rootSlots)
        }
        SoundEffectEngine.playFolderOpen()
        syncViewsWithState()
        Toast.makeText(dialogContext(), "已将「${slot.title}」移回主桌面", Toast.LENGTH_SHORT).show()
    }

    private fun clearSlotAt(index: Int) {
        val displayed = getCurrentDisplayedSlots()
        val item = displayed.getOrNull(index)
        if (currentOpenedFolder == null) {
            val rootIdx = if (item != null) rootSlots.indexOfFirst { it.id == item.id } else index
            if (rootIdx in rootSlots.indices) {
                rootSlots[rootIdx] = GridSlotItem(id = "empty_$rootIdx", slotIndex = rootIdx, type = PlatformType.EMPTY)
                repo.saveRootSlots(rootSlots)
            }
        } else {
            if (item != null) {
                folderItems.removeAll { it.id == item.id }
                repo.saveFolderItems(folderItems)
            }
        }
        SoundEffectEngine.playBackCancel()
        syncViewsWithState()
    }

    private fun persistSlotUpdate(slot: GridSlotItem) {
        if (currentOpenedFolder == null) {
            val idx = rootSlots.indexOfFirst { it.id == slot.id }
            if (idx >= 0) {
                rootSlots[idx] = slot
                repo.saveRootSlots(rootSlots)
            }
        } else {
            val idx = folderItems.indexOfFirst { it.id == slot.id }
            if (idx >= 0) {
                folderItems[idx] = slot
                repo.saveFolderItems(folderItems)
            }
        }
    }

    // ==================== 权限与 UI 辅助方法 ====================

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasStoragePermission()) {
            hasAutoScannedOnResume = true
            triggerBackgroundRomScan(showToastOnCompletion = false)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (hasStoragePermission() && !hasAutoScannedOnResume) {
            hasAutoScannedOnResume = true
            triggerBackgroundRomScan(showToastOnCompletion = false)
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                1001
            )
        }
    }

    private fun addSectionHeader(ctx: Context, parent: LinearLayout, text: String) {
        val tv = TextView(ctx).apply {
            this.text = text
            setTextColor(Color.parseColor("#1E293B"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(4))
        }
        parent.addView(tv)
    }

    private fun createStyledButton(ctx: Context, text: String, bgColor: Int, textColor: Int): Button {
        return Button(ctx).apply {
            this.text = text
            this.isAllCaps = false
            this.textSize = 12.5f
            this.setTextColor(textColor)
            this.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(bgColor)
                setStroke(dp(1), Color.argb(80, 255, 255, 255))
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun applyImmersiveFullscreen() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }
}
