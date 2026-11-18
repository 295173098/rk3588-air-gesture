package com.rk3588.gesturelib.sim

import com.rk3588.gesturelib.GestureId
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * 仿真场景库: 用 SimHand 生成带噪声的手部序列, 覆盖
 * 正向 (该触发必须触发) 与负向 (不该触发必须安静) 两类场景。
 *
 * 每个场景由随机种子参数化 (起点/速度/幅度/噪声), N 个种子 = N 次独立试验。
 */
object Scenarios {
    const val FRAME_MS = 33L
    const val FPS = 30

    /** 期望事件 (触发点回调的 gesture+dir 组合) */
    class Expect(
        val gesture: Int, val dir: Int,
        val min: Int = 1, val max: Int = Int.MAX_VALUE,
    )

    class Scenario(
        val name: String,
        /** 返回 (readyMs, 帧序列): readyMs = 姿势就绪时刻 (测触发延迟用) */
        val build: (Random) -> Pair<Long, List<SimFrame>>,
        val expect: List<Expect>,
    )

    // ------------------------------------------------------------------
    // 帧序列构建工具
    // ------------------------------------------------------------------

    class Timeline {
        val frames = mutableListOf<SimFrame>()

        fun hold(pose: SimHand.Pose, ms: Long) {
            repeat((ms / FRAME_MS).toInt()) { frames.add(SimFrame(true, pose)) }
        }

        fun absent(ms: Long) {
            repeat((ms / FRAME_MS).toInt()) { frames.add(SimFrame(false, null)) }
        }

        /** 移动手腕 (easeOut: 快出慢停, 模拟甩动) */
        fun move(from: SimHand.Pose, to: SimHand.Pose, ms: Long): Long {
            val n = (ms / FRAME_MS).toInt()
            repeat(n) { i ->
                val u = (i + 1f) / n
                val e = 1f - (1f - u) * (1f - u)
                val x = from.wristX + (to.wristX - from.wristX) * e
                val y = from.wristY + (to.wristY - from.wristY) * e
                frames.add(SimFrame(true, SimHand.Pose(x, y, from.dirDeg, from.palmLen,
                    from.curls, from.thumbOut)))
            }
            return n * FRAME_MS
        }

        /**
         * 姿势过渡 (握拳/展开): 四指按 生物学顺序 依次卷曲
         * (小指→无名指→中指→食指, [stag]=0 同时卷, 0.6=明显先后),
         * 拇指在过渡中段内收。
         */
        fun transition(from: SimHand.Pose, to: SimHand.Pose, ms: Long, stag: Float = 0.5f): Long {
            val n = (ms / FRAME_MS).toInt()
            // 各指起始进度比例: 小指 0, 无名指 0.18, 中指 0.40, 食指 0.62 (× stag)
            val starts = floatArrayOf(0.62f, 0.40f, 0.18f, 0f)
            repeat(n) { i ->
                val u = (i + 1f) / n
                val curls = FloatArray(4) { f ->
                    val local = ((u - starts[f] * stag) / 0.42f).coerceIn(0f, 1f)
                    from.curls[f] + (to.curls[f] - from.curls[f]) * local
                }
                val thumbOut = if (u < 0.5f) from.thumbOut else to.thumbOut
                frames.add(SimFrame(true, SimHand.Pose(from.wristX, from.wristY,
                    from.dirDeg, from.palmLen, curls, thumbOut)))
            }
            return n * FRAME_MS
        }

        /** 正弦横摆 (挥手) */
        fun waveX(pose: SimHand.Pose, ampPx: Float, freqHz: Float, ms: Long): Long {
            val n = (ms / FRAME_MS).toInt()
            repeat(n) { i ->
                val t = (i + 1) * FRAME_MS / 1000.0
                val x = pose.wristX + ampPx * sin(2 * PI * freqHz * t).toFloat()
                val y = pose.wristY + (ampPx * 0.12f) * sin(4 * PI * freqHz * t).toFloat()
                frames.add(SimFrame(true, SimHand.Pose(x, y, pose.dirDeg, pose.palmLen,
                    pose.curls, pose.thumbOut)))
            }
            return n * FRAME_MS
        }
    }

    // ------------------------------------------------------------------
    // 随机取值工具
    // ------------------------------------------------------------------

