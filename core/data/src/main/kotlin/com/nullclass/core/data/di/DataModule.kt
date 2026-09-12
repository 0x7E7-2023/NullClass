package com.nullclass.core.data.di

import android.content.Context
import androidx.room.Room
import com.nullclass.core.data.db.NullClassDatabase
import com.nullclass.core.data.db.MIGRATION_2_3
import com.nullclass.core.data.db.dao.CourseDao
import com.nullclass.core.data.db.dao.PeriodTimeDao
import com.nullclass.core.data.db.dao.SyncStateDao
import com.nullclass.core.data.db.dao.TermDao
import com.nullclass.core.data.db.dao.TimetableDao
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.CourseRepositoryImpl
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.data.repository.TermRepositoryImpl
import com.nullclass.core.data.repository.TimetableRepository
import com.nullclass.core.data.repository.TimetableRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import androidx.room.RoomDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NullClassDatabase =
        Room.databaseBuilder(context, NullClassDatabase::class.java, NullClassDatabase.NAME)
            // 本应用写入频率低（编辑课表），不需要 WAL 的并发读写；rollback journal 在部分
            // 模拟器（LDPlayer）上更稳：WAL 的跨连接失效通知与未 checkpoint 数据在这些环境不可靠
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            // v2 已发布上线：漏写迁移必须当场崩，绝不能静默清空用户的课表
            // （v1→v2 的破坏性迁移只服务过没有存量的开发期，随 v3 一并移除）
            .addMigrations(MIGRATION_2_3)
            .build()

    @Provides
    fun provideTimetableDao(db: NullClassDatabase): TimetableDao = db.timetableDao()

    @Provides
    fun provideTermDao(db: NullClassDatabase): TermDao = db.termDao()

    @Provides
    fun provideCourseDao(db: NullClassDatabase): CourseDao = db.courseDao()

    @Provides
    fun providePeriodTimeDao(db: NullClassDatabase): PeriodTimeDao = db.periodTimeDao()

    @Provides
    fun provideSyncStateDao(db: NullClassDatabase): SyncStateDao = db.syncStateDao()
}

@Module
@InstallIn(SingletonComponent::class)
internal interface RepositoryModule {

    @Binds
    fun bindCourseRepository(impl: CourseRepositoryImpl): CourseRepository

    @Binds
    fun bindTermRepository(impl: TermRepositoryImpl): TermRepository

    @Binds
    fun bindTimetableRepository(impl: TimetableRepositoryImpl): TimetableRepository
}
