package com.rk3588.airgesture

import android.app.Application
import com.rk3588.gesturelib.GestureEngine

/** Demo 应用: 初始化手势识别库 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        GestureEngine.init(this)
    }
}
