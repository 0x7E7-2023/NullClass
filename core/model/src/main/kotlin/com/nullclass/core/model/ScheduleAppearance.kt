package com.nullclass.core.model

import kotlin.math.roundToInt

/** 课程卡片内文字的排法。 */
enum class BlockTextAlign {
    /** 水平、垂直都居中（默认，也是加这项设置之前的样子）。 */
    CENTER,

    /** 顶格：从卡片左上角开始排。 */
    TOP_START;

    companion object {
        fun fromName(name: String?): BlockTextAlign = entries.firstOrNull { it.name == name } ?: CENTER
    }
}

/** 课程卡片的边框样式。 */
enum class BlockBorderStyle {
    NONE,

    /** 一个物理像素的实线（默认，也是加这项设置之前的样子）。 */
    SOLID,
    DASHED;

    companion object {
        fun fromName(name: String?): BlockBorderStyle = entries.firstOrNull { it.name == name } ?: SOLID
    }
}

/**
 * 周课表的外观（「我的 → 个性化设置」）。
 *
 * **默认值就是加这项设置之前的样子**：没动过设置的用户升级后，课表一个像素都不变。
 * 只存本机，与其他显示开关一样不进同步。网格线开关不在这里 —— 它早于本页存在，
 * 课表页的「显示设置」弹窗里也能切，两处读写的是同一个偏好。
 *
 * 可空的尺寸字段 null = 自动：自动值取决于屏幕与其他开关（见 `:feature:schedule` 的 GridStyle），
 * 这里存不下来，只记「用户是否亲手定过」。
 *
 * 行高按模式分两个字段：节次模式的「单节行高」与时间轴模式的「每小时高度」量纲不同，
 * 共用一个字段的话，节次模式调到 90dp 再开时间轴，就成了每小时 90dp、整天 2160dp。
 */
data class ScheduleAppearance(
    /** 24 小时时间轴：纵轴是一天 0:00–24:00，课程卡片按实际上课时间定位。 */
    val timelineMode: Boolean = false,
    /** 隐藏节次的起止时间（侧边栏只留节次号；卡片角标时间也不显示）。 */
    val hidePeriodTimes: Boolean = false,
    /** 表头只显示星期，不显示日期。 */
    val hideHeaderDates: Boolean = false,
    /** 课表页面文字颜色（ARGB）；null = 跟随主题。 */
    val pageTextColor: Int? = null,
    /** 节次模式的单节行高（dp）；null = 自动（按屏高撑满，不低于 56dp）。 */
    val periodCellHeightDp: Int? = null,
    /** 时间轴模式每小时的高度（dp）；null = 自动（[DEFAULT_TIMELINE_HOUR_HEIGHT_DP]）。 */
    val timelineHourHeightDp: Int? = null,
    /** 侧边栏（节次 / 整点那一列）宽度（dp）；null = 自动。 */
    val sidebarWidthDp: Int? = null,
    /** 表头高度（dp）；null = 自动（按内容包裹）。 */
    val headerHeightDp: Int? = null,
    /** 课程卡片文字颜色（ARGB）；null = 跟随主题。 */
    val blockTextColor: Int? = null,
    val blockTextAlign: BlockTextAlign = BlockTextAlign.CENTER,
    val blockBorderStyle: BlockBorderStyle = BlockBorderStyle.SOLID,
    /** 课程卡片内文字的缩放（百分比）。 */
    val blockTextScalePercent: Int = DEFAULT_TEXT_SCALE_PERCENT,
    val blockCornerRadiusDp: Int = DEFAULT_CORNER_RADIUS_DP,
    /** 卡片四周留出的空（dp），相邻两张卡片之间的缝是它的两倍。 */
    val blockSpacingDp: Float = DEFAULT_SPACING_DP,
    /** 卡片底色的不透明度（百分比）；文字与边框不受影响。 */
    val blockOpacityPercent: Int = DEFAULT_OPACITY_PERCENT,
) {

    /**
     * 越界收拢到合法范围、按步长取整、颜色补满不透明度。
     *
     * 读偏好时统一过一遍：手改过的、旧版本写的、将来范围变窄后留下的值，
     * 都不该让界面画出零高的格子、负宽的侧边栏或看不见的字。
     */
    fun sanitized(): ScheduleAppearance = copy(
        pageTextColor = pageTextColor?.let(::opaque),
        periodCellHeightDp = periodCellHeightDp?.coerceIn(PERIOD_CELL_HEIGHT_RANGE),
        timelineHourHeightDp = timelineHourHeightDp?.coerceIn(TIMELINE_HOUR_HEIGHT_RANGE),
        sidebarWidthDp = sidebarWidthDp?.coerceIn(SIDEBAR_WIDTH_RANGE),
        headerHeightDp = headerHeightDp?.coerceIn(HEADER_HEIGHT_RANGE),
        blockTextColor = blockTextColor?.let(::opaque),
        blockTextScalePercent = snap(blockTextScalePercent, TEXT_SCALE_RANGE, TEXT_SCALE_STEP),
        blockCornerRadiusDp = blockCornerRadiusDp.coerceIn(CORNER_RADIUS_RANGE),
        blockSpacingDp = snapSpacing(blockSpacingDp),
        blockOpacityPercent = snap(blockOpacityPercent, OPACITY_RANGE, OPACITY_STEP),
    )

    companion object {
        /** 下限 40dp：侧边栏「起始时间 / 节次号 / 结束时间」三行叠起来要这么高。 */
        val PERIOD_CELL_HEIGHT_RANGE = 40..160
        val TIMELINE_HOUR_HEIGHT_RANGE = 30..180

        /** 下限 20dp：两位数的节次号刚好放得下。 */
        val SIDEBAR_WIDTH_RANGE = 20..80

        /** 下限 24dp：一行星期刚好放得下。 */
        val HEADER_HEIGHT_RANGE = 24..96
        val TEXT_SCALE_RANGE = 70..150
        const val TEXT_SCALE_STEP = 5
        val CORNER_RADIUS_RANGE = 0..24
        const val SPACING_MAX_DP = 8f
        const val SPACING_STEP_DP = 0.5f
        val OPACITY_RANGE = 0..100
        const val OPACITY_STEP = 5

        /** 时间轴自动行高：每小时 60dp，一分钟约合 1dp，45 分钟的课 45dp 高。 */
        const val DEFAULT_TIMELINE_HOUR_HEIGHT_DP = 60
        const val DEFAULT_TEXT_SCALE_PERCENT = 100
        const val DEFAULT_CORNER_RADIUS_DP = 8
        const val DEFAULT_SPACING_DP = 1.5f
        const val DEFAULT_OPACITY_PERCENT = 100

        private fun opaque(argb: Int): Int = argb or (0xFF shl 24)

        private fun snap(value: Int, range: IntRange, step: Int): Int {
            val clamped = value.coerceIn(range)
            val steps = ((clamped - range.first).toFloat() / step).roundToInt()
            return (range.first + steps * step).coerceIn(range)
        }

        private fun snapSpacing(value: Float): Float {
            if (value.isNaN()) return DEFAULT_SPACING_DP
            val clamped = value.coerceIn(0f, SPACING_MAX_DP)
            return (clamped / SPACING_STEP_DP).roundToInt() * SPACING_STEP_DP
        }
    }
}
