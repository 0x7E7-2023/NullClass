package com.nullclass.widget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullclass.core.model.WidgetFontSize

/** 把未缩放的 sp/dp 基准乘上小组件字号档。 */
internal fun WidgetFontSize.sp(unscaled: Int): TextUnit = (unscaled * scale).sp

internal fun WidgetFontSize.dp(unscaled: Int): Dp = (unscaled * scale).dp
