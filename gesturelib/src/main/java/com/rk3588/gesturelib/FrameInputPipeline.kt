package com.rk3588.gesturelib

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 算法帧输入管线 (库内部)。
 *
 * 两条路径:
 *  1. 单帧分析 [analyzeBlocking] / [analyzeAsync] —— 一张图片进, 一份 [FrameDetection] 出,
 *     不经过时序状态机, 不触发动作。
 *  2. 连续外部帧 [start] + [pushBitmap]/[pushYuv] —— 与相机识别同构: NPU → 时序状态机 →
 *     实时回调 + 手势触发, 只是帧来源换成调用方。
 *
 * 与相机识别互斥 (共用同一套 RKNN 上下文和跟踪状态); NPU 模型按需加载,
 * 不依赖 [GestureService] 是否运行。
 */
internal object FrameInputPipeline {
    private const val TAG = "GestureFrameInput"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gesture-frame-input").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    private val out = RknnBridge.allocOut()

    @Volatile
    private var sm: GestureStateMachine? = null

    @Volatile
    var active = false
        private set

    // ------------------------------------------------------------------
    // 生命周期 (连续帧模式)
    // ------------------------------------------------------------------

    @Synchronized
    fun start(ctx: Context): Boolean {
        if (GestureService.running) {
            Log.w(TAG, "相机识别运行中, 外部帧模式不可同时启用")
            return false
        }
        if (!ensureInit(ctx)) return false
        val machine = sm ?: GestureStateMachine(
            listener = { action, gesture, dir ->
                GestureEngine.dispatchAction(action, gesture, dir)
            }
        ).also { sm = it }
        machine.reset()
        active = true
        Log.i(TAG, "frame input started")
        return true
    }

    fun stop() {
        active = false
        synchronized(this) { sm = null }
        Log.i(TAG, "frame input stopped")
    }

    // ------------------------------------------------------------------
    // 连续帧输入
    // ------------------------------------------------------------------

    /** 送入一张 Bitmap 帧 (异步; 上一帧未处理完时自动丢帧)。返回 false = 未启用/丢帧。 */
    fun pushBitmap(ctx: Context, bitmap: Bitmap): Boolean {
        if (!active) return false
        if (!busy.compareAndSet(false, true)) return false
        executor.execute {
            try {
                if (!ensureInit(ctx)) return@execute
                val rc = RknnBridge.processBitmap(toArgb(bitmap), false, out)
                if (rc != 0) {
                    Log.e(TAG, "bitmap 推理错误 rc=$rc")
                    return@execute
                }
                handleFrame(out)
            } catch (e: Exception) {
                Log.e(TAG, "帧处理异常", e)
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    /** 送入一帧 YUV420_888 (异步; 上一帧未处理完时自动丢帧)。返回 false = 未启用/丢帧。 */
    fun pushYuv(
        ctx: Context,
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        width: Int, height: Int,
        yStride: Int, uStride: Int, vStride: Int,
        uPixelStride: Int, vPixelStride: Int
    ): Boolean {
        if (!active) return false
        if (!busy.compareAndSet(false, true)) return false
        executor.execute {
            try {
                if (!ensureInit(ctx)) return@execute
                val rc = RknnBridge.process(
                    y, u, v, width, height,
                    yStride, uStride, vStride, uPixelStride, vPixelStride, out
                )
                if (rc != 0) {
                    Log.e(TAG, "yuv 推理错误 rc=$rc")
                    return@execute
                }
                handleFrame(out)
            } catch (e: Exception) {
                Log.e(TAG, "帧处理异常", e)
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    /** 与相机线程一致: 时序状态机 + 实时回调 (识别框由相机服务负责, 此处不涉及) */
    private fun handleFrame(o: FloatArray) {
        if (!active) return   // 帧处理期间已 stop, 不再回调
        val gesture = o[RknnBridge.OUT_GESTURE].toInt()
        val palmValid = o[RknnBridge.OUT_PALM_VALID] > 0.5f
        sm?.update(
            gesture, palmValid,
            o[RknnBridge.OUT_PALM_X], o[RknnBridge.OUT_PALM_Y],
            SystemClock.uptimeMillis()
        )
        GestureEngine.notifyFrame(o)
    }

    // ------------------------------------------------------------------
    // 单帧分析
    // ------------------------------------------------------------------

    /** 阻塞推理 (勿在主线程调用)。返回 null = 推理失败。 */
    fun analyzeBlocking(ctx: Context, bitmap: Bitmap): FrameDetection? {
        if (!ensureInit(ctx)) return null
        val o = RknnBridge.allocOut()
        val rc = RknnBridge.processBitmap(toArgb(bitmap), true, o)
        if (rc != 0) {
            Log.e(TAG, "bitmap 推理错误 rc=$rc")
            return null
        }
        return FrameDetection.from(o)
    }

    /** 异步推理, 回调在后台线程。 */
    fun analyzeAsync(ctx: Context, bitmap: Bitmap, callback: FrameAnalyzer) {
        executor.execute {
            callback.onResult(
                try {
                    analyzeBlocking(ctx, bitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "图片推理异常", e)
                    null
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun ensureInit(ctx: Context): Boolean {
        if (RknnBridge.ready) return true
        val rc = RknnBridge.init(ctx.assets)
        if (rc != 0) Log.e(TAG, "NPU 初始化失败 rc=$rc (检查 assets/models/*.rknn)")
        return rc == 0
    }

    /** JNI 只接受 RGBA_8888 内存布局; 其他配置 (565/HARDWARE) 先转一份 */
    private fun toArgb(bitmap: Bitmap): Bitmap =
        if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
}
