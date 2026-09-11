package com.nullclass.importer.jw.ocr

/**
 * 课表单元格文本解析（纯函数，OCR 与文本适配器共用）。
 *
 * 教务课表里的周次写法五花八门：`1-16周`、`1-16周(单)`、`1,3,5-8周`、`第1-16周`、
 * `2-16双周`、`单周`……这里只做**保守**解析：拿不准就返回 null，让上层要求用户校对，
 * 而不是猜一个看起来合理的范围（错行错列的静默错误比报错更糟）。
 */
object JwCourseTextParser {

    private val WEEK_RANGE = Regex("(\\d{1,2})\\s*[-—~至]\\s*(\\d{1,2})\\s*周?")
    private val WEEK_SINGLE = Regex("(\\d{1,2})\\s*周")
    private val PERIOD_RANGE = Regex("(\\d{1,2})\\s*[-—~至]\\s*(\\d{1,2})")
    private val PERIOD_SINGLE = Regex("(\\d{1,2})")
    private val PERIOD_LABEL = Regex("^(\\d{1,2})\\s*(?:[-—~至]\\s*(\\d{1,2}))?$")

    /** 周次解析结果。 */
    data class WeekSpec(val startWeek: Int, val endWeek: Int, val weekType: String)

    /** "1-16周(单)" / "第2-16周" / "3周" → WeekSpec；解析不出返回 null。 */
    fun parseWeeks(raw: String?, totalWeeks: Int = 30): WeekSpec? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        // 必须出现「周」：否则教室号会被当成周次范围。真实格子常写成
        // 「课名 / 教师 / 教室 / 周次」，而 "教1-101"、"教3-201" 里的数字段
        // 正好是合法的周次形状 → 会把 1-16 周静默改成 1-10 周。
        if (!text.contains('周')) return null
        val type = when {
            text.contains('单') -> "ODD"
            text.contains('双') -> "EVEN"
            else -> "ALL"
        }
        WEEK_RANGE.find(text)?.let { match ->
            val start = match.groupValues[1].toIntOrNull() ?: return null
            val end = match.groupValues[2].toIntOrNull() ?: return null
            if (start < 1 || end < start || end > totalWeeks) return null
            return WeekSpec(start, end, type)
        }
        WEEK_SINGLE.find(text)?.let { match ->
            val week = match.groupValues[1].toIntOrNull() ?: return null
            if (week < 1 || week > totalWeeks) return null
            return WeekSpec(week, week, type)
        }
        return null
    }

    /** "1-2" / "3" → (start, end)；解析不出返回 null。 */
    fun parsePeriods(raw: String?): Pair<Int, Int>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        PERIOD_RANGE.find(text)?.let { match ->
            val start = match.groupValues[1].toIntOrNull() ?: return null
            val end = match.groupValues[2].toIntOrNull() ?: return null
            if (start < 1 || end < start || end > 30) return null
            return start to end
        }
        PERIOD_SINGLE.find(text)?.let { match ->
            val value = match.groupValues[1].toIntOrNull() ?: return null
            if (value < 1 || value > 30) return null
            return value to value
        }
        return null
    }

    /**
     * 节次**列**里的纯节次标注："3" / "3-4" / "第3-4节"。
     * 与 [parsePeriods] 的区别：这里要求整段文本就是节次标注（`1-16周` 不算），
     * 因为节次列是行锚点的唯一来源，认错一行会让整行课程静默错位。
     *
     * 单元格里若混了别的行（常见写法是「节次」与「上课时间」叠在一格，"3\n10:00-10:45"，
     * 多行文本用换行拼接），逐行找第一行纯节次标注——不这么做的话整张表都找不到行锚点。
     * "08:00" 这类时间不会命中（正则不接受冒号）。
     */
    fun parsePeriodLabel(raw: String?): IntRange? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        parsePeriodLabelLine(text)?.let { return it }
        if (!text.contains('\n')) return null
        return text.lineSequence()
            .map { it.trim() }
            .firstNotNullOfOrNull { line -> parsePeriodLabelLine(line) }
    }

    private fun parsePeriodLabelLine(raw: String): IntRange? {
        val text = raw.trim().removePrefix("第").removeSuffix("节").trim()
        if (text.isEmpty()) return null
        val match = PERIOD_LABEL.matchEntire(text) ?: return null
        val start = match.groupValues[1].toIntOrNull() ?: return null
        val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: start
        if (start < 1 || end < start || end > 30) return null
        return start..end
    }

    private val DAY_LABELS = listOf(
        "周一" to 1, "星期一" to 1, "礼拜一" to 1, "monday" to 1, "mon" to 1,
        "周二" to 2, "星期二" to 2, "礼拜二" to 2, "tuesday" to 2, "tue" to 2,
        "周三" to 3, "星期三" to 3, "礼拜三" to 3, "wednesday" to 3, "wed" to 3,
        "周四" to 4, "星期四" to 4, "礼拜四" to 4, "thursday" to 4, "thu" to 4,
        "周五" to 5, "星期五" to 5, "礼拜五" to 5, "friday" to 5, "fri" to 5,
        "周六" to 6, "星期六" to 6, "礼拜六" to 6, "saturday" to 6, "sat" to 6,
        "周日" to 7, "周天" to 7, "星期日" to 7, "星期天" to 7, "sunday" to 7, "sun" to 7,
    )

    /** "周一" / "星期一" / "MON" → 1..7；认不出返回 null。 */
    fun parseDayOfWeek(raw: String?): Int? {
        val text = raw?.trim()?.lowercase().orEmpty()
        if (text.isEmpty()) return null
        DAY_LABELS.firstOrNull { text.contains(it.first) }?.let { return it.second }
        return null
    }

    /** 课表里常见的教室写法：教学楼、实验楼、A101、1-101 等。 */
    private val LOCATION_HINT = Regex(
        "(楼|馆|室|教室|机房|中心|操场|体育馆|报告厅|校区|[A-Za-z]\\s?\\d{2,4}|\\d{1,2}\\s?[-—]\\s?\\d{3,4})",
    )

    fun looksLikeLocation(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        // 含「周」的是周次文本，别把 "1-16周" 当成教室 "1-101"
        if (text.isEmpty() || text.contains('周')) return false
        return LOCATION_HINT.containsMatchIn(text)
    }

    private val TEACHER_HINT = Regex("^[\\u4e00-\\u9fa5]{2,4}([,，、/][\\u4e00-\\u9fa5]{2,4})*$")

    fun looksLikeTeacher(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        return text.isNotEmpty() && TEACHER_HINT.matches(text)
    }
}
