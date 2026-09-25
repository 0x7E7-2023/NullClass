package com.nullclass.feature.settings.transfer

import android.content.Context
import com.nullclass.core.model.ScheduleBlock
import com.nullclass.core.ui.i18n.ScheduleText
import com.nullclass.feature.settings.R

/**
 * `.ics` 导出里用到的全部文案，由调用方按当前语言取好后传入。
 *
 * 导出逻辑本身不碰 Android 资源：一来 [IcsCalendar] 是纯粹的格式拼装，二来它的单元测试
 * 因此不需要 Android 运行时。各字段是带 `%1$s` 占位符的模板，[blockSummary] 则把
 * 「周二 · 3-4节 · 第1-16周 · 单周」这一行整段交给调用方拼。
 */
data class IcsText(
    val calendarName: String,
    val term: String,
    val teacher: String,
    val note: String,
    val course: String,
    val exam: String,
    val time: String,
    val location: String,
    val seat: String,
    val blockSummary: (ScheduleBlock) -> String,
) {
    companion object {
        fun from(context: Context): IcsText = IcsText(
            calendarName = context.getString(R.string.settings_ics_calendar_name),
            term = context.getString(R.string.settings_ics_term),
            teacher = context.getString(R.string.settings_ics_teacher),
            note = context.getString(R.string.settings_ics_note),
            course = context.getString(R.string.settings_ics_course),
            exam = context.getString(R.string.settings_ics_exam),
            time = context.getString(R.string.settings_ics_time),
            location = context.getString(R.string.settings_ics_location),
            seat = context.getString(R.string.settings_ics_seat),
            blockSummary = { block -> ScheduleText.blockSummary(context, block) },
        )
    }
}
