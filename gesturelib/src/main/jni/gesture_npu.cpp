// gesture_npu.cpp — RK3588 NPU 隔空手势识别 JNI 层
//
// 管线: YUV420_888 -> RGB -> letterbox192 -> palm_detection(RKNN)
//       -> 解码2016 anchors + NMS -> ROI(旋转/放大) -> hand_landmark(RKNN)
//       -> 21 关键点 -> 手势分类
//
// 解码数学严格对齐 MediaPipe 官方 (palm_detection_cpu.pbtxt +
// ssd_anchors_calculator.cc + tflite_tensors_to_detections_calculator.cc +
// palm_detection_detection_to_roi.pbtxt + rect_transformation_calculator.cc):
//   palm input 192x192 float[0,1] letterbox; anchors 2016 (1152+864);
//   decode: x=raw/192*1.0+ax (fixed anchor w=h=1.0), score=sigmoid(clip(raw));
//   palm->ROI: scale 2.6, shift_y -0.5, square_long, rotation kp0->kp2 target 90°;
//   landmark input 224x224 float[0,1]; landmarks raw/224 in ROI-local space.

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include "rknn_api.h"

#include <cmath>
#include <cstring>
#include <chrono>
#include <vector>
#include <algorithm>
#include <memory>
#include <atomic>
#include <mutex>

#define LOG_TAG "GestureNPU"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// 常量 (与 MediaPipe palm_detection / hand_landmark 配置一致)
// ---------------------------------------------------------------------------
static constexpr int PALM_INPUT = 192;          // palm_detection 输入尺寸
static constexpr int PALM_NUM_BOXES = 2016;
static constexpr int PALM_NUM_COORDS = 18;      // 4 box + 7*2 keypoints
static constexpr int PALM_SCORE_CLIP = 100.0f;
static constexpr float PALM_SCORE_THRESH = 0.5f; // 与 MediaPipe 默认一致, 降低误检
static constexpr float PALM_NMS_THRESH = 0.35f;

static constexpr int LM_INPUT = 224;            // hand_landmark 输入尺寸
static constexpr int LM_NUM = 21;               // 关键点数

// palm box -> landmark ROI 变换参数 (palm_detection_detection_to_roi.pbtxt)
static constexpr float ROI_SCALE_PALM = 2.6f;
static constexpr float ROI_SHIFT_Y_PALM = -0.5f;
// landmark -> ROI 跟踪参数 (hand_landmark_landmarks_to_roi.pbtxt)
static constexpr float ROI_SCALE_TRACK = 2.0f;
static constexpr float ROI_SHIFT_Y_TRACK = -0.1f;

// 输出数组布局 (给 Kotlin)
static constexpr int OUT_SIZE = 74;
static constexpr int OUT_MS = 0;
static constexpr int OUT_GESTURE = 1;
static constexpr int OUT_SCORE = 2;
static constexpr int OUT_HAND = 3;
static constexpr int OUT_PALM_VALID = 4;
static constexpr int OUT_PALM_X = 5;
static constexpr int OUT_PALM_Y = 6;
static constexpr int OUT_PALM_W = 7;
static constexpr int OUT_PALM_H = 8;
static constexpr int OUT_LM = 9;                // 21*3, image-normalized x,y
static constexpr int OUT_DIR = 72;              // 保留偏移(恒 0, 滑动方向由 Kotlin 状态机计算)
static constexpr int OUT_OPEN = 73;             // 张开度: 伸直手指数(含拇指) 0..5

// 手势枚举 (与 Kotlin GestureId 一致)
// WAVE(挥动手臂) 是时序动作, 由 Kotlin 状态机检测, 不在此分类
enum Gesture {
    G_NONE = 0, G_PALM = 1, G_FIST = 2, G_VICTORY = 3
};

// MediaPipe 21 关键点索引
static constexpr int L_WRIST = 0;
static constexpr int L_THUMB_TIP = 4;
static constexpr int L_INDEX_MCP = 5, L_INDEX_PIP = 6, L_INDEX_TIP = 8;
static constexpr int L_MID_MCP = 9, L_MID_PIP = 10, L_MID_TIP = 12;
static constexpr int L_RING_MCP = 13, L_RING_PIP = 14, L_RING_TIP = 16;
static constexpr int L_PINKY_MCP = 17, L_PINKY_PIP = 18, L_PINKY_TIP = 20;

// ---------------------------------------------------------------------------
// RKNN 上下文
// ---------------------------------------------------------------------------
struct ModelCtx {
    rknn_context ctx = 0;
    uint32_t n_inputs = 1;
    uint32_t n_outputs = 2;
    rknn_tensor_attr in_attr{};
    std::vector<rknn_tensor_attr> out_attrs;
    int in_w = PALM_INPUT, in_h = PALM_INPUT;
    std::vector<float> in_buf;      // NHWC float
};

struct PalmBox {
    float cx, cy, w, h;            // image-normalized (未 letterbox 还原后)
    float score;
    float kpx[7], kpy[7];          // 图像归一化关键点
};

static ModelCtx g_palm;
static ModelCtx g_lm;
static bool g_ready = false;

// letterbox 参数 (由第一帧的图像尺寸确定)
static int g_img_w = 0, g_img_h = 0;
static int g_pad_x = 0, g_pad_y = 0;   // letterbox pad (像素)
static int g_resized_w = 0, g_resized_h = 0;

// 跟踪状态 (上一帧 landmark ROI)
struct TrackRoi {
    bool valid = false;
    float cx = 0, cy = 0, w = 0, h = 0;  // image-normalized, 方形
    float theta = 0;                      // 弧度
    int miss = 0;
};
static TrackRoi g_track;

