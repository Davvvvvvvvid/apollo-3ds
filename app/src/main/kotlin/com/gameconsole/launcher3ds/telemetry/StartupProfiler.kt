package com.gameconsole.launcher3ds.telemetry

import android.os.SystemClock
import android.util.Log

/**
 * ⚡ 3DS 桌面冷启动耗时诊断与性能分析器
 * 精确统计各阶段毫秒耗时，并在副屏「系统高级设置」与 Logcat 中全透明呈现！
 */
object StartupProfiler {

    private const val TAG = "3DS_STARTUP"

    var tClassLoad: Long = SystemClock.elapsedRealtime()
    var tOnCreateStart: Long = 0L
    var tRepoInit: Long = 0L
    var tViewsInit: Long = 0L
    var tSlotsLoad: Long = 0L
    var tSyncViews: Long = 0L
    var tLayoutSetup: Long = 0L
    var tOnResumeEnd: Long = 0L
    var tFirstFrameDraw: Long = 0L

    var detected3dsEmulatorPkg: String = ""
    var detected3dsEmulatorLabel: String = ""
    var detected3dsEmulatorInstalled: Boolean = false

    var isFirstFrameReported: Boolean = false

    fun markOnCreateStart() {
        tOnCreateStart = SystemClock.elapsedRealtime()
    }

    fun markRepoInit() {
        tRepoInit = SystemClock.elapsedRealtime()
    }

    fun markViewsInit() {
        tViewsInit = SystemClock.elapsedRealtime()
    }

    fun markSlotsLoad() {
        tSlotsLoad = SystemClock.elapsedRealtime()
    }

    fun markSyncViews() {
        tSyncViews = SystemClock.elapsedRealtime()
    }

    fun markLayoutSetup() {
        tLayoutSetup = SystemClock.elapsedRealtime()
    }

    fun markOnResumeEnd() {
        tOnResumeEnd = SystemClock.elapsedRealtime()
    }

    fun markFirstFrameDraw() {
        if (isFirstFrameReported && tFirstFrameDraw > 0) return
        tFirstFrameDraw = SystemClock.elapsedRealtime()
        isFirstFrameReported = true
        logStartupSummary()
    }

    val totalColdBootMs: Long
        get() {
            val base = if (tOnCreateStart > 0) tOnCreateStart else tClassLoad
            val end = if (tFirstFrameDraw > 0) tFirstFrameDraw else SystemClock.elapsedRealtime()
            return (end - base).coerceAtLeast(0)
        }

    val repoInitMs: Long get() = (tRepoInit - tOnCreateStart).coerceAtLeast(0)
    val viewsInitMs: Long get() = (tViewsInit - tRepoInit).coerceAtLeast(0)
    val slotsLoadMs: Long get() = (tSlotsLoad - tViewsInit).coerceAtLeast(0)
    val syncViewsMs: Long get() = (tSyncViews - tSlotsLoad).coerceAtLeast(0)
    val layoutSetupMs: Long get() = (tLayoutSetup - tSyncViews).coerceAtLeast(0)
    val onResumeMs: Long get() = (tOnResumeEnd - tLayoutSetup).coerceAtLeast(0)
    val firstFrameDrawMs: Long get() = (tFirstFrameDraw - tOnResumeEnd).coerceAtLeast(0)

    fun logStartupSummary() {
        val total = totalColdBootMs
        Log.i(
            TAG,
            "=======================================================================\n" +
            "⚡ [3DS 桌面冷启动耗时诊断]\n" +
            "总冷启动耗时: ${total}ms (秒开状态)\n" +
            "├─ 1. 基础配置与SharedPreferences: ${repoInitMs}ms\n" +
            "├─ 2. 上下屏视图与音频引擎实例化: ${viewsInitMs}ms\n" +
            "├─ 3. 游戏卡槽与目录缓存反序列化: ${slotsLoadMs}ms\n" +
            "├─ 4. 界面分类与主题状态同步: ${syncViewsMs}ms\n" +
            "├─ 5. 双屏拓扑与副屏 Presentation 挂载: ${layoutSetupMs}ms\n" +
            "├─ 6. 窗口就绪与生命周期恢复 (onResume): ${onResumeMs}ms\n" +
            "└─ 7. 屏幕首帧渲染 (Canvas onDraw): ${firstFrameDrawMs}ms\n" +
            "🎮 3DS 核心模拟器: $detected3dsEmulatorLabel ($detected3dsEmulatorPkg) [已安装=$detected3dsEmulatorInstalled]\n" +
            "======================================================================="
        )
    }

    fun getDiagnosticLines(): List<String> {
        val total = totalColdBootMs
        return listOf(
            "⚡ 冷启动总耗时: ${total} ms",
            "├─ 存储读取与缓存: ${slotsLoadMs} ms",
            "├─ 界面视图实例化: ${viewsInitMs} ms",
            "├─ 分类与状态同步: ${syncViewsMs} ms",
            "├─ 双屏拓扑挂载: ${layoutSetupMs} ms",
            "└─ 屏幕首帧渲染: ${firstFrameDrawMs} ms"
        )
    }
}
