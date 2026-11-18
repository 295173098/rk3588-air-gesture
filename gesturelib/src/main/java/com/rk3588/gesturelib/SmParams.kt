package com.rk3588.gesturelib

/**
 * 手势状态机可调参数。
 *
 * 生产环境默认从 [GestureConfig] 实时读取 (见 [fromConfig]);
 * 离线仿真 (gesturelib/src/test 的 GestureSim/SweepTest) 扫描出的最优值
 * 已固化到这里: victoryConfirmMin=9 / waveSwingMin=0.05 / moveEps=0.012 /
 * absentClearMs=200 —— 仿真指标 100% 检出 / 0 误触 / 平均延迟 ~55ms。
 */
data class SmParams(
    // ---- 确认/冷却 ----
    /** 静态姿势确认帧数 */
    val confirmFrames: Int = 3,
    /** 拳头(确定)最少确认帧 (易误触, 单独加严) */
    val fistConfirmMin: Int = 6,
    /** 剪刀手(返回)最少确认帧: 握拳过渡期会短暂呈现剪刀手形态,
     *  9 帧 (~300ms) 保证过渡窗口 < 确认时长, 仿真 0 误触 */
    val victoryConfirmMin: Int = 9,
    /** 普通动作冷却 ms */
    val cooldownMs: Int = 900,
    /** 全局动作间隔 ms (任一动作后, 间隔内不再触发) */
    val actionGapMs: Int = 3000,

    // ---- 方向滑动 ----
    /** 触发位移阈值 (归一化画面比例, 越小越灵敏) */
    val swipeThreshold: Float = 0.16f,
    /** 位移采样窗口 ms */
    val swipeWindowMs: Long = 220L,
    /** 停止确认时长 ms (位移超阈值后需停住这么久才触发, 区分滑动与持续挥动) */
    val swipeStopConfirmMs: Long = 130L,
    /** 挂起期间反向位移超过 阈值×该比例 → 取消挂起 (判为摆动回移) */
    val swipeReversalRatio: Float = 0.45f,
    /** 帧间位移超过该值(归一化)才算"移动中" (须高于掌心框抖动幅度) */
    val moveEps: Float = 0.012f,
    /** 连续无掌心框超过该时长才判定"手已离开"并清轨迹 (容忍偶发丢帧) */
    val absentClearMs: Long = 200L,

    // ---- 挥手 (X 轴往复摆动) ----
    /** 摆动历史窗口 ms */
    val waveWindowMs: Long = 1000L,
    /** 单段摆幅阈值 (图像宽度比例) */
    val waveSwingMin: Float = 0.05f,
    /** 折返次数阈值 (一次完整往返 = 2 次折返) */
    val waveMinSwings: Int = 2,
    /** 挥手触发最小间隔 ms */
    val waveCooldownMs: Long = 1500L,
) {
    companion object {
        /** 生产环境: 实时读取 GestureConfig (其余项用默认值) */
        fun fromConfig() = SmParams(
            confirmFrames = GestureConfig.confirmFrames,
            cooldownMs = GestureConfig.cooldownMs,
            actionGapMs = GestureConfig.actionGapMs,
            swipeThreshold = GestureConfig.swipeThreshold,
        )
    }
}
