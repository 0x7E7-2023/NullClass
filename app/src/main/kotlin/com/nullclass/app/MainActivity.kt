package com.nullclass.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nullclass.app.navigation.AppNavHost
import com.nullclass.core.data.prefs.UserPreferencesRepository
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NullClassTheme {
                AppNavHost()
            }
        }
        maybeRequestNotificationPermission()
        handleImportIntent(intent)
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

    /** 系统「用其他应用打开」.nullclass → 交给 TransferScreen 预览。 */
    private fun handleImportIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW && (uri.scheme == "content" || uri.scheme == "file")) {
            PendingImport.uri.value = uri
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent)
    }
}

