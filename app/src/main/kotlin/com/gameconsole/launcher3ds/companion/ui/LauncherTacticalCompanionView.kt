package com.gameconsole.launcher3ds.companion.ui

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.gameconsole.launcher3ds.companion.ai.DeepSeekClient
import com.gameconsole.launcher3ds.companion.model.CompanionProfile
import com.gameconsole.launcher3ds.companion.model.CompanionTab
import com.gameconsole.launcher3ds.companion.model.Sf4ButtonItem
import com.gameconsole.launcher3ds.companion.model.Sf4LayoutType
import com.gameconsole.launcher3ds.companion.repository.CompanionProfileRepository
import com.gameconsole.launcher3ds.model.GridSlotItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🎮 3DS 战术战情伴侣副屏视图 (LauncherTacticalCompanionView)
 * 100% 独立内建于 Launcher3DS，直接投射到副屏。
 * 集合六大杀手级面板：
 * 1. 🌐 剧情翻译 (Translation)
 * 2. 🖼️ 游戏画廊 (Gallery)
 * 3. 📖 图文攻略 (Guide)
 * 4. 🕹️ 街霸触控键 (SF4 Buttons)
 * 5. 🖱️ 高精触控板 (Touchpad)
 * 6. 🤖 DeepSeek 战略智囊 (AI Copilot)
 */
