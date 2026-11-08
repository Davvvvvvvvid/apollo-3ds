package com.gameconsole.launcher3ds.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager

/**
 * 🎮 Launcher3DS 原生主副屏复古液晶滤镜管理器 (独立运行，无需 Apollo 主程序)
 *
 * 核心特性：
 * 1. 独立双通道控制：
 *    - 主屏滤镜 (Main Screen Filter)：专为 2D/2.5D 像素游戏（如《冒险岛》、《宝可梦》、《逆转裁判》、GBA/街机）消除拉大发糊问题；
 *    - 副屏滤镜 (Subscreen Filter)：专为 3DS / NDS 副屏提供原厂黑矩阵次像素质感；
 * 2. 触控 100% 穿透：采用 FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE，窗口系统完全穿透，
 *    手柄、虚拟摇杆、手写笔滑动零阻碍、零延迟。
 * 3. 硬件着色：采用 BitmapShader 硬件重复平铺，零 CPU 占用，极低显存 (<10KB)。
 */
object LauncherRetroFilterManager {
    private const val TAG = "LauncherRetroFilter"

    enum class FilterType(val id: String, val displayName: String) {
        LCD_3X("LCD_3X", "LCD 3X 经典次像素"),
        DOT_MATRIX("DOT_MATRIX", "细密点阵 (Dot Matrix)"),
        SCANLINE("SCANLINE", "复古微扫描线"),
        CRT_APERTURE("CRT_APERTURE", "街机光栅条纹")
    }

    // 副屏滤镜视图与管理器
    private var secFilterOverlayView: RetroFilterView? = null
    private var secWindowManager: WindowManager? = null
    private var isSecShowing = false
    private var secCurrentType = FilterType.LCD_3X
    private var secCurrentOpacity = 20

    // 主屏滤镜视图与管理器
    private var mainFilterOverlayView: RetroFilterView? = null
    private var mainWindowManager: WindowManager? = null
    private var isMainShowing = false
    private var mainCurrentType = FilterType.LCD_3X
    private var mainCurrentOpacity = 20

