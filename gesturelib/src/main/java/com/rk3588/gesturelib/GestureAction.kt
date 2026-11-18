package com.rk3588.gesturelib

/** 内置动作集合 */
enum class GestureAction {
    // ✋ 张掌挥动的 4 个方向 (无障碍滑动语义: 朝挥动方向滑动屏幕)
    SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT,
    // ✌️ 剪刀手 = 返回
    BACK,
    // ✊ 拳头 = 确定 (焦点 OK 键)
    CONFIRM,
    // 挥动手臂默认不执行动作 (只回调, 集成方可 setAction 重映射)
    NONE
}
