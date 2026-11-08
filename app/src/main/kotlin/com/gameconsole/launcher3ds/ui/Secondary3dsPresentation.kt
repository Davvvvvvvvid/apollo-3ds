package com.gameconsole.launcher3ds.ui

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import com.gameconsole.launcher3ds.R

/**
 * 📺 双屏掌机副屏 Presentation 容器
 * 支持将 3DS 下屏 (LowerScreenView) 或 3DS 上屏 (UpperScreenView) 全屏无界投射到物理副屏，
 * 并支持在启动器设置中一键无缝互换上下屏映射。
 */
class Secondary3dsPresentation(
    outerContext: Context,
    display: Display,
    private val keyEventDispatcher: ((KeyEvent) -> Boolean)? = null,
    private val motionEventDispatcher: ((MotionEvent) -> Boolean)? = null
) : Presentation(outerContext, display, R.style.Theme_Launcher3DS) {

    private var rootContainer: FrameLayout? = null
    private var pendingView: View? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyEventDispatcher?.invoke(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (motionEventDispatcher?.invoke(event) == true) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.let { win ->
            WindowCompat.setDecorFitsSystemWindows(win, false)
            win.statusBarColor = Color.TRANSPARENT
            win.navigationBarColor = Color.TRANSPARENT
            win.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            win.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lp = win.attributes
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                win.attributes = lp
            }
            @Suppress("DEPRECATION")
            win.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#12161A"))
        }
        rootContainer = container
        setContentView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        pendingView?.let { v ->
            mountViewInternal(v)
        }
    }

    fun attachScreenView(view: View) {
        pendingView = view
        mountViewInternal(view)
    }

    private fun mountViewInternal(view: View) {
        val container = rootContainer ?: return
        if (view.parent === container) {
            view.visibility = View.VISIBLE
            view.requestLayout()
            view.invalidate()
            return
        }
        (view.parent as? ViewGroup)?.removeView(view)
        container.removeAllViews()
        view.visibility = View.VISIBLE
        container.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        container.requestLayout()
        container.invalidate()
        view.requestLayout()
        view.invalidate()
    }
}
