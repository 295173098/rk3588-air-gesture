package com.rk3588.gesturelib

import android.content.Context
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 鼠标模式控制器: 把 21 关键点翻译成虚拟鼠标事件。
 *
 *  ☝️ 食指单独伸出      → 光标跟随指尖 (平滑 + 比例映射)
 *  🤏 拇指食指捏合      → 左键按下; 保持+移动=拖拽; 松开=释放 (快速捏合即单击)
 *  ✌️ 两指张开瞬间      → 右键单击; 按住上下挥 = 滚轮
 *
 * 全部走 /dev/uinput 虚拟鼠标 (root), 系统级语义。
 */
class MouseController(context: Context) {

    companion object {
        private const val TAG = "MouseController"
        // 指尖有效区: 相机画面的中央 76% 映射到全屏
        private const val MARGIN = 0.12f
        // 光标比例步进 (目标差 × gain), 限速
        private const val CURSOR_GAIN = 0.45f
        private const val CURSOR_MAX_STEP = 64
        // 指尖 EMA 平滑
        private const val SMOOTH = 0.5f
        // 捏合判定阈值 (以 腕-中指MPC 距离为单位), 带迟滞
        private const val PINCH_ON = 0.42f
        private const val PINCH_OFF = 0.58f
        // 滚轮: 中指指尖每累计 N 像素滚动一格
        private const val WHEEL_STEP_PX = 34f
    }

    private var screenW = 1080
    private var screenH = 1920

    // 光标状态
    private var curX = 0f
    private var curY = 0f
    private var emaX = 0f
    private var emaY = 0f
    private var hasSmooth = false

    // 捏合/按键状态
    private var pinched = false
    private var leftDown = false

    // 右键/滚轮状态
    private var vActive = false
    private var wheelAcc = 0f
    private var lastWheelY = 0f
    private var hasWheelY = false

    init {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        screenW = m.widthPixels
        screenH = m.heightPixels
        curX = screenW / 2f
        curY = screenH / 2f
    }

    fun ensureSetup(): Boolean = VirtualMouse.setup()

    /** 每帧调用 (score>0.65 时 landmarks 有效; 否则传 null 释放按键) */
    fun onFrame(landmarks: FloatArray?, gesture: Int, now: Long) {
        if (!VirtualMouse.available) return

        if (landmarks == null) {
            // 手丢失: 释放按键, 复位状态
            if (leftDown) { VirtualMouse.button(true, false); leftDown = false }
            pinched = false
            vActive = false
            hasSmooth = false
            hasWheelY = false
            return
        }

        fun px(i: Int) = landmarks[i * 3] * screenW
        fun py(i: Int) = landmarks[i * 3 + 1] * screenH
        fun dist(a: Int, b: Int): Float {
            val dx = px(a) - px(b); val dy = py(a) - py(b)
            return sqrt(dx * dx + dy * dy)
        }

        val palm = max(dist(0, 9), 1f)  // 腕(0)→中指MCP(9)
        val pinchDist = dist(4, 8) / palm

        // ---- 左键: 捏合 (带迟滞) ----
        if (!pinched && pinchDist < PINCH_ON) {
            pinched = true
            VirtualMouse.button(true, true)
            leftDown = true
            Log.d(TAG, "pinch down")
        } else if (pinched && pinchDist > PINCH_OFF) {
            pinched = false
            VirtualMouse.button(true, false)
            leftDown = false
            Log.d(TAG, "pinch up")
        }

        // ---- 光标: ☝️ 食指模式跟随指尖 ----
        if (gesture == GestureId.POINT) {
            val tipX = px(8)
            val tipY = py(8)
            if (!hasSmooth) {
                emaX = tipX; emaY = tipY; hasSmooth = true
            } else {
                emaX += (tipX - emaX) * SMOOTH
                emaY += (tipY - emaY) * SMOOTH
            }
            // 归一化 (中央区域) → 屏幕目标
            val nx = ((emaX / screenW) - MARGIN) / (1 - 2 * MARGIN)
            val ny = ((emaY / screenH) - MARGIN) / (1 - 2 * MARGIN)
            val tx = (nx.coerceIn(0f, 1f)) * screenW
            val ty = (ny.coerceIn(0f, 1f)) * screenH
            val dx = (tx - curX) * CURSOR_GAIN
            val dy = (ty - curY) * CURSOR_GAIN
            val stepX = dx.toInt().coerceIn(-CURSOR_MAX_STEP, CURSOR_MAX_STEP)
            val stepY = dy.toInt().coerceIn(-CURSOR_MAX_STEP, CURSOR_MAX_STEP)
            if (stepX != 0 || stepY != 0) {
                VirtualMouse.rel(stepX, stepY)
                curX = (curX + stepX).coerceIn(0f, screenW - 1f)
                curY = (curY + stepY).coerceIn(0f, screenH - 1f)
            }
        } else {
            hasSmooth = false
        }

        // ---- 右键 + 滚轮: ✌️ 两指 ----
        if (gesture == GestureId.VICTORY) {
            if (!vActive) {
                vActive = true
                // 出现瞬间 = 右键单击 (down+up 脉冲)
                VirtualMouse.button(false, true)
                VirtualMouse.button(false, false)
                Log.d(TAG, "right click")
                hasWheelY = false
            } else {
                // 按住: 指尖纵向位移 = 滚轮
                val tipY = py(12)
                if (!hasWheelY) { lastWheelY = tipY; hasWheelY = true }
                wheelAcc += tipY - lastWheelY
                lastWheelY = tipY
                while (abs(wheelAcc) >= WHEEL_STEP_PX) {
                    val dir = if (wheelAcc > 0) -1 else 1  // 手往下(y+) = 滚轮向下
                    VirtualMouse.wheel(dir)
                    wheelAcc += if (wheelAcc > 0) -WHEEL_STEP_PX else WHEEL_STEP_PX
                }
            }
        } else {
            vActive = false
            hasWheelY = false
            wheelAcc = 0f
        }
    }
}
