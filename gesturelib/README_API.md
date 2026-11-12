# gesturelib 1.0.0 — RK3588 NPU 隔空手势识别库 · 集成文档

NPU 手势识别 + 虚拟鼠标 + 动作注入，封装为单个 AAR（含 RKNN 运行时与模型，无需额外依赖）。

## 一、环境要求

| 项目 | 要求 |
|------|------|
| 硬件 | RK3588（RKNPU DRM 驱动 ≥0.8.8，即 dmesg 可见 `Initialized rknpu`） |
| 系统 | Android 9+（推荐 Android 12，arm64-v8a） |
| **root** | **必需**（虚拟鼠标 uinput / 方向键注入 / 无障碍自动授权 / 摄像头静默授权） |
| 相机 | 任意 USB/MIPI 摄像头（640×480 @30fps） |

## 二、三步接入

**1. 放入 AAR**

```
app/libs/gesturelib-release.aar
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(files("libs/gesturelib-release.aar"))
}
```

或使用 Maven 坐标（已发布到接入方本机 mavenLocal 的话）：

```kotlin
implementation("com.rk3588:gesturelib:1.0.0")
```

**2. 初始化并启动**

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        GestureEngine.init(this)
    }
}

// 任意位置启动识别
GestureEngine.startRecognition()
```

**3. root 一键授权**（相机/悬浮窗/无障碍，库会自动尝试，也可手动调）

```kotlin
Thread { RootShell.autoGrant(packageName) }.start()
```

## 三、API（四类能力）

### 1. 悬浮识别框

```kotlin
GestureEngine.showOverlay(showPreview = true)  // 状态徽标 + 实时预览 + 骨架
GestureEngine.hideOverlay()
```

### 2. 开始 / 关闭识别

```kotlin
GestureEngine.startRecognition()
GestureEngine.stopRecognition()
GestureEngine.isRunning
```

### 3. 实时手势识别回调

```kotlin
GestureEngine.setOnGestureListener(object : GestureListener {
    // 每帧回调 (相机线程 ~30Hz, 勿做耗时操作)
    override fun onFrame(result: FrameResult) {
        // result.gesture / dir / score / landmarks(21点) / palmX,Y,W,H / frameMs
    }
    // 手势触发点 (只识别模式下也会回调)
    override fun onGestureTriggered(gesture: Int, dir: Int, defaultAction: GestureAction) {}
    // 内置动作执行完成
    override fun onActionExecuted(action: GestureAction) {}
})
```

### 4. 手势 → 功能映射

```kotlin
// 重映射: 手势 → 任意内置动作
GestureEngine.setAction(GestureId.THUMB_UP, GestureAction.SWIPE_UP)

// 置空: 只识别不动作 (回调仍然触发)
GestureEngine.setAction(GestureId.VICTORY, null)

// 便捷开关
GestureEngine.setGestureEnabled(GestureId.POINT, false)

// 完全接管: 库不执行内置动作, 由接入方决定
GestureEngine.setActionHandler { action, gesture, dir ->
    when (action) {
        GestureAction.VOL_UP -> myVolumeUp(); true
        else -> false  // false 落回内置执行
    }
}
GestureEngine.resetActions()  // 恢复默认
```

## 四、手势集与默认动作

| 手势 | 默认动作 | 说明 |
|------|----------|------|
| ☝️ 食指指向 | 🖱 光标移动 | 鼠标模式（默认开） |
| 🤏 拇指食指捏合 | 🖱 左键 | 快速捏合=单击；捏住移动=拖拽 |
| ✌️ 两指 | 🖱 右键 / 滚轮 | 出现瞬间=右键；按住上下挥=滚轮 |
| 👍 / 👎 | 音量 + / − | 按住连击 |
| ✋ 张掌上下挥 | 上滑 / 下滑 | 无障碍注入 |
| ✋张开 → 快速收拢 | 截图 | 张开度下降沿 ≥4指→≤1指，无需握拳 |
| ✊ 直接举拳 | 锁定 / 解锁 | 冻结全部手势响应 |

- 鼠标模式开关：`GestureEngine.mouseMode = false` 关闭后 ☝️ 变方向键 ↑↓←→、✌️ 变返回
- 关闭任意手势动作：`setAction(g, null)` —— 回调照常，动作不执行

## 五、可调参数

```kotlin
GestureEngine.actionGapMs    // 全局动作间隔 (默认 3000, 动作后隔这么久才识别下一个)
GestureEngine.confirmFrames  // 手势确认帧数 (默认 3)
GestureEngine.swipeThreshold // 挥动触发阈值 (默认 0.16)
GestureEngine.volumeRepeat   // 音量连击开关 (默认 true)
GestureEngine.useFrontCamera // 前置/后置摄像头
GestureEngine.flipH          // 水平翻转 (默认 false = 相机原始画面)
GestureEngine.bootStart      // 开机自启标记 (配合 BOOT_COMPLETED)
```

## 六、库自动合并的内容

- 权限: CAMERA / FOREGROUND_SERVICE(camera) / POST_NOTIFICATIONS / SYSTEM_ALERT_WINDOW
- 组件: 前台服务 `GestureService` + 无障碍服务 `GestureAccessibility`
- 资产: RKNN 模型 ×2 + librknnrt.so / libgesture_npu.so / libc++_shared.so

## 七、常见问题

| 现象 | 处理 |
|------|------|
| 手势有识别但没动作 | ① 无障碍未启用（root 授权或手动开）② 被映射置空 ③ 全局动作间隔未到 |
| 虚拟鼠标不出现 | logcat 看 `VirtualMouse` 标签；`chmod 666 /dev/uinput` 需 root 且 SELinux 兼容 |
| NPU 初始化失败 rc=-11/-13 | AAR 内模型缺失（完整 AAR 不会出现）；或设备无 RKNPU 驱动 |
| 误触发多 | 调大 `actionGapMs` / `confirmFrames`；光线充足时置信度显著提升 |
| 状态机日志 | `adb logcat -s GestureNPU GestureService GestureSM VirtualMouse GestureEngine` |
