package com.rk3588.gesturelib.sim

import com.rk3588.gesturelib.SmParams
import org.junit.Ignore
import org.junit.Test

/**
 * 参数网格扫描 (调参时手动运行):
 * 1. 去掉 @Ignore
 * 2. ./gradlew :gesturelib:testReleaseUnitTest --tests "com.rk3588.gesturelib.sim.SweepTest"
 * 3. 按得分挑最优参数, 回写 SmParams / GestureConfig / SimClassifier(及 C++) 默认值
 * 4. 恢复 @Ignore, 用 GestureSimulationTest 做回归门禁
 */
@Ignore("调参时手动移除此注解运行; 日常构建只跑 GestureSimulationTest")
class SweepTest {

    @Test
    fun sweep() {
        val scenarios = Scenarios.all(typeCount = 6, seedBase = 77)

        // ---- 基线 (当前生产默认值) ----
        val baseline = GestureSim(SmParams()).runSuite(scenarios, seedBase = 1000)
        println("=== 基线 (当前默认参数) ===")
        println(baseline.summary())
        baseline.failures().forEach { println("  ✗ ${it.scenario}: ${it.detail} [FP=${it.falsePositives}]") }

        // ---- 网格扫描 ----
        data class Cell(
            val p: SmParams, val thumbRatio: Float,
            val rate: Double, val fp: Int, val latency: Long, val score: Double,
        )

        val cells = mutableListOf<Cell>()
        for (swipeTh in floatArrayOf(0.13f, 0.16f, 0.20f)) {
            for (stopMs in longArrayOf(110, 130, 160)) {
                for (swingMin in floatArrayOf(0.05f, 0.06f, 0.08f)) {
                    for (confirm in intArrayOf(3, 4)) {
                        for (vConfirm in intArrayOf(3, 6, 9)) {
                            for (thumb in floatArrayOf(0.65f, 0.75f)) {
                                val p = SmParams(
                                    confirmFrames = confirm,
                                    victoryConfirmMin = vConfirm,
                                    swipeThreshold = swipeTh,
                                    swipeStopConfirmMs = stopMs,
                                    waveSwingMin = swingMin,
                                )
                                val r = GestureSim(p, thumbRatio = thumb)
                                    .runSuite(scenarios, seedBase = 1000)
                                cells.add(Cell(p, thumb, r.detectionRate, r.totalFp,
                                    r.avgLatencyMs, r.score))
                            }
                        }
                    }
                }
            }
        }

        println("\n=== 网格扫描: ${cells.size} 组合 ===")
        cells.sortedByDescending { it.score }.take(12).forEach { c ->
            println("score=${"%.1f".format(c.score)}  检出=${"%.1f".format(c.rate * 100)}%  " +
                    "FP=${c.fp}  延迟=${c.latency}ms  " +
                    "swipeTh=${c.p.swipeThreshold} stop=${c.p.swipeStopConfirmMs} " +
                    "swing=${c.p.waveSwingMin} confirm=${c.p.confirmFrames} " +
                    "vConfirm=${c.p.victoryConfirmMin} thumb=${c.thumbRatio}")
        }

        println("\n=== 误触最少的组合 (检出≥99% 中取 FP 最小) ===")
        cells.filter { it.rate >= 0.99 }.sortedBy { it.fp * 1000 + it.latency }.take(8)
            .forEach { c ->
                println("FP=${c.fp}  检出=${"%.1f".format(c.rate * 100)}%  " +
                        "延迟=${c.latency}ms  score=${"%.1f".format(c.score)}  " +
                        "swipeTh=${c.p.swipeThreshold} stop=${c.p.swipeStopConfirmMs} " +
                        "swing=${c.p.waveSwingMin} confirm=${c.p.confirmFrames} " +
                        "vConfirm=${c.p.victoryConfirmMin} thumb=${c.thumbRatio}")
            }
    }
}
