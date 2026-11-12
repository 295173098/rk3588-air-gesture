package com.rk3588.gesturelib

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 预览叠加层：绘制手掌框、21 关键点骨架、手势名与帧耗时。
 * 坐标为相机图像归一化坐标 (640x480)，SurfaceView 等比 4:3 时线性映射。
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        // MediaPipe HAND_CONNECTIONS
        val CONNECTIONS = arrayOf(
            intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4),
            intArrayOf(0, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 8),
            intArrayOf(5, 9), intArrayOf(9, 10), intArrayOf(10, 11), intArrayOf(11, 12),
            intArrayOf(9, 13), intArrayOf(13, 14), intArrayOf(14, 15), intArrayOf(15, 16),
            intArrayOf(13, 17), intArrayOf(17, 18), intArrayOf(18, 19), intArrayOf(19, 20),
            intArrayOf(0, 17)
        )
        val TIPS = intArrayOf(4, 8, 12, 16, 20)
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.CYAN
    }
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        color = Color.WHITE
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.YELLOW
    }
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 82, 82)
    }
    private val textBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#B3000000")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 44f
    }

    fun onFrameTick() {
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val snap = RecognitionState.latest
        if (snap == null) {
            drawBadge(canvas, w, "等待识别数据…", 0xFFBBBBBB.toInt())
            return
        }

        // 视频实际显示区域: ImageView fitCenter 显示 640x480 位图
        val scale = minOf(w / CameraController.PREVIEW_W, h / CameraController.PREVIEW_H)
        val cw = CameraController.PREVIEW_W * scale
        val ch = CameraController.PREVIEW_H * scale
        val ox = (w - cw) / 2f
        val oy = (h - ch) / 2f
        val mx = { nx: Float -> ox + nx * cw }
        val my = { ny: Float -> oy + ny * ch }

        // 置信度不足只显示徽标, 不画骨架 (无手时关键点为噪声)
        if (snap.score < 0.65f) {
            val name = RknnBridge.gestureNames.getOrElse(snap.gestureId) { "?" }
            drawBadge(canvas, w, "$name  %.0fms".format(snap.ms), 0xFF9E9E9E.toInt())
            return
        }

        // 手掌框
        if (snap.palmValid && snap.palmW > 0.01f) {
            val l = mx(snap.palmCx - snap.palmW / 2)
            val t = my(snap.palmCy - snap.palmH / 2)
            val r = mx(snap.palmCx + snap.palmW / 2)
            val b = my(snap.palmCy + snap.palmH / 2)
            canvas.drawRect(l, t, r, b, boxPaint)
        }

        // 骨架
        val lm = snap.landmarks
        for (c in CONNECTIONS) {
            val a = c[0] * 3
            val b = c[1] * 3
            canvas.drawLine(mx(lm[a]), my(lm[a + 1]), mx(lm[b]), my(lm[b + 1]), bonePaint)
        }
        // 关节点
        for (i in 0 until 21) {
            val x = mx(lm[i * 3])
            val y = my(lm[i * 3 + 1])
            val isTip = TIPS.contains(i)
            canvas.drawCircle(x, y, if (isTip) 11f else 7f, if (isTip) tipPaint else jointPaint)
        }

        // 手势信息徽标
        val name = RknnBridge.gestureNames.getOrElse(snap.gestureId) { "?" }
        val color = when (snap.gestureId) {
            RknnBridge.G_NONE -> 0xFF9E9E9E.toInt()
            RknnBridge.G_FIST -> 0xFFFFB300.toInt()
            else -> 0xFF4CAF50.toInt()
        }
        drawBadge(canvas, w, "$name  %.0fms".format(snap.ms), color)
    }

    private fun drawBadge(canvas: Canvas, w: Float, text: String, color: Int) {
        val tw = textPaint.measureText(text)
        canvas.drawRect(w / 2 - tw / 2 - 24, 24f, w / 2 + tw / 2 + 24, 110f, textBgPaint)
        textPaint.color = color
        canvas.drawText(text, w / 2 - tw / 2, 86f, textPaint)
    }
}
