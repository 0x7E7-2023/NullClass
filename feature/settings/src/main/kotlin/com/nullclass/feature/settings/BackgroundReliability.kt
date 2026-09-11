package com.nullclass.feature.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * 后台可靠性引导（不申请任何权限，只跳页面）：
 * - 电池优化列表页：系统 API，用户在列表里找到空课改为「不优化」
 * - 厂商自启动页：按 ROM 尝试已知的 Activity，解析不到就藏掉入口
 *
 * 注意：getActivityInfo 能解析 ≠ 一定能打开（部分 ROM 未导出或按场景拦截），
 * [startAutostartSettings] 打开失败时回落到应用详情页，绝不抛异常出去。
 */
internal object BackgroundReliability {

    private const val TAG = "BackgroundReliability"

    /** 已知厂商「自启动管理」页（dontkillmyapp 社区口径），按保有量排序。 */
    private val autostartCandidates = listOf(
        // 小米 MIUI / HyperOS
        "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
        // 华为 EMUI / HarmonyOS
        "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        // 荣耀 MagicOS
        "com.hihonor.systemmanager/com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        // OPPO / 一加 ColorOS
        "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
        // 旧版 OPPO
        "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity",
        // vivo / iQOO OriginOS
        "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        // 魅族 Flyme
        "com.meizu.safe/com.meizu.safe.permission.SmartBGActivity",
    )

    /** 本机是否有已知的厂商自启动页。没有（原生/三星等）返回 null，UI 藏掉入口。 */
    fun resolvedAutostartActivity(context: Context): ComponentName? {
        val pm = context.packageManager
        return autostartCandidates
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .firstOrNull { cn ->
                try {
                    pm.getActivityInfo(cn, 0)
                    true
                } catch (e: Exception) {
                    false
                }
            }
    }

    /** 打开电池优化列表页（系统 API，无需权限）。 */
    fun startBatteryOptimizationSettings(context: Context) {
        safeStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /**
     * 打开厂商自启动页；打不开（未导出/被拦）回落应用详情页。
     * 返回是否成功 startActivity（哪怕回落也算成功——用户总归到了某个能调的页面）。
     */
    fun startAutostartSettings(context: Context): Boolean {
        val cn = resolvedAutostartActivity(context)
        if (cn != null && safeStart(context, Intent().setComponent(cn))) return true
        // 回落：应用详情页（里面有「电池」/「启动」等入口，各 ROM 都打得开）
        return safeStart(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    }

    private fun safeStart(context: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "startActivity failed: ${intent.component ?: intent.action}", e)
        false
    }
}
