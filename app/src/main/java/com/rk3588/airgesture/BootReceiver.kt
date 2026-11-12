package com.rk3588.airgesture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rk3588.gesturelib.GestureConfig
import com.rk3588.gesturelib.GestureEngine

/** 开机自启 (GestureEngine.bootStart 开启时生效) */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        GestureEngine.init(context)
        if (GestureConfig.bootStart) {
            Log.i("BootReceiver", "boot start gesture recognition")
            GestureEngine.startRecognition()
        }
    }
}
