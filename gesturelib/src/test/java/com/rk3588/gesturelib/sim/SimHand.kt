package com.rk3588.gesturelib.sim

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 参数化 21 关键点手掌生成器 (仿真用)。
 *
 * 在 640×480 像素空间生成再归一化, 与真实管线 (gesture_npu.cpp) 的输入一致。
 * 几何模型:
 *  - L = 腕→中指MCP 掌长, 默认 90px (手占画面高 ~1/4, 对应 40~60cm 观察距离)
 *  - 四指从掌向伸出, 卷曲度 curl ∈ [0,1] 控制伸直→握紧插值
 *  - 拇指两态: 外张 (张掌) / 内收跨握 (拳头、剪刀手)
 *
 * 噪声模型 (模拟 MediaPipe 关键点抖动):
 *  - 每个关键点独立高斯抖动 sigmaPx (默认 2.5px ≈ 0.4% 画面宽)
 */
class SimHand(private val imgW: Int = 640, private val imgH: Int = 480) {

    /** 一次手掌姿态 */
    class Pose(
        val wristX: Float,             // 腕 (像素)
        val wristY: Float,
        /** 手指指向 (屏幕角, 0=右 90=下 -90=上) */
        val dirDeg: Float = -90f,
        val palmLen: Float = 90f,
        /** 四指卷曲度 [index, mid, ring, pinky] 0=伸直 1=握紧 */
        val curls: FloatArray = floatArrayOf(0f, 0f, 0f, 0f),
        /** 拇指是否外张 */
        val thumbOut: Boolean = true,
    ) {
        fun palm(): Pose = Pose(wristX, wristY, dirDeg, palmLen,
            floatArrayOf(0f, 0f, 0f, 0f), thumbOut = true)
        fun fist(): Pose = Pose(wristX, wristY, dirDeg, palmLen,
            floatArrayOf(1f, 1f, 1f, 1f), thumbOut = false)
        fun victory(): Pose = Pose(wristX, wristY, dirDeg, palmLen,
            floatArrayOf(0f, 0f, 0.9f, 0.9f), thumbOut = false)
    }

    /** 生成 21*3 归一化关键点 (含高斯噪声) */
    fun landmarks(pose: Pose, rnd: Random, sigmaPx: Float = 2.5f): FloatArray {
        val lm = FloatArray(63)
        val pts = Array(21) { FloatArray(2) }
        val a = pose.dirDeg * PI / 180.0
        val dx = cos(a).toFloat(); val dy = sin(a).toFloat()  // 屏幕坐标 (y 向下)
        val px = -dy; val py = dx                 // 垂直方向
        val L = pose.palmLen
        val wx = pose.wristX; val wy = pose.wristY

        // 腕
        pts[0][0] = wx; pts[0][1] = wy

        // 四指: (MCP,PIP,DIP,TIP 索引, 横向偏移比例, 相对掌向的展开角)
        val fingers = arrayOf(
            intArrayOf(5, 6, 7, 8), intArrayOf(9, 10, 11, 12),
            intArrayOf(13, 14, 15, 16), intArrayOf(17, 18, 19, 20)
        )
        val offsets = floatArrayOf(-0.42f, 0f, 0.40f, 0.75f)
        val bends = floatArrayOf(-8f, 0f, 8f, 18f)
        for (i in 0..3) {
            val t = pose.curls[i].coerceIn(0f, 1f)
            val mx = wx + dx * L + px * offsets[i] * L
            val my = wy + dy * L + py * offsets[i] * L
            val fa = a + bends[i] * PI / 180.0
            val fdx = cos(fa).toFloat(); val fdy = sin(fa).toFloat()
            // 伸直: pip 0.42L, dip 0.60L, tip 0.78L (沿指向)
            // 握紧: pip 0.28L, dip 0.20L+回折, tip 收回到 MCP 附近 (略向腕侧)
            val pipK = lerp(0.42f, 0.28f, t)
            val dipK = lerp(0.60f, 0.20f, t)
            val tipK = lerp(0.78f, -0.12f, t)
            val backK = 0.10f * t                 // 握紧时指尖向腕侧收
            set(pts, fingers[i][0], mx, my)
            set(pts, fingers[i][1], mx + fdx * pipK * L, my + fdy * pipK * L)
            set(pts, fingers[i][2], mx + fdx * dipK * L + dx * backK * L * 0.5f,
                my + fdy * dipK * L + dy * backK * L * 0.5f)
            set(pts, fingers[i][3], mx + fdx * tipK * L + dx * backK * L,
                my + fdy * tipK * L + dy * backK * L)
        }

        // 拇指链 (1=CMC 2=MCP 3=IP 4=TIP)
        if (pose.thumbOut) {
            val k = arrayOf(floatArrayOf(0.10f, -0.30f), floatArrayOf(0.30f, -0.50f),
                floatArrayOf(0.42f, -0.62f), floatArrayOf(0.55f, -0.70f))
            for (j in 0..3) set(pts, j + 1, wx + dx * k[j][0] * L + px * k[j][1] * L,
                wy + dy * k[j][0] * L + py * k[j][1] * L)
        } else {
            // 内收跨握: 拇指尖落在拳心偏小指侧 → dist(拇指尖,小指MCP) ≈ 0.54L
            val k = arrayOf(floatArrayOf(0.15f, -0.25f), floatArrayOf(0.40f, -0.15f),
                floatArrayOf(0.58f, 0.05f), floatArrayOf(0.70f, 0.30f))
            for (j in 0..3) set(pts, j + 1, wx + dx * k[j][0] * L + px * k[j][1] * L,
                wy + dy * k[j][0] * L + py * k[j][1] * L)
        }

        // 高斯噪声 + 归一化
        for (i in 0..20) {
            lm[3 * i] = ((pts[i][0] + gauss(rnd, sigmaPx)) / imgW)
            lm[3 * i + 1] = ((pts[i][1] + gauss(rnd, sigmaPx)) / imgH)
            lm[3 * i + 2] = 0f
        }
        return lm
    }

    /** 掌心检测框中心 (模拟 palm_detection 输出, 独立抖动 σ=2px) */
    fun palmCenter(pose: Pose, rnd: Random, sigmaPx: Float = 2f): Pair<Float, Float> {
        val a = pose.dirDeg * PI / 180.0
        val cx = pose.wristX + cos(a) * pose.palmLen * 0.5 + gauss(rnd, sigmaPx)
        val cy = pose.wristY + sin(a) * pose.palmLen * 0.5 + gauss(rnd, sigmaPx)
        return (cx / imgW).toFloat() to (cy / imgH).toFloat()
    }

    private fun set(pts: Array<FloatArray>, i: Int, x: Float, y: Float) {
        pts[i][0] = x; pts[i][1] = y
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        /** Box-Muller 高斯噪声 */
        fun gauss(rnd: Random, sigma: Float): Float {
            val u1 = (1.0 - rnd.nextDouble()).coerceAtLeast(1e-12)
            val u2 = rnd.nextDouble()
            return (sigma * kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) *
                    kotlin.math.cos(2 * PI * u2)).toFloat()
        }
    }
}
