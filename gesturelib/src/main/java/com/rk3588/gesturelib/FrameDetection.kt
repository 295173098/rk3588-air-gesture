package com.rk3588.gesturelib

/**
 * 单帧算法识别结果 —— 算法帧输入 (图片 / 外部帧) 的回调数据。
 *
 * 坐标均为图像归一化值 [0,1]，与输入帧的宽高对应；
 * 即使输入帧尺寸不是 640×480 也可直接乘宽高得到像素坐标。
 *
 * @property hasHand       本帧是否检测到手 (palm 有效或 landmark 置信度足够)
 * @property gesture       静态手势 id, 见 [GestureId]
 * @property gestureName   手势名称 (中文)
 * @property score         landmark presence 置信度 0..1
 * @property handedness    左右手分数 (sigmoid, -1 表示无输出)
 * @property openFingers   伸直手指数 (含拇指) 0..5
 * @property palmX/palmY   掌心框中心 (归一化)
 * @property palmW/palmH   掌心框宽高 (归一化)
 * @property landmarks     21×3 关键点 [x0,y0,z0, x1,y1,z1, ...], 归一化
 * @property inferenceMs   单帧 NPU 推理总耗时 (ms)
 */
class FrameDetection(
    val hasHand: Boolean,
    val gesture: Int,
    val gestureName: String,
    val score: Float,
    val handedness: Float,
    val openFingers: Int,
    val palmX: Float,
    val palmY: Float,
    val palmW: Float,
    val palmH: Float,
    val landmarks: FloatArray,
    val inferenceMs: Float
) {
    /** 第 i 个关键点 (0..20) 的 [x, y, z] 归一化坐标 */
    fun landmark(i: Int): FloatArray = landmarks.copyOfRange(i * 3, i * 3 + 3)

    companion object {
        /** 由 JNI 输出数组 (74 floats) 构造结果 */
        internal fun from(out: FloatArray): FrameDetection {
            val gesture = out[RknnBridge.OUT_GESTURE].toInt()
            val score = out[RknnBridge.OUT_SCORE]
            val palmValid = out[RknnBridge.OUT_PALM_VALID] > 0.5f
            return FrameDetection(
                hasHand = palmValid || score > 0.6f,
                gesture = gesture,
                gestureName = GestureId.names.getOrElse(gesture) { "未知" },
                score = score,
                handedness = out[RknnBridge.OUT_HAND],
                openFingers = out[RknnBridge.OUT_OPEN].toInt(),
                palmX = out[RknnBridge.OUT_PALM_X],
                palmY = out[RknnBridge.OUT_PALM_Y],
                palmW = out[RknnBridge.OUT_PALM_W],
                palmH = out[RknnBridge.OUT_PALM_H],
                landmarks = out.copyOfRange(RknnBridge.OUT_LM, RknnBridge.OUT_LM + 63),
                inferenceMs = out[RknnBridge.OUT_MS]
            )
        }
    }
}

/**
 * 算法帧结果回调。
 * @param result null = 推理失败 (NPU 未初始化 / 位图格式不支持 / 模型缺失)
 */
fun interface FrameAnalyzer {
    fun onResult(result: FrameDetection?)
}
