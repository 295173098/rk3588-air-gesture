package com.rk3588.gesturelib.sim

import com.rk3588.gesturelib.GestureId
import kotlin.math.sqrt

/**
 * C++ classify_gesture() 的 Kotlin 镜像 (仿真用)。
 *
 * ⚠️ 必须与 gesturelib/src/main/jni/gesture_npu.cpp 的 classify_gesture() 保持同步:
 * 修改 C++ 侧几何阈值时, 同步修改这里, 否则仿真结果与真机不符。
 *
 * 输入: 21*3 图像归一化关键点 (x,y ∈ [0,1], z 忽略), 与 RknnBridge.OUT_LM 一致。
 * 输出: GestureId (0无 1张掌 2拳头 3剪刀手)。
 */
object SimClassifier {

    // ---- 几何阈值 (与 C++ 一致; 扫描确认后的最优值回写 C++) ----
    /** 四指伸直判定: dist(腕,tip) > dist(腕,pip) × extendedRatio */
    const val EXTENDED_RATIO = 1.15f
    /** 拇指伸出判定: dist(拇指尖,小指MCP) > 掌长 × thumbRatio */
    const val THUMB_RATIO = 0.65f
    /**
     * 剪刀手加严: 无名指/小指需"明确卷曲" (dist(腕,tip) < dist(腕,pip) × victoryCurlRatio)。
     * NaN = 关闭 (与当前 C++ 行为一致)。用于抑制 张掌→握拳 过渡期误判成剪刀手。
     */
    const val VICTORY_CURL_RATIO = Float.NaN

    fun classify(
        lm: FloatArray,
        imgW: Int = 640,
        imgH: Int = 480,
        extendedRatio: Float = EXTENDED_RATIO,
        thumbRatio: Float = THUMB_RATIO,
        victoryCurlRatio: Float = VICTORY_CURL_RATIO,
    ): Int {
        fun px(i: Int) = lm[3 * i] * imgW
        fun py(i: Int) = lm[3 * i + 1] * imgH
        fun dist(a: Int, b: Int): Float {
            val dx = px(a) - px(b)
            val dy = py(a) - py(b)
            return sqrt(dx * dx + dy * dy)
        }
        fun extended(pip: Int, tip: Int) = dist(0, tip) > dist(0, pip) * extendedRatio
        fun curled(pip: Int, tip: Int) = dist(0, tip) < dist(0, pip) * victoryCurlRatio

        val palm = dist(0, 9)
        if (palm < 1f) return GestureId.NONE

        val idx = extended(6, 8)
        val mid = extended(10, 12)
        val rng = extended(14, 16)
        val pky = extended(18, 20)
        val thm = dist(4, 17) > palm * thumbRatio

        val four = (if (idx) 1 else 0) + (if (mid) 1 else 0) +
                (if (rng) 1 else 0) + (if (pky) 1 else 0)
        if (four == 4) return GestureId.PALM
        if (four == 0 && !thm) return GestureId.FIST
        if (idx && mid && !rng && !pky) {
            // 剪刀手加严: 无名指/小指必须明确卷曲 (NaN 时跳过)
            if (victoryCurlRatio.isNaN() || (curled(14, 16) && curled(18, 20))) {
                return GestureId.VICTORY
            }
        }
        return GestureId.NONE
    }
}
