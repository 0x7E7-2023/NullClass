package com.nullclass.feature.schedule

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * 课表背景图片压暗的程度：图片上再叠一层这个不透明度的黑色。
 *
 * 用户选的照片往往明亮、花哨，直接垫在课表下面会和课程卡片、表头文字抢视线；
 * 压暗一点再用是用户确认方案时提的要求。在显示时叠而不是导入时烧进文件：
 * 原图保持不动，以后要调深浅不必让用户重新选图。
 */
private const val WallpaperDimAlpha = 0.2f

/** 课表背景图片：铺满、居中裁切，并压暗 [WallpaperDimAlpha]。课表页与个性化设置的预览共用。 */
@Composable
internal fun ScheduleWallpaper(bitmap: Bitmap, modifier: Modifier = Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                drawRect(Color.Black.copy(alpha = WallpaperDimAlpha))
            },
    )
}
