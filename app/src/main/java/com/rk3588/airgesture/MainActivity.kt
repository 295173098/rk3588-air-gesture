package com.rk3588.airgesture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.rk3588.gesturelib.CameraController
import com.rk3588.gesturelib.FrameDetection
import com.rk3588.gesturelib.FrameResult
import com.rk3588.gesturelib.GestureAction
import com.rk3588.gesturelib.GestureConfig
import com.rk3588.gesturelib.GestureEngine
import com.rk3588.gesturelib.GestureId
import com.rk3588.gesturelib.GestureListener
import com.rk3588.gesturelib.OverlayView
import com.rk3588.gesturelib.RknnBridge

/**
 * Demo: 演示 gesturelib 的五类 API
 *  1. 开启/关闭悬浮识别框      engine.showOverlay / hideOverlay
 *  2. 开始/关闭识别            engine.startRecognition / stopRecognition
 *  3. 实时手势识别回调          engine.setOnGestureListener
 *  4. 手势→动作映射            engine.setAction / setGestureEnabled / setActionHandler
 *  5. 算法帧输入               engine.analyzeImage (图片 → NPU → 回调)
 *
 * 手势集: ✋ 张掌挥动 = 方向滑动 · ✊ 拳头 = 确定 · ✌️ 剪刀手 = 返回 · 👋 挥手 = 只回调
 */
