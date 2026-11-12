package com.rk3588.gesturelib

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.WindowManager

/**
 * 悬浮识别框: 状态徽标 + 可选实时预览(含骨架叠加)。
 * 由 GestureEngine.showOverlay/hideOverlay 控制, 服务每帧喂数据。
 */
@SuppressLint("InflateParams")
class RecognitionOverlay(private val context: Context) {

    companion object {
        private const val PREVIEW_W_DP = 240   // 预览宽 (dp)
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    // 双缓冲 (推理线程写) + 快照 (本悬浮窗绘制)
    private val bufA: Bitmap = Bitmap.createBitmap(
        CameraController.PREVIEW_W, CameraController.PREVIEW_H, Bitmap.Config.ARGB_8888)
    private val bufB: Bitmap = Bitmap.createBitmap(
        CameraController.PREVIEW_W, CameraController.PREVIEW_H, Bitmap.Config.ARGB_8888)
    private val uiBitmap: Bitmap = Bitmap.createBitmap(
        CameraController.PREVIEW_W, CameraController.PREVIEW_H, Bitmap.Config.ARGB_8888)

    private var root: LinearLayout? = null
    private var previewHost: FrameLayout? = null
    private var previewImage: ImageView? = null
    private var overlayView: OverlayView? = null
    private var statusText: TextView? = null
    private var previewShown = false

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        y = 60
    }

    val isShown: Boolean get() = root != null

    @SuppressLint("SetTextI18n")
    @Synchronized
    fun show(showPreview: Boolean) {
        if (root != null) {
            applyPreviewMode(showPreview)
            return
        }
        val ctx = context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B3000000"))
                cornerRadius = 12 * density
            }
            setPadding(10, 10, 10, 10)
        }

        // 预览区 (可选)
        val host = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = 8 * density
            }
        }
        val iv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(uiBitmap)
            setLayerType(View.LAYER_TYPE_SOFTWARE, null) // JNI 原地改像素必须软件层
        }
        val ov = OverlayView(ctx)
        host.addView(iv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        host.addView(ov, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        box.addView(host, LinearLayout.LayoutParams(
            (PREVIEW_W_DP * density).toInt(), (PREVIEW_W_DP * 3 / 4 * density).toInt()))

        // 状态栏
        val tv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(6, 8, 6, 2)
            text = "手势: -"
        }
        box.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root = box
        previewHost = host
        previewImage = iv
        overlayView = ov
        statusText = tv
        previewShown = showPreview

        // 注册预览数据源 (幂等)
        RknnBridge.setPreviewBitmaps(bufA, bufB)

        try {
            wm.addView(box, params)
            applyPreviewMode(showPreview)
        } catch (e: Exception) {
            root = null
        }
    }

    @Synchronized
    fun hide() {
        root?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        root = null
        previewHost = null
        previewImage = null
        overlayView = null
        statusText = null
    }

    /** 切换预览显示 (保留悬浮框) */
    private fun applyPreviewMode(showPreview: Boolean) {
        previewHost?.visibility = if (showPreview) View.VISIBLE else View.GONE
        previewShown = showPreview
    }

    /** 每帧: 刷新预览快照 + 骨架 */
    fun onFrame() {
        if (root == null) return
        if (previewShown) {
            RknnBridge.copyPreview(uiBitmap)
            previewImage?.postInvalidateOnAnimation()  // 相机线程调用, 需线程安全
            overlayView?.onFrameTick()
        }
    }

    /** 状态文本 */
    fun setStatus(text: String) {
        statusText?.text = text
    }
}
