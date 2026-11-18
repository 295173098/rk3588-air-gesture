package com.rk3588.gesturelib

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局配置（SharedPreferences 持久化）
 */
object GestureConfig {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("gesture_cfg", Context.MODE_PRIVATE)
    }

    private fun key(name: String) = name

    fun get(name: String, def: Boolean) = sp.getBoolean(key(name), def)
    fun set(name: String, v: Boolean) = sp.edit().putBoolean(key(name), v).apply()
    fun get(name: String, def: Int) = sp.getInt(key(name), def)
    fun set(name: String, v: Int) = sp.edit().putInt(key(name), v).apply()
    fun get(name: String, def: Float) = sp.getFloat(key(name), def)
    fun set(name: String, v: Float) = sp.edit().putFloat(key(name), v).apply()
    fun get(name: String, def: String) = sp.getString(key(name), def) ?: def
    fun set(name: String, v: String) = sp.edit().putString(key(name), v).apply()

    // ---- 开关 ----
    var useFrontCamera: Boolean
        get() = get("use_front_cam", false)
        set(v) = set("use_front_cam", v)

    var showHud: Boolean
        get() = get("show_hud", true)
        set(v) = set("show_hud", v)

    /** 水平翻转推理帧（与预览同步）。默认关: 相机原始画面即为正向 */
    var flipH: Boolean
        get() = get("flip_h_v2", false)
        set(v) = set("flip_h_v2", v)

    var rootFallback: Boolean
        get() = get("root_fallback", true)
        set(v) = set("root_fallback", v)

    var bootStart: Boolean
        get() = get("boot_start", false)
        set(v) = set("boot_start", v)

    // ---- 手势开关 ----
    /** ✋ 张掌挥动 = 4 方向滑动 */
    var enableSwipe: Boolean
        get() = get("en_swipe", true); set(v) = set("en_swipe", v)

    /** ✌️ 剪刀手 = 返回 */
    var enableVictory: Boolean
        get() = get("en_victory", true); set(v) = set("en_victory", v)

    /** ✊ 拳头 = 确定 */
    var enableFist: Boolean
        get() = get("en_fist", true); set(v) = set("en_fist", v)

    /** 👋 挥动手臂 (默认只回调不动作) */
    var enableWave: Boolean
        get() = get("en_wave", true); set(v) = set("en_wave", v)

    // ---- 灵敏度/阈值 ----
    /** 滑动触发位移阈值 (归一化画面比例) 0.10~0.35, 越小越灵敏 */
    var swipeThreshold: Float
        get() = get("swipe_threshold", 0.16f)
        set(v) = set("swipe_threshold", v)

    /** 手势确认所需连续帧数 1~8 */
    var confirmFrames: Int
        get() = get("confirm_frames", 3)
        set(v) = set("confirm_frames", v)

    /** 普通动作冷却 ms */
    var cooldownMs: Int
        get() = get("cooldown_ms", 900)
        set(v) = set("cooldown_ms", v)

    /** 全局动作间隔: 任一动作触发后, 间隔内不再识别下一个动作 */
    var actionGapMs: Int
        get() = get("action_gap_ms", 3000)
        set(v) = set("action_gap_ms", v)

    // ---- 手势动作启用 (按手势 id, 持久化) ----

    fun setEnabled(gesture: Int, enabled: Boolean) {
        val key = when (gesture) {
            GestureId.PALM -> "en_swipe"
            GestureId.FIST -> "en_fist"
            GestureId.VICTORY -> "en_victory"
            GestureId.WAVE -> "en_wave"
            else -> return
        }
        set(key, enabled)
    }

    fun isEnabled(gesture: Int): Boolean = when (gesture) {
        GestureId.PALM -> enableSwipe
        GestureId.FIST -> enableFist
        GestureId.VICTORY -> enableVictory
        GestureId.WAVE -> enableWave
        else -> true
    }
}
