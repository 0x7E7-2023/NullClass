package com.nullclass.core.data.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import com.nullclass.core.model.AppLanguage

/**
 * 应用语言的落地点。
 *
 * 分两条路径，按系统版本二选一，互不叠加：
 *
 * - **Android 13 及以上**：系统自带「应用语言」设置页，语言状态由 [LocaleManager] 保管。
 *   此时应用只负责写入 [LocaleManager]，资源切换与界面重建都交给系统。应用**不得**再自行包装
 *   Context —— 否则用户从系统设置页改的语言会被应用的旧值顶回去。
 * - **Android 12 及以下**：系统没有该机制，由应用自行在 `Activity.attachBaseContext` 中
 *   包装 Context（[wrap]）并重建界面；Application 的资源另由 [applyToApplication] 跟上，
 *   否则通知、小组件、ViewModel 经 Application Context 取到的仍是启动时的语言。
 *
 * 语言必须在 `attachBaseContext` 阶段就确定，而该方法早于依赖注入执行且不能挂起，
 * 读不了 DataStore，因此另用 SharedPreferences 存一份可同步读取的镜像。
 * 镜像由 `UserPreferencesRepository.appLanguage` 写入，以 DataStore 为准。
 */
object AppLocale {

    private const val PREFS_NAME = "app_locale"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_SYSTEM_MIGRATED = "system_migrated"

    /** 系统是否自行管理应用语言（Android 13+）。 */
    val isSystemManaged: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * 当前生效的语言。
     *
     * Android 13+ 以系统的 [LocaleManager] 为准 —— 用户可能是在系统设置页里改的，
     * 那条路径不会经过应用。低版本读本地镜像。
     */
    fun current(context: Context): AppLanguage {
        if (isSystemManaged) {
            val tag = context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.takeIf { !it.isEmpty }
                ?.get(0)
                ?.toLanguageTag()
            return fromTag(tag)
        }
        return AppLanguage.fromName(prefs(context).getString(KEY_LANGUAGE, null))
    }

    /** 更新可同步读取的镜像。由偏好仓库在 DataStore 变更时调用。 */
    fun cache(context: Context, language: AppLanguage) {
        prefs(context).edit().putString(KEY_LANGUAGE, language.name).apply()
    }

    /**
     * 把语言应用到系统（仅 Android 13+）。
     *
     * 系统收到后会自行重建 Activity，调用方无需也不应再手动重建。
     */
    fun applyToSystem(context: Context, language: AppLanguage) {
        if (!isSystemManaged) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        manager.applicationLocales = language.tag
            ?.let { LocaleList.forLanguageTags(it) }
            ?: LocaleList.getEmptyLocaleList()
    }

    /**
     * 按当前语言包装 [base]，返回资源已切换到目标语言的 Context。
     *
     * Android 13+ 与「跟随系统」都原样返回：前者由系统负责，后者本就该交给系统匹配资源目录。
     * 在 `Application` / `Activity` 的 `attachBaseContext` 中调用 —— 此时 [base] 是系统给的原始
     * Context，所以「跟随系统」原样返回就是系统语言。运行期切换见 [applyToApplication]。
     */
    fun wrap(base: Context): Context = wrap(base, current(base))

    fun wrap(base: Context, language: AppLanguage): Context {
        if (isSystemManaged) return base
        val tag = language.tag ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(config)
    }

    /**
     * 把语言套到 Application 自己的资源上（仅 Android 12 及以下）。
     *
     * Application 的 Context 只在进程启动时由 [wrap] 包装一次；应用内切换语言后不重启进程，
     * 不跟上的话通知、小组件、ViewModel 经 Application Context 取到的仍是旧语言。
     * 系统配置变化（旋转、改系统语言）会把资源按启动时的配置重算一遍，所以
     * `Application.onConfigurationChanged` 里也要再调一次。
     */
    fun applyToApplication(application: Context, language: AppLanguage) {
        if (isSystemManaged) return
        val resources = application.resources
        val config = Configuration(resources.configuration)
        config.setLocales(
            language.tag?.let { LocaleList.forLanguageTags(it) }
                ?: Resources.getSystem().configuration.locales,
        )
        // 已弃用但仍是 12 及以下唯一能原地改 Application 资源的办法；13+ 不走这里
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    /**
     * Android 13+ 首次运行时返回 true，之后恒为 false。
     *
     * 设备从 12 升到 13 后，语言存在本地镜像里、[LocaleManager] 却是空的；
     * 调用方借这一次机会把旧选择交给系统，之后就以 [LocaleManager] 为准。
     */
    fun takeSystemMigration(context: Context): Boolean {
        if (!isSystemManaged) return false
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_SYSTEM_MIGRATED, false)) return false
        prefs.edit().putBoolean(KEY_SYSTEM_MIGRATED, true).apply()
        return true
    }

    private fun fromTag(tag: String?): AppLanguage {
        if (tag.isNullOrBlank()) return AppLanguage.SYSTEM
        // 系统回填的标记可能带地区与文字（zh-Hans-CN），按前缀匹配
        return AppLanguage.entries.firstOrNull { entry ->
            val entryTag = entry.tag
            entryTag != null && tag.startsWith(entryTag, ignoreCase = true)
        } ?: AppLanguage.SYSTEM
    }

    // 不能经 applicationContext：Application.attachBaseContext 时它还是 null（Application 对象
    // 要等 attachBaseContext 返回后才挂到 LoadedApk 上），12 及以下会在启动时崩溃。
    // SharedPreferences 按包名存放，任何 Context 取到的都是同一份。
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