// anchors (一次生成)
struct Anchor { float cx, cy, w, h; };
static std::vector<Anchor> g_anchors;

// 工作缓冲
static std::vector<uint8_t> g_rgb;      // imgW*imgH*3 (相机帧)
static std::vector<uint8_t> g_ext_rgb;  // 算法帧 RGB888 (Bitmap/外部帧, 复用避免每帧分配)
static std::vector<float> g_palm_in;    // 192*192*3
static std::vector<float> g_lm_in;      // 224*224*3
static float g_fps_ema = 0;

// 水平翻转（抵消部分 USB 相机驱动默认的镜像输出），显示与推理保持一致
static bool g_flip_h = false;

// 预览位图双缓冲: 相机线程写 back 后原子切换 front; UI 线程在锁内拷贝快照绘制,
// 消除并发读写撕裂。
static jobject g_preview_bitmaps[2] = {nullptr, nullptr};
static std::atomic<int> g_preview_front{0};
static std::mutex g_preview_mutex;

// 推理管线互斥: 相机线程与算法帧线程 (Bitmap/外部帧) 可能并发调用同一套
// RKNN 上下文与跟踪状态, 必须串行化。
static std::mutex g_infer_mutex;

// ---------------------------------------------------------------------------
// anchors: 严格复刻 mediapipe ssd_anchors_calculator.cc GenerateAnchors()
// options: num_layers=4, min_scale=0.1484375, max_scale=0.75, input 192,
//          strides [8,16,16,16], aspect_ratios [1.0], fixed_anchor_size,
//          interpolated_scale_aspect_ratio 默认 1.0
// => stride8: 24x24 cells x2 anchors = 1152
//    stride16(合并3层): 12x12 cells x6 anchors = 864  (共 2016)
// ---------------------------------------------------------------------------
static float CalculateScale(float min_scale, float max_scale, int stride_index,
                            int num_strides) {
    if (num_strides == 1) return (min_scale + max_scale) * 0.5f;
    return min_scale + (max_scale - min_scale) * 1.0 * stride_index / (num_strides - 1.0f);
}

static void GenerateAnchors() {
    g_anchors.clear();
    const int strides[4] = {8, 16, 16, 16};
    const float min_scale = 0.1484375f, max_scale = 0.75f;
    const int num_strides = 4;
    const float interp_ar = 1.0f;
    const int input = PALM_INPUT;

    int layer_id = 0;
    while (layer_id < 4) {
        std::vector<float> scales, aspect_ratios;
        int last_same = layer_id;
        while (last_same < 4 && strides[last_same] == strides[layer_id]) {
            float scale = CalculateScale(min_scale, max_scale, last_same, num_strides);
            aspect_ratios.push_back(1.0f);
            scales.push_back(scale);
            if (interp_ar > 0.0f) {
                float scale_next = (last_same == num_strides - 1)
                        ? 1.0f
                        : CalculateScale(min_scale, max_scale, last_same + 1, num_strides);
                scales.push_back(std::sqrt(scale * scale_next));
                aspect_ratios.push_back(interp_ar);
            }
            last_same++;
        }
        (void)aspect_ratios; // 全部为 1.0

        int stride = strides[layer_id];
        int fm = (int)std::ceil(1.0f * input / stride);
        for (int y = 0; y < fm; ++y) {
            for (int x = 0; x < fm; ++x) {
                for (int a = 0; a < (int)scales.size(); ++a) {
                    (void)scales[a]; (void)a;  // fixed_anchor_size: 尺寸恒 1.0
                    Anchor an;
                    an.cx = (x + 0.5f) * 1.0f / fm;
                    an.cy = (y + 0.5f) * 1.0f / fm;
                    an.w = 1.0f;
                    an.h = 1.0f;
                    g_anchors.push_back(an);
                }
            }
        }
        layer_id = last_same;
    }
    LOGI("anchors generated: %zu", g_anchors.size());
}

// ---------------------------------------------------------------------------
// YUV420_888 -> RGB (BT.601 full range), 支持 pixelStride 1/2
// ---------------------------------------------------------------------------
static inline uint8_t clamp255(float v) {
    return (uint8_t)(v < 0 ? 0 : (v > 255 ? 255 : v));
}

static void yuv420_to_rgb(const uint8_t* y, const uint8_t* u, const uint8_t* v,
                          int w, int h,
                          int yStride, int uStride, int vStride,
                          int uPix, int vPix, uint8_t* rgb) {
    for (int row = 0; row < h; ++row) {
        const uint8_t* yRow = y + (size_t)row * yStride;
        const uint8_t* uRow = u + (size_t)(row >> 1) * uStride;
        const uint8_t* vRow = v + (size_t)(row >> 1) * vStride;
        uint8_t* rRow = rgb + (size_t)row * w * 3;
        for (int col = 0; col < w; ++col) {
            float Y = yRow[col];
            // 色度按半分辨率索引: 像素列 col -> 采样 col/2
            // 平面格式(uPix=1): 字节 col/2; 半平面 NV12(uPix=2): 字节 col (交错槽位)
            float U = uRow[(col >> 1) * uPix] - 128.0f;
            float V = vRow[(col >> 1) * vPix] - 128.0f;
            float r = Y + 1.402f * V;
            float gg = Y - 0.344136f * U - 0.714136f * V;
            float b = Y + 1.772f * U;
            rRow[col * 3 + 0] = clamp255(r);
            rRow[col * 3 + 1] = clamp255(gg);
            rRow[col * 3 + 2] = clamp255(b);
        }
    }
}

