package com.rk3588.gesturelib

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log

/**
 * 无障碍服务：全局注入滑动/点击手势与系统级操作（返回/主页）。
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

    /** 注入一次点击 (屏幕中心偏移) */
    fun tap(xRatio: Float = 0.5f, yRatio: Float = 0.5f) {
        val path = Path().apply {
            moveTo(screenW * xRatio, screenH * yRatio)
            lineTo(screenW * xRatio, screenH * yRatio)
        }
        dispatch(path, 60)
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
    fun globalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
}
