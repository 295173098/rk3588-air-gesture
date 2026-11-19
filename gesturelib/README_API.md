# gesturelib 1.2.0 — RK3588 NPU 隔空手势识别库 · 集成文档

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
implementation("com.rk3588:gesturelib:1.2.0")
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

## 三、API（五类能力）

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

### 5. 算法帧输入（图片 / 外部帧）

不接相机也能用：把外部帧直接送入同一套 NPU 管线，结果通过回调返回。

```kotlin
// 单帧分析（异步，回调在后台线程；result = null 表示推理失败）
GestureEngine.analyzeImage(bitmap) { result ->
    if (result != null && result.hasHand) {
        Log.i("Demo", "${result.gestureName} ${result.score} ${result.inferenceMs}ms")
        val lm = result.landmarks   // 21×3 归一化坐标
    }
}

// 同步版（勿在主线程调用）
val result: FrameDetection? = GestureEngine.analyzeImage(bitmap)
```

`FrameDetection` 字段：

| 字段 | 说明 |
|------|------|
| `hasHand` | 本帧是否检测到手 |
| `gesture` / `gestureName` | 静态手势 id / 名称 |
| `score` | landmark 置信度 0..1 |
| `handedness` | 左右手分数 |
| `openFingers` | 伸直手指数 0..5 |
| `palmX/Y/W/H` | 掌心框（图像归一化） |
| `landmarks` | 21×3 关键点（x,y,z 归一化），`landmark(i)` 取单点 |
| `inferenceMs` | 单帧 NPU 耗时 |

连续帧输入（视频流 / 其他相机 / 算法前处理结果）：

```kotlin
GestureEngine.startFrameInput()          // 不打开相机; 与 startRecognition 互斥
GestureEngine.pushFrame(bitmap)          // 每帧调用; 上一帧未处理完时自动丢帧
GestureEngine.pushYuvFrame(y, u, v, width, height,
    yStride, uStride, vStride, uPixelStride, vPixelStride)
GestureEngine.stopFrameInput()
GestureEngine.isFrameInputActive
```

- 连续帧模式与相机识别同构：复用完整时序状态机，`onFrame` /
  `onGestureTriggered` 照常回调，动作映射同样生效
- 单帧分析不经过状态机、不触发动作；每次分析都会重置跟踪 ROI，强制重新做掌检测
- NPU 模型按需加载，无需先 `startRecognition()`
- 相机帧与算法帧共用同一套 RKNN 上下文（JNI 内串行化）；
  `startRecognition()` 会自动退出外部帧模式
- `pushFrame` 的 bitmap 在回调前不要 recycle；`pushYuvFrame` 的 ByteBuffer
  必须是 direct buffer 且在回调前保持有效
- 输入尺寸不限（内部 letterbox），坐标按输入帧宽高归一化

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

进阶参数集中在 [SmParams]（剪刀手确认帧、挥手摆幅、停止确认时长、丢帧去抖等），
默认值来自离线仿真扫描，一般无需调整；确有需要可构造
`GestureStateMachine(listener) { SmParams(...) }` 覆盖。

## 六、离线仿真与调参

`gesturelib/src/test/java/com/rk3588/gesturelib/sim/` 内置一套 **JVM 离线仿真**，
不需要真机即可回归和调优时序识别：

- **SimHand**: 参数化 21 关键点手掌模型（张掌/拳头/剪刀手姿势 + 卷曲度插值 + 高斯噪声）
- **SimClassifier**: `gesture_npu.cpp classify_gesture()` 的 Kotlin 镜像（改 C++ 须同步）
- **Scenarios**: 17 类场景 × N 个随机种子——4 方向滑动、挥手（常规/大摆幅/停住）、
  拳头确定、剪刀手返回、张掌↔握拳过渡、反复开合、斜向滑动、静置干扰等
- **GestureSim**: 喂给**生产状态机**逐帧跑，统计检出率 / 误触 / 触发延迟

已通过仿真修复的问题（真机前就拦下）：

1. 启动初期 uptimeMillis 接近 0 时被全局动作间隔吞掉前 3 秒动作
2. 分类器单帧 NONE 抖动会清空运动轨迹 → 短促滑动/挥手漏检
3. 偶发丢帧立即清挂起 → 滑动被误杀（改为 200ms 去抖）
4. 停止确认阈值低于掌心抖动 → 挂起永远等不到静止窗口
5. 慢速握拳过渡期短暂呈现剪刀手形态 → 误触返回（victoryConfirmMin=9 根治）

调参流程（见 `sim/SweepTest`）: 去掉 `@Ignore` → 跑网格扫描 →
按「检出率 − 误触×20 − 延迟惩罚」挑最优 → 回写 `SmParams` → 恢复 `@Ignore`。
`GestureSimulationTest` 是日常回归门禁（当前指标: 100% 检出 / 0 误触 / ~66ms）。

> 仿真覆盖时序逻辑与阈值鲁棒性；NPU 模型质量、光照、距离等真机因素仍需实测验证。

## 七、库自动合并的内容

- 权限: CAMERA / FOREGROUND_SERVICE(camera) / POST_NOTIFICATIONS / SYSTEM_ALERT_WINDOW
- 组件: 前台服务 `GestureService` + 无障碍服务 `GestureAccessibility`
- 资产: RKNN 模型 ×2 + librknnrt.so / libgesture_npu.so / libc++_shared.so

## 八、常见问题

| 现象 | 处理 |
|------|------|
| 手势有识别但没动作 | ① 无障碍未启用（root 授权或手动开）② 被映射置空 ③ 全局动作间隔未到 |
| 拳头确定无反应 | 确定键走 root `input keyevent 23`，确认设备已 root |
| NPU 初始化失败 rc=-11/-13 | AAR 内模型缺失（完整 AAR 不会出现）；或设备无 RKNPU 驱动 |
| 误触发多 | 调大 `actionGapMs` / `confirmFrames` / `swipeThreshold`；光线充足时置信度显著提升 |
| `analyzeImage` 回调 null / 未检测到手 | ① 设备无 RKNPU 驱动或模型缺失 ② 图片中手部过小/过暗 ③ 位图非 RGBA_8888（库会自动转一份） |
| 外部帧模式启动失败 | 相机识别正在运行 —— 先 `stopRecognition()`；两种帧源互斥 |
| 状态机日志 | `adb logcat -s GestureNPU GestureService GestureSM GestureFrameInput` |