// RGB 水平镜像（行内左右交换像素）
static void flip_rgb_horizontal(uint8_t* rgb, int w, int h) {
    for (int y = 0; y < h; ++y) {
        uint8_t* row = rgb + (size_t)y * w * 3;
        for (int x = 0; x < w / 2; ++x) {
            uint8_t* a = row + (size_t)x * 3;
            uint8_t* b = row + (size_t)(w - 1 - x) * 3;
            for (int c = 0; c < 3; ++c) std::swap(a[c], b[c]);
        }
    }
}

// letterbox 缩放 RGB -> float NHWC [0,1]
static void letterbox_rgb(const uint8_t* rgb, int w, int h, int dst,
                          std::vector<float>& out) {
    float scale = std::min((float)dst / w, (float)dst / h);
    int rw = (int)(w * scale + 0.5f);
    int rh = (int)(h * scale + 0.5f);
    g_resized_w = rw; g_resized_h = rh;
    g_pad_x = (dst - rw) / 2;
    g_pad_y = (dst - rh) / 2;
    out.assign((size_t)dst * dst * 3, 0.f);
    for (int y = 0; y < rh; ++y) {
        int sy = (int)(y / scale);
        sy = sy < 0 ? 0 : (sy >= h ? h - 1 : sy);
        const uint8_t* src = rgb + (size_t)sy * w * 3;
        float* dstRow = out.data() + (size_t)(y + g_pad_y) * dst * 3;
        for (int x = 0; x < rw; ++x) {
            int sx = (int)(x / scale);
            sx = sx < 0 ? 0 : (sx >= w ? w - 1 : sx);
            dstRow[x * 3 + 0] = src[sx * 3 + 0] / 255.f;
            dstRow[x * 3 + 1] = src[sx * 3 + 1] / 255.f;
            dstRow[x * 3 + 2] = src[sx * 3 + 2] / 255.f;
        }
    }
}

// ---------------------------------------------------------------------------
// palm 解码 + NMS
// ---------------------------------------------------------------------------
static inline float sigmoid(float x) {
    if (x > PALM_SCORE_CLIP) x = PALM_SCORE_CLIP;
    if (x < -PALM_SCORE_CLIP) x = -PALM_SCORE_CLIP;
    return 1.f / (1.f + std::exp(-x));
}

static float iou(const PalmBox& a, const PalmBox& b) {
    float ax0 = a.cx - a.w / 2, ay0 = a.cy - a.h / 2;
    float ax1 = a.cx + a.w / 2, ay1 = a.cy + a.h / 2;
    float bx0 = b.cx - b.w / 2, by0 = b.cy - b.h / 2;
    float bx1 = b.cx + b.w / 2, by1 = b.cy + b.h / 2;
    float ix0 = std::max(ax0, bx0), iy0 = std::max(ay0, by0);
    float ix1 = std::min(ax1, bx1), iy1 = std::min(ay1, by1);
    float iw = std::max(0.f, ix1 - ix0), ih = std::max(0.f, iy1 - iy0);
    float inter = iw * ih;
    float areaA = a.w * a.h, areaB = b.w * b.h;
    return inter / (areaA + areaB - inter + 1e-6f);
}

// letterbox 坐标 -> 图像归一化坐标
static inline float lbx_to_img(float nx) { return (nx * PALM_INPUT - g_pad_x) / g_resized_w; }
static inline float lby_to_img(float ny) { return (ny * PALM_INPUT - g_pad_y) / g_resized_h; }

static void decode_palms(const float* regress, const float* clssf,
                         std::vector<PalmBox>& out) {
    out.clear();
    for (int i = 0; i < PALM_NUM_BOXES; ++i) {
        float score = sigmoid(clssf[i]);
        if (score < PALM_SCORE_THRESH) continue;
        const float* r = regress + (size_t)i * PALM_NUM_COORDS;
        const Anchor& a = g_anchors[i];
        // reverse_output_order=true: raw[0]=x raw[1]=y raw[2]=w raw[3]=h
        float x = r[0] / PALM_INPUT * a.w + a.cx;
        float y = r[1] / PALM_INPUT * a.h + a.cy;
        float w = r[2] / PALM_INPUT * a.w;
        float h = r[3] / PALM_INPUT * a.h;
        PalmBox b;
        b.score = score;
        b.cx = lbx_to_img(x);
        b.cy = lby_to_img(y);
        b.w = w * PALM_INPUT / g_resized_w;
        b.h = h * PALM_INPUT / g_resized_h;
        for (int k = 0; k < 7; ++k) {
            b.kpx[k] = lbx_to_img(r[4 + 2 * k] / PALM_INPUT * a.w + a.cx);
            b.kpy[k] = lby_to_img(r[5 + 2 * k] / PALM_INPUT * a.h + a.cy);
        }
        out.push_back(b);
    }
    // NMS (按分数降序, 抑制 IoU>阈值)
    std::sort(out.begin(), out.end(),
              [](const PalmBox& a, const PalmBox& b) { return a.score > b.score; });
    std::vector<PalmBox> keep;
    for (auto& b : out) {
        bool suppressed = false;
        for (auto& k : keep) {
            if (iou(b, k) > PALM_NMS_THRESH) { suppressed = true; break; }
        }
        if (!suppressed) keep.push_back(b);
        if (keep.size() >= 2) break; // 只需一只手
    }
    out.swap(keep);
}

