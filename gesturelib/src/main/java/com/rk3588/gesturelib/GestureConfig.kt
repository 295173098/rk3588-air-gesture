package com.rk3588.gesturelib

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局配置（SharedPreferences 持久化）
 */
object GestureConfig {
    private lateinit var sp: SharedPreferences

    const val ACTION_NONE = "none"
    const val ACTION_VOL_UP = "vol_up"
    const val ACTION_VOL_DOWN = "vol_down"
    const val ACTION_SWIPE_UP = "swipe_up"
    const val ACTION_SWIPE_DOWN = "swipe_down"
    const val ACTION_BACK = "back"
    const val ACTION_HOME = "home"
    const val ACTION_CLICK = "click"
    const val ACTION_LOCK = "lock"

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

    var volumeRepeat: Boolean
        get() = get("volume_repeat", true)
        set(v) = set("volume_repeat", v)

    // ---- 手势开关 ----
    var enableThumbUp: Boolean
        get() = get("en_thumb_up", true); set(v) = set("en_thumb_up", v)
    var enableThumbDown: Boolean
        get() = get("en_thumb_down", true); set(v) = set("en_thumb_down", v)
    var enableSwipe: Boolean
        get() = get("en_swipe", true); set(v) = set("en_swipe", v)
    var enableVictory: Boolean
        get() = get("en_victory", true); set(v) = set("en_victory", v)
    var enablePoint: Boolean
        get() = get("en_point", false); set(v) = set("en_point", v)
    var enableFist: Boolean
        get() = get("en_fist", true); set(v) = set("en_fist", v)

    /** ☝️ 指向 = 方向键 (D-pad) */
    var enableDpad: Boolean
        get() = get("en_dpad", true); set(v) = set("en_dpad", v)

    /** ✋张开→✊抓取 = 截图 */
    var enableGrab: Boolean
        get() = get("en_grab", true); set(v) = set("en_grab", v)

    /** 鼠标模式: ☝️=光标 🤏=左键 ✌️=右键+滚轮 (开启后替代方向键/✌️返回) */
    var mouseMode: Boolean
        get() = get("mouse_mode", true); set(v) = set("mouse_mode", v)

    /** 方向键连发间隔 ms */
    var dpadRepeatMs: Int
        get() = get("dpad_repeat_ms", 400)
        set(v) = set("dpad_repeat_ms", v)

    // ---- 灵敏度/阈值 ----
    /** 滑动触发位移阈值 (帧高比例) 0.10~0.35, 越小越灵敏 */
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

    /** 音量连击间隔 ms */
    var volumeRepeatMs: Int
        get() = get("volume_repeat_ms", 450)
        set(v) = set("volume_repeat_ms", v)

    // ---- 手势动作启用 (按手势 id, 持久化) ----

    fun setEnabled(gesture: Int, enabled: Boolean) {
        val key = when (gesture) {
            GestureId.PALM -> "en_swipe"
            GestureId.FIST -> "en_fist"
            GestureId.VICTORY -> "en_victory"
            GestureId.POINT -> "en_dpad"
            GestureId.THUMB_UP -> "en_thumb_up"
            GestureId.THUMB_DOWN -> "en_thumb_down"
            else -> return
        }
        set(key, enabled)
    }

    fun isEnabled(gesture: Int): Boolean = when (gesture) {
        GestureId.PALM -> enableSwipe
        GestureId.FIST -> enableFist
        GestureId.VICTORY -> enableVictory
        GestureId.POINT -> enableDpad
        GestureId.THUMB_UP -> enableThumbUp
        GestureId.THUMB_DOWN -> enableThumbDown
        else -> true
    }
}
