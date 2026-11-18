package com.rk3588.gesturelib

import android.util.Log
import kotlin.math.abs

/**
 * 时序状态机：把每帧的原始手势变成经过防抖/冷却的动作事件。
 * 只负责"何时触发"，动作是否执行由 GestureEngine 的映射决策决定
 * (这样"只识别不动作"模式下 onGestureTriggered 回调依然生效)。
 *
 * 手势集:
 *  - ✋ 张掌 + 挥动 → 4 方向滑动 (位移超阈值 + 停止确认, 主轴判定方向)
 *  - 👋 挥动手臂 → 张掌 X 轴往复摆动 (折返 ≥2 次), 默认只回调不动作
 *  - ✊ 拳头 → 确定 (CONFIRM)
 *  - ✌️ 剪刀手 → 返回 (BACK)
 *
 * 规则:
 *  - 静态姿势需连续 [GestureConfig.confirmFrames] 帧确认后触发
 *  - 滑动与挥手的区分: 滑动 = 单次大幅位移后停住; 持续摆动 = 挥手
 *  - 任一动作触发后 actionGapMs 内不再触发 (全局动作间隔)
 */
class GestureStateMachine(
    private val listener: (action: GestureAction, gesture: Int, dir: Int) -> Unit
) {

    companion object {
        private const val TAG = "GestureSM"
        // ---- 方向滑动 ----
        private const val SWIPE_WINDOW_MS = 220L        // 位移采样窗口
        private const val SWIPE_STOP_CONFIRM_MS = 130L  // 触发前的停止确认 (区分滑动与持续挥动)
        private const val SWIPE_PENDING_MAX_MS = 800L   // 挂起超时 (慢速漂移不计滑动)
        private const val SWIPE_REVERSAL_RATIO = 0.45f  // 反向位移超过 阈值×比例 → 取消挂起
        private const val MOVE_EPS = 0.008f             // 视为"移动中"的最小帧间位移
        // ---- 挥手 (X 轴往复摆动) ----
        private const val WAVE_WINDOW_MS = 1000L        // 摆动历史窗口
        private const val WAVE_SWING_MIN = 0.06f        // 单段摆幅 (图像宽度比例)
        private const val WAVE_MIN_SWINGS = 2           // 折返次数 (一次完整往返)
        private const val WAVE_COOLDOWN_MS = 1500L      // 挥手触发最小间隔
    }

    // 确认状态
    private var lastRawGesture = 0
    private var confirmCount = 0

    // 冷却
    private var lastActionTime = mutableMapOf<String, Long>()
    private var lastAnyAction = 0L                 // 全局动作间隔基准
    private var lastTriggerGesture = 0             // 已触发过的手势 (单次触发用)

    // 张掌运动轨迹 (t, (x, y)) 图像归一化
    private val palmHistory = mutableListOf<Pair<Long, Pair<Float, Float>>>()
    private var lastX = 0f
    private var lastY = 0f
    private var hasLastPos = false
    private var lastMoveT = 0L                     // 最近一次显著移动时间 (停止确认)

    // 滑动挂起 (位移超阈值后等待停止确认)
    private var pendingAxis = 0                    // 0无 1横 2纵
    private var pendingDir = 0                     // 1上 2下 3左 4右
    private var pendingCrossT = 0L
    private var pendingX = 0f
    private var pendingY = 0f
    private var lastSwipeDir = 0                   // 已触发方向 (同方向需手势离开后才能再次触发)

    fun reset() {
        confirmCount = 0
        lastRawGesture = 0
        lastActionTime.clear()
        lastAnyAction = 0L
        lastTriggerGesture = 0
        palmHistory.clear()
        hasLastPos = false
        lastMoveT = 0L
        pendingAxis = 0
        pendingDir = 0
        lastSwipeDir = 0
    }

    /** 每帧调用: gesture=识别结果, palmValid/palmX/palmY=掌心框 (图像归一化) */
    fun update(gesture: Int, palmValid: Boolean, palmX: Float, palmY: Float, now: Long) {
        // ---- 确认窗口 (静态姿势需连续帧) ----
        if (gesture == lastRawGesture && gesture != GestureId.NONE) {
            confirmCount++
        } else {
            confirmCount = if (gesture == GestureId.NONE) 0 else 1
            lastRawGesture = gesture
            // 手势切换: 重置单次触发与运动轨迹
            if (gesture != lastTriggerGesture) {
                lastTriggerGesture = 0
                palmHistory.clear()
                pendingAxis = 0
                hasLastPos = false
                lastSwipeDir = 0
            }
        }
        if (confirmCount < effectiveThreshold(gesture)) return

        when (gesture) {
            GestureId.PALM -> {
                // 无掌心坐标 (置信度不足) 无法追踪运动, 清轨迹
                if (!palmValid) {
                    palmHistory.clear()
                    pendingAxis = 0
                    hasLastPos = false
                    return
                }
                trackPalmMotion(palmX, palmY, now, gesture)
            }

            GestureId.FIST -> fireOnce(GestureAction.CONFIRM, "confirm", now, gesture)
            GestureId.VICTORY -> fireOnce(GestureAction.BACK, "back", now, gesture)
        }
    }

    /** 单次触发类手势 (拳头确定/剪刀手返回): 手势保持期间不重复, 切换手势后才可再触发 */
    private fun fireOnce(action: GestureAction, key: String, now: Long, gesture: Int) {
        if (lastTriggerGesture != gesture &&
            canFire(key, GestureConfig.cooldownMs.toLong(), now)
        ) {
            lastTriggerGesture = gesture
            mark(key, now)
            listener(action, gesture, 0)
        }
    }

    /**
     * 张掌运动分析 (确认帧后逐帧调用):
     *  1. 挥手: X 轴往复摆动, 窗口内折返 ≥ [WAVE_MIN_SWINGS] 次 → 触发
     *  2. 方向滑动: 位移超阈值进入挂起, 反向回移取消 (判为摆动), 停住后触发
     * 挥手优先级高于滑动 —— 持续摆动永远不会误触方向滑动。
     */
    private fun trackPalmMotion(x: Float, y: Float, now: Long, gesture: Int) {
        // 帧间移动标记 (滑动停止确认用)
        if (hasLastPos && (abs(x - lastX) > MOVE_EPS || abs(y - lastY) > MOVE_EPS)) {
            lastMoveT = now
        }
        lastX = x; lastY = y; hasLastPos = true

        palmHistory.add(now to (x to y))
        while (palmHistory.isNotEmpty() && now - palmHistory.first().first > WAVE_WINDOW_MS) {
            palmHistory.removeAt(0)
        }

        // ---- 1. 挥手检测 (X 轴往复) ----
        if (countXSwings() >= WAVE_MIN_SWINGS) {
            palmHistory.clear()
            pendingAxis = 0
            hasLastPos = false
            if (canFire("wave", WAVE_COOLDOWN_MS, now)) {
                mark("wave", now)
                lastTriggerGesture = gesture
                Log.i(TAG, "WAVE (x-axis oscillation)")
                listener(GestureAction.NONE, GestureId.WAVE, 0)
            }
            return
        }

        // ---- 2. 方向滑动 ----
        if (pendingAxis == 0) {
            val ref = palmHistory.firstOrNull { now - it.first >= SWIPE_WINDOW_MS } ?: return
            val dx = x - ref.second.first
            val dy = y - ref.second.second
            val thresh = GestureConfig.swipeThreshold
            if (abs(dx) >= thresh || abs(dy) >= thresh) {
                pendingAxis = if (abs(dx) > abs(dy)) 1 else 2
                pendingDir = when {
                    pendingAxis == 1 && dx < 0 -> 3      // 左
                    pendingAxis == 1 -> 4                // 右
                    dy < 0 -> 1                          // 上
                    else -> 2                            // 下
                }
                pendingCrossT = now
                pendingX = x; pendingY = y
            }
            return
        }

        // 挂起中: 反向位移 → 取消 (判为摆动回移, 不是滑动)
        val back = if (pendingAxis == 1) x - pendingX else y - pendingY
        val reversed = (pendingDir == 3 || pendingDir == 1)  // 已向左/上
        val backAmt = if (reversed) back else -back          // 反向时为正
        if (backAmt > GestureConfig.swipeThreshold * SWIPE_REVERSAL_RATIO) {
            pendingAxis = 0
            return
        }
        // 挂起超时 (慢速漂移)
        if (now - pendingCrossT > SWIPE_PENDING_MAX_MS) {
            pendingAxis = 0
            return
        }
        // 停止确认: 超过 [SWIPE_STOP_CONFIRM_MS] 无显著移动 → 触发
        if (now - lastMoveT >= SWIPE_STOP_CONFIRM_MS &&
            canFire("swipe", GestureConfig.cooldownMs.toLong(), now)
        ) {
            if (pendingDir != lastSwipeDir) {
                lastSwipeDir = pendingDir
                lastTriggerGesture = gesture
                mark("swipe", now)
                palmHistory.clear()
                val action = when (pendingDir) {
                    1 -> GestureAction.SWIPE_UP
                    2 -> GestureAction.SWIPE_DOWN
                    3 -> GestureAction.SWIPE_LEFT
                    else -> GestureAction.SWIPE_RIGHT
                }
                Log.i(TAG, "swipe dir=$pendingDir")
                listener(action, gesture, pendingDir)
            }
            pendingAxis = 0
            hasLastPos = false
        }
    }

    /** X 轴摆动折返计数: 位移方向反转且单段摆幅 ≥ [WAVE_SWING_MIN] 算一次折返 */
    private fun countXSwings(): Int {
        if (palmHistory.size < 3) return 0
        var swings = 0
        var dir = 0                                  // 0未定向 1右 -1左
        var pivot = palmHistory.first().second.first // 当前方向的极值点
        for ((_, pos) in palmHistory) {
            val x = pos.first
            when (dir) {
                0 -> when {
                    x - pivot >= WAVE_SWING_MIN -> { dir = 1; pivot = x }
                    pivot - x >= WAVE_SWING_MIN -> { dir = -1; pivot = x }
                }
                1 -> when {
                    x >= pivot -> pivot = x
                    pivot - x >= WAVE_SWING_MIN -> { swings++; dir = -1; pivot = x }
                }
                else -> when {
                    x <= pivot -> pivot = x
                    x - pivot >= WAVE_SWING_MIN -> { swings++; dir = 1; pivot = x }
                }
            }
        }
        return swings
    }

    /** 拳头(确定)是容易误触的动作, 需要更长的持续确认 */
    private fun effectiveThreshold(gesture: Int): Int =
        if (gesture == GestureId.FIST) maxOf(GestureConfig.confirmFrames, 6)
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
