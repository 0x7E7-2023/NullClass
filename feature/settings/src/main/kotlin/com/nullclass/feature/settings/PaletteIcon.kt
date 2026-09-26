package com.nullclass.feature.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Material「Palette」（调色板）图标，「我的 → 个性化设置」入口用。
 *
 * 项目只依赖 material-icons-core，那套里没有它；为一个图标引入整套 extended 图标库不划算，
 * 这里照 Material Icons（Apache 2.0）的原路径数据画一个。颜色由 Icon 的 tint 决定，填充色无所谓。
 */
internal val PaletteIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Palette",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(
            "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9c0.83,0 1.5,-0.67 1.5,-1.5 0,-0.39 -0.15,-0.74 " +
                "-0.39,-1.01 -0.23,-0.26 -0.38,-0.61 -0.38,-0.99 0,-0.83 0.67,-1.5 1.5,-1.5L16,16" +
                "c2.76,0 5,-2.24 5,-5 0,-4.42 -4.03,-8 -9,-8z" +
                "M6.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,9 6.5,9 8,9.67 8,10.5 7.33,12 6.5,12z" +
                "M9.5,8C8.67,8 8,7.33 8,6.5S8.67,5 9.5,5s1.5,0.67 1.5,1.5S10.33,8 9.5,8z" +
                "M14.5,8c-0.83,0 -1.5,-0.67 -1.5,-1.5S13.67,5 14.5,5s1.5,0.67 1.5,1.5S15.33,8 14.5,8z" +
                "M17.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S16.67,9 17.5,9s1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}
