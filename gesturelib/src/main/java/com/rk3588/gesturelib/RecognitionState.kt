package com.rk3588.gesturelib

/**
 * 每帧识别结果的不可变快照，供预览叠加层 / UI 轮询读取。
 */
object RecognitionState {

    class Snapshot(
        val gestureId: Int,
        val score: Float,
        val palmValid: Boolean,
        val palmCx: Float,
        val palmCy: Float,
        val palmW: Float,
        val palmH: Float,
        val landmarks: FloatArray,   // 21*3, 图像归一化
        val ms: Float
    )

    @Volatile
    var latest: Snapshot? = null
        private set

    fun update(out: FloatArray) {
        latest = Snapshot(
            out[RknnBridge.OUT_GESTURE].toInt(),
            out[RknnBridge.OUT_SCORE],
            out[RknnBridge.OUT_PALM_VALID] > 0.5f,
            out[RknnBridge.OUT_PALM_X],
            out[RknnBridge.OUT_PALM_Y],
            out[RknnBridge.OUT_PALM_W],
            out[RknnBridge.OUT_PALM_H],
            out.copyOfRange(RknnBridge.OUT_LM, RknnBridge.OUT_LM + 63),
            out[RknnBridge.OUT_MS]
        )
    }

    fun clear() {
        latest = null
    }
}
