package com.rk3588.gesturelib

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * 内置动作执行器: 无障碍主通道 + root 兜底。
 * 由 GestureEngine 在映射决策后调用。
 */
class ActionExecutor(private val context: Context) {
    companion object {
        private const val TAG = "ActionExecutor"
    }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun execute(action: GestureAction) {
        Log.i(TAG, "execute $action")
        when (action) {
            GestureAction.VOL_UP -> adjustVolume(true)
            GestureAction.VOL_DOWN -> adjustVolume(false)
            GestureAction.SWIPE_UP -> swipe(-1)
            GestureAction.SWIPE_DOWN -> swipe(1)
            GestureAction.BACK -> {
                if (GestureAccessibility.isConnected()) GestureAccessibility.instance?.globalBack()
                else if (GestureConfig.rootFallback) RootShell.exec("input keyevent 4")
            }
            GestureAction.HOME -> {
                if (GestureAccessibility.isConnected()) GestureAccessibility.instance?.globalHome()
                else if (GestureConfig.rootFallback) RootShell.exec("input keyevent 3")
            }
            GestureAction.CLICK -> {
                if (GestureAccessibility.isConnected()) GestureAccessibility.instance?.tap()
                else if (GestureConfig.rootFallback) {
                    val m = context.resources.displayMetrics
                    RootShell.exec("input tap ${m.widthPixels / 2} ${m.heightPixels / 2}")
                }
            }
            GestureAction.LOCK -> { /* 状态机内部处理 */ }
            // 方向键: 无障碍无法注入按键, 走 root input keyevent
            GestureAction.DPAD_UP -> dpadKey(19)
            GestureAction.DPAD_DOWN -> dpadKey(20)
            GestureAction.DPAD_LEFT -> dpadKey(21)
            GestureAction.DPAD_RIGHT -> dpadKey(22)
            // 截图: 系统截图键 (KEYCODE_SCREENSHOT)
            GestureAction.SCREENSHOT -> dpadKey(120)
            GestureAction.NONE -> {}
        }
    }

    private fun dpadKey(keycode: Int) {
        if (!RootShell.exec("input keyevent $keycode")) {
            Log.w(TAG, "keyevent $keycode failed (需要 root)")
        }
    }

    private fun adjustVolume(up: Boolean) {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun swipe(dir: Int) {
        // dir=-1 上滑(内容向上滚), dir=1 下滑
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
}
