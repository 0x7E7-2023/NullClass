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

    /** 周次表达式允许的字符：数字、列表分隔符、范围连接符、「第」、单双周标记。 */
    private val WEEK_EXPR_CHAR = Regex("[0-9,，、\\-—~至第单双]")

    private val WEEK_ITEM = Regex("^(\\d{1,2})(?:\\s*[-—~至]\\s*(\\d{1,2}))?$")

    private val PERIOD_IN_LINE = Regex("(\\d{1,2})\\s*[-—~至]\\s*(\\d{1,2})\\s*节|(\\d{1,2})\\s*节")

    private val COURSE_CODE_PREFIX = Regex("^\\d{4,10}\\s+")
    private val CLASS_SUFFIX = Regex("\\[[0-9A-Za-z]{1,12}]$")
    private val PERIOD_RANGE = Regex("(\\d{1,2})\\s*[-—~至]\\s*(\\d{1,2})")
    private val PERIOD_SINGLE = Regex("(\\d{1,2})")
    private val PERIOD_LABEL = Regex("^(\\d{1,2})\\s*(?:[-—~至]\\s*(\\d{1,2}))?$")

    /** 周次解析结果。 */
    data class WeekSpec(val startWeek: Int, val endWeek: Int, val weekType: String)

    /** "1-16周(单)" / "第2-16周" / "3周" → WeekSpec（取第一个周次段）；解析不出返回 null。 */
    fun parseWeeks(raw: String?, totalWeeks: Int = 30): WeekSpec? =
        parseWeekSpecs(raw, totalWeeks).firstOrNull()

    /**
     * 一段文本里的**全部**周次段。
     *
     * 教务格子常把几个不连续的周次写在同一行（金智：「2-5周,9-17周,星期1,1-2节,汇智楼105」），
     * 只取第一段会静默丢掉后面的周次——课表看着正常，学生却少上了一半的课。
     * 覆盖的写法：「1-16周」「1-16周(单)」「2-16双周」「1,3,5-8周」「第3周」。
     */
    fun parseWeekSpecs(raw: String?, totalWeeks: Int = 30): List<WeekSpec> {
        val text = raw?.trim().orEmpty()
        // 必须出现「周」：否则教室号会被当成周次范围——"教1-101" 里的数字段正好是合法周次的形状
        if (text.isEmpty() || !text.contains('周')) return emptyList()
        val specs = mutableListOf<WeekSpec>()
        var cursor = 0
        while (true) {
            val mark = text.indexOf('周', cursor)
            if (mark < 0) break
            // 从「周」往前吃回一整个周次表达式：数字、"1,3," 这样的列表、"5-8" 这样的范围、单双周标记
            var start = mark
            while (start > cursor && WEEK_EXPR_CHAR.matches(text[start - 1].toString())) start--
            val type = weekTypeAround(text, mark)
            text.substring(start, mark).split(',', '，', '、').forEach { item ->
                val bare = item.trim().removePrefix("第").replace("单", "").replace("双", "").trim()
                val match = WEEK_ITEM.matchEntire(bare) ?: return@forEach
                val first = match.groupValues[1].toIntOrNull() ?: return@forEach
                val last = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: first
                if (first < 1 || last < first || last > totalWeeks) return@forEach
                specs += WeekSpec(first, last, type)
            }
            cursor = mark + 1
        }
        // 表达式不在「周」前面时的老写法兜底（如「周次 1-16」）
        return specs.ifEmpty { legacyWeekSpec(text, totalWeeks)?.let { listOf(it) }.orEmpty() }
    }

    /** 周次段的单双周标记：「2-16双周」与「2-6周(双)」都算。 */
    private fun weekTypeAround(text: String, mark: Int): String {
        val window = text.substring(maxOf(0, mark - 1), minOf(text.length, mark + 4))
        return when {
            window.contains('单') -> "ODD"
            window.contains('双') -> "EVEN"
            else -> "ALL"
        }
    }

    private fun legacyWeekSpec(text: String, totalWeeks: Int): WeekSpec? {
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

    /**
     * 从整行里抠显式节次：「2-5周,星期1,1-2节,汇智楼105」→ (1, 2)；没有就返回 null。
     *
     * 为什么要它：格子跨多节时（连堂），行锚点只知道这门课从哪一行**开始**，
     * 拿它当节次会把 1-2 节的课记成 1 节。
     */
    fun parsePeriodsInLine(raw: String?): Pair<Int, Int>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = PERIOD_IN_LINE.find(text) ?: return null
        val first = match.groupValues[1].ifEmpty { match.groupValues[3] }.toIntOrNull() ?: return null
        val last = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: first
        if (first < 1 || last < first || last > 30) return null
        return first to last
    }

    /**
     * 从整行里抠教室：「2-5周,星期1,1-2节,汇智楼105」→「汇智楼105」。
     *
     * 教务格子常把「周次/星期/节次/教室」挤在同一行，逐行找整行是教室的写法会整条漏掉。
     */
    fun locationInLine(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (looksLikeLocation(text)) return text
        return text.split(',', '，', '、', ';', '；', ' ', '\n')
            .map { it.trim() }
            .firstOrNull { looksLikeLocation(it) }
    }

    /**
     * 「2010020 C语言程序设计[05]」→「C语言程序设计」：课号前缀与教学班后缀不是课名的一部分。
     *
     * 不去掉的话，同一门课的 A/B 教学班会（"[05]" 与 "[05S01]"）会变成两门课，
     * 而且课表上显示的是"2010020 C语言程序设计[05]"这种没人这么叫的名字。
     */
    fun cleanCourseName(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return text
        var cleaned = text.replace(COURSE_CODE_PREFIX, "").trim()
        while (true) {
            val next = cleaned.replace(CLASS_SUFFIX, "").trim()
            if (next == cleaned) break
            cleaned = next
        }
        return cleaned.ifEmpty { text }
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

    private val TIME_LABEL = Regex("^\\d{1,2}:\\d{2}(\\s*[-—~至]\\s*\\d{1,2}:\\d{2})?$")

    /**
     * 上课时间标注：「08:00」「08:00-08:45」。
     *
     * 只用来在**节次号一个都认不出时**定行；它本身不是节次（有冒号，跟「1-2」不会混），
     * 由行序推断出来的节次号要在校对页上讲清楚。
     */
    fun looksLikeTimeLabel(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return false
        if (TIME_LABEL.matches(text)) return true
        if (!text.contains('\n')) return false
        return text.lineSequence().map { it.trim() }.any { TIME_LABEL.matches(it) }
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

    /** 整行里的星期标注：「2-5周,星期1,1-2节,汇智楼105」→ 1。 */
    private val DAY_IN_LINE = Regex("(?:星期|礼拜|周)\\s*([一二三四五六日天1-7])")

    /**
     * 从整行文本里抠星期。
     *
     * 为什么要它：格子里的星期比「它落在哪一列」可靠——一格横跨好几个星期列（合班课常见）、
     * 表头排得偏一点，列都会指错日子，而教务页面写在格子里的「星期N」是它自己说的。
     * 认不出返回 null，由上层退回按列判。
     */
    fun parseDayInLine(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        parseDayOfWeek(text)?.let { return it }
        val token = DAY_IN_LINE.find(text)?.groupValues?.get(1) ?: return null
        return when (token) {
            "一" -> 1
            "二" -> 2
            "三" -> 3
            "四" -> 4
            "五" -> 5
            "六" -> 6
            "日", "天" -> 7
            else -> token.toIntOrNull()?.takeIf { it in 1..7 }
        }
    }

    /**
     * 课表里常见的教室写法：教学楼、实验楼、A101、1-101 等。
     *
     * 字母＋数字那条要卡在词首：教学班号 "[05S01]" 里的 "S01" 否则会命中，
     * 「6120011 职业生涯与发展规划[03S01]」这种课名行就会被当成教室。
     */
    private val LOCATION_HINT = Regex(
        "(楼|馆|室|教室|机房|中心|操场|体育馆|报告厅|校区" +
            "|(?<![0-9A-Za-z])[A-Za-z]\\s?\\d{2,4}" +
            "|(?<!\\d)\\d{1,2}\\s?[-—]\\s?\\d{3,4})",
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
