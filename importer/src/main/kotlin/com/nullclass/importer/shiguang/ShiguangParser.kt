package com.nullclass.importer.shiguang

import com.nullclass.core.model.CourseColorKeywords
import com.nullclass.core.model.DefaultPeriodTimes
import com.nullclass.core.model.MAX_TOTAL_WEEKS
import com.nullclass.core.model.MINUTES_PER_DAY
import com.nullclass.importer.BlockDto
import com.nullclass.importer.CourseDto
import com.nullclass.importer.ImportNotice
import com.nullclass.importer.ImportNoticeEntry
import com.nullclass.importer.ImportProvenance
import com.nullclass.importer.ScheduleFileError
import com.nullclass.importer.ScheduleFileException
import com.nullclass.importer.PeriodTimeDto
import com.nullclass.importer.ScheduleDocument
import com.nullclass.importer.TermDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * 拾光课程表（shiguangschedule_yyyyMMdd_HHmmss.json）导出文件解析器。
 *
 * 上游导出的是**扁平课程行 + 作息表 + 部分配置**三段：
 * ```json
 * { "courses": [{ "name","teacher","position","day","startSection","endSection","color","weeks":[..] }],
 *   "timeSlots": [{ "number","startTime","endTime" }],
 *   "config": { "semesterStartDate","semesterTotalWeeks","defaultClassDuration","defaultBreakDuration" } }
 * ```
 * 两处模型差异按《教务适配器移植手册》(docs/jw-adapter-porting.md) §4 的既有口径处理，
 * 不另立一套：
 * - **§4.1** 显式周次数组 → 极大段 + 单双周（一行可能切出多个 block）；
 * - **§4.3** `semesterStartDate` 要回退到每周起始日才是我们的 `firstDay`，直接拿来用会整学期偏；
 * - **§4.4** `isCustomTime`：还带节次就按节次放（自定义时间是附加信息），只有自定义时间
 *   就找最接近的一节（差 ≤1 小时），找不到才跳过——**不静默丢课**；
 * - **§4.5** 上游字段名自己会写错，`semesterTotalWeeks` / `totalWeeks` 两个都认。
 *
 * 颜色是上游调色板的**下标**（适配器里 `randomColor()` 取 1..12），不是色值：
 * 两边色板顺序不同，照搬下标只能保住「哪几门课同色」，保不住色相，所以
 * 下标非法时退回按课名关键词上色（与教务导入同一套）。
 */
object ShiguangParser {

    /** 上游 `config.defaultClassDuration` 的缺省值（分钟）。 */
    private const val DEFAULT_CLASS_DURATION = 45

    /** 上游 `config.defaultBreakDuration` 的缺省值（分钟）。 */
    private const val DEFAULT_BREAK_DURATION = 10

    /** 只有自定义时间没有节次时，容许「最接近的一节」差多少分钟（手册 §4.4）。 */
    private const val NEAREST_PERIOD_TOLERANCE = 60

    /**
     * 一天最多认多少节。上游没有这个上限，而作息表要补齐到最大节次
     * ——一条 `startSection: 99999` 就能让我们造出几万条节次记录，这里先卡住。
     */
    private const val MAX_PERIOD_INDEX = 60

    /** 作息表第 1 节就缺失时的起点（08:00）。 */
    private const val FIRST_PERIOD_START = 8 * 60

    /**
     * 导出文件没有课表名，用它当学期名的前缀。
     *
     * 这是**写进数据库的数据**（学期名会同步到其他设备、也是合并时的对齐键），
     * 不是界面文案，因此保留中文字面量、不随界面语言变化。
     */
    const val DEFAULT_TERM_NAME = "拾光课表"

    /**
     * 缺省学期名带上开学日期。
     *
     * 学期名是 ImportAligner 的对齐键（本来源每次导入都重铸 UUID，只能按名认）：
     * 名字恒为「拾光课表」的话，春季那份和秋季那份会被认成同一个学期 ——
     * 对不上的旧课程被打墓碑，等于**静默吞掉上一个学期的课表**。
     * 带上开学日期后，同一学期重复导入仍然对齐（刷新），不同学期各归各的。
     */
    internal fun defaultTermName(startDate: LocalDate?): String =
        if (startDate == null) DEFAULT_TERM_NAME else "$DEFAULT_TERM_NAME $startDate"

