package com.nullclass.feature.settings.transfer

import com.nullclass.core.model.CourseWithBlocks
import com.nullclass.core.model.ExamWithCourse
import com.nullclass.core.model.ExamFormat
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.ScheduleBlock
import com.nullclass.core.model.Term
import com.nullclass.core.model.WeekType
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** `.ics` 导出的结果；一个事件对应课程的一条时间安排。 */
internal data class IcsExportResult(
    val content: String,
    val courseEventCount: Int,
    val examEventCount: Int,
    val skippedBlockCount: Int,
) {
    val eventCount: Int get() = courseEventCount + examEventCount
}

/**
 * 把当前学期的周期性课程转换成 iCalendar 2.0。
 *
 * 每条时间安排导出成一个带 RRULE 的 VEVENT：普通每周课用 interval=1，单双周课用
 * interval=2。缺少节次起止时间的安排无法生成有意义的日历事件，会被统计为跳过，避免
 * 静默写出错误的午夜课程。
 */
internal object IcsCalendar {

    private val utcFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)

    fun build(
        text: IcsText,
        term: Term,
        schedule: List<CourseWithBlocks>,
        periodTimes: List<PeriodTime>,
        exams: List<ExamWithCourse> = emptyList(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        stamp: Instant = Instant.now(),
    ): IcsExportResult {
        val timesByPeriod = periodTimes.associateBy { it.periodIndex }
        val writer = IcsWriter()
        writer.raw("BEGIN:VCALENDAR")
        writer.raw("VERSION:2.0")
        writer.raw("PRODID:-//NullClass//Schedule//ZH-CN")
        writer.raw("CALSCALE:GREGORIAN")
        writer.raw("METHOD:PUBLISH")
        writer.textProperty("X-WR-CALNAME", text.calendarName.format(term.name))

        var courseEventCount = 0
        var skippedBlockCount = 0
        val stampText = utcFormatter.format(stamp)

        schedule
            .sortedWith(compareBy({ it.course.name }, { it.course.id }))
            .forEach { courseWithBlocks ->
                courseWithBlocks.blocks
                    .sortedWith(
                        compareBy(
                            { it.startWeek },
                            { it.endWeek },
                            { it.dayOfWeek },
                            { it.startPeriod },
                            { it.endPeriod },
                            { it.id },
                        ),
                    )
                    .forEachIndexed { blockIndex, block ->
                        val recurrence = recurrenceFor(block)
                        val startTime = timesByPeriod[block.startPeriod]
                        val endTime = timesByPeriod[block.endPeriod]
                        if (recurrence.count == 0 || startTime == null || endTime == null) {
                            skippedBlockCount++
                            return@forEachIndexed
                        }

                        val firstDate = LocalDate.ofEpochDay(
                            term.epochDayOf(recurrence.firstWeek, block.dayOfWeek),
                        )
                        val start = formatUtc(firstDate, startTime.startMinuteOfDay, zoneId)
                        val end = formatUtc(firstDate, endTime.endMinuteOfDay, zoneId)
                        val uid = stableUid(term, courseWithBlocks, block, blockIndex)

                        writer.raw("BEGIN:VEVENT")
                        writer.raw("UID:$uid")
                        writer.raw("DTSTAMP:$stampText")
                        writer.raw("DTSTART:$start")
                        writer.raw("DTEND:$end")
                        writer.textProperty("SUMMARY", courseWithBlocks.course.name)
                        writer.textProperty(
                            "DESCRIPTION",
                            description(
                                text,
                                term = term,
                                block = block,
                                teacher = courseWithBlocks.course.teacher?.takeIf { it.isNotBlank() },
                                note = courseWithBlocks.course.note?.takeIf { it.isNotBlank() },
                            ),
                        )
                        block.location
                            ?.takeIf { it.isNotBlank() }
                            ?.let { writer.textProperty("LOCATION", it) }
                        writer.raw(
                            "RRULE:FREQ=WEEKLY;" +
                                (if (recurrence.interval == 1) "" else "INTERVAL=${recurrence.interval};") +
                                "COUNT=${recurrence.count}",
                        )
                        writer.raw("STATUS:CONFIRMED")
                        writer.raw("TRANSP:OPAQUE")
                        writer.raw("END:VEVENT")
                        courseEventCount++
                    }
            }

        var examEventCount = 0
        exams
            .sortedWith(compareBy({ it.exam.dateEpochDay }, { it.exam.startMinuteOfDay ?: Int.MAX_VALUE }, { it.exam.id }))
            .forEach { item ->
                val exam = item.exam
                val date = LocalDate.ofEpochDay(exam.dateEpochDay)
                val uid = stableUid(term, item)
                val startMinute = exam.startMinuteOfDay
                val endMinute = exam.endMinuteOfDay

                writer.raw("BEGIN:VEVENT")
                writer.raw("UID:$uid")
                writer.raw("DTSTAMP:$stampText")
                if (startMinute != null && endMinute != null) {
                    writer.raw("DTSTART:${formatUtc(date, startMinute, zoneId)}")
                    writer.raw("DTEND:${formatUtc(date, endMinute, zoneId)}")
                } else {
                    // 时间未知时导出为全天事件，避免伪造一个午夜考试。
                    writer.raw("DTSTART;VALUE=DATE:${basicDateFormatter.format(date)}")
                    writer.raw("DTEND;VALUE=DATE:${basicDateFormatter.format(date.plusDays(1))}")
                }
                writer.textProperty("SUMMARY", "${item.course.name} · ${exam.title}")
                writer.textProperty("DESCRIPTION", examDescription(text, term, item))
                exam.location
                    ?.takeIf { it.isNotBlank() }
                    ?.let { writer.textProperty("LOCATION", it) }
                writer.raw("STATUS:CONFIRMED")
                writer.raw("TRANSP:OPAQUE")
                writer.raw("END:VEVENT")
                examEventCount++
            }

        writer.raw("END:VCALENDAR")
        return IcsExportResult(
            content = writer.toString(),
            courseEventCount = courseEventCount,
            examEventCount = examEventCount,
            skippedBlockCount = skippedBlockCount,
        )
    }

    private fun description(
        text: IcsText,
        term: Term,
        block: ScheduleBlock,
        teacher: String?,
        note: String?,
    ): String = buildLines {
        add(text.term.format(term.name))
        teacher?.let { add(text.teacher.format(it)) }
        note?.let { add(text.note.format(it)) }
        add(text.blockSummary(block))
    }

    private fun examDescription(
        text: IcsText,
        term: Term,
        item: ExamWithCourse,
    ): String = buildLines {
        add(text.term.format(term.name))
        add(text.course.format(item.course.name))
        add(text.exam.format(item.exam.title))
        ExamFormat.timeRange(item.exam)?.let { add(text.time.format(it)) }
        item.exam.location?.takeIf { it.isNotBlank() }?.let { add(text.location.format(it)) }
        item.exam.seat?.takeIf { it.isNotBlank() }?.let { add(text.seat.format(it)) }
        item.exam.note?.takeIf { it.isNotBlank() }?.let { add(text.note.format(it)) }
    }

    /** 逐行拼 DESCRIPTION：各行本身已是完整词条，这里只负责换行。 */
    private inline fun buildLines(build: MutableList<String>.() -> Unit): String =
        buildList(build).joinToString(LINE_BREAK)

    private const val LINE_BREAK = "\n"

    private fun recurrenceFor(block: ScheduleBlock): Recurrence {
        if (block.weekType == WeekType.ALL) {
            return Recurrence(
                firstWeek = block.startWeek,
                count = block.endWeek - block.startWeek + 1,
                interval = 1,
            )
        }
        val wantedParity = if (block.weekType == WeekType.ODD) 1 else 0
        val firstWeek = if (block.startWeek % 2 == wantedParity) {
            block.startWeek
        } else {
            block.startWeek + 1
        }
        return if (firstWeek > block.endWeek) {
            Recurrence(firstWeek = firstWeek, count = 0, interval = 2)
        } else {
            Recurrence(
                firstWeek = firstWeek,
                count = (block.endWeek - firstWeek) / 2 + 1,
                interval = 2,
            )
        }
    }

    private fun formatUtc(date: LocalDate, minuteOfDay: Int, zoneId: ZoneId): String =
        utcFormatter.format(
            date.atStartOfDay()
                .plusMinutes(minuteOfDay.toLong())
                .atZone(zoneId)
                .toInstant(),
        )

    /** 课块 UUID 通常已稳定；无 UUID 的测试/旧数据也用内容生成稳定 UID，便于重复导出去重。 */
    private fun stableUid(
        term: Term,
        courseWithBlocks: CourseWithBlocks,
        block: ScheduleBlock,
        blockIndex: Int,
    ): String {
        val seed = listOf(
            term.id,
            courseWithBlocks.course.id,
            block.id,
            blockIndex.toString(),
            block.startWeek.toString(),
            block.endWeek.toString(),
            block.weekType.name,
            block.dayOfWeek.toString(),
            block.startPeriod.toString(),
            block.endPeriod.toString(),
            block.location.orEmpty(),
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "${hex.take(24)}@nullclass"
    }

    private fun stableUid(term: Term, item: ExamWithCourse): String {
        val exam = item.exam
        val seed = listOf(
            term.id,
            item.course.id,
            exam.id,
            exam.title,
            exam.dateEpochDay.toString(),
            exam.startMinuteOfDay?.toString().orEmpty(),
            exam.endMinuteOfDay?.toString().orEmpty(),
            exam.location.orEmpty(),
            exam.seat.orEmpty(),
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "${hex.take(24)}@nullclass"
    }

    private data class Recurrence(
        val firstWeek: Int,
        val count: Int,
        val interval: Int,
    )

    private val basicDateFormatter = DateTimeFormatter.BASIC_ISO_DATE

    private class IcsWriter {
        private val output = StringBuilder()

        fun raw(line: String) {
            foldLine(line).forEach {
                output.append(it).append("\r\n")
            }
        }

        fun textProperty(name: String, value: String) {
            raw("$name:${escapeText(value)}")
        }

        override fun toString(): String = output.toString()
    }

    /** RFC 5545 的 75 字节折行；按 Unicode code point 切，避免拆开中文或 emoji 的 UTF-8。 */
    private fun foldLine(line: String): List<String> {
        if (line.toByteArray(Charsets.UTF_8).size <= 75) return listOf(line)

        val result = mutableListOf<String>()
        var firstLine = true
        var offset = 0
        var segment = StringBuilder()
        var segmentBytes = 0
        while (offset < line.length) {
            val codePoint = line.codePointAt(offset)
            val text = String(Character.toChars(codePoint))
            val textBytes = text.toByteArray(Charsets.UTF_8).size
            val limit = if (firstLine) 75 else 74
            if (segment.isNotEmpty() && segmentBytes + textBytes > limit) {
                result += if (firstLine) segment.toString() else " $segment"
                firstLine = false
                segment = StringBuilder()
                segmentBytes = 0
            }
            segment.append(text)
            segmentBytes += textBytes
            offset += Character.charCount(codePoint)
        }
        if (segment.isNotEmpty()) {
            result += if (firstLine) segment.toString() else " $segment"
        }
        return result
    }

    private fun escapeText(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\r', '\n' -> append("\\n")
                else -> append(char)
            }
        }
    }
}
