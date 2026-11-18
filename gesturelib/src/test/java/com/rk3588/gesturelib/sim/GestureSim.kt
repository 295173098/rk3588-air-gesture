package com.rk3588.gesturelib.sim

import com.rk3588.gesturelib.GestureAction
import com.rk3588.gesturelib.GestureId
import com.rk3588.gesturelib.GestureStateMachine
import com.rk3588.gesturelib.SmParams
import kotlin.random.Random

/** 单帧输入 (镜像 GestureService.onFrame 的数据流) */
class SimFrame(val present: Boolean, val pose: SimHand.Pose?)

/** 仿真中真实触发的事件 */
class Fired(val action: GestureAction, val gesture: Int, val dir: Int, val tMs: Long)

/**
 * 离线手势仿真器: 把 SimHand 生成的关键点喂给 **生产状态机** (GestureStateMachine),
 * 分类用 SimClassifier (C++ classify_gesture 的镜像)。
 *
 * 评分: 检出率 (期望事件满足比例) / 误触 (不该发的发了) / 触发延迟。
 */
class GestureSim(
    val smParams: SmParams,
    val extendedRatio: Float = SimClassifier.EXTENDED_RATIO,
    val thumbRatio: Float = SimClassifier.THUMB_RATIO,
    val victoryCurlRatio: Float = SimClassifier.VICTORY_CURL_RATIO,
    /** 丢帧率: 手偶发丢失一帧 (置信度跌落) */
    val dropoutP: Double = 0.01,
) {
    private val hand = SimHand()

    /** 跑一个场景帧序列, 返回全部触发事件 */
    fun run(frames: List<SimFrame>, rnd: Random): List<Fired> {
        val out = mutableListOf<Fired>()
        var t = 0L
        val sm = GestureStateMachine(
            { action, gesture, dir -> out.add(Fired(action, gesture, dir, t)) },
            { smParams })
        sm.reset()
        for (f in frames) {
            if (f.present && rnd.nextDouble() >= dropoutP) {
                val lm = hand.landmarks(f.pose!!, rnd)
                val g = SimClassifier.classify(lm, extendedRatio = extendedRatio,
                    thumbRatio = thumbRatio, victoryCurlRatio = victoryCurlRatio)
                val (cx, cy) = hand.palmCenter(f.pose, rnd)
                sm.update(g, true, cx, cy, t)
            } else {
                sm.update(GestureId.NONE, false, 0f, 0f, t)
            }
            t += Scenarios.FRAME_MS
        }
        return out
    }

    // ------------------------------------------------------------------
    // 场景评估
    // ------------------------------------------------------------------

    class Eval(
        val scenario: String,
        val detected: Int,      // 满足的期望数
        val expected: Int,      // min>=1 的期望总数 (硬性期望)
        val falsePositives: Int,
        val latencyMs: Long,    // 首个硬性期望的触发延迟 (平均值; -1=无)
        val detail: String,
    ) {
        val detectionRate: Double get() = if (expected == 0) 1.0 else detected.toDouble() / expected
    }

    fun evaluate(scenario: Scenarios.Scenario, rnd: Random): Eval {
        val (readyMs, frames) = scenario.build(rnd)
        val fired = run(frames, rnd)
        val byKey = fired.groupBy { it.gesture to it.dir }

        var detected = 0
        var expected = 0
        var firstLatency = -1L
        val details = mutableListOf<String>()
        for (e in scenario.expect) {
            if (e.min >= 1) expected++
            val matched = byKey[e.gesture to e.dir]?.size ?: 0
            val ok = matched in e.min..e.max
            if (e.min >= 1) {
                if (ok) detected++
                details.add("${label(e.gesture)}${dirName(e.dir)} x$matched (期望 ${e.min}..${if (e.max == Int.MAX_VALUE) "∞" else e.max}) ${if (ok) "✓" else "✗"}")
                if (ok && firstLatency < 0 && readyMs < frames.size * Scenarios.FRAME_MS) {
                    val first = byKey[e.gesture to e.dir]?.firstOrNull()
                    if (first != null) firstLatency = (first.tMs - readyMs).coerceAtLeast(0)
                }
            } else if (!ok) {
                details.add("${label(e.gesture)}${dirName(e.dir)} x$matched (期望 ≤${e.max}) ✗")
            }
        }
        // 误触 = 未声明的 (gesture,dir) + 声明过但超出 max 的超额事件
        val declared = scenario.expect.map { it.gesture to it.dir }.toSet()
        val fpUndeclared = fired.count { (it.gesture to it.dir) !in declared }
        val fpOver = byKey.mapNotNull { (key, list) ->
            val e = scenario.expect.firstOrNull { it.gesture == key.first && it.dir == key.second }
            if (e == null) null else (list.size - e.max).coerceAtLeast(0)
        }.sum()
        return Eval(scenario.name, detected, expected, fpUndeclared + fpOver, firstLatency,
            details.joinToString("; "))
    }

    /** 跑整个场景集, 汇总指标 */
    fun runSuite(scenarios: List<Scenarios.Scenario>, seedBase: Int = 1000): SuiteReport {
        val evals = mutableListOf<Eval>()
        scenarios.forEachIndexed { idx, sc ->
            evals.add(evaluate(sc, Random(seedBase + idx * 7919L)))
        }
        return SuiteReport(evals)
    }

    class SuiteReport(val evals: List<Eval>) {
        val totalExpected = evals.sumOf { it.expected }
        val totalDetected = evals.sumOf { it.detected }
        val totalFp = evals.sumOf { it.falsePositives }
        val detectionRate: Double =
            if (totalExpected == 0) 1.0 else totalDetected.toDouble() / totalExpected
        val avgLatencyMs: Long =
            evals.filter { it.latencyMs >= 0 }.map { it.latencyMs }.average()
                .let { if (it.isNaN()) -1 else it.toLong() }

        /** 综合分: 检出率 ×100 − 误触 ×20 − 延迟惩罚 */
        val score: Double get() = detectionRate * 100 - totalFp * 20 -
                (if (avgLatencyMs > 0) avgLatencyMs * 0.02 else 0.0)

        fun failures(): List<Eval> = evals.filter {
            it.detectionRate < 1.0 || it.falsePositives > 0
        }

        fun summary(): String =
            "检出 $totalDetected/$totalExpected (${("%.1f".format(detectionRate * 100))}%)  " +
                    "误触 $totalFp  平均延迟 ${avgLatencyMs}ms  得分 ${"%.1f".format(score)}"
    }

    companion object {
        fun label(gesture: Int) = GestureId.names.getOrElse(gesture) { "?" }
        fun dirName(dir: Int) = GestureId.dirNames.getOrElse(dir) { "" }
    }
}