// ---------------------------------------------------------------------------
// ROI 变换 (rect_transformation_calculator.cc 逻辑)
// rect (cx,cy,w,h 归一化) + 旋转 theta + scale + shift(square_long)
// ---------------------------------------------------------------------------
static void transform_rect(float cx, float cy, float w, float h, float theta,
                            float scale_x, float scale_y, float shift_y,
                            float& ncx, float& ncy, float& nw) {
    float width = w * scale_x;
    float height = h * scale_y;
    if (width >= height) height = width; else width = height; // square_long
    nw = width;
    // shift 在旋转坐标系中
    float shift_x = 0.f;
    float sy = width * shift_y;
    float cosT = std::cos(theta), sinT = std::sin(theta);
    ncx = cx + (shift_x * cosT - sy * sinT);
    ncy = cy + (shift_x * sinT + sy * cosT);
}

// ROI 旋转: 使裁剪框的"局部上方"对准 手腕->中指MCP 方向。
// 裁剪公式中局部上 (0,-1) 映射到图像方向 (sinθ, -cosθ),
// 故要求 θ = atan2(Δx, -Δy) (Δ 为像素空间向量)。
static float palm_rotation(const PalmBox& b) {
    float dx = (b.kpx[2] - b.kpx[0]) * g_img_w;
    float dy = (b.kpy[2] - b.kpy[0]) * g_img_h;
    return std::atan2(dx, -dy);
}

// HandLandmarksToRectCalculator: bbox + rotation wrist->middleMCP
static float lm_rotation(const float* lm) { // lm: image-normalized 21*3
    float dx = (lm[3 * L_MID_MCP + 0] - lm[3 * L_WRIST + 0]) * g_img_w;
    float dy = (lm[3 * L_MID_MCP + 1] - lm[3 * L_WRIST + 1]) * g_img_h;
    return std::atan2(dx, -dy);
}

// 旋转 ROI -> 224x224 float [0,1] (双线性)
static void crop_roi(const uint8_t* rgb, float cx, float cy, float w, float theta,
                     std::vector<float>& out) {
    out.assign((size_t)LM_INPUT * LM_INPUT * 3, 0.f);
    float cosT = std::cos(theta), sinT = std::sin(theta);
    float h = w; // 方形
    for (int j = 0; j < LM_INPUT; ++j) {
        float ly = (j + 0.5f) / LM_INPUT;
        for (int i = 0; i < LM_INPUT; ++i) {
            float lx = (i + 0.5f) / LM_INPUT;
            float dx = (lx - 0.5f) * w;
            float dy = (ly - 0.5f) * h;
            float rx = dx * cosT - dy * sinT;
            float ry = dx * sinT + dy * cosT;
            float px = (cx + rx) * g_img_w - 0.5f;
            float py = (cy + ry) * g_img_h - 0.5f;
            int x0 = (int)std::floor(px), y0 = (int)std::floor(py);
            float fx = px - x0, fy = py - y0;
            float* dst = out.data() + ((size_t)j * LM_INPUT + i) * 3;
            if (x0 >= 0 && y0 >= 0 && x0 + 1 < g_img_w && y0 + 1 < g_img_h) {
                const uint8_t* p00 = rgb + ((size_t)y0 * g_img_w + x0) * 3;
                const uint8_t* p01 = p00 + 3;
                const uint8_t* p10 = rgb + ((size_t)(y0 + 1) * g_img_w + x0) * 3;
                const uint8_t* p11 = p10 + 3;
                for (int c = 0; c < 3; ++c) {
                    float v = (1 - fy) * ((1 - fx) * p00[c] + fx * p01[c])
                            + fy * ((1 - fx) * p10[c] + fx * p11[c]);
                    dst[c] = v / 255.f;
                }
            }
        }
    }
}

// ROI-local 归一化坐标 -> 图像归一化坐标
static inline void roi_local_to_img(float lx, float ly, float cx, float cy,
                                    float w, float theta, float& ix, float& iy) {
    float dx = (lx - 0.5f) * w;
    float dy = (ly - 0.5f) * w;
    float cosT = std::cos(theta), sinT = std::sin(theta);
    ix = cx + dx * cosT - dy * sinT;
    iy = cy + dx * sinT + dy * cosT;
}

// ---------------------------------------------------------------------------
// 手势分类 (像素空间几何)
// ---------------------------------------------------------------------------
static int classify_gesture(const float* lm, int* openCnt) { // image-normalized 21*3
    auto px = [&](int i) { return lm[3 * i + 0] * g_img_w; };
    auto dist = [&](int a, int b) {
        float dx = px(a) - px(b);
        float dy = lm[3 * a + 1] * g_img_h - lm[3 * b + 1] * g_img_h;
        return std::sqrt(dx * dx + dy * dy);
    };
    float palm = dist(L_WRIST, L_MID_MCP);
    if (palm < 1.f) return G_NONE;

    // 四指伸直判定: tip 离腕明显比 pip 远
    auto extended = [&](int pip, int tip) {
        return dist(L_WRIST, tip) > dist(L_WRIST, pip) * 1.15f;
    };
    bool idx = extended(L_INDEX_PIP, L_INDEX_TIP);
    bool mid = extended(L_MID_PIP, L_MID_TIP);
    bool rng = extended(L_RING_PIP, L_RING_TIP);
    bool pky = extended(L_PINKY_PIP, L_PINKY_TIP);
    // 拇指: tip 到小指 MCP 的距离
    bool thm = dist(L_THUMB_TIP, L_PINKY_MCP) > palm * 0.65f;

    int four = (idx ? 1 : 0) + (mid ? 1 : 0) + (rng ? 1 : 0) + (pky ? 1 : 0);
    if (openCnt) *openCnt = four + (thm ? 1 : 0);

    if (four == 4) return G_PALM;
    if (four == 0 && !thm) return G_FIST;
    if (idx && mid && !rng && !pky) return G_VICTORY;
    return G_NONE;
}