    data class ShiguangResult(
        val term: TermDto,
        val courses: List<CourseDto>,
        val blocks: List<BlockDto>,
        val periodTimes: List<PeriodTimeDto>,
        /** 跳过了什么、替成了什么。只带标识与参数，文案由界面层按当前语言取。 */
        val warnings: List<ImportNoticeEntry>,
    )

    /** 解析中间态：一行导出课程（还没定学期总周数，所以先不切段）。 */
    private data class Row(
        val index: Int,
        val name: String,
        val teacher: String?,
        val position: String?,
        val day: Int,
        val startSection: Int?,
        val endSection: Int?,
        val color: Int?,
        val weeks: List<Int>,
        val isCustomTime: Boolean,
        val customStartTime: Int?,
        val customEndTime: Int?,
    )

    /** 周次极大段（手册 §4.1）。 */
    internal data class WeekRun(val startWeek: Int, val endWeek: Int, val weekType: String)

    /**
     * @param termName 学期名；导出文件里没有这个信息，缺省见 [defaultTermName]
     * @param today 推算开学日的基准（测试注入）
     * @param now 审计时间戳（测试注入；生产传 `System.currentTimeMillis()`）
     * @throws IllegalArgumentException 带用户可读 message
     */
    fun parse(
        raw: String,
        termName: String? = null,
        today: LocalDate = LocalDate.now(),
        now: Long = System.currentTimeMillis(),
    ): ShiguangResult {
        val warnings = mutableListOf<ImportNoticeEntry>()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        val root = try {
            json.parseToJsonElement(raw)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw ScheduleFileException(ScheduleFileError.SHIGUANG_BAD_JSON)
        }
        if (root !is JsonObject) {
            throw ScheduleFileException(ScheduleFileError.SHIGUANG_NOT_OBJECT)
        }

        val courseArray = (root["courses"] as? kotlinx.serialization.json.JsonArray)
            ?: throw ScheduleFileException(ScheduleFileError.SHIGUANG_NO_COURSES)
        if (courseArray.isEmpty()) throw ScheduleFileException(ScheduleFileError.SHIGUANG_EMPTY_COURSES)

        val config = root["config"] as? JsonObject

        // ---- 课程行（先收集，总周数要看完全部周次才能定）----
        val rows = mutableListOf<Row>()
        courseArray.forEachIndexed { i, element ->
            val at = i + 1
            val obj = element as? JsonObject
            if (obj == null) {
                warnings.add(ImportNoticeEntry(ImportNotice.SHIGUANG_ROW_NOT_OBJECT, listOf(at)))
                return@forEachIndexed
            }
            val name = obj.str("name")?.trim()
            if (name.isNullOrEmpty()) {
                warnings.add(ImportNoticeEntry(ImportNotice.SHIGUANG_ROW_NO_NAME, listOf(at)))
                return@forEachIndexed
            }
            val day = obj.int("day")
            if (day == null) {
                warnings.add(ImportNoticeEntry(ImportNotice.SHIGUANG_ROW_NO_DAY, listOf(name)))
                return@forEachIndexed
            }
            if (day !in 1..7) {
                warnings.add(ImportNoticeEntry(ImportNotice.SHIGUANG_ROW_BAD_DAY, listOf(name, day.toString())))
                return@forEachIndexed
            }
            val weeks = (obj["weeks"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.asIntOrNull() }
                ?.filter { it >= 1 }
                ?.distinct()
                ?.sorted()
                .orEmpty()
            rows.add(
                Row(
                    index = i,
                    name = name,
                    teacher = obj.str("teacher")?.trim()?.takeIf { it.isNotEmpty() },
                    position = obj.str("position")?.trim()?.takeIf { it.isNotEmpty() },
                    day = day,
                    startSection = obj.int("startSection"),
                    endSection = obj.int("endSection"),
                    color = obj.int("color"),
                    weeks = weeks,
                    isCustomTime = obj.bool("isCustomTime"),
                    customStartTime = parseHm(obj.str("customStartTime")),
                    customEndTime = parseHm(obj.str("customEndTime")),
                ),
            )
        }
        if (rows.isEmpty()) throw ScheduleFileException(ScheduleFileError.SHIGUANG_NO_ROWS)

        // ---- 学期 ----
        // §4.5：上游把 semesterTotalWeeks 写成 totalWeeks 的情况真实存在，两个都认
        val declaredWeeks = config?.int("semesterTotalWeeks") ?: config?.int("totalWeeks")
        val maxWeekInData = rows.flatMap { it.weeks }.maxOrNull() ?: 0
        // 声明的总周数装不下实际周次时以数据为准（装得下就不擅自扩），最后统一收进上限
        val rawTotalWeeks = maxOf(declaredWeeks ?: 0, maxWeekInData).takeIf { it > 0 } ?: 20
        val totalWeeks = rawTotalWeeks.coerceIn(1, MAX_TOTAL_WEEKS)
        if (totalWeeks != rawTotalWeeks) {
            warnings.add(
                ImportNoticeEntry(
                    ImportNotice.SHIGUANG_WEEKS_TRUNCATED,
                    listOf(rawTotalWeeks, totalWeeks),
                ),
            )
        } else if (declaredWeeks != null && declaredWeeks in 1..MAX_TOTAL_WEEKS && totalWeeks > declaredWeeks) {
            warnings.add(
                ImportNoticeEntry(
                    ImportNotice.SHIGUANG_WEEKS_EXCEED_DECLARED,
                    listOf(totalWeeks, declaredWeeks),
                ),
            )
        }

        val firstDayOfWeek = config?.int("firstDayOfWeek")?.takeIf { it in 1..7 } ?: 1
        val startDateText = config?.str("semesterStartDate")
        val startDate = parseDate(startDateText)
        if (startDate == null) {
            warnings.add(
                if (startDateText.isNullOrBlank()) {
                    ImportNoticeEntry(ImportNotice.SHIGUANG_NO_START_DATE)
                } else {
                    ImportNoticeEntry(ImportNotice.SHIGUANG_START_DATE_UNREADABLE, listOf(startDateText))
                },
            )
        }
        // §4.3：开学日要回退到「每周起始日」那一天，直接用 semesterStartDate 会整学期偏
        val firstDayEpochDay = alignToWeekStart(startDate ?: today, firstDayOfWeek)

        val termId = UUID.randomUUID().toString()
        val term = TermDto(
            id = termId,
            name = termName?.trim()?.takeIf { it.isNotEmpty() } ?: defaultTermName(startDate),
            firstDayEpochDay = firstDayEpochDay,
            totalWeeks = totalWeeks,
            isCurrent = false,
            createdAt = now,
            updatedAt = now,
        )

        // ---- 作息表 ----
        val classDuration = config?.int("defaultClassDuration")?.takeIf { it in 1..600 } ?: DEFAULT_CLASS_DURATION
        val breakDuration = config?.int("defaultBreakDuration")?.takeIf { it in 0..600 } ?: DEFAULT_BREAK_DURATION
        val slots = parseTimeSlots(root["timeSlots"], warnings)
        val periods = if (slots.isEmpty()) {
            warnings.add(ImportNoticeEntry(ImportNotice.SHIGUANG_NO_PERIOD_TABLE))
            DefaultPeriodTimes.create(termId).map {
                PeriodTimeDto(termId, it.periodIndex, it.startMinuteOfDay, it.endMinuteOfDay, it.session, now)
            }
        } else {
            slots.map { PeriodTimeDto(termId, it.first, it.second, it.third, sessionOf(it.second), now) }
        }

        // ---- 行 → 课块 ----
        val periodStarts = periods.associate { it.periodIndex to it.startMinuteOfDay }
        var customTimeDropped = 0
        data class Placed(val row: Row, val startPeriod: Int, val endPeriod: Int)

        val placed = rows.mapNotNull { row ->
            val start = row.startSection
            val end = row.endSection ?: row.startSection
            if (start != null && end != null && start >= 1 && end >= start) {
                if (end > MAX_PERIOD_INDEX) {
                    warnings.add(
                        ImportNoticeEntry(
                            ImportNotice.SHIGUANG_COURSE_BEYOND_LAST_PERIOD,
                            listOf(row.name, end, MAX_PERIOD_INDEX),
                        ),
                    )
                    return@mapNotNull null
                }
                if (row.isCustomTime) customTimeDropped++ // §4.4：节次优先，自定义时间只是附加信息
                return@mapNotNull Placed(row, start, end)
            }
            // §4.4：只有自定义时间 → 找最接近的一节
            val customStart = row.customStartTime
            if (customStart == null) {
                warnings.add(
                    ImportNoticeEntry(ImportNotice.SHIGUANG_COURSE_NO_TIME, listOf(row.name)),
                )
                return@mapNotNull null
            }
            val nearest = periodStarts.minByOrNull { kotlin.math.abs(it.value - customStart) }
            if (nearest == null || kotlin.math.abs(nearest.value - customStart) > NEAREST_PERIOD_TOLERANCE) {
                warnings.add(
                    ImportNoticeEntry(
                        ImportNotice.SHIGUANG_CUSTOM_TIME_NO_NEAREST,
                        listOf(row.name, formatHm(customStart)),
                    ),
                )
                return@mapNotNull null
            }
            warnings.add(
                ImportNoticeEntry(
                    ImportNotice.SHIGUANG_CUSTOM_TIME_NEAREST,
                    listOf(row.name, formatHm(customStart), nearest.key),
                ),
            )
            Placed(row, nearest.key, nearest.key)
        }
        if (customTimeDropped > 0) {
            warnings.add(
                ImportNoticeEntry(ImportNotice.SHIGUANG_CUSTOM_TIME_DROPPED, listOf(customTimeDropped)),
            )
        }
        if (placed.isEmpty()) throw ScheduleFileException(ScheduleFileError.SHIGUANG_NO_PLACEABLE)

        // 作息表短于实际节次：按默认课长/课间顺延补齐，否则课块会落在没有时间的节上
        val maxPeriod = placed.maxOf { it.endPeriod }
        val periodTimes = extendPeriods(periods, maxPeriod, classDuration, breakDuration, termId, now)
        if (periodTimes.size > periods.size) {
            warnings.add(
                ImportNoticeEntry(
                    ImportNotice.SHIGUANG_PERIODS_EXTENDED,
                    listOf(maxPeriod, periods.size, classDuration, breakDuration),
                ),
            )
        }

        // ---- 课程（按「课名 + 老师」归并，与 ImportAligner 的对齐口径一致）----
        val courseKeys = linkedMapOf<Pair<String, String>, MutableList<Placed>>()
        placed.forEach { courseKeys.getOrPut(it.row.name to it.row.teacher.orEmpty()) { mutableListOf() }.add(it) }

        var unmatchedColorCursor = 0
        val courses = mutableListOf<CourseDto>()
        val blocks = mutableListOf<BlockDto>()
        courseKeys.forEach { (key, items) ->
            val courseId = UUID.randomUUID().toString()
            val upstreamColor = items.firstNotNullOfOrNull { it.row.color?.takeIf { c -> c in 1..CourseColorKeywords.SIZE } }
            courses.add(
                CourseDto(
                    id = courseId,
                    termId = termId,
                    name = key.first,
                    teacher = key.second.takeIf { it.isNotEmpty() },
                    colorIndex = upstreamColor?.minus(1)
                        ?: (CourseColorKeywords.match(key.first) ?: (unmatchedColorCursor++ % CourseColorKeywords.SIZE)),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            items.forEach { item ->
                val declared = item.row.weeks
                val kept = declared.filter { it <= totalWeeks }
                val weeks = when {
                    kept.isNotEmpty() -> {
                        if (kept.size < declared.size) {
                            warnings.add(
                                ImportNoticeEntry(
                                    ImportNotice.SHIGUANG_WEEKS_OVERFLOW_DROPPED,
                                    listOf(item.row.name, totalWeeks),
                                ),
                            )
                        }
                        kept
                    }

                    declared.isEmpty() -> {
                        // 上游总会写 weeks，空的是「这条数据缺了信息」，按整学期导入比丢课强
                        warnings.add(
                            ImportNoticeEntry(
                                ImportNotice.SHIGUANG_WEEKS_MISSING,
                                listOf(item.row.name, totalWeeks),
                            ),
                        )
                        (1..totalWeeks).toList()
                    }

                    else -> {
                        // 周次全在学期之外（上限截断的尾巴）。**不能**铺成整学期：
                        // 只在第 35 周上一次的课会变成 1-30 周每周都有
                        warnings.add(
                            ImportNoticeEntry(
                                ImportNotice.SHIGUANG_WEEKS_ALL_OUT_OF_RANGE,
                                listOf(
                                    item.row.name,
                                    // 原样交出去，由界面层按当前语言的分隔符连起来
                                    declared,
                                    totalWeeks,
                                ),
                            ),
                        )
                        return@forEach
                    }
                }
                runsOf(weeks).forEach { run ->
                    blocks.add(
                        BlockDto(
                            id = UUID.randomUUID().toString(),
                            courseId = courseId,
                            termId = termId,
                            startWeek = run.startWeek,
                            endWeek = run.endWeek,
                            weekType = run.weekType,
                            dayOfWeek = item.row.day,
                            startPeriod = item.startPeriod,
                            endPeriod = item.endPeriod,
                            location = item.row.position,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                }
            }
        }

        // 周次全在学期之外的行被丢掉后，可能有课一条安排都不剩——别留下空壳课程
        val usedCourseIds = blocks.map { it.courseId }.toSet()
        if (usedCourseIds.isEmpty()) {
            throw ScheduleFileException(ScheduleFileError.SHIGUANG_WEEKS_OUT_OF_RANGE)
        }

        return ShiguangResult(
            term = term,
            courses = courses.filter { it.id in usedCourseIds },
            blocks = mergeAdjacent(blocks),
            periodTimes = periodTimes,
            warnings = warnings,
        )
    }

    /** 便捷方法：直接产出可进 ImportPreview 的 ScheduleDocument。 */
    fun parseToDocument(raw: String, termName: String? = null): ScheduleDocument {
        val result = parse(raw, termName)
        return ScheduleDocument(
            deviceId = ImportProvenance.SHIGUANG_IMPORT,
            generatedAt = System.currentTimeMillis(),
            terms = listOf(result.term),
            courses = result.courses,
            blocks = result.blocks,
            periodTimes = result.periodTimes,
        )
    }

    /**
     * 这份文本像不像拾光的导出文件。
     *
     * 给「用户直接点开 json 文件」那条路用：通用文件导入先认自己的 `.nullclass`，
     * 认不出来再问这里——所以判据要**足够严**（有 courses 数组，且有 timeSlots 或 config，
     * 且没有我们自己的 `formatVersion`），免得把别的课表格式也一口吞下去后报个莫名其妙的错。
     */
    fun looksLikeShiguang(raw: String): Boolean {
        val root = try {
            Json { ignoreUnknownKeys = true; isLenient = true }.parseToJsonElement(raw)
        } catch (e: kotlinx.serialization.SerializationException) {
            return false
        }
        if (root !is JsonObject) return false
        if (root.containsKey("formatVersion")) return false
        if (root["courses"] !is kotlinx.serialization.json.JsonArray) return false
        return root["timeSlots"] is kotlinx.serialization.json.JsonArray || root["config"] is JsonObject
    }

    // ---- 内部工具 ----

    /**
     * 周次集合 → 极大段（手册 §4.1）：步长 1 视作每周，步长 2 视作单/双周，落单的一周算 ALL。
     * 输入必须**已排序去重**。
     */
    internal fun runsOf(weeks: List<Int>): List<WeekRun> {
        val runs = mutableListOf<WeekRun>()
        var i = 0
        while (i < weeks.size) {
            val step = if (i + 1 < weeks.size && weeks[i + 1] - weeks[i] == 2) 2 else 1
            var j = i
            while (j + 1 < weeks.size && weeks[j + 1] - weeks[j] == step) j++
            val start = weeks[i]
            val end = weeks[j]
            val type = when {
                start == end || step == 1 -> "ALL"
                start % 2 == 1 -> "ODD"
                else -> "EVEN"
            }
            runs.add(WeekRun(start, end, type))
            i = j + 1
        }
        return runs
    }

    /**
     * 上游把连堂拆成一节一行（`school.js` 的示例就是 2/3/4 节三行），原样导入会在课表里
     * 叠成三块。同课同天同周次、节次首尾相接的合并成一块。
     */
    private fun mergeAdjacent(blocks: List<BlockDto>): List<BlockDto> {
        val sorted = blocks.sortedWith(
            compareBy({ it.courseId }, { it.dayOfWeek }, { it.startWeek }, { it.endWeek }, { it.weekType }, { it.startPeriod }),
        )
        val merged = mutableListOf<BlockDto>()
        sorted.forEach { block ->
            val last = merged.lastOrNull()
            val joinable = last != null &&
                last.courseId == block.courseId &&
                last.dayOfWeek == block.dayOfWeek &&
                last.startWeek == block.startWeek &&
                last.endWeek == block.endWeek &&
                last.weekType == block.weekType &&
                last.location == block.location
            when {
                joinable && last!!.endPeriod + 1 == block.startPeriod ->
                    merged[merged.lastIndex] = last.copy(endPeriod = block.endPeriod)
                // 完全重复（上游同一行导出两次）直接丢
                joinable && last!!.startPeriod == block.startPeriod && last.endPeriod == block.endPeriod -> Unit
                else -> merged.add(block)
            }
        }
        return merged
    }

    /** `timeSlots` → (节次, 开始分钟, 结束分钟)，按节次排序去重。 */
    private fun parseTimeSlots(
        element: kotlinx.serialization.json.JsonElement?,
        warnings: MutableList<ImportNoticeEntry>,
    ): List<Triple<Int, Int, Int>> {
        val array = element as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        val result = linkedMapOf<Int, Triple<Int, Int, Int>>()
        array.forEachIndexed { i, item ->
            val obj = item as? JsonObject
            val number = obj?.int("number")
            val start = parseHm(obj?.str("startTime"))
            val end = parseHm(obj?.str("endTime"))
            if (obj == null || number == null || number < 1 || start == null || end == null) {
                warnings.add(
                    ImportNoticeEntry(ImportNotice.SHIGUANG_PERIOD_ROW_INVALID, listOf(i + 1)),
                )
                return@forEachIndexed
            }
            // 作息表要补齐到最大节次，节次号本身就是放大系数：一条 number: 999999
            // 就能让我们造出上百万条节次记录（解析跑在主线程），这里和课程节次卡同一个上限
            if (number > MAX_PERIOD_INDEX) {
                warnings.add(
                    ImportNoticeEntry(
                        ImportNotice.SHIGUANG_PERIOD_OUT_OF_RANGE,
                        listOf(number, MAX_PERIOD_INDEX),
                    ),
                )
                return@forEachIndexed
            }
            if (end <= start) {
                warnings.add(
                    ImportNoticeEntry(ImportNotice.SHIGUANG_PERIOD_TIME_INVALID, listOf(number)),
                )
                return@forEachIndexed
            }
            result[number] = Triple(number, start, end)
        }
        return result.values.sortedBy { it.first }
    }

    /**
     * 作息表补齐到 [maxPeriod] 节。
     *
     * **按节次顺序逐个缺口填**，不是「从全表最晚的一节往后顺延」——作息表中间缺号时
     * （上游漏写，或某条格式非法被跳过），从全表最晚时间起算会把第 3、4 节排到第 5 节后面，
     * 节次序与时钟序倒挂，周视图与「下一节课」全按节次序走，就会报出 21 点上课这种时间。
     * 所以每个缺口只在**它前一节的结束**与**它后一节的开始**之间铺，铺不下就均分，
     * 保证时间随节次单调不减，且满足 PeriodTime 的硬约束（0 ≤ start < end ≤ 1440）。
     */
    private fun extendPeriods(
        periods: List<PeriodTimeDto>,
        maxPeriod: Int,
        classDuration: Int,
        breakDuration: Int,
        termId: String,
        now: Long,
    ): List<PeriodTimeDto> {
        val existing = periods.associateBy { it.periodIndex }
        if ((1..maxPeriod).all { it in existing }) return periods

        val result = mutableListOf<PeriodTimeDto>()
        var cursor = -1 // 上一节的结束时间；-1 = 前面还没有节次
        var index = 1
        while (index <= maxPeriod) {
            val present = existing[index]
            if (present != null) {
                result.add(present)
                cursor = present.endMinuteOfDay
                index++
                continue
            }
            // 连续缺口 [index, gapEnd]，以及缺口后的第一节（决定这段能铺到哪）
            var gapEnd = index
            while (gapEnd < maxPeriod && existing[gapEnd + 1] == null) gapEnd++
            val missing = gapEnd - index + 1
            val from = if (cursor >= 0) cursor else (FIRST_PERIOD_START - breakDuration).coerceAtLeast(0)
            val until = existing[gapEnd + 1]?.startMinuteOfDay ?: MINUTES_PER_DAY
            // 每节（含课间）能分到 step 分钟：塞得下就用正常课长，塞不下就均分，至少 2 分钟
            val span = (until - from).coerceAtLeast(missing * 2)
            val step = minOf(classDuration + breakDuration, span / missing).coerceAtLeast(2)
            repeat(missing) { k ->
                val slotStart = from + k * step
                val start = (slotStart + minOf(breakDuration, step - 1)).coerceIn(0, MINUTES_PER_DAY - 2)
                val end = minOf(start + classDuration, slotStart + step).coerceIn(start + 1, MINUTES_PER_DAY)
                result.add(PeriodTimeDto(termId, index + k, start, end, sessionOf(start), now))
                cursor = end
            }
            index = gapEnd + 1
        }
        return result.sortedBy { it.periodIndex }
    }

    /** 与 core:model Session 对齐：0 上午 / 1 下午 / 2 晚上。 */
    private fun sessionOf(startMinuteOfDay: Int): Int = when {
        startMinuteOfDay < 12 * 60 -> 0
        startMinuteOfDay < 18 * 60 -> 1
        else -> 2
    }

    /** "HH:mm" / "HH:mm:ss" → 分钟数；不合法返回 null。 */
    private fun parseHm(text: String?): Int? {
        val m = Regex("""^\s*(\d{1,2}):(\d{2})(?::\d{2})?\s*$""").matchEntire(text ?: return null) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun formatHm(minuteOfDay: Int): String =
        "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    private fun parseDate(text: String?): LocalDate? = try {
        text?.trim()?.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) }
    } catch (e: DateTimeParseException) {
        null
    }

    /** §4.3：开学日回退到「每周起始日」那一天（含当天）。[weekStart] 1=周一..7=周日。 */
    private fun alignToWeekStart(date: LocalDate, weekStart: Int): Long {
        val delta = Math.floorMod(date.dayOfWeek.value - weekStart, 7)
        return date.minusDays(delta.toLong()).toEpochDay()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.asIntOrNull()

    /**
     * 布尔字段。**全用 `as? JsonPrimitive` 取值**：导入文件是不可信输入，
     * 一个 `"isCustomTime": {}` 不该让整份本来能导的文件失败（`jsonPrimitive` 会抛）。
     * 上游有写 `"true"` 字符串的，一并认。
     */
    private fun JsonObject.bool(key: String): Boolean {
        val primitive = this[key] as? JsonPrimitive ?: return false
        primitive.booleanOrNull?.let { return it }
        return primitive.contentOrNull?.trim().equals("true", ignoreCase = true)
    }

    private fun kotlinx.serialization.json.JsonElement.asIntOrNull(): Int? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.trim()?.toIntOrNull()
    }
}
