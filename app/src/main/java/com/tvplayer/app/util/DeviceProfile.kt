package com.tvplayer.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Compatibility profile for legacy Android TV firmware and MStar devices. */
object DeviceProfile {

    fun isTv(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun isLegacyTv(context: Context): Boolean {
        if (!isTv(context)) return false
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val model = Build.MODEL.orEmpty().lowercase()
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1 ||
            manufacturer.contains("mstar") ||
            model.contains("mstar") ||
            model.contains("6a938")
    }
}