// ---------------------------------------------------------------------------
// RKNN 辅助
// ---------------------------------------------------------------------------
static bool load_model_file(AAssetManager* mgr, const char* name,
                            std::vector<uint8_t>& data) {
    AAsset* asset = AAssetManager_open(mgr, name, AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("asset not found: %s", name);
        return false;
    }
    off_t len = AAsset_getLength(asset);
    data.resize(len);
    AAsset_read(asset, data.data(), len);
    AAsset_close(asset);
    return true;
}

static bool setup_model(ModelCtx& m, const std::vector<uint8_t>& data, const char* tag) {
    int ret = rknn_init(&m.ctx, (void*)data.data(), data.size(), 0, nullptr);
    if (ret < 0) {
        LOGE("%s rknn_init fail ret=%d", tag, ret);
        return false;
    }
    rknn_input_output_num io_num;
    ret = rknn_query(m.ctx, RKNN_QUERY_IN_OUT_NUM, &io_num, sizeof(io_num));
    if (ret < 0) return false;
    m.n_inputs = io_num.n_input;
    m.n_outputs = io_num.n_output;

    m.in_attr = {};
    m.in_attr.index = 0;
    ret = rknn_query(m.ctx, RKNN_QUERY_INPUT_ATTR, &m.in_attr, sizeof(rknn_tensor_attr));
    if (ret < 0) return false;
    // dims 实测顺序 {N,H,W,C} (NHWC 自然序)。按通道维位置自适应:
    if (m.in_attr.n_dims == 4) {
        uint32_t a = m.in_attr.dims[1], b = m.in_attr.dims[2], c = m.in_attr.dims[3];
        if (c <= 4) { m.in_h = a; m.in_w = b; }       // {N,H,W,C}
        else { m.in_h = b; m.in_w = c; }              // {N,C,H,W}
    } else {
        m.in_w = PALM_INPUT; m.in_h = PALM_INPUT;
    }
    LOGI("%s: input dims=%d [%u %u %u %u] fmt=%d type=%d -> in=%dx%d", tag, m.in_attr.n_dims,
         m.in_attr.dims[0], m.in_attr.dims[1], m.in_attr.dims[2], m.in_attr.dims[3],
         m.in_attr.fmt, m.in_attr.type, m.in_w, m.in_h);
    m.in_buf.resize((size_t)m.in_w * m.in_h * 3);

    m.out_attrs.resize(m.n_outputs);
    for (uint32_t i = 0; i < m.n_outputs; ++i) {
        m.out_attrs[i] = {};
        m.out_attrs[i].index = i;
        rknn_query(m.ctx, RKNN_QUERY_OUTPUT_ATTR, &m.out_attrs[i], sizeof(rknn_tensor_attr));
        char dims[128] = {0};
        int off = 0;
        for (uint32_t d = 0; d < m.out_attrs[i].n_dims; ++d)
            off += snprintf(dims + off, sizeof(dims) - off, "%u ", m.out_attrs[i].dims[d]);
        LOGI("%s: out[%u] dims=[%s] n_elems=%u", tag, i, dims, m.out_attrs[i].n_elems);
    }
    return true;
}

static bool run_model(ModelCtx& m, std::vector<std::vector<float>>& outs) {
    rknn_input input;
    memset(&input, 0, sizeof(input));
    input.index = 0;
    input.type = RKNN_TENSOR_FLOAT32;
    input.fmt = RKNN_TENSOR_NHWC;
    input.size = (uint32_t)(m.in_buf.size() * sizeof(float));
    input.buf = m.in_buf.data();
    input.pass_through = 0;
    if (rknn_inputs_set(m.ctx, 1, &input) < 0) return false;
    if (rknn_run(m.ctx, nullptr) < 0) return false;

    std::vector<rknn_output> outputs(m.n_outputs);
    for (uint32_t i = 0; i < m.n_outputs; ++i) {
        outputs[i].want_float = 1;
        outputs[i].is_prealloc = 0;
        outputs[i].index = i;
    }
    if (rknn_outputs_get(m.ctx, m.n_outputs, outputs.data(), nullptr) < 0) return false;
    outs.resize(m.n_outputs);
    for (uint32_t i = 0; i < m.n_outputs; ++i) {
        const float* p = (const float*)outputs[i].buf;
        outs[i].assign(p, p + outputs[i].size / sizeof(float));
    }
    rknn_outputs_release(m.ctx, m.n_outputs, outputs.data());
    return true;
}

// ---------------------------------------------------------------------------
// 单帧处理
// ---------------------------------------------------------------------------

// 相机预览位图更新 (双缓冲): 写后台缓冲后原子切换 front
static void update_preview_bitmap(JNIEnv* env, int w, int h) {
    if (!g_preview_bitmaps[0]) return;
    std::lock_guard<std::mutex> lk(g_preview_mutex);
    int back = 1 - g_preview_front.load(std::memory_order_relaxed);
    jobject bmp = g_preview_bitmaps[back];
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bmp, &info) >= 0
        && info.width == (uint32_t)w && info.height == (uint32_t)h
        && info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bmp, &pixels) >= 0 && pixels) {
            auto* dst = (uint8_t*)pixels;
            for (int yy = 0; yy < h; ++yy) {
                const uint8_t* srcRow = g_rgb.data() + (size_t)yy * w * 3;
                uint8_t* dstRow = dst + (size_t)yy * info.stride;
                for (int xx = 0; xx < w; ++xx) {
                    dstRow[xx * 4 + 0] = srcRow[xx * 3 + 0];
                    dstRow[xx * 4 + 1] = srcRow[xx * 3 + 1];
                    dstRow[xx * 4 + 2] = srcRow[xx * 3 + 2];
                    dstRow[xx * 4 + 3] = 255;
                }
            }
            AndroidBitmap_unlockPixels(env, bmp);
            g_preview_front.store(back, std::memory_order_release);
        }
    }
}

