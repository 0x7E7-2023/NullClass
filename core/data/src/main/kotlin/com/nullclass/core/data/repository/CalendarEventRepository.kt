package com.nullclass.core.data.repository

import com.nullclass.core.data.db.dao.CalendarEventDao
import com.nullclass.core.data.db.entity.CalendarEventEntity
import com.nullclass.core.model.CalendarEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 日程安排的唯一读写入口（纯本地）。 */
@Singleton
class CalendarEventRepository @Inject constructor(
    private val dao: CalendarEventDao,
) {

    /** 全部日程：按日期升序，同日全天在前、再按开始时间。 */
    val events: Flow<List<CalendarEvent>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** 一次性读 [fromEpochDay] 起的日程（提醒排算用）。 */
    suspend fun getFrom(fromEpochDay: Long): List<CalendarEvent> =
        dao.getFrom(fromEpochDay).map { it.toDomain() }

    /** 新增或更新，返回 id。 */
    suspend fun upsert(event: CalendarEvent): String {
        val id = event.id.ifEmpty { UUID.randomUUID().toString() }
        dao.upsert(
            CalendarEventEntity(
                id = id,
                title = event.title,
                dateEpochDay = event.dateEpochDay,
                startMinuteOfDay = event.startMinuteOfDay,
                note = event.note,
                remindLeadMinutes = event.remindLeadMinutes,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    suspend fun delete(id: String) = dao.delete(id)

    private fun CalendarEventEntity.toDomain() = CalendarEvent(
        id = id,
        title = title,
        dateEpochDay = dateEpochDay,
        startMinuteOfDay = startMinuteOfDay,
        note = note,
        remindLeadMinutes = remindLeadMinutes,
    )
}