class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())

    // ---- 预览 ----
    private lateinit var previewFrame: FrameLayout
    private lateinit var previewImage: ImageView
    private lateinit var overlay: OverlayView
    private val uiBitmap = Bitmap.createBitmap(
        CameraController.PREVIEW_W, CameraController.PREVIEW_H, Bitmap.Config.ARGB_8888)
    private var lastPreviewW = 0
    private var lastPreviewH = 0

    // ---- 状态 ----
    @Volatile private var lastGestureName = "无"
    @Volatile private var lastFrameMs = 0f
    private val logLines = ArrayDeque<String>()
    private var overlayShowing = false

    private lateinit var tvStatus: TextView
    private lateinit var tvLive: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnOverlay: Button
    private lateinit var ivFrame: ImageView
    private lateinit var tvFrameResult: TextView

    // ---- 5. 算法帧输入: 系统图片选择器 → Bitmap → NPU → 回调 ----
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { analyzePickedImage(it) }
    }

    // ---- 3. 实时手势识别回调 ----
    private val listener = object : GestureListener {
        override fun onFrame(result: FrameResult) {
            lastGestureName = GestureId.names.getOrElse(result.gesture) { "?" }
            lastFrameMs = result.frameMs
        }

        override fun onGestureTriggered(gesture: Int, dir: Int, defaultAction: GestureAction) {
            ui.post {
                val name = GestureId.names.getOrElse(gesture) { "?" } +
                        if (dir > 0) "·" + GestureId.dirNames.getOrElse(dir) { "" } else ""
                val mapped = when {
                    !GestureEngine.isGestureEnabled(gesture) -> "只识别"
                    defaultAction == GestureAction.NONE -> "只回调"
                    else -> defaultAction.name
                }
                appendLog("触发 $name → $mapped")
            }
        }

        override fun onActionExecuted(action: GestureAction) {
            ui.post { appendLog("执行 ✓ $action") }
        }
    }

    private var statusCounter = 0

    private val tick = object : Runnable {
        override fun run() {
            // 悬浮识别框开启时隐藏 App 内预览, 避免重复画面
            previewFrame.visibility =
                if (overlayShowing) View.GONE else View.VISIBLE
            if (!overlayShowing) {
                RknnBridge.copyPreview(uiBitmap)
                previewImage.invalidate()
                overlay.onFrameTick()
            }
            // 状态文字降频 (每 ~500ms)
            if (++statusCounter % 15 == 0) refreshStatus()
            ui.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewFrame = findViewById(R.id.preview_frame)
        previewImage = findViewById(R.id.preview_image)
        overlay = findViewById(R.id.overlay_view)
        tvStatus = findViewById(R.id.tv_status)
        tvLive = findViewById(R.id.tv_live)
        tvLog = findViewById(R.id.tv_log)
        btnToggle = findViewById(R.id.btn_toggle)
        btnOverlay = findViewById(R.id.btn_overlay)
        ivFrame = findViewById(R.id.iv_frame)
        tvFrameResult = findViewById(R.id.tv_frame_result)

        // ---- 5. 算法帧输入: 选一张图片 → NPU 推理 → 回调展示 ----
        findViewById<Button>(R.id.btn_pick_image).setOnClickListener {
            pickImage.launch("image/*")
        }

        // 预览: 库内双缓冲 → 快照 → 软件层显示
        previewImage.setImageBitmap(uiBitmap)
        previewImage.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        previewFrame.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val fw = r - l; val fh = b - t
            if (fw <= 0 || fh <= 0 || (fw == lastPreviewW && fh == lastPreviewH))
                return@addOnLayoutChangeListener
            lastPreviewW = fw; lastPreviewH = fh
            var w = fw; var h = fw * 3 / 4
            if (h > fh) { h = fh; w = fh * 4 / 3 }
            previewImage.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
            previewImage.requestLayout()
        }

        // ---- 3. 注册实时回调 ----
        GestureEngine.setOnGestureListener(listener)

        // ---- 2. 开始/关闭识别 ----
        btnToggle.setOnClickListener {
            if (GestureEngine.isRunning) {
                GestureEngine.stopRecognition()
            } else {
                GestureEngine.startRecognition()
            }
            ui.postDelayed({ refreshStatus() }, 600)
        }

        // ---- 1. 悬浮识别框 ----
        btnOverlay.setOnClickListener {
            if (!GestureEngine.isRunning) {
                Toast.makeText(this, "先开始识别", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (overlayShowing) GestureEngine.hideOverlay() else GestureEngine.showOverlay(true)
            overlayShowing = !overlayShowing
            updateOverlayBtn()
        }
    }

    override fun onResume() {
        super.onResume()
        GestureEngine.init(applicationContext)
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private fun updateOverlayBtn() {
        btnOverlay.text = if (overlayShowing) "关闭识别框" else "开启识别框"
    }

    private fun bindSwitch(id: Int, gesture: Int, label: String) {
        val sw = findViewById<Switch>(id)
        sw.text = label
        sw.isChecked = GestureEngine.isGestureEnabled(gesture)
        sw.setOnCheckedChangeListener { _, checked ->
            // 4. 手势→动作映射: 关 = 只识别不动作
            GestureEngine.setGestureEnabled(gesture, checked)
            appendLog("$label ${if (checked) "→ 默认动作" else "→ 只识别不动作"}")
        }
    }

    private fun appendLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > 6) logLines.removeFirst()
        tvLog.text = logLines.joinToString("\n")
    }

    // ---- 5. 算法帧输入 ----

    /** 选图 → 解码 (限制最长边 1280, 避免大图占内存) → 异步 NPU 推理 → 回调展示 */
    private fun analyzePickedImage(uri: Uri) {
        val bitmap = decodeSampled(uri, 1280)
        if (bitmap == null) {
            Toast.makeText(this, "图片解码失败", Toast.LENGTH_SHORT).show()
            return
        }
        ivFrame.setImageBitmap(bitmap)
        tvFrameResult.text = "NPU 推理中…"
        GestureEngine.analyzeImage(bitmap) { r ->
            ui.post {
                if (r == null) {
                    tvFrameResult.text = "推理失败（检查 NPU 驱动 / 模型）"
                } else {
                    showFrameResult(r)
                }
            }
        }
    }

    private fun showFrameResult(r: FrameDetection) {
        tvFrameResult.text = if (!r.hasHand) {
            String.format("未检测到手 · 置信度 %.2f · %.0f ms", r.score, r.inferenceMs)
        } else {
            String.format(
                "手势: %s · 置信度 %.2f · %.0f ms\n掌框 (%.2f, %.2f) %.2fx%.2f · 张开手指 %d",
                r.gestureName, r.score, r.inferenceMs,
                r.palmX, r.palmY, r.palmW, r.palmH, r.openFingers
            )
        }
        appendLog("图片识别: ${r.gestureName} ${String.format("%.0f", r.inferenceMs)}ms")
    }

    private fun decodeSampled(uri: Uri, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    }

    private fun refreshStatus() {
        val running = GestureEngine.isRunning
        tvStatus.text = if (running) "识别运行中" else "识别已停止"
        tvStatus.setTextColor(if (running) 0xFF4CAF50.toInt() else 0xFFE53935.toInt())
        btnToggle.text = if (running) "停止识别" else "开始识别"

        tvLive.text = String.format("手势: %s   %.0f ms   %.1f fps",
            lastGestureName, lastFrameMs, if (lastFrameMs > 0) 1000f / lastFrameMs else 0f)
    }
}