    private val mainHandler = Handler(Looper.getMainLooper())

    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start overlay permission settings directly, fallback generic", e)
                try {
                    val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                } catch (_: Exception) {}
            }
        }
    }

    // =========================================================================
    // 1. 主屏复古滤镜接口 (解决《冒险岛》、2D 贴图等拉大发糊问题)
    // =========================================================================

    fun showMain(context: Context, typeStr: String = "LCD_3X", opacity: Int = 20) {
        mainCurrentType = parseFilterType(typeStr)
        mainCurrentOpacity = opacity.coerceIn(10, 80)

        mainHandler.post {
            if (!hasOverlayPermission(context)) {
                Log.w(TAG, "Cannot show main overlay: SYSTEM_ALERT_WINDOW not granted")
                return@post
            }
            showMainInternal(context)
        }
    }

    fun hideMain() {
        mainHandler.post {
            hideMainInternal()
        }
    }

    fun updateMain(context: Context, enabled: Boolean, typeStr: String = "LCD_3X", opacity: Int = 20) {
        mainCurrentType = parseFilterType(typeStr)
        mainCurrentOpacity = opacity.coerceIn(10, 80)

        mainHandler.post {
            if (!enabled) {
                hideMainInternal()
            } else if (isMainShowing) {
                mainFilterOverlayView?.updatePattern(mainCurrentType, mainCurrentOpacity)
            } else if (hasOverlayPermission(context)) {
                showMainInternal(context)
            }
        }
    }

    private fun showMainInternal(context: Context) {
        val appContext = context.applicationContext ?: context
        try {
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            mainWindowManager = wm

            if (mainFilterOverlayView == null) {
                val view = RetroFilterView(appContext)
                view.updatePattern(mainCurrentType, mainCurrentOpacity)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                wm.addView(view, lp)
                mainFilterOverlayView = view
                isMainShowing = true
                Log.d(TAG, "Main display RetroFilterView attached ($mainCurrentType, $mainCurrentOpacity%)")
            } else {
                mainFilterOverlayView?.updatePattern(mainCurrentType, mainCurrentOpacity)
                isMainShowing = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach RetroFilterView to main display", e)
            hideMainInternal()
        }
    }

    private fun hideMainInternal() {
        val view = mainFilterOverlayView ?: return
        try {
            if (view.isAttachedToWindow) {
                mainWindowManager?.removeView(view)
            }
            Log.d(TAG, "Main screen RetroFilterView detached")
        } catch (e: Throwable) {
            Log.w(TAG, "Error detaching main RetroFilterView", e)
        } finally {
            mainFilterOverlayView = null
            isMainShowing = false
        }
    }

    // =========================================================================
    // 2. 副屏复古滤镜接口 (兼容旧调用)
    // =========================================================================

    fun showSecondary(context: Context, typeStr: String = "LCD_3X", opacity: Int = 20) {
        secCurrentType = parseFilterType(typeStr)
        secCurrentOpacity = opacity.coerceIn(10, 80)

        mainHandler.post {
            if (!hasOverlayPermission(context)) {
                Log.w(TAG, "Cannot show secondary overlay: SYSTEM_ALERT_WINDOW not granted")
                return@post
            }
            showSecondaryInternal(context)
        }
    }

    fun hideSecondary() {
        mainHandler.post {
            hideSecondaryInternal()
        }
    }

    fun updateSecondary(context: Context, enabled: Boolean, typeStr: String = "LCD_3X", opacity: Int = 20) {
        secCurrentType = parseFilterType(typeStr)
        secCurrentOpacity = opacity.coerceIn(10, 80)

        mainHandler.post {
            if (!enabled) {
                hideSecondaryInternal()
            } else if (isSecShowing) {
                secFilterOverlayView?.updatePattern(secCurrentType, secCurrentOpacity)
            } else if (hasOverlayPermission(context)) {
                showSecondaryInternal(context)
            }
        }
    }

    // 兼容原旧方法名称
    fun show(context: Context, typeStr: String, opacity: Int) = showSecondary(context, typeStr, opacity)
    fun update(context: Context, enabled: Boolean, typeStr: String, opacity: Int) = updateSecondary(context, enabled, typeStr, opacity)
    fun hide() = hideAll()

    fun hideAll() {
        mainHandler.post {
            hideMainInternal()
            hideSecondaryInternal()
        }
    }

    private fun findSecondaryDisplay(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        val presDisplays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val secPres = presDisplays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (secPres != null) return secPres
        return dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    private fun showSecondaryInternal(context: Context) {
        val secDisplay = findSecondaryDisplay(context)
        if (secDisplay == null) {
            Log.d(TAG, "No secondary display detected; skip secondary retro filter overlay")
            return
        }

        try {
            val targetContext = context.createDisplayContext(secDisplay)
            val wm = targetContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            secWindowManager = wm

            if (secFilterOverlayView == null) {
                val view = RetroFilterView(targetContext)
                view.updatePattern(secCurrentType, secCurrentOpacity)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                wm.addView(view, lp)
                secFilterOverlayView = view
                isSecShowing = true
                Log.d(TAG, "RetroFilterView attached natively by Launcher3DS to display ${secDisplay.displayId} ($secCurrentType, $secCurrentOpacity%)")
            } else {
                secFilterOverlayView?.updatePattern(secCurrentType, secCurrentOpacity)
                isSecShowing = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach RetroFilterView to secondary display", e)
            hideSecondaryInternal()
        }
    }

    private fun hideSecondaryInternal() {
        val view = secFilterOverlayView ?: return
        try {
            if (view.isAttachedToWindow) {
                secWindowManager?.removeView(view)
            }
            Log.d(TAG, "Secondary RetroFilterView detached by Launcher3DS")
        } catch (e: Throwable) {
            Log.w(TAG, "Error detaching secondary RetroFilterView", e)
        } finally {
            secFilterOverlayView = null
            isSecShowing = false
        }
    }

    private fun parseFilterType(typeStr: String): FilterType {
        return try {
            FilterType.valueOf(typeStr)
        } catch (_: Exception) {
            FilterType.LCD_3X
        }
    }

    class RetroFilterView(context: Context) : View(context) {
        private val paint = Paint().apply {
            isAntiAlias = false
            isDither = false
            isFilterBitmap = false
        }
        private var cachedPatternBitmap: Bitmap? = null

        fun updatePattern(type: FilterType, opacity: Int) {
            val bmp = createPatternBitmap(type)
            cachedPatternBitmap?.recycle()
            cachedPatternBitmap = bmp
            paint.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            paint.alpha = (opacity * 255 / 100).coerceIn(0, 255)
            postInvalidate()
        }

        private fun createPatternBitmap(type: FilterType): Bitmap {
            return when (type) {
                FilterType.LCD_3X -> {
                    // 6x3 真实 3DS LCD RGB 次像素纯黑晶格 (Subtractive Black Matrix)
                    // 保证透光孔 100% 纯透，绝不增加杂散白光/彩光，从物理光学层面根除雾蒙蒙现象，保留原始高对比度与纯黑
                    val bmp = Bitmap.createBitmap(6, 3, Bitmap.Config.ARGB_8888)
                    for (x in 0 until 6) {
                        for (y in 0 until 3) {
                            if (y == 2) {
                                // 横向像素间隙黑线
                                bmp.setPixel(x, y, Color.argb(230, 0, 0, 0))
                            } else if (x == 5) {
                                // 纵向像素边界黑线
                                bmp.setPixel(x, y, Color.argb(255, 0, 0, 0))
                            } else if (x == 1 || x == 3) {
                                // 纵向 RGB 次像素之间的细微阴影缝隙
                                bmp.setPixel(x, y, Color.argb(130, 0, 0, 0))
                            } else {
                                // 纯透光孔 (游戏画面 100% 无损透出，对比度与色彩饱和度零损失)
                                bmp.setPixel(x, y, Color.TRANSPARENT)
                            }
                        }
                    }
                    bmp
                }
                FilterType.DOT_MATRIX -> {
                    // 4x4 方形微点阵晶格（NDS / Game Boy 触屏质感）
                    val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                    val border = Color.argb(255, 0, 0, 0)
                    val center = Color.TRANSPARENT
                    for (x in 0 until 4) {
                        for (y in 0 until 4) {
                            if (x == 3 || y == 3) {
                                bmp.setPixel(x, y, border)
                            } else {
                                bmp.setPixel(x, y, center)
                            }
                        }
                    }
                    bmp
                }
                FilterType.SCANLINE -> {
                    // 1x4 柔和水平扫描线（纯暗部衰减，无白雾）
                    val bmp = Bitmap.createBitmap(1, 4, Bitmap.Config.ARGB_8888)
                    bmp.setPixel(0, 0, Color.TRANSPARENT)
                    bmp.setPixel(0, 1, Color.TRANSPARENT)
                    bmp.setPixel(0, 2, Color.argb(120, 0, 0, 0))
                    bmp.setPixel(0, 3, Color.argb(255, 0, 0, 0))
                    bmp
                }
                FilterType.CRT_APERTURE -> {
                    // 3x1 纵向光栅条纹（特丽珑微晶栅）
                    val bmp = Bitmap.createBitmap(3, 1, Bitmap.Config.ARGB_8888)
                    bmp.setPixel(0, 0, Color.TRANSPARENT)
                    bmp.setPixel(1, 0, Color.TRANSPARENT)
                    bmp.setPixel(2, 0, Color.argb(255, 0, 0, 0))
                    bmp
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            cachedPatternBitmap?.recycle()
            cachedPatternBitmap = null
        }
    }
}
