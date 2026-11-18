package com.rk3588.gesturelib.sim

import com.rk3588.gesturelib.GestureId
import com.rk3588.gesturelib.SmParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归门禁: 生产默认参数下, 仿真指标不得退化。
 * 场景/噪声模型见 sim 包; 调参流程见 SweepTest。
 *
 * ⚠️ 若改动状态机或分类器导致本测试失败:
 *  1. 先确认失败是逻辑回归还是模型假设变化
 *  2. 跑 SweepTest 看新参数是否必要
 *  3. 修改门禁阈值需在提交说明中写明原因
 */
class GestureSimulationTest {

    private val params = SmParams()   // 生产默认值
    private val sim = GestureSim(params)

    private fun suite(typeCount: Int) = sim.runSuite(
        Scenarios.all(typeCount = typeCount, seedBase = 1234))

    @Test
    fun `整体检出率与误触在门禁内`() {
        val r = suite(typeCount = 12)
        println("门禁跑分: ${r.summary()}")
        r.evals.forEach { e ->
            if (e.detectionRate < 1.0 || e.falsePositives > 0)
                println("  ✗ ${e.scenario}: ${e.detail} [FP=${e.falsePositives}]")
        }
        assertTrue("检出率 ${r.detectionRate} < 0.95", r.detectionRate >= 0.95)
        assertTrue("误触 ${r.totalFp} > 6", r.totalFp <= 6)
    }

    @Test
    fun `滑动四个方向全部检出且方向正确`() {
        val scenarios = Scenarios.all(typeCount = 12, seedBase = 1234)
            .filter { it.name.startsWith("swipe_") }
        val r = sim.runSuite(scenarios, seedBase = 2000)
        assertEquals("滑动检出率必须 100%", 1.0, r.detectionRate, 1e-9)
        assertEquals("滑动场景不得有误触", 0, r.totalFp)
    }

    @Test
    fun `挥手检出且不误触方向滑动`() {
        val scenarios = Scenarios.all(typeCount = 12, seedBase = 1234)
            .filter { it.name.startsWith("wave") }
        val r = sim.runSuite(scenarios, seedBase = 3000)
        assertEquals("挥手检出率必须 100%", 1.0, r.detectionRate, 1e-9)
        assertEquals("挥手场景不得误触滑动/其他", 0, r.totalFp)
    }

    @Test
    fun `张掌到握拳过渡不得误触返回`() {
        val scenarios = Scenarios.all(typeCount = 12, seedBase = 1234)
            .filter { it.name.startsWith("palm_to_fist") }
        val r = sim.runSuite(scenarios, seedBase = 4000)
        val back = r.evals.sumOf { it.falsePositives }
        assertEquals("握拳过渡误触返回 (victoryConfirmMin 不足或分类器过松)", 0, back)
        assertEquals("过渡结束应触发 1 次确定", 1.0, r.detectionRate, 1e-9)
    }

    @Test
    fun `张掌静置与反复开合不得连发误触`() {
        val scenarios = Scenarios.all(typeCount = 12, seedBase = 1234)
            .filter { it.name.startsWith("idle") || it.name.startsWith("open_close") }
        val r = sim.runSuite(scenarios, seedBase = 5000)
        assertEquals("静置/开合场景不得有误触", 0, r.totalFp)
        assertTrue("开合确认次数超限", r.detectionRate >= 0.99)
    }
}
