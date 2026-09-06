package com.nullclass.core.data.di

import android.content.Context
import androidx.room.Room
import com.nullclass.core.data.db.NullClassDatabase
import com.nullclass.core.data.db.dao.CourseDao
import com.nullclass.core.data.db.dao.PeriodTimeDao
import com.nullclass.core.data.db.dao.SyncStateDao
import com.nullclass.core.data.db.dao.TermDao
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.CourseRepositoryImpl
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.data.repository.TermRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
            // v1→v2 无存量用户的破坏性迁移；v2 起必须改用显式 Migration（见 NullClassDatabase 注释）
            .fallbackToDestructiveMigration()
            .build()

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
}
