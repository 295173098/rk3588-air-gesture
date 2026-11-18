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
 *  - 静态姿势需连续 [SmParams.confirmFrames] 帧确认后触发
 *  - 滑动与挥手的区分: 滑动 = 单次大幅位移后停住; 持续摆动 = 挥手
 *  - 任一动作触发后 actionGapMs 内不再触发 (全局动作间隔)
 *
 * 调参: 所有阈值集中在 [SmParams], 生产走 GestureConfig,
 * 离线仿真直接注入固定参数扫描最优组合。
 */
class GestureStateMachine(
    private val listener: (action: GestureAction, gesture: Int, dir: Int) -> Unit,
    private val params: () -> SmParams = { SmParams.fromConfig() }
) {

    companion object {
        private const val TAG = "GestureSM"
        private const val SWIPE_PENDING_MAX_MS = 800L   // 挂起超时 (慢速漂移不计滑动)
        private const val STALE_MS = 300L               // 轨迹陈旧阈值 (手离开后重回时重置)
    }

    /** 调试钩子: 仿真/排查时注入, 生产置 null (内部状态流转逐条上报) */
    internal var debugTrace: ((String) -> Unit)? = null
    private fun trace(msg: String) { debugTrace?.invoke(msg) }

    // 确认状态
    private var lastRawGesture = 0
    private var lastRealGesture = 0                // 最近一个非 NONE 手势 (区分抖动与真实切换)
    private var confirmCount = 0
    private var absentSince: Long? = null          // 连续无掌心框起始时刻 (手离开去抖)

    // 冷却 (null = 尚未触发过任何动作; 修复启动初期 uptimeMillis 接近 0 时被全局间隔误拦)
    private var lastActionTime = mutableMapOf<String, Long>()
    private var lastAnyAction: Long? = null
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
        lastRealGesture = 0
        absentSince = null
        lastActionTime.clear()
        lastAnyAction = null
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
        val p = params()

        // ---- 确认窗口 (静态姿势需连续帧) ----
        if (gesture == lastRawGesture && gesture != GestureId.NONE) {
            confirmCount++
        } else {
            confirmCount = if (gesture == GestureId.NONE) 0 else 1
            lastRawGesture = gesture
            if (gesture != lastTriggerGesture) lastTriggerGesture = 0
        }

        // ---- 运动状态的生命周期 ----
        // 单帧 NONE = 置信度抖动/短暂丢帧, 保留轨迹 (仿真证明: 清了会把滑动/挥手截断)
        // 切换到不同真实手势 = 运动语义切换, 清轨迹
        if (gesture != GestureId.NONE && gesture != lastRealGesture) {
            palmHistory.clear()
            pendingAxis = 0
            hasLastPos = false
            lastSwipeDir = 0
        }
        if (gesture != GestureId.NONE) lastRealGesture = gesture

        // 手离开去抖: 连续 absentClearMs 无掌心框才判定真离开并清轨迹;
        // 偶发丢帧 (1~5 帧) 冻结轨迹原地等待, 手一回来滑动/挥手继续
        if (palmValid) {
            absentSince = null
        } else {
            val a = absentSince ?: now.also { absentSince = it }
            if (now - a >= p.absentClearMs &&
                (palmHistory.isNotEmpty() || pendingAxis != 0 || hasLastPos)
            ) {
                trace("t=$now absent-clear (hand gone ${now - a}ms)")
                palmHistory.clear()
                pendingAxis = 0
                hasLastPos = false
            }
        }

        val confirmed = confirmCount >= effectiveThreshold(gesture, p)

        when (gesture) {
            GestureId.PALM -> {
                // 无掌心坐标 (置信度不足) 无法追踪运动, 清轨迹
                if (!palmValid) {
                    palmHistory.clear()
                    pendingAxis = 0
                    hasLastPos = false
                    return
                }
                // 轨迹陈旧 (手离开较久后重回): 重置后再开始新轨迹
                val lastT = palmHistory.lastOrNull()?.first
                if (lastT != null && now - lastT > STALE_MS) {
                    palmHistory.clear()
                    pendingAxis = 0
                    hasLastPos = false
                    lastSwipeDir = 0
                }
                // 运动追踪不等确认帧: 抖动后重新确认期间也要继续记轨迹,
                // 否则短促挥动会在 220ms 参考窗建立前结束 (仿真发现的漏检根因)
                if (confirmed || lastRealGesture == GestureId.PALM) {
                    trackPalmMotion(palmX, palmY, now, gesture, p)
                }
            }

            GestureId.FIST -> if (confirmed) {
                fireOnce(GestureAction.CONFIRM, "confirm", now, gesture, p)
            }
            GestureId.VICTORY -> if (confirmed) {
                fireOnce(GestureAction.BACK, "back", now, gesture, p)
            }
        }
    }

    /** 单次触发类手势 (拳头确定/剪刀手返回): 手势保持期间不重复, 切换手势后才可再触发 */
    private fun fireOnce(
        action: GestureAction, key: String, now: Long, gesture: Int, p: SmParams
    ) {
        if (lastTriggerGesture != gesture &&
            canFire(key, p.cooldownMs.toLong(), now, p)
        ) {
            lastTriggerGesture = gesture
            mark(key, now)
            listener(action, gesture, 0)
        }
    }

    /**
     * 张掌运动分析 (确认帧后逐帧调用):
     *  1. 挥手: X 轴往复摆动, 窗口内折返 ≥ waveMinSwings 次 → 触发
     *  2. 方向滑动: 位移超阈值进入挂起, 反向回移取消 (判为摆动), 停住后触发
     * 挥手优先级高于滑动 —— 持续摆动永远不会误触方向滑动。
     */
    private fun trackPalmMotion(
        x: Float, y: Float, now: Long, gesture: Int, p: SmParams
    ) {
        // 帧间移动标记 (滑动停止确认用); 阈值须高于掌心框抖动, 否则永远凑不出静止窗口
        if (hasLastPos && (abs(x - lastX) > p.moveEps || abs(y - lastY) > p.moveEps)) {
            lastMoveT = now
            trace("t=$now move (lastMoveT=$lastMoveT)")
        }
        lastX = x; lastY = y; hasLastPos = true

        palmHistory.add(now to (x to y))
        while (palmHistory.isNotEmpty() && now - palmHistory.first().first > p.waveWindowMs) {
            palmHistory.removeAt(0)
        }

        // ---- 1. 挥手检测 (X 轴往复) ----
        if (countXSwings(p) >= p.waveMinSwings) {
            palmHistory.clear()
            pendingAxis = 0
            hasLastPos = false
            if (canFire("wave", p.waveCooldownMs, now, p)) {
                mark("wave", now)
                lastTriggerGesture = gesture
                Log.i(TAG, "WAVE (x-axis oscillation)")
                listener(GestureAction.NONE, GestureId.WAVE, 0)
            }
            return
        }

        // ---- 2. 方向滑动 ----
        if (pendingAxis == 0) {
            val ref = palmHistory.firstOrNull {
                now - it.first >= p.swipeWindowMs
            } ?: return
            val dx = x - ref.second.first
            val dy = y - ref.second.second
            val thresh = p.swipeThreshold
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
                trace("t=$now pending axis=$pendingAxis dir=$pendingDir dx=$dx dy=$dy")
            }
            return
        }

        // 挂起中: 反向位移 → 取消 (判为摆动回移, 不是滑动)
        val back = if (pendingAxis == 1) x - pendingX else y - pendingY
        val reversed = (pendingDir == 3 || pendingDir == 1)  // 已向左/上
        val backAmt = if (reversed) back else -back          // 反向时为正
        if (backAmt > p.swipeThreshold * p.swipeReversalRatio) {
            trace("t=$now pending CANCEL reversal backAmt=$backAmt")
            pendingAxis = 0
            return
        }
        // 挂起超时 (慢速漂移)
        if (now - pendingCrossT > SWIPE_PENDING_MAX_MS) {
            trace("t=$now pending TIMEOUT")
            pendingAxis = 0
            return
        }
        // 停止确认: 超过 swipeStopConfirmMs 无显著移动 → 触发
        if (now - lastMoveT >= p.swipeStopConfirmMs &&
            canFire("swipe", p.cooldownMs.toLong(), now, p)
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
                trace("t=$now swipe FIRE dir=$pendingDir")
                listener(action, gesture, pendingDir)
            } else {
                trace("t=$now swipe BLOCKED sameDir lastSwipeDir=$lastSwipeDir")
            }
            pendingAxis = 0
            hasLastPos = false
        }
    }

    /** X 轴摆动折返计数: 位移方向反转且单段摆幅 ≥ waveSwingMin 算一次折返 */
    private fun countXSwings(p: SmParams): Int {
        if (palmHistory.size < 3) return 0
        var swings = 0
        var dir = 0                                  // 0未定向 1右 -1左
        var pivot = palmHistory.first().second.first // 当前方向的极值点
        for ((_, pos) in palmHistory) {
            val x = pos.first
            when (dir) {
                0 -> when {
                    x - pivot >= p.waveSwingMin -> { dir = 1; pivot = x }
                    pivot - x >= p.waveSwingMin -> { dir = -1; pivot = x }
                }
                1 -> when {
                    x >= pivot -> pivot = x
                    pivot - x >= p.waveSwingMin -> { swings++; dir = -1; pivot = x }
                }
                else -> when {
                    x <= pivot -> pivot = x
                    x - pivot >= p.waveSwingMin -> { swings++; dir = 1; pivot = x }
                }
            }
        }
        return swings
    }

    /** 拳头/剪刀手是易误触动作, 需要更长的持续确认 */
    private fun effectiveThreshold(gesture: Int, p: SmParams): Int = when (gesture) {
        GestureId.FIST -> maxOf(p.confirmFrames, p.fistConfirmMin)
        GestureId.VICTORY -> maxOf(p.confirmFrames, p.victoryConfirmMin)
        else -> p.confirmFrames
    }

    private fun canFire(key: String, interval: Long, now: Long, p: SmParams): Boolean {
        // 全局动作间隔: 上一个动作触发后 actionGapMs 内一律不再触发
        val any = lastAnyAction
        if (any != null && now - any < p.actionGapMs) return false
        val last = lastActionTime[key] ?: return true
        return now - last >= interval
    }

    private fun mark(key: String, now: Long) {
        lastActionTime[key] = now
        lastAnyAction = now
    }
}
