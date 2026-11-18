package com.rk3588.gesturelib

/** 手势 Id (与 RknnBridge/分类器一致) */
object GestureId {
    const val NONE = 0
    const val PALM = 1      // ✋ 张掌 (挥动 = 方向滑动)
    const val FIST = 2      // ✊ 拳头 = 确定
    const val VICTORY = 3   // ✌️ 剪刀手 = 返回
    const val WAVE = 4      // 👋 挥动手臂 (张掌左右往复摆动, 时序检测)

    val names = arrayOf("无", "✋ 张掌", "✊ 拳头", "✌️ 剪刀手", "👋 挥手")
    val dirNames = arrayOf("", "上", "下", "左", "右")
}

/** 每帧识别结果 (实时回调) */
class FrameResult(
    val gesture: Int,       // GestureId
    val score: Float,       // landmark presence 置信度
    val palmValid: Boolean, // 本帧掌框是否有效
    val palmX: Float, val palmY: Float,   // 掌心 (图像归一化)
    val palmW: Float, val palmH: Float,   // 掌框宽高
    val landmarks: FloatArray,            // 21*3 (x,y 图像归一化 + z)
    val frameMs: Float      // 单帧推理耗时
)

/** 实时手势识别回调接口 */
interface GestureListener {
    /** 每帧回调 (相机线程, ~30Hz, 勿做耗时操作) */
    fun onFrame(result: FrameResult) {}

    /** 手势触发点回调: 无论该手势是否配置了动作都会回调 (只识别模式也触发)
     *  @param dir 滑动方向: 0无 1上 2下 3左 4右 (方向滑动/挥手时有效) */
    fun onGestureTriggered(gesture: Int, dir: Int, defaultAction: GestureAction) {}

    /** 内置动作执行完成回调 */
    fun onActionExecuted(action: GestureAction) {}
}

/**
 * 动作处理器: 设置后由外部完全接管动作执行 (库不再执行内置动作)。
 * 返回 true 表示已处理。
 */
fun interface ActionHandler {
    fun handle(action: GestureAction, gesture: Int, dir: Int): Boolean
}
