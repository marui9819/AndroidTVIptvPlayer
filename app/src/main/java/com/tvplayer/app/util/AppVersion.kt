package com.tvplayer.app.util

import android.content.Context

object AppVersion {
    fun label(context: Context): String {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        return "v$version"
    }
}
