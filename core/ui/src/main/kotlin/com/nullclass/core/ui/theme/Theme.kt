package com.nullclass.core.ui.theme

import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.nullclass.core.model.ThemeMode
import android.graphics.Color as AndroidColor

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
)

/**
 * 空课主题。[ThemeMode.DYNAMIC] 在 Android 12+ 用 Material You 动态取色、深浅跟随系统，
 * 低版本回退到内置 indigo 色板（与启动图标同源）；[ThemeMode.LIGHT] / [ThemeMode.DARK] 固定用内置色板。
 */
@Composable
fun NullClassTheme(
    themeMode: ThemeMode = ThemeMode.DYNAMIC,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.DYNAMIC -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = when {
        themeMode == ThemeMode.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    // 手动深浅色与系统不一致时，系统栏图标和 window 背景（values-night 按系统选的）
    // 都得跟着应用主题改，否则状态栏图标看不见、预测返回时透出反色底
    val activity = LocalActivity.current as? ComponentActivity
    val windowBackground = colorScheme.background.toArgb()
    DisposableEffect(activity, darkTheme, windowBackground) {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { darkTheme },
            // 与 enableEdgeToEdge 默认的三键导航栏遮罩同值（库里是 internal 常量）
            navigationBarStyle = SystemBarStyle.auto(
                AndroidColor.argb(0xe6, 0xFF, 0xFF, 0xFF),
                AndroidColor.argb(0x80, 0x1b, 0x1b, 0x1b),
            ) { darkTheme },
        )
        activity?.window?.setBackgroundDrawable(ColorDrawable(windowBackground))
        onDispose {}
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
