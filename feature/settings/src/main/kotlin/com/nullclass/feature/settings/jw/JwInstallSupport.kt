package com.nullclass.feature.settings.jw

import android.content.Context
import android.os.Build

/** 读取**应用**（而非 library 模块）的 versionCode：适配器用它做最低版本兼容判断。 */
object JwInstallSupport {

    fun appVersionCode(context: Context): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    } catch (e: Exception) {
        1
    }
}
