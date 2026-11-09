package com.gameconsole.launcher3ds.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.gameconsole.launcher3ds.model.BadgeCatalog
import com.gameconsole.launcher3ds.model.GameCategory
import com.gameconsole.launcher3ds.model.CustomCategoryItem
import com.gameconsole.launcher3ds.model.GridSlotItem
import com.gameconsole.launcher3ds.model.PlatformType
import com.gameconsole.launcher3ds.model.SystemAppletType
import com.gameconsole.launcher3ds.model.Theme3DS
import com.gameconsole.launcher3ds.model.ThemeCatalog
import com.gameconsole.launcher3ds.model.ScanStorageScope
import com.gameconsole.launcher3ds.telemetry.StartupProfiler
import com.gameconsole.launcher3ds.emulator.EmulatorLauncher
import com.gameconsole.launcher3ds.model.UpperDisplayMode
import com.gameconsole.launcher3ds.model.VideoFilterType
import com.gameconsole.launcher3ds.audio.SoundEffectEngine
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin

/**
 * 🎮 Nintendo 3DS 下屏 (Immersive Lower Touch Screen HOME Menu View)
 * 针对实机双屏截图深度优化：
 * 1. 修复顶栏左侧图标半透明发灰问题（独立 bitmapPaint 永不污染 alpha），改用实机风格大号独立 3D 图标按钮
 * 2. 消除上下巨大留白：解除 cellSize=220px 限制，让卡槽自动撑满中央 3DS 内凹圆角舞台框 (stageWellRect)
 * 3. 采用 1:1 纯正方形游戏封面图标（裁掉包装盒侧边白条），还原真实 3DS 立体文件夹造型，修复徽章「？」居中对齐
 * 4. 底部还原 3DS 实机连体三段式拱形底栏（中央带 ▲ 指示尖角）
 */
class LowerScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onSlotSelected(slot: GridSlotItem, index: Int)
        fun onSlotOpenRequested(slot: GridSlotItem, index: Int)
        fun onSlotManualOrConfigRequested(slot: GridSlotItem, index: Int)
        fun onRightActionRequested(slot: GridSlotItem, index: Int)
        fun onAppletClicked(applet: SystemAppletType)
        fun onZoomChanged(newRows: Int, zoomIn: Boolean)
        fun onSlotsSwappedOrMoved(fromIndex: Int, toIndex: Int)
        fun onCategoryStepRequested(delta: Int)
        fun onCategoryPickerRequested()
        fun onTopIconFocused(topIconIndex: Int, previewApplet: SystemAppletType)
        fun onVideoAudioToggleRequested()
        fun onVideoFilterSelected(filter: VideoFilterType)
        fun onThemeSelectedFromPanel(theme: Theme3DS)
        fun onQuickToggleBgmRequested()
        fun onQuickToggleSfxRequested()
        fun onQuickSwapScreensRequested()
        fun onQuickRescanRequested()
        fun onPick3dsEmulatorRequested()
        fun onPickNdsEmulatorRequested()
        fun onOpenAdvancedSettingsDialogRequested()
        fun onSettingsPanelClosed()
        fun onAutoRandomToggled()
        fun onAutoRandomIntervalChanged(newIntervalSec: Int)
        fun onAutoRandomIntervalDialogRequested()
        fun onToggleUpperLogoRequested()
        fun onToggleUpperBoxartRequested()
        fun onCycleUpperDisplayModeRequested()
        fun onPlatformToggled(platformId: String)
        fun onPresetOnly3dsRequested()
        fun onPresetHandheldsRequested()
        fun onPresetSelectAllPlatformsRequested()
        fun onPresetInvertPlatformsRequested()
        fun onPickRomDirectoryRequested()
        fun onRomDirectoryPicked(chosenDir: String)
        fun onCycle3dsEmulatorRequested()
        fun onCycleNdsEmulatorRequested()
        fun onToggleSplitPreviewRequested()
        fun onToggleSubscreenCompanionRequested()
        fun onToggleBgmRequested()
        fun onSetDefaultHomeRequested()
        fun onMediaLibraryToggled(path: String, enabled: Boolean)
        fun onEnableAllMediaLibrariesRequested()
        fun onDisableAllMediaLibrariesRequested()
        fun onSelect3dsEmulator(pkg: String)
        fun onSelectNdsEmulator(pkg: String)
        fun onPickCustomInstalledAppRequested(platform: PlatformType)
        fun onSubscreenRetroFilterToggled()
        fun onSubscreenRetroFilterTypeSelected(type: String)
        fun onSubscreenRetroFilterOpacitySelected(opacity: Int)
        fun onMainScreenRetroFilterToggled()
        fun onMainScreenRetroFilterAuto2DToggled()
        fun onMainScreenRetroFilterTypeSelected(type: String)
        fun onMainScreenRetroFilterOpacitySelected(opacity: Int)
        fun onScanStorageScopeChanged(scope: ScanStorageScope)
        fun onManualRomScanRequested()
        fun onMediaLibraryManagerOpened()
        fun onAddDirectoryRequested()
        fun onRemoveMediaLibraryRequested(path: String)
        fun onRemoveAllMediaLibrariesRequested()
        fun onToggleHideGamesWithoutCoverRequested()
        fun onGetStorageRoots(): List<java.io.File>
        fun onDirectoryChosenForMount(path: String)
        fun onCategoryManagerRequested()
        fun onCreateCategoryRequested()
        fun onEditCategoryRequested(category: CustomCategoryItem)
        fun onDeleteCategoryRequested(category: CustomCategoryItem)
        fun onMoveCategoryUpRequested(index: Int)
        fun onMoveCategoryDownRequested(index: Int)
        fun onResetCategoriesRequested()
        fun onAddGamesToCategoryRequested(category: CustomCategoryItem)
        fun onRemoveGameFromCategoryRequested(category: CustomCategoryItem, slot: GridSlotItem)
        fun onGetGamesForCategory(category: CustomCategoryItem): List<GridSlotItem>
        fun onGetCategoryGameCounts(): Map<String, Int>
        fun onGetAllCandidateGames(): List<GridSlotItem>
        fun onIsGameInCategory(catId: String, slot: GridSlotItem): Boolean
        fun onToggleGameCategory(catId: String, slot: GridSlotItem, add: Boolean)
        fun onSearchCategoryGamesPrompt(category: CustomCategoryItem, currentQuery: String, onQuerySubmitted: (String) -> Unit)
        fun onSelectCategory(category: CustomCategoryItem)
        fun onSaveCategory(id: String?, name: String, isSeries: Boolean, symbol: String, color: Int)
        fun onDeleteCategory(id: String)
        fun onLaunchInstalledAppRequested(packageName: String, activityName: String, launchOnSubscreen: Boolean)
        fun onAddAppToDesktopRequested(slot: GridSlotItem)
        fun onGetAllInstalledApps(): List<GridSlotItem>
    }

    var listener: Listener? = null

    var subscreenCompanionEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var subscreenRetroFilterEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var subscreenRetroFilterType: String = "LCD_3X"
        set(value) {
            field = value
            invalidate()
        }

    var subscreenRetroFilterOpacity: Int = 30
        set(value) {
            field = value
            invalidate()
        }

    var mainScreenRetroFilterEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var mainScreenRetroFilterAuto2D: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var mainScreenRetroFilterType: String = "LCD_3X"
        set(value) {
            field = value
            invalidate()
        }

    var mainScreenRetroFilterOpacity: Int = 20
        set(value) {
            field = value
            invalidate()
        }

    var showUpperBoxart: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var showUpperLogo: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var upperDisplayMode: UpperDisplayMode = UpperDisplayMode.BOXART_3D
        set(value) {
            field = value
            invalidate()
        }

    var isAutoRandomPlaying: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var autoRandomIntervalSec: Int = 10
        set(value) {
            field = value.coerceIn(3, 600)
            invalidate()
        }

    enum class SettingsSubPanel {
        NONE,
        AUTO_RANDOM_INTERVAL,
        ADVANCED_SETTINGS,
        ROM_DIRECTORY_PICKER,
        MEDIA_LIBRARY_MANAGER,
        EMULATOR_PICKER_3DS,
        EMULATOR_PICKER_NDS,
        STARTUP_DIAGNOSTICS,
        SUB_SCREEN_FILTER_CONFIG,
        MAIN_SCREEN_FILTER_CONFIG,
        ROM_SCAN_CONFIG,
        PEGASUS_METADATA_PREVIEW,
        CATEGORY_MANAGER,
        CATEGORY_GAME_MANAGER,
        CATEGORY_EDITOR
    }

    var settingsSubPanel: SettingsSubPanel = SettingsSubPanel.NONE
        set(value) {
            field = value
            subPanelCursorIndex = 0
            invalidate()
        }

    var mediaLibraries: List<com.gameconsole.launcher3ds.model.MediaLibraryItem> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var isSplitPreviewEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var candidateRomDirs: List<String> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var subPanelCursorIndex: Int = 0

    var isVideoAudioEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var currentVideoFilter: VideoFilterType = VideoFilterType.NONE
        set(value) {
            field = value
            invalidate()
        }

    var isBgmEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var pegasusInspectionProvider: ((String) -> com.gameconsole.launcher3ds.storage.LauncherRepository.PegasusFileInspection)? = null
    var hasPegasusMetadataProvider: ((String) -> Boolean)? = null
    var currentPegasusInspectPlatform: String = "3DS"
    var pegasusInspectRawTextMode: Boolean = false
    var pegasusInspectScrollY: Float = 0f
    var targetPegasusInspectScrollY: Float = 0f
    private var maxPegasusInspectScrollY: Float = 0f

    var isSfxEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var sfxVolumePercent: Int = 100
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    var enabledPlatforms: Set<String> = setOf("3DS")
        set(value) {
            field = value
            invalidate()
        }

    var platformGameCounts: Map<String, Int> = emptyMap()
        set(value) {
            field = value
            invalidate()
        }

    var lastMountedMediaDir: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var default3dsEmulatorPkg: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var defaultNdsEmulatorPkg: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var hideGamesWithoutCover: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var default3dsEmulatorLabel: String = "Citra"
        set(value) {
            field = value
            invalidate()
        }

    var defaultNdsEmulatorLabel: String = "DraStic"
        set(value) {
            field = value
            invalidate()
        }

    var isSwapScreensEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var scanStorageScope: ScanStorageScope = ScanStorageScope.BOTH
        set(value) {
            field = value
            invalidate()
        }

    var detectedTfCardPath: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var isScanInProgress: Boolean = false
        set(value) {
            field = value
            if (value) {
                scanHudAlpha = 1f
                scanCompleteTimestamp = 0L
            }
            invalidate()
        }

    var scanProgressPercent: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    var scanStatusTitle: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var scanDetailText: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private var scanHudAlpha: Float = 0f
    private var scanCompleteTimestamp: Long = 0L

    fun finishScanProgress(summaryText: String = "游戏库加载就绪") {
        scanProgressPercent = 100
        scanStatusTitle = "✔ $summaryText"
        scanCompleteTimestamp = System.currentTimeMillis()
        invalidate()
    }

    /**
     * 原版 3DS 风格内嵌设置面板开关与当前标签页：
     * - tab 0 = 🎬 上屏视频滤镜 (8款实时滤镜)
     * - tab 1 = 🎨 主题与声音设置
     * - tab 2 = 📂 游戏库与模拟器
     */
    var isSettingsPanelOpen: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var settingsTabIndex: Int = 0
        set(value) {
            field = value.coerceIn(0, 3)
            settingsCardCursorIndex = when (field) {
                0 -> VideoFilterType.entries.indexOf(currentVideoFilter).coerceAtLeast(0)
                1 -> ThemeCatalog.ALL_THEMES.indexOfFirst { it.id == currentTheme.id }.coerceAtLeast(0)
                2 -> 0
                3 -> 0
                else -> 0
            }
            invalidate()
        }

    var settingsCardCursorIndex: Int = 0
        set(value) {
            field = value.coerceAtLeast(0)
            if (settingsTabIndex == 2) {
                val totalP = ((com.gameconsole.launcher3ds.model.PlatformCatalog.getAllPlatforms(platformGameCounts.keys + mediaLibraries.map { it.name }).size + 7) / 8).coerceAtLeast(1)
                platformPage = (field / 8).coerceIn(0, totalP - 1)
            }
            invalidate()
        }

    var categories: List<CustomCategoryItem> = CustomCategoryItem.defaultCategories()
        set(value) {
            field = if (value.isNotEmpty()) value else CustomCategoryItem.defaultCategories()
            currentCategoryIndex = currentCategoryIndex.coerceIn(0, (field.size - 1).coerceAtLeast(0))
            invalidate()
        }

    var managingCategory: CustomCategoryItem? = null
        set(value) {
            field = value
            invalidate()
        }

    var currentCategoryIndex: Int = 0
        set(value) {
            field = value.coerceIn(0, (categories.size - 1).coerceAtLeast(0))
            scrollOffsetX = 0f
            targetScrollX = 0f
            invalidate()
        }

    var currentCategoryGameCount: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    /**
     * L2 / R2 切换下屏顶部 8 个功能图标时的当前高亮索引 (-1 表示焦点在下方的游戏网格上)：
     * 0 = HOME菜单设置
     * 1 = 游戏笔记
     * 2 = 好友列表
     * 3 = 通知列表
     * 4 = 互联网浏览器
     * 5 = Miiverse与徽章
     * 6 = 网格缩小 (-)
     * 7 = 网格放大 (+)
     */
    val allAppsBtnRect = RectF()
    val appsCloseBtnRect = RectF()
    var isAllAppsDrawerOpen: Boolean = false
    var allInstalledApps: List<GridSlotItem> = emptyList()
    var appsScrollY: Float = 0f
    var targetAppsScrollY: Float = 0f
    var maxAppsScrollY: Float = 0f

    var selectedTopIconIndex: Int = -1
        set(value) {
            field = value
            invalidate()
        }

    var currentTheme: Theme3DS = ThemeCatalog.ALL_THEMES.first()
        set(value) {
            field = value
            invalidate()
        }

    var slots: List<GridSlotItem> = emptyList()
        set(value) {
            field = value
            preloadIcons(value)
            invalidate()
        }

    var gridRows: Int = 2
        set(value) {
            field = value.coerceIn(1, 6)
            scrollOffsetX = 0f
            targetScrollX = 0f
            ensureSelectedVisible()
            invalidate()
        }

    var selectedIndex: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, (slots.size - 1).coerceAtLeast(0))
            field = clamped
            ensureSelectedVisible()
            invalidate()
        }

    var movingSourceIndex: Int = -1
        set(value) {
            field = value
            invalidate()
        }

    var activeFolderTitle: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var unreadNotificationsCount: Int = 1

    private var scrollOffsetX: Float = 0f
    private var targetScrollX: Float = 0f
    private var maxScrollX: Float = 0f
    private var animPhase: Float = 0f

    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val pendingIconKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val apolloRegularTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val apolloMediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val boldTypeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val clipPath = Path()
    private val arrowPath = Path()

    var isGameDescriptionOpen: Boolean = false
        private set
    var gameDescriptionResolver: ((GridSlotItem?) -> String)? = null

    private var descScrollY = 0f
    private var targetDescScrollY = 0f
    private var maxDescScrollY = 0f

    private val gameDescriptionBtnRect = RectF()
    private val descCloseBtnRect = RectF()
    private val descBackBtnRect = RectF()
    private val descLaunchBtnRect = RectF()
    private val descPrevBtnRect = RectF()
    private val descNextBtnRect = RectF()
    private val descContentAreaRect = RectF()
    private var cachedStaticLayout: android.text.StaticLayout? = null
    private var cachedLayoutKey: String = ""

    private val homeSettingsRect = RectF()
    private val gameLibraryBtnRect = RectF()
    private val videoAudioBtnRect = RectF()
    private val autoRandomBtnRect = RectF()
    private val swapScreensBtnRect = RectF()
    private val appletRects = Array(2) { RectF() }
    private val zoomOutRect = RectF()
    private val zoomInRect = RectF()
    private val pageLeftArrowRect = RectF()
    private val pageRightArrowRect = RectF()
    private val bottomLeftBtnRect = RectF()
    private val bottomCenterBtnRect = RectF()
    private val bottomRightBtnRect = RectF()
    private val folderCloseBannerRect = RectF()
    private val stageWellRect = RectF()
    private val slotScreenRects = HashMap<Int, RectF>()
    private val settingsTabRects = Array(4) { RectF() }
    private val settingsCardRects = HashMap<Int, RectF>()
    private var platformPage: Int = 0
    private val pagePrevRect = RectF()
    private val pageNextRect = RectF()

    private val subPanelBackBtnRect = RectF()
    private val intervalValueRect = RectF()
    private val stepMinus5BtnRect = RectF()
    private val stepMinus1BtnRect = RectF()
    private val stepPlus1BtnRect = RectF()
    private val stepPlus5BtnRect = RectF()
    private val presetChipRects = Array(8) { RectF() }
    private val intervalPlayToggleBtnRect = RectF()
    private val advancedCardRects = Array(8) { RectF() }
    private val romDirCardRects = Array(8) { RectF() }
    private var mediaLibScrollY: Float = 0f
    private var targetMediaLibScrollY: Float = 0f
    private var maxMediaLibScrollY: Float = 0f
    private val mediaLibCardRects = mutableListOf<RectF>()
    private val mediaLibRemoveItemRects = mutableListOf<RectF>()
    private val mediaLibContentArea = RectF()
    private val mediaLibAddBtnRect = RectF()
    private val mediaLibEnableAllBtnRect = RectF()
    private val mediaLibDisableAllBtnRect = RectF()
    private val mediaLibRemoveAllBtnRect = RectF()
    private val mediaLibHideMissingCoverBtnRect = RectF()

    // 底部系列与游戏分类管理器布局 Rects 与状态
    data class CategoryGameItemUi(
        val slot: GridSlotItem,
        var isIncluded: Boolean
    )

    private val categoryGamesCountCache = mutableMapOf<String, Int>()
    private var allCategoryGames = listOf<CategoryGameItemUi>()
    private var filteredCategoryGames = listOf<CategoryGameItemUi>()
    private var categoryGameSearchQuery: String = ""
    private var categoryGameFilterTab: Int = 0 // 0: 全部, 1: 已收录, 2: 未收录

    var editingCategory: CustomCategoryItem? = null
    var editingCategoryName: String = ""
    var editingCategoryIsSeries: Boolean = true
    var editingCategorySymbol: String = "🎮"
    var editingCategoryColor: Int = Color.parseColor("#E11D48")

    private var pendingDeleteCategoryId: String? = null
    private var pendingDeleteTimestamp: Long = 0L

    private var pendingResetCategories: Boolean = false
    private var pendingResetTimestamp: Long = 0L

    private val catMgrAddBtnRect = RectF()
    private val catMgrResetBtnRect = RectF()
    private val categoryContentArea = RectF()
    private val catGameContentArea = RectF()

    private val catGameSearchBoxRect = RectF()
    private val catGameSearchClearRect = RectF()
    private val catGameTabAllRect = RectF()
    private val catGameTabIncludedRect = RectF()
    private val catGameTabNotIncludedRect = RectF()

    private val categoryItemRects = ArrayList<RectF>()
    private val catUpBtnRects = ArrayList<RectF>()
    private val catDownBtnRects = ArrayList<RectF>()
    private val catEditBtnRects = ArrayList<RectF>()
    private val catDeleteBtnRects = ArrayList<RectF>()
    private val catManageGamesBtnRects = ArrayList<RectF>()

    private val catGameItemRects = ArrayList<RectF>()
    private val catGameActionBtnRects = ArrayList<RectF>()

    private val catEditorSeriesToggleRect = RectF()
    private val catEditorGenreToggleRect = RectF()
    private val catEditorNameBoxRect = RectF()
    private val catEditorSaveBtnRect = RectF()
    private val catEditorPresetChips = mutableListOf<Pair<RectF, String>>()
    private val catEditorSymbolChips = mutableListOf<Pair<RectF, String>>()
    private val catEditorColorChips = mutableListOf<Pair<RectF, Int>>()

    private val catGameQuickSearchChips = mutableListOf<Pair<RectF, String>>()

    private var categoryScrollY = 0f
    private var targetCategoryScrollY = 0f
    private var maxCategoryScrollY = 0f

    private var catGameScrollY = 0f
    private var targetCatGameScrollY = 0f
    private var maxCatGameScrollY = 0f

    fun updateCategoryState(
        newCategories: List<CustomCategoryItem>,
        newIndex: Int,
        newCount: Int,
        newSlots: List<GridSlotItem>
    ) {
        this.categories = newCategories
        this.currentCategoryIndex = newIndex
        this.currentCategoryGameCount = newCount
        this.slots = newSlots
        this.selectedIndex = 0
        this.scrollOffsetX = 0f
        this.targetScrollX = 0f
        invalidate()
    }

    fun openCategoryManager() {
        isSettingsPanelOpen = true
        settingsTabIndex = 3
        settingsSubPanel = SettingsSubPanel.CATEGORY_MANAGER
        categoryScrollY = 0f
        targetCategoryScrollY = 0f
        pendingDeleteCategoryId = null
        pendingResetCategories = false
        categoryGamesCountCache.clear()
        listener?.onGetCategoryGameCounts()?.let {
            categoryGamesCountCache.putAll(it)
        }
        invalidate()
    }

    fun openCategoryGameManager(cat: CustomCategoryItem) {
        managingCategory = cat
        isSettingsPanelOpen = true
        settingsTabIndex = 3
        settingsSubPanel = SettingsSubPanel.CATEGORY_GAME_MANAGER
        categoryGameSearchQuery = ""
        categoryGameFilterTab = 0
        val rawGames = listener?.onGetAllCandidateGames() ?: emptyList()
        allCategoryGames = rawGames.map { g ->
            CategoryGameItemUi(g, listener?.onIsGameInCategory(cat.id, g) ?: false)
        }
        updateFilteredCategoryGames()
        catGameScrollY = 0f
        targetCatGameScrollY = 0f
        invalidate()
    }

    fun openCategoryEditor(category: CustomCategoryItem?) {
        editingCategory = category
        if (category != null) {
            editingCategoryName = category.displayName
            editingCategoryIsSeries = category.isSeries
            editingCategorySymbol = category.badgeSymbol
            editingCategoryColor = category.accentColor
        } else {
            editingCategoryName = ""
            editingCategoryIsSeries = true
            editingCategorySymbol = "🎮"
            editingCategoryColor = Color.parseColor("#E11D48")
        }
        isSettingsPanelOpen = true
        settingsTabIndex = 3
        settingsSubPanel = SettingsSubPanel.CATEGORY_EDITOR
        invalidate()
    }

    fun updateFilteredCategoryGames() {
        val q = categoryGameSearchQuery.trim().lowercase()
        filteredCategoryGames = allCategoryGames.filter { item ->
            val matchesTab = when (categoryGameFilterTab) {
                1 -> item.isIncluded
                2 -> !item.isIncluded
                else -> true
            }
            if (!matchesTab) return@filter false
            if (q.isBlank()) return@filter true
            item.slot.title.lowercase().contains(q) ||
                item.slot.getEffectivePlatform().lowercase().contains(q) ||
                item.slot.romPath.lowercase().contains(q)
        }
        val cardH = 72f
        val cardGap = 8f
        val totalH = filteredCategoryGames.size * (cardH + cardGap) + 16f
        maxCatGameScrollY = (totalH - catGameContentArea.height()).coerceAtLeast(0f)
        targetCatGameScrollY = targetCatGameScrollY.coerceIn(0f, maxCatGameScrollY)
        catGameScrollY = targetCatGameScrollY
    }
    private val mediaLibPrevPageRect = RectF()
    private val mediaLibNextPageRect = RectF()

    private var mediaLibOriginSubPanel: SettingsSubPanel = SettingsSubPanel.NONE
    private var pendingRemovePath: String? = null
    private var pendingRemoveTimestamp: Long = 0L
    private var pendingRemoveAll: Boolean = false
    private var pendingRemoveAllTimestamp: Long = 0L

    private val validRomExtensions = setOf(
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
    var pickerCurrentDir: java.io.File? = null
    var pickerSubDirs: List<java.io.File> = emptyList()
    var pickerRomCountInCurrentDir: Int = 0
    var pickerStorageRoots: List<java.io.File> = emptyList()
    private var pickerScrollY: Float = 0f
    private var targetPickerScrollY: Float = 0f
    private var maxPickerScrollY: Float = 0f
    private val pickerFolderRects = mutableListOf<RectF>()
    private val pickerStorageTabRects = mutableListOf<RectF>()
    private val pickerUpItemRect = RectF()
    private val pickerContentArea = RectF()
    private val pickerConfirmBtnRect = RectF()
    private val pickerCancelBtnRect = RectF()
    private val scanHudRect = RectF()
    private val scanProgressBarTrackRect = RectF()
    private val scanProgressBarFillRect = RectF()

    private val emuCardRects = Array(8) { RectF() }
    private val emuPickCustomAppRect = RectF()
    private val emuDiagnosticsBtnRect = RectF()
    private val diagCardRects = Array(8) { RectF() }

    private val emuStatusBannerRect = RectF()
    private val emuPegasusPreviewBtnRect = RectF()
    private val pegasusInspectPlat3dsRect = RectF()
    private val pegasusInspectPlatNdsRect = RectF()
    private val pegasusInspectModeToggleRect = RectF()
    private val pegasusInspectRescanRect = RectF()
    private val pegasusInspectModeBottomRect = RectF()

    private val filterToggleSwitchRect = RectF()
    private val filterTypeRects = Array(4) { RectF() }
    private val filterOpacityRects = Array(4) { RectF() }
    private val filterPreviewBoxRect = RectF()
    private var subscreenPreviewShader: BitmapShader? = null
    private var lastPreviewType: String = ""
    private var lastPreviewOpacity: Int = -1

    private val mainFilterAuto2DSwitchRect = RectF()
    private val mainFilterGlobalSwitchRect = RectF()
    private val mainFilterTypeRects = Array(4) { RectF() }
    private val mainFilterOpacityRects = Array(4) { RectF() }
    private val mainFilterPreviewBoxRect = RectF()
    private var mainPreviewShader: BitmapShader? = null
    private var lastMainPreviewType: String = ""
    private var lastMainPreviewOpacity: Int = -1

    private val scanScopeOptionRects = Array(3) { RectF() }
    private val scanManualActionBtnRect = RectF()

    private val animatedCursorRect = RectF()
    private var hasCursorInit = false
    private val iconExecutor = java.util.concurrent.Executors.newFixedThreadPool(4)

    /**
     * 精简优化后的下屏顶栏中央 3 大核心功能（去除冗余图标，增加独立的「画面滤镜与设置」入口，左右 2+3+2 完全对称）
     */
    private val applets = arrayOf(
        SystemAppletType.MIIVERSE,
        SystemAppletType.GAME_LIBRARY
    )

    private val appletAssetNames = arrayOf(
        "3ds_art/applet_miiverse.png",
        ""
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleTap(e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                handleLongPress(e.x, e.y)
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isAllAppsDrawerOpen) {
                    targetAppsScrollY = (targetAppsScrollY + distanceY).coerceIn(0f, maxAppsScrollY)
                    invalidate()
                    return true
                }
                if (isGameDescriptionOpen) {
                    targetDescScrollY = (targetDescScrollY + distanceY).coerceIn(0f, maxDescScrollY)
                    invalidate()
                    return true
                }
                if (isSettingsPanelOpen && settingsSubPanel == SettingsSubPanel.PEGASUS_METADATA_PREVIEW) {
                    targetPegasusInspectScrollY = (targetPegasusInspectScrollY + distanceY).coerceIn(0f, maxPegasusInspectScrollY)
                    pegasusInspectScrollY = targetPegasusInspectScrollY
                    invalidate()
                    return true
                }
                if (isSettingsPanelOpen && settingsSubPanel == SettingsSubPanel.ROM_DIRECTORY_PICKER) {
                    targetPickerScrollY = (targetPickerScrollY + distanceY).coerceIn(0f, maxPickerScrollY)
                    pickerScrollY = targetPickerScrollY
                    invalidate()
                    return true
                }
                if (isSettingsPanelOpen && settingsSubPanel == SettingsSubPanel.MEDIA_LIBRARY_MANAGER) {
                    targetMediaLibScrollY = (targetMediaLibScrollY + distanceY).coerceIn(0f, maxMediaLibScrollY)
                    mediaLibScrollY = targetMediaLibScrollY
                    invalidate()
                    return true
                }
                if (isSettingsPanelOpen && settingsSubPanel == SettingsSubPanel.CATEGORY_MANAGER) {
                    targetCategoryScrollY = (targetCategoryScrollY + distanceY).coerceIn(0f, maxCategoryScrollY)
                    categoryScrollY = targetCategoryScrollY
                    invalidate()
                    return true
                }
                if (isSettingsPanelOpen && settingsSubPanel == SettingsSubPanel.CATEGORY_GAME_MANAGER) {
                    targetCatGameScrollY = (targetCatGameScrollY + distanceY).coerceIn(0f, maxCatGameScrollY)
                    catGameScrollY = targetCatGameScrollY
                    invalidate()
                    return true
                }
                if (abs(distanceX) > abs(distanceY)) {
                    targetScrollX = (targetScrollX + distanceX).coerceIn(0f, maxScrollX)
                    scrollOffsetX = targetScrollX
                    invalidate()
                    return true
                }
                return false
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (isGameDescriptionOpen) {
                    targetDescScrollY = (targetDescScrollY - velocityY * 0.35f).coerceIn(0f, maxDescScrollY)
                    invalidate()
                    return true
                }
                if (isSettingsPanelOpen && settingsSubPanel == SettingsSubPanel.ROM_DIRECTORY_PICKER) {
                    targetPickerScrollY = (targetPickerScrollY - velocityY * 0.30f).coerceIn(0f, maxPickerScrollY)
                    invalidate()
                    return true
                }
                if (isSettingsPanelOpen && settingsSubPanel == SettingsSubPanel.MEDIA_LIBRARY_MANAGER) {
                    targetMediaLibScrollY = (targetMediaLibScrollY - velocityY * 0.30f).coerceIn(0f, maxMediaLibScrollY)
                    invalidate()
                    return true
                }
                if (isSettingsPanelOpen && settingsSubPanel == SettingsSubPanel.CATEGORY_MANAGER) {
                    targetCategoryScrollY = (targetCategoryScrollY - velocityY * 0.30f).coerceIn(0f, maxCategoryScrollY)
                    invalidate()
                    return true
                }
                if (isSettingsPanelOpen && settingsSubPanel == SettingsSubPanel.CATEGORY_GAME_MANAGER) {
                    targetCatGameScrollY = (targetCatGameScrollY - velocityY * 0.30f).coerceIn(0f, maxCatGameScrollY)
                    invalidate()
                    return true
                }
                val pageStep = width * 0.72f
                if (abs(velocityX) > 450f) {
                    targetScrollX = if (velocityX < 0) {
                        (targetScrollX + pageStep).coerceIn(0f, maxScrollX)
                    } else {
                        (targetScrollX - pageStep).coerceIn(0f, maxScrollX)
                    }
                    invalidate()
                    return true
                }
                return false
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        animPhase += 0.055f
        scrollOffsetX += (targetScrollX - scrollOffsetX) * 0.28f

        val topBarH = (h * 0.135f).coerceIn(56f, 118f)
        val bottomBarH = (h * 0.105f).coerceIn(58f, 96f)

        // 1. 绘制全屏无界一体式 3DS 下屏主题底板
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            currentTheme.lowerBgTop,
            currentTheme.lowerBgBottom,
            Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // 2. 绘制顶部 3DS 独立大图标栏（左侧 2 枚 + 中央 3 枚精品宽体胶囊 + 右侧 2 枚，视觉严谨对称）
        drawAuthenticTopSystemBar(canvas, w, topBarH)

        val gridTop = if (activeFolderTitle != null && !isSettingsPanelOpen) topBarH + 40f else topBarH + 6f
        val gridBottom = h - bottomBarH - 6f

        if (isAllAppsDrawerOpen) {
            drawAllAppsDrawerPanel(canvas, w, h, gridTop, gridBottom, bottomBarH)
            drawAuthenticBottomActionBar(canvas, w, h, bottomBarH)
        } else if (isSettingsPanelOpen) {
            // 原版 3DS 风格的下屏内嵌「视频滤镜与系统设置」面板（开启时上屏视频继续实时播放预览！）
            drawAuthentic3dsSettingsPanel(canvas, w, h, gridTop, gridBottom, bottomBarH)
        } else if (isGameDescriptionOpen) {
            // 原版 3DS 风格的下屏内嵌「游戏介绍与详情手册」面板（上屏视频与3D卡带继续实时预览播放！）
            drawGameDescriptionPanel(canvas, w, h, gridTop, gridBottom, bottomBarH)
        } else {
            // 3. 绘制中央 3DS 实机内凹圆角大舞台框与可滚动卡带网格（饱满占满中间区域，零多余留白）
            if (activeFolderTitle != null) {
                drawFolderHeaderStrip(canvas, w, topBarH, gridTop)
            }
            drawCartridgeGrid(canvas, w, gridTop, gridBottom)

            // 4. 绘制左右翻页边缘立体三角按钮
            drawPageTurnArrows(canvas, w, gridTop, gridBottom)

            // 5. 绘制底部 3DS 连体三段式立体底栏
            drawAuthenticBottomActionBar(canvas, w, h, bottomBarH)
        }

        StartupProfiler.markFirstFrameDraw()
        postInvalidateDelayed(16)
    }

    private fun drawFolderHeaderStrip(canvas: Canvas, w: Float, topBarH: Float, stripBottom: Float) {
        folderCloseBannerRect.set(22f, topBarH + 2f, w - 22f, stripBottom - 4f)
        paint.style = Paint.Style.FILL
        paint.color = currentTheme.cursorMainColor
        paint.alpha = 255
        canvas.drawRoundRect(folderCloseBannerRect, 12f, 12f, paint)
        textPaint.color = Color.WHITE
        textPaint.textSize = (folderCloseBannerRect.height() * 0.45f).coerceIn(13f, 18f)
        textPaint.textAlign = Paint.Align.CENTER
        val cy = folderCloseBannerRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("📂 当前文件夹：${activeFolderTitle}  （点击此处或按 B 键返回桌面）", w / 2f, cy, textPaint)
    }

    /**
     * 还原 3DS 实机下屏顶栏：
     * - 左上角：HOME 菜单设置按钮 + 「▶ 视频原声 / 🔊 原声播放中」一键切换按钮（点击暂停 BGM 并放出上屏视频原声，再按恢复）
     * - 中央：5 枚大号高饱和度 3D 独立图标按钮（游戏笔记、好友列表、通知、浏览器、徽章屋）
     * - 右上角：[-] 与 [+] 网格缩放按钮（左右各两枚按键，100% 视觉对称）
     */
    private fun drawAuthenticTopSystemBar(canvas: Canvas, w: Float, barH: Float) {
        val padY = barH * 0.11f
        val btnSize = barH - padY * 2f
        val sideMargin = 16f
        val smallBtnW = btnSize * 0.96f

        // 3. 顶栏所有图标从最左侧边框平行等距分布到最右侧边框 (共 8 枚 3DS 独立大图标，宽敞通透)
        val totalIcons = 8
        val availBarW = w - sideMargin * 2f
        val gap = ((availBarW - smallBtnW * totalIcons) / (totalIcons - 1).coerceAtLeast(1)).coerceAtLeast(6f)
        val leftStart = sideMargin

        // 0: HOME 菜单与个性化设置 (左上角第 1 个图标，方案 A：专注主题、声音、副屏伴侣与桌面个性化)
        var curLeft = leftStart
        homeSettingsRect.set(curLeft, padY, curLeft + smallBtnW, barH - padY)
        val homeBmp = loadAssetIcon("3ds_art/applet_home_settings.png")
        if (homeBmp != null) {
            canvas.drawBitmap(homeBmp, null, homeSettingsRect, bitmapPaint)
        } else {
            drawBeveledButton(canvas, homeSettingsRect, currentTheme.bottomBarButtonColor, currentTheme.slotBorderColor, 14f)
        }

        // 1: 全部已安装应用·副屏抽屉
        curLeft += smallBtnW + gap
        allAppsBtnRect.set(curLeft, padY, curLeft + smallBtnW, barH - padY)
        val appsFill = if (isAllAppsDrawerOpen) Color.parseColor("#E0F2FE") else currentTheme.slotFillColor
        val appsBorder = if (isAllAppsDrawerOpen) Color.parseColor("#0284C7") else currentTheme.slotBorderColor
        drawBeveledButton(canvas, allAppsBtnRect, appsFill, appsBorder, 14f, isPrimary = isAllAppsDrawerOpen)
        drawAllAppsDrawerButtonSymbol(canvas, allAppsBtnRect, isAllAppsDrawerOpen)

        // 2: 视频原声播放切换
        curLeft += smallBtnW + gap
        videoAudioBtnRect.set(curLeft, padY, curLeft + smallBtnW, barH - padY)
        val audioBtnFill = if (isVideoAudioEnabled) Color.parseColor("#FFE4E6") else currentTheme.slotFillColor
        val audioBtnBorder = if (isVideoAudioEnabled) Color.parseColor("#E60012") else currentTheme.slotBorderColor
        drawBeveledButton(canvas, videoAudioBtnRect, audioBtnFill, audioBtnBorder, 14f, isPrimary = isVideoAudioEnabled)
        drawVideoAudioButtonSymbol(canvas, videoAudioBtnRect, isVideoAudioEnabled)

        // 2: 自动随机轮播
        curLeft += smallBtnW + gap
        autoRandomBtnRect.set(curLeft, padY, curLeft + smallBtnW, barH - padY)
        val autoRandomFill = if (isAutoRandomPlaying) Color.parseColor("#FEF3C7") else currentTheme.slotFillColor
        val autoRandomBorder = if (isAutoRandomPlaying) Color.parseColor("#F59E0B") else currentTheme.slotBorderColor
        drawBeveledButton(canvas, autoRandomBtnRect, autoRandomFill, autoRandomBorder, 14f, isPrimary = isAutoRandomPlaying)
        drawAutoRandomButtonSymbol(canvas, autoRandomBtnRect, isAutoRandomPlaying)

        // 3: 桌面徽章屋 (纯正 3DS 徽章)
        curLeft += smallBtnW + gap
        appletRects[0].set(curLeft, padY, curLeft + smallBtnW, barH - padY)
        drawBeveledButton(canvas, appletRects[0], currentTheme.slotFillColor, currentTheme.slotBorderColor, 14f)
        val badgeBmp = loadAssetIcon(appletAssetNames[0])
        if (badgeBmp != null) {
            val pad = smallBtnW * 0.10f
            canvas.drawBitmap(badgeBmp, null, RectF(curLeft + pad, padY + pad, curLeft + smallBtnW - pad, barH - padY - pad), bitmapPaint)
        }

        // 4: 游戏库与模拟器系统设置 (倒数第 3 个图标，方案 A：专注 ROM 路径、媒体库挂载与模拟器内核设置)
        curLeft += smallBtnW + gap
        appletRects[1].set(curLeft, padY, curLeft + smallBtnW, barH - padY)
        val isAdvActive = isSettingsPanelOpen && (settingsSubPanel == SettingsSubPanel.ADVANCED_SETTINGS || settingsTabIndex == 3)
        val advFill = if (isAdvActive) Color.parseColor("#FEF3C7") else currentTheme.slotFillColor
        val advBorder = if (isAdvActive) Color.parseColor("#F59E0B") else currentTheme.slotBorderColor
        drawBeveledButton(canvas, appletRects[1], advFill, advBorder, 14f, isPrimary = isAdvActive)
        drawGameLibrarySettingsIcon(canvas, RectF(curLeft + smallBtnW * 0.12f, padY + smallBtnW * 0.12f, curLeft + smallBtnW * 0.88f, barH - padY - smallBtnW * 0.12f))

        // 5: [-] 缩小网格按钮 (倒数第 2 个图标)
        curLeft += smallBtnW + gap
        zoomOutRect.set(curLeft, padY, curLeft + smallBtnW, barH - padY)
        drawBeveledButton(canvas, zoomOutRect, currentTheme.slotFillColor, currentTheme.slotBorderColor, 14f)
        drawZoomButtonSymbol(canvas, zoomOutRect, isZoomIn = false)

        // 6: [+] 放大网格按钮 (倒数第 1 个图标)
        curLeft += smallBtnW + gap
        zoomInRect.set(curLeft, padY, curLeft + smallBtnW, barH - padY)
        drawBeveledButton(canvas, zoomInRect, currentTheme.slotFillColor, currentTheme.slotBorderColor, 14f)
        drawZoomButtonSymbol(canvas, zoomInRect, isZoomIn = true)

        // 4. 当用户按 L2 / R2 键在顶栏 8 枚图标间切换时，绘制 3DS 四角发光选中框与悬浮功能名牌
        if (selectedTopIconIndex in 0..7) {
            val targetRect = when (selectedTopIconIndex) {
                0 -> homeSettingsRect
                1 -> allAppsBtnRect
                2 -> videoAudioBtnRect
                3 -> autoRandomBtnRect
                4 -> appletRects[0]
                5 -> appletRects[1]
                6 -> zoomOutRect
                else -> zoomInRect
            }
            draw3dsCornerCursor(canvas, targetRect)

            val topLabel = when (selectedTopIconIndex) {
                0 -> "🏠 HOME菜单·主题与个性化设置 (按A打开)"
                1 -> "📱 全部已安装应用·副屏抽屉 (按A打开)"
                2 -> if (isVideoAudioEnabled) "🔊 关闭视频原声·恢复背景音乐 (按A切换)" else "▶ 暂停背景音乐·播放视频原声 (按A切换)"
                3 -> if (isAutoRandomPlaying) "🎲 自动轮播游戏预览中 [每${autoRandomIntervalSec}秒] (按A停止)" else "🎲 开启自动随机轮播 [每${autoRandomIntervalSec}秒] (按A开启)"
                4 -> "🏅 桌面徽章屋 (按A打开)"
                5 -> "⚙ 游戏库与模拟器系统设置 (按A打开)"
                6 -> "➖ 缩小游戏图标 (按A执行)"
                else -> "➕ 放大游戏图标 (按A执行)"
            }
            val tipText = "L2 ◀  $topLabel  ▶ R2"
            textPaint.textSize = (barH * 0.24f).coerceIn(12f, 18f)
            val tipW = textPaint.measureText(tipText) + 28f
            val tipH = textPaint.textSize * 1.65f
            val tipCx = targetRect.centerX().coerceIn(tipW / 2f + 12f, w - tipW / 2f - 12f)
            val tipRect = RectF(tipCx - tipW / 2f, barH - 4f, tipCx + tipW / 2f, barH - 4f + tipH)

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(225, 20, 32, 48)
            canvas.drawRoundRect(tipRect, tipH / 2f, tipH / 2f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f
            paint.color = currentTheme.cursorMainColor
            canvas.drawRoundRect(tipRect, tipH / 2f, tipH / 2f, paint)
            paint.style = Paint.Style.FILL

            textPaint.color = Color.WHITE
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                tipText,
                tipRect.centerX(),
                tipRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f,
                textPaint
            )
        }
    }

    /**
     * 绘制 3DS 拟物风格「游戏库与模拟器平台」专属立体图标（3DS 经典游戏卡带造型 + 掌机十字键与微缩双屏立体徽标）
     */
    /**
     * 绘制 3DS 原版上下双屏互换立体图标（双液晶屏微缩轮廓 + 动态方向互换指示箭头）
     */
    private fun drawSwapScreensIcon(canvas: Canvas, r: RectF, isSwapped: Boolean) {
        val cx = r.centerX()
        val cy = r.centerY()
        val s = minOf(r.width(), r.height()) * 0.46f

        val screenW = s * 1.34f
        val screenH = s * 0.60f
        val gap = s * 0.36f

        val topRect = RectF(cx - screenW / 2f, cy - (screenH + gap / 2f), cx + screenW / 2f, cy - gap / 2f)
        val botRect = RectF(cx - screenW / 2f, cy + gap / 2f, cx + screenW / 2f, cy + screenH + gap / 2f)

        // 上屏底色与下屏底色（互换时高光跟随反转）
        paint.style = Paint.Style.FILL
        paint.color = if (isSwapped) Color.parseColor("#0284C7") else Color.parseColor("#475569")
        canvas.drawRoundRect(topRect, 3.5f, 3.5f, paint)

        paint.color = if (isSwapped) Color.parseColor("#475569") else Color.parseColor("#0284C7")
        canvas.drawRoundRect(botRect, 3.5f, 3.5f, paint)

        // 边框高光
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        paint.color = Color.WHITE
        canvas.drawRoundRect(topRect, 3.5f, 3.5f, paint)
        canvas.drawRoundRect(botRect, 3.5f, 3.5f, paint)

        // 中央垂直互换指示箭头 (⇅)
        paint.style = Paint.Style.FILL
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = s * 0.76f
        textPaint.color = if (isSwapped) Color.parseColor("#00E5FF") else currentTheme.cursorMainColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("⇅", cx, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
    }

    private fun drawGameLibraryIcon(canvas: Canvas, r: RectF) {
        val cx = r.centerX()
        val cy = r.centerY()
        val s = r.width() * 0.44f

        // 1. 3DS 经典游戏卡带主体外框（带标志性右上角防呆斜切卡槽缺角）
        val cartW = s * 1.48f
        val cartH = s * 1.58f
        val cartRect = RectF(cx - cartW / 2f, cy - cartH / 2f, cx + cartW / 2f, cy + cartH / 2f)
        val cartRadius = 5f

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            cartRect.left, cartRect.top, cartRect.right, cartRect.bottom,
            Color.parseColor("#38BDF8"),
            Color.parseColor("#0284C7"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(cartRect, cartRadius, cartRadius, paint)
        paint.shader = null

        // 2. 卡带上方卡槽凹槽与金手指微缩触点
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f
        paint.color = Color.WHITE
        canvas.drawRoundRect(cartRect, cartRadius, cartRadius, paint)

        // 3. 卡带中央微缩贴纸标签与十字键徽标
        val labelW = cartW * 0.72f
        val labelH = cartH * 0.65f
        val labelRect = RectF(cx - labelW / 2f, cy - labelH / 2f + 1f, cx + labelW / 2f, cy + labelH / 2f + 1f)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRoundRect(labelRect, 3f, 3f, paint)

        // 4. 标签内的深色游戏掌机手柄/十字键
        paint.color = Color.parseColor("#0369A1")
        val dpadArm = labelW * 0.28f
        val dpadThick = labelW * 0.14f
        // 横向臂
        canvas.drawRoundRect(
            RectF(cx - dpadArm, cy + 1f - dpadThick / 2f, cx + dpadArm, cy + 1f + dpadThick / 2f),
            2f, 2f, paint
        )
        // 纵向臂
        canvas.drawRoundRect(
            RectF(cx - dpadThick / 2f, cy + 1f - dpadArm, cx + dpadThick / 2f, cy + 1f + dpadArm),
            2f, 2f, paint
        )
        // 右上角两枚微缩动作按键点 (A / B)
        paint.color = Color.parseColor("#EF4444")
        canvas.drawCircle(cx + labelW * 0.32f, cy - labelH * 0.20f, 2.4f, paint)
        paint.color = Color.parseColor("#F59E0B")
        canvas.drawCircle(cx + labelW * 0.22f, cy - labelH * 0.05f, 2.4f, paint)
        paint.style = Paint.Style.FILL
    }

    /**
     * 绘制 3DS 拟物风格「画面滤镜与设置」专属立体图标（三原色滤镜光环 + 3DS 精密齿轮徽标）
     */
        /**
     * 绘制倒数第 3 个图标「游戏库与模拟器高级设置」立体机械齿轮图标
     */
    private fun drawGameLibrarySettingsIcon(canvas: Canvas, r: RectF) {
        val cx = r.centerX()
        val cy = r.centerY()
        val rad = r.width() * 0.26f

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            r.left, r.top, r.right, r.bottom,
            Color.parseColor("#334155"),
            Color.parseColor("#1E293B"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(r, rad, rad, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f
        paint.color = Color.parseColor("#94A3B8")
        canvas.drawRoundRect(r, rad, rad, paint)
        paint.style = Paint.Style.FILL

        // 绘制高精 3DS 机械齿轮 (8 个精密轮齿)
        val outerR = r.width() * 0.28f
        val innerR = r.width() * 0.18f
        paint.color = Color.parseColor("#F8FAFC")
        val toothW = r.width() * 0.08f
        val toothH = r.width() * 0.09f
        for (i in 0 until 8) {
            val angle = i * 45f * Math.PI.toFloat() / 180f
            val tx = cx + Math.cos(angle.toDouble()).toFloat() * (outerR + toothH * 0.2f)
            val ty = cy + Math.sin(angle.toDouble()).toFloat() * (outerR + toothH * 0.2f)
            canvas.save()
            canvas.rotate(i * 45f, tx, ty)
            canvas.drawRoundRect(RectF(tx - toothW / 2f, ty - toothH / 2f, tx + toothW / 2f, ty + toothH / 2f), 2f, 2f, paint)
            canvas.restore()
        }

        // 齿轮外环圆盘
        canvas.drawCircle(cx, cy, outerR, paint)
        // 齿轮内凹槽
        paint.color = Color.parseColor("#1E293B")
        canvas.drawCircle(cx, cy, innerR, paint)
        // 中心高亮金/青轴心
        paint.color = Color.parseColor("#F59E0B")
        canvas.drawCircle(cx, cy, innerR * 0.52f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, innerR * 0.22f, paint)
    }

    private fun drawVideoFilterSettingsIcon(canvas: Canvas, r: RectF) {
        val cx = r.centerX()
        val cy = r.centerY()
        val rad = r.width() * 0.26f

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            r.left, r.top, r.right, r.bottom,
            currentVideoFilter.previewColorTop,
            currentVideoFilter.previewColorBottom,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(r, rad, rad, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.WHITE
        canvas.drawRoundRect(r, rad, rad, paint)
        paint.style = Paint.Style.FILL

        // 三色滤镜交叠圆环
        val cr = r.width() * 0.19f
        val offset = r.width() * 0.11f
        paint.color = Color.argb(215, 255, 90, 120)
        canvas.drawCircle(cx - offset, cy - offset * 0.6f, cr, paint)
        paint.color = Color.argb(215, 50, 230, 160)
        canvas.drawCircle(cx + offset, cy - offset * 0.6f, cr, paint)
        paint.color = Color.argb(215, 60, 190, 255)
        canvas.drawCircle(cx, cy + offset * 0.85f, cr, paint)

        // 中心白色齿轮圆环
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, cr * 0.62f, paint)
        paint.color = currentVideoFilter.previewColorBottom
        canvas.drawCircle(cx, cy, cr * 0.28f, paint)
    }

    /**
     * 绘制左上角「视频原声播放 / BGM 切换」立体图标：
     * - 未开启时：蓝色圆角块 + 白色播放三角 `▶`
     * - 开启原声时：红色/珊瑚色发光圆角块 + 动态声波喇叭/均衡器条
     */
    private fun calculateColorLuminance(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun getHighContrastButtonTextColor(btnBgColor: Int, preferredColor: Int): Int {
        val bgLum = calculateColorLuminance(btnBgColor)
        val textLum = calculateColorLuminance(preferredColor)
        val lighter = maxOf(bgLum, textLum)
        val darker = minOf(bgLum, textLum)
        val cr = (lighter + 0.05f) / (darker + 0.05f)
        if (cr >= 3.2f) return preferredColor
        return if (bgLum > 0.5f) Color.parseColor("#1A202C") else Color.parseColor("#FFFFFF")
    }

    private fun truncateText(text: String, maxW: Float, p: Paint): String {
        if (p.measureText(text) <= maxW) return text
        var str = text
        while (str.length > 1 && p.measureText("$str…") > maxW) {
            str = str.substring(0, str.length - 1)
        }
        return "$str…"
    }

    private fun drawAllAppsDrawerButtonSymbol(canvas: Canvas, r: RectF, active: Boolean) {
        val cx = r.centerX()
        val cy = r.centerY()
        val s = minOf(r.width(), r.height()) * 0.28f
        paint.style = Paint.Style.FILL
        paint.color = if (active) Color.parseColor("#0284C7") else Color.parseColor("#0EA5E9")
        paint.alpha = 255

        val badgeRect = RectF(cx - s * 1.15f, cy - s * 1.15f, cx + s * 1.15f, cy + s * 1.15f)
        canvas.drawRoundRect(badgeRect, s * 0.45f, s * 0.45f, paint)

        // 绘制 4 个精致的 3DS 小方块应用网格图标 (2x2)
        paint.color = Color.WHITE
        val tilePad = s * 0.22f
        val tileGap = s * 0.20f
        val tileS = (s * 2.3f - tilePad * 2f - tileGap) / 2f
        val startX = cx - s * 1.15f + tilePad
        val startY = cy - s * 1.15f + tilePad

        for (row in 0..1) {
            for (col in 0..1) {
                val tLeft = startX + col * (tileS + tileGap)
                val tTop = startY + row * (tileS + tileGap)
                canvas.drawRoundRect(RectF(tLeft, tTop, tLeft + tileS, tTop + tileS), 3f, 3f, paint)
            }
        }
    }

    fun openAllAppsDrawer() {
        if (isSettingsPanelOpen) {
            closeSettingsPanel()
        }
        if (isGameDescriptionOpen) {
            closeGameDescription()
        }
        allInstalledApps = listener?.onGetAllInstalledApps() ?: emptyList()
        preloadIcons(allInstalledApps)
        isAllAppsDrawerOpen = true
        appsScrollY = 0f
        targetAppsScrollY = 0f
        SoundEffectEngine.playSelectApplet()
        invalidate()
    }

    fun closeAllAppsDrawer() {
        if (isAllAppsDrawerOpen) {
            isAllAppsDrawerOpen = false
            selectedTopIconIndex = -1
            SoundEffectEngine.playBackCancel()
            invalidate()
        }
    }

    fun toggleAllAppsDrawer() {
        if (isAllAppsDrawerOpen) {
            closeAllAppsDrawer()
        } else {
            openAllAppsDrawer()
        }
    }

    private fun drawAllAppsDrawerPanel(
        canvas: Canvas,
        w: Float,
        h: Float,
        panelTop: Float,
        panelBottom: Float,
        bottomBarH: Float
    ) {
        val sideMargin = 16f
        stageWellRect.set(sideMargin, panelTop, w - sideMargin, panelBottom)
        val stageRadius = 26f

        // 1. 底盘投影
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(32, 0, 0, 0)
        canvas.drawRoundRect(
            RectF(stageWellRect.left, stageWellRect.top + 3f, stageWellRect.right, stageWellRect.bottom + 3f),
            stageRadius, stageRadius, paint
        )

        // 2. 底盘渐变背景卡片
        val isDark = calculateColorLuminance(currentTheme.bottomBarButtonColor) < 0.45f
        val bgTop = if (isDark) blendColor(currentTheme.lowerBgTop, Color.WHITE, 0.12f) else blendColor(currentTheme.slotFillColor, Color.WHITE, 0.45f)
        val bgBottom = currentTheme.lowerBgBottom
        paint.shader = LinearGradient(
            0f, stageWellRect.top, 0f, stageWellRect.bottom,
            bgTop, bgBottom, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(stageWellRect, stageRadius, stageRadius, paint)
        paint.shader = null

        // 3. Header 区域 (高度 64px)
        val headerH = 64f
        val headerTop = stageWellRect.top + 8f
        val headerBottom = headerTop + headerH
        val headerLeft = stageWellRect.left + 18f
        val headerRight = stageWellRect.right - 18f

        // 右上角关闭按钮 [ ✕ 关闭 ]
        val closeBtnW = 96f
        val closeBtnH = 40f
        appsCloseBtnRect.set(headerRight - closeBtnW, headerTop + (headerH - closeBtnH) / 2f, headerRight, headerTop + (headerH + closeBtnH) / 2f)
        draw3dsSculptedDockButton(
            canvas = canvas,
            r = appsCloseBtnRect,
            radius = closeBtnH * 0.44f,
            isPrimary = false,
            accentOverride = Color.parseColor("#94A3B8"),
            drawOutline = true
        )
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 17f
        textPaint.color = if (isDark) Color.WHITE else Color.parseColor("#334155")
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("✕ 关闭", appsCloseBtnRect.centerX(), appsCloseBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // Header 左侧标题与副标题
        textPaint.typeface = boldTypeface
        textPaint.textSize = 24f
        textPaint.color = currentTheme.textPrimaryColor
        textPaint.textAlign = Paint.Align.LEFT
        val titleText = "📱 全部已安装应用 (Android Apps)"
        val titleY = headerTop + 28f
        canvas.drawText(titleText, headerLeft, titleY, textPaint)

        textPaint.typeface = apolloRegularTypeface
        textPaint.textSize = 15f
        textPaint.color = currentTheme.textSecondaryColor
        val subtitleText = "共 ${allInstalledApps.size} 款应用 · 点击卡片在副屏全屏启动 · 独立副屏多任务"
        canvas.drawText(subtitleText, headerLeft, headerTop + 54f, textPaint)

        // 4. 应用列表区域（裁切并滚动）
        val listTop = headerBottom + 6f
        val listBottom = stageWellRect.bottom - 12f
        val listLeft = stageWellRect.left + 16f
        val listRight = stageWellRect.right - 16f
        val listAvailW = listRight - listLeft

        canvas.save()
        canvas.clipRect(listLeft, listTop, listRight, listBottom)

        // 2 列网格卡片
        val cols = 2
        val colGap = 12f
        val rowGap = 10f
        val cardW = (listAvailW - colGap * (cols - 1)) / cols
        val cardH = 82f

        val totalRows = kotlin.math.ceil(allInstalledApps.size / cols.toFloat()).toInt()
        val totalContentH = totalRows * (cardH + rowGap)
        val viewportH = listBottom - listTop
        maxAppsScrollY = (totalContentH - viewportH + 20f).coerceAtLeast(0f)
        appsScrollY += (targetAppsScrollY - appsScrollY) * 0.35f

        val curScroll = appsScrollY

        for (i in allInstalledApps.indices) {
            val app = allInstalledApps[i]
            val row = i / cols
            val col = i % cols
            val cardTop = listTop - curScroll + row * (cardH + rowGap)
            val cardBottom = cardTop + cardH
            if (cardBottom < listTop - 10f || cardTop > listBottom + 10f) continue // 视野外剔除

            val cardLeft = listLeft + col * (cardW + colGap)
            val cardRight = cardLeft + cardW
            val cardRect = RectF(cardLeft, cardTop, cardRight, cardBottom)

            // 绘制 3D 浮雕卡片
            val cardR = 16f
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(18, 0, 0, 0)
            canvas.drawRoundRect(RectF(cardLeft, cardTop + 2f, cardRight, cardBottom + 2f), cardR, cardR, paint)

            val cardBgTop = if (isDark) blendColor(currentTheme.slotFillColor, Color.WHITE, 0.15f) else Color.WHITE
            val cardBgBottom = if (isDark) blendColor(currentTheme.slotFillColor, Color.BLACK, 0.10f) else blendColor(Color.WHITE, currentTheme.slotFillColor, 0.50f)
            paint.shader = LinearGradient(0f, cardTop, 0f, cardBottom, cardBgTop, cardBgBottom, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(cardRect, cardR, cardR, paint)
            paint.shader = null

            // 描边
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            paint.color = if (isDark) Color.argb(45, 255, 255, 255) else Color.argb(160, 203, 213, 225)
            canvas.drawRoundRect(cardRect, cardR, cardR, paint)
            paint.style = Paint.Style.FILL

            // 图标
            val iconPad = 12f
            val iconSize = cardH - iconPad * 2f
            val iconRect = RectF(cardLeft + iconPad, cardTop + iconPad, cardLeft + iconPad + iconSize, cardBottom - iconPad)
            val bmp = iconCache[getIconCacheKey(app)]
            if (bmp != null && !bmp.isRecycled) {
                canvas.drawBitmap(bmp, null, iconRect, bitmapPaint)
            } else {
                paint.color = Color.parseColor("#38BDF8")
                canvas.drawRoundRect(iconRect, 10f, 10f, paint)
                textPaint.color = Color.WHITE
                textPaint.textSize = 20f
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText("📱", iconRect.centerX(), iconRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
            }

            // 卡片右侧快捷按键区域：[🖥️主屏] 按键 (w=68, h=32)
            val btnW = 68f
            val btnH = 34f
            val btnMainX = cardRight - iconPad - btnW
            val btnMainY = cardTop + (cardH - btnH) / 2f
            val mainBtnRect = RectF(btnMainX, btnMainY, btnMainX + btnW, btnMainY + btnH)

            // 文字区域：居中偏左
            val textLeft = iconRect.right + 10f
            val textMaxW = mainBtnRect.left - textLeft - 8f

            textPaint.typeface = apolloMediumTypeface
            textPaint.textSize = 17f
            textPaint.color = if (isDark) Color.WHITE else Color.parseColor("#0F172A")
            textPaint.textAlign = Paint.Align.LEFT
            val titleStr = truncateText(app.title, textMaxW, textPaint)
            canvas.drawText(titleStr, textLeft, cardTop + 33f, textPaint)

            // 包名
            textPaint.typeface = apolloRegularTypeface
            textPaint.textSize = 12f
            textPaint.color = if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")
            val pkgStr = truncateText(app.packageName, textMaxW, textPaint)
            canvas.drawText(pkgStr, textLeft, cardTop + 54f, textPaint)

            // [🖥️主屏] 按钮绘制
            draw3dsSculptedDockButton(
                canvas = canvas,
                r = mainBtnRect,
                radius = 10f,
                isPrimary = false,
                accentOverride = Color.parseColor("#0284C7"),
                drawOutline = true
            )
            textPaint.typeface = apolloMediumTypeface
            textPaint.textSize = 13f
            textPaint.color = if (isDark) Color.WHITE else Color.parseColor("#0369A1")
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("🖥️主屏", mainBtnRect.centerX(), mainBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        }

        canvas.restore()

        // 绘制滚动指示条
        if (maxAppsScrollY > 0f) {
            val trackH = listBottom - listTop
            val thumbH = (trackH * (viewportH / totalContentH)).coerceIn(24f, trackH * 0.7f)
            val thumbTop = listTop + (trackH - thumbH) * (curScroll / maxAppsScrollY)
            val scrollTrackRect = RectF(listRight + 4f, listTop, listRight + 9f, listBottom)
            paint.color = Color.argb(25, 100, 116, 139)
            canvas.drawRoundRect(scrollTrackRect, 2.5f, 2.5f, paint)

            paint.color = Color.argb(140, 2, 132, 199)
            val thumbRect = RectF(listRight + 3.5f, thumbTop, listRight + 9.5f, thumbTop + thumbH)
            canvas.drawRoundRect(thumbRect, 3f, 3f, paint)
        }
    }

    private fun drawVideoAudioButtonSymbol(canvas: Canvas, r: RectF, active: Boolean) {
        val cx = r.centerX()
        val cy = r.centerY()
        val s = minOf(r.width(), r.height()) * 0.28f
        paint.style = Paint.Style.FILL
        paint.color = if (active) Color.parseColor("#E60012") else currentTheme.cursorMainColor
        paint.alpha = 255

        val badgeRect = RectF(cx - s * 1.15f, cy - s * 1.15f, cx + s * 1.15f, cy + s * 1.15f)
        canvas.drawRoundRect(badgeRect, s * 0.45f, s * 0.45f, paint)

        paint.color = Color.WHITE
        if (!active) {
            // 绘制清晰的白色播放三角形 ▶
            arrowPath.reset()
            val triW = s * 0.95f
            val triH = s * 1.05f
            arrowPath.moveTo(cx - triW * 0.38f, cy - triH * 0.52f)
            arrowPath.lineTo(cx + triW * 0.58f, cy)
            arrowPath.lineTo(cx - triW * 0.38f, cy + triH * 0.52f)
            arrowPath.close()
            canvas.drawPath(arrowPath, paint)
        } else {
            // 开启视频原声时：绘制三根动态跳动的音频声波柱
            val barW = (s * 0.32f).coerceAtLeast(3f)
            val gap = barW * 0.65f
            val h1 = s * (0.45f + 0.35f * kotlin.math.abs(sin(animPhase * 3.2f)))
            val h2 = s * (0.65f + 0.35f * kotlin.math.abs(sin(animPhase * 3.2f + 1.1f)))
            val h3 = s * (0.40f + 0.40f * kotlin.math.abs(sin(animPhase * 3.2f + 2.2f)))
            canvas.drawRoundRect(RectF(cx - barW * 1.5f - gap, cy - h1, cx - barW * 0.5f - gap, cy + h1), 2f, 2f, paint)
            canvas.drawRoundRect(RectF(cx - barW * 0.5f, cy - h2, cx + barW * 0.5f, cy + h2), 2f, 2f, paint)
            canvas.drawRoundRect(RectF(cx + barW * 0.5f + gap, cy - h3, cx + barW * 1.5f + gap, cy + h3), 2f, 2f, paint)
        }
    }

    /**
     * 绘制「🔀 自动随机轮播游戏」立体按钮图标：
     * - 未激活时：紫色圆角块 + 白色经典随机穿插双箭头 (Shuffle)
     * - 激活轮播时：高亮金色底色 + 专属 60FPS 顺时针旋转双循环轨道流光 + 中心逆旋呼吸星芒动态动效（彻底与左侧视频原声音量柱区分开）
     */
    private fun drawAutoRandomButtonSymbol(canvas: Canvas, r: RectF, active: Boolean) {
        val cx = r.centerX()
        val cy = r.centerY()
        val s = minOf(r.width(), r.height()) * 0.28f
        paint.style = Paint.Style.FILL
        paint.color = if (active) Color.parseColor("#F59E0B") else Color.parseColor("#8B5CF6")
        paint.alpha = 255

        val badgeRect = RectF(cx - s * 1.15f, cy - s * 1.15f, cx + s * 1.15f, cy + s * 1.15f)
        canvas.drawRoundRect(badgeRect, s * 0.45f, s * 0.45f, paint)

        if (!active) {
            // 未激活时：绘制精致的随机交叉双箭头 (Shuffle) 矢量图标
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (s * 0.22f).coerceAtLeast(2.5f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = Color.WHITE

            val w = s * 0.72f
            val h = s * 0.48f

            // 上主线：左上 -> 折向右下
            arrowPath.reset()
            arrowPath.moveTo(cx - w, cy - h)
            arrowPath.lineTo(cx - w * 0.25f, cy - h)
            arrowPath.lineTo(cx + w * 0.25f, cy + h)
            arrowPath.lineTo(cx + w, cy + h)
            canvas.drawPath(arrowPath, paint)

            // 下主线：左下 -> 折向右上（在中心断开少许呈现穿插效果）
            arrowPath.reset()
            arrowPath.moveTo(cx - w, cy + h)
            arrowPath.lineTo(cx - w * 0.25f, cy + h)
            arrowPath.lineTo(cx - w * 0.08f, cy + h * 0.45f)
            canvas.drawPath(arrowPath, paint)

            arrowPath.reset()
            arrowPath.moveTo(cx + w * 0.08f, cy - h * 0.45f)
            arrowPath.lineTo(cx + w * 0.25f, cy - h)
            arrowPath.lineTo(cx + w, cy - h)
            canvas.drawPath(arrowPath, paint)

            // 右上与右下箭头尖
            paint.style = Paint.Style.FILL
            val tipLen = s * 0.32f
            val tipWing = s * 0.30f

            arrowPath.reset()
            arrowPath.moveTo(cx + w + tipLen * 0.5f, cy - h)
            arrowPath.lineTo(cx + w - tipLen * 0.5f, cy - h - tipWing)
            arrowPath.lineTo(cx + w - tipLen * 0.5f, cy - h + tipWing)
            arrowPath.close()
            canvas.drawPath(arrowPath, paint)

            arrowPath.reset()
            arrowPath.moveTo(cx + w + tipLen * 0.5f, cy + h)
            arrowPath.lineTo(cx + w - tipLen * 0.5f, cy + h - tipWing)
            arrowPath.lineTo(cx + w - tipLen * 0.5f, cy + h + tipWing)
            arrowPath.close()
            canvas.drawPath(arrowPath, paint)
        } else {
            // 激活随机轮播时：专属「双弧形飞翼循环轨道顺时针旋转 + 中心逆旋呼吸星芒」动态动效
            // 绝不与左侧视频原声音量柱重样，完全体现“游戏随机自动巡航轮播”的动感！
            paint.color = Color.WHITE

            // 1) 外圈双弧形循环箭头：顺时针平滑旋转
            canvas.save()
            val rotAngle = (animPhase * 125f) % 360f
            canvas.rotate(rotAngle, cx, cy)

            val ringR = s * 0.74f
            val arcRect = RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (s * 0.22f).coerceAtLeast(2.4f)
            paint.strokeCap = Paint.Cap.ROUND

            // 绘制两段对称的圆弧轨道 (10°~140° 与 190°~320°)
            canvas.drawArc(arcRect, 10f, 130f, false, paint)
            canvas.drawArc(arcRect, 190f, 130f, false, paint)

            // 在 140° 和 320° 弧末端绘制顺时针切线方向的流线型箭头尖
            paint.style = Paint.Style.FILL
            val tipLen = s * 0.40f
            val tipWing = s * 0.32f

            // 箭头尖 1 (140° 顺切线方向)
            canvas.save()
            canvas.rotate(140f, cx, cy)
            arrowPath.reset()
            arrowPath.moveTo(cx + ringR, cy + tipLen * 0.55f)
            arrowPath.lineTo(cx + ringR - tipWing, cy - tipLen * 0.35f)
            arrowPath.lineTo(cx + ringR + tipWing, cy - tipLen * 0.35f)
            arrowPath.close()
            canvas.drawPath(arrowPath, paint)
            canvas.restore()

            // 箭头尖 2 (320° 顺切线方向)
            canvas.save()
            canvas.rotate(320f, cx, cy)
            arrowPath.reset()
            arrowPath.moveTo(cx + ringR, cy + tipLen * 0.55f)
            arrowPath.lineTo(cx + ringR - tipWing, cy - tipLen * 0.35f)
            arrowPath.lineTo(cx + ringR + tipWing, cy - tipLen * 0.35f)
            arrowPath.close()
            canvas.drawPath(arrowPath, paint)
            canvas.restore()

            canvas.restore() // 恢复外圈旋转

            // 2) 中心：独立逆向微旋的灵动四芒星（带有周期性呼吸律动缩放，象征随机探索/抽卡）
            canvas.save()
            val counterAngle = (-animPhase * 75f) % 360f
            canvas.rotate(counterAngle, cx, cy)

            val pulse = 0.78f + 0.26f * kotlin.math.abs(sin(animPhase * 3.4f))
            val starR = s * 0.33f * pulse
            val starInner = starR * 0.32f

            arrowPath.reset()
            arrowPath.moveTo(cx, cy - starR)
            arrowPath.lineTo(cx + starInner, cy - starInner)
            arrowPath.lineTo(cx + starR, cy)
            arrowPath.lineTo(cx + starInner, cy + starInner)
            arrowPath.lineTo(cx, cy + starR)
            arrowPath.lineTo(cx - starInner, cy + starInner)
            arrowPath.lineTo(cx - starR, cy)
            arrowPath.lineTo(cx - starInner, cy - starInner)
            arrowPath.close()

            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawPath(arrowPath, paint)
            canvas.restore()
        }
    }

    private fun drawZoomButtonSymbol(canvas: Canvas, r: RectF, isZoomIn: Boolean) {
        val cx = r.centerX()
        val cy = r.centerY()
        val s = minOf(r.width(), r.height()) * 0.25f
        paint.style = Paint.Style.FILL
        paint.color = currentTheme.cursorMainColor
        paint.alpha = 255
        if (isZoomIn) {
            val box = RectF(cx - s, cy - s, cx + s, cy + s)
            canvas.drawRoundRect(box, 5f, 5f, paint)
            textPaint.color = Color.WHITE
            textPaint.textSize = s * 1.35f
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("+", cx, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        } else {
            val half = s * 0.85f
            val gap = 2.8f
            canvas.drawRoundRect(RectF(cx - half - gap, cy - half - gap, cx - gap / 2f, cy - gap / 2f), 2.5f, 2.5f, paint)
            canvas.drawRoundRect(RectF(cx + gap / 2f, cy - half - gap, cx + half + gap, cy - gap / 2f), 2.5f, 2.5f, paint)
            canvas.drawRoundRect(RectF(cx - half - gap, cy + gap / 2f, cx - gap / 2f, cy + half + gap), 2.5f, 2.5f, paint)
            canvas.drawRoundRect(RectF(cx + gap / 2f, cy + gap / 2f, cx + half + gap, cy + half + gap), 2.5f, 2.5f, paint)
        }
    }

    /**
     * 绘制中央 3DS 实机内凹圆角大舞台与自适应满幅卡带网格（彻底解决上下留白过大的问题）
     */
    private fun drawCartridgeGrid(canvas: Canvas, w: Float, gridTop: Float, gridBottom: Float) {
        slotScreenRects.clear()
        val sideMargin = 16f
        stageWellRect.set(sideMargin, gridTop, w - sideMargin, gridBottom)
        val stageRadius = 26f

        // 1. 绘制 3DS 标志性的中央内凹圆角大舞台底盘 (Recessed Inner Stage Well)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(24, 0, 0, 0)
        canvas.drawRoundRect(stageWellRect, stageRadius, stageRadius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.argb(95, 255, 255, 255)
        canvas.drawRoundRect(stageWellRect, stageRadius, stageRadius, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 255

        val availH = stageWellRect.height().coerceAtLeast(80f)
        val sideArrowReserve = 42f
        val availW = (w - sideArrowReserve * 2f).coerceAtLeast(100f)

        val rows = gridRows.coerceIn(1, 6)
        val padV = (availH * 0.055f).coerceIn(10f, 28f)
        val gap = (availH / rows * 0.105f).coerceIn(8f, 24f)
        // 不再设置 220f 上限！让卡槽根据屏幕实际高度饱满撑满中央舞台区域
        val maxSingleCell = (availW * 0.44f).coerceAtLeast(180f)
        val cellSize = (((availH - padV * 2f - gap * (rows - 1)) / rows)).coerceIn(44f, maxSingleCell)

        val totalCols = ceil(slots.size.toDouble() / rows).toInt().coerceAtLeast(1)
        val contentW = totalCols * cellSize + (totalCols - 1) * gap
        maxScrollX = max(0f, contentW - availW + 28f)
        scrollOffsetX = scrollOffsetX.coerceIn(0f, maxScrollX)

        canvas.save()
        canvas.clipRect(stageWellRect.left + 8f, stageWellRect.top + 4f, stageWellRect.right - 8f, stageWellRect.bottom - 4f)

        val startX = sideArrowReserve + 14f - scrollOffsetX
        val startY = gridTop + (availH - (rows * cellSize + (rows - 1) * gap)) / 2f

        for (index in slots.indices) {
            val col = index / rows
            val row = index % rows
            val left = startX + col * (cellSize + gap)
            val top = startY + row * (cellSize + gap)
            val right = left + cellSize
            val bottom = top + cellSize

            if (right < sideArrowReserve - cellSize || left > w) continue

            val cellRect = RectF(left, top, right, bottom)
            slotScreenRects[index] = cellRect
            val item = slots[index]
            val isSelected = (index == selectedIndex)
            val isMovingSource = (index == movingSourceIndex)

            drawSingleSlot(canvas, cellRect, item, isSelected, isMovingSource, rows)
        }

        val selRect = slotScreenRects[selectedIndex]
        if (selRect != null) {
            if (!hasCursorInit) {
                animatedCursorRect.set(selRect)
                hasCursorInit = true
            } else {
                val ease = 0.35f
                animatedCursorRect.left += (selRect.left - animatedCursorRect.left) * ease
                animatedCursorRect.top += (selRect.top - animatedCursorRect.top) * ease
                animatedCursorRect.right += (selRect.right - animatedCursorRect.right) * ease
                animatedCursorRect.bottom += (selRect.bottom - animatedCursorRect.bottom) * ease
                if (abs(selRect.left - animatedCursorRect.left) < 0.6f && abs(selRect.top - animatedCursorRect.top) < 0.6f) {
                    animatedCursorRect.set(selRect)
                }
            }
            draw3dsCornerCursor(canvas, animatedCursorRect)
        }

        canvas.restore()
    }

    private fun drawSingleSlot(
        canvas: Canvas,
        box: RectF,
        item: GridSlotItem,
        isSelected: Boolean,
        isMovingSource: Boolean,
        rows: Int
    ) {
        val cornerR = (box.width() * 0.19f).coerceIn(8f, 34f)

        // A. 空白凹陷小卡槽
        if (item.type == PlatformType.EMPTY) {
            val inset = box.width() * 0.11f
            val emptyRect = RectF(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset)
            paint.style = Paint.Style.FILL
            paint.color = currentTheme.emptySlotRecessColor
            paint.alpha = 140
            canvas.drawRoundRect(emptyRect, cornerR * 0.72f, cornerR * 0.72f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = currentTheme.slotBorderColor
            paint.alpha = 90
            canvas.drawRoundRect(emptyRect, cornerR * 0.72f, cornerR * 0.72f, paint)
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            return
        }

        val scale = if (isSelected) 1.035f else 1.0f
        val drawRect = if (scale != 1.0f) {
            val dx = box.width() * (scale - 1f) / 2f
            val dy = box.height() * (scale - 1f) / 2f
            RectF(box.left - dx, box.top - dy, box.right + dx, box.bottom + dy)
        } else {
            box
        }

        // B. 3DS 徽章装饰 (Badge Arcade) — 立体问号砖/星章，确保文字严格居中
        if (item.type == PlatformType.BADGE) {
            val badge = BadgeCatalog.getById(item.badgeId)
            val cx = drawRect.centerX()
            val cy = drawRect.centerY()
            val pad = drawRect.width() * 0.08f
            val badgeRect = RectF(drawRect.left + pad, drawRect.top + pad, drawRect.right - pad, drawRect.bottom - pad)
            val bCorner = badgeRect.width() * 0.24f

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(55, 0, 0, 0)
            canvas.drawRoundRect(
                RectF(badgeRect.left + 2f, badgeRect.top + 5f, badgeRect.right + 2f, badgeRect.bottom + 5f),
                bCorner,
                bCorner,
                paint
            )
            paint.color = badge?.primaryColor ?: item.primaryColor
            canvas.drawRoundRect(badgeRect, bCorner, bCorner, paint)

            // 顶部高光
            paint.color = Color.argb(65, 255, 255, 255)
            canvas.drawRoundRect(
                RectF(badgeRect.left + 4f, badgeRect.top + 4f, badgeRect.right - 4f, badgeRect.centerY()),
                bCorner,
                bCorner,
                paint
            )

            // 四角铆钉点（还原马力欧问号砖块质感）
            val dotOff = badgeRect.width() * 0.14f
            paint.color = Color.argb(140, 120, 60, 0)
            canvas.drawCircle(badgeRect.left + dotOff, badgeRect.top + dotOff, 3.5f, paint)
            canvas.drawCircle(badgeRect.right - dotOff, badgeRect.top + dotOff, 3.5f, paint)
            canvas.drawCircle(badgeRect.left + dotOff, badgeRect.bottom - dotOff, 3.5f, paint)
            canvas.drawCircle(badgeRect.right - dotOff, badgeRect.bottom - dotOff, 3.5f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.WHITE
            canvas.drawRoundRect(badgeRect, bCorner, bCorner, paint)
            paint.style = Paint.Style.FILL

            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = Color.WHITE
            textPaint.textSize = badgeRect.width() * 0.50f
            val sym = badge?.symbol ?: "？"
            canvas.drawText(
                sym,
                cx,
                cy - (textPaint.descent() + textPaint.ascent()) / 2f,
                textPaint
            )
            return
        }

        // C. 3DS 立体文件夹图标（无底层白框，直接绘制立体双层文件夹造型与凹印字牌）
        if (item.type == PlatformType.FOLDER) {
            val pad = drawRect.width() * 0.06f
            val fRect = RectF(drawRect.left + pad, drawRect.top + pad, drawRect.right - pad, drawRect.bottom - pad)
            val fCorner = fRect.width() * 0.16f

            // 文件夹底部阴影
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(65, 0, 0, 0)
            canvas.drawRoundRect(
                RectF(fRect.left + 2f, fRect.top + fRect.height() * 0.14f + 5f, fRect.right + 2f, fRect.bottom + 5f),
                fCorner,
                fCorner,
                paint
            )

            // 后层文件夹背板与左上角凸起标签页
            val backColor = blendColor(item.primaryColor, Color.BLACK, 0.22f)
            paint.color = backColor
            val tabRect = RectF(fRect.left + fRect.width() * 0.06f, fRect.top, fRect.left + fRect.width() * 0.50f, fRect.top + fRect.height() * 0.32f)
            canvas.drawRoundRect(tabRect, fCorner * 0.6f, fCorner * 0.6f, paint)
            val backSheet = RectF(fRect.left, fRect.top + fRect.height() * 0.10f, fRect.right, fRect.bottom)
            canvas.drawRoundRect(backSheet, fCorner, fCorner, paint)

            // 夹在文件夹中间的白色纸张边缘
            val paperRect = RectF(fRect.left + fRect.width() * 0.07f, fRect.top + fRect.height() * 0.15f, fRect.right - fRect.width() * 0.07f, fRect.bottom - fRect.height() * 0.10f)
            paint.color = Color.parseColor("#F8FAFC")
            canvas.drawRoundRect(paperRect, fCorner * 0.5f, fCorner * 0.5f, paint)

            // 前层立体文件夹主袋面
            val frontPocket = RectF(fRect.left, fRect.top + fRect.height() * 0.22f, fRect.right, fRect.bottom)
            paint.shader = LinearGradient(
                0f, frontPocket.top, 0f, frontPocket.bottom,
                blendColor(item.primaryColor, Color.WHITE, 0.15f),
                item.primaryColor,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(frontPocket, fCorner, fCorner, paint)
            paint.shader = null

            // 前袋中央白色圆角铭牌 + 首字母
            val badgeSize = frontPocket.height() * 0.58f
            val plateRect = RectF(
                frontPocket.centerX() - badgeSize / 2f,
                frontPocket.centerY() - badgeSize / 2f,
                frontPocket.centerX() + badgeSize / 2f,
                frontPocket.centerY() + badgeSize / 2f
            )
            paint.color = Color.argb(235, 255, 255, 255)
            canvas.drawRoundRect(plateRect, badgeSize * 0.26f, badgeSize * 0.26f, paint)

            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = backColor
            textPaint.textSize = badgeSize * 0.62f
            val letter = item.folderLetter.ifBlank { item.title.take(1) }
            canvas.drawText(
                letter,
                plateRect.centerX(),
                plateRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f,
                textPaint
            )

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.argb(180, 255, 255, 255)
            canvas.drawRoundRect(frontPocket, fCorner, fCorner, paint)
            paint.style = Paint.Style.FILL
            return
        }

        // D. 标准 3DS / NDS 游戏卡带与安卓应用图标（1:1 饱满正方形圆角瓷白立体卡座）
        val shadowRect = RectF(drawRect.left + 2f, drawRect.top + 5f, drawRect.right + 2f, drawRect.bottom + 6f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(72, 0, 0, 0)
        canvas.drawRoundRect(shadowRect, cornerR, cornerR, paint)

        // 瓷白立体底座
        paint.color = if (isMovingSource) Color.parseColor("#FFF3B0") else currentTheme.slotFillColor
        canvas.drawRoundRect(drawRect, cornerR, cornerR, paint)

        val pad = (drawRect.width() * 0.068f).coerceIn(3f, 11f)
        val innerRect = RectF(drawRect.left + pad, drawRect.top + pad, drawRect.right - pad, drawRect.bottom - pad)
        val innerR = (cornerR - pad * 0.55f).coerceAtLeast(5f)

        val bmp = loadSlotIcon(item)
        if (bmp != null) {
            canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(innerRect, innerR, innerR, Path.Direction.CW)
            canvas.clipPath(clipPath)
            canvas.drawBitmap(bmp, null, innerRect, bitmapPaint)

            // 顶部柔和微弧水晶高光
            paint.shader = LinearGradient(
                0f, innerRect.top, 0f, innerRect.top + innerRect.height() * 0.30f,
                Color.argb(48, 255, 255, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(innerRect.left, innerRect.top, innerRect.right, innerRect.top + innerRect.height() * 0.30f, paint)
            paint.shader = null
            canvas.restore()
        } else {
            // 无图标时的卡带底座兜底：采用温润的 3DS 实体卡带灰色微光底座，杜绝生硬文字过渡
            paint.color = blendColor(currentTheme.slotFillColor, Color.BLACK, 0.08f)
            canvas.drawRoundRect(innerRect, innerR, innerR, paint)
            paint.color = Color.argb(35, 255, 255, 255)
            canvas.drawRoundRect(
                RectF(innerRect.left, innerRect.top, innerRect.right, innerRect.centerY()),
                innerR,
                innerR,
                paint
            )
        }

        // 外框高光立体描边
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (isSelected) 2.5f else 1.6f
        paint.color = if (isMovingSource) Color.parseColor("#FF8800") else currentTheme.slotBorderColor
        canvas.drawRoundRect(drawRect, cornerR, cornerR, paint)
        paint.style = Paint.Style.FILL
    }

    private fun draw3dsCornerCursor(canvas: Canvas, target: RectF) {
        val pulse = sin(animPhase * 2.4f) * 3.2f
        val pad = 4.5f + pulse
        val cursorRect = RectF(target.left - pad, target.top - pad, target.right + pad, target.bottom + pad)
        val armLen = (target.width() * 0.25f).coerceIn(12f, 34f)
        val strokeW = (target.width() * 0.058f).coerceIn(4f, 8.5f)

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        paint.strokeWidth = strokeW + 3.5f
        paint.color = currentTheme.cursorGlowColor
        paint.alpha = 130
        drawCornerBrackets(canvas, cursorRect, armLen)

        paint.strokeWidth = strokeW
        paint.color = currentTheme.cursorMainColor
        paint.alpha = 255
        drawCornerBrackets(canvas, cursorRect, armLen)

        paint.style = Paint.Style.FILL
    }

    private fun drawCornerBrackets(canvas: Canvas, r: RectF, arm: Float) {
        canvas.drawLine(r.left, r.top + arm, r.left, r.top, paint)
        canvas.drawLine(r.left, r.top, r.left + arm, r.top, paint)
        canvas.drawLine(r.right - arm, r.top, r.right, r.top, paint)
        canvas.drawLine(r.right, r.top, r.right, r.top + arm, paint)
        canvas.drawLine(r.left, r.bottom - arm, r.left, r.bottom, paint)
        canvas.drawLine(r.left, r.bottom, r.left + arm, r.bottom, paint)
        canvas.drawLine(r.right - arm, r.bottom, r.right, r.bottom, paint)
        canvas.drawLine(r.right, r.bottom - arm, r.right, r.bottom, paint)
    }

    private fun drawPageTurnArrows(canvas: Canvas, w: Float, gridTop: Float, gridBottom: Float) {
        val cy = (gridTop + gridBottom) / 2f
        val arrowH = 54f
        val arrowW = 30f

        pageLeftArrowRect.set(4f, cy - arrowH / 2f, 4f + arrowW, cy + arrowH / 2f)
        pageRightArrowRect.set(w - 4f - arrowW, cy - arrowH / 2f, w - 4f, cy + arrowH / 2f)

        if (scrollOffsetX > 4f) {
            drawBeveledButton(canvas, pageLeftArrowRect, currentTheme.slotFillColor, currentTheme.slotBorderColor, 10f)
            paint.color = currentTheme.cursorMainColor
            arrowPath.reset()
            arrowPath.moveTo(pageLeftArrowRect.right - 8f, pageLeftArrowRect.top + 13f)
            arrowPath.lineTo(pageLeftArrowRect.left + 8f, pageLeftArrowRect.centerY())
            arrowPath.lineTo(pageLeftArrowRect.right - 8f, pageLeftArrowRect.bottom - 13f)
            arrowPath.close()
            canvas.drawPath(arrowPath, paint)
        }

        if (scrollOffsetX < maxScrollX - 4f) {
            drawBeveledButton(canvas, pageRightArrowRect, currentTheme.slotFillColor, currentTheme.slotBorderColor, 10f)
            paint.color = currentTheme.cursorMainColor
            arrowPath.reset()
            arrowPath.moveTo(pageRightArrowRect.left + 8f, pageRightArrowRect.top + 13f)
            arrowPath.lineTo(pageRightArrowRect.right - 8f, pageRightArrowRect.centerY())
            arrowPath.lineTo(pageRightArrowRect.left + 8f, pageRightArrowRect.bottom - 13f)
            arrowPath.close()
            canvas.drawPath(arrowPath, paint)
        }
    }

    /**
     * 下屏底部 3DS 游戏类型/系列分类切换船坞栏：
     * - 左键：◀ L1 [上一分类]
     * - 中央微拱主屏：[分类色标] 当前游戏分类名称 (共X款) + 底部分类圆点指示器
     * - 右键：[下一分类] R1 ▶
     */
    private fun drawAuthenticBottomActionBar(canvas: Canvas, w: Float, h: Float, barH: Float) {
        val top = h - barH
        val sideMargin = 16f
        val dockBottom = h - 7f
        val dockTop = top + 3f

        // 1. 底部内凹船坞底座槽（与上方 stageWellRect 左右对齐）
        val dockTrayRect = RectF(sideMargin, dockTop, w - sideMargin, dockBottom)
        val trayR = (dockTrayRect.height() * 0.44f).coerceIn(16f, 28f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(30, 8, 24, 48)
        canvas.drawRoundRect(dockTrayRect, trayR, trayR, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f
        paint.color = Color.argb(145, 255, 255, 255)
        canvas.drawRoundRect(
            RectF(dockTrayRect.left + 1f, dockTrayRect.top + 1f, dockTrayRect.right - 1f, dockTrayRect.bottom),
            trayR,
            trayR,
            paint
        )
        paint.style = Paint.Style.FILL

        val innerPadH = 7f
        val innerPadV = 6f
        val gap = 10f
        val availDockW = dockTrayRect.width() - innerPadH * 2f
        val sideBtnW = availDockW * 0.275f
        val btnBottom = dockTrayRect.bottom - innerPadV
        val sideBtnTop = dockTrayRect.top + innerPadV
        val centerBtnTop = dockTrayRect.top + innerPadV - 4f

        bottomLeftBtnRect.set(
            dockTrayRect.left + innerPadH,
            sideBtnTop,
            dockTrayRect.left + innerPadH + sideBtnW,
            btnBottom
        )
        bottomRightBtnRect.set(
            dockTrayRect.right - innerPadH - sideBtnW,
            sideBtnTop,
            dockTrayRect.right - innerPadH,
            btnBottom
        )
        bottomCenterBtnRect.set(
            bottomLeftBtnRect.right + gap,
            centerBtnTop,
            bottomRightBtnRect.left - gap,
            btnBottom
        )

        val sideR = bottomLeftBtnRect.height() * 0.45f
        val centerR = bottomCenterBtnRect.height() * 0.46f

        val catList = categories.ifEmpty { CustomCategoryItem.defaultCategories() }
        val safeIdx = currentCategoryIndex.coerceIn(0, catList.size - 1)
        val curCat = catList[safeIdx]
        val prevCat = catList[(safeIdx - 1 + catList.size) % catList.size]
        val nextCat = catList[(safeIdx + 1) % catList.size]

        // 2. 绘制三枚丝滑全渐变 3D 实体立体按键
        draw3dsSculptedDockButton(canvas, bottomLeftBtnRect, sideR, isPrimary = false, accentOverride = curCat.accentColor)
        draw3dsSculptedDockButton(canvas, bottomCenterBtnRect, centerR, isPrimary = true, accentOverride = curCat.accentColor)
        draw3dsSculptedDockButton(canvas, bottomRightBtnRect, sideR, isPrimary = false, accentOverride = curCat.accentColor)

        val isDarkBtnTheme = calculateColorLuminance(currentTheme.bottomBarButtonColor) < 0.45f
        val sideBtnBg = if (isDarkBtnTheme) currentTheme.bottomBarButtonColor else Color.WHITE
        val centerBtnBg = if (isDarkBtnTheme) currentTheme.bottomBarButtonColor else Color.WHITE
        val sideTextColor = getHighContrastButtonTextColor(sideBtnBg, currentTheme.textPrimaryColor)
        val centerTextColor = getHighContrastButtonTextColor(centerBtnBg, currentTheme.textPrimaryColor)

        if (movingSourceIndex >= 0) {
            // 移动换位模式提示
            val sideFontSize = (bottomLeftBtnRect.height() * 0.36f).coerceIn(13f, 18f)
            textPaint.typeface = apolloMediumTypeface
            textPaint.isFakeBoldText = false
            textPaint.textSize = sideFontSize
            textPaint.color = sideTextColor
            textPaint.textAlign = Paint.Align.CENTER
            val cy = bottomLeftBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText("取消移动", bottomLeftBtnRect.centerX(), cy, textPaint)
            canvas.drawText("放到此处 (A)", bottomCenterBtnRect.centerX(), cy, textPaint)
            canvas.drawText("移出文件夹", bottomRightBtnRect.centerX(), cy, textPaint)
            textPaint.typeface = boldTypeface
            return
        }

        // 3. 中央分类主按钮顶部的 ▲ 指示标
        val triCy = bottomCenterBtnRect.top + 9f
        val triHalfW = 7f
        paint.style = Paint.Style.FILL
        paint.color = curCat.accentColor
        arrowPath.reset()
        arrowPath.moveTo(w / 2f, triCy - 4.5f)
        arrowPath.lineTo(w / 2f - triHalfW, triCy + 4.5f)
        arrowPath.lineTo(w / 2f + triHalfW, triCy + 4.5f)
        arrowPath.close()
        canvas.drawPath(arrowPath, paint)

        val pillH = (bottomLeftBtnRect.height() * 0.56f).coerceIn(22f, 42f)
        val pillW = pillH * 1.48f
        val baseSideFontSize = (bottomLeftBtnRect.height() * 0.44f).coerceIn(17f, 32f)

        // 4. 左按钮：绘制 [L1] 立体肩键徽章 + ◀ 上一分类名（采用 Apollo 细字体风格）
        val l1Rect = RectF(
            bottomLeftBtnRect.left + 12f,
            bottomLeftBtnRect.centerY() - pillH / 2f,
            bottomLeftBtnRect.left + 12f + pillW,
            bottomLeftBtnRect.centerY() + pillH / 2f
        )
        paint.color = curCat.accentColor
        canvas.drawRoundRect(l1Rect, pillH * 0.38f, pillH * 0.38f, paint)
        textPaint.typeface = apolloMediumTypeface
        textPaint.isFakeBoldText = false
        textPaint.color = Color.WHITE
        textPaint.textSize = pillH * 0.58f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("L1", l1Rect.centerX(), l1Rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        val leftLabel = "◀ ${prevCat.shortName}"
        val leftMaxW = (bottomLeftBtnRect.right - l1Rect.right - 14f).coerceAtLeast(40f)
        textPaint.typeface = apolloRegularTypeface
        textPaint.isFakeBoldText = false
        textPaint.color = sideTextColor
        textPaint.textSize = baseSideFontSize
        val leftMeasured = textPaint.measureText(leftLabel)
        if (leftMeasured > leftMaxW) {
            textPaint.textSize = (baseSideFontSize * (leftMaxW / leftMeasured)).coerceAtLeast(15f)
        }
        textPaint.textAlign = Paint.Align.CENTER
        val leftLabelCenter = (l1Rect.right + bottomLeftBtnRect.right - 6f) / 2f
        canvas.drawText(
            leftLabel,
            leftLabelCenter,
            bottomLeftBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f,
            textPaint
        )

        // 5. 右按钮：下一分类名 ▶ + [R1] 立体肩键徽章（采用 Apollo 细字体风格）
        val r1Rect = RectF(
            bottomRightBtnRect.right - 12f - pillW,
            bottomRightBtnRect.centerY() - pillH / 2f,
            bottomRightBtnRect.right - 12f,
            bottomRightBtnRect.centerY() + pillH / 2f
        )
        paint.color = curCat.accentColor
        canvas.drawRoundRect(r1Rect, pillH * 0.38f, pillH * 0.38f, paint)
        textPaint.typeface = apolloMediumTypeface
        textPaint.isFakeBoldText = false
        textPaint.color = Color.WHITE
        textPaint.textSize = pillH * 0.58f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("R1", r1Rect.centerX(), r1Rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        val rightLabel = "${nextCat.shortName} ▶"
        val rightMaxW = (r1Rect.left - bottomRightBtnRect.left - 14f).coerceAtLeast(40f)
        textPaint.typeface = apolloRegularTypeface
        textPaint.isFakeBoldText = false
        textPaint.color = sideTextColor
        textPaint.textSize = baseSideFontSize
        val rightMeasured = textPaint.measureText(rightLabel)
        if (rightMeasured > rightMaxW) {
            textPaint.textSize = (baseSideFontSize * (rightMaxW / rightMeasured)).coerceAtLeast(15f)
        }
        textPaint.textAlign = Paint.Align.CENTER
        val rightLabelCenter = (bottomRightBtnRect.left + 6f + r1Rect.left) / 2f
        canvas.drawText(
            rightLabel,
            rightLabelCenter,
            bottomRightBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f,
            textPaint
        )

        // 6. 中央主按钮：平台选择大标题 + 当前分类与游戏数量（Apollo 细字体）
        var centerFontSize = (bottomCenterBtnRect.height() * 0.44f).coerceIn(18f, 34f)
        textPaint.typeface = apolloMediumTypeface
        textPaint.isFakeBoldText = false
        textPaint.textSize = centerFontSize
        val mainCatTitle = if (activeFolderTitle != null) {
            "📂 $activeFolderTitle ($currentCategoryGameCount)"
        } else {
            "${curCat.displayName} ($currentCategoryGameCount)"
        }
        val maxCenterContentW = (bottomCenterBtnRect.width() - 28f).coerceAtLeast(80f)
        var titleW = textPaint.measureText(mainCatTitle)
        var badgeR = centerFontSize * 0.52f
        var totalCenterW = badgeR * 2f + 10f + titleW
        if (totalCenterW > maxCenterContentW) {
            val scale = (maxCenterContentW / totalCenterW).coerceIn(0.6f, 1f)
            centerFontSize *= scale
            textPaint.textSize = centerFontSize
            titleW = textPaint.measureText(mainCatTitle)
            badgeR = centerFontSize * 0.52f
            totalCenterW = badgeR * 2f + 10f + titleW
        }

        val startX = bottomCenterBtnRect.centerX() - totalCenterW / 2f
        val textCy = bottomCenterBtnRect.top + bottomCenterBtnRect.height() * 0.45f

        paint.style = Paint.Style.FILL
        paint.color = curCat.accentColor
        val badgeCx = startX + badgeR
        canvas.drawCircle(badgeCx, textCy, badgeR, paint)

        textPaint.color = Color.WHITE
        textPaint.textSize = badgeR * 1.15f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            curCat.badgeSymbol,
            badgeCx,
            textCy - (textPaint.descent() + textPaint.ascent()) / 2f,
            textPaint
        )

        textPaint.color = centerTextColor
        textPaint.textSize = centerFontSize
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            mainCatTitle,
            startX + badgeR * 2f + 10f,
            textCy - (textPaint.descent() + textPaint.ascent()) / 2f,
            textPaint
        )

        // 恢复默认 bold typeface
        textPaint.typeface = boldTypeface

        // 底部一排精致的分类进度圆点指示器 (● ○ ○ ○ ○ ○)
        val dotCount = catList.size
        val dotGap = 14f
        val dotR = 4.0f
        val dotsTotalW = (dotCount - 1) * dotGap
        val dotStartX = bottomCenterBtnRect.centerX() - dotsTotalW / 2f
        val dotY = bottomCenterBtnRect.bottom - 9.5f
        for (i in 0 until dotCount) {
            val dx = dotStartX + i * dotGap
            if (i == safeIdx) {
                paint.color = curCat.accentColor
                canvas.drawRoundRect(RectF(dx - dotR * 1.8f, dotY - dotR, dx + dotR * 1.8f, dotY + dotR), dotR, dotR, paint)
            } else {
                paint.color = Color.argb(95, 100, 116, 139)
                canvas.drawCircle(dx, dotY, dotR * 0.85f, paint)
            }
        }
    }

    private fun draw3dsSculptedDockButton(
        canvas: Canvas,
        r: RectF,
        radius: Float,
        isPrimary: Boolean,
        accentOverride: Int = currentTheme.cursorMainColor,
        drawOutline: Boolean = true,
        isSolid: Boolean = false
    ) {
        val isDarkBtn = calculateColorLuminance(currentTheme.bottomBarButtonColor) < 0.45f

        // 1. 底部投影
        paint.style = Paint.Style.FILL
        paint.color = if (isSolid || isDarkBtn) Color.argb(65, 0, 0, 0) else Color.argb(46, 8, 20, 38)
        canvas.drawRoundRect(RectF(r.left, r.top + 3.5f, r.right, r.bottom + 3.5f), radius, radius, paint)

        // 2. 实体按键底部立体厚唇边 (3D Bottom Lip)
        paint.color = if (isSolid) {
            blendColor(accentOverride, Color.BLACK, 0.28f)
        } else if (isDarkBtn) {
            blendColor(currentTheme.bottomBarButtonColor, Color.BLACK, 0.38f)
        } else if (isPrimary) {
            blendColor(accentOverride, Color.BLACK, 0.15f)
        } else {
            blendColor(currentTheme.slotBorderColor, Color.BLACK, 0.12f)
        }
        canvas.drawRoundRect(RectF(r.left, r.top + 2f, r.right, r.bottom + 2f), radius, radius, paint)

        // 3. 丝滑全高垂直渐变
        val topColor: Int
        val bottomColor: Int
        if (isSolid) {
            topColor = blendColor(accentOverride, Color.WHITE, 0.28f)
            bottomColor = blendColor(accentOverride, Color.BLACK, 0.12f)
        } else if (isDarkBtn) {
            topColor = blendColor(currentTheme.bottomBarButtonColor, Color.WHITE, 0.22f)
            bottomColor = if (isPrimary) {
                blendColor(currentTheme.bottomBarButtonColor, accentOverride, 0.30f)
            } else {
                blendColor(currentTheme.bottomBarButtonColor, Color.BLACK, 0.25f)
            }
        } else {
            topColor = Color.WHITE
            bottomColor = if (isPrimary) {
                blendColor(Color.WHITE, accentOverride, 0.20f)
            } else {
                blendColor(Color.WHITE, currentTheme.bottomBarButtonColor, 0.65f)
            }
        }
        paint.shader = LinearGradient(
            0f, r.top, 0f, r.bottom,
            topColor,
            bottomColor,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.shader = null

        if (drawOutline) {
            // 4. 内侧高光环
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f
            paint.color = if (isDarkBtn) Color.argb(45, 255, 255, 255) else Color.argb(200, 255, 255, 255)
            canvas.drawRoundRect(
                RectF(r.left + 1.8f, r.top + 1.8f, r.right - 1.8f, r.bottom - 1.8f),
                (radius - 1.5f).coerceAtLeast(4f),
                (radius - 1.5f).coerceAtLeast(4f),
                paint
            )

            // 5. 外框立体描边
            paint.strokeWidth = if (isSolid || isPrimary) 2.2f else 1.2f
            paint.color = if (isDarkBtn) {
                if (isPrimary || isSolid) accentOverride else blendColor(currentTheme.bottomBarButtonColor, Color.WHITE, 0.20f)
            } else if (isSolid || isPrimary) {
                accentOverride
            } else {
                currentTheme.slotBorderColor
            }
            canvas.drawRoundRect(r, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }
    }


    /**
     * 原版 Nintendo 3DS HOME 菜单风格的「视频滤镜与系统设置」内嵌面板：
     * - 打开此面板时，上屏预览视频继续保持播放，切换滤镜可实时看到上屏画面变化！
     * - 顶部 3 个 3DS 立体切换标签页（支持触控或按 L1 / R1 切换）：
     *   0 = 🎬 上屏视频滤镜 (8 款实时滤镜卡片)
     *   1 = 🎨 主题与声音 (6 款 3DS 主题 + BGM 开关 + 上下屏互换)
     *   2 = 📂 游戏库与模拟器 (一键扫描挂载 + 默认模拟器 + 完整高级设置)
     * - 底部原版 3DS 三段式操作栏：左侧 [L1] 上一页 · 中央 [✔ 完成并返回 HOME 桌面 (B)] · 右侧 下一页 [R1]
     */
    private fun drawAuthentic3dsSettingsPanel(
        canvas: Canvas,
        w: Float,
        h: Float,
        panelTop: Float,
        panelBottom: Float,
        bottomBarH: Float
    ) {
        settingsCardRects.clear()
        val sideMargin = 16f
        stageWellRect.set(sideMargin, panelTop, w - sideMargin, panelBottom)
        val stageRadius = 26f

        // 1. 绘制 3DS 瓷白/深灰磨砂底盘柔和阴影（彻底去除最外层的硬边描边线框）
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(26, 0, 0, 0)
        canvas.drawRoundRect(RectF(stageWellRect.left, stageWellRect.top + 3f, stageWellRect.right, stageWellRect.bottom + 3f), stageRadius, stageRadius, paint)

        val innerPanel = RectF(stageWellRect)
        paint.shader = LinearGradient(
            0f, innerPanel.top, 0f, innerPanel.bottom,
            blendColor(currentTheme.slotFillColor, Color.WHITE, 0.35f),
            currentTheme.lowerBgBottom,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(innerPanel, stageRadius, stageRadius, paint)
        paint.shader = null

        if (settingsSubPanel != SettingsSubPanel.NONE) {
            when (settingsSubPanel) {
                SettingsSubPanel.AUTO_RANDOM_INTERVAL -> drawAutoRandomIntervalSubPanel(canvas, innerPanel, w, h, bottomBarH)
                SettingsSubPanel.ADVANCED_SETTINGS -> drawAdvancedSettingsSubPanel(canvas, innerPanel, w, h, bottomBarH)
                SettingsSubPanel.ROM_DIRECTORY_PICKER -> drawRomDirectoryPickerSubPanel(canvas, innerPanel, w, h, bottomBarH)
                SettingsSubPanel.MEDIA_LIBRARY_MANAGER -> drawMediaLibraryManagerSubPanel(canvas, innerPanel, w, h, bottomBarH)
                SettingsSubPanel.EMULATOR_PICKER_3DS -> drawEmulatorPickerSubPanel(canvas, innerPanel, w, h, bottomBarH, PlatformType.N3DS)
                SettingsSubPanel.EMULATOR_PICKER_NDS -> drawEmulatorPickerSubPanel(canvas, innerPanel, w, h, bottomBarH, PlatformType.NDS)
                SettingsSubPanel.STARTUP_DIAGNOSTICS -> drawStartupDiagnosticsSubPanel(canvas, innerPanel, w, h, bottomBarH)
                SettingsSubPanel.SUB_SCREEN_FILTER_CONFIG -> drawSubScreenFilterConfigSubPanel(canvas, innerPanel, w, h, bottomBarH)
                SettingsSubPanel.MAIN_SCREEN_FILTER_CONFIG -> drawMainScreenFilterConfigSubPanel(canvas, innerPanel, w, h, bottomBarH)
                SettingsSubPanel.ROM_SCAN_CONFIG -> drawRomScanConfigSubPanel(canvas, innerPanel, w, h, bottomBarH)
                SettingsSubPanel.PEGASUS_METADATA_PREVIEW -> drawPegasusMetadataPreviewSubPanel(canvas, innerPanel, w, h, bottomBarH)
                SettingsSubPanel.CATEGORY_MANAGER -> drawCategoryManagerSubPanel(canvas, innerPanel, w, h, bottomBarH)
                SettingsSubPanel.CATEGORY_GAME_MANAGER -> drawCategoryGameManagerSubPanel(canvas, innerPanel, w, h, bottomBarH)
                SettingsSubPanel.CATEGORY_EDITOR -> drawCategoryEditorSubPanel(canvas, innerPanel, w, h, bottomBarH)
                SettingsSubPanel.NONE -> {}
            }
            return
        }

        // 2. 面板顶部：4 个 3DS 立体标签页按钮 (L1 ◀ [🎬 视频滤镜] [🎨 主题声音] [🎮 平台选择] [📂 游戏库管理] ▶ R1)
        val tabBarTop = innerPanel.top + 12f
        val tabBarH = (innerPanel.height() * 0.140f).coerceIn(38f, 62f)
        val tabGap = 8f
        val tabSidePad = 14f
        val totalTabW = innerPanel.width() - tabSidePad * 2f - tabGap * 3f
        val singleTabW = totalTabW / 4f
        val tabTitles = arrayOf("🎬 视频滤镜", "🎨 主题声音", "🎮 平台选择", "📂 游戏库管理")

        for (t in 0..3) {
            val tr = settingsTabRects[t]
            val tl = innerPanel.left + tabSidePad + t * (singleTabW + tabGap)
            tr.set(tl, tabBarTop, tl + singleTabW, tabBarTop + tabBarH)
            val isSelectedTab = (settingsTabIndex == t)
            val tabBorder = if (isSelectedTab) currentTheme.cursorMainColor else currentTheme.slotBorderColor
            draw3dsSculptedDockButton(
                canvas = canvas,
                r = tr,
                radius = tabBarH * 0.36f,
                isPrimary = isSelectedTab,
                accentOverride = tabBorder,
                drawOutline = !isSelectedTab
            )

            textPaint.typeface = apolloMediumTypeface
            textPaint.isFakeBoldText = false
            textPaint.color = if (isSelectedTab) currentTheme.textPrimaryColor else currentTheme.textSecondaryColor
            textPaint.textSize = (tabBarH * 0.38f).coerceIn(13f, 22f)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                tabTitles[t],
                tr.centerX(),
                tr.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f,
                textPaint
            )
        }

        // 3. 面板主体区域：根据当前选中的标签页绘制 3DS 立体设置卡片网格
        val contentTop = tabBarTop + tabBarH + 14f
        val contentBottom = innerPanel.bottom - 14f
        val contentLeft = innerPanel.left + 16f
        val contentRight = innerPanel.right - 16f
        val contentW = (contentRight - contentLeft).coerceAtLeast(100f)
        val contentH = (contentBottom - contentTop).coerceAtLeast(80f)

        when (settingsTabIndex) {
            0 -> {
                // Tab 0：8 款上屏视频滤镜卡片 (4列 × 2行)，实时预览！
                val cols = 4
                val rows = 2
                val gapX = 12f
                val gapY = 12f
                val cardW = (contentW - gapX * (cols - 1)) / cols
                val cardH = (contentH - gapY * (rows - 1)) / rows
                val filters = VideoFilterType.entries

                for ((idx, filter) in filters.withIndex()) {
                    val col = idx % cols
                    val row = idx / cols
                    val cl = contentLeft + col * (cardW + gapX)
                    val ct = contentTop + row * (cardH + gapY)
                    val cr = RectF(cl, ct, cl + cardW, ct + cardH)
                    settingsCardRects[idx] = cr

                    val isActiveFilter = (currentVideoFilter == filter)
                    val isFocused = (settingsCardCursorIndex == idx)

                    // 选中状态无硬边框线框 (drawOutline = !isActiveFilter)，改用优雅柔和的高亮填充与徽章
                    draw3dsSculptedDockButton(
                        canvas = canvas,
                        r = cr,
                        radius = 18f,
                        isPrimary = isActiveFilter || isFocused,
                        accentOverride = filter.accentColor,
                        drawOutline = !isActiveFilter
                    )

                    // 卡片上半部：迷你上屏滤镜色彩与纹理预览窗
                    val previewH = (cardH * 0.44f).coerceAtLeast(24f)
                    val previewRect = RectF(cr.left + 10f, cr.top + 10f, cr.right - 10f, cr.top + 10f + previewH)
                    paint.style = Paint.Style.FILL
                    paint.shader = LinearGradient(
                        previewRect.left, previewRect.top, previewRect.right, previewRect.bottom,
                        filter.previewColorTop,
                        filter.previewColorBottom,
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRoundRect(previewRect, 10f, 10f, paint)
                    paint.shader = null

                    // 迷你预览窗内的扫描线/网格装饰示意
                    if (filter == VideoFilterType.CRT_SCANLINE || filter == VideoFilterType.N3DS_LCD || filter == VideoFilterType.GAMEBOY_DMG) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 1f
                        paint.color = Color.argb(65, 0, 0, 0)
                        var ly = previewRect.top + 4f
                        while (ly < previewRect.bottom - 2f) {
                            canvas.drawLine(previewRect.left + 4f, ly, previewRect.right - 4f, ly, paint)
                            ly += 4f
                        }
                        paint.style = Paint.Style.FILL
                    }

                    // 右上角「使用中」徽章
                    if (isActiveFilter) {
                        val badgeH = (previewH * 0.48f).coerceIn(16f, 24f)
                        val badgeW = badgeH * 2.5f
                        val badgeRect = RectF(previewRect.right - badgeW - 6f, previewRect.top + 6f, previewRect.right - 6f, previewRect.top + 6f + badgeH)
                        paint.color = Color.argb(215, 16, 185, 129)
                        canvas.drawRoundRect(badgeRect, badgeH / 2f, badgeH / 2f, paint)
                        textPaint.color = Color.WHITE
                        textPaint.textSize = badgeH * 0.58f
                        textPaint.textAlign = Paint.Align.CENTER
                        canvas.drawText("✔ 使用中", badgeRect.centerX(), badgeRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
                    }

                    // 滤镜主标题
                    textPaint.color = currentTheme.textPrimaryColor
                    textPaint.textSize = (cardH * 0.165f).coerceIn(14f, 23f)
                    textPaint.textAlign = Paint.Align.CENTER
                    val titleY = previewRect.bottom + (cr.bottom - previewRect.bottom) * 0.42f
                    canvas.drawText(filter.displayName, cr.centerX(), titleY, textPaint)

                    // 滤镜简短描述
                    textPaint.color = currentTheme.textSecondaryColor
                    textPaint.textSize = (cardH * 0.115f).coerceIn(11f, 16f)
                    val descY = previewRect.bottom + (cr.bottom - previewRect.bottom) * 0.78f
                    canvas.drawText(filter.shortDesc, cr.centerX(), descY, textPaint)

                    if (isFocused) {
                        draw3dsCornerCursor(canvas, cr)
                    }
                }
            }

            1 -> {
                // Tab 1：6 款 3DS 主题配色 + Logo显示开关 + 背景音乐开关 (4列 × 2行 = 8张卡片)
                val cols = 4
                val rows = 2
                val gapX = 12f
                val gapY = 12f
                val cardW = (contentW - gapX * (cols - 1)) / cols
                val cardH = (contentH - gapY * (rows - 1)) / rows
                val themes = ThemeCatalog.ALL_THEMES

                for (idx in 0 until 8) {
                    val col = idx % cols
                    val row = idx / cols
                    val cl = contentLeft + col * (cardW + gapX)
                    val ct = contentTop + row * (cardH + gapY)
                    val cr = RectF(cl, ct, cl + cardW, ct + cardH)
                    settingsCardRects[idx] = cr
                    val isFocused = (settingsCardCursorIndex == idx)

                    if (idx < themes.size) {
                        val t = themes[idx]
                        val isCurTheme = (currentTheme.id == t.id)
                        draw3dsSculptedDockButton(
                            canvas = canvas,
                            r = cr,
                            radius = 18f,
                            isPrimary = isCurTheme || isFocused,
                            accentOverride = t.cursorMainColor
                        )

                        val swatchH = (cardH * 0.42f).coerceAtLeast(22f)
                        val swatchRect = RectF(cr.left + 10f, cr.top + 10f, cr.right - 10f, cr.top + 10f + swatchH)
                        paint.shader = LinearGradient(
                            swatchRect.left, swatchRect.top, swatchRect.right, swatchRect.bottom,
                            t.upperSkyTop,
                            t.cursorMainColor,
                            Shader.TileMode.CLAMP
                        )
                        canvas.drawRoundRect(swatchRect, 10f, 10f, paint)
                        paint.shader = null

                        textPaint.color = currentTheme.textPrimaryColor
                        textPaint.textSize = (cardH * 0.15f).coerceIn(13f, 21f)
                        textPaint.textAlign = Paint.Align.CENTER
                        canvas.drawText(
                            t.name,
                            cr.centerX(),
                            swatchRect.bottom + (cr.bottom - swatchRect.bottom) * 0.45f,
                            textPaint
                        )

                        textPaint.color = if (isCurTheme) t.cursorMainColor else currentTheme.textSecondaryColor
                        textPaint.textSize = (cardH * 0.115f).coerceIn(11f, 16f)
                        canvas.drawText(
                            if (isCurTheme) "✔ 当前使用主题" else "点击立即换肤",
                            cr.centerX(),
                            swatchRect.bottom + (cr.bottom - swatchRect.bottom) * 0.80f,
                            textPaint
                        )
                    } else if (idx == 6) {
                        // 卡片 6：上屏视觉模式 (4 种方案一键循环切换：✨ 悬浮Logo -> 📦 3D卡盒 -> 💎 双重豪华 -> 🎬 纯净大屏)
                        val curMode = upperDisplayMode
                        val accent = Color.parseColor(curMode.colorHex)
                        draw3dsSculptedDockButton(canvas, cr, 18f, isPrimary = isFocused || showUpperBoxart || showUpperLogo, accentOverride = accent)
                        textPaint.color = accent
                        textPaint.textSize = (cardH * 0.21f).coerceIn(16f, 28f)
                        textPaint.textAlign = Paint.Align.CENTER
                        canvas.drawText(curMode.title, cr.centerX(), cr.centerY() - cardH * 0.08f, textPaint)
                        textPaint.color = currentTheme.textSecondaryColor
                        textPaint.textSize = (cardH * 0.12f).coerceIn(11f, 16f)
                        canvas.drawText("点击切换方案 · " + curMode.shortLabel, cr.centerX(), cr.centerY() + cardH * 0.24f, textPaint)
                    } else {
                        // 卡片 7：音效音量调节设置 (默认 100%，点击依次切换 100% -> 75% -> 50% -> 25% -> 静音)
                        val isMuted = (sfxVolumePercent == 0 || !isSfxEnabled)
                        val accent = if (!isMuted) Color.parseColor("#F59E0B") else Color.parseColor("#64748B")
                        draw3dsSculptedDockButton(canvas, cr, 18f, isPrimary = isFocused || !isMuted, accentOverride = accent)
                        textPaint.color = accent
                        textPaint.textSize = (cardH * 0.22f).coerceIn(16f, 28f)
                        textPaint.textAlign = Paint.Align.CENTER
                        val volText = if (isMuted) "🔇 音效：已静音" else "🔊 音效音量：${sfxVolumePercent}%"
                        canvas.drawText(volText, cr.centerX(), cr.centerY() - cardH * 0.08f, textPaint)
                        textPaint.color = currentTheme.textSecondaryColor
                        textPaint.textSize = (cardH * 0.12f).coerceIn(11f, 16f)
                        val subText = if (isMuted) "点击恢复音量 (100%)" else "点击循环调节 (100/75/50/静音)"
                        canvas.drawText(subText, cr.centerX(), cr.centerY() + cardH * 0.24f, textPaint)
                    }

                    if (isFocused) {
                        draw3dsCornerCursor(canvas, cr)
                    }
                }
            }

            2 -> {
                // Tab 2：🎮 平台选择 (4列 × 2行，动态多页，翻页功能已移至底栏左右按键，卡片独占完整高度大空间！)
                val allPlatforms = com.gameconsole.launcher3ds.model.PlatformCatalog.getAllPlatforms(platformGameCounts.keys + mediaLibraries.map { it.name })
                val pageSize = 8
                val totalPages = ((allPlatforms.size + pageSize - 1) / pageSize).coerceAtLeast(1)
                platformPage = platformPage.coerceIn(0, totalPages - 1)
                pagePrevRect.setEmpty()
                pageNextRect.setEmpty()

                val cardsTop = contentTop
                val cardsBottom = contentBottom
                val cardsH = (cardsBottom - cardsTop).coerceAtLeast(100f)
                val cols = 4
                val rows = 2
                val gapX = 12f
                val gapY = 10f
                val cardW = (contentW - gapX * (cols - 1)) / cols
                val cardH = (cardsH - gapY * (rows - 1)) / rows

                val pageStartIdx = platformPage * pageSize
                val pagePlatforms = allPlatforms.drop(pageStartIdx).take(pageSize)

                for ((localIdx, plat) in pagePlatforms.withIndex()) {
                    val globalIdx = pageStartIdx + localIdx
                    val col = localIdx % cols
                    val row = localIdx / cols
                    val cl = contentLeft + col * (cardW + gapX)
                    val ct = cardsTop + row * (cardH + gapY)
                    val cr = RectF(cl, ct, cl + cardW, ct + cardH)
                    settingsCardRects[globalIdx] = cr

                    val isEnabled = enabledPlatforms.any { it.equals(plat.id, ignoreCase = true) }
                    val isFocused = (settingsCardCursorIndex == globalIdx)
                    val platColor = try { Color.parseColor(plat.colorHex) } catch (_: Exception) { currentTheme.cursorMainColor }

                    // 绘制立体圆角卡片底座
                    draw3dsSculptedDockButton(
                        canvas = canvas,
                        r = cr,
                        radius = 16f,
                        isPrimary = isEnabled || isFocused,
                        accentOverride = if (isEnabled) platColor else Color.parseColor("#94A3B8"),
                        drawOutline = !isEnabled
                    )

                    // 1) 顶部中央：专属机种色徽标小药丸 (如 [ 3DS ], [ NDS ], [ GBA ], [ PSP ], [ 街机 ])
                    val badgeH = (cardH * 0.17f).coerceIn(18f, 25f)
                    val badgeW = (badgeH * 2.3f).coerceIn(46f, 68f)
                    val badgeRect = RectF(cr.centerX() - badgeW / 2f, cr.top + cardH * 0.08f, cr.centerX() + badgeW / 2f, cr.top + cardH * 0.08f + badgeH)
                    paint.style = Paint.Style.FILL
                    paint.color = if (isEnabled) platColor else Color.argb(120, 100, 116, 139)
                    canvas.drawRoundRect(badgeRect, badgeH / 2f, badgeH / 2f, paint)

                    textPaint.typeface = apolloMediumTypeface
                    textPaint.textSize = badgeH * 0.58f
                    textPaint.color = Color.WHITE
                    textPaint.textAlign = Paint.Align.CENTER
                    val displayBadge = if (plat.shortName.length > 5) plat.id.take(5) else plat.shortName
                    canvas.drawText(displayBadge, badgeRect.centerX(), badgeRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

                    // 2) 中央主标题：平台全名
                    val cleanName = when (plat.id.uppercase()) {
                        "3DS" -> "Nintendo 3DS"
                        "NDS" -> "Nintendo DS"
                        "GBA" -> "Game Boy Adv"
                        "GBC" -> "Game Boy Color"
                        "GB" -> "Game Boy"
                        "N64" -> "任天堂 64"
                        "FC" -> "FC 红白机"
                        "SFC" -> "SFC 超任"
                        "NGC" -> "任天堂 NGC"
                        "WII" -> "任天堂 Wii"
                        "SWITCH" -> "Switch"
                        "PSP" -> "索尼 PSP"
                        "PS1" -> "索尼 PS1"
                        "PS2" -> "索尼 PS2"
                        "VITA" -> "索尼 PSV"
                        "MD" -> "世嘉 MD"
                        "DC" -> "世嘉 DC"
                        "SS" -> "世嘉土星"
                        "GG" -> "世嘉 GG"
                        "SMS" -> "世嘉 SMS"
                        "ARCADE" -> "经典街机"
                        "NEOGEO" -> "Neo Geo"
                        "PCE" -> "PC Engine"
                        "WSC" -> "神奇天鹅"
                        "DOS" -> "DOS 电脑"
                        "ATARI" -> "雅达利"
                        "POKEMINI" -> "宝可梦迷你"
                        "MSX" -> "MSX 电脑"
                        "ANDROID" -> "安卓游戏"
                        else -> plat.shortName
                    }

                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.typeface = apolloMediumTypeface
                    val titleSize = (cardH * 0.175f).coerceIn(20f, 25f)
                    textPaint.textSize = titleSize
                    textPaint.color = if (isEnabled) platColor else currentTheme.textPrimaryColor
                    val titleY = cr.top + cardH * 0.48f
                    canvas.drawText(cleanName, cr.centerX(), titleY, textPaint)

                    // 3) 居中副标题：已检测游戏款数
                    val count = platformGameCounts[plat.id] ?: 0
                    textPaint.typeface = apolloRegularTypeface
                    val countSize = (cardH * 0.11f).coerceIn(12.5f, 15f)
                    textPaint.textSize = countSize
                    textPaint.color = if (count > 0) currentTheme.textPrimaryColor else currentTheme.textSecondaryColor
                    val countY = cr.top + cardH * 0.66f
                    val countText = if (count > 0) "🎮 ${count} 款游戏就绪" else "未检测到 ROM"
                    canvas.drawText(countText, cr.centerX(), countY, textPaint)

                    // 4) 底部中央：大尺寸 3DS 触控开关药丸 (Toggle Switch)
                    val switchH = (cardH * 0.19f).coerceIn(22f, 28f)
                    val switchW = (cardW * 0.58f).coerceIn(98f, 135f)
                    val switchRect = RectF(cr.centerX() - switchW / 2f, cr.bottom - switchH - cardH * 0.08f, cr.centerX() + switchW / 2f, cr.bottom - cardH * 0.08f)

                    if (isEnabled) {
                        paint.style = Paint.Style.FILL
                        paint.color = Color.argb(235, 16, 185, 129) // 翡翠绿
                        canvas.drawRoundRect(switchRect, switchH / 2f, switchH / 2f, paint)
                        textPaint.typeface = apolloMediumTypeface
                        textPaint.textSize = switchH * 0.52f
                        textPaint.color = Color.WHITE
                        textPaint.textAlign = Paint.Align.CENTER
                        canvas.drawText("✔ 平台已启用", switchRect.centerX(), switchRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
                    } else {
                        paint.style = Paint.Style.FILL
                        paint.color = Color.argb(125, 100, 116, 139)
                        canvas.drawRoundRect(switchRect, switchH / 2f, switchH / 2f, paint)
                        textPaint.typeface = apolloRegularTypeface
                        textPaint.textSize = switchH * 0.52f
                        textPaint.color = Color.WHITE
                        textPaint.textAlign = Paint.Align.CENTER
                        canvas.drawText("○ 平台已隐藏", switchRect.centerX(), switchRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
                    }

                    if (isFocused) {
                        draw3dsCornerCursor(canvas, cr)
                    }
                }
            }

            3 -> {
                // Tab 3：📂 游戏库与模拟器管理（导入和管理功能：扫描导入ROMs、管理存储路径、模拟器绑定等）
                val cols = 2
                val rows = 3
                val gapX = 14f
                val gapY = 10f
                val cardW = (contentW - gapX) / 2f
                val cardH = (contentH - gapY * 2f) / 3f
                val titles = arrayOf(
                    "💾 扫描存储位置：${scanStorageScope.title}",
                    "🔍 立即手动扫描导入 ROMs",
                    "📂 挂载游戏目录管理与启闭",
                    "🎮 3DS 平台模拟器配置",
                    "🕹️ NDS 平台模拟器配置",
                    "⚙️ 存储路径与系统高级设置"
                )
                val descs = arrayOf(
                    "当前选择: ${scanStorageScope.shortLabel} · 点击配置 (内部存储 / TF卡 / 两者同时)",
                    "按当前介质(${scanStorageScope.shortLabel})全量扫描并匹配天马G封面与实机视频",
                    "已检测到 ${mediaLibraries.size} 个游戏目录 (已启用 ${mediaLibraries.count { it.isEnabled }} 个，点击进入手动启闭)",
                    run {
                        val has3ds = hasPegasusMetadataProvider?.invoke("3DS") == true
                        val metaLabel = if (has3ds) "✅介绍文件已就绪" else "⚠️未找到介绍文件"
                        "当前绑定: $default3dsEmulatorLabel · $metaLabel (点击配置)"
                    },
                    run {
                        val hasNds = hasPegasusMetadataProvider?.invoke("NDS") == true
                        val metaLabel = if (hasNds) "✅介绍文件已就绪" else "⚠️未找到介绍文件"
                        "当前绑定: $defaultNdsEmulatorLabel · $metaLabel (点击配置)"
                    },
                    "管理自定义目录、复古滤镜、双屏分显、音效与系统配置"
                )
                val accents = intArrayOf(
                    Color.parseColor("#0284C7"),
                    Color.parseColor("#10B981"),
                    Color.parseColor("#3B82F6"),
                    Color.parseColor("#E60012"),
                    Color.parseColor("#059669"),
                    Color.parseColor("#8B5CF6")
                )

                for (idx in 0 until 6) {
                    val col = idx % cols
                    val row = idx / cols
                    val cl = contentLeft + col * (cardW + gapX)
                    val ct = contentTop + row * (cardH + gapY)
                    val cr = RectF(cl, ct, cl + cardW, ct + cardH)
                    settingsCardRects[idx] = cr
                    val isFocused = (settingsCardCursorIndex == idx)

                    draw3dsSculptedDockButton(
                        canvas = canvas,
                        r = cr,
                        radius = 16f,
                        isPrimary = isFocused,
                        accentOverride = accents[idx]
                    )

                    textPaint.typeface = apolloMediumTypeface
                    textPaint.color = currentTheme.textPrimaryColor
                    textPaint.textSize = (cardH * 0.30f).coerceIn(18f, 25f)
                    textPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(titles[idx], cr.centerX(), cr.centerY() - cardH * 0.14f, textPaint)

                    textPaint.typeface = apolloRegularTypeface
                    textPaint.color = currentTheme.textSecondaryColor
                    textPaint.textSize = (cardH * 0.20f).coerceIn(14f, 18.5f)
                    canvas.drawText(descs[idx], cr.centerX(), cr.centerY() + cardH * 0.22f, textPaint)

                    if (isFocused) {
                        draw3dsCornerCursor(canvas, cr)
                    }
                }
            }
        }

        // 4. 底部原版 3DS 三段式设置底栏（左：[L1] ◀ 上一标签 | 中：✔ 完成并返回桌面 (B键) | 右：下一标签 ▶ [R1]）
        val dockTop = h - bottomBarH
        val sidePad = 12f
        val gap = 10f
        val btnTop = dockTop + 7f
        val btnBottom = h - 7f
        val totalW = w - sidePad * 2f - gap * 2f
        val sideW = totalW * 0.28f
        val centerW = totalW * 0.44f

        bottomLeftBtnRect.set(sidePad, btnTop, sidePad + sideW, btnBottom)
        bottomCenterBtnRect.set(bottomLeftBtnRect.right + gap, btnTop - 2f, bottomLeftBtnRect.right + gap + centerW, btnBottom)
        bottomRightBtnRect.set(bottomCenterBtnRect.right + gap, btnTop, w - sidePad, btnBottom)

        val btnRadius = (btnBottom - btnTop) * 0.48f

        // 根据当前所处具体设置 Tab 动态适配底栏左右功能按键（如翻页、切换项等）
        val allPlatformsForDock = if (settingsTabIndex == 2) com.gameconsole.launcher3ds.model.PlatformCatalog.getAllPlatforms(platformGameCounts.keys + mediaLibraries.map { it.name }) else emptyList()
        val totalPlatPages = if (settingsTabIndex == 2) ((allPlatformsForDock.size + 7) / 8).coerceAtLeast(1) else 1

        val (leftLabel, leftActive, leftAccent) = when (settingsTabIndex) {
            2 -> Triple(
                if (platformPage > 0) "◀ 上一页 (第 ${platformPage + 1}/$totalPlatPages 页)" else "◀ 首页 (第 1/$totalPlatPages 页)",
                platformPage > 0,
                currentTheme.cursorMainColor
            )
            0 -> Triple("◀ 上一滤镜 (L1)", false, currentTheme.cursorMainColor)
            1 -> Triple("◀ 上一主题 (L1)", false, currentTheme.cursorMainColor)
            3 -> Triple("🔍 手动扫描 (L1)", false, Color.parseColor("#10B981"))
            else -> Triple("◀ 上一项 (L1)", false, currentTheme.cursorMainColor)
        }

        val (rightLabel, rightActive, rightAccent) = when (settingsTabIndex) {
            2 -> Triple(
                if (platformPage < totalPlatPages - 1) "下一页 (第 ${platformPage + 1}/$totalPlatPages 页) ▶" else "末页 (第 $totalPlatPages/$totalPlatPages 页) ▶",
                platformPage < totalPlatPages - 1,
                currentTheme.cursorMainColor
            )
            0 -> Triple("下一滤镜 (R1) ▶", false, currentTheme.cursorMainColor)
            1 -> Triple("下一主题 (R1) ▶", false, currentTheme.cursorMainColor)
            3 -> Triple("⚙️ 高级设置 (R1) ▶", false, Color.parseColor("#8B5CF6"))
            else -> Triple("下一项 (R1) ▶", false, currentTheme.cursorMainColor)
        }

        draw3dsSculptedDockButton(
            canvas = canvas,
            r = bottomLeftBtnRect,
            radius = btnRadius,
            isPrimary = leftActive,
            accentOverride = leftAccent,
            drawOutline = !leftActive
        )
        draw3dsSculptedDockButton(
            canvas = canvas,
            r = bottomCenterBtnRect,
            radius = btnRadius,
            isPrimary = true,
            accentOverride = currentTheme.cursorMainColor
        )
        draw3dsSculptedDockButton(
            canvas = canvas,
            r = bottomRightBtnRect,
            radius = btnRadius,
            isPrimary = rightActive,
            accentOverride = rightAccent,
            drawOutline = !rightActive
        )

        val sideFontSz = (bottomLeftBtnRect.height() * 0.38f).coerceIn(14f, 22f)
        val centerFontSz = (bottomCenterBtnRect.height() * 0.44f).coerceIn(17f, 26f)

        textPaint.typeface = apolloMediumTypeface
        textPaint.isFakeBoldText = false
        textPaint.textAlign = Paint.Align.CENTER

        // 左功能键文字
        textPaint.color = if (leftActive) Color.WHITE else currentTheme.textPrimaryColor
        textPaint.textSize = sideFontSz
        canvas.drawText(
            leftLabel,
            bottomLeftBtnRect.centerX(),
            bottomLeftBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f,
            textPaint
        )

        // 右功能键文字
        textPaint.color = if (rightActive) Color.WHITE else currentTheme.textPrimaryColor
        textPaint.textSize = sideFontSz
        canvas.drawText(
            rightLabel,
            bottomRightBtnRect.centerX(),
            bottomRightBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f,
            textPaint
        )

        // 中央确认返回桌面键
        textPaint.color = currentTheme.textPrimaryColor
        textPaint.textSize = centerFontSz
        canvas.drawText(
            "✔ 完成并返回桌面 (B)",
            bottomCenterBtnRect.centerX(),
            bottomCenterBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f,
            textPaint
        )
        textPaint.typeface = boldTypeface
    }

    fun handleBackPressed(): Boolean {
        if (isAllAppsDrawerOpen) {
            closeAllAppsDrawer()
            return true
        }
        if (isGameDescriptionOpen) {
            closeGameDescription()
            return true
        }
        if (settingsSubPanel == SettingsSubPanel.PEGASUS_METADATA_PREVIEW) {
            settingsSubPanel = if (currentPegasusInspectPlatform == "NDS") SettingsSubPanel.EMULATOR_PICKER_NDS else SettingsSubPanel.EMULATOR_PICKER_3DS
            SoundEffectEngine.playBackCancel()
            invalidate()
            return true
        }
        if (settingsSubPanel == SettingsSubPanel.STARTUP_DIAGNOSTICS) {
            settingsSubPanel = SettingsSubPanel.EMULATOR_PICKER_3DS
            SoundEffectEngine.playBackCancel()
            invalidate()
            return true
        }
        if (settingsSubPanel == SettingsSubPanel.MEDIA_LIBRARY_MANAGER || 
            settingsSubPanel == SettingsSubPanel.ROM_DIRECTORY_PICKER ||
            settingsSubPanel == SettingsSubPanel.SUB_SCREEN_FILTER_CONFIG ||
            settingsSubPanel == SettingsSubPanel.MAIN_SCREEN_FILTER_CONFIG ||
            settingsSubPanel == SettingsSubPanel.EMULATOR_PICKER_3DS ||
            settingsSubPanel == SettingsSubPanel.EMULATOR_PICKER_NDS) {
            settingsSubPanel = SettingsSubPanel.ADVANCED_SETTINGS
            SoundEffectEngine.playBackCancel()
            invalidate()
            return true
        }
        if (settingsSubPanel != SettingsSubPanel.NONE) {
            settingsSubPanel = SettingsSubPanel.NONE
            SoundEffectEngine.playBackCancel()
            invalidate()
            return true
        }
        if (isSettingsPanelOpen) {
            closeSettingsPanel()
            return true
        }
        return false
    }

    fun openSettingsPanel(initialTab: Int = 0) {
        if (isAllAppsDrawerOpen) {
            isAllAppsDrawerOpen = false
        }
        if (isGameDescriptionOpen) {
            isGameDescriptionOpen = false
        }
        isSettingsPanelOpen = true
        settingsSubPanel = SettingsSubPanel.NONE
        settingsTabIndex = initialTab.coerceIn(0, 3)
        invalidate()
    }

    fun closeSettingsPanel() {
        if (isSettingsPanelOpen) {
            isSettingsPanelOpen = false
            settingsSubPanel = SettingsSubPanel.NONE
            selectedTopIconIndex = -1
            listener?.onSettingsPanelClosed()
            invalidate()
        }
    }

    fun handleSettingsActionLeft() {
        if (!isSettingsPanelOpen) return
        if (settingsSubPanel != SettingsSubPanel.NONE) return
        when (settingsTabIndex) {
            2 -> {
                val totalPages = ((com.gameconsole.launcher3ds.model.PlatformCatalog.getAllPlatforms(platformGameCounts.keys + mediaLibraries.map { it.name }).size + 7) / 8).coerceAtLeast(1)
                if (platformPage > 0) {
                    platformPage--
                    settingsCardCursorIndex = platformPage * 8
                    SoundEffectEngine.playCursorMove()
                    invalidate()
                }
            }
            0 -> {
                // 上一个视频滤镜
                val filters = VideoFilterType.entries
                val curIdx = filters.indexOf(currentVideoFilter)
                val prevIdx = (curIdx - 1 + filters.size) % filters.size
                listener?.onVideoFilterSelected(filters[prevIdx])
                SoundEffectEngine.playCursorMove()
                invalidate()
            }
            1 -> {
                // 上一个主题
                val themes = ThemeCatalog.ALL_THEMES
                val curIdx = themes.indexOfFirst { it.id == currentTheme.id }.coerceAtLeast(0)
                val prevIdx = (curIdx - 1 + themes.size) % themes.size
                listener?.onThemeSelectedFromPanel(themes[prevIdx])
                SoundEffectEngine.playCursorMove()
                invalidate()
            }
            3 -> {
                // 快速手动全量扫描导入 ROM
                SoundEffectEngine.playSelectApplet()
                listener?.onManualRomScanRequested()
            }
        }
    }

    fun handleSettingsActionRight() {
        if (!isSettingsPanelOpen) return
        if (settingsSubPanel != SettingsSubPanel.NONE) return
        when (settingsTabIndex) {
            2 -> {
                val totalPages = ((com.gameconsole.launcher3ds.model.PlatformCatalog.getAllPlatforms(platformGameCounts.keys + mediaLibraries.map { it.name }).size + 7) / 8).coerceAtLeast(1)
                if (platformPage < totalPages - 1) {
                    platformPage++
                    settingsCardCursorIndex = platformPage * 8
                    SoundEffectEngine.playCursorMove()
                    invalidate()
                }
            }
            0 -> {
                // 下一个视频滤镜
                val filters = VideoFilterType.entries
                val curIdx = filters.indexOf(currentVideoFilter)
                val nextIdx = (curIdx + 1) % filters.size
                listener?.onVideoFilterSelected(filters[nextIdx])
                SoundEffectEngine.playCursorMove()
                invalidate()
            }
            1 -> {
                // 下一个主题
                val themes = ThemeCatalog.ALL_THEMES
                val curIdx = themes.indexOfFirst { it.id == currentTheme.id }.coerceAtLeast(0)
                val nextIdx = (curIdx + 1) % themes.size
                listener?.onThemeSelectedFromPanel(themes[nextIdx])
                SoundEffectEngine.playCursorMove()
                invalidate()
            }
            3 -> {
                // 展开高级设置
                settingsSubPanel = SettingsSubPanel.ADVANCED_SETTINGS
                SoundEffectEngine.playSelectApplet()
                invalidate()
            }
        }
    }

    fun stepSettingsTab(delta: Int) {
        if (!isSettingsPanelOpen) return
        if (settingsSubPanel != SettingsSubPanel.NONE) return
        settingsTabIndex = (settingsTabIndex + delta + 4) % 4
        invalidate()
    }

    fun activateFocusedSettingsCard() {
        if (!isSettingsPanelOpen) return
        if (settingsSubPanel == SettingsSubPanel.EMULATOR_PICKER_3DS || settingsSubPanel == SettingsSubPanel.EMULATOR_PICKER_NDS) {
            val plat = if (settingsSubPanel == SettingsSubPanel.EMULATOR_PICKER_3DS) PlatformType.N3DS else PlatformType.NDS
            handleEmulatorCardClicked(subPanelCursorIndex, plat)
            return
        }
        if (settingsSubPanel == SettingsSubPanel.SUB_SCREEN_FILTER_CONFIG) {
            when (subPanelCursorIndex) {
                0 -> listener?.onSubscreenRetroFilterToggled()
                1 -> listener?.onSubscreenRetroFilterTypeSelected("LCD_3X")
                2 -> listener?.onSubscreenRetroFilterTypeSelected("DOT_MATRIX")
                3 -> listener?.onSubscreenRetroFilterTypeSelected("SCANLINE")
                4 -> listener?.onSubscreenRetroFilterTypeSelected("CRT_APERTURE")
                5 -> listener?.onSubscreenRetroFilterOpacitySelected(10)
                6 -> listener?.onSubscreenRetroFilterOpacitySelected(20)
                7 -> listener?.onSubscreenRetroFilterOpacitySelected(30)
                8 -> listener?.onSubscreenRetroFilterOpacitySelected(40)
            }
            return
        }
        if (settingsSubPanel == SettingsSubPanel.MAIN_SCREEN_FILTER_CONFIG) {
            when (subPanelCursorIndex) {
                0 -> listener?.onMainScreenRetroFilterAuto2DToggled()
                1 -> listener?.onMainScreenRetroFilterToggled()
                2 -> listener?.onMainScreenRetroFilterTypeSelected("LCD_3X")
                3 -> listener?.onMainScreenRetroFilterTypeSelected("DOT_MATRIX")
                4 -> listener?.onMainScreenRetroFilterTypeSelected("SCANLINE")
                5 -> listener?.onMainScreenRetroFilterTypeSelected("CRT_APERTURE")
                6 -> listener?.onMainScreenRetroFilterOpacitySelected(10)
                7 -> listener?.onMainScreenRetroFilterOpacitySelected(20)
                8 -> listener?.onMainScreenRetroFilterOpacitySelected(30)
                9 -> listener?.onMainScreenRetroFilterOpacitySelected(40)
            }
            return
        }

        if (settingsSubPanel == SettingsSubPanel.ADVANCED_SETTINGS) {
            triggerAdvancedCardAt(subPanelCursorIndex)
            return
        }
        if (settingsSubPanel == SettingsSubPanel.MEDIA_LIBRARY_MANAGER || settingsSubPanel == SettingsSubPanel.ROM_DIRECTORY_PICKER) {
            val lib = mediaLibraries.getOrNull(subPanelCursorIndex)
            if (lib != null) {
                listener?.onMediaLibraryToggled(lib.path, !lib.isEnabled)
                SoundEffectEngine.playSelectApplet()
                invalidate()
            }
            return
        }
        if (settingsSubPanel == SettingsSubPanel.AUTO_RANDOM_INTERVAL) {
            isAutoRandomPlaying = !isAutoRandomPlaying
            listener?.onAutoRandomToggled()
            SoundEffectEngine.playSelectApplet()
            invalidate()
            return
        }
        if (settingsSubPanel == SettingsSubPanel.ROM_SCAN_CONFIG) {
            val scopes = arrayOf(ScanStorageScope.BOTH, ScanStorageScope.INTERNAL_ONLY, ScanStorageScope.SDCARD_ONLY)
            if (subPanelCursorIndex in 0..2) {
                val chosen = scopes[subPanelCursorIndex]
                scanStorageScope = chosen
                SoundEffectEngine.playSelectApplet()
                listener?.onScanStorageScopeChanged(chosen)
                invalidate()
            } else if (subPanelCursorIndex == 3) {
                SoundEffectEngine.playSelectApplet()
                listener?.onManualRomScanRequested()
            }
            return
        }
        triggerSettingsCardAt(settingsCardCursorIndex)
    }

    private fun triggerSettingsCardAt(idx: Int) {
        when (settingsTabIndex) {
            0 -> {
                val filter = VideoFilterType.entries.getOrNull(idx) ?: return
                currentVideoFilter = filter
                listener?.onVideoFilterSelected(filter)
            }
            1 -> {
                val themes = ThemeCatalog.ALL_THEMES
                when {
                    idx in themes.indices -> listener?.onThemeSelectedFromPanel(themes[idx])
                    idx == 6 -> {
                        listener?.onCycleUpperDisplayModeRequested()
                    }
                    idx == 7 -> listener?.onQuickToggleSfxRequested()
                }
            }
            2 -> {
                // Tab 2：点击平台卡片，立即切换开启/关闭！
                val allPlatforms = com.gameconsole.launcher3ds.model.PlatformCatalog.getAllPlatforms(platformGameCounts.keys + mediaLibraries.map { it.name })
                val plat = allPlatforms.getOrNull(idx) ?: return
                listener?.onPlatformToggled(plat.id)
            }
            3 -> {
                // Tab 3：游戏库导入与模拟器管理
                when (idx) {
                    0 -> {
                        settingsSubPanel = SettingsSubPanel.ROM_SCAN_CONFIG
                        SoundEffectEngine.playSelectApplet()
                        invalidate()
                    }
                    1 -> {
                        SoundEffectEngine.playSelectApplet()
                        listener?.onManualRomScanRequested()
                    }
                    2 -> {
                        mediaLibOriginSubPanel = SettingsSubPanel.NONE
                        settingsSubPanel = SettingsSubPanel.MEDIA_LIBRARY_MANAGER
                        mediaLibScrollY = 0f
                        targetMediaLibScrollY = 0f
                        subPanelCursorIndex = 0
                        pendingRemovePath = null
                        pendingRemoveAll = false
                        SoundEffectEngine.playSelectApplet()
                        listener?.onMediaLibraryManagerOpened()
                        invalidate()
                    }
                    3 -> {
                        settingsSubPanel = SettingsSubPanel.EMULATOR_PICKER_3DS
                        SoundEffectEngine.playSelectApplet()
                        invalidate()
                    }
                    4 -> {
                        settingsSubPanel = SettingsSubPanel.EMULATOR_PICKER_NDS
                        SoundEffectEngine.playSelectApplet()
                        invalidate()
                    }
                    5 -> {
                        settingsSubPanel = SettingsSubPanel.ADVANCED_SETTINGS
                        SoundEffectEngine.playSelectApplet()
                        invalidate()
                    }
                }
            }
        }
        invalidate()
    }

    /**
     * 绘制统一的 3DS 下屏二级/三级设置页面底部操作底栏：
     * 将「◀ 返回 (B)」等核心操作按键放置在屏幕最底部，符合掌机单手握持人体工学，触手可及。
     */
    private fun drawSubPanelBottomBar(
        canvas: Canvas,
        w: Float,
        h: Float,
        bottomBarH: Float,
        backLabel: String = "◀ 返回 (B)"
    ) {
        val dockTop = h - bottomBarH
        val btnTop = dockTop + 7f
        val btnBottom = h - 7f
        val btnRadius = (btnBottom - btnTop) * 0.48f

        // 底部居中宽幅拟真 3DS 立体返回键 (4.5寸 960x720 屏幕大字高对比呈现)
        val btnW = (w * 0.48f).coerceIn(260f, 460f)
        subPanelBackBtnRect.set(w / 2f - btnW / 2f, btnTop, w / 2f + btnW / 2f, btnBottom)

        draw3dsSculptedDockButton(
            canvas = canvas,
            r = subPanelBackBtnRect,
            radius = btnRadius,
            isPrimary = false,
            accentOverride = currentTheme.cursorMainColor
        )

        textPaint.typeface = apolloMediumTypeface
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = currentTheme.textPrimaryColor
        textPaint.textSize = (subPanelBackBtnRect.height() * 0.46f).coerceIn(19f, 23f)
        canvas.drawText(
            backLabel,
            subPanelBackBtnRect.centerX(),
            subPanelBackBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f,
            textPaint
        )
    }

    private fun drawAutoRandomIntervalSubPanel(
        canvas: Canvas,
        innerPanel: RectF,
        w: Float,
        h: Float,
        bottomBarH: Float
    ) {
        val headerH = (innerPanel.height() * 0.11f).coerceIn(36f, 46f)
        val headerTop = innerPanel.top + 8f

        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = (headerH * 0.52f).coerceIn(16f, 22f)
        textPaint.color = currentTheme.cursorMainColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("🎲 自动随机轮播时长设置", innerPanel.centerX(), headerTop + headerH * 0.55f, textPaint)

        val descY = headerTop + headerH + 14f
        textPaint.typeface = apolloRegularTypeface
        textPaint.textSize = 13f
        textPaint.color = currentTheme.textSecondaryColor
        canvas.drawText("设置上屏实机游戏封面与演示视频的自动轮播间隔时间 (3~600秒)", innerPanel.centerX(), descY, textPaint)

        val counterTop = descY + 12f
        val counterH = (innerPanel.height() * 0.24f).coerceIn(75f, 105f)
        val counterW = 220f
        intervalValueRect.set(innerPanel.centerX() - counterW / 2f, counterTop, innerPanel.centerX() + counterW / 2f, counterTop + counterH)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(40, 0, 0, 0)
        canvas.drawRoundRect(RectF(intervalValueRect.left, intervalValueRect.top + 2f, intervalValueRect.right, intervalValueRect.bottom + 2f), 18f, 18f, paint)
        paint.color = blendColor(currentTheme.slotFillColor, Color.WHITE, 0.45f)
        canvas.drawRoundRect(intervalValueRect, 18f, 18f, paint)

        textPaint.typeface = boldTypeface
        textPaint.textSize = counterH * 0.48f
        textPaint.color = currentTheme.cursorMainColor
        canvas.drawText("$autoRandomIntervalSec", intervalValueRect.centerX(), intervalValueRect.centerY() - 2f, textPaint)

        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 13f
        textPaint.color = currentTheme.textSecondaryColor
        canvas.drawText("秒 / Seconds", intervalValueRect.centerX(), intervalValueRect.bottom - 12f, textPaint)

        val stepBtnW = 76f
        val stepBtnH = counterH * 0.65f
        val stepBtnY = intervalValueRect.centerY() - stepBtnH / 2f
        val gap = 10f

        stepMinus5BtnRect.set(intervalValueRect.left - gap * 2f - stepBtnW * 2f, stepBtnY, intervalValueRect.left - gap * 2f - stepBtnW, stepBtnY + stepBtnH)
        stepMinus1BtnRect.set(intervalValueRect.left - gap - stepBtnW, stepBtnY, intervalValueRect.left - gap, stepBtnY + stepBtnH)
        stepPlus1BtnRect.set(intervalValueRect.right + gap, stepBtnY, intervalValueRect.right + gap + stepBtnW, stepBtnY + stepBtnH)
        stepPlus5BtnRect.set(intervalValueRect.right + gap * 2f + stepBtnW, stepBtnY, intervalValueRect.right + gap * 2f + stepBtnW * 2f, stepBtnY + stepBtnH)

        draw3dsSculptedDockButton(canvas, stepMinus5BtnRect, 14f, isPrimary = false)
        draw3dsSculptedDockButton(canvas, stepMinus1BtnRect, 14f, isPrimary = false)
        draw3dsSculptedDockButton(canvas, stepPlus1BtnRect, 14f, isPrimary = false)
        draw3dsSculptedDockButton(canvas, stepPlus5BtnRect, 14f, isPrimary = false)

        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 16f
        textPaint.color = currentTheme.textPrimaryColor
        canvas.drawText("-5 秒", stepMinus5BtnRect.centerX(), stepMinus5BtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        canvas.drawText("-1 秒", stepMinus1BtnRect.centerX(), stepMinus1BtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        canvas.drawText("+1 秒", stepPlus1BtnRect.centerX(), stepPlus1BtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        canvas.drawText("+5 秒", stepPlus5BtnRect.centerX(), stepPlus5BtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        val presetLabelY = counterTop + counterH + 20f
        textPaint.typeface = apolloRegularTypeface
        textPaint.textSize = 13f
        textPaint.color = currentTheme.textSecondaryColor
        canvas.drawText("⚡ 快捷一键预设时长：", innerPanel.centerX(), presetLabelY, textPaint)

        val presets = intArrayOf(5, 10, 15, 20, 30, 45, 60, 120)
        val pCols = 4
        val pRows = 2
        val pGapX = 12f
        val pGapY = 8f
        val pGridTop = presetLabelY + 8f
        val pTotalW = (innerPanel.width() - 80f).coerceAtLeast(200f)
        val pCardW = (pTotalW - pGapX * (pCols - 1)) / pCols
        val pCardH = 34f
        val pLeft = innerPanel.centerX() - pTotalW / 2f

        for ((idx, sec) in presets.withIndex()) {
            val col = idx % pCols
            val row = idx / pCols
            val cl = pLeft + col * (pCardW + pGapX)
            val ct = pGridTop + row * (pCardH + pGapY)
            val cr = presetChipRects[idx]
            cr.set(cl, ct, cl + pCardW, ct + pCardH)

            val isCurrent = (autoRandomIntervalSec == sec)
            draw3dsSculptedDockButton(canvas, cr, 12f, isPrimary = isCurrent, accentOverride = if (isCurrent) currentTheme.cursorMainColor else Color.parseColor("#94A3B8"))

            textPaint.typeface = apolloMediumTypeface
            textPaint.textSize = 13.5f
            textPaint.color = if (isCurrent) Color.WHITE else currentTheme.textPrimaryColor
            val label = if (isCurrent) "✔ ${sec}秒" else "${sec}秒"
            canvas.drawText(label, cr.centerX(), cr.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        }

        // 底部 3DS 原版双键操作底栏：[ ◀ 返回 (B) ] [ 🎲 开始/停止轮播 ]
        val dockTop = h - bottomBarH
        val btnTop = dockTop + 7f
        val btnBottom = h - 7f
        val btnRadius = (btnBottom - btnTop) * 0.48f
        val sidePad = 14f
        val btnGap = 12f
        val availW = w - sidePad * 2f - btnGap
        val backW = availW * 0.38f

        subPanelBackBtnRect.set(sidePad, btnTop, sidePad + backW, btnBottom)
        intervalPlayToggleBtnRect.set(subPanelBackBtnRect.right + btnGap, btnTop, w - sidePad, btnBottom)

        draw3dsSculptedDockButton(
            canvas = canvas,
            r = subPanelBackBtnRect,
            radius = btnRadius,
            isPrimary = false
        )
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = (subPanelBackBtnRect.height() * 0.42f).coerceIn(15f, 20f)
        textPaint.color = currentTheme.textPrimaryColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("◀ 返回 (B)", subPanelBackBtnRect.centerX(), subPanelBackBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        val playAccent = if (isAutoRandomPlaying) Color.parseColor("#EF4444") else Color.parseColor("#10B981")
        draw3dsSculptedDockButton(
            canvas = canvas,
            r = intervalPlayToggleBtnRect,
            radius = btnRadius,
            isPrimary = true,
            accentOverride = playAccent
        )
        textPaint.textSize = (intervalPlayToggleBtnRect.height() * 0.42f).coerceIn(15f, 20f)
        textPaint.color = Color.WHITE
        val playText = if (isAutoRandomPlaying) "⏹️ 停止自动轮播" else "🎲 开始自动轮播"
        canvas.drawText(playText, intervalPlayToggleBtnRect.centerX(), intervalPlayToggleBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
    }

    private fun drawCategoryManagerSubPanel(
        canvas: Canvas,
        innerPanel: RectF,
        w: Float,
        h: Float,
        bottomBarH: Float
    ) {
        val headerH = 48f
        val headerTop = innerPanel.top + 6f
        val contentLeft = innerPanel.left + 14f
        val contentRight = innerPanel.right - 14f
        val contentW = (contentRight - contentLeft).coerceAtLeast(100f)

        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 26f
        textPaint.color = currentTheme.cursorMainColor
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("🏷️ 系列与游戏类别选择 & 管理", contentLeft, headerTop + 33f, textPaint)

        // 右上角：新建分类/系列 (实心按钮 + 纯白高对比文字)
        val pillW = 210f
        val pillH = 42f
        catMgrAddBtnRect.set(contentRight - pillW, headerTop + 3f, contentRight, headerTop + 3f + pillH)
        draw3dsSculptedDockButton(
            canvas = canvas,
            r = catMgrAddBtnRect,
            radius = pillH / 2f,
            isPrimary = true,
            accentOverride = Color.parseColor("#10B981"),
            isSolid = true
        )
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 19f
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("➕ 新建系列/分类", catMgrAddBtnRect.centerX(), catMgrAddBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 移除冗余说明副标题，直接紧凑展示卡片列表
        val contentTop = headerTop + headerH + 8f
        val dockTop = h - bottomBarH
        val contentBottom = innerPanel.bottom - 12f
        categoryContentArea.set(contentLeft, contentTop, contentRight, contentBottom)

        val cardH = 80f
        val cardGap = 8f
        val totalH = categories.size * (cardH + cardGap) + 16f
        maxCategoryScrollY = (totalH - categoryContentArea.height()).coerceAtLeast(0f)
        targetCategoryScrollY = targetCategoryScrollY.coerceIn(0f, maxCategoryScrollY)

        if (Math.abs(targetCategoryScrollY - categoryScrollY) > 0.5f) {
            categoryScrollY += (targetCategoryScrollY - categoryScrollY) * 0.35f
            postInvalidateOnAnimation()
        } else {
            categoryScrollY = targetCategoryScrollY
        }

        canvas.save()
        canvas.clipRect(categoryContentArea)

        var curY = categoryContentArea.top + 4f - categoryScrollY
        categoryItemRects.clear()
        catUpBtnRects.clear()
        catDownBtnRects.clear()
        catEditBtnRects.clear()
        catDeleteBtnRects.clear()
        catManageGamesBtnRects.clear()

        val isPendingDelExpired = (System.currentTimeMillis() - pendingDeleteTimestamp > 5000L)
        if (isPendingDelExpired) pendingDeleteCategoryId = null

        for ((idx, cat) in categories.withIndex()) {
            val r = RectF(contentLeft, curY, contentRight, curY + cardH)
            categoryItemRects.add(r)

            val isVisible = (r.bottom >= categoryContentArea.top - 10f && r.top <= categoryContentArea.bottom + 10f)
            if (isVisible) {
                val isAll = (cat.id == "all")
                draw3dsSculptedDockButton(
                    canvas = canvas,
                    r = r,
                    radius = 14f,
                    isPrimary = false,
                    accentOverride = if (isAll) Color.parseColor("#00A8E8") else cat.accentColor,
                    drawOutline = true
                )

                // 标题（4.5寸 960x720 屏幕清晰大字号 25f）
                textPaint.typeface = apolloMediumTypeface
                textPaint.textSize = 25f
                textPaint.color = currentTheme.textPrimaryColor
                textPaint.textAlign = Paint.Align.LEFT
                val titleX = r.left + 16f
                val titleY = r.centerY() - 7f
                canvas.drawText("${cat.badgeSymbol} ${cat.displayName}", titleX, titleY, textPaint)

                // 副标题（字号 18f，清晰深色字体，去除冗余的点击卡片提示）
                val tagStr = if (isAll) "✦ 根目录全部" else if (cat.isSeries) "🎮 IP系列" else "🕹️ 游戏类型"
                val gameCount = categoryGamesCountCache[cat.id] ?: 0
                val subStr = "$tagStr · 已收录 $gameCount 款游戏"
                textPaint.typeface = apolloRegularTypeface
                textPaint.textSize = 18f
                textPaint.color = Color.parseColor("#475569")
                canvas.drawText(subStr, titleX, r.centerY() + 19f, textPaint)

                if (!isAll) {
                    val btnH = 46f
                    val btnTop = r.centerY() - btnH / 2f
                    val btnBottom = r.centerY() + btnH / 2f
                    var rx = r.right - 12f

                    if (!cat.isBuiltIn) {
                        val isDelConfirm = (pendingDeleteCategoryId == cat.id)
                        val delW = if (isDelConfirm) 105f else 48f
                        val delRect = RectF(rx - delW, btnTop, rx, btnBottom)
                        catDeleteBtnRects.add(delRect)
                        draw3dsSculptedDockButton(canvas, delRect, 10f, isPrimary = isDelConfirm, accentOverride = Color.parseColor("#EF4444"), isSolid = isDelConfirm)
                        textPaint.typeface = apolloMediumTypeface
                        textPaint.textSize = if (isDelConfirm) 16f else 19f
                        textPaint.color = if (isDelConfirm) Color.WHITE else Color.parseColor("#EF4444")
                        textPaint.textAlign = Paint.Align.CENTER
                        canvas.drawText(if (isDelConfirm) "确认删除？" else "🗑", delRect.centerX(), delRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
                        rx -= (delW + 8f)
                    } else {
                        catDeleteBtnRects.add(RectF())
                    }

                    // ✏️ 重命名按钮
                    val editW = 48f
                    val editRect = RectF(rx - editW, btnTop, rx, btnBottom)
                    catEditBtnRects.add(editRect)
                    draw3dsSculptedDockButton(canvas, editRect, 10f, isPrimary = false, accentOverride = Color.parseColor("#F59E0B"))
                    textPaint.typeface = apolloMediumTypeface
                    textPaint.textSize = 19f
                    textPaint.color = Color.parseColor("#F59E0B")
                    textPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText("✏️", editRect.centerX(), editRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
                    rx -= (editW + 8f)

                    // ▼ 下移按钮
                    val downW = 52f
                    val downRect = RectF(rx - downW, btnTop, rx, btnBottom)
                    catDownBtnRects.add(downRect)
                    val canDown = (idx < categories.size - 1)
                    draw3dsSculptedDockButton(canvas, downRect, 10f, isPrimary = false, accentOverride = if (canDown) currentTheme.cursorMainColor else Color.GRAY)
                    textPaint.typeface = apolloMediumTypeface
                    textPaint.textSize = 20f
                    textPaint.color = if (canDown) currentTheme.textPrimaryColor else Color.GRAY
                    textPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText("▼", downRect.centerX(), downRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
                    rx -= (downW + 6f)

                    // ▲ 上移按钮
                    val upW = 52f
                    val upRect = RectF(rx - upW, btnTop, rx, btnBottom)
                    catUpBtnRects.add(upRect)
                    val canUp = (idx > 1) // 0 是 ALL
                    draw3dsSculptedDockButton(canvas, upRect, 10f, isPrimary = false, accentOverride = if (canUp) currentTheme.cursorMainColor else Color.GRAY)
                    textPaint.typeface = apolloMediumTypeface
                    textPaint.textSize = 20f
                    textPaint.color = if (canUp) currentTheme.textPrimaryColor else Color.GRAY
                    textPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText("▲", upRect.centerX(), upRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
                    rx -= (upW + 10f)

                    // 🎮 管理游戏 按钮 (实心高亮背景 + 纯白高对比文字)
                    val mgW = 140f
                    val mgRect = RectF(rx - mgW, btnTop, rx, btnBottom)
                    catManageGamesBtnRects.add(mgRect)
                    draw3dsSculptedDockButton(canvas, mgRect, 12f, isPrimary = true, accentOverride = cat.accentColor, isSolid = true)
                    textPaint.typeface = apolloMediumTypeface
                    textPaint.textSize = 19f
                    textPaint.color = Color.WHITE
                    textPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText("🎮 管理游戏", mgRect.centerX(), mgRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
                } else {
                    catUpBtnRects.add(RectF())
                    catDownBtnRects.add(RectF())
                    catEditBtnRects.add(RectF())
                    catDeleteBtnRects.add(RectF())
                    catManageGamesBtnRects.add(RectF())
                }
            } else {
                catUpBtnRects.add(RectF())
                catDownBtnRects.add(RectF())
                catEditBtnRects.add(RectF())
                catDeleteBtnRects.add(RectF())
                catManageGamesBtnRects.add(RectF())
            }

            curY += cardH + cardGap
        }

        canvas.restore()

        // 底部停靠按钮栏
        val dockBtnTop = dockTop + 7f
        val dockBtnBottom = h - 7f
        val btnRadius = (dockBtnBottom - dockBtnTop) * 0.48f
        val sidePad = 14f
        val gap = 12f

        val backW = (w - sidePad * 2f - gap) * 0.42f
        subPanelBackBtnRect.set(sidePad, dockBtnTop, sidePad + backW, dockBtnBottom)
        catMgrResetBtnRect.set(subPanelBackBtnRect.right + gap, dockBtnTop, w - sidePad, dockBtnBottom)

        draw3dsSculptedDockButton(canvas, subPanelBackBtnRect, btnRadius, isPrimary = false)
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 21f
        textPaint.color = currentTheme.textPrimaryColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("◀ 返回主屏 (B)", subPanelBackBtnRect.centerX(), subPanelBackBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        val isResetPending = pendingResetCategories && (System.currentTimeMillis() - pendingResetTimestamp < 5000L)
        draw3dsSculptedDockButton(canvas, catMgrResetBtnRect, btnRadius, isPrimary = isResetPending, accentOverride = if (isResetPending) Color.parseColor("#EF4444") else Color.parseColor("#F59E0B"), isSolid = isResetPending)
        textPaint.color = if (isResetPending) Color.WHITE else Color.parseColor("#D97706")
        textPaint.textSize = 21f
        canvas.drawText(if (isResetPending) "⚠️ 再次点击确认恢复默认" else "🔄 恢复默认排序", catMgrResetBtnRect.centerX(), catMgrResetBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
    }

    private fun drawCategoryGameManagerSubPanel(
        canvas: Canvas,
        innerPanel: RectF,
        w: Float,
        h: Float,
        bottomBarH: Float
    ) {
        val cat = managingCategory ?: return
        val headerH = 46f
        val headerTop = innerPanel.top + 6f
        val contentLeft = innerPanel.left + 14f
        val contentRight = innerPanel.right - 14f
        val contentW = (contentRight - contentLeft).coerceAtLeast(100f)

        // 1. 顶部标题栏 + 搜索框
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 24f
        textPaint.color = currentTheme.cursorMainColor
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("${cat.badgeSymbol}「${cat.displayName}」游戏管理", contentLeft, headerTop + 32f, textPaint)

        // 搜索框（右侧）
        val searchW = 320f.coerceAtMost(contentW * 0.48f)
        val searchH = 40f
        catGameSearchBoxRect.set(contentRight - searchW, headerTop + 4f, contentRight, headerTop + 4f + searchH)
        draw3dsSculptedDockButton(
            canvas = canvas,
            r = catGameSearchBoxRect,
            radius = searchH / 2f,
            isPrimary = false,
            accentOverride = if (categoryGameSearchQuery.isNotBlank()) currentTheme.cursorMainColor else currentTheme.slotBorderColor,
            drawOutline = true
        )

        val hasQuery = categoryGameSearchQuery.isNotBlank()
        if (hasQuery) {
            val clearW = 36f
            catGameSearchClearRect.set(catGameSearchBoxRect.right - clearW, catGameSearchBoxRect.top, catGameSearchBoxRect.right, catGameSearchBoxRect.bottom)
            textPaint.typeface = apolloMediumTypeface
            textPaint.textSize = 18f
            textPaint.color = Color.parseColor("#EF4444")
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("✖", catGameSearchClearRect.centerX(), catGameSearchClearRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

            textPaint.typeface = apolloMediumTypeface
            textPaint.textSize = 15.5f
            textPaint.color = currentTheme.textPrimaryColor
            textPaint.textAlign = Paint.Align.LEFT
            val queryText = "🔍 $categoryGameSearchQuery"
            canvas.drawText(queryText, catGameSearchBoxRect.left + 14f, catGameSearchBoxRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        } else {
            catGameSearchClearRect.set(0f, 0f, 0f, 0f)
            textPaint.typeface = apolloRegularTypeface
            textPaint.textSize = 15f
            textPaint.color = currentTheme.textSecondaryColor
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("🔍 点击快捷筛选游戏", catGameSearchBoxRect.centerX(), catGameSearchBoxRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        }

        // 2. 快捷搜索预设标签栏 (副屏快速点击，彻底无需弹窗！)
        val quickChipsTop = headerTop + headerH + 6f
        val quickChipH = 32f
        val quickKeywords = listOf("全部", "马里奥", "塞尔达", "宝可梦", "卡比", "火纹", "最终幻想", "3DS", "NDS", "GBA", "PSP", "PS1", "SFC", "FC")
        var chipX = contentLeft
        catGameQuickSearchChips.clear()

        for (kw in quickKeywords) {
            val chipW = (textPaint.measureText(kw) + 20f).coerceAtLeast(48f)
            if (chipX + chipW > contentRight) break
            val cr = RectF(chipX, quickChipsTop, chipX + chipW, quickChipsTop + quickChipH)
            catGameQuickSearchChips.add(Pair(cr, kw))

            val isSelected = (kw == "全部" && categoryGameSearchQuery.isBlank()) || (kw != "全部" && categoryGameSearchQuery.equals(kw, ignoreCase = true))
            draw3dsSculptedDockButton(
                canvas = canvas,
                r = cr,
                radius = quickChipH / 2f,
                isPrimary = isSelected,
                accentOverride = if (isSelected) currentTheme.cursorMainColor else currentTheme.slotBorderColor,
                drawOutline = !isSelected
            )
            textPaint.typeface = apolloMediumTypeface
            textPaint.textSize = 14f
            textPaint.color = if (isSelected) Color.WHITE else currentTheme.textPrimaryColor
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(kw, cr.centerX(), cr.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

            chipX += chipW + 6f
        }

        // 3. 标签栏（全部 / 已收录 / 未收录）
        val tabTop = quickChipsTop + quickChipH + 8f
        val tabH = 38f
        val tabGap = 8f
        val tabW = (contentW - tabGap * 2f) / 3f

        catGameTabAllRect.set(contentLeft, tabTop, contentLeft + tabW, tabTop + tabH)
        catGameTabIncludedRect.set(catGameTabAllRect.right + tabGap, tabTop, catGameTabAllRect.right + tabGap + tabW, tabTop + tabH)
        catGameTabNotIncludedRect.set(catGameTabIncludedRect.right + tabGap, tabTop, contentRight, tabTop + tabH)

        val totalIncluded = allCategoryGames.count { it.isIncluded }
        val totalNotIncluded = allCategoryGames.size - totalIncluded

        // Tab 全部
        draw3dsSculptedDockButton(
            canvas = canvas,
            r = catGameTabAllRect,
            radius = 10f,
            isPrimary = (categoryGameFilterTab == 0),
            accentOverride = if (categoryGameFilterTab == 0) currentTheme.cursorMainColor else currentTheme.slotBorderColor,
            drawOutline = (categoryGameFilterTab != 0)
        )
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 16f
        textPaint.color = if (categoryGameFilterTab == 0) Color.WHITE else currentTheme.textPrimaryColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("全部 (${allCategoryGames.size})", catGameTabAllRect.centerX(), catGameTabAllRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // Tab 已收录
        draw3dsSculptedDockButton(
            canvas = canvas,
            r = catGameTabIncludedRect,
            radius = 10f,
            isPrimary = (categoryGameFilterTab == 1),
            accentOverride = if (categoryGameFilterTab == 1) Color.parseColor("#10B981") else currentTheme.slotBorderColor,
            drawOutline = (categoryGameFilterTab != 1)
        )
        textPaint.color = if (categoryGameFilterTab == 1) Color.WHITE else currentTheme.textPrimaryColor
        canvas.drawText("✓ 已收录 ($totalIncluded)", catGameTabIncludedRect.centerX(), catGameTabIncludedRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // Tab 未收录
        draw3dsSculptedDockButton(
            canvas = canvas,
            r = catGameTabNotIncludedRect,
            radius = 10f,
            isPrimary = (categoryGameFilterTab == 2),
            accentOverride = if (categoryGameFilterTab == 2) Color.parseColor("#0284C7") else currentTheme.slotBorderColor,
            drawOutline = (categoryGameFilterTab != 2)
        )
        textPaint.color = if (categoryGameFilterTab == 2) Color.WHITE else currentTheme.textPrimaryColor
        canvas.drawText("➕ 未收录 ($totalNotIncluded)", catGameTabNotIncludedRect.centerX(), catGameTabNotIncludedRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 4. 游戏列表内容区域
        val contentTop = tabTop + tabH + 8f
        val dockTop = h - bottomBarH
        val contentBottom = innerPanel.bottom - 10f
        catGameContentArea.set(contentLeft, contentTop, contentRight, contentBottom)

        val cardH = 72f
        val cardGap = 8f
        val totalH = filteredCategoryGames.size * (cardH + cardGap) + 16f
        maxCatGameScrollY = (totalH - catGameContentArea.height()).coerceAtLeast(0f)
        targetCatGameScrollY = targetCatGameScrollY.coerceIn(0f, maxCatGameScrollY)

        if (Math.abs(targetCatGameScrollY - catGameScrollY) > 0.5f) {
            catGameScrollY += (targetCatGameScrollY - catGameScrollY) * 0.35f
            postInvalidateOnAnimation()
        } else {
            catGameScrollY = targetCatGameScrollY
        }

        canvas.save()
        canvas.clipRect(catGameContentArea)

        catGameItemRects.clear()
        catGameActionBtnRects.clear()

        if (filteredCategoryGames.isEmpty()) {
            val emptyCardH = 150f
            val emptyCardW = (contentW * 0.88f).coerceIn(300f, 600f)
            val emptyRect = RectF(
                innerPanel.centerX() - emptyCardW / 2f,
                contentTop + 20f,
                innerPanel.centerX() + emptyCardW / 2f,
                contentTop + 20f + emptyCardH
            )
            draw3dsSculptedDockButton(canvas, emptyRect, 16f, isPrimary = false, accentOverride = Color.parseColor("#F59E0B"))

            textPaint.typeface = apolloMediumTypeface
            textPaint.textSize = 21f
            textPaint.color = Color.parseColor("#F59E0B")
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("🔍 没有找到匹配的游戏", emptyRect.centerX(), emptyRect.top + emptyCardH * 0.40f, textPaint)

            textPaint.typeface = apolloRegularTypeface
            textPaint.textSize = 16f
            textPaint.color = currentTheme.textSecondaryColor
            canvas.drawText("可点击上方快捷芯片更换筛选，或切换「未收录/已收录」标签", emptyRect.centerX(), emptyRect.top + emptyCardH * 0.70f, textPaint)
        } else {
            var curY = catGameContentArea.top + 4f - catGameScrollY

            for (item in filteredCategoryGames) {
                val r = RectF(contentLeft, curY, contentRight, curY + cardH)
                catGameItemRects.add(r)

                val isVisible = (r.bottom >= catGameContentArea.top - 10f && r.top <= catGameContentArea.bottom + 10f)
                if (isVisible) {
                    val g = item.slot
                    draw3dsSculptedDockButton(
                        canvas = canvas,
                        r = r,
                        radius = 12f,
                        isPrimary = false,
                        accentOverride = if (item.isIncluded) Color.parseColor("#10B981") else currentTheme.slotBorderColor,
                        drawOutline = true
                    )

                    // 平台标签徽章
                    val plat = g.getEffectivePlatform()
                    val platW = 68f
                    val platH = 32f
                    val platRect = RectF(r.left + 14f, r.centerY() - platH / 2f, r.left + 14f + platW, r.centerY() + platH / 2f)
                    draw3dsSculptedDockButton(canvas, platRect, 8f, isPrimary = true, accentOverride = currentTheme.cursorMainColor)
                    textPaint.typeface = apolloMediumTypeface
                    textPaint.textSize = 14.5f
                    textPaint.color = Color.WHITE
                    textPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(plat, platRect.centerX(), platRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

                    // 右侧操作按钮：✓ 已收录 / ➕ 加入分类
                    val btnW = 135f
                    val btnH = 44f
                    val btnRect = RectF(r.right - 14f - btnW, r.centerY() - btnH / 2f, r.right - 14f, r.centerY() + btnH / 2f)
                    catGameActionBtnRects.add(btnRect)

                    if (item.isIncluded) {
                        draw3dsSculptedDockButton(canvas, btnRect, btnH / 2f, isPrimary = true, accentOverride = Color.parseColor("#10B981"))
                        textPaint.typeface = apolloMediumTypeface
                        textPaint.textSize = 16.5f
                        textPaint.color = Color.WHITE
                        textPaint.textAlign = Paint.Align.CENTER
                        canvas.drawText("✓ 已收录", btnRect.centerX(), btnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
                    } else {
                        draw3dsSculptedDockButton(canvas, btnRect, btnH / 2f, isPrimary = true, accentOverride = Color.parseColor("#0284C7"))
                        textPaint.typeface = apolloMediumTypeface
                        textPaint.textSize = 16.5f
                        textPaint.color = Color.WHITE
                        textPaint.textAlign = Paint.Align.CENTER
                        canvas.drawText("➕ 加入分类", btnRect.centerX(), btnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
                    }

                    // 游戏标题（大字体 21f）
                    val titleLeft = platRect.right + 14f
                    val titleRight = btnRect.left - 12f
                    textPaint.typeface = apolloMediumTypeface
                    textPaint.textSize = 21f
                    textPaint.color = currentTheme.textPrimaryColor
                    textPaint.textAlign = Paint.Align.LEFT
                    val titleY = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                    var dispTitle = g.title
                    if (textPaint.measureText(dispTitle) > (titleRight - titleLeft)) {
                        while (dispTitle.length > 4 && textPaint.measureText(dispTitle + "...") > (titleRight - titleLeft)) {
                            dispTitle = dispTitle.substring(0, dispTitle.length - 1)
                        }
                        dispTitle += "..."
                    }
                    canvas.drawText(dispTitle, titleLeft, titleY, textPaint)
                } else {
                    catGameActionBtnRects.add(RectF())
                }

                curY += cardH + cardGap
            }
        }

        canvas.restore()

        // 底部停靠按钮栏
        val dockBtnTop = dockTop + 7f
        val dockBtnBottom = h - 7f
        val btnRadius = (dockBtnBottom - dockBtnTop) * 0.48f
        val sidePad = 14f
        val gap = 12f

        val backW = 240f.coerceAtMost((w - sidePad * 2f - gap) * 0.45f)
        subPanelBackBtnRect.set(sidePad, dockBtnTop, sidePad + backW, dockBtnBottom)

        draw3dsSculptedDockButton(canvas, subPanelBackBtnRect, btnRadius, isPrimary = false)
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 17f
        textPaint.color = currentTheme.textPrimaryColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("◀ 返回分类管理 (B)", subPanelBackBtnRect.centerX(), subPanelBackBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 右侧状态提示
        val summaryRect = RectF(subPanelBackBtnRect.right + gap, dockBtnTop, w - sidePad, dockBtnBottom)
        draw3dsSculptedDockButton(canvas, summaryRect, btnRadius, isPrimary = false, accentOverride = currentTheme.slotBorderColor, drawOutline = true)
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 16.5f
        textPaint.color = currentTheme.cursorMainColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("${cat.displayName}: 当前已收录 $totalIncluded 款游戏", summaryRect.centerX(), summaryRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
    }

    private fun drawCategoryEditorSubPanel(
        canvas: Canvas,
        innerPanel: RectF,
        w: Float,
        h: Float,
        bottomBarH: Float
    ) {
        val headerH = 44f
        val headerTop = innerPanel.top + 6f
        val contentLeft = innerPanel.left + 14f
        val contentRight = innerPanel.right - 14f
        val contentW = (contentRight - contentLeft).coerceAtLeast(100f)

        // 1. 标题栏
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 24f
        textPaint.color = currentTheme.cursorMainColor
        textPaint.textAlign = Paint.Align.LEFT
        val titleText = if (editingCategory == null) "➕ 新建系列 / 游戏分类 (副屏自定)" else "✏️ 编辑分类 — ${editingCategory?.displayName}"
        canvas.drawText(titleText, contentLeft, headerTop + 30f, textPaint)

        // 2. 名称输入展示框
        val nameBoxTop = headerTop + headerH + 4f
        val nameBoxH = 46f
        catEditorNameBoxRect.set(contentLeft, nameBoxTop, contentRight, nameBoxTop + nameBoxH)
        draw3dsSculptedDockButton(
            canvas = canvas,
            r = catEditorNameBoxRect,
            radius = 12f,
            isPrimary = false,
            accentOverride = if (editingCategoryName.isNotBlank()) editingCategoryColor else currentTheme.slotBorderColor,
            drawOutline = true
        )
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 20f
        textPaint.color = if (editingCategoryName.isNotBlank()) currentTheme.textPrimaryColor else currentTheme.textSecondaryColor
        textPaint.textAlign = Paint.Align.LEFT
        val dispName = if (editingCategoryName.isNotBlank()) "${editingCategorySymbol} $editingCategoryName" else "👇 点击下方预设快速选择名称，或输入..."
        canvas.drawText(dispName, catEditorNameBoxRect.left + 16f, catEditorNameBoxRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 3. 属性选择：IP系列 vs 游戏类型
        val toggleTop = nameBoxTop + nameBoxH + 8f
        val toggleH = 38f
        val toggleW = (contentW - 12f) / 2f
        catEditorSeriesToggleRect.set(contentLeft, toggleTop, contentLeft + toggleW, toggleTop + toggleH)
        catEditorGenreToggleRect.set(catEditorSeriesToggleRect.right + 12f, toggleTop, contentRight, toggleTop + toggleH)

        draw3dsSculptedDockButton(
            canvas = canvas,
            r = catEditorSeriesToggleRect,
            radius = 10f,
            isPrimary = editingCategoryIsSeries,
            accentOverride = if (editingCategoryIsSeries) Color.parseColor("#E11D48") else currentTheme.slotBorderColor,
            drawOutline = !editingCategoryIsSeries
        )
        textPaint.textSize = 16f
        textPaint.color = if (editingCategoryIsSeries) Color.WHITE else currentTheme.textPrimaryColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("🎮 IP 精选系列 (如最终幻想/宝可梦)", catEditorSeriesToggleRect.centerX(), catEditorSeriesToggleRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        draw3dsSculptedDockButton(
            canvas = canvas,
            r = catEditorGenreToggleRect,
            radius = 10f,
            isPrimary = !editingCategoryIsSeries,
            accentOverride = if (!editingCategoryIsSeries) Color.parseColor("#0284C7") else currentTheme.slotBorderColor,
            drawOutline = editingCategoryIsSeries
        )
        textPaint.color = if (!editingCategoryIsSeries) Color.WHITE else currentTheme.textPrimaryColor
        canvas.drawText("🕹️ 通用游戏类型 (如RPG/动作/策略)", catEditorGenreToggleRect.centerX(), catEditorGenreToggleRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 4. 快捷预设名称选择 (Row 1: 系列, Row 2: 类型)
        catEditorPresetChips.clear()
        val presetsSeries = listOf("最终幻想", "怪物猎人", "生化危机", "恶魔城", "洛克人", "勇者斗恶龙", "仙剑奇侠", "合金装备", "大富翁", "拳皇", "街霸")
        val presetsGenre = listOf("动作冒险", "角色扮演", "策略战棋", "射击飞行", "竞速体育", "格斗对战", "休闲益智", "模拟经营")

        val pTop1 = toggleTop + toggleH + 10f
        val pH = 32f
        var px = contentLeft
        for (item in presetsSeries) {
            val pw = (textPaint.measureText(item) + 22f).coerceAtLeast(60f)
            if (px + pw > contentRight) break
            val pr = RectF(px, pTop1, px + pw, pTop1 + pH)
            catEditorPresetChips.add(Pair(pr, item))
            val isSel = (editingCategoryName == item)
            draw3dsSculptedDockButton(canvas, pr, pH / 2f, isPrimary = isSel, accentOverride = Color.parseColor("#E11D48"), drawOutline = !isSel)
            textPaint.textSize = 14f
            textPaint.color = if (isSel) Color.WHITE else currentTheme.textPrimaryColor
            canvas.drawText(item, pr.centerX(), pr.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
            px += pw + 8f
        }

        val pTop2 = pTop1 + pH + 6f
        px = contentLeft
        for (item in presetsGenre) {
            val pw = (textPaint.measureText(item) + 22f).coerceAtLeast(60f)
            if (px + pw > contentRight) break
            val pr = RectF(px, pTop2, px + pw, pTop2 + pH)
            catEditorPresetChips.add(Pair(pr, item))
            val isSel = (editingCategoryName == item)
            draw3dsSculptedDockButton(canvas, pr, pH / 2f, isPrimary = isSel, accentOverride = Color.parseColor("#0284C7"), drawOutline = !isSel)
            textPaint.textSize = 14f
            textPaint.color = if (isSel) Color.WHITE else currentTheme.textPrimaryColor
            canvas.drawText(item, pr.centerX(), pr.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
            px += pw + 8f
        }

        // 5. 徽标符号选择
        catEditorSymbolChips.clear()
        val symbols = listOf("🎮", "🕹️", "🗡️", "🍄", "⚡", "🌟", "🛡️", "🏎️", "🥊", "✈️", "☕", "⚙️")
        val symTop = pTop2 + pH + 10f
        val symSize = 36f
        var sx = contentLeft
        for (sym in symbols) {
            if (sx + symSize > contentRight) break
            val sr = RectF(sx, symTop, sx + symSize, symTop + symSize)
            catEditorSymbolChips.add(Pair(sr, sym))
            val isSel = (editingCategorySymbol == sym)
            draw3dsSculptedDockButton(canvas, sr, 8f, isPrimary = isSel, accentOverride = currentTheme.cursorMainColor, drawOutline = !isSel)
            textPaint.textSize = 18f
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(sym, sr.centerX(), sr.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
            sx += symSize + 8f
        }

        // 6. 主题色选择
        catEditorColorChips.clear()
        val colors = listOf(
            Color.parseColor("#E11D48"),
            Color.parseColor("#EF4444"),
            Color.parseColor("#F97316"),
            Color.parseColor("#F59E0B"),
            Color.parseColor("#10B981"),
            Color.parseColor("#0284C7"),
            Color.parseColor("#6366F1"),
            Color.parseColor("#8B5CF6")
        )
        val colTop = symTop + symSize + 10f
        val colSize = 36f
        var cx = contentLeft
        for (col in colors) {
            if (cx + colSize > contentRight) break
            val cr = RectF(cx, colTop, cx + colSize, colTop + colSize)
            catEditorColorChips.add(Pair(cr, col))
            val isSel = (editingCategoryColor == col)
            draw3dsSculptedDockButton(canvas, cr, 8f, isPrimary = true, accentOverride = col, drawOutline = isSel)
            if (isSel) {
                textPaint.textSize = 16f
                textPaint.color = Color.WHITE
                canvas.drawText("✔", cr.centerX(), cr.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
            }
            cx += colSize + 10f
        }

        // 7. 底部保存与返回操作栏
        val dockTop = h - bottomBarH
        val dockBtnTop = dockTop + 7f
        val dockBtnBottom = h - 7f
        val btnRadius = (dockBtnBottom - dockBtnTop) * 0.48f
        val sidePad = 14f
        val gap = 12f

        val backW = (w - sidePad * 2f - gap) * 0.42f
        subPanelBackBtnRect.set(sidePad, dockBtnTop, sidePad + backW, dockBtnBottom)
        catEditorSaveBtnRect.set(subPanelBackBtnRect.right + gap, dockBtnTop, w - sidePad, dockBtnBottom)

        draw3dsSculptedDockButton(canvas, subPanelBackBtnRect, btnRadius, isPrimary = false)
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 17f
        textPaint.color = currentTheme.textPrimaryColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("◀ 取消返回 (B)", subPanelBackBtnRect.centerX(), subPanelBackBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        val canSave = editingCategoryName.isNotBlank()
        draw3dsSculptedDockButton(canvas, catEditorSaveBtnRect, btnRadius, isPrimary = true, accentOverride = if (canSave) Color.parseColor("#10B981") else Color.GRAY)
        textPaint.color = Color.WHITE
        canvas.drawText("✔ 确认保存分类 (A)", catEditorSaveBtnRect.centerX(), catEditorSaveBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
    }

    private fun handleCategoryManagerTap(x: Float, y: Float) {
        if (subPanelBackBtnRect.contains(x, y)) {
            closeSettingsPanel()
            SoundEffectEngine.playSelectApplet()
            return
        }
        if (catMgrAddBtnRect.contains(x, y)) {
            SoundEffectEngine.playSelectApplet()
            openCategoryEditor(null)
            return
        }
        if (catMgrResetBtnRect.contains(x, y)) {
            if (pendingResetCategories && System.currentTimeMillis() - pendingResetTimestamp < 5000L) {
                pendingResetCategories = false
                listener?.onResetCategoriesRequested()
                categoryGamesCountCache.clear()
                listener?.onGetCategoryGameCounts()?.let {
                    categoryGamesCountCache.putAll(it)
                }
                SoundEffectEngine.playSelectApplet()
                invalidate()
            } else {
                pendingResetCategories = true
                pendingResetTimestamp = System.currentTimeMillis()
                SoundEffectEngine.playCursorMove()
                invalidate()
            }
            return
        }

        if (categoryContentArea.contains(x, y)) {
            for ((idx, r) in categoryItemRects.withIndex()) {
                val cat = categories.getOrNull(idx) ?: continue
                if (cat.id == "all") {
                    if (r.contains(x, y)) {
                        SoundEffectEngine.playSelectApplet()
                        listener?.onSelectCategory(cat)
                        closeSettingsPanel()
                        return
                    }
                    continue
                }

                val upR = catUpBtnRects.getOrNull(idx)
                if (upR != null && !upR.isEmpty && upR.contains(x, y)) {
                    SoundEffectEngine.playCursorMove()
                    listener?.onMoveCategoryUpRequested(idx)
                    return
                }

                val downR = catDownBtnRects.getOrNull(idx)
                if (downR != null && !downR.isEmpty && downR.contains(x, y)) {
                    SoundEffectEngine.playCursorMove()
                    listener?.onMoveCategoryDownRequested(idx)
                    return
                }

                val mgR = catManageGamesBtnRects.getOrNull(idx)
                if (mgR != null && !mgR.isEmpty && mgR.contains(x, y)) {
                    SoundEffectEngine.playSelectApplet()
                    openCategoryGameManager(cat)
                    return
                }

                val editR = catEditBtnRects.getOrNull(idx)
                if (editR != null && !editR.isEmpty && editR.contains(x, y)) {
                    SoundEffectEngine.playSelectApplet()
                    openCategoryEditor(cat)
                    return
                }

                val delR = catDeleteBtnRects.getOrNull(idx)
                if (delR != null && !delR.isEmpty && delR.contains(x, y)) {
                    if (pendingDeleteCategoryId == cat.id && System.currentTimeMillis() - pendingDeleteTimestamp < 5000L) {
                        pendingDeleteCategoryId = null
                        listener?.onDeleteCategory(cat.id)
                        categoryGamesCountCache.clear()
                        listener?.onGetCategoryGameCounts()?.let {
                            categoryGamesCountCache.putAll(it)
                        }
                        SoundEffectEngine.playSelectApplet()
                        invalidate()
                    } else {
                        pendingDeleteCategoryId = cat.id
                        pendingDeleteTimestamp = System.currentTimeMillis()
                        SoundEffectEngine.playCursorMove()
                        invalidate()
                    }
                    return
                }

                // 点击卡片直接切换为当前选中分类并返回主屏桌面！
                if (r.contains(x, y)) {
                    SoundEffectEngine.playSelectApplet()
                    listener?.onSelectCategory(cat)
                    closeSettingsPanel()
                    return
                }
            }
        }
    }

    private fun handleCategoryGameManagerTap(x: Float, y: Float) {
        val cat = managingCategory ?: return
        if (subPanelBackBtnRect.contains(x, y)) {
            settingsSubPanel = SettingsSubPanel.CATEGORY_MANAGER
            categoryGamesCountCache.clear()
            listener?.onGetCategoryGameCounts()?.let {
                categoryGamesCountCache.putAll(it)
            }
            SoundEffectEngine.playSelectApplet()
            invalidate()
            return
        }

        // 清除搜索按键 ✖
        if (!catGameSearchClearRect.isEmpty && catGameSearchClearRect.contains(x, y)) {
            categoryGameSearchQuery = ""
            updateFilteredCategoryGames()
            SoundEffectEngine.playCursorMove()
            invalidate()
            return
        }

        // 快捷搜索预设芯片点击 (直接在副屏过滤，零弹窗！)
        for ((cr, kw) in catGameQuickSearchChips) {
            if (cr.contains(x, y)) {
                categoryGameSearchQuery = if (kw == "全部") "" else kw
                updateFilteredCategoryGames()
                SoundEffectEngine.playSelectApplet()
                invalidate()
                return
            }
        }

        // Tab 全部
        if (catGameTabAllRect.contains(x, y)) {
            if (categoryGameFilterTab != 0) {
                categoryGameFilterTab = 0
                updateFilteredCategoryGames()
                SoundEffectEngine.playCursorMove()
                invalidate()
            }
            return
        }

        // Tab 已收录
        if (catGameTabIncludedRect.contains(x, y)) {
            if (categoryGameFilterTab != 1) {
                categoryGameFilterTab = 1
                updateFilteredCategoryGames()
                SoundEffectEngine.playCursorMove()
                invalidate()
            }
            return
        }

        // Tab 未收录
        if (catGameTabNotIncludedRect.contains(x, y)) {
            if (categoryGameFilterTab != 2) {
                categoryGameFilterTab = 2
                updateFilteredCategoryGames()
                SoundEffectEngine.playCursorMove()
                invalidate()
            }
            return
        }

        // 点击卡片上的操作按钮：切换已收录 / 未收录
        if (catGameContentArea.contains(x, y)) {
            for ((idx, btnRect) in catGameActionBtnRects.withIndex()) {
                if (!btnRect.isEmpty && btnRect.contains(x, y)) {
                    val item = filteredCategoryGames.getOrNull(idx) ?: continue
                    item.isIncluded = !item.isIncluded
                    listener?.onToggleGameCategory(cat.id, item.slot, item.isIncluded)
                    val cur = categoryGamesCountCache[cat.id] ?: allCategoryGames.count { it.isIncluded }
                    categoryGamesCountCache[cat.id] = if (item.isIncluded) cur + 1 else (cur - 1).coerceAtLeast(0)
                    SoundEffectEngine.playSelectApplet()
                    invalidate()
                    return
                }
            }
        }
    }

    private fun handleCategoryEditorTap(x: Float, y: Float) {
        if (subPanelBackBtnRect.contains(x, y)) {
            settingsSubPanel = SettingsSubPanel.CATEGORY_MANAGER
            SoundEffectEngine.playBackCancel()
            invalidate()
            return
        }

        // 属性切换
        if (catEditorSeriesToggleRect.contains(x, y)) {
            if (!editingCategoryIsSeries) {
                editingCategoryIsSeries = true
                editingCategorySymbol = "🎮"
                editingCategoryColor = Color.parseColor("#E11D48")
                SoundEffectEngine.playSelectApplet()
                invalidate()
            }
            return
        }
        if (catEditorGenreToggleRect.contains(x, y)) {
            if (editingCategoryIsSeries) {
                editingCategoryIsSeries = false
                editingCategorySymbol = "🕹️"
                editingCategoryColor = Color.parseColor("#0284C7")
                SoundEffectEngine.playSelectApplet()
                invalidate()
            }
            return
        }

        // 预设名称选择
        for ((pr, name) in catEditorPresetChips) {
            if (pr.contains(x, y)) {
                editingCategoryName = name
                SoundEffectEngine.playSelectApplet()
                invalidate()
                return
            }
        }

        // 图标符号选择
        for ((sr, sym) in catEditorSymbolChips) {
            if (sr.contains(x, y)) {
                editingCategorySymbol = sym
                SoundEffectEngine.playCursorMove()
                invalidate()
                return
            }
        }

        // 颜色选择
        for ((cr, col) in catEditorColorChips) {
            if (cr.contains(x, y)) {
                editingCategoryColor = col
                SoundEffectEngine.playCursorMove()
                invalidate()
                return
            }
        }

        // 保存分类
        if (catEditorSaveBtnRect.contains(x, y)) {
            if (editingCategoryName.isNotBlank()) {
                listener?.onSaveCategory(
                    editingCategory?.id,
                    editingCategoryName.trim(),
                    editingCategoryIsSeries,
                    editingCategorySymbol,
                    editingCategoryColor
                )
                openCategoryManager()
            }
            return
        }
    }

    private fun drawAdvancedSettingsSubPanel(
        canvas: Canvas,
        innerPanel: RectF,
        w: Float,
        h: Float,
        bottomBarH: Float
    ) {
        val headerH = (innerPanel.height() * 0.11f).coerceIn(40f, 48f)
        val headerTop = innerPanel.top + 8f

        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 26f
        textPaint.color = currentTheme.cursorMainColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("⚙️ 存储路径与系统高级设置", innerPanel.centerX(), headerTop + 33f, textPaint)

        val contentTop = headerTop + headerH + 8f
        val contentBottom = innerPanel.bottom - 12f
        val contentLeft = innerPanel.left + 16f
        val contentRight = innerPanel.right - 16f
        val contentW = (contentRight - contentLeft).coerceAtLeast(100f)
        val contentH = (contentBottom - contentTop).coerceAtLeast(100f)

        val cols = 2
        val rows = 4
        val gapX = 14f
        val gapY = 8f
        val cardW = (contentW - gapX) / cols
        val cardH = (contentH - gapY * (rows - 1)) / rows

        val subFilterTypeLabel = when (subscreenRetroFilterType) {
            "LCD_3X" -> "LCD 3X 经典"
            "DOT_MATRIX" -> "细密点阵"
            "SCANLINE" -> "微扫描线"
            "CRT_APERTURE" -> "街机光栅"
            else -> subscreenRetroFilterType
        }
        val mainFilterTypeLabel = when (mainScreenRetroFilterType) {
            "LCD_3X" -> "LCD 3X 经典"
            "DOT_MATRIX" -> "细密点阵"
            "SCANLINE" -> "微扫描线"
            "CRT_APERTURE" -> "街机光栅"
            else -> mainScreenRetroFilterType
        }
        val mainFilterActive = mainScreenRetroFilterAuto2D || mainScreenRetroFilterEnabled
        val mainFilterModeDesc = when {
            mainScreenRetroFilterEnabled -> "全局强制"
            mainScreenRetroFilterAuto2D -> "2D智能"
            else -> "关闭"
        }

        val titles = arrayOf(
            "📂 挂载游戏目录与启闭",
            "🏷️ 系列与游戏类别管理",
            if (hideGamesWithoutCover) "🖼️ 隐藏无封面游戏: 开启" else "🖼️ 隐藏无封面游戏: 关闭",
            if (mainFilterActive) "📺 3DS主屏复古滤镜: $mainFilterModeDesc" else "📺 3DS主屏复古滤镜: 关闭",
            if (subscreenRetroFilterEnabled) "⚡ 3DS副屏复古滤镜: 开启" else "⚡ 3DS副屏复古滤镜: 关闭",
            if (subscreenCompanionEnabled) "🎮 单屏游戏副屏面板: 开启" else "🎮 单屏游戏副屏面板: 关闭",
            if (isSplitPreviewEnabled) "📺 单屏合并预览：开启" else "📺 真双屏独立显示：开启",
            "🏠 设为掌机默认主桌面"
        )
        val descs = arrayOf(
            "已挂载 ${mediaLibraries.size} 个游戏目录 · 点击手动管理",
            "自定义分类名称 · 上下排序与增删游戏",
            if (hideGamesWithoutCover) "当前: 仅卡槽展示精选海报游戏" else "当前: 显示全部游戏 · 包含无封面ROM",
            if (mainFilterActive) "$mainFilterTypeLabel ($mainScreenRetroFilterOpacity%) · 点击配置" else "主屏原始拉伸 · 点击开启与配置",
            if (subscreenRetroFilterEnabled) "$subFilterTypeLabel ($subscreenRetroFilterOpacity%) · 点击配置" else "副屏原始拉伸 · 点击开启与配置",
            if (subscreenCompanionEnabled) "启动单屏游戏显示按键与攻略 · 点击关闭" else "启动单屏游戏时副屏原生黑屏 · 点击开启",
            if (isSplitPreviewEnabled) "单屏合并双屏画面 · 点击切换真双屏" else "上下屏独立分显 · 点击切换合并",
            "系统设置设为开机默认主屏幕 · 点击前往"
        )
        val accents = intArrayOf(
            Color.parseColor("#0284C7"),
            Color.parseColor("#F59E0B"),
            if (hideGamesWithoutCover) Color.parseColor("#10B981") else Color.parseColor("#64748B"),
            if (mainFilterActive) Color.parseColor("#0284C7") else Color.parseColor("#64748B"),
            if (subscreenRetroFilterEnabled) Color.parseColor("#10B981") else Color.parseColor("#64748B"),
            if (subscreenCompanionEnabled) Color.parseColor("#10B981") else Color.parseColor("#64748B"),
            Color.parseColor("#2B343D"),
            Color.parseColor("#E11D48")
        )

        for (idx in 0 until 8) {
            val col = idx % cols
            val row = idx / cols
            val cl = contentLeft + col * (cardW + gapX)
            val ct = contentTop + row * (cardH + gapY)
            val cr = advancedCardRects[idx]
            cr.set(cl, ct, cl + cardW, ct + cardH)

            val isFocused = (subPanelCursorIndex == idx)
            draw3dsSculptedDockButton(
                canvas = canvas,
                r = cr,
                radius = 16f,
                isPrimary = isFocused,
                accentOverride = accents[idx]
            )

            textPaint.typeface = apolloMediumTypeface
            textPaint.color = currentTheme.textPrimaryColor
            textPaint.textSize = (cardH * 0.32f).coerceIn(19f, 24f)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(titles[idx], cr.centerX(), cr.centerY() - cardH * 0.14f, textPaint)

            textPaint.typeface = apolloRegularTypeface
            textPaint.color = Color.parseColor("#475569")
            textPaint.textSize = (cardH * 0.22f).coerceIn(15.5f, 18.5f)
            canvas.drawText(descs[idx], cr.centerX(), cr.centerY() + cardH * 0.24f, textPaint)

            if (isFocused) {
                draw3dsCornerCursor(canvas, cr)
            }
        }

        drawSubPanelBottomBar(canvas, w, h, bottomBarH, "◀ 返回设置中心 (B)")
    }

    fun openRomDirectoryPicker() {
        pickerStorageRoots = listener?.onGetStorageRoots() ?: emptyList()
        if (pickerStorageRoots.isEmpty()) {
            pickerStorageRoots = listOfNotNull(android.os.Environment.getExternalStorageDirectory())
        }
        val tf = pickerStorageRoots.firstOrNull { 
            val p = it.absolutePath.lowercase()
            p.contains("storage/") && !p.contains("emulated/0")
        }
        pickerCurrentDir = tf ?: pickerStorageRoots.firstOrNull() ?: android.os.Environment.getExternalStorageDirectory()
        refreshPickerCurrentDir()
        settingsSubPanel = SettingsSubPanel.ROM_DIRECTORY_PICKER
        invalidate()
    }

    private fun refreshPickerCurrentDir() {
        val cur = pickerCurrentDir ?: return
        val files = try { cur.listFiles() ?: emptyArray() } catch (_: Exception) { emptyArray() }
        pickerRomCountInCurrentDir = files.count { it.isFile && validRomExtensions.contains(it.extension.lowercase()) }
        pickerSubDirs = files.filter { it.isDirectory && !it.name.startsWith(".") && !it.name.equals("Android", ignoreCase = true) }
            .sortedWith(compareBy { it.name.lowercase() })
        targetPickerScrollY = 0f
        pickerScrollY = 0f
    }

    private fun drawRomDirectoryPickerSubPanel(
        canvas: Canvas,
        innerPanel: RectF,
        w: Float,
        h: Float,
        bottomBarH: Float
    ) {
        val headerH = (innerPanel.height() * 0.11f).coerceIn(40f, 48f)
        val headerTop = innerPanel.top + 8f

        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 26f
        textPaint.color = currentTheme.cursorMainColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("📁 手动选择游戏文件夹挂载", innerPanel.centerX(), headerTop + 33f, textPaint)

        val contentLeft = innerPanel.left + 16f
        val contentRight = innerPanel.right - 16f
        val contentW = (contentRight - contentLeft).coerceAtLeast(100f)

        // 1. 存储介质选择选项卡行 (TF卡 / 内部存储) - 移除冗长副标题，大字选项卡
        val tabH = 40f
        val tabTop = headerTop + headerH + 8f
        pickerStorageTabRects.clear()
        if (pickerStorageRoots.isNotEmpty()) {
            val tabGap = 8f
            val tabW = ((contentW - tabGap * (pickerStorageRoots.size - 1)) / pickerStorageRoots.size.toFloat()).coerceIn(100f, 280f)
            for ((idx, root) in pickerStorageRoots.withIndex()) {
                val isTf = root.name.contains("-") || (root.absolutePath.contains("storage/") && !root.absolutePath.contains("emulated/0"))
                val label = if (isTf) "🎴 TF卡 (${root.name})" else "📱 内部存储"
                val isCur = pickerCurrentDir?.absolutePath?.startsWith(root.absolutePath) == true
                val tr = RectF(contentLeft + idx * (tabW + tabGap), tabTop, contentLeft + idx * (tabW + tabGap) + tabW, tabTop + tabH)
                pickerStorageTabRects.add(tr)

                draw3dsSculptedDockButton(
                    canvas = canvas,
                    r = tr,
                    radius = 10f,
                    isPrimary = isCur,
                    accentOverride = if (isCur) Color.parseColor("#0284C7") else Color.parseColor("#64748B"),
                    drawOutline = !isCur,
                    isSolid = isCur
                )
                textPaint.typeface = apolloMediumTypeface
                textPaint.textSize = 18f
                textPaint.color = if (isCur) Color.WHITE else currentTheme.textSecondaryColor
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(label, tr.centerX(), tr.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
            }
        }

        // 2. 当前浏览路径横幅与 ROM 统计
        val bannerTop = if (pickerStorageTabRects.isNotEmpty()) tabTop + tabH + 8f else tabTop
        val bannerH = 40f
        val bannerRect = RectF(contentLeft, bannerTop, contentRight, bannerTop + bannerH)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(35, 0, 0, 0)
        canvas.drawRoundRect(bannerRect, 8f, 8f, paint)

        val curPath = pickerCurrentDir?.absolutePath.orEmpty()
        val maxChars = (contentW / 12f).toInt().coerceIn(16, 65)
        val displayPath = if (curPath.length > maxChars) "..." + curPath.takeLast(maxChars - 3) else curPath

        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 17f
        textPaint.color = currentTheme.textPrimaryColor
        textPaint.textAlign = Paint.Align.LEFT
        val pathY = bannerRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("📂 当前: $displayPath", bannerRect.left + 14f, pathY, textPaint)

        if (pickerRomCountInCurrentDir > 0) {
            textPaint.typeface = apolloMediumTypeface
            textPaint.textSize = 17f
            textPaint.color = Color.parseColor("#10B981")
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("✨ 包含 $pickerRomCountInCurrentDir 款游戏 ROM", bannerRect.right - 14f, pathY, textPaint)
        }

        // 3. 文件夹列表滑动区域
        val listTop = bannerTop + bannerH + 8f
        val dockTop = h - bottomBarH
        val contentBottom = innerPanel.bottom - 10f
        pickerContentArea.set(contentLeft, listTop, contentRight, contentBottom)

        val isAtRoot = pickerStorageRoots.any { it.absolutePath == pickerCurrentDir?.absolutePath }
        val hasParent = pickerCurrentDir?.parentFile != null && !isAtRoot
        val itemH = 56f
        val itemGap = 6f
        val totalCount = (if (hasParent) 1 else 0) + pickerSubDirs.size
        val totalH = totalCount * (itemH + itemGap) + 10f
        maxPickerScrollY = (totalH - pickerContentArea.height()).coerceAtLeast(0f)
        targetPickerScrollY = targetPickerScrollY.coerceIn(0f, maxPickerScrollY)

        if (Math.abs(targetPickerScrollY - pickerScrollY) > 0.5f) {
            pickerScrollY += (targetPickerScrollY - pickerScrollY) * 0.35f
            postInvalidateOnAnimation()
        } else {
            pickerScrollY = targetPickerScrollY
        }

        canvas.save()
        canvas.clipRect(pickerContentArea)

        var curY = pickerContentArea.top + 4f - pickerScrollY
        pickerFolderRects.clear()

        if (hasParent) {
            pickerUpItemRect.set(contentLeft, curY, contentRight, curY + itemH)
            if (pickerUpItemRect.bottom >= pickerContentArea.top - 10f && pickerUpItemRect.top <= pickerContentArea.bottom + 10f) {
                draw3dsSculptedDockButton(canvas, pickerUpItemRect, 8f, isPrimary = false, accentOverride = Color.parseColor("#2563EB"))
                textPaint.typeface = apolloMediumTypeface
                textPaint.textSize = 18f
                textPaint.color = Color.parseColor("#2563EB")
                textPaint.textAlign = Paint.Align.LEFT
                val pName = pickerCurrentDir?.parentFile?.name?.ifBlank { "根目录" } ?: "根目录"
                canvas.drawText("⬆️ [返回上一级] .. ($pName)", pickerUpItemRect.left + 16f, pickerUpItemRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
            }
            curY += itemH + itemGap
        } else {
            pickerUpItemRect.setEmpty()
        }

        if (pickerSubDirs.isEmpty() && isAtRoot) {
            textPaint.typeface = apolloRegularTypeface
            textPaint.textSize = 16f
            textPaint.color = currentTheme.textSecondaryColor
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("（此根目录下无可见子文件夹）", pickerContentArea.centerX(), pickerContentArea.top + 50f, textPaint)
        }

        for (sub in pickerSubDirs) {
            val r = RectF(contentLeft, curY, contentRight, curY + itemH)
            pickerFolderRects.add(r)

            if (r.bottom >= pickerContentArea.top - 10f && r.top <= pickerContentArea.bottom + 10f) {
                val subRoms = sub.listFiles()?.count { it.isFile && validRomExtensions.contains(it.extension.lowercase()) } ?: 0
                val hasRoms = (subRoms > 0)
                draw3dsSculptedDockButton(
                    canvas = canvas,
                    r = r,
                    radius = 8f,
                    isPrimary = hasRoms,
                    accentOverride = if (hasRoms) Color.parseColor("#059669") else Color.parseColor("#64748B"),
                    drawOutline = !hasRoms
                )

                textPaint.typeface = if (hasRoms) apolloMediumTypeface else apolloRegularTypeface
                textPaint.textSize = 18f
                textPaint.color = if (hasRoms) Color.parseColor("#059669") else currentTheme.textPrimaryColor
                textPaint.textAlign = Paint.Align.LEFT
                val romTag = if (hasRoms) " (🎮 $subRoms 款游戏)" else ""
                val itemTitle = "📁 " + sub.name + romTag
                canvas.drawText(itemTitle, r.left + 16f, r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

                textPaint.typeface = apolloRegularTypeface
                textPaint.textSize = 16f
                textPaint.color = currentTheme.textSecondaryColor
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText("进入 ▶", r.right - 16f, r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
            }
            curY += itemH + itemGap
        }

        // 绘制滚动条
        if (maxPickerScrollY > 4f) {
            val scrollRatio = (pickerScrollY / maxPickerScrollY).coerceIn(0f, 1f)
            val barW = 5f
            val barTrackH = pickerContentArea.height() - 16f
            val thumbH = (barTrackH * (pickerContentArea.height() / totalH)).coerceIn(24f, barTrackH * 0.75f)
            val thumbY = pickerContentArea.top + 8f + scrollRatio * (barTrackH - thumbH)
            val thumbX = contentRight - barW - 3f

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(40, 0, 0, 0)
            canvas.drawRoundRect(RectF(thumbX, pickerContentArea.top + 8f, thumbX + barW, pickerContentArea.top + 8f + barTrackH), barW / 2f, barW / 2f, paint)
            paint.color = blendColor(currentTheme.cursorMainColor, Color.WHITE, 0.25f)
            canvas.drawRoundRect(RectF(thumbX, thumbY, thumbX + barW, thumbY + thumbH), barW / 2f, barW / 2f, paint)
        }

        canvas.restore()

        // 4. 底部操作底栏：[ ◀ 取消 (B) ] [ ✔ 确认挂载此文件夹 (A) ]
        val btnTop = dockTop + 7f
        val btnBottom = h - 7f
        val btnRadius = (btnBottom - btnTop) * 0.48f
        val sidePad = 14f
        val gap = 12f

        val availW = w - sidePad * 2f - gap
        val cancelW = (availW * 0.38f).coerceIn(160f, 260f)
        pickerCancelBtnRect.set(sidePad, btnTop, sidePad + cancelW, btnBottom)
        pickerConfirmBtnRect.set(pickerCancelBtnRect.right + gap, btnTop, w - sidePad, btnBottom)

        draw3dsSculptedDockButton(canvas, pickerCancelBtnRect, btnRadius, isPrimary = false)
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 20f
        textPaint.color = currentTheme.textPrimaryColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("◀ 返回目录管理 (B)", pickerCancelBtnRect.centerX(), pickerCancelBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        draw3dsSculptedDockButton(canvas, pickerConfirmBtnRect, btnRadius, isPrimary = true, accentOverride = Color.parseColor("#10B981"), isSolid = true)
        textPaint.color = Color.WHITE
        textPaint.textSize = 21f
        canvas.drawText("✔ 确认挂载当前文件夹", pickerConfirmBtnRect.centerX(), pickerConfirmBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
    }

    private fun handleRomDirectoryPickerTap(x: Float, y: Float) {
        if (pickerCancelBtnRect.contains(x, y)) {
            settingsSubPanel = SettingsSubPanel.MEDIA_LIBRARY_MANAGER
            SoundEffectEngine.playSelectApplet()
            invalidate()
            return
        }
        if (pickerConfirmBtnRect.contains(x, y)) {
            val path = pickerCurrentDir?.absolutePath.orEmpty()
            if (path.isNotBlank()) {
                SoundEffectEngine.playSelectApplet()
                listener?.onDirectoryChosenForMount(path)
            }
            return
        }
        for ((idx, tr) in pickerStorageTabRects.withIndex()) {
            if (tr.contains(x, y)) {
                val chosenRoot = pickerStorageRoots.getOrNull(idx) ?: continue
                pickerCurrentDir = chosenRoot
                refreshPickerCurrentDir()
                SoundEffectEngine.playCursorMove()
                invalidate()
                return
            }
        }
        if (pickerContentArea.contains(x, y)) {
            if (!pickerUpItemRect.isEmpty && pickerUpItemRect.contains(x, y)) {
                val parent = pickerCurrentDir?.parentFile
                val isAtRoot = pickerStorageRoots.any { it.absolutePath == pickerCurrentDir?.absolutePath }
                if (parent != null && !isAtRoot) {
                    pickerCurrentDir = parent
                    refreshPickerCurrentDir()
                    SoundEffectEngine.playCursorMove()
                    invalidate()
                }
                return
            }
            for ((idx, r) in pickerFolderRects.withIndex()) {
                if (r.contains(x, y)) {
                    val sub = pickerSubDirs.getOrNull(idx) ?: continue
                    pickerCurrentDir = sub
                    refreshPickerCurrentDir()
                    SoundEffectEngine.playCursorMove()
                    invalidate()
                    return
                }
            }
        }
    }

    private fun drawMediaLibraryManagerSubPanel(
        canvas: Canvas,
        innerPanel: RectF,
        w: Float,
        h: Float,
        bottomBarH: Float
    ) {
        val headerH = (innerPanel.height() * 0.11f).coerceIn(40f, 48f)
        val headerTop = innerPanel.top + 8f
        val contentLeft = innerPanel.left + 16f
        val contentRight = innerPanel.right - 16f
        val contentW = (contentRight - contentLeft).coerceAtLeast(100f)

        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 26f
        textPaint.color = currentTheme.cursorMainColor
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("📂 游戏目录管理 (手动挂载与启闭)", contentLeft, headerTop + 33f, textPaint)

        // 右上角：一键切换「隐藏无封面游戏」药丸按钮
        val pillW = 210f
        val pillH = 40f
        mediaLibHideMissingCoverBtnRect.set(contentRight - pillW, headerTop + (headerH - pillH) / 2f, contentRight, headerTop + (headerH + pillH) / 2f)
        draw3dsSculptedDockButton(
            canvas = canvas,
            r = mediaLibHideMissingCoverBtnRect,
            radius = pillH / 2f,
            isPrimary = hideGamesWithoutCover,
            accentOverride = if (hideGamesWithoutCover) Color.parseColor("#10B981") else Color.parseColor("#64748B"),
            drawOutline = !hideGamesWithoutCover,
            isSolid = hideGamesWithoutCover
        )
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 17f
        textPaint.color = if (hideGamesWithoutCover) Color.WHITE else currentTheme.textSecondaryColor
        textPaint.textAlign = Paint.Align.CENTER
        val hideLabel = if (hideGamesWithoutCover) "🖼️ 隐藏无封面: 开" else "🖼️ 隐藏无封面: 关"
        canvas.drawText(hideLabel, mediaLibHideMissingCoverBtnRect.centerX(), mediaLibHideMissingCoverBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 移除冗长说明副标题，直接留给列表更多纵向空间
        val contentTop = headerTop + headerH + 8f

        val dockTop = h - bottomBarH
        val btnTop = dockTop + 7f
        val btnBottom = h - 7f
        val btnRadius = (btnBottom - btnTop) * 0.48f
        val sidePad = 14f
        val gap = 10f

        if (mediaLibraries.isEmpty()) {
            val emptyCardH = 180f
            val emptyCardW = (contentW * 0.92f).coerceIn(320f, 640f)
            val emptyCardRect = RectF(
                innerPanel.centerX() - emptyCardW / 2f,
                contentTop + 16f,
                innerPanel.centerX() + emptyCardW / 2f,
                contentTop + 16f + emptyCardH
            )
            draw3dsSculptedDockButton(canvas, emptyCardRect, 18f, isPrimary = false, accentOverride = Color.parseColor("#F59E0B"))

            textPaint.typeface = apolloMediumTypeface
            textPaint.textSize = 25f
            textPaint.color = Color.parseColor("#D97706")
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("📂 暂未挂载游戏目录", emptyCardRect.centerX(), emptyCardRect.top + emptyCardH * 0.38f, textPaint)

            textPaint.typeface = apolloRegularTypeface
            textPaint.textSize = 18f
            textPaint.color = currentTheme.textSecondaryColor
            canvas.drawText("点击下方「➕ 添加目录」选择 TF 卡或内置存储挂载", emptyCardRect.centerX(), emptyCardRect.top + emptyCardH * 0.65f, textPaint)

            val availW = w - sidePad * 2f - gap
            val backW = (availW * 0.35f).coerceIn(160f, 240f)

            mediaLibPrevPageRect.setEmpty()
            mediaLibNextPageRect.setEmpty()
            mediaLibEnableAllBtnRect.setEmpty()
            mediaLibDisableAllBtnRect.setEmpty()
            mediaLibRemoveAllBtnRect.setEmpty()

            subPanelBackBtnRect.set(sidePad, btnTop, sidePad + backW, btnBottom)
            mediaLibAddBtnRect.set(subPanelBackBtnRect.right + gap, btnTop, w - sidePad, btnBottom)

            draw3dsSculptedDockButton(canvas, subPanelBackBtnRect, btnRadius, isPrimary = false)
            textPaint.typeface = apolloMediumTypeface
            textPaint.textSize = 20f
            textPaint.color = currentTheme.textPrimaryColor
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("◀ 返回 (B)", subPanelBackBtnRect.centerX(), subPanelBackBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

            draw3dsSculptedDockButton(canvas, mediaLibAddBtnRect, btnRadius, isPrimary = true, accentOverride = Color.parseColor("#2563EB"), isSolid = true)
            textPaint.color = Color.WHITE
            textPaint.textSize = 21f
            canvas.drawText("➕ 手动添加游戏目录", mediaLibAddBtnRect.centerX(), mediaLibAddBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
            return
        }

        val contentBottom = innerPanel.bottom - 10f
        mediaLibContentArea.set(contentLeft, contentTop, contentRight, contentBottom)

        val cardH = 82f
        val cardGap = 8f
        val totalH = mediaLibraries.size * (cardH + cardGap) + 12f
        maxMediaLibScrollY = (totalH - mediaLibContentArea.height()).coerceAtLeast(0f)
        targetMediaLibScrollY = targetMediaLibScrollY.coerceIn(0f, maxMediaLibScrollY)

        if (Math.abs(targetMediaLibScrollY - mediaLibScrollY) > 0.5f) {
            mediaLibScrollY += (targetMediaLibScrollY - mediaLibScrollY) * 0.35f
            postInvalidateOnAnimation()
        } else {
            mediaLibScrollY = targetMediaLibScrollY
        }

        canvas.save()
        canvas.clipRect(mediaLibContentArea)

        while (mediaLibCardRects.size < mediaLibraries.size) {
            mediaLibCardRects.add(RectF())
        }
        while (mediaLibRemoveItemRects.size < mediaLibraries.size) {
            mediaLibRemoveItemRects.add(RectF())
        }

        var curY = mediaLibContentArea.top + 6f - mediaLibScrollY
        for (idx in mediaLibraries.indices) {
            val cr = mediaLibCardRects[idx]
            cr.set(contentLeft, curY, contentRight, curY + cardH)
            val lib = mediaLibraries[idx]

            if (cr.bottom >= mediaLibContentArea.top - 10f && cr.top <= mediaLibContentArea.bottom + 10f) {
                val isEnabled = lib.isEnabled
                val isFocused = (subPanelCursorIndex == idx)
                val accent = if (isEnabled) Color.parseColor("#10B981") else Color.parseColor("#64748B")

                draw3dsSculptedDockButton(
                    canvas = canvas,
                    r = cr,
                    radius = 12f,
                    isPrimary = isEnabled || isFocused,
                    accentOverride = accent,
                    drawOutline = !isEnabled
                )

                // 右侧操作按钮：[ 移除 ] 和 [ 启闭 ]
                val removeW = 85f
                val removeH = 44f
                val removeRect = RectF(cr.right - removeW - 14f, cr.centerY() - removeH / 2f, cr.right - 14f, cr.centerY() + removeH / 2f)
                mediaLibRemoveItemRects[idx].set(removeRect)

                val switchW = 105f
                val switchH = 44f
                val switchRect = RectF(removeRect.left - 10f - switchW, cr.centerY() - switchH / 2f, removeRect.left - 10f, cr.centerY() + switchH / 2f)

                // 1) 左侧模拟器目录名称 (大字号 25f 醒目突出)
                textPaint.typeface = apolloMediumTypeface
                textPaint.textSize = 25f
                textPaint.color = if (isEnabled) currentTheme.textPrimaryColor else currentTheme.textSecondaryColor
                textPaint.textAlign = Paint.Align.LEFT
                val titleY = cr.top + cardH * 0.38f
                canvas.drawText("🎮 " + lib.name, cr.left + 16f, titleY, textPaint)

                // 2) 游戏数与存储介质 (清晰纯粹大字号 18f，不展示冗余raw路径)
                val isTf = lib.path.contains("storage/", ignoreCase = true) && !lib.path.contains("emulated/0", ignoreCase = true)
                val sourceTag = if (isTf) "TF卡" else "内置存储"
                val countText = if (lib.gameCount > 0) "🎮 ${lib.gameCount} 款游戏就绪 · " else ""
                textPaint.typeface = apolloRegularTypeface
                textPaint.textSize = 18f
                textPaint.color = Color.parseColor("#475569")
                canvas.drawText("$countText$sourceTag", cr.left + 16f, cr.top + cardH * 0.74f, textPaint)

                // 3) 右侧 3DS 触控开关药丸 (大字号 18f)
                paint.style = Paint.Style.FILL
                paint.color = if (isEnabled) Color.argb(235, 16, 185, 129) else Color.argb(130, 100, 116, 139)
                canvas.drawRoundRect(switchRect, switchH / 2f, switchH / 2f, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f
                paint.color = if (isEnabled) Color.argb(180, 255, 255, 255) else Color.argb(70, 255, 255, 255)
                canvas.drawRoundRect(switchRect, switchH / 2f, switchH / 2f, paint)

                textPaint.typeface = apolloMediumTypeface
                textPaint.textSize = 18f
                textPaint.color = Color.WHITE
                textPaint.textAlign = Paint.Align.CENTER
                val switchLabel = if (isEnabled) "✔ 已开启" else "○ 已关闭"
                canvas.drawText(switchLabel, switchRect.centerX(), switchRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

                // 4) 单项「移除」按钮 (字号 18f)
                val isPendingRemove = (pendingRemovePath == lib.path && System.currentTimeMillis() - pendingRemoveTimestamp < 4000L)
                val removeLabel = if (isPendingRemove) "⚠️ 确认?" else "🗑 移除"
                paint.style = Paint.Style.FILL
                paint.color = if (isPendingRemove) Color.argb(235, 220, 38, 38) else Color.argb(160, 239, 68, 68)
                canvas.drawRoundRect(removeRect, removeH / 2f, removeH / 2f, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f
                paint.color = Color.argb(160, 255, 255, 255)
                canvas.drawRoundRect(removeRect, removeH / 2f, removeH / 2f, paint)

                textPaint.typeface = apolloMediumTypeface
                textPaint.textSize = if (isPendingRemove) 15.5f else 18f
                textPaint.color = Color.WHITE
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(removeLabel, removeRect.centerX(), removeRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

                if (isFocused) {
                    draw3dsCornerCursor(canvas, cr)
                }
            }

            curY += cardH + cardGap
        }

        canvas.restore()

        // 底部原版四键操作底栏：[ ◀ 返回 (B) ] [ ➕ 添加目录 ] [ ✔ 全部开启 ] [ 🗑 全部移除 ]
        val availW = w - sidePad * 2f - gap * 3f
        val backW = availW * 0.23f
        val addW = availW * 0.27f
        val toggleW = availW * 0.24f
        val removeAllW = availW * 0.26f

        subPanelBackBtnRect.set(sidePad, btnTop, sidePad + backW, btnBottom)
        mediaLibAddBtnRect.set(subPanelBackBtnRect.right + gap, btnTop, subPanelBackBtnRect.right + gap + addW, btnBottom)
        mediaLibEnableAllBtnRect.set(mediaLibAddBtnRect.right + gap, btnTop, mediaLibAddBtnRect.right + gap + toggleW, btnBottom)
        mediaLibRemoveAllBtnRect.set(mediaLibEnableAllBtnRect.right + gap, btnTop, w - sidePad, btnBottom)
        mediaLibDisableAllBtnRect.setEmpty()
        mediaLibPrevPageRect.setEmpty()
        mediaLibNextPageRect.setEmpty()

        draw3dsSculptedDockButton(
            canvas = canvas,
            r = subPanelBackBtnRect,
            radius = btnRadius,
            isPrimary = false,
            accentOverride = currentTheme.cursorMainColor
        )
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 20f
        textPaint.color = currentTheme.textPrimaryColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("◀ 返回 (B)", subPanelBackBtnRect.centerX(), subPanelBackBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 添加目录按钮：实心深海蓝高对比度立体按键
        draw3dsSculptedDockButton(
            canvas = canvas,
            r = mediaLibAddBtnRect,
            radius = btnRadius,
            isPrimary = true,
            accentOverride = Color.parseColor("#2563EB"),
            isSolid = true
        )
        textPaint.textSize = 21f
        textPaint.color = Color.WHITE
        canvas.drawText("➕ 添加目录", mediaLibAddBtnRect.centerX(), mediaLibAddBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        val allAreEnabled = mediaLibraries.isNotEmpty() && mediaLibraries.all { it.isEnabled }
        val toggleLabel = if (allAreEnabled) "🚫 全部关闭" else "✔ 全部开启"
        val toggleColor = if (allAreEnabled) Color.parseColor("#64748B") else Color.parseColor("#10B981")
        draw3dsSculptedDockButton(
            canvas = canvas,
            r = mediaLibEnableAllBtnRect,
            radius = btnRadius,
            isPrimary = false,
            accentOverride = toggleColor
        )
        textPaint.textSize = 20f
        textPaint.color = toggleColor
        canvas.drawText(toggleLabel, mediaLibEnableAllBtnRect.centerX(), mediaLibEnableAllBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        val isPendingAll = (pendingRemoveAll && System.currentTimeMillis() - pendingRemoveAllTimestamp < 4000L)
        val allLabel = if (isPendingAll) "⚠️ 确认全部清空?" else "🗑 全部移除"
        val allColor = if (isPendingAll) Color.argb(255, 220, 38, 38) else Color.parseColor("#EF4444")
        draw3dsSculptedDockButton(
            canvas = canvas,
            r = mediaLibRemoveAllBtnRect,
            radius = btnRadius,
            isPrimary = isPendingAll,
            accentOverride = allColor,
            isSolid = isPendingAll
        )
        textPaint.textSize = if (isPendingAll) 17f else 20f
        textPaint.color = if (isPendingAll) Color.WHITE else Color.parseColor("#EF4444")
        canvas.drawText(allLabel, mediaLibRemoveAllBtnRect.centerX(), mediaLibRemoveAllBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
    }

    private fun handleEmulatorCardClicked(idx: Int, platform: PlatformType) {
        val emus = EmulatorLauncher.getAvailableEmulators(context, platform)
        val candidate = emus.getOrNull(idx) ?: return
        if (candidate.isInstalled) {
            if (platform == PlatformType.N3DS) {
                default3dsEmulatorPkg = candidate.packageName
                default3dsEmulatorLabel = candidate.name
                listener?.onSelect3dsEmulator(candidate.packageName)
            } else {
                defaultNdsEmulatorPkg = candidate.packageName
                defaultNdsEmulatorLabel = candidate.name
                listener?.onSelectNdsEmulator(candidate.packageName)
            }
            SoundEffectEngine.playSelectApplet()
            invalidate()
            android.widget.Toast.makeText(context, "已切换为：${candidate.name}", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(context, "${candidate.name} 尚未在设备安装，请先安装 APK 或选取已安装应用", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun drawEmulatorPickerSubPanel(
        canvas: Canvas,
        innerPanel: RectF,
        w: Float,
        h: Float,
        bottomBarH: Float,
        platform: PlatformType
    ) {
        val headerH = (innerPanel.height() * 0.11f).coerceIn(36f, 46f)
        val headerTop = innerPanel.top + 8f

        val platName = if (platform == PlatformType.N3DS) "3DS" else "NDS"
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = (headerH * 0.55f).coerceIn(18f, 25f)
        textPaint.color = currentTheme.cursorMainColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("🎮 $platName 核心模拟器配置", innerPanel.centerX(), headerTop + headerH * 0.55f, textPaint)

        val descY = headerTop + headerH + 6f
        val bannerH = (innerPanel.height() * 0.088f).coerceIn(28f, 38f)
        emuStatusBannerRect.set(innerPanel.left + 16f, descY, innerPanel.right - 16f, descY + bannerH)

        val inspection = pegasusInspectionProvider?.invoke(platName)
        val hasTxt = inspection?.exists == true
        val bannerBorder = if (hasTxt) Color.parseColor("#10B981") else Color.parseColor("#F59E0B")
        draw3dsSculptedDockButton(canvas, emuStatusBannerRect, 10f, isPrimary = hasTxt, accentOverride = bannerBorder)

        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = (bannerH * 0.44f).coerceIn(12.5f, 15f)
        textPaint.color = if (hasTxt) Color.WHITE else Color.parseColor("#FEF08A")
        textPaint.textAlign = Paint.Align.CENTER
        val bannerText = if (hasTxt) {
            "📖 介绍文件: ✅ 已在 TF卡/本地检测到 (${inspection?.totalEntries} 款游戏介绍 · 点击直接预览)"
        } else {
            "📖 介绍文件: ⚠️ 未检测到该模拟器的 metadata.pegasus.txt (点击排查与查看建议)"
        }
        canvas.drawText(bannerText, emuStatusBannerRect.centerX(), emuStatusBannerRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        val contentTop = emuStatusBannerRect.bottom + 8f
        val contentBottom = innerPanel.bottom - 12f
        val contentLeft = innerPanel.left + 16f
        val contentRight = innerPanel.right - 16f
        val contentW = (contentRight - contentLeft).coerceAtLeast(100f)
        val contentH = (contentBottom - contentTop).coerceAtLeast(100f)

        val emus = EmulatorLauncher.getAvailableEmulators(context, platform).take(8)
        val cols = 2
        val rows = 4
        val gapX = 14f
        val gapY = 8f
        val cardW = (contentW - gapX) / cols
        val cardH = (contentH - gapY * (rows - 1)) / rows

        val activePkg = if (platform == PlatformType.N3DS) default3dsEmulatorPkg else defaultNdsEmulatorPkg

        for (idx in emus.indices) {
            val col = idx % cols
            val row = idx / cols
            val cl = contentLeft + col * (cardW + gapX)
            val ct = contentTop + row * (cardH + gapY)
            val cr = emuCardRects[idx]
            cr.set(cl, ct, cl + cardW, ct + cardH)

            val item = emus[idx]
            val isSelected = item.packageName == activePkg || (activePkg.isBlank() && idx == 0 && item.isInstalled)
            val isInstalled = item.isInstalled

            val cardBorder = when {
                isSelected -> Color.parseColor("#F59E0B")
                isInstalled -> Color.parseColor("#10B981")
                else -> Color.parseColor("#475569")
            }

            draw3dsSculptedDockButton(
                canvas = canvas,
                r = cr,
                radius = 12f,
                isPrimary = isSelected,
                accentOverride = cardBorder
            )

            textPaint.typeface = apolloMediumTypeface
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = (cardH * 0.32f).coerceIn(16f, 21f)
            textPaint.color = if (isSelected) Color.parseColor("#F59E0B") else if (isInstalled) currentTheme.textPrimaryColor else currentTheme.textSecondaryColor
            canvas.drawText(item.name, cr.left + 12f, cr.top + cardH * 0.38f, textPaint)

            textPaint.typeface = apolloRegularTypeface
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.textSize = (cardH * 0.25f).coerceIn(13.5f, 17f)
            val statusStr = when {
                isSelected -> "★ 当前生效"
                isInstalled -> "● 已安装"
                else -> "○ 未安装"
            }
            textPaint.color = when {
                isSelected -> Color.parseColor("#F59E0B")
                isInstalled -> Color.parseColor("#10B981")
                else -> currentTheme.textSecondaryColor
            }
            canvas.drawText(statusStr, cr.right - 12f, cr.top + cardH * 0.38f, textPaint)

            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = (cardH * 0.21f).coerceIn(12f, 15f)
            textPaint.color = currentTheme.textSecondaryColor
            canvas.drawText(item.packageName, cr.left + 12f, cr.bottom - cardH * 0.20f, textPaint)
        }

        // 底部 3DS 原版三键核心操作底栏：[ ◀ 返回设置 (B) ] [ 🔍 选取其他已安装模拟器... ] [ 📖 介绍文件预览 ]
        val dockTop = h - bottomBarH
        val btnTop = dockTop + 7f
        val btnBottom = h - 7f
        val btnRadius = (btnBottom - btnTop) * 0.48f
        val sidePad = 14f
        val gap = 10f
        val availW = w - sidePad * 2f - gap * 2f
        val backW = availW * 0.28f
        val pickW = availW * 0.38f

        subPanelBackBtnRect.set(sidePad, btnTop, sidePad + backW, btnBottom)
        emuPickCustomAppRect.set(subPanelBackBtnRect.right + gap, btnTop, subPanelBackBtnRect.right + gap + pickW, btnBottom)
        emuPegasusPreviewBtnRect.set(emuPickCustomAppRect.right + gap, btnTop, w - sidePad, btnBottom)

        draw3dsSculptedDockButton(canvas, subPanelBackBtnRect, btnRadius, isPrimary = false)
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = (subPanelBackBtnRect.height() * 0.40f).coerceIn(13f, 16.5f)
        textPaint.color = currentTheme.textPrimaryColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("◀ 返回 (B)", subPanelBackBtnRect.centerX(), subPanelBackBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        draw3dsSculptedDockButton(canvas, emuPickCustomAppRect, btnRadius, isPrimary = true, accentOverride = currentTheme.cursorMainColor)
        textPaint.color = Color.WHITE
        canvas.drawText("🔍 选取其他模拟器", emuPickCustomAppRect.centerX(), emuPickCustomAppRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        draw3dsSculptedDockButton(canvas, emuPegasusPreviewBtnRect, btnRadius, isPrimary = true, accentOverride = Color.parseColor("#3B82F6"))
        textPaint.color = Color.WHITE
        canvas.drawText("📖 介绍文件预览", emuPegasusPreviewBtnRect.centerX(), emuPegasusPreviewBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
    }

    private fun drawStartupDiagnosticsSubPanel(
        canvas: Canvas,
        innerPanel: RectF,
        w: Float,
        h: Float,
        bottomBarH: Float
    ) {
        val headerH = (innerPanel.height() * 0.11f).coerceIn(36f, 46f)
        val headerTop = innerPanel.top + 8f

        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = (headerH * 0.52f).coerceIn(16f, 22f)
        textPaint.color = Color.parseColor("#F59E0B")
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("⚡ 3DS 桌面冷启动耗时诊断报告", innerPanel.centerX(), headerTop + headerH * 0.55f, textPaint)

        val descY = headerTop + headerH + 14f
        textPaint.typeface = apolloRegularTypeface
        textPaint.textSize = 13f
        textPaint.color = currentTheme.textSecondaryColor
        canvas.drawText("实机高精度毫秒遥测：全阶段异步解耦，已达秒开状态", innerPanel.centerX(), descY, textPaint)

        val contentTop = descY + 10f
        val contentBottom = innerPanel.bottom - 12f
        val contentLeft = innerPanel.left + 16f
        val contentRight = innerPanel.right - 16f
        val contentW = (contentRight - contentLeft).coerceAtLeast(100f)
        val contentH = (contentBottom - contentTop).coerceAtLeast(100f)

        val cols = 2
        val rows = 3
        val gapX = 14f
        val gapY = 10f
        val cardW = (contentW - gapX) / cols
        val cardH = (contentH - gapY * (rows - 1)) / rows

        val profiler = com.gameconsole.launcher3ds.telemetry.StartupProfiler
        val titles = arrayOf(
            "⚡ 冷启动总耗时",
            "💾 卡槽与目录缓存加载",
            "🎨 视图与音频引擎初始化",
            "🔄 双屏拓扑与上屏挂载",
            "🖼️ 屏幕首帧渲染",
            "🎮 3DS 核心模拟器"
        )
        val descs = arrayOf(
            "${profiler.totalColdBootMs} ms (秒开秒进)",
            "${profiler.slotsLoadMs} ms (SharedPreferences高速缓存)",
            "${profiler.viewsInitMs} ms (系统组件与音效)",
            "${profiler.layoutSetupMs} ms (Presentation分屏拓扑)",
            "${profiler.firstFrameDrawMs} ms (封面流式异步解码)",
            "$default3dsEmulatorLabel (包名: ${default3dsEmulatorPkg.ifBlank { "未指定" }})"
        )
        val accents = intArrayOf(
            Color.parseColor("#F59E0B"),
            Color.parseColor("#10B981"),
            Color.parseColor("#0284C7"),
            Color.parseColor("#8B5CF6"),
            Color.parseColor("#06B6D4"),
            Color.parseColor("#E60012")
        )

        for (idx in 0 until 6) {
            val col = idx % cols
            val row = idx / cols
            val cl = contentLeft + col * (cardW + gapX)
            val ct = contentTop + row * (cardH + gapY)
            val cr = diagCardRects[idx]
            cr.set(cl, ct, cl + cardW, ct + cardH)

            draw3dsSculptedDockButton(canvas, cr, 14f, isPrimary = false, accentOverride = accents[idx])

            textPaint.typeface = apolloMediumTypeface
            textPaint.color = accents[idx]
            textPaint.textSize = (cardH * 0.26f).coerceIn(13f, 18f)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(titles[idx], cr.centerX(), cr.centerY() - cardH * 0.12f, textPaint)

            textPaint.typeface = apolloRegularTypeface
            textPaint.color = currentTheme.textPrimaryColor
            textPaint.textSize = (cardH * 0.17f).coerceIn(10.5f, 14f)
            canvas.drawText(descs[idx], cr.centerX(), cr.centerY() + cardH * 0.22f, textPaint)
        }

        drawSubPanelBottomBar(canvas, w, h, bottomBarH, "◀ 返回模拟器配置 (B)")
    }


    private fun handleSubPanelTap(x: Float, y: Float) {
        if (subPanelBackBtnRect.contains(x, y)) {
            handleBackPressed()
            return
        }

        when (settingsSubPanel) {
            SettingsSubPanel.CATEGORY_MANAGER -> {
                handleCategoryManagerTap(x, y)
                return
            }
            SettingsSubPanel.CATEGORY_GAME_MANAGER -> {
                handleCategoryGameManagerTap(x, y)
                return
            }
            SettingsSubPanel.CATEGORY_EDITOR -> {
                handleCategoryEditorTap(x, y)
                return
            }
            SettingsSubPanel.AUTO_RANDOM_INTERVAL -> {
                if (stepMinus5BtnRect.contains(x, y)) {
                    val newVal = (autoRandomIntervalSec - 5).coerceIn(3, 600)
                    autoRandomIntervalSec = newVal
                    listener?.onAutoRandomIntervalChanged(newVal)
                    SoundEffectEngine.playCursorMove()
                    invalidate()
                    return
                }
                if (stepMinus1BtnRect.contains(x, y)) {
                    val newVal = (autoRandomIntervalSec - 1).coerceIn(3, 600)
                    autoRandomIntervalSec = newVal
                    listener?.onAutoRandomIntervalChanged(newVal)
                    SoundEffectEngine.playCursorMove()
                    invalidate()
                    return
                }
                if (stepPlus1BtnRect.contains(x, y)) {
                    val newVal = (autoRandomIntervalSec + 1).coerceIn(3, 600)
                    autoRandomIntervalSec = newVal
                    listener?.onAutoRandomIntervalChanged(newVal)
                    SoundEffectEngine.playCursorMove()
                    invalidate()
                    return
                }
                if (stepPlus5BtnRect.contains(x, y)) {
                    val newVal = (autoRandomIntervalSec + 5).coerceIn(3, 600)
                    autoRandomIntervalSec = newVal
                    listener?.onAutoRandomIntervalChanged(newVal)
                    SoundEffectEngine.playCursorMove()
                    invalidate()
                    return
                }
                val presets = intArrayOf(5, 10, 15, 20, 30, 45, 60, 120)
                for ((idx, sec) in presets.withIndex()) {
                    if (presetChipRects[idx].contains(x, y)) {
                        autoRandomIntervalSec = sec
                        listener?.onAutoRandomIntervalChanged(sec)
                        SoundEffectEngine.playCursorMove()
                        invalidate()
                        return
                    }
                }
                if (intervalPlayToggleBtnRect.contains(x, y)) {
                    isAutoRandomPlaying = !isAutoRandomPlaying
                    listener?.onAutoRandomToggled()
                    SoundEffectEngine.playSelectApplet()
                    invalidate()
                    return
                }
            }
            SettingsSubPanel.ADVANCED_SETTINGS -> {
                for (idx in 0 until 8) {
                    if (advancedCardRects[idx].contains(x, y)) {
                        subPanelCursorIndex = idx
                        triggerAdvancedCardAt(idx)
                        return
                    }
                }
            }
            SettingsSubPanel.ROM_DIRECTORY_PICKER -> {
                handleRomDirectoryPickerTap(x, y)
                return
            }
            SettingsSubPanel.MEDIA_LIBRARY_MANAGER -> {
                if (mediaLibHideMissingCoverBtnRect.contains(x, y)) {
                    SoundEffectEngine.playSelectApplet()
                    listener?.onToggleHideGamesWithoutCoverRequested()
                    invalidate()
                    return
                }
                if (subPanelBackBtnRect.contains(x, y)) {
                    pendingRemovePath = null
                    pendingRemoveAll = false
                    settingsSubPanel = mediaLibOriginSubPanel
                    SoundEffectEngine.playSelectApplet()
                    invalidate()
                    return
                }
                if (mediaLibAddBtnRect.contains(x, y)) {
                    pendingRemovePath = null
                    pendingRemoveAll = false
                    SoundEffectEngine.playSelectApplet()
                    openRomDirectoryPicker()
                    return
                }
                if (mediaLibraries.isEmpty()) {
                    return
                }
                if (mediaLibEnableAllBtnRect.contains(x, y)) {
                    pendingRemovePath = null
                    pendingRemoveAll = false
                    val allAreEnabled = mediaLibraries.isNotEmpty() && mediaLibraries.all { it.isEnabled }
                    if (allAreEnabled) {
                        listener?.onDisableAllMediaLibrariesRequested()
                    } else {
                        listener?.onEnableAllMediaLibrariesRequested()
                    }
                    SoundEffectEngine.playSelectApplet()
                    invalidate()
                    return
                }
                if (mediaLibRemoveAllBtnRect.contains(x, y)) {
                    val isPending = (pendingRemoveAll && System.currentTimeMillis() - pendingRemoveAllTimestamp < 4000L)
                    if (isPending) {
                        pendingRemoveAll = false
                        listener?.onRemoveAllMediaLibrariesRequested()
                        SoundEffectEngine.playSelectApplet()
                    } else {
                        pendingRemoveAll = true
                        pendingRemoveAllTimestamp = System.currentTimeMillis()
                        SoundEffectEngine.playCursorMove()
                    }
                    invalidate()
                    return
                }
                if (mediaLibContentArea.contains(x, y)) {
                    for (idx in mediaLibraries.indices) {
                        val cr = mediaLibCardRects.getOrNull(idx) ?: continue
                        if (cr.contains(x, y)) {
                            subPanelCursorIndex = idx
                            val lib = mediaLibraries[idx]
                            val remRect = mediaLibRemoveItemRects.getOrNull(idx)
                            if (remRect != null && remRect.contains(x, y)) {
                                val isPending = (pendingRemovePath == lib.path && System.currentTimeMillis() - pendingRemoveTimestamp < 4000L)
                                if (isPending) {
                                    pendingRemovePath = null
                                    listener?.onRemoveMediaLibraryRequested(lib.path)
                                    SoundEffectEngine.playSelectApplet()
                                } else {
                                    pendingRemovePath = lib.path
                                    pendingRemoveTimestamp = System.currentTimeMillis()
                                    SoundEffectEngine.playCursorMove()
                                }
                                invalidate()
                                return
                            }
                            pendingRemovePath = null
                            pendingRemoveAll = false
                            listener?.onMediaLibraryToggled(lib.path, !lib.isEnabled)
                            SoundEffectEngine.playSelectApplet()
                            invalidate()
                            return
                        }
                    }
                }
                pendingRemovePath = null
                pendingRemoveAll = false
            }
            SettingsSubPanel.EMULATOR_PICKER_3DS,
            SettingsSubPanel.EMULATOR_PICKER_NDS -> {
                val plat = if (settingsSubPanel == SettingsSubPanel.EMULATOR_PICKER_3DS) PlatformType.N3DS else PlatformType.NDS
                val platName = if (plat == PlatformType.N3DS) "3DS" else "NDS"
                if (emuStatusBannerRect.contains(x, y) || emuPegasusPreviewBtnRect.contains(x, y)) {
                    currentPegasusInspectPlatform = platName
                    targetPegasusInspectScrollY = 0f
                    settingsSubPanel = SettingsSubPanel.PEGASUS_METADATA_PREVIEW
                    SoundEffectEngine.playSelectApplet()
                    invalidate()
                    return
                }
                if (emuPickCustomAppRect.contains(x, y)) {
                    listener?.onPickCustomInstalledAppRequested(plat)
                    return
                }
                for (idx in 0 until 8) {
                    if (emuCardRects[idx].contains(x, y)) {
                        handleEmulatorCardClicked(idx, plat)
                        return
                    }
                }
            }
            SettingsSubPanel.PEGASUS_METADATA_PREVIEW -> {
                if (pegasusInspectPlat3dsRect.contains(x, y)) {
                    currentPegasusInspectPlatform = "3DS"
                    targetPegasusInspectScrollY = 0f
                    SoundEffectEngine.playCursorMove()
                    invalidate()
                    return
                }
                if (pegasusInspectPlatNdsRect.contains(x, y)) {
                    currentPegasusInspectPlatform = "NDS"
                    targetPegasusInspectScrollY = 0f
                    SoundEffectEngine.playCursorMove()
                    invalidate()
                    return
                }
                if (pegasusInspectModeToggleRect.contains(x, y) || pegasusInspectModeBottomRect.contains(x, y)) {
                    pegasusInspectRawTextMode = !pegasusInspectRawTextMode
                    targetPegasusInspectScrollY = 0f
                    SoundEffectEngine.playSelectApplet()
                    invalidate()
                    return
                }
                if (pegasusInspectRescanRect.contains(x, y)) {
                    listener?.onManualRomScanRequested()
                    SoundEffectEngine.playSelectApplet()
                    invalidate()
                    return
                }
            }
            SettingsSubPanel.SUB_SCREEN_FILTER_CONFIG -> {
                if (filterToggleSwitchRect.contains(x, y)) {
                    listener?.onSubscreenRetroFilterToggled()
                    return
                }
                val types = arrayOf("LCD_3X", "DOT_MATRIX", "SCANLINE", "CRT_APERTURE")
                for (i in 0 until 4) {
                    if (filterTypeRects[i].contains(x, y)) {
                        listener?.onSubscreenRetroFilterTypeSelected(types[i])
                        return
                    }
                }
                val opacities = intArrayOf(10, 20, 30, 40)
                for (i in 0 until 4) {
                    if (filterOpacityRects[i].contains(x, y)) {
                        listener?.onSubscreenRetroFilterOpacitySelected(opacities[i])
                        return
                    }
                }
            }
            SettingsSubPanel.MAIN_SCREEN_FILTER_CONFIG -> {
                if (mainFilterAuto2DSwitchRect.contains(x, y)) {
                    listener?.onMainScreenRetroFilterAuto2DToggled()
                    return
                }
                if (mainFilterGlobalSwitchRect.contains(x, y)) {
                    listener?.onMainScreenRetroFilterToggled()
                    return
                }
                val types = arrayOf("LCD_3X", "DOT_MATRIX", "SCANLINE", "CRT_APERTURE")
                for (i in 0 until 4) {
                    if (mainFilterTypeRects[i].contains(x, y)) {
                        listener?.onMainScreenRetroFilterTypeSelected(types[i])
                        return
                    }
                }
                val opacities = intArrayOf(10, 20, 30, 40)
                for (i in 0 until 4) {
                    if (mainFilterOpacityRects[i].contains(x, y)) {
                        listener?.onMainScreenRetroFilterOpacitySelected(opacities[i])
                        return
                    }
                }
            }
            SettingsSubPanel.STARTUP_DIAGNOSTICS -> {
                // back is handled above
            }
            SettingsSubPanel.ROM_SCAN_CONFIG -> {
                if (scanManualActionBtnRect.contains(x, y)) {
                    SoundEffectEngine.playSelectApplet()
                    listener?.onManualRomScanRequested()
                    return
                }
                val scopes = arrayOf(
                    ScanStorageScope.BOTH,
                    ScanStorageScope.INTERNAL_ONLY,
                    ScanStorageScope.SDCARD_ONLY
                )
                for (i in 0 until 3) {
                    if (scanScopeOptionRects[i].contains(x, y)) {
                        val chosen = scopes[i]
                        scanStorageScope = chosen
                        SoundEffectEngine.playSelectApplet()
                        listener?.onScanStorageScopeChanged(chosen)
                        invalidate()
                        return
                    }
                }
            }
            SettingsSubPanel.NONE -> {}
        }
    }

    private fun triggerAdvancedCardAt(idx: Int) {
        when (idx) {
            0 -> {
                mediaLibOriginSubPanel = SettingsSubPanel.ADVANCED_SETTINGS
                settingsSubPanel = SettingsSubPanel.MEDIA_LIBRARY_MANAGER
                mediaLibScrollY = 0f
                targetMediaLibScrollY = 0f
                subPanelCursorIndex = 0
                pendingRemovePath = null
                pendingRemoveAll = false
                SoundEffectEngine.playSelectApplet()
                listener?.onMediaLibraryManagerOpened()
                invalidate()
            }
            1 -> {
                SoundEffectEngine.playSelectApplet()
                openCategoryManager()
            }
            2 -> {
                SoundEffectEngine.playSelectApplet()
                listener?.onToggleHideGamesWithoutCoverRequested()
                invalidate()
            }
            3 -> {
                settingsSubPanel = SettingsSubPanel.MAIN_SCREEN_FILTER_CONFIG
                SoundEffectEngine.playSelectApplet()
                invalidate()
            }
            4 -> {
                settingsSubPanel = SettingsSubPanel.SUB_SCREEN_FILTER_CONFIG
                SoundEffectEngine.playSelectApplet()
                invalidate()
            }
            5 -> {
                subscreenCompanionEnabled = !subscreenCompanionEnabled
                SoundEffectEngine.playSelectApplet()
                listener?.onToggleSubscreenCompanionRequested()
                invalidate()
            }
            6 -> {
                SoundEffectEngine.playSelectApplet()
                isSplitPreviewEnabled = !isSplitPreviewEnabled
                listener?.onToggleSplitPreviewRequested()
                invalidate()
            }
            7 -> {
                SoundEffectEngine.playSelectApplet()
                listener?.onSetDefaultHomeRequested()
            }
        }
    }

    private fun triggerRomDirCardAt(idx: Int) {
        val dir = candidateRomDirs.getOrNull(idx) ?: return
        lastMountedMediaDir = dir
        SoundEffectEngine.playSelectApplet()
        listener?.onRomDirectoryPicked(dir)
        settingsSubPanel = SettingsSubPanel.ADVANCED_SETTINGS
        invalidate()
    }

    private fun drawBeveledButton(
        canvas: Canvas,
        r: RectF,
        fillColor: Int,
        borderColor: Int,
        radius: Float,
        isPrimary: Boolean = false
    ) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(48, 0, 0, 0)
        canvas.drawRoundRect(RectF(r.left, r.top + 3f, r.right, r.bottom + 3f), radius, radius, paint)

        paint.shader = LinearGradient(
            0f, r.top, 0f, r.bottom,
            Color.WHITE,
            fillColor,
            Shader.TileMode.CLAMP
        )
        paint.alpha = 255
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (isPrimary) 2.4f else 1.6f
        paint.color = borderColor
        paint.alpha = 255
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    private fun handleTap(x: Float, y: Float) {
        if (homeSettingsRect.contains(x, y)) {
            selectedTopIconIndex = -1
            SoundEffectEngine.playSelectApplet()
            openSettingsPanel(initialTab = 1)
            return
        }

        if (allAppsBtnRect.contains(x, y)) {
            selectedTopIconIndex = -1
            toggleAllAppsDrawer()
            return
        }

        if (videoAudioBtnRect.contains(x, y)) {
            selectedTopIconIndex = -1
            listener?.onVideoAudioToggleRequested()
            return
        }

        if (autoRandomBtnRect.contains(x, y)) {
            selectedTopIconIndex = -1
            isAutoRandomPlaying = !isAutoRandomPlaying
            listener?.onAutoRandomToggled()
            return
        }

        if (zoomOutRect.contains(x, y)) {
            selectedTopIconIndex = -1
            if (gridRows < 6) {
                gridRows += 1
                listener?.onZoomChanged(gridRows, zoomIn = false)
            }
            return
        }
        if (zoomInRect.contains(x, y)) {
            selectedTopIconIndex = -1
            if (gridRows > 1) {
                gridRows -= 1
                listener?.onZoomChanged(gridRows, zoomIn = true)
            }
            return
        }

        for (i in appletRects.indices) {
            if (appletRects[i].contains(x, y)) {
                selectedTopIconIndex = -1
                listener?.onAppletClicked(applets[i])
                return
            }
        }

        if (isAllAppsDrawerOpen) {
            handleAllAppsDrawerTap(x, y)
            return
        }

        // 若当前已展开原版 3DS 设置/滤镜面板，优先响应面板内的标签页、卡片与底部栏点击
        if (isSettingsPanelOpen) {
            if (settingsSubPanel != SettingsSubPanel.NONE) {
                handleSubPanelTap(x, y)
                return
            }
            for (t in 0..3) {
                if (settingsTabRects[t].contains(x, y)) {
                    settingsTabIndex = t
                    return
                }
            }
            if (settingsTabIndex == 2) {
                val allPlats = com.gameconsole.launcher3ds.model.PlatformCatalog.getAllPlatforms(platformGameCounts.keys + mediaLibraries.map { it.name })
                val totalP = ((allPlats.size + 7) / 8).coerceAtLeast(1)
                if (pagePrevRect.contains(x, y)) {
                    if (platformPage > 0) {
                        platformPage--
                        settingsCardCursorIndex = platformPage * 8
                        SoundEffectEngine.playCursorMove()
                        invalidate()
                    }
                    return
                }
                if (pageNextRect.contains(x, y)) {
                    if (platformPage < totalP - 1) {
                        platformPage++
                        settingsCardCursorIndex = platformPage * 8
                        SoundEffectEngine.playCursorMove()
                        invalidate()
                    }
                    return
                }
            }
            for ((idx, cardRect) in settingsCardRects) {
                if (cardRect.contains(x, y)) {
                    settingsCardCursorIndex = idx
                    triggerSettingsCardAt(idx)
                    return
                }
            }
            if (bottomLeftBtnRect.contains(x, y)) {
                handleSettingsActionLeft()
                return
            }
            if (bottomCenterBtnRect.contains(x, y)) {
                closeSettingsPanel()
                return
            }
            if (bottomRightBtnRect.contains(x, y)) {
                handleSettingsActionRight()
                return
            }
            return
        }

        if (activeFolderTitle != null && folderCloseBannerRect.contains(x, y)) {
            selectedTopIconIndex = -1
            listener?.onAppletClicked(SystemAppletType.NONE)
            return
        }

        val pageStep = width * 0.68f
        if (scrollOffsetX > 4f && pageLeftArrowRect.contains(x, y)) {
            targetScrollX = (targetScrollX - pageStep).coerceIn(0f, maxScrollX)
            invalidate()
            return
        }
        if (scrollOffsetX < maxScrollX - 4f && pageRightArrowRect.contains(x, y)) {
            targetScrollX = (targetScrollX + pageStep).coerceIn(0f, maxScrollX)
            invalidate()
            return
        }

        val curSlot = slots.getOrNull(selectedIndex)
        if (bottomLeftBtnRect.contains(x, y)) {
            if (movingSourceIndex >= 0) {
                movingSourceIndex = -1
            } else {
                selectedTopIconIndex = -1
                listener?.onCategoryStepRequested(-1)
            }
            return
        }
        if (bottomCenterBtnRect.contains(x, y)) {
            if (movingSourceIndex >= 0) {
                val from = movingSourceIndex
                movingSourceIndex = -1
                if (from != selectedIndex) {
                    listener?.onSlotsSwappedOrMoved(from, selectedIndex)
                }
            } else {
                selectedTopIconIndex = -1
                SoundEffectEngine.playSelectApplet()
                listener?.onCategoryPickerRequested()
            }
            return
        }
        if (bottomRightBtnRect.contains(x, y)) {
            if (movingSourceIndex >= 0 && curSlot != null) {
                listener?.onRightActionRequested(curSlot, selectedIndex)
            } else {
                selectedTopIconIndex = -1
                listener?.onCategoryStepRequested(1)
            }
            return
        }

        for ((index, slotRect) in slotScreenRects) {
            if (slotRect.contains(x, y)) {
                selectedTopIconIndex = -1
                val tappedSlot = slots.getOrNull(index) ?: return
                if (movingSourceIndex >= 0) {
                    val from = movingSourceIndex
                    movingSourceIndex = -1
                    selectedIndex = index
                    if (from != index) {
                        listener?.onSlotsSwappedOrMoved(from, index)
                    }
                    return
                }
                if (selectedIndex == index) {
                    listener?.onSlotOpenRequested(tappedSlot, index)
                } else {
                    selectedIndex = index
                    listener?.onSlotSelected(tappedSlot, index)
                }
                return
            }
        }
    }

    private fun handleAllAppsDrawerTap(x: Float, y: Float) {
        if (appsCloseBtnRect.contains(x, y)) {
            closeAllAppsDrawer()
            return
        }

        val h = height.toFloat()
        val topBarH = (h * 0.135f).coerceIn(56f, 118f)
        val bottomBarH = (h * 0.105f).coerceIn(58f, 96f)
        val panelTop = topBarH + 6f
        val panelBottom = h - bottomBarH - 6f
        val headerH = 64f
        val listTop = panelTop + 8f + headerH + 6f
        val listBottom = panelBottom - 12f
        val listLeft = 16f + 16f
        val listRight = width.toFloat() - 16f - 16f
        val listAvailW = listRight - listLeft

        if (x in listLeft..listRight && y in listTop..listBottom) {
            val cols = 2
            val colGap = 12f
            val rowGap = 10f
            val cardW = (listAvailW - colGap * (cols - 1)) / cols
            val cardH = 82f

            val relY = y - listTop + appsScrollY
            val row = (relY / (cardH + rowGap)).toInt()
            val relX = x - listLeft
            val col = if (relX in 0f..cardW) {
                0
            } else if (relX in (cardW + colGap)..(cardW * 2f + colGap)) {
                1
            } else {
                -1
            }

            if (col in 0..1 && row >= 0) {
                val appIdx = row * cols + col
                if (appIdx in allInstalledApps.indices) {
                    val app = allInstalledApps[appIdx]
                    val cardLeft = listLeft + col * (cardW + colGap)
                    val cardRight = cardLeft + cardW
                    val cardTop = listTop - appsScrollY + row * (cardH + rowGap)
                    val btnW = 68f
                    val btnH = 34f
                    val btnMainX = cardRight - 12f - btnW
                    val btnMainY = cardTop + (cardH - btnH) / 2f
                    val mainBtnRect = RectF(btnMainX, btnMainY, btnMainX + btnW, btnMainY + btnH)

                    if (mainBtnRect.contains(x, y)) {
                        // 在主屏启动
                        listener?.onLaunchInstalledAppRequested(app.packageName, app.activityName, launchOnSubscreen = false)
                    } else {
                        // 在副屏全屏启动
                        listener?.onLaunchInstalledAppRequested(app.packageName, app.activityName, launchOnSubscreen = true)
                    }
                }
            }
        }
    }

    private fun handleAllAppsDrawerLongPress(x: Float, y: Float) {
        val h = height.toFloat()
        val topBarH = (h * 0.135f).coerceIn(56f, 118f)
        val bottomBarH = (h * 0.105f).coerceIn(58f, 96f)
        val panelTop = topBarH + 6f
        val panelBottom = h - bottomBarH - 6f
        val headerH = 64f
        val listTop = panelTop + 8f + headerH + 6f
        val listBottom = panelBottom - 12f
        val listLeft = 16f + 16f
        val listRight = width.toFloat() - 16f - 16f
        val listAvailW = listRight - listLeft

        if (x in listLeft..listRight && y in listTop..listBottom) {
            val cols = 2
            val colGap = 12f
            val rowGap = 10f
            val cardW = (listAvailW - colGap * (cols - 1)) / cols
            val cardH = 82f

            val relY = y - listTop + appsScrollY
            val row = (relY / (cardH + rowGap)).toInt()
            val relX = x - listLeft
            val col = if (relX in 0f..cardW) 0 else if (relX in (cardW + colGap)..(cardW * 2f + colGap)) 1 else -1

            if (col in 0..1 && row >= 0) {
                val appIdx = row * cols + col
                if (appIdx in allInstalledApps.indices) {
                    val app = allInstalledApps[appIdx]
                    // 长按将应用快捷方式固定到 3DS 桌面网格
                    listener?.onAddAppToDesktopRequested(app)
                }
            }
        }
    }

    private fun handleLongPress(x: Float, y: Float) {
        if (isAllAppsDrawerOpen) {
            handleAllAppsDrawerLongPress(x, y)
            return
        }
        if (isSettingsPanelOpen) return
        for ((index, slotRect) in slotScreenRects) {
            if (slotRect.contains(x, y)) {
                val slot = slots.getOrNull(index) ?: return
                selectedTopIconIndex = -1
                selectedIndex = index
                listener?.onSlotSelected(slot, index)
                if (slot.type != PlatformType.EMPTY) {
                    listener?.onSlotManualOrConfigRequested(slot, index)
                }
                return
            }
        }
    }

    /**
     * 按 L2 / R2 键切换下屏顶部的 9 个功能图标（0=HOME设置, 1=游戏库与模拟器平台, 2=视频原声播放开关, 3=自动随机轮播, 4=游戏笔记, 5=桌面徽章, 6=滤镜·设置, 7=缩小网格, 8=放大网格）
     */
    fun stepTopBarIcon(delta: Int) {
        val totalTopIcons = 8
        selectedTopIconIndex = if (selectedTopIconIndex < 0) {
            if (delta >= 0) 0 else totalTopIcons - 1
        } else {
            (selectedTopIconIndex + delta + totalTopIcons) % totalTopIcons
        }
        val previewApplet = when (selectedTopIconIndex) {
            0 -> SystemAppletType.HOME_SETTINGS
            1 -> SystemAppletType.NONE
            2 -> SystemAppletType.NONE
            3 -> SystemAppletType.NONE
            4 -> applets[0]
            5 -> applets[1]
            else -> SystemAppletType.NONE
        }
        listener?.onTopIconFocused(selectedTopIconIndex, previewApplet)
        invalidate()
    }

    /**
     * 当焦点在顶栏图标上时按 A 键触发对应功能
     */
    fun activateFocusedTopIcon(): Boolean {
        if (selectedTopIconIndex !in 0..7) return false
        when (selectedTopIconIndex) {
            0 -> openSettingsPanel(initialTab = 1) // 方案 A：第 1 个图标负责 HOME 桌面、主题与个性化
            1 -> toggleAllAppsDrawer()             // 📱 全部已安装应用副屏抽屉
            2 -> listener?.onVideoAudioToggleRequested()
            3 -> {
                isAutoRandomPlaying = !isAutoRandomPlaying
                listener?.onAutoRandomToggled()
            }
            4 -> listener?.onAppletClicked(applets[0])
            5 -> listener?.onAppletClicked(applets[1])
            6 -> if (gridRows < 6) {
                gridRows += 1
                listener?.onZoomChanged(gridRows, zoomIn = false)
            }
            7 -> if (gridRows > 1) {
                gridRows -= 1
                listener?.onZoomChanged(gridRows, zoomIn = true)
            }
        }
        selectedTopIconIndex = -1
        invalidate()
        return true
    }

    fun moveCursorByDpad(dxCols: Int, dyRows: Int) {
        if (isGameDescriptionOpen) {
            if (dyRows != 0) {
                targetDescScrollY = (targetDescScrollY + dyRows * 90f).coerceIn(0f, maxDescScrollY)
                SoundEffectEngine.playCursorMove()
                invalidate()
                return
            }
            if (dxCols != 0) {
                if (dxCols > 0) selectNextGame() else selectPreviousGame()
                return
            }
            return
        }
        if (isSettingsPanelOpen) {
            if (settingsSubPanel == SettingsSubPanel.EMULATOR_PICKER_3DS || settingsSubPanel == SettingsSubPanel.EMULATOR_PICKER_NDS) {
                val cols = 2
                val rows = 4
                val col = subPanelCursorIndex % cols
                val row = subPanelCursorIndex / cols
                val newCol = (col + dxCols).coerceIn(0, cols - 1)
                val newRow = (row + dyRows).coerceIn(0, rows - 1)
                val newIdx = newRow * cols + newCol
                if (newIdx != subPanelCursorIndex) {
                    subPanelCursorIndex = newIdx
                    SoundEffectEngine.playCursorMove()
                    invalidate()
                }
                return
            }
            if (settingsSubPanel == SettingsSubPanel.EMULATOR_PICKER_3DS || settingsSubPanel == SettingsSubPanel.EMULATOR_PICKER_NDS) {
            val plat = if (settingsSubPanel == SettingsSubPanel.EMULATOR_PICKER_3DS) PlatformType.N3DS else PlatformType.NDS
            handleEmulatorCardClicked(subPanelCursorIndex, plat)
            return
        }
        if (settingsSubPanel == SettingsSubPanel.ADVANCED_SETTINGS) {
                val cols = 2
                val rows = 4
                val col = subPanelCursorIndex % cols
                val row = subPanelCursorIndex / cols
                val newCol = (col + dxCols).coerceIn(0, cols - 1)
                val newRow = (row + dyRows).coerceIn(0, rows - 1)
                val newIdx = newRow * cols + newCol
                if (newIdx != subPanelCursorIndex) {
                    subPanelCursorIndex = newIdx
                    SoundEffectEngine.playCursorMove()
                    invalidate()
                }
                return
            }
            if (settingsSubPanel == SettingsSubPanel.MEDIA_LIBRARY_MANAGER || settingsSubPanel == SettingsSubPanel.ROM_DIRECTORY_PICKER) {
                val maxIdx = (mediaLibraries.size - 1).coerceAtLeast(0)
                val newIdx = (subPanelCursorIndex + dyRows).coerceIn(0, maxIdx)
                if (newIdx != subPanelCursorIndex) {
                    subPanelCursorIndex = newIdx
                    val itemTop = newIdx * (68f + 8f)
                    val itemBottom = itemTop + 68f
                    val viewH = mediaLibContentArea.height().coerceAtLeast(100f)
                    if (itemTop < targetMediaLibScrollY) {
                        targetMediaLibScrollY = itemTop.coerceIn(0f, maxMediaLibScrollY)
                    } else if (itemBottom > targetMediaLibScrollY + viewH) {
                        targetMediaLibScrollY = (itemBottom - viewH).coerceIn(0f, maxMediaLibScrollY)
                    }
                    SoundEffectEngine.playCursorMove()
                    invalidate()
                }
                return
            }
            if (settingsSubPanel == SettingsSubPanel.AUTO_RANDOM_INTERVAL) {
                if (dxCols != 0) {
                    val delta = if (dxCols > 0) 5 else -5
                    val newVal = (autoRandomIntervalSec + delta).coerceIn(3, 600)
                    if (newVal != autoRandomIntervalSec) {
                        autoRandomIntervalSec = newVal
                        listener?.onAutoRandomIntervalChanged(newVal)
                        SoundEffectEngine.playCursorMove()
                        invalidate()
                    }
                }
                return
            }
            if (settingsSubPanel == SettingsSubPanel.ROM_SCAN_CONFIG) {
                if (dyRows != 0) {
                    val scopes = arrayOf(ScanStorageScope.BOTH, ScanStorageScope.INTERNAL_ONLY, ScanStorageScope.SDCARD_ONLY)
                    val curIdx = scopes.indexOf(scanStorageScope).coerceAtLeast(0)
                    val newIdx = (curIdx + dyRows).coerceIn(0, 2)
                    if (newIdx != curIdx) {
                        subPanelCursorIndex = newIdx
                        scanStorageScope = scopes[newIdx]
                        listener?.onScanStorageScopeChanged(scopes[newIdx])
                        SoundEffectEngine.playCursorMove()
                        invalidate()
                    }
                }
                return
            }

            if (settingsTabIndex == 2) {
                val allPlatforms = com.gameconsole.launcher3ds.model.PlatformCatalog.getAllPlatforms(platformGameCounts.keys + mediaLibraries.map { it.name })
                val totalPages = ((allPlatforms.size + 7) / 8).coerceAtLeast(1)
                val cols = 4
                val rows = 2
                val curPage = settingsCardCursorIndex / 8
                val localIdx = settingsCardCursorIndex % 8
                val localCol = localIdx % cols
                val localRow = localIdx / cols

                var newCol = localCol + dxCols
                var newRow = (localRow + dyRows).coerceIn(0, rows - 1)
                var newPage = curPage

                if (newCol > cols - 1) {
                    if (curPage < totalPages - 1) {
                        newPage = curPage + 1
                        newCol = 0
                        SoundEffectEngine.playCursorMove()
                    } else {
                        newCol = cols - 1
                    }
                } else if (newCol < 0) {
                    if (curPage > 0) {
                        newPage = curPage - 1
                        newCol = cols - 1
                        SoundEffectEngine.playCursorMove()
                    } else {
                        newCol = 0
                    }
                }

                val targetIdx = (newPage * 8 + newRow * cols + newCol).coerceIn(0, allPlatforms.size - 1)
                platformPage = targetIdx / 8
                settingsCardCursorIndex = targetIdx
                invalidate()
                return
            }

            val (cols, rows) = when (settingsTabIndex) {
                0 -> 4 to 2 // 8 滤镜
                1 -> 4 to 2 // 8 主题声音
                3 -> 2 to 3 // 6 游戏库管理
                else -> 4 to 2
            }
            val total = cols * rows
            val curCol = (settingsCardCursorIndex % cols).coerceIn(0, cols - 1)
            val curRow = (settingsCardCursorIndex / cols).coerceIn(0, rows - 1)
            val newCol = (curCol + dxCols).coerceIn(0, cols - 1)
            val newRow = (curRow + dyRows).coerceIn(0, rows - 1)
            val newIdx = (newRow * cols + newCol).coerceIn(0, total - 1)
            settingsCardCursorIndex = newIdx
            // 在「上屏视频滤镜」标签页移动方向键时，立即实时预览对应滤镜！
            if (settingsTabIndex == 0) {
                VideoFilterType.entries.getOrNull(newIdx)?.let { filter ->
                    currentVideoFilter = filter
                    listener?.onVideoFilterSelected(filter)
                }
            }
            invalidate()
            return
        }

        if (selectedTopIconIndex >= 0) {
            selectedTopIconIndex = -1
            listener?.onTopIconFocused(-1, SystemAppletType.NONE)
        }
        if (slots.isEmpty()) return
        val rows = gridRows.coerceIn(1, 6)
        val curCol = selectedIndex / rows
        val curRow = selectedIndex % rows
        val maxCol = (slots.size - 1) / rows

        val newCol = (curCol + dxCols).coerceIn(0, maxCol)
        val newRow = (curRow + dyRows).coerceIn(0, rows - 1)
        val newIdx = (newCol * rows + newRow).coerceIn(0, slots.size - 1)
        if (newIdx != selectedIndex) {
            selectedIndex = newIdx
            slots.getOrNull(newIdx)?.let { listener?.onSlotSelected(it, newIdx) }
        } else {
            invalidate()
        }
    }

    fun pageByShoulder(direction: Int) {
        if (isSettingsPanelOpen && (settingsSubPanel == SettingsSubPanel.MEDIA_LIBRARY_MANAGER || settingsSubPanel == SettingsSubPanel.ROM_DIRECTORY_PICKER)) {
            val scrollDelta = mediaLibContentArea.height().coerceAtLeast(150f) * 0.75f * direction
            targetMediaLibScrollY = (targetMediaLibScrollY + scrollDelta).coerceIn(0f, maxMediaLibScrollY)
            invalidate()
            return
        }
        if (isSettingsPanelOpen && settingsTabIndex == 2) {
            val allPlatforms = com.gameconsole.launcher3ds.model.PlatformCatalog.getAllPlatforms(platformGameCounts.keys + mediaLibraries.map { it.name })
            val totalPages = ((allPlatforms.size + 7) / 8).coerceAtLeast(1)
            val newPage = (platformPage + direction).coerceIn(0, totalPages - 1)
            if (newPage != platformPage) {
                platformPage = newPage
                settingsCardCursorIndex = newPage * 8
                SoundEffectEngine.playCursorMove()
                invalidate()
            }
            return
        }
        val pageStep = width * 0.68f
        targetScrollX = (targetScrollX + direction * pageStep).coerceIn(0f, maxScrollX)
        val rows = gridRows.coerceIn(1, 6)
        val jumpSlots = rows * 3 * direction
        val newIdx = (selectedIndex + jumpSlots).coerceIn(0, (slots.size - 1).coerceAtLeast(0))
        selectedIndex = newIdx
        slots.getOrNull(newIdx)?.let { listener?.onSlotSelected(it, newIdx) }
        invalidate()
    }

    private fun ensureSelectedVisible() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val topBarH = (h * 0.135f).coerceIn(56f, 118f)
        val bottomBarH = (h * 0.105f).coerceIn(58f, 96f)
        val availH = (h - topBarH - bottomBarH - 12f).coerceAtLeast(80f)
        val availW = (w - 84f).coerceAtLeast(100f)
        val rows = gridRows.coerceIn(1, 6)
        val padV = (availH * 0.055f).coerceIn(10f, 28f)
        val gap = (availH / rows * 0.105f).coerceIn(8f, 24f)
        val maxSingleCell = (availW * 0.44f).coerceAtLeast(180f)
        val cellSize = (((availH - padV * 2f - gap * (rows - 1)) / rows)).coerceIn(44f, maxSingleCell)

        val col = selectedIndex / rows
        val cellLeft = col * (cellSize + gap)
        val cellRight = cellLeft + cellSize
        val visibleW = (w - 96f).coerceAtLeast(100f)

        val cellCenter = cellLeft + cellSize / 2f
        val idealScroll = cellCenter - visibleW / 2f
        if (cellLeft < targetScrollX || cellRight > targetScrollX + visibleW) {
            targetScrollX = idealScroll.coerceIn(0f, maxScrollX)
        }
    }

    private fun getIconCacheKey(item: GridSlotItem): String {
        return "${item.id}_${item.boxArtPath}_${item.iconCachePath}_${item.assetIconName}_${item.packageName}"
    }

    private fun hasPossibleIcon(item: GridSlotItem): Boolean {
        return item.boxArtPath.isNotBlank() ||
            item.iconCachePath.isNotBlank() ||
            item.assetIconName.isNotBlank() ||
            (item.type == PlatformType.ANDROID_APP && item.packageName.isNotBlank())
    }

    /**
     * 提前预加载全部卡槽图标，进入分类或翻页时无感知在后台多线程解码完成，杜绝翻页时出现闪字或加载过渡延迟
     */
    private fun preloadIcons(items: List<GridSlotItem>) {
        if (items.isEmpty()) return
        // 仅优先异步加载前 24 个可见/邻近卡槽图标，杜绝向线程池堆积数千任务导致 CPU 饥饿
        val listCopy = items.take(24)
        for (item in listCopy) {
            if (item.type == PlatformType.EMPTY) continue
            val key = getIconCacheKey(item)
            if (iconCache.containsKey(key) || !pendingIconKeys.add(key)) continue
            if (!hasPossibleIcon(item)) {
                pendingIconKeys.remove(key)
                continue
            }

            iconExecutor.execute {
                try {
                    val bmp = decodeSlotBitmap(item)
                    if (bmp != null) {
                        iconCache[key] = bmp
                        postInvalidate()
                    }
                } finally {
                    pendingIconKeys.remove(key)
                }
            }
        }
    }

    fun predecodeSlotIconSync(item: GridSlotItem) {
        if (item.type == PlatformType.EMPTY) return
        val key = getIconCacheKey(item)
        if (iconCache.containsKey(key)) return
        val bmp = decodeSlotBitmap(item)
        if (bmp != null) {
            iconCache[key] = bmp
        }
    }

    /**
     * 加载卡槽图标：
     * 1. 优先从内存缓存中瞬时返回（0 延迟）；
     * 2. 若尚未缓存但存在封面/图标文件，则立即同步解码并写入缓存，直接渲染游戏真实图标，彻底杜绝先显示带背景色文字再切换成图标的过渡延迟！
     */
    private fun loadSlotIcon(item: GridSlotItem): Bitmap? {
        val key = getIconCacheKey(item)
        val cached = iconCache[key]
        if (cached != null) {
            return cached
        }
        if (!hasPossibleIcon(item)) {
            return null
        }
        if (pendingIconKeys.add(key)) {
            iconExecutor.execute {
                try {
                    val bmp = decodeSlotBitmap(item)
                    if (bmp != null) {
                        if (iconCache.size > 800) {
                            val keysToRemove = iconCache.keys.filter { !it.startsWith("3ds_art/") }.take(200)
                            keysToRemove.forEach { iconCache.remove(it) }
                        }
                        iconCache[key] = bmp
                        postInvalidate()
                    }
                } finally {
                    pendingIconKeys.remove(key)
                }
            }
        }
        return null
    }

    private fun decodeSlotBitmap(item: GridSlotItem): Bitmap? {
        var bmp: Bitmap? = null
        try {
            if (item.boxArtPath.isNotBlank() && File(item.boxArtPath).exists()) {
                bmp = decodeAndCropSquareCartridgeCover(item.boxArtPath)
            } else if (item.iconCachePath.isNotBlank() && File(item.iconCachePath).exists()) {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                bmp = BitmapFactory.decodeFile(item.iconCachePath, opts)
            } else if (item.assetIconName.isNotBlank()) {
                bmp = loadAssetIcon(item.assetIconName)
            } else if (item.type == PlatformType.ANDROID_APP && item.packageName.isNotBlank()) {
                try {
                    val d = context.packageManager.getApplicationIcon(item.packageName)
                    bmp = if (d is BitmapDrawable) {
                        d.bitmap
                    } else {
                        val created = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                        val c = Canvas(created)
                        d.setBounds(0, 0, 128, 128)
                        d.draw(c)
                        created
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return bmp
    }

    /**
     * 将外部 media/<游戏名>/boxFront.png (773x680) 智能裁切掉右侧 NINTENDO 3DS 白色竖条，生成饱满 1:1 正方形卡带封面图标
     */
    private fun decodeAndCropSquareCartridgeCover(filePath: String): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = 2
            }
            val raw = BitmapFactory.decodeFile(filePath, opts) ?: return null
            val rw = raw.width
            val rh = raw.height
            if (rw <= 0 || rh <= 0) return raw

            // 若宽高比接近 3DS 实体盒封面 (如 773x680)，裁掉右侧约 11.5% 的白色 NINTENDO 3DS 侧边条后再居中取 1:1 正方形
            val effectiveW = if (rw > rh * 1.04f) (rw * 0.885f).toInt().coerceAtLeast(1) else rw
            val side = minOf(effectiveW, rh)
            val cropX = ((effectiveW - side) / 2).coerceAtLeast(0)
            val cropY = ((rh - side) / 2).coerceAtLeast(0)

            val square = Bitmap.createBitmap(raw, cropX, cropY, side, side)
            val scaled = Bitmap.createScaledBitmap(square, 192, 192, true)
            if (square != raw && square != scaled) square.recycle()
            if (raw != scaled) raw.recycle()
            scaled
        } catch (_: Exception) {
            null
        }
    }

    private fun loadAssetIcon(assetPath: String): Bitmap? {
        val cached = iconCache[assetPath]
        if (cached != null) {
            return cached
        }
        val bmp = try {
            context.assets.open(assetPath).use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (_: Exception) {
            null
        }
        if (bmp != null) {
            iconCache[assetPath] = bmp
        }
        return bmp
    }

    private fun blendColor(c1: Int, c2: Int, ratio: Float): Int {
        val r = (Color.red(c1) * (1f - ratio) + Color.red(c2) * ratio).toInt().coerceIn(0, 255)
        val g = (Color.green(c1) * (1f - ratio) + Color.green(c2) * ratio).toInt().coerceIn(0, 255)
        val b = (Color.blue(c1) * (1f - ratio) + Color.blue(c2) * ratio).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun drawSubScreenFilterConfigSubPanel(
        canvas: Canvas,
        innerPanel: RectF,
        w: Float,
        h: Float,
        bottomBarH: Float
    ) {
        val headerH = (innerPanel.height() * 0.11f).coerceIn(40f, 48f)
        val headerTop = innerPanel.top + 8f

        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 26f
        textPaint.color = currentTheme.cursorMainColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("⚡ 3DS / NDS 副屏复古液晶滤镜", innerPanel.centerX(), headerTop + 33f, textPaint)

        val contentTop = headerTop + headerH + 8f
        val contentBottom = innerPanel.bottom - 12f
        val contentLeft = innerPanel.left + 16f
        val contentRight = innerPanel.right - 16f
        val contentW = (contentRight - contentLeft).coerceAtLeast(100f)
        val contentH = (contentBottom - contentTop).coerceAtLeast(100f)

        // 分割左右两栏：左侧 58% 设置控制卡片，右侧 42% 实时 3DS 副屏模拟预览窗
        val leftW = contentW * 0.58f
        val rightW = contentW - leftW - 14f
        val rightLeft = contentLeft + leftW + 14f

        // ===== 左侧控制卡片 (3 层垂直堆叠) =====
        val cardGap = 8f
        val toggleH = (contentH * 0.26f).coerceIn(48f, 64f)
        val presetH = (contentH * 0.45f).coerceIn(86f, 120f)
        val opacityH = contentH - toggleH - presetH - cardGap * 2f

        // 1. 滤镜总开关卡片 (去除冗长技术说明副标题，大字垂直居中)
        val toggleRect = RectF(contentLeft, contentTop, contentLeft + leftW, contentTop + toggleH)
        draw3dsSculptedDockButton(canvas, toggleRect, 14f, isPrimary = subscreenRetroFilterEnabled, accentOverride = if (subscreenRetroFilterEnabled) Color.parseColor("#10B981") else Color.parseColor("#64748B"))

        filterToggleSwitchRect.set(toggleRect.right - 145f, toggleRect.centerY() - 22f, toggleRect.right - 12f, toggleRect.centerY() + 22f)
        draw3dsSculptedDockButton(canvas, filterToggleSwitchRect, 10f, isPrimary = true, accentOverride = if (subscreenRetroFilterEnabled) Color.parseColor("#059669") else Color.parseColor("#475569"), isSolid = true)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 22f
        textPaint.color = currentTheme.textPrimaryColor
        canvas.drawText("🔘 副屏液晶滤镜总开关", toggleRect.left + 16f, toggleRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 19f
        textPaint.color = Color.WHITE
        val switchText = if (subscreenRetroFilterEnabled) "✔ 已开启" else "✖ 已关闭"
        canvas.drawText(switchText, filterToggleSwitchRect.centerX(), filterToggleSwitchRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 2. 滤镜风格预设卡片 (4 款预设，2x2 按钮网格)
        val presetTop = toggleRect.bottom + cardGap
        val presetRect = RectF(contentLeft, presetTop, contentLeft + leftW, presetTop + presetH)
        draw3dsSculptedDockButton(canvas, presetRect, 14f, isPrimary = false)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 18f
        textPaint.color = currentTheme.cursorMainColor
        canvas.drawText("📺 滤镜风格预设", presetRect.left + 14f, presetRect.top + 22f, textPaint)

        val btnGridTop = presetRect.top + 32f
        val btnGridH = presetRect.bottom - btnGridTop - 8f
        val btnGridW = presetRect.width() - 20f
        val btnGapX = 8f
        val btnGapY = 6f
        val btnW = (btnGridW - btnGapX) / 2f
        val btnH = (btnGridH - btnGapY) / 2f

        val presetTypes = arrayOf("LCD_3X", "DOT_MATRIX", "SCANLINE", "CRT_APERTURE")
        val presetNames = arrayOf("LCD 3X 经典", "细密点阵 (Dot)", "复古微扫描线", "街机光栅条纹")
        val presetColors = intArrayOf(
            Color.parseColor("#0284C7"),
            Color.parseColor("#10B981"),
            Color.parseColor("#8B5CF6"),
            Color.parseColor("#F59E0B")
        )

        for (i in 0 until 4) {
            val col = i % 2
            val row = i / 2
            val bl = presetRect.left + 10f + col * (btnW + btnGapX)
            val bt = btnGridTop + row * (btnH + btnGapY)
            val br = RectF(bl, bt, bl + btnW, bt + btnH)
            filterTypeRects[i].set(br)

            val isSelected = (subscreenRetroFilterType == presetTypes[i])
            draw3dsSculptedDockButton(canvas, br, 10f, isPrimary = isSelected, accentOverride = if (isSelected) presetColors[i] else Color.parseColor("#94A3B8"), drawOutline = !isSelected, isSolid = isSelected)

            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = if (isSelected) apolloMediumTypeface else apolloRegularTypeface
            textPaint.textSize = 18f
            textPaint.color = if (isSelected) Color.WHITE else currentTheme.textPrimaryColor
            val label = if (isSelected) "✔ ${presetNames[i]}" else presetNames[i]
            canvas.drawText(label, br.centerX(), br.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        }

        // 3. 滤镜浓度卡片 (1 行 4 档: 10%, 20%, 30%, 40%)
        val opacityTop = presetRect.bottom + cardGap
        val opacityRect = RectF(contentLeft, opacityTop, contentLeft + leftW, contentTop + contentH)
        draw3dsSculptedDockButton(canvas, opacityRect, 14f, isPrimary = false)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 18f
        textPaint.color = currentTheme.cursorMainColor
        canvas.drawText("💡 滤镜浓度 (不透明度)", opacityRect.left + 14f, opacityRect.top + 22f, textPaint)

        val opBtnTop = opacityRect.top + 30f
        val opBtnH = (opacityRect.bottom - opBtnTop - 8f).coerceAtLeast(26f)
        val opValues = intArrayOf(10, 20, 30, 40)
        val opLabels = arrayOf("10% 微弱", "20% 均衡★", "30% 鲜明", "40% 浓郁")
        val opBtnW = (opacityRect.width() - 20f - 8f * 3f) / 4f

        for (i in 0 until 4) {
            val obl = opacityRect.left + 10f + i * (opBtnW + 8f)
            val obr = RectF(obl, opBtnTop, obl + opBtnW, opBtnTop + opBtnH)
            filterOpacityRects[i].set(obr)

            val isSelected = (subscreenRetroFilterOpacity == opValues[i])
            draw3dsSculptedDockButton(canvas, obr, 8f, isPrimary = isSelected, accentOverride = if (isSelected) currentTheme.cursorMainColor else Color.parseColor("#94A3B8"), drawOutline = !isSelected, isSolid = isSelected)

            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = if (isSelected) apolloMediumTypeface else apolloRegularTypeface
            textPaint.textSize = 18f
            textPaint.color = if (isSelected) Color.WHITE else currentTheme.textPrimaryColor
            val label = if (isSelected) "✔ ${opLabels[i]}" else opLabels[i]
            canvas.drawText(label, obr.centerX(), obr.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        }

        // ===== 右侧：3DS 副屏实时模拟预览窗 =====
        filterPreviewBoxRect.set(rightLeft, contentTop, rightLeft + rightW, contentTop + contentH)
        draw3dsSculptedDockButton(canvas, filterPreviewBoxRect, 16f, isPrimary = false)

        // 预览窗标题
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 18f
        textPaint.color = currentTheme.cursorMainColor
        canvas.drawText("📱 3DS 副屏模拟实时效果", filterPreviewBoxRect.centerX(), filterPreviewBoxRect.top + 22f, textPaint)

        val previewScreenRect = RectF(
            filterPreviewBoxRect.left + 12f,
            filterPreviewBoxRect.top + 34f,
            filterPreviewBoxRect.right - 12f,
            filterPreviewBoxRect.bottom - 44f
        )

        // 绘制模拟 3DS 触控副屏底色与 UI 元件
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#1E293B")
        canvas.drawRoundRect(previewScreenRect, 10f, 10f, paint)

        // 模拟 3DS 触控网格线
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.argb(40, 255, 255, 255)
        var gridX = previewScreenRect.left + 30f
        while (gridX < previewScreenRect.right) {
            canvas.drawLine(gridX, previewScreenRect.top, gridX, previewScreenRect.bottom, paint)
            gridX += 30f
        }
        var gridY = previewScreenRect.top + 30f
        while (gridY < previewScreenRect.bottom) {
            canvas.drawLine(previewScreenRect.left, gridY, previewScreenRect.right, gridY, paint)
            gridY += 30f
        }

        // 模拟 3DS 经典触屏按钮 [ 背包 ] [ 地图 ] [ 技能 ] [ 触控区 ]
        val sampleBtnW = (previewScreenRect.width() - 30f) / 2f
        val sampleBtnH = (previewScreenRect.height() - 40f) / 2f
        val sampleLabels = arrayOf("🎒 道具背包 (BAG)", "🗺️ 世界地图 (MAP)", "⚔️ 招式指令 (SKILL)", "🕹️ 触控定位 (TOUCH)")
        val sampleColors = intArrayOf(
            Color.parseColor("#3B82F6"),
            Color.parseColor("#10B981"),
            Color.parseColor("#EF4444"),
            Color.parseColor("#F59E0B")
        )
        for (i in 0 until 4) {
            val sc = i % 2
            val sr = i / 2
            val sl = previewScreenRect.left + 10f + sc * (sampleBtnW + 10f)
            val st = previewScreenRect.top + 10f + sr * (sampleBtnH + 10f)
            val sRect = RectF(sl, st, sl + sampleBtnW, st + sampleBtnH)
            paint.style = Paint.Style.FILL
            paint.color = sampleColors[i]
            paint.alpha = 200
            canvas.drawRoundRect(sRect, 6f, 6f, paint)
            paint.alpha = 255

            textPaint.color = Color.WHITE
            textPaint.typeface = apolloMediumTypeface
            textPaint.textSize = 17f
            canvas.drawText(sampleLabels[i], sRect.centerX(), sRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        }

        // 叠加实际的滤镜 Shader 颗粒纹理！
        if (subscreenRetroFilterEnabled) {
            if (subscreenPreviewShader == null || lastPreviewType != subscreenRetroFilterType || lastPreviewOpacity != subscreenRetroFilterOpacity) {
                lastPreviewType = subscreenRetroFilterType
                lastPreviewOpacity = subscreenRetroFilterOpacity
                val filterBmp = createPreviewFilterBitmap(subscreenRetroFilterType)
                subscreenPreviewShader = BitmapShader(filterBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            }
            canvas.save()
            canvas.clipRect(previewScreenRect)
            paint.style = Paint.Style.FILL
            paint.shader = subscreenPreviewShader
            paint.alpha = (subscreenRetroFilterOpacity * 255 / 100).coerceIn(0, 255)
            canvas.drawRect(previewScreenRect, paint)
            paint.shader = null
            paint.alpha = 255
            canvas.restore()
        }

        // 底部绿色安全标签
        val badgeRect = RectF(filterPreviewBoxRect.left + 16f, filterPreviewBoxRect.bottom - 38f, filterPreviewBoxRect.right - 16f, filterPreviewBoxRect.bottom - 8f)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#065F46")
        canvas.drawRoundRect(badgeRect, 6f, 6f, paint)

        textPaint.color = Color.parseColor("#34D399")
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 16f
        canvas.drawText("✔ 触屏底层穿透 · 零延迟", badgeRect.centerX(), badgeRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        drawSubPanelBottomBar(canvas, w, h, bottomBarH, "◀ 返回高级设置 (B)")
    }

    private fun drawMainScreenFilterConfigSubPanel(
        canvas: Canvas,
        innerPanel: RectF,
        w: Float,
        h: Float,
        bottomBarH: Float
    ) {
        val headerH = (innerPanel.height() * 0.11f).coerceIn(40f, 48f)
        val headerTop = innerPanel.top + 8f

        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 26f
        textPaint.color = currentTheme.cursorMainColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("📺 3DS 上屏 (主屏) 复古液晶滤镜", innerPanel.centerX(), headerTop + 33f, textPaint)

        val contentTop = headerTop + headerH + 8f
        val contentBottom = innerPanel.bottom - 12f
        val contentLeft = innerPanel.left + 16f
        val contentRight = innerPanel.right - 16f
        val contentW = (contentRight - contentLeft).coerceAtLeast(100f)
        val contentH = (contentBottom - contentTop).coerceAtLeast(100f)

        // 分割左右两栏：左侧 58% 设置控制卡片，右侧 42% 实时 3DS 主屏模拟预览窗
        val leftW = contentW * 0.58f
        val rightW = contentW - leftW - 14f
        val rightLeft = contentLeft + leftW + 14f

        // ===== 左侧控制卡片 (3 层垂直堆叠) =====
        val cardGap = 8f
        val toggleH = (contentH * 0.34f).coerceIn(76f, 105f)
        val presetH = (contentH * 0.40f).coerceIn(86f, 120f)
        val opacityH = contentH - toggleH - presetH - cardGap * 2f

        // 1. 策略与总开关卡片 (双行：智能 2D 识别 + 全局强制总开关，去除多余发虚说明，大字高对比呈现)
        val toggleRect = RectF(contentLeft, contentTop, contentLeft + leftW, contentTop + toggleH)
        val isAnyActive = mainScreenRetroFilterAuto2D || mainScreenRetroFilterEnabled
        draw3dsSculptedDockButton(canvas, toggleRect, 14f, isPrimary = isAnyActive, accentOverride = if (isAnyActive) Color.parseColor("#0284C7") else Color.parseColor("#64748B"))

        val rowH = toggleH / 2f
        // Row 1: 智能 2D 识别 (推荐)
        mainFilterAuto2DSwitchRect.set(toggleRect.right - 140f, toggleRect.top + 7f, toggleRect.right - 10f, toggleRect.top + rowH - 7f)
        draw3dsSculptedDockButton(canvas, mainFilterAuto2DSwitchRect, 8f, isPrimary = true, accentOverride = if (mainScreenRetroFilterAuto2D) Color.parseColor("#059669") else Color.parseColor("#475569"), isSolid = true)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 20f
        textPaint.color = currentTheme.textPrimaryColor
        canvas.drawText("✨ 智能 2D 识别 (推荐)", toggleRect.left + 16f, toggleRect.top + rowH * 0.5f - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 18f
        textPaint.color = Color.WHITE
        val auto2dText = if (mainScreenRetroFilterAuto2D) "✔ 智能开启" else "✖ 智能关闭"
        canvas.drawText(auto2dText, mainFilterAuto2DSwitchRect.centerX(), mainFilterAuto2DSwitchRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // Row 2: 全局强制总开关
        val row2Top = toggleRect.top + rowH
        mainFilterGlobalSwitchRect.set(toggleRect.right - 140f, row2Top + 7f, toggleRect.right - 10f, toggleRect.bottom - 7f)
        draw3dsSculptedDockButton(canvas, mainFilterGlobalSwitchRect, 8f, isPrimary = true, accentOverride = if (mainScreenRetroFilterEnabled) Color.parseColor("#0284C7") else Color.parseColor("#475569"), isSolid = true)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 20f
        textPaint.color = currentTheme.textPrimaryColor
        canvas.drawText("🟢 全局强制开关", toggleRect.left + 16f, row2Top + rowH * 0.5f - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 18f
        textPaint.color = Color.WHITE
        val globalText = if (mainScreenRetroFilterEnabled) "✔ 全局开启" else "✖ 全局关闭"
        canvas.drawText(globalText, mainFilterGlobalSwitchRect.centerX(), mainFilterGlobalSwitchRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 2. 滤镜风格预设卡片 (4 款预设，2x2 按钮网格)
        val presetTop = toggleRect.bottom + cardGap
        val presetRect = RectF(contentLeft, presetTop, contentLeft + leftW, presetTop + presetH)
        draw3dsSculptedDockButton(canvas, presetRect, 14f, isPrimary = false)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 18f
        textPaint.color = currentTheme.cursorMainColor
        canvas.drawText("📺 主屏滤镜风格预设", presetRect.left + 14f, presetRect.top + 22f, textPaint)

        val btnGridTop = presetRect.top + 32f
        val btnGridH = presetRect.bottom - btnGridTop - 8f
        val btnGridW = presetRect.width() - 20f
        val btnGapX = 8f
        val btnGapY = 6f
        val btnW = (btnGridW - btnGapX) / 2f
        val btnH = (btnGridH - btnGapY) / 2f

        val presetTypes = arrayOf("LCD_3X", "DOT_MATRIX", "SCANLINE", "CRT_APERTURE")
        val presetNames = arrayOf("LCD 3X 经典", "细密点阵 (Dot)", "复古微扫描线", "街机光栅条纹")
        val presetColors = intArrayOf(
            Color.parseColor("#0284C7"),
            Color.parseColor("#10B981"),
            Color.parseColor("#8B5CF6"),
            Color.parseColor("#F59E0B")
        )

        for (i in 0 until 4) {
            val col = i % 2
            val row = i / 2
            val bl = presetRect.left + 10f + col * (btnW + btnGapX)
            val bt = btnGridTop + row * (btnH + btnGapY)
            val br = RectF(bl, bt, bl + btnW, bt + btnH)
            mainFilterTypeRects[i].set(br)

            val isSelected = (mainScreenRetroFilterType == presetTypes[i])
            draw3dsSculptedDockButton(canvas, br, 10f, isPrimary = isSelected, accentOverride = if (isSelected) presetColors[i] else Color.parseColor("#94A3B8"), drawOutline = !isSelected, isSolid = isSelected)

            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = if (isSelected) apolloMediumTypeface else apolloRegularTypeface
            textPaint.textSize = 18f
            textPaint.color = if (isSelected) Color.WHITE else currentTheme.textPrimaryColor
            val label = if (isSelected) "✔ ${presetNames[i]}" else presetNames[i]
            canvas.drawText(label, br.centerX(), br.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        }

        // 3. 滤镜浓度卡片 (1 行 4 档: 10%, 20%, 30%, 40%)
        val opacityTop = presetRect.bottom + cardGap
        val opacityRect = RectF(contentLeft, opacityTop, contentLeft + leftW, contentTop + contentH)
        draw3dsSculptedDockButton(canvas, opacityRect, 14f, isPrimary = false)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 18f
        textPaint.color = currentTheme.cursorMainColor
        canvas.drawText("💡 主屏滤镜浓度", opacityRect.left + 14f, opacityRect.top + 22f, textPaint)

        val opBtnTop = opacityRect.top + 30f
        val opBtnH = (opacityRect.bottom - opBtnTop - 8f).coerceAtLeast(26f)
        val opValues = intArrayOf(10, 20, 30, 40)
        val opLabels = arrayOf("10% 微弱", "20% 推荐★", "30% 鲜明", "40% 浓郁")
        val opBtnW = (opacityRect.width() - 20f - 8f * 3f) / 4f

        for (i in 0 until 4) {
            val obl = opacityRect.left + 10f + i * (opBtnW + 8f)
            val obr = RectF(obl, opBtnTop, obl + opBtnW, opBtnTop + opBtnH)
            mainFilterOpacityRects[i].set(obr)

            val isSelected = (mainScreenRetroFilterOpacity == opValues[i])
            draw3dsSculptedDockButton(canvas, obr, 8f, isPrimary = isSelected, accentOverride = if (isSelected) currentTheme.cursorMainColor else Color.parseColor("#94A3B8"), drawOutline = !isSelected, isSolid = isSelected)

            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = if (isSelected) apolloMediumTypeface else apolloRegularTypeface
            textPaint.textSize = 18f
            textPaint.color = if (isSelected) Color.WHITE else currentTheme.textPrimaryColor
            val label = if (isSelected) "✔ ${opLabels[i]}" else opLabels[i]
            canvas.drawText(label, obr.centerX(), obr.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        }

        // ===== 右侧：3DS 主屏实时模拟预览窗 =====
        filterPreviewBoxRect.set(rightLeft, contentTop, rightLeft + rightW, contentTop + contentH)
        draw3dsSculptedDockButton(canvas, filterPreviewBoxRect, 16f, isPrimary = false)

        // 预览窗标题
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 18f
        textPaint.color = currentTheme.cursorMainColor
        canvas.drawText("📺 3DS 主屏模拟实时效果", filterPreviewBoxRect.centerX(), filterPreviewBoxRect.top + 22f, textPaint)

        val previewScreenRect = RectF(
            filterPreviewBoxRect.left + 12f,
            filterPreviewBoxRect.top + 34f,
            filterPreviewBoxRect.right - 12f,
            filterPreviewBoxRect.bottom - 44f
        )

        // 模拟 3DS 经典上屏马里奥游戏世界
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#0284C7")
        canvas.drawRoundRect(previewScreenRect, 10f, 10f, paint)

        // 模拟草地地面
        val groundH = previewScreenRect.height() * 0.32f
        paint.color = Color.parseColor("#15803D")
        canvas.drawRect(previewScreenRect.left, previewScreenRect.bottom - groundH, previewScreenRect.right, previewScreenRect.bottom, paint)

        // 模拟问号方块与砖块
        val blockH = 26f
        val blockW = 26f
        val blockY = previewScreenRect.centerY() - 10f
        val blockStartX = previewScreenRect.centerX() - blockW * 1.5f - 6f
        val blockColors = intArrayOf(Color.parseColor("#EA580C"), Color.parseColor("#DC2626"), Color.parseColor("#EA580C"))
        val blockChars = arrayOf("?", "★", "?")
        for (b in 0 until 3) {
            val bx = blockStartX + b * (blockW + 6f)
            paint.color = blockColors[b]
            canvas.drawRoundRect(RectF(bx, blockY, bx + blockW, blockY + blockH), 4f, 4f, paint)
            textPaint.color = Color.WHITE
            textPaint.typeface = apolloMediumTypeface
            textPaint.textSize = 16f
            canvas.drawText(blockChars[b], bx + blockW / 2f, blockY + blockH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        }

        // 叠加主屏滤镜纹理
        val mainActive = mainScreenRetroFilterAuto2D || mainScreenRetroFilterEnabled
        if (mainActive) {
            if (mainPreviewShader == null || lastMainPreviewType != mainScreenRetroFilterType || lastMainPreviewOpacity != mainScreenRetroFilterOpacity) {
                lastMainPreviewType = mainScreenRetroFilterType
                lastMainPreviewOpacity = mainScreenRetroFilterOpacity
                val filterBmp = createPreviewFilterBitmap(mainScreenRetroFilterType)
                mainPreviewShader = BitmapShader(filterBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            }
            canvas.save()
            canvas.clipRect(previewScreenRect)
            paint.style = Paint.Style.FILL
            paint.shader = mainPreviewShader
            paint.alpha = (mainScreenRetroFilterOpacity * 255 / 100).coerceIn(0, 255)
            canvas.drawRect(previewScreenRect, paint)
            paint.shader = null
            paint.alpha = 255
            canvas.restore()
        }

        // 底部微液晶微层激活标签
        val badgeRect = RectF(filterPreviewBoxRect.left + 16f, filterPreviewBoxRect.bottom - 38f, filterPreviewBoxRect.right - 16f, filterPreviewBoxRect.bottom - 8f)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#065F46")
        canvas.drawRoundRect(badgeRect, 6f, 6f, paint)

        textPaint.color = Color.parseColor("#34D399")
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 16f
        canvas.drawText("✔ 2D像素点阵填充 · 消除发糊发虚", badgeRect.centerX(), badgeRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        drawSubPanelBottomBar(canvas, w, h, bottomBarH, "◀ 返回高级设置 (B)")
    }

    private fun drawRomScanConfigSubPanel(
        canvas: Canvas,
        innerPanel: RectF,
        w: Float,
        h: Float,
        bottomBarH: Float
    ) {
        val headerH = (innerPanel.height() * 0.11f).coerceIn(36f, 46f)
        val headerTop = innerPanel.top + 8f

        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = (headerH * 0.55f).coerceIn(18f, 25f)
        textPaint.color = currentTheme.cursorMainColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("💾 ROM 与媒体扫描存储设置", innerPanel.centerX(), headerTop + headerH * 0.55f, textPaint)

        val contentTop = headerTop + headerH + 6f
        val contentBottom = innerPanel.bottom - 10f
        val contentLeft = innerPanel.left + 16f
        val contentRight = innerPanel.right - 16f
        val contentW = (contentRight - contentLeft).coerceAtLeast(100f)
        val contentH = (contentBottom - contentTop).coerceAtLeast(100f)

        // 硬件状态条 (顶部轻量条目)
        val statusH = (contentH * 0.13f).coerceIn(32f, 42f)
        val statusRect = RectF(contentLeft, contentTop, contentRight, contentTop + statusH)
        paint.style = Paint.Style.FILL
        paint.color = blendColor(currentTheme.slotFillColor, Color.BLACK, 0.08f)
        canvas.drawRoundRect(statusRect, 10f, 10f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = blendColor(currentTheme.slotBorderColor, Color.WHITE, 0.2f)
        canvas.drawRoundRect(statusRect, 10f, 10f, paint)

        textPaint.typeface = apolloRegularTypeface
        textPaint.textSize = (statusH * 0.48f).coerceIn(14f, 18f)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = currentTheme.textSecondaryColor
        val tfDesc = if (detectedTfCardPath.isNotBlank()) "已挂载: $detectedTfCardPath" else "未检测到外置TF卡"
        val statusText = "📱 内部存储: /storage/emulated/0 (就绪)  |  🎴 外置TF卡: $tfDesc"
        canvas.drawText(statusText, statusRect.left + 14f, statusRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 三个选项开关卡片 (垂直分布)
        val cardsTop = statusRect.bottom + 8f
        val cardsH = contentBottom - cardsTop
        val gapY = 8f
        val cardH = (cardsH - gapY * 2f) / 3f

        val scopes = arrayOf(
            ScanStorageScope.BOTH,
            ScanStorageScope.INTERNAL_ONLY,
            ScanStorageScope.SDCARD_ONLY
        )
        val titles = arrayOf(
            "💾 两者同时扫描 (推荐)",
            "📱 仅扫描内置存储",
            "🎴 仅扫描外置 TF 卡"
        )
        val descs = arrayOf(
            "全面检索掌机内置存储与外置TF卡下的 ROMs 与天马G Media包",
            "仅扫描掌机内部闪存存储空间 (/storage/emulated/0)，跳过外置TF卡",
            if (detectedTfCardPath.isNotBlank()) "仅扫描外置TF卡扩展存储 ($detectedTfCardPath)" else "⚠️ 仅扫描外置TF卡 (当前未检测到TF卡挂载)"
        )
        val accents = intArrayOf(
            Color.parseColor("#0284C7"),
            if (hideGamesWithoutCover) Color.parseColor("#10B981") else Color.parseColor("#64748B"),
            Color.parseColor("#F59E0B")
        )

        for (i in 0 until 3) {
            val scope = scopes[i]
            val isSelected = (scanStorageScope == scope)
            val isFocused = (subPanelCursorIndex == i)
            val ct = cardsTop + i * (cardH + gapY)
            val cr = scanScopeOptionRects[i]
            cr.set(contentLeft, ct, contentRight, ct + cardH)

            draw3dsSculptedDockButton(
                canvas = canvas,
                r = cr,
                radius = 14f,
                isPrimary = isSelected || isFocused,
                accentOverride = if (isSelected) accents[i] else Color.parseColor("#94A3B8"),
                drawOutline = !isSelected
            )

            // 左侧：主标题与描述
            textPaint.typeface = apolloMediumTypeface
            textPaint.color = if (isSelected) accents[i] else currentTheme.textPrimaryColor
            textPaint.textSize = (cardH * 0.32f).coerceIn(18f, 25f)
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(titles[i], cr.left + 20f, cr.centerY() - cardH * 0.13f, textPaint)

            textPaint.typeface = apolloRegularTypeface
            textPaint.color = currentTheme.textSecondaryColor
            textPaint.textSize = (cardH * 0.21f).coerceIn(14f, 18.5f)
            canvas.drawText(descs[i], cr.left + 20f, cr.centerY() + cardH * 0.22f, textPaint)

            // 右侧：开关状态小药丸 (Toggle Badge)
            val badgeW = (cr.width() * 0.20f).coerceIn(120f, 160f)
            val badgeH = (cardH * 0.48f).coerceIn(28f, 36f)
            val badgeRect = RectF(cr.right - badgeW - 16f, cr.centerY() - badgeH / 2f, cr.right - 16f, cr.centerY() + badgeH / 2f)

            paint.style = Paint.Style.FILL
            paint.color = if (isSelected) accents[i] else Color.argb(80, 100, 116, 139)
            canvas.drawRoundRect(badgeRect, badgeH / 2f, badgeH / 2f, paint)

            textPaint.typeface = apolloMediumTypeface
            textPaint.color = Color.WHITE
            textPaint.textSize = (badgeH * 0.50f).coerceIn(14f, 17.5f)
            textPaint.textAlign = Paint.Align.CENTER
            val badgeLabel = if (isSelected) "✔ 已生效" else "○ 点击切换"
            canvas.drawText(badgeLabel, badgeRect.centerX(), badgeRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

            if (isFocused) {
                draw3dsCornerCursor(canvas, cr)
            }
        }

        // 底部双操作底栏：[ ◀ 返回设置 (B) ]  [ 🔍 立即执行手动全量扫描 ]
        val dockTop = h - bottomBarH
        val btnTop = dockTop + 7f
        val btnBottom = h - 7f
        val btnRadius = (btnBottom - btnTop) * 0.48f
        val sidePad = 14f
        val btnGap = 12f
        val availW = w - sidePad * 2f - btnGap
        val backW = (availW * 0.35f).coerceIn(160f, 260f)

        subPanelBackBtnRect.set(sidePad, btnTop, sidePad + backW, btnBottom)
        scanManualActionBtnRect.set(subPanelBackBtnRect.right + btnGap, btnTop, w - sidePad, btnBottom)

        draw3dsSculptedDockButton(
            canvas = canvas,
            r = subPanelBackBtnRect,
            radius = btnRadius,
            isPrimary = false
        )
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = (subPanelBackBtnRect.height() * 0.42f).coerceIn(15f, 20f)
        textPaint.color = currentTheme.textPrimaryColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("◀ 返回设置 (B)", subPanelBackBtnRect.centerX(), subPanelBackBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        draw3dsSculptedDockButton(
            canvas = canvas,
            r = scanManualActionBtnRect,
            radius = btnRadius,
            isPrimary = true,
            accentOverride = Color.parseColor("#10B981")
        )
        textPaint.textSize = (scanManualActionBtnRect.height() * 0.42f).coerceIn(15f, 20f)
        textPaint.color = Color.WHITE
        canvas.drawText("🔍 立即执行手动全量扫描 (${scanStorageScope.shortLabel})", scanManualActionBtnRect.centerX(), scanManualActionBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
    }

    private fun createPreviewFilterBitmap(type: String): Bitmap {
        return when (type) {
            "LCD_3X" -> {
                val bmp = Bitmap.createBitmap(6, 3, Bitmap.Config.ARGB_8888)
                for (x in 0 until 6) {
                    for (y in 0 until 3) {
                        if (y == 2) {
                            bmp.setPixel(x, y, Color.argb(230, 0, 0, 0))
                        } else if (x == 5) {
                            bmp.setPixel(x, y, Color.argb(255, 0, 0, 0))
                        } else if (x == 1 || x == 3) {
                            bmp.setPixel(x, y, Color.argb(130, 0, 0, 0))
                        } else {
                            bmp.setPixel(x, y, Color.TRANSPARENT)
                        }
                    }
                }
                bmp
            }
            "DOT_MATRIX" -> {
                val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                val border = Color.argb(255, 0, 0, 0)
                val center = Color.TRANSPARENT
                for (x in 0 until 4) {
                    for (y in 0 until 4) {
                        if (x == 3 || y == 3) bmp.setPixel(x, y, border) else bmp.setPixel(x, y, center)
                    }
                }
                bmp
            }
            "SCANLINE" -> {
                val bmp = Bitmap.createBitmap(1, 4, Bitmap.Config.ARGB_8888)
                bmp.setPixel(0, 0, Color.TRANSPARENT)
                bmp.setPixel(0, 1, Color.TRANSPARENT)
                bmp.setPixel(0, 2, Color.argb(120, 0, 0, 0))
                bmp.setPixel(0, 3, Color.argb(255, 0, 0, 0))
                bmp
            }
            "CRT_APERTURE" -> {
                val bmp = Bitmap.createBitmap(3, 1, Bitmap.Config.ARGB_8888)
                bmp.setPixel(0, 0, Color.TRANSPARENT)
                bmp.setPixel(1, 0, Color.TRANSPARENT)
                bmp.setPixel(2, 0, Color.argb(255, 0, 0, 0))
                bmp
            }
            else -> createPreviewFilterBitmap("LCD_3X")
        }
    }



    fun openGameDescription() {
        if (isAllAppsDrawerOpen) {
            isAllAppsDrawerOpen = false
        }
        if (isSettingsPanelOpen) {
            closeSettingsPanel()
        }
        isGameDescriptionOpen = true
        descScrollY = 0f
        targetDescScrollY = 0f
        cachedStaticLayout = null
        cachedLayoutKey = ""
        SoundEffectEngine.playSelectApplet()
        invalidate()
    }

    fun closeGameDescription() {
        if (isGameDescriptionOpen) {
            isGameDescriptionOpen = false
            selectedTopIconIndex = -1
            SoundEffectEngine.playBackCancel()
            invalidate()
        }
    }

    fun toggleGameDescription() {
        if (isGameDescriptionOpen) {
            closeGameDescription()
        } else {
            openGameDescription()
        }
    }

    fun selectPreviousGame() {
        if (slots.isEmpty()) return
        var nextIdx = (selectedIndex - 1 + slots.size) % slots.size
        var attempts = 0
        while (slots[nextIdx].type == PlatformType.EMPTY && attempts < slots.size) {
            nextIdx = (nextIdx - 1 + slots.size) % slots.size
            attempts++
        }
        selectedIndex = nextIdx
        targetDescScrollY = 0f
        descScrollY = 0f
        cachedStaticLayout = null
        cachedLayoutKey = ""
        SoundEffectEngine.playCursorMove()
        listener?.onSlotSelected(slots[selectedIndex], selectedIndex)
        invalidate()
    }

    fun selectNextGame() {
        if (slots.isEmpty()) return
        var nextIdx = (selectedIndex + 1) % slots.size
        var attempts = 0
        while (slots[nextIdx].type == PlatformType.EMPTY && attempts < slots.size) {
            nextIdx = (nextIdx + 1) % slots.size
            attempts++
        }
        selectedIndex = nextIdx
        targetDescScrollY = 0f
        descScrollY = 0f
        cachedStaticLayout = null
        cachedLayoutKey = ""
        SoundEffectEngine.playCursorMove()
        listener?.onSlotSelected(slots[selectedIndex], selectedIndex)
        invalidate()
    }

    /**
     * 绘制 3DS 拟物风格「游戏介绍与详情手册」专属立体图标（3DS 官方操作说明书造型 + 烫金书脊与书签）
     */
    private fun drawGameDescriptionIcon(canvas: Canvas, r: RectF, isActive: Boolean) {
        val cx = r.centerX()
        val cy = r.centerY()
        val s = minOf(r.width(), r.height()) * 0.44f

        val bookW = s * 1.42f
        val bookH = s * 1.54f
        val bookRect = RectF(cx - bookW / 2f, cy - bookH / 2f, cx + bookW / 2f, cy + bookH / 2f)
        val radius = 4f

        // 1. 书籍封皮背景 (3DS 经典暖橙/琥珀金色调)
        paint.style = Paint.Style.FILL
        val topColor = if (isActive) Color.parseColor("#FBBF24") else Color.parseColor("#F59E0B")
        val botColor = if (isActive) Color.parseColor("#D97706") else Color.parseColor("#B45309")
        paint.shader = LinearGradient(
            bookRect.left, bookRect.top, bookRect.right, bookRect.bottom,
            topColor, botColor, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(bookRect, radius, radius, paint)
        paint.shader = null

        // 2. 左侧书脊深色立体压痕
        val spineW = bookW * 0.18f
        val spineRect = RectF(bookRect.left, bookRect.top, bookRect.left + spineW, bookRect.bottom)
        paint.color = Color.parseColor("#78350F")
        canvas.drawRoundRect(spineRect, radius, radius, paint)

        // 3. 右侧页面纸张边缘 (浅米色内页质感)
        val pageW = bookW * 0.12f
        val pageRect = RectF(bookRect.right - pageW, bookRect.top + 2f, bookRect.right - 1.5f, bookRect.bottom - 2f)
        paint.color = Color.parseColor("#FEF3C7")
        canvas.drawRoundRect(pageRect, 2f, 2f, paint)

        // 4. 书底伸出的红丝带书签
        val ribbonW = bookW * 0.16f
        val ribbonH = bookH * 0.22f
        val ribbonLeft = cx + bookW * 0.08f
        val ribbonRect = RectF(ribbonLeft, bookRect.bottom - 3f, ribbonLeft + ribbonW, bookRect.bottom + ribbonH)
        paint.color = Color.parseColor("#E11D48")
        canvas.drawRoundRect(ribbonRect, 1.5f, 1.5f, paint)

        // 5. 封皮中央立体烫金/洁白「📖」符号或说明书图案
        paint.style = Paint.Style.FILL
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = s * 0.72f
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("📖", cx + bookW * 0.05f, cy + 1f - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 6. 外边框微光描边
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = Color.argb(180, 255, 255, 255)
        canvas.drawRoundRect(bookRect, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    /**
     * 绘制副屏（下屏）正中的 3DS 风格「游戏介绍与详情手册」卡片面板
     */
    private fun ellipsize(text: String, p: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f || p.measureText(text) <= maxWidth) return text
        var len = text.length
        while (len > 1 && p.measureText(text.substring(0, len) + "…") > maxWidth) {
            len--
        }
        return text.substring(0, len) + "…"
    }

    private fun drawGameDescriptionPanel(
        canvas: Canvas,
        w: Float,
        h: Float,
        panelTop: Float,
        panelBottom: Float,
        bottomBarH: Float
    ) {
        val sideMargin = 16f
        stageWellRect.set(sideMargin, panelTop, w - sideMargin, panelBottom)
        val stageRadius = 26f

        // 1. 绘制 3DS 底盘柔和阴影
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(26, 0, 0, 0)
        canvas.drawRoundRect(
            RectF(stageWellRect.left, stageWellRect.top + 3f, stageWellRect.right, stageWellRect.bottom + 3f),
            stageRadius, stageRadius, paint
        )

        // 2. 绘制面板背景卡片
        val innerPanel = RectF(stageWellRect)
        paint.shader = LinearGradient(
            0f, innerPanel.top, 0f, innerPanel.bottom,
            blendColor(currentTheme.slotFillColor, Color.WHITE, 0.35f),
            currentTheme.lowerBgBottom,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(innerPanel, stageRadius, stageRadius, paint)
        paint.shader = null

        // 3. 当前选中的游戏卡槽
        val curSlot = slots.getOrNull(selectedIndex)

        // 4. Header 区域
        val headerH = (innerPanel.height() * 0.16f).coerceIn(48f, 72f)
        val headerTop = innerPanel.top + 10f
        val headerBottom = headerTop + headerH
        val headerLeft = innerPanel.left + 16f
        val headerRight = innerPanel.right - 16f

        // 右上角关闭按钮 [ ✕ ]
        val closeBtnSize = (headerH * 0.58f).coerceIn(28f, 36f)
        descCloseBtnRect.set(headerRight - closeBtnSize, headerTop + (headerH - closeBtnSize) / 2f, headerRight, headerTop + (headerH + closeBtnSize) / 2f)
        draw3dsSculptedDockButton(
            canvas = canvas,
            r = descCloseBtnRect,
            radius = closeBtnSize / 2f,
            isPrimary = false,
            accentOverride = Color.parseColor("#94A3B8"),
            drawOutline = true
        )
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = closeBtnSize * 0.56f
        textPaint.color = currentTheme.textSecondaryColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("✕", descCloseBtnRect.centerX(), descCloseBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 标题与平台信息
        if (curSlot != null && curSlot.type != PlatformType.EMPTY) {
            val effPlatform = curSlot.getEffectivePlatform()
            val platDef = com.gameconsole.launcher3ds.model.PlatformCatalog.getById(effPlatform)
            val platColor = platDef?.colorHex?.let { Color.parseColor(it) } ?: curSlot.primaryColor

            // 平台药丸徽标
            textPaint.typeface = apolloMediumTypeface
            textPaint.textSize = (headerH * 0.28f).coerceIn(13f, 17f)
            val platBadgeText = effPlatform
            val badgeW = textPaint.measureText(platBadgeText) + 20f
            val badgeH = (headerH * 0.38f).coerceIn(20f, 26f)
            val badgeRect = RectF(headerLeft, headerTop + 2f, headerLeft + badgeW, headerTop + 2f + badgeH)
            paint.style = Paint.Style.FILL
            paint.color = platColor
            canvas.drawRoundRect(badgeRect, 6f, 6f, paint)
            textPaint.color = Color.WHITE
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(platBadgeText, badgeRect.centerX(), badgeRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

            // 游戏标题
            textPaint.typeface = apolloMediumTypeface
            textPaint.textSize = (headerH * 0.40f).coerceIn(17f, 24f)
            textPaint.color = currentTheme.textPrimaryColor
            textPaint.textAlign = Paint.Align.LEFT
            val titleX = badgeRect.right + 12f
            val maxTitleW = (descCloseBtnRect.left - titleX - 12f).coerceAtLeast(60f)
            val titleY = badgeRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(ellipsize(curSlot.title, textPaint, maxTitleW), titleX, titleY, textPaint)

            // 副标题与开发商
            val devName = curSlot.developer.ifBlank { curSlot.publisher }.ifBlank { "官方资料" }
            val metaInfo = "开发商/厂商: $devName  ·  ${curSlot.subtitle}"
            textPaint.typeface = apolloRegularTypeface
            textPaint.textSize = (headerH * 0.25f).coerceIn(12.5f, 15.5f)
            textPaint.color = currentTheme.textSecondaryColor
            val subY = headerBottom - 6f
            canvas.drawText(ellipsize(metaInfo, textPaint, headerRight - headerLeft - closeBtnSize - 12f), headerLeft, subY, textPaint)
        } else {
            textPaint.typeface = apolloMediumTypeface
            textPaint.textSize = 20f
            textPaint.color = currentTheme.textPrimaryColor
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("📖 游戏说明与官方介绍", headerLeft, headerTop + headerH * 0.5f, textPaint)
        }

        // 分割线
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = blendColor(currentTheme.slotBorderColor, Color.TRANSPARENT, 0.4f)
        canvas.drawLine(headerLeft, headerBottom + 2f, headerRight, headerBottom + 2f, paint)

        // 5. 底部操作按钮区域（固定在 innerPanel 底部）
        val dockH = (innerPanel.height() * 0.15f).coerceIn(42f, 58f)
        val dockBottom = innerPanel.bottom - 10f
        val dockTop = dockBottom - dockH
        val dockGap = 12f

        val btnW = (innerPanel.width() - 32f - dockGap * 2f) / 3f

        // 左按钮：◀ 返回桌面 (B)
        descBackBtnRect.set(headerLeft, dockTop, headerLeft + btnW, dockBottom)
        draw3dsSculptedDockButton(
            canvas = canvas,
            r = descBackBtnRect,
            radius = dockH * 0.38f,
            isPrimary = false,
            accentOverride = Color.parseColor("#64748B"),
            drawOutline = true
        )
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = (dockH * 0.36f).coerceIn(14f, 19f)
        textPaint.color = currentTheme.textPrimaryColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("◀ 返回桌面 (B)", descBackBtnRect.centerX(), descBackBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 中间按钮：切换上一款 / 下一款
        val midLeft = descBackBtnRect.right + dockGap
        val midRight = midLeft + btnW
        val halfW = (btnW - 6f) / 2f
        descPrevBtnRect.set(midLeft, dockTop, midLeft + halfW, dockBottom)
        descNextBtnRect.set(midLeft + halfW + 6f, dockTop, midRight, dockBottom)

        draw3dsSculptedDockButton(
            canvas = canvas,
            r = descPrevBtnRect,
            radius = dockH * 0.38f,
            isPrimary = false,
            accentOverride = Color.parseColor("#94A3B8"),
            drawOutline = true
        )
        canvas.drawText("◀ 上一款", descPrevBtnRect.centerX(), descPrevBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        draw3dsSculptedDockButton(
            canvas = canvas,
            r = descNextBtnRect,
            radius = dockH * 0.38f,
            isPrimary = false,
            accentOverride = Color.parseColor("#94A3B8"),
            drawOutline = true
        )
        canvas.drawText("下一款 ▶", descNextBtnRect.centerX(), descNextBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 右按钮：▶ 立即启动 (A)
        descLaunchBtnRect.set(midRight + dockGap, dockTop, innerPanel.right - 16f, dockBottom)
        draw3dsSculptedDockButton(
            canvas = canvas,
            r = descLaunchBtnRect,
            radius = dockH * 0.38f,
            isPrimary = true,
            accentOverride = Color.parseColor("#10B981"),
            drawOutline = false
        )
        textPaint.color = Color.WHITE
        canvas.drawText("▶ 立即启动 (A)", descLaunchBtnRect.centerX(), descLaunchBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 6. 介绍文本正文滚动显示区域
        descContentAreaRect.set(headerLeft, headerBottom + 8f, headerRight, dockTop - 8f)
        val textCardRadius = 14f

        paint.style = Paint.Style.FILL
        paint.color = blendColor(currentTheme.slotFillColor, Color.WHITE, 0.65f)
        canvas.drawRoundRect(descContentAreaRect, textCardRadius, textCardRadius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = blendColor(currentTheme.slotBorderColor, Color.TRANSPARENT, 0.2f)
        canvas.drawRoundRect(descContentAreaRect, textCardRadius, textCardRadius, paint)

        // 解析并绘制介绍文本
        val rawDesc = curSlot?.let { gameDescriptionResolver?.invoke(it) ?: it.description }.orEmpty().trim()
        val contentW = (descContentAreaRect.width() - 40f).toInt().coerceAtLeast(100)

        if (rawDesc.isBlank()) {
            textPaint.typeface = apolloMediumTypeface
            textPaint.textSize = 17f
            textPaint.color = currentTheme.textSecondaryColor
            textPaint.textAlign = Paint.Align.CENTER
            val emptyCy = descContentAreaRect.centerY()
            canvas.drawText("📖 暂无此游戏的官方介绍", descContentAreaRect.centerX(), emptyCy - 14f, textPaint)
            textPaint.typeface = apolloRegularTypeface
            textPaint.textSize = 14f
            canvas.drawText("可在游戏目录或媒体包放置 metadata.pegasus.txt 即可自动展示剧情与攻略", descContentAreaRect.centerX(), emptyCy + 18f, textPaint)
            maxDescScrollY = 0f
            return
        }

        val layoutKey = "${curSlot?.id}_${contentW}_${rawDesc.hashCode()}"
        val layout = if (cachedLayoutKey == layoutKey && cachedStaticLayout != null) {
            cachedStaticLayout!!
        } else {
            val tp = android.text.TextPaint(textPaint).apply {
                typeface = apolloRegularTypeface
                textSize = (innerPanel.height() * 0.040f).coerceIn(16f, 20f)
                color = currentTheme.textPrimaryColor
                isAntiAlias = true
            }
            val newLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.text.StaticLayout.Builder.obtain(rawDesc, 0, rawDesc.length, tp, contentW)
                    .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(6f, 1.25f)
                    .setIncludePad(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                android.text.StaticLayout(rawDesc, tp, contentW, android.text.Layout.Alignment.ALIGN_NORMAL, 1.25f, 6f, true)
            }
            cachedStaticLayout = newLayout
            cachedLayoutKey = layoutKey
            newLayout
        }

        val totalTextHeight = layout.height.toFloat()
        val visibleAreaH = descContentAreaRect.height() - 32f
        maxDescScrollY = (totalTextHeight - visibleAreaH).coerceAtLeast(0f)

        // 平滑滚动物理插值
        descScrollY += (targetDescScrollY - descScrollY) * 0.35f
        if (kotlin.math.abs(targetDescScrollY - descScrollY) < 0.2f) {
            descScrollY = targetDescScrollY
        }

        canvas.save()
        canvas.clipRect(
            descContentAreaRect.left + 4f,
            descContentAreaRect.top + 4f,
            descContentAreaRect.right - 4f,
            descContentAreaRect.bottom - 4f
        )
        canvas.translate(descContentAreaRect.left + 20f, descContentAreaRect.top + 16f - descScrollY)
        layout.draw(canvas)
        canvas.restore()

        // 绘制垂直滚动条
        if (maxDescScrollY > 4f) {
            val scrollRatio = (descScrollY / maxDescScrollY).coerceIn(0f, 1f)
            val barW = 6f
            val barTrackH = descContentAreaRect.height() - 24f
            val thumbH = (barTrackH * (visibleAreaH / totalTextHeight)).coerceIn(24f, barTrackH * 0.75f)
            val thumbY = descContentAreaRect.top + 12f + scrollRatio * (barTrackH - thumbH)
            val thumbX = descContentAreaRect.right - barW - 6f

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(45, 0, 0, 0)
            canvas.drawRoundRect(RectF(thumbX, descContentAreaRect.top + 12f, thumbX + barW, descContentAreaRect.top + 12f + barTrackH), barW / 2f, barW / 2f, paint)

            paint.color = currentTheme.cursorMainColor
            canvas.drawRoundRect(RectF(thumbX, thumbY, thumbX + barW, thumbY + thumbH), barW / 2f, barW / 2f, paint)
        }
    }


    private fun drawPegasusMetadataPreviewSubPanel(
        canvas: Canvas,
        innerPanel: RectF,
        w: Float,
        h: Float,
        bottomBarH: Float
    ) {
        val inspection = pegasusInspectionProvider?.invoke(currentPegasusInspectPlatform)
        val hasFile = inspection?.exists == true

        // 1. 顶部标题栏 + 平台切换胶囊按钮 (3DS / NDS)
        val headerH = (innerPanel.height() * 0.11f).coerceIn(36f, 46f)
        val headerTop = innerPanel.top + 8f

        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = (headerH * 0.52f).coerceIn(16f, 21f)
        textPaint.color = currentTheme.cursorMainColor
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("📖 模拟器 metadata.pegasus.txt 探测与预览", innerPanel.left + 16f, headerTop + headerH * 0.55f, textPaint)

        // 平台选择胶囊按钮：[ 🎮 3DS ]  [ 🕹️ NDS ]
        val platBtnW = 95f
        val platBtnH = (headerH * 0.75f).coerceIn(28f, 36f)
        val platBtnTop = headerTop + (headerH - platBtnH) / 2f

        pegasusInspectPlatNdsRect.set(innerPanel.right - 16f - platBtnW, platBtnTop, innerPanel.right - 16f, platBtnTop + platBtnH)
        pegasusInspectPlat3dsRect.set(pegasusInspectPlatNdsRect.left - 10f - platBtnW, platBtnTop, pegasusInspectPlatNdsRect.left - 10f, platBtnTop + platBtnH)

        val is3ds = currentPegasusInspectPlatform.equals("3DS", ignoreCase = true)
        draw3dsSculptedDockButton(canvas, pegasusInspectPlat3dsRect, platBtnH * 0.44f, isPrimary = is3ds, accentOverride = Color.parseColor("#E60012"))
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 14f
        textPaint.color = if (is3ds) Color.WHITE else currentTheme.textSecondaryColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("🎮 3DS", pegasusInspectPlat3dsRect.centerX(), pegasusInspectPlat3dsRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        draw3dsSculptedDockButton(canvas, pegasusInspectPlatNdsRect, platBtnH * 0.44f, isPrimary = !is3ds, accentOverride = Color.parseColor("#2B343D"))
        textPaint.color = if (!is3ds) Color.WHITE else currentTheme.textSecondaryColor
        canvas.drawText("🕹️ NDS", pegasusInspectPlatNdsRect.centerX(), pegasusInspectPlatNdsRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 2. 状态信息 Hero 卡片
        val heroTop = headerTop + headerH + 8f
        val heroH = if (hasFile) 78f else 88f
        val heroRect = RectF(innerPanel.left + 16f, heroTop, innerPanel.right - 16f, heroTop + heroH)
        val heroAccent = if (hasFile) Color.parseColor("#10B981") else Color.parseColor("#F59E0B")
        draw3dsSculptedDockButton(canvas, heroRect, 14f, isPrimary = false, accentOverride = heroAccent)

        // Hero 卡片内容
        val heroBadgeW = if (hasFile) 115f else 170f
        val heroBadgeH = 24f
        val heroBadgeRect = RectF(heroRect.left + 12f, heroRect.top + 10f, heroRect.left + 12f + heroBadgeW, heroRect.top + 10f + heroBadgeH)
        paint.style = Paint.Style.FILL
        paint.color = heroAccent
        canvas.drawRoundRect(heroBadgeRect, 6f, 6f, paint)

        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 12.5f
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(if (hasFile) "✅ 文件已检测到" else "⚠️ 暂未检测到该文件", heroBadgeRect.centerX(), heroBadgeRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 路径展示
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = apolloRegularTypeface
        textPaint.textSize = 13f
        textPaint.color = currentTheme.textPrimaryColor
        val pathLabel = "📁 " + (inspection?.filePath.orEmpty())
        val displayPath = if (pathLabel.length > 55) pathLabel.take(28) + "..." + pathLabel.takeLast(24) else pathLabel
        canvas.drawText(displayPath, heroBadgeRect.right + 12f, heroBadgeRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 第二行指标 / 提示
        if (hasFile) {
            textPaint.typeface = apolloRegularTypeface
            textPaint.textSize = 13f
            textPaint.color = currentTheme.textSecondaryColor
            val statsStr = "📦 文件大小: ${inspection?.fileSizeFormatted}   |   🔤 识别编码: ${inspection?.encodingName}   |   🎮 已解析介绍: ${inspection?.totalEntries} 款游戏"
            canvas.drawText(statsStr, heroRect.left + 14f, heroRect.top + 54f, textPaint)
        } else {
            textPaint.typeface = apolloRegularTypeface
            textPaint.textSize = 12.5f
            textPaint.color = Color.parseColor("#F59E0B")
            canvas.drawText("💡 提示: 请将 metadata.pegasus.txt 放在 TF 卡对应机种目录下 (如 3DS media/3DS/ 或 roms/3ds/)", heroRect.left + 14f, heroRect.top + 52f, textPaint)
            textPaint.color = currentTheme.textSecondaryColor
            canvas.drawText("放入后点击下方【重新探测并刷新】即可立即识别全量游戏剧情介绍", heroRect.left + 14f, heroRect.top + 70f, textPaint)
        }

        // 3. 模式切换条：[ 📋 结构化卡片模式 (${inspection?.totalEntries}款) ]  [ 📄 原始文本 ]
        val modeBarTop = heroRect.bottom + 8f
        val modeBarH = 30f
        val modeBtnW = 160f
        pegasusInspectModeToggleRect.set(innerPanel.right - 16f - modeBtnW, modeBarTop, innerPanel.right - 16f, modeBarTop + modeBarH)

        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = 14f
        textPaint.color = currentTheme.textSecondaryColor
        textPaint.textAlign = Paint.Align.LEFT
        val previewTitle = if (pegasusInspectRawTextMode) "📄 metadata.pegasus.txt 原始文件内容预览:" else "📋 已解析游戏介绍卡片 (${inspection?.totalEntries ?: 0} 款):"
        canvas.drawText(previewTitle, innerPanel.left + 16f, modeBarTop + modeBarH * 0.65f, textPaint)

        draw3dsSculptedDockButton(canvas, pegasusInspectModeToggleRect, modeBarH * 0.42f, isPrimary = true, accentOverride = Color.parseColor("#6366F1"))
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 12.5f
        canvas.drawText(if (pegasusInspectRawTextMode) "切换: 📋 卡片视图" else "切换: 📄 原始文本", pegasusInspectModeToggleRect.centerX(), pegasusInspectModeToggleRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        // 4. 内容滚动展示区域
        val contentTop = modeBarTop + modeBarH + 6f
        val contentBottom = innerPanel.bottom - 12f
        val contentArea = RectF(innerPanel.left + 16f, contentTop, innerPanel.right - 16f, contentBottom)

        // 绘制内嵌容器背景
        paint.style = Paint.Style.FILL
        paint.color = blendColor(currentTheme.slotFillColor, Color.BLACK, 0.15f)
        canvas.drawRoundRect(contentArea, 12f, 12f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = blendColor(currentTheme.slotBorderColor, Color.TRANSPARENT, 0.4f)
        canvas.drawRoundRect(contentArea, 12f, 12f, paint)

        // 裁剪并滚动绘制内容
        canvas.save()
        canvas.clipRect(contentArea)

        if (!hasFile || (inspection != null && inspection.totalEntries == 0 && inspection.rawTextSnippet.isBlank())) {
            textPaint.typeface = apolloMediumTypeface
            textPaint.textSize = 16f
            textPaint.color = currentTheme.textSecondaryColor
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("暂无内容可供预览", contentArea.centerX(), contentArea.centerY() - 10f, textPaint)
            textPaint.textSize = 13f
            canvas.drawText("检测到文件不存在或为空，放入文件后点击下方【重新探测并刷新】即可", contentArea.centerX(), contentArea.centerY() + 16f, textPaint)
            maxPegasusInspectScrollY = 0f
            canvas.restore()
        } else if (pegasusInspectRawTextMode) {
            // 原始文本模式
            val lines = (inspection?.rawTextSnippet.orEmpty()).lines()
            val lineH = 22f
            val totalH = lines.size * lineH + 20f
            maxPegasusInspectScrollY = (totalH - contentArea.height()).coerceAtLeast(0f)
            pegasusInspectScrollY += (targetPegasusInspectScrollY - pegasusInspectScrollY) * 0.35f

            var curY = contentArea.top + 18f - pegasusInspectScrollY
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = 13f
            textPaint.typeface = Typeface.MONOSPACE

            for ((i, line) in lines.withIndex()) {
                if (curY in (contentArea.top - 20f)..(contentArea.bottom + 20f)) {
                    val lineNumStr = String.format(java.util.Locale.US, "%03d | ", i + 1)
                    textPaint.color = Color.parseColor("#64748B")
                    canvas.drawText(lineNumStr, contentArea.left + 12f, curY, textPaint)

                    val numW = textPaint.measureText(lineNumStr)
                    textPaint.color = when {
                        line.startsWith("game:", ignoreCase = true) -> Color.parseColor("#38BDF8")
                        line.startsWith("file:", ignoreCase = true) -> Color.parseColor("#4ADE80")
                        line.startsWith("developer:", ignoreCase = true) -> Color.parseColor("#FBBF24")
                        line.startsWith("description:", ignoreCase = true) -> Color.parseColor("#F472B6")
                        else -> Color.parseColor("#E2E8F0")
                    }
                    canvas.drawText(line, contentArea.left + 12f + numW, curY, textPaint)
                }
                curY += lineH
            }
            canvas.restore()
        } else {
            // 结构化卡片模式
            val entries = (inspection?.sampleEntries ?: emptyList()).take(60)
            val cardGap = 8f
            val cardPad = 10f
            val cardItemW = contentArea.width() - 20f

            // 预估卡片高度并平滑滚动
            var totalH = 10f
            val entryHeights = FloatArray(entries.size)
            for ((i, e) in entries.withIndex()) {
                val descLen = e.description.length
                val hEst = if (descLen > 120) 100f else if (descLen > 50) 80f else 62f
                entryHeights[i] = hEst
                totalH += hEst + cardGap
            }
            maxPegasusInspectScrollY = (totalH - contentArea.height()).coerceAtLeast(0f)
            pegasusInspectScrollY += (targetPegasusInspectScrollY - pegasusInspectScrollY) * 0.35f

            var curY = contentArea.top + 10f - pegasusInspectScrollY
            val itemLeft = contentArea.left + 10f

            for ((i, e) in entries.withIndex()) {
                val itemH = entryHeights[i]
                val itemRect = RectF(itemLeft, curY, itemLeft + cardItemW, curY + itemH)

                if (itemRect.bottom >= contentArea.top && itemRect.top <= contentArea.bottom) {
                    paint.style = Paint.Style.FILL
                    paint.color = blendColor(currentTheme.slotFillColor, Color.WHITE, 0.45f)
                    canvas.drawRoundRect(itemRect, 8f, 8f, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1f
                    paint.color = blendColor(currentTheme.slotBorderColor, Color.TRANSPARENT, 0.3f)
                    canvas.drawRoundRect(itemRect, 8f, 8f, paint)

                    // 序号 & 游戏名
                    textPaint.typeface = apolloMediumTypeface
                    textPaint.textAlign = Paint.Align.LEFT
                    textPaint.textSize = 14.5f
                    textPaint.color = currentTheme.textPrimaryColor
                    val titleStr = "#${i + 1}  " + e.game.ifBlank { "未知游戏" }
                    val titleDisplay = if (titleStr.length > 40) titleStr.take(38) + "..." else titleStr
                    canvas.drawText(titleDisplay, itemRect.left + cardPad, itemRect.top + 20f, textPaint)

                    // 文件 & 开发商
                    textPaint.typeface = apolloRegularTypeface
                    textPaint.textSize = 12f
                    textPaint.color = currentTheme.textSecondaryColor
                    val fileDevStr = "💿 文件: ${e.file.ifBlank { "未指定" }}   |   🏢 厂商: ${e.developer.ifBlank { "未知" }}"
                    canvas.drawText(fileDevStr, itemRect.left + cardPad, itemRect.top + 38f, textPaint)

                    // 介绍内容
                    if (e.description.isNotBlank()) {
                        textPaint.textSize = 12f
                        textPaint.color = currentTheme.textPrimaryColor
                        val descSnippet = e.description.replace("\n", " ").trim()
                        val descLine1 = if (descSnippet.length > 55) descSnippet.take(52) + "..." else descSnippet
                        canvas.drawText(descLine1, itemRect.left + cardPad, itemRect.top + 56f, textPaint)
                        if (itemH > 75f && descSnippet.length > 52) {
                            val descLine2 = descSnippet.drop(52).take(52) + if (descSnippet.length > 104) "..." else ""
                            canvas.drawText(descLine2, itemRect.left + cardPad, itemRect.top + 74f, textPaint)
                        }
                    } else {
                        textPaint.textSize = 11.5f
                        textPaint.color = currentTheme.textSecondaryColor
                        canvas.drawText("（该条目暂无简介正文）", itemRect.left + cardPad, itemRect.top + 56f, textPaint)
                    }
                }
                curY += itemH + cardGap
            }
            canvas.restore()
        }

        // 5. 底部 3DS 原版三段式底栏
        val dockTop = h - bottomBarH
        val btnTop = dockTop + 7f
        val btnBottom = h - 7f
        val btnRadius = (btnBottom - btnTop) * 0.48f
        val sidePad = 14f
        val gap = 10f
        val availW = w - sidePad * 2f - gap * 2f
        val backW = availW * 0.28f
        val rescanW = availW * 0.44f

        subPanelBackBtnRect.set(sidePad, btnTop, sidePad + backW, btnBottom)
        pegasusInspectRescanRect.set(subPanelBackBtnRect.right + gap, btnTop, subPanelBackBtnRect.right + gap + rescanW, btnBottom)
        pegasusInspectModeBottomRect.set(pegasusInspectRescanRect.right + gap, btnTop, w - sidePad, btnBottom)

        draw3dsSculptedDockButton(canvas, subPanelBackBtnRect, btnRadius, isPrimary = false)
        textPaint.typeface = apolloMediumTypeface
        textPaint.textSize = (subPanelBackBtnRect.height() * 0.40f).coerceIn(14f, 17f)
        textPaint.color = currentTheme.textPrimaryColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("◀ 返回设置 (B)", subPanelBackBtnRect.centerX(), subPanelBackBtnRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        draw3dsSculptedDockButton(canvas, pegasusInspectRescanRect, btnRadius, isPrimary = true, accentOverride = Color.parseColor("#10B981"))
        textPaint.color = Color.WHITE
        canvas.drawText("🔄 重新探测并全量刷新", pegasusInspectRescanRect.centerX(), pegasusInspectRescanRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)

        draw3dsSculptedDockButton(canvas, pegasusInspectModeBottomRect, btnRadius, isPrimary = true, accentOverride = Color.parseColor("#6366F1"))
        textPaint.color = Color.WHITE
        canvas.drawText(if (pegasusInspectRawTextMode) "📋 卡片模式" else "📄 纯文本", pegasusInspectModeBottomRect.centerX(), pegasusInspectModeBottomRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
    }

}
