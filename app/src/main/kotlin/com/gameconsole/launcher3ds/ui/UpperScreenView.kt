package com.gameconsole.launcher3ds.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.os.BatteryManager
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.gameconsole.launcher3ds.audio.SoundEffectEngine
import com.gameconsole.launcher3ds.model.BadgeCatalog
import com.gameconsole.launcher3ds.model.FriendProfile
import com.gameconsole.launcher3ds.model.GameCategory
import com.gameconsole.launcher3ds.model.GameCategoryClassifier
import com.gameconsole.launcher3ds.model.GameNoteData
import com.gameconsole.launcher3ds.model.GridSlotItem
import com.gameconsole.launcher3ds.model.NotificationItem
import com.gameconsole.launcher3ds.model.PlatformType
import com.gameconsole.launcher3ds.model.SystemAppletType
import com.gameconsole.launcher3ds.model.Theme3DS
import com.gameconsole.launcher3ds.model.ThemeCatalog
import com.gameconsole.launcher3ds.model.UpperDisplayMode
import com.gameconsole.launcher3ds.model.VideoFilterType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * 🖥️ Nintendo 3DS 上屏 (Immersive Upper 3D Stage View)
 * 完整支持加载外部 `3DS/media/<游戏名称>/` 下的三大媒体资源：
 * 1. `boxFront.png`：左侧微倾 3D 实体游戏包装盒 + 全屏动态色彩氛围映射
 * 2. `video.mp4`：右侧 3DS 瓷白圆角预览屏内嵌 60fps 硬件解码实机视频动态循环预览
 * 3. `logo.png`：前景高视差悬浮透明游戏官方 Logo 徽标
 */
class UpperScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var currentTheme: Theme3DS = ThemeCatalog.ALL_THEMES.first()
        set(value) {
            field = value
            invalidate()
        }

    var scanProgressPercent: Int = -1
        set(value) {
            field = value
            invalidate()
        }

    var scanStatusText: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var isVideoFullscreen: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                videoTextureView.invalidateOutline()
                invalidate()
            }
        }

    fun toggleVideoFullscreen(): Boolean {
        val item = selectedSlot
        if (!isVideoFullscreen) {
            if (item == null || item.videoPath.isBlank() || !isStageApplet(activeApplet)) {
                return false
            }
            isVideoFullscreen = true
            SoundEffectEngine.playSelectApplet()
            videoTextureView.invalidateOutline()
            invalidate()
            return true
        } else {
            isVideoFullscreen = false
            SoundEffectEngine.playBackCancel()
            videoTextureView.invalidateOutline()
            invalidate()
            return true
        }
    }

    fun exitVideoFullscreen(): Boolean {
        if (isVideoFullscreen) {
            isVideoFullscreen = false
            SoundEffectEngine.playBackCancel()
            videoTextureView.invalidateOutline()
            invalidate()
            return true
        }
        return false
    }

    private var isUpdatingSlot: Boolean = false

    fun updateSelectedSlot(
        newSlot: GridSlotItem?,
        keepPreviewPlaying: Boolean = isVideoAudioEnabled,
        keepAudioEnabled: Boolean = isVideoAudioEnabled
    ) {
        val changed = selectedSlot?.id != newSlot?.id ||
            selectedSlot?.videoPath != newSlot?.videoPath ||
            selectedSlot?.bannerAnimationPath != newSlot?.bannerAnimationPath ||
            selectedSlot?.boxArtPath != newSlot?.boxArtPath ||
            selectedSlot?.iconCachePath != newSlot?.iconCachePath ||
            selectedSlot?.assetIconName != newSlot?.assetIconName

        isUpdatingSlot = true
        try {
            // 立即停止上一款游戏的视频播放并隐藏画面，彻底杜绝切游戏时旧视频重播与闪烁
            stopAndReleaseVideoPlayer()
            videoTextureView.alpha = 0f
            isVideoFrameReady = false

            selectedSlot = newSlot
            val shouldPlayPreview = if (keepAudioEnabled) true else keepPreviewPlaying
            isPreviewVideoPlaying = shouldPlayPreview
            isVideoAudioEnabled = keepAudioEnabled
            applyVideoAudioVolume()

            bannerPopScale = 0.84f
            currentVideoNativeWidth = 0
            currentVideoNativeHeight = 0
            if (newSlot?.videoPath.isNullOrBlank() && newSlot?.bannerAnimationPath.isNullOrBlank()) {
                isVideoFullscreen = false
            }
        } finally {
            isUpdatingSlot = false
        }

        if (changed || newSlot != null) {
            syncVideoPlayback()
        }
        invalidate()
    }

    var selectedSlot: GridSlotItem? = null
        set(value) {
            val changed = field?.id != value?.id ||
                field?.videoPath != value?.videoPath ||
                field?.bannerAnimationPath != value?.bannerAnimationPath ||
                field?.boxArtPath != value?.boxArtPath ||
                field?.iconCachePath != value?.iconCachePath ||
                field?.assetIconName != value?.assetIconName
            field = value
            if (isUpdatingSlot) {
                return
            }
            if (changed) {
                updateSelectedSlot(
                    newSlot = value,
                    keepPreviewPlaying = isVideoAudioEnabled,
                    keepAudioEnabled = isVideoAudioEnabled
                )
            } else {
                invalidate()
            }
        }

    var activeApplet: SystemAppletType = SystemAppletType.NONE
        set(value) {
            if (field != value) {
                field = value
                if (!isUpdatingSlot) {
                    syncVideoPlayback()
                    invalidate()
                }
            }
        }

    var friendProfile: FriendProfile = FriendProfile()
    var activeGameNote: GameNoteData? = null
    var selectedNotification: NotificationItem? = null
    var folderChildrenCount: Int = 0
    var playCoins: Int = 128
    var stepsCount: Int = 3650
    var onTopBadgeClickListener: (() -> Unit)? = null
    var onVideoAudioToggleRequested: (() -> Unit)? = null

    /**
     * 是否正在播放实机演示视频 (video.mp4)。
     * 当游戏配置了 3DS 上屏 3D 横幅动效 (bannerAnimationPath) 时：
     * - 默认状态 (false)：在按播放预览视频之前，循环播放 3DS 原版 3D 横幅动效 (如马里奥3D大陆_横幅渲染.mp4 / .cgfx 渲染动效)；
     * - 切换状态 (true)：当用户点击「播放预览视频」按键或点击视频窗口后，切换播放实机演示视频 (video.mp4)。
     */
    var isPreviewVideoPlaying: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (!isUpdatingSlot) {
                    syncVideoPlayback()
                    invalidate()
                }
            }
        }

    /**
     * 是否在上屏展示官方透明 Logo (logo.png)，默认关闭
     */
    /**
     * 是否在上屏展示 3D 游戏包装盒 (Boxart 3D Case)
     */
    var showUpperBoxart: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * 是否在上屏展示官方透明 Logo (logo.png)
     */
    var showUpperLogo: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * 当前上屏主舞台显示方案
     */
    var upperDisplayMode: UpperDisplayMode = UpperDisplayMode.LOGO_ONLY
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * 是否开启上屏预览视频原声（开启时：暂停 3DS 背景音乐，放出视频原声；关闭时：视频静音，恢复 3DS 背景音乐）
     */
    var isVideoAudioEnabled: Boolean = false
        set(value) {
            field = value
            applyVideoAudioVolume()
            invalidate()
        }

    /**
     * 当前上屏预览视频与画面滤镜
     */
    var videoFilter: VideoFilterType = VideoFilterType.NONE
        set(value) {
            if (field != value) {
                field = value
                applyVideoFilterEffect()
                invalidate()
            }
        }

    private val videoFilterLayerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var activeVideoColorFilter: ColorMatrixColorFilter? = null

    private var launchSplashProgress: Float = 0f
    private var launchSplashTitle: String = ""

    private var animPhase: Float = 0f
    private var bannerPopScale: Float = 1f

    // 硬件陀螺仪 / 重力感应器 3D 裸眼视差联动
    private var isSensorRegistered = false
    private var targetSensorTiltX: Float = 0f
    private var targetSensorTiltY: Float = 0f
    private var smoothSensorTiltX: Float = 0f
    private var smoothSensorTiltY: Float = 0f

    // 3D 包装盒 360° 自由旋转把玩与手柄右摇杆控制
    var boxRotAngleY: Float = 0f       // 0° .. 360° 水平旋转
    var boxRotAngleX: Float = 0f       // -28° .. +28° 俯仰倾斜
    private var boxRotVelocityY: Float = 0f
    private var isInteractingWithBox: Boolean = false
    private var lastBoxTouchX: Float = 0f
    private var lastBoxTouchY: Float = 0f
    private var lastStickMoveTime: Long = 0L

    private val imageExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val bitmapCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val pendingBitmapKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // 硬件加速内嵌实机视频预览组件 (用于播放 media/<游戏名>/video.mp4)
    private val videoTextureView: TextureView
    private var videoSurface: Surface? = null
    private var videoPlayer: MediaPlayer? = null
    private var currentVideoPath: String = ""
    private var isVideoFrameReady: Boolean = false
    private var isHostResumed: Boolean = true
    private var currentVideoNativeWidth: Int = 0
    private var currentVideoNativeHeight: Int = 0
    private val currentScreenAbsRect = RectF()
    private val currentBoxAbsRect = RectF()
    private val topAudioToggleRect = RectF()
    private var baseTextureW: Int = 960
    private var baseTextureH: Int = 580
    private val videoMatrix = Matrix()

    /**
     * 如果是 3DS 或 NDS 的游戏视频，智能判断是横版还是竖版（上下双屏合成录像）。
     * 若为竖版视频，在预览区域仅显示上半部分（上屏实机游玩画面），消除黑边并防止画面被挤扁。
     */
    private fun applyVideoTransformIfNeeded() {
        val item = selectedSlot
        val norm = item?.getEffectivePlatform()?.uppercase()?.trim() ?: ""
        val is3dsOrNds = (item?.type == PlatformType.N3DS || item?.type == PlatformType.NDS ||
                norm == "3DS" || norm == "N3DS" || norm == "NDS" || norm == "DS")
        val isVertical = is3dsOrNds && currentVideoNativeWidth > 0 && currentVideoNativeHeight > currentVideoNativeWidth

        videoMatrix.reset()
        if (isVertical) {
            // Y 轴放大 2 倍，轴心设在顶部 (0, 0)，使得原本位于 [0, 50%] 的上半屏画面撑满视图 [0, 100%]
            // 下半部分（触摸屏）落到视图可视区域之外，被 TextureView outline 自然裁剪
            videoMatrix.setScale(1f, 2f, 0f, 0f)
        }
        videoTextureView.setTransform(videoMatrix)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val apolloRegularTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val apolloMediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val boldTypeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val strokePath = Path()
    private val clipPath = Path()
    private val bubblePath = Path()
    private val rect = RectF()
    private val dateFormat = SimpleDateFormat("MM/dd (E) HH:mm", Locale.CHINA)

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false

        videoTextureView = TextureView(context).apply {
            isOpaque = false
            alpha = 0f
            pivotX = 0f
            pivotY = 0f
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val isFullBleed = isVideoFullscreen && (currentScreenAbsRect.width() >= width && currentScreenAbsRect.height() >= height)
                    val r = if (isFullBleed) 0f else if (isVideoFullscreen) 10f else 14f
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            clipToOutline = true
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    videoSurface?.release()
                    videoSurface = Surface(surface)
                    syncVideoPlayback()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    stopAndReleaseVideoPlayer()
                    videoSurface?.release()
                    videoSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
        addView(videoTextureView, LayoutParams(baseTextureW, baseTextureH))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerSensorListener()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unregisterSensorListener()
    }

    fun onHostResume() {
        isHostResumed = true
        registerSensorListener()
        syncVideoPlayback()
    }

    fun onHostPause() {
        isHostResumed = false
        unregisterSensorListener()
        stopAndReleaseVideoPlayer()
    }

    private fun registerSensorListener() {
        if (isSensorRegistered) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val rotVec = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val gravity = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val target = rotVec ?: gravity ?: accel
        if (target != null) {
            sm.registerListener(sensorListener, target, SensorManager.SENSOR_DELAY_GAME)
            isSensorRegistered = true
        }
    }

    private fun unregisterSensorListener() {
        if (!isSensorRegistered) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sm?.unregisterListener(sensorListener)
        isSensorRegistered = false
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val rotMat = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                    val orient = FloatArray(3)
                    SensorManager.getOrientation(rotMat, orient)
                    targetSensorTiltX = (orient[2] * 1.6f).coerceIn(-1.5f, 1.5f)
                    targetSensorTiltY = (orient[1] * 1.6f).coerceIn(-1.5f, 1.5f)
                }
                Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                    val ax = event.values[0] / 9.8f
                    val ay = event.values[1] / 9.8f
                    targetSensorTiltX = (-ax * 1.6f).coerceIn(-1.5f, 1.5f)
                    targetSensorTiltY = ((ay - 0.5f) * 1.6f).coerceIn(-1.5f, 1.5f)
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun rotateBoxartByStick(dx: Float, dy: Float) {
        boxRotAngleY = (boxRotAngleY + dx) % 360f
        boxRotAngleX = (boxRotAngleX + dy).coerceIn(-28f, 28f)
        lastStickMoveTime = System.currentTimeMillis()
        invalidate()
    }

    /**
     * 解析各游戏平台的实机预览视频原生比例：
     * - 3DS 与 NDS 保持 3DS 独有的 5:3 (1.66f) 显示基准
     * - 其它所有机种（GBA、PSP、FC、SFC、MD、PS1、街机等）精准适配其原生电视/屏幕比例（如 4:3、3:2、16:9）
     * - 若视频文件已加载且带有实际像素宽高，优先采用视频自身的真实原生画面比例
     */
    private fun resolvePlatformVideoAspectRatio(platformId: String, videoW: Int, videoH: Int): Float {
        val norm = platformId.uppercase().trim()
        val isVertical = videoW > 0 && videoH > 0 && videoH > videoW

        // 针对 3DS / NDS 双屏机种，判断预览视频是横版还是竖版（上下双屏录像）
        if (norm == "3DS" || norm == "N3DS") {
            if (isVertical) {
                // 竖版上下双屏合成视频：只显示上半部分（上屏），画面比例按上屏真实宽高比计算
                val topAspect = (2f * videoW.toFloat()) / videoH.toFloat()
                return topAspect.coerceIn(1.40f, 1.85f)
            }
            // 横版视频：若有真实视频尺寸优先采用，否则使用 3DS 原生上屏 5:3 (1.6667f)
            if (videoW > 0 && videoH > 0) {
                val ratio = videoW.toFloat() / videoH.toFloat()
                if (ratio in 1.20f..2.00f) return ratio
            }
            return 5f / 3f // 1.6667f
        }

        if (norm == "NDS" || norm == "DS") {
            if (isVertical) {
                // NDS 竖版上下双屏合成视频：只显示上半部分（上屏），画面比例按上屏真实宽高比计算
                val topAspect = (2f * videoW.toFloat()) / videoH.toFloat()
                return topAspect.coerceIn(1.20f, 1.50f)
            }
            // 横版视频：若有真实视频尺寸优先采用，否则使用 NDS 原生上屏 4:3 (1.3333f)
            if (videoW > 0 && videoH > 0) {
                val ratio = videoW.toFloat() / videoH.toFloat()
                if (ratio in 1.15f..1.85f) return ratio
            }
            return 4f / 3f // 1.3333f
        }

        // 若视频文件已就绪且带有真实原始像素尺寸，优先采用视频自身的真实原生画面比例
        if (videoW > 0 && videoH > 0) {
            val ratio = videoW.toFloat() / videoH.toFloat()
            if (ratio in 0.70f..1.85f) {
                return ratio
            }
        }

        // 针对除 3DS/NDS 以外的各主流模拟器主机的原生荧幕硬件标准比例：
        return when (norm) {
            "PSP" -> 16f / 9f                  // 1.7778f (PSP 宽屏 480x272)
            "GBA" -> 3f / 2f                   // 1.50f (GBA 原生 240x160)
            "GB", "GBC" -> 10f / 9f            // 1.111f (Game Boy 原生 160x144)
            "WS", "WSC" -> 1.61f               // (WonderSwan 原生 224x144)
            "NGP", "NGPC" -> 1.06f             // Neo Geo Pocket
            "FC", "NES",                       // 任天堂红白机 4:3
            "SFC", "SNES",                     // 超级任天堂 4:3
            "MD", "GENESIS", "MEGADRIVE",      // 世嘉五代 4:3
            "PS1", "PSX", "PLAYSTATION",       // 索尼初代 PS 4:3
            "N64",                             // N64 4:3
            "DC", "DREAMCAST",                 // DC 4:3
            "SS", "SATURN",                    // 土星 4:3
            "ARCADE", "FBA", "MAME", "NEOGEO", // 经典街机标准 4:3
            "PCE", "TG16" -> 4f / 3f           // PC Engine 4:3
            else -> 4f / 3f                    // 其他经典复古机种通用 4:3 电视标准
        }
    }

    private fun isStageApplet(applet: SystemAppletType): Boolean {
        return applet == SystemAppletType.NONE ||
            applet == SystemAppletType.HOME_SETTINGS ||
            applet == SystemAppletType.VIDEO_FILTER_SETTINGS ||
            applet == SystemAppletType.GAME_LIBRARY ||
            applet == SystemAppletType.GAME_DESCRIPTION
    }

    private fun applyVideoFilterEffect() {
        val cm: ColorMatrix? = when (videoFilter) {
            VideoFilterType.NONE -> null
            VideoFilterType.N3DS_LCD -> ColorMatrix(
                floatArrayOf(
                    1.04f, 0.02f, 0.00f, 0f, 4f,
                    0.01f, 1.03f, 0.01f, 0f, 3f,
                    0.00f, 0.02f, 0.96f, 0f, -2f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            VideoFilterType.CRT_SCANLINE -> ColorMatrix(
                floatArrayOf(
                    1.18f, 0.04f, 0.00f, 0f, -10f,
                    0.02f, 1.15f, 0.02f, 0f, -10f,
                    0.00f, 0.04f, 1.24f, 0f, -4f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            VideoFilterType.VIVID_HDR -> {
                val sat = ColorMatrix().apply { setSaturation(1.42f) }
                val contrast = ColorMatrix(
                    floatArrayOf(
                        1.16f, 0f, 0f, 0f, -14f,
                        0f, 1.16f, 0f, 0f, -14f,
                        0f, 0f, 1.18f, 0f, -12f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                sat.postConcat(contrast)
                sat
            }
            VideoFilterType.WARM_RETRO -> ColorMatrix(
                floatArrayOf(
                    1.12f, 0.14f, 0.02f, 0f, 12f,
                    0.05f, 0.98f, 0.04f, 0f, 4f,
                    0.00f, 0.06f, 0.76f, 0f, -14f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            VideoFilterType.COOL_CYBER -> ColorMatrix(
                floatArrayOf(
                    0.86f, 0.04f, 0.14f, 0f, -8f,
                    0.02f, 1.06f, 0.12f, 0f, 2f,
                    0.08f, 0.12f, 1.28f, 0f, 16f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            VideoFilterType.GAMEBOY_DMG -> ColorMatrix(
                floatArrayOf(
                    0.26f, 0.52f, 0.10f, 0f, 18f,
                    0.34f, 0.68f, 0.14f, 0f, 28f,
                    0.08f, 0.18f, 0.04f, 0f, 8f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            VideoFilterType.NOIR_MONO -> ColorMatrix(
                floatArrayOf(
                    0.38f, 0.72f, 0.14f, 0f, -22f,
                    0.38f, 0.72f, 0.14f, 0f, -22f,
                    0.38f, 0.72f, 0.14f, 0f, -22f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }

        val cf = cm?.let { ColorMatrixColorFilter(it) }
        activeVideoColorFilter = cf
        videoFilterLayerPaint.colorFilter = cf
        if (cf == null) {
            videoTextureView.setLayerType(View.LAYER_TYPE_NONE, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                videoTextureView.setRenderEffect(null)
            }
        } else {
            videoTextureView.setLayerType(View.LAYER_TYPE_HARDWARE, videoFilterLayerPaint)
            videoTextureView.setLayerPaint(videoFilterLayerPaint)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                videoTextureView.setRenderEffect(RenderEffect.createColorFilterEffect(cf))
            }
        }
    }

    private fun syncVideoPlayback() {
        val item = selectedSlot
        val targetVideo = if (isHostResumed && isStageApplet(activeApplet) && item != null && item.type != PlatformType.EMPTY) {
            when {
                item.bannerAnimationPath.isNotBlank() -> {
                    if (isPreviewVideoPlaying && item.videoPath.isNotBlank()) {
                        item.videoPath
                    } else {
                        item.bannerAnimationPath
                    }
                }
                else -> item.videoPath.takeIf { it.isNotBlank() } ?: ""
            }
        } else {
            ""
        }

        if (targetVideo.isBlank()) {
            stopAndReleaseVideoPlayer()
            videoTextureView.alpha = 0f
            return
        }

        val surf = videoSurface
        if (surf == null || !surf.isValid) {
            return
        }

        if (targetVideo == currentVideoPath && videoPlayer != null) {
            try {
                applyVideoAudioVolume()
                if (videoPlayer?.isPlaying == false) {
                    videoPlayer?.start()
                }
                if (isVideoFrameReady) {
                    videoTextureView.alpha = 1f
                }
                return
            } catch (_: Exception) {
            }
        }

        stopAndReleaseVideoPlayer()
        currentVideoPath = targetVideo
        isVideoFrameReady = false
        videoTextureView.alpha = 0f

        try {
            val mp = MediaPlayer()
            videoPlayer = mp
            mp.setSurface(surf)

            var isDataSourceSet = false
            // 1. 如果是本地存在的真实文件（Windows 或安卓挂载路径）
            if (!targetVideo.startsWith("asset://") && !targetVideo.startsWith("assets/")) {
                val f = File(targetVideo)
                if (f.exists() && f.length() > 0) {
                    try {
                        val mmr = MediaMetadataRetriever()
                        mmr.setDataSource(f.absolutePath)
                        val wStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        val hStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        val rotStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        val w = wStr?.toIntOrNull() ?: 0
                        val h = hStr?.toIntOrNull() ?: 0
                        val rot = rotStr?.toIntOrNull() ?: 0
                        if (w > 0 && h > 0) {
                            if (rot == 90 || rot == 270) {
                                currentVideoNativeWidth = h
                                currentVideoNativeHeight = w
                            } else {
                                currentVideoNativeWidth = w
                                currentVideoNativeHeight = h
                            }
                        }
                        mmr.release()
                    } catch (_: Exception) {
                    }
                    try {
                        mp.setDataSource(f.absolutePath)
                        isDataSourceSet = true
                    } catch (e: Exception) {
                        android.util.Log.w("UpperScreenView", "Local file setDataSource failed: $targetVideo", e)
                    }
                }
            }

            // 2. 如果本地文件不存在（如在安卓真机上没有 I:\ 驱动器路径），或显式指定了 asset://，自动使用内置 Asset 资源回退
            if (!isDataSourceSet) {
                val assetPath = when {
                    targetVideo.startsWith("asset://") -> targetVideo.removePrefix("asset://")
                    targetVideo.startsWith("assets/") -> targetVideo.removePrefix("assets/")
                    item != null && item.bannerAnimationPath.isNotBlank() && (!isPreviewVideoPlaying || item.videoPath.isBlank()) -> "3ds_art/mario_3d_land_banner.mp4"
                    item != null && item.bannerAnimationPath.isNotBlank() && isPreviewVideoPlaying -> "3ds_art/mario_3d_land_video.mp4"
                    targetVideo.contains("banner", ignoreCase = true) || targetVideo.contains("横幅", ignoreCase = true) -> "3ds_art/mario_3d_land_banner.mp4"
                    targetVideo.contains("video", ignoreCase = true) -> "3ds_art/mario_3d_land_video.mp4"
                    else -> "3ds_art/mario_3d_land_banner.mp4"
                }
                try {
                    val afd = context.assets.openFd(assetPath)
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                    isDataSourceSet = true
                } catch (e: Exception) {
                    android.util.Log.e("UpperScreenView", "Asset openFd failed for $assetPath", e)
                }
            }

            if (!isDataSourceSet) {
                stopAndReleaseVideoPlayer()
                return
            }

            mp.isLooping = true
            val isShowingBanner = item != null && item.bannerAnimationPath.isNotBlank() && (!isPreviewVideoPlaying || item.videoPath.isBlank())
            val vol = if (!isShowingBanner && isVideoAudioEnabled) 1f else 0f
            mp.setVolume(vol, vol)
            mp.setOnPreparedListener { player ->
                if (player.videoWidth > 0 && player.videoHeight > 0) {
                    currentVideoNativeWidth = player.videoWidth
                    currentVideoNativeHeight = player.videoHeight
                }
                if (currentVideoPath == targetVideo && isHostResumed && isStageApplet(activeApplet)) {
                    try {
                        val isBannerNow = item != null && item.bannerAnimationPath.isNotBlank() && (!isPreviewVideoPlaying || item.videoPath.isBlank())
                        val v = if (!isBannerNow && isVideoAudioEnabled) 1f else 0f
                        player.setVolume(v, v)
                        player.start()
                        isVideoFrameReady = true
                        videoTextureView.alpha = 1f
                        invalidate()
                    } catch (_: Exception) {
                    }
                }
            }
            mp.setOnVideoSizeChangedListener { _, width, height ->
                if (width > 0 && height > 0) {
                    currentVideoNativeWidth = width
                    currentVideoNativeHeight = height
                    invalidate()
                }
            }
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    isVideoFrameReady = true
                    videoTextureView.alpha = 1f
                    invalidate()
                }
                false
            }
            mp.setOnErrorListener { _, _, _ ->
                isVideoFrameReady = false
                videoTextureView.alpha = 0f
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            stopAndReleaseVideoPlayer()
        }
    }

    private fun applyVideoAudioVolume() {
        try {
            val item = selectedSlot
            val isShowingBanner = item != null && item.bannerAnimationPath.isNotBlank() && (!isPreviewVideoPlaying || item.videoPath.isBlank())
            val vol = if (!isShowingBanner && isVideoAudioEnabled) 1f else 0f
            videoPlayer?.setVolume(vol, vol)
        } catch (_: Exception) {
        }
    }

    private fun stopAndReleaseVideoPlayer() {
        currentVideoPath = ""
        isVideoFrameReady = false
        currentVideoNativeWidth = 0
        currentVideoNativeHeight = 0
        videoMatrix.reset()
        videoTextureView.setTransform(videoMatrix)
        videoTextureView.alpha = 0f
        try {
            videoPlayer?.setSurface(null)
            videoPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            videoPlayer?.release()
        } catch (_: Exception) {
        }
        videoPlayer = null
    }

    fun triggerLaunchSplash(gameTitle: String, onFinished: () -> Unit) {
        launchSplashTitle = gameTitle
        launchSplashProgress = 0.02f
        val startTime = System.currentTimeMillis()
        val duration = 920L
        post(object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                launchSplashProgress = (elapsed.toFloat() / duration).coerceIn(0.02f, 1f)
                invalidate()
                if (elapsed < duration) {
                    postDelayed(this, 16)
                } else {
                    launchSplashProgress = 0f
                    invalidate()
                    onFinished()
                }
            }
        })
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        animPhase += 0.032f
        if (bannerPopScale < 1f) {
            bannerPopScale = (bannerPopScale + (1f - bannerPopScale) * 0.22f).coerceAtMost(1f)
        }

        // 1. 绘制全屏无界 3DS 上屏天空背景与 3D 视差舞台（从 y=0 一直铺满到 y=h，顶部零色差）
        drawUpperStageBackground(canvas, w, h)

        val topBarH = (h * 0.088f).coerceIn(36f, 64f)

        // 2. 绘制上屏中央核心舞台（位于 videoTextureView 底层或同步更新 videoTextureView 的坐标）
        when (activeApplet) {
            SystemAppletType.GAME_NOTES -> {
                videoTextureView.alpha = 0f
                drawGameNotesUpperStage(canvas, w, h, topBarH)
            }
            SystemAppletType.FRIEND_LIST -> {
                videoTextureView.alpha = 0f
                drawFriendCardUpperStage(canvas, w, h, topBarH)
            }
            SystemAppletType.NOTIFICATIONS -> {
                videoTextureView.alpha = 0f
                drawNotificationUpperStage(canvas, w, h, topBarH)
            }
            SystemAppletType.MIIVERSE -> {
                videoTextureView.alpha = 0f
                drawMiiverseUpperStage(canvas, w, h, topBarH)
            }
            SystemAppletType.BROWSER -> {
                videoTextureView.alpha = 0f
                drawBrowserUpperStage(canvas, w, h, topBarH)
            }
            else -> drawGameBanner3DStage(canvas, w, h, topBarH)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        // 先渲染子视图（即硬件解码播放 video.mp4 的圆角 videoTextureView）
        super.dispatchDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val topBarH = (h * 0.088f).coerceIn(36f, 64f)

        // 在视频画面之上叠加：实时画面滤镜纹理、玻璃镜面光泽、LIVE VIDEO 徽标以及前景 3D 视差悬浮的透明游戏官方 Logo (logo.png)
        val item = selectedSlot
        if (isStageApplet(activeApplet) && item != null && item.type != PlatformType.EMPTY && !currentScreenAbsRect.isEmpty) {
            // 1) 视频屏实时画面滤镜叠加层（扫描线 / 3DS 液晶点阵 / 复古色调暗角等）+ 顶部镜面玻璃光泽
            canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(currentScreenAbsRect, 13f, 13f, Path.Direction.CW)
            canvas.clipPath(clipPath)

            drawVideoFilterOverlay(canvas, currentScreenAbsRect)

            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                0f, currentScreenAbsRect.top, 0f, currentScreenAbsRect.top + currentScreenAbsRect.height() * 0.24f,
                Color.argb(40, 255, 255, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(
                currentScreenAbsRect.left,
                currentScreenAbsRect.top,
                currentScreenAbsRect.right,
                currentScreenAbsRect.top + currentScreenAbsRect.height() * 0.24f,
                paint
            )
            paint.shader = null
            canvas.restore()

            // 2) 若当前正在播放实机预览视频 (video.mp4) 或 3D 横幅动效，或开启了滤镜，在预览窗右上角绘制微标
            if ((isVideoFrameReady && videoTextureView.alpha > 0.5f) || videoFilter != VideoFilterType.NONE) {
                val isShowingBanner = item.bannerAnimationPath.isNotBlank() && (!isPreviewVideoPlaying || item.videoPath.isBlank())
                val baseTag = when {
                    isShowingBanner && isVideoFullscreen -> "✨ 3DS 原版 3D 横幅 · 全屏"
                    isShowingBanner -> "✨ 3DS 原版 3D 横幅"
                    isVideoFullscreen && isVideoAudioEnabled -> "🔊 全屏原声"
                    isVideoFullscreen -> "⛶ 等比全屏"
                    isVideoAudioEnabled -> "🔊 视频原声中"
                    else -> "▶ 实机视频"
                }
                val tagText = if (videoFilter == VideoFilterType.NONE) {
                    baseTag
                } else {
                    "$baseTag · ${videoFilter.displayName}"
                }
                statusTextPaint.textSize = (currentScreenAbsRect.height() * 0.045f).coerceIn(11f, 16f)
                val tw = statusTextPaint.measureText(tagText) + 20f
                val th = statusTextPaint.textSize * 1.55f
                val tagRect = RectF(
                    currentScreenAbsRect.right - tw - 12f,
                    currentScreenAbsRect.top + 12f,
                    currentScreenAbsRect.right - 12f,
                    currentScreenAbsRect.top + 12f + th
                )
                paint.style = Paint.Style.FILL
                paint.color = when {
                    isShowingBanner -> Color.argb(220, 2, 132, 199)
                    isVideoAudioEnabled -> Color.argb(210, 230, 0, 18)
                    videoFilter != VideoFilterType.NONE -> Color.argb(195, Color.red(videoFilter.accentColor), Color.green(videoFilter.accentColor), Color.blue(videoFilter.accentColor))
                    else -> Color.argb(145, 12, 20, 32)
                }
                canvas.drawRoundRect(tagRect, th / 2f, th / 2f, paint)
                statusTextPaint.color = Color.WHITE
                statusTextPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(
                    tagText,
                    tagRect.centerX(),
                    tagRect.centerY() - (statusTextPaint.descent() + statusTextPaint.ascent()) / 2f,
                    statusTextPaint
                )

                if (isVideoFullscreen) {
                    val exitHint = "◀ 双击或按 B 退出全屏"
                    statusTextPaint.textSize = (currentScreenAbsRect.height() * 0.040f).coerceIn(11f, 15f)
                    val eTw = statusTextPaint.measureText(exitHint) + 20f
                    val eTh = statusTextPaint.textSize * 1.55f
                    val exitRect = RectF(
                        currentScreenAbsRect.left + 12f,
                        currentScreenAbsRect.top + 12f,
                        currentScreenAbsRect.left + 12f + eTw,
                        currentScreenAbsRect.top + 12f + eTh
                    )
                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb(175, 15, 23, 42)
                    canvas.drawRoundRect(exitRect, eTh / 2f, eTh / 2f, paint)

                    statusTextPaint.color = Color.WHITE
                    statusTextPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(
                        exitHint,
                        exitRect.centerX(),
                        exitRect.centerY() - (statusTextPaint.descent() + statusTextPaint.ascent()) / 2f,
                        statusTextPaint
                    )
                }
            }

            // 3) 前景 3D 视差悬浮官方透明 Logo (仅在开启卡盒且开启Logo的“双重豪华模式”下在卡盒上叠加)
            val shouldDrawOverlayLogo = showUpperBoxart && showUpperLogo && upperDisplayMode == UpperDisplayMode.DUAL_HYBRID
            val logoBmp = if (shouldDrawOverlayLogo) resolveSlotLogo(item) else null
            if (logoBmp != null && !currentBoxAbsRect.isEmpty) {
                val maxLogoW = currentBoxAbsRect.width() * 0.92f
                val maxLogoH = currentBoxAbsRect.height() * 0.36f
                val aspect = logoBmp.width.toFloat().coerceAtLeast(1f) / logoBmp.height.toFloat().coerceAtLeast(1f)
                var drawW = maxLogoW
                var drawH = drawW / aspect
                if (drawH > maxLogoH) {
                    drawH = maxLogoH
                    drawW = drawH * aspect
                }
                val parallaxY = sin(animPhase * 1.25f + 0.45f) * 5f
                val logoCx = currentBoxAbsRect.right - drawW * 0.30f
                val logoCy = currentBoxAbsRect.bottom - drawH * 0.32f + parallaxY
                val logoRect = RectF(logoCx - drawW / 2f, logoCy - drawH / 2f, logoCx + drawW / 2f, logoCy + drawH / 2f)

                // Logo 底部柔光衬托背影，确保任何色系的透明 Logo 均清晰立体
                paint.style = Paint.Style.FILL
                paint.shader = RadialGradient(
                    logoRect.centerX(),
                    logoRect.centerY(),
                    maxOf(drawW, drawH) * 0.58f,
                    Color.argb(120, 8, 16, 28),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
                canvas.drawOval(
                    RectF(logoRect.left - 12f, logoRect.top - 8f, logoRect.right + 12f, logoRect.bottom + 10f),
                    paint
                )
                paint.shader = null

                canvas.drawBitmap(logoBmp, null, logoRect, bitmapPaint)
            }
        }

        // 3. 绘制完全无背景条的 3DS 实机顶部状态元素（直接融入天空背景）
        drawImmersiveTopStatusBar(canvas, w, topBarH)

        // 4. 启动游戏时的红黑立体聚拢过场
        if (launchSplashProgress > 0f) {
            drawNintendo3dsLaunchSplash(canvas, w, h, launchSplashProgress)
        }

        postInvalidateDelayed(16)
    }

    /**
     * 在上屏预览视频窗口上实时绘制 8 款定制视觉滤镜纹理与色彩氛围层
     */
    private fun drawVideoFilterOverlay(canvas: Canvas, r: RectF) {
        when (videoFilter) {
            VideoFilterType.NONE -> return

            VideoFilterType.N3DS_LCD -> {
                // 原版 3DS 240p 液晶像素点阵：微暖液晶背光 + 横纵精细像素网格
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(18, 255, 248, 225)
                canvas.drawRect(r, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                paint.color = Color.argb(48, 12, 18, 26)
                val step = 4.5f
                var y = r.top
                while (y < r.bottom) {
                    canvas.drawLine(r.left, y, r.right, y, paint)
                    y += step
                }
                paint.color = Color.argb(34, 12, 18, 26)
                var x = r.left
                while (x < r.right) {
                    canvas.drawLine(x, r.top, x, r.bottom, paint)
                    x += step
                }
                paint.style = Paint.Style.FILL
            }

            VideoFilterType.CRT_SCANLINE -> {
                // 复古街机 CRT 显像管：明显水平扫描线 + 滚动刷新光带 + 四角显像管暗角
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.4f
                paint.color = Color.argb(78, 0, 0, 0)
                val step = 3.8f
                var y = r.top
                while (y < r.bottom) {
                    canvas.drawLine(r.left, y, r.right, y, paint)
                    y += step
                }
                paint.style = Paint.Style.FILL

                // 缓慢下移的 CRT 电子束扫描亮带
                val scanPos = (animPhase * 0.45f) % 1f
                val barY = r.top + r.height() * scanPos
                paint.shader = LinearGradient(
                    0f, barY - 28f, 0f, barY + 28f,
                    intArrayOf(Color.TRANSPARENT, Color.argb(32, 160, 230, 255), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(r.left, barY - 28f, r.right, barY + 28f, paint)
                paint.shader = null

                // CRT 四角暗角
                drawScreenVignette(canvas, r, Color.argb(115, 4, 8, 18))
            }

            VideoFilterType.VIVID_HDR -> {
                // 鲜艳绚丽 HDR：通透高光柔化与边缘微光增艳
                paint.style = Paint.Style.FILL
                paint.shader = LinearGradient(
                    r.left, r.top, r.right, r.bottom,
                    Color.argb(36, 255, 70, 130),
                    Color.argb(36, 0, 210, 255),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(r, paint)
                paint.shader = null
            }

            VideoFilterType.WARM_RETRO -> {
                // 怀旧暖调胶片：温暖琥珀柔光滤镜 + 复古暗角
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(56, 245, 166, 35)
                canvas.drawRect(r, paint)
                drawScreenVignette(canvas, r, Color.argb(95, 48, 24, 6))
            }

            VideoFilterType.COOL_CYBER -> {
                // 清冷赛博：冰蓝与霓虹紫双色对角光影 + 细微未来扫描线
                paint.style = Paint.Style.FILL
                paint.shader = LinearGradient(
                    r.left, r.top, r.right, r.bottom,
                    Color.argb(54, 0, 229, 255),
                    Color.argb(58, 147, 51, 234),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(r, paint)
                paint.shader = null

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                paint.color = Color.argb(32, 0, 240, 255)
                var y = r.top
                while (y < r.bottom) {
                    canvas.drawLine(r.left, y, r.right, y, paint)
                    y += 6f
                }
                paint.style = Paint.Style.FILL
            }

            VideoFilterType.GAMEBOY_DMG -> {
                // 初代 Game Boy 经典橄榄绿屏色调 + 点阵网格
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(88, 139, 172, 15)
                canvas.drawRect(r, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                paint.color = Color.argb(58, 15, 56, 15)
                val step = 4.2f
                var y = r.top
                while (y < r.bottom) {
                    canvas.drawLine(r.left, y, r.right, y, paint)
                    y += step
                }
                var x = r.left
                while (x < r.right) {
                    canvas.drawLine(x, r.top, x, r.bottom, paint)
                    x += step
                }
                paint.style = Paint.Style.FILL
                drawScreenVignette(canvas, r, Color.argb(90, 15, 56, 15))
            }

            VideoFilterType.NOIR_MONO -> {
                // 经典黑白胶片：冷银盐暗调与电影胶片暗角
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(46, 15, 23, 42)
                canvas.drawRect(r, paint)
                drawScreenVignette(canvas, r, Color.argb(125, 0, 0, 0))
            }
        }
    }

    private fun drawScreenVignette(canvas: Canvas, r: RectF, edgeColor: Int) {
        val radius = maxOf(r.width(), r.height()) * 0.72f
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            r.centerX(),
            r.centerY(),
            radius,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, edgeColor),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(r, paint)
        paint.shader = null
    }

    private fun drawUpperStageBackground(canvas: Canvas, w: Float, h: Float) {
        val item = selectedSlot
        val accentColor = if (item != null && item.type != PlatformType.EMPTY) {
            item.primaryColor
        } else {
            currentTheme.cursorMainColor
        }
        val horizonY = h * 0.65f

        // 1. 融合游戏主色调的动态 3DS 天空背景渐变
        val skyTopBlended = blendColor(currentTheme.upperSkyTop, accentColor, 0.14f)
        val skyMidBlended = blendColor(currentTheme.upperSkyBottom, accentColor, 0.08f)
        paint.shader = LinearGradient(
            0f, 0f, 0f, horizonY,
            skyTopBlended,
            skyMidBlended,
            Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        canvas.drawRect(0f, 0f, w, horizonY + 2f, paint)
        paint.shader = null

        // 2. 当前游戏画面的超柔和全景环境光映射（优先使用预览图或实体盒封面 boxFront.png 渲染氛围）
        val atmosphereBmp = if (item != null && item.type != PlatformType.EMPTY) {
            resolveSlotPreview(item) ?: resolveSlotBoxart(item)
        } else {
            null
        }
        if (atmosphereBmp != null) {
            canvas.save()
            bitmapPaint.alpha = 32
            val bgRect = RectF(-w * 0.08f, -h * 0.08f, w * 1.08f, horizonY + h * 0.08f)
            canvas.drawBitmap(atmosphereBmp, null, bgRect, bitmapPaint)
            bitmapPaint.alpha = 255

            // 柔和天空雾化遮罩，确保背景细腻通透、不喧宾夺主
            paint.shader = LinearGradient(
                0f, 0f, 0f, horizonY + h * 0.08f,
                intArrayOf(
                    Color.argb(195, Color.red(skyTopBlended), Color.green(skyTopBlended), Color.blue(skyTopBlended)),
                    Color.argb(145, Color.red(skyMidBlended), Color.green(skyMidBlended), Color.blue(skyMidBlended)),
                    Color.argb(245, Color.red(currentTheme.upperSkyBottom), Color.green(currentTheme.upperSkyBottom), Color.blue(currentTheme.upperSkyBottom))
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, horizonY + h * 0.08f, paint)
            paint.shader = null
            canvas.restore()
        }

        // 3. 舞台中央大型径向环境辉光 (Ambient Stage Aura)
        val auraRadius = w * 0.52f
        paint.shader = RadialGradient(
            w * 0.5f,
            h * 0.38f,
            auraRadius,
            intArrayOf(
                Color.argb(72, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                Color.argb(28, Color.red(currentTheme.cursorGlowColor), Color.green(currentTheme.cursorGlowColor), Color.blue(currentTheme.cursorGlowColor)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // 4. 3DS 标志性斜向体积光束 (Volumetric Sunbeams / God Rays)
        for (ray in 0 until 4) {
            val rayDrift = sin(animPhase * 0.45f + ray * 1.2f) * (w * 0.03f)
            val topX = w * (0.14f + ray * 0.24f) + rayDrift
            val rayW = w * (0.07f + (ray % 2) * 0.035f)
            val skew = w * 0.14f
            bubblePath.reset()
            bubblePath.moveTo(topX, 0f)
            bubblePath.lineTo(topX + rayW, 0f)
            bubblePath.lineTo(topX + rayW + skew, horizonY)
            bubblePath.lineTo(topX + skew * 0.75f, horizonY)
            bubblePath.close()

            paint.shader = LinearGradient(
                0f, 0f, 0f, horizonY,
                Color.argb(36, 255, 255, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(bubblePath, paint)
            paint.shader = null
        }

        // 5. 远景柔美 3DS 律动声波云带 (Sweeping Horizon Ribbon Waves)
        for (wave in 0..1) {
            val waveY = horizonY - h * (0.06f + wave * 0.055f)
            val amp = h * (0.032f + wave * 0.012f)
            val phase = animPhase * (0.6f + wave * 0.25f)
            bubblePath.reset()
            bubblePath.moveTo(0f, horizonY)
            bubblePath.lineTo(0f, waveY + sin(phase) * amp)
            bubblePath.cubicTo(
                w * 0.33f, waveY - cos(phase) * amp * 1.3f,
                w * 0.66f, waveY + sin(phase + 1.4f) * amp * 1.3f,
                w, waveY - cos(phase + 0.8f) * amp
            )
            bubblePath.lineTo(w, horizonY)
            bubblePath.close()

            paint.shader = LinearGradient(
                0f, waveY - amp, 0f, horizonY,
                Color.argb(if (wave == 0) 48 else 32, 255, 255, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(bubblePath, paint)
            paint.shader = null
        }

        // 6. 底部 3D 透视棋盘格舞台地面 (3D Perspective Checkered Stage Floor)
        val floorTopColor = blendColor(currentTheme.upperSkyBottom, accentColor, 0.06f)
        val floorBottomColor = blendColor(currentTheme.upperSkyTop, currentTheme.upperGridColor, 0.22f)
        paint.shader = LinearGradient(
            0f, horizonY, 0f, h,
            floorTopColor,
            floorBottomColor,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, horizonY, w, h, paint)
        paint.shader = null

        val centerX = w * 0.5f
        val drift = (animPhase * 10f) % 48f
        val driftNorm = drift / 48f
        val numRows = 6
        val rowYs = FloatArray(numRows + 1) { i ->
            val r = i.toFloat() / numRows
            horizonY + (h - horizonY) * (r * r)
        }

        // 绘制交替半透明 3D 透视地砖块，营造强烈 3D 纵深感
        paint.style = Paint.Style.FILL
        for (r in 0 until numRows) {
            val y1 = rowYs[r]
            val y2 = rowYs[r + 1]
            val t1 = (r.toFloat() / numRows) * (r.toFloat() / numRows)
            val t2 = ((r + 1).toFloat() / numRows) * ((r + 1).toFloat() / numRows)
            for (c in -7..6) {
                if ((r + c + 100) % 2 == 0) {
                    val cShift = c + driftNorm
                    val topL = centerX + cShift * (w * 0.048f)
                    val topR = centerX + (cShift + 1f) * (w * 0.048f)
                    val botL = centerX + cShift * (w * 0.145f)
                    val botR = centerX + (cShift + 1f) * (w * 0.145f)

                    val x1L = topL + (botL - topL) * t1
                    val x1R = topR + (botR - topR) * t1
                    val x2L = topL + (botL - topL) * t2
                    val x2R = topR + (botR - topR) * t2

                    bubblePath.reset()
                    bubblePath.moveTo(x1L, y1)
                    bubblePath.lineTo(x1R, y1)
                    bubblePath.lineTo(x2R, y2)
                    bubblePath.lineTo(x2L, y2)
                    bubblePath.close()

                    paint.color = Color.WHITE
                    paint.alpha = (18 + r * 5).coerceIn(12, 48)
                    canvas.drawPath(bubblePath, paint)
                }
            }
        }

        // 透视网格线
        paint.color = currentTheme.upperGridColor
        paint.alpha = 72
        paint.strokeWidth = 1.5f
        paint.style = Paint.Style.STROKE
        for (i in 0..numRows) {
            canvas.drawLine(0f, rowYs[i], w, rowYs[i], paint)
        }
        for (i in -8..8) {
            val cShift = i + driftNorm
            val topX = centerX + cShift * (w * 0.048f)
            val bottomX = centerX + cShift * (w * 0.145f)
            canvas.drawLine(topX, horizonY, bottomX, h, paint)
        }

        // 地平线发光分界光带 (Horizon Light Seam)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, horizonY - 12f, 0f, horizonY + 14f,
            intArrayOf(Color.TRANSPARENT, Color.argb(135, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, horizonY - 12f, w, horizonY + 14f, paint)
        paint.shader = null

        // 7. 两侧立体 3D 等轴测悬浮魔方与音符星尘（分布在左右景深层，丰富画面且不遮挡中央舞台）
        val wingPositionsX = floatArrayOf(0.07f, 0.14f, 0.21f, 0.79f, 0.86f, 0.93f)
        val wingPositionsY = floatArrayOf(0.24f, 0.46f, 0.29f, 0.28f, 0.48f, 0.23f)
        for (i in wingPositionsX.indices) {
            val seed = i * 1.45f
            val cx = w * wingPositionsX[i] + sin(animPhase * 0.75f + seed) * 14f
            val cy = h * wingPositionsY[i] + cos(animPhase * 0.65f + seed) * 16f
            val cubeR = (14f + (i % 3) * 6f)
            drawIsometric3dCube(canvas, cx, cy, cubeR, accentColor, currentTheme.cursorGlowColor)
        }

        // 漂浮的 3DS 音乐符号与星光粒子
        val symbols = arrayOf("♪", "✦", "♫", "✦")
        textPaint.textAlign = Paint.Align.CENTER
        for (i in 0 until 6) {
            val sx = w * (if (i < 3) 0.10f + i * 0.07f else 0.76f + (i - 3) * 0.07f)
            val sy = h * (0.18f + (i % 3) * 0.15f) + sin(animPhase * 1.1f + i) * 10f
            textPaint.textSize = 15f + (i % 2) * 5f
            textPaint.color = blendColor(accentColor, Color.WHITE, 0.45f)
            textPaint.alpha = 115
            canvas.drawText(symbols[i % symbols.size], sx, sy, textPaint)
        }
        textPaint.alpha = 255
        paint.alpha = 255
    }

    /**
     * 绘制真正的三面受光 3D 等轴测悬浮方块 (Top / Left / Right Facets)
     */
    private fun drawIsometric3dCube(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        accentColor: Int,
        glowColor: Int
    ) {
        val dx = r * 0.866f
        val dy = r * 0.5f

        // 顶面 (最亮)
        bubblePath.reset()
        bubblePath.moveTo(cx, cy - r)
        bubblePath.lineTo(cx + dx, cy - dy)
        bubblePath.lineTo(cx, cy)
        bubblePath.lineTo(cx - dx, cy - dy)
        bubblePath.close()
        paint.style = Paint.Style.FILL
        paint.color = blendColor(glowColor, Color.WHITE, 0.68f)
        paint.alpha = 110
        canvas.drawPath(bubblePath, paint)

        // 左面 (中调)
        bubblePath.reset()
        bubblePath.moveTo(cx - dx, cy - dy)
        bubblePath.lineTo(cx, cy)
        bubblePath.lineTo(cx, cy + r)
        bubblePath.lineTo(cx - dx, cy + dy)
        bubblePath.close()
        paint.color = blendColor(accentColor, Color.WHITE, 0.35f)
        paint.alpha = 95
        canvas.drawPath(bubblePath, paint)

        // 右面 (暗调立体侧影)
        bubblePath.reset()
        bubblePath.moveTo(cx, cy)
        bubblePath.lineTo(cx + dx, cy - dy)
        bubblePath.lineTo(cx + dx, cy + dy)
        bubblePath.lineTo(cx, cy + r)
        bubblePath.close()
        paint.color = blendColor(accentColor, Color.BLACK, 0.15f)
        paint.alpha = 85
        canvas.drawPath(bubblePath, paint)

        // 棱线高光
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = Color.WHITE
        paint.alpha = 135
        canvas.drawLine(cx - dx, cy - dy, cx, cy, paint)
        canvas.drawLine(cx + dx, cy - dy, cx, cy, paint)
        canvas.drawLine(cx, cy, cx, cy + r, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val item = selectedSlot
            val hasVideoOrBanner = item != null && (item.videoPath.isNotBlank() || item.bannerAnimationPath.isNotBlank())
            if (hasVideoOrBanner && isStageApplet(activeApplet)) {
                return toggleVideoFullscreen()
            }
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (isVideoFullscreen) {
                onVideoAudioToggleRequested?.invoke()
                return true
            }
            val x = e.x
            val y = e.y
            if (topAudioToggleRect.contains(x, y) ||
                (!currentScreenAbsRect.isEmpty && currentScreenAbsRect.contains(x, y))
            ) {
                onVideoAudioToggleRequested?.invoke()
                return true
            }
            return false
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        if (isVideoFullscreen) {
            gestureDetector.onTouchEvent(event)
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hitBox = if (!currentBoxAbsRect.isEmpty) {
                    val exp = RectF(currentBoxAbsRect.left - 30f, currentBoxAbsRect.top - 30f, currentBoxAbsRect.right + 30f, currentBoxAbsRect.bottom + 30f)
                    exp.contains(x, y)
                } else false
                if (hitBox) {
                    isInteractingWithBox = true
                    lastBoxTouchX = x
                    lastBoxTouchY = y
                    boxRotVelocityY = 0f
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isInteractingWithBox) {
                    val dx = x - lastBoxTouchX
                    val dy = y - lastBoxTouchY
                    boxRotAngleY = (boxRotAngleY + dx * 0.75f) % 360f
                    boxRotAngleX = (boxRotAngleX - dy * 0.40f).coerceIn(-28f, 28f)
                    boxRotVelocityY = dx * 0.55f
                    lastBoxTouchX = x
                    lastBoxTouchY = y
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isInteractingWithBox) {
                    isInteractingWithBox = false
                    invalidate()
                    return true
                }
            }
        }

        if (gestureDetector.onTouchEvent(event)) {
            return true
        }
        return super.onTouchEvent(event)
    }

    /**
     * 100% 无底色条的沉浸式 3DS 顶栏：
     * - 左上角：视频原声 / 背景音乐一键切换胶囊按钮（点击暂停 BGM 并放出视频原声，再点恢复 BGM）
     * - 计步器与游戏币半透明磨砂胶囊
     * - 右上角：日期时间 + 3DS 四格立体电池
     */
    private fun drawImmersiveTopStatusBar(canvas: Canvas, w: Float, barH: Float) {
        val cy = barH * 0.56f
        val pillH = barH * 0.68f

        // 上屏顶部左侧已根据用户要求隐藏，不再显示任何状态胶囊与播放按键
        topAudioToggleRect.setEmpty()

        val isDarkSky = isColorDark(currentTheme.upperSkyTop)
        val primaryHudColor = if (isDarkSky) Color.WHITE else Color.parseColor("#1E293B")
        val pillBgAlphaColor = if (isDarkSky) Color.argb(55, 0, 0, 0) else Color.argb(85, 255, 255, 255)

        // 3. 右上角：日期时间 + 3DS 四格立体电池（包裹在轻盈半透明胶囊中，电池正极朝右不遮挡文字）
        val (batteryPct, isCharging) = readBatteryState()
        val battW = barH * 1.15f
        val battH = barH * 0.50f
        statusTextPaint.textSize = barH * 0.37f
        val dateStr = dateFormat.format(Date()) + (if (isCharging) " ⚡" else "")
        val dateTextW = statusTextPaint.measureText(dateStr)
        val rightPillW = dateTextW + battW + 46f
        val rightPillRect = RectF(w - 14f - rightPillW, cy - pillH / 2f, w - 14f, cy + pillH / 2f)
        paint.color = pillBgAlphaColor
        canvas.drawRoundRect(rightPillRect, pillH / 2f, pillH / 2f, paint)

        val battRight = rightPillRect.right - 16f
        val battLeft = battRight - battW

        // 电池正极小帽（置于右侧）
        rect.set(battRight, cy - battH * 0.26f, battRight + 3.5f, cy + battH * 0.26f)
        paint.color = primaryHudColor
        canvas.drawRoundRect(rect, 1.5f, 1.5f, paint)

        // 电池外框
        rect.set(battLeft, cy - battH / 2f, battRight, cy + battH / 2f)
        paint.color = Color.argb(65, 0, 0, 0)
        canvas.drawRoundRect(rect, 3.5f, 3.5f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f
        paint.color = primaryHudColor
        canvas.drawRoundRect(rect, 3.5f, 3.5f, paint)
        paint.style = Paint.Style.FILL

        val bars = when {
            batteryPct >= 75 -> 4
            batteryPct >= 50 -> 3
            batteryPct >= 25 -> 2
            else -> 1
        }
        val barColor = when {
            isCharging -> Color.parseColor("#FFA000")
            bars == 1 -> Color.parseColor("#FF3B30")
            else -> Color.parseColor("#00C8FF")
        }
        val innerPad = 2.5f
        val segGap = 1.8f
        val segW = (battW - innerPad * 2f - segGap * 3f) / 4f
        paint.color = barColor
        for (b in 0 until bars) {
            val sx = battLeft + innerPad + b * (segW + segGap)
            canvas.drawRect(sx, rect.top + innerPad, sx + segW, rect.bottom - innerPad, paint)
        }

        statusTextPaint.color = primaryHudColor
        statusTextPaint.textSize = barH * 0.37f
        statusTextPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(dateStr, battLeft - 12f, cy - (statusTextPaint.descent() + statusTextPaint.ascent()) / 2f, statusTextPaint)
    }

    /**
     * 绘制上屏中央的大尺寸 3D 展示舞台：
     * - 左侧：微倾 3D 实体游戏包装盒 (`boxFront.png`)
     * - 右侧：大幅放大的影院级圆角实机游戏视频/画面预览屏 (`video.mp4`)
     * - 舞台底部：无边界全息光环底座 + 与上屏融为一体的半透明磨砂水晶标题铭牌
     */
    private fun drawGameBanner3DStage(canvas: Canvas, w: Float, h: Float, topBarH: Float) {
        val item = selectedSlot
        val stageCenterX = w * 0.5f
        val stageCenterY = topBarH + (h - topBarH) * 0.415f
        // 1. 硬件陀螺仪/重力感应器 3D 裸眼视差联动平滑计算
        smoothSensorTiltX += (targetSensorTiltX - smoothSensorTiltX) * 0.15f
        smoothSensorTiltY += (targetSensorTiltY - smoothSensorTiltY) * 0.15f

        val idleTiltX = sin(animPhase * 0.85f) * 3.2f
        val idleTiltY = sin(animPhase * 1.25f) * 2.2f
        val totalTiltX = smoothSensorTiltX * 24f + idleTiltX
        val totalTiltY = smoothSensorTiltY * 18f + idleTiltY

        val floatY = totalTiltY * 0.35f + sin(animPhase * 1.25f) * 4f
        val tiltX = totalTiltX

        // 3D 包装盒惯性自转与回正
        if (!isInteractingWithBox && System.currentTimeMillis() - lastStickMoveTime > 350L) {
            val currentNorm = ((boxRotAngleY % 360f) + 540f) % 360f - 180f
            val diff = 0f - currentNorm
            boxRotVelocityY += diff * 0.08f
            boxRotVelocityY *= 0.76f
            boxRotAngleY += boxRotVelocityY
            boxRotAngleX += (0f - boxRotAngleX) * 0.12f
            if (kotlin.math.abs(diff) < 0.15f && kotlin.math.abs(boxRotVelocityY) < 0.15f) {
                boxRotAngleY = 0f
                boxRotVelocityY = 0f
                boxRotAngleX = 0f
            }
        }

        val accentColor = if (item != null && item.type != PlatformType.EMPTY) {
            item.primaryColor
        } else {
            currentTheme.cursorMainColor
        }

        // 中心环绕声波圆环动画
        for (ring in 1..3) {
            val ringPhase = (animPhase * 0.65f + ring * 0.33f) % 1f
            val ringRadius = (minOf(w, h) * 0.22f) + ringPhase * (minOf(w, h) * 0.28f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.2f * (1f - ringPhase)
            paint.color = blendColor(currentTheme.cursorMainColor, accentColor, 0.35f)
            paint.alpha = ((1f - ringPhase) * 90).toInt().coerceIn(0, 255)
            canvas.drawCircle(stageCenterX, stageCenterY + floatY, ringRadius, paint)
        }
        paint.style = Paint.Style.FILL
        paint.alpha = 255

        if (item == null || item.type == PlatformType.EMPTY) {
            currentScreenAbsRect.setEmpty()
            currentBoxAbsRect.setEmpty()
            videoTextureView.alpha = 0f

            val cubeSize = minOf(w, h) * 0.16f
            canvas.save()
            canvas.translate(stageCenterX, stageCenterY + floatY)
            canvas.rotate(sin(animPhase * 0.7f) * 12f)
            rect.set(-cubeSize, -cubeSize, cubeSize, cubeSize)
            paint.color = Color.argb(75, 255, 255, 255)
            canvas.drawRoundRect(rect, 20f, 20f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = currentTheme.cursorMainColor
            canvas.drawRoundRect(rect, 20f, 20f, paint)
            paint.style = Paint.Style.FILL

            textPaint.color = currentTheme.textPrimaryColor
            textPaint.textSize = cubeSize * 0.44f
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("3DS", 0f, -(textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
            canvas.restore()

            draw3dsSpeechBubblePlate(
                canvas = canvas,
                w = w,
                h = h,
                stageBottomY = stageCenterY + cubeSize * 1.35f,
                title = "Nintendo 3DS HOME 菜单",
                subtitle = "按 L1/R1 切换游戏分类 · 按 L2/R2 切换顶部系统图标 · 自动挂载 3DS/media",
                platformBadge = "EMPTY SLOT",
                badgeColor = currentTheme.cursorMainColor
            )
            return
        }

        val previewBmp = resolveSlotPreview(item)
        val boxBmp = resolveSlotBoxart(item) ?: resolveSlotIcon(item)
        val logoBmp = if (showUpperLogo) resolveSlotLogo(item) else null
        val hasMediaShowcase = boxBmp != null || previewBmp != null || item.videoPath.isNotBlank() || item.bannerAnimationPath.isNotBlank()

        // 解除原本 460px 的高度上限，将中央舞台与视频窗口大幅放大至上屏可用高度的 68%
        val stageH = ((h - topBarH) * 0.68f).coerceIn(180f, h * 0.72f) * bannerPopScale

        // 绘制 3D 透视地面上的全息渐变能量光环底座
        val pedestalY = stageCenterY + stageH * 0.52f
        val pedRx = stageH * 1.32f
        val pedRy = stageH * 0.13f
        canvas.save()
        canvas.translate(stageCenterX + tiltX * 0.3f, pedestalY)
        canvas.scale(1f, pedRy / pedRx)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            0f, 0f, pedRx,
            intArrayOf(
                Color.argb(55, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                Color.argb(30, 15, 28, 46),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(0f, 0f, pedRx, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.color = blendColor(currentTheme.cursorGlowColor, Color.WHITE, 0.4f)
        paint.alpha = 85
        canvas.drawCircle(0f, 0f, pedRx * 0.78f, paint)
        paint.strokeWidth = 4f
        paint.alpha = 45
        canvas.drawCircle(0f, 0f, pedRx * 0.96f, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        canvas.restore()

        val isGameItem = item.type != PlatformType.EMPTY && item.type != PlatformType.FOLDER && item.type != PlatformType.BADGE
        if (isGameItem && hasMediaShowcase) {
            // 全屏等比播放模式：支持双击全屏等比播放，如果四周不能撑满，那背景色还是保留上屏原本的背景
            val canFullscreen = item.videoPath.isNotBlank() || item.bannerAnimationPath.isNotBlank()
            if (isVideoFullscreen && canFullscreen) {
                val platform = item.getEffectivePlatform()
                val screenAspect = resolvePlatformVideoAspectRatio(
                    platform,
                    currentVideoNativeWidth,
                    currentVideoNativeHeight
                )
                val screenRatio = w / h
                val fullW: Float
                val fullH: Float
                if (screenAspect > screenRatio) {
                    // 宽撑满，上下留空（保留上屏原本的天空与主题背景）
                    fullW = w
                    fullH = (w / screenAspect).coerceAtMost(h)
                } else {
                    // 高撑满，左右留空（保留上屏原本的天空与主题背景）
                    fullH = h
                    fullW = (h * screenAspect).coerceAtMost(w)
                }
                val left = (w - fullW) / 2f
                val top = (h - fullH) / 2f
                currentScreenAbsRect.set(left, top, left + fullW, top + fullH)
                currentBoxAbsRect.setEmpty()

                if (isVideoFrameReady && isStageApplet(activeApplet)) {
                    val baseW = (videoTextureView.width).coerceAtLeast(1).toFloat()
                    val baseH = (videoTextureView.height).coerceAtLeast(1).toFloat()
                    videoTextureView.pivotX = 0f
                    videoTextureView.pivotY = 0f
                    videoTextureView.translationX = currentScreenAbsRect.left
                    videoTextureView.translationY = currentScreenAbsRect.top
                    videoTextureView.scaleX = currentScreenAbsRect.width() / baseW
                    videoTextureView.scaleY = currentScreenAbsRect.height() / baseH
                    videoTextureView.alpha = 1f
                    applyVideoTransformIfNeeded()
                } else {
                    videoTextureView.alpha = 0f
                }
                return
            }

            if (upperDisplayMode == UpperDisplayMode.LOGO_ONLY) {
                // 👑 纯粹 Logo 模式：超大尺寸高透官方 Logo 居中悬浮极简展示，隐藏视频屏与标题飘带
                val logoBmp = resolveSlotLogo(item) ?: resolveSlotBoxart(item) ?: resolveSlotIcon(item)
                val cx = stageCenterX + tiltX * 0.4f
                val floatHeroY = kotlin.math.sin((animPhase + 0.35f) * 1.35f) * 8f
                val cy = stageCenterY + floatHeroY
                val curRotY = (boxRotAngleY * 0.40f + totalTiltX * 0.35f).coerceIn(-25f, 25f)
                val curRotX = (boxRotAngleX * 0.40f + totalTiltY * 0.25f).coerceIn(-20f, 20f)

                videoTextureView.alpha = 0f
                currentScreenAbsRect.setEmpty()

                if (logoBmp != null) {
                    val maxLogoW = (w * 0.72f).coerceIn(420f, w * 0.85f)
                    val maxLogoH = ((h - topBarH) * 0.74f).coerceIn(240f, h * 0.78f) * bannerPopScale
                    val lAspect = (logoBmp.width.toFloat() / logoBmp.height.toFloat().coerceAtLeast(1f)).coerceIn(0.40f, 3.40f)
                    var drawW = maxLogoW
                    var drawH = drawW / lAspect
                    if (drawH > maxLogoH) {
                        drawH = maxLogoH
                        drawW = drawH * lAspect
                    }
                    val heroRect = RectF(cx - drawW / 2f, cy - drawH / 2f, cx + drawW / 2f, cy + drawH / 2f)
                    currentBoxAbsRect.set(heroRect)

                    canvas.save()
                    canvas.rotate(curRotX * 0.35f, cx, cy)

                    // 1) 地面柔和透视椭圆阴影
                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb(65, 8, 14, 24)
                    val shadowY = cy + drawH / 2f + 16f - floatHeroY * 0.3f
                    val shadowW = drawW * 0.75f
                    canvas.drawOval(
                        RectF(cx - shadowW / 2f, shadowY - 8f, cx + shadowW / 2f, shadowY + 8f),
                        paint
                    )

                    // 2) 悬浮 Logo 背底轻柔环境光晕 (搭配游戏重音色，超大范围柔光)
                    paint.shader = RadialGradient(
                        cx,
                        cy,
                        maxOf(drawW, drawH) * 0.70f,
                        intArrayOf(
                            Color.argb(95, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                            Color.argb(35, 15, 23, 42),
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(0f, 0.55f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawOval(
                        RectF(heroRect.left - 32f, heroRect.top - 20f, heroRect.right + 32f, heroRect.bottom + 20f),
                        paint
                    )
                    paint.shader = null

                    if (resolveSlotLogo(item) != null) {
                        // 绘制官方透明游戏 Logo
                        canvas.drawBitmap(logoBmp, null, heroRect, bitmapPaint)

                        // 表面极品流光掠过 (增加主机旗舰展示感)
                        val sheenProgress = ((animPhase * 0.6f) % 2.0f) - 0.5f
                        val sheenX = heroRect.left + heroRect.width() * sheenProgress
                        val sheenW = heroRect.width() * 0.28f
                        paint.shader = LinearGradient(
                            sheenX - sheenW, heroRect.top, sheenX + sheenW, heroRect.bottom,
                            intArrayOf(Color.TRANSPARENT, Color.argb(45, 255, 255, 255), Color.TRANSPARENT),
                            null, Shader.TileMode.CLAMP
                        )
                        canvas.drawRect(heroRect, paint)
                        paint.shader = null
                    } else {
                        // 优雅降级：若该游戏暂无透明 Logo，以大尺寸极简圆角卡片形式精致呈现封面
                        paint.color = Color.WHITE
                        canvas.drawRoundRect(RectF(heroRect.left - 2f, heroRect.top - 2f, heroRect.right + 2f, heroRect.bottom + 2f), 16f, 16f, paint)
                        clipPath.reset()
                        clipPath.addRoundRect(heroRect, 14f, 14f, Path.Direction.CW)
                        canvas.clipPath(clipPath)
                        canvas.drawBitmap(logoBmp, null, heroRect, bitmapPaint)
                    }
                    canvas.restore()
                }
                return
            }

            // 双组件并列 3D 舞台：根据各主机平台与封面真实尺寸比例动态排版
            val plat = item.getEffectivePlatform().uppercase()
            val defaultBoxAspect = when (plat) {
                "3DS" -> 0.88f       // 3DS 经典竖版白盒
                "NDS" -> 0.92f       // NDS 经典正方黑盒
                "PSP" -> 0.58f       // PSP 高挑透明 UMD 盒
                "PS1" -> 1.00f       // PS1 经典 CD 正方形晶莹盒
                "PS2" -> 0.71f       // PS2 标准 DVD 盒
                "GBA" -> 1.38f       // GBA 横版卡盒
                "GBC", "GB" -> 0.90f // GB/GBC 经典竖版方盒
                "FC" -> 0.72f        // FC / NES 经典包装盒
                "SFC" -> 1.40f       // SFC / 超任横版包装盒
                "N64" -> 1.40f       // N64 横版包装盒
                "MD" -> 0.70f        // 世嘉 MD 竖版硬塑盒
                "ARCADE" -> 0.72f    // 街机机台海报
                "DC" -> 1.00f        // 世嘉 DC 正方形光盘盒
                "SS" -> 0.72f        // 世嘉土星竖版盒
                else -> 0.88f
            }

            // 若已有加载的封面图，以真实扫描图的高精度原始像素比例为准！
            val boxAspect = if (boxBmp != null && boxBmp.width > 0 && boxBmp.height > 0) {
                (boxBmp.width.toFloat() / boxBmp.height.toFloat()).coerceIn(0.50f, 1.85f)
            } else {
                defaultBoxAspect
            }

            val platform = item.getEffectivePlatform()
            val screenAspect = resolvePlatformVideoAspectRatio(
                platform,
                currentVideoNativeWidth,
                currentVideoNativeHeight
            )

            val hasLeftComponent = showUpperBoxart || showUpperLogo
            var screenH = stageH * 0.98f
            var screenW = screenH * screenAspect
            var boxH = 0f
            var boxW = 0f
            var gapBetween = 0f

            if (showUpperBoxart) {
                // 3D 卡盒模式 (或双重豪华模式)
                if (boxAspect < 1.05f) {
                    boxH = stageH * 0.84f
                    boxW = boxH * boxAspect
                } else {
                    boxW = (stageH * 0.90f).coerceAtMost(w * 0.36f)
                    boxH = (boxW / boxAspect).coerceAtMost(stageH * 0.80f)
                    boxW = boxH * boxAspect
                }
                gapBetween = stageH * 0.035f
            } else if (showUpperLogo) {
                // 悬浮Logo旗舰模式：左侧以大尺寸高透 Logo 为主体 (若无 Logo 则优雅降级为简约卡盒)
                val heroAspect = if (logoBmp != null) {
                    (logoBmp.width.toFloat() / logoBmp.height.toFloat().coerceAtLeast(1f)).coerceIn(0.50f, 2.80f)
                } else {
                    boxAspect
                }
                if (heroAspect > 1.15f) {
                    boxW = (stageH * 1.05f).coerceAtMost(w * 0.45f)
                    boxH = (boxW / heroAspect).coerceIn(stageH * 0.38f, stageH * 0.78f)
                    boxW = boxH * heroAspect
                } else {
                    boxH = stageH * 0.88f
                    boxW = (boxH * heroAspect).coerceAtMost(w * 0.40f)
                    boxH = boxW / heroAspect
                }
                gapBetween = stageH * 0.045f
            } else {
                // 纯净大屏影院模式：左侧组件隐藏，右侧屏幕自动居中放大
                boxW = 0f
                boxH = 0f
                gapBetween = 0f
                screenH = stageH * 1.02f
                screenW = (screenH * screenAspect).coerceAtMost(w * 0.86f)
                screenH = screenW / screenAspect
            }

            var totalStageW = boxW + gapBetween + screenW
            val maxStageW = w * 0.92f
            if (totalStageW > maxStageW) {
                val scale = maxStageW / totalStageW
                screenH *= scale
                screenW *= scale
                boxH *= scale
                boxW *= scale
                gapBetween *= scale
                totalStageW = maxStageW
            }

            val cx = stageCenterX + tiltX * 0.4f
            val cy = stageCenterY + floatY

            val boxRect: RectF
            val screenRect: RectF

            if (hasLeftComponent && boxW > 0f) {
                val boxCenterX = -totalStageW / 2f + boxW / 2f
                boxRect = RectF(boxCenterX - boxW / 2f, -boxH / 2f, boxCenterX + boxW / 2f, boxH / 2f)
                val screenCenterX = totalStageW / 2f - screenW / 2f
                screenRect = RectF(screenCenterX - screenW / 2f, -screenH / 2f, screenCenterX + screenW / 2f, screenH / 2f)
                currentBoxAbsRect.set(
                    cx + boxRect.left,
                    cy + boxRect.top,
                    cx + boxRect.right,
                    cy + boxRect.bottom
                )
            } else {
                boxRect = RectF()
                screenRect = RectF(-screenW / 2f, -screenH / 2f, screenW / 2f, screenH / 2f)
                currentBoxAbsRect.setEmpty()
            }

            currentScreenAbsRect.set(
                cx + screenRect.left,
                cy + screenRect.top,
                cx + screenRect.right,
                cy + screenRect.bottom
            )

            // 同步更新内嵌硬件加速视频视图 (videoTextureView) 的坐标与缩放
            if (isVideoFrameReady && isStageApplet(activeApplet)) {
                val baseW = (videoTextureView.width).coerceAtLeast(1).toFloat()
                val baseH = (videoTextureView.height).coerceAtLeast(1).toFloat()
                videoTextureView.pivotX = 0f
                videoTextureView.pivotY = 0f
                videoTextureView.translationX = currentScreenAbsRect.left
                videoTextureView.translationY = currentScreenAbsRect.top
                videoTextureView.scaleX = currentScreenAbsRect.width() / baseW
                videoTextureView.scaleY = currentScreenAbsRect.height() / baseH
                videoTextureView.alpha = 1f
                applyVideoTransformIfNeeded()
            } else {
                videoTextureView.alpha = 0f
            }

            canvas.save()
            canvas.translate(cx, cy)

            // 1. 左侧：3D 卡盒或悬浮 Logo
            if (showUpperBoxart && boxBmp != null) {
                canvas.save()

                val curRotY = (boxRotAngleY - 4.0f + totalTiltX * 0.45f)
                val curRotX = (boxRotAngleX + totalTiltY * 0.35f)
                canvas.rotate(curRotX * 0.35f, boxRect.centerX(), boxRect.centerY())

                val radY = Math.toRadians(curRotY.toDouble())
                val cosY = kotlin.math.cos(radY).toFloat()
                val sinY = kotlin.math.sin(radY).toFloat()
                val isFrontFacing = (cosY >= 0f)

                val projRatio = kotlin.math.abs(cosY).coerceIn(0.04f, 1.0f)
                val faceW = (boxW * projRatio).coerceAtLeast(3f)
                val faceRect = RectF(boxRect.centerX() - faceW / 2f, boxRect.top, boxRect.centerX() + faceW / 2f, boxRect.bottom)

                val boxThickness = (boxW * 0.125f).coerceIn(12f, 26f)
                val caseShellColor = when (plat) {
                    "3DS" -> Color.WHITE
                    "NDS" -> Color.parseColor("#23272E")
                    "PSP" -> Color.parseColor("#E2E8F0")
                    "PS1" -> Color.parseColor("#CBD5E1")
                    "GBA" -> Color.parseColor("#4C1D95")
                    "GBC", "GB" -> Color.parseColor("#64748B")
                    "FC" -> Color.parseColor("#991B1B")
                    "SFC" -> Color.parseColor("#1E40AF")
                    "MD" -> Color.parseColor("#1E293B")
                    "ARCADE" -> Color.parseColor("#B45309")
                    else -> Color.WHITE
                }

                // 3D 包装盒地底透视阴影
                paint.color = Color.argb(85, 10, 16, 26)
                canvas.drawOval(
                    RectF(faceRect.left - 8f, faceRect.bottom - 4f, faceRect.right + 8f, faceRect.bottom + 14f),
                    paint
                )

                // 绘制书脊侧面厚度 (Spine Thickness)
                if (kotlin.math.abs(sinY) > 0.05f) {
                    val spineW = (boxThickness * kotlin.math.abs(sinY)).coerceAtLeast(2f)
                    val spineRect = if (sinY > 0f) {
                        RectF(faceRect.right, faceRect.top + 3f, faceRect.right + spineW, faceRect.bottom - 3f)
                    } else {
                        RectF(faceRect.left - spineW, faceRect.top + 3f, faceRect.left, faceRect.bottom - 3f)
                    }
                    paint.color = blendColor(caseShellColor, Color.BLACK, 0.35f)
                    canvas.drawRoundRect(spineRect, 4f, 4f, paint)

                    // 书脊正中锁扣与微型机种标识
                    paint.color = blendColor(caseShellColor, Color.WHITE, 0.20f)
                    val notchH = spineRect.height() * 0.25f
                    canvas.drawRect(spineRect.centerX() - 1.5f, spineRect.centerY() - notchH / 2f, spineRect.centerX() + 1.5f, spineRect.centerY() + notchH / 2f, paint)
                }

                // 绘制包装盒塑封外壳
                paint.color = caseShellColor
                canvas.drawRoundRect(
                    RectF(faceRect.left - 3.5f, faceRect.top - 3.5f, faceRect.right + 3.5f, faceRect.bottom + 3.5f),
                    10f,
                    10f,
                    paint
                )

                clipPath.reset()
                clipPath.addRoundRect(faceRect, 8f, 8f, Path.Direction.CW)
                canvas.clipPath(clipPath)

                if (isFrontFacing) {
                    // 正面：高清官方封面
                    canvas.drawBitmap(boxBmp, null, faceRect, bitmapPaint)
                } else {
                    // 背面：如果有 back 封面图则渲染，否则渲染 3DS 原版卡盒背面说明与截图排版
                    val backBmp = resolveSlotBackCover(item)
                    if (backBmp != null) {
                        canvas.drawBitmap(backBmp, null, faceRect, bitmapPaint)
                    } else {
                        drawAuthenticBoxBack(canvas, faceRect, item, plat)
                    }
                }

                // 表面流光反光扫过 (Holographic Specular Sheen)
                val sheenX = faceRect.left + faceRect.width() * ((sinY + 1f) / 2f)
                val sheenW = faceRect.width() * 0.35f
                paint.shader = LinearGradient(
                    sheenX - sheenW, faceRect.top, sheenX + sheenW, faceRect.bottom,
                    intArrayOf(Color.TRANSPARENT, Color.argb(48, 255, 255, 255), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawRect(faceRect, paint)
                paint.shader = null

                canvas.restore()
            } else if (showUpperLogo && !boxRect.isEmpty) {
                // 1. 左侧：高精度悬浮游戏透明 Logo (Floating Logo Hero)
                canvas.save()
                val curRotY = (boxRotAngleY * 0.45f + totalTiltX * 0.35f).coerceIn(-25f, 25f)
                val curRotX = (boxRotAngleX * 0.45f + totalTiltY * 0.25f).coerceIn(-20f, 20f)
                val floatHeroY = kotlin.math.sin((animPhase + 0.35f) * 1.35f) * 6f
                canvas.rotate(curRotX * 0.35f, boxRect.centerX(), boxRect.centerY())

                val heroCenterX = boxRect.centerX()
                val heroCenterY = boxRect.centerY() + floatHeroY

                val effectiveLogo = logoBmp ?: boxBmp
                if (effectiveLogo != null) {
                    val maxLW = boxRect.width() * 0.95f
                    val maxLH = boxRect.height() * 0.95f
                    val lAspect = effectiveLogo.width.toFloat().coerceAtLeast(1f) / effectiveLogo.height.toFloat().coerceAtLeast(1f)
                    var drawLW = maxLW
                    var drawLH = drawLW / lAspect
                    if (drawLH > maxLH) {
                        drawLH = maxLH
                        drawLW = drawLH * lAspect
                    }
                    val heroDrawRect = RectF(
                        heroCenterX - drawLW / 2f,
                        heroCenterY - drawLH / 2f,
                        heroCenterX + drawLW / 2f,
                        heroCenterY + drawLH / 2f
                    )

                    // 1) 地面柔和透视椭圆阴影（随悬浮高度微调）
                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb(65, 8, 14, 24)
                    val shadowY = boxRect.bottom + 8f - floatHeroY * 0.3f
                    val shadowW = drawLW * 0.75f
                    canvas.drawOval(
                        RectF(heroCenterX - shadowW / 2f, shadowY - 6f, heroCenterX + shadowW / 2f, shadowY + 6f),
                        paint
                    )

                    // 2) 悬浮 Logo 背底轻柔环境光晕 (搭配游戏重音色)
                    paint.shader = RadialGradient(
                        heroCenterX,
                        heroCenterY,
                        maxOf(drawLW, drawLH) * 0.65f,
                        intArrayOf(
                            Color.argb(85, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                            Color.argb(30, 15, 23, 42),
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(0f, 0.55f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawOval(
                        RectF(heroDrawRect.left - 24f, heroDrawRect.top - 16f, heroDrawRect.right + 24f, heroDrawRect.bottom + 16f),
                        paint
                    )
                    paint.shader = null

                    if (logoBmp != null) {
                        // 绘制官方透明游戏 Logo
                        canvas.drawBitmap(logoBmp, null, heroDrawRect, bitmapPaint)

                        // 微妙流光掠过 (增加顶级主机 UI 质感)
                        val sheenProgress = ((animPhase * 0.6f) % 2.0f) - 0.5f
                        val sheenX = heroDrawRect.left + heroDrawRect.width() * sheenProgress
                        val sheenW = heroDrawRect.width() * 0.28f
                        paint.shader = LinearGradient(
                            sheenX - sheenW, heroDrawRect.top, sheenX + sheenW, heroDrawRect.bottom,
                            intArrayOf(Color.TRANSPARENT, Color.argb(35, 255, 255, 255), Color.TRANSPARENT),
                            null, Shader.TileMode.CLAMP
                        )
                        canvas.drawRect(heroDrawRect, paint)
                        paint.shader = null
                    } else if (boxBmp != null) {
                        // 优雅降级：若该游戏暂无提取到透明 Logo，以极简圆角卡片形式精致呈现
                        paint.color = Color.WHITE
                        canvas.drawRoundRect(RectF(heroDrawRect.left - 2f, heroDrawRect.top - 2f, heroDrawRect.right + 2f, heroDrawRect.bottom + 2f), 12f, 12f, paint)
                        clipPath.reset()
                        clipPath.addRoundRect(heroDrawRect, 10f, 10f, Path.Direction.CW)
                        canvas.clipPath(clipPath)
                        canvas.drawBitmap(boxBmp, null, heroDrawRect, bitmapPaint)
                    }
                }
                canvas.restore()
            }

            // 2. 右侧：大幅放大的影院级实机游戏画面/视频预览屏
            val bezelPad = 7f
            val outerScreen = RectF(
                screenRect.left - bezelPad,
                screenRect.top - bezelPad,
                screenRect.right + bezelPad,
                screenRect.bottom + bezelPad
            )
            paint.color = Color.argb(85, 12, 20, 32)
            canvas.drawRoundRect(
                RectF(outerScreen.left + 4f, outerScreen.top + 8f, outerScreen.right + 4f, outerScreen.bottom + 8f),
                22f,
                22f,
                paint
            )
            paint.color = Color.WHITE
            canvas.drawRoundRect(outerScreen, 20f, 20f, paint)

            canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(screenRect, 14f, 14f, Path.Direction.CW)
            canvas.clipPath(clipPath)

            // 当视频尚未首帧就绪或无视频时，绘制静态预览图或艺术封面底幕 + 高清透明 Logo
            bitmapPaint.colorFilter = activeVideoColorFilter
            if (previewBmp != null) {
                val norm = item.getEffectivePlatform().uppercase().trim()
                val is3dsOrNds = (item.type == PlatformType.N3DS || item.type == PlatformType.NDS ||
                        norm == "3DS" || norm == "N3DS" || norm == "NDS" || norm == "DS")
                if (is3dsOrNds && previewBmp.height > previewBmp.width) {
                    // 同样针对 3DS / NDS 竖版截图：只截取并显示上半部分（上屏画面）
                    val srcRect = Rect(0, 0, previewBmp.width, previewBmp.height / 2)
                    canvas.drawBitmap(previewBmp, srcRect, screenRect, bitmapPaint)
                } else {
                    canvas.drawBitmap(previewBmp, null, screenRect, bitmapPaint)
                }
            } else {
                paint.shader = LinearGradient(
                    screenRect.left, screenRect.top, screenRect.right, screenRect.bottom,
                    blendColor(item.primaryColor, Color.BLACK, 0.45f),
                    blendColor(item.secondaryColor, Color.BLACK, 0.72f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(screenRect, paint)
                paint.shader = null

                if (boxBmp != null) {
                    bitmapPaint.alpha = 85
                    canvas.drawBitmap(boxBmp, null, screenRect, bitmapPaint)
                    bitmapPaint.alpha = 255
                }
                if (logoBmp != null) {
                    val maxLW = screenRect.width() * 0.78f
                    val maxLH = screenRect.height() * 0.62f
                    val aspect = logoBmp.width.toFloat().coerceAtLeast(1f) / logoBmp.height.toFloat().coerceAtLeast(1f)
                    var lW = maxLW
                    var lH = lW / aspect
                    if (lH > maxLH) {
                        lH = maxLH
                        lW = lH * aspect
                    }
                    val lRect = RectF(
                        screenRect.centerX() - lW / 2f,
                        screenRect.centerY() - lH / 2f,
                        screenRect.centerX() + lW / 2f,
                        screenRect.centerY() + lH / 2f
                    )
                    canvas.drawBitmap(logoBmp, null, lRect, bitmapPaint)
                }
            }
            bitmapPaint.colorFilter = null

            // 顶部极轻的玻璃光泽（不遮挡任何画面内容）
            paint.shader = LinearGradient(
                0f, screenRect.top, 0f, screenRect.top + screenH * 0.25f,
                Color.argb(42, 255, 255, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(screenRect.left, screenRect.top, screenRect.right, screenRect.top + screenH * 0.25f, paint)
            paint.shader = null
            canvas.restore()

            canvas.restore()
        } else {
            currentScreenAbsRect.setEmpty()
            currentBoxAbsRect.setEmpty()
            videoTextureView.alpha = 0f

            // 文件夹、徽章或普通安卓应用：居中绘制大尺寸立体展示卡
            val cardH = stageH * 0.86f
            val cardW = cardH * 1.45f
            val cx = stageCenterX + tiltX * 0.4f
            val cy = stageCenterY + floatY

            canvas.save()
            canvas.translate(cx, cy)
            val cardRect = RectF(-cardW / 2f, -cardH / 2f, cardW / 2f, cardH / 2f)

            paint.color = Color.argb(85, 0, 0, 0)
            canvas.drawRoundRect(RectF(cardRect.left + 4f, cardRect.top + 7f, cardRect.right + 4f, cardRect.bottom + 7f), 22f, 22f, paint)
            paint.color = Color.WHITE
            canvas.drawRoundRect(RectF(cardRect.left - 5f, cardRect.top - 5f, cardRect.right + 5f, cardRect.bottom + 5f), 22f, 22f, paint)

            paint.shader = LinearGradient(
                cardRect.left, cardRect.top, cardRect.right, cardRect.bottom,
                item.primaryColor,
                blendColor(item.primaryColor, Color.BLACK, 0.35f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(cardRect, 18f, 18f, paint)
            paint.shader = null

            if (boxBmp != null) {
                val iconS = cardH * 0.64f
                val dst = RectF(-iconS / 2f, -iconS / 2f, iconS / 2f, iconS / 2f)
                canvas.drawBitmap(boxBmp, null, dst, bitmapPaint)
            } else {
                drawStylizedCartridgeSymbol(canvas, cardRect, item)
            }
            canvas.restore()
        }

        // 3. 紧贴 3D 舞台正下方的沉浸式半透明磨砂水晶标题带（与上屏透视舞台完美融为一体）
        val catName = GameCategory.fromId(item.categoryId).shortName
        val platDef = com.gameconsole.launcher3ds.model.PlatformCatalog.getById(item.getEffectivePlatform())
        val platTitle = platDef?.shortName ?: "3DS"
        val platformBadge = when (item.type) {
            PlatformType.FOLDER -> "3DS 文件夹 (${folderChildrenCount}项)"
            PlatformType.BADGE -> "桌面徽章"
            PlatformType.ANDROID_APP -> "ANDROID APP · $catName"
            PlatformType.EMPTY -> ""
            else -> "$platTitle · $catName"
        }
        val badgeColor = when (item.type) {
            PlatformType.FOLDER, PlatformType.BADGE, PlatformType.EMPTY -> currentTheme.cursorMainColor
            else -> try { Color.parseColor(platDef?.colorHex) } catch (_: Exception) { Color.parseColor("#E60012") }
        }
        val hours = item.playTimeMinutes / 60
        val mins = item.playTimeMinutes % 60
        val subText = if (item.type == PlatformType.N3DS || item.type == PlatformType.NDS) {
            val subClean = item.subtitle.trim()
            val pubClean = item.publisher.trim()
            val metaPrefix = when {
                subClean.isBlank() -> pubClean
                pubClean.isBlank() || subClean.contains(pubClean, ignoreCase = true) -> subClean
                else -> "$subClean  ·  $pubClean"
            }
            "$metaPrefix  ·  游玩 ${item.playCount} 次 (${hours}小时${mins}分)"
        } else {
            item.subtitle.ifBlank { item.publisher }
        }

        val effectiveBottomY = if (!currentScreenAbsRect.isEmpty) {
            currentScreenAbsRect.bottom + 8f
        } else {
            stageCenterY + stageH * 0.50f
        }

        // 显示 LOGO 的时候不要显示标题
        val isShowingLogo = (upperDisplayMode == UpperDisplayMode.LOGO_ONLY || 
                             upperDisplayMode == UpperDisplayMode.LOGO_HERO || 
                             (!showUpperBoxart && showUpperLogo))
        val hasVisibleLogo = (resolveSlotLogo(item) != null)

        val shouldShowTitlePlate = if (upperDisplayMode == UpperDisplayMode.LOGO_ONLY) {
            false
        } else if (isShowingLogo && hasVisibleLogo) {
            false
        } else {
            true
        }

        if (shouldShowTitlePlate) {
            draw3dsSpeechBubblePlate(
                canvas = canvas,
                w = w,
                h = h,
                stageBottomY = effectiveBottomY,
                title = item.title,
                subtitle = subText,
                platformBadge = platformBadge,
                badgeColor = badgeColor
            )
        }
    }

    /**
     * 与上屏 3D 舞台无缝融合的半透明磨砂水晶标题飘带 (Integrated Frosted-Glass Stage Title Ribbon)：
     * - 两侧向外羽化渐隐的流光翼带，消除生硬白框边界感
     * - 半透明磨砂玻璃渐变底板（透出底部 3D 舞台网格与游戏主题光晕）
     * - 顶部跨界居中镶嵌平台立体胶囊铭牌（NINTENDO 3DS / NINTENDO DS）
     */
    private fun draw3dsSpeechBubblePlate(
        canvas: Canvas,
        w: Float,
        h: Float,
        stageBottomY: Float,
        title: String,
        subtitle: String,
        platformBadge: String,
        badgeColor: Int
    ) {
        val accentColor = selectedSlot?.primaryColor ?: badgeColor
        val plateW = (w * 0.74f).coerceIn(520f, 1380f)
        val plateH = (h * 0.195f).coerceIn(88f, 142f)
        val left = (w - plateW) / 2f
        val top = (stageBottomY + h * 0.018f).coerceAtMost(h - plateH - h * 0.015f)
        val radius = plateH * 0.46f
        rect.set(left, top, left + plateW, top + plateH)

        // 1. 左右向两侧远端柔和羽化消融的流光翼带（让标题区彻底融入整个上屏空间，不再像孤立贴纸）
        val wingW = w * 0.82f
        val wingLeft = (w - wingW) / 2f
        val wingRect = RectF(wingLeft, rect.centerY() - plateH * 0.28f, wingLeft + wingW, rect.centerY() + plateH * 0.28f)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            wingRect.left, 0f, wingRect.right, 0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(65, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                Color.argb(110, 255, 255, 255),
                Color.argb(65, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(wingRect, paint)
        paint.shader = null

        // 2. 柔和的彩色环境背光投影
        paint.color = Color.argb(42, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        canvas.drawRoundRect(
            RectF(left - 4f, top + 3f, left + plateW + 4f, top + plateH + 6f),
            radius + 4f,
            radius + 4f,
            paint
        )

        // 3. 半透明磨砂水晶主体（透出底层 3D 透视舞台网格，与上屏融为一体）
        val isDarkTheme = isColorDark(currentTheme.upperSkyBottom)
        val glassTop = if (isDarkTheme) {
            Color.argb(185, 24, 36, 56)
        } else {
            Color.argb(192, 255, 255, 255)
        }
        val glassBottom = if (isDarkTheme) {
            val tinted = blendColor(Color.parseColor("#101A2B"), accentColor, 0.22f)
            Color.argb(165, Color.red(tinted), Color.green(tinted), Color.blue(tinted))
        } else {
            val tinted = blendColor(Color.parseColor("#E6F0FA"), accentColor, 0.14f)
            Color.argb(158, Color.red(tinted), Color.green(tinted), Color.blue(tinted))
        }
        paint.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            glassTop,
            glassBottom,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null

        // 4. 水晶高光双层描边（外层淡主题色流光 + 内层纯白高光镜面边）
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.6f
        paint.color = Color.argb(235, 255, 255, 255)
        canvas.drawRoundRect(rect, radius, radius, paint)

        paint.strokeWidth = 1.4f
        paint.color = Color.argb(95, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        canvas.drawRoundRect(
            RectF(rect.left - 1.5f, rect.top - 1.5f, rect.right + 1.5f, rect.bottom + 1.5f),
            radius + 1.5f,
            radius + 1.5f,
            paint
        )
        paint.style = Paint.Style.FILL

        // 5. 跨界镶嵌在水晶牌正上沿的 3DS 立体平台徽章（将上方 3D 封面舞台与下方标题紧密锁合）
        if (platformBadge.isNotBlank()) {
            val badgeFontSize = (plateH * 0.19f).coerceIn(14f, 22f)
            textPaint.typeface = apolloMediumTypeface
            textPaint.isFakeBoldText = false
            textPaint.textSize = badgeFontSize
            val bW = textPaint.measureText(platformBadge) + 32f
            val bH = badgeFontSize * 1.62f
            val bRect = RectF(
                rect.centerX() - bW / 2f,
                rect.top - bH * 0.52f,
                rect.centerX() + bW / 2f,
                rect.top + bH * 0.48f
            )
            paint.color = badgeColor
            canvas.drawRoundRect(bRect, bH / 2f, bH / 2f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.8f
            paint.color = Color.WHITE
            canvas.drawRoundRect(bRect, bH / 2f, bH / 2f, paint)
            paint.style = Paint.Style.FILL

            textPaint.color = Color.WHITE
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                platformBadge,
                bRect.centerX(),
                bRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f,
                textPaint
            )
        }

        // 6. 第一行：居中大号游戏主标题（两侧点缀 3DS 菱形星芒）
        val titleColor = if (isDarkTheme) Color.WHITE else Color.parseColor("#162232")
        textPaint.color = titleColor
        textPaint.typeface = apolloMediumTypeface
        textPaint.isFakeBoldText = false
        val titleFontSize = (plateH * 0.40f).coerceIn(28f, 52f)
        textPaint.textSize = titleFontSize
        textPaint.textAlign = Paint.Align.CENTER
        val maxTextW = plateW * 0.90f
        val cleanTitle = ellipsize(title, textPaint, maxTextW - 52f)
        val titleY = rect.top + plateH * 0.48f
        canvas.drawText(
            cleanTitle,
            rect.centerX(),
            titleY,
            textPaint
        )

        // 标题左右两侧精致菱形暗纹点缀
        val halfTitleW = textPaint.measureText(cleanTitle) / 2f
        val diamondSize = titleFontSize * 0.44f
        textPaint.textSize = diamondSize
        textPaint.color = blendColor(accentColor, currentTheme.cursorMainColor, 0.4f)
        textPaint.alpha = 185
        canvas.drawText("◆", rect.centerX() - halfTitleW - 20f, titleY - 2f, textPaint)
        canvas.drawText("◆", rect.centerX() + halfTitleW + 20f, titleY - 2f, textPaint)
        textPaint.alpha = 255

        // 7. 第二行：居中展示发行商与游玩记录（使用 Apollo 优雅细字体）
        val subFontSize = (plateH * 0.22f).coerceIn(16f, 26f)
        textPaint.typeface = apolloRegularTypeface
        textPaint.isFakeBoldText = false
        textPaint.textSize = subFontSize
        textPaint.color = if (isDarkTheme) Color.parseColor("#CBD5E1") else Color.parseColor("#475569")
        textPaint.textAlign = Paint.Align.CENTER
        val subY = rect.top + plateH * 0.81f
        canvas.drawText(ellipsize(subtitle, textPaint, maxTextW), rect.centerX(), subY, textPaint)

        // 恢复默认 bold typeface
        textPaint.typeface = boldTypeface
    }

    private fun drawStylizedCartridgeSymbol(canvas: Canvas, box: RectF, item: GridSlotItem) {
        when (item.type) {
            PlatformType.FOLDER -> {
                textPaint.color = Color.WHITE
                textPaint.textSize = box.height() * 0.36f
                textPaint.textAlign = Paint.Align.CENTER
                val letter = item.folderLetter.ifBlank { item.title.take(1) }
                canvas.drawText(
                    "📂 $letter",
                    box.centerX(),
                    box.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f,
                    textPaint
                )
            }
            PlatformType.BADGE -> {
                val badge = BadgeCatalog.getById(item.badgeId)
                textPaint.color = Color.WHITE
                textPaint.textSize = box.height() * 0.42f
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(
                    badge?.symbol ?: "★",
                    box.centerX(),
                    box.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f,
                    textPaint
                )
            }
            else -> {
                textPaint.color = Color.WHITE
                textPaint.textSize = box.height() * 0.28f
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(
                    item.title.take(4),
                    box.centerX(),
                    box.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f,
                    textPaint
                )
            }
        }
    }

    private fun drawGameNotesUpperStage(canvas: Canvas, w: Float, h: Float, topBarH: Float) {
        val pad = w * 0.08f
        val cardRect = RectF(pad, topBarH + 12f, w - pad, h - 18f)
        paint.color = Color.parseColor("#FFFDF7")
        canvas.drawRoundRect(cardRect, 18f, 18f, paint)

        paint.color = Color.parseColor("#E6DEC8")
        paint.strokeWidth = 1f
        var lineY = cardRect.top + 52f
        while (lineY < cardRect.bottom - 12f) {
            canvas.drawLine(cardRect.left + 16f, lineY, cardRect.right - 16f, lineY, paint)
            lineY += 26f
        }

        val headerRect = RectF(cardRect.left, cardRect.top, cardRect.right, cardRect.top + 42f)
        paint.color = Color.parseColor("#F58220")
        canvas.drawRoundRect(headerRect, 18f, 18f, paint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 17f
        textPaint.textAlign = Paint.Align.LEFT
        val noteTitle = activeGameNote?.gameTitle ?: selectedSlot?.title ?: "全局掌机攻略备忘"
        canvas.drawText("✏️ 游戏笔记 — $noteTitle", headerRect.left + 18f, headerRect.centerY() + 6f, textPaint)

        val memo = activeGameNote?.textMemo.orEmpty()
        if (memo.isNotBlank()) {
            textPaint.color = Color.parseColor("#2B343D")
            textPaint.textSize = 16f
            val lines = memo.lines().take(6)
            for ((idx, line) in lines.withIndex()) {
                canvas.drawText(
                    ellipsize(line, textPaint, cardRect.width() - 36f),
                    cardRect.left + 20f,
                    headerRect.bottom + 30f + idx * 26f,
                    textPaint
                )
            }
        } else {
            textPaint.color = Color.parseColor("#9BA5B0")
            textPaint.textSize = 15f
            canvas.drawText(
                "在下屏手写板绘制地图标记或输入攻略文字，上屏将实时同步展示！",
                cardRect.left + 20f,
                headerRect.bottom + 32f,
                textPaint
            )
        }

        val strokes = activeGameNote?.strokes.orEmpty()
        val drawArea = RectF(cardRect.left + 16f, headerRect.bottom + 10f, cardRect.right - 16f, cardRect.bottom - 12f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        for (s in strokes) {
            if (s.points.size < 2) continue
            paint.color = try {
                Color.parseColor(s.colorHex)
            } catch (_: Exception) {
                Color.BLACK
            }
            paint.strokeWidth = s.strokeWidth
            strokePath.reset()
            val p0 = s.points.first()
            strokePath.moveTo(drawArea.left + p0.x * drawArea.width(), drawArea.top + p0.y * drawArea.height())
            for (i in 1 until s.points.size) {
                val pt = s.points[i]
                strokePath.lineTo(drawArea.left + pt.x * drawArea.width(), drawArea.top + pt.y * drawArea.height())
            }
            canvas.drawPath(strokePath, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawFriendCardUpperStage(canvas: Canvas, w: Float, h: Float, topBarH: Float) {
        val cardW = w * 0.72f
        val cardH = (h - topBarH) * 0.80f
        val left = (w - cardW) / 2f
        val top = topBarH + ((h - topBarH) - cardH) / 2f
        val cardRect = RectF(left, top, left + cardW, top + cardH)

        paint.color = Color.WHITE
        canvas.drawRoundRect(cardRect, 20f, 20f, paint)

        val headH = cardH * 0.22f
        val headRect = RectF(left, top, left + cardW, top + headH)
        paint.color = Color.parseColor("#FF6B00")
        canvas.drawRoundRect(headRect, 20f, 20f, paint)

        paint.color = Color.parseColor("#7CFF6B")
        canvas.drawCircle(left + 24f, headRect.centerY(), 7f, paint)

        textPaint.color = Color.WHITE
        textPaint.textSize = (headH * 0.42f).coerceIn(14f, 22f)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("😊 好友名片 (Friend Card) · 在线", left + 40f, headRect.centerY() + 6f, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("FC: ${friendProfile.friendCode}", cardRect.right - 18f, headRect.centerY() + 6f, textPaint)

        val miiCx = left + cardW * 0.20f
        val miiCy = top + headH + (cardH - headH) * 0.52f
        val headR = minOf(cardW, cardH) * 0.14f

        paint.color = friendProfile.miiShirtColor
        val bodyRect = RectF(miiCx - headR * 1.1f, miiCy + headR * 0.5f, miiCx + headR * 1.1f, cardRect.bottom - 14f)
        canvas.drawRoundRect(bodyRect, 18f, 18f, paint)

        paint.color = Color.parseColor("#FFE0BD")
        canvas.drawCircle(miiCx, miiCy - headR * 0.35f, headR, paint)

        paint.color = Color.parseColor("#3B2818")
        canvas.drawArc(
            RectF(miiCx - headR, miiCy - headR * 1.38f, miiCx + headR, miiCy + headR * 0.4f),
            180f,
            180f,
            true,
            paint
        )

        paint.color = Color.parseColor("#1F242A")
        canvas.drawCircle(miiCx - headR * 0.35f, miiCy - headR * 0.4f, 3.5f, paint)
        canvas.drawCircle(miiCx + headR * 0.35f, miiCy - headR * 0.4f, 3.5f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        canvas.drawArc(
            RectF(miiCx - headR * 0.35f, miiCy - headR * 0.35f, miiCx + headR * 0.35f, miiCy + headR * 0.05f),
            15f,
            150f,
            false,
            paint
        )
        paint.style = Paint.Style.FILL

        val rightLeft = left + cardW * 0.38f
        val bubbleRect = RectF(rightLeft, top + headH + 14f, cardRect.right - 18f, top + headH + (cardH - headH) * 0.46f)
        paint.color = Color.parseColor("#F2F6FA")
        canvas.drawRoundRect(bubbleRect, 14f, 14f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = Color.parseColor("#CFD8E3")
        paint.strokeWidth = 2f
        canvas.drawRoundRect(bubbleRect, 14f, 14f, paint)
        paint.style = Paint.Style.FILL

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.parseColor("#2A343D")
        textPaint.textSize = (cardH * 0.085f).coerceIn(13f, 20f)
        canvas.drawText(
            "「${ellipsize(friendProfile.statusMessage, textPaint, bubbleRect.width() - 24f)}」",
            bubbleRect.left + 12f,
            bubbleRect.centerY() + 5f,
            textPaint
        )

        textPaint.color = Color.parseColor("#FF6B00")
        textPaint.textSize = (cardH * 0.11f).coerceIn(15f, 24f)
        canvas.drawText(friendProfile.miiName, rightLeft, bubbleRect.bottom + cardH * 0.16f, textPaint)

        textPaint.color = Color.parseColor("#5C6B78")
        textPaint.textSize = (cardH * 0.08f).coerceIn(12f, 17f)
        val playingTitle = selectedSlot?.title?.ifBlank { friendProfile.favoriteGame } ?: friendProfile.favoriteGame
        canvas.drawText(
            "当前聚焦软件：${ellipsize(playingTitle, textPaint, cardRect.right - rightLeft - 18f)}",
            rightLeft,
            bubbleRect.bottom + cardH * 0.30f,
            textPaint
        )
        canvas.drawText(
            "最喜爱的游戏：${ellipsize(friendProfile.favoriteGame, textPaint, cardRect.right - rightLeft - 18f)}",
            rightLeft,
            bubbleRect.bottom + cardH * 0.42f,
            textPaint
        )
    }

    private fun drawNotificationUpperStage(canvas: Canvas, w: Float, h: Float, topBarH: Float) {
        val pad = w * 0.10f
        val cardRect = RectF(pad, topBarH + 12f, w - pad, h - 18f)
        paint.color = Color.WHITE
        canvas.drawRoundRect(cardRect, 18f, 18f, paint)

        val notif = selectedNotification
        val accent = if (notif?.isSpotPass == false) Color.parseColor("#18B858") else Color.parseColor("#0098DB")
        val headerRect = RectF(cardRect.left, cardRect.top, cardRect.right, cardRect.top + 44f)
        paint.color = accent
        canvas.drawRoundRect(headerRect, 18f, 18f, paint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 17f
        textPaint.textAlign = Paint.Align.LEFT
        val title = notif?.title ?: "Nintendo 3DS 通知中心"
        canvas.drawText("🔔 $title", headerRect.left + 16f, headerRect.centerY() + 6f, textPaint)

        textPaint.color = Color.parseColor("#5C6B78")
        textPaint.textSize = 13f
        val meta = if (notif != null) "来自：${notif.sender}   |   ${notif.dateStr}" else "SpotPass / StreetPass"
        canvas.drawText(meta, cardRect.left + 18f, headerRect.bottom + 24f, textPaint)

        textPaint.color = Color.parseColor("#222B34")
        textPaint.textSize = 15f
        val bodyLines = (notif?.body ?: "暂无通知").lines()
        var y = headerRect.bottom + 50f
        for (line in bodyLines) {
            if (y > cardRect.bottom - 16f) break
            canvas.drawText(ellipsize(line, textPaint, cardRect.width() - 36f), cardRect.left + 18f, y, textPaint)
            y += 24f
        }
    }

    private fun drawMiiverseUpperStage(canvas: Canvas, w: Float, h: Float, topBarH: Float) {
        draw3dsSpeechBubblePlate(
            canvas = canvas,
            w = w,
            h = h,
            stageBottomY = topBarH + (h - topBarH) * 0.55f,
            title = "Miiverse 社区与徽章抓取机 (Badge Arcade)",
            subtitle = "在下屏选择喜欢的任天堂徽章，一键装饰到桌面空白卡槽上！",
            platformBadge = "MIIVERSE",
            badgeColor = Color.parseColor("#56C428")
        )
    }

    private fun drawBrowserUpperStage(canvas: Canvas, w: Float, h: Float, topBarH: Float) {
        draw3dsSpeechBubblePlate(
            canvas = canvas,
            w = w,
            h = h,
            stageBottomY = topBarH + (h - topBarH) * 0.55f,
            title = "Nintendo 3DS 互联网浏览器",
            subtitle = "支持在下屏快速检索当前选中游戏的百科、金手指与全流程攻略",
            platformBadge = "BROWSER",
            badgeColor = Color.parseColor("#1D84E6")
        )
    }

    private fun drawNintendo3dsLaunchSplash(canvas: Canvas, w: Float, h: Float, progress: Float) {
        // 经典原版 3DS 白底开机启动过场动画 (Nintendo 3DS Iconic White Boot Sequence)
        // 0.0f .. 0.82f：经典纯白高光底色；0.82f .. 1.0f：平滑暗转黑场进入模拟器
        paint.style = Paint.Style.FILL
        if (progress < 0.82f) {
            paint.color = Color.parseColor("#FAF9F6")
            canvas.drawRect(0f, 0f, w, h, paint)
        } else {
            val fadeToBlack = ((progress - 0.82f) / 0.18f).coerceIn(0f, 1f)
            paint.color = Color.argb((fadeToBlack * 255).toInt(), 10, 10, 12)
            canvas.drawRect(0f, 0f, w, h, paint)
            return
        }

        val cx = w / 2f
        val cy = h * 0.44f

        // 1. 经典红白双屏 3D 立体方块汇聚动画 (Dual Stereoscopic 3D Screen Blocks)
        val convergeP = (progress / 0.38f).coerceIn(0f, 1f)
        val bounceOvershoot = sin(convergeP * Math.PI * 0.5).toFloat()

        val blockSize = (h * 0.13f).coerceIn(36f, 68f)
        val blockGap = blockSize * 0.18f
        val totalBlocksW = blockSize * 2f + blockGap

        // 上屏方块 (从上方落下) 与 下屏方块 (从下方升起) 汇聚
        val startYOffset = (1f - bounceOvershoot) * (h * 0.35f)
        val topBlockRect = RectF(
            cx - totalBlocksW / 2f,
            cy - blockSize / 2f - startYOffset,
            cx - totalBlocksW / 2f + blockSize,
            cy + blockSize / 2f - startYOffset
        )
        val btmBlockRect = RectF(
            topBlockRect.right + blockGap,
            cy - blockSize / 2f + startYOffset,
            topBlockRect.right + blockGap + blockSize,
            cy + blockSize / 2f + startYOffset
        )

        // 3D 投影阴影
        paint.color = Color.argb(40, 0, 0, 0)
        canvas.drawRoundRect(RectF(topBlockRect.left + 3f, topBlockRect.top + 5f, topBlockRect.right + 3f, topBlockRect.bottom + 5f), 12f, 12f, paint)
        canvas.drawRoundRect(RectF(btmBlockRect.left + 3f, btmBlockRect.top + 5f, btmBlockRect.right + 3f, btmBlockRect.bottom + 5f), 12f, 12f, paint)

        // 经典任天堂红色立体方块
        paint.color = Color.parseColor("#E60012")
        canvas.drawRoundRect(topBlockRect, 10f, 10f, paint)
        canvas.drawRoundRect(btmBlockRect, 10f, 10f, paint)

        // 方块高光斜边
        paint.color = Color.argb(60, 255, 255, 255)
        canvas.drawRoundRect(RectF(topBlockRect.left + 2f, topBlockRect.top + 2f, topBlockRect.right - 2f, topBlockRect.top + blockSize * 0.40f), 8f, 8f, paint)
        canvas.drawRoundRect(RectF(btmBlockRect.left + 2f, btmBlockRect.top + 2f, btmBlockRect.right - 2f, btmBlockRect.top + blockSize * 0.40f), 8f, 8f, paint)

        // 2. 标志性 "Nintendo 3DS" 徽标文字
        if (progress > 0.22f) {
            val logoAlpha = ((progress - 0.22f) / 0.20f).coerceIn(0f, 1f)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.DEFAULT_BOLD
            textPaint.textSize = (blockSize * 0.58f).coerceIn(20f, 38f)

            // 绘制立体 "Nintendo 3DS"
            textPaint.color = Color.argb((logoAlpha * 255).toInt(), 45, 55, 72)
            canvas.drawText("Nintendo 3DS", cx, cy + blockSize * 1.15f, textPaint)

            // 3. 经典流光扫过效果 (Progress 0.35 .. 0.70)
            if (progress in 0.35f..0.70f) {
                val sheenP = (progress - 0.35f) / 0.35f
                val sweepX = cx - totalBlocksW + sheenP * (totalBlocksW * 2.8f)
                val sheenW = blockSize * 0.65f
                paint.shader = LinearGradient(
                    sweepX - sheenW, cy - blockSize, sweepX + sheenW, cy + blockSize,
                    intArrayOf(Color.TRANSPARENT, Color.argb(120, 255, 255, 255), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawRect(cx - w * 0.4f, cy - blockSize * 1.2f, cx + w * 0.4f, cy + blockSize * 1.6f, paint)
                paint.shader = null
            }

            // 4. 下方游戏标题与 3DS 原版脉动跳跃点
            textPaint.typeface = apolloMediumTypeface
            textPaint.textSize = (h * 0.052f).coerceIn(13f, 22f)
            textPaint.color = Color.argb((logoAlpha * 220).toInt(), 71, 85, 105)
            canvas.drawText(launchSplashTitle, cx, cy + blockSize * 1.70f, textPaint)

            // 3DS 晶莹点跳动动画
            val dotCount = 4
            val dotSpacing = 16f
            val dotR = 4f
            val dotsTotalW = (dotCount - 1) * dotSpacing
            val dotsY = cy + blockSize * 2.15f
            for (i in 0 until dotCount) {
                val dotX = cx - dotsTotalW / 2f + i * dotSpacing
                val wave = sin(animPhase * 3.5f + i * 0.85f)
                val dotAlpha = (140 + wave * 90).toInt().coerceIn(40, 255)
                paint.color = Color.argb((dotAlpha * logoAlpha).toInt(), 230, 0, 18)
                canvas.drawCircle(dotX, dotsY + wave * 3f, dotR, paint)
            }
        }
    }

    private fun resolveSlotPreview(item: GridSlotItem): Bitmap? {
        val key = "preview_${item.id}_${item.previewCachePath}_${item.assetPreviewName}"
        val cached = bitmapCache[key]
        if (cached != null) return cached

        var bmp: Bitmap? = null
        if (item.previewCachePath.isNotBlank() && File(item.previewCachePath).exists()) {
            bmp = BitmapFactory.decodeFile(item.previewCachePath)
        } else if (item.assetPreviewName.isNotBlank()) {
            bmp = decodeAssetBitmap(item.assetPreviewName)
        }
        if (bmp != null) {
            if (bitmapCache.size > 60) bitmapCache.clear()
            bitmapCache[key] = bmp
        }
        return bmp
    }

    /**
     * 解析上屏专用的带完整侧边书脊的实体包装盒封面 (boxFront.png 或 *_box.png)
     */
    private fun resolveSlotBackCover(item: GridSlotItem): Bitmap? {
        val key = "back_${item.id}_${item.boxArtPath}"
        val cached = bitmapCache[key]
        if (cached != null) return cached
        var bmp: Bitmap? = null
        if (item.boxArtPath.isNotBlank()) {
            val f = File(item.boxArtPath)
            val parent = f.parentFile
            if (parent != null && parent.exists()) {
                val candidate1 = File(parent, "boxBack.png")
                val candidate2 = File(parent, "back.png")
                if (candidate1.exists()) bmp = BitmapFactory.decodeFile(candidate1.absolutePath)
                else if (candidate2.exists()) bmp = BitmapFactory.decodeFile(candidate2.absolutePath)
            }
        }
        if (bmp != null) {
            if (bitmapCache.size > 60) bitmapCache.clear()
            bitmapCache[key] = bmp
        }
        return bmp
    }

    private fun drawAuthenticBoxBack(canvas: Canvas, r: RectF, item: GridSlotItem, plat: String) {
        paint.color = Color.parseColor("#F8FAFC")
        canvas.drawRect(r, paint)

        // 顶部警示条与机种特性
        paint.color = Color.parseColor("#0F172A")
        val topBarH = r.height() * 0.12f
        canvas.drawRect(r.left, r.top, r.right, r.top + topBarH, paint)

        textPaint.color = Color.WHITE
        textPaint.textSize = topBarH * 0.45f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("$plat · OFFICIAL GAME CARD", r.centerX(), r.top + topBarH * 0.65f, textPaint)

        // 中间两张微型实机截图小框
        val screenBoxW = r.width() * 0.42f
        val screenBoxH = screenBoxW * 0.65f
        val screenBoxY = r.top + topBarH + r.height() * 0.08f

        paint.color = Color.parseColor("#CBD5E1")
        val s1 = RectF(r.left + r.width() * 0.06f, screenBoxY, r.left + r.width() * 0.06f + screenBoxW, screenBoxY + screenBoxH)
        val s2 = RectF(r.right - r.width() * 0.06f - screenBoxW, screenBoxY, r.right - r.width() * 0.06f, screenBoxY + screenBoxH)
        canvas.drawRoundRect(s1, 4f, 4f, paint)
        canvas.drawRoundRect(s2, 4f, 4f, paint)

        paint.color = blendColor(item.primaryColor, Color.BLACK, 0.35f)
        canvas.drawRoundRect(RectF(s1.left + 2f, s1.top + 2f, s1.right - 2f, s1.bottom - 2f), 3f, 3f, paint)
        canvas.drawRoundRect(RectF(s2.left + 2f, s2.top + 2f, s2.right - 2f, s2.bottom - 2f), 3f, 3f, paint)

        // 底部任天堂正品红印章与条形码
        val barcodeW = r.width() * 0.45f
        val barcodeH = r.height() * 0.16f
        val barcodeRect = RectF(r.left + 8f, r.bottom - barcodeH - 8f, r.left + 8f + barcodeW, r.bottom - 8f)
        paint.color = Color.WHITE
        canvas.drawRect(barcodeRect, paint)
        paint.color = Color.BLACK
        var bx = barcodeRect.left + 4f
        while (bx < barcodeRect.right - 4f) {
            val barW = if (((bx.toInt() * 7) % 3) == 0) 3f else 1.5f
            canvas.drawRect(bx, barcodeRect.top + 3f, bx + barW, barcodeRect.bottom - 3f, paint)
            bx += barW + 2f
        }

        // Nintendo Seal of Quality
        val sealR = barcodeH * 0.45f
        paint.color = Color.parseColor("#E60012")
        canvas.drawCircle(r.right - sealR - 12f, barcodeRect.centerY(), sealR, paint)
        textPaint.color = Color.WHITE
        textPaint.textSize = sealR * 0.52f
        canvas.drawText("ORIGINAL", r.right - sealR - 12f, barcodeRect.centerY() + textPaint.textSize * 0.35f, textPaint)
    }

    private fun resolveSlotBoxart(item: GridSlotItem): Bitmap? {
        val key = "box_${item.id}_${item.boxArtPath}_${item.assetIconName}"
        val cached = bitmapCache[key]
        if (cached != null) return cached

        if (pendingBitmapKeys.add(key)) {
            imageExecutor.execute {
                try {
                    var bmp: Bitmap? = null
                    if (item.boxArtPath.isNotBlank() && File(item.boxArtPath).exists()) {
                        bmp = BitmapFactory.decodeFile(item.boxArtPath)
                    } else if (item.assetIconName.isNotBlank()) {
                        val boxAsset = item.assetIconName.replace("_icon.png", "_box.png")
                        bmp = decodeAssetBitmap(boxAsset)
                    }
                    if (bmp == null && (item.id == "showcase_3ds_mario_3d_land" || item.title.contains("3D大陆", ignoreCase = true))) {
                        bmp = decodeAssetBitmap("3ds_art/mario_3d_land_banner_cover.png")
                            ?: decodeAssetBitmap("3ds_art/mario_3d_land_box.png")
                    }
                    if (bmp != null) {
                        if (bitmapCache.size > 80) bitmapCache.clear()
                        bitmapCache[key] = bmp
                        postInvalidate()
                    }
                } catch (_: Exception) {
                } finally {
                    pendingBitmapKeys.remove(key)
                }
            }
        }
        return null
    }

    /**
     * 解析上屏前景悬浮展示的透明背景游戏 Logo (logo.png)
     */
    private fun resolveSlotLogo(item: GridSlotItem): Bitmap? {
        val key = "logo_${item.id}_${item.logoPath}"
        val cached = bitmapCache[key]
        if (cached != null) return cached

        if (pendingBitmapKeys.add(key)) {
            imageExecutor.execute {
                try {
                    var bmp: Bitmap? = null
                    if (item.logoPath.isNotBlank()) {
                        val f = File(item.logoPath)
                        if (f.exists()) bmp = BitmapFactory.decodeFile(f.absolutePath)
                    }
                    if (bmp == null && item.romPath.isNotBlank()) {
                        val romF = File(item.romPath)
                        val parent = romF.parentFile
                        if (parent != null && parent.exists()) {
                            val candidateNames = listOf("logo.png", "wheel.png", "marquee.png", "title.png", "banner.png", "logo.jpg")
                            for (name in candidateNames) {
                                val cand = File(parent, name)
                                if (cand.exists() && cand.length() > 0) {
                                    bmp = BitmapFactory.decodeFile(cand.absolutePath)
                                    break
                                }
                            }
                        }
                    }
                    if (bmp == null && (item.id == "showcase_3ds_mario_3d_land" || item.title.contains("3D大陆", ignoreCase = true))) {
                        bmp = decodeAssetBitmap("3ds_art/mario_3d_land_logo.png")
                    }
                    if (bmp != null) {
                        if (bitmapCache.size > 80) bitmapCache.clear()
                        bitmapCache[key] = bmp
                        postInvalidate()
                    }
                } catch (_: Exception) {
                } finally {
                    pendingBitmapKeys.remove(key)
                }
            }
        }
        return null
    }

    private fun resolveSlotIcon(item: GridSlotItem): Bitmap? {
        val key = "icon_${item.id}_${item.iconCachePath}_${item.assetIconName}_${item.packageName}"
        val cached = bitmapCache[key]
        if (cached != null) return cached

        if (pendingBitmapKeys.add(key)) {
            imageExecutor.execute {
                try {
                    var bmp: Bitmap? = null
                    if (item.iconCachePath.isNotBlank() && File(item.iconCachePath).exists()) {
                        bmp = BitmapFactory.decodeFile(item.iconCachePath)
                    } else if (item.assetIconName.isNotBlank()) {
                        bmp = decodeAssetBitmap(item.assetIconName)
                    } else if (item.type == PlatformType.ANDROID_APP && item.packageName.isNotBlank()) {
                        val drawable = context.packageManager.getApplicationIcon(item.packageName)
                        bmp = if (drawable is BitmapDrawable) {
                            drawable.bitmap
                        } else {
                            val created = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                            val c = Canvas(created)
                            drawable.setBounds(0, 0, 96, 96)
                            drawable.draw(c)
                            created
                        }
                    }
                    if (bmp != null) {
                        if (bitmapCache.size > 80) bitmapCache.clear()
                        bitmapCache[key] = bmp
                        postInvalidate()
                    }
                } catch (_: Exception) {
                } finally {
                    pendingBitmapKeys.remove(key)
                }
            }
        }
        return null
    }

    private fun decodeAssetBitmap(assetPath: String): Bitmap? {
        return try {
            context.assets.open(assetPath).use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readBatteryState(): Pair<Int, Boolean> {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 85) ?: 85
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val pct = ((level * 100f) / scale.coerceAtLeast(1)).toInt().coerceIn(1, 100)
            pct to charging
        } catch (_: Exception) {
            85 to false
        }
    }

    private fun isColorDark(color: Int): Boolean {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        return luminance < 0.56
    }

    private fun blendColor(c1: Int, c2: Int, ratio: Float): Int {
        val r = (Color.red(c1) * (1f - ratio) + Color.red(c2) * ratio).toInt().coerceIn(0, 255)
        val g = (Color.green(c1) * (1f - ratio) + Color.green(c2) * ratio).toInt().coerceIn(0, 255)
        val b = (Color.blue(c1) * (1f - ratio) + Color.blue(c2) * ratio).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun ellipsize(text: String, p: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f || p.measureText(text) <= maxWidth) return text
        var len = text.length
        while (len > 1 && p.measureText(text.substring(0, len) + "…") > maxWidth) {
            len--
        }
        return text.substring(0, len) + "…"
    }
}
