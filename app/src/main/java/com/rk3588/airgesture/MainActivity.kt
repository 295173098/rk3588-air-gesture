package com.rk3588.airgesture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rk3588.gesturelib.CameraController
import com.rk3588.gesturelib.FrameResult
import com.rk3588.gesturelib.GestureAction
import com.rk3588.gesturelib.GestureConfig
import com.rk3588.gesturelib.GestureEngine
import com.rk3588.gesturelib.GestureId
import com.rk3588.gesturelib.GestureListener
import com.rk3588.gesturelib.OverlayView
import com.rk3588.gesturelib.RknnBridge
import com.rk3588.gesturelib.RootShell

/**
 * Demo: 演示 gesturelib 的四类 API
 *  1. 开启/关闭悬浮识别框      engine.showOverlay / hideOverlay
 *  2. 开始/关闭识别            engine.startRecognition / stopRecognition
 *  3. 实时手势识别回调          engine.setOnGestureListener
 *  4. 手势→动作映射            engine.setAction / setGestureEnabled / setActionHandler
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
    private lateinit var rowCamera: TextView
    private lateinit var rowOverlay: TextView
    private lateinit var rowAccess: TextView
    private lateinit var sbGap: SeekBar
    private lateinit var lblGap: TextView

    // ---- 3. 实时手势识别回调 ----
    private val listener = object : GestureListener {
        override fun onFrame(result: FrameResult) {
            lastGestureName = GestureId.names.getOrElse(result.gesture) { "?" } +
                    if (result.gesture == GestureId.POINT && result.dir > 0)
                        "·" + GestureId.dirNames.getOrElse(result.dir) { "" } else ""
            lastFrameMs = result.frameMs
        }

        override fun onGestureTriggered(gesture: Int, dir: Int, defaultAction: GestureAction) {
            ui.post {
                val name = GestureId.names.getOrElse(gesture) { "?" } +
                        if (dir > 0) "·" + GestureId.dirNames.getOrElse(dir) { "" } else ""
                val mapped = if (GestureEngine.isGestureEnabled(gesture))
                    defaultAction.name else "只识别"
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
        rowCamera = findViewById(R.id.row_camera)
        rowOverlay = findViewById(R.id.row_overlay)
        rowAccess = findViewById(R.id.row_access)
        sbGap = findViewById(R.id.sb_gap)
        lblGap = findViewById(R.id.lbl_gap)

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
                if (!hasCamera()) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
                    return@setOnClickListener
                }
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

        // ---- 4. 手势映射开关 (关 = 只识别不动作) ----
        bindSwitch(R.id.sw_palm, GestureId.PALM, "✋ 挥动滑动")
        bindSwitch(R.id.sw_thumb_up, GestureId.THUMB_UP, "👍 音量+")
        bindSwitch(R.id.sw_thumb_down, GestureId.THUMB_DOWN, "👎 音量-")
        bindSwitch(R.id.sw_victory, GestureId.VICTORY, "✌️ 返回")
        bindSwitch(R.id.sw_point, GestureId.POINT, "☝️ 方向键")
        bindSwitch(R.id.sw_fist, GestureId.FIST, "✊ 锁定")
        val swGrab = findViewById<Switch>(R.id.sw_grab)
        swGrab.isChecked = GestureConfig.enableGrab
        swGrab.setOnCheckedChangeListener { _, c -> GestureConfig.enableGrab = c }
        val swMouse = findViewById<Switch>(R.id.sw_mouse)
        swMouse.isChecked = GestureConfig.mouseMode
        swMouse.setOnCheckedChangeListener { _, c ->
            GestureConfig.mouseMode = c
            appendLog("鼠标模式 ${if (c) "开 (☝️光标 🤏左键 ✌️右键/滚轮)" else "关"}")
        }

        // 自定义动作处理器示例 (打开后所有动作交给外部处理, 库不再执行内置动作)
        // GestureEngine.setActionHandler(ActionHandler { action, gesture, dir ->
        //     android.util.Log.i("Demo", "自定义处理 $action gesture=$gesture dir=$dir")
        //     true
        // })

        // 权限
        findViewById<Button>(R.id.btn_root_grant).setOnClickListener {
            Thread {
                if (RootShell.hasRoot()) {
                    RootShell.autoGrant(packageName)
                    ui.post { Toast.makeText(this, "root 授权完成", Toast.LENGTH_SHORT).show() }
                } else {
                    ui.post { Toast.makeText(this, "未获取 root", Toast.LENGTH_SHORT).show() }
                }
            }.start()
        }
        rowCamera.setOnClickListener {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }
        rowOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
        rowAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // 动作间隔
        sbGap.max = 6000
        sbGap.progress = GestureConfig.actionGapMs.coerceIn(500, 6000)
        sbGap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    GestureConfig.actionGapMs = p.coerceAtLeast(500)
                    lblGap.text = "动作间隔 ${GestureConfig.actionGapMs} ms"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        lblGap.text = "动作间隔 ${GestureConfig.actionGapMs} ms"
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

    private fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun refreshStatus() {
        val running = GestureEngine.isRunning
        tvStatus.text = if (running) "识别运行中" else "识别已停止"
        tvStatus.setTextColor(if (running) 0xFF4CAF50.toInt() else 0xFFE53935.toInt())
        btnToggle.text = if (running) "停止识别" else "开始识别"

        rowCamera.text = "相机权限 ${if (hasCamera()) "✓" else "✗"}"
        rowOverlay.text = "悬浮窗权限 ${if (Settings.canDrawOverlays(this)) "✓" else "✗"}"
        rowAccess.text = "无障碍服务 ${if (com.rk3588.gesturelib.GestureAccessibility.isConnected()) "✓" else "✗"}"

        tvLive.text = String.format("手势: %s   %.0f ms   %.1f fps",
            lastGestureName, lastFrameMs, if (lastFrameMs > 0) 1000f / lastFrameMs else 0f)
    }
}
