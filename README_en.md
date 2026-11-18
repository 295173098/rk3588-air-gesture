<div align="center">

# rk3588-air-gesture

**RK3588 NPU Air-Gesture Control · Android AAR Library + Demo**

Real-time camera gesture recognition on the RK3588 6-TOPS NPU:
🖐 4-direction swipe · 👋 wave · ✊ confirm · ✌️ back

中文 | [English](README_en.md)

`Non-commercial license · Free for personal use`

</div>

---

## ✨ Features

- **Real-time NPU inference**: MediaPipe-style hand models (palm detection + 21
  landmarks) converted to RKNN fp16 — 30~40 ms per frame, live skeleton overlay
- **Focused gesture set**: ✋ open palm swipe = up/down/left/right ·
  👋 wave arm · ✊ fist = confirm · ✌️ victory = back
- **Swipe / wave auto separation**: one big displacement then stop = directional
  swipe; repeated side-to-side oscillation = wave — no false triggers between them
- **Packaged as an AAR**: four API groups (recognition overlay / start-stop /
  real-time callbacks / action mapping) — integrate with a few lines
- **Configurable**: every gesture can be remapped or set to
  "recognize only, no action"; global action gap prevents false triggers
- **root-powered**: key injection (confirm / back), auto permission grant,
  boot start

## 📖 Illustrated Tutorial

### Step 1 · Install & Grant

Download `app-debug.apk` from [Releases](https://github.com/295173098/rk3588-air-gesture/releases):

```bash
adb install -r app-debug.apk
```

Open the app and tap **One-click root grant** — camera / overlay /
accessibility should all turn ✓:

![Main UI](docs/images/ui-main.png)

### Step 2 · Start Recognition

Tap **Start recognition** — the foreground service starts, the NPU pipeline
runs (`GestureNPU: init OK` in logcat), and the live preview with skeleton
appears at the bottom.

### Step 3 · Recognition Overlay (optional)

Tap **Show overlay** — a floating window appears at the top-right corner
with live preview + gesture badge + confidence, on top of **any** app:

![Floating overlay](docs/images/ui-overlay.png)

### Step 4 · Gesture in Front of the Camera

Stay 40~60 cm from the lens, palm facing the camera. Default gestures:

| Gesture | Action | Notes |
|---------|--------|-------|
| ✋ open palm, swipe up | swipe up | displacement threshold + stop confirmation |
| ✋ open palm, swipe down | swipe down | scrolling, video apps |
| ✋ open palm, swipe left | swipe left | page turning |
| ✋ open palm, swipe right | swipe right | |
| 👋 side-to-side wave | wave event | callback only by default; remappable via `setAction` |
| ✊ fist | confirm | focused OK key (requires root) |
| ✌️ victory | back | accessibility globalBack / root fallback |

> Swipe and wave never interfere: continuous oscillation is never mistaken for
> a directional swipe. Mappings are toggleable per gesture in the app
> (off = recognize only), and fully remappable via `setAction` / `setActionHandler`.

### Step 5 · Integrate as a Library (optional)

Grab `gesturelib-release.aar` from
[Releases](https://github.com/295173098/rk3588-air-gesture/releases):

```kotlin
dependencies { implementation(files("libs/gesturelib-release.aar")) }

GestureEngine.init(context)
GestureEngine.startRecognition()
GestureEngine.showOverlay(true)
GestureEngine.setOnGestureListener(object : GestureListener { /* realtime */ })
GestureEngine.setAction(GestureId.WAVE, null)          // recognize only
GestureEngine.setAction(GestureId.FIST, GestureAction.BACK)  // remap
```

Full API: see the [integration guide](gesturelib/README_API.md) or the demo
source in `app/`.

## 🔨 Build from Source

```bash
git clone https://github.com/295173098/rk3588-air-gesture.git
cd rk3588-air-gesture
./gradlew :app:assembleDebug          # demo APK
./gradlew :gesturelib:assembleRelease # library AAR
```

Requires: JDK 17 · Android SDK 34 · NDK 26.3 · CMake 3.22.1
(model conversion scripts in `tools/model_convert/`)

## 🧠 How It Works

```
Camera2 (640×480 YUV) → JNI: YUV→RGB → letterbox 192
  → palm_detection.rknn (NPU) → 2016-anchor decode + NMS
  → rotated ROI → hand_landmark.rknn (NPU 224×224) → 21 landmarks
  → geometric static-pose classification (palm / fist / victory) + palm trajectory
  → temporal state machine (confirm frames / cooldown / stop-confirm / reversal count)
      ├─ directional swipe: displacement threshold + stop confirm, dominant axis
      │    → accessibility-injected 4-direction screen swipe
      ├─ wave: X-axis oscillation (≥2 reversals) → event callback
      ├─ fist confirm: root input keyevent 23 (DPAD_CENTER)
      └─ victory back: accessibility globalBack / root keyevent 4
```

Decode math strictly follows the official MediaPipe sources
(SSD anchors / box decode / rect transformation).

## 📄 License

Licensed under the [Non-Commercial Use License](LICENSE):
**free for personal, non-commercial use**; any commercial use requires prior
written permission from the author.

## 🙏 Credits

[MediaPipe Hands](https://github.com/google-ai-edge/mediapipe) ·
[airockchip rknn-toolkit2](https://github.com/airockchip/rknn-toolkit2) ·
[PINTO0309 相关研究](https://github.com/PINTO0309)