@SuppressLint("ViewConstructor")
class LauncherTacticalCompanionView(
    context: Context,
    private val slot: GridSlotItem,
    private val repo: CompanionProfileRepository,
    private val onScreenCaptureRequested: ((callback: (Bitmap?) -> Unit) -> Unit)? = null,
    private val onKeyActionRequested: ((keyCode: Int, isTurbo: Boolean) -> Unit)? = null
) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private var profile: CompanionProfile = repo.getProfileForGame(slot)
    private var currentTab: CompanionTab = profile.defaultTab

    // UI 主框架
    private val topStatusBar: LinearLayout
    private val contentContainer: FrameLayout
    private val bottomTabBar: LinearLayout

    // 各面板子容器
    private val translatePanel: LinearLayout
    private val galleryPanel: FrameLayout
    private val guidePanel: LinearLayout
    private val sf4Panel: LinearLayout
    private val touchpadPanel: FrameLayout
    private val aiPanel: LinearLayout

    // 状态栏组件
    private lateinit var tvGameBadge: TextView
    private lateinit var tvTemplateCapsule: TextView
    private lateinit var tvBatteryClock: TextView

    // DeepSeek 聊天历史
    private val chatMessages = mutableListOf<DeepSeekClient.ChatMessage>()
    private lateinit var chatHistoryLayout: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var etAiQuestion: EditText

    init {
        setBackgroundColor(Color.parseColor("#E6EBF0"))

        // 1. 顶部状态栏 (3DS 原厂珍珠白拟物风格)
        topStatusBar = createTopStatusBar()
        addView(topStatusBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply {
            gravity = Gravity.TOP
        })

        // 2. 底部 6 大功能 Dock 栏 (3DS 塑胶微凸按键栏)
        bottomTabBar = createBottomTabBar()
        addView(bottomTabBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(50)).apply {
            gravity = Gravity.BOTTOM
        })

        // 3. 中间内容容器
        contentContainer = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#E6EBF0"))
        }
        addView(contentContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(40)
            bottomMargin = dp(50)
        })

        // 4. 初始化六大面板
        translatePanel = buildTranslatePanel()
        galleryPanel = buildGalleryPanel()
        guidePanel = buildGuidePanel()
        sf4Panel = buildSf4ButtonsPanel()
        touchpadPanel = buildTouchpadPanel()
        aiPanel = buildAiChatPanel()

        switchTab(currentTab)
    }

    // =========================================================================
    // 顶部状态栏与模板切换胶囊 (3DS 原厂风格)
    // =========================================================================

    private fun createTopStatusBar(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#E2E8F0"))
            ).apply {
                setStroke(dp(1), Color.parseColor("#94A3B8"))
            }
            setPadding(dp(10), 0, dp(10), 0)

            // StreetPass 绿灯
            val streetpassLed = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#22C55E"))
                    setStroke(dp(1), Color.parseColor("#15803D"))
                }
            }
            addView(streetpassLed, LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                marginEnd = dp(6)
            })

            // 游戏名称与平台
            tvGameBadge = TextView(context).apply {
                text = "${slot.title} · ${slot.getEffectivePlatform()}"
                setTextColor(Color.parseColor("#1E293B"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine()
            }
            addView(tvGameBadge, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

            // 模板胶囊
            tvTemplateCapsule = TextView(context).apply {
                text = "🏷️ ${profile.name.take(8)} ▾"
                setTextColor(Color.parseColor("#0284C7"))
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FFFFFF"))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.parseColor("#94A3B8"))
                }
                setOnClickListener { showTemplateManagementDialog() }
            }
            addView(tvTemplateCapsule, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            })

            // 时钟与金币电池
            tvBatteryClock = TextView(context).apply {
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                text = "🪙 15 · 🔋 [■■■] $timeStr"
                setTextColor(Color.parseColor("#576574"))
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(tvBatteryClock)

            // 关闭/隐藏伴侣按钮
            val btnClose = TextView(context).apply {
                text = "✕"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(6), dp(2), dp(6), dp(2))
                setOnClickListener {
                    com.gameconsole.launcher3ds.companion.overlay.LauncherCompanionOverlayManager.hide()
                }
            }
            addView(btnClose, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            })
        }
    }

    // =========================================================================
    // 底部动态功能标签栏 (支持按模板/游戏单独配置显隐)
    // =========================================================================

    private fun createBottomTabBar(): LinearLayout {
        val tabLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#E2E8F0"), Color.parseColor("#CBD5E1"))
            ).apply {
                setStroke(dp(1), Color.parseColor("#94A3B8"))
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        refreshBottomTabBar(tabLayout)
        return tabLayout
    }

    private fun refreshBottomTabBar(targetBar: LinearLayout = bottomTabBar) {
        targetBar.removeAllViews()
        val visibleTabs = profile.getEffectiveTabs()
        targetBar.weightSum = visibleTabs.size.toFloat()

        if (!visibleTabs.contains(currentTab)) {
            currentTab = visibleTabs.firstOrNull() ?: CompanionTab.SF4_BUTTONS
        }

        for (tab in visibleTabs) {
            val isActive = (tab == currentTab)
            val tabView = TextView(context).apply {
                tag = tab
                text = "${tab.icon}\n${tab.title}"
                setTextColor(if (isActive) Color.parseColor("#0369A1") else Color.parseColor("#576574"))
                textSize = if (visibleTabs.size <= 3) 11f else 9.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = create3dsTabBackground(isActive)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    vibrateQuick()
                    switchTab(tab)
                }
            }
            targetBar.addView(tabView, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
    }

    private fun create3dsTabBackground(isActive: Boolean): GradientDrawable {
        return if (isActive) {
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#E0F2FE"), Color.parseColor("#BAE6FD"))
            ).apply {
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), Color.parseColor("#0284C7"))
            }
        } else {
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#E2E8F0"))
            ).apply {
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), Color.parseColor("#94A3B8"))
            }
        }
    }

    private fun switchTab(targetTab: CompanionTab) {
        currentTab = targetTab
        contentContainer.removeAllViews()

        val panelView: View = when (targetTab) {
            CompanionTab.TRANSLATE -> translatePanel
            CompanionTab.GALLERY -> galleryPanel
            CompanionTab.GUIDE -> guidePanel
            CompanionTab.SF4_BUTTONS -> sf4Panel
            CompanionTab.TOUCHPAD -> touchpadPanel
            CompanionTab.AI_CHAT -> aiPanel
        }
        contentContainer.addView(panelView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 更新 3DS 实体触控按键外观
        val visibleTabs = profile.getEffectiveTabs()
        for (i in 0 until bottomTabBar.childCount) {
            val tv = bottomTabBar.getChildAt(i) as? TextView ?: continue
            val tab = tv.tag as? CompanionTab ?: visibleTabs.getOrNull(i)
            val isActive = (tab == targetTab)
            tv.setTextColor(if (isActive) Color.parseColor("#0369A1") else Color.parseColor("#576574"))
            tv.background = create3dsTabBackground(isActive)
        }
    }

    // =========================================================================
    // 1. 🌐 剧情翻译面板
    // =========================================================================

    private fun buildTranslatePanel(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))

            val tvTitle = TextView(context).apply {
                text = "🌐 实时台词与双语剧情记录"
                setTextColor(Color.parseColor("#1E293B"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(tvTitle)

            val tvTip = TextView(context).apply {
                text = "点击下方按钮抓取主屏台词对话框，AI 将自动翻译为中文并沉淀到本记录本中。"
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11f
            }
            addView(tvTip, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(8)
            })

            // 翻译记录滚动区
            val recordScroll = ScrollView(context).apply {
                isFillViewport = true
            }
            val recordLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }

            // 默认欢迎台词卡片
            val defaultCard = buildTranslateCard("システム起動 / System Initialized", "【战术伴侣已就绪】暂未抓取到剧情台词。请在游戏中对话时点击下方“抓屏翻译”。")
            recordLayout.addView(defaultCard)
            recordScroll.addView(recordLayout)
            addView(recordScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

            // 底部操作按钮
            val actionRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }

            val btnTranslateNow = Button(context).apply {
                text = "📸 抓取上屏剧情翻译"
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2563EB"))
                    cornerRadius = dp(8).toFloat()
                }
                setOnClickListener {
                    vibrateQuick()
                    onScreenCaptureRequested?.invoke { bmp ->
                        if (bmp != null) {
                            Toast.makeText(context, "正在由 AI 视觉识别上屏剧情台词...", Toast.LENGTH_SHORT).show()
                            DeepSeekClient.askDeepSeek(
                                config = profile.aiChat,
                                conversationHistory = emptyList(),
                                userQuestion = "请识别当前画面对话框里的日文/英文原文，并翻译为流畅的简体中文。格式严格如下：\n[原文]: ...\n[中文]: ...",
                                screencap = bmp,
                                callback = object : DeepSeekClient.ChatCallback {
                                    override fun onChunk(chunkText: String) {}
                                    override fun onComplete(fullText: String) {
                                        val card = buildTranslateCard("主屏游戏画面", fullText)
                                        recordLayout.addView(card, 0)
                                    }
                                    override fun onError(errorMsg: String) {
                                        Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        } else {
                            Toast.makeText(context, "未能获取到上屏画面", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            actionRow.addView(btnTranslateNow, LinearLayout.LayoutParams(0, dp(40), 1f))

            addView(actionRow)
        }
    }

    private fun buildTranslateCard(original: String, translated: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFFFFF"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#CBD5E1"))
            }
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }

            val tvOrig = TextView(context).apply {
                text = original
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11f
            }
            addView(tvOrig)

            val tvTrans = TextView(context).apply {
                text = translated
                setTextColor(Color.parseColor("#0284C7"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(tvTrans, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
        }
    }

    // =========================================================================
    // 2. 🖼️ 游戏画廊面板
    // =========================================================================

    private fun buildGalleryPanel(): FrameLayout {
        return FrameLayout(context).apply {
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (profile.gallery.customImagePaths.isNotEmpty()) {
                    val bmp = BitmapFactory.decodeFile(profile.gallery.customImagePaths.first())
                    if (bmp != null) setImageBitmap(bmp)
                } else if (slot.boxArtPath.isNotBlank()) {
                    val bmp = BitmapFactory.decodeFile(slot.boxArtPath)
                    if (bmp != null) setImageBitmap(bmp)
                }
            }
            addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            val tvCaption = TextView(context).apply {
                text = "🖼️ ${slot.title} · 官方海报与原画展台"
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.argb(180, 0, 0, 0))
                    cornerRadius = dp(16).toFloat()
                }
            }
            addView(tvCaption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            })
        }
    }

    // =========================================================================
    // 3. 📖 图文攻略面板
    // =========================================================================

    private fun buildGuidePanel(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))

            val tvTitle = TextView(context).apply {
                text = "📖 3DS 游戏备忘笔记 (Game Notes)"
                setTextColor(Color.parseColor("#1E293B"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(tvTitle)

            val scroll = ScrollView(context).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FFFBE8"))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(2), Color.parseColor("#EAB308"))
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val tvContent = TextView(context).apply {
                val textGuide = if (profile.guide.guideText.isNotBlank()) {
                    profile.guide.guideText
                } else {
                    "【3DS 战术笔记已就绪】\n暂未在 ROM 目录发现关联的同名攻略文档（.txt / .md）。\n您可以在此目录放置同名攻略文件，或随时切换到【DeepSeek AI】面板，让智囊直接看上屏画面为您指点迷津！"
                }
                text = textGuide
                setTextColor(Color.parseColor("#451A03"))
                textSize = 13f
                setLineSpacing(dp(4).toFloat(), 1.15f)
            }
            scroll.addView(tvContent)
            addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(6)
            })
        }
    }

    // =========================================================================
    // 4. 🕹️ 3DS 街霸4 虚拟触控按键面板 (大方格/6键/8键)
    // =========================================================================

    private fun buildSf4ButtonsPanel(): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        // 顶部切换排布条
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvMode = TextView(context).apply {
            text = "🕹️ 街霸4 触控连段台 (${profile.sf4Layout.displayName})"
            setTextColor(Color.parseColor("#1E293B"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        bar.addView(tvMode, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val btnLayoutToggle = Button(context).apply {
            text = "切换排布"
            setTextColor(Color.parseColor("#1E293B"))
            textSize = 11f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFFFFF"))
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), Color.parseColor("#94A3B8"))
            }
            setOnClickListener {
                profile.sf4Layout = when (profile.sf4Layout) {
                    Sf4LayoutType.FOUR_PADS_2X2 -> Sf4LayoutType.SIX_PADS_3X2
                    Sf4LayoutType.SIX_PADS_3X2 -> Sf4LayoutType.EIGHT_PADS_4X2
                    Sf4LayoutType.EIGHT_PADS_4X2 -> Sf4LayoutType.FOUR_PADS_2X2
                }
                repo.saveGameProfile(profile)
                refreshSf4Grid(this@LauncherTacticalCompanionView.sf4GridContainer)
                tvMode.text = "🕹️ 街霸4 触控连段台 (${profile.sf4Layout.displayName})"
            }
        }
        bar.addView(btnLayoutToggle, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)))
        root.addView(bar)

        val grid = GridLayout(context).apply {
            this@LauncherTacticalCompanionView.sf4GridContainer = this
        }
        root.addView(grid, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(8)
        })

        refreshSf4Grid(grid)
        return root
    }

    private lateinit var sf4GridContainer: GridLayout

    private fun refreshSf4Grid(grid: GridLayout) {
        grid.removeAllViews()
        val layout = profile.sf4Layout
        grid.rowCount = layout.rows
        grid.columnCount = layout.cols

        val totalButtons = layout.rows * layout.cols
        while (profile.sf4Buttons.size < totalButtons) {
            val idx = profile.sf4Buttons.size + 1
            profile.sf4Buttons.add(
                Sf4ButtonItem(id = "b_$idx", title = "按键 $idx", subTitle = "自定义", colorTheme = "#2563EB")
            )
        }

        for (i in 0 until totalButtons) {
            val item = profile.sf4Buttons[i]
            val btnFrame = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(item.colorTheme))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(2), Color.argb(120, 255, 255, 255))
                }
                isClickable = true
                isFocusable = true

                setOnClickListener {
                    vibrateQuick()
                    handleSf4ButtonClick(item)
                }

                setOnLongClickListener {
                    showEditButtonDialog(item)
                    true
                }
            }

            val textLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }

            val tvTitle = TextView(context).apply {
                text = item.title
                setTextColor(Color.WHITE)
                textSize = if (layout == Sf4LayoutType.FOUR_PADS_2X2) 18f else 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            textLayout.addView(tvTitle)

            if (item.subTitle.isNotBlank()) {
                val tvSub = TextView(context).apply {
                    text = item.subTitle
                    setTextColor(Color.argb(200, 255, 255, 255))
                    textSize = 11f
                    gravity = Gravity.CENTER
                }
                textLayout.addView(tvSub)
            }

            btnFrame.addView(textLayout, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

            val glp = GridLayout.LayoutParams().apply {
                rowSpec = GridLayout.spec(i / layout.cols, 1f)
                columnSpec = GridLayout.spec(i % layout.cols, 1f)
                width = 0
                height = 0
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(btnFrame, glp)
        }
    }

    private fun handleSf4ButtonClick(btn: Sf4ButtonItem) {
        onKeyActionRequested?.invoke(btn.keyCode, btn.isTurbo)
        Toast.makeText(context, "⚡ ${btn.title}", Toast.LENGTH_SHORT).show()
    }

    private fun showEditButtonDialog(btn: Sf4ButtonItem) {
        val etTitle = EditText(context).apply {
            setText(btn.title)
            hint = "按键名称 (如: 升龙拳)"
        }
        val etSub = EditText(context).apply {
            setText(btn.subTitle)
            hint = "副标题 (如: →↓↘+P)"
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
            addView(etTitle)
            addView(etSub)
        }

        AlertDialog.Builder(context)
            .setTitle("编辑触控按键")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                btn.title = etTitle.text.toString().trim().ifBlank { btn.title }
                btn.subTitle = etSub.text.toString().trim()
                repo.saveGameProfile(profile)
                refreshSf4Grid(sf4GridContainer)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // =========================================================================
    // 5. 🖱️ 高精度触控板面板 (Precision Trackpad)
    // =========================================================================

    private fun buildTouchpadPanel(): FrameLayout {
        val root = FrameLayout(context).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val trackpadSurface = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFFFFF"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(2), Color.parseColor("#94A3B8"))
            }

            val tvTip = TextView(context).apply {
                text = "🖱️ 3DS 拟物触控板区域\n单指滑动移动指针 · 轻按下方虚拟微动按键"
                setTextColor(Color.parseColor("#64748B"))
                textSize = 12f
                gravity = Gravity.CENTER
            }
            addView(tvTip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

            var lastTouchX = 0f
            var lastTouchY = 0f

            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouchX = event.x
                        lastTouchY = event.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.x - lastTouchX) * profile.touchpad.sensitivity
                        val dy = (event.y - lastTouchY) * profile.touchpad.sensitivity
                        lastTouchX = event.x
                        lastTouchY = event.y
                        // 移动指针回调可接入注入引擎
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        vibrateQuick()
                        true
                    }
                    else -> true
                }
            }
        }

        root.addView(trackpadSurface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            bottomMargin = dp(52)
        })

        // 底部物理模拟左右键
        val bottomMouseButtons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val btnLeft = Button(context).apply {
            text = "鼠标左键 (Left Click)"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0284C7"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#0369A1"))
            }
            setOnClickListener {
                vibrateQuick()
                Toast.makeText(context, "左键点击", Toast.LENGTH_SHORT).show()
            }
        }
        bottomMouseButtons.addView(btnLeft, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginEnd = dp(6)
        })

        val btnRight = Button(context).apply {
            text = "鼠标右键 (Right Click)"
            setTextColor(Color.parseColor("#1E293B"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFFFFF"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#94A3B8"))
            }
            setOnClickListener {
                vibrateQuick()
                Toast.makeText(context, "右键点击", Toast.LENGTH_SHORT).show()
            }
        }
        bottomMouseButtons.addView(btnRight, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(6)
        })

        root.addView(bottomMouseButtons, LayoutParams(LayoutParams.MATCH_PARENT, dp(44), Gravity.BOTTOM))
        return root
    }

    // =========================================================================
    // 6. 🤖 DeepSeek AI 战略导师面板
    // =========================================================================

    private fun buildAiChatPanel(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))

            val scroll = ScrollView(context).apply {
                isFillViewport = true
            }
            chatScrollView = scroll

            val history = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            chatHistoryLayout = history
            scroll.addView(history)

            // 欢迎消息
            val welcomeCard = buildAiBubble("assistant", "你好！我是你的专属战略智囊。当前你正在游玩《${slot.title}》，遇到卡关、迷宫解谜、Boss 弱点随时问我！点击下方【📷 看上屏问 AI】我能直接看着你的画面给你支招。")
            history.addView(welcomeCard)

            addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

            // 快捷提示词横滚条
            val chipsScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
            }
            val chipsLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val quickPrompts = listOf("🗺️ 当前迷宫路线怎么走？", "💥 当前 BOSS 弱点是什么？", "⚔️ 推荐前期加点与配队", "🎁 附近有哪些隐藏宝箱？")
            for (qp in quickPrompts) {
                val chip = TextView(context).apply {
                    text = qp
                    setTextColor(Color.parseColor("#0284C7"))
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#FFFFFF"))
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), Color.parseColor("#CBD5E1"))
                    }
                    setOnClickListener {
                        vibrateQuick()
                        askAi(qp, null)
                    }
                }
                chipsLayout.addView(chip, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(6)
                })
            }
            chipsScroll.addView(chipsLayout)
            addView(chipsScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            })

            // 底部输入与操作条
            val inputRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val btnCamera = Button(context).apply {
                text = "📷 看上屏"
                setTextColor(Color.WHITE)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#7C3AED"))
                    cornerRadius = dp(8).toFloat()
                }
                setOnClickListener {
                    vibrateQuick()
                    onScreenCaptureRequested?.invoke { bmp ->
                        if (bmp != null) {
                            askAi("请看我当前的主屏游戏画面，告诉我接下来该怎么做或注意什么要点？", bmp)
                        } else {
                            Toast.makeText(context, "未获取到画面", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            inputRow.addView(btnCamera, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(38)).apply {
                marginEnd = dp(6)
            })

            val etInput = EditText(context).apply {
                hint = "向 DeepSeek 战略导师提问..."
                setHintTextColor(Color.parseColor("#94A3B8"))
                setTextColor(Color.parseColor("#1E293B"))
                textSize = 12f
                setPadding(dp(10), 0, dp(10), 0)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FFFFFF"))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), Color.parseColor("#CBD5E1"))
                }
                setSingleLine()
            }
            etAiQuestion = etInput
            inputRow.addView(etInput, LinearLayout.LayoutParams(0, dp(38), 1f))

            val btnSend = Button(context).apply {
                text = "发送"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0284C7"))
                    cornerRadius = dp(8).toFloat()
                }
                setOnClickListener {
                    val q = etInput.text.toString().trim()
                    if (q.isNotBlank()) {
                        etInput.setText("")
                        askAi(q, null)
                    }
                }
            }
            inputRow.addView(btnSend, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(38)).apply {
                marginStart = dp(6)
            })

            addView(inputRow)
        }
    }

    private fun askAi(question: String, screencap: Bitmap?) {
        val userBubble = buildAiBubble("user", question)
        chatHistoryLayout.addView(userBubble)

        val aiBubble = buildAiBubble("assistant", "正在思索中...")
        chatHistoryLayout.addView(aiBubble)
        val tvAiContent = aiBubble.findViewWithTag<TextView>("content")

        chatScrollView.post { chatScrollView.fullScroll(ScrollView.FOCUS_DOWN) }

        val userMsg = DeepSeekClient.ChatMessage("user", question, screencap)
        chatMessages.add(userMsg)

        val streamingText = StringBuilder()
        DeepSeekClient.askDeepSeek(
            config = profile.aiChat,
            conversationHistory = chatMessages,
            userQuestion = question,
            screencap = screencap,
            callback = object : DeepSeekClient.ChatCallback {
                override fun onChunk(chunkText: String) {
                    streamingText.append(chunkText)
                    tvAiContent?.text = streamingText.toString()
                    chatScrollView.post { chatScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }

                override fun onComplete(fullText: String) {
                    tvAiContent?.text = fullText
                    chatMessages.add(DeepSeekClient.ChatMessage("assistant", fullText))
                    chatScrollView.post { chatScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }

                override fun onError(errorMsg: String) {
                    tvAiContent?.text = "❌ $errorMsg"
                }
            }
        )
    }

    private fun buildAiBubble(role: String, content: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val isUser = (role == "user")
            gravity = if (isUser) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }

            val bubble = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply {
                    if (isUser) {
                        setColor(Color.parseColor("#0284C7"))
                        cornerRadius = dp(10).toFloat()
                    } else {
                        setColor(Color.parseColor("#FFFFFF"))
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), Color.parseColor("#CBD5E1"))
                    }
                }
            }

            val tvSender = TextView(context).apply {
                text = if (isUser) "玩家 (Player)" else "🤖 DeepSeek 战略智囊"
                setTextColor(if (isUser) Color.parseColor("#BAE6FD") else Color.parseColor("#0284C7"))
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
            }
            bubble.addView(tvSender)

            val tvText = TextView(context).apply {
                tag = "content"
                text = content
                setTextColor(if (isUser) Color.WHITE else Color.parseColor("#1E293B"))
                textSize = 13f
                setLineSpacing(dp(2).toFloat(), 1.1f)
            }
            bubble.addView(tvText, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            })

            addView(bubble)
        }
    }

    // =========================================================================
    // 模板管理弹窗与标签显隐定制 (套用、显隐定制、另存为)
    // =========================================================================

    private fun showTemplateManagementDialog() {
        val templates = repo.listAllTemplates()
        val options = mutableListOf<String>()
        options.add("❌ 隐藏本次副屏伴侣")
        options.add("⚙️ 自定义底部显示标签 (显隐定制)...")
        options.add("💾 将当前配置另存为新模板...")
        options.addAll(templates.map { "📋 套用模板：${it.name}" })

        AlertDialog.Builder(context)
            .setTitle("副屏战术伴侣 · 模板与标签配置")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        com.gameconsole.launcher3ds.companion.overlay.LauncherCompanionOverlayManager.hide()
                    }
                    1 -> showTabConfigDialog()
                    2 -> showSaveAsTemplateDialog()
                    else -> {
                        val targetTemplate = templates[which - 3]
                        profile = repo.applyTemplateToGame(targetTemplate.id, slot)
                        tvTemplateCapsule.text = "🏷️ ${profile.name.take(8)} ▾"
                        refreshBottomTabBar()
                        switchTab(profile.defaultTab)
                        Toast.makeText(context, "已套用模板：${targetTemplate.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showTabConfigDialog() {
        val allTabs = CompanionTab.values()
        val tabNames = allTabs.map { "${it.icon} ${it.title}" }.toTypedArray()
        val currentEnabled = profile.getEffectiveTabs()
        val checkedItems = BooleanArray(allTabs.size) { idx ->
            currentEnabled.contains(allTabs[idx])
        }

        AlertDialog.Builder(context)
            .setTitle("⚙️ 底部功能标签显隐定制")
            .setMultiChoiceItems(tabNames, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("保存生效") { _, _ ->
                val selectedTabs = mutableListOf<CompanionTab>()
                for (i in allTabs.indices) {
                    if (checkedItems[i]) {
                        selectedTabs.add(allTabs[i])
                    }
                }
                if (selectedTabs.isEmpty()) {
                    Toast.makeText(context, "至少需勾选保留 1 个标签", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                profile.enabledTabs = selectedTabs
                repo.saveGameProfile(profile)
                refreshBottomTabBar()
                switchTab(profile.getEffectiveTabs().first())
                Toast.makeText(context, "已更新底部标签！当前展示 ${selectedTabs.size} 个标签", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSaveAsTemplateDialog() {
        val input = EditText(context).apply {
            hint = "输入模板名称 (如: 卡普空格斗6键)"
        }
        AlertDialog.Builder(context)
            .setTitle("另存为新模板")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    val tpl = repo.saveAsTemplate(profile, name)
                    Toast.makeText(context, "已保存新模板：${tpl.name}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun vibrateQuick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(20, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(20)
            }
        } catch (_: Exception) {}
    }

    private fun dp(v: Int): Int = (v * density).toInt()
}
