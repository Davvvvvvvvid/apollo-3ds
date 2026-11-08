package com.gameconsole.launcher3ds.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.gameconsole.launcher3ds.model.NoteStroke
import com.gameconsole.launcher3ds.model.StrokePoint

/**
 * ✏️ Nintendo 3DS 游戏笔记手写画板 (支持黑/红/蓝三色笔迹 + 实时镜像到上屏)
 */
class GameNotesCanvasView(
    context: Context,
    val strokes: MutableList<NoteStroke>,
    private val onStrokeUpdated: () -> Unit
) : View(context) {

    var currentInkColorHex: String = "#1A1A1A"
    var currentStrokeWidth: Float = 5.5f
    private var activeStroke: NoteStroke? = null

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6DEC8")
        strokeWidth = 1f
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    init {
        setBackgroundColor(Color.parseColor("#FFFDF7"))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val nx = (event.x / w).coerceIn(0f, 1f)
        val ny = (event.y / h).coerceIn(0f, 1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val s = NoteStroke(
                    colorHex = currentInkColorHex,
                    strokeWidth = currentStrokeWidth,
                    points = mutableListOf(StrokePoint(nx, ny))
                )
                strokes.add(s)
                activeStroke = s
                onStrokeUpdated()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                activeStroke?.points?.add(StrokePoint(nx, ny))
                onStrokeUpdated()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeStroke?.points?.add(StrokePoint(nx, ny))
                activeStroke = null
                onStrokeUpdated()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 绘制 3DS 笔记本方格底纹
        val step = 28f
        var x = step
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, gridPaint)
            x += step
        }
        var y = step
        while (y < h) {
            canvas.drawLine(0f, y, w, y, gridPaint)
            y += step
        }

        // 绘制所有手写笔画
        for (s in strokes) {
            if (s.points.size < 2) continue
            strokePaint.color = try {
                Color.parseColor(s.colorHex)
            } catch (_: Exception) {
                Color.BLACK
            }
            strokePaint.strokeWidth = s.strokeWidth
            path.reset()
            val p0 = s.points.first()
            path.moveTo(p0.x * w, p0.y * h)
            for (i in 1 until s.points.size) {
                val pt = s.points[i]
                path.lineTo(pt.x * w, pt.y * h)
            }
            canvas.drawPath(path, strokePaint)
        }
    }
}
