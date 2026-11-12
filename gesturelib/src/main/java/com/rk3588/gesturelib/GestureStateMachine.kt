package com.rk3588.gesturelib

import android.util.Log

/**
 * 时序状态机：把每帧的原始手势变成经过防抖/冷却的动作事件。
 * 只负责"何时触发"，动作是否执行由 GestureEngine 的映射决策决定
 * (这样"只识别不动作"模式下 onGestureTriggered 回调依然生效)。
 *
 * 规则:
 *  - 静态手势需连续 [GestureConfig.confirmFrames] 帧确认后触发
 *  - 音量手势按住可连击 (间隔 volumeRepeatMs)
 *  - 张掌(PALM)期间追踪掌心位移，超过阈值触发上滑/下滑
 *  - 张掌→握拳 快速序列 = 抓取(截图)；直接举拳 = 锁定切换
 *  - 任一动作触发后 actionGapMs 内不再触发 (全局动作间隔)
 */
class GestureStateMachine(
    private val listener: (action: GestureAction, gesture: Int, dir: Int) -> Unit
) {

    companion object {
        private const val TAG = "GestureSM"
        private const val SWIPE_WINDOW_MS = 220L      // 位移采样窗口
        private const val GRAB_HISTORY_MS = 800L      // 张开度历史窗口
        private const val GRAB_OPEN_MIN = 4           // 起点: ≥4 指张开
        private const val GRAB_CLOSED_MAX = 1         // 终点: ≤1 指 (半收拢也算)
        private const val GRAB_DROP_MIN_MS = 120L     // 收拢最短耗时 (滤抖动)
        private const val GRAB_DROP_MAX_MS = 700L     // 收拢最长耗时
    }

    // 确认状态
    private var lastRawGesture = 0
    private var lastDir = 0
    private var confirmCount = 0

    // 冷却
    private var lastActionTime = mutableMapOf<String, Long>()
    private var lastAnyAction = 0L                 // 全局动作间隔基准
    private var lastTriggerGesture = 0             // 已触发过的手势(用于单次触发)
    private var gestureHoldStart = 0L              // 当前确认手势的起始时间

    // 滑动追踪
    private var swipeHistory = mutableListOf<Pair<Long, Float>>() // (t, palmY)
    private var lastSwipeDir = 0                    // 0无 1上 2下

    // 抓取 (截图): 张开度下降沿检测 (≥4指 快速收拢到 ≤1指, 无需握拳姿势)
    private val grabHistory = mutableListOf<Pair<Long, Int>>()

    // 锁定
    var locked = false
        private set

    fun reset() {
        confirmCount = 0
        lastRawGesture = 0
        lastDir = 0
        swipeHistory.clear()
        lastSwipeDir = 0
        grabHistory.clear()
    }

    /** 每帧调用: gesture=识别结果, dir=点指方向, palmY=掌心 y, openness=张开度(-1=无效) */
    fun update(gesture: Int, dir: Int, palmY: Float?, openness: Int, now: Long) {
        // ---- 抓取检测: 逐帧独立于手势分类 (锁定时不响应) ----
        if (!locked) detectGrab(openness, now)

        // ---- 确认窗口 (手势+方向 都一致才算连续) ----
        if (gesture == lastRawGesture && dir == lastDir && gesture != GestureId.NONE) {
            confirmCount++
        } else {
            confirmCount = if (gesture == GestureId.NONE) 0 else 1
            lastRawGesture = gesture
            lastDir = dir
            if (gesture != lastTriggerGesture) {
                lastTriggerGesture = 0
                gestureHoldStart = 0
                swipeHistory.clear()
                lastSwipeDir = 0
            }
        }
        val confirmed = confirmCount >= effectiveThreshold(gesture)
        if (!confirmed) return

        if (gestureHoldStart == 0L) gestureHoldStart = now

        // ---- 锁定逻辑 ----
        if (locked) {
            if (gesture == GestureId.FIST && canFire("lock", GestureConfig.cooldownMs.toLong(), now)) {
                locked = false
                lastTriggerGesture = gesture
                mark("lock", now)
                Log.i(TAG, "UNLOCK")
                listener(GestureAction.LOCK, gesture, 0)
            }
            return
        }

        when (gesture) {
            GestureId.FIST -> {
                // 锁定切换 (抓取=截图已由张开度下降沿独立检测)
                if (canFire("lock", GestureConfig.cooldownMs.toLong(), now) &&
                    lastTriggerGesture != gesture) {
                    locked = true
                    lastTriggerGesture = gesture
                    mark("lock", now)
                    Log.i(TAG, "LOCK")
                    listener(GestureAction.LOCK, gesture, 0)
                }
            }

            GestureId.THUMB_UP -> fireVolume(GestureAction.VOL_UP, now, gesture)
            GestureId.THUMB_DOWN -> fireVolume(GestureAction.VOL_DOWN, now, gesture)

            GestureId.VICTORY -> {
                if (canFire("back", GestureConfig.cooldownMs.toLong(), now) &&
                    lastTriggerGesture != gesture) {
                    lastTriggerGesture = gesture
                    mark("back", now)
                    listener(GestureAction.BACK, gesture, 0)
                }
            }

            GestureId.POINT -> {
                // ☝️ 指向 = 方向键, 按住连发
                if (dir != 0 && canFire("dpad", GestureConfig.dpadRepeatMs.toLong(), now)) {
                    lastTriggerGesture = gesture
                    mark("dpad", now)
                    val action = when (dir) {
                        1 -> GestureAction.DPAD_UP
                        2 -> GestureAction.DPAD_DOWN
                        3 -> GestureAction.DPAD_LEFT
                        else -> GestureAction.DPAD_RIGHT
                    }
                    listener(action, gesture, dir)
                }
            }

            GestureId.PALM -> trackSwipe(palmY, now, gesture)
        }
    }

    /** 音量: 首次触发后若手势保持则按 volumeRepeatMs 连击 */
    private fun fireVolume(action: GestureAction, now: Long, gesture: Int) {
        val key = if (action == GestureAction.VOL_UP) "vol_up" else "vol_down"
        val interval = if (GestureConfig.volumeRepeat) GestureConfig.volumeRepeatMs.toLong()
        else GestureConfig.cooldownMs.toLong()
        if (canFire(key, interval, now)) {
            lastTriggerGesture = gesture
            mark(key, now)
            listener(action, gesture, 0)
        }
    }

    /**
     * 抓取检测: 张开度从 ≥4 指 快速收拢到 ≤1 指 (下降沿)。
     * 不要求最终姿势是握拳 —— 半收拢、拇指留存都算。
     * 触发后清历史, 需重新张开才会再触发。
     */
    private fun detectGrab(openness: Int, now: Long) {
        if (openness < 0) {  // 无效帧 (无手/低置信度): 清历史
            grabHistory.clear()
            return
        }
        grabHistory.add(now to openness)
        while (grabHistory.isNotEmpty() && now - grabHistory.first().first > GRAB_HISTORY_MS) {
            grabHistory.removeAt(0)
        }
        val maxOpen = grabHistory.maxOfOrNull { it.second } ?: return
        if (maxOpen < GRAB_OPEN_MIN || openness > GRAB_CLOSED_MAX) return
        // 最近一次全开时刻
        val tOpen = grabHistory.lastOrNull { it.second >= GRAB_OPEN_MIN }?.first ?: return
        val dropMs = now - tOpen
        if (dropMs < GRAB_DROP_MIN_MS || dropMs > GRAB_DROP_MAX_MS) return
        if (!GestureConfig.enableGrab) return
        if (!canFire("shot", GestureConfig.cooldownMs.toLong(), now)) return
        mark("shot", now)
        lastTriggerGesture = GestureId.FIST  // 抑制随后的握拳锁定
        grabHistory.clear()                  // 需重新张开才会再触发
        Log.i(TAG, "GRAB (openness drop) -> SCREENSHOT")
        listener(GestureAction.SCREENSHOT, GestureId.FIST, 0)
    }

    /** 张掌滑动检测 */
    private fun trackSwipe(palmY: Float?, now: Long, gesture: Int) {
        if (palmY == null) return
        swipeHistory.add(now to palmY)
        while (swipeHistory.isNotEmpty() && now - swipeHistory.first().first > SWIPE_WINDOW_MS * 2) {
            swipeHistory.removeAt(0)
        }
        val oldest = swipeHistory.firstOrNull { now - it.first >= SWIPE_WINDOW_MS } ?: return
        val dy = palmY - oldest.second
        val thresh = GestureConfig.swipeThreshold
        if (kotlin.math.abs(dy) >= thresh) {
            val dir = if (dy < 0) 1 else 2  // y 减小 = 手向上挥 = 上滑
            // 同方向需手势离开后才能再次触发, 防止持续挥动连发
            if (dir != lastSwipeDir && canFire("swipe", GestureConfig.cooldownMs.toLong(), now)) {
                lastSwipeDir = dir
                lastTriggerGesture = gesture
                mark("swipe", now)
                swipeHistory.clear()
                Log.i(TAG, "swipe dir=$dir dy=$dy")
                listener(
                    if (dir == 1) GestureAction.SWIPE_UP else GestureAction.SWIPE_DOWN,
                    gesture, 0
                )
            }
        }
    }

    /** 锁定/解锁是元动作，需要更长的持续确认，避免误触发 */
    private fun effectiveThreshold(gesture: Int): Int =
        if (gesture == GestureId.FIST) maxOf(GestureConfig.confirmFrames, 8)
        else GestureConfig.confirmFrames

    private fun canFire(key: String, interval: Long, now: Long): Boolean {
        // 全局动作间隔: 上一个动作触发后 actionGapMs 内一律不再触发
        if (now - lastAnyAction < GestureConfig.actionGapMs) return false
        val last = lastActionTime[key] ?: 0L
        return now - last >= interval
    }

    private fun mark(key: String, now: Long) {
        lastActionTime[key] = now
        lastAnyAction = now
    }
}
