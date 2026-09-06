package com.nullclass.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.nullclass.core.data.db.dao.CourseDao
import com.nullclass.core.data.db.dao.TermDao
import com.nullclass.core.data.db.entity.CourseEntity
import com.nullclass.core.data.db.entity.PeriodTimeEntity
import com.nullclass.core.data.db.entity.ScheduleBlockEntity
import com.nullclass.core.data.db.entity.TermEntity

@Database(
    entities = [
        TermEntity::class,
        CourseEntity::class,
        ScheduleBlockEntity::class,
        PeriodTimeEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NullClassDatabase : RoomDatabase() {
    abstract fun termDao(): TermDao
    abstract fun courseDao(): CourseDao

    companion object {
        const val NAME = "nullclass.db"
    }
}
