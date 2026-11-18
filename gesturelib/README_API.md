# gesturelib 1.1.0 — RK3588 NPU 隔空手势识别库 · 集成文档

NPU 手势识别 + 动作注入，封装为单个 AAR（含 RKNN 运行时与模型，无需额外依赖）。

## 一、环境要求

| 项目 | 要求 |
|------|------|
| 硬件 | RK3588（RKNPU DRM 驱动 ≥0.8.8，即 dmesg 可见 `Initialized rknpu`） |
| 系统 | Android 9+（推荐 Android 12，arm64-v8a） |
| **root** | **推荐**（确定键注入 / 无障碍自动授权 / 摄像头静默授权；返回与滑动可用无障碍） |
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
implementation("com.rk3588:gesturelib:1.1.0")
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
        // result.gesture / score / landmarks(21点) / palmX,Y,W,H / frameMs
    }
    // 手势触发点 (只识别模式下也会回调; dir: 1上 2下 3左 4右)
    override fun onGestureTriggered(gesture: Int, dir: Int, defaultAction: GestureAction) {}
    // 内置动作执行完成
    override fun onActionExecuted(action: GestureAction) {}
})
```

### 4. 手势 → 功能映射

```kotlin
// 重映射: 手势 → 任意内置动作
GestureEngine.setAction(GestureId.FIST, GestureAction.BACK)

// 置空: 只识别不动作 (回调仍然触发)
GestureEngine.setAction(GestureId.VICTORY, null)

// 便捷开关
GestureEngine.setGestureEnabled(GestureId.WAVE, false)

// 完全接管: 库不执行内置动作, 由接入方决定
GestureEngine.setActionHandler { action, gesture, dir ->
    when (action) {
        GestureAction.SWIPE_UP -> myScrollUp(); true
        else -> false  // false 落回内置执行
    }
}
GestureEngine.resetActions()  // 恢复默认
```

## 四、手势集与默认动作

| 手势 | 默认动作 | 说明 |
|------|----------|------|
| ✋ 张掌向上挥 | 向上滑动 | 位移超阈值 + 停住确认；无障碍注入 |
| ✋ 张掌向下挥 | 向下滑动 | 刷视频、滚动列表 |
| ✋ 张掌向左挥 | 向左滑动 | 翻页 |
| ✋ 张掌向右挥 | 向右滑动 | |
| 👋 左右往复摆动 | 无（只回调） | 挥手事件，`onGestureTriggered(GestureId.WAVE)`；可 setAction 重映射 |
| ✊ 拳头 | 确定 | KEYCODE_DPAD_CENTER，需 root |
| ✌️ 剪刀手 | 返回 | 无障碍 globalBack / root keyevent 4 |

- 滑动与挥手互不干扰：单次大幅位移后停住 = 滑动；1 秒内左右折返 ≥2 次 = 挥手
- 关闭任意手势动作：`setAction(g, null)` —— 回调照常，动作不执行

## 五、可调参数

```kotlin
GestureEngine.actionGapMs    // 全局动作间隔 (默认 3000, 动作后隔这么久才识别下一个)
GestureEngine.confirmFrames  // 手势确认帧数 (默认 3)
GestureEngine.swipeThreshold // 滑动触发阈值 (默认 0.16, 归一化画面比例)
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
| 拳头确定无反应 | 确定键走 root `input keyevent 23`，确认设备已 root |
| NPU 初始化失败 rc=-11/-13 | AAR 内模型缺失（完整 AAR 不会出现）；或设备无 RKNPU 驱动 |
| 误触发多 | 调大 `actionGapMs` / `confirmFrames` / `swipeThreshold`；光线充足时置信度显著提升 |
| 状态机日志 | `adb logcat -s GestureNPU GestureService GestureSM GestureEngine` |
