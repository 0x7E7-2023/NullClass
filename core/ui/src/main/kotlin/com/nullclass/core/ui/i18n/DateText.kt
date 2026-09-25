package com.nullclass.core.ui.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.nullclass.core.model.ExamRelativeDay
import com.nullclass.core.ui.R
import java.time.LocalDate

/**
 * 各界面共用的日期文案。
 *
 * 与 [ScheduleText] 同理：`:core:model` 只判断「是今天还是三天后」（[ExamRelativeDay]），
 * 具体怎么说交给资源 —— 年月日的写法各语言差别很大，不能在业务代码里拼。
 */
object DateText {

    /** 「2026年12月20日」。 */
    fun date(context: Context, epochDay: Long): String {
        val date = LocalDate.ofEpochDay(epochDay)
        return context.getString(R.string.fmt_date, date.year, date.monthValue, date.dayOfMonth)
    }

    /** 「12月20日」。 */
    fun monthDay(context: Context, epochDay: Long): String {
        val date = LocalDate.ofEpochDay(epochDay)
        return context.getString(R.string.fmt_month_day, date.monthValue, date.dayOfMonth)
    }

    /** 考试相对今天的位置：「今天」「明天」「3 天后」「已结束」。 */
    fun examRelative(context: Context, examEpochDay: Long, todayEpochDay: Long): String =
        when (val relative = ExamRelativeDay.of(examEpochDay, todayEpochDay)) {
            ExamRelativeDay.Today -> context.getString(R.string.fmt_exam_relative_today)
            ExamRelativeDay.Tomorrow -> context.getString(R.string.fmt_exam_relative_tomorrow)
            ExamRelativeDay.Yesterday -> context.getString(R.string.fmt_exam_relative_yesterday)
            ExamRelativeDay.Past -> context.getString(R.string.fmt_exam_relative_past)
            is ExamRelativeDay.InDays -> relative.days.toInt().let { days ->
                context.resources.getQuantityString(R.plurals.fmt_exam_relative_in_days, days, days)
            }
        }
}

@Composable
fun dateLabel(epochDay: Long): String = DateText.date(LocalContext.current, epochDay)

@Composable
fun monthDayLabel(epochDay: Long): String = DateText.monthDay(LocalContext.current, epochDay)

@Composable
fun examRelativeLabel(examEpochDay: Long, todayEpochDay: Long): String =
    DateText.examRelative(LocalContext.current, examEpochDay, todayEpochDay)