// RGB888 输入核心: palm -> landmark -> 静态手势分类。
// 相机帧与算法帧 (Bitmap / 外部 YUV) 共用; 调用方须持有 g_infer_mutex。
// reset_track=true 用于单张图片分析: 丢弃上一帧跟踪 ROI, 强制重新做 palm 检测。
static int process_rgb(const uint8_t* rgb, int w, int h, float* out, bool reset_track) {
    auto t0 = std::chrono::steady_clock::now();
    memset(out, 0, OUT_SIZE * sizeof(float));

    if (g_img_w != w || g_img_h != h) {
        g_img_w = w; g_img_h = h;
        g_track = TrackRoi{};
    } else if (reset_track) {
        g_track = TrackRoi{};
    }

    // 1. palm detection
    letterbox_rgb(rgb, w, h, g_palm.in_w, g_palm_in);
    memcpy(g_palm.in_buf.data(), g_palm_in.data(), g_palm.in_buf.size() * sizeof(float));
    std::vector<std::vector<float>> palm_out;
    if (!run_model(g_palm, palm_out)) return -1;
    // 输出: 找 [*,18] 与 [*,1]
    const float* regress = nullptr; const float* clssf = nullptr;
    for (auto& o : palm_out) {
        if (o.size() == PALM_NUM_BOXES * PALM_NUM_COORDS) regress = o.data();
        else if (o.size() == PALM_NUM_BOXES) clssf = o.data();
    }
    float palm_ms_total = 0;
    if (!regress || !clssf) {
        LOGW("palm output mismatch");
        return -2;
    }

    std::vector<PalmBox> palms;
    decode_palms(regress, clssf, palms);

    float palm_x = 0, palm_y = 0, palm_w = 0, palm_h = 0;
    bool palm_valid = !palms.empty();
    if (palm_valid) {
        const PalmBox& p = palms[0];
        palm_x = p.cx; palm_y = p.cy; palm_w = p.w; palm_h = p.h;
    }

    // 3. ROI 选择: 优先跟踪, 否则 palm
    float roi_cx = 0, roi_cy = 0, roi_w = 0, roi_theta = 0;
    bool use_track = false;
    if (g_track.valid && g_track.miss < 5) {
        use_track = true;
        roi_cx = g_track.cx; roi_cy = g_track.cy;
        roi_w = g_track.w; roi_theta = g_track.theta;
    } else if (palm_valid) {
        float theta = palm_rotation(palms[0]);
        transform_rect(palms[0].cx, palms[0].cy, palms[0].w, palms[0].h, theta,
                       ROI_SCALE_PALM, ROI_SCALE_PALM, ROI_SHIFT_Y_PALM,
                       roi_cx, roi_cy, roi_w);
        roi_theta = theta;
    } else {
        g_track.miss++;
        out[OUT_PALM_VALID] = 0;
        auto t1 = std::chrono::steady_clock::now();
        float ms = std::chrono::duration<float, std::milli>(t1 - t0).count();
        g_fps_ema = g_fps_ema * 0.9f + ms * 0.1f;
        out[OUT_MS] = ms;
        return 0;
    }

    // 4. landmark 推理
    crop_roi(rgb, roi_cx, roi_cy, roi_w, roi_theta, g_lm_in);
    memcpy(g_lm.in_buf.data(), g_lm_in.data(), g_lm.in_buf.size() * sizeof(float));
    std::vector<std::vector<float>> lm_out;
    if (!run_model(g_lm, lm_out)) return -3;
    // 输出: [63 landmarks][1 presence][1 handedness][63 world]
    const float* lm_raw = nullptr; const float* presence = nullptr; const float* handed = nullptr;
    for (auto& o : lm_out) {
        if (o.size() >= LM_NUM * 3 && lm_raw == nullptr) lm_raw = o.data();
        else if (o.size() == 1 && presence == nullptr) presence = o.data();
        else if (o.size() == 1) handed = o.data();
    }
    if (!lm_raw) {
        LOGW("landmark output mismatch");
        return -4;
    }

    float score = presence ? sigmoid(*presence) : 1.f;
    // 关键点映射到图像坐标
    float lm[LM_NUM * 3];
    for (int i = 0; i < LM_NUM; ++i) {
        float lx = lm_raw[3 * i + 0] / g_lm.in_w;
        float ly = lm_raw[3 * i + 1] / g_lm.in_h;
        float ix, iy;
        roi_local_to_img(lx, ly, roi_cx, roi_cy, roi_w, roi_theta, ix, iy);
        lm[3 * i + 0] = ix;
        lm[3 * i + 1] = iy;
        lm[3 * i + 2] = lm_raw[3 * i + 2] / g_lm.in_w;
    }

    // 5. 更新跟踪 ROI (presence 是 logit, sigmoid>0.6 才认为有手)
    if (score > 0.6f) {
        // bbox of landmarks
        float minx = 1e9f, miny = 1e9f, maxx = -1e9f, maxy = -1e9f;
        for (int i = 0; i < LM_NUM; ++i) {
            minx = std::min(minx, lm[3 * i + 0]);
            maxx = std::max(maxx, lm[3 * i + 0]);
            miny = std::min(miny, lm[3 * i + 1]);
            maxy = std::max(maxy, lm[3 * i + 1]);
        }
        float bcx = (minx + maxx) / 2, bcy = (miny + maxy) / 2;
        float bw = (maxx - minx) * g_img_w / g_img_w; // 保持归一化
        float bh = (maxy - miny);
        float theta = lm_rotation(lm);
        float ncx, ncy, nw;
        transform_rect(bcx, bcy, bw, bh, theta,
                       ROI_SCALE_TRACK, ROI_SCALE_TRACK, ROI_SHIFT_Y_TRACK,
                       ncx, ncy, nw);
        g_track.cx = ncx; g_track.cy = ncy; g_track.w = nw;
        g_track.theta = theta;
        g_track.valid = true;
        g_track.miss = 0;
    } else {
        g_track.miss++;
    }

    // 6. 手势分类 (置信度不足时不输出手势)
    //    滑动方向/挥手由 Kotlin 状态机基于掌心轨迹做时序检测, 此处只分类静态姿势
    int openCnt = 0;
    int gesture = (score > 0.65f) ? classify_gesture(lm, &openCnt) : G_NONE;

    // 输出
    auto t1 = std::chrono::steady_clock::now();
    float ms = std::chrono::duration<float, std::milli>(t1 - t0).count();
    (void)palm_ms_total;
    g_fps_ema = g_fps_ema * 0.9f + ms * 0.1f;

    // 心跳日志 (每 60 帧)
    static int hb = 0;
    if (++hb % 60 == 1) {
        LOGI("hb gesture=%d score=%.2f palm=%.2f,%.2f w=%.2f ms=%.1f",
             gesture, score, palm_x, palm_y, palm_w, ms);
    }

    out[OUT_MS] = ms;
    out[OUT_GESTURE] = (float)gesture;
    out[OUT_SCORE] = score;
    out[OUT_HAND] = handed ? sigmoid(*handed) : -1;
    out[OUT_PALM_VALID] = palm_valid ? 1.f : 0.f;
    out[OUT_PALM_X] = palm_x;
    out[OUT_PALM_Y] = palm_y;
    out[OUT_PALM_W] = palm_w;
    out[OUT_PALM_H] = palm_h;
    out[OUT_OPEN] = (float)openCnt;
    memcpy(out + OUT_LM, lm, sizeof(lm));
    return 0;
}

