package com.nullclass.feature.schedule

import com.nullclass.core.model.Term

/**
 * 周次编号的身份：决定「第 N 周是哪几天」的只有学期身份与开学日。
 *
 * 它一变，用户之前翻到的那一周就换了意义——**那个选择必须作废**。只认这两项：
 * 总周数只裁剪周次范围、不改变任何一周的日期，改它不该把用户从翻到的周次上拽走；
 * 课表名、颜色、课程内容同理，都不进这个键。
 */
internal data class WeekNumbering(val termId: String, val firstDayEpochDay: Long) {
    companion object {
        fun of(term: Term) = WeekNumbering(term.id, term.firstDayEpochDay)
    }
}

/**
 * 用户翻到的周次。**连同当时的[周次编号]一起记**——单存一个数字的话，
 * 换学期后它就成了一个没有意义的值：新学期才第 1 周，页面上却还停在上学期翻到的第 5 周，
 * 那一周不上的课块（下半学期开课、单双周）整片消失，看着像「课没渲染出来」。
 *
 * 教务导入新学期正是走这条路（[com.nullclass.sync.ImportAligner] 把开学日最新的那个学期
 * 标成当前），而今日页/小组件各自按今天现算周次，不受影响——所以症状只在周视图，
 * 且重启应用（ViewModel 重建、翻到的周次归零）就消失。
 */
internal data class SelectedWeek(val numbering: WeekNumbering, val week: Int)

/**
 * 用户翻到的周次在**当前**周次编号下还算不算数：编号对不上就作废（返回 null = 跟随今天）。
 *
 * 写成「读的时候比对」而不是「编号变化时清空」：后者靠一个额外的收集协程去改状态，
 * 与 uiState 的组装是两条协程，谁先跑到不确定——切编号的那一瞬间回写进来的选择
 * （翻页器从保存态恢复的旧页码，见 ScheduleScreen 的 key）会被当成新编号下的选择收下。
 * 比对是纯函数，没有时序可言。
 */
internal fun SelectedWeek?.weekIn(current: WeekNumbering): Int? =
    this?.week?.takeIf { numbering == current }

/**
 * 周视图当前显示的周次：用户翻过就用它，否则跟随今天，都没有（今天不在学期内且没翻过）
 * 落到第 1 周。最后**夹进学期范围**：翻到的周次可能是上一个学期留下的，而学期总周数变短
 * （编辑学期、导入短学期）时它就越界了——越界会让整个网格空着，顶栏还写着「第 15 周」。
 */
internal fun resolveVisibleWeek(selected: Int?, todayWeek: Int?, totalWeeks: Int): Int =
    (selected ?: todayWeek ?: 1).coerceIn(1, totalWeeks)
