<div align="center">

# rk3588-air-gesture

**RK3588 NPU 隔空手势控制 · Android AAR 库 + Demo**

使用 RK3588 的 6 TOPS NPU 实时识别摄像头手势：
🖱 虚拟鼠标（光标/左键/右键/滚轮）· 🔊 音量 · 📃 上滑下滑 · ⬅️ 返回 · 📸 截图

[English](README_en.md) | 中文

`非商业授权 · 个人免费使用`

</div>

---

## ✨ 功能特性

- **NPU 实时推理**：MediaPipe 手部模型（掌检测 + 21 关键点）转 RKNN fp16，
  单帧 30~40ms，悬浮识别框实时显示骨架
- **🖱 鼠标模式**（root uinput 虚拟鼠标，系统级光标）：
  ☝️ 指尖移动光标 · 🤏 捏合左键（按住拖拽）· ✌️ 右键 · ✌️ 按住挥动滚轮
- **手势动作集**：👍👎 音量 · ✋ 挥动上滑下滑 · ✌️ 返回 · ✋→✊ 抓取截图 · ✊ 举拳锁定
- **封装为 AAR 库**：四类 API（悬浮识别框 / 启停识别 / 实时回调 / 动作映射），
  一行代码集成到任何 App
- **可配置**：每个手势可单独设为「只识别不动作」或重映射；全局动作间隔防误触
- **root 增强**：uinput 虚拟鼠标、按键注入、无障碍/权限自动授权、开机自启

## 📖 图文使用教程

### 第 1 步 · 安装与授权

从 [Releases](https://github.com/295173098/rk3588-air-gesture/releases) 下载 `app-debug.apk` 安装：

```bash
adb install -r app-debug.apk
```

打开 App，点「**一键 Root 授权**」，相机 / 悬浮窗 / 无障碍三项全部变 ✓：

![主界面](docs/images/ui-main.png)

### 第 2 步 · 开始识别

点「**开始识别**」—— 悬浮服务启动，NPU 管线运行（可在 logcat 看到
`GestureNPU: init OK`）。底部预览出现实时画面与骨架。

### 第 3 步 · 悬浮识别框（可选）

点「**开启识别框**」—— 右上角出现独立悬浮窗：实时预览 + 手势徽标 +
置信度显示，悬浮于**任何** App 之上，方便随时看到识别状态：

![悬浮识别框](docs/images/ui-overlay.png)

### 第 4 步 · 对着摄像头做手势

距镜头 40~60cm，掌心正对镜头。默认手势表：

| 手势 | 功能 | 说明 |
|------|------|------|
| ☝️ 食指指向 | 🖱 移动光标 | 指尖平滑映射全屏 |
| 🤏 拇指食指捏合 | 🖱 左键 | 快速捏合=单击；捏住移动=拖拽 |
| ✌️ 两指 | 🖱 右键 / 滚轮 | 张开瞬间=右键；按住上下挥=滚轮 |
| 👍 / 👎 | 音量 + / − | 按住连击 |
| ✋ 张掌上下挥 | 上滑 / 下滑 | 刷视频、滚动列表 |
| ✌️ | 返回 | 鼠标模式关闭时 |
| ✋张开 → 快速收拢 | 📸 截图 | 无需完全握拳 |
| ✊ 直接举拳 | 锁定 / 解锁 | 冻结全部手势 |

> 手势映射可在 App 内逐项开关（关 = 只识别不动作），
> 库集成方可用 `setAction` / `setActionHandler` 任意重映射。

### 第 5 步 · 作为库集成（可选）

把 `gesturelib-release.aar`（见 Releases）放进你的工程：

```kotlin
dependencies { implementation(files("libs/gesturelib-release.aar")) }

GestureEngine.init(context)
GestureEngine.startRecognition()
GestureEngine.showOverlay(true)
GestureEngine.setOnGestureListener(object : GestureListener { /* 实时回调 */ })
GestureEngine.setAction(GestureId.THUMB_UP, null)  // 只识别不动作
```

完整 API 见 [INTEGRATION 文档](gesturelib/README_API.md) 或 demo 源码 `app/`。

## 🔨 自行构建

```bash
git clone https://github.com/295173098/rk3588-air-gesture.git
cd rk3588-air-gesture
./gradlew :app:assembleDebug          # Demo APK
./gradlew :gesturelib:assembleRelease # 库 AAR
```

依赖：JDK 17 · Android SDK 34 · NDK 26.3 · CMake 3.22.1（模型转换见 `tools/model_convert/`）

## 🧠 技术原理

```
Camera2 (640×480 YUV) → JNI: YUV→RGB → letterbox 192
  → palm_detection.rknn (NPU) → 2016 anchors 解码 + NMS
  → 旋转 ROI → hand_landmark.rknn (NPU 224×224) → 21 关键点
  → 手势几何分类 + 张开度轨迹 (抓取/滑动/点指方向)
  → 时序状态机 (确认帧/冷却/序列) → 动作执行
      ├─ 无障碍 dispatchGesture (滑动/点击)
      ├─ uinput 虚拟鼠标 (光标/左右键/滚轮, root)
      └─ root input keyevent (方向键/截图/音量兜底)
```

解码数学严格对齐 MediaPipe 官方源码（SSD anchors / box decode / ROI 变换）。

## 📁 目录结构

```
gesturelib/   库模块（可单独发布 AAR）
app/          Demo App
tools/        模型转换脚本 (rknn-toolkit2)
docs/images/  教程配图
```

## 📄 许可证

本项目采用 [非商业授权 · 个人免费使用协议](LICENSE)：
个人学习、研究、爱好者使用**免费**；任何商业用途需事先取得作者书面授权。

## 🙏 致谢

[MediaPipe Hands](https://github.com/google-ai-edge/mediapipe) ·
[airockchip rknn-toolkit2](https://github.com/airockchip/rknn-toolkit2) ·
[PINTO0309 相关研究](https://github.com/PINTO0309)
