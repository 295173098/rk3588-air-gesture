package com.rk3588.gesturelib

import android.content.res.AssetManager

/**
 * JNI 桥接：RK3588 NPU 手势推理
 *
 * 输出数组布局 (72 floats):
 *  [0] 单帧总耗时 ms
 *  [1] 手势 id: 0无 1张掌 2握拳 3V 4指 5拇指上 6拇指下
 *  [2] landmark presence 分数
 *  [3] 左右手分数
 *  [4] 本帧 palm 检测是否有效 (1/0)
 *  [5][6] palm 中心 (图像归一化)
 *  [7][8] palm 宽高 (图像归一化)
 *  [9..71] 21 关键点 x,y (图像归一化) + z
 */
object RknnBridge {
    init {
        System.loadLibrary("gesture_npu")
    }

    const val G_NONE = 0
    const val G_PALM = 1
    const val G_FIST = 2
    const val G_VICTORY = 3
    const val G_POINT = 4
    const val G_THUMB_UP = 5
    const val G_THUMB_DOWN = 6

    // 输出数组偏移 (与 gesture_npu.cpp 中 OUT_* 一致)
    const val OUT_MS = 0
    const val OUT_GESTURE = 1
    const val OUT_SCORE = 2
    const val OUT_HAND = 3
    const val OUT_PALM_VALID = 4
    const val OUT_PALM_X = 5
    const val OUT_PALM_Y = 6
    const val OUT_PALM_W = 7
    const val OUT_PALM_H = 8
    const val OUT_LM = 9
    const val OUT_DIR = 72
    const val OUT_OPEN = 73

    /** 点指方向 (与 C++ OUT_DIR 一致) */
    val dirNames = arrayOf("", "上", "下", "左", "右")

    val gestureNames = arrayOf(
        "无", "✋ 张掌", "✊ 握拳", "✌️ V", "☝️ 指", "👍 拇指上", "👎 拇指下"
    )

    fun init(am: AssetManager): Int = nativeInit(am)
    fun deinit() = nativeDeinit()
    fun allocOut(): FloatArray = nativeAllocOut()

    fun process(
        y: java.nio.ByteBuffer, u: java.nio.ByteBuffer, v: java.nio.ByteBuffer,
        width: Int, height: Int,
        yStride: Int, uStride: Int, vStride: Int,
        uPixStride: Int, vPixStride: Int,
        out: FloatArray
    ): Int = nativeProcessFrame(y, u, v, width, height, yStride, uStride, vStride,
        uPixStride, vPixStride, out)

    fun setFlipH(v: Boolean) = nativeSetFlipH(v)

    /** 注册预览双缓冲位图：推理线程写后台缓冲后原子切换 */
    fun setPreviewBitmaps(a: android.graphics.Bitmap, b: android.graphics.Bitmap) =
        nativeSetPreviewBitmaps(a, b)

    /** UI 线程调用：把当前帧快照拷入绘制用位图（锁内，无撕裂） */
    fun copyPreview(dst: android.graphics.Bitmap): Boolean = nativeCopyPreview(dst)

    fun fps(): Float = nativeFps()

    private external fun nativeInit(assetManager: AssetManager): Int
    private external fun nativeDeinit()
    private external fun nativeAllocOut(): FloatArray
    private external fun nativeProcessFrame(
        y: java.nio.ByteBuffer, u: java.nio.ByteBuffer, v: java.nio.ByteBuffer,
        width: Int, height: Int,
        yStride: Int, uStride: Int, vStride: Int,
        uPixStride: Int, vPixStride: Int,
        out: FloatArray
    ): Int
    private external fun nativeSetFlipH(flip: Boolean)
    private external fun nativeSetPreviewBitmaps(a: android.graphics.Bitmap, b: android.graphics.Bitmap)
    private external fun nativeCopyPreview(dst: android.graphics.Bitmap): Boolean
    private external fun nativeFps(): Float
}
