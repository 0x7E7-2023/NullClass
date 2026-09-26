package com.nullclass.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nullclass.app.navigation.AppNavHost
import com.nullclass.core.data.locale.AppLocale
import com.nullclass.core.data.prefs.UserPreferencesRepository
import com.nullclass.core.model.AppLanguage
import com.nullclass.core.ui.layout.ProvideWindowSize
import com.nullclass.core.ui.theme.NullClassTheme
import com.nullclass.feature.settings.transfer.PendingImport
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var userPreferences: UserPreferencesRepository

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝不打扰，设置页保留重试入口 */ }

    /**
     * 语言必须早于资源加载确定，因此在这里包装 Context。
     * Android 13+ 由系统负责，[AppLocale.wrap] 原样返回。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        observeLanguageChange()
        // 同步读上次的主题，首帧就是用户选的深浅色
        val initialThemeMode = userPreferences.lastThemeMode
        setContent {
            val themeMode by userPreferences.themeMode.collectAsState(initial = initialThemeMode)
            NullClassTheme(themeMode) {
                // 垫一层不透明背景：预测返回手势的 pop 过渡中，上一页从透明淡入、
                // 当前页缩小，两层半透明叠加时会透出 window 背景（浅色主题下是白色，
                // 深色模式就是刺眼的白边）。垫上 colorScheme.background 后透出的
                // 恰好是各页面 Scaffold 同色背景，过渡浑然一体。
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // 窗口尺寸档位在这里量一次向下提供：Activity 只接管了语言相关的 configChanges，
                    // 旋转/分屏 resize 仍会整体重建，自然跟着重测
                    ProvideWindowSize {
                        AppNavHost()
                    }
                }
            }
        }
        maybeRequestNotificationPermission()
        handleImportIntent(intent)
    }

    /**
     * 语言变更后原地换掉本 Activity 的资源，再把新配置发给界面，Compose 据此重组成新语言。
     *
     * 不用 recreate()：重建会整窗重画，切换时闪一下，还会丢掉滚动位置等界面状态。
     * 仅限 Android 12 及以下：13 起语言由系统的 LocaleManager 管理，系统会把配置变更发过来
     * （清单里声明了 configChanges，不会重建）。
     */
    private fun observeLanguageChange() {
        if (AppLocale.isSystemManaged) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                userPreferences.appLanguage.collect { applyLanguageInPlace(it) }
            }
        }
    }

    private fun applyLanguageInPlace(language: AppLanguage) {
        AppLocale.applyInPlace(this, language)
        // 配置没变时 Compose 按值比较，不会多重组一次
        window.decorView.dispatchConfigurationChanged(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 12 及以下改系统语言时，系统会把资源按 attachBaseContext 时的语言重算，把当前选择重新套上
        if (!AppLocale.isSystemManaged) applyLanguageInPlace(AppLocale.current(this))
    }

    /** 首启动一次性请求通知权限（API 33+）；拒绝不打扰。 */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        lifecycleScope.launch {
            if (!userPreferences.notificationPermissionAsked.first()) {
                userPreferences.setNotificationPermissionAsked()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * 系统「用其他应用打开」.nullclass → 交给 TransferScreen 预览。
     * 只处理一次（B5）：重建（旋转/进程恢复）时 getIntent() 复用同一 Intent 实例，
     * 消费后清掉 data 防止重复弹预览；onNewIntent 每次是新 Intent 实例，不受影响。
     */
    private fun handleImportIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW && (uri.scheme == "content" || uri.scheme == "file")) {
            PendingImport.uri.value = uri
        }
        intent.setData(null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent)
    }
}

