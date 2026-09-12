package com.nullclass.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.nullclass.core.data.db.dao.CourseDao
import com.nullclass.core.data.db.dao.PeriodTimeDao
import com.nullclass.core.data.db.dao.SyncStateDao
import com.nullclass.core.data.db.dao.TermDao
import com.nullclass.core.data.db.dao.TimetableDao
import com.nullclass.core.data.db.entity.CourseEntity
import com.nullclass.core.data.db.entity.PeriodTimeEntity
import com.nullclass.core.data.db.entity.ScheduleBlockEntity
import com.nullclass.core.data.db.entity.SyncStateEntity
import com.nullclass.core.data.db.entity.TermEntity
import com.nullclass.core.data.db.entity.TimetableEntity

/**
 * schema 历史：
 *  v1 — 首版（Long 自增 id，无审计列）
 *  v2 — UUID 主键 + 审计三列 + 软删除墓碑 + 冗余列/索引 + period_times.session（同步就绪）
 *  v3 — timetables 表（课表成为顶层容器）+ terms.timetableId；存量学期归入「我的课表」
 * v1→v2 无存量用户走破坏性迁移；v2 起任何改表必须提供 Migration + 测试（v3 的迁移见 [MIGRATION_2_3]）。
 */
@Database(
    entities = [
        TimetableEntity::class,
        TermEntity::class,
        CourseEntity::class,
        ScheduleBlockEntity::class,
        PeriodTimeEntity::class,
        SyncStateEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class NullClassDatabase : RoomDatabase() {
    abstract fun timetableDao(): TimetableDao
    abstract fun termDao(): TermDao
    abstract fun courseDao(): CourseDao
    abstract fun periodTimeDao(): PeriodTimeDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        const val NAME = "nullclass.db"
    }
}
