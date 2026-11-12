package com.rk3588.gesturelib

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * Camera2 封装：打开指定摄像头，640x480 YUV 输出到 ImageReader。
 */
class CameraController(private val context: Context) {
    companion object {
        private const val TAG = "CameraController"
        const val PREVIEW_W = 640
        const val PREVIEW_H = 480
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** 帧回调（在相机线程上） */
    var frameListener: ((ImageReader, android.media.Image) -> Unit)? = null

    private fun startThread() {
        thread = HandlerThread("cam").also { it.start() }
        handler = Handler(thread!!.looper)
    }

    fun open(frontCamera: Boolean) {
        startThread()
        val facing = if (frontCamera) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        val id = cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
        } ?: cameraManager.cameraIdList.firstOrNull() ?: run {
            Log.e(TAG, "no camera found")
            return
        }

        imageReader = ImageReader.newInstance(PREVIEW_W, PREVIEW_H,
            android.graphics.ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                frameListener?.invoke(reader, img)
            }, handler)
        }

        cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                createSession()
            }
            override fun onDisconnected(device: CameraDevice) {
                device.close()
            }
            override fun onError(device: CameraDevice, error: Int) {
                Log.e(TAG, "camera error: $error")
                device.close()
            }
        }, handler)
    }

    private fun createSession() {
        val dev = camera ?: return
        val reader = imageReader ?: return
        val outputs = listOf(reader.surface)
        dev.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(reader.surface)
                    set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                        android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
                s.setRepeatingRequest(request.build(), null, handler)
                Log.i(TAG, "capture session started")
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                Log.e(TAG, "session configure failed")
            }
        }, handler)
    }

    fun close() {
        try {
            session?.close()
            camera?.close()
            imageReader?.close()
        } catch (_: Exception) {
        }
        session = null
        camera = null
        imageReader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }
}
