package com.rk3588.gesturelib

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.util.Log

/**
 * 无障碍服务：全局注入滑动手势与系统级操作（返回）。
 */
class GestureAccessibility : AccessibilityService() {
    companion object {
        private const val TAG = "GestureAcc"
        @Volatile
        var instance: GestureAccessibility? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var screenW = 1080
    private var screenH = 1920

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        serviceInfo = serviceInfo.apply {
            eventTypes = android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }
        Log.i(TAG, "accessibility connected $screenW x $screenH")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 注入一个竖直滑动 */
    fun swipeVertical(fromYRatio: Float, toYRatio: Float, durationMs: Long = 220) {
        val x = screenW / 2f
        val path = Path().apply {
            moveTo(x, screenH * fromYRatio)
            lineTo(x, screenH * toYRatio)
        }
        dispatch(path, durationMs)
    }

    /** 注入一个横向滑动 */
    fun swipeHorizontal(fromXRatio: Float, toXRatio: Float, durationMs: Long = 220) {
        val y = screenH / 2f
        val path = Path().apply {
            moveTo(screenW * fromXRatio, y)
            lineTo(screenW * toXRatio, y)
        }
        dispatch(path, durationMs)
    }

    private fun dispatch(path: Path, durationMs: Long) {
        val svc = instance ?: return
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        mainHandler.post {
            val ok = svc.dispatchGesture(desc, null, null)
            Log.d(TAG, "dispatch gesture ok=$ok")
        }
    }

    fun globalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
}
