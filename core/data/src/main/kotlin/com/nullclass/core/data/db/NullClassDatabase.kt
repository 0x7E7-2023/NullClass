package com.nullclass.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.nullclass.core.data.db.dao.CourseDao
import com.nullclass.core.data.db.dao.PeriodTimeDao
import com.nullclass.core.data.db.dao.SyncStateDao
import com.nullclass.core.data.db.dao.TermDao
import com.nullclass.core.data.db.entity.CourseEntity
import com.nullclass.core.data.db.entity.PeriodTimeEntity
import com.nullclass.core.data.db.entity.ScheduleBlockEntity
import com.nullclass.core.data.db.entity.SyncStateEntity
import com.nullclass.core.data.db.entity.TermEntity

/**
 * schema 历史：
 *  v1 — 首版（Long 自增 id，无审计列）
 *  v2 — UUID 主键 + 审计三列 + 软删除墓碑 + 冗余列/索引 + period_times.session（同步就绪）
 * v1→v2 无存量用户走破坏性迁移；v2 起任何改表必须提供 Migration + 测试。
 */
@Database(
    entities = [
        TermEntity::class,
        CourseEntity::class,
        ScheduleBlockEntity::class,
        PeriodTimeEntity::class,
        SyncStateEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class NullClassDatabase : RoomDatabase() {
    abstract fun termDao(): TermDao
    abstract fun courseDao(): CourseDao
    abstract fun periodTimeDao(): PeriodTimeDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        const val NAME = "nullclass.db"
    }
}