    private fun Random.f(min: Float, max: Float) = min + nextFloat() * (max - min)
    private fun Random.i(min: Int, max: Int) = (min + nextInt(max - min + 1)).toLong()

    private fun randomPalm(rnd: Random, x: Float? = null, y: Float? = null): SimHand.Pose {
        val dir = -90f + rnd.f(-12f, 12f)   // 手指大体朝上, 带自然倾斜
        return SimHand.Pose(
            x ?: rnd.f(180f, 460f), y ?: rnd.f(240f, 400f),
            dir, rnd.f(75f, 110f),
        )
    }

    // ------------------------------------------------------------------
    // 场景定义
    // ------------------------------------------------------------------

    /** 汇总场景集: typeCount = 每类场景的随机种子数 (多次独立试验取统计) */
    fun all(typeCount: Int = 10, @Suppress("UNUSED_PARAMETER") seedBase: Int = 42): List<Scenario> {
        val single = singlePass()
        val list = mutableListOf<Scenario>()
        repeat(typeCount) { i ->
            single.forEach { d -> list.add(Scenario("${d.name}#$i", d.build, d.expect)) }
        }
        return list
    }

    private fun singlePass(): List<Scenario> {
        val list = mutableListOf<Scenario>()

        // ---- 正向: 4 方向滑动 ----
        val dirs = listOf(
            "up" to (0f to -1f), "down" to (0f to 1f),
            "left" to (-1f to 0f), "right" to (1f to 0f),
        )
        for ((dirName, axis) in dirs) {
            val dirId = when (dirName) {
                "up" -> 1; "down" -> 2; "left" -> 3; else -> 4
            }
            list.add(Scenario("swipe_$dirName", { rnd ->
                val tl = Timeline()
                val pose = randomPalm(rnd)
                // 纵向阈值按画面高 (0.16*480=77px), 横向按宽 (0.16*640=102px)
                val dist = if (axis.first != 0f) rnd.f(115f, 165f) else rnd.f(90f, 135f)
                val from = pose
                val to = SimHand.Pose(pose.wristX + axis.first * dist,
                    pose.wristY + axis.second * dist, pose.dirDeg, pose.palmLen)
                tl.hold(pose, rnd.i(150, 300))
                val moveMs = tl.move(from, to, rnd.i(130, 240).toLong())
                tl.hold(to, rnd.i(350, 550))
                tl.absent(rnd.i(100, 200))
                (moveMs + rnd.i(150, 300).toLong()) to tl.frames
            }, listOf(Expect(GestureId.PALM, dirId))))
        }

        // ---- 正向: 挥手 (常规幅度) ----
        list.add(Scenario("wave", { rnd ->
            val tl = Timeline()
            val pose = randomPalm(rnd, x = rnd.f(240f, 400f))
            tl.hold(pose, rnd.i(100, 200))
            val dur = tl.waveX(pose, rnd.f(25f, 40f), rnd.f(1.5f, 2.5f), rnd.i(1200, 2000).toLong())
            tl.hold(pose, rnd.i(300, 500))
            (dur / 2) to tl.frames
        }, listOf(
            Expect(GestureId.WAVE, 0, min = 1, max = 3),
            Expect(GestureId.PALM, 1, min = 0, max = 0),
            Expect(GestureId.PALM, 2, min = 0, max = 0),
            Expect(GestureId.PALM, 3, min = 0, max = 0),
            Expect(GestureId.PALM, 4, min = 0, max = 0),
        )))

        // ---- 正向: 挥手后停住 (不得误触滑动) ----
        list.add(Scenario("wave_then_hold", { rnd ->
            val tl = Timeline()
            val pose = randomPalm(rnd, x = rnd.f(240f, 400f))
            tl.hold(pose, 150)
            tl.waveX(pose, rnd.f(28f, 40f), rnd.f(1.8f, 2.4f), 1400)
            tl.hold(pose, 600)
            700L to tl.frames
        }, listOf(
            Expect(GestureId.WAVE, 0, min = 1, max = 3),
            Expect(GestureId.PALM, 1, min = 0, max = 0),
            Expect(GestureId.PALM, 2, min = 0, max = 0),
            Expect(GestureId.PALM, 3, min = 0, max = 0),
            Expect(GestureId.PALM, 4, min = 0, max = 0),
        )))

        // ---- 正向: 大幅挥手 (单段摆幅超过滑动阈值) ----
        list.add(Scenario("wave_big", { rnd ->
            val tl = Timeline()
            val pose = randomPalm(rnd, x = rnd.f(260f, 380f))
            tl.hold(pose, 100)
            tl.waveX(pose, rnd.f(56f, 66f), rnd.f(1.6f, 2.2f), 1600)
            tl.hold(pose, 600)
            800L to tl.frames
        }, listOf(
            Expect(GestureId.WAVE, 0, min = 1, max = 4),
            Expect(GestureId.PALM, 1, min = 0, max = 0),
            Expect(GestureId.PALM, 2, min = 0, max = 0),
            Expect(GestureId.PALM, 3, min = 0, max = 0),
            Expect(GestureId.PALM, 4, min = 0, max = 0),
        )))

        // ---- 正向: 拳头 = 确定 ----
        list.add(Scenario("fist_confirm", { rnd ->
            val tl = Timeline()
            tl.absent(rnd.i(50, 150))
            val pose = SimHand.Pose(rnd.f(200f, 440f), rnd.f(260f, 400f),
                -90f + rnd.f(-10f, 10f), rnd.f(75f, 110f))
            val appear = SimHand.Pose(pose.wristX + 30f, pose.wristY + 30f,
                pose.dirDeg, pose.palmLen)
            tl.move(appear.fist(), pose.fist(), rnd.i(120, 200).toLong())
            tl.hold(pose.fist(), rnd.i(700, 1000))
            300L to tl.frames
        }, listOf(
            Expect(GestureId.FIST, 0, min = 1, max = 1),
        )))

        // ---- 正向: 剪刀手 = 返回 (从静止出现) ----
        list.add(Scenario("victory_back", { rnd ->
            val tl = Timeline()
            tl.absent(rnd.i(50, 150))
            val pose = SimHand.Pose(rnd.f(200f, 440f), rnd.f(260f, 400f),
                -90f + rnd.f(-10f, 10f), rnd.f(75f, 110f))
            val appear = SimHand.Pose(pose.wristX + 30f, pose.wristY + 30f,
                pose.dirDeg, pose.palmLen)
            tl.move(appear.victory(), pose.victory(), rnd.i(120, 200).toLong())
            tl.hold(pose.victory(), rnd.i(700, 1000))
            300L to tl.frames
        }, listOf(
            Expect(GestureId.VICTORY, 0, min = 1, max = 1),
        )))

        // ---- 正向: 张掌 → 剪刀手 (刻意从张掌比 V, 必须能触发) ----
        list.add(Scenario("palm_to_victory", { rnd ->
            val tl = Timeline()
            val pose = randomPalm(rnd)
            tl.hold(pose.palm(), rnd.i(300, 500))
            val vPose = SimHand.Pose(pose.wristX, pose.wristY, pose.dirDeg, pose.palmLen,
                floatArrayOf(0f, 0f, 0.9f, 0.9f), thumbOut = false)
            tl.transition(pose.palm(), vPose, 250)
            tl.hold(vPose, rnd.i(700, 1000))
            tl.absent(150)
            650L to tl.frames
        }, listOf(
            Expect(GestureId.VICTORY, 0, min = 1, max = 1),
            Expect(GestureId.FIST, 0, min = 0, max = 0),
        )))

        // ---- 负向: 张掌静置 (抖动+缓漂移) 不得触发 ----
        list.add(Scenario("idle_palm", { rnd ->
            val tl = Timeline()
            val pose = randomPalm(rnd)
            repeat((1500 / FRAME_MS).toInt()) { i ->
                val jx = pose.wristX + Scenarios.gauss(rnd, 3f)
                val jy = pose.wristY + Scenarios.gauss(rnd, 3f)
                tl.frames.add(SimFrame(true,
                    SimHand.Pose(jx, jy, pose.dirDeg, pose.palmLen)))
            }
            Int.MAX_VALUE.toLong() to tl.frames
        }, listOf()))

        // ---- 负向: 拳头静置 → 恰好 1 次确定 (不连发) ----
        list.add(Scenario("idle_fist", { rnd ->
            val tl = Timeline()
            val pose = SimHand.Pose(rnd.f(200f, 440f), rnd.f(260f, 400f),
                -90f, rnd.f(75f, 110f))
            tl.hold(pose.fist(), 1300)
            Int.MAX_VALUE.toLong() to tl.frames
        }, listOf(
            Expect(GestureId.FIST, 0, min = 1, max = 1),
        )))

        // ---- 负向: 张掌→握拳 过渡 (慢/快两种), 不得误触返回 ----
        for ((tag, closeMs) in listOf("fast" to 250L, "slow" to 600L)) {
            list.add(Scenario("palm_to_fist_$tag", { rnd ->
                val tl = Timeline()
                val pose = randomPalm(rnd)
                tl.hold(pose.palm(), rnd.i(300, 500))
                tl.transition(pose.palm(), pose.fist(), closeMs, stag = 0.5f)
                tl.hold(pose.fist(), rnd.i(700, 900))
                tl.absent(150)
                (400 + closeMs) to tl.frames
            }, listOf(
                Expect(GestureId.FIST, 0, min = 1, max = 1),
                Expect(GestureId.VICTORY, 0, min = 0, max = 0),
                Expect(GestureId.PALM, 1, min = 0, max = 0),
                Expect(GestureId.PALM, 2, min = 0, max = 0),
                Expect(GestureId.PALM, 3, min = 0, max = 0),
                Expect(GestureId.PALM, 4, min = 0, max = 0),
            )))
        }

        // ---- 负向: 反复张掌-握拳 (抓取式), 确定不连发且不误触 ----
        list.add(Scenario("open_close_cycles", { rnd ->
            val tl = Timeline()
            val pose = randomPalm(rnd)
            repeat(3) {
                tl.hold(pose.palm(), 400)
                tl.transition(pose.palm(), pose.fist(), 300)
                tl.hold(pose.fist(), 300)
                tl.transition(pose.fist(), pose.palm(), 300)
            }
            tl.absent(100)
            Int.MAX_VALUE.toLong() to tl.frames
        }, listOf(
            Expect(GestureId.FIST, 0, min = 1, max = 2),
            Expect(GestureId.VICTORY, 0, min = 0, max = 0),
            Expect(GestureId.WAVE, 0, min = 0, max = 0),
        )))

        // ---- 负向: 斜向滑动 → 至多 1 次 (方向不摇摆) ----
        list.add(Scenario("diagonal_swipe", { rnd ->
            val tl = Timeline()
            val pose = randomPalm(rnd)
            val dx = rnd.f(115f, 150f) * (if (rnd.nextBoolean()) 1f else -1f)
            val dy = rnd.f(85f, 115f) * (if (rnd.nextBoolean()) 1f else -1f)
            val to = SimHand.Pose(pose.wristX + dx, pose.wristY + dy,
                pose.dirDeg, pose.palmLen)
            tl.hold(pose, 200)
            tl.move(pose, to, rnd.i(150, 250).toLong())
            tl.hold(to, rnd.i(350, 500))
            tl.absent(150)
            450L to tl.frames
        }, listOf(
            Expect(GestureId.PALM, 1, min = 0, max = 1),
            Expect(GestureId.PALM, 2, min = 0, max = 1),
            Expect(GestureId.PALM, 3, min = 0, max = 1),
            Expect(GestureId.PALM, 4, min = 0, max = 1),
        )))

        // ---- 负向: 快速横穿画面 → 至多 1 次 ----
        list.add(Scenario("fast_traverse", { rnd ->
            val tl = Timeline()
            val y = rnd.f(260f, 400f)
            val from = SimHand.Pose(rnd.f(80f, 140f), y, -90f, rnd.f(75f, 100f))
            val to = SimHand.Pose(from.wristX + rnd.f(380f, 460f) *
                    (if (rnd.nextBoolean()) 1f else -1f), y, -90f, from.palmLen)
            tl.hold(from, 120)
            tl.move(from, to, rnd.i(180, 260).toLong())
            tl.hold(to, 400)
            tl.absent(120)
            420L to tl.frames
        }, listOf(
            Expect(GestureId.PALM, 1, min = 0, max = 1),
            Expect(GestureId.PALM, 2, min = 0, max = 1),
            Expect(GestureId.PALM, 3, min = 0, max = 1),
            Expect(GestureId.PALM, 4, min = 0, max = 1),
        )))

        return list
    }

    fun gauss(rnd: Random, sigma: Float): Float = SimHand.gauss(rnd, sigma)
}
