package com.nullclass.core.data.repository

import com.nullclass.core.data.db.entity.CourseEntity
import com.nullclass.core.data.db.entity.CourseWithBlocksEntity
import com.nullclass.core.data.db.entity.PeriodTimeEntity
import com.nullclass.core.data.db.entity.ScheduleBlockEntity
import com.nullclass.core.data.db.entity.TermEntity
import com.nullclass.core.data.db.entity.TimetableEntity
import com.nullclass.core.model.Course
import com.nullclass.core.model.CourseWithBlocks
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.ScheduleBlock
import com.nullclass.core.model.Term
import com.nullclass.core.model.Timetable
import com.nullclass.core.model.WeekType

/** Entity ↔ 领域模型映射。注意：@Relation 不带墓碑过滤，映射层统一处理。 */

internal fun TimetableEntity.toModel() = Timetable(
    id = id,
    name = name,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun TermEntity.toModel() = Term(
    id = id,
    name = name,
    firstDayEpochDay = firstDayEpochDay,
    totalWeeks = totalWeeks,
)

internal fun Term.toEntity(timetableId: String, createdAt: Long, updatedAt: Long, isCurrent: Boolean = false) = TermEntity(
    id = id,
    timetableId = timetableId,
    name = name,
    firstDayEpochDay = firstDayEpochDay,
    totalWeeks = totalWeeks,
    isCurrent = isCurrent,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun CourseEntity.toModel() = Course(
    id = id,
    termId = termId,
    name = name,
    teacher = teacher,
    note = note,
    colorIndex = colorIndex,
)

internal fun Course.toEntity(createdAt: Long, updatedAt: Long) = CourseEntity(
    id = id,
    termId = termId,
    name = name,
    teacher = teacher,
    note = note,
    colorIndex = colorIndex,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun ScheduleBlockEntity.toModel() = ScheduleBlock(
    id = id,
    courseId = courseId,
    startWeek = startWeek,
    endWeek = endWeek,
    weekType = runCatching { WeekType.valueOf(weekType) }.getOrDefault(WeekType.ALL),
    dayOfWeek = dayOfWeek,
    startPeriod = startPeriod,
    endPeriod = endPeriod,
    location = location,
)

internal fun ScheduleBlock.toEntity(termId: String, createdAt: Long, updatedAt: Long) = ScheduleBlockEntity(
    id = id,
    courseId = courseId,
    termId = termId,
    startWeek = startWeek,
    endWeek = endWeek,
    weekType = weekType.name,
    dayOfWeek = dayOfWeek,
    startPeriod = startPeriod,
    endPeriod = endPeriod,
    location = location,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun PeriodTimeEntity.toModel() = PeriodTime(
    termId = termId,
    periodIndex = periodIndex,
    startMinuteOfDay = startMinuteOfDay,
    endMinuteOfDay = endMinuteOfDay,
    session = session,
)

internal fun PeriodTime.toEntity(updatedAt: Long) = PeriodTimeEntity(
    termId = termId,
    periodIndex = periodIndex,
    startMinuteOfDay = startMinuteOfDay,
    endMinuteOfDay = endMinuteOfDay,
    session = session,
    updatedAt = updatedAt,
)

/** 墓碑过滤在这里做（Room @Relation 不支持条件）。 */
internal fun CourseWithBlocksEntity.toModel() = CourseWithBlocks(
    course = course.toModel(),
    blocks = blocks.filter { it.deletedAt == null }.map { it.toModel() },
)
