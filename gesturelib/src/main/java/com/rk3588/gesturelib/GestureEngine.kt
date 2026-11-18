package com.rk3588.gesturelib

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * NPU 手势识别引擎 (单例) —— 库的公开入口。
 *
 * 用法:
 * ```kotlin
 * val engine = GestureEngine
 * engine.init(context)
 * engine.setActionHandler { action, g, dir -> true }   // 可选: 接管动作
 * engine.setOnGestureListener(listener)                // 可选: 实时回调
 * engine.showOverlay(true)                             // 1. 开启悬浮识别框
 * engine.startRecognition()                            // 2. 开始识别
 * engine.stopRecognition()                             //    关闭识别
 * engine.setAction(GestureId.WAVE, null)               // 4. 该手势只识别不动作
 * engine.setAction(GestureId.FIST, GestureAction.BACK) // 4. 重映射动作
 * ```
 *
 * 手势集: ✋ 张掌挥动 = 4 方向滑动 · ✊ 拳头 = 确定 · ✌️ 剪刀手 = 返回 · 👋 挥手 = 只回调
 */
object GestureEngine {
    private const val TAG = "GestureEngine"

    private var appContext: Context? = null
    private var executor: ActionExecutor? = null

    @Volatile
    var listener: GestureListener? = null
        private set

    @Volatile
    var actionHandler: ActionHandler? = null
        private set

    // 手势→动作映射: 缺省(键不存在)=使用内置默认动作; 值=null=只识别不动作
    private val gestureActions = HashMap<Int, GestureAction?>()

    // 悬浮识别框期望状态 (服务未运行时先记住)
    @Volatile
    internal var overlayWanted = false
    @Volatile
    internal var overlayShowPreview = true

    val isRunning: Boolean get() = GestureService.running

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 初始化 (幂等, 在 startRecognition 前任意时机调用) */
    @Synchronized
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            GestureConfig.init(appContext!!)
            executor = ActionExecutor(appContext!!)
        }
    }

    /** 2. 开始识别: 启动前台服务 (相机 + NPU 管线) */
    fun startRecognition() {
        val ctx = appContext ?: throw IllegalStateException("先调用 GestureEngine.init(context)")
        // 无障碍被系统重置时 root 自愈
        if (!GestureAccessibility.isConnected() && GestureConfig.rootFallback) {
            Thread {
                if (RootShell.hasRoot()) {
                    RootShell.autoGrant(ctx.packageName)
                    Log.i(TAG, "accessibility auto-repaired via root")
                }
            }.start()
        }
        ContextCompat.startForegroundService(ctx, Intent(ctx, GestureService::class.java))
    }

    /** 2. 关闭识别 */
    fun stopRecognition() {
        val ctx = appContext ?: return
        ctx.startService(Intent(ctx, GestureService::class.java).putExtra("stop", true))
    }

    // ------------------------------------------------------------------
    // 1. 悬浮识别框
    // ------------------------------------------------------------------

    /** 开启悬浮识别框 (showPreview=true 时附带实时预览画面+骨架) */
    fun showOverlay(showPreview: Boolean) {
        overlayWanted = true
        overlayShowPreview = showPreview
        GestureService.instance?.applyOverlay()
    }

    /** 关闭悬浮识别框 */
    fun hideOverlay() {
        overlayWanted = false
        GestureService.instance?.applyOverlay()
    }

    // ------------------------------------------------------------------
    // 3. 实时回调
    // ------------------------------------------------------------------

    fun setOnGestureListener(l: GestureListener?) {
        listener = l
    }

    // ------------------------------------------------------------------
    // 4. 手势→动作映射
    // ------------------------------------------------------------------

    /**
     * 设置手势对应的功能。
     * @param action 映射的动作; 传 null = 只识别不动作; 不设置 = 内置默认功能
     */
    @Synchronized
    fun setAction(gesture: Int, action: GestureAction?) {
        gestureActions[gesture] = action
    }

    /** 恢复全部默认映射 */
    @Synchronized
    fun resetActions() {
        gestureActions.clear()
    }

    /** 便捷: 启用/禁用手势动作 (等价 setAction(g, null / 默认)) */
    fun setGestureEnabled(gesture: Int, enabled: Boolean) {
        if (enabled) {
            synchronized(this) { gestureActions.remove(gesture) }
            GestureConfig.setEnabled(gesture, true)
        } else {
            setAction(gesture, null)
            GestureConfig.setEnabled(gesture, false)
        }
    }

    fun isGestureEnabled(gesture: Int): Boolean = GestureConfig.isEnabled(gesture)

    /** 自定义动作处理器 (设置后库不再执行内置动作, 由外部决定) */
    fun setActionHandler(h: ActionHandler?) {
        actionHandler = h
    }

    // ------------------------------------------------------------------
    // 配置代理 (常用项)
    // ------------------------------------------------------------------

    var useFrontCamera: Boolean
        get() = GestureConfig.useFrontCamera
        set(v) = run { GestureConfig.useFrontCamera = v }
    var flipH: Boolean
        get() = GestureConfig.flipH
        set(v) = run { GestureConfig.flipH = v; RknnBridge.setFlipH(v) }
    var actionGapMs: Int
        get() = GestureConfig.actionGapMs
        set(v) = run { GestureConfig.actionGapMs = v }
    var swipeThreshold: Float
        get() = GestureConfig.swipeThreshold
        set(v) = run { GestureConfig.swipeThreshold = v }
    var confirmFrames: Int
        get() = GestureConfig.confirmFrames
        set(v) = run { GestureConfig.confirmFrames = v }
    var bootStart: Boolean
        get() = GestureConfig.bootStart
        set(v) = run { GestureConfig.bootStart = v }

    // ------------------------------------------------------------------
    // 内部: 服务回调
    // ------------------------------------------------------------------

    internal fun dispatchFrame(result: FrameResult) {
        listener?.onFrame(result)
    }

    /** 动作触发点: 回调 -> 映射决策 -> (处理器 | 内置执行) */
    internal fun dispatchAction(action: GestureAction, gesture: Int, dir: Int) {
        listener?.onGestureTriggered(gesture, dir, action)

        // 映射决策: 显式映射(含 null)优先, 否则用触发时的默认动作
        val mapped: GestureAction? = synchronized(this) {
            if (gestureActions.containsKey(gesture)) gestureActions[gesture] else action
        }
        if (mapped == null || mapped == GestureAction.NONE) return  // 只识别不动作

        val handler = actionHandler
        if (handler != null) {
            val handled = try {
                handler.handle(mapped, gesture, dir)
            } catch (e: Exception) {
                Log.w(TAG, "actionHandler error", e)
                false
            }
            if (handled) {
                listener?.onActionExecuted(mapped)
                return
            }
            // 处理器未处理 -> 落回内置执行
        }
        executor?.execute(mapped)
        listener?.onActionExecuted(mapped)
    }

    internal fun requestHudFlash(text: String) {
        GestureService.instance?.flashHud(text)
    }
}
