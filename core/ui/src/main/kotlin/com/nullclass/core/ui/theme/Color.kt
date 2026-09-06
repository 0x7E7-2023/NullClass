package com.nullclass.core.ui.theme

import androidx.compose.ui.graphics.Color

// 无动态取色设备（Android 11 及以下）的回退色板。
// 主色与启动图标配色（#4F46E5 indigo）保持一致。

val Indigo10 = Color(0xFFE0E0FF)
val Indigo20 = Color(0xFFBEC2FF)
val Indigo30 = Color(0xFF949EFF)
val Indigo40 = Color(0xFF6B78F5)
val Indigo80 = Color(0xFF4F46E5)
val Indigo90 = Color(0xFF3F38B8)

val Gray10 = Color(0xFF1A1B21)
val Gray20 = Color(0xFF2F3038)
val Gray90 = Color(0xFFE3E1E9)
val Gray95 = Color(0xFFF2EFF7)

val LightPrimary = Indigo80
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Indigo10
val LightOnPrimaryContainer = Indigo90
val LightSecondary = Color(0xFF5B5B72)
val LightBackground = Color(0xFFFCF8FF)
val LightSurface = Color(0xFFFCF8FF)
val LightOnBackground = Gray10
val LightOnSurface = Gray10
val LightSurfaceVariant = Gray95
val LightOnSurfaceVariant = Color(0xFF47464F)

val DarkPrimary = Indigo20
val DarkOnPrimary = Indigo90
val DarkPrimaryContainer = Indigo30
val DarkOnPrimaryContainer = Indigo10
val DarkSecondary = Color(0xFFC4C5DD)
val DarkBackground = Gray10
val DarkSurface = Gray10
val DarkOnBackground = Gray90
val DarkOnSurface = Gray90
val DarkSurfaceVariant = Gray20
val DarkOnSurfaceVariant = Color(0xFFC8C5D0)
