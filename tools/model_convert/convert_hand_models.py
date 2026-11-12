#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MediaPipe 手部模型 -> RKNN (RK3588) 转换脚本
=============================================
用法 (Linux, Python 3.10/3.11):
    pip install "numpy<2" rknn-toolkit2
    python convert_hand_models.py <hand_detector.tflite> <hand_landmarks_detector.tflite> <输出目录>

源模型获取 (hand_landmarker.task 解压):
    curl -o hand_landmarker.task \
      https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task
    unzip hand_landmarker.task

产物:
    palm_detection.rknn   (192x192 输入, 2016 anchors 输出)
    hand_landmark.rknn    (224x224 输入, 21 关键点输出)

注意: App 的 C++ 解码按 float [0,1] 输入喂图, 因此转换不设 mean/std。
"""
import sys
import os
from rknn.api import RKNN


def convert(tflite_path, out_path, tag):
    rknn = RKNN(verbose=False)

    print(f"[{tag}] config...")
    ret = rknn.config(
        mean_values=[[0, 0, 0]],
        std_values=[[1, 1, 1]],        # App 直接喂 float [0,1]，运行时不再缩放
        target_platform="rk3588",
    )
    if ret != 0:
        print(f"[{tag}] config failed: {ret}")
        return False

    print(f"[{tag}] load_tflite {tflite_path}")
    ret = rknn.load_tflite(model=tflite_path)
    if ret != 0:
        print(f"[{tag}] load_tflite failed: {ret}")
        return False

    print(f"[{tag}] build (fp16)...")
    ret = rknn.build(do_quantization=False)
    if ret != 0:
        print(f"[{tag}] build failed: {ret}")
        return False

    ret = rknn.export_rknn(out_path)
    if ret != 0:
        print(f"[{tag}] export failed: {ret}")
        return False

    print(f"[{tag}] OK -> {out_path}")
    rknn.release()
    return True


if __name__ == "__main__":
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(1)
    det, lm, outdir = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(outdir, exist_ok=True)
    ok1 = convert(det, os.path.join(outdir, "palm_detection.rknn"), "palm")
    ok2 = convert(lm, os.path.join(outdir, "hand_landmark.rknn"), "landmark")
    sys.exit(0 if (ok1 and ok2) else 2)
