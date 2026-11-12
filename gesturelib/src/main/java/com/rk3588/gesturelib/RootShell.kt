package com.rk3588.gesturelib

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Root 命令封装。设备 su 不支持 `su -c`，需用 `su 0 sh -c '...'` 形式。
 */
object RootShell {
    private const val TAG = "RootShell"

    /** 是否具备 root */
    fun hasRoot(): Boolean = run("id", 3000)?.contains("uid=0") == true

    /** 执行一条 root 命令，返回输出文本（失败返回 null） */
    fun run(cmd: String, timeoutMs: Long = 8000): String? = try {
        val proc = ProcessBuilder("su", "0", "sh", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
        proc.waitFor()
        output
    } catch (e: Exception) {
        Log.w(TAG, "root cmd failed: $cmd : ${e.message}")
        null
    }

    /** 执行命令但只关心成败 */
    fun exec(cmd: String): Boolean = run(cmd)?.let { true } ?: false

    /** 给本 App 静默授权（相机/通知/悬浮窗/无障碍） */
    fun autoGrant(pkg: String): String {
        val log = StringBuilder()
        // 运行时权限
        for (perm in listOf(
            "android.permission.CAMERA",
            "android.permission.POST_NOTIFICATIONS",
        )) {
            val r = run("pm grant $pkg $perm")
            log.append("pm grant $perm: ${r?.trim() ?: "fail"}\n")
        }
        // 悬浮窗
        val overlay = run("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        log.append("appops overlay: ${overlay?.trim() ?: "fail"}\n")
        // 无障碍服务：读取现有列表并追加，避免覆盖其他服务
        val current = run("settings get secure enabled_accessibility_services")?.trim() ?: ""
        val svc = "$pkg/${GestureAccessibility::class.java.name}"
        val merged = if (current.isNullOrEmpty() || current == "null") svc
        else if (current.contains(pkg)) current
        else "$current:$svc"
        run("settings put secure enabled_accessibility_services \"$merged\"")
        run("settings put secure accessibility_enabled 1")
        log.append("accessibility: done\n")
        return log.toString()
    }
}
