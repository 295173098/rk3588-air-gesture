package com.rk3588.gesturelib

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.Image
import android.media.ImageReader
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 库内部前台服务: 常驻后台跑相机 + NPU 推理 + 手势状态机 + 悬浮识别框。
 * 通过 GestureEngine.startRecognition/stopRecognition 控制。
 */
class GestureService : Service() {
    companion object {
        private const val TAG = "GestureService"
        private const val CHANNEL_ID = "gesture_lib_service"
        private const val NOTIF_ID = 1

        @Volatile
        var running = false
            private set

        @Volatile
        var instance: GestureService? = null
            private set
    }

    private var camera: CameraController? = null
    private val overlay: RecognitionOverlay by lazy { RecognitionOverlay(this) }
    private val sm = GestureStateMachine(
        listener = { action, gesture, dir ->
            GestureEngine.dispatchAction(action, gesture, dir)
        }
    )
    private val out = RknnBridge.allocOut()
    private val busy = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stop = intent?.getBooleanExtra("stop", false) ?: false
        if (stop) {
            stopSelfInternal()
            return START_NOT_STICKY
        }
        if (!running) {
            startForegroundInternal()
            startPipeline()
        }
        GestureEngine.overlayWanted.let { applyOverlay() }
        return START_STICKY
    }

    private fun startForegroundInternal() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.lib_channel_name),
                NotificationManager.IMPORTANCE_LOW)
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.lib_notif_title))
            .setContentText(getString(R.string.lib_notif_text))
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun startPipeline() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "无相机权限")
            stopSelfInternal()
            return
        }
        val rc = RknnBridge.init(assets)
        if (rc != 0) {
            Log.e(TAG, "NPU 初始化失败 rc=$rc (检查 assets/models/*.rknn)")
            stopSelfInternal()
            return
        }
        Log.i(TAG, "rknn init ok")
        RknnBridge.setFlipH(GestureConfig.flipH)

        running = true
        sm.reset()
        applyOverlay()

        camera = CameraController(this).apply {
            frameListener = { _, image -> onFrame(image) }
            open(GestureConfig.useFrontCamera)
        }
    }

    /** 悬浮识别框: 按 GestureEngine 的期望状态显示/隐藏 */
    fun applyOverlay() {
        if (GestureEngine.overlayWanted && running) {
            overlay.show(GestureEngine.overlayShowPreview)
        } else {
            overlay.hide()
        }
    }

    fun flashHud(text: String) {
        overlay.setStatus(text)
    }

    /** 相机线程回调 */
    private fun onFrame(image: Image) {
        if (!running) {
            image.close()
            return
        }
        if (!busy.compareAndSet(false, true)) {
            image.close() // 处理不过来就丢帧
            return
        }
        try {
            val planes = image.planes
            val rc = RknnBridge.process(
                planes[0].buffer, planes[1].buffer, planes[2].buffer,
                image.width, image.height,
                planes[0].rowStride, planes[1].rowStride, planes[2].rowStride,
                planes[1].pixelStride, planes[2].pixelStride,
                out
            )
            if (rc != 0) {
                Log.e(TAG, "推理错误 rc=$rc")
            } else {
                val gesture = out[RknnBridge.OUT_GESTURE].toInt()
                val palmValid = out[RknnBridge.OUT_PALM_VALID] > 0.5f
                val palmX = out[RknnBridge.OUT_PALM_X]
                val palmY = out[RknnBridge.OUT_PALM_Y]
                val now = SystemClock.uptimeMillis()
                sm.update(gesture, palmValid, palmX, palmY, now)

                val frameMs = out[RknnBridge.OUT_MS]
                RecognitionState.update(out)

                // 3. 实时回调
                GestureEngine.listener?.let { l ->
                    val lm = FloatArray(63)
                    System.arraycopy(out, RknnBridge.OUT_LM, lm, 0, 63)
                    l.onFrame(FrameResult(
                        gesture, out[RknnBridge.OUT_SCORE],
                        palmValid, palmX, palmY,
                        out[RknnBridge.OUT_PALM_W], out[RknnBridge.OUT_PALM_H],
                        lm, frameMs
                    ))
                }

                // 1. 悬浮识别框刷新
                if (overlay.isShown) {
                    overlay.onFrame()
                    val name = GestureId.names.getOrElse(gesture) { "?" }
                    overlay.setStatus(String.format("%s  %.0fms", name, frameMs))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "帧处理异常", e)
        } finally {
            image.close()
            busy.set(false)
        }
    }

    private fun stopSelfInternal() {
        running = false
        RecognitionState.clear()
        overlay.hide()
        try {
            camera?.close()
        } catch (_: Exception) {
        }
        camera = null
        try {
            RknnBridge.deinit()
        } catch (_: Exception) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        instance = null
        super.onDestroy()
    }
}
