package com.rk3588.gesturelib

import android.content.Context
import android.util.Log

/**
 * 内置动作执行器: 无障碍主通道 + root 兜底。
 * 由 GestureEngine 在映射决策后调用。
 */
class ActionExecutor(private val context: Context) {
    companion object {
        private const val TAG = "ActionExecutor"
        private const val KEYCODE_BACK = 4
        private const val KEYCODE_DPAD_CENTER = 23   // 确定键 (焦点 OK)
    }

    fun execute(action: GestureAction) {
        Log.i(TAG, "execute $action")
        when (action) {
            // 4 方向滑动: 朝挥动方向注入屏幕滑动 (内容随手势方向滚动)
            GestureAction.SWIPE_UP -> swipeVertical(-1)
            GestureAction.SWIPE_DOWN -> swipeVertical(1)
            GestureAction.SWIPE_LEFT -> swipeHorizontal(-1)
            GestureAction.SWIPE_RIGHT -> swipeHorizontal(1)

            GestureAction.BACK -> {
                if (GestureAccessibility.isConnected()) GestureAccessibility.instance?.globalBack()
                else if (GestureConfig.rootFallback) keyEvent(KEYCODE_BACK)
            }

            // 确定: 焦点 OK 键 (KEYCODE_DPAD_CENTER), 需 root
            GestureAction.CONFIRM -> {
                if (!keyEvent(KEYCODE_DPAD_CENTER)) {
                    Log.w(TAG, "CONFIRM 需要 root (input keyevent 23)")
                }
            }

            GestureAction.NONE -> {}
        }
    }

    /** root 注入按键事件, 返回是否成功 */
    private fun keyEvent(keycode: Int): Boolean {
        if (!RootShell.exec("input keyevent $keycode")) {
            Log.w(TAG, "keyevent $keycode failed (需要 root)")
            return false
        }
        return true
    }

    /** dir=-1 上滑(内容向上滚), dir=1 下滑 */
    private fun swipeVertical(dir: Int) {
        val fromY = if (dir < 0) 0.72f else 0.28f
        val toY = if (dir < 0) 0.28f else 0.72f
        if (GestureAccessibility.isConnected()) {
            GestureAccessibility.instance?.swipeVertical(fromY, toY)
        } else if (GestureConfig.rootFallback) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            val x = m.widthPixels / 2
            val y1 = (m.heightPixels * fromY).toInt()
            val y2 = (m.heightPixels * toY).toInt()
            RootShell.exec("input swipe $x $y1 $x $y2 200")
        }
    }

    /** dir=-1 左滑(内容向左翻), dir=1 右滑 */
    private fun swipeHorizontal(dir: Int) {
        val fromX = if (dir < 0) 0.72f else 0.28f
        val toX = if (dir < 0) 0.28f else 0.72f
        if (GestureAccessibility.isConnected()) {
            GestureAccessibility.instance?.swipeHorizontal(fromX, toX)
        } else if (GestureConfig.rootFallback) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            val y = m.heightPixels / 2
            val x1 = (m.widthPixels * fromX).toInt()
            val x2 = (m.widthPixels * toX).toInt()
            RootShell.exec("input swipe $x1 $y $x2 $y 200")
        }
    }
}