// 相机帧入口: YUV420_888 -> RGB -> 共用核心
static int process_frame(JNIEnv* env, const uint8_t* y, const uint8_t* u, const uint8_t* v,
                         int w, int h,
                         int yStride, int uStride, int vStride,
                         int uPix, int vPix,
                         float* out) {
    // g_img_w/g_img_h 会被算法帧路径改写, 而算法帧不分配 g_rgb。
    // 只按尺寸变化判断会漏掉 "先 analyzeImage 640x480, 再开相机" 的情况:
    // 此时尺寸相同但 g_rgb 为空 -> yuv420_to_rgb 越界写。按实际容量判断。
    if (g_rgb.size() != (size_t)w * h * 3) {
        g_rgb.resize((size_t)w * h * 3);
    }
    yuv420_to_rgb(y, u, v, w, h, yStride, uStride, vStride, uPix, vPix, g_rgb.data());
    if (g_flip_h) flip_rgb_horizontal(g_rgb.data(), w, h);

    // 预览位图每帧更新 (放在一切提前 return 之前)
    update_preview_bitmap(env, w, h);

    return process_rgb(g_rgb.data(), w, h, out, false);
}

// ---------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------
extern "C" {

JNIEXPORT jint JNICALL
Java_com_rk3588_gesturelib_RknnBridge_nativeInit(JNIEnv* env, jclass,
                                                 jobject assetManager) {
    std::lock_guard<std::mutex> lk(g_infer_mutex);
    if (g_ready) return 0;
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) return -10;

    std::vector<uint8_t> palm_data, lm_data;
    if (!load_model_file(mgr, "models/palm_detection.rknn", palm_data)) return -11;
    if (!load_model_file(mgr, "models/hand_landmark.rknn", lm_data)) return -12;

    GenerateAnchors();
    if (!setup_model(g_palm, palm_data, "palm")) return -13;
    if (!setup_model(g_lm, lm_data, "landmark")) return -14;
    if (g_palm.in_w != PALM_INPUT || g_lm.in_w != LM_INPUT) {
        LOGW("model input size mismatch: palm=%d landmark=%d", g_palm.in_w, g_lm.in_w);
    }
    g_ready = true;
    LOGI("init OK, palm in=%dx%d lm in=%dx%d", g_palm.in_w, g_palm.in_h, g_lm.in_w, g_lm.in_h);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_rk3588_gesturelib_RknnBridge_nativeDeinit(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_infer_mutex);
    if (g_palm.ctx) rknn_destroy(g_palm.ctx);
    if (g_lm.ctx) rknn_destroy(g_lm.ctx);
    g_palm.ctx = 0;
    g_lm.ctx = 0;
    g_ready = false;
}

