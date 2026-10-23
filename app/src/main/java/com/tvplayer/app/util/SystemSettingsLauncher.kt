package com.tvplayer.app.util

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.util.concurrent.TimeUnit

object SystemSettingsLauncher {

    private const val TAG = "SystemSettingsLauncher"

    data class LaunchResult(
        val success: Boolean,
        val method: String,
        val message: String
    )

    fun openDeveloperOptions(context: Context): LaunchResult {
        val intents = listOf(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) to "系统开发者选项 Intent",
            Intent("android.settings.DEVELOPMENT_SETTINGS") to "开发者选项兼容 Intent",
            explicitIntent("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsActivity") to "Android 原生开发者页面",
            explicitIntent("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity") to "Android 原生开发者面板",
            explicitIntent("com.android.settings", "com.android.settings.DevelopmentSettings") to "旧版 Android 开发者页面",
            explicitIntent("com.android.tv.settings", "com.android.tv.settings.system.developer.DeveloperSettingsActivity") to "Android TV 开发者页面",
            explicitIntent("com.android.tv.settings", "com.android.tv.settings.device.display.developer.DeveloperOptionsActivity") to "Android TV 开发者选项"
        )

        intents.forEach { (intent, method) ->
            if (tryStart(context, intent)) {
                return LaunchResult(true, method, "已尝试打开开发者选项")
            }
        }

        if (tryRootStartDeveloperOptions()) {
            return LaunchResult(true, "已有 Root 的 su/am 命令", "已通过系统命令尝试打开开发者选项")
        }

        return LaunchResult(false, "无可用入口", "系统未暴露开发者选项页面，可能需要 USB 调试或系统权限")
    }

    fun openSystemSettings(context: Context): LaunchResult {
        val intents = listOf(
            Intent(Settings.ACTION_SETTINGS) to "系统设置首页",
            Intent("android.settings.DEVICE_SETTINGS") to "设备设置页面"
        )
        intents.forEach { (intent, method) ->
            if (tryStart(context, intent)) {
                return LaunchResult(true, method, "已打开系统设置")
            }
        }
        return LaunchResult(false, "无可用入口", "系统设置页面无法启动")
    }

    fun isRootAvailable(): Boolean {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "command -v su"))
            val finished = waitForProcess(process, 800L)
            if (!finished) process.destroy()
            finished && process.exitValue() == 0
        }.getOrDefault(false)
    }

    private fun explicitIntent(packageName: String, className: String): Intent {
        return Intent(Settings.ACTION_SETTINGS).setComponent(ComponentName(packageName, className))
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        return runCatching {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) return false
            context.startActivity(intent)
            true
        }.onFailure {
            Log.d(TAG, "Unable to start settings intent=${intent.component ?: intent.action}", it)
        }.getOrDefault(false)
    }

    private fun tryRootStartDeveloperOptions(): Boolean {
        return runCatching {
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
            )
            process.outputStream.close()
            val finished = waitForProcess(process, 1_200L)
            if (!finished) {
                process.destroy()
                false
            } else {
                process.exitValue() == 0
            }
        }.onFailure {
            Log.d(TAG, "Root command is unavailable", it)
        }.getOrDefault(false)
    }

    private fun waitForProcess(process: Process, timeoutMs: Long): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { process.exitValue(); true }.getOrDefault(false)) {
                return true
            }
            try {
                Thread.sleep(40L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return runCatching { process.exitValue(); true }.getOrDefault(false)
    }
}
