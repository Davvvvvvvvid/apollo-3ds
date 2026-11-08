package com.gameconsole.launcher3ds.companion.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.gameconsole.launcher3ds.companion.repository.CompanionProfileRepository
import com.gameconsole.launcher3ds.companion.ui.LauncherTacticalCompanionView
import com.gameconsole.launcher3ds.model.GridSlotItem

/**
 * 📺 Launcher3DS 原生副屏战术伴侣管理器
 * 负责在单屏游戏（PSP、PS1、GBA、街机、安卓单屏等）运行时，
 * 独立将全功能战术伴侣面板（翻译、画廊、攻略、街霸键、触控板、DeepSeek）挂载到物理副屏！
 */
object LauncherCompanionOverlayManager {
    private const val TAG = "CompanionOverlay"

    private var companionView: LauncherTacticalCompanionView? = null
    private var secWindowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isShowing = false

    fun show(
        context: Context,
        slot: GridSlotItem,
        onScreenCaptureRequested: ((callback: (android.graphics.Bitmap?) -> Unit) -> Unit)? = null,
        onKeyActionRequested: ((keyCode: Int, isTurbo: Boolean) -> Unit)? = null
    ) {
        mainHandler.post {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return@post
            val presDisplays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            val secDisplay = presDisplays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
                ?: dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

            if (secDisplay == null) {
                Log.d(TAG, "No secondary display detected; skip companion overlay")
                return@post
            }

            try {
                hideInternal()

                val targetContext = context.createDisplayContext(secDisplay)
                val wm = targetContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                secWindowManager = wm

                val repo = CompanionProfileRepository(targetContext)
                val view = LauncherTacticalCompanionView(
                    context = targetContext,
                    slot = slot,
                    repo = repo,
                    onScreenCaptureRequested = onScreenCaptureRequested,
                    onKeyActionRequested = onKeyActionRequested
                )

                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )

                wm.addView(view, lp)
                companionView = view
                isShowing = true
                Log.d(TAG, "Attached TacticalCompanionView natively for ${slot.title} on display ${secDisplay.displayId}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach TacticalCompanionView to secondary display", e)
                hideInternal()
            }
        }
    }

    fun hide() {
        mainHandler.post {
            hideInternal()
        }
    }

    private fun hideInternal() {
        val view = companionView ?: return
        try {
            if (view.isAttachedToWindow) {
                secWindowManager?.removeView(view)
            }
            Log.d(TAG, "TacticalCompanionView detached by Launcher3DS")
        } catch (e: Throwable) {
            Log.w(TAG, "Error detaching TacticalCompanionView", e)
        } finally {
            companionView = null
            isShowing = false
        }
    }
}