JNIEXPORT jint JNICALL
Java_com_rk3588_gesturelib_RknnBridge_nativeProcessFrame(
        JNIEnv* env, jclass,
        jobject yBuf, jobject uBuf, jobject vBuf,
        jint width, jint height,
        jint yStride, jint uStride, jint vStride,
        jint uPixStride, jint vPixStride,
        jfloatArray outArr) {

    auto y = (const uint8_t*)env->GetDirectBufferAddress(yBuf);
    auto u = (const uint8_t*)env->GetDirectBufferAddress(uBuf);
    auto v = (const uint8_t*)env->GetDirectBufferAddress(vBuf);
    if (!y || !u || !v) return -20;

    std::lock_guard<std::mutex> lk(g_infer_mutex);
    jfloat* out = env->GetFloatArrayElements(outArr, nullptr);
    int ret = process_frame(env, y, u, v, width, height,
                            yStride, uStride, vStride,
                            uPixStride, vPixStride, out);
    env->ReleaseFloatArrayElements(outArr, out, 0);
    return ret;
}

// 算法帧入口: Bitmap (RGBA_8888) -> RGB888 -> 共用核心。
// resetTrack=true: 单张图片分析, 丢弃上一帧跟踪 ROI。
JNIEXPORT jint JNICALL
Java_com_rk3588_gesturelib_RknnBridge_nativeProcessBitmap(
        JNIEnv* env, jclass,
        jobject bitmap, jboolean resetTrack, jfloatArray outArr) {

    std::lock_guard<std::mutex> lk(g_infer_mutex);
    if (!g_ready) return -40;
    if (!bitmap) return -41;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return -42;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return -43;
    int w = (int)info.width, h = (int)info.height;
    if (w <= 0 || h <= 0) return -44;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) return -45;

    std::vector<uint8_t>& rgb = g_ext_rgb;
    rgb.resize((size_t)w * h * 3);
    const auto* src = (const uint8_t*)pixels;
    for (int yy = 0; yy < h; ++yy) {
        const uint8_t* srcRow = src + (size_t)yy * info.stride;
        uint8_t* dstRow = rgb.data() + (size_t)yy * w * 3;
        for (int xx = 0; xx < w; ++xx) {
            dstRow[xx * 3 + 0] = srcRow[xx * 4 + 0];
            dstRow[xx * 3 + 1] = srcRow[xx * 4 + 1];
            dstRow[xx * 3 + 2] = srcRow[xx * 4 + 2];
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);

    if (g_flip_h) flip_rgb_horizontal(rgb.data(), w, h);

    jfloat* out = env->GetFloatArrayElements(outArr, nullptr);
    int ret = process_rgb(rgb.data(), w, h, out, resetTrack != JNI_FALSE);
    env->ReleaseFloatArrayElements(outArr, out, 0);
    return ret;
}

JNIEXPORT void JNICALL
Java_com_rk3588_gesturelib_RknnBridge_nativeSetPreviewBitmaps(JNIEnv* env, jclass,
                                                              jobject bmpA, jobject bmpB) {
    std::lock_guard<std::mutex> lk(g_preview_mutex);
    for (auto& ref : g_preview_bitmaps) {
        if (ref) env->DeleteGlobalRef(ref);
        ref = nullptr;
    }
    g_preview_front.store(0, std::memory_order_relaxed);
    if (bmpA) g_preview_bitmaps[0] = env->NewGlobalRef(bmpA);
    if (bmpB) g_preview_bitmaps[1] = env->NewGlobalRef(bmpB);
    LOGI("preview bitmaps set (double buffer)");
}

// UI 线程调用: 在互斥锁内把当前 front 帧拷到快照位图, 与写线程永不交叉
JNIEXPORT jboolean JNICALL
Java_com_rk3588_gesturelib_RknnBridge_nativeCopyPreview(JNIEnv* env, jclass, jobject dst) {
    if (!dst) return JNI_FALSE;
    std::lock_guard<std::mutex> lk(g_preview_mutex);
    jobject src = g_preview_bitmaps[g_preview_front.load(std::memory_order_acquire)];
    if (!src) return JNI_FALSE;
    AndroidBitmapInfo si, di;
    if (AndroidBitmap_getInfo(env, src, &si) < 0) return JNI_FALSE;
    if (AndroidBitmap_getInfo(env, dst, &di) < 0) return JNI_FALSE;
    if (si.width != di.width || si.height != di.height
        || si.format != ANDROID_BITMAP_FORMAT_RGBA_8888
        || di.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    void* sp = nullptr;
    void* dp = nullptr;
    if (AndroidBitmap_lockPixels(env, src, &sp) < 0 || !sp) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, dst, &dp) < 0 || !dp) {
        AndroidBitmap_unlockPixels(env, src);
        return JNI_FALSE;
    }
    auto* s = (const uint8_t*)sp;
    auto* d = (uint8_t*)dp;
    for (uint32_t yy = 0; yy < si.height; ++yy) {
        memcpy(d + (size_t)yy * di.stride, s + (size_t)yy * si.stride,
               (size_t)si.width * 4);
    }
    AndroidBitmap_unlockPixels(env, src);
    AndroidBitmap_unlockPixels(env, dst);
    return JNI_TRUE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_rk3588_gesturelib_RknnBridge_nativeAllocOut(JNIEnv* env, jclass) {
    return env->NewFloatArray(OUT_SIZE);
}

JNIEXPORT void JNICALL
Java_com_rk3588_gesturelib_RknnBridge_nativeSetFlipH(JNIEnv*, jclass, jboolean flip) {
    g_flip_h = flip;
}

JNIEXPORT jfloat JNICALL
Java_com_rk3588_gesturelib_RknnBridge_nativeFps(JNIEnv*, jclass) {
    return g_fps_ema;
}

} // extern "C"
