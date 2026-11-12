package com.rk3588.gesturelib

import android.util.Log

/**
 * 虚拟鼠标: 通过 /dev/uinput 注入系统级鼠标事件 (需 root)。
 * 系统会出现真实光标, 左右键/滚轮为原生语义, 所有 App 通用。
 */
object VirtualMouse {
    private const val TAG = "VirtualMouse"

    @Volatile
    var available = false
        private set

    init {
        System.loadLibrary("gesture_npu")  // 与引擎同库, 幂等
    }

    /** root 放开 /dev/uinput 权限并创建虚拟设备 */
    fun setup(): Boolean {
        if (available) return true
        RootShell.exec("chmod 666 /dev/uinput")
        available = nativeSetup()
        Log.i(TAG, "setup available=$available")
        return available
    }

    fun shutdown() {
        nativeShutdown()
        available = false
    }

    /** 相对移动光标 */
    fun rel(dx: Int, dy: Int) = nativeRel(dx, dy)

    /** 按键: left ? 左键 : 右键 */
    fun button(left: Boolean, down: Boolean) = nativeButton(left, down)

    /** 滚轮: dy>0 向上 */
    fun wheel(dy: Int) = nativeWheel(dy)

    private external fun nativeSetup(): Boolean
    private external fun nativeShutdown()
    private external fun nativeRel(dx: Int, dy: Int)
    private external fun nativeButton(left: Boolean, down: Boolean)
    private external fun nativeWheel(dy: Int)
}
