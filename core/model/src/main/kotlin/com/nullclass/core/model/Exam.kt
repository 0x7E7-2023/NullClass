package com.nullclass.core.model

/**
 * 一门课程的一次考试。
 *
 * 考试不是独立课程：它通过 [courseId] 归属于一门具体课程，因此课程改名、换色后
 * 考试列表会自动使用最新的课程信息。同一门课允许有多场考试（期中、期末、补考等）。
 */
data class Exam(
    val id: String = "",
    val courseId: String = "",
    /** 例：期中考试、期末考试、补考；保留为文本以支持学校自定义名称。 */
    val title: String = "期末考试",
    /** 考试日期（LocalDate.toEpochDay()）。 */
    val dateEpochDay: Long = 0L,
    /** 允许暂时只有日期，没有具体时刻。 */
    val startMinuteOfDay: Int? = null,
    val endMinuteOfDay: Int? = null,
    val location: String? = null,
    val seat: String? = null,
    val note: String? = null,
)

/** 考试及其所属课程，供列表和课程详情直接展示。 */
data class ExamWithCourse(
    val exam: Exam,
    val course: Course,
)
