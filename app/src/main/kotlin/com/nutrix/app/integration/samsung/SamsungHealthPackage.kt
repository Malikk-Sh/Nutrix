package com.nutrix.app.integration.samsung

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object SamsungHealthPackage {
    const val PACKAGE = "com.sec.android.app.shealth"

    fun isInstalled(context: Context): Boolean = versionName(context) != null

    fun versionName(context: Context): String? = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(PACKAGE, 0)
        }
        info.versionName ?: ""
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
